use super::*;
use std::os::windows::process::CommandExt;
use std::{
    net::TcpListener,
    process::{Command, Stdio},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc,
    },
};
use windows::Win32::{
    Foundation::{CloseHandle, HANDLE, WAIT_TIMEOUT},
    System::Threading::{
        OpenProcess, PROCESS_SYNCHRONIZE, PROCESS_TERMINATE, TerminateProcess, WaitForSingleObject,
    },
};

pub(crate) struct Fixture {
    pub root: PathBuf,
    pub status: PathBuf,
    pub pid: u32,
    pub port: u16,
    child: Option<Child>,
    process: HANDLE,
    release: PathBuf,
    gate: Arc<AtomicBool>,
    stopped: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
    sent: mpsc::Receiver<()>,
    sender: mpsc::Sender<()>,
    pub commands: Arc<AtomicUsize>,
    state_seen: Arc<AtomicBool>,
}

impl Fixture {
    pub fn new(kind: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "rhremote-native-cleanup-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir(&root).unwrap();
        let status = root.join("status.json");
        let ready = root.join("ready");
        let release = root.join("release");
        let script = root.join("native-fixture.ps1");
        fs::write(&script, r#"param([string]$Ready,[string]$Release,[string]$Status,[string]$Kind)
[IO.File]::WriteAllText($Ready,'ready')
while(-not(Test-Path -LiteralPath $Release)){Start-Sleep -Milliseconds 10}
if($Kind-ne'skip'){
 $data='{"processId":'+$PID+',"streaming":false,"phase":"transport_closed","transportReleased":true,"launchCleanupStatus":"'+$Kind+'"}'
 [IO.File]::WriteAllText($Status,$data)
}
"#).unwrap();
        let child = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
            ])
            .arg(&script)
            .arg("-Ready")
            .arg(&ready)
            .arg("-Release")
            .arg(&release)
            .arg("-Status")
            .arg(&status)
            .arg("-Kind")
            .arg(kind)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        let pid = child.id();
        let process =
            unsafe { OpenProcess(PROCESS_SYNCHRONIZE | PROCESS_TERMINATE, false, pid).unwrap() };
        let deadline = Instant::now() + Duration::from_secs(10);
        while !ready.exists() {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(5));
        }
        let port = TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let (sender, sent) = mpsc::channel();
        Self {
            root,
            status,
            pid,
            port,
            child: Some(child),
            process,
            release,
            gate: Arc::new(AtomicBool::new(true)),
            stopped: Arc::new(AtomicBool::new(false)),
            worker: None,
            sent,
            sender,
            commands: Arc::new(AtomicUsize::new(0)),
            state_seen: Arc::new(AtomicBool::new(false)),
        }
    }
    pub fn start_server(&mut self, advertised_pid: u32, hold_state: bool) {
        let server = tiny_http::Server::http((std::net::Ipv4Addr::LOCALHOST, self.port)).unwrap();
        self.gate.store(!hold_state, Ordering::SeqCst);
        let (gate, stopped, sender, commands, state_seen, status, pid) = (
            self.gate.clone(),
            self.stopped.clone(),
            self.sender.clone(),
            self.commands.clone(),
            self.state_seen.clone(),
            self.status.clone(),
            self.pid,
        );
        self.worker = Some(std::thread::spawn(move || {
            while !stopped.load(Ordering::SeqCst) {
                let ended = || {
                    fs::read(&status)
                        .ok()
                        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
                        .is_some_and(|s| {
                            s["processId"].as_u64() == Some(u64::from(pid))
                                && s["phase"] == "transport_closed"
                        })
                };
                if ended() {
                    break;
                }
                let Ok(Some(request)) = server.recv_timeout(Duration::from_millis(25)) else {
                    continue;
                };
                if ended() {
                    break;
                }
                if !request.headers().iter().any(|h| {
                    h.field.equiv("Authorization") && h.value.as_str() == "Bearer fixture-token"
                }) {
                    let _ = request.respond(tiny_http::Response::empty(403));
                    continue;
                }
                let body = if request.url() == "/v1/state" {
                    state_seen.store(true, Ordering::SeqCst);
                    while !gate.load(Ordering::SeqCst) && !stopped.load(Ordering::SeqCst) {
                        std::thread::sleep(Duration::from_millis(5));
                    }
                    json!({"ok":true,"processId":advertised_pid,"streaming":false,"phase":"connecting","firstFrame":false})
                } else if request.url() == "/v1/disconnect" {
                    commands.fetch_add(1, Ordering::SeqCst);
                    let _ = sender.send(());
                    json!({"ok":true,"accepted":true})
                } else {
                    json!({"ok":false})
                };
                let _ = request.respond(tiny_http::Response::from_string(body.to_string()));
            }
        }));
    }
    pub fn context(&self, limit: Duration) -> Context {
        Context {
            port: self.port,
            token: "fixture-token".into(),
            status: self.status.clone(),
            limit,
        }
    }
    pub fn take_child(&mut self) -> Child {
        self.child.take().unwrap()
    }
    pub fn wait_disconnect(&self) {
        self.sent.recv_timeout(Duration::from_secs(4)).unwrap();
    }
    pub fn release(&self) {
        fs::write(&self.release, b"finish").unwrap();
    }
    fn alive(&self) -> bool {
        unsafe { WaitForSingleObject(self.process, 0) == WAIT_TIMEOUT }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::write(&self.release, b"finish");
        self.gate.store(true, Ordering::SeqCst);
        self.stopped.store(true, Ordering::SeqCst);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        unsafe {
            if WaitForSingleObject(self.process, 3000) == WAIT_TIMEOUT {
                let _ = TerminateProcess(self.process, 1);
                let _ = WaitForSingleObject(self.process, 1000);
            }
            let _ = CloseHandle(self.process);
        }
        if let Some(mut child) = self.child.take() {
            let _ = child.wait();
        }
        assert_eq!(self.root.parent(), Some(std::env::temp_dir().as_path()));
        assert!(
            self.root
                .file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("rhremote-native-cleanup-")
        );
        let _ = fs::remove_dir_all(&self.root);
    }
}

