//! The real Controller, command argument mapping and view binding, without native windows.
use crate::{Commands, MainWindow, args_for, view_state::MainView};
use i_slint_backend_testing::{
    AccessibleRole, ElementHandle, TestingBackend, TestingBackendOptions,
};
use rhremote_core::{Controller, Device, Platform, Profile, Saved, random_token, sync_model};
use serde_json::{Value, json};
use slint::{
    ComponentHandle, LogicalPosition, LogicalSize, Model,
    platform::{PointerEventButton, WindowEvent},
};
use std::{
    cell::RefCell,
    collections::BTreeMap,
    path::PathBuf,
    rc::Rc,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

/// Only the external boundary is substituted. No sockets, vault or production data are used.
struct Boundary {
    sync: Mutex<Value>,
    updates: Mutex<Value>,
    update_gate: Mutex<Option<(std::sync::mpsc::Sender<()>, std::sync::mpsc::Receiver<()>)>>,
    sync_gate: Mutex<Option<(std::sync::mpsc::Sender<()>, std::sync::mpsc::Receiver<()>)>>,
    fail_save: AtomicBool,
    save_failure: Mutex<Option<String>>,
    calls: Mutex<Vec<String>>,
}

fn profile_focus_ids(ui:&MainWindow)->Vec<String>{
    let result=Rc::new(RefCell::new(Vec::new()));let saved=result.clone();let probe=ui.global::<crate::UiTest>();
    probe.set_enabled(true);probe.set_operation("inspect".into());probe.on_report(move|id,_,_,_,focused,_,_,_,_,_,_|{if focused{saved.borrow_mut().push(id.to_string());}});
    ui.window().take_snapshot().unwrap();probe.set_generation(probe.get_generation()+1);ui.window().take_snapshot().unwrap();
    let ids=result.borrow().clone();ids
}

#[test]
fn empty_device_views_offer_the_correct_recovery_without_changing_profiles(){
    backend();
    for (width,height) in [(720.,560.),(1180.,780.)]{for dark in [false,true]{
        let mut f=Flow::new(width,height);if dark{f.input("appearance.toggle","");}f.input("device.select","host");let saved=f.saved();
        let query="no matching device";f.input("devices.filter",query);assert_eq!(f.ui.get_devices().row_count(),0);assert_eq!(f.ui.get_device_total(),3);f.capture(&format!("empty-{width}-{dark}-no-results"));
        assert_eq!(f.click("清除搜索","devices.filter.clear",query)["ok"],true);assert_eq!(f.controller.state()["deviceQuery"],"");assert_eq!(f.controller.state()["selected"],"host");assert_eq!(f.saved(),saved);assert!(f.boundary.calls.lock().unwrap().is_empty());
        f.input("devices.filter",query);let clear=f.button("清除搜索","devices.filter.clear",query);let p=clear.absolute_position();let size=clear.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
        f.ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});f.input("devices.filter","new missing device");f.ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});assert!(f.pending.borrow().is_empty(),"Held old Clear erased the newer query");assert_eq!(f.controller.state()["deviceQuery"],"new missing device");
        assert_eq!(f.controller.dispatch("devices.filter.clear",json!({"expectedQuery":query}))["ok"],false);assert_eq!(f.click("清除搜索","devices.filter.clear","new missing device")["ok"],true);
        f.input("device.settings.open","host");f.input("settings.input","volume=77");f.input("devices.filter",query);if width>1000.{assert_eq!(f.click("清除搜索","devices.filter.clear",query)["ok"],true);}else{f.input("devices.filter.clear",query);}assert_eq!(f.controller.state()["settingsInputs"]["volume"],"77");assert!(f.controller.state()["settingsDirty"].as_bool().unwrap());assert_eq!(f.saved(),saved);f.input("settings.cancel","");
        let mut devices:Vec<Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();for d in &mut devices{d.online=false;}f.controller.replace_devices(devices);f.refresh();assert_eq!(f.ui.get_devices().row_count(),3);f.capture(&format!("empty-{width}-{dark}-offline"));
        f.controller.replace_devices(vec![]);f.refresh();assert_eq!(f.ui.get_device_total(),0);assert_eq!(f.ui.get_selected_uuid(),"");f.capture(&format!("empty-{width}-{dark}-first-use"));let add=ElementHandle::find_by_accessible_label(&f.ui,"添加电脑").find(|e|e.accessible_id().is_some_and(|id|id.starts_with("action|device.add.open||"))).expect("Empty workspace Add, separate from navigation");assert_eq!(f.activate(add,"添加电脑","device.add.open","")["ok"],true);assert_eq!(f.controller.state()["page"],"add");assert!(f.boundary.calls.lock().unwrap().is_empty());
    }}
}

#[test]
fn device_overview_selection_resets_scroll_and_refresh_preserves_it(){
    backend();
    fn scroll_probe(f:&mut Flow,delta:Option<f32>)->Value{
        let result=Rc::new(RefCell::new(Vec::new()));let saved=result.clone();
        let probe=f.ui.global::<crate::UiTest>();probe.set_enabled(true);
        probe.on_report(move|id,kind,_,_,_,_,_,width,height,y,maximum|{
            if id=="scroll|details"&&kind=="scroll"{saved.borrow_mut().push(json!({"y":y,"maximum":maximum,"width":width,"height":height}));}
        });
        if let Some(delta)=delta{
            probe.set_target("scroll|details".into());probe.set_scroll_delta(delta);probe.set_operation("scroll".into());probe.set_generation(probe.get_generation()+1);f.ui.window().take_snapshot().unwrap();
        }
        probe.set_operation("inspect".into());probe.set_generation(probe.get_generation()+1);f.ui.window().take_snapshot().unwrap();
        let rows=result.borrow();assert_eq!(rows.len(),1,"Exactly one active detail viewport");rows[0].clone()
    }
    let mut records=Vec::new();
    for (width,height) in [(720.,560.),(1180.,780.)]{for dark in [false,true]{for actual_row in [false,true]{
        let mut f=Flow::new(width,height);if dark{f.input("appearance.toggle","");}f.input("device.select","host");
        let profiles=[f.controller.profile("host"),f.controller.profile("claw")];
        let before=scroll_probe(&mut f,Some(-100000.));assert!(before["maximum"].as_f64().unwrap()>0.,"Fixture must scroll: {before}");assert!(before["y"].as_f64().unwrap()< -20.,"Fixture never left the top: {before}");
        let mut devices:Vec<Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();
        let other=devices.iter_mut().find(|d|d.uuid=="claw").unwrap();other.online=!other.online;f.controller.replace_devices(devices);f.refresh();
        let refreshed=scroll_probe(&mut f,None);assert!((before["y"].as_f64().unwrap()-refreshed["y"].as_f64().unwrap()).abs()<1.,"Background discovery moved the current overview");
        let tag=format!("overview-{width}-{height}-{dark}-{}",if actual_row{"row"}else{"action"});f.capture(&format!("{tag}-host-bottom"));
        if actual_row{
            let row=ElementHandle::find_by_accessible_label(&f.ui,"选择设备 claw").find(|e|e.accessible_id().is_some_and(|id|id=="device|claw")).unwrap();
            assert_eq!(f.activate(row,"claw","device.select","claw")["ok"],true);
        }else{f.input("device.select","claw");}
        let selected=scroll_probe(&mut f,None);assert_eq!(f.ui.get_selected_uuid(),"claw");
        let focus=profile_focus_ids(&f.ui);if actual_row{assert!(focus.iter().any(|id|id=="device|claw"),"Device row lost focus: {focus:?}");}
        assert_eq!(serde_json::to_value(&profiles).unwrap(),serde_json::to_value([f.controller.profile("host"),f.controller.profile("claw")]).unwrap(),"View navigation changed device profiles");
        f.capture(&format!("{tag}-claw-selected"));
        records.push(json!({"tag":tag,"width":width,"height":height,"dark":dark,"input":if actual_row{"actual-row"}else{"shared-action"},"before":before,"refreshed":refreshed,"selected":selected,"focus":focus,"profilesUnchanged":true}));
    }}}
    if let Some(root)=std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR"){
        let path=PathBuf::from(root).join("overview-selection.json");assert!(!path.exists());std::fs::write(path,serde_json::to_vec_pretty(&records).unwrap()).unwrap();
    }
    assert_eq!(records.len(),8);for row in records{assert!(row["selected"]["y"].as_f64().unwrap().abs()<1.,"New device retained previous overview scroll: {row}");}
}
fn profile_editor_id(f:&Flow,key:&str)->String{
    let state=f.controller.state();let meta=state["settingsCatalog"].as_array().unwrap().iter().find(|meta|meta["key"]==key).unwrap();format!("input|{}",meta["label"].as_str().unwrap())
}
fn click_profile_category(f:&mut Flow,key:&str){
    let prefix=format!("action|settings.tab|{key}|");let element=i_slint_backend_testing::ElementQuery::from_root(&f.ui).find_all().into_iter().find(|item|item.accessible_id().is_some_and(|id|id.starts_with(&prefix))).expect("Actual profile category");
    let label=element.accessible_label().unwrap().to_string();assert_eq!(f.activate(element,&label,"settings.tab",key)["ok"],true);
}

