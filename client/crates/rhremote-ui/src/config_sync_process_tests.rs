//! Opt-in real DAV/process-boundary checks. Only test binaries have a checkpoint hook.
//! The saved vault entry is borrowed for this process; configure/clear are never delegated.
use super::*;
use crate::{Commands, MainWindow, args_for, view_state::MainView};
use i_slint_backend_testing::{
    AccessibleRole, ElementHandle, TestingBackend, TestingBackendOptions,
};
use rhremote_core::{Controller, Device, Platform, Saved};
use slint::{
    ComponentHandle, LogicalPosition, LogicalSize,
    platform::{PointerEventButton, WindowEvent},
};
use std::{
    cell::RefCell,
    path::Path,
    process::{Child, Command, Stdio},
    rc::Rc,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Instant,
};

const OWNER: &str = ".rhremote-ui-owner.json";
const DOC: &str = "rhremote-config-v1.json";
const LOCK_BODY: &str = r#"<?xml version="1.0"?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner>RHRemote owned process test</D:owner></D:lockinfo>"#;
const POINTS: [&str; 5] = [
    "after_remote",
    "before_apply",
    "after_shared",
    "after_apply",
    "after_account",
];
thread_local! {static ARMED: RefCell<Option<(PathBuf,String)>> = const {RefCell::new(None)};}

fn write_json(path: &Path, value: &impl Serialize) {
    atomic(path, value).expect("Write owned test evidence");
}
fn read_json(path: &Path) -> Value {
    serde_json::from_slice(&fs::read(path).expect("Read owned test evidence")).expect("Owned JSON")
}
fn checkpoint(directory: &Path, point: &str) {
    let armed = ARMED.with(|value| {
        value
            .borrow()
            .as_ref()
            .is_some_and(|(root, wanted)| root == directory && wanted == point)
    });
    if !armed {
        return;
    }
    write_json(
        &directory.join("checkpoint.json"),
        &json!({"point":point,"pid":std::process::id()}),
    );
    let end = Instant::now() + Duration::from_secs(180);
    while Instant::now() < end {
        std::thread::sleep(Duration::from_millis(50));
    }
    std::process::exit(86);
}
pub(super) fn after_account(directory: &Path) {
    checkpoint(directory, "after_account");
}

struct Reply {
    code: u16,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}
