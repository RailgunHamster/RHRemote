//! Exercise the queued test API against real software-rendered Slint buttons.
use crate::{Commands, MainWindow, StreamPicker, StreamToolbar, ui_driver::Driver, ui_test::Bridge};
use i_slint_backend_testing::{TestingBackend, TestingBackendOptions};
use rhremote_core::{Controller, Platform};
use serde_json::{Value, json};
use slint::{ComponentHandle, LogicalSize, Model};
use std::{cell::RefCell, rc::Rc, sync::{Arc, Mutex}, time::Duration};

#[derive(Default)]
struct Boundary { calls: Mutex<Vec<String>>, requests:Mutex<Vec<Value>>, control_error:Mutex<Option<String>> }
impl Platform for Boundary {
    fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        self.calls.lock().unwrap().push(id.into()); self.requests.lock().unwrap().push(json!({"id":id,"args":args}));
        if id == "stream.display.select" { if let Some(error) = self.control_error.lock().unwrap().clone() { return Err(error); } }
        Ok(json!({}))
    }
}
struct Fixture {
    ui: MainWindow, toolbar: StreamToolbar, picker: StreamPicker,
    controller: Arc<Controller>, boundary: Arc<Boundary>, bridge: Arc<Bridge>, driver: Driver,
    calls: Rc<RefCell<Vec<(String, String)>>>, storage: std::path::PathBuf,
}
impl Fixture {
    fn new() -> Self {
        slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
            mock_time: true, threading: false, renderer_name: Some("software".into()),
        }))).unwrap();
        Self::in_current_backend()
    }
    fn in_current_backend()->Self {
        let storage = std::env::temp_dir().join(format!("rhremote-button-api-{}.json", rhremote_core::random_token().unwrap()));
        let boundary = Arc::new(Boundary::default());
        let controller = Controller::new("test", storage.clone(), boundary.clone()).unwrap();
        controller.replace_devices(serde_json::from_value(json!([
            {"uuid":"host","name":"Server","address":"192.0.2.1","online":true,"paired":true}
        ])).unwrap());
        assert_eq!(controller.dispatch("device.connect", json!({"uuid":"host"}))["ok"], true);
        controller.stream_state(true, json!({"phase":"connected","firstFrame":false,"nativeCleanupPending":false}));
        let ui = MainWindow::new().unwrap();
        let toolbar = StreamToolbar::new().unwrap();
        let picker = StreamPicker::new().unwrap();
        ui.window().set_size(LogicalSize::new(900., 700.));
        toolbar.window().set_size(LogicalSize::new(560., 292.));
        toolbar.set_expanded(true); toolbar.set_pending(true);
        ui.show().unwrap(); toolbar.show().unwrap();
        let bridge = Arc::new(Bridge::new(true));
        let driver = Driver::new(bridge.clone(), &ui, &toolbar, &picker);
        let calls = Rc::new(RefCell::new(Vec::new()));
        macro_rules! bind {($window:expr) => {{
            let trace = bridge.clone(); let calls = calls.clone();
            $window.global::<Commands>().on_trigger(move |id, arg| {
                trace.observe_action(id.as_str()); calls.borrow_mut().push((id.to_string(), arg.to_string()));
            });
        }}}
        bind!(ui); bind!(toolbar); bind!(picker);
        i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
        Self { ui, toolbar, picker, controller, boundary, bridge, driver, calls, storage }
    }
    fn drain(&mut self, id: &str) -> Value {
        for _ in 0..12 {
            self.driver.tick(&self.ui, &self.toolbar, &self.picker, &self.controller);
            i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
            let value = self.bridge.execute("view.ui.result", &json!({"id":id})).unwrap();
            if value["status"] == "complete" || value["status"] == "failed" { return value; }
        }
        panic!("Button request did not complete");
    }
    fn activate(&mut self, args: Value) -> Value {
        let request = self.bridge.execute("view.ui.activate", &args).unwrap();
        self.drain(request["id"].as_str().unwrap())
    }
    fn request(&mut self, action: &str, args: Value) -> Value {
        let request = self.bridge.execute(action, &args).unwrap();
        self.drain(request["id"].as_str().unwrap())
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.picker.hide(); let _ = self.toolbar.hide(); let _ = self.ui.hide();
        if self.storage.exists() { std::fs::remove_file(&self.storage).unwrap(); }
    }
}

#[path="session_picker_tests.rs"]
mod session_picker_tests;

#[path = "session_reading_tests.rs"]
mod session_reading_tests;

