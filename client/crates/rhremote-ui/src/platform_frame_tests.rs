use super::*;
use std::sync::atomic::{AtomicU8, AtomicU32, AtomicUsize};
type AudioGate = Arc<Mutex<Option<(std::sync::mpsc::Sender<()>, std::sync::mpsc::Receiver<()>)>>>;

struct Accepted;
impl Platform for Accepted {
    fn execute(&self, _: &str, _: &Value) -> Result<Value, String> {
        Ok(json!({"accepted":true}))
    }
}
struct Fixture {
    native: Arc<NativePlatform>,
    controller: Arc<rhremote_core::Controller>,
    root: PathBuf,
    mode: Arc<AtomicU8>,
    pid: Arc<AtomicU32>,
    posts: Arc<AtomicUsize>,
    audio: Arc<AtomicBool>,
    volume: Arc<AtomicU64>,
    reject_audio: Arc<AtomicBool>,
    audio_gate: AudioGate,
    done: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}
impl Fixture {
    fn child() -> Child {
        Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Sleep -Seconds 30",
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .unwrap()
    }
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "rhremote-frame-milestone-{}",
            rhremote_core::random_token().unwrap()
        ));
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let port = server.server_addr().to_ip().unwrap().port();
        let child = Self::child();
        let pid = Arc::new(AtomicU32::new(child.id()));
        let mode = Arc::new(AtomicU8::new(0));
        let posts = Arc::new(AtomicUsize::new(0));
        let done = Arc::new(AtomicBool::new(false));
        let audio = Arc::new(AtomicBool::new(true));
        let volume = Arc::new(AtomicU64::new(42));
        let reject_audio = Arc::new(AtomicBool::new(false));
        let audio_gate: AudioGate = Arc::new(Mutex::new(None));
        let (wp, wm, wc, wd) = (pid.clone(), mode.clone(), posts.clone(), done.clone());
        let (wa, wv, wr, wg) = (
            audio.clone(),
            volume.clone(),
            reject_audio.clone(),
            audio_gate.clone(),
        );
        let worker = std::thread::spawn(move || {
            while !wd.load(Ordering::SeqCst) {
                let Some(mut request) = server.recv_timeout(Duration::from_millis(20)).unwrap()
                else {
                    continue;
                };
                if request.method() == &tiny_http::Method::Post {
                    if request.url() == "/v1/disconnect" {
                        wc.fetch_add(1, Ordering::SeqCst);
                        request
                            .respond(tiny_http::Response::from_string(r#"{"ok":true}"#))
                            .unwrap();
                    } else {
                        assert_eq!(request.url(), "/v1/audio");
                        let mut body = String::new();
                        std::io::Read::read_to_string(request.as_reader(), &mut body).unwrap();
                        let args: Value = serde_json::from_str(&body).unwrap();
                        let result = if wr.load(Ordering::SeqCst) {
                            json!({"ok":false,"enabled":false,"volume":9})
                        } else {
                            if let Some(enabled) = args["enabled"].as_bool() {
                                wa.store(enabled, Ordering::SeqCst);
                            }
                            if let Some(volume) = args["volume"].as_u64() {
                                wv.store(volume, Ordering::SeqCst);
                            }
                            json!({"ok":true,"enabled":wa.load(Ordering::SeqCst),"volume":wv.load(Ordering::SeqCst)})
                        };
                        let gate = wg.lock().unwrap().take();
                        if let Some((started, release)) = gate {
                            let _ = started.send(());
                            let _ = release.recv_timeout(Duration::from_secs(2));
                        }
                        request
                            .respond(tiny_http::Response::from_string(result.to_string()))
                            .unwrap();
                    }
                    continue;
                }
                assert_eq!(request.url(), "/v1/state");
                let mode = wm.load(Ordering::SeqCst);
                if mode == 1 {
                    request
                        .respond(
                            tiny_http::Response::from_string("temporarily unavailable")
                                .with_status_code(503),
                        )
                        .unwrap();
                } else {
                    request.respond(tiny_http::Response::from_string(json!({"processId":wp.load(Ordering::SeqCst)+u32::from(mode==3),
                        "phase":"connected","streaming":true,"firstFrame":mode==0,"streamWindow":"1",
                        "audioEnabled":wa.load(Ordering::SeqCst),"volume":wv.load(Ordering::SeqCst),"retryEligible":false}).to_string())).unwrap();
                }
            }
        });
        let mut native = NativePlatform::new(
            PathBuf::from("unused-frame-milestone.exe"),
            "fixture-private-token".into(),
            root.join("native"),
        )
        .unwrap();
        native.port = port;
        *native.child.lock().unwrap() = Some(child);
        let mut recovery = Recovery::new(json!({}));
        recovery.native_started = Some(Instant::now());
        *native.recovery.lock().unwrap() = Some(recovery);
        let controller = rhremote_core::Controller::new(
            "fixture",
            root.join("preferences.json"),
            Arc::new(Accepted),
        )
        .unwrap();
        controller.replace_devices(vec![Device {
            uuid: "fixture-host".into(),
            name: "fixture".into(),
            display_name: String::new(),
            address: "192.0.2.1".into(),
            addresses: vec![],
            online: true,
            paired: true,
            is_self: false,
        }]);
        assert_eq!(
            controller.dispatch("device.connect", json!({"uuid":"fixture-host"}))["ok"],
            true
        );
        Self {
            native: Arc::new(native),
            controller,
            root,
            mode,
            pid,
            posts,
            audio,
            volume,
            reject_audio,
            audio_gate,
            done,
            worker: Some(worker),
        }
    }
    fn read(&self) -> Value {
        let value = self.native.state().unwrap();
        self.controller
            .stream_state(value["streaming"] == true, value);
        self.controller.state()
    }
    fn fallback(&self, value: Value) {
        fs::write(&self.native.session_status, value.to_string()).unwrap();
        self.mode.store(1, Ordering::SeqCst);
    }
    fn old_uptime(&self) {
        self.native
            .recovery
            .lock()
            .unwrap()
            .as_mut()
            .unwrap()
            .native_started = Some(Instant::now() - Duration::from_secs(31));
    }
    fn stale_connected(&self) -> Value {
        json!({"processId":self.pid.load(Ordering::SeqCst),"phase":"connected","streaming":true,"firstFrame":false,"error":"","transportReleased":false})
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        super::recovery_transport_tests::stop_fixture(&self.native);
        self.done.store(true, Ordering::SeqCst);
        if let Some(worker) = self.worker.take() {
            worker.join().unwrap();
        }
        assert!(
            self.root.starts_with(std::env::temp_dir())
                && self
                    .root
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .starts_with("rhremote-frame-milestone-")
        );
        fs::remove_dir_all(&self.root).unwrap();
    }
}

