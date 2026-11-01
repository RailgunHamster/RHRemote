//! Own picker widgets, input events and queued UI API with the production Controller.
use super::*;
use i_slint_backend_testing::{ElementHandle,ElementQuery};
use slint::{ModelRc,VecModel,LogicalPosition,platform::{WindowEvent,PointerEventButton}};
use std::{collections::BTreeSet,path::PathBuf};

fn evidence()->Option<PathBuf>{
    let path=std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR").filter(|v|!v.is_empty()).map(PathBuf::from)?;
    assert!(path.is_absolute()&&path.is_dir());Some(path)
}
fn write(name:&str,value:&Value){if let Some(root)=evidence(){let file=root.join(name);assert!(!file.exists());std::fs::write(file,serde_json::to_vec_pretty(value).unwrap()).unwrap();}}
fn capture(f:&Fixture,name:&str){if let Some(root)=evidence(){let file=root.join(format!("{name}.png"));assert!(!file.exists());let frame=f.picker.window().take_snapshot().unwrap();image::save_buffer(file,frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();}}
fn model<T:Clone+'static>(rows:Vec<T>)->ModelRc<T>{ModelRc::new(VecModel::from(rows))}
fn backend(){slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();}
fn open(f:&mut Fixture,width:f32,height:f32,dark:bool){
    f.controller.stream_state(true,json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}));
    if f.controller.state()["controls"]!=true{assert_eq!(f.controller.dispatch("stream.controls.toggle",json!({}))["ok"],true);}
    assert_eq!(f.controller.dispatch("stream.menu.open",json!({"menu":"displays"}))["ok"],true);
    f.picker.global::<crate::Palette>().set_dark(dark);f.picker.set_kind("displays".into());f.picker.set_busy(false);f.picker.set_error("".into());f.picker.window().set_size(LogicalSize::new(width,height));f.picker.show().unwrap();
    f.calls.borrow_mut().clear();f.boundary.calls.lock().unwrap().clear();f.boundary.requests.lock().unwrap().clear();
}
fn rect(f:&Fixture,id:&str)->Value{
    let elements=ElementQuery::from_root(&f.picker).find_all();let target=elements.iter().find(|e|e.accessible_id().is_some_and(|v|v==id)).expect("Picker control missing");let p=target.absolute_position();let size=target.size();let bounds=f.picker.window().size();
    let(mut left,mut top,mut right,mut bottom)=(0f32,0f32,bounds.width as f32,bounds.height as f32);
    for parent in &elements{if parent.type_name().is_none_or(|v|v!="ScrollView")||parent.computed_opacity()<=0.{continue;}if parent.query_descendants().find_all().iter().any(|e|e.accessible_id().is_some_and(|v|v==id)){let q=parent.absolute_position();let area=parent.size();left=left.max(q.x);top=top.max(q.y);right=right.min(q.x+area.width);bottom=bottom.min(q.y+area.height);}}
    json!({"id":id,"x":p.x,"y":p.y,"width":size.width,"height":size.height,"viewport":{"left":left,"top":top,"right":right,"bottom":bottom},"fullyVisible":target.computed_opacity()>0.&&size.width>0.&&size.height>0.&&p.x>=left-1.&&p.y>=top-1.&&p.x+size.width<=right+1.&&p.y+size.height<=bottom+1.})
}

#[test]
fn picker_pointer_updates_preserve_the_pressed_target_and_never_send_a_replacement(){
    backend();let mut records=vec![];
    for(width,height)in[(320.,220.),(420.,400.)]{for dark in[false,true]{for change in["unchanged","other-row","value-only","producer-refresh","kind-change","label-change","busy-then-ready","disabled-then-ready"]{
        let mut f=Fixture::in_current_backend();open(&mut f,width,height,dark);let labels=Rc::new(VecModel::from(vec!["屏幕 A".into(),"屏幕 B".into()]));let values=Rc::new(VecModel::from(vec!["display-a".into(),"display-b".into()]));let enabled=Rc::new(VecModel::from(vec![true,true]));
        f.picker.set_labels(labels.clone().into());f.picker.set_values(values.clone().into());f.picker.set_enabled_items(enabled.clone().into());f.picker.window().take_snapshot().unwrap();
        let row=ElementHandle::find_by_accessible_label(&f.picker,"屏幕 A").find(|e|e.accessible_id().is_some_and(|v|v=="picker|displays|display-a")).unwrap();let p=row.absolute_position();let size=row.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);assert_eq!(rect(&f,"picker|displays|display-a")["fullyVisible"],true);
        f.picker.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});
        match change{
            "unchanged"=>{},"other-row"=>labels.set_row_data(1,"屏幕 B · 新名称".into()),
            "value-only"=>{values.set_row_data(0,"display-b".into());values.set_row_data(1,"display-a".into());},
            "producer-refresh"=>{f.picker.set_labels(model(vec!["屏幕 B".into(),"屏幕 A".into()]));f.picker.set_values(model(vec!["display-b".into(),"display-a".into()]));},
            "kind-change"=>{f.picker.set_kind("shortcuts".into());f.picker.set_labels(model(vec!["Ctrl+A".into(),"Tab".into()]));f.picker.set_values(model(vec!["ctrl-a".into(),"tab".into()]));},
            "label-change"=>labels.set_row_data(0,"屏幕 A · 新名称".into()),
            "busy-then-ready"=>{f.picker.set_busy(true);f.picker.window().take_snapshot().unwrap();f.picker.set_busy(false);},
            "disabled-then-ready"=>{enabled.set_row_data(0,false);f.picker.window().take_snapshot().unwrap();enabled.set_row_data(0,true);},_=>unreachable!(),
        }
        f.picker.window().take_snapshot().unwrap();f.picker.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});f.picker.window().take_snapshot().unwrap();
        let emitted=std::mem::take(&mut*f.calls.borrow_mut());let original=matches!(change,"unchanged"|"other-row"|"label-change");let expected=if original{vec![("stream.display.select".to_owned(),"display-a".to_owned())]}else{vec![]};let mut results=vec![];
        for(id,arg)in&emitted{let response=f.controller.dispatch(id,crate::args_for(id,arg,&f.controller.state()));results.push(response);}
        let requests=f.boundary.requests.lock().unwrap().clone();let passed=emitted==expected&&if original{requests.len()==1&&requests[0]["id"]=="stream.display.select"&&requests[0]["args"]["displayId"]=="display-a"&&results.iter().all(|r|r["ok"]==true)}else{requests.is_empty()};
        let name=format!("picker-press-{change}-{width}-{dark}");capture(&f,&name);records.push(json!({"name":name,"width":width,"dark":dark,"change":change,"callbacks":emitted,"expectedCallbacks":expected,"adapterRequests":requests,"results":results,"passed":passed}));
    }}}
    write("picker-pointer-results187.json",&json!(records));assert_eq!(records.len(),32);let failures=records.iter().filter(|r|r["passed"]!=true).map(|r|r["name"].as_str().unwrap()).collect::<Vec<_>>();assert!(failures.is_empty(),"Picker press changed target: {failures:?}");
}