struct Scope {
    agent: ureq::Agent,
    auth: String,
    normal: String,
    root: url::Url,
    owner: Vec<u8>,
    allowed_documents: Mutex<Vec<Value>>,
}
impl Scope {
    fn request(
        &self,
        method: &str,
        address: &str,
        body: &[u8],
        headers: &[(String, String)],
    ) -> Reply {
        let target = url::Url::parse(address).expect("Owned URL");
        let owned = target.origin() == self.root.origin()
            && (target.path() == self.root.path().trim_end_matches('/')
                || target.path().starts_with(self.root.path()));
        assert!(
            owned || (method == "GET" && address == self.normal),
            "Request escaped isolated DAV scope"
        );
        assert!(!(!owned && method != "GET"));
        let mut request = self
            .agent
            .request(method, address)
            .set("Authorization", &self.auth);
        for (name, value) in headers {
            request = request.set(name, value);
        }
        let response = match request.send_bytes(body) {
            Ok(value) => value,
            Err(ureq::Error::Status(_, value)) => value,
            Err(_) => panic!("Owned DAV transport failed"),
        };
        let code = response.status();
        let headers = ["ETag", "DAV", "Lock-Token", "Timeout", "Content-Type"]
            .into_iter()
            .filter_map(|name| {
                response
                    .header(name)
                    .map(|value| (name.into(), value.into()))
            })
            .collect();
        let mut bytes = Vec::new();
        response
            .into_reader()
            .take(LIMIT + 1)
            .read_to_end(&mut bytes)
            .expect("Read owned DAV response");
        assert!(bytes.len() <= LIMIT as usize);
        Reply {
            code,
            headers,
            body: bytes,
        }
    }
    fn doc(&self) -> String {
        self.root.join(DOC).unwrap().to_string()
    }
    fn create(&self) {
        assert_eq!(
            self.request(
                "PROPFIND",
                self.root.as_str(),
                &[],
                &[("Depth".into(), "0".into())]
            )
            .code,
            404,
            "Use a fresh isolated collection"
        );
        assert_eq!(
            self.request("MKCOL", self.root.as_str(), &[], &[]).code,
            201
        );
        assert!(
            [200, 201, 204].contains(
                &self
                    .request(
                        "PUT",
                        self.root.join(OWNER).unwrap().as_str(),
                        &self.owner,
                        &[
                            ("If-None-Match".into(), "*".into()),
                            ("Content-Type".into(), "application/json".into())
                        ]
                    )
                    .code
            )
        );
    }
    fn cleanup(&self) {
        let owner = self.request("GET", self.root.join(OWNER).unwrap().as_str(), &[], &[]);
        assert_eq!(owner.code, 200);
        assert_eq!(
            owner.body, self.owner,
            "Owned DAV marker changed; retain collection"
        );
        let lock = self.request(
            "LOCK",
            self.root.as_str(),
            LOCK_BODY.as_bytes(),
            &[
                ("Depth".into(), "infinity".into()),
                ("Timeout".into(), "Second-60".into()),
                ("Content-Type".into(), "application/xml".into()),
            ],
        );
        assert!(
            [200, 201].contains(&lock.code),
            "Collection cleanup lock unavailable; retain collection"
        );
        let token = lock
            .headers
            .iter()
            .find(|h| h.0 == "Lock-Token")
            .expect("Cleanup token")
            .1
            .clone();
        assert!(token.starts_with('<') && token.ends_with('>') && !token.contains(['\r', '\n']));
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let listing = self.request(
                "PROPFIND",
                self.root.as_str(),
                &[],
                &[("Depth".into(), "1".into())],
            );
            assert_eq!(listing.code, 207);
            let text = std::str::from_utf8(&listing.body).unwrap();
            let xml = roxmltree::Document::parse(text).unwrap();
            let allowed = [
                self.root.path().to_string(),
                self.root.path().trim_end_matches('/').to_string(),
                self.root.join(OWNER).unwrap().path().to_string(),
                self.root.join(DOC).unwrap().path().to_string(),
            ];
            let mut count = 0;
            for node in xml
                .descendants()
                .filter(|n| n.is_element() && n.tag_name().name() == "href")
            {
                let url = self.root.join(node.text().unwrap_or("")).unwrap();
                assert_eq!(url.origin(), self.root.origin());
                assert!(
                    allowed.contains(&url.path().to_string()),
                    "Unexpected DAV entry; retain collection"
                );
                count += 1;
            }
            assert!(count >= 1);
            let document = self.request("GET", &self.doc(), &[], &[]);
            if document.code == 200 && !document.body.is_empty() {
                let value: Value = serde_json::from_slice(&document.body)
                    .expect("Unexpected document; retain collection");
                assert!(
                    self.allowed_documents.lock().unwrap().contains(&value),
                    "Unexpected document contents; retain collection"
                );
            } else {
                assert!([200, 404].contains(&document.code));
            }
            assert!(
                [200, 204].contains(
                    &self
                        .request(
                            "DELETE",
                            self.root.as_str(),
                            &[],
                            &[("If".into(), format!("({token})"))]
                        )
                        .code
                )
            );
        }));
        let unlocked = self.request(
            "UNLOCK",
            self.root.as_str(),
            &[],
            &[("Lock-Token".into(), token)],
        );
        assert!([200, 204].contains(&unlocked.code));
        if let Err(error) = result {
            std::panic::resume_unwind(error);
        }
        assert_eq!(
            self.request(
                "PROPFIND",
                self.root.as_str(),
                &[],
                &[("Depth".into(), "0".into())]
            )
            .code,
            404
        );
    }
}

#[derive(Default)]
struct Wire {
    events: Vec<Value>,
    puts: usize,
    committed: bool,
    lease_deadline: u64,
    hold: bool,
}
struct Gateway {
    address: String,
    wire: Arc<Mutex<Wire>>,
    stop: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}