struct Lease(Arc<AtomicBool>);
impl Drop for Lease {
    fn drop(&mut self) {
        self.0.store(true, Ordering::SeqCst);
    }
}
fn reports(queue: &Queue) -> Vec<Report> {
    let until = Instant::now() + Duration::from_secs(5);
    let mut result = Vec::new();
    while queue.pending() {
        result.extend(queue.reap());
        assert!(Instant::now() < until);
        std::thread::sleep(Duration::from_millis(5));
    }
    result
}

#[test]
fn cancellation_is_nonblocking_and_holds_transport_until_actual_process_exit() {
    let mut f = Fixture::new("cancelled");
    f.start_server(f.pid, true);
    let queue = Queue::default();
    let released = Arc::new(AtomicBool::new(false));
    let context = f.context(Duration::from_secs(4));
    let began = Instant::now();
    queue.enqueue(f.take_child(), context, Lease(released.clone()));
    assert!(began.elapsed() < Duration::from_millis(500));
    let until = Instant::now() + Duration::from_secs(3);
    while !f.state_seen.load(Ordering::SeqCst) {
        assert!(Instant::now() < until);
        std::thread::sleep(Duration::from_millis(5));
    }
    assert!(queue.pending() && f.alive());
    assert!(!released.load(Ordering::SeqCst));
    f.gate.store(true, Ordering::SeqCst);
    f.wait_disconnect();
    assert!(f.alive());
    assert!(!released.load(Ordering::SeqCst));
    f.release();
    let result = reports(&queue);
    assert_eq!(result.len(), 1);
    assert!(result[0].confirmed);
    assert!(!result[0].forced);
    assert!(!f.alive());
    assert!(released.load(Ordering::SeqCst));
    assert!(queue.failure().is_none());
}
#[test]
fn control_port_may_appear_after_cancellation_was_requested() {
    let mut f = Fixture::new("none");
    let queue = Queue::default();
    let context = f.context(Duration::from_secs(4));
    queue.enqueue(f.take_child(), context, ());
    std::thread::sleep(Duration::from_millis(150));
    assert!(queue.pending() && f.alive());
    f.start_server(f.pid, false);
    f.wait_disconnect();
    f.release();
    assert!(reports(&queue)[0].confirmed);
}
#[test]
fn wrong_process_id_cannot_receive_disconnect_and_only_owned_child_is_forced() {
    let mut f = Fixture::new("cancelled");
    let decoy = Fixture::new("none");
    f.start_server(decoy.pid, false);
    let queue = Queue::default();
    let context = f.context(Duration::from_millis(150));
    queue.enqueue(f.take_child(), context, ());
    let result = reports(&queue);
    assert!(result[0].forced && !result[0].confirmed);
    assert_eq!(f.commands.load(Ordering::SeqCst), 0);
    assert!(decoy.alive());
    assert!(!f.alive());
    assert!(queue.failure().is_some());
}
#[test]
fn failed_remote_cleanup_is_not_reported_as_confirmed_when_process_exits() {
    let mut f = Fixture::new("failed");
    f.start_server(f.pid, false);
    let queue = Queue::default();
    let context = f.context(Duration::from_secs(4));
    queue.enqueue(f.take_child(), context, ());
    f.wait_disconnect();
    f.release();
    let result = reports(&queue);
    assert!(!result[0].forced && !result[0].confirmed);
    assert!(queue.failure().is_some());
    queue.clear_failure();
    assert!(queue.failure().is_none());
}
#[test]
fn old_status_file_cannot_confirm_another_process() {
    let mut f = Fixture::new("skip");
    fs::write(&f.status,json!({"processId":u64::from(f.pid)+1,"phase":"transport_closed","transportReleased":true,"launchCleanupStatus":"cancelled"}).to_string()).unwrap();
    f.start_server(f.pid, false);
    let queue = Queue::default();
    let context = f.context(Duration::from_secs(4));
    queue.enqueue(f.take_child(), context, ());
    f.wait_disconnect();
    f.release();
    assert!(!reports(&queue)[0].confirmed);
}
#[test]
fn pairing_cleanup_also_holds_admission_and_its_transport_lease() {
    let queue = Queue::default();
    let released = Arc::new(AtomicBool::new(false));
    let (ack, wait) = mpsc::channel();
    queue.track_pairing(
        std::thread::spawn(move || wait.recv().unwrap()),
        Lease(released.clone()),
    );
    assert!(queue.pending());
    assert!(queue.reap().is_empty());
    assert!(!released.load(Ordering::SeqCst));
    ack.send(true).unwrap();
    let result = reports(&queue);
    assert_eq!(result[0].kind, "pairing");
    assert!(result[0].confirmed);
    assert!(released.load(Ordering::SeqCst));
}