#[test]
fn stale_owned_file_after_http_failure_does_not_restart_first_frame_wait() {
    let f = Fixture::new();
    assert_eq!(f.read()["connection"]["phase"], "connected");
    f.fallback(f.stale_connected());
    f.old_uptime();
    let state = f.read();
    assert_eq!(
        f.posts.load(Ordering::SeqCst),
        0,
        "An established session received an erroneous initial-frame disconnect"
    );
    assert_eq!(state["connection"]["phase"], "connected");
    assert_eq!(state["connecting"], false);
    assert_eq!(state["statistics"]["firstFrame"], true);
    assert_eq!(state["statistics"]["firstFrameFromMilestone"], true);
    assert_eq!(state["statistics"]["streamWindow"], "1");
    assert_eq!(state["audioEnabled"], true);
    assert_eq!(state["volume"], 42);
    assert_eq!(
        state["statistics"]["nativeControl"]["request"]["httpStatus"],
        503
    );
    assert_eq!(
        state["statistics"]["nativeControl"]["firstFrame"], false,
        "Raw diagnostics must retain the file's actual flag"
    );
}
#[test]
fn an_unseen_first_frame_is_not_invented_from_a_connected_status_file() {
    let f = Fixture::new();
    f.fallback(f.stale_connected());
    let state = f.read();
    assert_eq!(state["statistics"]["firstFrame"], false);
    assert_eq!(state["connecting"], true);
    assert_ne!(state["statistics"]["firstFrameFromMilestone"], true);
}
#[test]
fn another_native_process_does_not_inherit_the_previous_frame() {
    let f = Fixture::new();
    f.read();
    let mut old = f.native.child.lock().unwrap().take().unwrap();
    old.kill().unwrap();
    old.wait().unwrap();
    let next = Fixture::child();
    assert_ne!(next.id(), f.pid.load(Ordering::SeqCst));
    f.pid.store(next.id(), Ordering::SeqCst);
    *f.native.child.lock().unwrap() = Some(next);
    f.fallback(f.stale_connected());
    let state = f.read();
    assert_eq!(state["statistics"]["firstFrame"], false);
    assert_ne!(state["statistics"]["streamWindow"], "1");
    assert_eq!(state["connecting"], true);
}
#[test]
fn a_foreign_fallback_file_cannot_supply_a_cached_frame_or_window() {
    let f = Fixture::new();
    f.read();
    let mut stale = f.stale_connected();
    stale["processId"] = json!(f.pid.load(Ordering::SeqCst) + 1);
    f.fallback(stale);
    let state = f.read();
    assert_eq!(state["statistics"]["awaitingOwnedState"], true);
    assert_eq!(state["statistics"]["firstFrame"], false);
    assert_ne!(state["statistics"]["streamWindow"], "1");
}
#[test]
fn an_actual_interruption_is_not_hidden_by_a_previous_frame() {
    let f = Fixture::new();
    f.read();
    f.fallback(json!({"processId":f.pid.load(Ordering::SeqCst),"phase":"interrupted","streaming":false,"firstFrame":false,"retryEligible":true}));
    let state = f.read();
    assert_eq!(state["streaming"], false);
    assert_eq!(state["statistics"]["firstFrame"], false);
    assert_eq!(state["connection"]["phase"], "reconnecting");
    assert_eq!(f.posts.load(Ordering::SeqCst), 0);
}
#[test]
fn a_fresh_native_reset_invalidates_the_previous_frame_milestone() {
    let f = Fixture::new();
    f.read();
    f.mode.store(2, Ordering::SeqCst);
    assert_eq!(f.read()["statistics"]["firstFrame"], false);
    f.fallback(f.stale_connected());
    assert_eq!(f.read()["statistics"]["firstFrame"], false);
}
#[test]
fn a_foreign_http_process_cannot_receive_a_first_frame_timeout_disconnect() {
    let f = Fixture::new();
    f.mode.store(3, Ordering::SeqCst);
    f.old_uptime();
    let state = f.read();
    assert_eq!(state["statistics"]["awaitingOwnedState"], true);
    assert_eq!(
        f.posts.load(Ordering::SeqCst),
        0,
        "The timeout branch sent a command to the wrong process"
    );
}

