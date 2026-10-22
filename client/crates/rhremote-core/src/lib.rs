use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fs,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};
mod global_settings;
pub mod sync_model;
pub mod network;

pub const ACTIONS: &str = include_str!("actions.json");
pub fn settings_catalog() -> Value {
    serde_json::from_str(include_str!("settings.json")).unwrap()
}
pub fn catalog() -> Value {
    serde_json::from_str(ACTIONS).expect("compiled action catalog")
}
pub fn shortcuts() -> Value {
    serde_json::from_str(include_str!("../../../shared/shortcuts.json")).unwrap()
}
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
pub fn random_token() -> Result<String, String> {
    let mut bytes = [0; 32];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase", deny_unknown_fields)]
pub struct Profile {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_mbps: u32,
    pub codec: String,
    pub swap_buttons: bool,
    pub trackpad: bool,
    pub statistics: bool,
    pub hdr: bool,
    pub audio_enabled: bool,
    pub volume: u32,
    pub play_host_audio: bool,
    pub audio_config: String,
    pub mute_on_focus_loss: bool,
    pub absolute_mouse: bool,
    pub reverse_scroll: bool,
    pub capture_sys_keys: String,
    pub frame_pacing: bool,
    pub vsync: bool,
    pub decoder: String,
    pub window_mode: String,
    pub packet_size: u32,
    pub packet_size_auto: bool,
    pub network_mode: String,
    pub remote_bitrate_mbps: u32,
    pub route_order: Vec<String>,
    pub relay_url: String,
    pub on_screen_controller: bool,
    pub gamepad_enabled: bool,
    pub full_range: bool,
    pub display_mode: String,
}
impl Default for Profile {
    fn default() -> Self {
        Self {
            width: 1920,
            height: 1080,
            fps: 30,
            bitrate_mbps: 25,
            codec: "HEVC".into(),
            swap_buttons: false,
            trackpad: false,
            statistics: false,
            hdr: false,
            audio_enabled: false,
            volume: 100,
            play_host_audio: true,
            audio_config: "stereo".into(),
            mute_on_focus_loss: false,
            absolute_mouse: true,
            reverse_scroll: false,
            capture_sys_keys: "fullscreen".into(),
            frame_pacing: true,
            vsync: true,
            decoder: "hardware".into(),
            window_mode: "windowed".into(),
            packet_size: 1392,
            packet_size_auto: true,
            network_mode: "auto".into(),
            remote_bitrate_mbps: 0,
            route_order: network::default_route_order(),
            relay_url: network::DEFAULT_RELAY.into(),
            on_screen_controller: false,
            gamepad_enabled: false,
            full_range: false,
            display_mode: "auto".into(),
        }
    }
}
impl Profile {
    pub fn validate(&self) -> Result<(), String> {
        network::validate_routes(&self.route_order, &self.relay_url)?;
        if !(320..=8192).contains(&self.width)
            || !(320..=8192).contains(&self.height)
            || self.width % 2 != 0
            || self.height % 2 != 0
            || u64::from(self.width) * u64::from(self.height) > 64_000_000
        {
            return Err("unsupported resolution".into());
        }
        if !(10..=240).contains(&self.fps)
            || !(1..=300).contains(&self.bitrate_mbps)
            || !["auto", "HEVC", "H.264", "AV1"].contains(&self.codec.as_str())
            || !["auto", "lan", "tailscale", "internet"].contains(&self.network_mode.as_str())
            || self.remote_bitrate_mbps > 300
            || self.volume > 100
            || !(1024..=1472).contains(&self.packet_size)
            || !["stereo", "5.1-surround", "7.1-surround"].contains(&self.audio_config.as_str())
            || !["never", "fullscreen", "always"].contains(&self.capture_sys_keys.as_str())
            || !["auto", "hardware", "software"].contains(&self.decoder.as_str())
            || !["windowed", "borderless", "fullscreen"].contains(&self.window_mode.as_str())
            || !["auto", "virtual"].contains(&self.display_mode.as_str())
        {
            return Err("invalid stream profile".into());
        }
        Ok(())
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    pub uuid: String,
    pub name: String,
    #[serde(default)]
    pub display_name: String,
    pub address: String,
    #[serde(default)]
    pub addresses: Vec<String>,
    pub online: bool,
    pub paired: bool,
    #[serde(default)]
    pub is_self: bool,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Saved {
    pub device_order_initialized: bool,
    pub profiles: BTreeMap<String, Profile>,
    pub dark: bool,
    pub devices: Vec<sync_model::SharedDevice>,
    pub client_id: String,
    pub clients: BTreeMap<String, sync_model::ClientInfo>,
    pub client_profiles: BTreeMap<String, BTreeMap<String, sync_model::ClientProfile>>,
    pub profile_schema_version: u32,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct State {
    pub platform: String,
    pub devices: Vec<Device>,
    pub saved: Saved,
    pub page: String,
    pub selected: String,
    pub draft: Option<Profile>,
    pub draft_display_name: String,
    pub profile_tab: String,
    pub profile_sources_expanded: bool,
    pub settings_catalog: Value,
    pub status: String,
    pub streaming: bool,
    pub connecting: bool,
    #[serde(skip)]
    connect_in_flight: bool,
    #[serde(skip)]
    stream_epoch: u64,
    pub audio_enabled: bool,
    pub volume: u32,
    pub statistics: Value,
    pub network: Value,
    pub controls: bool,
    pub stream_menu: String,
    pub trackpad: bool,
    pub pan: bool,
    pub keyboard: bool,
    pub show_stats: bool,
    pub pair_pin: String,
    #[serde(skip)]
    pair_attempt: u64,
    pub revision: u64,
    pub last_action: Value,
    pub window_action: String,
    pub preview: Value,
    pub toolbar: Value,
    pub settings_tab: String,
    pub sync: Value,
    pub sync_busy: bool,
    pub updates: Value,
    pub updates_busy: bool,
    #[serde(skip)]
    installing_update: bool,
}
#[derive(Clone, Copy, Debug)]
pub struct ActionTiming {
    pub requested_at_us: u128,
    pub queue_wait_us: u128,
}
pub trait Platform: Send + Sync {
    fn execute(&self, action: &str, args: &Value) -> Result<Value, String>;
    fn connection_requested(&self, _timing: Option<ActionTiming>) {}
}
pub struct Controller {
    state: Mutex<State>,
    storage: PathBuf,
    platform: Arc<dyn Platform>,
}
impl Controller {
    pub fn new(
        platform_name: &str,
        storage: PathBuf,
        platform: Arc<dyn Platform>,
    ) -> Result<Arc<Self>, String> {
        let mut saved: Saved = if storage.exists() {
            serde_json::from_slice(&fs::read(&storage).map_err(|e| e.to_string())?)
                .map_err(|e| format!("preferences: {e}"))?
        } else {
            Saved::default()
        };
        if storage.exists() && saved.profile_schema_version < 2 {
            let backup = storage.with_extension("pre-client-profiles.json");
            if !backup.exists() {
                fs::copy(&storage, backup).map_err(|e| e.to_string())?;
            }
        }
        saved.initialize_client(platform_name)?;
        if !saved.device_order_initialized {
            // Older versions sorted the cards independently of the stored UUID array.
            saved.devices.sort_by_key(|d| {
                (
                    !d.name.eq_ignore_ascii_case("winserver"),
                    d.name.to_lowercase(),
                )
            });
            saved.device_order_initialized = true;
        }
        let controller = Arc::new(Self {
            state: Mutex::new(State {
                platform: platform_name.into(),
                devices: vec![],
                saved,
                page: "devices".into(),
                selected: String::new(),
                draft: None,
                draft_display_name: String::new(),
                profile_tab: "video".into(),
                profile_sources_expanded: false,
                settings_catalog: settings_catalog(),
                status: String::new(),
                streaming: false,
                connecting: false,
                connect_in_flight: false,
                stream_epoch: 0,
                audio_enabled: false,
                volume: 100,
                statistics: Value::Null,
                network: Value::Null,
                controls: false,
                stream_menu: String::new(),
                trackpad: false,
                pan: false,
                keyboard: false,
                show_stats: false,
                pair_pin: String::new(),
                pair_attempt: 0,
                revision: 0,
                last_action: Value::Null,
                window_action: String::new(),
                preview: Value::Null,
                toolbar: Value::Null,
                settings_tab: "updates".into(),
                sync: platform
                    .execute("sync.state", &json!({}))
                    .unwrap_or(json!({})),
                sync_busy: false,
                updates: platform
                    .execute("updates.state", &json!({}))
                    .unwrap_or(json!({})),
                updates_busy: false,
                installing_update: false,
            }),
            storage,
            platform,
        });
        // Persist the installation identity before the first sync, including on a fresh install.
        controller.persist(&controller.state.lock().unwrap().saved)?;
        Ok(controller)
    }
    pub fn state(&self) -> Value {
        serde_json::to_value(&*self.state.lock().unwrap()).unwrap()
    }
    pub fn profile(&self, uuid: &str) -> Profile {
        self.state.lock().unwrap().saved.effective_profile(uuid)
    }
    pub fn replace_devices(&self, mut devices: Vec<Device>) {
        let mut s = self.state.lock().unwrap();
        for d in &mut devices {
            d.display_name = s
                .saved
                .devices
                .iter()
                .find(|h| h.uuid == d.uuid)
                .map(|h| h.display_name.clone())
                .unwrap_or_default();
        }
        let saved = &s.saved.devices;
        devices.sort_by_key(|d| {
            saved
                .iter()
                .position(|h| h.uuid == d.uuid)
                .unwrap_or(usize::MAX)
        });
        s.devices = devices;
        s.revision += 1;
    }
    pub fn stream_state(&self, streaming: bool, statistics: Value) {
        self.stream_state_for(self.stream_epoch(), streaming, statistics);
    }
    pub fn stream_epoch(&self) -> u64 {
        self.state.lock().unwrap().stream_epoch
    }
    pub fn stream_state_for(&self, epoch: u64, streaming: bool, statistics: Value) {
        let mut s = self.state.lock().unwrap();
        // A poll started before a new connection cannot erase its pending state.
        if epoch != s.stream_epoch || s.connect_in_flight {
            return;
        }
        let was_connecting = s.connecting;
        if streaming && !s.streaming {
            s.status = String::new();
        } else if !streaming && s.streaming {
            s.status = "已断开".into();
        }
        s.streaming = streaming;
        if !streaming {
            s.stream_menu.clear();
        }
        s.audio_enabled = statistics["audioEnabled"] == true;
        if let Some(v) = statistics["volume"].as_u64() {
            s.volume = v as u32;
        }
        s.connecting = matches!(
            statistics["phase"].as_str(),
            Some("connecting" | "starting" | "reconnecting")
        );
        if streaming && statistics["firstFrame"] == false {
            s.connecting = true;
        }
        if let Some(error) = statistics["error"].as_str().filter(|e| !e.is_empty()) {
            s.status = error.into();
        } else if s.connecting {
            s.status = if statistics["phase"] == "reconnecting" {
                "连接中断，正在自动恢复…"
            } else if streaming {
                "等待主机首帧…"
            } else {
                "正在建立串流…"
            }
            .into();
        } else if was_connecting && streaming {
            s.status = String::new();
        }
        if let Some(network)=statistics.get("network"){s.network=network.clone();}
        s.statistics = statistics;
        s.revision += 1;
    }
    pub fn toolbar_state(&self, value: Value) {
        let mut s = self.state.lock().unwrap();
        if s.toolbar != value {
            s.toolbar = value;
            s.revision += 1;
        }
    }
    pub fn pairing_complete(&self, attempt: u64, device: Device, error: String) {
        let mut s = self.state.lock().unwrap();
        if s.page != "pair" || s.pair_attempt != attempt || s.selected != device.uuid {
            return;
        }
        if let Some(row) = s.devices.iter_mut().find(|d| d.uuid == device.uuid) {
            *row = device.clone();
        }
        s.page = "devices".into();
        s.pair_pin.clear();
        s.status = if device.paired {
            "配对完成".into()
        } else {
            error
        };
        s.revision += 1;
    }
    fn persist(&self, saved: &Saved) -> Result<(), String> {
        if let Some(parent) = self.storage.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let temp = self.storage.with_extension("json.tmp");
        fs::write(&temp, serde_json::to_vec_pretty(saved).unwrap()).map_err(|e| e.to_string())?;
        // rename replaces the destination on Windows and Unix through the platform filesystem API.
        fs::rename(&temp, &self.storage).map_err(|e| e.to_string())
    }
    pub fn dispatch(&self, id: &str, args: Value) -> Value {
        self.dispatch_timed(id, args, None)
    }
    pub fn dispatch_timed(&self, id: &str, args: Value, timing: Option<ActionTiming>) -> Value {
        if !catalog().as_array().unwrap().iter().any(|a| a["id"] == id) {
            return json!({"ok":false,"error":"unknown action","action":id});
        }
        let result = if id.starts_with("updates.") {
            self.dispatch_update(id)
        } else if id.starts_with("sync.") || id.starts_with("app.settings.") {
            self.dispatch_global(id, args)
        } else {
            self.dispatch_inner(id, args, timing)
        };
        let response = match result {
            Ok(value) => json!({"ok":true,"action":id,"result":value}),
            Err(error) => json!({"ok":false,"action":id,"error":error}),
        };
        let mut s = self.state.lock().unwrap();
        s.last_action = response.clone();
        s.revision += 1;
        response
    }
    fn dispatch_inner(&self, id: &str, mut args: Value, timing: Option<ActionTiming>) -> Result<Value, String> {
        let mut s = self.state.lock().unwrap();
        if s.sync_busy
            && matches!(
                id,
                "device.settings.open"
                    | "settings.set"
                    | "settings.copy_from"
                    | "settings.save"
                    | "appearance.toggle"
                    | "device.add.submit"
                    | "device.move"
            )
        {
            return Err("同步进行中，请稍后保存".into());
        }
        if s.installing_update && id == "device.connect" {
            return Err("更新操作进行中，请稍后连接".into());
        }
        match id {
            "device.move" => {
                if s.page != "devices" {
                    return Err("请先返回设备列表".into());
                }
                let uuid = args["uuid"].as_str().ok_or("uuid required")?;
                let index = s
                    .devices
                    .iter()
                    .position(|d| d.uuid == uuid)
                    .ok_or("unknown device")?;
                let target = match args["direction"].as_str() {
                    Some("up") => index.saturating_sub(1),
                    Some("down") => (index + 1).min(s.devices.len() - 1),
                    _ => return Err("direction must be up or down".into()),
                };
                let mut next = s.saved.clone();
                next.devices = sync_model::from_state(&s).devices;
                let a = next
                    .devices
                    .iter()
                    .position(|d| d.uuid == s.devices[index].uuid)
                    .unwrap();
                let b = next
                    .devices
                    .iter()
                    .position(|d| d.uuid == s.devices[target].uuid)
                    .unwrap();
                next.devices.swap(a, b);
                self.persist(&next)?;
                s.saved = next;
                s.devices.swap(index, target);
                s.status = "设备顺序已保存".into();
            }
            "device.network.configure" => {
                let uuid = args["uuid"].as_str().ok_or("uuid required")?;
                let device=s.devices.iter().find(|d|d.uuid==uuid).ok_or("unknown device")?.clone();
                let primary=args["address"].as_str().unwrap_or(&device.address).to_owned();
                let extra:Vec<String>=serde_json::from_value(args["addresses"].clone()).map_err(|_|"addresses requires an array of hostnames or IPs")?;
                let all=network::addresses(&primary,&extra)?;
                let mut next=s.saved.clone();next.devices=sync_model::from_state(&s).devices;
                let host=next.devices.iter_mut().find(|d|d.uuid==uuid).ok_or("unknown device")?;
                host.address=primary.clone();host.addresses=all.into_iter().filter(|a|!a.eq_ignore_ascii_case(&primary)).collect();
                let extras=host.addresses.clone();
                self.persist(&next)?;
                s.saved=next;
                let host=s.devices.iter_mut().find(|d|d.uuid==uuid).unwrap();host.address=primary;host.addresses=extras;
                s.status="连接地址已保存".into();
                let rows=s.devices.clone();drop(s);
                self.platform.execute("devices.restore", &json!({"devices":rows}))?;
                return Ok(json!({"saved":true}));
            }
            "device.network.diagnose" => {
                let uuid=args["uuid"].as_str().ok_or("uuid required")?;
                let mut device=s.devices.iter().find(|d|d.uuid==uuid).ok_or("unknown device")?.clone();
                if let Some(saved)=s.saved.devices.iter().find(|d|d.uuid==uuid){device.addresses=saved.addresses.clone();}
                if let Some(primary)=args.get("address").and_then(Value::as_str){device.address=primary.into();}
                if let Some(extra)=args.get("addresses"){device.addresses=serde_json::from_value(extra.clone()).map_err(|_|"addresses requires an array")?;}
                network::addresses(&device.address,&device.addresses)?;
                let profile=if s.selected==uuid{s.draft.clone().unwrap_or_else(||s.saved.effective_profile(uuid))}else{s.saved.effective_profile(uuid)};
                args["device"]=json!(device);args["profile"]=json!(profile);
                drop(s);let result=self.platform.execute(id,&args)?;
                let mut s=self.state.lock().unwrap();s.network=result.clone();s.revision+=1;return Ok(result);
            }
            "devices.open" | "device.add.cancel" | "settings.cancel" => {
                s.page = "devices".into();
                s.draft = None;
                s.draft_display_name.clear();
            }
            "device.add.open" => {
                s.page = "add".into();
            }
            "device.settings.open" => {
                let uuid = args["uuid"]
                    .as_str()
                    .filter(|v| !v.is_empty())
                    .unwrap_or(&s.selected)
                    .to_owned();
                if !s.devices.iter().any(|d| d.uuid == uuid) {
                    return Err("unknown device".into());
                }
                s.draft = Some(s.saved.effective_profile(&uuid));
                s.draft_display_name = s
                    .saved
                    .devices
                    .iter()
                    .find(|d| d.uuid == uuid)
                    .map(|d| d.display_name.clone())
                    .unwrap_or_default();
                s.selected = uuid;
                s.page = "settings".into();
                s.profile_sources_expanded = false;
            }
            "settings.route.move" | "settings.route.toggle" => {
                let mut next=s.draft.clone().ok_or("open device settings first")?;
                let route=args["route"].as_str().filter(|r|network::ROUTES.iter().any(|(id,_)|r==id)).ok_or("unknown route")?;
                if id=="settings.route.toggle" {
                    if next.route_order.iter().any(|r|r==route){next.route_order.retain(|r|r!=route);}else{next.route_order.push(route.into());}
                }else{
                    let index=next.route_order.iter().position(|r|r==route).ok_or("route is disabled")?;
                    let target=match args["direction"].as_str(){Some("up")=>index.saturating_sub(1),Some("down")=>(index+1).min(next.route_order.len()-1),_=>return Err("direction must be up or down".into())};
                    next.route_order.swap(index,target);
                }
                next.validate()?;s.draft=Some(next);
            }
            "settings.set" => {
                let current = s.draft.as_ref().ok_or("open device settings first")?;
                let mut value = serde_json::to_value(current).unwrap();
                let key = args["key"].as_str().ok_or("key required")?;
                if key == "displayName" {
                    let name = sync_model::display_name(
                        args["value"]
                            .as_str()
                            .ok_or("displayName requires a string")?,
                    )?;
                    s.draft_display_name = name;
                    s.revision += 1;
                    return Ok(json!({"revision":s.revision}));
                } else if key == "resolution" {
                    let v = args["value"]
                        .as_array()
                        .filter(|v| v.len() == 2)
                        .ok_or("resolution requires [width,height]")?;
                    value["width"] = v[0].clone();
                    value["height"] = v[1].clone();
                } else if value.get(key).is_some() {
                    value[key] = args["value"].clone();
                } else {
                    return Err("unknown profile option".into());
                }
                let next: Profile = serde_json::from_value(value).map_err(|e| e.to_string())?;
                next.validate()?;
                s.draft = Some(next);
            }
            "settings.catalog" => return Ok(json!({"fields":settings_catalog()})),
            "settings.sources.toggle" => {
                if s.draft.is_none() {
                    return Err("open device settings first".into());
                }
                s.profile_sources_expanded = !s.profile_sources_expanded;
            }
            "settings.tab" => {
                if s.draft.is_none() {
                    return Err("open device settings first".into());
                }
                let tab = args["tab"].as_str().ok_or("tab required")?;
                if !["video", "audio", "input", "network", "advanced"].contains(&tab) {
                    return Err("unknown device settings tab".into());
                }
                s.profile_tab = tab.into();
            }
            "settings.copy_from" => {
                if s.draft.is_none() {
                    return Err("open device settings first".into());
                }
                let client = args["clientId"].as_str().ok_or("clientId required")?;
                if client == s.saved.client_id {
                    return Err("select another client".into());
                }
                let mut p = s
                    .saved
                    .client_profiles
                    .get(client)
                    .and_then(|profiles| profiles.get(&s.selected))
                    .ok_or("no profile for this client and device")?
                    .to_profile();
                // Copy quality/input preferences only; the host mouse setting is shared separately.
                p.swap_buttons = s.draft.as_ref().unwrap().swap_buttons;
                s.draft = Some(p);
            }
            "settings.save" => {
                let p = s.draft.clone().ok_or("no settings draft")?;
                p.validate()?;
                let mut next = s.saved.clone();
                next.devices = sync_model::from_state(&s).devices;
                let name = sync_model::display_name(
                    args.get("displayName")
                        .and_then(Value::as_str)
                        .unwrap_or(&s.draft_display_name),
                )?;
                if let Some(d) = next.devices.iter_mut().find(|d| d.uuid == s.selected) {
                    d.display_name = name.clone();
                }
                next.save_profile(&s.selected, p);
                self.persist(&next)?;
                let selected = s.selected.clone();
                if let Some(d) = s.devices.iter_mut().find(|d| d.uuid == selected) {
                    d.display_name = name;
                }
                s.saved = next;
                s.draft = None;
                s.page = "devices".into();
                s.status = "连接偏好已保存".into();
            }
            "appearance.toggle" => {
                let mut next = s.saved.clone();
                next.devices = sync_model::from_state(&s).devices;
                next.dark = !next.dark;
                self.persist(&next)?;
                s.saved = next;
            }
            "stream.controls.toggle" => {
                s.controls = !s.controls;
                s.stream_menu.clear();
            }
            "stream.menu.open" => {
                if !s.streaming || !s.controls {
                    return Err("请先展开活动串流的控制栏".into());
                }
                let menu = args["menu"].as_str().unwrap_or("");
                if !["shortcuts", "displays"].contains(&menu) {
                    return Err("unknown stream menu".into());
                }
                s.stream_menu = menu.into();
            }
            "stream.menu.close" => s.stream_menu.clear(),
            "stream.shortcuts.list" => return Ok(shortcuts()),
            "stream.shortcuts.open" => s.controls = true,
            "stream.displays.open" => s.controls = true,
            "window.minimize"
            | "window.maximize"
            | "window.close"
            | "view.snapshot"
            | "view.toolbar.snapshot"
            | "view.picker.snapshot" => {
                if id == "window.close" && s.streaming {
                    drop(s);
                    self.platform.execute("stream.disconnect", &json!({}))?;
                    s = self.state.lock().unwrap();
                }
                if id == "window.close" {
                    drop(s);
                    self.platform.execute("engine.close", &json!({}))?;
                    s = self.state.lock().unwrap();
                }
                s.window_action = id.into();
            }
            _ => {
                if id == "stream.shortcut"
                    && !shortcuts()
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|v| v["id"] == args["shortcut"] && args["shortcut"].is_string())
                {
                    return Err("unknown remote shortcut".into());
                }
                if id == "stream.window.move"
                    && ![Some("left"), Some("right")].contains(&args["direction"].as_str())
                {
                    return Err("direction must be left or right".into());
                }
                if id == "pair.cancel" && s.page != "pair" {
                    return Err("no active pairing".into());
                }
                if id.starts_with("stream.") || id == "input.gesture" {
                    if !s.streaming && !(id == "stream.disconnect" && s.connecting) {
                        return Err("no active stream".into());
                    }
                }
                if id == "device.connect" || id == "device.pair" {
                    if s.page == "pair" {
                        return Err("请先完成或取消当前配对".into());
                    }
                    if s.streaming || s.connecting {
                        return Err("disconnect the current session first".into());
                    }
                    let uuid = args["uuid"].as_str().ok_or("uuid required")?.to_owned();
                    let d = s
                        .devices
                        .iter()
                        .find(|d| d.uuid == uuid)
                        .ok_or("unknown device")?
                        .clone();
                    if d.is_self {
                        return Err("this is the local device; its configuration remains available for editing and sync".into());
                    }

                    if id == "device.connect" && !d.paired {
                        return Err("device must be paired first".into());
                    }
                    s.selected = uuid.clone();
                    args["device"] = serde_json::to_value(&d).unwrap();
                    if let Some(saved) = s.saved.devices.iter().find(|h| h.uuid == uuid) {
                        args["device"]["addresses"] = json!(saved.addresses);
                    }
                    args["profile"] =
                        serde_json::to_value(s.saved.effective_profile(&uuid)).unwrap();
                    if id == "device.connect" {
                        // An ended stream's firstFrame must not describe this attempt.
                        s.statistics = Value::Null;
                        s.connecting = true;
                        s.connect_in_flight = true;
                        s.stream_epoch += 1;
                        s.status = "正在连接…".into();
                        s.revision += 1;
                    }
                    if id == "device.pair" {
                        s.pair_attempt += 1;
                        args["attemptId"] = json!(s.pair_attempt);
                        let pin = args["pin"].as_str().map(str::to_owned).unwrap_or_else(|| {
                            let mut bytes = [0; 4];
                            getrandom::fill(&mut bytes).expect("OS random source");
                            format!("{:04}", u32::from_ne_bytes(bytes) % 10000)
                        });
                        if pin.len() != 4 || !pin.bytes().all(|b| b.is_ascii_digit()) {
                            return Err("PIN must contain four digits".into());
                        }
                        args["pin"] = json!(pin);
                        s.pair_pin = pin;
                        s.page = "pair".into();
                    }
                }
                if id == "device.add.submit" {
                    let address = args["address"]
                        .as_str()
                        .filter(|v| !v.trim().is_empty())
                        .ok_or("address required")?;
                    if address.contains(['/', '\\', '@', '\n', '\r']) {
                        return Err("enter an IP address or hostname".into());
                    }
                }
                drop(s);
                if id == "device.connect" {
                    self.platform.connection_requested(timing);
                }
                let result = match self.platform.execute(id, &args) {
                    Ok(value) => value,
                    Err(error) => {
                        if id == "device.connect" {
                            let mut state = self.state.lock().unwrap();
                            state.connect_in_flight = false;
                            state.connecting = false;
                            state.stream_epoch += 1;
                            state.status = error.clone();
                        }
                        if id == "device.pair" {
                            let mut state = self.state.lock().unwrap();
                            if args["attemptId"].as_u64() == Some(state.pair_attempt) {
                                state.page = "devices".into();
                                state.pair_pin.clear();
                                state.status = error.clone();
                            }
                        }
                        return Err(error);
                    }
                };
                let mut s = self.state.lock().unwrap();
                match id {
                    "device.add.submit" | "devices.refresh" => {
                        if let Some(devices) = result.get("devices") {
                            s.devices = serde_json::from_value(devices.clone())
                                .map_err(|e| e.to_string())?;
                            let saved_devices = s.saved.devices.clone();
                            for d in &mut s.devices {
                                d.display_name = saved_devices
                                    .iter()
                                    .find(|h| h.uuid == d.uuid)
                                    .map(|h| h.display_name.clone())
                                    .unwrap_or_default();
                            }
                            s.devices.sort_by_key(|d| {
                                saved_devices
                                    .iter()
                                    .position(|h| h.uuid == d.uuid)
                                    .unwrap_or(usize::MAX)
                            });
                        }
                        if id == "device.add.submit" {
                            let mut next = s.saved.clone();
                            next.devices = sync_model::from_state(&s).devices;
                            self.persist(&next)?;
                            s.saved = next;
                        }
                        if id == "device.add.submit" {
                            s.page = "devices".into();
                        }
                    }
                    "device.connect" => {
                        s.connect_in_flight = false;
                        s.stream_epoch += 1;
                        s.status = "正在连接…".into();
                        s.show_stats = args["profile"]["statistics"].as_bool().unwrap_or(false);
                    }
                    "pair.cancel" => {
                        s.pair_attempt += 1;
                        s.page = "devices".into();
                        s.pair_pin.clear();
                    }
                    "stream.disconnect" => {
                        s.status = "正在断开…".into();
                        s.pan = false;
                        s.keyboard = false;
                    }
                    "stream.mode.toggle" => {
                        s.trackpad = !s.trackpad;
                    }
                    "stream.pan.toggle" => {
                        s.pan = !s.pan;
                    }
                    "stream.keyboard.toggle" => {
                        s.keyboard = !s.keyboard;
                    }
                    "stream.stats.toggle" => {
                        s.show_stats = !s.show_stats;
                    }
                    "stream.audio.toggle" => {
                        s.audio_enabled = result["enabled"] == true;
                    }
                    "stream.audio.volume" => {
                        s.volume = result["volume"].as_u64().unwrap_or(100) as u32;
                    }
                    _ => {}
                }
                return Ok(result);
            }
        }
        s.revision += 1;
        Ok(json!({"revision":s.revision}))
    }
    pub fn take_window_action(&self) -> String {
        let mut s = self.state.lock().unwrap();
        std::mem::take(&mut s.window_action)
    }
    pub fn message(&self, text: String) {
        let mut s = self.state.lock().unwrap();
        s.status = text;
        s.revision += 1;
    }
    pub fn preview_complete(&self, value: Value) {
        let mut s = self.state.lock().unwrap();
        s.preview = value;
        s.revision += 1;
    }
}

pub fn start_http(
    controller: Arc<Controller>,
    port: u16,
    token: String,
) -> Result<std::thread::JoinHandle<()>, String> {
    if token.len() < 32 {
        return Err("control token must be at least 32 characters".into());
    }
    let server = tiny_http::Server::http((std::net::Ipv4Addr::LOCALHOST, port))
        .map_err(|e| e.to_string())?;
    Ok(std::thread::spawn(move || {
        for mut request in server.incoming_requests() {
            let authenticated = request.headers().iter().any(|h| {
                h.field.equiv("Authorization") && h.value.as_str() == format!("Bearer {token}")
            });
            let origin = request.headers().iter().any(|h| h.field.equiv("Origin"));
            let (status, value) = if !authenticated || origin {
                (403, json!({"ok":false,"error":"unauthorized"}))
            } else if request.method() == &tiny_http::Method::Get {
                match request.url() {
                    "/v1/state" => (200, json!({"ok":true,"state":controller.state()})),
                    "/v1/actions" => (200, json!({"ok":true,"actions":catalog()})),
                    _ => (404, json!({"ok":false,"error":"unknown endpoint"})),
                }
            } else if request.method() == &tiny_http::Method::Post && request.url() == "/v1/action"
            {
                if request.body_length().is_none_or(|n| n > 8192) {
                    (
                        413,
                        json!({"ok":false,"error":"bounded Content-Length required"}),
                    )
                } else {
                    let mut body = String::new();
                    use std::io::Read;
                    match request
                        .as_reader()
                        .take(8193)
                        .read_to_string(&mut body)
                        .ok()
                        .and_then(|_| serde_json::from_str::<Value>(&body).ok())
                    {
                        Some(v) => {
                            let id = v["id"].as_str().unwrap_or("");
                            let response = controller
                                .dispatch(id, v.get("args").cloned().unwrap_or(json!({})));
                            (if response["ok"] == true { 200 } else { 422 }, response)
                        }
                        None => (400, json!({"ok":false,"error":"invalid JSON"})),
                    }
                }
            } else {
                (404, json!({"ok":false,"error":"unknown endpoint"}))
            };
            let response = tiny_http::Response::from_string(value.to_string())
                .with_status_code(status)
                .with_header(
                    tiny_http::Header::from_bytes(
                        "Content-Type",
                        "application/json; charset=utf-8",
                    )
                    .unwrap(),
                )
                .with_header(tiny_http::Header::from_bytes("Cache-Control", "no-store").unwrap());
            let _ = request.respond(response);
        }
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn gamepads_are_off_by_default_and_can_be_enabled_per_client() {
        let mut value = serde_json::to_value(Profile::default()).unwrap();
        value.as_object_mut().unwrap().remove("gamepadEnabled");
        assert!(!serde_json::from_value::<Profile>(value.clone()).unwrap().gamepad_enabled);
        value["gamepadEnabled"] = json!(true);
        let profile = serde_json::from_value::<Profile>(value).unwrap();
        assert!(profile.gamepad_enabled);
        assert_eq!(serde_json::to_value(profile).unwrap()["gamepadEnabled"], true);
    }
    struct Stub;
    impl Platform for Stub {
        fn execute(&self, _: &str, _: &Value) -> Result<Value, String> {
            Err("no transport in unit tests".into())
        }
    }
    fn controller() -> Arc<Controller> {
        let file = std::env::temp_dir().join(format!(
            "rhremote-test-{}-{}.json",
            std::process::id(),
            random_token().unwrap()
        ));
        let c = Controller::new("test", file, Arc::new(Stub)).unwrap();
        c.replace_devices(vec![
            Device {
                uuid: "a".into(),
                name: "A".into(),
                display_name: String::new(),
                address: "localhost".into(),
                addresses: Vec::new(),
                online: true,
                paired: true,
                is_self: false,
            },
            Device {
                uuid: "b".into(),
                name: "B".into(),
                display_name: String::new(),
                address: "localhost".into(),
                addresses: Vec::new(),
                online: true,
                paired: true,
                is_self: false,
            },
        ]);
        c
    }
    #[test]
    fn stream_picker_requires_active_controls_and_closes_with_stream() {
        let c = controller();
        assert_eq!(
            c.dispatch("stream.menu.open", json!({"menu":"shortcuts"}))["ok"],
            false
        );
        c.stream_state(true, json!({"firstFrame":true}));
        c.dispatch("stream.controls.toggle", json!({}));
        assert_eq!(
            c.dispatch("stream.menu.open", json!({"menu":"unknown"}))["ok"],
            false
        );
        assert_eq!(
            c.dispatch("stream.menu.open", json!({"menu":"shortcuts"}))["ok"],
            true
        );
        assert_eq!(c.state()["streamMenu"], "shortcuts");
        c.dispatch("stream.controls.toggle", json!({}));
        assert_eq!(c.state()["streamMenu"], "");
        c.dispatch("stream.controls.toggle", json!({}));
        c.dispatch("stream.menu.open", json!({"menu":"displays"}));
        c.stream_state(false, json!({}));
        assert_eq!(c.state()["streamMenu"], "");
    }
    #[test]
    fn pairing_completion_returns_to_devices_and_stale_completion_is_ignored() {
        let c = controller();
        let mut device: Device = serde_json::from_value(c.state()["devices"][0].clone()).unwrap();
        {
            let mut s = c.state.lock().unwrap();
            s.page = "pair".into();
            s.selected = device.uuid.clone();
            s.pair_pin = "1234".into();
            s.pair_attempt = 2;
        }
        device.paired = true;
        c.pairing_complete(1, device.clone(), String::new());
        assert_eq!(c.state()["page"], "pair");
        c.pairing_complete(2, device.clone(), String::new());
        assert_eq!(c.state()["page"], "devices");
        assert_eq!(c.state()["pairPin"], "");
        assert_eq!(c.state()["status"], "配对完成");
        {
            let mut s = c.state.lock().unwrap();
            s.page = "pair".into();
            s.selected = device.uuid.clone();
            s.pair_pin = "5678".into();
            s.pair_attempt = 3;
        }
        device.paired = false;
        c.pairing_complete(3, device, "配对码未被主机确认".into());
        assert_eq!(c.state()["page"], "devices");
        assert_eq!(c.state()["status"], "配对码未被主机确认");
        assert_eq!(c.state()["pairPin"], "");
    }
    #[test]
    fn failed_pair_launch_does_not_leave_a_waiting_pin() {
        let c = controller();
        assert_eq!(
            c.dispatch("device.pair", json!({"uuid":"a","pin":"1234"}))["ok"],
            false
        );
        assert_eq!(c.state()["page"], "devices");
        assert_eq!(c.state()["pairPin"], "");
    }
    #[test]
    fn update_checks_do_not_block_connect_but_installation_does() {
        let c = controller();
        {
            let mut s = c.state.lock().unwrap();
            s.updates_busy = true;
            s.installing_update = false;
        }
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"a"}))["error"],
            "no transport in unit tests"
        );
        {
            let mut s = c.state.lock().unwrap();
            s.installing_update = true;
        }
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"a"}))["error"],
            "更新操作进行中，请稍后连接"
        );
    }
    #[test]
    fn handshake_does_not_report_first_frame_ready() {
        let c = controller();
        c.stream_state(true, json!({"phase":"connected","firstFrame":false}));
        assert_eq!(c.state()["connecting"], true);
        assert_eq!(c.state()["status"], "等待主机首帧…");
        c.stream_state(true, json!({"phase":"connected","firstFrame":true}));
        assert_eq!(c.state()["connecting"], false);
        assert_eq!(c.state()["status"], "");
    }
    #[test]
    fn cancel_does_not_write_and_devices_are_isolated() {
        let c = controller();
        assert_eq!(
            c.dispatch("device.settings.open", json!({"uuid":"a"}))["ok"],
            true
        );
        assert_eq!(
            c.dispatch("settings.set", json!({"key":"swapButtons","value":true}))["ok"],
            true
        );
        c.dispatch("settings.cancel", json!({}));
        assert!(!c.profile("a").swap_buttons);
        c.dispatch("device.settings.open", json!({"uuid":"a"}));
        c.dispatch("settings.set", json!({"key":"swapButtons","value":true}));
        assert_eq!(c.dispatch("settings.save", json!({}))["ok"], true);
        assert!(c.profile("a").swap_buttons);
        assert!(!c.profile("b").swap_buttons);
        let _ = fs::remove_file(&c.storage);
    }
    #[test]
    fn invalid_settings_are_rejected_without_changing_draft() {
        let c = controller();
        c.dispatch("device.settings.open", json!({"uuid":"a"}));
        assert_eq!(
            c.dispatch("settings.set", json!({"key":"fps","value":900}))["ok"],
            false
        );
        assert_eq!(c.state()["draft"]["fps"], 30);
    }
    #[test]
    fn alias_is_shared_host_metadata_and_cancel_keeps_original() {
        let c = controller();
        c.dispatch("device.settings.open", json!({"uuid":"a"}));
        c.dispatch(
            "settings.set",
            json!({"key":"displayName","value":"开发机"}),
        );
        c.dispatch("settings.cancel", json!({}));
        assert_eq!(c.state()["devices"][0]["displayName"], "");
        c.dispatch("device.settings.open", json!({"uuid":"a"}));
        assert_eq!(
            c.dispatch("settings.save", json!({"displayName":" 开发机 "}))["ok"],
            true
        );
        assert_eq!(c.state()["devices"][0]["name"], "A");
        assert_eq!(c.state()["devices"][0]["displayName"], "开发机");
        assert_eq!(
            c.export_shared()
                .devices
                .iter()
                .find(|d| d.uuid == "a")
                .unwrap()
                .display_name,
            "开发机"
        );
        let refreshed: Vec<Device> = serde_json::from_value(c.state()["devices"].clone()).unwrap();
        c.replace_devices(refreshed);
        assert_eq!(c.state()["devices"][0]["displayName"], "开发机");
        c.dispatch("device.settings.open", json!({"uuid":"a"}));
        assert_eq!(
            c.dispatch(
                "settings.set",
                json!({"key":"displayName","value":"bad\nname"})
            )["ok"],
            false
        );
        c.dispatch("settings.save", json!({"displayName":""}));
        assert_eq!(c.state()["devices"][0]["displayName"], "");
        let _ = fs::remove_file(&c.storage);
    }
    #[test]
    fn no_stream_no_remote_input() {
        assert_eq!(
            controller().dispatch("input.gesture", json!({"gesture":"tap","x":1,"y":1}))["ok"],
            false
        );
    }
    #[test]
    fn extended_options_migrate_and_remain_client_owned() {
        let mut p:Profile=serde_json::from_value(json!({"width":2520,"height":1680,"fps":30,"bitrateMbps":25,"codec":"HEVC","swapButtons":true,"trackpad":false,"statistics":false})).unwrap();
        assert!(!p.audio_enabled);
        assert!(p.play_host_audio);
        assert_eq!(p.width, 2520);
        p.audio_enabled = true;
        p.volume = 35;
        p.audio_config = "5.1-surround".into();
        p.codec = "AV1".into();
        p.fps = 120;
        p.width = 1680;
        p.height = 2520;
        p.packet_size = 1200;
        p.validate().unwrap();
        let shared = sync_model::ClientProfile::from_profile(&p);
        let value = serde_json::to_value(&shared).unwrap();
        assert!(value.get("swapButtons").is_none());
        assert_eq!(value["audioEnabled"], true);
        assert_eq!(value["volume"], 35);
        let restored: sync_model::ClientProfile = serde_json::from_value(value).unwrap();
        assert_eq!(restored.to_profile().audio_config, "5.1-surround");
        p.volume = 101;
        assert!(p.validate().is_err());
    }
    #[test]
    fn pending_connection_blocks_duplicate_start_and_failure_reaches_ui() {
        let c = controller();
        c.stream_state(false, json!({"phase":"connecting"}));
        assert_eq!(c.state()["connecting"], true);
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"a"}))["ok"],
            false
        );
        c.stream_state(
            false,
            json!({"phase":"error","error":"No video traffic (-100)"}),
        );
        assert_eq!(c.state()["connecting"], false);
        assert_eq!(c.state()["status"], "No video traffic (-100)");
        c.stream_state(false, Value::Null);
        assert_eq!(c.state()["status"], "No video traffic (-100)");
        let _ = fs::remove_file(&c.storage);
    }
    #[test]
    fn connect_is_visible_during_start_and_stale_polls_cannot_enable_duplicate_requests() {
        use std::sync::{atomic::{AtomicUsize, Ordering}, mpsc};
        struct SlowConnect {
            started: mpsc::Sender<()>,
            release: Mutex<mpsc::Receiver<()>>,
            calls: AtomicUsize,
        }
        impl Platform for SlowConnect {
            fn execute(&self, action: &str, _: &Value) -> Result<Value, String> {
                if action != "device.connect" {return Err("unused test action".into());}
                self.calls.fetch_add(1, Ordering::SeqCst);
                self.started.send(()).unwrap();
                self.release.lock().unwrap().recv_timeout(std::time::Duration::from_secs(5)).unwrap();
                Ok(json!({"accepted":true}))
            }
        }
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let transport = Arc::new(SlowConnect {started:started_tx,release:Mutex::new(release_rx),calls:AtomicUsize::new(0)});
        let template = controller();
        let devices:Vec<Device> = serde_json::from_value(template.state()["devices"].clone()).unwrap();
        let file=template.storage.with_extension("concurrent.json");
        let c=Controller::new("test",file,transport.clone()).unwrap();
        c.replace_devices(devices);
        c.stream_state(false,json!({"phase":"transport_closed","firstFrame":true}));
        assert_eq!(c.state()["statistics"]["firstFrame"],true);
        let old_epoch=c.stream_epoch();
        let worker=c.clone();
        let start=std::thread::spawn(move || worker.dispatch("device.connect",json!({"uuid":"a"})));
        started_rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap();
        assert_eq!(c.state()["connecting"],true);
        assert_eq!(c.state()["status"],"正在连接…");
        assert!(c.state()["statistics"].is_null());
        c.stream_state_for(old_epoch,false,json!({"phase":"idle"}));
        let pending_epoch=c.stream_epoch();
        c.stream_state_for(pending_epoch,false,json!({"phase":"error","error":"stale failure"}));
        assert_eq!(c.state()["connecting"],true);
        assert_eq!(c.dispatch("device.connect",json!({"uuid":"a"}))["ok"],false);
        assert_eq!(transport.calls.load(Ordering::SeqCst),1);
        release_tx.send(()).unwrap();
        assert_eq!(start.join().unwrap()["ok"],true);
        // A poll captured during spawn may arrive after spawn returns.
        c.stream_state_for(pending_epoch,false,json!({"phase":"idle"}));
        assert_eq!(c.state()["connecting"],true);
        c.stream_state(true,json!({"phase":"connected","firstFrame":true}));
        assert_eq!(c.state()["connecting"],false);
        let _=fs::remove_file(&c.storage);
        let _=fs::remove_file(&template.storage);
    }
    #[test]
    fn rejected_native_start_clears_pending_connection_and_displays_error() {
        let c=controller();
        let result=c.dispatch("device.connect",json!({"uuid":"a"}));
        assert_eq!(result["ok"],false);
        assert_eq!(c.state()["connecting"],false);
        assert_eq!(c.state()["status"],"no transport in unit tests");
        let _=fs::remove_file(&c.storage);
    }
    #[test]
    fn self_device_is_kept_editable_but_cannot_connect() {
        let c = controller();
        c.replace_devices(vec![Device {
            uuid: "a".into(),
            name: "Self".into(),
            display_name: String::new(),
            address: "localhost".into(),
            addresses: Vec::new(),
            online: true,
            paired: true,
            is_self: true,
        }]);
        assert_eq!(c.state()["devices"][0]["isSelf"], true);
        assert_eq!(
            c.dispatch("device.settings.open", json!({"uuid":"a"}))["ok"],
            true
        );
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"a"}))["ok"],
            false
        );
    }
    #[test]
    fn catalog_has_unique_ids() {
        let mut ids = std::collections::HashSet::new();
        for item in catalog().as_array().unwrap() {
            assert!(ids.insert(item["id"].as_str().unwrap()));
        }
    }
}