impl Gateway {
    fn new(scope: Arc<Scope>, directory: PathBuf, hold: bool) -> Self {
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let origin = format!("http://{}", server.server_addr());
        let path = scope.root.join(DOC).unwrap().path().to_owned();
        let address = format!("{origin}{path}");
        let wire = Arc::new(Mutex::new(Wire {
            hold,
            ..Default::default()
        }));
        let state = wire.clone();
        let stop = Arc::new(AtomicBool::new(false));
        let stopping = stop.clone();
        write_json(
            &directory.join("wire.json"),
            &json!({"events":[],"puts":0,"leaseDeadlineMs":0}),
        );
        let worker = std::thread::spawn(move || {
            while !stopping.load(Ordering::SeqCst) {
                let Some(mut request) = server.recv_timeout(Duration::from_millis(50)).unwrap()
                else {
                    continue;
                };
                let method = request.method().as_str().to_owned();
                let header = |name: &str| {
                    request
                        .headers()
                        .iter()
                        .find(|h| h.field.as_str().as_str().eq_ignore_ascii_case(name))
                        .map(|h| h.value.as_str().to_owned())
                };
                if request.url() != path
                    || !matches!(
                        method.as_str(),
                        "OPTIONS" | "LOCK" | "UNLOCK" | "GET" | "PUT"
                    )
                    || header("Authorization").as_deref() != Some(scope.auth.as_str())
                {
                    let _ = request.respond(tiny_http::Response::empty(403));
                    continue;
                }
                let headers = [
                    "If",
                    "If-Match",
                    "If-None-Match",
                    "Lock-Token",
                    "Depth",
                    "Timeout",
                    "Content-Type",
                ]
                .into_iter()
                .filter_map(|name| header(name).map(|value| (name.into(), value)))
                .collect::<Vec<_>>();
                let mut body = Vec::new();
                request
                    .as_reader()
                    .take(LIMIT + 1)
                    .read_to_end(&mut body)
                    .unwrap();
                assert!(body.len() <= LIMIT as usize);
                let response = scope.request(&method, &scope.doc(), &body, &headers);
                if method == "PUT" && (200..300).contains(&response.code) {
                    let independent = scope.request("GET", &scope.doc(), &[], &[]);
                    assert_eq!(independent.code, 200);
                    assert_eq!(
                        independent.body, body,
                        "Real server did not store PUT bytes"
                    );
                    fs::write(directory.join("committed.json"), &body).unwrap();
                }
                {
                    let mut state = state.lock().unwrap_or_else(|error| error.into_inner());
                    if method == "LOCK" && (200..300).contains(&response.code) {
                        let text = String::from_utf8_lossy(&response.body);
                        let body_timeout = roxmltree::Document::parse(&text).ok().and_then(|xml| {
                            xml.descendants()
                                .find(|n| n.is_element() && n.tag_name().name() == "timeout")
                                .and_then(|n| n.text())
                                .map(str::to_owned)
                        });
                        let timeout = body_timeout
                            .or_else(|| {
                                response
                                    .headers
                                    .iter()
                                    .find(|h| h.0 == "Timeout")
                                    .map(|h| h.1.clone())
                            })
                            .expect("Known finite DAV lease required");
                        let seconds: u64 = timeout
                            .trim()
                            .strip_prefix("Second-")
                            .expect("Finite DAV lease")
                            .parse()
                            .unwrap();
                        assert!((1..=120).contains(&seconds));
                        state.lease_deadline = rhremote_core::now_ms() + seconds * 1000;
                    }
                    if method == "UNLOCK" && (200..300).contains(&response.code) {
                        state.lease_deadline = 0;
                    }
                    if method == "PUT" && (200..300).contains(&response.code) {
                        state.puts += 1;
                        state.committed = true;
                    }
                    state
                        .events
                        .push(json!({"method":method,"status":response.code}));
                    write_json(
                        &directory.join("wire.json"),
                        &json!({"events":state.events,"puts":state.puts,"leaseDeadlineMs":state.lease_deadline}),
                    );
                }
                if method == "PUT" && (200..300).contains(&response.code) {
                    while !stopping.load(Ordering::SeqCst)
                        && state.lock().unwrap_or_else(|error| error.into_inner()).hold
                    {
                        std::thread::sleep(Duration::from_millis(10));
                    }
                }
                let mut reply =
                    tiny_http::Response::from_data(response.body).with_status_code(response.code);
                for (name, value) in response.headers {
                    reply.add_header(
                        tiny_http::Header::from_bytes(name.as_bytes(), value.as_bytes()).unwrap(),
                    );
                }
                let _ = request.respond(reply);
            }
        });
        Self {
            address,
            wire,
            stop,
            worker: Some(worker),
        }
    }
}
impl Drop for Gateway {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(worker) = self.worker.take() {
            worker.join().expect("Owned gateway stopped");
        }
    }
}

