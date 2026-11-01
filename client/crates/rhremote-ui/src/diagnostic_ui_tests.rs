//! Actual Slint buttons, input bindings and Controller; the network boundary is gated.
use super::*;
use std::{sync::{mpsc,atomic::AtomicUsize},time::Instant};
use slint::platform::Key;
struct Gate {entered:mpsc::Sender<Value>,started:Mutex<mpsc::Receiver<Value>>,release:mpsc::Sender<()>,released:Mutex<mpsc::Receiver<()>>,cleaning:mpsc::Sender<()>,began:Mutex<mpsc::Receiver<()>>,finish:mpsc::Sender<()>,finished:Mutex<mpsc::Receiver<()>>,requests:AtomicUsize,cleanups:AtomicUsize,fail:AtomicBool}
impl Gate {
    fn new()->Arc<Self>{let(entered,started)=mpsc::channel();let(release,released)=mpsc::channel();let(cleaning,began)=mpsc::channel();let(finish,finished)=mpsc::channel();Arc::new(Self{entered,started:Mutex::new(started),release,released:Mutex::new(released),cleaning,began:Mutex::new(began),finish,finished:Mutex::new(finished),requests:AtomicUsize::new(0),cleanups:AtomicUsize::new(0),fail:AtomicBool::new(false)})}
    fn entered(&self)->Value{self.started.lock().unwrap().recv_timeout(Duration::from_secs(3)).unwrap()}
    fn release(&self){self.release.send(()).unwrap();self.began.lock().unwrap().recv_timeout(Duration::from_secs(3)).unwrap();}
    fn finish(&self){self.finish.send(()).unwrap();}
}
struct Host(Arc<Gate>);struct Task{gate:Arc<Gate>,args:Value}
impl rhremote_core::DiagnosticTask for Task {
    fn run(&mut self,cancel:Arc<AtomicBool>)->Result<Value,String>{self.gate.requests.fetch_add(1,Ordering::SeqCst);self.gate.entered.send(self.args.clone()).unwrap();self.gate.released.lock().unwrap().recv_timeout(Duration::from_secs(8)).unwrap();if cancel.load(Ordering::SeqCst){Err("已取消诊断".into())}else{Ok(json!({"uuid":self.args["device"]["uuid"],"selectedAddress":self.args["device"]["address"],"route":"lan","path":"direct","attempts":[{"address":self.args["device"]["address"],"ok":true,"elapsedMs":1}]}))}}
    fn cleanup(&mut self)->Result<(),String>{self.gate.cleanups.fetch_add(1,Ordering::SeqCst);self.gate.cleaning.send(()).unwrap();self.gate.finished.lock().unwrap().recv_timeout(Duration::from_secs(8)).unwrap();if self.gate.fail.swap(false,Ordering::SeqCst){Err("fixture cleanup failure".into())}else{Ok(())}}
}
impl Platform for Host {
    fn execute(&self,id:&str,_:&Value)->Result<Value,String>{Ok(if id=="updates.state"{json!({"managed":false,"phase":"idle"})}else{json!({})})}
    fn prepare_diagnostic(&self,device:&Device,profile:&Profile)->Option<Result<Box<dyn rhremote_core::DiagnosticTask>,String>>{Some(Ok(Box::new(Task{gate:self.0.clone(),args:json!({"device":device,"profile":profile})})))}
}
fn backend(){slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();}
fn setup(width:f32,height:f32,dark:bool)->(Flow,Arc<Gate>){let gate=Gate::new();let mut f=Flow::with_platform(width,height,Arc::new(Boundary::default()),Arc::new(Host(gate.clone())));if dark{f.input("appearance.toggle","");}f.input("device.settings.open","host");f.input("settings.tab","network");(f,gate)}
fn args(f:&Flow,id:&str)->String{match id{"device.network.diagnose"=>f.ui.get_diagnostic_start_args().to_string(),"device.network.cancel"=>f.ui.get_diagnostic_cancel_args().to_string(),"device.network.details.toggle"=>f.ui.get_diagnostic_details_args().to_string(),_=>String::new()}}
fn settle(f:&mut Flow,phase:&str){let until=Instant::now()+Duration::from_secs(3);while f.controller.state()["diagnostics"]["phase"]!=phase{assert!(Instant::now()<until,"{}",f.controller.state());std::thread::sleep(Duration::from_millis(2));}f.refresh();}
fn reveal(f:&mut Flow,label:&str,id:&str)->ElementHandle{
    for _ in 0..5{let button=f.button(label,id,"");let p=button.absolute_position();let size=button.size();let window=f.ui.window().size();
        let network=f.controller.state()["page"]=="settings"&&f.controller.state()["profileTab"]=="network";
        let(top,bottom)=if network{let viewport=ElementHandle::find_by_element_id(&f.ui,"MainWindow::form-scroll").next().unwrap();let area=viewport.absolute_position();(area.y,area.y+viewport.size().height)}else{(0.,window.height as f32)};
        if p.y>=top&&p.y+size.height<=bottom&&p.x>=0.&&p.x+size.width<=window.width as f32{return button;}
        let viewport=ElementHandle::find_by_element_id(&f.ui,"MainWindow::form-scroll").next().unwrap();viewport.scroll(0.,top+12.-p.y);i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(500));f.refresh();
    }panic!("{label} never became fully visible");
}
fn key(f:&Flow,down:bool){f.ui.window().dispatch_event(if down{WindowEvent::KeyPressed{text:Key::Return.into()}}else{WindowEvent::KeyReleased{text:Key::Return.into()}});}
fn press(f:&mut Flow,button:&ElementHandle,keyboard:bool){
    if keyboard{let probe=f.ui.global::<crate::UiTest>();probe.set_enabled(true);probe.set_target(button.accessible_id().unwrap());probe.set_operation("focus".into());probe.set_generation(probe.get_generation()+1);f.ui.window().take_snapshot().unwrap();assert!(profile_focus_ids(&f.ui).contains(&button.accessible_id().unwrap().to_string()));key(f,true);}
    else{let p=button.absolute_position();let s=button.size();f.ui.window().dispatch_event(WindowEvent::PointerPressed{position:LogicalPosition::new(p.x+s.width/2.,p.y+s.height/2.),button:PointerEventButton::Left});}
}
fn release(f:&mut Flow,button:&ElementHandle,keyboard:bool){if keyboard{key(f,false);}else{let p=button.absolute_position();let s=button.size();f.ui.window().dispatch_event(WindowEvent::PointerReleased{position:LogicalPosition::new(p.x+s.width/2.,p.y+s.height/2.),button:PointerEventButton::Left});}}
fn click(f:&mut Flow,label:&str,id:&str,keyboard:bool)->Value{let button=reveal(f,label,id);let expected=args(f,id);assert!(button.accessible_enabled().unwrap());assert!(f.pending.borrow().is_empty());press(f,&button,keyboard);release(f,&button,keyboard);let events=std::mem::take(&mut*f.pending.borrow_mut());assert_eq!(events,vec![(id.to_owned(),expected.clone())]);f.command(id,&expected)}
fn record(rows:&mut Vec<Value>,f:&Flow,gate:&Gate,name:&str){f.capture(name);rows.push(json!({"case":name,"requests":gate.requests.load(Ordering::SeqCst),"cleanups":gate.cleanups.load(Ordering::SeqCst),"diagnostics":f.controller.state()["diagnostics"],"page":f.controller.state()["page"]}));}
fn save(name:&str,rows:&[Value]){if let Some(folder)=std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR"){let path=PathBuf::from(folder).join(format!("{name}.json"));assert!(!path.exists());std::fs::write(path,serde_json::to_vec_pretty(rows).unwrap()).unwrap();}}
fn summary_visible(f:&Flow){let summary=ElementHandle::find_by_element_id(&f.ui,"MainWindow::diagnostic-summary").next().unwrap();let viewport=ElementHandle::find_by_element_id(&f.ui,"MainWindow::form-scroll").next().unwrap();let p=summary.absolute_position();let area=viewport.absolute_position();assert!(p.y>=area.y-1.&&p.y+summary.size().height<=area.y+viewport.size().height+1.,"Diagnostic result is clipped: summary={p:?}/{:?} viewport={area:?}/{:?}",summary.size(),viewport.size());}

#[test]fn actual_diagnostic_buttons_cancel_retry_and_show_details_in_both_themes_and_sizes(){
    backend();let mut records=vec![];
    for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{for keyboard in [false,true]{
        let(mut f,gate)=setup(width,height,dark);let before=f.saved();let prefix=format!("diagnostic-{width}-{dark}-{keyboard}");
        assert_eq!(click(&mut f,"诊断连接","device.network.diagnose",keyboard)["ok"],true);let captured=gate.entered();assert_eq!(captured["device"]["uuid"],"host");assert_eq!(f.ui.get_diagnostic_busy(),true);record(&mut records,&f,&gate,&format!("{prefix}-running"));
        assert_eq!(f.controller.dispatch("device.network.diagnose",json!({"uuid":"host"}))["ok"],false);assert_eq!(click(&mut f,"取消诊断","device.network.cancel",keyboard)["ok"],true);gate.release();f.refresh();assert!(f.ui.get_diagnostic_busy());assert!(!f.ui.get_diagnostic_can_cancel());record(&mut records,&f,&gate,&format!("{prefix}-cleaning"));gate.finish();settle(&mut f,"cancelled");
        assert_eq!(click(&mut f,"诊断连接","device.network.diagnose",keyboard)["ok"],true);gate.entered();gate.release();gate.finish();settle(&mut f,"completed");summary_visible(&f);record(&mut records,&f,&gate,&format!("{prefix}-completed"));
        assert_eq!(click(&mut f,"查看详情","device.network.details.toggle",keyboard)["ok"],true);record(&mut records,&f,&gate,&format!("{prefix}-details"));assert_eq!(click(&mut f,"收起详情","device.network.details.toggle",keyboard)["ok"],true);summary_visible(&f);record(&mut records,&f,&gate,&format!("{prefix}-collapsed"));
        assert_eq!(f.saved(),before);assert_eq!(gate.requests.load(Ordering::SeqCst),2);
    }}}save("diagnostic-controls",&records);
}
#[test]fn background_diagnostic_has_the_right_device_and_cleanup_retry_on_other_pages(){
    backend();let mut records=vec![];
    for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{for keyboard in [false,true]{
        let(mut f,gate)=setup(width,height,dark);let before=f.saved();let prefix=format!("diagnostic-banner-{width}-{dark}-{keyboard}");gate.fail.store(true,Ordering::SeqCst);click(&mut f,"诊断连接","device.network.diagnose",keyboard);gate.entered();
        f.input("device.settings.open","claw");f.input("settings.tab","network");assert_eq!(f.ui.get_diagnostic_start_label(),"诊断连接");assert!(f.ui.get_diagnostic_start_disabled());assert!(f.ui.get_diagnostic_banner());assert!(f.ui.get_diagnostic_banner_text().contains("winserver"));record(&mut records,&f,&gate,&format!("{prefix}-other-device"));
        f.input("settings.cancel","");f.input("app.settings.tab","about");click(&mut f,"取消诊断","device.network.cancel",keyboard);gate.release();gate.finish();settle(&mut f,"cleanup-error");record(&mut records,&f,&gate,&format!("{prefix}-cleanup-error"));
        click(&mut f,"重试清理","device.network.cancel",keyboard);gate.began.lock().unwrap().recv_timeout(Duration::from_secs(3)).unwrap();gate.finish();settle(&mut f,"cancelled");assert_eq!(gate.requests.load(Ordering::SeqCst),1);assert_eq!(gate.cleanups.load(Ordering::SeqCst),2);assert_eq!(f.controller.state()["settingsTab"],"about");assert!(!f.ui.get_diagnostic_banner());assert_eq!(f.saved(),before);record(&mut records,&f,&gate,&format!("{prefix}-recovered"));
    }}}save("diagnostic-banner",&records);
}
#[test]fn held_diagnostic_controls_cannot_target_changed_settings_or_a_replacement_task(){
    backend();let mut records=vec![];
    for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{for keyboard in [false,true]{
        let(mut f,gate)=setup(width,height,dark);let before=f.saved();let old=args(&f,"device.network.diagnose");let button=reveal(&mut f,"诊断连接","device.network.diagnose");press(&mut f,&button,keyboard);f.input("settings.network.edit","address=192.0.2.9");release(&mut f,&button,keyboard);
        let events=std::mem::take(&mut*f.pending.borrow_mut());for(id,arg)in events{assert_eq!(arg,old,"A held button retargeted the new settings");assert_eq!(f.command(&id,&arg)["ok"],false);}assert_eq!(gate.requests.load(Ordering::SeqCst),0);
        f.input("settings.network.edit","address=192.0.2.1");click(&mut f,"诊断连接","device.network.diagnose",keyboard);gate.entered();let old=args(&f,"device.network.cancel");let button=reveal(&mut f,"取消诊断","device.network.cancel");press(&mut f,&button,keyboard);gate.release();gate.finish();settle(&mut f,"completed");
        // Start the replacement through the same public action while the old key is held.
        assert_eq!(f.command("device.network.diagnose",&args(&f,"device.network.diagnose"))["ok"],true);gate.entered();reveal(&mut f,"取消诊断","device.network.cancel");release(&mut f,&button,keyboard);
        let events=std::mem::take(&mut*f.pending.borrow_mut());for(id,arg)in events{assert_eq!(arg,old,"A held cancel retargeted the replacement task");assert_eq!(f.command(&id,&arg)["ok"],false);}assert_eq!(f.controller.state()["diagnostics"]["cancelRequested"],false);
        click(&mut f,"取消诊断","device.network.cancel",keyboard);gate.release();gate.finish();settle(&mut f,"cancelled");assert_eq!(gate.requests.load(Ordering::SeqCst),2);assert_eq!(f.saved(),before);record(&mut records,&f,&gate,&format!("diagnostic-held-{width}-{dark}-{keyboard}"));
    }}}save("diagnostic-held",&records);
}
#[test]fn diagnostic_completion_preserves_an_editor_or_manual_scroll_away_from_the_action_row(){
    backend();let mut records=vec![];
    for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{for focus_editor in [false,true]{
        let(mut f,gate)=setup(width,height,dark);let before=f.saved();click(&mut f,"诊断连接","device.network.diagnose",false);gate.entered();
        let viewport=ElementHandle::find_by_element_id(&f.ui,"MainWindow::form-scroll").next().unwrap();viewport.scroll(0.,100000.);i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(500));f.refresh();
        let editor=i_slint_backend_testing::ElementQuery::from_root(&f.ui).find_all().into_iter().find(|e|e.accessible_id().is_some_and(|id|id=="input|首选 IP 或主机名")).expect("Visible address editor after scrolling to the top");
        if focus_editor{let probe=f.ui.global::<crate::UiTest>();probe.set_enabled(true);probe.set_target(editor.accessible_id().unwrap());probe.set_operation("focus".into());probe.set_generation(probe.get_generation()+1);f.ui.window().take_snapshot().unwrap();}
        let position=editor.absolute_position();let focused=profile_focus_ids(&f.ui);
        gate.release();gate.finish();settle(&mut f,"completed");assert!((editor.absolute_position().y-position.y).abs()<1.,"A late result pulled the current editor away");
        let after=profile_focus_ids(&f.ui);if focus_editor{assert_eq!(after,focused);assert!(after.contains(&"input|首选 IP 或主机名".to_owned()));}else{
            // The same start button changes its caption from busy to idle. Its
            // action and exact bound arguments must retain focus, not its old caption.
            let identity=|ids:Vec<String>|ids.into_iter().map(|id|if id.starts_with("action|device.network.diagnose|"){id.rsplit_once('|').unwrap().0.to_owned()}else{id}).collect::<Vec<_>>();
            assert!(focused.iter().any(|id|id.starts_with("action|device.network.diagnose|")));assert_eq!(identity(after),identity(focused));
        }assert_eq!(f.saved(),before);record(&mut records,&f,&gate,&format!("diagnostic-scroll-{width}-{dark}-{focus_editor}"));
    }}}save("diagnostic-scroll",&records);
}
#[test]fn invalid_diagnostic_address_has_local_feedback_without_starting_a_task(){
    backend();for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{let(mut f,gate)=setup(width,height,dark);let before=f.saved();f.input("settings.network.edit","address=https://not-a-host/path");assert_eq!(click(&mut f,"诊断连接","device.network.diagnose",false)["ok"],false);assert!(!f.controller.state()["settingsError"].as_str().unwrap().is_empty());assert_eq!(f.controller.state()["diagnostics"]["operationId"],0);assert_eq!(gate.requests.load(Ordering::SeqCst),0);assert_eq!(f.saved(),before);f.capture(&format!("diagnostic-invalid-{width}-{dark}"));}}
}
