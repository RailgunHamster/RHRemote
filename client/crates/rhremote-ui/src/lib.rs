use rhremote_core::Controller;
use serde_json::{Value, json};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::{path::PathBuf, sync::Arc, time::Duration};
mod config_sync;
mod devices;
mod device_bootstrap;
mod probe_report;
mod form_models;
mod view_state;
mod ui_test;
mod ui_keyboard;
mod ui_driver;
#[cfg(test)]
mod ui_driver_tests;
#[cfg(test)]
mod decision_tests;
#[cfg(test)]
mod workspace_flow_tests;
mod preview_capture;
mod preview_placement;
mod network;
mod native_cleanup;
mod native_control;
#[cfg(target_os="windows")]
mod native_window;
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
mod window_events;
mod updates;
mod update_cancel;
mod ui_rendering;
mod floating_keyboard;
mod device_lookup;
mod action_queue;
mod device_probe;
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

fn report_queued_error(controller:&Controller,id:&str,response:&Value){
    // Refresh errors live beside their retry action; do not leave a second,
    // unrelated global message after the refresh succeeds.
    if id!="devices.refresh" && !id.starts_with("device.network.") && response["ok"]!=true && response["ignored"]!=true {
        controller.message(response["error"].as_str().unwrap_or("操作失败").into());
    }
}

