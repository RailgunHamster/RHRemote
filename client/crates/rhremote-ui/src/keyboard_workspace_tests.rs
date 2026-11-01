//! Traverse real workspace controls using the same keyboard function as the application API.
use super::*;
use std::collections::BTreeSet;
use i_slint_backend_testing::ElementQuery;

fn geometry(f:&Flow,id:&str)->Value{
    let elements=ElementQuery::from_root(&f.ui).find_all();let Some(target)=elements.iter().find(|element|element.accessible_id().is_some_and(|value|value==id))else{return json!({"id":id,"fullyVisible":false,"reason":"focused element not found"})};
    let p=target.absolute_position();let size=target.size();let window=f.ui.window().size();let(mut left,mut top,mut right,mut bottom)=(0f32,0f32,window.width as f32,window.height as f32);let mut clips=vec![];
    for element in &elements {
        if element.type_name().is_none_or(|name|name!="ScrollView")||element.computed_opacity()<=0.{continue;}
        if !element.query_descendants().find_all().iter().any(|child|child.accessible_id().is_some_and(|value|value==id)){continue;}
        let at=element.absolute_position();let area=element.size();left=left.max(at.x);top=top.max(at.y);right=right.min(at.x+area.width);bottom=bottom.min(at.y+area.height);clips.push(json!({"x":at.x,"y":at.y,"width":area.width,"height":area.height}));
    }
    json!({"id":id,"x":p.x,"y":p.y,"width":size.width,"height":size.height,"viewport":{"left":left,"top":top,"right":right,"bottom":bottom},"scrollViewports":clips,"fullyVisible":size.width>0.&&size.height>0.&&target.computed_opacity()>0.&&p.x>=left-1.&&p.y>=top-1.&&p.x+size.width<=right+1.&&p.y+size.height<=bottom+1.})
}