#[test]
fn automatic_bitrate_uses_real_controls_and_keeps_manual_edits_at_both_widths(){
    backend();
    fn reveal(f:&mut Flow,label:&str,role:AccessibleRole)->ElementHandle{
        for _ in 0..24{
            let viewport=ElementHandle::find_by_element_id(&f.ui,"MainWindow::form-scroll").next().unwrap();
            let area=viewport.absolute_position();
            if let Some(item)=ElementHandle::find_by_accessible_label(&f.ui,label).find(|item|item.accessible_role()==Some(role)){
                let p=item.absolute_position();let height=item.size().height;
                if p.y>=area.y && p.y+height<=area.y+viewport.size().height{return item;}
                viewport.scroll(0.,area.y+8.-p.y);
            }else{viewport.scroll(0.,-100.);}
            f.refresh();
        }
        panic!("Profile control {label} did not become visible");
    }
    for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{
        let mut f=Flow::new(width,height);if dark{f.input("appearance.toggle","");}f.input("device.settings.open","host");
        f.input("settings.input","bitrateMbps=41");
        let toggle=reveal(&mut f,"自动码率（连接时）：关闭",AccessibleRole::Button);
        assert_eq!(f.activate(toggle,"自动码率（连接时）：关闭","settings.set","bitrateAuto=toggle")["ok"],true);
        assert_eq!(f.controller.state()["draft"]["bitrateAuto"],true);assert_eq!(f.controller.state()["draft"]["bitrateMbps"],41);
        let label=f.controller.state()["settingsCatalog"].as_array().unwrap().iter().find(|meta|meta["key"]=="bitrateMbps").unwrap()["label"].as_str().unwrap().to_owned();
        let editor=reveal(&mut f,&label,AccessibleRole::TextInput);
        assert_eq!(editor.accessible_enabled(),Some(false));
        assert_eq!(f.command("settings.input","bitrateMbps=13")["ok"],false);
        f.input("settings.tab","audio");f.input("settings.tab","video");
        let toggle=reveal(&mut f,"自动码率（连接时）：开启",AccessibleRole::Button);
        assert_eq!(f.activate(toggle,"自动码率（连接时）：开启","settings.set","bitrateAuto=toggle")["ok"],true);
        assert_eq!(f.controller.state()["draft"]["bitrateAuto"].as_bool().unwrap_or(false),false);
        f.input("settings.input","bitrateMbps=unfinished");f.input("settings.set","bitrateAuto=toggle");f.input("settings.set","bitrateAuto=toggle");
        assert_eq!(f.controller.state()["settingsInputs"]["bitrateMbps"],"unfinished");assert_eq!(f.click("保存","settings.save","")["ok"],false);
        f.input("settings.set","bitrateAuto=toggle");assert_eq!(f.click("保存","settings.save","")["ok"],true);assert!(f.controller.profile("host").bitrate_auto);
        f.capture(&format!("auto-bitrate-{width}-{dark}"));
    }}
}

#[test]
fn profile_error_focus_follows_new_failures_without_stealing_background_or_category_focus(){
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{
        let mut f=Flow::new(width,height);if dark{f.input("appearance.toggle","");}f.input("device.settings.open","host");f.input("settings.tab","audio");f.input("settings.input","volume=invalid");let before=std::fs::read(&f.storage).unwrap();
        assert_eq!(f.click("保存","settings.save","")["ok"],false);let id=profile_editor_id(&f,"volume");assert!(profile_focus_ids(&f.ui).contains(&id),"Invalid editor did not receive focus");
        click_profile_category(&mut f,"audio");let selected=profile_focus_ids(&f.ui);assert!(!selected.contains(&id));assert!(selected.iter().any(|id|id.starts_with("action|settings.tab|audio|")));
        click_profile_category(&mut f,"video");click_profile_category(&mut f,"audio");assert!(!profile_focus_ids(&f.ui).contains(&id),"Recreated editor consumed an old focus request");
        let devices=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();f.controller.replace_devices(devices);f.refresh();assert!(!profile_focus_ids(&f.ui).contains(&id),"Background device update stole category focus");
        assert_eq!(f.click("保存","settings.save","")["ok"],false);assert!(profile_focus_ids(&f.ui).contains(&id),"Repeated failure did not refocus the editor");
        assert_eq!(f.controller.state()["settingsInputs"]["volume"],"invalid");assert_eq!(std::fs::read(&f.storage).unwrap(),before);
    }}
}

#[test]
fn profile_error_focus_after_save_and_leave_overrides_the_old_content_target(){
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let mut f=Flow::new(1180.,780.);f.input("device.settings.open","host");f.input("settings.tab","audio");f.input("settings.input","fps=invalid");let before=std::fs::read(&f.storage).unwrap();
    f.click("设置","app.settings.open","");assert!(f.controller.state()["navigationPrompt"].is_object());assert_eq!(f.click("保存并继续","navigation.resolve","save")["ok"],false);
    assert!(f.controller.state()["navigationPrompt"].is_null());assert_eq!(f.controller.state()["profileTab"],"video");assert!(profile_focus_ids(&f.ui).contains(&profile_editor_id(&f,"fps")));assert_eq!(std::fs::read(&f.storage).unwrap(),before);
}

#[test]
fn profile_error_focus_reaches_the_address_editor_after_validation_switches_categories(){
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let mut f=Flow::new(1180.,780.);f.input("device.settings.open","host");f.input("settings.network.edit","address=https://not-a-host/path");let before=std::fs::read(&f.storage).unwrap();
    assert_eq!(f.click("保存","settings.save","")["ok"],false);assert_eq!(f.controller.state()["profileTab"],"network");assert!(profile_focus_ids(&f.ui).contains(&"input|首选 IP 或主机名".to_string()));assert_eq!(std::fs::read(&f.storage).unwrap(),before);
}

