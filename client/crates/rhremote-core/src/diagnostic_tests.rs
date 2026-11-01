use super::*;
use std::{path::PathBuf,sync::{mpsc,atomic::AtomicUsize},time::{Duration,Instant}};
struct Host {
    entered:mpsc::Sender<Value>,release:Arc<Mutex<mpsc::Receiver<()>>>,cleaning:mpsc::Sender<()>,finish:Arc<Mutex<mpsc::Receiver<()>>>,
    requests:Arc<AtomicUsize>,cleanups:Arc<AtomicUsize>,fail:Arc<AtomicBool>,closes:AtomicUsize,
}
struct Task {host:Arc<Host>,args:Value}
impl DiagnosticTask for Task {
    fn run(&mut self,cancel:Arc<AtomicBool>)->Result<Value,String>{
        self.host.requests.fetch_add(1,Ordering::SeqCst);self.host.entered.send(self.args.clone()).unwrap();self.host.release.lock().unwrap().recv_timeout(Duration::from_secs(4)).unwrap();checkpoint(&cancel)?;
        Ok(json!({"uuid":self.args["device"]["uuid"],"selectedAddress":self.args["device"]["address"]}))
    }
    fn cleanup(&mut self)->Result<(),String>{self.host.cleanups.fetch_add(1,Ordering::SeqCst);self.host.cleaning.send(()).unwrap();self.host.finish.lock().unwrap().recv_timeout(Duration::from_secs(4)).unwrap();if self.host.fail.swap(false,Ordering::SeqCst){Err("fixture cleanup failure".into())}else{Ok(())}}
}
struct PlatformHost(Arc<Host>);
impl Platform for PlatformHost {
    fn execute(&self,id:&str,_:&Value)->Result<Value,String>{if id=="engine.close"{self.0.closes.fetch_add(1,Ordering::SeqCst);}Ok(json!({}))}
    fn prepare_diagnostic(&self,device:&Device,profile:&Profile)->Option<Result<Box<dyn DiagnosticTask>,String>>{Some(Ok(Box::new(Task{host:self.0.clone(),args:json!({"device":device,"profile":profile})})))}
}
struct Fixture {c:Arc<Controller>,host:Arc<Host>,started:mpsc::Receiver<Value>,release:mpsc::Sender<()>,cleaning:mpsc::Receiver<()>,finish:mpsc::Sender<()>,file:PathBuf}
impl Fixture {
    fn new()->Self{
        let(entered,started)=mpsc::channel();let(release,released)=mpsc::channel();let(cleaning,began)=mpsc::channel();let(finish,finished)=mpsc::channel();
        let host=Arc::new(Host{entered,release:Arc::new(Mutex::new(released)),cleaning,finish:Arc::new(Mutex::new(finished)),requests:Arc::new(AtomicUsize::new(0)),cleanups:Arc::new(AtomicUsize::new(0)),fail:Arc::new(AtomicBool::new(false)),closes:AtomicUsize::new(0)});
        let file=std::env::temp_dir().join(format!("rhremote-diagnostic-{}.json",crate::random_token().unwrap()));let c=Controller::new("test",file.clone(),Arc::new(PlatformHost(host.clone()))).unwrap();
        c.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"A","address":"192.0.2.1","online":false,"paired":true},{"uuid":"other","name":"B","address":"192.0.2.2","online":false,"paired":true}])).unwrap());
        Self{c,host,started,release,cleaning:began,finish,file}
    }
    fn start(&self)->u64{let response=self.c.dispatch("device.network.diagnose",json!({"uuid":"host"}));assert_eq!(response["ok"],true);response["result"]["operationId"].as_u64().unwrap()}
    fn complete(&self){self.release.send(()).unwrap();self.cleaning.recv_timeout(Duration::from_secs(2)).unwrap();self.finish.send(()).unwrap();self.phase("completed");}
    fn phase(&self,phase:&str){let end=Instant::now()+Duration::from_secs(2);loop{let s=self.c.state();if s["diagnostics"]["phase"]==phase{return;}assert!(Instant::now()<end,"{s}");std::thread::sleep(Duration::from_millis(2));}}
}
impl Drop for Fixture {fn drop(&mut self){for _ in 0..4{let _=self.release.send(());let _=self.finish.send(());}let until=Instant::now()+Duration::from_secs(2);while self.c.state()["diagnostics"]["busy"]==true&&Instant::now()<until{std::thread::sleep(Duration::from_millis(2));}if self.file.exists(){std::fs::remove_file(&self.file).unwrap();}}}

