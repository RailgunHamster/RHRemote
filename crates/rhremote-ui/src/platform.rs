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

pub struct NativePlatform {
    pub engine: PathBuf,
    native_data: PathBuf,
    session_status: PathBuf,
    pub devices: Mutex<Vec<Device>>,
    child: Mutex<Option<Child>>,
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
            .env_remove("RHREMOTE_CONTROL_TOKEN");
        command.current_dir(&self.native_data);
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
            if let Ok(mut child) = self
                .command()
                .args(["list", &d.address])
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()
            {
                let end = Instant::now() + Duration::from_secs(5);
                loop {
                    match child.try_wait() {
                        Ok(Some(status)) => {
                            if status.success() {
                                if let Ok(output) = child.wait_with_output() {
                                    d.paired =
                                        String::from_utf8_lossy(&output.stdout).contains("Desktop");
                                }
                            }
                            break;
                        }
                        Ok(None) if Instant::now() < end => {
                            std::thread::sleep(Duration::from_millis(25))
                        }
                        _ => {
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
    pub fn state(&self) -> Result<Value, String> {
        let response = ureq::get(&format!("http://127.0.0.1:{}/v1/state", self.port))
            .set("Authorization", &format!("Bearer {}", self.token))
            .timeout(Duration::from_millis(500))
            .call();
        if let Ok(response) = response {
            let mut value: Value = response.into_json().map_err(|e| e.to_string())?;
            value["tracing"] = self.statistics.lock().unwrap().state();
            return Ok(value);
        }
        let mut value: Value = fs::read(&self.session_status)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or(json!({"streaming":false}));
        let mut slot = self.child.lock().unwrap();
        if let Some(child) = slot.as_mut() {
            if child.try_wait().map_err(|e| e.to_string())?.is_some()
                && matches!(
                    value["phase"].as_str(),
                    Some("connecting" | "starting" | "connected")
                )
            {
                value["phase"] = json!("error");
                value["error"] = json!("串流进程已退出，请重新连接");
            }
        }
        value["tracing"] = self.statistics.lock().unwrap().state();
        Ok(value)
    }
    fn stop_owned(&self) -> Result<Value, String> {
        if let Some(mut child) = self.child.lock().unwrap().take() {
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
            .timeout(Duration::from_secs(3))
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
                let mut slot = self.child.lock().unwrap();
                if let Some(child) = slot.as_mut() {
                    if child.try_wait().map_err(|e| e.to_string())?.is_none() {
                        return Err("native session already running".into());
                    }
                }
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
                    .env("RHREMOTE_CONTROL_TOKEN", &self.token)
                    .env("RHREMOTE_SESSION_STATUS", &self.session_status)
                    .env(
                        "RHREMOTE_AUDIO_ENABLED",
                        if p["audioEnabled"] == true { "1" } else { "0" },
                    )
                    .env(
                        "RHREMOTE_AUDIO_VOLUME",
                        p["volume"].as_u64().unwrap_or(100).to_string(),
                    )
                    .spawn()
                    .map_err(|e| e.to_string())?;
                *slot = Some(child);
                Ok(json!({"accepted":true}))
            }
            "device.pair" => {
                let mut slot = self.child.lock().unwrap();
                if slot
                    .as_mut()
                    .is_some_and(|c| c.try_wait().ok().flatten().is_none())
                {
                    return Err("native operation is already running".into());
                }
                *slot = Some(
                    self.command()
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
                Ok(json!({"accepted":true}))
            }
            "pair.cancel" => {
                if let Some(mut child) = self.child.lock().unwrap().take() {
                    let _ = child.kill();
                    let _ = child.wait();
                }
                Ok(json!({"cancelled":true}))
            }
            "stream.disconnect" => {
                if self.state()?["streaming"] == true {
                    self.control("disconnect", &json!({}))
                } else {
                    self.stop_owned()
                }
            }
            "input.gesture" => self.control("input", args),
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
