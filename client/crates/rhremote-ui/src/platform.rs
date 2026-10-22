use rhremote_core::{Device, Platform};
use serde_json::{Value, json};
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::Mutex,
    time::{Duration, Instant},
};

struct Recovery {
    args: Value,
    policy: crate::recovery::Policy,
}

#[cfg(all(test, windows))]
mod recovery_transport_tests {
    use super::*;
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
        *p.recovery.lock().unwrap() = Some(Recovery {
            args: json!({"profile":{}}),
            policy: crate::recovery::Policy::default(),
        });
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
        p.stop_owned().unwrap();
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
            for request in server.incoming_requests().take(3) {
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
        *p.recovery.lock().unwrap() = Some(Recovery {
            args: json!({"profile":{}}),
            policy: crate::recovery::Policy::default(),
        });
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
}

pub struct NativePlatform {
    pub engine: PathBuf,
    native_data: PathBuf,
    session_status: PathBuf,
    pub devices: Mutex<Vec<Device>>,
    child: Mutex<Option<Child>>,
    retiring: Mutex<Vec<(Child, Instant)>>,
    state_agent: ureq::Agent,
    pairing: Mutex<Option<(u64, Device)>>,
    recovery: Mutex<Option<Recovery>>,
    token: String,
    port: u16,
    pub sync: Mutex<crate::config_sync::ConfigSync>,
    device_operation: Mutex<()>,
    updates: Mutex<crate::updates::Updates>,
    pub statistics: Mutex<crate::statistics::Statistics>,
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
            child: Mutex::new(None),
            retiring: Mutex::new(Vec::new()),
            state_agent: ureq::AgentBuilder::new()
                .try_proxy_from_env(false)
                .redirects(0)
                .timeout_connect(Duration::from_millis(100))
                .timeout_read(Duration::from_millis(400))
                .timeout_write(Duration::from_millis(400))
                .timeout(Duration::from_millis(500))
                .build(),
            pairing: Mutex::new(None),
            recovery: Mutex::new(None),
            token,
            port: std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
                .and_then(|s| s.local_addr())
                .map_err(|e| e.to_string())?
                .port(),
            sync: Mutex::new(crate::config_sync::ConfigSync::new(data)?),
            device_operation: Mutex::new(()),
            updates: Mutex::new(crate::updates::Updates::new()),
            statistics: Mutex::new(statistics),
        })
    }
    fn command(&self) -> Command {
        let mut command = Command::new(&self.engine);
        command
            .env_remove("RHREMOTE_CONTROL_PORT")
            .env_remove("RHREMOTE_CONTROL_TOKEN")
            .env_remove("RHREMOTE_TARGET_UUID");
        command.current_dir(&self.native_data);
        command
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        #[cfg(windows)]
        command.creation_flags(0x08000000);
        command
    }
    fn probe(&self, device: &Device) -> Device {
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
        let url = format!(
            "http://{}:47989/serverinfo?uniqueid=0123456789ABCDEF",
            d.address
        );
        if let Ok(response) = ureq::get(&url).timeout(Duration::from_secs(2)).call() {
            if let Ok(text) = response.into_string() {
                if let Ok(doc) = roxmltree::Document::parse(&text) {
                    if let Some(uuid) = doc
                        .descendants()
                        .find(|n| n.has_tag_name("uniqueid"))
                        .and_then(|n| n.text())
                    {
                        if uuid.eq_ignore_ascii_case(&d.uuid) {
                            d.online = true;
                        }
                    }
                }
            }
        }
        if d.online {
            let child = self
                .command()
                .args(["list", &d.address])
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
        let (attempt, device) = pending.take()?;
        child.take();
        drop(child);
        drop(pending);
        let outcome: Value = fs::read(&self.session_status)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or(Value::Null);
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
    fn spawn_stream(&self, args: &Value) -> Result<Child, String> {
        let p = &args["profile"];
        let trace = self.statistics.lock().unwrap().session(p)?;
        fs::write(
            &self.session_status,
            b"{\"streaming\":false,\"phase\":\"connecting\",\"error\":\"\"}",
        )
        .map_err(|e| e.to_string())?;
        let address = args["device"]["address"]
            .as_str()
            .ok_or("address required")?;
        let resolution = format!("{}x{}", p["width"], p["height"]);
        let fps = p["fps"].to_string();
        let rate = (p["bitrateMbps"].as_u64().unwrap_or(25) * 1000).to_string();
        let mut command = self.command();
        command.env_remove("RHREMOTE_STATISTICS_FILE");
        if let Some(path) = trace {
            command.env("RHREMOTE_STATISTICS_FILE", path);
        }
        command
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
                "--packet-size",
                &p["packetSize"].as_u64().unwrap_or(1392).to_string(),
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
                "RHREMOTE_AUDIO_VOLUME",
                p["volume"].as_u64().unwrap_or(100).to_string(),
            )
            .spawn()
            .map_err(|e| e.to_string())
    }
    pub fn state(&self) -> Result<Value, String> {
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
        let alive = slot
            .as_mut()
            .is_some_and(|c| c.try_wait().ok().flatten().is_none());
        // The adapter publishes this only after LiStopConnection and session cleanup.
        // Its HTTP listener must also be gone before the replacement reuses the port.
        let released = alive
            && http_unavailable
            && value["transportReleased"] == true
            && value["phase"] == "transport_closed";
        {
            let mut retired = self.retiring.lock().unwrap();
            retired.retain_mut(|(child, since)| {
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
        }
        if let Some(child) = slot.as_ref() {
            value["streamProcessId"] = json!(child.id());
        }
        if !alive {
            value["streaming"] = json!(false);
            value["streamWindow"] = json!("0");
        }
        if let Some(r) = recovery.as_mut() {
            if alive && value["streaming"] == true {
                if value["displays"].is_array() && value["selectedDisplay"].is_object() {
                    r.args["resumeSelection"] = json!({"displays":value["displays"],"selected":value["selectedDisplay"],"initialCaptureIndex":1});
                }
                for key in ["audioEnabled", "volume"] {
                    if !value[key].is_null() {
                        r.args["profile"][key] = value[key].clone();
                    }
                }
            }
            match r.policy.observe(
                rhremote_core::now_ms(),
                alive && !released,
                alive && value["streaming"] == true && value["firstFrame"] == true,
                value["retryEligible"] == true,
            ) {
                crate::recovery::Decision::Launch => {
                    r.args["recoveryAttempt"] = json!(r.policy.attempts);
                    if let Some(mut old) = slot.take() {
                        if old.try_wait().ok().flatten().is_some() {
                            let _ = old.wait();
                        } else {
                            self.retiring.lock().unwrap().push((old, Instant::now()));
                        }
                    }
                    match self.spawn_stream(&r.args) {
                        Ok(child) => {
                            *slot = Some(child);
                            value["streaming"] = json!(false);
                            value["phase"] = json!("reconnecting");
                            value["error"] = json!("");
                            value["firstFrame"] = json!(false);
                        }
                        Err(e) => {
                            value["error"] = json!(e);
                            value["phase"] = json!("reconnecting");
                        }
                    }
                }
                crate::recovery::Decision::Waiting => {
                    value["phase"] = json!("reconnecting");
                    value["error"] = json!("");
                }
                crate::recovery::Decision::Exhausted => {
                    if let Some(mut child) = slot.take() {
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                    value["streaming"] = json!(false);
                    value["phase"] = json!("error");
                    value["error"] = json!("自动恢复未成功，请检查主机后重新连接");
                    *recovery = None;
                }
                crate::recovery::Decision::Idle => {}
            }
            if let Some(r) = recovery.as_ref() {
                value["reconnectAttempts"] = json!(r.policy.attempts);
            }
        }
        if !alive
            && value["phase"] != "reconnecting"
            && matches!(
                value["phase"].as_str(),
                Some("connecting" | "starting" | "connected")
            )
        {
            value["phase"] = json!("error");
            value["error"] = json!("串流进程已退出，请重新连接");
        }
        drop(slot);
        drop(recovery);
        value["tracing"] = self.statistics.lock().unwrap().state();
        Ok(value)
    }
    fn stop_owned(&self) -> Result<Value, String> {
        self.recovery.lock().unwrap().take();
        if let Some(mut child) = self.child.lock().unwrap().take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        for (mut child, _) in self.retiring.lock().unwrap().drain(..) {
            let _ = child.kill();
            let _ = child.wait();
        }
        fs::write(
            &self.session_status,
            b"{\"streaming\":false,\"phase\":\"disconnected\",\"error\":\"\"}",
        )
        .map_err(|e| e.to_string())?;
        Ok(json!({"cancelled":true}))
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
        let _device_guard = if action.starts_with("devices.") || action == "device.add.submit" {
            Some(self.device_operation.lock().unwrap())
        } else {
            None
        };
        match action {
            "engine.close" => self.stop_owned(),
            "statistics.state" => Ok(self.statistics.lock().unwrap().state()),
            "statistics.configure" => {
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
                let mut recovery = self.recovery.lock().unwrap();
                let mut slot = self.child.lock().unwrap();
                if let Some(child) = slot.as_mut() {
                    if child.try_wait().map_err(|e| e.to_string())?.is_none() {
                        return Err("native session already running".into());
                    }
                }
                let child = self.spawn_stream(args)?;
                *slot = Some(child);
                *recovery=Some(Recovery { args:args.clone(), policy:crate::recovery::Policy::default() });
                Ok(json!({"accepted":true}))
            }
            "device.pair" => {
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
                fs::write(
                    &self.session_status,
                    b"{\"streaming\":false,\"phase\":\"pairing\",\"error\":\"\"}",
                )
                .map_err(|e| e.to_string())?;
                *slot = Some(
                    self.command()
                        .env("RHREMOTE_PAIR_OPERATION", "1")
                        .env("RHREMOTE_SESSION_STATUS", &self.session_status)
                        .args([
                            "pair",
                            args["device"]["address"]
                                .as_str()
                                .ok_or("address required")?,
                            "--pin",
                            args["pin"].as_str().ok_or("PIN required")?,
                        ])
                        .spawn()
                        .map_err(|e| e.to_string())?,
                );
                *pending = Some((attempt, device));
                Ok(json!({"accepted":true}))
            }
            "pair.cancel" => {
                self.pairing.lock().unwrap().take();
                if let Some(mut child) = self.child.lock().unwrap().take() {
                    let _ = child.kill();
                    let _ = child.wait();
                }
                Ok(json!({"cancelled":true}))
            }
            "stream.disconnect" => {
                self.recovery.lock().unwrap().take();
                if self.state()?["streaming"] == true {
                    self.control("disconnect", &json!({}))
                } else {
                    self.stop_owned()
                }
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
