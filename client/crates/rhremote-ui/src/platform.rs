use rhremote_core::{Device, DeviceObservation, Platform, DeviceCheck, CheckOutcome, CheckSource, CheckPhase};
use serde_json::{Value, json};
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

fn parse_added_device(address:&str,body:&str)->Result<Device,String>{
    if body.len()>262144{return Err("设备信息过大".into());}
    let doc=roxmltree::Document::parse(body).map_err(|_|"设备没有返回有效的 Sunshine 信息".to_string())?;
    if doc.root_element().tag_name().name()!="root"||doc.root_element().attribute("status_code")!=Some("200"){return Err("设备拒绝了信息请求".into());}
    let field=|key|doc.descendants().find(|n|n.has_tag_name(key)).and_then(|n|n.text()).map(str::trim).filter(|v|!v.is_empty());
    Ok(Device{uuid:field("uniqueid").ok_or("设备没有返回标识")?.into(),name:field("hostname").unwrap_or(address).into(),display_name:String::new(),address:address.into(),addresses:Vec::new(),online:true,paired:false,is_self:false})
}

/// A background poll must not undo an accepted add or address change made after it started.
fn apply_device_refresh(current:&mut [Device],before:&[Device],probed:Vec<Device>,expected_epoch:u64,current_epoch:u64){
    if expected_epoch!=current_epoch{return;}
    for device in probed{if let Some(slot)=current.iter_mut().find(|d|d.uuid==device.uuid){if before.iter().find(|d|d.uuid==device.uuid)==Some(&*slot){*slot=device;}}}
}

#[path = "pair_preparation.rs"]
mod pair_preparation;
use pair_preparation::PendingPair;
#[cfg(all(test, windows))]
#[path = "platform_frame_tests.rs"]
mod frame_milestone_tests;
struct FirstFrameState {
    pid: u32,
    stream_window: Option<String>,
    audio_enabled: Option<bool>,
    volume: Option<u64>,
}
impl FirstFrameState {
    fn observe(pid: u32, value: &Value, previous: Option<&Self>) -> Self {
        let previous = previous.filter(|state| state.pid == pid);
        Self {
            pid,
            stream_window: value["streamWindow"].as_str().filter(|value| value.parse::<usize>().is_ok()).map(str::to_owned)
                .or_else(|| previous.and_then(|state| state.stream_window.clone())),
            audio_enabled: value["audioEnabled"].as_bool().or_else(|| previous.and_then(|state| state.audio_enabled)),
            volume: value["volume"].as_u64().filter(|value| *value <= 100).or_else(|| previous.and_then(|state| state.volume)),
        }
    }
    fn restore(&self, value: &mut Value) {
        value["firstFrame"] = json!(true);
        value["firstFrameFromMilestone"] = json!(true);
        if value["streamWindow"].is_null() { if let Some(window) = &self.stream_window { value["streamWindow"] = json!(window); } }
        if value["audioEnabled"].is_null() { if let Some(enabled) = self.audio_enabled { value["audioEnabled"] = json!(enabled); } }
        if value["volume"].is_null() { if let Some(volume) = self.volume { value["volume"] = json!(volume); } }
    }
}
struct Recovery {
    args: Value,
    policy: crate::recovery::Policy,
    routes: crate::recovery::Routes,
    pending: Option<std::thread::JoinHandle<Result<LaunchOutcome, String>>>,
    cancel: Arc<AtomicBool>,
    current: Value,
    native_started: Option<Instant>,
    timeout_requested: bool,
    first_frame_wait: crate::recovery::FirstFrameWait,
    first_frame: Option<FirstFrameState>,
    attempt: u32,
}
impl Recovery {
    fn new(args: Value) -> Self {
        Self {
            args,
            policy: Default::default(),
            routes: Default::default(),
            pending: None,
            cancel: Arc::new(AtomicBool::new(false)),
            current: Value::Null,
            native_started: None,
            timeout_requested: false,
            first_frame_wait: Default::default(),
            first_frame: None,
            attempt: 0,
        }
    }
}
struct OwnedChild {
    child: Option<Child>,
    cleanup: crate::native_cleanup::Queue,
    context: crate::native_cleanup::Context,
    network: Arc<crate::network::Network>,
}
impl OwnedChild {
    fn take(mut self) -> Child {
        self.child.take().unwrap()
    }
}
impl Drop for OwnedChild {
    fn drop(&mut self) {
        if let Some(child) = self.child.take() {
            self.cleanup.enqueue(child, self.context.clone(), self.network.clone());
        }
    }
}
struct LaunchOutcome {
    child: OwnedChild,
    network: Value,
}
#[path="platform_diagnostics.rs"]
mod diagnostic;

#[derive(Clone)]
struct LaunchContext {
    engine: PathBuf,
    native_data: PathBuf,
    session_status: PathBuf,
    token: String,
    port: u16,
    network: Arc<crate::network::Network>,
    statistics: Arc<Mutex<crate::statistics::Statistics>>,
    cleanup: crate::native_cleanup::Queue,
}

// The CLI rejects zero; it is an internal preference value for auto detection.
fn packet_arguments(size: u32) -> Vec<String> {
    if size == 0 {
        Vec::new()
    } else {
        vec!["--packet-size".into(), size.to_string()]
    }
}
#[cfg(test)]
mod packet_argument_tests {
    #[test]
    fn automatic_packets_never_reach_the_cli_as_zero() {
        assert!(super::packet_arguments(0).is_empty());
        assert_eq!(super::packet_arguments(1024), vec!["--packet-size", "1024"]);
        assert_eq!(super::packet_arguments(1200), vec!["--packet-size", "1200"]);
    }
}