#[test]
fn profile_error_focus_reveals_every_numeric_editor_and_relay_within_the_form_viewport(){
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let catalog={let f=Flow::new(1180.,780.);f.controller.state()["settingsCatalog"].as_array().unwrap().iter().filter(|field|field["type"]=="number"&&field["platforms"].as_array().is_none_or(|p|p.iter().any(|p|p=="windows"))).map(|field|field["key"].as_str().unwrap().to_string()).collect::<Vec<_>>()};assert!(!catalog.is_empty());
    for(width,height)in[(720.,560.),(1180.,780.)]{for key in catalog.iter().map(String::as_str).chain(["relayUrl"]){
        let mut f=Flow::new(width,height);f.input("device.settings.open","host");
        let id=if key=="relayUrl"{f.input("settings.network.edit","relayUrl=invalid-relay");"input|中继服务器".to_string()}else{f.input("settings.input",&format!("{key}=invalid"));profile_editor_id(&f,key)};
        assert_eq!(f.click("保存","settings.save","")["ok"],false);assert!(profile_focus_ids(&f.ui).contains(&id),"{width}/{key} not focused");
        let editor=i_slint_backend_testing::ElementQuery::from_root(&f.ui).find_all().into_iter().find(|item|item.accessible_id().is_some_and(|value|value==id)).expect("Focused error editor");
        let viewport=ElementHandle::find_by_element_id(&f.ui,"MainWindow::form-scroll").next().unwrap();
        let mut positions = Vec::new();
        let mut fully_visible = false;
        for _ in 0..=10 {
            let p = editor.absolute_position();
            let size = editor.size();
            let area = viewport.absolute_position();
            let bounds = viewport.size();
            positions.push((p, size, area, bounds));
            if p.y >= area.y - 1. && p.y + size.height <= area.y + bounds.height + 1. {
                fully_visible = true;
                break;
            }
            i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
            f.ui.window().take_snapshot().unwrap();
        }
        assert!(fully_visible,"{width}/{key}: focused editor remained clipped after layout settled: {positions:?}");
        if key == "relayUrl" && width == 720. {
            let revealed_y = editor.absolute_position().y;
            viewport.scroll(0., 100000.);
            i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(500));
            f.ui.window().take_snapshot().unwrap();
            let user_y = editor.absolute_position().y;
            assert!(user_y > revealed_y + 20., "User could not scroll away from the error");
            f.refresh();
            i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(500));
            f.ui.window().take_snapshot().unwrap();
            assert!((editor.absolute_position().y - user_y).abs() < 1., "Error reveal fought user scrolling");
        }
    }}
}
impl Default for Boundary {
    fn default() -> Self {
        Self {
            updates: Mutex::new(json!({"managed":false,"phase":"idle"})),
            update_gate: Mutex::new(None),
            sync_gate: Mutex::new(None),
            sync: Mutex::new(
                json!({"url":"https://example.test/dav/","username":"fixture","hasPassword":true,"automatic":false,"intervalMinutes":15}),
            ),
            fail_save: AtomicBool::new(false),
            save_failure: Mutex::new(None),
            calls: Mutex::new(Vec::new()),
        }
    }
}
impl Platform for Boundary {
    fn cancel_update(&self,_:u64)->Result<(),String>{self.calls.lock().unwrap().push("updates.cancel".into());Ok(())}
    fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        match id {
            "sync.state" => Ok(self.sync.lock().unwrap().clone()),
            "updates.state" => Ok(self.updates.lock().unwrap().clone()),
            "updates.prepare" => {
                self.calls.lock().unwrap().push(id.into());
                if let Some((entered, released)) = self.update_gate.lock().unwrap().take() {
                    entered.send(()).unwrap();
                    released.recv_timeout(Duration::from_secs(5)).unwrap();
                }
                let ready = json!({"managed":true,"phase":"downloaded","availableVersion":"9.0.0","pendingRestart":true,"status":"更新已下载，可安装更新"});
                *self.updates.lock().unwrap() = ready.clone();
                Ok(ready)
            }
            "updates.install" => {
                self.calls.lock().unwrap().push(id.into());
                let applying = json!({"managed":true,"phase":"applying","availableVersion":"9.0.0","pendingRestart":true});
                *self.updates.lock().unwrap() = applying.clone();
                Ok(applying)
            }
            "devices.restore" => Ok(json!({"devices":args["devices"]})),
            "sync.run" => {
                self.calls.lock().unwrap().push(id.into());
                if let Some((entered, released)) = self.sync_gate.lock().unwrap().take() {
                    entered.send(()).unwrap();
                    released.recv_timeout(Duration::from_secs(5)).unwrap();
                }
                Err("fixture transfer ended".into())
            }
            "sync.configure" => {
                self.calls.lock().unwrap().push(id.into());
                if self.fail_save.load(Ordering::SeqCst) {
                    return Err(self.save_failure.lock().unwrap().clone().unwrap_or_else(||"WebDAV 地址无效".into()));
                }
                let mut saved = args.clone();
                saved.as_object_mut().unwrap().remove("password");
                saved["hasPassword"] = json!(true);
                *self.sync.lock().unwrap() = saved.clone();
                Ok(saved)
            }
            "sync.clear" => {
                self.calls.lock().unwrap().push(id.into());
                *self.sync.lock().unwrap() = json!({"url":"","username":"","hasPassword":false,"automatic":false,"intervalMinutes":5});
                Ok(self.sync.lock().unwrap().clone())
            }
            _ => Err(format!("Unexpected external operation in view flow: {id}")),
        }
    }
}