#[test]
fn save_error_details_are_focusable_and_scrollable_through_the_application_test_api() {
    let mut f=Fixture::new();f.ui.window().set_size(LogicalSize::new(720.,560.));
    fn state(f:&mut Fixture,id:&str)->Value{let result=f.request("view.ui.inspect",json!({}));result["result"]["controls"].as_array().unwrap().iter().find(|c|c["id"]==id).expect("Visible error details").clone()}
    for profile in [false,true]{
        let id=if profile{f.ui.set_page("settings".into());f.ui.set_settings_error("保存失败的详细原因。\n".repeat(80).into());"scroll|profile-error"}else{f.ui.set_page("global-settings".into());f.ui.set_settings_tab("sync".into());f.ui.set_sync_error("WebDAV 保存失败的详细原因。\n".repeat(80).into());"scroll|sync-error"};
        f.ui.window().take_snapshot().unwrap();f.calls.borrow_mut().clear();
        assert_eq!(f.request("view.ui.focus",json!({"control":id}))["status"],"complete");assert_eq!(state(&mut f,id)["focused"],true);
        for key in ["PageDown","Down"]{assert_eq!(f.request("view.ui.key",json!({"key":key}))["status"],"complete");assert!(state(&mut f,id)["scroll"]["y"].as_f64().unwrap()<0.);}
        assert_eq!(f.request("view.ui.key",json!({"key":"Home"}))["status"],"complete");assert!(state(&mut f,id)["scroll"]["y"].as_f64().unwrap().abs()<1.);
        assert_eq!(f.request("view.ui.scroll",json!({"control":id,"pixels":-10000}))["status"],"complete");let bottom=state(&mut f,id);assert_eq!(bottom["scroll"]["y"],bottom["scroll"]["minimumY"]);
        assert_eq!(f.request("view.ui.key",json!({"key":"PageUp"}))["status"],"complete");assert!(state(&mut f,id)["scroll"]["y"].as_f64().unwrap()>bottom["scroll"]["y"].as_f64().unwrap());assert!(f.calls.borrow().is_empty(),"Reading an error triggered a business action");
    }
}

#[test]
fn activation_key_repeats_do_not_replay_buttons_navigation_device_rows_or_picker_items() {
    let mut f=Fixture::new();
    for kind in ["button","navigation","device","picker"] {
        f.ui.set_page(if kind=="button"{"add"}else{"devices"}.into());
        f.ui.set_add_address("192.0.2.1".into());
        f.ui.set_devices(slint::ModelRc::new(slint::VecModel::from(vec![crate::DeviceRow{uuid:"host".into(),name:"Fixture".into(),..Default::default()}])));
        let window=if kind=="picker"{"picker"}else{"main"};
        if kind=="picker" {
            f.controller.stream_state(true,json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}));
            assert_eq!(f.controller.dispatch("stream.shortcuts.open",json!({}))["ok"],true);
            f.picker.set_kind("shortcuts".into());f.picker.set_labels(slint::ModelRc::new(slint::VecModel::from(vec!["Tab".into()])));f.picker.set_values(slint::ModelRc::new(slint::VecModel::from(vec!["tab".into()])));f.picker.show().unwrap();
        }
        let inspection=f.request("view.ui.inspect",json!({"window":window}));assert_eq!(inspection["status"],"complete");
        let control=inspection["result"]["controls"].as_array().unwrap().iter().find(|row|{
            let id=row["id"].as_str().unwrap_or("");match kind{"button"=>id.starts_with("action|device.add.submit|"),"navigation"=>id=="nav|app.settings.open","device"=>id=="device|host",_=>id=="picker|shortcuts|tab"}
        }).expect("Actual activation control")["id"].clone();
        assert_eq!(f.request("view.ui.focus",json!({"window":window,"control":control}))["status"],"complete");
        for key in ["Enter","Space"] {
            f.calls.borrow_mut().clear();
            for _ in 0..3 {assert_eq!(f.request("view.ui.key",json!({"window":window,"key":key,"repeat":true}))["status"],"complete");}
            assert!(f.calls.borrow().is_empty(),"{kind} repeated {key} replayed a command");
            assert_eq!(f.request("view.ui.key",json!({"window":window,"key":key}))["status"],"complete");
            assert_eq!(f.calls.borrow().len(),1,"{kind} ordinary {key} must execute once");
        }
    }
    f.picker.hide().unwrap();
}

#[test]
fn editable_fields_keep_repeated_space_and_arrow_events() {
    let mut f=Fixture::new();f.ui.set_page("devices".into());f.ui.set_device_query("test".into());
    assert_eq!(f.request("view.ui.focus",json!({"control":"input|搜索名称或地址"}))["status"],"complete");
    assert_eq!(f.request("view.ui.key",json!({"key":"End"}))["status"],"complete");f.calls.borrow_mut().clear();
    assert_eq!(f.request("view.ui.key",json!({"key":"Left","repeat":true}))["status"],"complete");
    assert_eq!(f.request("view.ui.key",json!({"key":"Space","repeat":true}))["status"],"complete");
    assert_eq!(&*f.calls.borrow(),&[("devices.filter".into(),"tes t".into())]);
}

#[test]
fn search_editor_rebinds_after_user_edits_and_external_query_resets() {
    let mut f=Fixture::new();f.ui.set_page("devices".into());
    for pass in 0..2 {
        f.ui.set_device_query("test".into());f.ui.window().take_snapshot().unwrap();
        assert_eq!(f.request("view.ui.focus",json!({"control":"input|搜索名称或地址"}))["status"],"complete");
        assert_eq!(f.request("view.ui.key",json!({"key":"End"}))["status"],"complete");f.calls.borrow_mut().clear();
        f.request("view.ui.key",json!({"key":"Left","repeat":true}));f.request("view.ui.key",json!({"key":"Space","repeat":true}));
        assert_eq!(&*f.calls.borrow(),&[("devices.filter".into(),"tes t".into())],"Search text was not reset before pass {pass}");
        // Model feedback, followed by an explicit external clear, on the same editor instance.
        f.ui.set_device_query("tes t".into());f.ui.window().take_snapshot().unwrap();
        f.ui.set_device_query("".into());f.ui.window().take_snapshot().unwrap();
    }
}