fn inspect(f:&Flow)->Vec<Value>{
    let rows=Rc::new(RefCell::new(Vec::new()));let output=rows.clone();let probe=f.ui.global::<crate::UiTest>();probe.set_enabled(true);
    probe.on_report(move|id,kind,label,enabled,focused,_,_,width,height,_,_|output.borrow_mut().push(json!({"id":id.as_str(),"kind":kind.as_str(),"label":label.as_str(),"enabled":enabled,"focused":focused,"width":width,"height":height})));
    probe.set_operation("inspect".into());probe.set_generation(probe.get_generation()+1);f.ui.window().take_snapshot().unwrap();let result=rows.borrow().clone();result
}
fn anchor(f:&Flow){
    if f.ui.get_navigation_prompt(){f.ui.set_navigation_choice(0);}else if f.ui.get_sync_clear_prompt(){f.ui.set_sync_clear_choice(0);}else{
        let probe=f.ui.global::<crate::UiTest>();probe.set_operation("focus".into());probe.set_target("page".into());probe.set_generation(probe.get_generation()+1);
    }f.ui.window().take_snapshot().unwrap();
}
fn traverse(f:&mut Flow,backward:bool,expected:&BTreeSet<String>,name:&str)->Value{
    anchor(f);let mut sequence=vec![];let mut rectangles=vec![];let mut problems=vec![];let mut first=None;let mut captured=false;
    for index in 0..expected.len()*3+10 {
        crate::ui_keyboard::send(&f.ui,"Tab",backward,false);i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(250));f.ui.window().take_snapshot().unwrap();
        let rows=inspect(f);let focused=rows.iter().filter(|r|r["focused"]==true).map(|r|r["id"].as_str().unwrap().to_owned()).collect::<Vec<_>>();
        if focused.len()!=1{problems.push(format!("Tab {index} has {} focus markers: {focused:?}",focused.len()));break;}
        let current=focused[0].clone();if first.as_ref()==Some(&current){break;}
        if first.is_none(){first=Some(current.clone());}
        if sequence.contains(&current){problems.push(format!("Tab loop repeated {current} before its first item"));break;}
        sequence.push(current.clone());
        if index==1 {
            // A normal background binding refresh must retain the same keyboard target.
            f.refresh();let after=inspect(f).into_iter().filter(|r|r["focused"]==true).map(|r|r["id"].as_str().unwrap().to_owned()).collect::<Vec<_>>();
            if after!=focused{problems.push(format!("Background refresh changed focus from {focused:?} to {after:?}"));break;}
        }
        let rect=geometry(f,&current);if rect["fullyVisible"]!=true{problems.push(format!("Focused control remains clipped: {current}"));if !captured{f.capture(&format!("{name}-{}-clipped",if backward{"backward"}else{"forward"}));captured=true;}}rectangles.push(rect);
    }
    let seen=sequence.iter().cloned().collect::<BTreeSet<_>>();let missing=expected.difference(&seen).cloned().collect::<Vec<_>>();let extra=seen.difference(expected).cloned().collect::<Vec<_>>();
    json!({"direction":if backward{"backward"}else{"forward"},"sequence":sequence,"rectangles":rectangles,"missing":missing,"unexpected":extra,"problems":problems,"passed":missing.is_empty()&&extra.is_empty()&&problems.is_empty()})
}
fn setup(f:&mut Flow,context:&str){
    match context {
        "devices"=>{},
        "video-manual"|"video-auto"|"video-dirty"|"audio"|"input"|"network"|"advanced"|"advanced-sources"|"copy"|"unsaved-device"=>{
            f.input("device.settings.open","host");let tab=if context.starts_with("video")||context=="unsaved-device"{"video"}else if context=="advanced-sources"||context=="copy"{"advanced"}else{context};f.input("settings.tab",tab);
            if context=="video-auto"{f.input("settings.set","bitrateAuto=toggle");}
            if context=="video-dirty"||context=="unsaved-device"{f.input("settings.input","fps=24");}
            if context=="advanced-sources"||context=="copy"{f.input("settings.sources.toggle","");}
            if context=="copy"{f.input("settings.copy.preview","fixture-pad");}
            if context=="unsaved-device"{f.input("devices.open","");assert!(f.ui.get_navigation_prompt());}
        },
        "updates-idle"|"updates-available"|"updates-downloaded"|"updates-error"=>{
            let phase=context.strip_prefix("updates-").unwrap();if phase!="idle"{*f.boundary.updates.lock().unwrap()=json!({"managed":true,"phase":if phase=="error"{"error"}else{phase},"availableVersion":"9.0.0","pendingRestart":phase=="downloaded","error":if phase=="error"{"fixture check failure"}else{""}});}f.input("app.settings.tab","updates");
        },
        "sync-saved"|"sync-dirty"|"unsaved-sync"|"sync-clear"=>{
            f.input("app.settings.tab","sync");if context=="sync-dirty"||context=="unsaved-sync"{f.input("sync.edit","username=edited-fixture");}
            if context=="unsaved-sync"{f.input("devices.open","");assert!(f.ui.get_navigation_prompt());}
            if context=="sync-clear"{f.input("sync.clear","");assert!(f.ui.get_sync_clear_prompt());}
        },
        "appearance"|"about"=>f.input("app.settings.tab",context),
        "device-info"=>f.input("device.info.open","host"),
        "add-empty"|"add-filled"=>{f.input("device.add.open","");if context=="add-filled"{f.input("device.add.input","192.0.2.9");}},
        "connection-failed"=>{let response=f.command("device.connect","host");assert_eq!(response["ok"],false);assert_eq!(f.controller.state()["connection"]["phase"],"failed");},
        _=>panic!("Unknown workspace case {context}"),
    }
}
#[test]
fn full_workspace_tab_cycles_cover_current_controls_and_preserve_focus_on_refresh(){
    backend();let mut records=vec![];let contexts=["devices","video-manual","video-auto","video-dirty","audio","input","network","advanced","advanced-sources","copy","updates-idle","updates-available","updates-downloaded","updates-error","sync-saved","sync-dirty","appearance","about","device-info","add-empty","add-filled","unsaved-device","unsaved-sync","sync-clear","connection-failed"];
    for (width,height) in [(720.,560.),(1180.,780.)]{for dark in [false,true]{for context in contexts{
        let mut f=Flow::new(width,height);assert!(ElementHandle::find_by_element_id(&f.ui,"MainWindow::main-keys").next().is_some(),"Workspace geometry requires Slint debug metadata; use SLINT_EMIT_DEBUG_INFO=1 for Release tests");if dark{f.input("appearance.toggle","");}setup(&mut f,context);let saved=f.saved();let state=f.controller.state();
        let controls=inspect(&f);let enabled=controls.iter().filter(|row|row["enabled"]==true&&matches!(row["kind"].as_str(),Some("button"|"navigation"|"device"|"input"|"checkbox"|"combobox"|"spinbox"))).map(|row|row["id"].as_str().unwrap().to_owned()).collect::<Vec<_>>();
        let expected=enabled.iter().cloned().collect::<BTreeSet<_>>();assert_eq!(expected.len(),enabled.len(),"Duplicate control IDs on {context}");assert!(!expected.is_empty(),"No controls on {context}");
        let name=format!("tab-{context}-{width}-{dark}");let forward=traverse(&mut f,false,&expected,&name);let backward=traverse(&mut f,true,&expected,&name);let sequence=forward["sequence"].as_array().unwrap();let reverse=backward["sequence"].as_array().unwrap();
        let inverse=reverse.first().and_then(|first|sequence.iter().position(|v|v==first)).is_some_and(|start|sequence.len()==reverse.len()&&reverse.iter().enumerate().all(|(index,item)|item==&sequence[(start+sequence.len()-index)%sequence.len()]));
        let no_actions=f.pending.borrow().is_empty();let untouched=f.saved()==saved&&f.controller.state()["page"]==state["page"]&&f.controller.state()["settingsInputs"]==state["settingsInputs"];
        let passed=forward["passed"]==true&&backward["passed"]==true&&inverse&&no_actions&&untouched;f.capture(&name);
        records.push(json!({"name":name,"context":context,"width":width,"dark":dark,"expected":expected,"controls":controls,"forward":forward,"backward":backward,"reverseOrderConsistent":inverse,"noActionsDispatched":no_actions,"savedAndDraftUnchanged":untouched,"passed":passed}));
    }}}
    if let Some(folder)=std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR"){let path=PathBuf::from(folder).join("workspace-tab-results.json");assert!(!path.exists());std::fs::write(path,serde_json::to_vec_pretty(&records).unwrap()).unwrap();}
    let failures=records.iter().filter(|r|r["passed"]!=true).map(|r|r["name"].as_str().unwrap()).collect::<Vec<_>>();assert!(failures.is_empty(),"Workspace keyboard failures: {failures:?}");
}

