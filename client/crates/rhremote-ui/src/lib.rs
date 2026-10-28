use rhremote_core::Controller;
use serde_json::{Value, json};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::{path::PathBuf, sync::Arc, time::Duration};
mod config_sync;
mod devices;
mod form_models;
mod view_state;
mod ui_test;
mod ui_keyboard;
mod ui_driver;
mod preview_capture;
mod preview_placement;
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
mod device_lookup;
#[cfg(test)]
mod form_tests;
#[cfg(test)]
mod sync_ui_tests;
#[cfg(test)]
mod appearance_ui_tests;
#[cfg(test)]
mod view_flow_tests;
#[cfg(test)]
mod sync_commit_ui_tests;
#[cfg(test)]
mod focus_clip_tests;
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
        "navigation.resolve"|"sync.clear.resolve"=>json!({"choice":arg}),
        "settings.input"=>{let(key,value)=arg.split_once('=').unwrap_or(("",""));json!({"key":key,"value":value})},
        "settings.step"=>{let(key,direction)=arg.split_once('|').unwrap_or(("",""));json!({"key":key,"direction":direction})},
        "settings.network.edit"=>{let(key,value)=arg.split_once('=').unwrap_or(("",""));json!({"key":key,"value":value})},
        "statistics.configure" => json!({"enabled":arg=="true"}),
        "app.settings.tab" | "settings.tab" | "stream.controls.tab" => json!({"tab":arg}),
        "stream.audio.volume" => json!({"volume":arg.parse::<u32>().unwrap_or(101)}),
        "stream.shortcut" => json!({"shortcut":arg}),
        "stream.menu.open" => json!({"menu":arg}),
        "stream.window.move" => json!({"direction":arg}),
        "stream.display.select" => json!({"displayId":arg}),
        "sync.edit"=>{let(key,value)=arg.split_once('=').unwrap_or(("",""));json!({"key":key,"value":match key{"automatic"=>json!(value=="true"),"intervalMinutes"=>json!(value.parse::<u32>().unwrap_or(0)),_=>json!(value)}})},
        "sync.configure" => {
            let fields: Vec<_> = arg.splitn(5, '\n').collect();
            if fields.len() == 5 {
                json!({"url":fields[0],"username":fields[1],"password":fields[2],"automatic":fields[3]=="true","intervalMinutes":fields[4].parse::<u32>().unwrap_or(0)})
            } else {
                json!({})
            }
        }
        "sync.resolve" => json!({"resolution":arg}),
        "settings.copy_from" | "settings.copy.preview" => json!({"clientId":arg}),
        "settings.save" => json!({}),
        "device.connect" | "device.pair" | "device.settings.open" | "device.info.open" | "device.select" => json!({"uuid":arg}),
        "devices.filter" => json!({"query":arg}),
        "device.info.input"=>json!({"displayName":arg}),
        "device.add.submit" | "device.add.input" => json!({"address":arg}),
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
    let passive_preview=native.ui_test.enabled;
    let monitor=std::env::var("RHREMOTE_TEST_MONITOR").ok();
    let coordinates=std::env::var("RHREMOTE_TEST_WINDOW_ORIGIN").ok();
    let preview_origin=preview_placement::origin(passive_preview,monitor.as_deref(),coordinates.as_deref())?;
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name(ui_rendering::renderer().into())
        .with_winit_window_attributes_hook(move |attributes| {
            if attributes.title == "RHRemote-Moonlight · 串流控制" || passive_preview {
                attributes.with_active(false)
            } else {
                attributes
            }
        })
        .select()?;
    let ui = MainWindow::new()?;
    if let Some((x,y))=preview_origin {ui.window().set_position(slint::PhysicalPosition::new(x,y));}
    ui.set_isolated_preview(std::env::var("RHREMOTE_TEST_MODE").as_deref()==Ok("1") && std::env::var_os("RHREMOTE_DATA_DIR").is_some_and(|v|!v.is_empty()));
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
            // Network updates must not occupy the navigation/action queue.
            if id.starts_with("updates.") || rhremote_core::is_session_control_action(&id) || matches!(id.as_str(),"sync.run"|"sync.resolve") {
                let actions=actions.clone();
                std::thread::spawn(move||{let response=actions.dispatch(&id,args);if response["ok"]!=true && !id.starts_with("updates.") && !rhremote_core::is_session_control_action(&id){actions.message(response["error"].as_str().unwrap_or("操作失败").into());}});
                continue;
            }
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
        toolbar_timing.ui_test.observe_action(id.as_str());
        if toolbar_sender
            .try_send((id.to_string(), arg.to_string(), toolbar_timing.statistics.lock().unwrap().now(), std::time::Instant::now()))
            .is_err()
        {
            toolbar_queue.message("操作队列已满".into());
        }
        if matches!(id.as_str(),"stream.shortcut"|"stream.window.move"|"stream.display.select") {
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
        picker_timing.ui_test.observe_action(id.as_str());
        if picker_sender
            .try_send((id.to_string(), arg.to_string(), picker_timing.statistics.lock().unwrap().now(), std::time::Instant::now()))
            .is_err()
        {
            picker_actions.message("操作队列已满".into());
        }
    });
    let dismiss = controller.clone();
    let dismiss_timing = native.clone();
    picker.on_dismiss(move || {
        dismiss_timing.ui_test.observe_action("stream.menu.close");
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
        ui_timing.ui_test.observe_action(id.as_str());
        if sender.try_send((id.to_string(), arg.to_string(), ui_timing.statistics.lock().unwrap().now(), std::time::Instant::now())).is_err() {
            queue.message("操作队列已满，请稍后重试".into());
        }
    });
    {
        use slint::winit_030::{EventResult,WinitWindowAccessor,winit::{event::{WindowEvent,ElementState},keyboard::{Key,NamedKey}}};
        let controls=controller.clone();let weak=ui.as_weak();let shift=std::cell::Cell::new(false);
        ui.window().on_winit_window_event(move |_,event| {
            if let WindowEvent::ModifiersChanged(value)=event{shift.set(value.state().shift_key());}
            if let Some(ui)=weak.upgrade(){
                if let WindowEvent::KeyboardInput{event,..}=event {
                    let key=match event.logical_key{Key::Named(NamedKey::Escape)=>"Escape",Key::Named(NamedKey::Tab)=>"Tab",Key::Named(NamedKey::Enter)=>"Enter",_=>"Other"};
                    if ui_keyboard::modal(&ui,&controls,key,event.state==ElementState::Pressed,event.repeat,shift.get()).is_some(){return EventResult::PreventDefault;}
                }
            }
            EventResult::Propagate
        });
    }
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
    let mut main_view=view_state::MainView::new(&ui);
    let mut toolbar_display_fingerprint = String::new();
    let mut picker_fingerprint = String::new();
    let mut ui_driver=ui_driver::Driver::new(native.ui_test.clone(),&ui,&toolbar,&picker);
    timer.start(slint::TimerMode::Repeated,Duration::from_millis(100),move||{
        let Some(ui)=weak.upgrade() else{return};
        let state=view.state();
        main_view.refresh(&ui,&state,|id|view.profile(id));
        if let Some(toolbar)=toolbar_weak.upgrade(){
            toolbar.set_expanded(state["controls"]==true);
            let pending=state["connecting"]==true;
            let canceling=matches!(state["connection"]["phase"].as_str(),Some("cancelling"|"cleaning"));
            toolbar.set_pending(pending);toolbar.set_canceling(canceling);
            toolbar.set_pending_cancel_label(if canceling {"正在断开…"}else if state["connection"]["phase"]=="reconnecting" {"取消恢复"}else{"取消连接"}.into());
            toolbar.set_audio_enabled(state["audioEnabled"]==true);toolbar.set_audio_volume(state["volume"].as_i64().unwrap_or(100) as i32);
            toolbar.set_device_name(ui.get_selected_name());toolbar.set_stats_enabled(state["showStats"]==true);
            let controls_tab=state["controlsTab"].as_str().unwrap_or("input");let feedback=&state["controlAction"];let feedback_visible=feedback["group"]==controls_tab;
            toolbar.set_controls_tab(controls_tab.into());toolbar.set_busy(feedback["busy"]==true || state["streaming"]!=true || canceling);toolbar.set_failed(feedback_visible && feedback["error"].as_str().is_some_and(|s|!s.is_empty()));
            toolbar.set_status(if state["connecting"]==true {state["status"].as_str().unwrap_or("")}else if feedback_visible {feedback["message"].as_str().unwrap_or("")}else{""}.into());
            toolbar.global::<Palette>().set_dark(state["saved"]["dark"]==true);
            let display_fingerprint=format!("{}:{}",state["statistics"]["displays"],state["statistics"]["selectedDisplay"]["id"]);
            if display_fingerprint!=toolbar_display_fingerprint {
                toolbar_display_fingerprint=display_fingerprint;
                toolbar.set_displays(ui.get_remote_displays());
                toolbar.set_display_labels(ModelRc::new(VecModel::from(ui.get_remote_displays().iter().map(|d|d.label).collect::<Vec<_>>())));
                toolbar.set_display_index(ui.get_remote_displays().iter().position(|d|d.chosen).map(|i|i as i32).unwrap_or(-1));
            }
            let mut toolbar_state=overlay::follow(&toolbar,&state);
            if let Some(picker)=picker_weak.upgrade(){
                let kind=state["streamMenu"].as_str().unwrap_or("");
                let fingerprint=if kind=="displays"{format!("{kind}:{toolbar_display_fingerprint}")}else{kind.into()};
                if fingerprint!=picker_fingerprint {
                    picker_fingerprint=fingerprint;picker.set_kind(kind.into());
                    if kind=="shortcuts" {picker.set_labels(toolbar.get_shortcut_labels());picker.set_values(toolbar.get_shortcut_ids());picker.set_enabled_items(ModelRc::new(VecModel::from(vec![true;toolbar.get_shortcut_ids().row_count()])));picker.set_chosen_items(Default::default());}
                    else if kind=="displays" {
                        picker.set_labels(toolbar.get_display_labels());picker.set_values(ModelRc::new(VecModel::from(toolbar.get_displays().iter().map(|d|d.id).collect::<Vec<_>>())));
                        picker.set_enabled_items(ModelRc::new(VecModel::from(toolbar.get_displays().iter().map(|d|d.available).collect::<Vec<_>>())));picker.set_chosen_items(ModelRc::new(VecModel::from(toolbar.get_displays().iter().map(|d|d.chosen).collect::<Vec<_>>())));
                    }
                }
                let picker_error=if kind=="feedback" {feedback["error"].as_str().unwrap_or("")}else if (kind=="displays" && feedback["group"]=="displays" || kind=="shortcuts" && feedback["group"]=="input") && feedback["error"].as_str().is_some_and(|e|!e.is_empty()) {feedback["message"].as_str().unwrap_or("操作失败")}else{""};
                picker.set_busy(feedback["busy"]==true);picker.set_error(picker_error.into());
                picker.global::<Palette>().set_dark(state["saved"]["dark"]==true);
                toolbar_state["picker"]=overlay::follow_picker(&picker,&state,&toolbar_state);
            }
            view.toolbar_state(toolbar_state);
            if let Some(picker)=picker_weak.upgrade(){ui_driver.tick(&ui,&toolbar,&picker,&view);}
        }
        match view.take_window_action().as_str(){
            "window.minimize"=>ui.window().set_minimized(true),"window.maximize"=>ui.window().set_maximized(!ui.window().is_maximized()),"window.close"=>{let _=slint::quit_event_loop();},
            _=>{}
        }
        if let Some(request)=view.take_preview_request(){
            let toolbar=toolbar_weak.upgrade();let picker=picker_weak.upgrade();
            let result=match request.window.as_str(){
                "main"=>preview_capture::capture(ui.window(),&preview,json!({"page":ui.get_page().as_str(),"settingsTab":ui.get_settings_tab().as_str(),"profileTab":ui.get_profile_tab().as_str()})),
                "toolbar"=>toolbar.as_ref().ok_or_else(||"控制栏不可用".to_string()).and_then(|t|preview_capture::capture(t.window(),&preview.with_file_name("toolbar-preview.png"),json!({"controlsTab":t.get_controls_tab().as_str()}))),
                "picker"=>picker.as_ref().ok_or_else(||"选择列表不可用".to_string()).and_then(|p|preview_capture::capture(p.window(),&preview.with_file_name("picker-preview.png"),json!({"menu":p.get_kind().as_str()}))),
                _=>Err("未知截图窗口".into()),
            };
            view.preview_complete(&request,result);
        }
    });
    let poll = controller.clone();
    let transport = native.clone();
    std::thread::spawn(move || {
        poll.dispatch("devices.refresh", json!({}));
        loop {
            if let Some((attempt, device, error, cleanup_pending)) = transport.pairing_result() {
                poll.pairing_complete(attempt, device, error, cleanup_pending);
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
            let now = rhremote_core::now_ms();
            if automatic.automatic_sync_due(now,last_attempt) {
                let result=automatic.dispatch("sync.run",json!({"automatic":true}));
                if result["result"]["skipped"]!=true{last_attempt=now;}
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
    fn android_product_action_ids_exist_in_the_shared_or_android_catalog() {
        let catalog = rhremote_core::catalog();
        let mut ids: std::collections::HashSet<_> = catalog
            .as_array()
            .unwrap()
            .iter()
            .map(|a| a["id"].as_str().unwrap())
            .collect();
        // Android appends touch-only actions to its loaded shared catalogue.
        // Read the actual registration instead of maintaining an exception list.
        let android_api = include_str!(
            "../../../android/overlay/app/src/main/java/com/limelight/rhremote/RhActions.java"
        );
        for registration in android_api.split("catalog.put(obj(\"id\",\"").skip(1) {
            ids.insert(registration.split('"').next().unwrap());
        }
        for source in [
            include_str!(
                "../../../android/overlay/app/src/main/java/com/limelight/rhremote/RhHome.java"
            ),
            include_str!(
                "../../../android/overlay/app/src/main/java/com/limelight/rhremote/RhDeviceBrowser.java"
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
                    "navigation.",
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
