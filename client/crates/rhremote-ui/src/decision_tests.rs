//! Use the production Slint controls, renderer binding and queued argument capture.
//! The platform boundary is local memory; no host, installer or WebDAV is contacted.
use crate::{Commands,MainWindow,view_state::MainView};
use i_slint_backend_testing::{ElementHandle,TestingBackend,TestingBackendOptions};
use rhremote_core::{Controller,Platform};
use serde_json::{Value,json};
use slint::{ComponentHandle,LogicalPosition,LogicalSize,Model,platform::{PointerEventButton,WindowEvent}};
use std::{cell::RefCell,rc::Rc,sync::{Arc,Mutex,atomic::{AtomicUsize,Ordering}}};

#[derive(Default)]
struct Boundary {saved:Mutex<Value>,removals:AtomicUsize,calls:Mutex<Vec<(String,std::thread::ThreadId)>>,gate:Mutex<Option<Arc<Gate>>>,refresh_failure:std::sync::atomic::AtomicBool,refresh_progress:Mutex<Option<rhremote_core::DeviceObservation>>,sync_import:std::sync::atomic::AtomicBool}
struct Gate {
    operation: &'static str,
    entered: std::sync::mpsc::Sender<std::thread::ThreadId>,
    released: Mutex<std::sync::mpsc::Receiver<()>>,
}
impl Platform for Boundary {
    fn observe_devices(&self,args:&Value,publish:&mut dyn FnMut(rhremote_core::DeviceObservation))->Result<Value,String>{
        if let Some(observation)=self.refresh_progress.lock().unwrap().take(){publish(observation);}self.execute("devices.refresh",args)
    }
    fn execute(&self,id:&str,args:&Value)->Result<Value,String>{
        self.calls.lock().unwrap().push((id.into(),std::thread::current().id()));
        let gate=self.gate.lock().unwrap().clone();
        if let Some(gate)=gate.filter(|gate|gate.operation==id){
            gate.entered.send(std::thread::current().id()).map_err(|e|e.to_string())?;
            gate.released.lock().unwrap().recv_timeout(std::time::Duration::from_secs(5)).map_err(|e|e.to_string())?;
        }
        if id=="devices.refresh" && self.refresh_failure.load(Ordering::SeqCst){return Err("测试设备状态请求超时".into());}
        Ok(match id {
            "devices.restore"=>json!({"devices":args["devices"]}),
            "sync.run" if self.sync_import.load(Ordering::SeqCst)=>{
                let mut shared=args["shared"].clone();shared["devices"].as_array_mut().unwrap().push(json!({"uuid":"imported","name":"New PC","displayName":"同步设备","address":"192.0.2.8","swapButtons":false}));json!({"shared":shared})
            },
            "updates.state"=>json!({"managed":false}),
            "sync.state"=>self.saved.lock().unwrap().clone(),
            "sync.configure"=>{let mut next=args.clone();next.as_object_mut().unwrap().remove("password");next["hasPassword"]=json!(true);*self.saved.lock().unwrap()=next.clone();next},
            "sync.clear"=>{self.removals.fetch_add(1,Ordering::SeqCst);let next=json!({"url":"","hasPassword":false,"automatic":false});*self.saved.lock().unwrap()=next.clone();next},
            _=>json!({}),
        })
    }
}
type Command=(String,String,Option<String>);
struct Fixture {
    ui:MainWindow,view:MainView,controller:Arc<Controller>,platform:Arc<Boundary>,queued:Rc<RefCell<Vec<Command>>>,storage:std::path::PathBuf,
}
impl Fixture {
    fn new()->Self{Self::with_storage(None)}
    fn with_storage(storage:Option<std::path::PathBuf>)->Self{
        slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
        let storage=storage.unwrap_or_else(||std::env::temp_dir().join(format!("rhremote-decisions-{}.json",rhremote_core::random_token().unwrap())));let platform=Arc::new(Boundary::default());let controller=Controller::new("test",storage.clone(),platform.clone()).unwrap();
        controller.replace_devices(serde_json::from_value(json!([
            {"uuid":"host","name":"winserver","address":"192.0.2.1","paired":true,"online":true},
            {"uuid":"second","name":"Claw","address":"192.0.2.2","paired":true,"online":false}
        ])).unwrap());
        let ui=MainWindow::new().unwrap();ui.window().set_size(LogicalSize::new(1180.,780.));let view=MainView::new(&ui);let queued=Rc::new(RefCell::new(Vec::new()));let target=queued.clone();let weak=ui.as_weak();
        ui.global::<Commands>().on_trigger(move|id,arg|{let owner=weak.upgrade().and_then(|ui|crate::request_owner(&ui,id.as_str()));target.borrow_mut().push((id.to_string(),arg.to_string(),owner));});ui.show().unwrap();Self{ui,view,controller,platform,queued,storage}
    }
    fn refresh(&mut self){self.view.refresh(&self.ui,&self.controller.state(),|id|self.controller.profile(id));self.ui.window().take_snapshot().unwrap();self.ui.window().take_snapshot().unwrap();}
    fn click(&self,label:&str){
        let button=ElementHandle::find_by_accessible_label(&self.ui,label).find(|v|v.accessible_role()==Some(i_slint_backend_testing::AccessibleRole::Button)&&v.accessible_enabled()!=Some(false)).unwrap_or_else(||panic!("Missing enabled native Slint button {label}"));let p=button.absolute_position();let size=button.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
        self.ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});self.ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
    }
    fn flush(&self)->Value{assert_eq!(self.queued.borrow().len(),1);let(id,arg,owner)=self.queued.borrow_mut().remove(0);self.controller.dispatch(&id,crate::queued_args(&id,&arg,&self.controller.state(),owner))}
    fn capture(&self,name:&str){
        let Some(root)=std::env::var_os("RHREMOTE_DECISION_EVIDENCE_DIR")else{return};let root=std::path::PathBuf::from(root);assert!(root.is_absolute());std::fs::create_dir_all(&root).unwrap();let path=root.join(format!("{name}.png"));assert!(!path.exists());let pixels=self.ui.window().take_snapshot().unwrap();image::save_buffer(&path,pixels.as_bytes(),pixels.width(),pixels.height(),image::ColorType::Rgba8).unwrap();
    }
}
impl Drop for Fixture {fn drop(&mut self){let _=self.ui.hide();if self.storage.exists(){std::fs::remove_file(&self.storage).unwrap();}}}

