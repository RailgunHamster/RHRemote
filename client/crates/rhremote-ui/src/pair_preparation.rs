//! A pairing attempt owns its preparation worker and child, never the stream slot.
use super::*;
use std::thread::JoinHandle;

pub(super) struct PendingPair {
    pub attempt:u64,
    pub device:Device,
    cancel:Arc<AtomicBool>,
    stage:Stage,
}
enum Stage {
    Preparing(JoinHandle<Result<OwnedPair,String>>),
    Running(OwnedPair),
    Empty,
}
struct OwnedPair {
    retired:bool,
    child:Option<Child>,
    cancellation:Option<crate::pairing::Cancellation>,
    cleanup:crate::native_cleanup::Queue,
    network:Arc<crate::network::Network>,
}
impl OwnedPair {
    fn finish(&mut self,cancelled:bool) {
        if std::mem::replace(&mut self.retired,true) {return;}
        if let Some(mut child)=self.child.take() {
            if child.try_wait().ok().flatten().is_none() {let _=child.kill();}
            let _=child.wait();
        }
        let job=self.cancellation.take().and_then(|cancellation|cancellation.finish(cancelled));
        if cancelled {
            // Signed cancellation needs the old tunnel. Release it only after
            // that reply, and keep retry admission closed throughout retirement.
            let network=self.network.clone();
            let job=std::thread::spawn(move||{
                let confirmed=job.is_none_or(|job|job.join().unwrap_or(false));
                network.release_transport();
                confirmed
            });
            self.cleanup.track_pairing(job,self.network.clone());
        }
    }
}
impl Drop for OwnedPair {fn drop(&mut self){self.finish(true);}}

impl PendingPair {
    pub fn start(context:LaunchContext,admission:Arc<crate::device_probe::Admission>,attempt:u64,device:Device,profile:rhremote_core::Profile,pin:String,regression:bool)->Result<Self,String>{
        let cancel=Arc::new(AtomicBool::new(false));
        let worker_cancel=cancel.clone();let target=device.clone();
        let job=std::thread::Builder::new().name("pair-preparation".into()).spawn(move||{
            let _lease=admission.foreground_cancelable(&worker_cancel).ok_or("配对已取消")?;
            let mut owned=OwnedPair{retired:false,child:None,cancellation:None,cleanup:context.cleanup.clone(),network:context.network.clone()};
            let (address,_)=context.network.resolve_excluding(&target,&profile,&[],&worker_cancel)?;
            if worker_cancel.load(Ordering::SeqCst) {return Err("配对已取消".into());}
            let cancellation=crate::pairing::Cancellation::new(&context.native_data,&address,context.network.native_environment().0)?;
            owned.cancellation=Some(cancellation);
            fs::write(&context.session_status,b"{\"streaming\":false,\"phase\":\"pair_preparing\",\"error\":\"\"}").map_err(|e|e.to_string())?;
            let cancellation=owned.cancellation.as_ref().unwrap();
            if worker_cancel.load(Ordering::SeqCst) {return Err("配对已取消".into());}
            owned.child=Some(context.command()
                .env("RHREMOTE_PAIR_OPERATION","1")
                .env("RHREMOTE_TARGET_UUID",&target.uuid)
                .env("RHREMOTE_CONNECT_ADDRESS",&address)
                .env("RHREMOTE_TEST_PAIR_PENDING",if regression {"1"}else{"0"})
                .env("RHREMOTE_PAIR_ID",cancellation.id())
                .env("RHREMOTE_PAIR_CANCEL_FILE",cancellation.path())
                .env("RHREMOTE_SESSION_STATUS",&context.session_status)
                .args(["pair",&address,"--pin",&pin]).spawn().map_err(|e|e.to_string())?);
            if worker_cancel.load(Ordering::SeqCst) {return Err("配对已取消".into());}
            Ok(owned)
        }).map_err(|e|e.to_string())?;
        Ok(Self{attempt,device,cancel,stage:Stage::Preparing(job)})
    }
    pub fn phase(&self)->&'static str {
        match &self.stage {
            Stage::Running(owned) if owned.cancellation.as_ref().is_some_and(|c|c.protocol_started())=>"pairing",
            Stage::Running(_)=>"contacting",
            _=>"preparing",
        }
    }
    pub fn cancel(&self){self.cancel.store(true,Ordering::SeqCst);}
    pub fn cancellation_id(&self)->Option<String>{
        if let Stage::Running(owned)=&self.stage {owned.cancellation.as_ref().map(|c|c.id().to_owned())}else{None}
    }
    /// Retirement is tracked before the caller releases the attempt admission lock.
    pub fn retire(mut self,cleanup:&crate::native_cleanup::Queue,network:Arc<crate::network::Network>)->bool {
        self.cancel();
        cleanup.coordinate(network,move||{
            match std::mem::replace(&mut self.stage,Stage::Empty) {
                Stage::Preparing(job)=>drop(job.join()),
                stage=>drop(stage),
            }
        })
    }
    pub fn poll(&mut self,status_path:&Path)->Option<Result<(),String>> {
        if matches!(&self.stage,Stage::Preparing(job) if !job.is_finished()) {return None;}
        if matches!(&self.stage,Stage::Preparing(_)) {
            let Stage::Preparing(job)=std::mem::replace(&mut self.stage,Stage::Empty) else {unreachable!()};
            match job.join() {
                Ok(Ok(owned))=>self.stage=Stage::Running(owned),
                Ok(Err(error))=>return Some(Err(error)),
                Err(_)=>return Some(Err("配对准备任务异常结束".into())),
            }
        }
        let Stage::Running(owned)=&mut self.stage else {return None;};
        let exit=match owned.child.as_mut().unwrap().try_wait(){Ok(Some(exit))=>exit,Ok(None)=>return None,Err(_)=>return Some(Err("无法读取配对进程状态".into()))};
        let outcome:Value=fs::read(status_path).ok().and_then(|b|serde_json::from_slice(&b).ok()).unwrap_or(Value::Null);
        let paired=outcome["phase"]=="paired"&&exit.success();
        owned.finish(!paired);
        Some(if paired {Ok(())} else if let Some(message)=outcome["error"].as_str().filter(|s|!s.is_empty()) {
            Err(message.chars().take(512).collect())
        } else if exit.success() {Err("主机尚未确认配对，请在目标电脑的 Sunshine 提交当前配对码后重试".into())}
        else {Err(format!("配对进程退出（{}），请检查目标主机是否在线",exit.code().unwrap_or(-1)))})
    }
}
impl Drop for PendingPair {fn drop(&mut self){self.cancel();}}