struct Backend {
    sync: Mutex<ConfigSync>,
    directory: PathBuf,
}
impl Platform for Backend {
    fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        match id {
            "sync.state" => Ok(self.sync.lock().unwrap().state()),
            "updates.state" => Ok(json!({"managed":false})),
            "devices.restore" => {
                checkpoint(&self.directory, "after_shared");
                Ok(args.clone())
            }
            "sync.run" => {
                let shared = serde_json::from_value(args["shared"].clone())
                    .map_err(|_| "Invalid owned configuration")?;
                let value = self.sync.lock().unwrap().run(shared, Resolution::Review)?;
                checkpoint(&self.directory, "before_apply");
                Ok(value)
            }
            // In particular, never let this borrowed credential identity be configured or removed.
            _ => Err("Operation outside the isolated process test".into()),
        }
    }
    fn sync_applied(
        &self,
        shared: &SharedConfig,
        preferences: &Path,
    ) -> Result<Option<Value>, String> {
        checkpoint(&self.directory, "after_apply");
        self.sync
            .lock()
            .unwrap()
            .applied(shared, preferences)
            .map(Some)
    }
}
struct Ui {
    window: MainWindow,
    view: MainView,
    controller: Arc<Controller>,
    queue: Rc<RefCell<Vec<(String, String)>>>,
}
impl Ui {
    fn new(directory: &Path) -> Self {
        slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
            mock_time: true,
            threading: false,
            renderer_name: Some("software".into()),
        })))
        .unwrap();
        let backend = Arc::new(Backend {
            sync: Mutex::new(ConfigSync::new(directory.into()).unwrap()),
            directory: directory.into(),
        });
        let controller =
            Controller::new("windows", directory.join("preferences.json"), backend).unwrap();
        let saved: Saved =
            serde_json::from_slice(&fs::read(directory.join("preferences.json")).unwrap()).unwrap();
        controller.replace_devices(
            saved
                .devices
                .iter()
                .map(|d| Device {
                    uuid: d.uuid.clone(),
                    name: d.name.clone(),
                    display_name: d.display_name.clone(),
                    address: d.address.clone(),
                    addresses: d.addresses.clone(),
                    online: false,
                    paired: false,
                    is_self: false,
                })
                .collect(),
        );
        assert_eq!(
            controller.dispatch("app.settings.tab", json!({"tab":"sync"}))["ok"],
            true
        );
        let window = MainWindow::new().unwrap();
        window.window().set_size(LogicalSize::new(900., 720.));
        let view = MainView::new(&window);
        let queue = Rc::new(RefCell::new(Vec::new()));
        let pending = queue.clone();
        window.global::<Commands>().on_trigger(move |id, arg| {
            pending.borrow_mut().push((id.to_string(), arg.to_string()))
        });
        window.show().unwrap();
        let mut ui = Self {
            window,
            view,
            controller,
            queue,
        };
        ui.refresh();
        ui
    }
    fn refresh(&mut self) {
        self.view
            .refresh(&self.window, &self.controller.state(), |id| {
                self.controller.profile(id)
            });
        i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
        self.window.window().take_snapshot().unwrap();
    }
    fn capture(&self, path: &Path) {
        let pixels = self.window.window().take_snapshot().unwrap();
        assert!(!path.exists());
        image::save_buffer(
            path,
            pixels.as_bytes(),
            pixels.width(),
            pixels.height(),
            image::ColorType::Rgba8,
        )
        .unwrap();
    }
    fn click_sync(&mut self) -> Value {
        let elements: Vec<_> = ElementHandle::find_by_accessible_label(&self.window, "立即同步")
            .filter(|e| e.accessible_role() == Some(AccessibleRole::Button))
            .collect();
        assert_eq!(elements.len(), 1);
        let button = &elements[0];
        let p = button.absolute_position();
        let size = button.size();
        assert!(p.x >= 0. && p.y >= 0. && p.x + size.width <= 900. && p.y + size.height <= 720.);
        let at = LogicalPosition::new(p.x + size.width / 2., p.y + size.height / 2.);
        for event in [
            WindowEvent::PointerPressed {
                position: at,
                button: PointerEventButton::Left,
            },
            WindowEvent::PointerReleased {
                position: at,
                button: PointerEventButton::Left,
            },
        ] {
            self.window.window().dispatch_event(event);
        }
        let emitted = std::mem::take(&mut *self.queue.borrow_mut());
        assert_eq!(emitted, vec![("sync.run".into(), String::new())]);
        let result = self.controller.dispatch(
            "sync.run",
            args_for("sync.run", "", &self.controller.state()),
        );
        self.refresh();
        result
    }
}
impl Drop for Ui {
    fn drop(&mut self) {
        let _ = self.window.hide();
    }
}