#[test]
fn imported_device_uses_check_then_verified_connect_without_inheriting_remote_pairing(){
    let root=std::env::temp_dir().join(format!("rhremote-import-controls-{}",rhremote_core::random_token().unwrap()));std::fs::create_dir(&root).unwrap();
    let mut f=Fixture::with_storage(Some(root.join("preferences.json")));f.ui.set_app_version(env!("CARGO_PKG_VERSION").into());f.ui.set_isolated_preview(true);
    if std::env::var("RHREMOTE_STARTUP_TEST_DARK").as_deref()==Ok("1"){f.controller.dispatch("appearance.toggle",json!({}));}
    if std::env::var("RHREMOTE_REFRESH_TEST_WIDTH").as_deref()==Ok("720"){f.ui.window().set_size(LogicalSize::new(720.,560.));}
    f.platform.sync_import.store(true,Ordering::SeqCst);
    assert_eq!(f.controller.dispatch("sync.run",json!({}))["ok"],true);
    assert_eq!(f.controller.dispatch("device.select",json!({"uuid":"imported"}))["ok"],true);f.refresh();f.capture("import-unchecked");
    assert_eq!(f.ui.get_selected_device().status.as_str(),"待检查");assert!(f.ui.get_selected_device().pending);
    assert!(!ElementHandle::find_by_accessible_label(&f.ui,"配对").any(|control|control.accessible_enabled()==Some(true)),"a shared record was mistaken for observed unpaired state");
    let mut device:rhremote_core::Device=serde_json::from_value(f.controller.state()["devices"][2].clone()).unwrap();device.online=true;device.paired=true;
    *f.platform.refresh_progress.lock().unwrap()=Some(rhremote_core::DeviceObservation{device,pairing_verified:true,check:None});
    f.click("检查连接");assert_eq!(f.queued.borrow()[0].0,"devices.refresh");assert_eq!(f.queued.borrow()[0].1,"imported");assert_eq!(f.flush()["ok"],true);
    f.refresh();f.capture("import-verified");assert!(!f.ui.get_selected_device().pending);assert_eq!(f.ui.get_selected_device().status.as_str(),"已配对");
    f.click("连接");assert_eq!(f.queued.borrow()[0].0,"device.connect");assert_eq!(f.queued.borrow()[0].1,"imported");
    assert_eq!(f.controller.state()["devices"][0]["paired"],true,"import revoked an existing local pairing");
    f.queued.borrow_mut().clear();
    let mut rows:Vec<rhremote_core::Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();rows[2].is_self=true;
    f.controller.replace_devices_pending(rows);f.refresh();f.capture("import-local");
    assert_eq!(f.ui.get_selected_device().status.as_str(),"本机");
    assert!(!f.ui.get_selected_device().pending);
    assert!(!ElementHandle::find_by_accessible_label(&f.ui,"连接").any(|control|control.accessible_enabled()==Some(true)));
    assert_eq!(f.controller.dispatch("device.connect",json!({"uuid":"imported"}))["ok"],false);
    f.click("连接设置");assert_eq!(f.flush()["ok"],true,"self-device configuration remains editable");
    // Only this random fixture directory owns the sync backup and preferences.
    drop(f);std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn native_check_outcomes_explain_the_failure_and_route_the_actual_primary_button(){
    use rhremote_core::{CheckOutcome as O,CheckSource as S,DeviceCheck};
    let mut f=Fixture::new();f.ui.set_app_version(env!("CARGO_PKG_VERSION").into());f.ui.set_isolated_preview(true);
    if std::env::var("RHREMOTE_STARTUP_TEST_DARK").as_deref()==Ok("1"){f.controller.dispatch("appearance.toggle",json!({}));}
    if std::env::var("RHREMOTE_REFRESH_TEST_WIDTH").as_deref()==Ok("720"){f.ui.window().set_size(LogicalSize::new(720.,560.));}
    let mut rows:Vec<rhremote_core::Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();rows[0].online=false;rows[0].paired=false;
    for (outcome,source,certificate,verified,label,button,action,name) in [
        (O::Unpaired,S::Http,false,true,"未配对","配对","device.pair","unpaired"),
        (O::AuthRejected,S::Http,true,true,"认证失败","重新配对","device.pair","auth-rejected"),
        (O::AuthRejected,S::Https,true,true,"认证失败","重新配对","device.pair","auth-rejected-https"),
        (O::CertificateMismatch,S::Http,true,false,"证书不符","重新配对","device.pair","certificate-mismatch"),
        (O::TlsFailure,S::Http,true,false,"安全连接失败","连接","device.connect","tls-failure"),
        (O::IdentityMismatch,S::Https,true,false,"设备标识不符","检查连接","devices.refresh","identity-mismatch"),
        (O::Timeout,S::Https,true,false,"检查超时","连接","device.connect","timeout-stored"),
        (O::Unreachable,S::None,false,false,"暂时无法连接","检查连接","devices.refresh","unreachable"),
    ]{
        f.controller.replace_devices_pending(rows.clone());
        *f.platform.refresh_progress.lock().unwrap()=Some(rhremote_core::DeviceObservation{device:rows[0].clone(),pairing_verified:true,check:Some(DeviceCheck{phase:rhremote_core::CheckPhase::ServerInfo,outcome:O::Unpaired,source:S::Http,has_certificate:false,elapsed_us:1000})});
        assert_eq!(f.controller.dispatch("devices.refresh",json!({"uuid":"host"}))["ok"],true);
        let mut device=rows[0].clone();device.online=outcome!=O::Unreachable;
        *f.platform.refresh_progress.lock().unwrap()=Some(rhremote_core::DeviceObservation{device,pairing_verified:verified,check:Some(DeviceCheck{phase:rhremote_core::CheckPhase::ServerInfo,outcome,source,has_certificate:certificate,elapsed_us:1000})});
        assert_eq!(f.controller.dispatch("devices.refresh",json!({"uuid":"host"}))["ok"],true);f.refresh();
        assert_eq!(f.ui.get_selected_device().status.as_str(),label);f.capture(&format!("check-{name}"));
        let control=ElementHandle::find_by_accessible_label(&f.ui,button).find(|control|control.accessible_enabled()==Some(true)).unwrap();let position=control.absolute_position();let size=control.size();let window=f.ui.window().size().to_logical(f.ui.window().scale_factor());
        assert!(position.x>=0.&&position.y>=0.&&position.x+size.width<=window.width&&position.y+size.height<=window.height,"primary button clipped for {name}");
        let card=ElementHandle::find_by_accessible_label(&f.ui,"桌面连接区域").next().unwrap();let card_position=card.absolute_position();let card_size=card.size();
        assert!(position.y+size.height+16.<=card_position.y+card_size.height+0.5,"primary button lost its bottom inset for {name}: button bottom {}, card bottom {}",position.y+size.height,card_position.y+card_size.height);
        f.click(button);assert_eq!(f.queued.borrow()[0].0,action);assert_eq!(f.queued.borrow()[0].1,"host");f.queued.borrow_mut().clear();
        if outcome==O::IdentityMismatch{assert_eq!(f.controller.dispatch("device.connect",json!({"uuid":"host"}))["ok"],false);assert_eq!(f.controller.dispatch("device.pair",json!({"uuid":"host"}))["ok"],false);}
    }
}

#[test]
fn device_trust_network_failure_keeps_identity_feedback_and_the_matching_primary_action(){
    use rhremote_core::{CheckOutcome as O,CheckSource as S,DeviceCheck};
    let mut f=Fixture::new();f.ui.set_app_version(env!("CARGO_PKG_VERSION").into());f.ui.set_isolated_preview(true);
    let dark=std::env::var("RHREMOTE_STARTUP_TEST_DARK").as_deref()==Ok("1");if dark{f.controller.dispatch("appearance.toggle",json!({}));}
    let compact=std::env::var("RHREMOTE_REFRESH_TEST_WIDTH").as_deref()==Ok("720");if compact{f.ui.window().set_size(LogicalSize::new(720.,560.));}
    let original:Vec<rhremote_core::Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();
    let profiles=[f.controller.profile("host"),f.controller.profile("second")];
    assert_eq!(f.controller.dispatch("devices.filter",json!({"query":"server"}))["ok"],true);
    fn observe(f:&Fixture,outcome:O){
        let mut device:rhremote_core::Device=serde_json::from_value(f.controller.state()["devices"][0].clone()).unwrap();device.online=outcome!=O::Unreachable;device.paired=outcome==O::Paired;
        *f.platform.refresh_progress.lock().unwrap()=Some(rhremote_core::DeviceObservation{device,pairing_verified:matches!(outcome,O::Paired|O::Unpaired|O::AuthRejected),check:Some(DeviceCheck{phase:rhremote_core::CheckPhase::ServerInfo,outcome,source:if outcome==O::Unreachable{S::None}else{S::Https},has_certificate:outcome!=O::Unreachable,elapsed_us:1000})});
        assert_eq!(f.controller.dispatch("devices.refresh",json!({"uuid":"host"}))["ok"],true);
    }
    let mut records=Vec::new();
    for (prior,label,repair,name) in [
        (O::AuthRejected,"认证失败",true,"auth"),
        (O::CertificateMismatch,"证书不符",true,"certificate"),
        (O::Unpaired,"需要重新配对",true,"unpaired"),
        (O::IdentityMismatch,"设备标识不符",false,"identity"),
        (O::InvalidResponse,"设备信息无效",false,"invalid"),
    ]{for (latest,current_label,network) in [(O::Timeout,"检查超时","timeout"),(O::Unreachable,"暂时无法连接","unreachable")]{
        // These are independent examples. A real verified observation, rather
        // than replacing the device array, clears the preceding trust result.
        f.controller.replace_devices(original.clone());observe(&f,O::Paired);observe(&f,prior);f.refresh();
        let tag=format!("trust-{name}-{network}");f.capture(&format!("{tag}-before"));
        observe(&f,latest);f.refresh();f.capture(&format!("{tag}-after"));
        let row=f.ui.get_selected_device();let button=if repair&&latest==O::Timeout{"重新配对"}else{"检查连接"};
        let action=if button=="重新配对"{"device.pair"}else{"devices.refresh"};
        let card=ElementHandle::find_by_accessible_label(&f.ui,"桌面连接区域").next().unwrap();
        let actual=card.query_descendants().find_all().into_iter().find(|item|item.accessible_role()==Some(i_slint_backend_testing::AccessibleRole::Button)&&item.accessible_enabled()==Some(true)).expect("Visible primary action");
        let actual_label=actual.accessible_label().unwrap().to_string();let p=actual.absolute_position();let size=actual.size();let window=f.ui.window().size().to_logical(f.ui.window().scale_factor());
        assert!(p.x>=0.&&p.y>=0.&&p.x+size.width<=window.width&&p.y+size.height<=window.height,"Primary control clipped for {tag}");
        assert!(p.y+size.height+16.<=card.absolute_position().y+card.size().height+0.5,"Primary control lost card padding for {tag}");
        f.click(&actual_label);let queued=f.queued.borrow_mut().remove(0);assert!(f.queued.borrow().is_empty());
        let state=f.controller.state();
        assert_eq!(state["selected"],"host");assert_eq!(state["deviceQuery"],"server");assert_eq!(state["devices"][1],serde_json::to_value(&original[1]).unwrap());
        records.push(json!({"tag":tag,"dark":dark,"compact":compact,"prior":prior,"latest":latest,"expectedStatus":label,"actualStatus":row.status.as_str(),"expectedButton":button,"actualButton":actual_label,"expectedAction":action,"actualAction":queued.0,"actualTarget":queued.1,"hint":row.check_hint.as_str(),"currentLabel":current_label,"check":state["deviceChecks"]["host"],"trust":state["deviceTrustChecks"]["host"],"buttonFullyVisible":true,"cardInsetPreserved":true}));
    }}
    observe(&f,O::Paired);f.refresh();f.capture("trust-recovered");assert_eq!(f.ui.get_selected_device().status.as_str(),"已配对");f.click("连接");assert_eq!(f.queued.borrow_mut().remove(0).0,"device.connect");
    assert_eq!(serde_json::to_value(profiles).unwrap(),serde_json::to_value([f.controller.profile("host"),f.controller.profile("second")]).unwrap());
    if let Some(root)=std::env::var_os("RHREMOTE_DECISION_EVIDENCE_DIR"){let path=std::path::PathBuf::from(root).join("trust-controls.json");assert!(!path.exists());std::fs::write(path,serde_json::to_vec_pretty(&records).unwrap()).unwrap();}
    for row in records{assert_eq!(row["actualStatus"],row["expectedStatus"],"A weaker network failure erased known trust feedback: {row}");assert_eq!(row["actualButton"],row["expectedButton"]);assert_eq!(row["actualAction"],row["expectedAction"]);assert_eq!(row["actualTarget"],"host");assert!(row["hint"].as_str().unwrap().contains(row["currentLabel"].as_str().unwrap()));}
}

#[test]
fn pairing_feedback_preserves_the_check_in_detail_and_uses_its_own_status_color(){
    use rhremote_core::{CheckOutcome as O,CheckSource as S,DeviceCheck};
    let mut f=Fixture::new();let dark=std::env::var("RHREMOTE_STARTUP_TEST_DARK").as_deref()==Ok("1");if dark{f.controller.dispatch("appearance.toggle",json!({}));}if std::env::var("RHREMOTE_REFRESH_TEST_WIDTH").as_deref()==Ok("720"){f.ui.window().set_size(LogicalSize::new(720.,560.));}
    let original:Vec<rhremote_core::Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();
    for (outcome,certificate,verified,label,failure,name) in [
        (O::TlsFailure,true,false,"安全连接失败",true,"tls"),
        (O::CertificateMismatch,true,false,"证书不符",true,"certificate"),
        (O::Unpaired,true,true,"需要重新配对",true,"repair"),
        (O::AuthRejected,true,true,"认证失败",true,"auth"),
        (O::IdentityMismatch,true,false,"设备标识不符",true,"identity"),
        (O::InvalidResponse,true,false,"设备信息无效",true,"invalid"),
        (O::Timeout,true,false,"检查超时",true,"timeout"),
        (O::Unavailable,true,false,"状态未确认",true,"unknown"),
        (O::Paired,true,true,"已配对",false,"paired"),
        (O::Unpaired,false,true,"未配对",false,"unpaired"),
    ]{
        f.controller.replace_devices(original.clone());let mut device=original[0].clone();device.paired=outcome==O::Paired;
        *f.platform.refresh_progress.lock().unwrap()=Some(rhremote_core::DeviceObservation{device:original[0].clone(),pairing_verified:true,check:Some(DeviceCheck{phase:rhremote_core::CheckPhase::ServerInfo,outcome:O::Paired,source:S::Https,has_certificate:true,elapsed_us:1000})});
        assert_eq!(f.controller.dispatch("devices.refresh",json!({"uuid":"host"}))["ok"],true);
        *f.platform.refresh_progress.lock().unwrap()=Some(rhremote_core::DeviceObservation{device,pairing_verified:verified,check:Some(DeviceCheck{phase:rhremote_core::CheckPhase::ServerInfo,outcome,source:S::Https,has_certificate:certificate,elapsed_us:1000})});
        assert_eq!(f.controller.dispatch("devices.refresh",json!({"uuid":"host"}))["ok"],true);f.refresh();f.capture(&format!("pairing-feedback-{name}"));
        assert_eq!(f.ui.get_selected_device().online,!matches!(outcome,O::IdentityMismatch|O::InvalidResponse));
        let all_labels:Vec<_>=ElementHandle::find_by_accessible_label(&f.ui,label).collect();assert!(all_labels.len()>=3,"the row, header and detail must retain {label}, found {}",all_labels.len());
        let row=ElementHandle::find_by_element_id(&f.ui,"DeviceListPane::device-row-status").find(|v|v.accessible_label().as_deref()==Some(label)).expect("selected row status");let header=ElementHandle::find_by_element_id(&f.ui,"DeviceContextHeader::device-status-label").next().expect("device header status");let field=ElementHandle::find_by_element_id(&f.ui,"MainWindow::pairing-status-field").next().expect("pairing detail field");let detail=field.query_descendants().find_all().into_iter().find(|v|v.accessible_label().as_deref()==Some(label)).expect("pairing detail must retain the check result");let labels=vec![row,header,detail];
        let expected=if failure{if dark{[242u8,139,156]}else{[176,68,82]}}else if outcome==O::Paired{if dark{[139,199,177]}else{[54,156,129]}}else if dark{[164,177,200]}else{[105,119,142]};
        let pixels=f.ui.window().take_snapshot().unwrap();let scale=f.ui.window().scale_factor();let mut checked=0;
        let backgrounds=if dark{[[27f32,34.,50.],[21.,27.,41.],[37.,52.,82.]]}else{[[255f32,255.,255.],[246.,247.,250.],[237.,242.,255.]]};
        for item in labels{let position=item.absolute_position();let size=item.size();let (left,top,right,bottom)=((position.x*scale).floor()as i32,(position.y*scale).floor()as i32,((position.x+size.width)*scale).ceil()as i32,((position.y+size.height)*scale).ceil()as i32);if left<0||top<0||right>pixels.width()as i32||bottom>pixels.height()as i32||size.width<=0.||size.height<=0.{continue}let mut matches=0;for y in top..bottom{for x in left..right{let i=((y as usize*pixels.width()as usize)+x as usize)*4;let rgba=&pixels.as_bytes()[i..i+4];
            // Small glyphs may contain no fully opaque foreground pixels. Verify
            // their alpha blend against each actual surface, excluding background.
            let ink=backgrounds.iter().any(|bg|{let delta:Vec<f32>=(0..3).map(|c|expected[c]as f32-bg[c]).collect();let norm=delta.iter().map(|v|v*v).sum::<f32>();let coverage=(0..3).map(|c|(rgba[c]as f32-bg[c])*delta[c]).sum::<f32>()/norm;(0.2..=1.02).contains(&coverage)&&(0..3).all(|c|(rgba[c]as f32-(bg[c]+coverage*delta[c])).abs()<=3.)});if ink{matches+=1;}
        }}assert!(matches>=4,"{label} is missing its semantic text color at {position:?}: {matches} pixels");checked+=1;}
        assert!(checked>=1,"no visible {label} controls were verified");
    }
}

#[test]
fn pair_preparation_controls_show_pin_only_for_the_current_protocol_and_cancel_from_each_stage(){
    let mut f=Fixture::new();f.ui.set_app_version(env!("CARGO_PKG_VERSION").into());f.ui.set_isolated_preview(true);
    if std::env::var("RHREMOTE_STARTUP_TEST_DARK").as_deref()==Ok("1"){f.controller.dispatch("appearance.toggle",json!({}));}
    if std::env::var("RHREMOTE_REFRESH_TEST_WIDTH").as_deref()==Ok("720"){f.ui.window().set_size(LogicalSize::new(720.,560.));}
    for (index,stage) in ["preparing","contacting","pairing"].into_iter().enumerate(){
        assert_eq!(f.controller.dispatch("device.pair",json!({"uuid":"host","pin":"1234"}))["ok"],true);
        if stage!="preparing" {f.controller.stream_state(false,json!({"pairAttemptId":index as u64*2+1,"pairingPhase":stage,"nativeCleanupPending":false}));}
        f.refresh();assert_eq!(f.controller.state()["connection"]["phase"],stage);
        assert_eq!(f.ui.get_pair_pin().as_str(),if stage=="pairing" {"1234"}else{""});
        f.capture(&format!("pair-{stage}"));
        assert!(!ElementHandle::find_by_accessible_label(&f.ui,"连接").any(|control|control.accessible_enabled()==Some(true)));
        f.click("取消配对");assert_eq!(f.queued.borrow()[0].0,"pair.cancel");assert_eq!(f.flush()["ok"],true);
        assert_eq!(f.controller.state()["pairPin"],"");
        f.controller.stream_state(false,json!({"phase":"cancelled","nativeCleanupPending":false}));
        assert_eq!(f.controller.state()["page"],"devices");
    }
}

#[test]
fn an_unchecked_startup_device_does_not_offer_pairing_as_an_established_fact(){
    let mut f=Fixture::new();
    f.ui.set_app_version(env!("CARGO_PKG_VERSION").into());f.ui.set_isolated_preview(true);
    if std::env::var("RHREMOTE_STARTUP_TEST_DARK").as_deref()==Ok("1"){f.controller.dispatch("appearance.toggle",json!({}));}
    if std::env::var("RHREMOTE_REFRESH_TEST_WIDTH").as_deref()==Ok("720"){f.ui.window().set_size(LogicalSize::new(720.,560.));}
    let mut state=f.controller.state();state["devices"][0]["paired"]=json!(false);state["devices"][0]["online"]=json!(false);state["devicesPending"]=json!(["host"]);state["devicesRefreshing"]=json!(true);
    f.view.refresh(&f.ui,&state,|id|f.controller.profile(id));f.ui.window().take_snapshot().unwrap();
    let checking=ElementHandle::find_by_accessible_label(&f.ui,"检查中…").find(|control|control.accessible_role()==Some(i_slint_backend_testing::AccessibleRole::Button));
    assert!(checking.is_some_and(|control|control.accessible_enabled()==Some(false)),"an unchecked device offered Connect or Pair instead of its pending check");
    state["devices"][0]["online"]=json!(true);let rows:Vec<rhremote_core::Device>=serde_json::from_value(state["devices"].clone()).unwrap();f.controller.replace_devices_pending(rows);f.refresh();f.capture("startup-unverified-online");
    f.click("检查连接");assert_eq!(f.queued.borrow()[0].1,"host");assert_eq!(f.flush()["ok"],true);f.refresh();
    f.click("配对");assert_eq!(f.flush()["ok"],true);assert_eq!(f.controller.state()["page"],"pair");
}

#[test]
fn startup_controls_expose_each_verified_device_before_the_remaining_checks_finish(){
    let mut f=Fixture::new();f.ui.set_app_version(env!("CARGO_PKG_VERSION").into());f.ui.set_isolated_preview(true);
    if std::env::var("RHREMOTE_STARTUP_TEST_DARK").as_deref()==Ok("1"){f.controller.dispatch("appearance.toggle",json!({}));}
    if std::env::var("RHREMOTE_REFRESH_TEST_WIDTH").as_deref()==Ok("720"){f.ui.window().set_size(LogicalSize::new(720.,560.));}
    let mut rows:Vec<rhremote_core::Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();let mut verified=rows[0].clone();verified.paired=true;verified.online=true;
    for row in &mut rows{row.paired=false;row.online=false;}f.controller.replace_devices_pending(rows);f.refresh();f.capture("startup-unchecked");
    assert_eq!(f.controller.state()["devicesPending"],json!(["host","second"]));assert!(ElementHandle::find_by_accessible_label(&f.ui,"检查连接").any(|control|control.accessible_enabled()==Some(true)));
    assert_eq!(f.controller.dispatch("device.connect",json!({"uuid":"host"}))["ok"],false);
    let(sender,receiver)=std::sync::mpsc::sync_channel(8);let queue={let c=f.controller.clone();std::thread::spawn(move||crate::action_queue::run(c,receiver))};let weak=f.ui.as_weak();
    f.ui.global::<Commands>().on_trigger(move|id,arg|{let owner=weak.upgrade().and_then(|ui|crate::request_owner(&ui,id.as_str()));sender.send((id.to_string(),arg.to_string(),0,std::time::Instant::now(),owner)).unwrap();});
    let(entered,started)=std::sync::mpsc::channel();let(release,released)=std::sync::mpsc::channel();*f.platform.refresh_progress.lock().unwrap()=Some(rhremote_core::DeviceObservation{device:verified,pairing_verified:true,check:None});
    *f.platform.gate.lock().unwrap()=Some(Arc::new(Gate{operation:"devices.refresh",entered,released:Mutex::new(released)}));
    f.click("刷新");started.recv_timeout(std::time::Duration::from_secs(2)).unwrap();f.refresh();f.capture("startup-first-verified");
    let state=f.controller.state();assert_eq!(state["devicesRefreshing"],true);assert_eq!(state["devicesPending"],json!(["second"]));assert!(ElementHandle::find_by_accessible_label(&f.ui,"连接").any(|control|control.accessible_enabled()==Some(true)));
    f.click("连接");let deadline=std::time::Instant::now()+std::time::Duration::from_secs(2);while !f.platform.calls.lock().unwrap().iter().any(|(id,_)|id=="device.connect")&&std::time::Instant::now()<deadline{std::thread::sleep(std::time::Duration::from_millis(5));}
    let accepted=f.controller.state();release.send(()).unwrap();*f.platform.gate.lock().unwrap()=None;f.ui.global::<Commands>().on_trigger(|_,_|{});queue.join().unwrap();
    assert_eq!(accepted["connecting"],true);assert_eq!(accepted["devicesRefreshing"],true);assert_eq!(accepted["connection"]["uuid"],"host");
    let deadline=std::time::Instant::now()+std::time::Duration::from_secs(2);while f.controller.state()["devicesRefreshing"]==true&&std::time::Instant::now()<deadline{std::thread::sleep(std::time::Duration::from_millis(5));}
    assert_eq!(f.controller.state()["devicesPending"],json!(["second"]));assert_eq!(f.controller.state()["devices"][0]["paired"],true);
}

#[test]
fn a_startup_button_cannot_change_its_command_or_device_during_one_pointer_press(){
    let mut f=Fixture::new();let mut rows:Vec<rhremote_core::Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();let verified=rows[0].clone();rows[0].paired=false;rows[0].online=false;f.controller.replace_devices_pending(rows);f.refresh();
    let button=ElementHandle::find_by_accessible_label(&f.ui,"检查连接").find(|element|element.accessible_role()==Some(i_slint_backend_testing::AccessibleRole::Button)).unwrap();let origin=button.absolute_position();let size=button.size();let position=LogicalPosition::new(origin.x+size.width/2.,origin.y+size.height/2.);
    f.ui.window().dispatch_event(WindowEvent::PointerMoved{position});f.ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});
    *f.platform.refresh_progress.lock().unwrap()=Some(rhremote_core::DeviceObservation{device:verified,pairing_verified:true,check:None});assert_eq!(f.controller.dispatch("devices.refresh",json!({}))["ok"],true);f.refresh();
    f.ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
    assert!(f.queued.borrow().is_empty(),"releasing a check press unexpectedly started a connection");
    f.click("连接");let(id,arg,_)=f.queued.borrow_mut().remove(0);assert_eq!(id,"device.connect");assert_eq!(arg,"host");
    let button=ElementHandle::find_by_accessible_label(&f.ui,"连接").find(|element|element.accessible_role()==Some(i_slint_backend_testing::AccessibleRole::Button)).unwrap();let origin=button.absolute_position();let size=button.size();let position=LogicalPosition::new(origin.x+size.width/2.,origin.y+size.height/2.);
    f.ui.window().dispatch_event(WindowEvent::PointerMoved{position});f.ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});assert_eq!(f.controller.dispatch("device.select",json!({"uuid":"second"}))["ok"],true);f.refresh();f.ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
    assert!(f.queued.borrow().is_empty(),"releasing a press connected a different device");f.click("连接");let(id,arg,_)=f.queued.borrow_mut().remove(0);assert_eq!(id,"device.connect");assert_eq!(arg,"second");
}