#[test]
fn update_cancel_uses_the_visible_operation_and_keeps_controls_busy_until_cleanup(){
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    for dark in [false,true] {for(width,height)in[(720.,560.),(1180.,780.)]{
        let boundary=Arc::new(Boundary::default());*boundary.updates.lock().unwrap()=json!({"managed":true,"phase":"available","availableVersion":"9.0.0"});
        let (entered,started)=std::sync::mpsc::channel();let (release,wait)=std::sync::mpsc::channel();*boundary.update_gate.lock().unwrap()=Some((entered,wait));
        let mut f=Flow::with_boundary(width,height,boundary.clone());if f.controller.state()["saved"]["dark"]!=dark{f.input("appearance.toggle","");}f.input("app.settings.tab","updates");
        let controller=f.controller.clone();let worker=std::thread::spawn(move||controller.dispatch("updates.primary",json!({})));
        started.recv_timeout(Duration::from_secs(3)).unwrap();f.refresh();let operation=f.controller.state()["updates"]["operationId"].as_u64().unwrap().to_string();
        f.capture(&format!("cancel-ready-{width}-{dark}"));let result=f.click("取消","updates.cancel",&operation);assert_eq!(result["ok"],true,"{result}");f.capture(&format!("cancel-cleaning-{width}-{dark}"));
        assert_eq!(f.controller.state()["updates"]["phase"],"cancelling");assert!(f.ui.get_updates_busy());assert!(f.ui.get_update_cancelling());assert_eq!(f.ui.global::<crate::Palette>().get_dark(),dark);
        let label=f.ui.get_update_primary_text();let item=ElementHandle::find_by_accessible_label(&f.ui,label.as_str()).find(|item|item.accessible_id().is_some_and(|id|id.starts_with("action|updates.primary|"))).unwrap();
        assert_eq!(item.accessible_enabled(),Some(false));
        release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);f.refresh();assert!(!f.ui.get_updates_busy());assert_eq!(f.controller.state()["updates"]["phase"],"cancelled");
        assert!(!f.ui.get_update_can_cancel());assert!(!f.ui.get_update_cancelling());assert!(f.controller.take_window_action().is_empty());assert_eq!(&*boundary.calls.lock().unwrap(),&["updates.prepare","updates.cancel"]);
    }}
}
struct Flow {
    ui: MainWindow,
    view: MainView,
    controller: Arc<Controller>,
    boundary: Arc<Boundary>,
    pending: Rc<RefCell<Vec<(String, String)>>>,
    directory: PathBuf,
    storage: PathBuf,
}
impl Flow {
    fn new(width: f32, height: f32) -> Self {
        Self::with_boundary(width, height, Arc::new(Boundary::default()))
    }
    fn with_boundary(width: f32, height: f32, boundary: Arc<Boundary>) -> Self {
        Self::with_platform(width,height,boundary.clone(),boundary)
    }
    fn with_platform(width:f32,height:f32,boundary:Arc<Boundary>,platform:Arc<dyn Platform>)->Self {
        let directory =
            std::env::temp_dir().join(format!("rhremote-view-flow-{}", random_token().unwrap()));
        std::fs::create_dir(&directory).unwrap();
        let storage = directory.join("preferences.json");
        let source = Profile {
            width: 1600,
            height: 900,
            bitrate_mbps: 35,
            ..Default::default()
        };
        let saved = Saved {
            client_id: "fixture-windows".into(),
            profile_schema_version: 2,
            device_order_initialized: true,
            clients: BTreeMap::from([
                (
                    "fixture-windows".into(),
                    sync_model::ClientInfo {
                        name: "Windows".into(),
                        platform: "windows".into(),
                    },
                ),
                (
                    "fixture-pad".into(),
                    sync_model::ClientInfo {
                        name: "平板".into(),
                        platform: "android".into(),
                    },
                ),
            ]),
            client_profiles: BTreeMap::from([(
                "fixture-pad".into(),
                BTreeMap::from([(
                    "host".into(),
                    sync_model::ClientProfile::from_profile(&source),
                )]),
            )]),
            ..Default::default()
        };
        std::fs::write(&storage, serde_json::to_vec(&saved).unwrap()).unwrap();
        let controller = Controller::new("windows", storage.clone(), platform).unwrap();
        let devices:Vec<Device>=serde_json::from_value(json!([
            {"uuid":"host","name":"winserver","address":"192.0.2.1","addresses":["100.64.0.1"],"online":true,"paired":true},
            {"uuid":"self","name":"Local PC","address":"192.0.2.2","online":true,"paired":true,"isSelf":true},
            {"uuid":"claw","name":"claw","address":"192.0.2.3","online":false,"paired":true}
        ])).unwrap();
        controller.replace_devices(devices);
        let ui = MainWindow::new().unwrap();
        ui.window().set_size(LogicalSize::new(width, height));
        ui.set_app_version(env!("CARGO_PKG_VERSION").into());
        let view = MainView::new(&ui);
        let pending = Rc::new(RefCell::new(Vec::new()));
        let queue = pending.clone();
        ui.global::<Commands>()
            .on_trigger(move |id, arg| queue.borrow_mut().push((id.to_string(), arg.to_string())));
        ui.show().unwrap();
        let mut flow = Self {
            ui,
            view,
            controller,
            boundary,
            pending,
            directory,
            storage,
        };
        flow.refresh();
        flow
    }
    fn refresh(&mut self) {
        self.view.refresh(&self.ui, &self.controller.state(), |id| {
            self.controller.profile(id)
        });
        i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
        // Materialize the actual conditional controls before querying or interacting.
        self.ui.window().take_snapshot().unwrap();
        assert_eq!(
            self.ui.get_page().as_str(),
            self.controller.state()["page"].as_str().unwrap()
        );
    }
    fn command(&mut self, id: &str, arg: &str) -> Value {
        let state = self.controller.state();
        let result = self.controller.dispatch(id, args_for(id, arg, &state));
        self.refresh();
        result
    }
    fn input(&mut self, id: &str, arg: &str) {
        let result = self.command(id, arg);
        assert_eq!(result["ok"], true, "{id}: {result}");
    }
    fn button(&self, label: &str, id: &str, arg: &str) -> ElementHandle {
        let intent = self.ui.get_update_intent();
        let diagnostic=match id{"device.network.diagnose"=>self.ui.get_diagnostic_start_args(),"device.network.cancel"=>self.ui.get_diagnostic_cancel_args(),"device.network.details.toggle"=>self.ui.get_diagnostic_details_args(),_=>"".into()};
        let arg=if arg.is_empty()&&id.starts_with("device.network."){diagnostic.as_str()}else if id=="updates.primary"&&arg.is_empty(){intent.as_str()}else{arg};
        let action = format!("action|{id}|{arg}|");
        let controls: Vec<_> = ElementHandle::find_by_accessible_label(&self.ui, label)
            .filter(|e| {
                e.accessible_role() == Some(AccessibleRole::Button)
                    && e.accessible_id().is_some_and(|value| {
                        value.starts_with(&action)
                            || arg.is_empty() && value == format!("nav|{id}").as_str()
                    })
            })
            .collect();
        assert_eq!(controls.len(), 1, "Expected one button {label}");
        controls.into_iter().next().unwrap()
    }
    fn click(&mut self, label: &str, id: &str, arg: &str) -> Value {
        let element = self.button(label, id, arg);
        self.activate(element, label, id, arg)
    }
    fn choose_device(&mut self, uuid: &str) -> Value {
        let key = format!("device|{uuid}");
        let element = ElementHandle::find_by_accessible_label(
            &self.ui,
            if uuid == "host" {
                "选择设备 winserver"
            } else {
                "选择设备 claw"
            },
        )
        .find(|e| e.accessible_id().is_some_and(|id| id == key.as_str()))
        .unwrap();
        self.activate(element, uuid, "device.settings.open", uuid)
    }
    fn activate(&mut self, element: ElementHandle, label: &str, id: &str, arg: &str) -> Value {
        let (id, arg) = self.emit(element, label, id, arg);
        self.command(&id, &arg)
    }
    fn emit(
        &mut self,
        element: ElementHandle,
        label: &str,
        id: &str,
        arg: &str,
    ) -> (String, String) {
        let intent = self.ui.get_update_intent();
        let diagnostic=match id{"device.network.diagnose"=>self.ui.get_diagnostic_start_args(),"device.network.cancel"=>self.ui.get_diagnostic_cancel_args(),"device.network.details.toggle"=>self.ui.get_diagnostic_details_args(),_=>"".into()};
        let arg=if arg.is_empty()&&id.starts_with("device.network."){diagnostic.as_str()}else if id=="updates.primary"&&arg.is_empty(){intent.as_str()}else{arg};
        let p = element.absolute_position();
        let s = element.size();
        let window = self.ui.window().size();
        assert!(
            p.x >= 0.
                && p.y >= 0.
                && p.x + s.width <= window.width as f32
                && p.y + s.height <= window.height as f32,
            "{label} outside window: {p:?} {s:?}"
        );
        assert!(self.pending.borrow().is_empty());
        let position = LogicalPosition::new(p.x + s.width / 2., p.y + s.height / 2.);
        for event in [
            WindowEvent::PointerPressed {
                position,
                button: PointerEventButton::Left,
            },
            WindowEvent::PointerReleased {
                position,
                button: PointerEventButton::Left,
            },
        ] {
            self.ui.window().dispatch_event(event);
        }
        let emitted = std::mem::take(&mut *self.pending.borrow_mut());
        assert_eq!(
            emitted,
            vec![(id.to_string(), arg.to_string())],
            "Wrong or duplicate shared action for {label}"
        );
        emitted.into_iter().next().unwrap()
    }
    fn capture(&self, name: &str) {
        if let Some(root) = std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR") {
            let frame = self.ui.window().take_snapshot().unwrap();
            let path = PathBuf::from(root).join(format!("flow-{name}.png"));
            assert!(!path.exists(), "Never replace earlier evidence");
            image::save_buffer(
                path,
                frame.as_bytes(),
                frame.width(),
                frame.height(),
                image::ColorType::Rgba8,
            )
            .unwrap();
        }
    }
    fn saved(&self) -> Vec<u8> {
        std::fs::read(&self.storage).unwrap()
    }
}
impl Drop for Flow {
    fn drop(&mut self) {
        let _ = self.ui.hide();
        let root = std::env::temp_dir().canonicalize().unwrap();
        let target = self.directory.canonicalize().unwrap();
        assert!(
            target.parent() == Some(root.as_path())
                && target
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .starts_with("rhremote-view-flow-")
        );
        std::fs::remove_dir_all(target).unwrap();
    }
}
fn backend() {
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
        mock_time: true,
        threading: false,
        renderer_name: Some("software".into()),
    })))
    .unwrap();
}