#[test]
#[ignore = "Only spawned by the opt-in real DAV parent"]
fn process_worker() {
    let directory = PathBuf::from(
        std::env::var_os("RHREMOTE_DAV_PROCESS_WORKER").expect("Owned worker root required"),
    )
    .canonicalize()
    .unwrap();
    let spec = read_json(&directory.join("case.json"));
    let point = spec["point"].as_str().unwrap();
    assert!(POINTS.contains(&point));
    let phase = std::env::var("RHREMOTE_DAV_PROCESS_PHASE").unwrap();
    assert!(matches!(phase.as_str(), "prepare" | "verify"));
    let mut ui = Ui::new(&directory);
    let blocked = point != "after_account";
    if phase == "prepare" {
        ARMED.with(|value| *value.borrow_mut() = Some((directory.clone(), point.into())));
        let _ = ui.click_sync();
        panic!("Prepare worker passed its checkpoint");
    }
    assert_eq!(ui.controller.state()["sync"]["automaticBlocked"], blocked);
    assert_eq!(ui.controller.state()["syncBusy"], false);
    if blocked {
        assert!(
            ui.window.get_sync_last().contains("待确认")
                && ui.window.get_sync_last().contains("暂停")
        );
        assert!(!ui.controller.automatic_sync_due(u64::MAX, 0));
        let before = read_json(&directory.join("wire.json"));
        let automatic = ui
            .controller
            .dispatch("sync.run", json!({"automatic":true}));
        assert_eq!(automatic["ok"], true);
        assert!(automatic.to_string().contains("sync_interrupted"));
        assert_eq!(before, read_json(&directory.join("wire.json")));
    }
    ui.capture(&directory.join("cold.png"));
    let old_account = fs::read(directory.join("webdav-local.json")).unwrap();
    for tab in ["about", "sync"] {
        assert_eq!(
            ui.controller
                .dispatch("app.settings.tab", json!({"tab":tab}))["ok"],
            true
        );
        ui.refresh();
    }
    assert_eq!(
        ui.controller.dispatch(
            "sync.edit",
            json!({"key":"username","value":"discarded-fixture-identity"})
        )["ok"],
        true
    );
    assert_eq!(ui.controller.dispatch("sync.cancel", json!({}))["ok"], true);
    ui.refresh();
    assert_eq!(
        old_account,
        fs::read(directory.join("webdav-local.json")).unwrap()
    );
    assert_eq!(ui.controller.state()["sync"]["automaticBlocked"], blocked);
    ui.capture(&directory.join("cancelled-draft.png"));
    let mut wait_ms = 0;
    if point == "after_remote" {
        let rejected = ui.click_sync();
        assert_eq!(rejected["ok"], false);
        assert!(
            ui.controller.state()["syncError"]
                .as_str()
                .unwrap()
                .contains("HTTP 423")
        );
        assert_eq!(ui.controller.state()["sync"]["automaticBlocked"], true);
        ui.capture(&directory.join("owned-lease.png"));
        let deadline = read_json(&directory.join("wire.json"))["leaseDeadlineMs"]
            .as_u64()
            .unwrap();
        assert!(deadline > 0);
        let end = deadline + 2000;
        wait_ms = end.saturating_sub(rhremote_core::now_ms());
        assert!(wait_ms <= 122000);
        while rhremote_core::now_ms() < end {
            std::thread::sleep(Duration::from_millis(50));
        }
    }
    let result = ui.click_sync();
    assert_eq!(result["ok"], true);
    assert_eq!(ui.controller.state()["sync"]["automaticBlocked"], false);
    assert_eq!(ui.controller.state()["sync"]["phase"], "completed");
    assert!(
        ui.controller.state()["sync"]["lastSyncMs"]
            .as_u64()
            .unwrap()
            > 1
    );
    let expected = read_json(&directory.join("expected.json"));
    let saved: Saved =
        serde_json::from_slice(&fs::read(directory.join("preferences.json")).unwrap()).unwrap();
    assert_eq!(
        serde_json::to_value(saved.devices).unwrap(),
        expected["devices"]
    );
    assert_eq!(saved.dark, expected["dark"]);
    assert_eq!(
        read_json(&directory.join("webdav-local.json"))["base"],
        expected
    );
    ui.capture(&directory.join("reconciled.png"));
    write_json(
        &directory.join("verified.json"),
        &json!({"point":point,"pid":std::process::id(),"passed":true,"coldAutomaticBlocked":blocked,"automaticSkipWithoutRequests":if blocked{json!(true)}else{Value::Null},"draftCancelPreservedAccount":true,"naturalLeaseWaitMs":wait_ms,"manualRetryCompleted":true,"actualSlintControls":true,"physicalWindow":false,"osInputInjected":false}),
    );
}