#[test]
fn actual_navigation_remains_usable_during_a_production_queued_device_refresh(){
    let mut f=Fixture::new();f.ui.set_app_version(env!("CARGO_PKG_VERSION").into());f.ui.set_isolated_preview(true);
    if let Ok(width)=std::env::var("RHREMOTE_REFRESH_TEST_WIDTH"){assert_eq!(width,"720");f.ui.window().set_size(LogicalSize::new(720.,560.));}
    let (sender,receiver)=std::sync::mpsc::sync_channel(8);let queue={let c=f.controller.clone();std::thread::spawn(move||crate::action_queue::run(c,receiver))};
    let weak=f.ui.as_weak();f.ui.global::<Commands>().on_trigger(move|id,arg|{let owner=weak.upgrade().and_then(|ui|crate::request_owner(&ui,id.as_str()));sender.send((id.to_string(),arg.to_string(),0,std::time::Instant::now(),owner)).unwrap();});
    for dark in [false,true]{
        if f.controller.state()["saved"]["dark"]!=dark{f.controller.dispatch("appearance.toggle",json!({}));}f.refresh();
        let before=f.controller.state();let saved=std::fs::read(&f.storage).unwrap();
        let(entered,started)=std::sync::mpsc::channel();let(release,released)=std::sync::mpsc::channel();
        *f.platform.gate.lock().unwrap()=Some(Arc::new(Gate{operation:"devices.refresh",entered,released:Mutex::new(released)}));
        f.click("刷新");started.recv_timeout(std::time::Duration::from_secs(2)).unwrap();f.refresh();f.click("设置");
        let deadline=std::time::Instant::now()+std::time::Duration::from_millis(500);
        while f.controller.state()["page"]!="global-settings"&&std::time::Instant::now()<deadline{std::thread::sleep(std::time::Duration::from_millis(5));}
        let state=f.controller.state();f.refresh();f.capture(if dark{"queue-dark-settings-during-refresh"}else{"queue-light-settings-during-refresh"});
        release.send(()).unwrap();let deadline=std::time::Instant::now()+std::time::Duration::from_secs(2);
        while f.controller.state()["devicesRefreshing"]==true&&std::time::Instant::now()<deadline{std::thread::sleep(std::time::Duration::from_millis(5));}
        *f.platform.gate.lock().unwrap()=None;
        assert_eq!(state["page"],"global-settings","the real Settings control waited behind a network probe");assert_eq!(state["devicesRefreshing"],true);
        assert_eq!(f.controller.state()["devicesRefreshing"],false);assert_eq!(f.controller.state()["saved"],before["saved"]);assert_eq!(std::fs::read(&f.storage).unwrap(),saved);
        f.controller.dispatch("devices.open",json!({}));f.refresh();assert_eq!(f.controller.state()["selected"],before["selected"]);
    }
    f.ui.global::<Commands>().on_trigger(|_,_|{});queue.join().unwrap();
}