#[test]
fn controller_bound_forms_save_validate_cancel_and_copy_through_real_controls() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        let mut f = Flow::new(width, height);
        f.input("devices.filter", "100.64.0.1");
        assert_eq!(f.ui.get_devices().row_count(), 1);
        assert_eq!(f.ui.get_selected_uuid(), "host");
        f.click("连接设置", "device.settings.open", "host");
        let original = f.saved();
        f.input("settings.input", "fps=unfinished");
        f.click("网络", "settings.tab", "network");
        f.input("settings.network.edit", "addresses=100.64.0.1, 192.0.2.9");
        assert_eq!(f.saved(), original);
        assert_eq!(f.click("保存", "settings.save", "")["ok"], false);
        assert_eq!(f.ui.get_profile_tab(), "video");
        assert!(!f.ui.get_settings_error().is_empty());
        assert_eq!(f.controller.state()["settingsInputs"]["fps"], "unfinished");
        assert_eq!(f.saved(), original);
        f.capture(&format!("{tag}-invalid-profile"));
        f.click("概览", "device.select", "host");
        assert!(f.ui.get_navigation_prompt());
        f.click("继续编辑", "navigation.resolve", "stay");
        assert!(!f.ui.get_navigation_prompt());
        f.input("settings.input", "fps=24");
        f.click("概览", "device.select", "host");
        f.click("保存并继续", "navigation.resolve", "save");
        assert_eq!(f.ui.get_page(), "devices");
        assert_eq!(f.ui.get_device_query(), "100.64.0.1");
        assert_eq!(f.controller.profile("host").fps, 24);
        assert_eq!(
            f.controller.state()["saved"]["devices"][0]["addresses"],
            json!(["100.64.0.1", "192.0.2.9"])
        );
        let saved = f.saved();
        f.click("连接设置", "device.settings.open", "host");
        f.click("高级", "settings.tab", "advanced");
        f.click("从其他客户端复制配置", "settings.sources.toggle", "");
        f.click("查看配置", "settings.copy.preview", "fixture-pad");
        assert_eq!(f.ui.get_page(), "profile-copy");
        assert!(f.ui.get_copy_source().starts_with("平板 ·"));
        f.capture(&format!("{tag}-copy-preview"));
        f.click("复制到草稿", "settings.copy.apply", "");
        assert_eq!(f.ui.get_page(), "settings");
        assert!(f.ui.get_settings_dirty());
        assert_eq!(f.ui.get_profile_width(), 1600);
        assert_eq!(f.controller.profile("host").fps, 24);
        assert_eq!(f.saved(), saved);
        f.click("取消", "settings.cancel", "");
        assert_eq!(f.saved(), saved);
        f.click("连接设置", "device.settings.open", "host");
        f.input("settings.input", "fps=25");
        f.click("概览", "device.select", "host");
        f.click("放弃更改", "navigation.resolve", "discard");
        assert_eq!(f.saved(), saved);
        assert_eq!(f.ui.get_selected_uuid(), "host");
        let restarted = Controller::new("windows", f.storage.clone(), f.boundary.clone()).unwrap();
        assert_eq!(restarted.profile("host").fps, 24);
        f.capture(&format!("{tag}-restored-devices"));
        f.input("devices.filter", "");
        assert_eq!(f.ui.get_devices().row_count(), 3);
        assert_eq!(f.ui.get_selected_uuid(), "host");
        assert_eq!(f.saved(), saved);
        f.capture(&format!("{tag}-all-devices"));
    }
}

#[test]
fn controller_bound_sync_keeps_failed_input_and_separates_cancel_from_remove() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        let mut f = Flow::new(width, height);
        f.input("app.settings.tab", "sync");
        let before = f.boundary.sync.lock().unwrap().clone();
        let prefs = f.saved();
        f.input("sync.edit", "url=http://example.test/dav/new/");
        f.input("sync.edit", "password=fixture=only");
        f.input("sync.edit", "intervalMinutes=60");
        assert!(!f.controller.state().to_string().contains("fixture=only"));
        f.click("更新", "app.settings.tab", "updates");
        assert!(f.ui.get_navigation_prompt());
        f.click("继续编辑", "navigation.resolve", "stay");
        f.boundary.fail_save.store(true, Ordering::SeqCst);
        assert_eq!(f.click("保存", "sync.save", "")["ok"], false);
        assert_eq!(f.ui.get_sync_url(), "http://example.test/dav/new/");
        assert_eq!(f.ui.get_sync_error(), "WebDAV 地址无效");
        assert_eq!(*f.boundary.sync.lock().unwrap(), before);
        assert_eq!(f.saved(), prefs);
        f.capture(&format!("{tag}-sync-failed"));
        f.click("取消", "sync.cancel", "");
        assert_eq!(f.ui.get_sync_url(), "https://example.test/dav/");
        assert!(!f.ui.get_sync_dirty());
        assert!(f.ui.get_sync_error().is_empty());
        f.input("sync.edit", "username=unfinished");
        f.click("移除同步设置", "sync.clear", "");
        assert!(f.ui.get_sync_clear_prompt());
        f.click("取消", "sync.clear.resolve", "cancel");
        assert!(!f.ui.get_sync_clear_prompt());
        assert_eq!(f.ui.get_sync_username(), "unfinished");
        assert_eq!(*f.boundary.sync.lock().unwrap(), before);
        f.boundary.fail_save.store(false, Ordering::SeqCst);
        f.click("保存", "sync.save", "");
        assert!(!f.ui.get_sync_dirty());
        assert_eq!(f.boundary.sync.lock().unwrap()["username"], "unfinished");
        f.capture(&format!("{tag}-sync-saved"));
        assert_eq!(
            f.saved(),
            prefs,
            "Sync credentials must not enter shared preferences"
        );
    }
}

