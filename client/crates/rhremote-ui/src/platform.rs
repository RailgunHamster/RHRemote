use rhremote_core::{Device, Platform};
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
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

struct PendingPair {
    attempt: u64,
    device: Device,
    cancellation: crate::pairing::Cancellation,
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
    // These older recovery fixtures use sleeping shells, not a native API.
    // Explicitly finish only those fixtures; real graceful teardown is tested
    // with an authenticated control server in native_cleanup's process tests.
    fn stop_fixture(p: &NativePlatform) {
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
        let worker = std::thread::spawn(move || {
            server.recv().unwrap().respond(tiny_http::Response::from_string(
                r#"{"streaming":false,"firstFrame":false,"routeFatal":true,"phase":"error","error":"decoder failed"}"#,
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
        let worker = std::thread::spawn(move || {
            server.recv().unwrap().respond(tiny_http::Response::from_string(
                r#"{"streaming":false,"firstFrame":false,"routeRetryEligible":true,"phase":"error","error":"network failed"}"#,
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
        let worker = std::thread::spawn(move || {
            let request = server.recv().unwrap();
            request
                .respond(tiny_http::Response::from_string(
                    r#"{"streaming":true,"firstFrame":true,"retryEligible":true}"#,
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
        *p.child.lock().unwrap() = Some(child);
        *p.recovery.lock().unwrap() = Some(Recovery::new(json!({"profile":{}})));
        p.state().unwrap();
        worker.join().unwrap();
        fs::write(
            &p.session_status,
            br#"{"streaming":false,"retryEligible":true,"transportReleased":false}"#,
        )
        .unwrap();
        p.state().unwrap();
        assert!(p.retiring.lock().unwrap().is_empty());
        fs::write(
            &p.session_status,
            br#"{"streaming":false,"retryEligible":true,"transportReleased":true,"phase":"transport_closed"}"#,
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
        let worker = std::thread::spawn(move || {
            for request in server.incoming_requests().take(2) {
                request.respond(tiny_http::Response::from_string(r#"{"ok":true,"streaming":true,"firstFrame":true,"phase":"connected","retryEligible":true}"#)).unwrap();
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
        assert!(p.recovery.lock().unwrap().is_none());
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
    child: Arc<Mutex<Option<Child>>>,
    retiring: Arc<Mutex<Vec<(Child, Instant, bool)>>>,
    state_agent: ureq::Agent,
    pairing: Arc<Mutex<Option<PendingPair>>>,
    recovery: Arc<Mutex<Option<Recovery>>>,
    token: String,
    port: u16,
    pub sync: Mutex<crate::config_sync::ConfigSync>,
    device_operation: Mutex<()>,
    network: Arc<crate::network::Network>,
    updates: Mutex<crate::updates::Updates>,
    pub statistics: Arc<Mutex<crate::statistics::Statistics>>,
    retired_launches: Arc<Mutex<Vec<std::thread::JoinHandle<Result<LaunchOutcome, String>>>>>,
    cleanup: crate::native_cleanup::Queue,
    stop_requested: AtomicBool,
    stop_submission: Mutex<()>,
    closing: AtomicBool,
}
impl NativePlatform {
    pub fn new(engine: PathBuf, token: String, data: PathBuf) -> Result<Self, String> {
        let statistics = crate::statistics::Statistics::new(&data);
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
            network: Arc::new(crate::network::Network::default()),
            updates: Mutex::new(crate::updates::Updates::new()),
            statistics: Arc::new(Mutex::new(statistics)),
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
    fn probe(&self, device: &Device) -> Device {
        if self.closing.load(Ordering::SeqCst) || self.stop_requested.load(Ordering::SeqCst) || self.cleanup.pending() || !self.retired_launches.lock().unwrap().is_empty() {
            return device.clone();
        }
        // Probing another host must not replace a live or pending stream's tunnel.
        if self
            .recovery
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|r| r.pending.is_some())
        {
            return device.clone();
        }
        if self
            .child
            .lock()
            .unwrap()
            .as_mut()
            .is_some_and(|c| c.try_wait().ok().flatten().is_none())
        {
            return device.clone();
        }
        let mut d = device.clone();
        d.online = false;
        d.paired = false;
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
                        d.is_self = uuid.eq_ignore_ascii_case(&d.uuid);
                    }
                }
            }
        }
        if d.is_self {
            d.online = true;
            return d;
        }
        let mut native_address = d.address.clone();
        if let Ok((address, _)) = self.network.resolve(&d, &rhremote_core::Profile::default()) {
            native_address = address.clone();
            if address != "127.77.0.2" {
                d.address = address;
            }
            d.online = true;
            for a in self.network.discovered(&d) {
                if d.addresses.len() < 7 && !d.addresses.contains(&a) && a != d.address {
                    d.addresses.push(a);
                }
            }
        }
        if d.online {
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
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn();
            if let Err(ref error) = child {
                self.probe_diagnostic("spawn", &format!("{}: {}", self.engine.display(), error));
            }
            if let Ok(mut child) = child {
                let end = Instant::now() + Duration::from_secs(5);
                loop {
                    match child.try_wait() {
                        Ok(Some(status)) => {
                            if status.success() {
                                match child.wait_with_output() {
                                    Ok(output) => {
                                        // A successful authenticated app-list request establishes pairing.
                                        // App names and an initially empty list do not define trust.
                                        d.paired = output.status.success();
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
                            let _ = child.kill();
                            let _ = child.wait();
                            break;
                        }
                    }
                }
            }
        }
        d
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
    pub fn pairing_result(&self) -> Option<(u64, Device, String)> {
        let mut pending = self.pairing.lock().unwrap();
        if pending.is_none() {
            return None;
        }
        let mut child = self.child.lock().unwrap();
        let status = child.as_mut()?.try_wait().ok()??;
        let operation = pending.take()?;
        let (attempt, device) = (operation.attempt, operation.device);
        child.take();
        drop(child);
        drop(pending);
        let outcome: Value = fs::read(&self.session_status)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or(Value::Null);
        self.finish_pairing(operation.cancellation, outcome["phase"] != "paired");
        let mut device = self.probe(&device);
        if outcome["phase"] == "paired" {
            device.paired = true;
        }
        let error = if device.paired {
            String::new()
        } else if let Some(message) = outcome["error"].as_str().filter(|s| !s.is_empty()) {
            message.chars().take(512).collect()
        } else if status.success() {
            "主机尚未确认配对，请在目标电脑的 Sunshine 提交当前配对码后重试".into()
        } else {
            format!(
                "配对进程退出（{}），请检查目标主机是否在线",
                status.code().unwrap_or(-1)
            )
        };
        let _ = fs::write(
            &self.session_status,
            b"{\"streaming\":false,\"phase\":\"idle\",\"error\":\"\"}",
        );
        Some((attempt, device, error))
    }
    fn network_host_diagnostic(&self, address: &str, uuid: &str) -> Result<Value, String> {
        if self
            .child
            .lock()
            .unwrap()
            .as_mut()
            .is_some_and(|c| c.try_wait().ok().flatten().is_none())
        {
            return Err("请先断开串流再运行主机网络诊断".into());
        }
        let request = self.native_data.join("network-request.json");
        let response = self.native_data.join("network-response.json");
        fs::write(&request, br#"{"path":"/v1/network/diagnose"}"#).map_err(|e| e.to_string())?;
        fs::write(&response, b"{}").map_err(|e| e.to_string())?;
        let mut child = self
            .command()
            .args(["list", address])
            .env("RHREMOTE_TARGET_UUID", uuid)
            .env("RHREMOTE_CONNECT_ADDRESS", address)
            .env("RHREMOTE_HOST_REQUEST", request)
            .env("RHREMOTE_HOST_RESPONSE", &response)
            .spawn()
            .map_err(|e| e.to_string())?;
        let deadline = Instant::now() + Duration::from_secs(12);
        loop {
            if child.try_wait().map_err(|e| e.to_string())?.is_some() {
                break;
            }
            if Instant::now() >= deadline {
                let _ = child.kill();
                let _ = child.wait();
                return Err("主机网络诊断超时；检查配对、47995 与主机服务版本".into());
            }
            std::thread::sleep(Duration::from_millis(25));
        }
        let reply: Value = serde_json::from_slice(&fs::read(response).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
        if reply["ok"] == true {
            Ok(reply["result"].clone())
        } else {
            Err(reply["error"]
                .as_str()
                .unwrap_or("主机网络诊断不可用；检查服务版本与配对证书")
                .into())
        }
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
            let _ = self.statistics.lock().unwrap().event("native_cleanup_end", json!({"kind":report.kind,"pid":report.pid,"forced":report.forced,"confirmed":report.confirmed,"error":report.error}));
        }
        let http_state: Option<Value> = self
            .state_agent
            .get(&format!("http://127.0.0.1:{}/v1/state", self.port))
            .set("Authorization", &format!("Bearer {}", self.token))
            .timeout(Duration::from_millis(500))
            .call()
            .ok()
            .and_then(|r| r.into_json().ok());
        let http_unavailable = http_state.is_none();
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
                && !released
                && r.native_started
                    .is_some_and(|t| t.elapsed() >= Duration::from_secs(15))
                && matches!(
                    value["phase"].as_str(),
                    Some("connecting" | "starting" | "connected" | "reconnecting")
                )
            {
                if !r.timeout_requested {
                    r.timeout_requested = true;
                    let _ = self.statistics.lock().unwrap().event(
                        "route_first_frame_timeout",
                        json!({"attempt":r.attempt,"network":r.current}),
                    );
                    // Bounded native request; cancellation never waits on WAN I/O.
                    let _ = self
                        .state_agent
                        .post(&format!("http://127.0.0.1:{}/v1/disconnect", self.port))
                        .set("Authorization", &format!("Bearer {}", self.token))
                        .timeout(Duration::from_millis(500))
                        .send_json(json!({}));
                }
                if r.native_started
                    .is_some_and(|t| t.elapsed() >= Duration::from_secs(17))
                {
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
                    let _=self.statistics.lock().unwrap().event("route_media_failed",json!({"attempt":r.attempt,"network":r.current,"error":value["error"],"terminationCode":value["terminationCode"]}));
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
        let (recovery,retired,pairing,child,retiring,cleanup,context,network)=(self.recovery.clone(),self.retired_launches.clone(),self.pairing.clone(),self.child.clone(),self.retiring.clone(),self.cleanup.clone(),self.cleanup_context(),self.network.clone());
        if !self.cleanup.coordinate(self.network.clone(),move||{
            if let Some(mut r)=recovery.lock().unwrap().take(){r.cancel.store(true,Ordering::SeqCst);if let Some(job)=r.pending.take(){retired.lock().unwrap().push(job);}}
            let pairing=pairing.lock().unwrap().take();
            if let Some(mut process)=child.lock().unwrap().take(){
                if pairing.is_some(){let _=process.kill();let _=process.wait();}
                else{cleanup.enqueue(process,context.clone(),network.clone());}
            }
            if let Some(pairing)=pairing {if let Some(job)=pairing.cancellation.finish(true){cleanup.track_pairing(job,network.clone());}}
            for (process,_,_) in retiring.lock().unwrap().drain(..){cleanup.enqueue(process,context.clone(),network.clone());}
        }) {
            self.stop_requested.store(false,Ordering::SeqCst);
            return Err("无法启动清理协调任务".into());
        }
        Ok(json!({"cancelled":true,"cleanupPending":true}))
    }
    fn finish_pairing(&self, cancellation: crate::pairing::Cancellation, cancelled: bool) {
        if let Some(job)=cancellation.finish(cancelled){self.cleanup.track_pairing(job,self.network.clone());}
    }
    fn cleanup_context(&self) -> crate::native_cleanup::Context {
        crate::native_cleanup::Context {port:self.port,token:self.token.clone(),status:self.session_status.clone(),limit:Duration::from_secs(15)}
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
}
impl Platform for NativePlatform {
    fn connection_requested(&self, timing: Option<rhremote_core::ActionTiming>) {
        self.statistics.lock().unwrap().connection_requested(timing);
    }
    fn execute(&self, action: &str, args: &Value) -> Result<Value, String> {
        let _device_guard = if action.starts_with("devices.")
            || action == "device.add.submit"
            || action == "device.network.diagnose"
        {
            Some(self.device_operation.lock().unwrap())
        } else {
            None
        };
        match action {
            "engine.close" => {
                self.closing.store(true,Ordering::SeqCst);
                let result=self.stop_owned();
                if result.is_err(){self.closing.store(false,Ordering::SeqCst);}
                result
            },
            "network.state" => Ok(self.network.last.lock().unwrap().clone()),
            "device.network.diagnose" => {
                if self.closing.load(Ordering::SeqCst) || self.stop_requested.load(Ordering::SeqCst) || self.cleanup.pending(){return Err("上一连接正在结束，请稍后重试".into());}
                if self.recovery.lock().unwrap().as_ref().is_some_and(|r|r.pending.is_some()) || self.child.lock().unwrap().as_mut().is_some_and(|c|c.try_wait().ok().flatten().is_none()){
                    return Err("请先断开当前连接，再运行网络诊断".into());
                }
                let d:Device=serde_json::from_value(args["device"].clone()).map_err(|e|e.to_string())?;
                let p:rhremote_core::Profile=serde_json::from_value(args["profile"].clone()).map_err(|e|e.to_string())?;
                let mut result=self.network.diagnose(&d,&p);
                if let Some(address)=result["selectedAddress"].as_str().map(str::to_owned){
                    match self.network_host_diagnostic(&address,&d.uuid){Ok(host)=>result["host"]=host,Err(error)=>result["hostError"]=json!(error)}
                }
                *self.network.last.lock().unwrap()=result.clone();
                Ok(result)
            }
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
                self.sync.lock().unwrap().run(local, resolution)
            }
            "devices.restore" => {
                let rows: Vec<Device> =
                    serde_json::from_value(args["devices"].clone()).map_err(|e| e.to_string())?;
                let mut old = self.devices.lock().unwrap().clone();
                for mut d in rows {
                    d.online = false;
                    d.paired = false;
                    d.is_self = false;
                    old.retain(|o| !o.uuid.eq_ignore_ascii_case(&d.uuid));
                    old.push(d);
                }
                let next: Vec<_> = old.iter().map(|d| self.probe(d)).collect();
                *self.devices.lock().unwrap() = next.clone();
                Ok(json!({"devices":next}))
            }
            "devices.refresh" => {
                let old = self.devices.lock().unwrap().clone();
                let next: Vec<_> = old.iter().map(|d| self.probe(d)).collect();
                *self.devices.lock().unwrap() = next.clone();
                Ok(json!({"devices":next}))
            }
            "device.add.submit" => {
                let address = args["address"].as_str().ok_or("address required")?;
                let response = ureq::get(&format!(
                    "http://{address}:47989/serverinfo?uniqueid=0123456789ABCDEF"
                ))
                .timeout(Duration::from_secs(3))
                .call()
                .map_err(|e| e.to_string())?
                .into_string()
                .map_err(|e| e.to_string())?;
                let doc = roxmltree::Document::parse(&response).map_err(|e| e.to_string())?;
                let field = |key| {
                    doc.descendants()
                        .find(|n| n.has_tag_name(key))
                        .and_then(|n| n.text())
                        .map(str::to_owned)
                };
                let device = self.probe(&Device {
                    uuid: field("uniqueid").ok_or("host UUID missing")?,
                    name: field("hostname").unwrap_or(address.into()),
                    display_name: String::new(),
                    address: address.into(),
                    addresses: Vec::new(),
                    online: true,
                    paired: false,
                    is_self: false,
                });
                let mut devices = self.devices.lock().unwrap();
                devices.retain(|d| d.uuid != device.uuid);
                devices.push(device);
                Ok(json!({"devices":*devices}))
            }
            "device.connect" => {
                if let Some(failure)=args["testInitialFailure"].as_str(){
                    if std::env::var("RHREMOTE_TEST_MODE").as_deref()!=Ok("1")||!matches!(failure,"network"|"identity"|"decoder"){return Err("首帧故障注入仅允许在 Windows 测试模式使用".into());}
                }
                let mut recovery = self.recovery.lock().unwrap();
                let mut slot = self.child.lock().unwrap();
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
                if self.closing.load(Ordering::SeqCst) || self.stop_requested.load(Ordering::SeqCst) || self.cleanup.pending(){return Err("上一连接正在结束，请稍后重试".into());}
                let pending_regression = crate::pairing::pending_regression(
                    args,
                    std::env::var("RHREMOTE_TEST_MODE").as_deref() == Ok("1"),
                )?;
                let mut pending = self.pairing.lock().unwrap();
                let mut slot = self.child.lock().unwrap();
                if slot
                    .as_mut()
                    .is_some_and(|c| c.try_wait().ok().flatten().is_none())
                {
                    return Err("native operation is already running".into());
                }
                let attempt = args["attemptId"].as_u64().ok_or("attempt ID required")?;
                let device: Device =
                    serde_json::from_value(args["device"].clone()).map_err(|e| e.to_string())?;
                let profile:rhremote_core::Profile=serde_json::from_value(args["profile"].clone()).map_err(|e|e.to_string())?;
                let (pair_address,_)=self.network.resolve(&device,&profile)?;
                let cancellation=crate::pairing::Cancellation::new(&self.native_data,&pair_address,self.network.native_environment().0)?;
                fs::write(
                    &self.session_status,
                    b"{\"streaming\":false,\"phase\":\"pairing\",\"error\":\"\"}",
                )
                .map_err(|e| e.to_string())?;
                *slot = Some(
                    self.command()
                        .env("RHREMOTE_PAIR_OPERATION", "1")
                        .env("RHREMOTE_TARGET_UUID", &device.uuid)
                        .env("RHREMOTE_CONNECT_ADDRESS", &pair_address)
                        .env("RHREMOTE_TEST_PAIR_PENDING", if pending_regression { "1" } else { "0" })
                        .env("RHREMOTE_PAIR_ID", cancellation.id())
                        .env("RHREMOTE_PAIR_CANCEL_FILE", cancellation.path())
                        .env("RHREMOTE_SESSION_STATUS", &self.session_status)
                        .args([
                            "pair",
                            &pair_address,
                            "--pin",
                            args["pin"].as_str().ok_or("PIN required")?,
                        ])
                        .spawn()
                        .map_err(|e| e.to_string())?,
                );
                *pending = Some(PendingPair {attempt, device, cancellation});
                Ok(json!({"accepted":true}))
            }
            "pair.cancel" => {
                let operation=self.pairing.lock().unwrap().take();
                let pair_id=operation.as_ref().map(|p|p.cancellation.id().to_owned());
                if let Some(mut child) = self.child.lock().unwrap().take() {
                    let _ = child.kill();
                    let _ = child.wait();
                }
                if let Some(operation)=operation {self.finish_pairing(operation.cancellation, true);}
                Ok(json!({"cancelled":true,"pairId":pair_id}))
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
                self.control("audio", &json!({"enabled":state["audioEnabled"]!=true}))
            }
            "stream.audio.volume" => {
                let volume = args["volume"]
                    .as_u64()
                    .filter(|v| *v <= 100)
                    .ok_or("volume must be 0..100")?;
                self.control("audio", &json!({"volume":volume}))
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
                p["displayMode"].as_str().unwrap_or("auto"),
            )
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
            child: OwnedChild {child:Some(child),cleanup:self.cleanup.clone(),context:crate::native_cleanup::Context {port:self.port,token:self.token.clone(),status:self.session_status.clone(),limit:Duration::from_secs(15)},network:self.network.clone()},
            network,
        })
    }
}