#[test]
fn device_refresh_controls_show_pending_failure_and_retry_without_losing_context(){
    let mut f=Fixture::new();
    f.ui.set_app_version(env!("CARGO_PKG_VERSION").into());
    f.ui.set_isolated_preview(true);
    if let Ok(width)=std::env::var("RHREMOTE_REFRESH_TEST_WIDTH") {
        assert_eq!(width,"720");f.ui.window().set_size(LogicalSize::new(720.,560.));
    }
    for dark in [false,true]{
        if f.controller.state()["saved"]["dark"]!=dark{f.controller.dispatch("appearance.toggle",json!({}));}
        f.controller.dispatch("device.select",json!({"uuid":"host"}));f.controller.dispatch("devices.filter",json!({"query":"winserver"}));f.refresh();
        let before=f.controller.state();let original_file=std::fs::read(&f.storage).unwrap();
        let(entered,started)=std::sync::mpsc::channel();let(release,released)=std::sync::mpsc::channel();
        *f.platform.gate.lock().unwrap()=Some(Arc::new(Gate{operation:"devices.refresh",entered,released:Mutex::new(released)}));
        f.click("刷新");let(id,arg,owner)=f.queued.borrow_mut().remove(0);let controller=f.controller.clone();
        let worker=std::thread::spawn(move||controller.dispatch(&id,crate::queued_args(&id,&arg,&controller.state(),owner)));
        assert_ne!(started.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),std::thread::current().id());f.refresh();
        let pending=ElementHandle::find_by_accessible_label(&f.ui,"刷新中…").find(|v|v.accessible_role()==Some(i_slint_backend_testing::AccessibleRole::Button));
        let disabled=pending.as_ref().is_some_and(|v|v.accessible_enabled()==Some(false));
        f.capture(if dark{"refresh-dark-pending"}else{"refresh-light-pending"});
        if let Some(button)=pending{button.invoke_accessible_default_action();}
        let no_duplicate=f.queued.borrow().is_empty();let busy=f.controller.state()["devicesRefreshing"].clone();
        release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);*f.platform.gate.lock().unwrap()=None;
        assert_eq!(busy,true);assert!(disabled,"actual refresh button did not show a disabled pending state");assert!(no_duplicate);f.refresh();
        assert!(ElementHandle::find_by_accessible_label(&f.ui,"刷新").any(|v|v.accessible_enabled()==Some(true)));
        let original_status=f.controller.state()["status"].clone();
        f.platform.refresh_failure.store(true,Ordering::SeqCst);f.click("刷新");let failure=f.flush();assert_eq!(failure["ok"],false);crate::report_queued_error(&f.controller,"devices.refresh",&failure);f.refresh();
        assert_eq!(f.controller.state()["status"],original_status,"the production queue leaked a local refresh failure into unrelated global status");
        f.capture(if dark{"refresh-dark-failed"}else{"refresh-light-failed"});
        assert!(ElementHandle::find_by_accessible_label(&f.ui,"测试设备状态请求超时").next().is_some(),"refresh error was not visible beside its retry action");
        assert!(ElementHandle::find_by_accessible_label(&f.ui,"刷新").any(|v|v.accessible_enabled()==Some(true)));
        f.controller.dispatch("app.settings.open",json!({}));f.refresh();f.controller.dispatch("devices.open",json!({}));f.refresh();
        assert!(ElementHandle::find_by_accessible_label(&f.ui,"测试设备状态请求超时").next().is_some());
        f.platform.refresh_failure.store(false,Ordering::SeqCst);f.click("刷新");assert_eq!(f.flush()["ok"],true);f.refresh();
        f.capture(if dark{"refresh-dark-retried"}else{"refresh-light-retried"});
        assert!(ElementHandle::find_by_accessible_label(&f.ui,"测试设备状态请求超时").next().is_none());
        let after=f.controller.state();assert_eq!(after["selected"],before["selected"]);assert_eq!(after["deviceQuery"],before["deviceQuery"]);assert_eq!(after["saved"],before["saved"]);assert_eq!(std::fs::read(&f.storage).unwrap(),original_file);
    }
}