#[test]
fn live_search_edits_commit_before_model_refresh_and_keep_the_same_editor_reusable() {
    let mut f=Fixture::new();f.controller.stream_state(false,json!({"phase":"idle"}));assert_eq!(f.controller.dispatch("devices.open",json!({}))["ok"],true);
    let controller=f.controller.clone();let trace=f.bridge.clone();let calls=f.calls.clone();
    f.ui.global::<Commands>().on_trigger(move|id,arg|{
        trace.observe_action(id.as_str());calls.borrow_mut().push((id.to_string(),arg.to_string()));
        assert_eq!(crate::view_state::dispatch_local_input(&controller,id.as_str(),arg.as_str(),None).expect("Only local search edits expected")["ok"],true);
    });
    let mut view=crate::view_state::MainView::new(&f.ui);
    f.controller.dispatch("devices.filter",json!({"query":"test"}));view.refresh(&f.ui,&f.controller.state(),|uuid|f.controller.profile(uuid));f.ui.window().take_snapshot().unwrap();
    assert_eq!(f.request("view.ui.focus",json!({"control":"input|搜索名称或地址"}))["status"],"complete");assert_eq!(f.request("view.ui.key",json!({"key":"End"}))["status"],"complete");
    for count in 1..=4 {
        assert_eq!(f.request("view.ui.key",json!({"key":"Space","repeat":true}))["status"],"complete");
        let expected=format!("test{}"," ".repeat(count));assert_eq!(f.controller.state()["deviceQuery"],expected);
        view.refresh(&f.ui,&f.controller.state(),|uuid|f.controller.profile(uuid));f.ui.window().take_snapshot().unwrap();assert_eq!(f.ui.get_device_query().as_str(),expected);
    }
    f.controller.dispatch("devices.filter",json!({"query":""}));view.refresh(&f.ui,&f.controller.state(),|uuid|f.controller.profile(uuid));f.ui.window().take_snapshot().unwrap();
    f.request("view.ui.key",json!({"key":"Space","repeat":true}));assert_eq!(f.controller.state()["deviceQuery"]," ");
    assert!(crate::view_state::dispatch_local_input(&f.controller,"sync.run","ignored",None).is_none());
}

#[test]
fn form_editors_follow_external_values_after_their_first_user_edit() {
    let mut f=Fixture::new();f.ui.window().set_size(LogicalSize::new(1180.,780.));f.ui.set_selected_uuid("host".into());
    f.ui.set_devices(slint::ModelRc::new(slint::VecModel::from(vec![crate::DeviceRow{uuid:"host".into(),name:"Fixture".into(),..Default::default()}])));
    let fields=Rc::new(slint::VecModel::from(vec![crate::SettingRow{key:"fps".into(),label:"帧率".into(),kind:"number".into(),input_text:"24".into(),minimum:1,maximum:240,step:1,..Default::default()}]));f.ui.set_profile_fields(fields.clone().into());
    let mut mismatches=Vec::new();
    for (kind,label,action,prefix) in [
        ("add","设备地址","device.add.input",""),("info","设备名称","device.info.input",""),
        ("dav-url","WebDAV 目录地址（HTTP / HTTPS）","sync.edit","url="),("dav-user","WebDAV 用户名","sync.edit","username="),
        ("address","首选 IP 或主机名","settings.network.edit","address="),("addresses","备用地址","settings.network.edit","addresses="),("relay","中继服务器","settings.network.edit","relayUrl="),
        ("number","帧率","settings.input","fps=")
    ] {
        f.ui.set_page(match kind{"add"=>"add","info"=>"device-info","dav-url"|"dav-user"=>"global-settings",_=>"settings"}.into());
        f.ui.set_settings_tab("sync".into());f.ui.set_profile_tab(if kind=="number"{"video"}else{"network"}.into());
        let view=f.ui.as_weak().upgrade().unwrap();let set=|text:&str|match kind {
            "add"=>view.set_add_address(text.into()),"info"=>view.set_info_name(text.into()),"dav-url"=>view.set_sync_url(text.into()),"dav-user"=>view.set_sync_username(text.into()),
            "address"=>view.set_network_address(text.into()),"addresses"=>view.set_network_addresses(text.into()),"relay"=>view.set_relay_url(text.into()),
            _=>{let mut row=fields.row_data(0).unwrap();row.input_text=text.into();fields.set_row_data(0,row);}
        };
        set(if kind=="number"{"24"}else{"alpha"});f.ui.window().take_snapshot().unwrap();
        assert_eq!(f.request("view.ui.focus",json!({"control":format!("input|{label}")}))["status"],"complete","{kind}");
        f.request("view.ui.key",json!({"key":"End"}));f.calls.borrow_mut().clear();f.request("view.ui.key",json!({"key":"Space"}));
        assert_eq!(f.calls.borrow().len(),1,"First actual edit {kind}");
        let next=if kind=="number"{"45"}else{"beta"};set(next);f.ui.window().take_snapshot().unwrap();f.calls.borrow_mut().clear();
        f.request("view.ui.key",json!({"key":"End"}));f.request("view.ui.key",json!({"key":"Space"}));
        let expected=vec![(action.to_string(),format!("{prefix}{next} "))];
        if *f.calls.borrow()!=expected{mismatches.push((kind,expected,f.calls.borrow().clone()));}
    }
    assert!(mismatches.is_empty(),"Edited fields retained old text after model changes: {mismatches:?}");
}