#[test]fn admitted_diagnostic_freezes_inputs_without_blocking_navigation_or_changing_drafts(){
    let f=Fixture::new();f.c.dispatch("device.settings.open",json!({"uuid":"host"}));let started=Instant::now();f.start();assert!(started.elapsed()<Duration::from_millis(250));let captured=f.started.recv_timeout(Duration::from_secs(2)).unwrap();
    f.c.dispatch("settings.network.edit",json!({"key":"address","value":"192.0.2.9"}));f.complete();
    assert_eq!(captured["device"]["address"],"192.0.2.1");assert_eq!(captured["device"]["addresses"],json!([]));assert_eq!(f.c.state()["draftAddress"],"192.0.2.9");assert_eq!(f.c.state()["diagnostics"]["result"]["selectedAddress"],"192.0.2.1");
}
#[test]fn cancellation_is_immediate_but_admission_waits_for_actual_cleanup(){
    let f=Fixture::new();let operation=f.start();f.started.recv_timeout(Duration::from_secs(2)).unwrap();let began=Instant::now();assert_eq!(f.c.dispatch("device.network.cancel",json!({"operationId":operation,"expectedAction":"cancel"}))["ok"],true);assert!(began.elapsed()<Duration::from_millis(250));
    for id in ["device.connect","device.pair","device.network.diagnose"]{assert_eq!(f.c.dispatch(id,json!({"uuid":"other"}))["ok"],false);}
    assert_eq!(f.c.state()["updateInstallHint"],"请先完成或取消连接诊断");f.release.send(()).unwrap();f.cleaning.recv_timeout(Duration::from_secs(2)).unwrap();assert_eq!(f.c.state()["diagnostics"]["busy"],true);f.finish.send(()).unwrap();f.phase("cancelled");
    let next=f.start();assert_eq!(next,operation+1);f.started.recv_timeout(Duration::from_secs(2)).unwrap();assert_eq!(f.c.dispatch("device.network.cancel",json!({"operationId":operation}))["ok"],false);assert_eq!(f.c.state()["diagnostics"]["cancelRequested"],false);f.complete();
}
#[test]fn cleanup_failure_keeps_ownership_and_retry_runs_no_new_network_work(){
    let f=Fixture::new();f.host.fail.store(true,Ordering::SeqCst);let operation=f.start();f.started.recv_timeout(Duration::from_secs(2)).unwrap();f.release.send(()).unwrap();f.cleaning.recv_timeout(Duration::from_secs(2)).unwrap();f.finish.send(()).unwrap();f.phase("cleanup-error");
    let state=f.c.state();assert_eq!(state["diagnostics"]["busy"],true);assert_eq!(state["diagnostics"]["cancelAction"],"cleanup");assert_eq!(f.c.dispatch("device.connect",json!({"uuid":"other"}))["ok"],false);
    assert_eq!(f.c.dispatch("device.network.cancel",json!({"operationId":operation,"expectedAction":"cancel"}))["ok"],false);
    assert_eq!(f.c.dispatch("device.network.cancel",json!({"operationId":operation,"expectedAction":"cleanup"}))["ok"],true);f.cleaning.recv_timeout(Duration::from_secs(2)).unwrap();f.finish.send(()).unwrap();f.phase("cancelled");
    assert_eq!(f.host.requests.load(Ordering::SeqCst),1);assert_eq!(f.host.cleanups.load(Ordering::SeqCst),2);
}
#[test]fn completed_diagnostic_does_not_replace_another_devices_page_feedback(){
    let f=Fixture::new();f.start();f.started.recv_timeout(Duration::from_secs(2)).unwrap();assert_eq!(f.c.dispatch("device.settings.open",json!({"uuid":"other"}))["ok"],true);f.c.dispatch("app.settings.open",json!({}));let before=f.c.state();f.complete();let after=f.c.state();
    for key in ["selected","page","status","settingsTab"]{assert_eq!(after[key],before[key],"{key}");}assert_eq!(after["diagnostics"]["uuid"],"host");
}
#[test]fn stale_configuration_and_old_details_cannot_operate_a_new_request(){
    let f=Fixture::new();f.c.dispatch("device.settings.open",json!({"uuid":"host"}));f.c.dispatch("settings.tab",json!({"tab":"network"}));let intent=f.c.state()["diagnosticRequestIntent"].clone();f.c.dispatch("settings.network.edit",json!({"key":"address","value":"192.0.2.9"}));
    assert_eq!(f.c.dispatch("device.network.diagnose",json!({"uuid":"host","expectedIntent":intent}))["ok"],false);assert_eq!(f.host.requests.load(Ordering::SeqCst),0);
    let operation=f.start();f.started.recv_timeout(Duration::from_secs(2)).unwrap();f.complete();
    assert_eq!(f.c.dispatch("device.network.details.toggle",json!({"uuid":"other","operationId":operation}))["ok"],false);
    assert_eq!(f.c.dispatch("device.network.details.toggle",json!({"uuid":"host","operationId":operation,"expectedExpanded":false}))["ok"],true);
    assert_eq!(f.c.dispatch("device.network.details.toggle",json!({"uuid":"host","operationId":operation,"expectedExpanded":false}))["ok"],false);assert_eq!(f.host.requests.load(Ordering::SeqCst),1);
}
#[test]fn closing_waits_for_diagnostic_cleanup_before_requesting_process_exit(){
    let f=Fixture::new();f.start();f.started.recv_timeout(Duration::from_secs(2)).unwrap();assert_eq!(f.c.dispatch("window.close",json!({}))["result"]["cleanupPending"],true);assert_eq!(f.c.state()["windowAction"],"");assert_eq!(f.host.closes.load(Ordering::SeqCst),0);
    f.release.send(()).unwrap();f.cleaning.recv_timeout(Duration::from_secs(2)).unwrap();assert_eq!(f.c.state()["windowAction"],"");f.finish.send(()).unwrap();let until=Instant::now()+Duration::from_secs(2);while f.c.state()["windowAction"]!="window.close"&&Instant::now()<until{std::thread::sleep(Duration::from_millis(2));}
    assert_eq!(f.c.state()["windowAction"],"window.close");assert_eq!(f.host.closes.load(Ordering::SeqCst),1);
}