/// Only admission and cleanup acknowledgements are simulated; Controller owns every phase.
struct SessionBoundary { common:Arc<Boundary>, pair_attempt:Arc<std::sync::atomic::AtomicU64> }
impl Platform for SessionBoundary {
    fn execute(&self,id:&str,args:&Value)->Result<Value,String>{
        if matches!(id,"device.connect"|"device.pair"|"pair.cancel"|"stream.disconnect") {
            if id=="device.pair"{self.pair_attempt.store(args["attemptId"].as_u64().unwrap(),Ordering::SeqCst);}
            self.common.calls.lock().unwrap().push(id.into());
            return Ok(json!({"accepted":true,"cleanupPending":matches!(id,"pair.cancel"|"stream.disconnect")}));
        }
        self.common.execute(id,args)
    }
}
fn session_flow(width:f32,height:f32,dark:bool,pair:bool,phase:&str)->(Flow,Arc<std::sync::atomic::AtomicU64>){
    let common=Arc::new(Boundary::default());let pair_attempt=Arc::new(std::sync::atomic::AtomicU64::new(0));let platform=Arc::new(SessionBoundary{common:common.clone(),pair_attempt:pair_attempt.clone()});
    let mut f=Flow::with_platform(width,height,common,platform);if dark{f.input("appearance.toggle","");}
    if pair{let mut devices:Vec<Device>=serde_json::from_value(f.controller.state()["devices"].clone()).unwrap();devices.iter_mut().find(|d|d.uuid=="host").unwrap().paired=false;f.controller.replace_devices(devices);f.refresh();}
    f.input("devices.filter","100.64.0.1");
    if pair{f.click("配对","device.pair","host");}else{f.click("连接","device.connect","host");}
    let attempt=pair_attempt.load(Ordering::SeqCst);
    if pair {
        if matches!(phase,"contacting"|"pairing") {f.controller.stream_state(false,json!({"pairAttemptId":attempt,"pairingPhase":phase,"nativeCleanupPending":false}));}
        if phase=="cancelling"{f.input("pair.cancel","");}
        if matches!(phase,"cleaning"|"failed"){
            let device:Device=serde_json::from_value(f.controller.state()["devices"][0].clone()).unwrap();
            f.controller.pairing_complete(attempt,device,"配对失败，请重新确认配对码。".into(),phase=="cleaning");
        }
    }else{
        match phase {
            "first-frame"=>f.controller.stream_state(true,json!({"phase":"connected","firstFrame":false,"nativeCleanupPending":false})),
            "connected"=>f.controller.stream_state(true,json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false})),
            "reconnecting"=>{f.controller.stream_state(true,json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}));f.controller.stream_state(true,json!({"phase":"reconnecting","firstFrame":false,"nativeCleanupPending":false}));},
            "cancelling"=>f.input("stream.disconnect",""),
            "cleaning"|"failed"=>f.controller.stream_state(false,json!({"phase":"ended","error":"连接中断，请检查网络后重试。","nativeCleanupPending":phase=="cleaning"})),
            "starting"=>{},_=>panic!("Unknown phase {phase}"),
        }
    }
    f.refresh();assert_eq!(f.controller.state()["connection"]["phase"],phase);(f,pair_attempt)
}
fn empty_cycle(f:&mut Flow,backward:bool)->Value{
    anchor(f);let mut problems=vec![];
    for _ in 0..4{
        crate::ui_keyboard::send(&f.ui,"Tab",backward,false);f.ui.window().take_snapshot().unwrap();
        let active=inspect(f).into_iter().filter(|r|r["focused"]==true&&r["kind"]!="page").collect::<Vec<_>>();
        if !active.is_empty(){problems.push(format!("Cleanup focused a disabled/background control: {active:?}"));}
        f.refresh();
    }
    json!({"sequence":[],"problems":problems,"passed":problems.is_empty()})
}
#[test]
fn pairing_and_connection_phases_keep_keyboard_targets_visible_and_disable_background_actions(){
    backend();let mut records=vec![];
    for(width,height)in[(720.,560.),(1180.,780.)]{for dark in[false,true]{for(pair,phases)in[
        (true,vec!["preparing","contacting","pairing","cancelling","cleaning","failed"]),
        (false,vec!["starting","first-frame","connected","reconnecting","cancelling","cleaning","failed"]),
    ]{for phase in phases{
        let(mut f,_)=session_flow(width,height,dark,pair,phase);let state=f.controller.state();let saved=f.saved();let calls=f.boundary.calls.lock().unwrap().clone();let name=format!("session-tab-{}-{phase}-{width}-{dark}",if pair{"pair"}else{"connect"});
        assert_eq!(f.ui.get_pair_pin().is_empty(),!pair||phase!="pairing");
        let controls=inspect(&f);let expected=controls.iter().filter(|r|r["enabled"]==true&&matches!(r["kind"].as_str(),Some("button"|"navigation"|"device"|"input"|"checkbox"|"combobox"|"spinbox"))).map(|r|r["id"].as_str().unwrap().to_owned()).collect::<BTreeSet<_>>();
        let cleanup=matches!(phase,"cancelling"|"cleaning");assert_eq!(expected.is_empty(),cleanup,"Unexpected keyboard targets during {phase}: {expected:?}");
        let forward=if cleanup{empty_cycle(&mut f,false)}else{traverse(&mut f,false,&expected,&name)};
        let reverse=if cleanup{empty_cycle(&mut f,true)}else{traverse(&mut f,true,&expected,&name)};
        let untouched=f.saved()==saved&&f.controller.state()["connection"]==state["connection"]&&f.controller.state()["deviceQuery"]==state["deviceQuery"]&&f.boundary.calls.lock().unwrap().as_slice()==calls.as_slice()&&f.pending.borrow().is_empty();
        let cancel_hint=!(pair&&phase=="cancelling")||f.ui.get_page()=="connection"&&f.ui.get_connection_title()=="正在取消配对";
        let stale_hint=pair&&phase=="cancelling"&&ElementHandle::find_by_accessible_label(&f.ui,"正在启动主机配对，完成后显示配对码。").any(|e|e.computed_opacity()>0.);
        let needs_progress=pair&&matches!(phase,"preparing"|"contacting");let progress=ElementHandle::find_by_element_id(&f.ui,"MainWindow::pair-progress").any(|e|e.computed_opacity()>0.&&e.size().width>0.&&e.size().height>0.);
        let progress_correct=progress==needs_progress;
        let passed=forward["passed"]==true&&reverse["passed"]==true&&untouched&&cancel_hint&&!stale_hint&&progress_correct;f.capture(&name);
        records.push(json!({"name":name,"pairing":pair,"phase":phase,"width":width,"dark":dark,"controls":controls,"expected":expected,"forward":forward,"backward":reverse,"noActionsAndDataUnchanged":untouched,"cancelInstructionCorrect":cancel_hint,"staleStartInstructionVisible":stale_hint,"progressNeeded":needs_progress,"pairProgressVisible":progress,"progressCorrect":progress_correct,"passed":passed}));
    }}}}
    if let Some(folder)=std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR"){let path=PathBuf::from(folder).join("connection-keyboard-results186.json");assert!(!path.exists());std::fs::write(path,serde_json::to_vec_pretty(&records).unwrap()).unwrap();}
    assert_eq!(records.len(),52);let failures=records.iter().filter(|r|r["passed"]!=true).map(|r|r["name"].as_str().unwrap()).collect::<Vec<_>>();assert!(failures.is_empty(),"Session keyboard failures: {failures:?}");
}