#[test]
fn stateful_form_controls_follow_external_values_after_user_changes() {
    let mut f=Fixture::new();f.ui.window().set_size(LogicalSize::new(1180.,780.));f.ui.set_selected_uuid("host".into());
    f.ui.set_devices(slint::ModelRc::new(slint::VecModel::from(vec![crate::DeviceRow{uuid:"host".into(),name:"Fixture".into(),..Default::default()}])));
    let mut mismatches=Vec::new();
    f.ui.set_page("global-settings".into());f.ui.set_settings_tab("sync".into());f.ui.set_sync_automatic(false);
    assert_eq!(f.request("view.ui.focus",json!({"control":"check|自动同步"}))["status"],"complete");f.request("view.ui.key",json!({"key":"Space"}));
    f.ui.set_sync_automatic(true);f.ui.set_sync_automatic(false);let report=f.request("view.ui.inspect",json!({}));
    if report["result"]["controls"].as_array().unwrap().iter().find(|c|c["id"]=="check|自动同步").unwrap()["checked"]!=false{mismatches.push("automatic checkbox retained user value".to_string());}
    f.ui.set_sync_interval_index(0);assert_eq!(f.request("view.ui.focus",json!({"control":"choice|自动同步间隔"}))["status"],"complete");f.request("view.ui.key",json!({"key":"Down"}));
    f.ui.set_sync_interval_index(1);f.ui.set_sync_interval_index(2);f.calls.borrow_mut().clear();f.request("view.ui.key",json!({"key":"Down"}));
    if *f.calls.borrow()!=vec![("sync.edit".to_string(),"intervalMinutes=60".to_string())]{mismatches.push(format!("interval retained old selection: {:?}",f.calls.borrow()));}
    f.ui.set_page("settings".into());f.ui.set_profile_tab("network".into());
    let routes=Rc::new(slint::VecModel::from(vec![crate::RouteRow{key:"lan".into(),label:"局域网".into(),enabled:true,can_disable:true,..Default::default()}]));f.ui.set_network_routes(routes.clone().into());
    assert_eq!(f.request("view.ui.focus",json!({"control":"check|连接路径：局域网"}))["status"],"complete");f.request("view.ui.key",json!({"key":"Space"}));let mut route=routes.row_data(0).unwrap();route.enabled=false;routes.set_row_data(0,route.clone());route.enabled=true;routes.set_row_data(0,route);
    let report=f.request("view.ui.inspect",json!({}));if report["result"]["controls"].as_array().unwrap().iter().find(|c|c["id"]=="check|连接路径：局域网").unwrap()["checked"]!=true{mismatches.push("route checkbox retained user value".to_string());}
    f.ui.set_profile_tab("video".into());let choices=Rc::new(slint::VecModel::from(vec![crate::SettingRow{key:"codec".into(),label:"视频编码".into(),kind:"select".into(),selected_index:0,option_labels:slint::ModelRc::new(slint::VecModel::from(vec!["A".into(),"B".into(),"C".into(),"D".into()])),option_values:slint::ModelRc::new(slint::VecModel::from(vec!["a".into(),"b".into(),"c".into(),"d".into()])),..Default::default()}]));f.ui.set_profile_fields(choices.clone().into());
    assert_eq!(f.request("view.ui.focus",json!({"control":"choice|视频编码"}))["status"],"complete");f.request("view.ui.key",json!({"key":"Down"}));let mut row=choices.row_data(0).unwrap();row.selected_index=1;choices.set_row_data(0,row.clone());row.selected_index=2;choices.set_row_data(0,row);f.calls.borrow_mut().clear();f.request("view.ui.key",json!({"key":"Down"}));
    if *f.calls.borrow()!=vec![("settings.set".to_string(),"codec=d".to_string())]{mismatches.push(format!("profile choice retained old selection: {:?}",f.calls.borrow()));}
    f.toolbar.set_pending(false);f.toolbar.set_controls_tab("audio".into());f.toolbar.set_audio_volume(40);
    assert_eq!(f.request("view.ui.focus",json!({"window":"toolbar","control":"spin|串流音量"}))["status"],"complete");f.request("view.ui.key",json!({"window":"toolbar","key":"Up"}));f.toolbar.set_audio_volume(41);f.toolbar.set_audio_volume(90);f.calls.borrow_mut().clear();f.request("view.ui.key",json!({"window":"toolbar","key":"Up"}));
    if *f.calls.borrow()!=vec![("stream.audio.volume".to_string(),"91".to_string())]{mismatches.push(format!("volume retained old value: {:?}",f.calls.borrow()));}
    assert!(mismatches.is_empty(),"Stateful controls did not restore their model values: {mismatches:?}");
}

