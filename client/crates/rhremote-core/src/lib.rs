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
pub mod release_history;
#[cfg(test)]
mod sync_commit_tests;
pub use global_settings::update_primary_action;
mod interaction;
pub use interaction::is_profile_form_action;
mod add_device;
mod device_info;
pub mod window_source;
mod device_refresh;
mod device_check;
mod diagnostics;
pub use diagnostics::{DiagnosticTask,DiagnosticState};
pub use device_check::{DeviceCheck,CheckOutcome,CheckSource,CheckPhase};
#[cfg(test)]
mod device_trust_tests;
mod device_import;
mod connection;
mod profile_copy;
pub use profile_copy::{Field as ProfileField, fields as profile_fields};
mod session_ui;
mod preview;
pub use preview::PreviewRequest;
pub use session_ui::is_control_action as is_session_control_action;
pub mod sync_model;
pub mod network;
pub mod bitrate;
mod desktop_catalog;
mod desktop_sessions;
pub mod proxy;
pub mod keyboard;

pub const ACTIONS: &str = include_str!("actions.json");
pub fn settings_catalog() -> Value {
    let mut catalog:Value=serde_json::from_str(include_str!("settings.json")).unwrap();
    let fields=catalog.as_array_mut().unwrap();if !fields.iter().any(|field|field["key"]=="bitrateAuto"){let at=fields.iter().position(|field|field["key"]=="bitrateMbps").unwrap_or(fields.len());fields.insert(at,serde_json::from_str(include_str!("../../../shared/auto-bitrate-setting.json")).unwrap());}desktop_catalog::extend(&mut catalog);catalog
}
pub fn catalog() -> Value {
    let mut catalog=serde_json::from_str(ACTIONS).expect("compiled action catalog");desktop_catalog::extend(&mut catalog);desktop_catalog::extend_actions(&mut catalog);catalog
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
    #[serde(skip_serializing_if="bitrate::is_manual")]
    pub bitrate_auto: bool,
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
            bitrate_auto: false,
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
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
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
/// A reachability observation may leave authenticated pairing state unknown.
#[derive(Clone,Debug)]
pub struct DeviceObservation {pub device:Device,pub pairing_verified:bool,pub check:Option<DeviceCheck>}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Saved {
    /// Local appearance preference, deliberately outside the WebDAV document.
    pub hide_keyboard_button: bool,
    pub simple_keyboard: bool,
    pub device_order_initialized: bool,
    /// Local membership established by an applied shared document. Kept outside
    /// SharedConfig so a legacy identity cache is imported only where appropriate.
    pub device_membership_authoritative: bool,
    pub profiles: BTreeMap<String, Profile>,
    pub dark: bool,
    pub devices: Vec<sync_model::SharedDevice>,
    pub client_id: String,
    pub clients: BTreeMap<String, sync_model::ClientInfo>,
    pub client_profiles: BTreeMap<String, BTreeMap<String, sync_model::ClientProfile>>,
    pub profile_schema_version: u32,
    pub relay_defaults_version: u32,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct State {
    pub platform: String,
    pub devices: Vec<Device>,
    pub devices_refreshing: bool,
    pub devices_refresh_error: String,
    pub devices_pending: Vec<String>,
    pub device_checks: BTreeMap<String,DeviceCheck>,
    /// Last unresolved identity or authentication failure in this process.
    /// Current reachability remains separately available in device_checks.
    pub device_trust_checks: BTreeMap<String,DeviceCheck>,
    pub saved: Saved,
    pub page: String,
    pub selected: String,
    pub window_picker: window_source::WindowPicker,
    pub device_query: String,
    pub add: add_device::AddState,
    pub info_draft: Option<device_info::InfoDraft>,
    pub draft: Option<Profile>,
    #[serde(skip)]
    profile_draft_id: String,
    pub draft_display_name: String,
    pub draft_address: String,
    pub draft_addresses: String,
    pub draft_relay: String,
    pub settings_inputs: BTreeMap<String,String>,
    pub settings_error: String,
    pub settings_error_field: String,
    pub settings_error_request: u64,
    #[serde(skip)]
    pending_navigation: Option<interaction::PendingNavigation>,
    #[serde(skip)]
    decision_generation: u64,
    #[serde(skip)]
    sync_clear_request_id: String,
    pub sync_clear_pending: bool,
    pub profile_tab: String,
    pub profile_sources_expanded: bool,
    pub profile_copy: Option<profile_copy::Preview>,
    pub profile_copy_notice: String,
    pub profile_copy_error: String,
    pub settings_catalog: Value,
    pub status: String,
    pub streaming: bool,
    pub connecting: bool,
    pub connection: connection::Connection,
    pub closing: bool,
    #[serde(skip)]
    connect_in_flight: bool,
    #[serde(skip)]
    desktop_open_requests:usize,
    #[serde(skip)]
    stream_epoch: u64,
    pub audio_enabled: bool,
    pub volume: u32,
    pub statistics: Value,
    pub network: Value,
    pub diagnostics: DiagnosticState,
    pub controls: bool,
    pub floating_keyboard: bool,
    pub keyboard_modifiers: Vec<String>,
    pub floating_keyboard_request: u64,
    pub floating_keyboard_error: String,
    pub controls_tab: String,
    pub control_action: session_ui::ControlAction,
    pub control_requests: usize,
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
    #[serde(skip)]
    preview_request: Option<PreviewRequest>,
    pub toolbar: Value,
    pub ui_rendering: Value,
    pub settings_tab: String,
    pub proxy: Value,
    pub proxy_draft: Option<Value>,
    pub proxy_error: String,
    pub sync: Value,
    #[serde(skip)]
    sync_draft: Option<Value>,
    pub sync_error: String,
    pub sync_generation: u64,
    pub sync_busy: bool,
    pub updates: Value,
    pub update_details: bool,
    pub updates_busy: bool,
    #[serde(skip)]
    update_operation: u64,
    #[serde(skip)]
    update_cancel_requested: bool,
    #[serde(skip)]
    installing_update: bool,
}

/// Preserve configured endpoints when discovery temporarily selects another route.
fn connection_device(state: &State, uuid: &str) -> Result<Device, String> {
    let mut device = state.devices.iter().find(|d| d.uuid == uuid)
        .ok_or("unknown device")?.clone();
    if let Some(saved) = state.saved.devices.iter().find(|d| d.uuid == uuid) {
        let configured: Vec<String> = std::iter::once(&saved.address)
            .chain(saved.addresses.iter()).cloned().collect();
        device.addresses = network::merge_extra(&device.address, &configured, &device.addresses);
    }
    Ok(device)
}
#[derive(Clone, Copy, Debug)]
pub struct ActionTiming {
    pub requested_at_us: u128,
    pub queue_wait_us: u128,
}
pub trait Platform: Send + Sync {
    /// A desktop browser delegates validated connection requests to independent windows.
    fn separate_sessions(&self) -> bool { false }
    fn session_only(&self) -> bool { false }
    fn sessions_state(&self) -> Option<Value> { None }
    fn session_identity(&self) -> Option<Value> { None }
    fn persist_session_preferences(&self, _saved:&Saved) -> Option<Result<(),String>> { None }
    /// Local task construction only; all I/O and cleanup run on its own worker.
    fn prepare_diagnostic(&self,_device:&Device,_profile:&Profile)->Option<Result<Box<dyn DiagnosticTask>,String>>{None}
    /// Construct a cancellable authenticated window-list request without starting a stream.
    fn prepare_windows(&self,_device:&Device,_profile:&Profile)->Option<Result<Box<dyn DiagnosticTask>,String>>{None}
    /// Register cancellation before the admitted worker can enter blocking I/O.
    fn begin_update(&self,_operation:u64,_action:&str,_cancellable:bool)->Result<(),String>{Ok(())}
    fn cancel_update(&self,_operation:u64)->Result<(),String>{Err("此更新操作不支持取消".into())}
    fn execute(&self, action: &str, args: &Value) -> Result<Value, String>;
    fn observe_devices(&self,args:&Value,_publish:&mut dyn FnMut(DeviceObservation))->Result<Value,String>{self.execute("devices.refresh",args)}
    fn connection_requested(&self, _timing: Option<ActionTiming>) {}
    fn update_state(&self) -> Option<Value> { None }
    fn proxy_state(&self)->Option<Value>{None}
    /// Called only after the exact shared configuration has been persisted and read back.
    fn sync_applied(&self, _shared:&sync_model::SharedConfig, _preferences:&std::path::Path)->Result<Option<Value>,String>{Ok(None)}
    fn probe_device(&self,address:&str,cancel:Arc<std::sync::atomic::AtomicBool>)->Result<Device,String>{
        if cancel.load(std::sync::atomic::Ordering::SeqCst){return Err("已取消添加".into());}
        let value=self.execute("device.add.probe",&json!({"address":address}))?;
        serde_json::from_value(value["device"].clone()).map_err(|_|"设备信息无效".into())
    }
    /// Local cache publication only; callers have already committed shared host facts.
    fn added_device(&self,_device:&Device){}
}
pub struct Controller {
    state: Mutex<State>,
    storage: PathBuf,
    platform: Arc<dyn Platform>,
    owner:std::sync::Weak<Controller>,
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
        if storage.exists() && saved.relay_defaults_version < 1 {
            let backup = storage.with_extension("pre-https-relay.json");
            if !backup.exists() { fs::copy(&storage, backup).map_err(|e| e.to_string())?; }
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
        let controller = Arc::new_cyclic(|owner|Self {
            state: Mutex::new(State {
                platform: platform_name.into(),
                devices: vec![],
                devices_refreshing: false,
                devices_refresh_error: String::new(),
                devices_pending: Vec::new(),
                device_checks:BTreeMap::new(),
                device_trust_checks:BTreeMap::new(),
                saved,
                page: "devices".into(),
                selected: String::new(),
                window_picker: window_source::WindowPicker::default(),
                device_query: String::new(),
                add: add_device::AddState::default(),
                info_draft: None,
                draft: None,
                profile_draft_id: String::new(),
                draft_display_name: String::new(),
                draft_address: String::new(),
                draft_addresses: String::new(),
                draft_relay: String::new(),
                settings_inputs:BTreeMap::new(),settings_error:String::new(),settings_error_field:String::new(),settings_error_request:0,pending_navigation:None,decision_generation:0,sync_clear_request_id:String::new(),sync_clear_pending:false,
                profile_tab: "video".into(),
                profile_sources_expanded: false,
                profile_copy: None,
                profile_copy_notice: String::new(),
                profile_copy_error: String::new(),
                settings_catalog: settings_catalog(),
                status: String::new(),
                streaming: false,
                connecting: false,
                connection: connection::Connection::default(),
                closing: false,
                connect_in_flight: false,
                desktop_open_requests:0,
                stream_epoch: 0,
                audio_enabled: false,
                volume: 100,
                statistics: Value::Null,
                network: Value::Null,
                diagnostics: DiagnosticState::default(),
                controls: false,
                floating_keyboard: false,
                keyboard_modifiers: Vec::new(),
                floating_keyboard_request: 0,
                floating_keyboard_error: String::new(),
                controls_tab: "input".into(),
                control_action: Default::default(),
                control_requests: 0,
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
                preview_request: None,
                toolbar: Value::Null,
                ui_rendering: Value::Null,
                settings_tab: "updates".into(),
                sync: platform
                    .execute("sync.state", &json!({}))
                    .unwrap_or(json!({})),
                proxy: platform.proxy_state().filter(|v|v.get("protocol").is_some()).unwrap_or_else(||proxy::Settings::default().form()),
                proxy_draft: None,
                proxy_error: String::new(),
                sync_busy: false,
                sync_draft: None,
                sync_error: String::new(),
                sync_generation: 0,
                updates: platform
                    .execute("updates.state", &json!({}))
                    .unwrap_or(json!({})),
                updates_busy: false,
                update_operation: 0,
                update_cancel_requested: false,
                update_details: false,
                installing_update: false,
            }),
            storage,
            platform,
            owner:owner.clone(),
        });
        // Persist the installation identity before the first sync, including on a fresh install.
        controller.persist(&controller.state.lock().unwrap().saved)?;
        Ok(controller)
    }
    pub fn state(&self) -> Value {
        let s=self.state.lock().unwrap();
        let mut value = serde_json::to_value(&*s).unwrap();
        if s.connection.operation=="pair" && s.connection.phase!="pairing" {value["pairPin"]=json!("");}
        value["floatingKeyboardSession"]=json!(s.stream_epoch);
        value["profileDraftId"]=json!(if s.draft.is_some(){s.profile_draft_id.as_str()}else{""});
        value["settingsDirty"]=json!(interaction::dirty(&s));
        value["infoDirty"]=json!(device_info::dirty(&s));
        diagnostics::enrich(&s,&mut value);
        value["connection"]["title"]=json!(s.connection.title());
        value["connection"]["busy"]=json!(s.connection.busy());
        value["navigation"]=json!({"locked":connection::navigation_locked(&s),"reason":connection::navigation_hint(&s)});
        value["proxyDirty"]=json!(s.proxy_draft.as_ref().is_some_and(|v|v!=&s.proxy));
        value["proxyBusy"]=json!(s.streaming||s.connecting||s.sync_busy||s.updates_busy||s.installing_update||s.diagnostics.busy);
        value["syncDirty"]=json!(interaction::sync_dirty(&s));
        value["syncPasswordReusable"]=json!(interaction::sync_password_reusable(&s));
        value["updateInstallHint"]=json!(global_settings::update_installation_blocked(&s).unwrap_or(""));
        value["updatesBusy"]=json!(s.updates_busy || s.installing_update);
        let update_operation=s.update_operation;
        let update_cancellable=s.updates_busy && !s.installing_update && !s.update_cancel_requested;
        let update_cancelling=s.updates_busy && s.update_cancel_requested;
        value["syncDraft"]=s.sync_draft.clone().map(|mut d|{d.as_object_mut().unwrap().remove("password");d}).unwrap_or(Value::Null);
        value["syncClearPrompt"]=if s.sync_clear_pending{json!({"requestId":s.sync_clear_request_id,"title":"移除同步设置？","message":"仅移除此设备保存的 WebDAV 地址和登录信息，并关闭自动同步。本机远程设备和服务器上的配置文件保留。"})}else{Value::Null};
        value["navigationPrompt"]=if let Some(pending)=&s.pending_navigation{json!({"requestId":pending.request_id,"kind":if pending.info{"unsaved-info"}else if pending.sync{"unsaved-sync"}else{"unsaved-device"},"title":"保存更改？","message":if pending.info{"设备名称有未保存的更改。"}else if pending.sync{"WebDAV 设置有未保存的更改。"}else{"连接设置有未保存的更改。"}})}else{Value::Null};
        drop(s);
        // Read progress without taking the updater's network/installation lock.
        if let Some(sessions)=self.platform.sessions_state() {
            if sessions["busy"]==true {
                value["proxyBusy"]=json!(true);
                value["updateInstallHint"]=json!("请先关闭所有远程窗口");
            }
            value["desktopSessions"]=sessions;
        }
        if let Some(session)=self.platform.session_identity(){value["desktopSession"]=session;}
        if let Some(updates) = self.platform.update_state() { value["updates"] = updates; }
        value["updates"]["operationId"]=json!(update_operation);
        value["updates"]["intent"]=json!(global_settings::update_primary_intent(&value["updates"],update_operation));
        value["updates"]["canCancel"]=json!(update_cancellable);
        value["updates"]["cancelRequested"]=json!(update_cancelling);
        if update_cancelling {value["updates"]["phase"]=json!("cancelling");value["updates"]["status"]=json!("正在取消更新…");}
        value
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
        if !s.streaming && !s.connecting && s.page == "devices"
            && !devices.iter().any(|d| d.uuid == s.selected)
        {
            s.selected = devices.iter().find(|d| !d.is_self).or_else(|| devices.first())
                .map(|d| d.uuid.clone()).unwrap_or_default();
        }
        let present:std::collections::HashSet<_>=devices.iter().map(|device|device.uuid.clone()).collect();
        s.devices_pending.retain(|uuid|present.contains(uuid)&&!devices.iter().any(|device|&device.uuid==uuid&&device.is_self));
        s.device_checks.retain(|uuid,_|present.contains(uuid));
        s.device_trust_checks.retain(|uuid,_|present.contains(uuid));
        s.devices = devices;
        s.revision += 1;
    }
    /// Bootstrap local records without claiming that their network or pairing
    /// state has already been checked. This is not a public control action.
    pub fn replace_devices_pending(&self,devices:Vec<Device>){
        self.replace_devices(devices);let mut state=self.state.lock().unwrap();
        state.devices_pending=state.devices.iter().filter(|device|!device.is_self).map(|device|device.uuid.clone()).collect();state.revision+=1;
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
        if epoch != s.stream_epoch || s.connect_in_flight || statistics.is_null() {
            return;
        }
        let was_connecting = s.connecting;
        if streaming && !s.streaming {
            s.status = String::new();
        } else if !streaming && s.streaming {
            s.status = "已断开".into();
        }
        if s.streaming && !streaming {session_ui::clear(&mut s);}
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
            Some("connecting" | "starting" | "reconnecting" | "cancelling")
        ) || statistics["nativeCleanupPending"] == true;
        if streaming && statistics["firstFrame"] == false {
            s.connecting = true;
        }
        if let Some(error) = statistics["error"].as_str().filter(|e| !e.is_empty() && !s.connection.stopping) {
            s.status = error.into();
        } else if s.connecting {
            s.status = if statistics["phase"] == "cancelling" {
                "正在断开…"
            } else if statistics["phase"] == "reconnecting" {
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
        if s.closing && statistics["nativeCleanupPending"] != false {
            s.connecting = true;
            s.status = "正在断开…".into();
        }
        if s.closing && !streaming && !s.connecting && statistics["nativeCleanupPending"] == false {
            s.closing = false;
            s.window_action = "window.close".into();
        }
        connection::observe(&mut s, streaming, &statistics);
        if self.platform.session_only() && s.page=="devices" && s.connection.operation=="connect" {
            s.page="connection".into();
        }
        if !streaming&&!s.connecting&&!s.connection.busy(){s.controls=false;s.floating_keyboard=false;s.keyboard_modifiers.clear();}
        s.statistics = statistics;
        s.revision += 1;
    }
    pub fn toolbar_state(&self, value: Value) {
        let mut s = self.state.lock().unwrap();
        session_ui::keyboard_feedback(&mut s, &value["floatingKeyboard"]);
        if s.toolbar != value {
            s.toolbar = value;
            s.revision += 1;
        }
    }
    pub fn ui_rendering(&self, value: Value) {
        let mut s=self.state.lock().unwrap();
        s.ui_rendering=value;
        s.revision+=1;
    }
    pub fn pairing_complete(&self, attempt: u64, device: Device, error: String, cleanup_pending: bool) {
        let mut s = self.state.lock().unwrap();
        if s.page != "pair" || s.pair_attempt != attempt || s.selected != device.uuid {
            return;
        }
        if let Some(row) = s.devices.iter_mut().find(|d| d.uuid == device.uuid) {
            *row = device.clone();
        }
        s.pair_pin.clear();
        s.connect_in_flight=false;
        s.stream_epoch+=1;
        if error.is_empty() && device.paired {
            s.devices_pending.retain(|uuid|uuid!=&device.uuid);
            s.clear_device_checks(&device.uuid);
            s.page="devices".into();s.connecting=false;
            s.connection.phase="paired".into();s.connection.error.clear();s.status="配对完成".into();
        } else {
            s.page="connection".into();s.connecting=cleanup_pending;
            s.connection.error=if error.is_empty(){"配对未完成，请重试".into()}else{error};
            s.connection.phase=if cleanup_pending{"cleaning"}else{"failed"}.into();
            s.status=s.connection.error.clone();
        }
        s.revision += 1;
    }
    fn persist(&self, saved: &Saved) -> Result<(), String> {
        if let Some(result)=self.platform.persist_session_preferences(saved){return result;}
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
        if self.platform.session_only() {
            if matches!(id,"devices.open"|"connection.dismiss"|"navigation.back") {
                let _=self.platform.execute("session.return",&json!({}));
                return self.dispatch("window.close",json!({}));
            }
            if !desktop_sessions::session_action(id) {
                return json!({"ok":false,"action":id,"error":"请在设备主窗口中修改此设置"});
            }
        }
        if let Some(error)=self.detached_session_block(id){return json!({"ok":false,"action":id,"error":error});}
        let profile_origin=args.get("draftId").cloned();
        if !interaction::profile_origin_matches(&self.state.lock().unwrap(),id,profile_origin.as_ref()){return interaction::ignored_profile_action(id);}
        let result = if let Some(result)=self.update_installation_gate(id,&args){result} else if id=="session.preferences" {self.apply_session_preferences(&args)} else if id=="navigation.resolve" {self.resolve_navigation(args)} else if id=="navigation.back" {
            let page={let s=self.state.lock().unwrap();if s.pending_navigation.is_some(){"decision".into()}else{s.page.clone()}};
            let clear_pending=self.state.lock().unwrap().sync_clear_pending;
            let action=if clear_pending {"sync.clear.resolve"} else {match page.as_str(){"decision"=>"navigation.resolve","profile-copy"=>"settings.copy.cancel","connection"=>{let s=self.state.lock().unwrap();if !s.connection.busy(){"connection.dismiss"}else if s.connection.operation=="pair"{"pair.cancel"}else{"stream.disconnect"}},"add"=>"device.add.cancel","pair"=>"pair.cancel",_=>"devices.open"}};
            let result=self.dispatch(action,if clear_pending{json!({"choice":"cancel"})}else if page=="decision"{json!({"choice":"stay"})}else{json!({})});if result["ok"]==true{Ok(result["result"].clone())}else{Err(result["error"].as_str().unwrap_or("返回失败").into())}
        } else if let Some(result)=self.window_picker_gate(id){result} else if let Some(result)=self.diagnostic_gate(id){result} else if let Some(result)=self.connection_gate(id) {result} else if id.starts_with("connection.") {self.connection_action(id)} else if is_session_control_action(id){self.dispatch_session_control(id,args)} else if let Some(result)=self.navigation_gate(id,&args) {result} else if matches!(id,"device.network.diagnose"|"device.network.cancel"|"device.network.details.toggle"){self.dispatch_diagnostic(id,args)} else if let Some(result)=self.profile_copy_gate(id){result} else if id.starts_with("settings.copy."){self.dispatch_profile_copy(id,args)} else if let Some(result)=self.add_gate(id,&args){result} else if id.starts_with("device.add.") {
            self.dispatch_add(id,args)
        } else if id.starts_with("device.info.") {
            self.dispatch_info(id,args)
        } else if id.starts_with("session.") {
            self.platform.execute(id,&args)
        } else if id.starts_with("updates.") {
            self.dispatch_update(id,&args)
        } else if id.starts_with("proxy.") || id.starts_with("sync.") || id.starts_with("app.settings.") {
            self.dispatch_global(id, args)
        } else if matches!(id,"device.windows.open"|"device.windows.close"|"device.window.connect") {
            self.dispatch_window_picker(id,args)
        } else if id=="devices.refresh" {
            self.refresh_devices(args)
        } else {
            self.dispatch_inner(id, args, timing)
        };
        let response = match result {
            Ok(value) => json!({"ok":true,"action":id,"result":value}),
            Err(error) => json!({"ok":false,"action":id,"error":error}),
        };
        let mut s = self.state.lock().unwrap();
        // A command can become stale after admission while another API call replaces
        // the draft. Never attach that failure to the newly opened form.
        if response["ok"]!=true&&!interaction::profile_origin_matches(&s,id,profile_origin.as_ref()){return interaction::ignored_profile_action(id);}
        if response["ok"]!=true && id.starts_with("proxy."){s.proxy_error=response["error"].as_str().unwrap_or("代理设置未保存").into();}
        if response["ok"]!=true && id.starts_with("device.add.") && s.page=="add" && !s.add.busy(){s.add.error=response["error"].as_str().unwrap_or("添加设备失败").into();}
        if response["ok"]!=true && id.starts_with("device.info."){if let Some(d)=s.info_draft.as_mut(){d.error=response["error"].as_str().unwrap_or("设备名称保存失败").into();}}
        if response["ok"]!=true && id.starts_with("settings."){if id.starts_with("settings.copy.")||id=="settings.copy_from"||s.page=="profile-copy"{s.profile_copy_error=response["error"].as_str().unwrap_or("复制配置失败").into();}else{s.settings_error=response["error"].as_str().unwrap_or("设置修改失败").into();}}
        s.last_action = response.clone();
        s.revision += 1;
        response
    }
    fn dispatch_inner(&self, id: &str, mut args: Value, timing: Option<ActionTiming>) -> Result<Value, String> {
        let mut s = self.state.lock().unwrap();
        interaction::ensure_profile_origin(&s,id,&args)?;
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
        if matches!(id,"settings.input"|"settings.set"|"settings.step"|"settings.network.edit"|"settings.route.move"|"settings.route.toggle"){s.profile_copy_notice.clear();}
        match id {
            "device.select" => {
                if s.streaming || s.connecting {
                    return Err("请先返回空闲设备列表".into());
                }
                let uuid = args["uuid"].as_str().ok_or("uuid required")?;
                if !s.devices.iter().any(|d| d.uuid == uuid) {
                    return Err("unknown device".into());
                }
                s.selected = uuid.into();
                s.page="devices".into();
            }
            "devices.filter" => {
                let query = args["query"].as_str().ok_or("query required")?;
                if query.chars().count() > 128 {
                    return Err("搜索内容不能超过 128 字符".into());
                }
                s.device_query = query.into();
            }
            "devices.filter.clear" => {
                if args["expectedQuery"].as_str() != Some(s.device_query.as_str()) {
                    return Err("搜索内容已变化，请重新操作".into());
                }
                s.device_query.clear();
            }
            "device.move" => {
                if s.streaming || s.connecting {
                    return Err("请先结束远程连接".into());
                }
                let uuid = args["uuid"].as_str().ok_or("uuid required")?;
                let index = s
                    .devices
                    .iter()
                    .position(|d| d.uuid == uuid)
                    .ok_or("unknown device")?;
                let target = if args.get("targetUuid").is_some() {
                    let target=args["targetUuid"].as_str().ok_or("targetUuid must be a string")?;
                    s.devices.iter().position(|d|d.uuid==target).ok_or("unknown target device")?
                } else { match args["direction"].as_str() {
                    Some("up") => index.saturating_sub(1),
                    Some("down") => (index + 1).min(s.devices.len() - 1),
                    _ => return Err("direction must be up or down".into()),
                }};
                let placement=if args.get("targetUuid").is_some(){match args["placement"].as_str(){Some("before")=>Some(false),Some("after")=>Some(true),_=>return Err("placement must be before or after".into())}}else{None};
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
                if let Some(after)=placement {
                    if a!=b {let device=next.devices.remove(a);let destination=b-usize::from(a<b)+usize::from(after);next.devices.insert(destination,device);}
                }else{next.devices.swap(a, b);}
                self.persist(&next)?;
                s.saved = next;
                if let Some(after)=placement {
                    if index!=target {let device=s.devices.remove(index);let destination=target-usize::from(index<target)+usize::from(after);s.devices.insert(destination,device);}
                }else{s.devices.swap(index, target);}
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
                let changed=host.address!=primary || host.addresses!=all.iter().filter(|a|!a.eq_ignore_ascii_case(&primary)).cloned().collect::<Vec<_>>();
                host.address=primary.clone();host.addresses=all.into_iter().filter(|a|!a.eq_ignore_ascii_case(&primary)).collect();
                let extras=host.addresses.clone();
                self.persist(&next)?;
                s.saved=next;
                let host=s.devices.iter_mut().find(|d|d.uuid==uuid).unwrap();host.address=primary;host.addresses=extras;
                if changed {s.clear_device_checks(uuid);if !s.devices_pending.contains(&uuid.to_owned()){s.devices_pending.push(uuid.to_owned());}}
                s.status="连接地址已保存".into();
                let rows=s.devices.clone();drop(s);
                self.platform.execute("devices.restore", &json!({"devices":rows}))?;
                return Ok(json!({"saved":true}));
            }
            "devices.open" | "settings.cancel" => {
                if s.page=="connection" {s.status.clear();}
                s.page = "devices".into();
                interaction::clear(&mut s);
                device_import::select_available(&mut s);
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
                let draft_id=random_token()?;
                s.settings_inputs.clear();s.settings_error.clear();s.settings_error_field.clear();
                s.profile_draft_id=draft_id;
                s.draft = Some(s.saved.effective_profile(&uuid));
                let host=s.saved.devices.iter().find(|d|d.uuid==uuid).map(|d|(d.address.clone(),d.addresses.clone())).unwrap_or_else(||{let d=s.devices.iter().find(|d|d.uuid==uuid).unwrap();(d.address.clone(),d.addresses.clone())});
                s.draft_address=host.0;s.draft_addresses=host.1.join(", ");s.draft_relay=s.draft.as_ref().unwrap().relay_url.clone();
                s.draft_display_name = s
                    .saved
                    .devices
                    .iter()
                    .find(|d| d.uuid == uuid)
                    .map(|d| d.display_name.clone())
                    .unwrap_or_default();
                s.selected = uuid;
                s.page = "settings".into();
                s.status.clear();
                s.profile_sources_expanded = false;
            }
            "settings.route.move" | "settings.route.toggle" => {
                let mut next=s.draft.clone().ok_or("open device settings first")?;
                let route=args["route"].as_str().filter(|r|network::ROUTES.iter().any(|(id,_)|r==id)).ok_or("unknown route")?;
                if id=="settings.route.toggle" {
                    if next.route_order.iter().any(|r|r==route){next.route_order.retain(|r|r!=route);}else{next.route_order.push(route.into());}
                }else{
                    let index=next.route_order.iter().position(|r|r==route).ok_or("route is disabled")?;
                    if args.get("targetRoute").is_some(){
                        let target=args["targetRoute"].as_str().ok_or("targetRoute must be a string")?;
                        let target=next.route_order.iter().position(|r|r==target).ok_or("target route is disabled")?;
                        let after=match args["placement"].as_str(){Some("before")=>false,Some("after")=>true,_=>return Err("placement must be before or after".into())};
                        if index!=target{let route=next.route_order.remove(index);next.route_order.insert(target-usize::from(index<target)+usize::from(after),route);}
                    }else{
                        let target=match args["direction"].as_str(){Some("up")=>index.saturating_sub(1),Some("down")=>(index+1).min(next.route_order.len()-1),_=>return Err("direction must be up or down".into())};
                        next.route_order.swap(index,target);
                    }
                }
                next.validate()?;s.draft=Some(next);
            }
            "settings.input" => {
                if s.draft.is_none(){return Err("请先打开连接设置".into());}
                let key=args["key"].as_str().ok_or("key required")?;let value=args["value"].as_str().filter(|v|v.len()<=4096).ok_or("输入内容过长")?;
                if key=="bitrateMbps"&&s.draft.as_ref().is_some_and(|p|p.bitrate_auto){return Err("请先关闭自动码率再修改手动值".into());}
                if key=="displayName"{s.draft_display_name=value.into();}else if settings_catalog().as_array().unwrap().iter().any(|f|f["key"]==key&&f["type"]=="number"){s.settings_inputs.insert(key.into(),value.into());}else{return Err("unknown editable field".into());}
                s.settings_error.clear();s.settings_error_field.clear();
            }
            "settings.step" => {
                if s.draft.is_none(){return Err("请先打开连接设置".into());}
                let key=args["key"].as_str().ok_or("key required")?;let meta=settings_catalog().as_array().unwrap().iter().find(|f|f["key"]==key&&f["type"]=="number").cloned().ok_or("unknown numeric field")?;
                if key=="bitrateMbps"&&s.draft.as_ref().is_some_and(|p|p.bitrate_auto){return Err("请先关闭自动码率再修改手动值".into());}
                let base=serde_json::to_value(s.draft.as_ref().unwrap()).unwrap()[key].as_i64().unwrap();let number=s.settings_inputs.get(key).and_then(|v|v.trim().parse::<i64>().ok()).unwrap_or(base);
                let sign=match args["direction"].as_str(){Some("up")=>1,Some("down")=>-1,_=>return Err("direction required".into())};
                let value=(number+sign*meta["step"].as_i64().unwrap_or(1)).clamp(meta["min"].as_i64().unwrap(),meta["max"].as_i64().unwrap());s.settings_inputs.insert(key.into(),value.to_string());s.settings_error.clear();
            }
            "settings.network.edit" => {
                if s.draft.is_none(){return Err("open device settings first".into());}
                let value=args["value"].as_str().filter(|v|v.len()<=4096).ok_or("network field requires bounded text")?;
                s.settings_error.clear();s.settings_error_field.clear();
                match args["key"].as_str(){Some("address")=>s.draft_address=value.into(),Some("addresses")=>s.draft_addresses=value.into(),Some("relayUrl")=>s.draft_relay=value.into(),_=>return Err("unknown network field".into())}
            }
            "settings.set" => {
                s.settings_error.clear();s.settings_error_field.clear();
                let current = s.draft.as_ref().ok_or("open device settings first")?;
                let mut value = serde_json::to_value(current).unwrap();
                let key = args["key"].as_str().ok_or("key required")?;
                if key=="bitrateMbps"&&current.bitrate_auto{return Err("请先关闭自动码率再修改手动值".into());}
                if key=="bitrateAuto"&&args["value"]==true{if let Some(number)=s.settings_inputs.get("bitrateMbps").and_then(|text|text.trim().parse::<u32>().ok()).filter(|n|(1..=300).contains(n)){value["bitrateMbps"]=json!(number);s.settings_inputs.remove("bitrateMbps");}}
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
                    s.settings_inputs.remove("width");s.settings_inputs.remove("height");
                } else if value.get(key).is_some() || key=="bitrateAuto" {
                    value[key] = args["value"].clone();s.settings_inputs.remove(key);
                } else {
                    return Err("unknown profile option".into());
                }
                let next: Profile = serde_json::from_value(value).map_err(|e| e.to_string())?;
                next.validate()?;
                if key=="relayUrl"{s.draft_relay=next.relay_url.clone();}
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
                let client=args["clientId"].as_str().ok_or("clientId required")?;profile_copy::copy_from(&mut s,client)?;
            }
            "settings.save" => {
                let p=interaction::validated_profile(&mut s)?;
                let mut next = s.saved.clone();
                next.devices = sync_model::from_state(&s).devices;
                let (primary,extra)=interaction::endpoints(&mut s)?;
                let previous_endpoints=s.saved.devices.iter().find(|d|d.uuid==s.selected).map(|d|(&d.address,&d.addresses))
                    .or_else(||s.devices.iter().find(|d|d.uuid==s.selected).map(|d|(&d.address,&d.addresses)));
                let network_changed=previous_endpoints.is_none_or(|(address,addresses)|address!=&primary||addresses!=&extra)
                    || !device_import::same_route(&s.saved.effective_profile(&s.selected),&p);
                let name=match sync_model::display_name(args.get("displayName").and_then(Value::as_str).unwrap_or(&s.draft_display_name)){Ok(v)=>v,Err(e)=>{s.profile_tab="advanced".into();s.settings_error_field="displayName".into();return Err(e);}};
                if let Some(d) = next.devices.iter_mut().find(|d| d.uuid == s.selected) {
                    d.display_name = name.clone();d.address=primary.clone();d.addresses=extra.clone();
                }
                next.save_profile(&s.selected, p);
                self.persist(&next)?;
                let selected = s.selected.clone();
                if let Some(d) = s.devices.iter_mut().find(|d| d.uuid == selected) {
                    d.display_name = name;d.address=primary;d.addresses=extra;
                }
                s.saved = next;
                if network_changed {s.clear_device_checks(&selected);if !s.devices_pending.contains(&selected){s.devices_pending.push(selected);}}
                interaction::clear(&mut s);
                s.page = "devices".into();
                s.status = "连接设置已保存".into();
                let rows=s.devices.clone();s.revision+=1;drop(s);
                if network_changed{self.platform.execute("devices.restore",&json!({"devices":rows}))?;}
                return Ok(json!({"saved":true}));
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
                if s.controls{s.keyboard_modifiers.clear();}
                s.stream_menu.clear();
            }
            "stream.keyboard.floating.set" => {
                if !s.streaming || s.connection.stopping {return Err("没有可操作的串流".into());}
                if s.control_action.busy {return Err("上一项会话操作尚未完成".into());}
                let enabled=args["enabled"].as_bool().ok_or("enabled boolean required")?;
                if enabled {
                    s.floating_keyboard_request=s.floating_keyboard_request.checked_add(1).ok_or("keyboard request exhausted")?;
                }
                s.floating_keyboard=enabled;
                s.keyboard_modifiers.clear();
                s.floating_keyboard_error.clear();
                s.controls=false;s.stream_menu.clear();
            }
            "stream.keyboard.button.set" => {
                if s.control_action.busy{return Err("上一项会话操作尚未完成".into());}
                let enabled=args["enabled"].as_bool().ok_or("enabled boolean required")?;
                let mut next=s.saved.clone();next.devices=sync_model::from_state(&s).devices;
                next.hide_keyboard_button=!enabled;self.persist(&next)?;s.saved=next;
            }
            "stream.keyboard.layout.set" => {
                if s.control_action.busy{return Err("上一项会话操作尚未完成".into());}
                let simple=match args["layout"].as_str(){Some("simple")=>true,Some("full")=>false,_=>return Err("layout must be full or simple".into())};
                let mut next=s.saved.clone();next.devices=sync_model::from_state(&s).devices;next.simple_keyboard=simple;self.persist(&next)?;s.saved=next;s.keyboard_modifiers.clear();s.floating_keyboard_error.clear();
            }
            "stream.keyboard.catalog" => return Ok(serde_json::from_str(keyboard::JSON).unwrap()),
            "stream.controls.tab" => {
                if !s.streaming || !s.controls {return Err("请先展开活动串流的控制栏".into());}
                let tab=args["tab"].as_str().unwrap_or("");if !["input","displays","audio"].contains(&tab){return Err("unknown stream control tab".into());}
                s.controls_tab=tab.into();s.stream_menu.clear();
            }
            "stream.menu.open" => {
                if !s.streaming || !s.controls {
                    return Err("请先展开活动串流的控制栏".into());
                }
                let menu = args["menu"].as_str().unwrap_or("");
                if !["shortcuts", "displays", "feedback"].contains(&menu) {
                    return Err("unknown stream menu".into());
                }
                if menu=="feedback" && s.control_action.error.is_empty(){return Err("没有会话操作错误".into());}
                s.stream_menu = menu.into();
            }
            "stream.menu.close" => s.stream_menu.clear(),
            "stream.shortcuts.list" => return Ok(shortcuts()),
            "stream.shortcuts.open" | "stream.displays.open" => {if !s.streaming {return Err("没有可操作的串流".into());}s.controls=true;s.controls_tab=if id=="stream.shortcuts.open"{"input"}else{"displays"}.into();s.stream_menu=if id=="stream.shortcuts.open"{"shortcuts"}else{""}.into();},
            "view.snapshot" | "view.toolbar.snapshot" | "view.picker.snapshot" | "view.keyboard.snapshot" => {
                return preview::request(&mut s,id,&args);
            }
            "window.minimize"
            | "window.maximize"
            | "window.activate"
            | "window.close"
            => {
                if id == "window.close" {
                    if diagnostics::defer_close(&mut s){return Ok(json!({"cleanupPending":true,"diagnostic":true}));}
                    s.stream_epoch += 1;
                    drop(s);
                    let result = self.platform.execute("engine.close", &json!({}));
                    s = self.state.lock().unwrap();
                    let result=match result {Ok(value)=>value,Err(error)=>{s.closing=false;return Err(error)}};
                    if result["cleanupPending"] == true {
                        s.closing = true;
                        s.connecting = true;
                        s.window_action.clear();
                        s.status = "正在断开…".into();
                    } else {
                        s.window_action = id.into();
                    }
                } else {
                    s.window_action = id.into();
                }
            }
            _ => {
                if id == "pair.cancel" {
                    if s.connection.operation!="pair" || !s.connection.busy() || s.connection.stopping || s.connection.phase=="cleaning" {
                        return Err("no active pairing".into());
                    }
                    s.pair_attempt+=1;s.pair_pin.clear();s.page="connection".into();
                    s.connection.stopping=true;s.connection.phase="cancelling".into();
                    s.connecting=true;s.connect_in_flight=true;s.stream_epoch+=1;
                    s.status="正在取消配对…".into();s.revision+=1;
                }
                if id.starts_with("stream.") || id == "input.gesture" {
                    if !s.streaming && !(id == "stream.disconnect" && s.connecting) {
                        return Err("no active stream".into());
                    }
                }
                if id == "device.connect" || id == "device.pair" {
                    if s.window_picker.busy{return Err("窗口列表请求尚未清理，请稍候".into());}
                    if s.control_requests>0{return Err("上一项会话操作正在结束，请稍候".into());}
                    if s.page == "pair" {
                        return Err("请先完成或取消当前配对".into());
                    }
                    if s.streaming || s.connecting {
                        return Err("disconnect the current session first".into());
                    }
                    let uuid = args["uuid"].as_str().ok_or("uuid required")?.to_owned();
                    let d = connection_device(&s, &uuid)?;
                    if d.is_self {
                        return Err("this is the local device; its configuration remains available for editing and sync".into());
                    }
                    let check=s.device_trust_checks.get(&uuid).or_else(||s.device_checks.get(&uuid));
                    if check.is_some_and(|check|check.blocks_connection()){return Err("设备身份检查未通过，请检查连接地址".into());}
                    if id=="device.connect" && check.is_some_and(|check|check.repair_required()){return Err(check.unwrap().hint().into());}
                    if s.devices_pending.contains(&uuid) && (id=="device.connect"&&!d.paired || id=="device.pair"&&!d.online){return Err("设备状态尚未确认，请先检查连接".into());}
                    if id == "device.connect" && !d.paired {
                        return Err("device must be paired first".into());
                    }
                    s.selected = uuid.clone();
                    args["device"] = serde_json::to_value(&d).unwrap();
                    args["profile"] =
                        serde_json::to_value(s.saved.effective_profile(&uuid)).unwrap();
                    if id=="device.connect" {
                        if let Some(window)=args.get("window").cloned(){
                            if s.window_picker.uuid!=uuid||!s.window_picker.rows.iter().any(|row|row==&window&&row["available"]==true)||!window_source::valid(window["id"].as_str().unwrap_or("")){return Err("请重新选择窗口".into());}
                            let (width,height)=window_source::resolution(&window,args["profile"]["width"].as_u64().unwrap_or(1920) as u32,args["profile"]["height"].as_u64().unwrap_or(1080) as u32)?;
                            args["profile"]["width"]=json!(width);args["profile"]["height"]=json!(height);
                        }
                    }
                    if id == "device.connect" && self.platform.separate_sessions() {
                        args["saved"]=serde_json::to_value(&s.saved).map_err(|e|e.to_string())?;
                        s.page="devices".into();s.revision+=1;s.desktop_open_requests+=1;
                        drop(s);
                        self.platform.connection_requested(timing);
                        let result=self.platform.execute("session.open",&args);
                        self.state.lock().unwrap().desktop_open_requests-=1;
                        return result;
                    }
                    if id == "device.connect" {
                        session_ui::clear(&mut s);s.controls_tab="input".into();
                        s.connection.begin(&d);
                        s.connection.window=args.get("window").cloned();
                        s.page = "connection".into();
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
                        s.connection.begin(&d);s.connection.operation="pair".into();s.connection.phase="preparing".into();
                        s.pair_pin = pin;
                        s.page = "pair".into();
                        s.connecting=true;s.connect_in_flight=true;s.stream_epoch+=1;s.status="正在准备配对连接".into();s.revision+=1;
                    }
                }
                if id=="stream.disconnect" {session_ui::clear(&mut s);}
                if id == "stream.disconnect" && !s.connection.uuid.is_empty() {
                    s.connection.stopping=true;
                    s.connection.phase="cancelling".into();
                    s.connecting=true;
                    s.revision+=1;
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
                            state.connection.fail(error.clone());
                        }
                        if id == "device.pair" {
                            let mut state = self.state.lock().unwrap();
                            if args["attemptId"].as_u64() == Some(state.pair_attempt) {
                                state.page = "connection".into();state.connect_in_flight=false;state.connecting=false;
                                state.pair_pin.clear();
                                state.status = error.clone();
                                state.connection.fail(error.clone());state.stream_epoch+=1;
                            }
                        }
                        if id=="pair.cancel" {let mut state=self.state.lock().unwrap();state.connect_in_flight=false;state.stream_epoch+=1;state.status=error.clone();state.connection.error=error.clone();}
                        return Err(error);
                    }
                };
                let mut s = self.state.lock().unwrap();
                match id {
                    "device.connect" => {
                        s.connect_in_flight = false;
                        s.stream_epoch += 1;
                        s.status = "正在连接…".into();
                        if !s.connection.stopping {s.connection.phase="starting".into();}
                        s.show_stats = args["profile"]["statistics"].as_bool().unwrap_or(false);
                    }
                    "pair.cancel" => {
                        s.connect_in_flight=false;s.stream_epoch+=1;
                        if result["cleanupPending"]==false {
                            s.connecting=false;s.connection.phase="cancelled".into();s.connection.error.clear();
                            s.page="devices".into();s.status="已取消配对".into();
                        }
                    }
                    "device.pair" => {if args["attemptId"].as_u64()==Some(s.pair_attempt){s.connect_in_flight=false;s.stream_epoch+=1;}}
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
        let action=std::mem::take(&mut s.window_action);
        if action=="window.close" {preview::cancel(&mut s);}
        action
    }
    pub fn message(&self, text: String) {
        let mut s = self.state.lock().unwrap();
        s.status = text;
        s.revision += 1;
    }
    pub fn take_preview_request(&self) -> Option<PreviewRequest> {
        let mut s=self.state.lock().unwrap();
        let request=s.preview_request.take()?;
        s.preview["status"]=json!("running");
        s.revision+=1;
        Some(request)
    }
    pub fn preview_complete(&self, request: &PreviewRequest, result: Result<Value,String>) {
        let mut s = self.state.lock().unwrap();
        if s.preview["id"]!=request.id || s.preview["window"]!=request.window || s.preview["status"]!="running" {return;}
        match result {
            Ok(value) => {
                for field in ["path","width","height","page","settingsTab","profileTab","controlsTab","menu"] {
                    if let Some(value)=value.get(field) {s.preview[field]=value.clone();}
                }
                s.preview["status"]=json!("complete");s.preview["ok"]=json!(true);
            }
            Err(error) => {s.preview["status"]=json!("failed");s.preview["ok"]=json!(false);s.preview["error"]=json!(error);}
        }
        s.preview["completedAtMs"]=json!(now_ms());
        s.revision += 1;
    }
}

pub fn start_http(
    controller: Arc<Controller>,
    port: u16,
    token: String,
) -> Result<std::thread::JoinHandle<()>, String> {
    start_http_endpoint(controller,port,token).map(|(_,thread)|thread)
}
/// Bind the API atomically, including port zero for private per-session endpoints.
pub fn start_http_endpoint(controller:Arc<Controller>,port:u16,token:String)->Result<(u16,std::thread::JoinHandle<()>),String>{
    if token.len() < 32 {
        return Err("control token must be at least 32 characters".into());
    }
    let server = tiny_http::Server::http((std::net::Ipv4Addr::LOCALHOST, port))
        .map_err(|e| e.to_string())?;
    let bound_port=server.server_addr().to_ip().ok_or("control API is not TCP")?.port();
    Ok((bound_port,std::thread::spawn(move || {
        let update_request = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let sync_request = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let session_request = Arc::new(std::sync::atomic::AtomicBool::new(false));
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
                            if matches!(id,"session.action"|"session.activate"|"session.close") {
                                let controller=controller.clone();let id=id.to_owned();let args=v.get("args").cloned().unwrap_or(json!({}));
                                std::thread::spawn(move||{
                                    let value=controller.dispatch(&id,args);let status=if value["ok"]==true{200}else{422};
                                    let response=tiny_http::Response::from_string(value.to_string()).with_status_code(status)
                                        .with_header(tiny_http::Header::from_bytes("Content-Type","application/json; charset=utf-8").unwrap())
                                        .with_header(tiny_http::Header::from_bytes("Cache-Control","no-store").unwrap());
                                    let _=request.respond(response);
                                });
                                continue;
                            }
                            // Keep state/navigation HTTP calls responsive while a
                            // synchronous network operation waits on its server.
                            if matches!(id,"updates.primary"|"updates.check"|"updates.download"|"updates.install"|"sync.run"|"sync.resolve") || is_session_control_action(id) {
                                use std::sync::atomic::Ordering;
                                let operation=if id.starts_with("sync."){&sync_request}else if is_session_control_action(id){&session_request}else{&update_request};
                                if !operation.swap(true,Ordering::AcqRel) {
                                    let controller=controller.clone();let gate=operation.clone();
                                    let id=id.to_owned();let args=v.get("args").cloned().unwrap_or(json!({}));
                                    std::thread::spawn(move||{
                                        struct Release(Arc<std::sync::atomic::AtomicBool>);
                                        impl Drop for Release {fn drop(&mut self){self.0.store(false,Ordering::Release);}}
                                        let _release=Release(gate);
                                        let value=controller.dispatch(&id,args);
                                        let status=if value["ok"]==true{200}else{422};
                                        let response=tiny_http::Response::from_string(value.to_string()).with_status_code(status)
                                            .with_header(tiny_http::Header::from_bytes("Content-Type","application/json; charset=utf-8").unwrap())
                                            .with_header(tiny_http::Header::from_bytes("Cache-Control","no-store").unwrap());
                                        let _=request.respond(response);
                                    });
                                    continue;
                                }
                                (422,json!({"ok":false,"error":if id.starts_with("sync."){"同步操作正在进行"}else if is_session_control_action(id){"上一项会话操作尚未完成"}else{"更新操作正在进行"},"action":id}))
                            } else {
                            let response = controller
                                .dispatch(id, v.get("args").cloned().unwrap_or(json!({})));
                            (if response["ok"] == true { 200 } else { 422 }, response)
                            }
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
    })))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn snapshot_request_replaces_old_completion_with_an_identifiable_pending_result() {
        let file=std::env::temp_dir().join(format!("rhremote-preview-{}.json",random_token().unwrap()));
        let c=Controller::new("test",file.clone(),Arc::new(Stub)).unwrap();
        c.state.lock().unwrap().preview=json!({"ok":true,"path":"old-toolbar.png"});
        let response=c.dispatch("view.snapshot",json!({"requestId":"main-1"}));
        assert_eq!(response["result"]["id"],"main-1");
        assert_eq!(response["result"]["status"],"queued");
        assert_eq!(c.state()["preview"]["window"],"main");
        assert!(c.state()["preview"]["path"].is_null());
        assert_ne!(c.state()["preview"]["ok"],true);
        let _=std::fs::remove_file(file);
    }
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
    struct CloseTransport { fail: bool }
    impl Platform for CloseTransport {
        fn execute(&self, id:&str, _: &Value)->Result<Value,String> {
            if id!="engine.close"{return Err("fixture action unsupported".into());}
            if self.fail {Err("cleanup unavailable".into())} else {Ok(json!({"cancelled":true,"cleanupPending":true}))}
        }
    }
    #[test]
    fn close_window_waits_for_cleanup_and_rejects_stale_or_missing_poll_evidence() {
        let file=std::env::temp_dir().join(format!("rhremote-close-{}.json",random_token().unwrap()));
        let c=Controller::new("test",file.clone(),Arc::new(CloseTransport {fail:false})).unwrap();
        c.stream_state(false,json!({"phase":"connecting","nativeCleanupPending":false}));let old=c.stream_epoch();
        assert_eq!(c.dispatch("window.close",json!({}))["ok"],true);
        assert_eq!(c.state()["closing"],true);assert!(c.take_window_action().is_empty());
        c.stream_state_for(old,false,json!({"phase":"disconnected","nativeCleanupPending":false}));assert!(c.take_window_action().is_empty());
        c.stream_state(false,Value::Null);assert!(c.take_window_action().is_empty());
        c.stream_state(false,json!({"phase":"cancelling","nativeCleanupPending":true}));assert_eq!(c.state()["connecting"],true);assert!(c.take_window_action().is_empty());
        c.stream_state(false,json!({"phase":"disconnected","nativeCleanupPending":false}));assert_eq!(c.state()["closing"],false);assert_eq!(c.take_window_action(),"window.close");
        let _=fs::remove_file(file);
    }
    #[test]
    fn failed_close_keeps_window_open() {
        let file=std::env::temp_dir().join(format!("rhremote-close-failure-{}.json",random_token().unwrap()));
        let c=Controller::new("test",file.clone(),Arc::new(CloseTransport {fail:true})).unwrap();
        assert_eq!(c.dispatch("window.close",json!({}))["ok"],false);assert_eq!(c.state()["closing"],false);assert!(c.take_window_action().is_empty());
        let _=fs::remove_file(file);
    }
    impl Platform for Stub {
        fn execute(&self, _: &str, _: &Value) -> Result<Value, String> {
            Err("no transport in unit tests".into())
        }
    }
    #[test]
    fn official_relay_migration_is_backed_up_local_and_once_only() {
        use sync_model::ClientProfile;
        let root = std::env::temp_dir().join(format!("rhremote-relay-{}", random_token().unwrap()));
        fs::create_dir(&root).unwrap();
        let file = root.join("preferences.json");
        let old = Profile {
            width: 2560, height: 1440, bitrate_mbps: 41, audio_enabled: true,
            relay_url: network::LEGACY_DEFAULT_RELAY.trim_end_matches('/').into(),
            route_order: vec!["relay".into(), "lan".into()], ..Profile::default()
        };
        let custom = Profile { relay_url: "http://custom.example:33447/".into(), ..old.clone() };
        let mut saved = Saved {
            client_id: "pc".into(), profile_schema_version: 2, device_order_initialized: true,
            ..Saved::default()
        };
        saved.profiles.insert("host".into(), old.clone());
        saved.profiles.insert("custom".into(), custom.clone());
        saved.client_profiles.insert("pc".into(), BTreeMap::from([
            ("host".into(), ClientProfile::from_profile(&old)),
            ("custom".into(), ClientProfile::from_profile(&custom)),
        ]));
        saved.client_profiles.insert("pad".into(), BTreeMap::from([
            ("host".into(), ClientProfile::from_profile(&old)),
        ]));
        let original = serde_json::to_vec_pretty(&saved).unwrap();
        fs::write(&file, &original).unwrap();
        let c = Controller::new("windows", file.clone(), Arc::new(Stub)).unwrap();
        let mut upgraded = old.clone();
        upgraded.relay_url = network::DEFAULT_RELAY.into();
        assert_eq!(c.profile("host"), upgraded);
        assert_eq!(c.profile("custom"), custom);
        let persisted: Saved = serde_json::from_slice(&fs::read(&file).unwrap()).unwrap();
        assert_eq!(persisted.client_profiles["pad"]["host"].relay_url, old.relay_url);
        assert_eq!(persisted.relay_defaults_version, 1);
        let backup = file.with_extension("pre-https-relay.json");
        assert_eq!(fs::read(&backup).unwrap(), original);
        assert!(serde_json::to_value(c.export_shared()).unwrap().get("relayDefaultsVersion").is_none());
        // After migration, an explicit HTTP choice is still a valid user setting.
        let mut edited = persisted;
        edited.client_profiles.get_mut("pc").unwrap().get_mut("host").unwrap().relay_url = old.relay_url.clone();
        edited.profiles.get_mut("host").unwrap().relay_url = old.relay_url.clone();
        fs::write(&file, serde_json::to_vec(&edited).unwrap()).unwrap();
        let restarted = Controller::new("windows", file.clone(), Arc::new(Stub)).unwrap();
        assert_eq!(restarted.profile("host").relay_url, old.relay_url);
        assert_eq!(fs::read(&backup).unwrap(), original);
        fs::remove_file(file).unwrap();
        fs::remove_file(backup).unwrap();
        fs::remove_dir(root).unwrap();
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
    fn browsing_devices_protects_active_sessions_and_leaves_clean_drafts_without_saving() {
        let c = controller();
        let saved = c.state()["saved"].clone();
        assert_eq!(c.dispatch("device.select", json!({"uuid":"b"}))["ok"], true);
        assert_eq!(c.state()["selected"], "b");
        assert!(c.state()["draft"].is_null());
        assert_eq!(c.dispatch("device.select", json!({"uuid":"missing"}))["ok"], false);
        assert_eq!(c.state()["selected"], "b");
        for field in ["streaming", "connecting"] {
            {
                let mut s = c.state.lock().unwrap();
                s.streaming = field == "streaming";
                s.connecting = field == "connecting";
            }
            assert_eq!(c.dispatch("device.select", json!({"uuid":"a"}))["ok"], false);
            assert_eq!(c.state()["selected"], "b");
        }
        { let mut s = c.state.lock().unwrap(); s.streaming=false; s.connecting=false; }
        c.dispatch("device.settings.open", json!({"uuid":"b"}));
        assert_eq!(c.dispatch("device.select", json!({"uuid":"a"}))["ok"], true);
        assert_eq!(c.state()["selected"], "a");
        assert!(c.state()["draft"].is_null());
        assert_eq!(c.state()["saved"], saved);
    }
    #[test]
    fn refresh_preserves_selection_and_only_falls_back_when_a_device_disappears() {
        let c = controller();
        let rows: Vec<Device> = serde_json::from_value(c.state()["devices"].clone()).unwrap();
        c.dispatch("device.select", json!({"uuid":"b"}));
        c.replace_devices(rows.clone());
        assert_eq!(c.state()["selected"], "b");
        c.replace_devices(vec![rows[0].clone()]);
        assert_eq!(c.state()["selected"], "a");
        c.replace_devices(vec![]);
        assert_eq!(c.state()["selected"], "");
        let mut rows = rows;
        rows[0].is_self = true;
        c.replace_devices(rows);
        assert_eq!(c.state()["selected"], "b");
    }
    #[test]
    fn device_search_is_bounded_and_does_not_change_synced_settings() {
        let c = controller();
        let saved = c.state()["saved"].clone();
        let selected = c.state()["selected"].clone();
        assert_eq!(c.dispatch("devices.filter", json!({"query":"测试"}))["ok"], true);
        assert_eq!(c.state()["deviceQuery"], "测试");
        assert_eq!(c.dispatch("devices.filter", json!({"query":"a".repeat(129)}))["ok"], false);
        assert_eq!(c.state()["deviceQuery"], "测试");
        assert_eq!(c.state()["saved"], saved);
        assert_eq!(c.state()["selected"], selected);
    }
    #[test]
    fn discovery_address_change_keeps_saved_primary_for_connect_pair_and_diagnose() {
        struct Capture(Mutex<Vec<Value>>);
        impl Platform for Capture {
            fn execute(&self, action: &str, args: &Value) -> Result<Value, String> {
                if !matches!(action,"device.connect"|"device.pair"|"device.network.diagnose") {
                    return Err("unused test action".into());
                }
                self.0.lock().unwrap().push(args["device"].clone());
                Ok(json!({"accepted":true}))
            }
        }
        for action in ["device.connect","device.pair","device.network.diagnose"] {
            let capture=Arc::new(Capture(Mutex::new(Vec::new())));
            let file=std::env::temp_dir().join(format!("rhremote-endpoints-{}.json",random_token().unwrap()));
            let c=Controller::new("test",file.clone(),capture.clone()).unwrap();
            let devices=serde_json::from_value(controller().state()["devices"].clone()).unwrap();
            c.replace_devices(devices);
            {
                let mut s=c.state.lock().unwrap();
                s.saved.devices=sync_model::from_state(&s).devices;
                s.saved.devices[0].address="192.168.10.20".into();
                s.saved.devices[0].addresses=vec!["100.73.139.109".into()];
                s.devices[0].address="100.73.139.109".into();
                s.devices[0].addresses=vec!["100.73.139.109".into(),"host.example".into()];
            }
            let before=c.state()["saved"].clone();
            assert_eq!(c.dispatch(action,json!({"uuid":"a","pin":"1234"}))["ok"],true,"{action}");
            if action=="device.network.diagnose"{let until=std::time::Instant::now()+std::time::Duration::from_secs(2);while c.state()["diagnostics"]["busy"]==true&&std::time::Instant::now()<until{std::thread::sleep(std::time::Duration::from_millis(2));}assert_eq!(c.state()["diagnostics"]["busy"],false);}
            let requests=capture.0.lock().unwrap();
            assert_eq!(requests.len(),1);
            assert_eq!(requests[0]["address"],"100.73.139.109");
            assert_eq!(requests[0]["addresses"],json!(["192.168.10.20","host.example"]));
            assert_eq!(c.state()["saved"],before,"Route resolution must not rewrite sync preferences");
            fs::remove_file(file).unwrap();
        }
    }
    #[test]
    fn saved_primary_has_priority_when_endpoint_capacity_is_full() {
        let c=controller();
        let mut s=c.state.lock().unwrap();
        s.saved.devices=sync_model::from_state(&s).devices;
        s.saved.devices[0].address="192.168.10.20".into();
        s.saved.devices[0].addresses=(1..8).map(|i|format!("host{i}.example")).collect();
        s.devices[0].address="100.73.139.109".into();
        s.devices[0].addresses=vec!["observed.example".into()];
        let device=connection_device(&s,"a").unwrap();
        let all=network::addresses(&device.address,&device.addresses).unwrap();
        assert_eq!(all.len(),network::MAX_ADDRESSES);
        assert_eq!(all[1],"192.168.10.20");
        assert!(!all.contains(&"host7.example".into()));
        assert!(connection_device(&s,"missing").is_err());
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
        c.pairing_complete(1, device.clone(), String::new(), false);
        assert_eq!(c.state()["page"], "pair");
        c.pairing_complete(2, device.clone(), String::new(), false);
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
        c.pairing_complete(3, device, "配对码未被主机确认".into(), false);
        assert_eq!(c.state()["page"], "connection");
        assert_eq!(c.state()["status"], "配对码未被主机确认");
        assert_eq!(c.state()["pairPin"], "");
    }
    #[test]
    fn failed_repair_is_not_reported_as_success_from_a_cached_pairing() {
        let c=controller();let mut device:Device=serde_json::from_value(c.state()["devices"][0].clone()).unwrap();device.paired=true;
        {let mut s=c.state.lock().unwrap();s.page="pair".into();s.selected=device.uuid.clone();s.pair_attempt=4;s.pair_pin="1234".into();}
        c.pairing_complete(4,device,"配对码不正确".into(),false);
        assert_eq!(c.state()["status"],"配对码不正确");assert_eq!(c.state()["devices"][0]["paired"],true);assert_eq!(c.state()["pairPin"],"");
    }
    #[test]
    fn failed_pair_launch_does_not_leave_a_waiting_pin() {
        let c = controller();
        assert_eq!(
            c.dispatch("device.pair", json!({"uuid":"a","pin":"1234"}))["ok"],
            false
        );
        assert_eq!(c.state()["page"], "connection");
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
            "正在安装更新，请稍候"
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
    fn automatic_bitrate_is_per_device_keeps_manual_edits_and_obeys_form_lifetime() {
        let c=controller();
        let act=|id:&str,args:Value|{let result=c.dispatch(id,args);assert_eq!(result["ok"],true,"{id}: {result}");};
        act("device.settings.open",json!({"uuid":"a"}));
        act("settings.input",json!({"key":"bitrateMbps","value":"41"}));
        act("settings.set",json!({"key":"bitrateAuto","value":true}));
        assert_eq!(c.state()["draft"]["bitrateMbps"],41);
        for(id,args)in[("settings.input",json!({"key":"bitrateMbps","value":"13"})),("settings.step",json!({"key":"bitrateMbps","direction":"up"})),("settings.set",json!({"key":"bitrateMbps","value":13}))]{assert_eq!(c.dispatch(id,args)["ok"],false);}
        act("settings.tab",json!({"tab":"audio"}));act("settings.tab",json!({"tab":"video"}));
        assert_eq!(c.state()["draft"]["bitrateAuto"],true);act("settings.cancel",json!({}));assert!(!c.profile("a").bitrate_auto);
        act("device.settings.open",json!({"uuid":"a"}));act("settings.input",json!({"key":"bitrateMbps","value":"invalid"}));
        act("settings.set",json!({"key":"bitrateAuto","value":true}));act("settings.set",json!({"key":"bitrateAuto","value":false}));
        assert_eq!(c.state()["settingsInputs"]["bitrateMbps"],"invalid");assert_eq!(c.dispatch("settings.save",json!({}))["ok"],false);
        act("settings.set",json!({"key":"bitrateAuto","value":true}));act("settings.save",json!({}));
        assert!(c.profile("a").bitrate_auto);assert!(!c.profile("b").bitrate_auto);assert_eq!(c.profile("a").bitrate_mbps,25);
        let reloaded=Controller::new("test",c.storage.clone(),Arc::new(Stub)).unwrap();assert!(reloaded.profile("a").bitrate_auto);
        let _=fs::remove_file(&c.storage);
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

#[cfg(test)]
mod update_install_tests;
#[cfg(test)]
mod update_cancel_tests;

#[cfg(test)]
mod reorder_tests;