#[cfg(test)]
mod tests {
    use super::*;
    fn drain(queue:&crate::native_cleanup::Queue){
        let deadline=Instant::now()+Duration::from_secs(3);
        while queue.pending()&&Instant::now()<deadline {queue.reap();std::thread::sleep(Duration::from_millis(5));}
        assert!(!queue.pending());assert!(queue.failure().is_none());
    }
    #[test]
    fn cancelled_preparation_holds_retry_admission_until_its_exact_worker_retires(){
        let cleanup=crate::native_cleanup::Queue::default();let network=Arc::new(crate::network::Network::default());
        let cancel=Arc::new(AtomicBool::new(false));let (release,released)=std::sync::mpsc::channel();
        let job={let cleanup=cleanup.clone();let network=network.clone();std::thread::spawn(move||{
            released.recv_timeout(Duration::from_secs(3)).unwrap();
            Ok(OwnedPair{retired:false,child:None,cancellation:None,cleanup,network})
        })};
        let device:Device=serde_json::from_value(json!({"uuid":"test","name":"PC","address":"192.0.2.1","online":true,"paired":false})).unwrap();
        let pending=PendingPair{attempt:1,device,cancel:cancel.clone(),stage:Stage::Preparing(job)};
        assert!(pending.retire(&cleanup,network));assert!(cancel.load(Ordering::SeqCst));
        assert!(cleanup.pending(),"retry reopened before the old preparation worker released its resources");
        release.send(()).unwrap();drain(&cleanup);
    }
    #[cfg(windows)]
    #[test]
    fn completed_pair_exchange_needs_no_extra_probe_and_does_not_cancel_success(){
        let root=std::env::temp_dir().join(format!("rhremote-pair-outcome-{}",rhremote_core::random_token().unwrap()));fs::create_dir(&root).unwrap();
        let status=root.join("status.json");fs::write(&status,b"{\"phase\":\"paired\"}").unwrap();
        let child=Command::new("cmd.exe").args(["/d","/c","exit","0"]).creation_flags(0x08000000).spawn().unwrap();
        let cleanup=crate::native_cleanup::Queue::default();let network=Arc::new(crate::network::Network::default());
        let cancellation=crate::pairing::Cancellation::new(&root,"192.0.2.1",0).unwrap();let proof=cancellation.path().to_owned();let id=cancellation.id().to_owned();
        let owned=OwnedPair{retired:false,child:Some(child),cancellation:Some(cancellation),cleanup:cleanup.clone(),network};
        let device:Device=serde_json::from_value(json!({"uuid":"test","name":"PC","address":"192.0.2.1","online":true,"paired":false})).unwrap();
        let mut pending=PendingPair{attempt:1,device,cancel:Arc::new(AtomicBool::new(false)),stage:Stage::Running(owned)};
        assert_eq!(pending.cancellation_id(),Some(id));
        let deadline=Instant::now()+Duration::from_secs(3);
        let result=loop {if let Some(result)=pending.poll(&status){break result;}assert!(Instant::now()<deadline);std::thread::sleep(Duration::from_millis(5));};
        assert_eq!(result,Ok(()));drop(pending);assert!(!proof.exists());assert!(!cleanup.pending(),"successful pairing queued a cancellation");
        fs::remove_dir_all(root).unwrap();
    }
}