#[test]
fn picker_keyboard_traverses_the_complete_list_and_escape_closes_only_the_menu(){
    backend();let mut records=vec![];
    for(width,height)in[(320.,220.),(420.,400.)]{for dark in[false,true]{
        let mut f=Fixture::in_current_backend();open(&mut f,width,height,dark);let labels=(0..18).map(|i|format!("屏幕 {i:02} · 长标题测试").into()).collect();let values=(0..18).map(|i|format!("display-{i}").into()).collect();let enabled=(0..18).map(|i|i!=5).collect();
        f.picker.set_labels(model(labels));f.picker.set_values(model(values));f.picker.set_enabled_items(model(enabled));f.picker.window().take_snapshot().unwrap();
        let controller=f.controller.clone();let calls=f.calls.clone();let bridge=f.bridge.clone();f.picker.on_dismiss(move||{bridge.observe_action("stream.menu.close");calls.borrow_mut().push(("stream.menu.close".into(),"".into()));assert_eq!(controller.dispatch("stream.menu.close",json!({}))["ok"],true);});
        let inspected=f.request("view.ui.inspect",json!({"window":"picker"}));let expected=inspected["result"]["controls"].as_array().unwrap().iter().filter(|r|r["enabled"]==true&&matches!(r["kind"].as_str(),Some("button"|"menu-item"))).map(|r|r["id"].as_str().unwrap().to_owned()).collect::<BTreeSet<_>>();assert_eq!(expected.len(),18);
        let mut cycles=vec![];for backward in[false,true]{assert_eq!(f.request("view.ui.focus",json!({"window":"picker","control":"scope|picker"}))["status"],"complete");let mut seen=vec![];let mut positions=vec![];let mut problems=vec![];
            for _ in 0..expected.len()*3+10{let result=f.request("view.ui.key",json!({"window":"picker","key":"Tab","shift":backward}));assert_eq!(result["status"],"complete");let focused=result["result"]["focused"].as_array().unwrap();if focused.len()!=1{problems.push(format!("Focus markers: {focused:?}"));break;}let id=focused[0].as_str().unwrap().to_owned();if seen.first()==Some(&id){break;}if seen.contains(&id){problems.push(format!("Early focus repeat: {id}"));break;}let position=rect(&f,&id);if position["fullyVisible"]!=true{problems.push(format!("Focused picker item clipped: {id}"));}positions.push(position);seen.push(id);}
            let seen_set=seen.iter().cloned().collect::<BTreeSet<_>>();let passed=seen_set==expected&&problems.is_empty();cycles.push(json!({"backward":backward,"sequence":seen,"positions":positions,"problems":problems,"passed":passed}));
        }
        let controls_unchanged=f.controller.state()["controls"]==true;let no_actions=f.calls.borrow().is_empty()&&f.boundary.requests.lock().unwrap().is_empty();let closed=f.request("view.ui.key",json!({"window":"picker","key":"Escape"}));assert_eq!(closed["status"],"complete");assert_eq!(f.controller.state()["streamMenu"],"");assert_eq!(f.controller.state()["controls"],true);assert_eq!(&*f.calls.borrow(),&[("stream.menu.close".into(),"".into())]);
        let name=format!("picker-tab-{width}-{dark}");capture(&f,&name);let passed=cycles.iter().all(|r|r["passed"]==true)&&controls_unchanged&&no_actions;records.push(json!({"name":name,"width":width,"dark":dark,"expected":expected,"cycles":cycles,"escapeClosedOnlyMenu":true,"noBusinessActionsDuringTraversal":no_actions,"passed":passed}));
    }}
    write("picker-keyboard-results187.json",&json!(records));assert_eq!(records.len(),4);let failures=records.iter().filter(|r|r["passed"]!=true).map(|r|r["name"].as_str().unwrap()).collect::<Vec<_>>();assert!(failures.is_empty(),"Picker traversal failures: {failures:?}");
}
