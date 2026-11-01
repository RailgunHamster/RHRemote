//! Explicit local-data workflow acceptance using production Slint controls.
//! No native window, host connection, credential, updater or WebDAV implementation is loaded.
use crate::{Commands,MainWindow,view_state::MainView};
use i_slint_backend_testing::{AccessibleRole,ElementHandle,ElementQuery,TestingBackend,TestingBackendOptions};
use rhremote_core::{Controller,Device,Platform,Saved};
use serde_json::{Value,json};
use slint::{ComponentHandle,LogicalPosition,LogicalSize,Model,platform::{Key,PointerEventButton,WindowEvent}};
use std::{cell::RefCell,collections::BTreeMap,path::PathBuf,rc::Rc,sync::{Arc,Mutex},time::Duration};

struct Boundary {devices:Vec<Device>,calls:Mutex<Vec<String>>}
impl Platform for Boundary {
    fn execute(&self,id:&str,args:&Value)->Result<Value,String>{
        self.calls.lock().unwrap().push(id.into());
        match id {
            "sync.state"=>Ok(json!({"url":"","hasPassword":false,"automatic":false,"intervalMinutes":15})),
            "updates.state"=>Ok(json!({"managed":false,"phase":"idle"})),
            "devices.restore"=>Ok(json!({"devices":args["devices"]})),
            "devices.refresh"=>Ok(json!({"devices":self.devices})),
            "device.connect"=>Err("UI 流程夹具：连接不可用".into()),
            _=>Err(format!("Unexpected platform operation in isolated UI flow: {id}")),
        }
    }
}
type Command=(String,String,Option<String>);
struct Fixture {
    ui:MainWindow,view:MainView,controller:Arc<Controller>,boundary:Arc<Boundary>,queue:Rc<RefCell<Vec<Command>>>,
    storage:PathBuf,folder:PathBuf,records:Vec<Value>,profiles:BTreeMap<String,Value>,other_clients:BTreeMap<String,Value>,order:Vec<String>,
}
impl Fixture {
    fn new(saved:&Saved,dark:bool,width:f32,height:f32,folder:PathBuf)->Self{
        std::fs::create_dir_all(&folder).unwrap();let storage=folder.join("isolated-preferences.json");assert!(!storage.exists());
        let mut copy=saved.clone();copy.dark=dark;std::fs::write(&storage,serde_json::to_vec_pretty(&copy).unwrap()).unwrap();
        // Readiness is an explicit fixture. No native pairing identity is copied.
        let devices=copy.devices.iter().map(|d|Device{uuid:d.uuid.clone(),name:d.name.clone(),display_name:d.display_name.clone(),address:d.address.clone(),addresses:d.addresses.clone(),online:true,paired:true,is_self:false}).collect::<Vec<_>>();
        let order=devices.iter().map(|d|d.uuid.clone()).collect();let boundary=Arc::new(Boundary{devices:devices.clone(),calls:Mutex::new(Vec::new())});
        let controller=Controller::new("windows",storage.clone(),boundary.clone()).unwrap();controller.replace_devices(devices);
        let profiles=copy.devices.iter().map(|d|(d.uuid.clone(),serde_json::to_value(controller.profile(&d.uuid)).unwrap())).collect();
        let current=controller.state();let other_clients=current["saved"]["clientProfiles"].as_object().unwrap().iter().filter(|(id,_)|*id!=&copy.client_id).map(|(id,value)|(id.clone(),value.clone())).collect();
        let ui=MainWindow::new().unwrap();ui.set_isolated_preview(true);ui.set_app_version(env!("CARGO_PKG_VERSION").into());ui.window().set_size(LogicalSize::new(width,height));let view=MainView::new(&ui);
        let queue=Rc::new(RefCell::new(Vec::new()));let target=queue.clone();let weak=ui.as_weak();
        ui.global::<Commands>().on_trigger(move|id,arg|{let owner=weak.upgrade().and_then(|ui|crate::request_owner(&ui,id.as_str()));target.borrow_mut().push((id.to_string(),arg.to_string(),owner));});
        ui.show().unwrap();let mut fixture=Self{ui,view,controller,boundary,queue,storage,folder,records:Vec::new(),profiles,other_clients,order};fixture.refresh();fixture
    }
    fn refresh(&mut self){self.view.refresh(&self.ui,&self.controller.state(),|id|self.controller.profile(id));i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(250));for _ in 0..2{self.ui.window().take_snapshot().unwrap();}}
    fn click_element(&self,element:ElementHandle){
        let p=element.absolute_position();let size=element.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);let viewport=self.ui.window().size();
        assert!(size.width>0.&&size.height>0.&&position.x>=0.&&position.y>=0.&&position.x<viewport.width as f32&&position.y<viewport.height as f32,"Control is outside the current window");
        self.ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});self.ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
    }
    fn element(&self,id:&str,arg:&str)->ElementHandle{
        let prefix=format!("action|{id}|{arg}|");let exact=if id=="device-row"{format!("device|{arg}")}else{format!("nav|{id}")};
        let buttons=ElementQuery::from_root(&self.ui).find_all().into_iter().filter(|e|e.accessible_role()==Some(AccessibleRole::Button)).collect::<Vec<_>>();
        buttons.iter().find(|e|e.accessible_id().is_some_and(|v|v.starts_with(&prefix))).or_else(||buttons.iter().find(|e|e.accessible_id().is_some_and(|v|v==exact))).cloned().unwrap_or_else(||panic!("Missing actual Slint action {id}/{arg}"))
    }
    fn flush(&mut self,expected:&str)->Value{
        assert_eq!(self.queue.borrow().len(),1,"An actual control must emit one operation: {expected}");let(id,arg,owner)=self.queue.borrow_mut().remove(0);let expected=if expected=="device-row"{"device.select"}else{expected};assert_eq!(id,expected);
        let result=self.controller.dispatch(&id,crate::queued_args(&id,&arg,&self.controller.state(),owner));self.refresh();result
    }
    fn click(&mut self,id:&str,arg:&str)->Value{assert!(self.queue.borrow().is_empty());self.click_element(self.element(id,arg));self.flush(id)}
    fn row(&mut self,uuid:&str,expected:&str)->Value{assert!(self.queue.borrow().is_empty());self.click_element(self.element("device-row",uuid));self.flush(expected)}
    fn text(&mut self,label:&str,value:&str,expected:&str){
        if expected=="devices.filter"&&self.controller.state()["deviceQuery"]==value{assert_eq!(self.ui.get_device_query().as_str(),value);return;}
        assert!(self.queue.borrow().is_empty());let editor=ElementHandle::find_by_accessible_label(&self.ui,label).find(|e|e.accessible_read_only().is_some()).unwrap_or_else(||panic!("Missing actual editor {label}"));self.click_element(editor);
        crate::ui_keyboard::send_to_window(self.ui.window(),"Home",false,false);crate::ui_keyboard::send_to_window(self.ui.window(),"End",true,false);
        let text:slint::SharedString=if value.is_empty(){Key::Backspace.into()}else{value.into()};self.ui.window().dispatch_event(WindowEvent::KeyPressed{text:text.clone()});self.ui.window().dispatch_event(WindowEvent::KeyReleased{text});assert_eq!(self.flush(expected)["ok"],true);
    }
    fn profile_values(&self){for(uuid,expected)in &self.profiles{assert_eq!(&serde_json::to_value(self.controller.profile(uuid)).unwrap(),expected,"Another device's stored profile changed");}let state=self.controller.state();for(client,expected)in &self.other_clients{assert_eq!(&state["saved"]["clientProfiles"][client],expected,"Another client's profile changed");}}
    fn checkpoint(&mut self,name:&str,uuid:&str,query:&str){
        self.profile_values();let state=self.controller.state();assert_eq!(state["selected"],uuid);assert_eq!(state["deviceQuery"],query);
        assert_eq!(self.ui.get_selected_uuid().as_str(),uuid);assert_eq!(self.ui.get_device_query().as_str(),query);
        let device=state["devices"].as_array().unwrap().iter().find(|d|d["uuid"]==uuid).unwrap();let display=device["displayName"].as_str().filter(|name|!name.is_empty()).unwrap_or_else(||device["name"].as_str().unwrap());assert_eq!(self.ui.get_selected_name().as_str(),display);
        if state["page"]=="devices"{let profile=self.controller.profile(uuid);assert_eq!(self.ui.get_profile_width(),profile.width as i32);assert_eq!(self.ui.get_profile_height(),profile.height as i32);assert_eq!(self.ui.get_fps(),profile.fps as i32);assert_eq!(self.ui.get_bitrate(),profile.bitrate_mbps as i32);}
        if state["page"]=="settings"&&state["profileTab"]=="audio"{let volume=self.ui.get_profile_fields().iter().find(|field|field.key=="volume").unwrap();let expected=state["settingsInputs"]["volume"].as_str().map(str::to_owned).unwrap_or_else(||self.controller.profile(uuid).volume.to_string());assert_eq!(volume.input_text.as_str(),expected);}
        assert_eq!(state["devices"].as_array().unwrap().iter().map(|d|d["uuid"].as_str().unwrap().to_owned()).collect::<Vec<_>>(),self.order);
        self.records.push(json!({"step":name,"page":state["page"],"selectedIndex":self.order.iter().position(|id|id==uuid),"queryRetained":true,"uiMatchesController":true,"profilesVerified":self.profiles.len(),"otherClientProfilesVerified":self.other_clients.len()}));
    }
    fn capture(&self,name:&str){let pixels=self.ui.window().take_snapshot().unwrap();let path=self.folder.join(format!("{name}.png"));assert!(!path.exists());image::save_buffer(path,pixels.as_bytes(),pixels.width(),pixels.height(),image::ColorType::Rgba8).unwrap();}
    fn capture_pages(&mut self,saved:&Saved){
        let device=saved.devices.iter().find(|d|d.name.eq_ignore_ascii_case("winserver")).unwrap_or(&saved.devices[0]);let uuid=&device.uuid;
        self.row(uuid,"device.select");self.capture("devices");self.click("device.settings.open",uuid);
        for tab in ["video","audio","input","network","advanced"]{self.click("settings.tab",tab);self.capture(&format!("profile-{tab}"));}self.click("settings.cancel","");
        self.click("app.settings.open","");for tab in ["updates","sync","appearance","about"]{self.click("app.settings.tab",tab);self.capture(&format!("application-{tab}"));}self.click("devices.open","");
        self.click("device.info.open",uuid);self.capture("device-info");self.click("device.info.cancel","");self.click("device.add.open","");self.capture("device-add");self.click("device.add.cancel","");
        self.click("device.settings.open",uuid);self.click("settings.tab","audio");let label=self.ui.get_profile_fields().iter().find(|f|f.key=="volume").unwrap().label;let value=if self.controller.profile(uuid).volume==37{38}else{37};self.text(label.as_str(),&value.to_string(),"settings.input");self.click("devices.open","");assert!(self.controller.state()["navigationPrompt"].is_object());self.capture("unsaved");self.click("navigation.resolve","discard");self.profile_values();
        // Restore the initial category before the existing workflow continues.
        self.click("app.settings.open","");self.click("app.settings.tab","updates");self.click("devices.open","");
    }
    fn run(&mut self,saved:&Saved){
        assert_eq!(self.ui.get_devices().iter().map(|d|d.uuid.to_string()).collect::<Vec<_>>(),self.order);self.capture("all-devices");
        self.capture_pages(saved);
        for(index,device)in saved.devices.iter().enumerate(){
            let uuid=&device.uuid;let query=if device.display_name.is_empty(){&device.name}else{&device.display_name};let other=&saved.devices[(index+1)%saved.devices.len()].uuid;
            self.text("搜索名称或地址","","devices.filter");assert_eq!(self.row(uuid,"device.select")["ok"],true);self.text("搜索名称或地址",query,"devices.filter");self.checkpoint("selected",uuid,query);
            assert_eq!(self.click("devices.refresh","")["ok"],true);self.checkpoint("refreshed",uuid,query);self.capture(&format!("device-{index}-overview"));
            assert_eq!(self.click("device.settings.open",uuid)["ok"],true);assert_eq!(self.click("settings.tab","audio")["ok"],true);let field=self.ui.get_profile_fields().iter().find(|f|f.key=="volume").unwrap();let changed=if self.controller.profile(uuid).volume==37{38}else{37};self.text(field.label.as_str(),&changed.to_string(),"settings.input");
            self.text("搜索名称或地址","","devices.filter");assert_eq!(self.row(other,"device.settings.open")["ok"],true);assert!(self.controller.state()["navigationPrompt"].is_object());self.click("navigation.resolve","stay");assert_eq!(self.controller.state()["settingsInputs"]["volume"],changed.to_string());self.checkpoint("switch-stay",uuid,"");
            self.row(other,"device.settings.open");self.click("navigation.resolve","save");self.profiles.get_mut(uuid).unwrap()["volume"]=json!(changed);self.checkpoint("switch-saved",other,"");assert_eq!(self.controller.state()["page"],"settings");
            self.row(uuid,"device.settings.open");self.text("搜索名称或地址",query,"devices.filter");let field=self.ui.get_profile_fields().iter().find(|f|f.key=="volume").unwrap();self.text(field.label.as_str(),&(changed+1).to_string(),"settings.input");self.click("app.settings.open","");self.click("navigation.resolve","stay");self.checkpoint("settings-stay",uuid,query);self.click("app.settings.open","");self.click("navigation.resolve","discard");self.checkpoint("settings-discard",uuid,query);assert_eq!(self.controller.state()["page"],"global-settings");self.click("devices.open","");self.checkpoint("settings-return",uuid,query);
            assert_eq!(self.click("device.connect",uuid)["ok"],false);assert_eq!(self.controller.state()["connection"]["phase"],"failed");self.checkpoint("connection-failed",uuid,query);self.capture(&format!("device-{index}-connection-error"));assert_eq!(self.click("connection.retry","")["ok"],false);self.checkpoint("connection-retried",uuid,query);assert_eq!(self.click("connection.dismiss","")["ok"],true);self.checkpoint("connection-return",uuid,query);
            self.click("app.settings.open","");let primary=ElementQuery::from_root(&self.ui).find_all().into_iter().filter(|e|e.accessible_id().is_some_and(|id|id.starts_with("action|updates.primary|"))).count();assert_eq!(primary,1,"Updates must have one primary workflow button");
            self.click("app.settings.tab","sync");self.text("WebDAV 目录地址（HTTP / HTTPS）","http://fixture.example.test/dav/","sync.edit");assert_eq!(self.controller.state()["syncDirty"],true);self.click("sync.cancel","");assert_eq!(self.controller.state()["syncDirty"],false);
            for tab in ["appearance","about","updates"]{self.click("app.settings.tab",tab);assert_eq!(self.controller.state()["settingsTab"],tab);}self.click("devices.open","");self.checkpoint("global-return",uuid,query);
        }
        // Exercise the self-device UI flag without reading a native pairing identity.
        self.text("搜索名称或地址","","devices.filter");let mut devices=self.boundary.devices.clone();devices[0].is_self=true;self.controller.replace_devices(devices);self.refresh();let own=&saved.devices[0].uuid;self.row(own,"device.select");self.click_element(self.element("device.connect",own));assert!(self.queue.borrow().is_empty(),"A self-device button dispatched a connection");self.profile_values();self.capture("self-fixture-disabled");
        let calls=self.boundary.calls.lock().unwrap();assert_eq!(calls.iter().filter(|id|*id=="device.connect").count(),saved.devices.len()*2);assert!(calls.iter().all(|id|matches!(id.as_str(),"sync.state"|"updates.state"|"devices.refresh"|"devices.restore"|"device.connect")));
        std::fs::write(self.folder.join("result.json"),serde_json::to_vec_pretty(&json!({"passed":true,"checkpoints":self.records,"deviceCount":saved.devices.len(),"realSlintControls":true,"nativeWindowCreated":false,"transientReadiness":"fixture","connectionErrors":"controlled platform boundary","realHostConnected":false,"webdavAccessed":false,"updaterAccessed":false})).unwrap()).unwrap();
    }
}
impl Drop for Fixture {fn drop(&mut self){if std::thread::panicking(){let state=self.controller.state();let _=std::fs::write(self.folder.join("failure-state.json"),serde_json::to_vec_pretty(&json!({"checkpoints":self.records,"page":state["page"],"profileTab":state["profileTab"],"settingsTab":state["settingsTab"],"selectedIndex":self.order.iter().position(|id|Some(id.as_str())==state["selected"].as_str()),"pendingDecision":state["navigationPrompt"].is_object()})).unwrap());}let _=self.ui.hide();if self.storage.exists(){std::fs::remove_file(&self.storage).unwrap();}}}

