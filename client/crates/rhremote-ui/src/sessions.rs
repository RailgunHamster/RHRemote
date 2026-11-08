//! Independent desktop UI processes. Each process owns one native engine, recovery
//! loop and set of overlays; the browser owns shared preferences and supervision.
use rhremote_core::{Device, Saved};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::os::windows::process::CommandExt;
use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU16, Ordering},
    },
    time::{Duration, Instant},
};

const SWITCH: &str = "--desktop-session";
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct Launch {
    pub id: String,
    pub app_data: PathBuf,
    pub runtime: PathBuf,
    pub device: Device,
    pub window: Option<Value>,
    pub parent_port: u16,
    pub requested_at_ms: u64,
    pub statistics: bool,
}
pub(crate) struct ChildContext {
    pub launch: Launch,
    parent_token: String,
    baseline: Mutex<(
        bool,
        bool,
        Vec<String>,
        std::collections::BTreeMap<String, rhremote_core::ButtonPosition>,
    )>,
}
fn canonical_id(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
fn read_bounded(path: &Path, max: u64) -> Result<Vec<u8>, String> {
    let file = fs::File::open(path).map_err(|e| e.to_string())?;
    if file.metadata().map_err(|e| e.to_string())?.len() > max {
        return Err("会话文件过大".into());
    }
    let mut bytes = vec![];
    file.take(max + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() as u64 > max {
        return Err("会话文件过大".into());
    }
    Ok(bytes)
}
pub(crate) fn prepare_identity(launch: &Launch) -> Result<(), String> {
    let source = launch.app_data.join("engine-state/RHRemote/RHRemote.ini");
    let destination = launch.runtime.join("engine-state/RHRemote/RHRemote.ini");
    fs::create_dir_all(destination.parent().unwrap()).map_err(|e| e.to_string())?;
    fs::copy(source, destination).map_err(|e| format!("无法读取已配对的客户端身份：{e}"))?;
    rhremote_core::proxy::Settings::load(&launch.app_data)?.save(&launch.runtime)
}
impl ChildContext {
    pub fn from_args() -> Result<Option<Arc<Self>>, String> {
        let args: Vec<_> = std::env::args_os().collect();
        if !args.iter().any(|a| a == SWITCH) {
            return Ok(None);
        }
        if args.len() != 3 || args[1] != SWITCH {
            return Err("Invalid desktop session arguments".into());
        }
        let path = PathBuf::from(&args[2]);
        let launch: Launch = serde_json::from_slice(&read_bounded(&path, 262144)?)
            .map_err(|_| "Invalid desktop session envelope")?;
        if !canonical_id(&launch.id)
            || launch.device.is_self
            || !launch.device.paired
            || launch.parent_port == 0
            || !launch.app_data.is_absolute()
        {
            return Err("Invalid desktop session identity".into());
        }
        let expected = launch.app_data.join("sessions").join(&launch.id);
        if launch.runtime != expected
            || fs::canonicalize(&path).map_err(|e| e.to_string())?
                != fs::canonicalize(expected.join("launch.json")).map_err(|e| e.to_string())?
        {
            return Err("Invalid desktop session directory".into());
        }
        let parent_token = std::env::var("RHREMOTE_PARENT_CONTROL_TOKEN")
            .map_err(|_| "Missing session parent authentication")?;
        if !canonical_id(&parent_token) {
            return Err("Invalid session parent authentication".into());
        }
        let saved: Saved = serde_json::from_slice(&read_bounded(
            &launch.runtime.join("preferences.json"),
            4 * 1024 * 1024,
        )?)
        .map_err(|_| "Invalid session preferences")?;
        Ok(Some(Arc::new(Self {
            launch,
            parent_token,
            baseline: Mutex::new((
                saved.hide_keyboard_button,
                saved.simple_keyboard,
                saved.pinned_shortcuts,
                saved.button_positions,
            )),
        })))
    }
    pub fn identity(&self) -> Value {
        json!({"id":self.launch.id,"uuid":self.launch.device.uuid,"processId":std::process::id()})
    }
    pub fn persist(&self, saved: &Saved) -> Result<(), String> {
        let mut previous = self.baseline.lock().unwrap();
        let mut changes = json!({});
        if previous.0 != saved.hide_keyboard_button {
            changes["hideKeyboardButton"] = json!(saved.hide_keyboard_button);
        }
        if previous.1 != saved.simple_keyboard {
            changes["simpleKeyboard"] = json!(saved.simple_keyboard);
        }
        if previous.2 != saved.pinned_shortcuts {
            changes["pinnedShortcuts"] = json!(saved.pinned_shortcuts);
        }
        if previous.3 != saved.button_positions {
            changes["buttonPositions"] = json!(saved.button_positions);
        }
        if !changes.as_object().unwrap().is_empty() {
            let value = post(
                self.launch.parent_port,
                &self.parent_token,
                "session.preferences",
                json!({"sessionId":self.launch.id,"changes":changes}),
                Duration::from_secs(3),
            )?;
            if value["ok"] != true {
                return Err(value["error"].as_str().unwrap_or("会话偏好未保存").into());
            }
        }
        *previous = (
            saved.hide_keyboard_button,
            saved.simple_keyboard,
            saved.pinned_shortcuts.clone(),
            saved.button_positions.clone(),
        );
        Ok(())
    }
    pub fn return_to_devices(&self) -> Result<Value, String> {
        fs::write(self.launch.runtime.join("return-to-devices"), b"").map_err(|e| e.to_string())?;
        Ok(json!({"accepted":true}))
    }
    pub fn publish_endpoint(&self, port: u16) -> Result<(), String> {
        let path = self.launch.runtime.join("endpoint.json");
        let temp = path.with_extension("tmp");
        fs::write(
            &temp,
            json!({"port":port,"processId":std::process::id()}).to_string(),
        )
        .map_err(|e| e.to_string())?;
        fs::rename(temp, path).map_err(|e| e.to_string())
    }
}
fn agent(timeout: Duration) -> ureq::Agent {
    ureq::AgentBuilder::new()
        .try_proxy_from_env(false)
        .redirects(0)
        .timeout_connect(Duration::from_millis(150))
        .timeout(timeout)
        .build()
}
fn parse_response(response: ureq::Response) -> Result<Value, String> {
    let mut body = String::new();
    response
        .into_reader()
        .take(4 * 1024 * 1024 + 1)
        .read_to_string(&mut body)
        .map_err(|e| e.to_string())?;
    if body.len() > 4 * 1024 * 1024 {
        return Err("会话响应过大".into());
    }
    serde_json::from_str(&body).map_err(|_| "无效的会话响应".into())
}
fn post(port: u16, token: &str, id: &str, args: Value, timeout: Duration) -> Result<Value, String> {
    let response = agent(timeout)
        .post(&format!("http://127.0.0.1:{port}/v1/action"))
        .set("Authorization", &format!("Bearer {token}"))
        .send_json(json!({"id":id,"args":args}));
    parse_response(match response {
        Ok(r) => r,
        Err(ureq::Error::Status(_, r)) => r,
        Err(_) => return Err("远程窗口控制接口暂不可用".into()),
    })
}
fn get_state(port: u16, token: &str) -> Result<Value, String> {
    let response = agent(Duration::from_millis(600))
        .get(&format!("http://127.0.0.1:{port}/v1/state"))
        .set("Authorization", &format!("Bearer {token}"))
        .call()
        .map_err(|_| "远程窗口状态暂不可用")?;
    let response = parse_response(response)?;
    if response["ok"] != true {
        return Err("远程窗口拒绝状态读取".into());
    }
    Ok(response["state"].clone())
}
struct Entry {
    id: String,
    uuid: String,
    name: String,
    runtime: PathBuf,
    token: String,
    child: Mutex<Child>,
    port: AtomicU16,
    state: Mutex<Value>,
    alive: AtomicBool,
    activate: AtomicBool,
    close: AtomicBool,
    action_busy: AtomicBool,
    status_error: Mutex<String>,
}
impl Entry {
    fn owned_state(&self, port: u16) -> Result<Value, String> {
        let state = get_state(port, &self.token)?;
        let pid = self.child.lock().unwrap().id();
        if state["desktopSession"]["id"] != self.id
            || state["desktopSession"]["processId"] != pid
            || state["desktopSession"]["uuid"] != self.uuid
        {
            return Err("远程窗口身份不匹配".into());
        }
        Ok(state)
    }
    fn command(&self, id: &str, args: Value) -> Result<Value, String> {
        if !self.alive.load(Ordering::Acquire) {
            return Err("远程窗口已关闭".into());
        }
        let port = self.port.load(Ordering::Acquire);
        if port == 0 {
            return Err("远程窗口正在启动，请稍候".into());
        }
        // Recheck the original live process and its authenticated endpoint before
        // every command. A recycled TCP port must never receive another host's input.
        if self
            .child
            .lock()
            .unwrap()
            .try_wait()
            .map_err(|e| e.to_string())?
            .is_some()
        {
            return Err("远程窗口已退出".into());
        }
        self.owned_state(port)?;
        let value = post(port, &self.token, id, args, Duration::from_secs(15))?;
        if value["ok"] != true {
            return Err(value["error"].as_str().unwrap_or("会话操作失败").into());
        }
        Ok(value["result"].clone())
    }
    fn summary(&self) -> Value {
        let state = self.state.lock().unwrap();
        json!({"id":self.id,"uuid":self.uuid,"name":self.name,"processId":self.child.lock().unwrap().id(),"alive":self.alive.load(Ordering::Acquire),"phase":state["connection"]["phase"].as_str().unwrap_or("starting"),"streaming":state["streaming"]==true,"firstFrame":state["connection"]["firstFrame"]==true,"error":state["connection"]["error"].as_str().unwrap_or(""),"controlError":*self.status_error.lock().unwrap()})
    }
}
pub(crate) struct Manager {
    app_data: PathBuf,
    engine: PathBuf,
    executable: PathBuf,
    parent_port: u16,
    parent_token: String,
    entries: Mutex<Vec<Arc<Entry>>>,
    closing: AtomicBool,
    return_requested: Arc<AtomicBool>,
}
impl Manager {
    pub fn new(
        app_data: PathBuf,
        engine: PathBuf,
        parent_port: u16,
        parent_token: String,
    ) -> Result<Arc<Self>, String> {
        Ok(Arc::new(Self {
            app_data,
            engine,
            executable: std::env::current_exe().map_err(|e| e.to_string())?,
            parent_port,
            parent_token,
            entries: Mutex::new(vec![]),
            closing: AtomicBool::new(false),
            return_requested: Arc::new(AtomicBool::new(false)),
        }))
    }
    pub fn snapshot(&self) -> Value {
        let entries = self.entries.lock().unwrap();
        let items: Vec<_> = entries
            .iter()
            .filter(|e| e.alive.load(Ordering::Acquire))
            .map(|e| e.summary())
            .collect();
        let mut errors = serde_json::Map::new();
        for e in entries.iter().filter(|e| !e.alive.load(Ordering::Acquire)) {
            let summary = e.summary();
            let err = summary["error"]
                .as_str()
                .filter(|s| !s.is_empty())
                .or_else(|| summary["controlError"].as_str().filter(|s| !s.is_empty()));
            if summary["phase"] == "failed" || err.is_some() {
                errors.insert(e.uuid.clone(), json!(err.unwrap_or("连接失败")));
            }
        }
        json!({"busy":!items.is_empty(),"closing":self.closing.load(Ordering::Acquire),"items":items,"errors":errors})
    }
    pub fn busy(&self) -> bool {
        self.entries
            .lock()
            .unwrap()
            .iter()
            .any(|e| e.alive.load(Ordering::Acquire))
    }
    pub fn take_return(&self) -> bool {
        self.return_requested.swap(false, Ordering::AcqRel)
    }
    fn entry(&self, args: &Value) -> Result<Arc<Entry>, String> {
        let id = args["sessionId"].as_str().ok_or("sessionId required")?;
        self.entries
            .lock()
            .unwrap()
            .iter()
            .find(|e| e.id == id && e.alive.load(Ordering::Acquire))
            .cloned()
            .ok_or("远程窗口已关闭或不存在".into())
    }
    pub fn open(&self, args: &Value, statistics: bool) -> Result<Value, String> {
        let device: Device =
            serde_json::from_value(args["device"].clone()).map_err(|_| "无效的远程设备")?;
        if device.is_self || !device.paired {
            return Err("请先配对远程设备".into());
        }
        let mut entries = self.entries.lock().unwrap();
        if self.closing.load(Ordering::Acquire) {
            return Err("应用正在关闭".into());
        }
        if let Some(entry) = entries
            .iter()
            .find(|e| e.uuid.eq_ignore_ascii_case(&device.uuid) && e.alive.load(Ordering::Acquire))
        {
            entry.activate.store(true, Ordering::Release);
            return Ok(json!({"accepted":true,"sessionId":entry.id,"reused":true}));
        }
        entries.retain(|entry| {
            entry.alive.load(Ordering::Acquire)
                || (!entry.uuid.eq_ignore_ascii_case(&device.uuid)
                    && entry.summary()["phase"] == "failed")
        });
        let id = rhremote_core::random_token()?;
        let token = rhremote_core::random_token()?;
        let runtime = self.app_data.join("sessions").join(&id);
        crate::platform_paths::prepare_data(&runtime).map_err(|e| e.to_string())?;
        let saved: Saved =
            serde_json::from_value(args["saved"].clone()).map_err(|_| "无效的会话配置")?;
        fs::write(
            runtime.join("preferences.json"),
            serde_json::to_vec(&saved).map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;
        let launch = Launch {
            id: id.clone(),
            app_data: self.app_data.clone(),
            runtime: runtime.clone(),
            device: device.clone(),
            window: args.get("window").cloned(),
            parent_port: self.parent_port,
            requested_at_ms: rhremote_core::now_ms(),
            statistics,
        };
        let envelope = runtime.join("launch.json");
        fs::write(
            &envelope,
            serde_json::to_vec(&launch).map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;
        let child = Command::new(&self.executable)
            .arg(SWITCH)
            .arg(&envelope)
            .env("RHREMOTE_DATA_DIR", &runtime)
            .env("RHREMOTE_NATIVE_ENGINE", &self.engine)
            .env("RHREMOTE_CONTROL_TOKEN", &token)
            .env("RHREMOTE_PARENT_CONTROL_TOKEN", &self.parent_token)
            .env_remove("RHREMOTE_CONTROL_PORT")
            .env_remove("RHREMOTE_TARGET_UUID")
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .map_err(|e| format!("无法打开远程窗口：{e}"))?;
        let name = if device.display_name.is_empty() {
            device.name
        } else {
            device.display_name
        };
        let entry = Arc::new(Entry {
            id: id.clone(),
            uuid: device.uuid,
            name,
            runtime,
            token,
            child: Mutex::new(child),
            port: AtomicU16::new(0),
            state: Mutex::new(Value::Null),
            alive: AtomicBool::new(true),
            activate: AtomicBool::new(false),
            close: AtomicBool::new(false),
            action_busy: AtomicBool::new(false),
            status_error: Mutex::new(String::new()),
        });
        entries.push(entry.clone());
        drop(entries);
        let return_requested = self.return_requested.clone();
        std::thread::spawn(move || monitor(entry, return_requested));
        Ok(json!({"accepted":true,"sessionId":id,"reused":false}))
    }
    pub fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        if id == "session.list" {
            return Ok(self.snapshot());
        }
        let entry = self.entry(args)?;
        match id {
            "session.state" => {
                let state = entry.state.lock().unwrap();
                Ok(if state.is_object() {
                    state.clone()
                } else {
                    json!({"desktopSession":{"id":entry.id,"uuid":entry.uuid,"processId":entry.child.lock().unwrap().id()},"streaming":false,"connecting":true,"connection":{"uuid":entry.uuid,"name":entry.name,"phase":"starting","firstFrame":false,"error":""}})
                })
            }
            "session.activate" => {
                entry.activate.store(true, Ordering::Release);
                Ok(json!({"accepted":true}))
            }
            "session.close" => {
                entry.close.store(true, Ordering::Release);
                Ok(json!({"accepted":true}))
            }
            "session.preferences.validate" => Ok(json!({"uuid":entry.uuid})),
            "session.action" => {
                let action = args["action"].as_str().ok_or("action required")?;
                if !(action.starts_with("stream.")
                    || action.starts_with("view.ui.")
                    || matches!(
                        action,
                        "connection.retry"
                            | "view.snapshot"
                            | "view.toolbar.snapshot"
                            | "view.picker.snapshot"
                            | "view.keyboard.snapshot"
                            | "window.activate"
                            | "window.close"
                    ))
                {
                    return Err("不支持此远程窗口操作".into());
                }
                if action == "window.close" {
                    entry.close.store(true, Ordering::Release);
                    return Ok(json!({"accepted":true}));
                }
                if action == "stream.disconnect" {
                    return entry.command(action, json!({}));
                }
                if entry.action_busy.swap(true, Ordering::AcqRel) {
                    return Err("该窗口的上一项操作尚未完成".into());
                }
                struct Release(Arc<Entry>);
                impl Drop for Release {
                    fn drop(&mut self) {
                        self.0.action_busy.store(false, Ordering::Release);
                    }
                }
                let _release = Release(entry.clone());
                entry.command(action, args.get("args").cloned().unwrap_or(json!({})))
            }
            _ => Err("unknown session action".into()),
        }
    }
    pub fn close_all(&self) -> Value {
        self.closing.store(true, Ordering::Release);
        for entry in self.entries.lock().unwrap().iter() {
            entry.close.store(true, Ordering::Release);
        }
        json!({"cleanupPending":self.busy()})
    }
}
fn monitor(entry: Arc<Entry>, return_requested: Arc<AtomicBool>) {
    let began = Instant::now();
    let mut close_sent = false;
    loop {
        match entry.child.lock().unwrap().try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    *entry.status_error.lock().unwrap() = format!("远程窗口异常退出：{status}");
                }
                entry.alive.store(false, Ordering::Release);
                if entry.runtime.join("return-to-devices").is_file() {
                    return_requested.store(true, Ordering::Release);
                }
                break;
            }
            Err(error) => {
                *entry.status_error.lock().unwrap() = error.to_string();
            }
            _ => {}
        }
        let endpoint = read_bounded(&entry.runtime.join("endpoint.json"), 4096)
            .ok()
            .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok());
        let port = endpoint.and_then(|v| {
            (v["processId"].as_u64() == Some(u64::from(entry.child.lock().unwrap().id())))
                .then(|| {
                    v["port"]
                        .as_u64()
                        .and_then(|p| u16::try_from(p).ok())
                        .filter(|p| *p > 0)
                })
                .flatten()
        });
        if let Some(port) = port {
            match entry.owned_state(port) {
                Ok(state) => {
                    let terminal_without_toolbar = state["connection"]["busy"] != true
                        && state["connecting"] != true
                        && state["streaming"] != true
                        && (matches!(
                            state["connection"]["phase"].as_str(),
                            Some("ended" | "cancelled")
                        ) || (state["connection"]["phase"] == "failed"
                            && state["toolbar"]["visible"] != true));
                    *entry.state.lock().unwrap() = state;
                    entry.port.store(port, Ordering::Release);
                    entry.status_error.lock().unwrap().clear();
                    if terminal_without_toolbar {
                        entry.close.store(true, Ordering::Release);
                    }
                    if entry.close.load(Ordering::Acquire) && !close_sent {
                        close_sent = entry.command("window.close", json!({})).is_ok();
                    } else if entry.activate.swap(false, Ordering::AcqRel) {
                        let _ = entry.command("window.activate", json!({}));
                    }
                }
                Err(error) => {
                    *entry.status_error.lock().unwrap() = error;
                }
            }
        } else if began.elapsed() > Duration::from_secs(30) {
            *entry.status_error.lock().unwrap() = "远程窗口启动超时".into();
        }
        if entry.close.load(Ordering::Acquire) {
            // EOF is also observed if the browser crashes. It is a graceful,
            // process-owned shutdown signal and cannot target a reused PID.
            entry.child.lock().unwrap().stdin.take();
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rhremote_core::{Controller, Platform};
    use std::sync::mpsc;
    struct Host {
        identity: Mutex<Value>,
        calls: Mutex<Vec<String>>,
        slow: Option<(mpsc::Sender<()>, Mutex<mpsc::Receiver<()>>)>,
    }
    impl Platform for Host {
        fn session_only(&self) -> bool {
            true
        }
        fn session_identity(&self) -> Option<Value> {
            Some(self.identity.lock().unwrap().clone())
        }
        fn execute(&self, id: &str, _: &Value) -> Result<Value, String> {
            self.calls.lock().unwrap().push(id.into());
            if id == "stream.audio.volume" {
                if let Some((entered, release)) = &self.slow {
                    entered.send(()).unwrap();
                    release
                        .lock()
                        .unwrap()
                        .recv_timeout(Duration::from_secs(5))
                        .unwrap();
                }
            }
            Ok(json!({"accepted":true,"cleanupPending":false,"volume":37}))
        }
    }
    struct Fixture {
        root: PathBuf,
        manager: Arc<Manager>,
        entries: Vec<Arc<Entry>>,
    }
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "rhremote-multi-test-{}",
                rhremote_core::random_token().unwrap()
            ));
            fs::create_dir_all(&root).unwrap();
            let manager = Manager::new(
                root.clone(),
                PathBuf::from("unused-test-engine.exe"),
                1,
                rhremote_core::random_token().unwrap(),
            )
            .unwrap();
            Self {
                root,
                manager,
                entries: vec![],
            }
        }
        fn add(
            &mut self,
            uuid: &str,
            slow: Option<(mpsc::Sender<()>, Mutex<mpsc::Receiver<()>>)>,
        ) -> (Arc<Entry>, Arc<Controller>, Arc<Host>) {
            let child = Command::new("cmd.exe")
                .args(["/d", "/c", "set /p RHREMOTE_OWNED_SESSION_FIXTURE="])
                .creation_flags(0x08000000)
                .stdin(Stdio::piped())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap();
            let id = rhremote_core::random_token().unwrap();
            let runtime = self.root.join(&id);
            fs::create_dir(&runtime).unwrap();
            let host = Arc::new(Host {
                identity: Mutex::new(json!({"id":id,"uuid":uuid,"processId":child.id()})),
                calls: Mutex::new(vec![]),
                slow,
            });
            let controller =
                Controller::new("windows", runtime.join("preferences.json"), host.clone()).unwrap();
            controller.replace_devices(vec![Device {
                uuid: uuid.into(),
                name: uuid.into(),
                address: "192.0.2.1".into(),
                display_name: String::new(),
                addresses: vec![],
                online: true,
                paired: true,
                is_self: false,
            }]);
            assert_eq!(
                controller.dispatch("device.connect", json!({"uuid":uuid}))["ok"],
                true
            );
            controller.stream_state(
                true,
                json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}),
            );
            let token = rhremote_core::random_token().unwrap();
            let (port, _) =
                rhremote_core::start_http_endpoint(controller.clone(), 0, token.clone()).unwrap();
            let entry = Arc::new(Entry {
                id,
                uuid: uuid.into(),
                name: uuid.into(),
                runtime,
                token,
                child: Mutex::new(child),
                port: AtomicU16::new(port),
                state: Mutex::new(controller.state()),
                alive: AtomicBool::new(true),
                activate: AtomicBool::new(false),
                close: AtomicBool::new(false),
                action_busy: AtomicBool::new(false),
                status_error: Mutex::new(String::new()),
            });
            self.manager.entries.lock().unwrap().push(entry.clone());
            self.entries.push(entry.clone());
            (entry, controller, host)
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            for entry in &self.entries {
                let mut child = entry.child.lock().unwrap();
                child.stdin.take();
                let until = Instant::now() + Duration::from_secs(2);
                while child.try_wait().unwrap().is_none() {
                    if Instant::now() > until {
                        let _ = child.kill();
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
                let _ = child.wait();
                entry.alive.store(false, Ordering::Release);
            }
            let _ = fs::remove_dir_all(&self.root);
        }
    }
    #[test]
    fn two_owned_sessions_keep_input_keyboard_and_disconnect_separate() {
        let mut f = Fixture::new();
        let (a, ca, ha) = f.add("a", None);
        let (b, cb, hb) = f.add("b", None);
        assert_ne!(
            a.port.load(Ordering::Relaxed),
            b.port.load(Ordering::Relaxed)
        );
        f.manager.execute("session.action",&json!({"sessionId":a.id,"action":"stream.keyboard.floating.set","args":{"enabled":true}})).unwrap();
        assert_eq!(ca.state()["floatingKeyboard"], true);
        assert_eq!(cb.state()["floatingKeyboard"], false);
        f.manager.execute("session.action",&json!({"sessionId":a.id,"action":"stream.keyboard.press","args":{"key":"ctrl-left"}})).unwrap();
        assert_eq!(cb.state()["keyboardModifiers"], json!([]));
        f.manager.execute("session.action",&json!({"sessionId":b.id,"action":"stream.shortcut","args":{"shortcut":"ctrl-alt-delete"}})).unwrap();
        assert!(
            hb.calls
                .lock()
                .unwrap()
                .iter()
                .any(|id| id == "stream.shortcut")
        );
        assert!(
            !ha.calls
                .lock()
                .unwrap()
                .iter()
                .any(|id| id == "stream.shortcut")
        );
        f.manager
            .execute(
                "session.action",
                &json!({"sessionId":a.id,"action":"stream.disconnect"}),
            )
            .unwrap();
        assert_eq!(ca.state()["connection"]["phase"], "cancelling");
        assert_eq!(cb.state()["connection"]["phase"], "connected");
        ca.stream_state(
            false,
            json!({"phase":"disconnected","nativeCleanupPending":false}),
        );
        assert_eq!(ca.state()["page"], "connection");
        assert_eq!(ca.state()["floatingKeyboard"], false);
        assert_eq!(f.manager.snapshot()["items"].as_array().unwrap().len(), 2);
    }
    #[test]
    fn duplicate_host_reuses_its_window_and_closed_session_ids_do_not_target_a_replacement() {
        let mut f = Fixture::new();
        let (a, _, _) = f.add("host-a", None);
        let (b, _, _) = f.add("host-b", None);
        *a.state.lock().unwrap() = Value::Null;
        let starting = f
            .manager
            .execute("session.state", &json!({"sessionId":a.id}))
            .unwrap();
        assert_eq!(starting["connecting"], true);
        assert_eq!(starting["desktopSession"]["uuid"], "host-a");
        let device = json!({"uuid":"HOST-A","name":"same host","address":"100.64.0.1","online":true,"paired":true});
        let result = f.manager.open(&json!({"device":device}), false).unwrap();
        assert_eq!(result["sessionId"], a.id);
        assert_eq!(result["reused"], true);
        assert!(a.activate.load(Ordering::Acquire));
        assert!(!b.activate.load(Ordering::Acquire));
        a.alive.store(false, Ordering::Release);
        let (replacement, _, _) = f.add("host-a", None);
        assert!(
            f.manager
                .execute("session.close", &json!({"sessionId":a.id}))
                .is_err()
        );
        assert!(!replacement.close.load(Ordering::Acquire));
        f.manager
            .execute("session.close", &json!({"sessionId":b.id}))
            .unwrap();
        assert!(b.close.load(Ordering::Acquire));
        assert!(!replacement.close.load(Ordering::Acquire));
    }
    #[test]
    fn authenticated_endpoint_must_also_match_the_original_process_and_session() {
        let mut f = Fixture::new();
        let (a, _, ha) = f.add("a", None);
        let (b, _, hb) = f.add("b", None);
        *ha.identity.lock().unwrap() =
            json!({"id":a.id,"uuid":"a","processId":b.child.lock().unwrap().id()});
        assert!(
            a.command("stream.shortcut", json!({"shortcut":"ctrl-alt-delete"}))
                .is_err()
        );
        a.port
            .store(b.port.load(Ordering::Acquire), Ordering::Release);
        assert!(
            a.command("stream.shortcut", json!({"shortcut":"ctrl-alt-delete"}))
                .is_err()
        );
        assert!(
            !ha.calls
                .lock()
                .unwrap()
                .iter()
                .any(|id| id == "stream.shortcut")
        );
        assert!(
            !hb.calls
                .lock()
                .unwrap()
                .iter()
                .any(|id| id == "stream.shortcut")
        );
    }
    #[test]
    fn a_slow_control_on_one_host_does_not_block_another_or_the_parent_api() {
        let mut f = Fixture::new();
        let (entered, waiting) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let (a, _, _) = f.add("a", Some((entered, Mutex::new(released))));
        let (b, _, hb) = f.add("b", None);
        let platform = Arc::new(
            crate::platform::NativePlatform::new(
                PathBuf::from("unused-parent-engine.exe"),
                rhremote_core::random_token().unwrap(),
                f.root.join("browser"),
            )
            .unwrap(),
        );
        assert!(platform.sessions.set(f.manager.clone()).is_ok());
        let parent =
            Controller::new("windows", f.root.join("browser/preferences.json"), platform).unwrap();
        let token = rhremote_core::random_token().unwrap();
        let (port, _) = rhremote_core::start_http_endpoint(parent, 0, token.clone()).unwrap();
        let parent_token = token.clone();
        let aid = a.id.clone();
        let pending = std::thread::spawn(move || {
            post(
                port,
                &parent_token,
                "session.action",
                json!({"sessionId":aid,"action":"stream.audio.volume","args":{"volume":37}}),
                Duration::from_secs(5),
            )
        });
        waiting.recv_timeout(Duration::from_secs(2)).unwrap();
        let began = Instant::now();
        let state = get_state(port, &token).unwrap();
        assert_eq!(
            state["desktopSessions"]["items"].as_array().unwrap().len(),
            2
        );
        let other=post(port,&token,"session.action",json!({"sessionId":b.id,"action":"stream.shortcut","args":{"shortcut":"ctrl-alt-delete"}}),Duration::from_secs(2)).unwrap();
        assert_eq!(other["ok"], true);
        assert!(began.elapsed() < Duration::from_secs(2));
        assert!(
            hb.calls
                .lock()
                .unwrap()
                .iter()
                .any(|id| id == "stream.shortcut")
        );
        release.send(()).unwrap();
        assert_eq!(pending.join().unwrap().unwrap()["ok"], true);
    }
    #[test]
    fn parent_close_waits_for_every_owned_window_and_eof_closes_only_the_requested_child() {
        let mut f = Fixture::new();
        let (a, _, _) = f.add("a", None);
        let (b, _, _) = f.add("b", None);
        for e in [&a, &b] {
            fs::write(e.runtime.join("endpoint.json"),json!({"port":e.port.load(Ordering::Acquire),"processId":e.child.lock().unwrap().id()}).to_string()).unwrap();
            let e = e.clone();
            let returning = f.manager.return_requested.clone();
            std::thread::spawn(move || monitor(e, returning));
        }
        f.manager
            .execute("session.close", &json!({"sessionId":a.id}))
            .unwrap();
        let until = Instant::now() + Duration::from_secs(3);
        while a.alive.load(Ordering::Acquire) {
            assert!(Instant::now() < until);
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(b.alive.load(Ordering::Acquire));
        assert!(f.manager.busy());
        assert_eq!(f.manager.close_all()["cleanupPending"], true);
        let until = Instant::now() + Duration::from_secs(3);
        while f.manager.busy() {
            assert!(Instant::now() < until);
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(!b.alive.load(Ordering::Acquire));
        assert!(f.manager.open(&json!({"device":{"uuid":"c","name":"c","address":"192.0.2.3","paired":true,"online":true}}),false).is_err());
    }
    #[test]
    fn browser_buttons_activate_the_matching_session_and_repaint_without_a_core_revision() {
        use crate::{Commands, MainWindow, view_state::MainView};
        use i_slint_backend_testing::ElementHandle;
        use slint::{
            ComponentHandle, LogicalPosition, LogicalSize, Model,
            platform::{PointerEventButton, WindowEvent},
        };
        use std::{cell::RefCell, rc::Rc};
        i_slint_backend_testing::init_no_event_loop();
        let mut f = Fixture::new();
        let (a, _, _) = f.add("a", None);
        let (b, _, _) = f.add("b", None);
        let platform = Arc::new(
            crate::platform::NativePlatform::new(
                PathBuf::from("unused-ui-engine.exe"),
                rhremote_core::random_token().unwrap(),
                f.root.join("browser"),
            )
            .unwrap(),
        );
        platform.sessions.set(f.manager.clone()).ok().unwrap();
        let controller =
            Controller::new("windows", f.root.join("browser/preferences.json"), platform).unwrap();
        controller.replace_devices(
            ["a", "b"]
                .map(|uuid| Device {
                    uuid: uuid.into(),
                    name: uuid.into(),
                    address: "192.0.2.1".into(),
                    display_name: String::new(),
                    addresses: vec![],
                    online: true,
                    paired: true,
                    is_self: false,
                })
                .to_vec(),
        );
        controller.dispatch("device.select", json!({"uuid":"a"}));
        let ui = MainWindow::new().unwrap();
        ui.window().set_size(LogicalSize::new(1180., 780.));
        let mut view = MainView::new(&ui);
        let clicks = Rc::new(RefCell::new(vec![]));
        let received = clicks.clone();
        ui.global::<Commands>().on_trigger(move |id, arg| {
            received
                .borrow_mut()
                .push((id.to_string(), arg.to_string()))
        });
        ui.show().unwrap();
        for (uuid, entry) in [("a", &a), ("b", &b)] {
            controller.dispatch("device.select", json!({"uuid":uuid}));
            view.refresh(&ui, &controller.state(), |uuid| controller.profile(uuid));
            let button = ElementHandle::find_by_accessible_label(&ui, "切换到窗口")
                .next()
                .unwrap();
            let origin = button.absolute_position();
            let size = button.size();
            let position =
                LogicalPosition::new(origin.x + size.width / 2., origin.y + size.height / 2.);
            ui.window().dispatch_event(WindowEvent::PointerPressed {
                position,
                button: PointerEventButton::Left,
            });
            ui.window().dispatch_event(WindowEvent::PointerReleased {
                position,
                button: PointerEventButton::Left,
            });
            let (id, arg) = clicks.borrow_mut().pop().unwrap();
            assert_eq!(id, "session.activate");
            assert_eq!(
                crate::args_for(&id, &arg, &controller.state())["sessionId"],
                entry.id
            );
        }
        let revision = controller.state()["revision"].clone();
        b.state.lock().unwrap()["connection"]["phase"] = json!("reconnecting");
        view.refresh(&ui, &controller.state(), |uuid| controller.profile(uuid));
        assert_eq!(controller.state()["revision"], revision);
        assert_eq!(
            ui.get_devices()
                .iter()
                .find(|d| d.uuid == "b")
                .unwrap()
                .status,
            "正在恢复连接"
        );
        assert_eq!(ui.get_selected_session_hint(), "连接中断，正在自动恢复…");
        assert!(
            ElementHandle::find_by_accessible_label(&ui, "断开连接")
                .next()
                .is_some()
        );
        b.state.lock().unwrap()["connection"]["phase"] = json!("failed");
        b.state.lock().unwrap()["connection"]["error"] = json!("无法连接目标主机");
        b.alive.store(false, Ordering::Release);
        view.refresh(&ui, &controller.state(), |uuid| controller.profile(uuid));
        assert!(!ui.get_selected_has_session());
        assert!(ui.get_selected_session_failed());
        assert_eq!(ui.get_selected_session_hint(), "无法连接目标主机");
        assert!(
            ElementHandle::find_by_accessible_label(&ui, "连接")
                .next()
                .is_some()
        );
        ui.hide().unwrap();
    }
    #[test]
    fn per_window_identity_snapshots_do_not_write_back_stale_pairing_or_proxy_settings() {
        let f = Fixture::new();
        let identity = f.root.join("engine-state/RHRemote/RHRemote.ini");
        fs::create_dir_all(identity.parent().unwrap()).unwrap();
        fs::write(&identity, b"[fixture]\npaired=old\n").unwrap();
        let mut proxy = rhremote_core::proxy::Settings::default();
        proxy.enabled = true;
        proxy.remote = false;
        proxy.save(&f.root).unwrap();
        let device = Device {
            uuid: "host".into(),
            name: "Host".into(),
            display_name: String::new(),
            address: "192.0.2.1".into(),
            addresses: vec![],
            online: true,
            paired: true,
            is_self: false,
        };
        let a = Launch {
            id: rhremote_core::random_token().unwrap(),
            app_data: f.root.clone(),
            runtime: f.root.join("session-a"),
            device: device.clone(),
            window: None,
            parent_port: 1,
            requested_at_ms: 0,
            statistics: false,
        };
        let b = Launch {
            runtime: f.root.join("session-b"),
            id: rhremote_core::random_token().unwrap(),
            ..a.clone()
        };
        prepare_identity(&a).unwrap();
        fs::write(&identity, b"[fixture]\npaired=new\n").unwrap();
        prepare_identity(&b).unwrap();
        let relative = "engine-state/RHRemote/RHRemote.ini";
        fs::write(a.runtime.join(relative), b"[fixture]\npaired=session-a\n").unwrap();
        assert_eq!(fs::read(&identity).unwrap(), b"[fixture]\npaired=new\n");
        assert_eq!(
            fs::read(b.runtime.join(relative)).unwrap(),
            b"[fixture]\npaired=new\n"
        );
        assert_eq!(
            rhremote_core::proxy::Settings::load(&a.runtime).unwrap(),
            proxy
        );
    }
}
