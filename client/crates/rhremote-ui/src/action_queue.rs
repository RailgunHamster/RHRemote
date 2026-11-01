//! Production UI dispatch. Local navigation must not wait for device probes.
use rhremote_core::Controller;
use std::sync::{Arc, mpsc::Receiver};

pub(crate) type Request = (String, String, u128, std::time::Instant, Option<String>);

pub(crate) fn run(actions: Arc<Controller>, receiver: Receiver<Request>) {
    for (id, arg, requested_at_us, queued, decision) in receiver {
        let args = crate::queued_args(&id, &arg, &actions.state(), decision);
        if id == "devices.refresh" {
            let actions=actions.clone();
            std::thread::spawn(move || {
                let response=actions.dispatch_timed(&id,args,Some(rhremote_core::ActionTiming {requested_at_us,queue_wait_us:queued.elapsed().as_micros()}));
                crate::report_queued_error(&actions,&id,&response);
            });
            continue;
        }
        if id.starts_with("updates.") || rhremote_core::is_session_control_action(&id) || matches!(id.as_str(), "sync.run" | "sync.resolve") {
            let actions = actions.clone();
            std::thread::spawn(move || {
                let response = actions.dispatch(&id, args);
                if response["ok"] != true && !id.starts_with("updates.") && !rhremote_core::is_session_control_action(&id) {
                    actions.message(response["error"].as_str().unwrap_or("操作失败").into());
                }
            });
            continue;
        }
        let response = actions.dispatch_timed(&id, args, Some(rhremote_core::ActionTiming { requested_at_us, queue_wait_us: queued.elapsed().as_micros() }));
        crate::report_queued_error(&actions, &id, &response);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rhremote_core::Platform;
    use serde_json::{Value, json};
    use std::{sync::{Mutex, mpsc}, time::{Duration, Instant}};

    struct DelayedProbe { entered: mpsc::Sender<()>, release: Mutex<mpsc::Receiver<()>> }
    impl Platform for DelayedProbe {
        fn execute(&self, id: &str, _: &Value) -> Result<Value, String> {
            if id == "devices.refresh" { self.entered.send(()).unwrap(); self.release.lock().unwrap().recv_timeout(Duration::from_secs(4)).unwrap(); }
            Ok(json!({}))
        }
    }

    #[test]
    fn device_refresh_does_not_delay_subsequent_navigation_in_the_production_queue() {
        let root = std::env::temp_dir().join(format!("rhremote-queue-{}", rhremote_core::random_token().unwrap()));
        let (entered, started) = mpsc::channel(); let (release, released) = mpsc::channel();
        let controller = Controller::new("test", root.clone(), Arc::new(DelayedProbe { entered, release: Mutex::new(released) })).unwrap();
        let (sender, receiver) = mpsc::sync_channel(8); let worker = { let c=controller.clone(); std::thread::spawn(move || run(c, receiver)) };
        sender.send(("devices.refresh".into(), "".into(), 0, Instant::now(), None)).unwrap();
        started.recv_timeout(Duration::from_secs(2)).unwrap();
        sender.send(("app.settings.open".into(), "".into(), 0, Instant::now(), None)).unwrap();
        let deadline = Instant::now() + Duration::from_millis(500);
        while controller.state()["page"] != "global-settings" && Instant::now() < deadline { std::thread::sleep(Duration::from_millis(5)); }
        let state = controller.state();
        release.send(()).unwrap(); drop(sender); worker.join().unwrap();
        let deadline=Instant::now()+Duration::from_secs(2); while controller.state()["devicesRefreshing"]==true && Instant::now()<deadline { std::thread::sleep(Duration::from_millis(5)); }
        if root.exists() { std::fs::remove_file(root).unwrap(); }
        assert_eq!(state["page"], "global-settings", "the production queue kept navigation behind a paused probe");
        assert_eq!(state["devicesRefreshing"], true);
        assert_eq!(controller.state()["devicesRefreshing"], false);
    }

    #[test]
    fn a_running_diagnostic_does_not_keep_navigation_behind_network_io() {
        struct DelayedDiagnostic { entered:mpsc::Sender<()>, release:Mutex<mpsc::Receiver<()>> }
        impl Platform for DelayedDiagnostic {
            fn execute(&self,id:&str,_:&Value)->Result<Value,String>{
                if id=="device.network.diagnose" {self.entered.send(()).unwrap();self.release.lock().unwrap().recv_timeout(Duration::from_secs(4)).unwrap();}
                Ok(json!({"uuid":"host","selectedAddress":"192.0.2.1"}))
            }
        }
        let file=std::env::temp_dir().join(format!("rhremote-diagnostic-queue-{}.json",rhremote_core::random_token().unwrap()));
        let(entered,started)=mpsc::channel();let(release,released)=mpsc::channel();
        let controller=Controller::new("test",file.clone(),Arc::new(DelayedDiagnostic{entered,release:Mutex::new(released)})).unwrap();
        controller.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"fixture","address":"192.0.2.1","online":false,"paired":false}])).unwrap());
        let(sender,receiver)=mpsc::sync_channel(8);let worker={let c=controller.clone();std::thread::spawn(move||run(c,receiver))};
        sender.send(("device.network.diagnose".into(),"host".into(),0,Instant::now(),None)).unwrap();started.recv_timeout(Duration::from_secs(2)).unwrap();
        sender.send(("app.settings.open".into(),"".into(),0,Instant::now(),None)).unwrap();
        let deadline=Instant::now()+Duration::from_millis(500);while controller.state()["page"]!="global-settings"&&Instant::now()<deadline{std::thread::sleep(Duration::from_millis(5));}
        let during=controller.state();release.send(()).unwrap();drop(sender);worker.join().unwrap();
        let deadline=Instant::now()+Duration::from_secs(2);while controller.state()["diagnostics"]["busy"]==true&&Instant::now()<deadline{std::thread::sleep(Duration::from_millis(5));}
        if file.exists(){std::fs::remove_file(file).unwrap();}
        assert_eq!(during["page"],"global-settings","The ordinary action queue still waited for the diagnostic's network I/O");
        assert_eq!(during["diagnostics"]["busy"],true,"Navigation must not silently cancel the diagnostic");
    }
}