#[test]
fn local_draft_edits_preserve_owner_validation_without_io_or_persistence() {
    let f=Fixture::new();f.controller.stream_state(false,json!({"phase":"idle"}));
    let saved=f.controller.state()["saved"].clone();let bytes=std::fs::read(&f.storage).ok();
    let edit=|id:&str,text:&str,owner:Option<String>|crate::view_state::dispatch_local_input(&f.controller,id,text,owner).unwrap();
    f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));
    let owner=f.controller.state()["profileDraftId"].as_str().unwrap().to_owned();f.boundary.calls.lock().unwrap().clear();
    for (id,arg) in [("settings.input","fps=45"),("settings.step","fps|up"),("settings.network.edit","address=example.invalid"),("settings.set","audioEnabled=toggle"),("settings.set","audioEnabled=toggle"),("settings.set","codec=H.264"),("settings.route.toggle","lan"),("settings.route.move","tailscale_direct|up")] {
        assert_eq!(edit(id,arg,Some(owner.clone()))["ok"],true,"{id}/{arg}");
    }
    assert_eq!(f.controller.state()["settingsInputs"]["fps"],"46");
    assert_eq!(f.controller.state()["draftAddress"],"example.invalid");
    f.controller.dispatch("settings.cancel",json!({}));f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));
    let before=f.controller.state();let rejected=edit("settings.input","fps=99",Some(owner));
    assert_eq!(rejected["ignored"],true);assert_eq!(f.controller.state()["settingsInputs"],before["settingsInputs"]);
    f.controller.dispatch("settings.cancel",json!({}));f.controller.dispatch("device.add.open",json!({}));
    assert_eq!(edit("device.add.input","example.invalid",None)["ok"],true);
    f.controller.dispatch("device.add.cancel",json!({}));f.controller.dispatch("device.info.open",json!({"uuid":"host"}));
    assert_eq!(edit("device.info.input","Local alias",None)["ok"],true);assert_eq!(edit("device.info.reset","",None)["ok"],true);
    assert!(f.boundary.calls.lock().unwrap().is_empty());
    f.controller.dispatch("device.info.cancel",json!({}));f.controller.dispatch("app.settings.tab",json!({"tab":"sync"}));f.boundary.calls.lock().unwrap().clear();
    for arg in ["url=http://example.invalid/dav","username=draft-user","automatic=true","intervalMinutes=30"]{assert_eq!(edit("sync.edit",arg,None)["ok"],true);}
    assert_eq!(f.controller.state()["syncDraft"]["intervalMinutes"],30);
    for id in ["settings.save","sync.save","sync.run","device.info.save","device.add.submit","device.connect","stream.audio.volume","settings.copy_from"] {
        assert!(crate::view_state::dispatch_local_input(&f.controller,id,"ignored",None).is_none(),"{id} must remain queued");
    }
    assert!(f.boundary.calls.lock().unwrap().is_empty());assert_eq!(f.controller.state()["saved"],saved);assert_eq!(std::fs::read(&f.storage).ok(),bytes);
}

#[test]
fn rapid_form_edits_commit_before_model_feedback_and_follow_external_changes() {
    let mut f=Fixture::new();f.controller.stream_state(false,json!({"phase":"idle"}));
    let controller=f.controller.clone();let weak=f.ui.as_weak();let trace=f.bridge.clone();
    f.ui.global::<Commands>().on_trigger(move|id,arg|{
        trace.observe_action(id.as_str());let ui=weak.upgrade().unwrap();
        let owner=crate::request_owner(&ui,id.as_str());
        assert_eq!(crate::view_state::dispatch_local_input(&controller,id.as_str(),arg.as_str(),owner).unwrap()["ok"],true);
    });
    let mut view=crate::view_state::MainView::new(&f.ui);
    for (kind,label,id,key) in [
        ("add","设备地址","device.add.input","address"),("info","设备名称","device.info.input","displayName"),
        ("dav","WebDAV 目录地址（HTTP / HTTPS）","sync.edit","url"),("dav","WebDAV 用户名","sync.edit","username"),
        ("network","首选 IP 或主机名","settings.network.edit","address"),("network","备用地址","settings.network.edit","addresses"),("network","中继服务器","settings.network.edit","relayUrl"),
        ("number","帧率","settings.input","fps")
    ] {
        match kind {
            "add"=>{f.controller.dispatch("device.add.open",json!({}));},
            "info"=>{f.controller.dispatch("device.info.open",json!({"uuid":"host"}));},
            "dav"=>{f.controller.dispatch("app.settings.tab",json!({"tab":"sync"}));},
            _=>{f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));f.controller.dispatch("settings.tab",json!({"tab":if kind=="number"{"video"}else{"network"}}));}
        }
        let draft_value=|text:&str|match kind{"add"|"info"=>json!({key:text}),_=>json!({"key":key,"value":text})};
        let read=|state:&Value|match kind{"add"=>state["add"]["address"].clone(),"info"=>state["infoDraft"]["displayName"].clone(),"dav"=>state["syncDraft"][key].clone(),"number"=>state["settingsInputs"][key].clone(),_=>state[match key{"address"=>"draftAddress","addresses"=>"draftAddresses",_=>"draftRelay"}].clone()};
        let initial=if kind=="number"{"24"}else{"alpha"};assert_eq!(f.controller.dispatch(id,draft_value(initial))["ok"],true);
        view.refresh(&f.ui,&f.controller.state(),|uuid|f.controller.profile(uuid));f.ui.window().take_snapshot().unwrap();
        assert_eq!(f.request("view.ui.focus",json!({"control":format!("input|{label}")}))["status"],"complete","{label}");f.request("view.ui.key",json!({"key":"End"}));
        for count in 1..=3 {
            f.request("view.ui.key",json!({"key":"Space"}));assert_eq!(read(&f.controller.state()),format!("{initial}{}"," ".repeat(count)),"{label}");
            view.refresh(&f.ui,&f.controller.state(),|uuid|f.controller.profile(uuid));f.ui.window().take_snapshot().unwrap();
        }
        let next=if kind=="number"{"45"}else{"beta"};f.controller.dispatch(id,draft_value(next));view.refresh(&f.ui,&f.controller.state(),|uuid|f.controller.profile(uuid));f.ui.window().take_snapshot().unwrap();
        f.request("view.ui.key",json!({"key":"End"}));f.request("view.ui.key",json!({"key":"Space"}));assert_eq!(read(&f.controller.state()),format!("{next} "),"{label} after replacement");
        let cancel=match kind{"add"=>"device.add.cancel","info"=>"device.info.cancel","dav"=>"sync.cancel",_=>"settings.cancel"};assert_eq!(f.controller.dispatch(cancel,json!({}))["ok"],true);
    }
}