fn keyboard_action(f:&mut Flow,label:&str,id:&str)->Value{
    let element=f.button(label,id,"");let target=element.accessible_id().unwrap().to_owned();assert_eq!(geometry(f,&target)["fullyVisible"],true);
    let probe=f.ui.global::<crate::UiTest>();probe.set_enabled(true);probe.set_operation("focus".into());probe.set_target(target.into());probe.set_generation(probe.get_generation()+1);f.ui.window().take_snapshot().unwrap();
    assert!(f.pending.borrow().is_empty());crate::ui_keyboard::send(&f.ui,"Enter",false,false);f.ui.window().take_snapshot().unwrap();
    let emitted=std::mem::take(&mut*f.pending.borrow_mut());assert_eq!(emitted,vec![(id.to_owned(),String::new())],"Keyboard action for {label}");f.command(id,"")
}
#[test]
fn pairing_cancel_failure_retry_and_return_use_real_keyboard_buttons_and_preserve_the_device(){
    backend();let mut records=vec![];
    for(width,height)in[(720.,560.),(1180.,780.)]{for dark in[false,true]{
        let(mut f,pair_attempt)=session_flow(width,height,dark,true,"pairing");let saved=f.saved();let query=f.ui.get_device_query();let selected=f.ui.get_selected_uuid();
        assert_eq!(keyboard_action(&mut f,"取消配对","pair.cancel")["ok"],true);assert_eq!(f.controller.state()["connection"]["phase"],"cancelling");assert!(f.ui.get_pair_pin().is_empty());
        f.controller.stream_state(false,json!({"phase":"cancelled","nativeCleanupPending":true}));f.refresh();assert_eq!(f.ui.get_page(),"connection");
        for id in["device.connect","device.pair","devices.open","app.settings.open"]{assert_eq!(f.command(id,"host")["ok"],false,"Busy action {id}");}
        f.controller.stream_state(false,json!({"phase":"cancelled","nativeCleanupPending":false}));f.refresh();assert_eq!(f.ui.get_page(),"devices");
        f.click("配对","device.pair","host");let attempt=f.controller.state()["connection"]["attempt"].as_u64().unwrap();let device:Device=serde_json::from_value(f.controller.state()["devices"][0].clone()).unwrap();
        f.controller.pairing_complete(pair_attempt.load(Ordering::SeqCst),device.clone(),"配对码错误。".into(),false);f.refresh();assert_eq!(f.controller.state()["connection"]["phase"],"failed");
        assert_eq!(keyboard_action(&mut f,"重试配对","connection.retry")["ok"],true);assert_eq!(f.controller.state()["connection"]["uuid"],"host");assert_eq!(f.controller.state()["connection"]["attempt"],attempt+1);
        f.controller.pairing_complete(pair_attempt.load(Ordering::SeqCst),device,"配对码错误。".into(),false);f.refresh();assert_eq!(keyboard_action(&mut f,"返回设备","connection.dismiss")["ok"],true);
        assert_eq!(f.ui.get_page(),"devices");assert_eq!(f.ui.get_device_query(),query);assert_eq!(f.ui.get_selected_uuid(),selected);assert_eq!(f.saved(),saved);
        let calls=f.boundary.calls.lock().unwrap().clone();assert_eq!(calls.iter().filter(|id|id.as_str()=="device.pair").count(),3);assert_eq!(calls.iter().filter(|id|id.as_str()=="pair.cancel").count(),1);assert!(!calls.iter().any(|id|id=="device.connect"));
        let name=format!("session-actions-pair-{width}-{dark}");f.capture(&name);records.push(json!({"name":name,"ownEnterButtons":3,"pairRequests":3,"cancelRequests":1,"selectedAndQueryPreserved":true,"savedUnchanged":true}));
    }}
    if let Some(folder)=std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR"){let path=PathBuf::from(folder).join("connection-keyboard-actions186.json");assert!(!path.exists());std::fs::write(path,serde_json::to_vec_pretty(&records).unwrap()).unwrap();}
    assert_eq!(records.len(),4);
}