#[test]
fn long_save_errors_keep_the_body_and_actions_visible_and_full_details_scrollable() {
    backend();
    fn probe(ui:&MainWindow,id:&str,delta:Option<f32>)->Value {
        let rows=Rc::new(RefCell::new(Vec::new()));let saved=rows.clone();let key=id.to_string();let test=ui.global::<crate::UiTest>();test.set_enabled(true);
        test.on_report(move|id,kind,_,_,_,_,_,width,height,y,maximum|{if id==key.as_str()&&kind=="scroll"{saved.borrow_mut().push(json!({"width":width,"height":height,"y":y,"maximum":maximum}));}});
        if let Some(delta)=delta{test.set_operation("scroll".into());test.set_target(id.into());test.set_scroll_delta(delta);test.set_generation(test.get_generation()+1);ui.window().take_snapshot().unwrap();}
        test.set_operation("inspect".into());test.set_generation(test.get_generation()+1);ui.window().take_snapshot().unwrap();
        let rows=rows.borrow();assert_eq!(rows.len(),1,"Missing active viewport {id}");rows[0].clone()
    }
    let message=(0..80).map(|n|format!("第 {n} 段：WebDAV 保存失败，服务端返回了较长的错误说明。请检查目录和账户设置。\n")).collect::<String>()+"END-OF-ERROR-184";
    let mut cases=Vec::new();
    for(width,height)in[(720.,560.),(1180.,780.)]{for dark in [false,true]{for kind in ["profile","sync"]{
        let boundary=Arc::new(Boundary::default());let mut f=Flow::with_boundary(width,height,boundary.clone());if f.ui.get_dark()!=dark{f.input("appearance.toggle","");}
        let original=f.saved();
        if kind=="sync" {
            f.input("app.settings.tab","sync");f.input("sync.edit","username=unfinished-184");boundary.fail_save.store(true,Ordering::SeqCst);*boundary.save_failure.lock().unwrap()=Some(message.clone());
            assert_eq!(f.click("保存","sync.save","")["ok"],false);assert_eq!(f.ui.get_sync_error(),message.as_str());assert!(f.ui.get_sync_dirty());
        }else{
            f.input("device.settings.open","host");f.input("settings.input","fps=invalid");assert_eq!(f.click("保存","settings.save","")["ok"],false);
            // Controlled OS-write detail at the view boundary; the actual validation draft remains owned by Controller.
            f.ui.set_settings_error(message.clone().into());f.ui.window().take_snapshot().unwrap();
        }
        let body=ElementHandle::find_by_element_id(&f.ui,"MainWindow::form-scroll").next().unwrap();let save=f.button("保存",if kind=="sync"{"sync.save"}else{"settings.save"},"");let position=save.absolute_position();let size=save.size();
        let usable=body.size().height>=160.&&position.y>=0.&&position.y+size.height<=height+1.;let name=format!("{kind}-{width}-{dark}");f.capture(&format!("error184-{name}-top"));
        let mut row=json!({"name":name,"kind":kind,"width":width,"height":height,"bodyHeight":body.size().height,"saveY":position.y,"saveHeight":size.height,"usable":usable});
        if usable {
            let id=if kind=="sync"{"scroll|sync-error"}else{"scroll|profile-error"};let before=probe(&f.ui,id,None);let after=probe(&f.ui,id,Some(-1_000_000.));
            assert!(before["height"].as_f64().unwrap()<=80.1);assert!(before["maximum"].as_f64().unwrap()>100.);assert!((after["y"].as_f64().unwrap()+after["maximum"].as_f64().unwrap()).abs()<1.);
            assert_eq!(f.saved(),original);assert_eq!(if kind=="sync"{f.ui.get_sync_error()}else{f.ui.get_settings_error()},message.as_str());f.capture(&format!("error184-{name}-bottom"));
            row["errorBefore"]=before;row["errorAfter"]=after;row["fullErrorPreserved"]=json!(true);
            f.click("取消",if kind=="sync"{"sync.cancel"}else{"settings.cancel"},"");assert_eq!(f.saved(),original);
            if kind=="sync"{assert!(!f.ui.get_sync_dirty());assert!(f.ui.get_sync_error().is_empty());assert_eq!(f.ui.get_sync_username(),"fixture");boundary.fail_save.store(false,Ordering::SeqCst);f.input("sync.edit","username=saved-184");assert_eq!(f.click("保存","sync.save","")["ok"],true);assert_eq!(boundary.sync.lock().unwrap()["username"],"saved-184");}
            else{assert_eq!(f.controller.state()["page"],"devices");assert_eq!(f.controller.state()["selected"],"host");}
            row["actualCancelAndRecovery"]=json!(true);f.capture(&format!("error184-{name}-recovered"));
        }
        cases.push(row);
    }}}
    if let Some(out)=std::env::var_os("RHREMOTE_FLOW_CAPTURE_DIR"){std::fs::write(PathBuf::from(out).join("error184-results.json"),serde_json::to_vec_pretty(&json!({"cases":cases,"scope":"Software backend, actual Controller and controls; sync error via owned Platform boundary, profile long detail injected into view; no OS windows/input or real remote/WebDAV"})).unwrap()).unwrap();}
    assert_eq!(cases.len(),8);for row in cases{assert_eq!(row["usable"],true,"Long error collapsed the settings body or hid Save: {row}");}
}

#[test]
fn save_error_keyboard_navigation_keeps_details_readable_without_moving_the_form() {
    backend();
    fn sample(ui:&MainWindow,id:&str,operation:&str)->Value{
        let rows=Rc::new(RefCell::new(Vec::new()));let saved=rows.clone();let key=id.to_string();let test=ui.global::<crate::UiTest>();test.set_enabled(true);
        test.on_report(move|id,kind,_,_,focused,_,_,_,height,y,maximum|{if id==key.as_str()&&kind=="scroll"{saved.borrow_mut().push(json!({"focused":focused,"height":height,"y":y,"maximum":maximum}));}});
        if operation!="inspect"{test.set_operation(operation.into());test.set_target(id.into());test.set_generation(test.get_generation()+1);ui.window().take_snapshot().unwrap();}
        test.set_operation("inspect".into());test.set_generation(test.get_generation()+1);ui.window().take_snapshot().unwrap();let rows=rows.borrow();assert_eq!(rows.len(),1);rows[0].clone()
    }
    let mut cases=Vec::new();let message="保存失败的详细原因，请检查目录和账户设置。\n".repeat(80)+"END-OF-KEYBOARD-184";
    for(width,height)in[(720.,560.),(1180.,780.)]{for dark in [false,true]{for kind in ["profile","sync"]{
        let mut f=Flow::new(width,height);if f.ui.get_dark()!=dark{f.input("appearance.toggle","");}
        let id=if kind=="sync"{f.input("app.settings.tab","sync");f.ui.set_sync_error(message.clone().into());"scroll|sync-error"}else{f.input("device.settings.open","host");f.ui.set_settings_error(message.clone().into());"scroll|profile-error"};
        f.ui.window().take_snapshot().unwrap();let form=ElementHandle::find_by_element_id(&f.ui,"MainWindow::form-scroll").next().unwrap();let form_position=form.absolute_position();let form_size=form.size();let saved=f.saved();
        assert!(sample(&f.ui,id,"focus")["focused"].as_bool().unwrap());let mut keys=Vec::new();
        for key in [slint::platform::Key::DownArrow,slint::platform::Key::UpArrow,slint::platform::Key::PageDown,slint::platform::Key::PageUp,slint::platform::Key::End,slint::platform::Key::Home]{
            f.ui.window().dispatch_event(WindowEvent::KeyPressed{text:key.into()});f.ui.window().dispatch_event(WindowEvent::KeyReleased{text:key.into()});f.ui.window().take_snapshot().unwrap();keys.push(sample(&f.ui,id,"inspect"));
        }
        assert!(keys[0]["y"].as_f64().unwrap()<0.);assert!(keys[1]["y"].as_f64().unwrap().abs()<1.);assert!(keys[2]["y"].as_f64().unwrap()< -32.);assert!(keys[3]["y"].as_f64().unwrap().abs()<1.);
        assert!((keys[4]["y"].as_f64().unwrap()+keys[4]["maximum"].as_f64().unwrap()).abs()<1.);assert!(keys[5]["y"].as_f64().unwrap().abs()<1.);
        assert_eq!(form.absolute_position(),form_position);assert_eq!(form.size(),form_size);assert_eq!(f.saved(),saved);
        // A new error starts at the top; repeated reads of the same error do not reset user scrolling.
        f.ui.window().dispatch_event(WindowEvent::KeyPressed{text:slint::platform::Key::End.into()});f.ui.window().dispatch_event(WindowEvent::KeyReleased{text:slint::platform::Key::End.into()});f.ui.window().take_snapshot().unwrap();let bottom=sample(&f.ui,id,"inspect");
        if kind=="sync"{f.ui.set_sync_error(message.clone().into());}else{f.ui.set_settings_error(message.clone().into());}f.ui.window().take_snapshot().unwrap();assert_eq!(sample(&f.ui,id,"inspect")["y"],bottom["y"]);
        f.capture(&format!("error184-keyboard-{kind}-{width}-{dark}-bottom"));
        if kind=="sync"{f.ui.set_sync_error((message.clone()+" changed").into());}else{f.ui.set_settings_error((message.clone()+" changed").into());}f.ui.window().take_snapshot().unwrap();assert!(sample(&f.ui,id,"inspect")["y"].as_f64().unwrap().abs()<1.);
        cases.push(json!({"kind":kind,"width":width,"height":height,"dark":dark,"keyStates":keys,"sameMessageKeepsScroll":true,"newMessageStartsAtTop":true,"formUnchanged":true}));
    }}}
    if let Some(out)=std::env::var_os("RHREMOTE_FLOW_CAPTURE_DIR"){std::fs::write(PathBuf::from(out).join("error184-keyboard-results.json"),serde_json::to_vec_pretty(&json!({"cases":cases,"scope":"Slint own window event dispatch; no OS keyboard injection or physical keyboard claim"})).unwrap()).unwrap();}
    assert_eq!(cases.len(),8);
}