#[test]
fn stale_profile_choice_restores_the_unchanged_authoritative_value() {
    let mut f=Fixture::new();f.controller.stream_state(false,json!({"phase":"idle"}));
    f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));
    let mut view=crate::view_state::MainView::new(&f.ui);view.refresh(&f.ui,&f.controller.state(),|uuid|f.controller.profile(uuid));f.ui.window().take_snapshot().unwrap();
    let initial=f.ui.get_profile_fields().iter().find(|r|r.key=="codec").unwrap();
    let next=initial.option_values.row_data(initial.selected_index as usize+1).unwrap();
    let outcomes=Rc::new(RefCell::new(Vec::new()));let observed=outcomes.clone();let controller=f.controller.clone();let weak=f.ui.as_weak();let trace=f.bridge.clone();
    f.ui.global::<Commands>().on_trigger(move|id,arg|{
        trace.observe_action(id.as_str());let owner=crate::request_owner(&weak.upgrade().unwrap(),id.as_str());
        observed.borrow_mut().push(crate::view_state::dispatch_local_input(&controller,id.as_str(),arg.as_str(),owner).unwrap());
    });
    // Replace the draft without yet refreshing the old source control. Its values
    // are identical, but the owner is different and the first edit must be ignored.
    f.controller.dispatch("settings.cancel",json!({}));f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));
    assert_eq!(f.request("view.ui.focus",json!({"control":"choice|视频编码"}))["status"],"complete");f.request("view.ui.key",json!({"key":"Down"}));
    assert_eq!(outcomes.borrow()[0]["ignored"],true);
    view.refresh(&f.ui,&f.controller.state(),|uuid|f.controller.profile(uuid));f.ui.window().take_snapshot().unwrap();
    assert_eq!(f.ui.get_profile_fields().iter().find(|r|r.key=="codec").unwrap().selected_index,initial.selected_index);
    f.request("view.ui.key",json!({"key":"Down"}));assert_eq!(outcomes.borrow()[1]["ok"],true);assert_eq!(f.controller.state()["draft"]["codec"],next.as_str());
}

#[test]
fn exact_selection_activates_each_duplicate_cancel_button_once_and_rejects_stale_controls() {
    let mut f=Fixture::new();f.ui.set_page("add".into());
    assert_eq!(f.activate(json!({"actionId":"device.add.cancel"}))["status"],"failed");
    for label in ["返回设备","取消"]{
        let control=format!("action|device.add.cancel||{label}");
        let result=f.activate(json!({"window":"main","actionId":"device.add.cancel","control":control}));
        assert_eq!(result["status"],"complete","{result}");assert_eq!(result["result"]["activatedControl"],control);
        assert_eq!(result["result"]["commandIds"],json!(["device.add.cancel"]));
        assert_eq!(result["result"]["systemInputInjected"],false);assert_eq!(result["result"]["windowActivationRequested"],false);
    }
    assert_eq!(&*f.calls.borrow(),&[("device.add.cancel".into(),"".into()),("device.add.cancel".into(),"".into())]);
    f.ui.set_page("devices".into());
    assert_eq!(f.activate(json!({"actionId":"device.add.cancel","control":"action|device.add.cancel||取消"}))["status"],"failed");
    assert_eq!(f.calls.borrow().len(),2);
}

#[test]
fn drag_sort_api_preserves_compound_targets_and_grip_focus_respects_modal_guards(){
    let mut f=Fixture::new();f.ui.set_page("devices".into());f.ui.set_devices(slint::ModelRc::new(slint::VecModel::from(vec![crate::DeviceRow{uuid:"host".into(),name:"Server".into(),..Default::default()}])));
    assert_eq!(crate::args_for("device.move","host|other|after",&json!({})),json!({"uuid":"host","targetUuid":"other","placement":"after"}));assert_eq!(crate::args_for("settings.route.move","lan|relay|before",&json!({})),json!({"route":"lan","targetRoute":"relay","placement":"before"}));
    let args=json!({"control":"reorder|devices|host"});assert_eq!(f.request("view.ui.focus",args.clone())["status"],"complete");f.ui.set_navigation_prompt(true);assert_eq!(f.request("view.ui.focus",args)["status"],"failed");assert!(f.calls.borrow().is_empty());
}

