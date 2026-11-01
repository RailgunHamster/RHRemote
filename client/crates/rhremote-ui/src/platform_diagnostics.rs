//! A private read-only native child and its tunnel lease belong to this task.
use super::*;
use rhremote_core::DiagnosticTask;

pub(super) struct Task {
    context:LaunchContext,device:Device,profile:rhremote_core::Profile,
    admission:Arc<crate::device_probe::Admission>,lease:Option<crate::device_probe::OwnedForeground>,
    stream:Arc<Mutex<Option<Child>>>,pairing:Arc<Mutex<Option<PendingPair>>>,recovery:Arc<Mutex<Option<Recovery>>>,
    retiring:Arc<Mutex<Vec<(Child,Instant,bool)>>>,launches:Arc<Mutex<Vec<std::thread::JoinHandle<Result<LaunchOutcome,String>>>>>,
    child:Option<Child>,files:Vec<PathBuf>,request:PathBuf,response:PathBuf,probes:Vec<std::thread::JoinHandle<()>>,
    window_list:bool,
}
impl Task {
    pub(super) fn new(native:&NativePlatform,device:&Device,profile:&rhremote_core::Profile)->Result<Self,String>{
        if native.closing.load(Ordering::SeqCst)||native.stop_requested.load(Ordering::SeqCst){return Err("上一连接正在结束".into());}
        let id=rhremote_core::random_token()?;let context=native.launcher();
        Ok(Self{request:context.native_data.join(format!("network-{id}-request.json")),response:context.native_data.join(format!("network-{id}-response.json")),context,device:device.clone(),profile:profile.clone(),admission:native.probe_admission.clone(),lease:None,stream:native.child.clone(),pairing:native.pairing.clone(),recovery:native.recovery.clone(),retiring:native.retiring.clone(),launches:native.retired_launches.clone(),child:None,files:vec![],probes:vec![],window_list:false})
    }
    pub(super) fn windows(native:&NativePlatform,device:&Device,profile:&rhremote_core::Profile)->Result<Self,String>{let mut task=Self::new(native,device,profile)?;task.window_list=true;Ok(task)}
    fn checkpoint(cancel:&AtomicBool)->Result<(),String>{if cancel.load(Ordering::SeqCst){Err("已取消诊断".into())}else{Ok(())}}
    fn host(&mut self,address:&str,cancel:&AtomicBool)->Result<Value,String>{
        use std::io::{Read,Write};Self::checkpoint(cancel)?;
        let request=if self.window_list{&b"{\"path\":\"/rhremote/windows\"}"[..]}else{&b"{\"path\":\"/v1/network/diagnose\"}"[..]};
        for(path,body)in[(&self.request,request),(&self.response,&b"{}"[..])]{
            let mut file=fs::OpenOptions::new().write(true).create_new(true).open(path).map_err(|e|e.to_string())?;self.files.push(path.clone());file.write_all(body).map_err(|e|e.to_string())?;
        }
        Self::checkpoint(cancel)?;
        self.child=Some(self.context.command().args(["list",address]).env("RHREMOTE_TARGET_UUID",&self.device.uuid).env("RHREMOTE_CONNECT_ADDRESS",address).env("RHREMOTE_HOST_REQUEST",&self.request).env("RHREMOTE_HOST_RESPONSE",&self.response).spawn().map_err(|e|e.to_string())?);
        let until=Instant::now()+Duration::from_secs(12);
        loop{
            Self::checkpoint(cancel)?;
            if self.child.as_mut().unwrap().try_wait().map_err(|e|e.to_string())?.is_some(){break;}
            if Instant::now()>=until{return Err("主机网络诊断超时；检查配对、47995 与主机服务版本".into());}
            std::thread::sleep(Duration::from_millis(25));
        }
        let mut bytes=Vec::new();fs::File::open(&self.response).map_err(|e|e.to_string())?.take(262145).read_to_end(&mut bytes).map_err(|e|e.to_string())?;
        if bytes.len()>262144{return Err("主机诊断响应过大".into());}
        Self::checkpoint(cancel)?;let reply:Value=serde_json::from_slice(&bytes).map_err(|_|"主机诊断响应无效")?;
        if reply["ok"]==true{Ok(reply["result"].clone())}else{Err(reply["error"].as_str().unwrap_or("主机网络诊断不可用；检查服务版本与配对证书").into())}
    }
}
impl DiagnosticTask for Task {
    fn run(&mut self,cancel:Arc<AtomicBool>)->Result<Value,String>{
        Self::checkpoint(&cancel)?;
        self.lease=Some(self.admission.foreground_owned(&cancel).ok_or("已取消诊断")?);
        if self.context.cleanup.pending()||!self.launches.lock().unwrap().is_empty()||self.retiring.lock().unwrap().iter().any(|(_,_,released)|!released)||self.pairing.lock().unwrap().is_some()||self.recovery.lock().unwrap().is_some()||self.stream.lock().unwrap().as_mut().is_some_and(|child|child.try_wait().ok().flatten().is_none()){
            // No diagnostic has touched the shared tunnel yet; leave the current owner alone.
            self.lease=None;return Err("请先完成或断开当前连接，再运行网络诊断".into());
        }
        let mut result=self.context.network.diagnose_cancelable(&self.device,&self.profile,cancel.clone(),&mut self.probes)?;
        Self::checkpoint(&cancel)?;
        if let Some(address)=result["selectedAddress"].as_str().map(str::to_owned){
            if self.window_list{return self.host(&address,&cancel);}
            match self.host(&address,&cancel){Ok(host)=>result["host"]=host,Err(error)=>{Self::checkpoint(&cancel)?;result["hostError"]=json!(error);}}
        if self.window_list{return Err("无法连接主机以读取窗口列表".into());}
        }
        Self::checkpoint(&cancel)?;Ok(result)
    }
    fn cleanup(&mut self)->Result<(),String>{
        if let Some(child)=self.child.as_mut(){
            if child.try_wait().map_err(|e|e.to_string())?.is_none(){child.kill().map_err(|e|e.to_string())?;}
            child.wait().map_err(|e|e.to_string())?;self.child=None;
        }
        let mut failed=false;while let Some(worker)=self.probes.pop(){failed|=worker.join().is_err();}
        if failed{return Err("诊断请求线程异常退出".into());}
        if self.lease.is_some(){self.context.network.release_diagnostic()?;}
        while let Some(path)=self.files.last(){match fs::remove_file(path){Ok(())=>{},Err(error)if error.kind()==std::io::ErrorKind::NotFound=>{},Err(error)=>return Err(error.to_string())}self.files.pop();}
        self.lease=None;Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture()->(PathBuf,NativePlatform,Device){
        let root=std::env::temp_dir().join(format!("rhremote-diagnostic-native-{}",rhremote_core::random_token().unwrap()));fs::create_dir(&root).unwrap();
        // The nonexistent engine is inside the fixture, so no legacy identity is imported.
        let native=NativePlatform::new(root.join("never-spawn.exe"),"fixture-token".into(),root.clone()).unwrap();
        let device=serde_json::from_value(json!({"uuid":"fixture","name":"fixture","address":"192.0.2.1","online":false,"paired":false})).unwrap();(root,native,device)
    }
    fn remove_fixture(root:PathBuf){let resolved=root.canonicalize().unwrap();assert!(resolved.starts_with(std::env::temp_dir().canonicalize().unwrap()));assert!(root.file_name().unwrap().to_string_lossy().starts_with("rhremote-diagnostic-native-"));fs::remove_dir_all(resolved).unwrap();}
    #[test]fn cancellation_while_waiting_for_an_old_probe_does_not_stop_its_transport(){
        let(root,native,device)=fixture();let probe=native.probe_admission.probe().unwrap();let task=Task::new(&native,&device,&Default::default()).unwrap();let cancel=Arc::new(AtomicBool::new(false));
        let worker={let cancel=cancel.clone();std::thread::spawn(move||{let mut task=task;let result=task.run(cancel);assert!(result.is_err());task.cleanup().unwrap();assert!(task.child.is_none());assert!(task.lease.is_none());})};
        let until=Instant::now()+Duration::from_secs(2);while !probe.cancel.load(Ordering::SeqCst)&&Instant::now()<until{std::thread::sleep(Duration::from_millis(2));}assert!(probe.cancel.load(Ordering::SeqCst));cancel.store(true,Ordering::SeqCst);worker.join().unwrap();
        assert!(native.probe_admission.probe().is_none(),"Cancellation released the older probe's lease");drop(probe);assert!(native.probe_admission.probe().is_some());drop(native);remove_fixture(root);
    }
    #[test]fn a_recovery_between_launches_prevents_diagnostic_io_without_cancelling_the_recovery(){
        let(root,native,device)=fixture();*native.recovery.lock().unwrap()=Some(Recovery::new(json!({})));
        let mut task=Task::new(&native,&device,&Default::default()).unwrap();assert!(task.run(Arc::new(AtomicBool::new(false))).unwrap_err().contains("当前连接"));assert!(task.lease.is_none());task.cleanup().unwrap();assert!(native.recovery.lock().unwrap().is_some());assert!(native.network.last.lock().unwrap().is_null());assert!(task.files.is_empty());native.recovery.lock().unwrap().take();drop(task);drop(native);remove_fixture(root);
    }
    #[cfg(windows)]
    #[test]fn cleanup_reaps_only_the_diagnostic_child_and_retains_the_foreground_lease_until_done(){
        let(root,native,device)=fixture();let mut task=Task::new(&native,&device,&Default::default()).unwrap();task.lease=native.probe_admission.foreground_owned(&AtomicBool::new(false));
        let spawn=||Command::new("pwsh.exe").args(["-NoProfile","-NonInteractive","-Command","Start-Sleep -Seconds 30"]).creation_flags(0x08000000).spawn().unwrap();
        let owned=spawn();let owned_pid=owned.id();let unrelated=spawn();let unrelated_pid=unrelated.id();*native.child.lock().unwrap()=Some(unrelated);task.child=Some(owned);
        assert!(native.probe_admission.probe().is_none());task.cleanup().unwrap();assert!(task.child.is_none());assert!(native.probe_admission.probe().is_some());
        let mut slot=native.child.lock().unwrap();let child=slot.as_mut().unwrap();assert_eq!(child.id(),unrelated_pid);assert_ne!(owned_pid,unrelated_pid);assert!(child.try_wait().unwrap().is_none(),"Diagnostic cleanup killed the other child");child.kill().unwrap();child.wait().unwrap();slot.take();drop(slot);drop(task);drop(native);remove_fixture(root);
    }
}