struct OwnedChild(Child);
impl Drop for OwnedChild {
    fn drop(&mut self) {
        if self.0.try_wait().ok().flatten().is_none() {
            let _ = self.0.kill();
        }
        let _ = self.0.wait();
    }
}
fn spawn(directory: &Path, phase: &str) -> OwnedChild {
    let exe = std::env::current_exe().unwrap();
    let output = fs::File::create(directory.join(format!("{phase}.stdout.log"))).unwrap();
    let error = fs::File::create(directory.join(format!("{phase}.stderr.log"))).unwrap();
    let mut command = Command::new(exe);
    command
        .args([
            "--ignored",
            "--exact",
            "config_sync::process_tests::process_worker",
            "--nocapture",
            "--test-threads=1",
        ])
        .env("RHREMOTE_DAV_PROCESS_WORKER", directory)
        .env("RHREMOTE_DAV_PROCESS_PHASE", phase)
        .stdin(Stdio::null())
        .stdout(output)
        .stderr(error);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    OwnedChild(command.spawn().unwrap())
}
fn fixture() -> SharedConfig {
    let mut base = ConfigSync::test_configuration();
    let mut peer = base.devices[0].clone();
    peer.uuid = "peer".into();
    peer.name = "Peer".into();
    peer.address = "192.0.2.114".into();
    base.devices.push(peer);
    let profile = base.profiles["pc"]["self"].clone();
    base.profiles
        .get_mut("pc")
        .unwrap()
        .insert("peer".into(), profile);
    base
}
fn saved(shared: &SharedConfig) -> Saved {
    Saved {
        client_id: "pc".into(),
        profiles: shared.profiles["pc"]
            .iter()
            .map(|(k, v)| (k.clone(), v.to_profile()))
            .collect(),
        devices: shared.devices.clone(),
        clients: shared.clients.clone(),
        client_profiles: shared.profiles.clone(),
        dark: shared.dark,
        profile_schema_version: 2,
        relay_defaults_version: 1,
        device_order_initialized: true,
        ..Default::default()
    }
}