#[test]
fn exact_navigation_selection_keeps_current_disabled_and_modal_guards() {
    let mut f=Fixture::new();let args=json!({"window":"main","actionId":"app.settings.open","control":"nav|app.settings.open"});
    f.ui.set_navigation_locked(true);assert_eq!(f.activate(args.clone())["status"],"failed");
    f.ui.set_navigation_locked(false);f.ui.set_navigation_prompt(true);assert_eq!(f.activate(args.clone())["status"],"failed");
    assert!(f.calls.borrow().is_empty());f.ui.set_navigation_prompt(false);
    let result=f.activate(args);assert_eq!(result["status"],"complete","{result}");assert_eq!(result["result"]["activatedControl"],"nav|app.settings.open");
    assert_eq!(&*f.calls.borrow(),&[("app.settings.open".into(),"".into())]);
}

#[test]
fn exact_caption_and_pending_precondition_are_rechecked_at_execution() {
    let mut f=Fixture::new();f.toolbar.set_pending(false);
    let args=json!({"requestId":"exact-pending","window":"toolbar","actionId":"stream.disconnect","control":"action|stream.disconnect||断开","expectedPending":false});
    f.bridge.execute("view.ui.activate",&args).unwrap();f.toolbar.set_pending(true);
    assert_eq!(f.drain("exact-pending")["status"],"failed");assert!(f.calls.borrow().is_empty());
    assert_eq!(f.activate(json!({"window":"toolbar","actionId":"stream.disconnect","control":"action|stream.disconnect||断开"}))["status"],"failed");
    f.toolbar.set_pending(false);let result=f.activate(json!({"window":"toolbar","actionId":"stream.disconnect","control":"action|stream.disconnect||断开","expectedPending":false}));
    assert_eq!(result["status"],"complete","{result}");assert_eq!(f.calls.borrow().len(),1);
}

#[test]
fn sidebar_navigation_activates_its_real_callback_exactly_once() {
    let mut f=Fixture::new();
    let args=json!({"requestId":"sidebar-settings","window":"main","actionId":"app.settings.open"});
    let result=f.activate(args.clone());
    assert_eq!(result["status"],"complete","{result}");
    assert_eq!(result["result"]["activatedControl"],"nav|app.settings.open");
    assert_eq!(result["result"]["commandIds"],json!(["app.settings.open"]));
    assert_eq!(result["result"]["systemInputInjected"],false);
    assert_eq!(result["result"]["windowActivationRequested"],false);
    assert_eq!(&*f.calls.borrow(),&[("app.settings.open".into(),"".into())]);
    let reused=f.bridge.execute("view.ui.activate",&args).unwrap();
    assert_eq!(reused["status"],"complete");assert_eq!(f.calls.borrow().len(),1);
}

#[test]
fn sidebar_navigation_rechecks_disabled_modal_and_argument_boundaries() {
    let mut f=Fixture::new();
    let args=json!({"window":"main","actionId":"app.settings.open"});
    f.ui.set_navigation_locked(true);
    assert_eq!(f.activate(args.clone())["status"],"failed");assert!(f.calls.borrow().is_empty());
    f.ui.set_navigation_locked(false);f.ui.set_navigation_prompt(true);
    assert_eq!(f.activate(args.clone())["status"],"failed");assert!(f.calls.borrow().is_empty());
    f.ui.set_navigation_prompt(false);
    assert_eq!(f.activate(json!({"window":"main","actionId":"app.settings.open","argument":"unexpected"}))["status"],"failed");
    assert!(f.calls.borrow().is_empty());
    assert_eq!(f.activate(args)["status"],"complete");assert_eq!(f.calls.borrow().len(),1);
}

#[test]
fn semantic_button_activation_survives_a_label_change_after_queueing() {
    let mut f = Fixture::new();
    let args = json!({"requestId":"changing-label","window":"toolbar","actionId":"stream.disconnect"});
    f.bridge.execute("view.ui.activate", &args).unwrap();
    f.toolbar.set_pending(false);
    f.controller.stream_state(true, json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}));
    let result = f.drain("changing-label");
    assert_eq!(result["status"], "complete", "{result}");
    assert_eq!(result["result"]["activatedLabel"], "断开");
    assert_eq!(result["result"]["commandIds"], json!(["stream.disconnect"]));
    assert_eq!(&*f.calls.borrow(), &[("stream.disconnect".into(), "".into())]);
    assert_eq!(result["result"]["systemInputInjected"], false);
    assert_eq!(result["result"]["windowActivationRequested"], false);
}

#[test]
fn pending_precondition_is_rechecked_when_the_queued_button_executes() {
    let mut f = Fixture::new();
    let args = json!({"requestId":"pending-changed","window":"toolbar","actionId":"stream.disconnect","expectedPending":true});
    f.bridge.execute("view.ui.activate", &args).unwrap();
    f.toolbar.set_pending(false);
    let result = f.drain("pending-changed");
    assert_eq!(result["status"], "failed"); assert!(f.calls.borrow().is_empty());
    f.toolbar.set_pending(true);
    assert_eq!(f.bridge.execute("view.ui.activate", &args).unwrap()["status"], "failed");
    let result = f.activate(json!({"requestId":"pending-current","window":"toolbar","actionId":"stream.disconnect","expectedPending":true}));
    assert_eq!(result["status"], "complete", "{result}");
    assert_eq!(result["result"]["toolbarPendingBefore"], true);
    assert_eq!(f.calls.borrow().len(), 1);
}