#[test]
fn a_new_admission_does_not_reuse_a_milestone_even_with_the_same_pid() {
    let f = Fixture::new();
    f.read();
    let child = f.native.child.lock().unwrap().take().unwrap();
    let outcome = LaunchOutcome {
        child: OwnedChild {
            child: Some(child),
            cleanup: f.native.cleanup.clone(),
            context: f.native.cleanup_context(),
            network: f.native.network.clone(),
        },
        network: json!({}),
    };
    f.native.recovery.lock().unwrap().as_mut().unwrap().pending =
        Some(std::thread::spawn(move || Ok(outcome)));
    let until = Instant::now() + Duration::from_secs(2);
    while !f
        .native
        .recovery
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .pending
        .as_ref()
        .unwrap()
        .is_finished()
    {
        assert!(Instant::now() < until);
        std::thread::sleep(Duration::from_millis(1));
    }
    f.mode.store(2, Ordering::SeqCst);
    assert_eq!(f.read()["statistics"]["firstFrame"], false);
    f.fallback(f.stale_connected());
    assert_eq!(f.read()["statistics"]["firstFrame"], false);
}

#[test]
fn a_normal_native_platform_cannot_enable_synthetic_state_failures() {
    let f = Fixture::new();
    f.read();
    assert!(
        f.native
            .execute(
                "view.ui.native-state-fault",
                &json!({"processId":f.pid.load(Ordering::SeqCst),"polls":2})
            )
            .is_err()
    );
    assert_eq!(
        f.read()["statistics"]["nativeControl"]["request"]["result"],
        "decoded"
    );
}

#[test]
fn acknowledged_audio_toggle_is_not_undone_by_a_stale_file() {
    let f = Fixture::new();
    f.read();
    f.fallback(f.stale_connected());
    let result = f.native.execute("stream.audio.toggle", &json!({})).unwrap();
    assert_eq!(result["enabled"], false);
    assert_eq!(
        f.read()["audioEnabled"],
        false,
        "The fallback overwrote a successful mute acknowledgement"
    );
}

#[test]
fn acknowledged_audio_volume_is_not_undone_by_a_stale_file() {
    let f = Fixture::new();
    f.read();
    f.fallback(f.stale_connected());
    let result = f
        .native
        .execute("stream.audio.volume", &json!({"volume":37}))
        .unwrap();
    assert_eq!(result["volume"], 37);
    assert_eq!(
        f.read()["volume"],
        37,
        "The fallback overwrote the acknowledged volume"
    );
}