fn accessible_controls(ui:&MainWindow)->Vec<(String,ElementHandle,bool)>{
    i_slint_backend_testing::ElementQuery::from_root(ui).find_all().into_iter().filter_map(|element|{
        let id=element.accessible_id()?.to_string();
        if !["action|","nav|","device|","input|","choice|","check|","spin|"].iter().any(|prefix|id.starts_with(prefix)){return None;}
        let enabled=element.accessible_enabled().unwrap_or(true);Some((id,element,enabled))
    }).collect()
}

#[test]
fn modal_accessibility_disables_background_without_losing_the_profile_editor(){
    let mut f=Fixture::new();
    for dark in [false,true]{
        if f.controller.state()["saved"]["dark"]!=dark{f.controller.dispatch("appearance.toggle",json!({}));}
        f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));f.controller.dispatch("settings.input",json!({"key":"fps","value":"24"}));f.refresh();
        let editor=ElementHandle::find_by_accessible_label(&f.ui,"帧率").find(|v|v.accessible_read_only().is_some()).expect("actual frame-rate editor");
        let p=editor.absolute_position();let size=editor.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
        f.ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});f.ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});f.refresh();
        assert_eq!(f.ui.global::<crate::KeyboardFocus>().get_last_content_control(),"input|帧率");
        let background=accessible_controls(&f.ui);assert!(background.iter().any(|(id,_,enabled)|id=="input|帧率"&&*enabled));let saved=f.controller.state()["saved"].clone();
        f.controller.dispatch("devices.open",json!({}));f.refresh();assert!(f.ui.get_navigation_prompt());
        for(id,element,_)in &background{assert_eq!(element.accessible_enabled(),Some(false),"modal exposes an enabled background control: {id}");}
        let choices:Vec<_>=accessible_controls(&f.ui).into_iter().filter(|(id,_,_)|id.starts_with("action|navigation.resolve|")).collect();assert_eq!(choices.len(),3);assert!(choices.iter().all(|(_,_,enabled)|*enabled));
        for(id,element,_)in &background{if id.starts_with("action|")||id.starts_with("nav|")||id.starts_with("device|"){element.invoke_accessible_default_action();}}
        assert!(f.queued.borrow().is_empty(),"a late background accessibility action reached the command queue");assert_eq!(f.controller.state()["saved"],saved);
        f.click("继续编辑");assert_eq!(f.flush()["ok"],true);f.refresh();
        for(id,element,enabled)in &background{assert_eq!(element.accessible_enabled().unwrap_or(true),*enabled,"closing the decision did not restore {id}");}
        f.ui.window().dispatch_event(WindowEvent::KeyPressed{text:"7".into()});f.ui.window().dispatch_event(WindowEvent::KeyReleased{text:"7".into()});
        assert_eq!(f.queued.borrow().len(),1,"the original editor did not regain its input focus");assert_eq!(f.queued.borrow()[0].0,"settings.input");assert_eq!(f.flush()["ok"],true);assert_eq!(f.controller.state()["saved"],saved);
        f.controller.dispatch("settings.cancel",json!({}));f.refresh();
    }
}