#[cfg(all(test, windows))]
mod recovery_transport_tests {
    use super::*;
    fn owned_json(body:&str,pid:u32)->String{let mut value:Value=serde_json::from_str(body).unwrap();value["processId"]=json!(pid);value.to_string()}
    #[test]
    fn live_native_without_a_phase_keeps_the_controller_pending_and_rejects_duplicate_connects() {
        struct Accepted(std::sync::atomic::AtomicUsize);
        impl Platform for Accepted {
            fn execute(&self,id:&str,_:&Value)->Result<Value,String>{if id=="device.connect"{self.0.fetch_add(1,Ordering::SeqCst);}Ok(json!({"accepted":true}))}
        }
        let root=std::env::temp_dir().join(format!("rhremote-empty-native-phase-{}",rhremote_core::random_token().unwrap()));
        let server=tiny_http::Server::http("127.0.0.1:0").unwrap();let port=server.server_addr().to_ip().unwrap().port();
        let child=Command::new("powershell.exe").args(["-NoProfile","-NonInteractive","-Command","Start-Sleep -Seconds 30"]).creation_flags(0x08000000).spawn().unwrap();let pid=child.id();
        let worker=std::thread::spawn(move||{for phase in ["","starting","connected"]{let Some(request)=server.recv_timeout(Duration::from_secs(2)).unwrap() else {break};request.respond(tiny_http::Response::from_string(json!({"processId":pid,"phase":phase,"streaming":phase=="connected","firstFrame":phase=="connected"}).to_string())).unwrap();}});
        let mut native=NativePlatform::new(PathBuf::from("unused-empty-phase.exe"),"fixture-token".into(),root.clone()).unwrap();native.port=port;*native.child.lock().unwrap()=Some(child);
        let mut recovery=Recovery::new(json!({}));recovery.native_started=Some(Instant::now());*native.recovery.lock().unwrap()=Some(recovery);
        let accepted=Arc::new(Accepted(std::sync::atomic::AtomicUsize::new(0)));
        let controller=rhremote_core::Controller::new("test",root.join("preferences.json"),accepted.clone()).unwrap();
        controller.replace_devices(vec![Device{uuid:"owned-host".into(),name:"winserver".into(),display_name:String::new(),address:"192.0.2.1".into(),addresses:Vec::new(),online:true,paired:true,is_self:false}]);
        assert_eq!(controller.dispatch("device.connect",json!({"uuid":"owned-host"}))["ok"],true);
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{
            for expected in ["starting","starting","connected"] {
                let state=native.state().unwrap();controller.stream_state(state["streaming"]==true,state);
                assert_eq!(controller.state()["connection"]["phase"],expected,"An owned API heartbeat without a phase is not an ended connection");
                assert_eq!(controller.state()["connection"]["error"],"");
                assert_eq!(controller.dispatch("device.connect",json!({"uuid":"owned-host"}))["ok"],false);
                assert_eq!(accepted.0.load(Ordering::SeqCst),1);
            }
            assert_eq!(controller.state()["connection"]["firstFrame"],true);
        }));
        stop_fixture(&native);worker.join().unwrap();assert!(root.starts_with(std::env::temp_dir())&&root.file_name().unwrap().to_string_lossy().starts_with("rhremote-empty-native-phase-"));fs::remove_dir_all(&root).unwrap();
        if let Err(failure)=result {std::panic::resume_unwind(failure);}
    }
    #[test]
    fn an_empty_native_phase_still_has_a_bounded_initialization_budget() {
        let root=std::env::temp_dir().join(format!("rhremote-empty-phase-budget-{}",rhremote_core::random_token().unwrap()));
        let server=tiny_http::Server::http("127.0.0.1:0").unwrap();let port=server.server_addr().to_ip().unwrap().port();
        let child=Command::new("powershell.exe").args(["-NoProfile","-NonInteractive","-Command","Start-Sleep -Seconds 30"]).creation_flags(0x08000000).spawn().unwrap();let pid=child.id();
        let worker=std::thread::spawn(move||{let request=server.recv_timeout(Duration::from_secs(2)).unwrap().unwrap();request.respond(tiny_http::Response::from_string(json!({"processId":pid,"phase":"","streaming":false,"firstFrame":false}).to_string())).unwrap();if let Some(request)=server.recv_timeout(Duration::from_secs(2)).unwrap(){let path=request.url().to_owned();request.respond(tiny_http::Response::from_string("{}")).unwrap();Some(path)}else{None}});
        let mut native=NativePlatform::new(PathBuf::from("unused-empty-budget.exe"),"fixture-token".into(),root.clone()).unwrap();native.port=port;*native.child.lock().unwrap()=Some(child);
        let mut recovery=Recovery::new(json!({}));recovery.native_started=Some(Instant::now()-Duration::from_secs(16));*native.recovery.lock().unwrap()=Some(recovery);
        let _=native.state().unwrap();let timed_out=native.recovery.lock().unwrap().as_ref().unwrap().timeout_requested;
        let request=worker.join().unwrap();stop_fixture(&native);assert!(root.starts_with(std::env::temp_dir())&&root.file_name().unwrap().to_string_lossy().starts_with("rhremote-empty-phase-budget-"));fs::remove_dir_all(root).unwrap();
        assert!(timed_out,"An uninitialized API must not wait indefinitely");assert_eq!(request.as_deref(),Some("/v1/disconnect"));
    }
    fn protocol_budget_fixture(owned_status: bool) {
        let root=std::env::temp_dir().join(format!("rhremote-protocol-budget-{}",rhremote_core::random_token().unwrap()));
        let server=tiny_http::Server::http("127.0.0.1:0").unwrap();
        let port=server.server_addr().to_ip().unwrap().port();
        let child=Command::new("powershell.exe").args(["-NoProfile","-NonInteractive","-Command","Start-Sleep -Seconds 30"])
            .creation_flags(0x08000000).spawn().unwrap();
        let id=child.id();
        let worker=std::thread::spawn(move || {
            let request=server.recv_timeout(Duration::from_secs(3)).unwrap().unwrap();
            let state=json!({"phase":"connected","streaming":true,"firstFrame":false,"processId":if owned_status{id}else{id+1}});
            request.respond(tiny_http::Response::from_string(state.to_string())).unwrap();
            if !owned_status {
                if let Some(request)=server.recv_timeout(Duration::from_millis(200)).unwrap(){
                    let path=request.url().to_owned();request.respond(tiny_http::Response::from_string("{}")).unwrap();Some(path)
                } else { None }
            } else { None }
        });
        let mut platform=NativePlatform::new(PathBuf::from("unused-clock-fixture.exe"),"fixture-token".into(),root.clone()).unwrap();
        platform.port=port;
        *platform.child.lock().unwrap()=Some(child);
        let mut recovery=Recovery::new(json!({}));
        recovery.native_started=Some(Instant::now()-Duration::from_secs(16));
        *platform.recovery.lock().unwrap()=Some(recovery);
        let state=platform.state().unwrap();
        let command=worker.join().unwrap();
        assert!(command.is_none(),"An unrelated API cannot receive a timeout disconnect");
        assert_eq!(platform.recovery.lock().unwrap().as_ref().unwrap().timeout_requested,!owned_status);
        if owned_status {assert_eq!(state["phase"],"connected");}
        stop_fixture(&platform);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn an_owned_protocol_ready_process_gets_a_separate_frame_budget() {protocol_budget_fixture(true);}
    #[test]
    fn another_process_cannot_extend_the_first_frame_budget() {protocol_budget_fixture(false);}
    #[test]
    fn a_previous_status_file_cannot_fail_release_or_connect_the_new_process() {
        let root=std::env::temp_dir().join(format!("rhremote-state-owner-{}",rhremote_core::random_token().unwrap()));
        let p=NativePlatform::new(PathBuf::from("unused-state-owner.exe"),"fixture-token".into(),root.clone()).unwrap();
        let child=Command::new("powershell.exe").args(["-NoProfile","-NonInteractive","-Command","Start-Sleep -Seconds 30"]).creation_flags(0x08000000).spawn().unwrap();
        let pid=child.id();*p.child.lock().unwrap()=Some(child);let mut recovery=Recovery::new(json!({}));recovery.native_started=Some(Instant::now());*p.recovery.lock().unwrap()=Some(recovery);
        for value in [
            json!({"processId":pid+1,"phase":"error","routeFatal":true,"error":"old failure"}),
            json!({"processId":pid+1,"phase":"connected","streaming":true,"firstFrame":true}),
            json!({"processId":pid+1,"phase":"transport_closed","transportReleased":true,"retryEligible":true}),
            json!({"phase":"disconnected","streaming":false,"firstFrame":false}),
        ] {
            fs::write(&p.session_status,value.to_string()).unwrap();let state=p.state().unwrap();
            assert_eq!(state["phase"],"connecting");assert_eq!(state["streaming"],false);assert_eq!(state["firstFrame"],false);assert_eq!(state["error"],"");assert_eq!(state["awaitingOwnedState"],true);
            assert!(p.child.lock().unwrap().is_some());assert!(!p.recovery.lock().unwrap().as_ref().unwrap().policy.armed());assert!(p.retiring.lock().unwrap().is_empty());
        }
        stop_fixture(&p);fs::remove_dir_all(root).unwrap();
    }
    // These older recovery fixtures use sleeping shells, not a native API.
    // Explicitly finish only those fixtures; real graceful teardown is tested
    // with an authenticated control server in native_cleanup's process tests.
    pub(super) fn stop_fixture(p: &NativePlatform) {
        if let Some(child) = p.child.lock().unwrap().as_mut() { let _=child.kill();let _=child.wait(); }
        for (child,_,_) in p.retiring.lock().unwrap().iter_mut() {let _=child.kill();let _=child.wait();}
        p.stop_owned().unwrap();
        let until=Instant::now()+Duration::from_secs(3);
        while p.cleanup.pending() {p.cleanup.reap();assert!(Instant::now()<until);std::thread::sleep(Duration::from_millis(5));}
    }
    #[test]
    fn ui_trace_waits_for_a_pending_launch_to_return_before_finishing() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-pending-trace-{}", rhremote_core::random_token().unwrap()
        ));
        let p = NativePlatform::new(PathBuf::from("missing-test-engine.exe"), "test-token".into(), root.clone()).unwrap();
        {
            let mut trace = p.statistics.lock().unwrap();
            trace.configure(true).unwrap();
            trace.session(&json!({})).unwrap();
        }
        let (release, waiting) = std::sync::mpsc::channel();
        let mut recovery = Recovery::new(json!({}));
        recovery.pending = Some(std::thread::spawn(move || {
            waiting.recv().unwrap();
            Err("fixture launch finished".into())
        }));
        *p.recovery.lock().unwrap() = Some(recovery);
        assert_eq!(p.state().unwrap()["tracing"]["recording"], true);
        release.send(()).unwrap();
        let until = Instant::now() + Duration::from_secs(3);
        while !p.recovery.lock().unwrap().as_ref().unwrap().pending.as_ref().unwrap().is_finished() {
            assert!(Instant::now() < until);
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(p.state().unwrap()["tracing"]["recording"], false);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn ui_trace_waits_for_the_retiring_process_even_after_transport_release() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-retiring-trace-{}", rhremote_core::random_token().unwrap()
        ));
        let p = NativePlatform::new(PathBuf::from("missing-test-engine.exe"), "test-token".into(), root.clone()).unwrap();
        {
            let mut trace = p.statistics.lock().unwrap();
            trace.configure(true).unwrap();
            trace.session(&json!({})).unwrap();
        }
        let child = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 20"])
            .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
            .creation_flags(0x08000000).spawn().unwrap();
        p.retiring.lock().unwrap().push((child, Instant::now(), true));
        assert_eq!(p.state().unwrap()["tracing"]["recording"], true);
        stop_fixture(&p);
        assert_eq!(p.state().unwrap()["tracing"]["recording"], false);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn cancelling_pending_connection_does_not_wait_for_the_network_worker() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-cancel-{}",
            rhremote_core::random_token().unwrap()
        ));
        let p = NativePlatform::new(
            PathBuf::from("missing-test-engine.exe"),
            "test-local-token".into(),
            root.clone(),
        )
        .unwrap();
        let mut r = Recovery::new(json!({}));
        let flag = r.cancel.clone();
        let barrier = Arc::new(std::sync::Barrier::new(2));
        let ready = barrier.clone();
        r.pending = Some(std::thread::spawn(move || {
            ready.wait();
            while !flag.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_millis(5));
            }
            Err("cancelled".into())
        }));
        *p.recovery.lock().unwrap() = Some(r);
        barrier.wait();
        let started = Instant::now();
        stop_fixture(&p);
        assert!(started.elapsed() < Duration::from_millis(500));
        let until = Instant::now() + Duration::from_secs(2);
        while !p.reap_launches() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(p.reap_launches());
        assert!(p.recovery.lock().unwrap().is_none());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn discarded_launch_result_terminates_its_owned_process() {
        use windows::Win32::{
            Foundation::{CloseHandle, WAIT_OBJECT_0},
            System::Threading::{OpenProcess, PROCESS_SYNCHRONIZE, WaitForSingleObject},
        };
        let child = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Sleep -Seconds 20",
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        unsafe {
            let process = OpenProcess(PROCESS_SYNCHRONIZE, false, child.id()).unwrap();
            let cleanup=crate::native_cleanup::Queue::default();
            let status=std::env::temp_dir().join(format!("discarded-{}.json",rhremote_core::random_token().unwrap()));
            drop(OwnedChild {child:Some(child),cleanup:cleanup.clone(),context:crate::native_cleanup::Context {port:9,token:"unused-fixture".into(),status:status.clone(),limit:Duration::from_millis(100)},network:Arc::new(crate::network::Network::default())});
            assert_eq!(WaitForSingleObject(process, 3000), WAIT_OBJECT_0);
            let until=Instant::now()+Duration::from_secs(3);while cleanup.pending(){cleanup.reap();assert!(Instant::now()<until);std::thread::sleep(Duration::from_millis(5));}
            let _ = CloseHandle(process);
            let _ = fs::remove_file(status);
        }
    }
    #[test]
    fn fatal_identity_error_wins_over_a_previous_timeout_or_retry_marker() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-fatal-{}",
            rhremote_core::random_token().unwrap()
        ));
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let port = server.server_addr().to_ip().unwrap().port();
        let worker = std::thread::spawn(move || {
            server.recv().unwrap().respond(tiny_http::Response::from_string(r#"{"streaming":false,"firstFrame":false,"routeFatal":true,"routeRetryEligible":true,"error":"certificate mismatch"}"#)).unwrap()
        });
        let mut p = NativePlatform::new(
            PathBuf::from("missing-test-engine.exe"),
            "test-local-token".into(),
            root.clone(),
        )
        .unwrap();
        p.port = port;
        let mut r = Recovery::new(json!({}));
        r.timeout_requested = true;
        r.current = json!({"route":"lan","selectedAddress":"127.0.0.1"});
        *p.recovery.lock().unwrap() = Some(r);
        let value = p.state().unwrap();
        worker.join().unwrap();
        assert_eq!(value["phase"], "error");
        assert_eq!(value["error"], "certificate mismatch");
        assert_eq!(value["routeFallbackAttempts"], 0);
        assert!(p.recovery.lock().unwrap().is_none());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn fatal_session_finishes_cleanup_before_another_session_can_start() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-fatal-cleanup-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir_all(&root).unwrap();
        let marker = root.join("cleanup-finished");
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let port = server.server_addr().to_ip().unwrap().port();
        let (pid_tx,pid_rx)=std::sync::mpsc::channel::<u32>();
        let worker = std::thread::spawn(move || {
            // Setup hands off metadata after Windows creates the child. A setup
            // failure drops the sender; recovery deadlines are asserted below.
            let pid=pid_rx.recv().expect("fixture setup ended before PID handoff");
            server.recv().unwrap().respond(tiny_http::Response::from_string(
                owned_json(r#"{"streaming":false,"firstFrame":false,"routeFatal":true,"phase":"error","error":"decoder failed"}"#,pid),
            )).unwrap();
        });
        let mut p = NativePlatform::new(
            PathBuf::from("missing-test-engine.exe"),
            "test-local-token".into(),
            root.clone(),
        )
        .unwrap();
        p.port = port;
        let child = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command",
                "Start-Sleep -Seconds 2; [IO.File]::WriteAllText($env:RHREMOTE_TEST_CLEANUP_MARKER, 'done')"])
            .env("RHREMOTE_TEST_CLEANUP_MARKER", &marker)
            .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
            .creation_flags(0x08000000).spawn().unwrap();
        pid_tx.send(child.id()).unwrap();
        *p.child.lock().unwrap() = Some(child);
        *p.recovery.lock().unwrap() = Some(Recovery::new(json!({})));
        let state = p.state().unwrap();
        worker.join().unwrap();
        assert_eq!(state["nativeCleanupPending"], true);
        assert!(
            p.execute("device.connect", &json!({}))
                .unwrap_err()
                .contains("上一会话正在退出")
        );
        // A killed process cannot write this marker. This checks the real cleanup
        // lifetime, rather than only checking which bookkeeping list contains it.
        p.retiring.lock().unwrap()[0].0.wait().unwrap();
        assert_eq!(fs::read_to_string(&marker).unwrap(), "done");
        assert_eq!(p.state().unwrap()["nativeCleanupPending"], false);
        stop_fixture(&p);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn initial_media_failure_stays_connecting_while_native_cleanup_runs() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-route-wait-{}",
            rhremote_core::random_token().unwrap()
        ));
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let port = server.server_addr().to_ip().unwrap().port();
        let (pid_tx,pid_rx)=std::sync::mpsc::channel::<u32>();
        let worker = std::thread::spawn(move || {
            let pid=pid_rx.recv().expect("fixture setup ended before PID handoff");
            server.recv().unwrap().respond(tiny_http::Response::from_string(
                owned_json(r#"{"streaming":false,"firstFrame":false,"routeRetryEligible":true,"phase":"error","error":"network failed"}"#,pid),
            )).unwrap();
        });
        let mut p = NativePlatform::new(
            PathBuf::from("missing-test-engine.exe"),
            "test-local-token".into(),
            root.clone(),
        )
        .unwrap();
        p.port = port;
        let child = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Sleep -Seconds 20",
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        pid_tx.send(child.id()).unwrap();
        *p.child.lock().unwrap() = Some(child);
        *p.recovery.lock().unwrap() = Some(Recovery::new(json!({})));
        let state = p.state().unwrap();
        worker.join().unwrap();
        assert_eq!(state["phase"], "reconnecting");
        assert_eq!(state["error"], "");
        assert!(p.retiring.lock().unwrap().is_empty());
        assert!(p.recovery.lock().unwrap().is_some());
        stop_fixture(&p);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn released_transport_retires_a_live_process_after_its_control_port_closes() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-release-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir_all(&root).unwrap();
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let port = server.server_addr().to_ip().unwrap().port();
        let (pid_tx,pid_rx)=std::sync::mpsc::channel::<u32>();
        let worker = std::thread::spawn(move || {
            let pid=pid_rx.recv().expect("fixture setup ended before PID handoff");
            let request = server.recv().unwrap();
            request
                .respond(tiny_http::Response::from_string(
                    owned_json(r#"{"streaming":true,"firstFrame":true,"retryEligible":true}"#,pid),
                ))
                .unwrap();
        });
        let mut p = NativePlatform::new(
            PathBuf::from("missing-test-engine.exe"),
            "test-local-token".into(),
            root.clone(),
        )
        .unwrap();
        p.port = port;
        let child = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Sleep -Seconds 20",
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        let pid=child.id();
        pid_tx.send(pid).unwrap();
        *p.child.lock().unwrap() = Some(child);
        *p.recovery.lock().unwrap() = Some(Recovery::new(json!({"profile":{}})));
        p.state().unwrap();
        worker.join().unwrap();
        fs::write(
            &p.session_status,
            owned_json(r#"{"streaming":false,"retryEligible":true,"transportReleased":false}"#,pid),
        )
        .unwrap();
        p.state().unwrap();
        assert!(p.retiring.lock().unwrap().is_empty());
        fs::write(
            &p.session_status,
            owned_json(r#"{"streaming":false,"retryEligible":true,"transportReleased":true,"phase":"transport_closed"}"#,pid),
        )
        .unwrap();
        p.state().unwrap();
        std::thread::sleep(Duration::from_millis(550));
        p.state().unwrap();
        assert!(p.child.lock().unwrap().is_none());
        assert_eq!(p.retiring.lock().unwrap().len(), 1);
        assert_eq!(
            p.recovery.lock().unwrap().as_ref().unwrap().policy.attempts,
            1
        );
        stop_fixture(&p);
        assert!(p.retiring.lock().unwrap().is_empty());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn live_http_state_arms_recovery_and_disconnect_cancels_it() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-recovery-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir_all(&root).unwrap();
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let port = server.server_addr().to_ip().unwrap().port();
        let (pid_tx,pid_rx)=std::sync::mpsc::channel::<u32>();
        let worker = std::thread::spawn(move || {
            let pid=pid_rx.recv().expect("fixture setup ended before PID handoff");
            for request in server.incoming_requests().take(2) {
                request.respond(tiny_http::Response::from_string(owned_json(r#"{"ok":true,"streaming":true,"firstFrame":true,"phase":"connected","retryEligible":true}"#,pid))).unwrap();
            }
        });
        let mut p = NativePlatform::new(
            PathBuf::from("unused-test-engine.exe"),
            "local-test-token".into(),
            root.clone(),
        )
        .unwrap();
        p.port = port;
        let child = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Sleep -Seconds 20",
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        let id = child.id();
        pid_tx.send(child.id()).unwrap();
        *p.child.lock().unwrap() = Some(child);
        *p.recovery.lock().unwrap() = Some(Recovery::new(json!({"profile":{}})));
        let live = p.state().unwrap();
        p.child.lock().unwrap().as_mut().unwrap().kill().unwrap();
        p.child.lock().unwrap().as_mut().unwrap().wait().unwrap();
        let waiting = p.state().unwrap();
        let cancelled = p.execute("stream.disconnect", &json!({})).unwrap();
        worker.join().unwrap();
        assert_eq!(live["streamProcessId"], id);
        assert_eq!(waiting["phase"], "reconnecting");
        assert_eq!(waiting["streaming"], false);
        assert_eq!(cancelled["cancelled"], true);
        // Disconnect acknowledges submission; the coordinator clears recovery asynchronously.
        let until = Instant::now() + Duration::from_secs(3);
        while p.cleanup.pending() {
            p.cleanup.reap();
            assert!(Instant::now() < until, "Owned cancellation did not finish");
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(p.recovery.lock().unwrap().is_none());
        // This shell has no graceful-teardown API; native_cleanup tests cover that protocol.
        assert_eq!(root.parent(), Some(std::env::temp_dir().as_path()));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn disconnect_during_startup_is_async_and_keeps_admission_until_native_cleanup() {
        let mut fixture=crate::native_cleanup::tests::Fixture::new("cancelled");
        fixture.start_server(fixture.pid,false);
        let mut p=NativePlatform::new(PathBuf::from("unused-fixture-engine.exe"),"fixture-token".into(),fixture.root.join("platform")).unwrap();
        p.port=fixture.port;p.session_status=fixture.status.clone();
        *p.child.lock().unwrap()=Some(fixture.take_child());
        *p.recovery.lock().unwrap()=Some(Recovery::new(json!({})));
        let began=Instant::now();let result=p.execute("stream.disconnect",&json!({})).unwrap();
        assert!(began.elapsed()<Duration::from_millis(500));assert_eq!(result["cleanupPending"],true);
        fixture.wait_disconnect();
        let pending=p.state().unwrap();assert_eq!(pending["phase"],"cancelling");assert_eq!(pending["nativeCleanupPending"],true);assert_eq!(pending["error"],"");
        assert!(p.execute("device.connect",&json!({})).unwrap_err().contains("上一连接正在结束"));
        fixture.release();let until=Instant::now()+Duration::from_secs(5);
        loop{let state=p.state().unwrap();if state["nativeCleanupPending"]==false{assert_eq!(state["phase"],"disconnected");assert_eq!(state["error"],"");break;}assert!(Instant::now()<until);std::thread::sleep(Duration::from_millis(10));}
        assert!(p.recovery.lock().unwrap().is_none());assert!(p.cleanup.failure().is_none());
    }

    #[test]
    fn cancellation_does_not_wait_for_another_worker_holding_admission_locks() {
        let root=std::env::temp_dir().join(format!("rhremote-stop-lock-{}",rhremote_core::random_token().unwrap()));
        let p=NativePlatform::new(PathBuf::from("unused-fixture-engine.exe"),"fixture-token".into(),root.clone()).unwrap();
        let guard=p.recovery.lock().unwrap();
        let began=Instant::now();
        assert_eq!(p.stop_owned().unwrap()["cleanupPending"],true);
        assert!(began.elapsed()<Duration::from_millis(500));
        assert_eq!(p.stop_owned().unwrap()["cleanupPending"],true);
        assert!(p.cleanup.pending());
        drop(guard);
        let until=Instant::now()+Duration::from_secs(3);
        while p.state().unwrap()["nativeCleanupPending"]==true {assert!(Instant::now()<until);std::thread::sleep(Duration::from_millis(5));}
        assert!(!p.stop_requested.load(Ordering::SeqCst));
        fs::remove_dir_all(root).unwrap();
    }
}

pub struct NativePlatform {
    pub engine: PathBuf,
    native_data: PathBuf,
    session_status: PathBuf,
    pub devices: Mutex<Vec<Device>>,
    device_epoch: AtomicU64,
    child: Arc<Mutex<Option<Child>>>,
    retiring: Arc<Mutex<Vec<(Child, Instant, bool)>>>,
    state_agent: ureq::Agent,
    pairing: Arc<Mutex<Option<PendingPair>>>,
    recovery: Arc<Mutex<Option<Recovery>>>,
    token: String,
    port: u16,
    pub sync: Mutex<crate::config_sync::ConfigSync>,
    device_operation: Mutex<()>,
    probe_admission: Arc<crate::device_probe::Admission>,
    local_uuid: Mutex<Option<String>>,
    network: Arc<crate::network::Network>,
    updates: Mutex<crate::updates::Updates>,
    update_snapshot: Arc<Mutex<Value>>,
    update_cancel: Arc<crate::update_cancel::Control>,
    pub statistics: Arc<Mutex<crate::statistics::Statistics>>,
    pub ui_test: Arc<crate::ui_test::Bridge>,
    retired_launches: Arc<Mutex<Vec<std::thread::JoinHandle<Result<LaunchOutcome, String>>>>>,
    cleanup: crate::native_cleanup::Queue,
    stop_requested: AtomicBool,
    stop_submission: Mutex<()>,
    closing: AtomicBool,
}
impl NativePlatform {
    pub fn new(engine: PathBuf, token: String, data: PathBuf) -> Result<Self, String> {
        let default_data=PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap_or_default()).join("RHRemote");
        let ui_test=Arc::new(crate::ui_test::Bridge::new(std::env::var_os("RHREMOTE_DATA_DIR").is_some()&&crate::ui_test::isolated(&data,&default_data,&std::env::var("RHREMOTE_TEST_MODE").unwrap_or_default())));
        let statistics = crate::statistics::Statistics::new(&data);
        let updates=crate::updates::Updates::new();
        updates.load_source(&data);
        let update_snapshot=updates.snapshot();
        let update_cancel=updates.cancellation();
        let native_data = data.join("engine-state");
        fs::create_dir_all(&native_data).map_err(|e| e.to_string())?;
        fs::write(native_data.join("portable.dat"), b"").map_err(|e| e.to_string())?;
        let preferences = native_data.join("RHRemote/RHRemote.ini");
        let legacy = engine
            .parent()
            .unwrap_or(Path::new("."))
            .join("RHRemote/RHRemote.ini");
        if !preferences.exists() && legacy.exists() {
            fs::create_dir_all(preferences.parent().unwrap()).map_err(|e| e.to_string())?;
            fs::copy(legacy, &preferences).map_err(|e| e.to_string())?;
        }
        let mut rows: BTreeMap<String, BTreeMap<String, String>> = BTreeMap::new();
        if let Ok(text) = fs::read_to_string(preferences) {
            let mut hosts = false;
            for line in text.lines() {
                if line.starts_with('[') {
                    hosts = line == "[hosts]";
                    continue;
                }
                if !hosts {
                    continue;
                }
                if let Some((key, value)) = line.split_once('=') {
                    if let Some((index, field)) = key.split_once('\\') {
                        if ["uuid", "hostname", "localaddress", "manualaddress"].contains(&field) {
                            rows.entry(index.into())
                                .or_default()
                                .insert(field.into(), value.into());
                        }
                    }
                }
            }
        }
        let devices = rows
            .into_values()
            .filter_map(|m| {
                Some(Device {
                    addresses: Vec::new(),
                    uuid: m.get("uuid")?.clone(),
                    name: m.get("hostname")?.clone(),
                    display_name: String::new(),
                    address: m
                        .get("manualaddress")
                        .filter(|v| !v.is_empty())
                        .or_else(|| m.get("localaddress"))?
                        .clone(),
                    online: false,
                    paired: false,
                    is_self: false,
                })
            })
            .collect();
        Ok(Self {
            engine,
            native_data,
            session_status: data.join("session-status.json"),
            devices: Mutex::new(devices),
            device_epoch: AtomicU64::new(0),
            child: Arc::new(Mutex::new(None)),
            retiring: Arc::new(Mutex::new(Vec::new())),
            state_agent: ureq::AgentBuilder::new()
                .try_proxy_from_env(false)
                .redirects(0)
                .timeout_connect(Duration::from_millis(100))
                .timeout_read(Duration::from_millis(400))
                .timeout_write(Duration::from_millis(400))
                .timeout(Duration::from_millis(500))
                .build(),
            pairing: Arc::new(Mutex::new(None)),
            recovery: Arc::new(Mutex::new(None)),
            token,
            port: std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
                .and_then(|s| s.local_addr())
                .map_err(|e| e.to_string())?
                .port(),
            sync: Mutex::new(crate::config_sync::ConfigSync::new(data)?),
            device_operation: Mutex::new(()),
            probe_admission: Arc::new(crate::device_probe::Admission::default()),
            local_uuid: Mutex::new(None),
            network: Arc::new(crate::network::Network::default()),
            updates: Mutex::new(updates),
            update_snapshot,
            update_cancel,
            statistics: Arc::new(Mutex::new(statistics)),
            ui_test,
            retired_launches: Arc::new(Mutex::new(Vec::new())),
            cleanup: Default::default(),
            stop_requested: AtomicBool::new(false),
            stop_submission: Mutex::new(()),
            closing: AtomicBool::new(false),
        })
    }
    fn launcher(&self) -> LaunchContext {
        LaunchContext {
            engine: self.engine.clone(),
            native_data: self.native_data.clone(),
            session_status: self.session_status.clone(),
            token: self.token.clone(),
            port: self.port,
            network: self.network.clone(),
            statistics: self.statistics.clone(),
            cleanup: self.cleanup.clone(),
        }
    }
    fn command(&self) -> Command {
        self.launcher().command()
    }
    fn local_identity(&self)->Option<String>{
        if let Some(uuid)=self.local_uuid.lock().unwrap().clone(){return Some(uuid);}
        let cancel=Arc::new(AtomicBool::new(false));
        let text=crate::device_lookup::server_info("127.0.0.1",cancel,Duration::from_millis(700)).ok()?;
        let device=parse_added_device("127.0.0.1",&text).ok()?;
        *self.local_uuid.lock().unwrap()=Some(device.uuid.clone());Some(device.uuid)
    }
    fn probe(&self, device: &Device, profile: &rhremote_core::Profile) -> Option<DeviceObservation> {
        self.probe_with_progress(device,profile,&mut |_|{})
    }
    fn probe_with_progress(&self,device:&Device,profile:&rhremote_core::Profile,publish:&mut dyn FnMut(DeviceObservation))->Option<DeviceObservation>{
        let Some(lease)=self.probe_admission.probe() else {return None};
        let cancel=&*lease.cancel;
        if self.closing.load(Ordering::SeqCst) || self.stop_requested.load(Ordering::SeqCst) || self.cleanup.pending() || !self.retired_launches.lock().unwrap().is_empty() {
            return None;
        }
        if self.pairing.lock().unwrap().is_some() {return None;}
        // Probing another host must not replace a live or pending stream's tunnel.
        if self
            .recovery
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|r| r.pending.is_some())
        {
            return None;
        }
        if self
            .child
            .lock()
            .unwrap()
            .as_mut()
            .is_some_and(|c| c.try_wait().ok().flatten().is_none())
        {
            return None;
        }
        let mut d = device.clone();
        // A bounded, file-gated delay is only recognized by the isolated UI test
        // bridge. It holds a real probe lease, so foreground cancellation still
        // follows the production cleanup/admission path.
        if self.ui_test.enabled{
            let gate=self.native_data.parent().unwrap().join("device-probe-gate");
            if gate.is_file(){
                self.statistics.lock().unwrap().mark("device_probe_test_gate_entered");let deadline=Instant::now()+Duration::from_secs(30);
                while gate.is_file()&&!cancel.load(Ordering::SeqCst)&&Instant::now()<deadline{std::thread::sleep(Duration::from_millis(15));}
                self.statistics.lock().unwrap().mark("device_probe_test_gate_exited");
            }
        }
        if cancel.load(Ordering::SeqCst){return None;}
        d.online = false;
        let mut check=Some(DeviceCheck{phase:CheckPhase::RouteSelection,outcome:CheckOutcome::Unreachable,source:CheckSource::None,has_certificate:false,elapsed_us:0});
        // Failed reachability or a generic CLI exit does not revoke a cached
        // pairing. The CLI uses the same exit for unpaired and timed-out hosts.
        let mut pairing_verified=false;
        if let Ok(response) =
            ureq::get("http://127.0.0.1:47989/serverinfo?uniqueid=0123456789ABCDEF")
                .timeout(Duration::from_millis(700))
                .call()
        {
            if let Ok(text) = response.into_string() {
                if let Ok(doc) = roxmltree::Document::parse(&text) {
                    if let Some(uuid) = doc
                        .descendants()
                        .find(|n| n.has_tag_name("uniqueid"))
                        .and_then(|n| n.text())
                    {
                        *self.local_uuid.lock().unwrap()=Some(uuid.into());
                        d.is_self = uuid.eq_ignore_ascii_case(&d.uuid);
                    }
                }
            }
        }
        if d.is_self {
            d.online = true;
            return Some(DeviceObservation{device:d,pairing_verified:true,check:None});
        }
        if cancel.load(Ordering::SeqCst) {return None;}
        let mut phase_started=Instant::now();
        let mut native_address = d.address.clone();
        if let Ok((address, _)) = self.network.resolve_excluding(&d, profile, &[], cancel) {
            native_address = address.clone();
            if address != "127.77.0.2" {
                d.address = address;
            }
            d.online = true;
            for a in self.network.discovered_cancelable(&d,cancel) {
                if d.addresses.len() < 7 && !d.addresses.contains(&a) && a != d.address {
                    d.addresses.push(a);
                }
            }
        }
        if cancel.load(Ordering::SeqCst) {self.network.release_transport();return None;}
        if d.online {
            phase_started=Instant::now();
            check=Some(DeviceCheck{phase:CheckPhase::NativeProcess,outcome:CheckOutcome::Unavailable,source:CheckSource::None,has_certificate:false,elapsed_us:0});
            let binding=match crate::probe_report::Binding::new(&self.native_data,&d.uuid){Ok(binding)=>binding,Err(_)=>return Some(DeviceObservation{device:d,pairing_verified:false,check})};
            let mut reported=false;let mut report_invalid=false;
            let metadata = self.native_data.join(format!(
                "network-{}.json",
                d.uuid
                    .chars()
                    .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
                    .collect::<String>()
            ));
            let _ = fs::write(&metadata, b"{}");
            let child = self
                .command()
                .args(["list", &native_address])
                .env("RHREMOTE_TARGET_UUID", &d.uuid)
                .env("RHREMOTE_CONNECT_ADDRESS", &native_address)
                .env("RHREMOTE_NETWORK_METADATA", &metadata)
                .env("RHREMOTE_PROBE_OPERATION", "1")
                .env("RHREMOTE_PROBE_ID",&binding.id)
                .env("RHREMOTE_PROBE_REPORT",&binding.path)
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn();
            if let Err(ref error) = child {
                self.probe_diagnostic("spawn", &format!("{}: {}", self.engine.display(), error));
            }
            if let Ok(mut child) = child {
                let end = Instant::now() + Duration::from_secs(5);
                loop {
                    if cancel.load(Ordering::SeqCst) {
                        let _=child.kill();let _=child.wait();self.network.release_transport();return None;
                    }
                    if !reported {
                        match crate::probe_report::observe(&binding,&mut d,&mut pairing_verified,&mut check){
                            Ok(true)=>{publish(DeviceObservation{device:d.clone(),pairing_verified,check:check.clone()});reported=true;},
                            Err(error) if !report_invalid=>{self.probe_diagnostic("report",&error);report_invalid=true;},
                            _=>{},
                        }
                    }
                    match child.try_wait() {
                        Ok(Some(status)) => {
                            if let Err(error)=crate::probe_report::observe(&binding,&mut d,&mut pairing_verified,&mut check){if !report_invalid{self.probe_diagnostic("report",&error);}}
                            if status.success() {
                                match child.wait_with_output() {
                                    Ok(output) => {
                                        // Older engines publish no report. The current fast path
                                        // reports serverinfo before optional metadata, not an app-list.
                                        if !reported&&!binding.path.exists(){d.paired=output.status.success();pairing_verified=d.paired;check=None;}
                                        if let Ok(bytes) = fs::read(&metadata) {
                                            if let Ok(info) =
                                                serde_json::from_slice::<Value>(&bytes)
                                            {
                                                if info["ok"] == true {
                                                    if let Some(address)=info["result"]["transport"]["address"].as_str().filter(|a|rhremote_core::network::transport_id(a).is_some()){if d.addresses.len()<7&&!d.addresses.iter().any(|a|a==address){d.addresses.push(address.into());}}
                                                }
                                            }
                                        }
                                    }
                                    Err(error) => {
                                        self.probe_diagnostic("read_output", &error.to_string())
                                    }
                                }
                            } else {
                                self.probe_diagnostic("exit", &format!("code={:?}", status.code()));
                            }
                            break;
                        }
                        Ok(None) if Instant::now() < end => {
                            std::thread::sleep(Duration::from_millis(25))
                        }
                        _ => {
                            self.probe_diagnostic("timeout", "native list exceeded 5 seconds");
                            crate::probe_report::timed_out(&mut check,phase_started.elapsed().as_micros() as u64);
                            let _ = child.kill();
                            let _ = child.wait();
                            break;
                        }
                    }
                }
            }
        }
        if cancel.load(Ordering::SeqCst) {self.network.release_transport();return None;}
        if let Some(check)=&mut check{if check.phase!=CheckPhase::ServerInfo {check.elapsed_us=phase_started.elapsed().as_micros() as u64;}}
        Some(DeviceObservation{device:d,pairing_verified,check})
    }
    fn refresh_device_rows(&self,args:&Value,publish:&mut dyn FnMut(DeviceObservation))->Result<Value,String>{
        let (before,epoch)={let devices=self.devices.lock().unwrap();(devices.clone(),self.device_epoch.load(Ordering::Acquire))};
        let target=args.get("uuid").and_then(Value::as_str);
        if target.is_some_and(|uuid|!before.iter().any(|device|device.uuid==uuid)){return Err("设备记录尚未就绪，请刷新设备列表".into());}
        let mut observed=Vec::new();let mut verified=Vec::new();let mut checks=BTreeMap::new();
        for device in before.iter().filter(|device|target.is_none_or(|uuid|device.uuid==uuid)){
            let profile=args["profiles"].get(&device.uuid).cloned().map(serde_json::from_value::<rhremote_core::Profile>).transpose().map_err(|e|e.to_string())?.unwrap_or_default();
            let Some(observation)=self.probe_with_progress(device,&profile,&mut |early|{
                let accepted={let latest=self.devices.lock().unwrap();epoch==self.device_epoch.load(Ordering::Acquire)&&latest.iter().find(|current|current.uuid==device.uuid)==Some(device)};
                if accepted{publish(early);}
            })else{continue;};
            let accepted={let mut latest=self.devices.lock().unwrap();
                if epoch!=self.device_epoch.load(Ordering::Acquire)||latest.iter().find(|current|current.uuid==device.uuid)!=Some(device){false}else{
                    apply_device_refresh(&mut latest,&before,vec![observation.device.clone()],epoch,epoch);true
                }
            };
            if accepted{if let Some(check)=&observation.check{checks.insert(observation.device.uuid.clone(),check.clone());}observed.push(observation.device.uuid.clone());if observation.pairing_verified{verified.push(observation.device.uuid.clone());}publish(observation);}
        }
        Ok(json!({"devices":*self.devices.lock().unwrap(),"observedUuids":observed,"verifiedUuids":verified,"checks":checks}))
    }
    fn probe_diagnostic(&self, stage: &str, error: &str) {
        use std::io::Write;
        let path = self.native_data.join("probe-errors.log");
        if fs::metadata(&path).is_ok_and(|m| m.len() > 1_000_000) {
            let _ = fs::write(&path, "");
        }
        if let Ok(mut file) = fs::OpenOptions::new().create(true).append(true).open(path) {
            let _ = writeln!(
                file,
                "{} stage={} {}",
                rhremote_core::now_ms(),
                stage,
                error
            );
        }
    }
    pub fn pairing_result(&self) -> Option<(u64, Device, String, bool)> {
        let mut pending=self.pairing.lock().unwrap();
        let result=pending.as_mut()?.poll(&self.session_status)?;
        let operation=pending.take()?;
        let mut device=operation.device.clone();
        if result.is_ok() {device.paired=true;}
        // No second authenticated probe: the completed PIN exchange is the evidence.
        let _=fs::write(&self.session_status,b"{\"streaming\":false,\"phase\":\"idle\",\"error\":\"\"}");
        Some((operation.attempt,device,result.err().unwrap_or_default(),self.cleanup.pending()))
    }
    fn reap_launches(&self) -> bool {
        let mut retired = self.retired_launches.lock().unwrap();
        let mut i = 0;
        while i < retired.len() {
            if retired[i].is_finished() {
                let job = retired.swap_remove(i);
                let _ = job.join();
            } else {
                i += 1;
            }
        }
        retired.is_empty()
    }
    fn queue_launch(&self, r: &mut Recovery) {
        r.attempt += 1;
        r.args["routeFailures"] = json!(r.routes.failed);
        r.args["nativeAttempt"] = json!(r.attempt);
        let context = self.launcher();
        let args = r.args.clone();
        let cancel = r.cancel.clone();
        r.pending = Some(std::thread::spawn(move || {
            let outcome = context.spawn_stream(&args, &cancel);
            if cancel.load(Ordering::SeqCst) {
                drop(outcome);
                Err("连接已取消".into())
            } else {
                outcome
            }
        }));
    }
    fn retire_child(&self, slot: &mut Option<Child>, transport_released: bool) {
        if let Some(mut child) = slot.take() {
            if child.try_wait().ok().flatten().is_some() {
                let _ = child.wait();
            } else {
                self.retiring
                    .lock()
                    .unwrap()
                    .push((child, Instant::now(), transport_released));
            }
        }
    }
    pub fn state(&self) -> Result<Value, String> {
        self.reap_launches();
        for report in self.cleanup.reap() {
            let _ = self.statistics.lock().unwrap().event("native_cleanup_end", json!({"kind":report.kind,"pid":report.pid,"forced":report.forced,"confirmed":report.confirmed,"error":report.error,"control":report.control}));
        }
        let injected = self.ui_test.enabled && self.ui_test.take_native_state_fault(self.child.lock().unwrap().as_ref().map(|child| child.id()));
        let (http_state, control_trace) = if injected {
            (None, crate::native_control::synthetic_unavailable())
        } else {
            crate::native_control::read_state(&self.state_agent, self.port, &self.token, Duration::from_millis(500))
        };
        let http_unavailable = http_state.is_none();
        let state_source = if http_state.is_some() { "http" } else { "file_or_empty" };
        let mut value = http_state
            .or_else(|| {
                fs::read(&self.session_status)
                    .ok()
                    .and_then(|b| serde_json::from_slice(&b).ok())
            })
            .unwrap_or(json!({"streaming":false}));
        let mut recovery = self.recovery.lock().unwrap();
        let mut slot = self.child.lock().unwrap();
        let mut terminal = false;
        if self.stop_requested.load(Ordering::SeqCst) {
            if let Some(mut r)=recovery.take(){r.cancel.store(true,Ordering::SeqCst);if let Some(job)=r.pending.take(){self.retired_launches.lock().unwrap().push(job);}}
        }
        if let Some(r) = recovery.as_mut() {
            if r.pending.as_ref().is_some_and(|j| j.is_finished()) {
                let result = r
                    .pending
                    .take()
                    .unwrap()
                    .join()
                    .unwrap_or_else(|_| Err("连接工作线程异常退出".into()));
                match result {
                    Ok(outcome) => {
                        r.current = outcome.network;
                        *slot = Some(outcome.child.take());
                        r.native_started = Some(Instant::now());
                        r.timeout_requested = false;
                        r.first_frame_wait = Default::default();
                        r.first_frame = None;
                        value = json!({"streaming":false,"firstFrame":false,"phase":if r.policy.armed(){"reconnecting"}else{"connecting"},"error":""});
                    }
                    Err(error) => {
                        value = json!({"streaming":false,"firstFrame":false,"phase":if r.policy.armed(){"reconnecting"}else{"error"},"error":error});
                        terminal = !r.policy.armed();
                        let _ = fs::write(&self.session_status, value.to_string());
                    }
                }
            }
            if r.pending.is_some() {
                value = json!({"streaming":false,"firstFrame":false,"phase":if r.attempt>1{"reconnecting"}else{"connecting"},"error":""});
            }
        }
        let mut alive = slot
            .as_mut()
            .is_some_and(|c| c.try_wait().ok().flatten().is_none());
        let current_pid = slot.as_ref().map(|child| child.id());
        let owned_state = alive && value["processId"].as_u64() == current_pid.map(u64::from);
        let owned_http = owned_state && !http_unavailable;
        let control_observation = json!({"request":control_trace,"port":self.port,"stateSource":state_source,
            "expectedPid":slot.as_ref().map(|child|child.id()),"childAlive":alive,
            "reportedPid":value["processId"].as_u64(),"firstFrame":value["firstFrame"].as_bool(),
            "streaming":value["streaming"].as_bool()});
        if alive {
            let _ = self.statistics.lock().unwrap().event("native_state_read", control_observation.clone());
        }
        // HTTP is read before the admission lock, and the fallback file can still
        // describe the previous process. Neither may end or arm a fresh child.
        if alive && value["processId"].as_u64()!=slot.as_ref().map(|child|u64::from(child.id())) {
            value=json!({"streaming":false,"firstFrame":false,"phase":if recovery.as_ref().is_some_and(|r|r.policy.armed()){"reconnecting"}else{"connecting"},"error":"","awaitingOwnedState":true});
        } else if alive && recovery.is_some()
            && value["phase"].as_str().is_none_or(str::is_empty)
            && value["firstFrame"] != true
            && value["error"].as_str().is_none_or(str::is_empty)
            && value["routeFatal"] != true
        {
            // The native API starts serving before its first stage is published.
            // Preserve the admitted launch and keep its initialization budget active.
            value["phase"]=json!(if recovery.as_ref().is_some_and(|r|r.policy.armed()){"reconnecting"}else{"connecting"});
            value["awaitingNativePhase"]=json!(true);
        }
        let mut released = alive
            && http_unavailable
            && value["transportReleased"] == true
            && value["phase"] == "transport_closed";
        self.retiring
            .lock()
            .unwrap()
            .retain_mut(|(child, since, _)| {
                if child.try_wait().ok().flatten().is_some() {
                    return false;
                }
                if since.elapsed() >= Duration::from_secs(15) {
                    let _ = child.kill();
                    let _ = child.wait();
                    return false;
                }
                true
            });
        if let Some(r) = recovery.as_mut() {
            let connected = owned_state && value["phase"] == "connected" && value["streaming"] == true
                && value["routeFatal"] != true && value["transportReleased"] != true
                && value["error"].as_str().is_none_or(str::is_empty) && !r.timeout_requested;
            // The private status file may predate the first render. Preserve
            // only an already observed milestone for this same live child;
            // current errors, resets and process replacements remain decisive.
            if owned_state && (!connected || (owned_http && value["firstFrame"] != true)) {
                r.first_frame = None;
            }
            if connected && value["firstFrame"] == true {
                r.first_frame = Some(FirstFrameState::observe(current_pid.unwrap(), &value, r.first_frame.as_ref()));
            } else if connected && http_unavailable {
                if let Some(first) = r.first_frame.as_ref().filter(|first| Some(first.pid) == current_pid) {
                    first.restore(&mut value);
                    let _ = self.statistics.lock().unwrap().event("native_first_frame_restored", json!({"processId":first.pid}));
                }
            }
            let rendered = alive && value["streaming"] == true && value["firstFrame"] == true;
            if value["routeFatal"] == true {
                terminal = true;
                r.timeout_requested = false;
                value["phase"] = json!("error");
                if value["error"].as_str().is_none_or(|e| e.is_empty()) {
                    value["error"] = json!("连接校验或解码失败，已停止自动回退");
                }
            }
            r.routes.healthy(rhremote_core::now_ms(), rendered);
            if rendered {
                if value["displays"].is_array() && value["selectedDisplay"].is_object() {
                    r.args["resumeSelection"] = json!({"displays":value["displays"],"selected":value["selectedDisplay"],"initialCaptureIndex":1});
                }
                for key in ["audioEnabled", "volume"] {
                    if !value[key].is_null() {
                        r.args["profile"][key] = value[key].clone();
                    }
                }
            }
            if !terminal
                && r.pending.is_none()
                && alive
                && !rendered
                && !r.first_frame.as_ref().is_some_and(|first| Some(first.pid) == current_pid)
                && !released
                && r.native_started.is_some()
                && matches!(
                    value["phase"].as_str(),
                    Some("connecting" | "starting" | "connected" | "reconnecting")
                )
            {
                let elapsed = r.native_started.unwrap().elapsed().as_millis().min(u64::MAX as u128) as u64;
                // Ignore a previous child's status file or an unrelated API process.
                let protocol_ready = value["phase"] == "connected" && value["streaming"] == true
                    && value["processId"].as_u64() == slot.as_ref().map(|child| u64::from(child.id()));
                let decision = r.first_frame_wait.observe(elapsed, protocol_ready);
                if decision == crate::recovery::FirstFrameDecision::Disconnect {
                    r.timeout_requested = true;
                    let _ = self.statistics.lock().unwrap().event(
                        "route_first_frame_timeout",
                        json!({"attempt":r.attempt,"network":r.current,"nativeElapsedMs":elapsed,"protocolReadyObserved":protocol_ready}),
                    );
                    // Bounded native request; cancellation never waits on WAN I/O.
                    if owned_http {
                        let _ = self
                            .state_agent
                            .post(&format!("http://127.0.0.1:{}/v1/disconnect", self.port))
                            .set("Authorization", &format!("Bearer {}", self.token))
                            .timeout(Duration::from_millis(500))
                            .send_json(json!({}));
                    }
                }
                if decision == crate::recovery::FirstFrameDecision::ForceExit {
                    if let Some(mut child) = slot.take() {
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                    alive = false;
                    released = false;
                }
            }
            if !terminal
                && r.pending.is_none()
                && (!alive || released)
                && !rendered
                && (value["routeRetryEligible"] == true || r.timeout_requested)
            {
                let failed = crate::recovery::FailedRoute {
                    route: r.current["route"].as_str().unwrap_or("").into(),
                    address: r.current["remoteAddress"]
                        .as_str()
                        .or_else(|| r.current["selectedAddress"].as_str())
                        .unwrap_or("")
                        .into(),
                };
                if r.routes.admit(rhremote_core::now_ms(), failed, true) {
                    let detail=if r.timeout_requested {json!("等待首帧超时")} else {value["error"].clone()};
                    let _=self.statistics.lock().unwrap().event("route_media_failed",json!({"attempt":r.attempt,"network":r.current,"error":detail,"terminationCode":value["terminationCode"]}));
                    self.retire_child(&mut slot, true);
                    self.queue_launch(r);
                    value = json!({"streaming":false,"firstFrame":false,"phase":"reconnecting","error":""});
                } else {
                    terminal = true;
                    value["phase"] = json!("error");
                    value["error"] = json!("媒体连接回退未成功，已达到本轮尝试上限");
                }
            }
            if !terminal && r.pending.is_none() {
                match r.policy.observe(
                    rhremote_core::now_ms(),
                    alive && !released,
                    rendered,
                    value["retryEligible"] == true,
                ) {
                    crate::recovery::Decision::Launch => {
                        r.args["recoveryAttempt"] = json!(r.policy.attempts);
                        if r.policy.attempts == 1 {
                            let origin = self.statistics.lock().unwrap().now();
                            r.args["connectOriginUs"] = json!(origin as u64);
                        }
                        self.retire_child(&mut slot, true);
                        self.queue_launch(r);
                        value = json!({"streaming":false,"firstFrame":false,"phase":"reconnecting","error":""});
                    }
                    crate::recovery::Decision::Waiting => {
                        value["phase"] = json!("reconnecting");
                        value["error"] = json!("");
                    }
                    crate::recovery::Decision::Exhausted => {
                        terminal = true;
                        value["phase"] = json!("error");
                        value["error"] = json!("自动恢复未成功，请检查主机后重新连接");
                    }
                    crate::recovery::Decision::Idle => {}
                }
            }
            if r.pending.is_some() {
                value["streaming"] = json!(false);
                value["firstFrame"] = json!(false);
                value["phase"] = json!(if r.attempt > 1 {
                    "reconnecting"
                } else {
                    "connecting"
                });
                value["error"] = json!("");
            }
            if !terminal
                && alive
                && !rendered
                && (value["routeRetryEligible"] == true || r.timeout_requested)
            {
                value["phase"] = json!("reconnecting");
                value["error"] = json!("");
            }
            value["reconnectAttempts"] = json!(r.policy.attempts);
            value["routeFallbackAttempts"] = json!(r.routes.failed.len());
            value["routeFailures"] = json!(r.routes.failed);
            value["nativeAttempt"] = json!(r.attempt);
        }
        if terminal {
            if let Some(mut r) = recovery.take() {
                r.cancel.store(true, Ordering::SeqCst);
                if let Some(job) = r.pending.take() {
                    self.retired_launches.lock().unwrap().push(job);
                }
            }
            // Let the engine finish its trace and transport cleanup before reusing
            // the control port. The retiring process still has a bounded lifetime.
            self.retire_child(&mut slot, released);
            alive = false;
            value["streaming"] = json!(false);
            let _ = fs::write(&self.session_status, value.to_string());
        }
        if let Some(child) = slot.as_ref() {
            value["streamProcessId"] = json!(child.id());
        }
        if !alive {
            value["streaming"] = json!(false);
            value["streamWindow"] = json!("0");
        }
        // Keep the submission and completion decisions atomic. Otherwise an
        // idle poll could clear stop_requested before the coordinator is queued.
        let stop_submission = self.stop_submission.lock().unwrap();
        let cleanup_pending = self.cleanup.pending()
            || !self.retired_launches.lock().unwrap().is_empty()
            || self.retiring.lock().unwrap().iter().any(|(_,_,released)|!released);
        if !alive
            && value["phase"] != "reconnecting"
            && recovery.as_ref().is_none_or(|r| r.pending.is_none())
            && matches!(
                value["phase"].as_str(),
                Some("connecting" | "starting" | "connected")
            )
        {
            value["phase"] = json!("error");
            value["error"] = json!("串流进程已退出，请重新连接");
        }
        // Hold the admission locks until closing the old UI trace, so a new
        // launch cannot create a trace between the idle check and this footer.
        if !alive
            && value["phase"] != "reconnecting"
            && recovery.as_ref().is_none_or(|r| r.pending.is_none())
            && self.retiring.lock().unwrap().is_empty()
            && self.retired_launches.lock().unwrap().is_empty()
            && !cleanup_pending
        {
            let _ = self.statistics.lock().unwrap().finish();
            if self.stop_requested.swap(false, Ordering::SeqCst) {
                self.network.try_release_transport();
                value["phase"] = json!("disconnected");
                value["streaming"] = json!(false);
                value["error"] = json!("");
            }
        }
        drop(stop_submission);
        drop(slot);
        drop(recovery);
        if let Some(pair)=self.pairing.lock().unwrap().as_ref() {
            value["pairAttemptId"]=json!(pair.attempt);
            value["pairingPhase"]=json!(pair.phase());
        }
        value["nativeCleanupPending"] = json!(cleanup_pending);
        if self.stop_requested.load(Ordering::SeqCst) && cleanup_pending {
            value["phase"] = json!("cancelling");
            value["streaming"] = json!(false);
            value["firstFrame"] = json!(false);
            value["error"] = json!("");
        } else if let Some(error) = self.cleanup.failure() {
            value["phase"] = json!("error");
            value["error"] = json!(error);
            value["routeFatal"] = json!(true);
        }
        value["tracing"] = self.statistics.lock().unwrap().state();
        value["nativeControl"] = control_observation;
        value["network"] = self.network.last.lock().unwrap().clone();
        if value["streaming"] == true && value["firstFrame"] == true {
            value["network"]["mediaUdp"] = json!("streaming");
        }
        Ok(value)
    }
    fn stop_owned(&self) -> Result<Value, String> {
        let _submission = self.stop_submission.lock().unwrap();
        if self.stop_requested.swap(true, Ordering::SeqCst) && (self.cleanup.pending() || self.cleanup.failure().is_none()) {
            return Ok(json!({"cancelled":true,"cleanupPending":true}));
        }
        self.probe_admission.cancel_probe();
        if let Some(pair)=self.pairing.lock().unwrap().as_ref(){pair.cancel();}
        let admission=self.probe_admission.clone();
        let (recovery,retired,pairing,child,retiring,cleanup,context,network)=(self.recovery.clone(),self.retired_launches.clone(),self.pairing.clone(),self.child.clone(),self.retiring.clone(),self.cleanup.clone(),self.cleanup_context(),self.network.clone());
        if !self.cleanup.coordinate(self.network.clone(),move||{
            if let Some(mut r)=recovery.lock().unwrap().take(){r.cancel.store(true,Ordering::SeqCst);if let Some(job)=r.pending.take(){retired.lock().unwrap().push(job);}}
            let _foreground=admission.foreground();
            if let Some(pair)=pairing.lock().unwrap().take(){pair.retire(&cleanup,network.clone());}
            if let Some(process)=child.lock().unwrap().take(){cleanup.enqueue(process,context.clone(),network.clone());}
            for (process,_,_) in retiring.lock().unwrap().drain(..){cleanup.enqueue(process,context.clone(),network.clone());}
        }) {
            self.stop_requested.store(false,Ordering::SeqCst);
            return Err("无法启动清理协调任务".into());
        }
        Ok(json!({"cancelled":true,"cleanupPending":true}))
    }
    fn cleanup_context(&self) -> crate::native_cleanup::Context {
        crate::native_cleanup::Context {port:self.port,token:self.token.clone(),status:self.session_status.clone(),limit:Duration::from_secs(20)}
    }
    fn control(&self, path: &str, args: &Value) -> Result<Value, String> {
        let response = ureq::post(&format!("http://127.0.0.1:{}/v1/{path}", self.port))
            .set("Authorization", &format!("Bearer {}", self.token))
            .timeout(Duration::from_secs(
                if ["shortcut", "display", "displays"].contains(&path) {
                    22
                } else {
                    3
                },
            ))
            .send_json(args.clone());
        match response {
            Ok(response) => response.into_json().map_err(|e| e.to_string()),
            Err(ureq::Error::Status(code, response)) => Err(format!(
                "native HTTP {code}: {}",
                response
                    .into_json::<Value>()
                    .map(|v| v.to_string())
                    .unwrap_or_default()
            )),
            Err(_) => Err("native control transport unavailable".into()),
        }
    }
    fn audio_control(&self, args: &Value) -> Result<Value, String> {
        // Bind a reply to the admitted child, including its launch instant:
        // a late acknowledgement must not update a replacement's snapshot.
        let owner = {
            let recovery = self.recovery.lock().unwrap();
            let mut child = self.child.lock().unwrap();
            match (recovery.as_ref(), child.as_mut()) {
                (Some(recovery), Some(child)) => {
                    if child.try_wait().ok().flatten().is_none() {
                        recovery.native_started.map(|started| (child.id(), started))
                    } else { None }
                }
                _ => None,
            }
        };
        let value = self.control("audio", args)?;
        if value["ok"] == true {
            let mut recovery = self.recovery.lock().unwrap();
            let mut child = self.child.lock().unwrap();
            if let (Some((pid, started)), Some(recovery), Some(child)) = (owner, recovery.as_mut(), child.as_mut()) {
                if recovery.native_started == Some(started) && child.id() == pid && child.try_wait().ok().flatten().is_none() {
                    if let Some(first) = recovery.first_frame.as_mut().filter(|first| first.pid == pid) {
                        if let Some(enabled) = value["enabled"].as_bool() {
                            first.audio_enabled = Some(enabled);
                            recovery.args["profile"]["audioEnabled"] = json!(enabled);
                        }
                        if let Some(volume) = value["volume"].as_u64().filter(|volume| *volume <= 100) {
                            first.volume = Some(volume);
                            recovery.args["profile"]["volume"] = json!(volume);
                        }
                        let _ = self.statistics.lock().unwrap().event("native_audio_ack_state", json!({"processId":pid}));
                    }
                }
            }
        }
        Ok(value)
    }
    #[cfg(windows)]
    fn test_native_window(&self, args: &Value) -> Result<Value, String> {
        use windows::Win32::{
            Foundation::{HWND, POINT, RECT},
            Graphics::Gdi::{ClientToScreen, GetMonitorInfoW, MonitorFromWindow, MONITORINFO, MONITOR_DEFAULTTONULL},
            UI::WindowsAndMessaging::*,
        };
        if !self.ui_test.enabled { return Err("界面测试接口仅在隔离测试预览中启用".into()); }
        let pid = args["processId"].as_u64().and_then(|pid| u32::try_from(pid).ok()).filter(|pid| *pid > 0).ok_or("processId required")?;
        let command = args["command"].as_str().filter(|command| ["inspect", "minimize", "restore", "restore-placement", "resize"].contains(command)).ok_or("command must be inspect / minimize / restore / restore-placement / resize")?;
        let resize=if command=="resize" {
            let width=args["width"].as_i64().and_then(|v|i32::try_from(v).ok()).filter(|v|(256..=3840).contains(v)).ok_or("width must be 256–3840 physical pixels")?;
            let height=args["height"].as_i64().and_then(|v|i32::try_from(v).ok()).filter(|v|(160..=2160).contains(v)).ok_or("height must be 160–2160 physical pixels")?;
            Some((width,height))
        }else{None};
        // Hold the admission and child locks across the nonblocking window command.
        // Callers supply a PID, never an arbitrary HWND or another process's window.
        let recovery = self.recovery.lock().unwrap();
        let mut child = self.child.lock().unwrap();
        if !child.as_mut().is_some_and(|child| child.id() == pid && child.try_wait().ok().flatten().is_none()) {
            return Err("窗口测试仅支持本次存活子进程".into());
        }
        let window = recovery.as_ref().and_then(|recovery| recovery.first_frame.as_ref()).filter(|first| first.pid == pid)
            .and_then(|first| first.stream_window.as_ref()).and_then(|window| window.parse::<usize>().ok()).filter(|window| *window > 0)
            .ok_or("窗口测试需要本次已确认首帧及窗口" )?;
        let hwnd = HWND(window as *mut _);
        unsafe {
            let mut actual_pid = 0;
            GetWindowThreadProcessId(hwnd, Some(&mut actual_pid));
            if actual_pid != pid { return Err("窗口句柄不属于本次子进程".into()); }
            let resize_queued=if let Some((width,height))=resize {
                let mut rect=RECT::default();GetWindowRect(hwnd,&mut rect).map_err(|_|"无法读取本次窗口区域")?;
                let mut monitor=MONITORINFO{cbSize:std::mem::size_of::<MONITORINFO>()as u32,..Default::default()};
                if !GetMonitorInfoW(MonitorFromWindow(hwnd,MONITOR_DEFAULTTONULL),&mut monitor).as_bool()
                    ||rect.left<monitor.rcMonitor.left||rect.top<monitor.rcMonitor.top
                    ||rect.left+width>monitor.rcMonitor.right||rect.top+height>monitor.rcMonitor.bottom {
                    return Err("测试尺寸必须留在本次窗口所在显示器内".into());
                }
                SetWindowPos(hwnd,None,0,0,width,height,SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE|SWP_ASYNCWINDOWPOS).is_ok()
            }else{false};
            let queued = match command {
                // The isolated preview must not activate the next top-level
                // window while minimizing a background stream.
                "minimize" => ShowWindowAsync(hwnd, SW_SHOWMINNOACTIVE).as_bool(),
                "restore" => ShowWindowAsync(hwnd, SW_SHOWNOACTIVATE).as_bool(),
                "restore-placement" => {
                    let mut placement=WINDOWPLACEMENT{length:std::mem::size_of::<WINDOWPLACEMENT>()as u32,..Default::default()};
                    GetWindowPlacement(hwnd,&mut placement).map_err(|_|"无法读取本次窗口位置")?;
                    placement.showCmd=SW_SHOWNOACTIVATE.0 as u32;
                    placement.flags|=WPF_ASYNCWINDOWPLACEMENT;
                    SetWindowPlacement(hwnd,&placement).is_ok()
                }
                "resize" => resize_queued,
                _ => false,
            };
            if command != "inspect" && !queued { return Err("本次窗口命令未被系统接受".into()); }
            let (mut rect, mut client, mut origin) = (RECT::default(), RECT::default(), POINT::default());
            GetWindowRect(hwnd, &mut rect).map_err(|_| "无法读取本次窗口区域")?;
            let client_available = GetClientRect(hwnd, &mut client).is_ok() && ClientToScreen(hwnd, &mut origin).as_bool();
            let mut monitor = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
            let monitor_available = GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONULL), &mut monitor).as_bool();
            let thread_state=crate::native_window::read_thread_state(hwnd,pid)?;
            Ok(json!({"processId":pid,"streamWindow":window.to_string(),"command":command,"queued":queued,
                "minimized":IsIconic(hwnd).as_bool(),"visible":IsWindowVisible(hwnd).as_bool(),"foreground":GetForegroundWindow()==hwnd,"threadState":thread_state,
                "windowRect":{"x":rect.left,"y":rect.top,"width":rect.right-rect.left,"height":rect.bottom-rect.top},
                "clientGeometryAvailable":client_available,"clientRect":{"x":origin.x,"y":origin.y,"width":client.right-client.left,"height":client.bottom-client.top},
                "monitorAvailable":monitor_available,"monitorBounds":{"x":monitor.rcMonitor.left,"y":monitor.rcMonitor.top,"width":monitor.rcMonitor.right-monitor.rcMonitor.left,"height":monitor.rcMonitor.bottom-monitor.rcMonitor.top}}))
        }
    }
}
impl Platform for NativePlatform {
    fn prepare_diagnostic(&self,device:&Device,profile:&rhremote_core::Profile)->Option<Result<Box<dyn rhremote_core::DiagnosticTask>,String>>{
        Some(diagnostic::Task::new(self,device,profile).map(|task|Box::new(task) as Box<dyn rhremote_core::DiagnosticTask>))
    }
    fn prepare_windows(&self,device:&Device,profile:&rhremote_core::Profile)->Option<Result<Box<dyn rhremote_core::DiagnosticTask>,String>>{
        Some(diagnostic::Task::windows(self,device,profile).map(|task|Box::new(task) as Box<dyn rhremote_core::DiagnosticTask>))
    }
    fn observe_devices(&self,args:&Value,publish:&mut dyn FnMut(DeviceObservation))->Result<Value,String>{
        let _guard=self.device_operation.lock().unwrap();self.refresh_device_rows(args,publish)
    }
    fn begin_update(&self,operation:u64,action:&str,cancellable:bool)->Result<(),String>{self.update_cancel.begin(operation,action,cancellable)}
    fn cancel_update(&self,operation:u64)->Result<(),String>{self.update_cancel.cancel(operation)}
    fn sync_applied(&self,shared:&rhremote_core::sync_model::SharedConfig,preferences:&std::path::Path)->Result<Option<Value>,String>{self.sync.lock().unwrap().applied(shared,preferences).map(Some)}
    fn probe_device(&self,address:&str,cancel:Arc<AtomicBool>)->Result<Device,String>{
        let body=crate::device_lookup::server_info(address,cancel.clone(),Duration::from_secs(3))?;
        if cancel.load(Ordering::SeqCst){return Err("已取消添加".into());}
        let mut device=parse_added_device(address,&body)?;
        let own_uuid=self.devices.lock().unwrap().iter().find(|d|d.is_self).map(|d|d.uuid.clone());
        let own_uuid=own_uuid.or_else(||crate::device_lookup::server_info("127.0.0.1",cancel.clone(),Duration::from_millis(700)).ok().and_then(|text|parse_added_device("127.0.0.1",&text).ok()).map(|d|d.uuid));
        device.is_self=own_uuid.is_some_and(|id|id.eq_ignore_ascii_case(&device.uuid));
        if cancel.load(Ordering::SeqCst){return Err("已取消添加".into());}Ok(device)
    }
    fn added_device(&self,device:&Device){let mut devices=self.devices.lock().unwrap();if let Some(existing)=devices.iter_mut().find(|d|d.uuid.eq_ignore_ascii_case(&device.uuid)){*existing=device.clone();}else{devices.push(device.clone());}self.device_epoch.fetch_add(1,Ordering::Release);}
    fn update_state(&self)->Option<Value>{Some(self.update_snapshot.lock().unwrap().clone())}
    fn connection_requested(&self, timing: Option<rhremote_core::ActionTiming>) {
        self.statistics.lock().unwrap().connection_requested(timing);
    }
    fn execute(&self, action: &str, args: &Value) -> Result<Value, String> {
        // Cancel and retire any idle list probe before acquiring session/device
        // locks. The lease remains held until a pending session owns the tunnel.
        let _foreground=(matches!(action,"device.connect") || action=="devices.restore"&&args["probe"]!=true).then(||self.probe_admission.foreground());
        let _device_guard = if action.starts_with("devices.")
        {
            Some(self.device_operation.lock().unwrap())
        } else {
            None
        };
        match action {
            #[cfg(windows)]
            "view.ui.native-window" => self.test_native_window(args),
            "view.ui.native-state-fault" => {
                if !self.ui_test.enabled { return Err("界面测试接口仅在隔离测试预览中启用".into()); }
                let pid = args["processId"].as_u64().and_then(|pid| u32::try_from(pid).ok()).filter(|pid| *pid > 0).ok_or("processId required")?;
                let polls = args["polls"].as_u64().and_then(|polls| u8::try_from(polls).ok()).filter(|polls| (1..=8).contains(polls)).ok_or("polls must be 1–8")?;
                let recovery = self.recovery.lock().unwrap();
                let mut child = self.child.lock().unwrap();
                if !child.as_mut().is_some_and(|child| child.id() == pid && child.try_wait().ok().flatten().is_none())
                    || !recovery.as_ref().is_some_and(|recovery| recovery.first_frame.as_ref().is_some_and(|first| first.pid == pid)) {
                    return Err("测试故障仅支持已显示首帧的本次进程".into());
                }
                self.ui_test.fail_native_state(pid, polls)
            }
            id if id.starts_with("view.ui.")=>self.ui_test.execute(id,args),
            "engine.close" => {
                self.closing.store(true,Ordering::SeqCst);
                let result=self.stop_owned();
                if result.is_err(){self.closing.store(false,Ordering::SeqCst);}
                result
            },
            "network.state" => Ok(self.network.last.lock().unwrap().clone()),
            "device.network.diagnose" => Err("诊断需通过控制器任务接口启动".into()),
            "statistics.state" => Ok(self.statistics.lock().unwrap().state()),
            "statistics.configure" => {
                if self.closing.load(Ordering::SeqCst) || self.stop_requested.load(Ordering::SeqCst) || self.cleanup.pending(){return Err("disconnect before changing test mode".into());}
                if self.recovery.lock().unwrap().as_ref().is_some_and(|r|r.pending.is_some()){return Err("disconnect before changing test mode".into());}
                let mut child = self.child.lock().unwrap();
                if child
                    .as_mut()
                    .is_some_and(|c| c.try_wait().ok().flatten().is_none())
                {
                    return Err("disconnect before changing test mode".into());
                }
                let enabled = args["enabled"].as_bool().ok_or("enabled must be boolean")?;
                self.statistics.lock().unwrap().configure(enabled)
            }
            "updates.source.set" => self.updates.lock().unwrap().set_source(self.native_data.parent().ok_or("应用配置目录不可用")?,args["source"].as_str().ok_or("请选择更新源")?),
            id if id.starts_with("updates.") => self.updates.lock().unwrap().execute(id),
            "sync.state" => Ok(self.sync.lock().unwrap().state()),
            "sync.configure" => self.sync.lock().unwrap().configure(args),
            "sync.clear" => self.sync.lock().unwrap().clear(),
            "sync.run" | "sync.resolve" => {
                let local =
                    serde_json::from_value(args["shared"].clone()).map_err(|e| e.to_string())?;
                let resolution = if action == "sync.run" {
                    rhremote_core::sync_model::Resolution::Review
                } else {
                    match args["resolution"].as_str() {
                        Some("local") => rhremote_core::sync_model::Resolution::Local,
                        Some("remote") => rhremote_core::sync_model::Resolution::Remote,
                        _ => return Err("choose local or remote resolution".into()),
                    }
                };
                let review=match args.get("reviewId"){None=>None,Some(value)=>Some(value.as_str().ok_or("reviewId must be a string")?)};
                self.sync.lock().unwrap().run_reviewed(local, resolution, review)
            }
            "devices.restore" => {
                let rows: Vec<Device> =
                    serde_json::from_value(args["devices"].clone()).map_err(|e| e.to_string())?;
                let mut old = if args["replace"]==true {Vec::new()}else{self.devices.lock().unwrap().clone()};
                for d in rows {
                    old.retain(|o| !o.uuid.eq_ignore_ascii_case(&d.uuid));
                    old.push(d);
                }
                // Save/sync supplies the controller's existing observations; it
                // must not perform another full network scan on the action queue.
                // Bootstrap still explicitly requests its old authenticated scan.
                let next: Vec<_> = if args["probe"]==true {old.iter().map(|d| self.probe(d,&rhremote_core::Profile::default()).map(|observation|observation.device).unwrap_or_else(||d.clone())).collect()} else {
                    // Shared configuration deliberately has no isSelf fact. Reapply
                    // this machine's observed identity before returning it to Core.
                    if args["localOnly"]!=true{if let Some(uuid)=self.local_identity(){for row in &mut old{row.is_self=uuid.eq_ignore_ascii_case(&row.uuid);}}}
                    old
                };
                *self.devices.lock().unwrap() = next.clone();
                self.device_epoch.fetch_add(1,Ordering::Release);
                Ok(json!({"devices":next}))
            }
            "devices.refresh" => {
                self.refresh_device_rows(args,&mut |_|{})
            }
            "device.connect" => {
                if self.pairing.lock().unwrap().is_some() {return Err("配对尚未结束".into());}
                if let Some(failure)=args["testInitialFailure"].as_str(){
                    if std::env::var("RHREMOTE_TEST_MODE").as_deref()!=Ok("1")||!matches!(failure,"network"|"identity"|"decoder"){return Err("首帧故障注入仅允许在 Windows 测试模式使用".into());}
                }
                let mut recovery = self.recovery.lock().unwrap();
                let mut slot = self.child.lock().unwrap();
                if self.pairing.lock().unwrap().is_some() {return Err("配对尚未结束".into());}
                if let Some(child) = slot.as_mut() {
                    if child.try_wait().map_err(|e| e.to_string())?.is_none() {
                        return Err("native session already running".into());
                    }
                }
                if recovery.as_ref().is_some_and(|r|r.pending.is_some()){return Err("连接已在进行".into());}
                if self.closing.load(Ordering::SeqCst) || !self.reap_launches() || self.cleanup.pending() || self.stop_requested.load(Ordering::SeqCst){return Err("上一连接正在结束，请稍后重试".into());}
                if self.retiring.lock().unwrap().iter().any(|(_,_,released)|!released){return Err("上一会话正在退出，请稍后重试".into());}
                self.cleanup.clear_failure();
                let mut launch_args=args.clone();launch_args["connectOriginUs"]=json!(self.statistics.lock().unwrap().request_origin() as u64);
                let mut r=Recovery::new(launch_args);self.queue_launch(&mut r);*recovery=Some(r);
                Ok(json!({"accepted":true}))
            }
            "device.pair" => {
                // Match state/connect lock order. Shutdown holds stop_submission
                // while signalling a pair; never wait for recovery with pair held.
                let recovery=self.recovery.lock().unwrap();
                let mut slot=self.child.lock().unwrap();
                let mut pending=self.pairing.lock().unwrap();
                if self.closing.load(Ordering::SeqCst) || self.stop_requested.load(Ordering::SeqCst) || self.cleanup.pending() {return Err("上一连接正在结束，请稍后重试".into());}
                if pending.is_some() || recovery.is_some() || slot.as_mut().is_some_and(|c|c.try_wait().ok().flatten().is_none()) {return Err("native operation is already running".into());}
                let regression=crate::pairing::pending_regression(args,std::env::var("RHREMOTE_TEST_MODE").as_deref()==Ok("1"))?;
                let attempt=args["attemptId"].as_u64().ok_or("attempt ID required")?;
                let device:Device=serde_json::from_value(args["device"].clone()).map_err(|e|e.to_string())?;
                let profile:rhremote_core::Profile=serde_json::from_value(args["profile"].clone()).map_err(|e|e.to_string())?;
                let pin=args["pin"].as_str().filter(|pin|pin.len()==4&&pin.bytes().all(|b|b.is_ascii_digit())).ok_or("PIN must contain four digits")?.to_owned();
                self.cleanup.clear_failure();
                *pending=Some(PendingPair::start(self.launcher(),self.probe_admission.clone(),attempt,device,profile,pin,regression)?);
                Ok(json!({"accepted":true,"phase":"preparing"}))
            }
            "pair.cancel" => {
                let mut pending=self.pairing.lock().unwrap();
                let Some(operation)=pending.take() else {return Ok(json!({"cancelled":true,"cleanupPending":self.cleanup.pending()}));};
                let pair_id=operation.cancellation_id();
                operation.cancel();
                if !operation.retire(&self.cleanup,self.network.clone()) {return Err("无法启动配对清理任务".into());}
                // A stale cancellation never touches an unrelated stream child.
                let _=fs::write(&self.session_status,b"{\"streaming\":false,\"phase\":\"cancelled\",\"error\":\"\"}");
                Ok(json!({"cancelled":true,"pairId":pair_id,"cleanupPending":self.cleanup.pending()}))
            }
            "stream.disconnect" => {
                self.stop_owned()
            }
            "input.gesture" => self.control("input", args),
            "stream.test.interrupt" => self.control("test-interrupt", &json!({})),
            "stream.displays.refresh" => self.control("displays", &json!({})),
            "stream.display.select" => self.control("display", args),
            "stream.shortcut" => self.control("shortcut", args),
            "stream.window.move" => self.control("shortcut", &json!({"shortcut": if args["direction"] == "left" {"win-shift-left"} else {"win-shift-right"}})),
            "stream.stats.toggle" => self.control("stats-toggle", &json!({})),
            "stream.audio.toggle" => {
                let state = self.state()?;
                self.audio_control(&json!({"enabled":state["audioEnabled"]!=true}))
            }
            "stream.audio.volume" => {
                let volume = args["volume"]
                    .as_u64()
                    .filter(|v| *v <= 100)
                    .ok_or("volume must be 0..100")?;
                self.audio_control(&json!({"volume":volume}))
            }
            _ => Err(format!(
                "action {action} is not supported by the Windows native adapter"
            )),
        }
    }
}

impl LaunchContext {
    fn command(&self) -> Command {
        let mut command = Command::new(&self.engine);
        let (offset, endpoint_id) = self.network.native_environment();
        command
            .env("RHREMOTE_TUNNEL_PORT_OFFSET", offset.to_string())
            .env("RHREMOTE_TUNNEL_CLIENT_ID", endpoint_id);
        command.env("RHREMOTE_OWNER_PID", std::process::id().to_string());
        command
            .env_remove("RHREMOTE_CONTROL_PORT")
            .env_remove("RHREMOTE_CONTROL_TOKEN")
            .env_remove("RHREMOTE_TARGET_UUID");
        command.env_remove("RHREMOTE_PAIR_ID").env_remove("RHREMOTE_PAIR_CANCEL_FILE");
        command.env_remove("RHREMOTE_PROBE_OPERATION").env_remove("RHREMOTE_PROBE_ID").env_remove("RHREMOTE_PROBE_REPORT").env_remove("RHREMOTE_NETWORK_METADATA");
        command.current_dir(&self.native_data);
        command
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        #[cfg(windows)]
        command.creation_flags(0x08000000);
        command
    }
    fn spawn_stream(&self, args: &Value, cancel: &AtomicBool) -> Result<LaunchOutcome, String> {
        // A native engine has fresh UDP ports and ENet state. A previous engine's
        // port map must not be reused as a new Sunshine media session.
        self.network.release_transport();
        let p = &args["profile"];
        let device: Device =
            serde_json::from_value(args["device"].clone()).map_err(|e| e.to_string())?;
        let profile: rhremote_core::Profile =
            serde_json::from_value(p.clone()).map_err(|e| e.to_string())?;
        let trace = self
            .statistics
            .lock()
            .unwrap()
            .session_at(p, args["connectOriginUs"].as_u64().map(u128::from))?;
        self.statistics.lock().unwrap().event(
            "network_resolve_begin",
            json!({"routeOrder":profile.route_order}),
        )?;
        let failed: Vec<crate::recovery::FailedRoute> = serde_json::from_value(
            args["routeFailures"]
                .as_array()
                .cloned()
                .map(Value::Array)
                .unwrap_or_else(|| json!([])),
        )
        .map_err(|e| e.to_string())?;
        let selected = self
            .network
            .resolve_excluding(&device, &profile, &failed, cancel);
        self.statistics.lock().unwrap().event(
            if selected.is_ok() {
                "network_resolve_end"
            } else {
                "network_resolve_failed"
            },
            self.network.last.lock().unwrap().clone(),
        )?;
        let (resolved, policy) = selected?;
        if cancel.load(Ordering::SeqCst) {
            return Err("连接已取消".into());
        }
        let network = self.network.last.lock().unwrap().clone();
        fs::write(
            &self.session_status,
            b"{\"streaming\":false,\"phase\":\"connecting\",\"error\":\"\"}",
        )
        .map_err(|e| e.to_string())?;
        let address = resolved.as_str();
        let resolution = format!("{}x{}", p["width"], p["height"]);
        let fps = p["fps"].to_string();
        let rate = (policy.bitrate_mbps * 1000).to_string();
        let mut command = self.command();
        command.env_remove("RHREMOTE_TEST_INITIAL_FAILURE");
        if args["nativeAttempt"] == 1 && std::env::var("RHREMOTE_TEST_MODE").as_deref() == Ok("1") {
            if let Some(failure) = args["testInitialFailure"].as_str() {
                command.env("RHREMOTE_TEST_INITIAL_FAILURE", failure);
            }
        }
        command.env(
            "RHREMOTE_TUNNEL_PORT_OFFSET",
            policy.port_offset.to_string(),
        );
        if !policy.client_endpoint_id.is_empty() {
            command.env("RHREMOTE_TUNNEL_CLIENT_ID", &policy.client_endpoint_id);
        }
        self.statistics.lock().unwrap().event(
            "native_spawn_begin",
            json!({"route":self.network.last.lock().unwrap()["route"]}),
        )?;
        command.env_remove("RHREMOTE_STATISTICS_FILE");
        if let Some(path) = trace {
            command.env("RHREMOTE_STATISTICS_FILE", path);
        }
        let child = command
            .args([
                "stream",
                address,
                "Desktop",
                "--resolution",
                &resolution,
                "--fps",
                &fps,
                "--bitrate",
                &rate,
                "--display-mode",
                p["windowMode"].as_str().unwrap_or("windowed"),
                "--video-codec",
                p["codec"].as_str().unwrap_or("HEVC"),
                "--video-decoder",
                p["decoder"].as_str().unwrap_or("hardware"),
                if p["absoluteMouse"] == false {
                    "--no-absolute-mouse"
                } else {
                    "--absolute-mouse"
                },
                if p["framePacing"] == false {
                    "--no-frame-pacing"
                } else {
                    "--frame-pacing"
                },
                if p["vsync"] == false {
                    "--no-vsync"
                } else {
                    "--vsync"
                },
                if p["playHostAudio"] == false {
                    "--no-audio-on-host"
                } else {
                    "--audio-on-host"
                },
                "--audio-config",
                p["audioConfig"].as_str().unwrap_or("stereo"),
                "--capture-system-keys",
                p["captureSysKeys"].as_str().unwrap_or("fullscreen"),
                if p["reverseScroll"] == true {
                    "--reverse-scroll-direction"
                } else {
                    "--no-reverse-scroll-direction"
                },
                if p["muteOnFocusLoss"] == true {
                    "--mute-on-focus-loss"
                } else {
                    "--no-mute-on-focus-loss"
                },
                "--no-quit-after",
                if p["hdr"] == true {
                    "--hdr"
                } else {
                    "--no-hdr"
                },
                if p["swapButtons"] == true {
                    "--mouse-buttons-swap"
                } else {
                    "--no-mouse-buttons-swap"
                },
                if p["statistics"] == true {
                    "--performance-overlay"
                } else {
                    "--no-performance-overlay"
                },
                if p["trackpad"] == true {
                    "--touchscreen-trackpad"
                } else {
                    "--no-touchscreen-trackpad"
                },
            ])
            .args(packet_arguments(policy.packet_size))
            .env(
                "RHREMOTE_PACKET_AUTO",
                if policy.packet_size == 0 { "1" } else { "0" },
            )
            .env("RHREMOTE_NETWORK_MODE", &policy.network)
            .env("RHREMOTE_CONNECT_ADDRESS", address)
            .env("RHREMOTE_CONTROL_PORT", self.port.to_string())
            .env(
                "RHREMOTE_TARGET_UUID",
                args["device"]["uuid"]
                    .as_str()
                    .ok_or("device UUID required")?,
            )
            .env("RHREMOTE_CONTROL_TOKEN", &self.token)
            .env("RHREMOTE_SESSION_STATUS", &self.session_status)
            .env(
                "RHREMOTE_RESUME_SELECTION",
                if args["recoveryAttempt"] == 1 {
                    args["resumeSelection"].to_string()
                } else {
                    String::new()
                },
            )
            .env(
                "RHREMOTE_DISPLAY_MODE",
                if args["window"].is_object(){"window"}else{p["displayMode"].as_str().unwrap_or("auto")},
            )
            .env("RHREMOTE_WINDOW_SELECTION",if args["window"].is_object(){args["window"].to_string()}else{String::new()})
            .env(
                "RHREMOTE_AUDIO_ENABLED",
                if p["audioEnabled"] == true { "1" } else { "0" },
            )
            .env(
                "RHREMOTE_GAMEPAD_ENABLED",
                if p["gamepadEnabled"].as_bool().unwrap_or(false) {
                    "1"
                } else {
                    "0"
                },
            )
            .env(
                "RHREMOTE_AUDIO_VOLUME",
                p["volume"].as_u64().unwrap_or(100).to_string(),
            )
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(LaunchOutcome {
            child: OwnedChild {child:Some(child),cleanup:self.cleanup.clone(),context:crate::native_cleanup::Context {port:self.port,token:self.token.clone(),status:self.session_status.clone(),limit:Duration::from_secs(20)},network:self.network.clone()},
            network,
        })
    }
}

#[cfg(test)]
mod add_device_tests{
    use super::*;
    #[test]
    fn synced_local_identity_is_disabled_even_when_shared_rows_have_no_local_flags(){
        let root=std::env::temp_dir().join(format!("rhremote-restore-self-{}",rhremote_core::random_token().unwrap()));
        let platform=Arc::new(NativePlatform::new(PathBuf::from("unused-local-identity.exe"),"fixture-token".into(),root.clone()).unwrap());
        *platform.local_uuid.lock().unwrap()=Some("host-1".into());
        let local=device();let mut remote=device();remote.uuid="different-host".into();remote.is_self=true;
        let result=platform.execute("devices.restore",&json!({"devices":[local,remote]})).unwrap();
        assert_eq!(result["devices"][0]["isSelf"],true,"a synchronized local record became connectable");
        assert_eq!(result["devices"][1]["isSelf"],false,"another host inherited a local-only flag");
        let controller=rhremote_core::Controller::new("test",root.join("test-preferences.json"),platform.clone()).unwrap();
        controller.replace_devices(serde_json::from_value(result["devices"].clone()).unwrap());
        let connect=controller.dispatch("device.connect",json!({"uuid":"host-1"}));assert_eq!(connect["ok"],false);assert!(connect["error"].as_str().unwrap().contains("local device"));
        assert_eq!(controller.dispatch("device.settings.open",json!({"uuid":"host-1"}))["ok"],true);assert_eq!(controller.state()["devices"][0]["isSelf"],true);
        drop(controller);drop(platform);fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn restoring_saved_configuration_preserves_observed_identity_without_another_network_scan(){
        let root=std::env::temp_dir().join(format!("rhremote-restore-local-{}",rhremote_core::random_token().unwrap()));
        let platform=NativePlatform::new(PathBuf::from("unused-local-restore.exe"),"fixture-token".into(),root.clone()).unwrap();
        *platform.local_uuid.lock().unwrap()=Some("host-1".into());
        let mut observed=device();observed.paired=true;observed.is_self=true;observed.online=true;
        let result=platform.execute("devices.restore",&json!({"devices":[observed]})).unwrap();
        assert_eq!(result["devices"],json!([observed]));assert_eq!(*platform.devices.lock().unwrap(),vec![observed]);assert_eq!(platform.device_epoch.load(Ordering::Acquire),1);
        drop(platform);fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn shared_import_replaces_cards_without_erasing_native_identity_or_restoring_removed_rows(){
        let root=std::env::temp_dir().join(format!("rhremote-import-cache-{}",rhremote_core::random_token().unwrap()));
        let platform=NativePlatform::new(PathBuf::from("unused-import.exe"),"fixture-token".into(),root.clone()).unwrap();
        *platform.local_uuid.lock().unwrap()=Some("host-1".into());
        let local=device();let mut removed=device();removed.uuid="removed".into();
        platform.execute("devices.restore",&json!({"devices":[local.clone(),removed],"localOnly":true})).unwrap();
        let identity=platform.native_data.join("RHRemote/RHRemote.ini");fs::create_dir_all(identity.parent().unwrap()).unwrap();fs::write(&identity,b"[General]\nfixture=test-owned identity sentinel\n").unwrap();
        let result=platform.execute("devices.restore",&json!({"devices":[local],"replace":true})).unwrap();
        assert_eq!(result["devices"].as_array().unwrap().len(),1);assert_eq!(result["devices"][0]["isSelf"],true);
        assert_eq!(platform.devices.lock().unwrap().len(),1);assert_eq!(fs::read(&identity).unwrap(),b"[General]\nfixture=test-owned identity sentinel\n");
        drop(platform);fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn native_pair_admission_and_cancel_do_not_wait_for_an_old_probe() {
        let root=std::env::temp_dir().join(format!("rhremote-probe-preempt-{}",rhremote_core::random_token().unwrap()));
        let platform=Arc::new(NativePlatform::new(PathBuf::from("must-not-spawn-pair.exe"),"fixture-token".into(),root.clone()).unwrap());
        let probe=platform.probe_admission.probe().unwrap();
        let before=Instant::now();
        let result=platform.execute("device.pair",&json!({"attemptId":1,"device":device(),"profile":rhremote_core::Profile::default(),"pin":"1234"})).unwrap();
        assert_eq!(result["accepted"],true);assert!(before.elapsed()<Duration::from_millis(500));
        let deadline=Instant::now()+Duration::from_secs(2);while !probe.cancel.load(Ordering::SeqCst)&&Instant::now()<deadline{std::thread::yield_now();}
        assert!(probe.cancel.load(Ordering::SeqCst));assert!(platform.pairing.lock().unwrap().is_some());assert!(platform.child.lock().unwrap().is_none());
        let before=Instant::now();let cancelled=platform.execute("pair.cancel",&json!({})).unwrap();
        assert_eq!(cancelled["cancelled"],true);assert!(before.elapsed()<Duration::from_millis(500));
        let deadline=Instant::now()+Duration::from_secs(2);while platform.cleanup.pending()&&Instant::now()<deadline{platform.cleanup.reap();std::thread::sleep(Duration::from_millis(5));}
        assert!(!platform.cleanup.pending(),"cancel waited for the old probe instead of retiring only its own worker");
        assert!(platform.cleanup.failure().is_none());assert!(platform.pairing.lock().unwrap().is_none());
        assert!(platform.child.lock().unwrap().is_none());assert_eq!(serde_json::from_slice::<Value>(&fs::read(&platform.session_status).unwrap()).unwrap()["phase"],"cancelled");
        drop(probe);drop(platform);fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn closing_during_pair_preparation_signals_cancel_before_waiting_for_probe_cleanup(){
        let root=std::env::temp_dir().join(format!("rhremote-close-preparation-{}",rhremote_core::random_token().unwrap()));
        let platform=NativePlatform::new(PathBuf::from("must-not-spawn-pair.exe"),"fixture-token".into(),root.clone()).unwrap();
        let probe=platform.probe_admission.probe().unwrap();
        platform.execute("device.pair",&json!({"attemptId":1,"device":device(),"profile":rhremote_core::Profile::default(),"pin":"1234"})).unwrap();
        let before=Instant::now();assert_eq!(platform.execute("engine.close",&json!({})).unwrap()["cleanupPending"],true);
        assert!(before.elapsed()<Duration::from_millis(500),"close waited synchronously for a preparation transport lease");
        assert!(probe.cancel.load(Ordering::SeqCst));drop(probe);
        let deadline=Instant::now()+Duration::from_secs(3);while platform.cleanup.pending()&&Instant::now()<deadline{platform.cleanup.reap();std::thread::sleep(Duration::from_millis(5));}
        assert!(!platform.cleanup.pending());assert!(platform.cleanup.failure().is_none());
        assert!(platform.pairing.lock().unwrap().is_none());assert!(platform.child.lock().unwrap().is_none());
        drop(platform);fs::remove_dir_all(root).unwrap();
    }
    #[cfg(windows)]
    #[test]
    fn stale_pair_cancel_does_not_take_or_terminate_a_stream_child() {
        let root=std::env::temp_dir().join(format!("rhremote-pair-stale-cancel-{}",rhremote_core::random_token().unwrap()));
        let platform=NativePlatform::new(PathBuf::from("unused.exe"),"fixture-token".into(),root.clone()).unwrap();
        let child=Command::new("pwsh.exe").args(["-NoProfile","-NonInteractive","-Command","Start-Sleep -Seconds 30"]).creation_flags(0x08000000).spawn().unwrap();
        let pid=child.id();*platform.child.lock().unwrap()=Some(child);
        let response=platform.execute("pair.cancel",&json!({})).unwrap();assert_eq!(response["cancelled"],true);
        let mut slot=platform.child.lock().unwrap();
        assert_eq!(slot.as_ref().map(|child|child.id()),Some(pid));
        let child=slot.as_mut().unwrap();assert!(child.try_wait().unwrap().is_none(),"stale pair cancellation killed the stream");
        child.kill().unwrap();child.wait().unwrap();slot.take();drop(slot);drop(platform);fs::remove_dir_all(root).unwrap();
    }
    fn device()->Device{parse_added_device("192.168.0.104","<root status_code=\"200\"><uniqueid>host-1</uniqueid><hostname>PC</hostname></root>").unwrap()}
    #[test]fn discovery_does_not_claim_pairing_from_public_metadata(){let d=parse_added_device("100.73.139.109","<root status_code=\"200\"><uniqueid>host-1</uniqueid><hostname>PC</hostname><PairStatus>1</PairStatus></root>").unwrap();assert!(d.online);assert!(!d.paired);assert!(!d.is_self);assert_eq!(d.address,"100.73.139.109");}
    #[test]fn malformed_or_rejected_server_info_cannot_be_saved(){for text in ["not XML","<root status_code=\"403\"/>","<root status_code=\"200\"><hostname>PC</hostname></root>"]{assert!(parse_added_device("192.168.0.104",text).is_err());}}
    #[test]fn poll_applies_unchanged_records_but_keeps_concurrent_additions_and_edits(){let original=device();let mut offline=original.clone();offline.online=false;let mut added=original.clone();added.uuid="host-2".into();let mut latest=vec![original.clone(),added.clone()];apply_device_refresh(&mut latest,std::slice::from_ref(&original),vec![offline.clone()],0,0);assert!(!latest[0].online);assert_eq!(latest[1],added);let mut edited=original.clone();edited.addresses.push("100.73.139.109".into());let mut latest=vec![edited.clone()];apply_device_refresh(&mut latest,std::slice::from_ref(&original),vec![offline.clone()],0,0);assert_eq!(latest[0],edited);let mut latest=vec![original.clone()];apply_device_refresh(&mut latest,std::slice::from_ref(&original),vec![offline],0,1);assert_eq!(latest[0],original);}
}