#[test]
fn rejected_audio_reply_cannot_change_the_confirmed_state() {
    let f = Fixture::new();
    f.read();
    f.fallback(f.stale_connected());
    f.reject_audio.store(true, Ordering::SeqCst);
    assert_eq!(
        f.native
            .execute("stream.audio.volume", &json!({"volume":37}))
            .unwrap()["ok"],
        false
    );
    let state = f.read();
    assert_eq!(state["audioEnabled"], true);
    assert_eq!(state["volume"], 42);
}

#[test]
fn late_audio_ack_cannot_change_a_replacement_admission() {
    let f = Fixture::new();
    f.read();
    f.fallback(f.stale_connected());
    let (started, waiting) = std::sync::mpsc::channel();
    let (release, resumed) = std::sync::mpsc::channel();
    *f.audio_gate.lock().unwrap() = Some((started, resumed));
    let native = f.native.clone();
    let caller =
        std::thread::spawn(move || native.execute("stream.audio.volume", &json!({"volume":37})));
    waiting.recv_timeout(Duration::from_secs(2)).unwrap();
    f.volume.store(78, Ordering::SeqCst);
    f.audio.store(true, Ordering::SeqCst);
    {
        let mut recovery = f.native.recovery.lock().unwrap();
        let recovery = recovery.as_mut().unwrap();
        recovery.native_started = Some(Instant::now());
        recovery.first_frame = Some(FirstFrameState::observe(
            f.pid.load(Ordering::SeqCst),
            &json!({"streamWindow":"1","audioEnabled":true,"volume":78}),
            None,
        ));
    }
    release.send(()).unwrap();
    assert_eq!(caller.join().unwrap().unwrap()["volume"], 37);
    assert_eq!(
        f.read()["volume"],
        78,
        "A late reply crossed the native admission boundary"
    );
}

#[test]
fn normal_clients_cannot_run_native_window_tests() {
    let f = Fixture::new();
    f.read();
    for command in ["inspect", "minimize", "restore", "restore-placement", "resize"] {
        assert!(f.native.execute("view.ui.native-window", &json!({"processId":f.pid.load(Ordering::SeqCst),"command":command})).unwrap_err().contains("隔离"));
    }
}

#[test]
fn isolated_window_tests_need_a_live_owned_child_and_confirmed_window() {
    let mut f = Fixture::new();
    Arc::get_mut(&mut f.native).unwrap().ui_test = Arc::new(crate::ui_test::Bridge::new(true));
    let pid = f.pid.load(Ordering::SeqCst);
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":pid,"command":"inspect"})).is_err());
    f.read();
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":pid+1,"command":"minimize"})).is_err());
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":pid+1,"command":"resize","width":640,"height":480})).is_err());
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":pid+1,"command":"restore-placement"})).is_err());
    for (width,height) in [(0,480),(4000,480),(640,159),(640,2161)] {
        assert!(f.native.execute("view.ui.native-window", &json!({"processId":pid,"command":"resize","width":width,"height":height})).is_err());
    }
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":pid,"command":"close"})).is_err());
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":0,"command":"restore"})).is_err());
    f.native.recovery.lock().unwrap().as_mut().unwrap().first_frame = None;
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":pid,"command":"minimize"})).is_err());
}

#[test]
fn a_real_window_from_another_process_is_never_minimized() {
    use windows::{core::w, Win32::{Foundation::HWND, UI::WindowsAndMessaging::*}};
    struct Hidden(HWND);
    impl Drop for Hidden { fn drop(&mut self) { unsafe { let _ = DestroyWindow(self.0); } } }
    let hwnd = unsafe { CreateWindowExW(WINDOW_EX_STYLE::default(),w!("STATIC"),w!("RHRemote ownership test"),WS_POPUP,0,0,40,40,None,None,None,None) }.unwrap();
    let _window = Hidden(hwnd);
    let mut f = Fixture::new();
    Arc::get_mut(&mut f.native).unwrap().ui_test = Arc::new(crate::ui_test::Bridge::new(true));
    f.read();
    f.native.recovery.lock().unwrap().as_mut().unwrap().first_frame.as_mut().unwrap().stream_window = Some((hwnd.0 as usize).to_string());
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":f.pid.load(Ordering::SeqCst),"command":"minimize"})).unwrap_err().contains("句柄"));
    assert!(f.native.execute("view.ui.native-window", &json!({"processId":f.pid.load(Ordering::SeqCst),"command":"resize","width":640,"height":480})).unwrap_err().contains("句柄"));
    assert!(!unsafe { IsIconic(hwnd) }.as_bool());
    assert!(!unsafe { IsWindowVisible(hwnd) }.as_bool());
}