#[test]
fn modal_accessibility_keeps_only_current_sync_decisions_enabled(){
    let mut f=Fixture::new();
    for dark in [false,true]{
        if f.controller.state()["saved"]["dark"]!=dark{f.controller.dispatch("appearance.toggle",json!({}));}
        f.controller.dispatch("app.settings.tab",json!({"tab":"sync"}));
        assert_eq!(f.controller.dispatch("sync.configure",json!({"url":"http://example.test/dav/","username":"fixture","password":"fixture-only","automatic":false,"intervalMinutes":15}))["ok"],true);f.refresh();
        let background=accessible_controls(&f.ui);assert!(background.iter().any(|(id,_,enabled)|id.starts_with("input|")&&*enabled));let original=f.platform.saved.lock().unwrap().clone();
        f.controller.dispatch("sync.clear",json!({}));f.refresh();assert!(f.ui.get_sync_clear_prompt());
        for(id,element,_)in &background{assert_eq!(element.accessible_enabled(),Some(false),"removal exposes an enabled background control: {id}");}
        let choices:Vec<_>=accessible_controls(&f.ui).into_iter().filter(|(id,_,_)|id.starts_with("action|sync.clear.resolve|")).collect();assert_eq!(choices.len(),2);assert!(choices.iter().all(|(_,_,enabled)|*enabled));
        for(id,element,_)in &background{if id.starts_with("action|")||id.starts_with("nav|"){element.invoke_accessible_default_action();}}
        assert!(f.queued.borrow().is_empty());f.click("取消");assert_eq!(f.flush()["ok"],true);f.refresh();
        for(id,element,enabled)in &background{assert_eq!(element.accessible_enabled().unwrap_or(true),*enabled,"removal cancellation did not restore {id}");}
        assert_eq!(*f.platform.saved.lock().unwrap(),original);assert_eq!(f.platform.removals.load(Ordering::SeqCst),0);
        f.controller.dispatch("devices.open",json!({}));f.refresh();
    }
}