#[test]
#[ignore = "Real saved DAV credential, isolated collections and owned child process termination; explicit review root required"]
fn real_service_commit_process_boundaries() {
    let root = PathBuf::from(
        std::env::var_os("RHREMOTE_REAL_DAV_PROCESS_DIR")
            .expect("Explicit owned review root required"),
    );
    assert!(root.is_absolute() && !root.exists());
    assert!(root.parent().unwrap().is_dir());
    fs::create_dir(&root).unwrap();
    let root = root.canonicalize().unwrap();
    let normal_path =
        PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap()).join("RHRemote/webdav-local.json");
    let original = fs::read(&normal_path).unwrap();
    let normal: Local = serde_json::from_slice(&original).expect("Saved DAV metadata");
    assert!(!normal.credential_id.is_empty());
    let mut base_url = url::Url::parse(&normal.url).unwrap();
    assert!(matches!(
        base_url.host_str(),
        Some("winserver.taile71e4.ts.net" | "192.168.0.104" | "100.73.139.109")
    ));
    assert_eq!(base_url.port_or_known_default(), Some(5244));
    assert!(matches!(base_url.scheme(), "http" | "https"));
    assert!(
        base_url.username().is_empty()
            && base_url.password().is_none()
            && base_url.query().is_none()
            && base_url.fragment().is_none()
    );
    if !base_url.path().ends_with('/') {
        base_url.set_path(&format!("{}/", base_url.path()));
    }
    let password = ConfigSync::credential(&normal.credential_id)
        .unwrap()
        .get_password()
        .expect("Use existing saved DAV credential");
    let auth = format!(
        "Basic {}",
        base64::engine::general_purpose::STANDARD.encode(format!("{}:{password}", normal.username))
    );
    drop(password);
    let mut conclusions = Vec::new();
    for point in POINTS {
        let directory = root.join(point);
        fs::create_dir(&directory).unwrap();
        let token = rhremote_core::random_token().unwrap();
        let url = base_url
            .join(&format!("RHRemote-UI-wincommit-{token}/"))
            .unwrap();
        let owner=serde_json::to_vec(&json!({"purpose":"RHRemote owned Windows commit process UI test","nonce":token,"point":point})).unwrap();
        let scope = Arc::new(Scope {
            agent: ureq::AgentBuilder::new()
                .redirects(0)
                .timeout(Duration::from_secs(15))
                .build(),
            auth: auth.clone(),
            normal: endpoint(&normal.url).unwrap().to_string(),
            root: url,
            owner,
            allowed_documents: Mutex::new(Vec::new()),
        });
        write_json(
            &directory.join("collection-plan.json"),
            &json!({"root":scope.root.as_str(),"owner":serde_json::from_slice::<Value>(&scope.owner).unwrap(),"normalDocumentReadOnly":true}),
        );
        let normal_before = scope.request("GET", &scope.normal, &[], &[]);
        assert_eq!(normal_before.code, 200);
        let created = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| scope.create()));
        if created.is_err() {
            let cleaned =
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| scope.cleanup()));
            write_json(
                &directory.join("verification.json"),
                &json!({"point":point,"passed":false,"creationFailed":true,"cleanupCompleted":cleaned.is_ok()}),
            );
            panic!("Isolated collection creation failed; inspect its retained ownership plan");
        }
        let mut gateway = Gateway::new(scope.clone(), directory.clone(), point == "after_remote");
        let work = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let base = fixture();
            let mut local = base.clone();
            local.devices[0].display_name = "本机修改".into();
            let mut remote = base.clone();
            remote.devices[1].display_name = "远端修改".into();
            remote.dark = true;
            let expected = merge(&local, &remote, Some(&base), Resolution::Review);
            assert!(expected.conflicts.is_empty());
            *scope.allowed_documents.lock().unwrap() = vec![
                serde_json::to_value(&remote).unwrap(),
                serde_json::to_value(&expected.merged).unwrap(),
            ];
            assert!(
                [200, 201, 204].contains(
                    &scope
                        .request(
                            "PUT",
                            &scope.doc(),
                            &serde_json::to_vec(&remote).unwrap(),
                            &[("Content-Type".into(), "application/json".into())]
                        )
                        .code
                )
            );
            let private = Local {
                url: gateway.address.trim_end_matches(DOC).into(),
                username: normal.username.clone(),
                credential_id: normal.credential_id.clone(),
                automatic: true,
                interval_minutes: 5,
                last_sync_ms: 1,
                base: Some(base),
                ..Default::default()
            };
            write_json(&directory.join("webdav-local.json"), &private);
            write_json(&directory.join("preferences.json"), &saved(&local));
            write_json(&directory.join("expected.json"), &expected.merged);
            write_json(
                &directory.join("case.json"),
                &json!({"point":point,"realService":true,"borrowedCredentialNeverConfiguredOrRemoved":true,"davRoot":scope.root.as_str()}),
            );
            let mut child = spawn(&directory, "prepare");
            let old_pid = child.0.id();
            let end = Instant::now() + Duration::from_secs(45);
            loop {
                let ready = if point == "after_remote" {
                    gateway
                        .wire
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .committed
                } else {
                    directory.join("checkpoint.json").exists()
                };
                if ready {
                    break;
                }
                assert!(
                    child.0.try_wait().unwrap().is_none(),
                    "Prepare child exited before checkpoint; inspect its logs"
                );
                assert!(Instant::now() < end, "Owned prepare checkpoint timed out");
                std::thread::sleep(Duration::from_millis(20));
            }
            let account = read_json(&directory.join("webdav-local.json"));
            let prefs = read_json(&directory.join("preferences.json"));
            let expected_value = serde_json::to_value(&expected.merged).unwrap();
            let remote_check = scope.request("GET", &scope.doc(), &[], &[]);
            assert_eq!(remote_check.code, 200);
            assert_eq!(
                serde_json::from_slice::<Value>(&remote_check.body).unwrap(),
                expected_value
            );
            assert_eq!(read_json(&directory.join("committed.json")), expected_value);
            let applied = point != "after_remote" && point != "before_apply";
            assert_eq!(prefs["dark"], applied);
            assert_eq!(account["pendingTransfer"], point == "after_remote");
            assert_eq!(
                account["pendingApply"],
                matches!(point, "before_apply" | "after_shared" | "after_apply")
            );
            if point == "after_account" {
                assert_eq!(account["base"], expected_value);
                assert!(account["lastSyncMs"].as_u64().unwrap() > 1);
            } else {
                assert_eq!(account["lastSyncMs"], 1);
                assert_eq!(account["base"]["dark"], false);
            }
            if point != "after_remote" {
                assert_eq!(
                    read_json(&directory.join("checkpoint.json"))["pid"],
                    old_pid
                );
            }
            child
                .0
                .kill()
                .expect("Terminate only the spawned test child through its original handle");
            let status = child.0.wait().unwrap();
            assert!(!status.success());
            gateway
                .wire
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .hold = false;
            let mut verify = spawn(&directory, "verify");
            let new_pid = verify.0.id();
            assert_ne!(old_pid, new_pid);
            let until = Instant::now() + Duration::from_secs(150);
            loop {
                if let Some(status) = verify.0.try_wait().unwrap() {
                    assert!(
                        status.success(),
                        "Cold verification failed; inspect owned logs"
                    );
                    break;
                }
                assert!(Instant::now() < until, "Cold verification timed out");
                std::thread::sleep(Duration::from_millis(50));
            }
            let proof = read_json(&directory.join("verified.json"));
            assert_eq!(proof["passed"], true);
            assert_eq!(proof["pid"], new_pid);
            let remote = scope.request("GET", &scope.doc(), &[], &[]);
            assert_eq!(remote.code, 200);
            assert_eq!(
                serde_json::from_slice::<Value>(&remote.body).unwrap(),
                expected_value
            );
            assert_eq!(
                gateway
                    .wire
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .puts,
                2
            );
            json!({"point":point,"passed":true,"oldPid":old_pid,"newPid":new_pid,"checkpointRemoteMatches":true,"localAppliedAtCheckpoint":applied,"cold":proof,"successfulChildPuts":2})
        }));
        let deadline = gateway
            .wire
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .lease_deadline;
        gateway
            .wire
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .hold = false;
        gateway.stop.store(true, Ordering::SeqCst);
        let gateway_exited = if let Some(worker) = gateway.worker.take() {
            worker.join().is_ok()
        } else {
            true
        };
        if work.is_err() && deadline > rhremote_core::now_ms() {
            let until = deadline + 2000;
            assert!(until.saturating_sub(rhremote_core::now_ms()) <= 122000);
            while rhremote_core::now_ms() < until {
                std::thread::sleep(Duration::from_millis(50));
            }
        }
        let cleaned = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| scope.cleanup()));
        let normal_after = scope.request("GET", &scope.normal, &[], &[]);
        let normal_unchanged = normal_after.code == 200 && normal_after.body == normal_before.body;
        let passed = work.is_ok() && gateway_exited && cleaned.is_ok() && normal_unchanged;
        let evidence = json!({"point":point,"passed":passed,"gatewayExited":gateway_exited,"process":work.as_ref().ok(),"cleanupCompleted":cleaned.is_ok(),"normalDocumentUnchanged":normal_unchanged,"normalLocalAccountUnchanged":fs::read(&normal_path).unwrap()==original});
        write_json(&directory.join("verification.json"), &evidence);
        conclusions.push(evidence);
        write_json(
            &root.join("conclusion.json"),
            &json!({"cases":conclusions,"publicReleased":false,"physicalWindowsWindow":false,"oppoOperated":false}),
        );
        assert!(
            passed,
            "Real DAV process boundary failed; retain its original evidence"
        );
        assert!(
            fs::read(&normal_path).unwrap() == original,
            "Normal account changed externally"
        );
    }
    assert_eq!(conclusions.len(), 5);
}