#[test]
fn changing_category_starts_at_the_top_and_retains_the_controller_draft() {
    backend();
    let mut f = Flow::new(720., 560.);
    f.click("连接设置", "device.settings.open", "host");
    f.input("settings.input", "fps=24");
    assert!(
        ElementHandle::find_by_accessible_label(&f.ui, "画面宽度")
            .next()
            .is_some()
    );
    let form = ElementHandle::find_by_element_id(&f.ui, "MainWindow::form-scroll")
        .next()
        .unwrap();
    form.scroll(0., -100000.);
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
    f.refresh();
    assert!(
        ElementHandle::find_by_accessible_label(&f.ui, "画面宽度")
            .next()
            .is_none(),
        "The fixture must actually scroll the first field outside the viewport"
    );
    f.input("settings.input", "fps=25");
    assert!(
        ElementHandle::find_by_accessible_label(&f.ui, "画面宽度")
            .next()
            .is_none(),
        "Editing the current draft must retain its scroll position"
    );
    f.capture("compact-scrolled-video");
    f.click("网络", "settings.tab", "network");
    assert_eq!(f.ui.get_profile_tab(), "network");
    f.capture("compact-network-category");
    let editor_before = ElementHandle::find_by_accessible_label(&f.ui, "首选 IP 或主机名").next();
    let address = match editor_before {
        Some(editor) => editor,
        None => {
            form.scroll(0., 100000.);
            f.refresh();
            assert!(
                ElementHandle::find_by_accessible_label(&f.ui, "首选 IP 或主机名")
                    .next()
                    .is_some(),
                "The network category did not create its address editor"
            );
            f.capture("compact-network-manually-scrolled-top");
            panic!(
                "The new category retained the old scroll offset; scrolling back to the top restored its address editor"
            );
        }
    };
    assert!(
        address.absolute_position().y >= form.absolute_position().y,
        "New category retained the previous scroll offset: address {:?}, form {:?}",
        address.absolute_position(),
        form.absolute_position()
    );
    assert_eq!(f.controller.state()["settingsInputs"]["fps"], "25");
    f.click("画面", "settings.tab", "video");
    assert!(
        ElementHandle::find_by_accessible_label(&f.ui, "画面宽度")
            .next()
            .is_some()
    );
    assert_eq!(f.controller.state()["settingsInputs"]["fps"], "25");
}

#[test]
fn device_settings_keep_the_list_and_guard_cross_device_drafts() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        for dark in [false, true] {
            let mut f = Flow::new(width, height);
            f.ui.global::<crate::Palette>().set_dark(dark);
            f.click("连接设置", "device.settings.open", "host");
            f.click("音频", "settings.tab", "audio");
            let before = f.saved();
            f.input("settings.input", "volume=42");
            f.choose_device("claw");
            assert!(f.ui.get_navigation_prompt());
            f.click("继续编辑", "navigation.resolve", "stay");
            assert_eq!(f.ui.get_selected_uuid(), "host");
            assert_eq!(f.controller.state()["settingsInputs"]["volume"], "42");
            assert_eq!(f.saved(), before);
            f.choose_device("claw");
            f.click("放弃更改", "navigation.resolve", "discard");
            assert_eq!(f.ui.get_page(), "settings");
            assert_eq!(f.ui.get_selected_uuid(), "claw");
            assert_eq!(f.ui.get_profile_tab(), "audio");
            assert!(!f.ui.get_settings_dirty());
            assert_eq!(f.saved(), before);
            f.capture(&format!("navigation-{tag}-{dark}-claw-audio"));
            f.input("settings.input", "volume=75");
            f.choose_device("host");
            f.click("保存并继续", "navigation.resolve", "save");
            assert_eq!(f.ui.get_selected_uuid(), "host");
            assert_eq!(f.ui.get_profile_tab(), "audio");
            assert_eq!(f.controller.profile("claw").volume, 75);
            assert_eq!(f.controller.profile("host").volume, 100);
            let saved = f.saved();
            f.input("settings.input", "volume=unfinished");
            f.choose_device("claw");
            assert_eq!(
                f.click("保存并继续", "navigation.resolve", "save")["ok"],
                false
            );
            assert_eq!(f.ui.get_selected_uuid(), "host");
            assert_eq!(
                f.controller.state()["settingsInputs"]["volume"],
                "unfinished"
            );
            assert!(!f.ui.get_settings_error().is_empty());
            assert_eq!(f.saved(), saved);
            f.click("概览", "device.select", "host");
            f.click("放弃更改", "navigation.resolve", "discard");
            assert_eq!(f.ui.get_page(), "devices");
            assert_eq!(f.ui.get_selected_uuid(), "host");
            assert_eq!(f.saved(), saved);
        }
    }
}

#[test]
fn page_back_buttons_stay_on_the_left_and_restore_device_context() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        for dark in [false, true] {
            let mut f = Flow::new(width, height);
            f.ui.global::<crate::Palette>().set_dark(dark);
            f.input("devices.filter", "100.64.0.1");
            let before = f.saved();
            for (open, back, back_id, list) in [
                ("device.info.open", "返回设备", "navigation.back", true),
                ("device.add.open", "返回设备", "device.add.cancel", false),
                ("app.settings.open", "返回", "devices.open", false),
            ] {
                f.input(open, if list { "host" } else { "" });
                let button = f.button(back, back_id, "");
                let navigation_width = if width < 980. { 64. } else { 184. };
                let list_width = if list {
                    if width < 980. { 224. } else { 276. }
                } else {
                    0.
                };
                let expected_x = navigation_width + list_width + f.ui.get_form_inset();
                assert!(
                    (button.absolute_position().x - expected_x).abs() < 1.,
                    "{open}: Back must be the first control in the page header"
                );
                f.capture(&format!("left-back-{open}-{tag}-{dark}"));
                assert_eq!(f.click(back, back_id, "")["ok"], true);
                assert_eq!(f.ui.get_page(), "devices");
                assert_eq!(f.ui.get_selected_uuid(), "host");
                assert_eq!(f.ui.get_device_query(), "100.64.0.1");
                assert_eq!(f.saved(), before);
            }
        }
    }
}