#[test]
fn modal_keyboard_queues_the_same_owned_action_before_resuming_a_connection() {
    let mut f=Fixture::new();
    f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));
    f.controller.dispatch("settings.input",json!({"key":"fps","value":"24"}));
    assert_eq!(f.controller.dispatch("device.connect",json!({"uuid":"host"}))["ok"],true);f.refresh();
    let pending=f.controller.state()["navigationPrompt"].clone();let calls=f.platform.calls.lock().unwrap().clone();
    for _ in 0..2{crate::ui_keyboard::modal(&f.ui,"Tab",true,false,false);}
    assert_eq!(crate::ui_keyboard::send(&f.ui,"Enter",false,false),Some("navigation.resolve"));
    assert_eq!(f.queued.borrow().len(),1,"Keyboard confirmation bypassed the shared UI command queue");
    assert_eq!(f.controller.state()["navigationPrompt"],pending,"The event callback must not execute the pending action");
    assert_eq!(*f.platform.calls.lock().unwrap(),calls,"The UI event called the platform before a worker accepted its command");
    let (id,arg,owner)=f.queued.borrow_mut().remove(0);assert_eq!(id,"navigation.resolve");assert_eq!(arg,"save");assert_eq!(owner.as_deref(),pending["requestId"].as_str());
    let (entered,started)=std::sync::mpsc::channel();let(release,released)=std::sync::mpsc::channel();
    *f.platform.gate.lock().unwrap()=Some(Arc::new(Gate{operation:"device.connect",entered,released:Mutex::new(released)}));
    let controller=f.controller.clone();let worker=std::thread::spawn(move||controller.dispatch(&id,crate::queued_args(&id,&arg,&controller.state(),owner)));
    assert_ne!(started.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),std::thread::current().id());
    f.refresh();assert!(f.controller.state()["navigationPrompt"].is_null());assert!(f.ui.get_connecting());
    crate::ui_keyboard::send_to_window(f.ui.window(),"Tab",false,false);f.ui.window().take_snapshot().unwrap();
    assert!(!worker.is_finished(),"The renderer was only exercised after platform work ended");release.send(()).unwrap();
    assert_eq!(worker.join().unwrap()["ok"],true);assert_eq!(f.controller.profile("host").fps,24);
    let calls=f.platform.calls.lock().unwrap();let resumed=calls.iter().filter(|(id,_)|id=="device.connect").collect::<Vec<_>>();assert_eq!(resumed.len(),1);assert_ne!(resumed[0].1,std::thread::current().id());
}

#[test]
fn window_close_keeps_the_view_live_while_native_cleanup_runs_on_a_worker() {
    let mut f=Fixture::new();f.controller.dispatch("device.connect",json!({"uuid":"host"}));
    f.controller.stream_state(true,json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}));f.refresh();
    let before=f.platform.calls.lock().unwrap().len();
    assert!(matches!(crate::request_close(&f.ui),slint::CloseRequestResponse::KeepWindowShown));
    assert_eq!(f.platform.calls.lock().unwrap().len(),before);assert_eq!(f.queued.borrow().len(),1);
    let(id,arg,owner)=f.queued.borrow_mut().remove(0);assert_eq!(id,"window.close");
    let(entered,started)=std::sync::mpsc::channel();let(release,released)=std::sync::mpsc::channel();
    *f.platform.gate.lock().unwrap()=Some(Arc::new(Gate{operation:"engine.close",entered,released:Mutex::new(released)}));
    let controller=f.controller.clone();let worker=std::thread::spawn(move||controller.dispatch(&id,crate::queued_args(&id,&arg,&controller.state(),owner)));
    assert_ne!(started.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),std::thread::current().id());
    f.refresh();f.ui.window().take_snapshot().unwrap();assert!(!worker.is_finished());assert_eq!(f.controller.take_window_action(),"");
    release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);assert_eq!(f.controller.take_window_action(),"window.close");
}

#[test]
fn modal_space_resolves_each_navigation_choice_once_without_repeating_or_releasing_into_the_page(){
    let mut f=Fixture::new();
    for choice in 0..3 {
        f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));
        f.controller.dispatch("settings.input",json!({"key":"fps","value":(24+choice).to_string()}));
        f.controller.dispatch("devices.open",json!({}));f.refresh();
        for _ in 0..choice {crate::ui_keyboard::modal(&f.ui,"Tab",true,false,false);}
        let pending=f.controller.state()["navigationPrompt"].clone();
        crate::ui_keyboard::modal(&f.ui,"Space",true,true,false);
        crate::ui_keyboard::modal(&f.ui,"Space",false,false,false);
        assert_eq!(f.controller.state()["navigationPrompt"],pending);
        assert_eq!(crate::ui_keyboard::send(&f.ui,"Space",false,false),Some("navigation.resolve"));
        assert_eq!(f.flush()["ok"],true);let state=f.controller.state();assert!(state["navigationPrompt"].is_null());
        assert_eq!(state["page"],if choice==0{"settings"}else{"devices"});
        assert_eq!(f.controller.profile("host").fps,if choice==2{26}else{30});
        if choice==0 {assert_eq!(state["settingsInputs"]["fps"],"24");f.controller.dispatch("settings.cancel",json!({}));}
    }
}

#[test]
fn modal_space_resolves_sync_cancel_and_remove_using_the_current_decision(){
    let mut f=Fixture::new();
    f.controller.dispatch("app.settings.tab",json!({"tab":"sync"}));
    assert_eq!(f.controller.dispatch("sync.configure",json!({"url":"https://example.test/dav/","username":"fixture","password":"fixture-only","automatic":false,"intervalMinutes":15}))["ok"],true);
    for choice in 0..2 {
        assert_eq!(f.controller.dispatch("sync.clear",json!({}))["ok"],true);f.refresh();
        assert!(f.controller.state()["syncClearPrompt"].is_object(),"choice {choice}");
        if choice==1 {crate::ui_keyboard::modal(&f.ui,"Tab",true,false,false);}
        let pending=f.controller.state()["syncClearPrompt"].clone();
        crate::ui_keyboard::modal(&f.ui,"Space",true,true,false);
        crate::ui_keyboard::modal(&f.ui,"Space",false,false,false);
        assert_eq!(f.controller.state()["syncClearPrompt"],pending);
        assert_eq!(crate::ui_keyboard::send(&f.ui,"Space",false,false),Some("sync.clear.resolve"));
        assert_eq!(f.flush()["ok"],true);assert!(f.controller.state()["syncClearPrompt"].is_null());
        assert_eq!(f.platform.removals.load(Ordering::SeqCst),choice);
        assert_eq!(f.controller.state()["sync"]["hasPassword"],choice==0);
    }
}

#[test]
fn queued_decision_buttons_cannot_save_a_replacement_navigation(){
    let mut f=Fixture::new();
    for (case,value) in [("queued",24),("visible",25)]{
        f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));f.controller.dispatch("settings.input",json!({"key":"fps","value":value.to_string()}));f.controller.dispatch("devices.open",json!({}));f.refresh();let old=f.ui.get_navigation_request_id();assert!(!old.is_empty());let before=f.controller.state()["saved"].clone();
        if case=="queued"{f.click("保存并继续");}
        f.controller.dispatch("navigation.resolve",json!({"choice":"stay"}));f.controller.dispatch("device.settings.open",json!({"uuid":"second"}));let current=f.controller.state()["navigationPrompt"].clone();assert_ne!(old.as_str(),current["requestId"].as_str().unwrap());
        if case=="visible"{f.click("保存并继续");}
        assert_eq!(f.flush()["ok"],false);assert_eq!(f.controller.state()["navigationPrompt"],current);assert_eq!(f.controller.state()["saved"],before);assert_eq!(f.controller.state()["selected"],"host");assert_eq!(f.ui.get_navigation_choice(),2);
        // Enter from the not-yet-redrawn dialog must carry its old identifier too.
        for key in ["Enter","Space"]{crate::ui_keyboard::modal(&f.ui,key,true,false,false);assert_eq!(f.flush()["ok"],false);assert_eq!(f.controller.state()["navigationPrompt"],current);assert_eq!(f.controller.state()["saved"],before);}
        f.refresh();assert_eq!(f.ui.get_navigation_choice(),0);assert_eq!(f.ui.get_navigation_request_id(),current["requestId"].as_str().unwrap());f.capture(&format!("navigation-{case}"));
        f.click("保存并继续");assert_eq!(f.flush()["ok"],true);assert_eq!(f.controller.profile("host").fps,value);assert_eq!(f.controller.state()["selected"],"second");assert_eq!(f.controller.profile("second").fps,30);
    }
}