#[test]
#[ignore="Explicit daily-preferences clone; does not access user data during default test runs"]
fn daily_device_workspace_preserves_context_through_the_complete_ui_flow(){
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let source=PathBuf::from(std::env::var_os("RHREMOTE_WORKSPACE_FLOW_SOURCE").expect("Explicit source preferences required"));let evidence=PathBuf::from(std::env::var_os("RHREMOTE_WORKSPACE_FLOW_EVIDENCE").expect("Explicit fresh evidence required"));assert!(source.is_absolute()&&evidence.is_absolute());assert!(!evidence.exists());std::fs::create_dir_all(&evidence).unwrap();
    let before=std::fs::read(&source).unwrap();let saved:Saved=serde_json::from_slice(&before).unwrap();assert!(saved.devices.len()>=3);assert!(saved.profile_schema_version>=2&&saved.device_order_initialized);
    for dark in [false,true]{for(width,height,name)in [(720.,560.,"compact"),(1180.,780.,"wide")]{let folder=evidence.join(format!("{name}-{}",if dark{"dark"}else{"light"}));let mut fixture=Fixture::new(&saved,dark,width,height,folder);fixture.run(&saved);assert_eq!(std::fs::read(&source).unwrap(),before,"Normal preferences changed");}}
    std::fs::write(evidence.join("result.json"),serde_json::to_vec_pretty(&json!({"passed":true,"cases":4,"deviceCount":saved.devices.len(),"sourceBytesUnchanged":true,"realSlintControls":true,"physicalDisplayTested":false,"realConnectionTested":false,"credentialsCopied":false})).unwrap()).unwrap();
}