#[test]
fn update_primary_buttons_keep_edits_open_until_the_actual_draft_decision() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        for dark in [false, true] {
            let boundary = Arc::new(Boundary::default());
            *boundary.updates.lock().unwrap() =
                json!({"managed":true,"phase":"available","availableVersion":"9.0.0"});
            let (entered, ready) = std::sync::mpsc::channel();
            let (release, released) = std::sync::mpsc::channel();
            *boundary.update_gate.lock().unwrap() = Some((entered, released));
            let mut f = Flow::with_boundary(width, height, boundary.clone());
            f.ui.global::<crate::Palette>().set_dark(dark);
            f.input("app.settings.tab", "updates");
            let before = f.saved();
            let button = f.button("下载并安装", "updates.primary", "");
            let (id, arg) = f.emit(button, "下载并安装", "updates.primary", "");
            let controller = f.controller.clone();
            let args = args_for(&id, &arg, &controller.state());
            let job = std::thread::spawn(move || controller.dispatch(&id, args));
            ready.recv_timeout(Duration::from_secs(5)).unwrap();
            f.refresh();
            f.click("返回", "devices.open", "");
            f.click("连接设置", "device.settings.open", "host");
            f.click("音频", "settings.tab", "audio");
            f.input("settings.input", "volume=37");
            release.send(()).unwrap();
            assert_eq!(job.join().unwrap()["ok"], true);
            f.refresh();
            assert!(f.controller.take_window_action().is_empty());
            assert!(f.ui.get_navigation_prompt());
            assert_eq!(f.saved(), before);
            f.capture(&format!("update-{tag}-{dark}-draft-after-download"));
            f.click("继续编辑", "navigation.resolve", "stay");
            assert_eq!(f.controller.state()["settingsInputs"]["volume"], "37");
            assert_eq!(*boundary.calls.lock().unwrap(), ["updates.prepare"]);
            f.click("概览", "device.select", "host");
            f.click("保存并继续", "navigation.resolve", "save");
            assert_eq!(f.controller.profile("host").volume, 37);
            f.click("设置", "app.settings.open", "");
            f.capture(&format!("update-{tag}-{dark}-ready-after-edit"));
            f.click("重启并安装", "updates.primary", "");
            assert_eq!(
                *boundary.calls.lock().unwrap(),
                ["updates.prepare", "updates.install"]
            );
            assert_eq!(f.controller.take_window_action(), "window.close");
            assert!(f.ui.get_updates_busy());
            assert!(
                !f.button("正在安装…", "updates.primary", "")
                    .accessible_enabled()
                    .unwrap()
            );
        }
    }
}

#[test]
fn update_primary_keeps_the_pressed_candidate_even_when_the_label_is_unchanged(){
    backend();
    for (width,height) in [(720.,560.),(1180.,780.)]{for dark in [false,true]{for change in ["artifact","operation","roundtrip","notes"]{
        let mut f=Flow::new(width,height);if dark{f.input("appearance.toggle","");}f.input("app.settings.tab","updates");
        let mut state=f.controller.state();state["revision"]=json!(state["revision"].as_u64().unwrap()+1);state["updates"]=json!({"phase":"available","managed":true,"availableVersion":"9.0.0","candidateId":"package-a","intent":"candidate-a"});
        f.view.refresh(&f.ui,&state,|id|f.controller.profile(id));i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));f.ui.window().take_snapshot().unwrap();
        let button=f.button("下载并安装","updates.primary","candidate-a");assert_eq!(button.accessible_enabled(),Some(true));let p=button.absolute_position();let size=button.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
        f.ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});
        if change!="notes"{state["updates"]["intent"]=json!(format!("candidate-{change}"));}else{state["updates"]["status"]=json!("更新说明已刷新");}
        f.view.refresh(&f.ui,&state,|id|f.controller.profile(id));i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));f.ui.window().take_snapshot().unwrap();
        if change=="roundtrip"{state["updates"]["intent"]=json!("candidate-a");f.view.refresh(&f.ui,&state,|id|f.controller.profile(id));i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));f.ui.window().take_snapshot().unwrap();}
        f.ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
        let old=std::mem::take(&mut *f.pending.borrow_mut());
        assert_eq!(old,if change=="notes"{vec![("updates.primary".into(),"candidate-a".into())]}else{vec![]},"{width}/{dark}/{change}");
        let current=f.ui.get_update_intent().to_string();let button=f.button("下载并安装","updates.primary",&current);let (id,arg)=f.emit(button,"下载并安装","updates.primary",&current);
        assert_eq!(args_for(&id,&arg,&state),json!({"expectedIntent":current}));f.capture(&format!("update-intent-{width}-{dark}-{change}"));
    }}}
}

#[test]
fn prepared_update_shows_why_installation_waits_and_enables_one_button_after_sync() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        let boundary = Arc::new(Boundary::default());
        *boundary.updates.lock().unwrap() = json!({"managed":true,"phase":"downloaded","availableVersion":"9.0.0","pendingRestart":true,"status":"更新已下载，可安装更新"});
        let (entered, ready) = std::sync::mpsc::channel();
        let (release, released) = std::sync::mpsc::channel();
        *boundary.sync_gate.lock().unwrap() = Some((entered, released));
        let mut f = Flow::with_boundary(width, height, boundary.clone());
        f.input("app.settings.tab", "updates");
        let before = f.saved();
        let controller = f.controller.clone();
        let job = std::thread::spawn(move || controller.dispatch("sync.run", json!({})));
        ready.recv_timeout(Duration::from_secs(5)).unwrap();
        f.refresh();
        let button = f.button("重启并安装", "updates.primary", "");
        let enabled = button.accessible_enabled();
        let hint = ElementHandle::find_by_accessible_label(&f.ui, "请先完成配置同步").next();
        let rejected = f.controller.dispatch("updates.primary", json!({}));
        f.capture(&format!("update-{tag}-waiting-for-sync"));
        release.send(()).unwrap();
        assert_eq!(job.join().unwrap()["ok"], false);
        assert_eq!(enabled, Some(false));
        assert_eq!(rejected["error"], "请先完成配置同步");
        let hint = hint.expect("Disabled install must have a visible explanation");
        assert!(hint.absolute_position().y >= height - 100.);
        assert!(hint.size().width > 0.);
        f.refresh();
        assert!(
            f.button("重启并安装", "updates.primary", "")
                .accessible_enabled()
                .unwrap()
        );
        f.click("重启并安装", "updates.primary", "");
        assert_eq!(
            *boundary.calls.lock().unwrap(),
            ["sync.run", "updates.install"]
        );
        assert_eq!(f.controller.take_window_action(), "window.close");
        assert_eq!(f.saved(), before);
    }
}

#[path="diagnostic_ui_tests.rs"]
mod diagnostic_tests;
#[path="keyboard_workspace_tests.rs"]
mod keyboard_workspace_tests;

#[test]
fn published_update_notes_reach_the_bound_windows_view(){
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let boundary=Arc::new(Boundary::default());let notes="发布时间：2026-11-01 18:20 UTC+08:00\n\n代理设置";
    *boundary.updates.lock().unwrap()=json!({"managed":true,"phase":"available","availableVersion":"9.0.0","notes":notes});let mut f=Flow::with_boundary(720.,560.,boundary);f.input("app.settings.tab","updates");
    assert_eq!(f.ui.get_update_notes().as_str(),notes);f.capture("published-update-notes");
}