#[test]
fn a_disabled_button_does_not_dispatch_and_completed_requests_do_not_replay() {
    let mut f = Fixture::new(); f.toolbar.set_canceling(true);
    let result = f.activate(json!({"requestId":"disabled","window":"toolbar","actionId":"stream.disconnect"}));
    assert_eq!(result["status"], "failed"); assert!(f.calls.borrow().is_empty());
    f.toolbar.set_canceling(false);
    let args = json!({"requestId":"once","window":"toolbar","actionId":"stream.disconnect"});
    assert_eq!(f.activate(args.clone())["status"], "complete");
    assert_eq!(f.bridge.execute("view.ui.activate", &args).unwrap()["status"], "complete");
    assert_eq!(f.calls.borrow().len(), 1);
    assert!(f.bridge.execute("view.ui.activate", &json!({"requestId":"once","window":"toolbar","actionId":"stream.disconnect","expectedPending":true})).is_err());
}

#[test]
fn modal_dialogs_keep_background_buttons_blocked_for_semantic_activation() {
    let mut f = Fixture::new(); f.ui.set_page("add".into());
    f.ui.set_add_address("192.0.2.1".into()); f.ui.set_navigation_prompt(true);
    let result = f.activate(json!({"window":"main","actionId":"device.add.submit","argument":"192.0.2.1"}));
    assert_eq!(result["status"], "failed"); assert!(f.calls.borrow().is_empty());
    let result = f.activate(json!({"window":"main","actionId":"navigation.resolve","argument":"stay"}));
    assert_eq!(result["status"], "complete", "{result}");
    assert_eq!(&*f.calls.borrow(), &[("navigation.resolve".into(), "stay".into())]);
}

#[test]
fn detached_failure_controls_require_the_current_ui_owned_session_context() {
    let mut f = Fixture::new();
    f.controller.stream_state(true,json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}));
    f.controller.stream_state(false,json!({"phase":"error","error":"automatic recovery exhausted","firstFrame":false,"nativeCleanupPending":false}));
    assert_eq!(f.controller.state()["connection"]["phase"],"failed");
    f.toolbar.set_connection_failed(true);f.toolbar.set_pending(false);
    let attempt=f.controller.state()["connection"]["attempt"].clone();
    for context in [json!({"visible":false}),json!({"visible":true,"detached":true,"connectionFailed":true,"uuid":"other","attempt":attempt}),json!({"visible":true,"detached":true,"connectionFailed":true,"uuid":"host","attempt":99})] {
        f.controller.toolbar_state(context);
        assert_eq!(f.activate(json!({"window":"toolbar","actionId":"connection.retry"}))["status"],"failed");
        assert!(f.calls.borrow().is_empty());
    }
    f.controller.toolbar_state(json!({"visible":true,"detached":true,"connectionFailed":true,"uuid":"host","attempt":attempt}));
    let result=f.activate(json!({"window":"toolbar","actionId":"connection.retry"}));
    assert_eq!(result["status"],"complete","{result}");
    assert_eq!(result["result"]["commandIds"],json!(["connection.retry"]));
    let result=f.activate(json!({"window":"toolbar","actionId":"connection.dismiss"}));
    assert_eq!(result["status"],"complete","{result}");
    assert_eq!(result["result"]["commandIds"],json!(["connection.dismiss"]));
    f.controller.dispatch("connection.dismiss",json!({}));
    assert_eq!(f.activate(json!({"window":"toolbar","actionId":"connection.retry"}))["status"],"failed");
    assert_eq!(f.calls.borrow().len(),2,"a stale failure card must not dispatch");
}

#[test]
fn floating_keys_use_actual_widget_callbacks_and_reject_hidden_or_busy_input() {
    let mut f = Fixture::new();
    let keyboard = crate::FloatingKeyboardWindow::new().unwrap();
    keyboard.window().set_size(LogicalSize::new(840.,560.));
    keyboard.set_keys(crate::floating_keyboard::model(&crate::floating_keyboard::layout(840.,560.)));
    let trace=f.bridge.clone();let calls=f.calls.clone();
    keyboard.global::<Commands>().on_trigger(move|id,arg|{trace.observe_action(id.as_str());calls.borrow_mut().push((id.to_string(),arg.to_string()));});
    f.driver.attach_keyboard(&keyboard);keyboard.show().unwrap();
    f.controller.toolbar_state(json!({"floatingKeyboard":{"visible":true}}));
    for key in crate::floating_keyboard::layout(840.,560.) {
        let result=f.activate(json!({"window":"keyboard","actionId":"stream.keyboard.press","argument":key.id}));
        assert_eq!(result["status"],"complete","{result}");
        assert_eq!(result["result"]["commandIds"],json!(["stream.keyboard.press"]));
        assert_eq!(result["result"]["systemInputInjected"],false);
        assert_eq!(f.calls.borrow().last().unwrap(),&("stream.keyboard.press".into(),key.id.into()));
    }
    assert_eq!(f.calls.borrow().len(),108);
    keyboard.set_busy(true);
    assert_eq!(f.activate(json!({"window":"keyboard","actionId":"stream.keyboard.press","argument":"tab"}))["status"],"failed");
    keyboard.set_busy(false);keyboard.hide().unwrap();
    f.controller.toolbar_state(json!({"floatingKeyboard":{"visible":false}}));
    assert_eq!(f.activate(json!({"window":"keyboard","actionId":"stream.keyboard.press","argument":"tab"}))["status"],"failed");
    assert_eq!(f.calls.borrow().len(),108);
}