fn args_for(id: &str, arg: &str, state: &Value) -> Value {
    match id {
        "devices.refresh" if !arg.is_empty()=>json!({"uuid":arg}),
        "device.move" => {
            let (uuid, direction) = arg.split_once('|').unwrap_or(("", ""));
            json!({"uuid":uuid,"direction":direction})
        }
        "device.network.configure" => {
            let mut fields=arg.splitn(3,'|');
            let uuid=fields.next().unwrap_or("");let primary=fields.next().unwrap_or("");let extra=fields.next().unwrap_or("");
            json!({"uuid":uuid,"address":primary.trim(),"addresses":extra.split([',',';','\n']).map(str::trim).filter(|s|!s.is_empty()).collect::<Vec<_>>()})
        }
        "device.network.diagnose" if !arg.starts_with('{')=>json!({"uuid":arg}),
        "device.network.diagnose"|"device.network.cancel"|"device.network.details.toggle"=>serde_json::from_str(arg).unwrap_or_else(|_|json!({})),
        "updates.cancel"=>json!({"operationId":arg.parse::<u64>().unwrap_or(0)}),
        "updates.primary" if !arg.is_empty()=>json!({"expectedIntent":arg}),
        "navigation.resolve"|"sync.clear.resolve"=>json!({"choice":arg}),
        "settings.input"=>{let(key,value)=arg.split_once('=').unwrap_or(("",""));json!({"key":key,"value":value})},
        "settings.step"=>{let(key,direction)=arg.split_once('|').unwrap_or(("",""));json!({"key":key,"direction":direction})},
        "settings.network.edit"=>{let(key,value)=arg.split_once('=').unwrap_or(("",""));json!({"key":key,"value":value})},
        "statistics.configure" => json!({"enabled":arg=="true"}),
        "stream.keyboard.floating.set" => json!({"enabled":arg=="true"}),
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
        "sync.resolve" => if let Some((resolution,review))=arg.split_once('|'){json!({"resolution":resolution,"reviewId":review})}else{json!({"resolution":arg})},
        "settings.copy_from" | "settings.copy.preview" => json!({"clientId":arg}),
        "settings.save" => json!({}),
        "device.window.connect"=>{let(uuid,source)=arg.split_once('|').unwrap_or(("",""));json!({"uuid":uuid,"source":source})},
        "device.windows.open" | "device.connect" | "device.pair" | "device.settings.open" | "device.info.open" | "device.select" => json!({"uuid":arg}),
        "devices.filter" => json!({"query":arg}),
        "devices.filter.clear" => json!({"expectedQuery":arg}),
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
pub(crate) fn request_owner(ui:&MainWindow,id:&str)->Option<String>{
    if rhremote_core::is_profile_form_action(id){return Some(ui.get_profile_draft_id().to_string());}
    match id{"navigation.resolve"=>Some(ui.get_navigation_request_id().to_string()),"sync.clear.resolve"=>Some(ui.get_sync_clear_request_id().to_string()),_=>None}
}
pub(crate) fn queued_args(id:&str,arg:&str,state:&Value,owner:Option<String>)->Value{
    let mut args=args_for(id,arg,state);if let Some(request)=owner{args[if rhremote_core::is_profile_form_action(id){"draftId"}else{"requestId"}]=json!(request);}args
}
pub(crate) fn request_close(ui: &MainWindow) -> slint::CloseRequestResponse {
    // Closing can wait for native cleanup or present an unsaved-draft decision.
    // Keep the window alive while the existing action worker handles that work.
    ui.global::<Commands>().invoke_trigger("window.close".into(), "".into());
    slint::CloseRequestResponse::KeepWindowShown
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
    device_bootstrap::restore(&controller.state()["saved"],&*native)?;
    controller.replace_devices_pending(native.devices.lock().unwrap().clone());
    native.statistics.lock().unwrap().mark("devices_loaded_local");
    if let Ok(port) = std::env::var("RHREMOTE_CONTROL_PORT") {
        rhremote_core::start_http(controller.clone(), port.parse()?, token)?;
    }
    let passive_preview=native.ui_test.enabled;
    let monitor=std::env::var("RHREMOTE_TEST_MONITOR").ok();
    let coordinates=std::env::var("RHREMOTE_TEST_WINDOW_ORIGIN").ok();
    let preview_origin=preview_placement::origin(passive_preview,monitor.as_deref(),coordinates.as_deref())?;
    // At this hook the component title has not reached the native attributes.
    // Allocate the main adapter first, then make subsequent tool windows passive.
    let creating_overlay=std::rc::Rc::new(std::cell::Cell::new(false));
    let overlay_attributes=creating_overlay.clone();
    let window_creation=Arc::new(std::sync::Mutex::new(Vec::<Value>::new()));
    let creation_attributes=window_creation.clone();
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name(ui_rendering::renderer().into())
        .with_winit_window_attributes_hook(move |attributes| {
            let overlay=overlay_attributes.get();
            let active=!passive_preview&&!overlay;
            let mut records=creation_attributes.lock().unwrap();
            if records.len()<8{records.push(json!({"role":if overlay{"overlay"}else{"main"},"active":active,"normalActive":!overlay,"preview":passive_preview}));}
            attributes.with_active(active)
        })
        .select()?;
    let ui = MainWindow::new()?;
    let _=ui.window();
    creating_overlay.set(true);
    if let Some((x,y))=preview_origin {ui.window().set_position(slint::PhysicalPosition::new(x,y));}
    ui.set_isolated_preview(std::env::var("RHREMOTE_TEST_MODE").as_deref()==Ok("1") && std::env::var_os("RHREMOTE_DATA_DIR").is_some_and(|v|!v.is_empty()));
    ui.set_app_version(env!("CARGO_PKG_VERSION").into());
    controller.ui_rendering(ui_rendering::metadata());
    native.statistics.lock().unwrap().mark("ui_backend_selected");
    let toolbar = StreamToolbar::new()?;
    let keyboard = FloatingKeyboardWindow::new()?;
    let picker = StreamPicker::new()?;
    if passive_preview {
        use slint::winit_030::{WinitWindowAccessor,EventResult,winit::event::WindowEvent};
        for(role,window)in[(window_events::Role::Toolbar,toolbar.window()),(window_events::Role::Keyboard,keyboard.window())]{
            let mut recorder=window_events::Recorder::new(true,role,native.statistics.clone());
            window.on_winit_window_event(window_events::compose(move|event:&WindowEvent|recorder.observe(event),|_|EventResult::Propagate));
        }
    }
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
    let (sender, receiver) = std::sync::mpsc::sync_channel::<action_queue::Request>(64);
    std::thread::spawn(move || action_queue::run(actions, receiver));
    let toolbar_sender = sender.clone();
    let toolbar_queue = controller.clone();
    let toolbar_timing = native.clone();
    toolbar.global::<Commands>().on_trigger(move |id, arg| {
        toolbar_timing.ui_test.observe_action(id.as_str());
        if toolbar_sender
            .try_send((id.to_string(), arg.to_string(), toolbar_timing.statistics.lock().unwrap().now(), std::time::Instant::now(),None))
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
    let keyboard_sender=sender.clone();let keyboard_actions=controller.clone();let keyboard_timing=native.clone();
    keyboard.global::<Commands>().on_trigger(move|id,arg|{
        keyboard_timing.ui_test.observe_action(id.as_str());
        if keyboard_sender.try_send((id.to_string(),arg.to_string(),keyboard_timing.statistics.lock().unwrap().now(),std::time::Instant::now(),None)).is_err(){keyboard_actions.message("操作队列已满".into());}
        if id=="stream.shortcut"{overlay::focus_stream(&keyboard_actions.state());}
    });
    let keyboard_close=controller.clone();keyboard.window().on_close_requested(move||{keyboard_close.dispatch("stream.keyboard.floating.set",json!({"enabled":false}));slint::CloseRequestResponse::KeepWindowShown});
    let picker_sender = sender.clone();
    let picker_actions = controller.clone();
    let picker_timing = native.clone();
    picker.global::<Commands>().on_trigger(move |id, arg| {
        picker_timing.ui_test.observe_action(id.as_str());
        if picker_sender
            .try_send((id.to_string(), arg.to_string(), picker_timing.statistics.lock().unwrap().now(), std::time::Instant::now(),None))
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
        let mut recorder=window_events::Recorder::new(passive_preview,window_events::Role::Picker,native.statistics.clone());
        picker.window().on_winit_window_event(window_events::compose(move|event|recorder.observe(event),move |event| {
            if matches!(event, WindowEvent::Focused(false)) {
                dismiss.dispatch("stream.menu.close", json!({}));
            }
            EventResult::Propagate
        }));
    }
    let queue = controller.clone();
    let ui_timing = native.clone();
    let decision_view=ui.as_weak();
    ui.global::<Commands>().on_trigger(move |id, arg| {
        ui_timing.ui_test.observe_action(id.as_str());
        let decision=decision_view.upgrade().and_then(|ui|request_owner(&ui,id.as_str()));
        if let Some(result)=view_state::dispatch_local_input(&queue,id.as_str(),arg.as_str(),decision.clone()) {
            if result["ok"]!=true && result["ignored"]!=true {queue.message(result["error"].as_str().unwrap_or("修改失败").into());}
            return;
        }
        if sender.try_send((id.to_string(), arg.to_string(), ui_timing.statistics.lock().unwrap().now(), std::time::Instant::now(),decision)).is_err() {
            queue.message("操作队列已满，请稍后重试".into());
        }
    });
    {
        use slint::winit_030::{EventResult,WinitWindowAccessor,winit::event::{WindowEvent,ElementState}};
        let weak=ui.as_weak();let shift=std::cell::Cell::new(false);
        let mut recorder=window_events::Recorder::new(passive_preview,window_events::Role::Main,native.statistics.clone());
        ui.window().on_winit_window_event(window_events::compose(move|event|recorder.observe(event),move |event| {
            if let WindowEvent::ModifiersChanged(value)=event{shift.set(value.state().shift_key());}
            if let Some(ui)=weak.upgrade(){
                if let WindowEvent::KeyboardInput{event,..}=event {
                    let key=ui_keyboard::native_key_name(&event.logical_key);
                    if ui_keyboard::modal(&ui,key,event.state==ElementState::Pressed,event.repeat,shift.get()).is_some(){return EventResult::PreventDefault;}
                }
            }
            EventResult::Propagate
        }));
    }
    let close_view = ui.as_weak();
    ui.window().on_close_requested(move || {
        close_view.upgrade().map(|ui|request_close(&ui))
            .unwrap_or(slint::CloseRequestResponse::KeepWindowShown)
    });
    let timer = slint::Timer::default();
    let weak = ui.as_weak();
    let toolbar_weak = toolbar.as_weak();
    let picker_weak = picker.as_weak();
    let keyboard_weak = keyboard.as_weak();
    let view = controller.clone();
    let preview = data.join("preview.png");
    let mut main_view=view_state::MainView::new(&ui);
    main_view.refresh(&ui,&controller.state(),|uuid|controller.profile(uuid));
    native.statistics.lock().unwrap().mark("ui_initial_view_bound");
    let mut toolbar_display_fingerprint = String::new();
    let mut picker_fingerprint = String::new();
    let mut toolbar_follower=overlay::Follower::default();
    let mut published_window_creation=0;
    let mut ui_driver=ui_driver::Driver::new(native.ui_test.clone(),&ui,&toolbar,&picker);
    ui_driver.attach_keyboard(&keyboard);
    let mut keyboard_state=Value::Null;
    let mut keyboard_feedback=floating_keyboard::Feedback::default();
    let keyboard_timing=native.clone();
    timer.start(slint::TimerMode::Repeated,Duration::from_millis(100),move||{
        let Some(ui)=weak.upgrade() else{return};
        let creation=window_creation.lock().unwrap();
        if creation.len()!=published_window_creation {
            published_window_creation=creation.len();
            let mut metadata=ui_rendering::metadata();metadata["windowCreation"]=json!(*creation);
            view.ui_rendering(metadata);
        }
        drop(creation);
        let state=view.state();
        main_view.refresh(&ui,&state,|id|view.profile(id));
        if let Some(toolbar)=toolbar_weak.upgrade(){
            toolbar.set_expanded(state["controls"]==true);
            toolbar.set_floating_keyboard(state["floatingKeyboard"]==true);
            toolbar.set_floating_keyboard_error(state["floatingKeyboardError"].as_str().unwrap_or("").into());
            let pending=state["connecting"]==true;
            let canceling=matches!(state["connection"]["phase"].as_str(),Some("cancelling"|"cleaning"));
            toolbar.set_pending(pending);toolbar.set_canceling(canceling);
            toolbar.set_connection_failed(state["page"]=="connection" && state["connection"]["operation"]=="connect" && state["connection"]["phase"]=="failed");
            toolbar.set_connection_error(state["connection"]["error"].as_str().unwrap_or("").into());
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
            let mut toolbar_state=toolbar_follower.follow(&toolbar,&state);
            if let Some(keyboard)=keyboard_weak.upgrade(){
                keyboard_state=floating_keyboard::follow(&keyboard,&state,&keyboard_state);
                let report=keyboard_feedback.report(&state,&keyboard_state,std::time::Instant::now());
                if let Some(event)=keyboard_feedback.transition(&state,&report){let _=keyboard_timing.statistics.lock().unwrap().event("floating_keyboard_state",event);}
                toolbar_state["floatingKeyboard"]=report;
            }
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
            let toolbar=toolbar_weak.upgrade();let picker=picker_weak.upgrade();let keyboard=keyboard_weak.upgrade();
            let result=match request.window.as_str(){
                "main"=>preview_capture::capture(ui.window(),&preview,json!({"page":ui.get_page().as_str(),"settingsTab":ui.get_settings_tab().as_str(),"profileTab":ui.get_profile_tab().as_str()})),
                "toolbar"=>toolbar.as_ref().ok_or_else(||"控制栏不可用".to_string()).and_then(|t|preview_capture::capture(t.window(),&preview.with_file_name("toolbar-preview.png"),json!({"controlsTab":t.get_controls_tab().as_str()}))),
                "picker"=>picker.as_ref().ok_or_else(||"选择列表不可用".to_string()).and_then(|p|preview_capture::capture(p.window(),&preview.with_file_name("picker-preview.png"),json!({"menu":p.get_kind().as_str()}))),
                "keyboard"=>keyboard.as_ref().filter(|k|k.window().is_visible()).ok_or_else(||"悬浮键盘未显示".to_string()).and_then(|k|preview_capture::capture(k.window(),&preview.with_file_name("keyboard-preview.png"),json!({"alpha":166}))),
                _=>Err("未知截图窗口".into()),
            };
            view.preview_complete(&request,result);
        }
    });
    ui.show()?;
    native.statistics.lock().unwrap().mark("ui_shown");
    let poll = controller.clone();
    let transport = native.clone();
    std::thread::spawn(move || {
        transport.statistics.lock().unwrap().mark("devices_check_begin");
        poll.dispatch("devices.refresh", json!({}));
        transport.statistics.lock().unwrap().mark("devices_check_end");
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
                if checked["ok"] == true && checked["result"]["phase"]!="cancelled" && checked["result"]["availableVersion"].is_string() {
                    auto_updates.dispatch("updates.download", json!({}));
                }
            }
            std::thread::sleep(Duration::from_secs(30));
        }
    });
    if let Ok(position) = std::env::var("RHREMOTE_UI_POSITION") {
        if let Some((x, y)) = position.split_once(',') {
            if let (Ok(x), Ok(y)) = (x.parse::<i32>(), y.parse::<i32>()) {
                ui.window().set_position(slint::PhysicalPosition::new(x, y));
            }
        }
    }
    ui.run()?;
    native.statistics.lock().unwrap().mark("ui_event_loop_returned");
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