#[test]
fn queued_decision_buttons_cannot_remove_a_replacement_sync_account(){
    let mut f=Fixture::new();f.controller.dispatch("app.settings.tab",json!({"tab":"sync"}));assert_eq!(f.controller.dispatch("sync.configure",json!({"url":"https://example.test/dav/","username":"fixture","password":"fixture-only","automatic":false,"intervalMinutes":15}))["ok"],true);
    f.controller.dispatch("sync.clear",json!({}));f.refresh();let old=f.ui.get_sync_clear_request_id();f.controller.dispatch("sync.clear",json!({}));assert_eq!(f.controller.state()["syncClearPrompt"]["requestId"],old.as_str());let before=f.platform.saved.lock().unwrap().clone();f.click("移除");
    f.controller.dispatch("sync.clear.resolve",json!({"choice":"cancel"}));f.controller.dispatch("sync.clear",json!({}));let current=f.controller.state()["syncClearPrompt"].clone();assert_ne!(old.as_str(),current["requestId"].as_str().unwrap());assert_eq!(f.flush()["ok"],false);assert_eq!(*f.platform.saved.lock().unwrap(),before);assert_eq!(f.platform.removals.load(Ordering::SeqCst),0);
    for key in ["Enter","Space"]{crate::ui_keyboard::modal(&f.ui,key,true,false,false);assert_eq!(f.flush()["ok"],false);assert_eq!(f.controller.state()["syncClearPrompt"],current);assert_eq!(f.platform.removals.load(Ordering::SeqCst),0);}
    f.refresh();assert_eq!(f.ui.get_sync_clear_choice(),0);f.capture("sync-removal-current");crate::ui_keyboard::modal(&f.ui,"Enter",true,false,false);assert_eq!(f.flush()["ok"],true);assert!(f.controller.state()["syncClearPrompt"].is_null());assert_eq!(*f.platform.saved.lock().unwrap(),before);
    f.controller.dispatch("sync.clear",json!({}));f.refresh();f.click("移除");assert_eq!(f.flush()["ok"],true);assert_eq!(f.platform.removals.load(Ordering::SeqCst),1);assert_eq!(f.controller.state()["sync"]["hasPassword"],false);
}

#[test]
fn queued_profile_steps_cannot_edit_another_devices_draft(){
    let mut f=Fixture::new();
    for case in ["queued","visible"]{
        assert_eq!(f.controller.dispatch("device.settings.open",json!({"uuid":"host"}))["ok"],true);f.refresh();
        let field=f.ui.get_profile_fields().iter().find(|v|v.key=="fps").unwrap();let label=format!("增加{}",field.label);
        if case=="queued"{f.click(&label);}
        assert_eq!(f.controller.dispatch("device.settings.open",json!({"uuid":"second"}))["ok"],true);
        let before=f.controller.state();
        if case=="visible"{f.click(&label);}
        let result=f.flush();assert_eq!(result["ok"],false,"old {case} step changed the replacement draft");assert_eq!(result["ignored"],true);
        let after=f.controller.state();assert_eq!(after["settingsInputs"],before["settingsInputs"]);assert_eq!(after["draft"],before["draft"]);assert_eq!(after["settingsError"],before["settingsError"]);
        f.refresh();f.click(&label);assert_eq!(f.flush()["ok"],true);assert_ne!(f.controller.state()["settingsInputs"],before["settingsInputs"]);
        assert_eq!(f.controller.dispatch("settings.cancel",json!({}))["ok"],true);
    }
}

#[test]
fn queued_profile_save_cannot_commit_another_devices_draft(){
    let mut f=Fixture::new();f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));f.controller.dispatch("settings.input",json!({"key":"fps","value":"24"}));f.refresh();f.click("保存");
    f.controller.dispatch("settings.cancel",json!({}));f.controller.dispatch("device.settings.open",json!({"uuid":"second"}));f.controller.dispatch("settings.input",json!({"key":"fps","value":"23"}));let before=f.controller.state();let saved=std::fs::read(&f.storage).unwrap();
    let result=f.flush();assert_eq!(result["ok"],false,"old save committed the newly selected device");assert_eq!(result["ignored"],true);assert_eq!(f.controller.state()["page"],"settings");assert_eq!(f.controller.state()["settingsInputs"],before["settingsInputs"]);assert_eq!(std::fs::read(&f.storage).unwrap(),saved);
    f.refresh();f.capture("profile-current-save");f.click("保存");assert_eq!(f.flush()["ok"],true);assert_eq!(f.controller.profile("host").fps,30);assert_eq!(f.controller.profile("second").fps,23);
}

#[test]
fn queued_profile_cancel_cannot_discard_a_reopened_draft_of_the_same_device(){
    let mut f=Fixture::new();f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));f.refresh();f.click("取消");
    f.controller.dispatch("settings.cancel",json!({}));f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));f.controller.dispatch("settings.input",json!({"key":"fps","value":"23"}));let before=f.controller.state();
    let result=f.flush();assert_eq!(result["ok"],false,"old cancel discarded a later draft of the same device");assert_eq!(result["ignored"],true);assert_eq!(f.controller.state()["page"],"settings");assert_eq!(f.controller.state()["settingsInputs"],before["settingsInputs"]);assert_eq!(f.controller.state()["settingsError"],before["settingsError"]);
    f.refresh();f.click("取消");assert_eq!(f.flush()["ok"],true);assert_eq!(f.controller.state()["page"],"devices");assert_eq!(f.controller.profile("host").fps,30);
}

#[test]
fn queued_profile_text_cannot_reach_a_different_devices_editor(){
    let mut f=Fixture::new();f.controller.dispatch("device.settings.open",json!({"uuid":"host"}));f.refresh();
    let label=f.ui.get_profile_fields().iter().find(|v|v.key=="width").unwrap().label;
    let type_digit=|f:&Fixture,digit:&str|{
        let editor=ElementHandle::find_by_accessible_label(&f.ui,label.as_str()).find(|v|v.accessible_read_only().is_some()).expect("actual width LineEdit");let p=editor.absolute_position();let size=editor.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
        f.ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});f.ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
        f.ui.window().dispatch_event(WindowEvent::KeyPressed{text:digit.into()});f.ui.window().dispatch_event(WindowEvent::KeyReleased{text:digit.into()});assert_eq!(f.queued.borrow()[0].0,"settings.input");
    };
    type_digit(&f,"1");f.controller.dispatch("device.settings.open",json!({"uuid":"second"}));let before=f.controller.state();assert_eq!(f.flush()["ignored"],true);assert_eq!(f.controller.state()["settingsInputs"],before["settingsInputs"]);
    f.refresh();type_digit(&f,"2");let expected=f.queued.borrow()[0].1.strip_prefix("width=").unwrap().to_owned();assert_eq!(f.flush()["ok"],true);assert_eq!(f.controller.state()["settingsInputs"]["width"],expected);
}
