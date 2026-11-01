//! The Linux service owns one engine process group and handles normal systemd shutdown.
use crate::runtime::Options;
use serde_json::{Value,json};
use std::{fs,os::unix::{fs::OpenOptionsExt,process::CommandExt},process::{Child,Command,Stdio},sync::{Arc,atomic::{AtomicBool,AtomicU32,Ordering},mpsc},time::{Duration,Instant}};

static ENGINE_PID:AtomicU32=AtomicU32::new(0);

pub(crate) struct Supervisor {
    child:Option<Child>,
    stop:Arc<AtomicBool>,
}

impl Supervisor {
    pub(crate) fn start(options:&Options)->Result<Self,Box<dyn std::error::Error>> {
        let stop=Arc::new(AtomicBool::new(false));
        let signal_stop=stop.clone();
        let (ready,receive)=mpsc::sync_channel(1);
        std::thread::spawn(move|| {
            let result=(||->Result<(),String>{
                let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e|e.to_string())?;
                runtime.block_on(async {
                    use tokio::signal::unix::{signal,SignalKind};
                    let mut terminate=signal(SignalKind::terminate()).map_err(|e|e.to_string())?;
                    let mut interrupt=signal(SignalKind::interrupt()).map_err(|e|e.to_string())?;
                    let _=ready.send(Ok(()));
                    tokio::select!{_=terminate.recv()=>{},_=interrupt.recv()=>{}}
                    signal_stop.store(true,Ordering::SeqCst);
                    Ok(())
                })
            })();
            if let Err(error)=result {let _=ready.send(Err(error));}
        });
        receive.recv_timeout(Duration::from_secs(3))??;
        let mut value=Self {child:None,stop};
        if options.unified {
            let engine=options.sunshine.as_ref().ok_or("Engine path missing")?.canonicalize()?;
            let config=options.config.join("sunshine.conf").canonicalize()?;
            fs::create_dir_all(&options.config)?;
            let log=fs::OpenOptions::new().create(true).append(true).mode(0o600).open(options.config.join("rhremote-engine.log"))?;
            let child=Command::new(&engine).arg(config).current_dir(engine.parent().ok_or("Engine directory missing")?)
                .process_group(0).stdin(Stdio::null()).stdout(Stdio::from(log.try_clone()?)).stderr(Stdio::from(log)).spawn()?;
            ENGINE_PID.store(child.id(),Ordering::SeqCst);
            value.child=Some(child);
        }
        Ok(value)
    }
    pub(crate) fn stopping(&self)->bool {self.stop.load(Ordering::SeqCst)}
    pub(crate) fn check(&mut self)->Result<(),Box<dyn std::error::Error>> {
        if let Some(child)=&mut self.child {
            if let Some(status)=child.try_wait()? {ENGINE_PID.store(0,Ordering::SeqCst);return Err(format!("Sunshine engine exited: {status}").into());}
        }
        Ok(())
    }
}

fn stop_owned_group(child:&mut Child) {
    if child.try_wait().ok().flatten().is_some(){return;}
    let Ok(pid)=i32::try_from(child.id())else{return;};
    if pid<=1{return;}
    // The child was created with process_group(0). Its unreaped PID cannot be
    // reused between this liveness check and signalling this owned group.
    unsafe {libc::kill(-pid,libc::SIGTERM);}
    let until=Instant::now()+Duration::from_secs(3);
    while Instant::now()<until {
        if child.try_wait().ok().flatten().is_some(){return;}
        std::thread::sleep(Duration::from_millis(20));
    }
    unsafe {libc::kill(-pid,libc::SIGKILL);}
    let _=child.wait();
}
impl Drop for Supervisor {
    fn drop(&mut self){if let Some(child)=&mut self.child{stop_owned_group(child);}ENGINE_PID.store(0,Ordering::SeqCst);}
}
pub(crate) fn health()->Value {
    let pid=ENGINE_PID.load(Ordering::SeqCst);
    json!({"platform":"linux","managed":pid!=0,"enginePid":if pid==0{None}else{Some(pid)}})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dropping_the_service_stops_and_reaps_only_its_engine_group() {
        let mut child=Command::new("/usr/bin/sleep").arg("30").process_group(0).spawn().unwrap();
        stop_owned_group(&mut child);
        assert!(child.try_wait().unwrap().is_some());
    }
}
