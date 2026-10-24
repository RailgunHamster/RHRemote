use rhremote_core::{Controller, Profile};
use serde_json::{Value, json};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::{path::PathBuf, sync::Arc, time::Duration};
mod config_sync;
mod devices;
mod network;
mod native_cleanup;
#[cfg(windows)]
mod transport_owner;
#[cfg(windows)]
mod transport_worker;

pub fn run_transport_worker() -> Option<Result<(), String>> {
    #[cfg(windows)]
    { transport_worker::run_from_args() }
    #[cfg(not(windows))]
    { None }
}
mod overlay;
mod pairing;
mod platform;
mod recovery;
mod statistics;
mod updates;
mod ui_rendering;
slint::include_modules!();

fn args_for(id: &str, arg: &str, state: &Value) -> Value {
    match id {
        "device.move" => {
            let (uuid, direction) = arg.split_once('|').unwrap_or(("", ""));
            json!({"uuid":uuid,"direction":direction})
        }
        "device.network.configure" => {
            let mut fields=arg.splitn(3,'|');
            let uuid=fields.next().unwrap_or("");let primary=fields.next().unwrap_or("");let extra=fields.next().unwrap_or("");
            json!({"uuid":uuid,"address":primary.trim(),"addresses":extra.split([',',';','\n']).map(str::trim).filter(|s|!s.is_empty()).collect::<Vec<_>>()})
        }
        "device.network.diagnose" => json!({"uuid":arg}),
        "statistics.configure" => json!({"enabled":arg=="true"}),
        "app.settings.tab" | "settings.tab" => json!({"tab":arg}),
        "stream.audio.volume" => json!({"volume":arg.parse::<u32>().unwrap_or(101)}),
        "stream.shortcut" => json!({"shortcut":arg}),
        "stream.menu.open" => json!({"menu":arg}),
        "stream.window.move" => json!({"direction":arg}),
        "stream.display.select" => json!({"displayId":arg}),
        "sync.configure" => {
            let fields: Vec<_> = arg.splitn(5, '\n').collect();
            if fields.len() == 5 {
                json!({"url":fields[0],"username":fields[1],"password":fields[2],"automatic":fields[3]=="true","intervalMinutes":fields[4].parse::<u32>().unwrap_or(0)})
            } else {
                json!({})
            }
        }
        "sync.resolve" => json!({"resolution":arg}),
        "settings.copy_from" => json!({"clientId":arg}),
        "settings.save" => json!({"displayName":arg}),
        "device.connect" | "device.pair" | "device.settings.open" => json!({"uuid":arg}),
        "device.add.submit" => json!({"address":arg}),
        "settings.route.move" => {let (route,direction)=arg.split_once('|').unwrap_or(("",""));json!({"route":route,"direction":direction})},
        "settings.route.toggle" => json!({"route":arg}),
        "settings.set" => {
            let (key, value) = arg.split_once('=').unwrap_or(("", ""));
            let parsed = if key=="routeOrder" && value=="default" {json!(rhremote_core::network::default_route_order())} else if key == "resolution" {
                Value::Array(
                    value
                        .split('x')
                        .filter_map(|n| n.parse::<u32>().ok())
                        .map(Value::from)
                        .collect(),
                )
            } else if value == "toggle" {
                Value::Bool(!state["draft"][key].as_bool().unwrap_or(false))
            } else if [
                "width",
                "height",
                "fps",
                "bitrateMbps",
                "volume",
                "packetSize",
                "remoteBitrateMbps",
            ]
            .contains(&key)
            {
                json!(value.parse::<u32>().unwrap_or(0))
            } else {
                json!(value)
            };
            json!({"key":key,"value":parsed})
        }
        _ => json!({}),
    }
}
pub fn run() -> Result<(), Box<dyn std::error::Error>> {
    let data = std::env::var_os("RHREMOTE_DATA_DIR")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap_or_default()).join("RHRemote")
        });
    std::fs::create_dir_all(&data)?;
    let token = if let Ok(value) = std::env::var("RHREMOTE_CONTROL_TOKEN") {
        value
    } else {
        rhremote_core::random_token()?
    };
    let engine = native_engine_path(
        std::env::var_os("RHREMOTE_NATIVE_ENGINE"),
        std::env::current_exe()?,
    );
    let native = Arc::new(platform::NativePlatform::new(
        engine,
        token.clone(),
        data.clone(),
    )?);
    let controller = Controller::new("windows", data.join("preferences.json"), native.clone())?;
    native.statistics.lock().unwrap().mark("preferences_loaded");
    let saved = controller.state()["saved"]["devices"]
        .as_array()
        .cloned()
        .unwrap_or_default();
    if !saved.is_empty() {
        let rows:Vec<_>=saved.iter().map(|d|json!({"uuid":d["uuid"],"name":d["name"],"address":d["address"],"addresses":d["addresses"].as_array().cloned().unwrap_or_default(),"online":false,"paired":false,"isSelf":false})).collect();
        use rhremote_core::Platform;
        native.execute("devices.restore", &json!({"devices":rows}))?;
    }
    controller.replace_devices(native.devices.lock().unwrap().clone());
    native.statistics.lock().unwrap().mark("devices_restored");
    if let Ok(port) = std::env::var("RHREMOTE_CONTROL_PORT") {
        rhremote_core::start_http(controller.clone(), port.parse()?, token)?;
    }
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name(ui_rendering::renderer().into())
        .with_winit_window_attributes_hook(|attributes| {
            if attributes.title == "RHRemote-Moonlight · 串流控制" {
                attributes.with_active(false)
            } else {
                attributes
            }
        })
        .select()?;
    let ui = MainWindow::new()?;
    ui.set_app_version(env!("CARGO_PKG_VERSION").into());
    controller.ui_rendering(ui_rendering::metadata());
    native.statistics.lock().unwrap().mark("ui_backend_selected");
    let toolbar = StreamToolbar::new()?;
    let picker = StreamPicker::new()?;
    let shortcuts = rhremote_core::shortcuts();
    ui.set_shortcut_labels(ModelRc::new(VecModel::from(
        shortcuts
            .as_array()
            .unwrap()
            .iter()
            .map(|s| slint::SharedString::from(s["label"].as_str().unwrap()))
            .collect::<Vec<_>>(),
    )));
    ui.set_shortcut_ids(ModelRc::new(VecModel::from(
        shortcuts
            .as_array()
            .unwrap()
            .iter()
            .map(|s| slint::SharedString::from(s["id"].as_str().unwrap()))
            .collect::<Vec<_>>(),
    )));
    toolbar.set_shortcut_labels(ui.get_shortcut_labels());
    toolbar.set_shortcut_ids(ui.get_shortcut_ids());
    native.statistics.lock().unwrap().mark("ui_created");
    let actions = controller.clone();
    let (sender, receiver) = std::sync::mpsc::sync_channel::<(String, String, u128, std::time::Instant)>(64);
    std::thread::spawn(move || {
        for (id, arg, requested_at_us, queued) in receiver {
            let args = args_for(&id, &arg, &actions.state());
            let response = actions.dispatch_timed(&id, args, Some(rhremote_core::ActionTiming{requested_at_us,queue_wait_us:queued.elapsed().as_micros()}));
            if response["ok"] != true {
                actions.message(response["error"].as_str().unwrap_or("操作失败").into());
            }
        }
    });
    let toolbar_sender = sender.clone();
    let toolbar_queue = controller.clone();
    let toolbar_timing = native.clone();
    toolbar.global::<Commands>().on_trigger(move |id, arg| {
        if toolbar_sender
            .try_send((id.to_string(), arg.to_string(), toolbar_timing.statistics.lock().unwrap().now(), std::time::Instant::now()))
            .is_err()
        {
            toolbar_queue.message("操作队列已满".into());
        }
        if id.as_str() != "stream.disconnect" {
            overlay::focus_stream(&toolbar_queue.state());
        }
    });
    toolbar
        .window()
        .on_close_requested(|| slint::CloseRequestResponse::KeepWindowShown);
    let picker_sender = sender.clone();
    let picker_actions = controller.clone();
    let picker_timing = native.clone();
    picker.global::<Commands>().on_trigger(move |id, arg| {
        if picker_sender
            .try_send((id.to_string(), arg.to_string(), picker_timing.statistics.lock().unwrap().now(), std::time::Instant::now()))
            .is_err()
        {
            picker_actions.message("操作队列已满".into());
        }
    });
    let dismiss = controller.clone();
    picker.on_dismiss(move || {
        dismiss.dispatch("stream.menu.close", json!({}));
    });
    let dismiss = controller.clone();
    picker.window().on_close_requested(move || {
        dismiss.dispatch("stream.menu.close", json!({}));
        slint::CloseRequestResponse::KeepWindowShown
    });
    {
        use slint::winit_030::{EventResult, WinitWindowAccessor, winit::event::WindowEvent};
        let dismiss = controller.clone();
        picker.window().on_winit_window_event(move |_, event| {
            if matches!(event, WindowEvent::Focused(false)) {
                dismiss.dispatch("stream.menu.close", json!({}));
            }
            EventResult::Propagate
        });
    }
    let queue = controller.clone();
    let ui_timing = native.clone();
    ui.global::<Commands>().on_trigger(move |id, arg| {
        if sender.try_send((id.to_string(), arg.to_string(), ui_timing.statistics.lock().unwrap().now(), std::time::Instant::now())).is_err() {
            queue.message("操作队列已满，请稍后重试".into());
        }
    });
    let close = controller.clone();
    ui.window().on_close_requested(move || {
        let result = close.dispatch("window.close", json!({}));
        if result["ok"] != true {
            close.message(result["error"].as_str().unwrap_or("断开失败").into());
        }
        slint::CloseRequestResponse::KeepWindowShown
    });
    let timer = slint::Timer::default();
    let weak = ui.as_weak();
    let toolbar_weak = toolbar.as_weak();
    let picker_weak = picker.as_weak();
    let view = controller.clone();
    let preview = data.join("preview.png");
    let mut revision = u64::MAX;
    let profile_model = std::rc::Rc::new(VecModel::<SettingRow>::default());
    ui.set_profile_fields(ModelRc::from(profile_model.clone()));
    let mut profile_fingerprint = String::new();
    let mut toolbar_display_fingerprint = String::new();
    timer.start(slint::TimerMode::Repeated,Duration::from_millis(100),move||{
        let Some(ui)=weak.upgrade() else{return};let state=view.state();let next=state["revision"].as_u64().unwrap_or(0);
        ui.set_compact(ui.window().size().width as f32/ui.window().scale_factor()<760.0);
        if next!=revision {
            ui.set_test_mode(state["statistics"]["tracing"]["enabled"]==true);ui.set_trace_path(state["statistics"]["tracing"]["latest"].as_str().unwrap_or("").into());
            revision=next;ui.set_page(state["page"].as_str().unwrap_or("devices").into());ui.set_status(state["status"].as_str().unwrap_or("").into());ui.set_dark(state["saved"]["dark"].as_bool().unwrap_or(false));ui.global::<Palette>().set_dark(state["saved"]["dark"].as_bool().unwrap_or(false));
            ui.set_settings_tab(state["settingsTab"].as_str().unwrap_or("updates").into());ui.set_sync_url(state["sync"]["url"].as_str().unwrap_or("").into());ui.set_sync_username(state["sync"]["username"].as_str().unwrap_or("").into());ui.set_sync_automatic(state["sync"]["automatic"]==true);ui.set_sync_interval_index(match state["sync"]["intervalMinutes"].as_u64().unwrap_or(5){15=>1,30=>2,60=>3,_=>0});ui.set_sync_has_password(state["sync"]["hasPassword"]==true);ui.set_sync_busy(state["syncBusy"]==true);ui.set_sync_conflicts(state["sync"]["conflicts"].as_array().map(|a|a.iter().filter_map(|v|v.as_str()).collect::<Vec<_>>().join("\n")).unwrap_or_default().into());ui.set_sync_last(if state["sync"]["lastSyncMs"].as_u64().unwrap_or(0)>0{"已同步"}else{"尚未同步"}.into());
            ui.set_updates_managed(state["updates"]["managed"]==true);ui.set_updates_busy(state["updatesBusy"]==true);ui.set_updates_available(state["updates"]["availableVersion"].as_str().is_some());ui.set_updates_pending(state["updates"]["pendingRestart"]==true);ui.set_update_version(state["updates"]["availableVersion"].as_str().unwrap_or("").into());ui.set_update_status(state["updates"]["status"].as_str().unwrap_or("").into());
            let devices=state["devices"].as_array().cloned().unwrap_or_default();let selected=state["selected"].as_str().unwrap_or("");let rows:Vec<DeviceRow>=devices.iter().map(|d|{let p:Profile=serde_json::from_value(state["saved"]["profiles"][d["uuid"].as_str().unwrap_or("")].clone()).unwrap_or_default();DeviceRow{uuid:d["uuid"].as_str().unwrap_or("").into(),name:d["displayName"].as_str().filter(|n|!n.is_empty()).or_else(||d["name"].as_str()).unwrap_or("").into(),status:if d["isSelf"]==true{"本机"}else if d["online"]!=true{"离线"}else if d["paired"]==true{"● 已配对"}else{"待配对"}.into(),summary:format!("{} × {}  ·  {} 帧  ·  {} Mbps",p.width,p.height,p.fps,p.bitrate_mbps).into(),paired:d["paired"]==true,online:d["online"]==true,is_self:d["isSelf"]==true}}).collect();
            ui.set_selected_name(devices.iter().find(|d|d["uuid"]==selected).and_then(|d|d["displayName"].as_str().filter(|n|!n.is_empty()).or_else(||d["name"].as_str())).unwrap_or("").into());ui.set_draft_display_name(state["draftDisplayName"].as_str().unwrap_or("").into());devices::update_devices(&ui,rows);
            let host=state["saved"]["devices"].as_array().into_iter().flatten().find(|d|d["uuid"]==selected).or_else(||devices.iter().find(|d|d["uuid"]==selected));
            ui.set_selected_uuid(selected.into());
            ui.set_network_address(host.and_then(|d|d["address"].as_str()).unwrap_or("").into());
            ui.set_network_addresses(host.and_then(|d|d["addresses"].as_array()).map(|a|a.iter().filter_map(Value::as_str).collect::<Vec<_>>().join(", ")).unwrap_or_default().into());
            ui.set_network_report(if state["network"]["uuid"]==selected{network::report_text(&state["network"])}else{"尚未诊断".into()}.into());
            let client_id=state["saved"]["clientId"].as_str().unwrap_or("");
            ui.set_profile_tab(state["profileTab"].as_str().unwrap_or("video").into());
            let order:Vec<&str>=state["draft"]["routeOrder"].as_array().into_iter().flatten().filter_map(Value::as_str).collect();
            let mut route_rows=Vec::new();
            for key in &order {if let Some((_,label))=rhremote_core::network::ROUTES.iter().find(|(id,_)|id==key){route_rows.push(RouteRow{key:(*key).into(),label:(*label).into(),enabled:true});}}
            for (key,label) in rhremote_core::network::ROUTES {if !order.contains(key){route_rows.push(RouteRow{key:(*key).into(),label:(*label).into(),enabled:false});}}
            ui.set_network_routes(ModelRc::new(VecModel::from(route_rows)));
            ui.set_relay_url(state["draft"]["relayUrl"].as_str().unwrap_or(rhremote_core::network::DEFAULT_RELAY).into());
            ui.set_profile_sources_expanded(state["profileSourcesExpanded"]==true);
            ui.set_audio_enabled(state["audioEnabled"]==true);ui.set_audio_volume(state["volume"].as_i64().unwrap_or(100) as i32);
            let remote:Vec<RemoteDisplay>=state["statistics"]["displays"].as_array().into_iter().flatten().map(|d|RemoteDisplay{id:d["id"].as_str().unwrap_or("").into(),label:format!("{} · {}×{}",if d["virtual"]==true{"虚拟屏"}else{d["name"].as_str().filter(|v|!v.is_empty()).unwrap_or("屏幕")},d["width"],d["height"]).into(),chosen:d["id"]==state["statistics"]["selectedDisplay"]["id"],available:d["available"]==true}).collect();
            ui.set_remote_displays(ModelRc::new(VecModel::from(remote)));ui.set_display_error(state["statistics"]["displayError"].as_str().unwrap_or("").into());
            let fingerprint=format!("{}:{}",state["profileTab"],state["draft"]);
            if fingerprint!=profile_fingerprint {
                profile_fingerprint=fingerprint;
                let rows:Vec<SettingRow>=state["settingsCatalog"].as_array().into_iter().flatten().filter(|f|f["tab"]==state["profileTab"]&&f["platforms"].as_array().is_none_or(|p|p.iter().any(|v|v=="windows"))).map(|f|{
                    let key=f["key"].as_str().unwrap();let value=&state["draft"][key];let opts=f["options"].as_array().cloned().unwrap_or_default();
                    let labels:Vec<slint::SharedString>=opts.iter().map(|v|v[1].as_str().unwrap_or("").into()).collect();
                    let values:Vec<slint::SharedString>=opts.iter().map(|v|v[0].as_str().unwrap_or("").into()).collect();
                    SettingRow{key:key.into(),label:f["label"].as_str().unwrap_or("").into(),kind:f["type"].as_str().unwrap_or("").into(),help:f["help"].as_str().unwrap_or("").into(),flag:value==true,number:value.as_i64().unwrap_or(0) as i32,minimum:f["min"].as_i64().unwrap_or(0) as i32,maximum:f["max"].as_i64().unwrap_or(100) as i32,step:f["step"].as_i64().unwrap_or(1) as i32,selected_index:opts.iter().position(|o|o[0]==*value).unwrap_or(0) as i32,option_labels:ModelRc::new(VecModel::from(labels)),option_values:ModelRc::new(VecModel::from(values))}
                }).collect();
                if profile_model.row_count()==rows.len()&&(rows.is_empty()||profile_model.row_data(0).is_some_and(|r|r.key==rows[0].key)){for(i,row)in rows.into_iter().enumerate(){profile_model.set_row_data(i,row);}}else{profile_model.set_vec(rows);}
            }
            let client_name=state["saved"]["clients"][client_id]["name"].as_str().unwrap_or("Windows");
            ui.set_client_name(format!("{} · {}",client_name,client_id.chars().rev().take(4).collect::<String>().chars().rev().collect::<String>()).into());
            let sources:Vec<ProfileSource>=state["saved"]["clientProfiles"].as_object().into_iter().flatten().filter(|(id,profiles)|id.as_str()!=client_id&&profiles.get(selected).is_some()).map(|(id,profiles)|{
                let p=&profiles[selected];let name=state["saved"]["clients"][id]["name"].as_str().unwrap_or(id);
                let suffix=id.chars().rev().take(4).collect::<String>().chars().rev().collect::<String>();
                ProfileSource{client_id:id.into(),label:format!("{} · {} · {} × {} · {} 帧",name,suffix,p["width"],p["height"],p["fps"]).into()}
            }).collect();ui.set_profile_sources(ModelRc::new(VecModel::from(sources)));
            let p:Profile=serde_json::from_value(state["draft"].clone()).unwrap_or_else(|_|view.profile(selected));ui.set_profile_width(p.width as i32);ui.set_profile_height(p.height as i32);ui.set_fps(p.fps as i32);ui.set_bitrate(p.bitrate_mbps as i32);ui.set_codec(p.codec.into());ui.set_swapped(p.swap_buttons);ui.set_trackpad(p.trackpad);ui.set_statistics(if state["streaming"]==true{state["showStats"]==true}else{p.statistics});ui.set_pair_pin(state["pairPin"].as_str().unwrap_or("").into());ui.set_streaming(state["streaming"]==true);ui.set_connecting(state["connecting"]==true);ui.set_controls(state["controls"]==true);ui.set_stream_statistics(state["statistics"]["statistics"].as_str().unwrap_or("").into());
        }
        if let Some(toolbar)=toolbar_weak.upgrade(){
            toolbar.set_expanded(state["controls"]==true);
            toolbar.set_audio_enabled(state["audioEnabled"]==true);toolbar.set_audio_volume(state["volume"].as_i64().unwrap_or(100) as i32);
            toolbar.set_device_name(ui.get_selected_name());toolbar.set_status(state["status"].as_str().unwrap_or("").into());toolbar.set_stats_enabled(state["showStats"]==true);
            toolbar.global::<Palette>().set_dark(state["saved"]["dark"]==true);
            let display_fingerprint=format!("{}:{}",state["statistics"]["displays"],state["statistics"]["selectedDisplay"]["id"]);
            if display_fingerprint!=toolbar_display_fingerprint {
                toolbar_display_fingerprint=display_fingerprint;
                toolbar.set_displays(ui.get_remote_displays());
                toolbar.set_display_labels(ModelRc::new(VecModel::from(ui.get_remote_displays().iter().map(|d|d.label).collect::<Vec<_>>())));
                if let Some(index)=ui.get_remote_displays().iter().position(|d|d.chosen){toolbar.set_display_index(index as i32);}
            }
            let mut toolbar_state=overlay::follow(&toolbar,&state);
            if let Some(picker)=picker_weak.upgrade(){
                let kind=state["streamMenu"].as_str().unwrap_or("");
                if !kind.is_empty() && kind!=picker.get_kind().as_str(){
                    picker.set_kind(kind.into());
                    if kind=="shortcuts" {picker.set_labels(toolbar.get_shortcut_labels());picker.set_values(toolbar.get_shortcut_ids());}
                }
                if kind=="displays" {
                    picker.set_labels(toolbar.get_display_labels());
                    picker.set_values(ModelRc::new(VecModel::from(toolbar.get_displays().iter().map(|d|d.id).collect::<Vec<_>>())));
                }
                picker.global::<Palette>().set_dark(state["saved"]["dark"]==true);
                toolbar_state["picker"]=overlay::follow_picker(&picker,&state,&toolbar_state);
            }
            view.toolbar_state(toolbar_state);
        }
        match view.take_window_action().as_str(){
            "window.minimize"=>ui.window().set_minimized(true),"window.maximize"=>ui.window().set_maximized(!ui.window().is_maximized()),"window.close"=>{let _=slint::quit_event_loop();},
            action @ ("view.snapshot"|"view.toolbar.snapshot"|"view.picker.snapshot")=>{let toolbar=toolbar_weak.upgrade();let picker=picker_weak.upgrade();let snapshot=if action=="view.toolbar.snapshot" {toolbar.as_ref().ok_or(slint::PlatformError::Other("控制栏不可用".into())).and_then(|t|t.window().take_snapshot())}else if action=="view.picker.snapshot" {picker.as_ref().ok_or(slint::PlatformError::Other("选择列表不可用".into())).and_then(|t|t.window().take_snapshot())}else{ui.window().take_snapshot()};let preview=if action=="view.toolbar.snapshot" {preview.with_file_name("toolbar-preview.png")}else if action=="view.picker.snapshot" {preview.with_file_name("picker-preview.png")}else{preview.clone()};match snapshot{Ok(pixels)=>{let pending=preview.with_file_name("preview.pending.png");let result=image::save_buffer(&pending,pixels.as_bytes(),pixels.width(),pixels.height(),image::ColorType::Rgba8).map_err(|e|e.to_string()).and_then(|_|std::fs::rename(&pending,&preview).map_err(|e|e.to_string()));view.preview_complete(match result{Ok(_)=>json!({"ok":true,"path":preview,"completedAtMs":rhremote_core::now_ms()}),Err(e)=>json!({"ok":false,"error":e,"completedAtMs":rhremote_core::now_ms()})});},Err(e)=>view.message(format!("导出失败：{e}"))}},_=>{}
        }
    });
    let poll = controller.clone();
    let transport = native.clone();
    std::thread::spawn(move || {
        poll.dispatch("devices.refresh", json!({}));
        loop {
            if let Some((attempt, device, error)) = transport.pairing_result() {
                poll.pairing_complete(attempt, device, error);
            }
            let epoch = poll.stream_epoch();
            if let Ok(value) = transport.state() {
                poll.stream_state_for(epoch, value["streaming"] == true, value);
            } else {
                poll.stream_state_for(epoch, false, Value::Null);
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    });
    let automatic = controller.clone();
    std::thread::spawn(move || {
        let mut last_attempt = 0u64;
        loop {
            let state = automatic.state();
            let now = rhremote_core::now_ms();
            if state["sync"]["automatic"] == true
                && state["syncBusy"] != true
                && state["draft"].is_null()
                && state["sync"]["conflicts"]
                    .as_array()
                    .is_none_or(|a| a.is_empty())
                && now.saturating_sub(
                    last_attempt.max(state["sync"]["lastSyncMs"].as_u64().unwrap_or(0)),
                ) >= state["sync"]["intervalMinutes"].as_u64().unwrap_or(5) * 60_000
            {
                last_attempt = now;
                automatic.dispatch("sync.run", json!({}));
            }
            std::thread::sleep(Duration::from_secs(15));
        }
    });
    let auto_updates = controller.clone();
    std::thread::spawn(move || {
        let mut last_attempt = 0u64;
        loop {
            let state = auto_updates.state();
            let now = rhremote_core::now_ms();
            if state["updates"]["managed"] == true
                && state["updatesBusy"] != true
                && now.saturating_sub(last_attempt) > 21_600_000
            {
                last_attempt = now;
                let checked = auto_updates.dispatch("updates.check", json!({}));
                if checked["ok"] == true && checked["result"]["availableVersion"].is_string() {
                    auto_updates.dispatch("updates.download", json!({}));
                }
            }
            std::thread::sleep(Duration::from_secs(30));
        }
    });
    ui.show()?;
    native.statistics.lock().unwrap().mark("ui_shown");
    if let Ok(position) = std::env::var("RHREMOTE_UI_POSITION") {
        if let Some((x, y)) = position.split_once(',') {
            if let (Ok(x), Ok(y)) = (x.parse::<i32>(), y.parse::<i32>()) {
                ui.window().set_position(slint::PhysicalPosition::new(x, y));
            }
        }
    }
    ui.run()?;
    Ok(())
}
fn native_engine_path(override_path: Option<std::ffi::OsString>, executable: PathBuf) -> PathBuf {
    override_path
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| executable.parent().unwrap().join("engine/RHRemote.exe"))
}
#[cfg(test)]
mod tests {
    #[test]
    fn empty_native_engine_override_uses_packaged_engine() {
        let exe = std::path::PathBuf::from("C:/RHRemote/current/RHRemote.exe");
        let expected = exe.parent().unwrap().join("engine/RHRemote.exe");
        assert_eq!(super::native_engine_path(None, exe.clone()), expected);
        assert_eq!(
            super::native_engine_path(Some(std::ffi::OsString::new()), exe.clone()),
            expected
        );
        let custom = std::ffi::OsString::from("C:/custom/engine.exe");
        assert_eq!(
            super::native_engine_path(Some(custom.clone()), exe),
            std::path::PathBuf::from(custom)
        );
    }
    #[test]
    fn android_product_action_ids_exist_in_shared_catalog() {
        let catalog = rhremote_core::catalog();
        let ids: std::collections::HashSet<_> = catalog
            .as_array()
            .unwrap()
            .iter()
            .map(|a| a["id"].as_str().unwrap())
            .collect();
        for source in [
            include_str!(
                "../../../android/overlay/app/src/main/java/com/limelight/rhremote/RhHome.java"
            ),
            include_str!(
                "../../../android/overlay/app/src/main/java/com/limelight/rhremote/RhStreamControls.java"
            ),
            include_str!(
                "../../../android/overlay/app/src/main/java/com/limelight/rhremote/RhStreamPanel.java"
            ),
        ] {
            for value in source.split('"').skip(1).step_by(2) {
                if [
                    "devices.",
                    "device.",
                    "settings.",
                    "app.settings.",
                    "appearance.",
                    "sync.",
                    "updates.",
                    "pair.",
                    "stream.",
                    "input.",
                ]
                .iter()
                .any(|prefix| value.starts_with(prefix))
                    && !value.ends_with('.')
                    && !value.contains([' ', '\\'])
                {
                    assert!(
                        ids.contains(value),
                        "Android action missing from API: {value}"
                    );
                }
            }
        }
    }
    #[test]
    fn every_button_action_is_in_catalog() {
        let ui = include_str!("../ui/app.slint");
        let ids: std::collections::HashSet<_> = rhremote_core::catalog()
            .as_array()
            .unwrap()
            .iter()
            .map(|a| a["id"].as_str().unwrap().to_owned())
            .collect();
        for line in ui.split("action-id:").skip(1) {
            let value = line.trim_start();
            if let Some(value) = value.strip_prefix('"') {
                let id = value.split('"').next().unwrap();
                assert!(ids.contains(id), "missing API action {id}");
            }
        }
        assert!(ui.contains("device.connect") && ui.contains("device.pair"));
    }
}
