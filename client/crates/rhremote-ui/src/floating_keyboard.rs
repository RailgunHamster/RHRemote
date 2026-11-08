use serde_json::{Value, json};
use slint::{ComponentHandle, ModelRc, VecModel};

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Key {
    pub id: &'static str,pub label: &'static str,pub description: &'static str,
    pub action:&'static str,pub argument:&'static str,pub selected:bool,
    pub x:f32,pub y:f32,pub width:f32,pub height:f32,
}
pub(crate) fn layout(width:f32,height:f32)->Vec<Key>{
    if !width.is_finite()||!height.is_finite()||width<640.||height<240.{return vec![];}
    let unit=((width-32.)/23.).min(56.);let row=((height-96.)/6.).min(44.);
    let left=(width-23.*unit)/2.;let top=height-16.-6.*row;
    rhremote_core::keyboard::KEYS.iter().map(|key|Key{id:&key.id,label:&key.label,description:&key.description,action:"stream.keyboard.press",argument:&key.id,selected:false,x:left+key.x*unit+2.,y:top+key.row*row+2.,width:key.width*unit-4.,height:key.height*row-4.}).collect()
}
pub(crate) fn simple_layout(width:f32,height:f32)->Vec<Key>{
    if !width.is_finite()||!height.is_finite()||width<320.||height<240.{return vec![];}
    let(left,right,top,bottom,gap)=(16.,width-16.,16.,height-16.,6.);let k=48f32.min((right-left-4.*gap)/6.);let wide=2.*k+gap;let roomy=bottom-top>=320.;
    let tab=if roomy{(top+128f32.max((bottom-top)*0.26)).min(bottom-3.*k-3.*gap)}else{top+k+8.};
    let mut result=vec![];
    let mut add=|id:&'static str,x,y,w|{let key=rhremote_core::keyboard::key(id).unwrap();result.push(Key{id:&key.id,label:&key.label,description:&key.description,action:"stream.keyboard.press",argument:if id=="meta-left"{"meta-left|tap"}else{&key.id},selected:false,x,y,width:w,height:k});};
    add("escape",left,top,k);add("meta-left",left+k+gap,top,k);add("tab",left,tab,wide);
    if roomy{add("backspace",right-wide,top+64.,wide);add("delete",right-wide,top+64.+k+gap,wide);add("enter",right-wide,top+64.+2.*(k+gap),wide);for(i,id)in["ctrl-left","alt-left","shift-left"].into_iter().enumerate(){add(id,left+i as f32*(k+gap),bottom-k,k);}}
    let pad=right-3.*k-2.*gap;add("arrow-up",pad+k+gap,bottom-2.*k-gap,k);add("arrow-left",pad,bottom-k,k);add("arrow-down",pad+k+gap,bottom-k,k);add("arrow-right",pad+2.*(k+gap),bottom-k,k);
    result.push(Key{id:"shift-tab",label:"⇧ Tab",description:"Shift+Tab",action:"stream.shortcut",argument:"shift-tab",selected:false,x:left,y:tab+k+gap,width:wide,height:k});result
}
fn selected_layout(width:f32,height:f32,state:&Value)->Vec<Key>{if state["saved"]["simpleKeyboard"]==true{simple_layout(width,height)}else{layout(width,height)}}

struct ShortcutMeta {
    id: &'static str,
    key_id: &'static str,
    short_label: &'static str,
    full_label: &'static str,
}
fn shortcut_catalog() -> &'static [ShortcutMeta] {
    static CATALOG: std::sync::OnceLock<Vec<ShortcutMeta>> = std::sync::OnceLock::new();
    CATALOG.get_or_init(|| {
        rhremote_core::shortcuts()
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|item| {
                let id: &'static str = Box::leak(item["id"].as_str()?.to_owned().into_boxed_str());
                let full_label: &'static str =
                    Box::leak(item["label"].as_str()?.to_owned().into_boxed_str());
                let short = full_label
                    .split_once(" · ")
                    .map(|(prefix, _)| prefix)
                    .unwrap_or(full_label);
                let short_label: &'static str = Box::leak(short.to_owned().into_boxed_str());
                let key_id: &'static str =
                    Box::leak(format!("shortcut:{id}").into_boxed_str());
                Some(ShortcutMeta {
                    id,
                    key_id,
                    short_label,
                    full_label,
                })
            })
            .collect()
    })
}

fn scene(width:f32,height:f32,state:&Value)->Vec<Key>{
    let full_keyboard=state["floatingKeyboard"]==true && state["saved"]["simpleKeyboard"]!=true;
    let mut keys=if state["floatingKeyboard"]==true{selected_layout(width,height,state)}else{vec![]};
    for key in &mut keys {if let Ok(source)=rhremote_core::keyboard::key(key.id){let modifier=&source.modifier;key.selected=key.argument!="meta-left|tap"&&!modifier.is_empty()&&state["keyboardModifiers"].as_array().is_some_and(|values|values.iter().any(|value|value==modifier));}}
    if state["floatingKeyboard"]==true&&!keys.is_empty(){
        let simple=state["saved"]["simpleKeyboard"]==true;let compact=simple&&height-32.<320.;
        let y=if simple{if compact{height-16.-44.}else{80.}}else{keys.iter().map(|key|key.y).fold(f32::MAX,f32::min)-46.};
        keys.push(Key{id:"keyboard-layout",label:if simple{"完整键盘"}else{"简化键盘"},description:"切换键盘布局",action:"stream.keyboard.layout.set",argument:if simple{"full"}else{"simple"},selected:false,x:16.,y,width:104.,height:44.});
    }
    if state["saved"]["hideKeyboardButton"]!=true && width>=120. && height>=80.{
        let enabled=state["floatingKeyboard"]==true;
        keys.push(Key{id:"keyboard-toggle",label:if enabled{"收起"}else{"键盘"},description:if enabled{"收起悬浮键盘"}else{"展开悬浮键盘"},action:"stream.keyboard.floating.set",argument:if enabled{"false"}else{"true"},selected:enabled,x:width-88.,y:16.,width:72.,height:48.});
        if !full_keyboard {
            if let Some(pinned)=state["saved"]["pinnedShortcuts"].as_array() {
                let chip_w=84f32.min((width-24.).max(48.));
                let chip_h=38f32;
                let gap=6f32;
                let mut col_right=width-16.;
                let mut cur_y=16.+48.+gap;
                for item in pinned {
                    let Some(id)=item.as_str() else {continue};
                    let Some(meta)=shortcut_catalog().iter().find(|m|m.id==id) else {continue};
                    if cur_y+chip_h>height-16. && col_right-chip_w-gap>=chip_w+16. {
                        col_right-=chip_w+gap;
                        cur_y=16.;
                    }
                    let x=(col_right-chip_w).clamp(4.,(width-chip_w-4.).max(4.));
                    let y=cur_y.clamp(4.,(height-chip_h-4.).max(4.));
                    keys.push(Key{id:meta.key_id,label:meta.short_label,description:meta.full_label,action:"stream.shortcut",argument:meta.id,selected:false,x,y,width:chip_w,height:chip_h});
                    cur_y+=chip_h+gap;
                }
            }
        }
    }
    if !full_keyboard {
        if let Some(positions)=state["saved"]["buttonPositions"].as_object() {
            for key in &mut keys {
                if let Some(pos)=positions.get(key.id) {
                    if let (Some(rx),Some(ry))=(pos["x"].as_f64(),pos["y"].as_f64()) {
                        let rx=(rx as f32).clamp(0.,1.);
                        let ry=(ry as f32).clamp(0.,1.);
                        let span_x=(width-key.width-8.).max(1.);
                        let span_y=(height-key.height-8.).max(1.);
                        key.x=(4.+rx*span_x).clamp(4.,(width-key.width-4.).max(4.));
                        key.y=(4.+ry*span_y).clamp(4.,(height-key.height-4.).max(4.));
                    }
                }
            }
            keys.sort_by_key(|key|positions.get(key.id).and_then(|p|p["order"].as_u64()).unwrap_or(0));
        }
    }
    keys
}
pub(crate) fn model(keys:&[Key])->ModelRc<crate::KeyboardKey>{
    let full_keyboard=keys.len()>60;
    ModelRc::new(VecModel::from(keys.iter().map(|key|crate::KeyboardKey{id:key.id.into(),label:key.label.into(),description:key.description.into(),action:key.action.into(),argument:key.argument.into(),chosen:key.selected,x:key.x,y:key.y,width:key.width,height:key.height,draggable:!full_keyboard}).collect::<Vec<_>>()))
}

pub(crate) fn follow(window:&crate::FloatingKeyboardWindow,state:&Value,previous:&Value)->Value{
    let hidden=if state["floatingKeyboard"]!=true&&state["saved"]["hideKeyboardButton"]==true{"disabled"}else if state["streaming"]!=true{"not-streaming"}else if state["connection"]["stopping"]==true{"stopping"}else if state["controls"]==true{"controls-open"}else if !state["streamMenu"].as_str().unwrap_or("").is_empty(){"menu-open"}else{""};
    if !hidden.is_empty(){let mut report=crate::overlay::hide_auxiliary(window.window());report["reason"]=json!(hidden);return report;}
    #[cfg(windows)]
    unsafe{
        use raw_window_handle::{HasWindowHandle,RawWindowHandle};
        use slint::winit_030::WinitWindowAccessor;
        use windows::{core::w,Win32::{Foundation::{HANDLE,HWND,POINT,RECT},Graphics::Gdi::{ClientToScreen,CreateRectRgn,DeleteObject,SetWindowRgn},UI::WindowsAndMessaging::*}};
        let Some(owner)=crate::overlay::owned_window(state)else{let _=window.hide();return json!({"visible":false,"reason":"native-window-unavailable"});};
        if IsIconic(owner).as_bool(){let _=window.hide();return json!({"visible":false,"reason":"native-minimized"});}
        if !IsWindowVisible(owner).as_bool(){let _=window.hide();return json!({"visible":false,"reason":"native-hidden"});}
        let(mut client,mut origin)=(RECT::default(),POINT::default());
        if GetClientRect(owner,&mut client).is_err()||!ClientToScreen(owner,&mut origin).as_bool(){let _=window.hide();return json!({"visible":false,"error":"无法读取远程窗口区域","errorCode":"geometry"});}
        let(width,height)=(client.right-client.left,client.bottom-client.top);
        if width<1||height<1{let _=window.hide();return json!({"visible":false,"reason":"native-empty"});}
        let source=json!({"owner":format!("{}",owner.0 as usize),"request":state["floatingKeyboardRequest"],"session":state["floatingKeyboardSession"],"x":origin.x,"y":origin.y,"width":width,"height":height,"scale":window.window().scale_factor(),"opened":state["floatingKeyboard"],"buttonHidden":state["saved"]["hideKeyboardButton"],"modifiers":state["keyboardModifiers"],"simple":state["saved"]["simpleKeyboard"],"pinned":state["saved"]["pinnedShortcuts"],"positions":state["saved"]["buttonPositions"]});
        if previous["error"].is_string()&&previous["source"]==source{return previous.clone();}
        // The first native allocation is one pixel at the owned stream, never an opaque full-screen flash.
        if !window.window().is_visible(){window.window().set_position(slint::PhysicalPosition::new(origin.x,origin.y));window.window().set_size(slint::PhysicalSize::new(1,1));if window.show().is_err(){return json!({"visible":false,"error":"无法打开悬浮键盘","errorCode":"open"});}}
        let scale=window.window().scale_factor();let keys=scene(width as f32/scale,height as f32/scale,state);
        if keys.is_empty() || state["floatingKeyboard"]==true&&selected_layout(width as f32/scale,height as f32/scale,state).is_empty(){let _=window.hide();return json!({"visible":false,"error":"远程窗口过小，无法显示悬浮键盘","errorCode":"small","source":source});}
        let fingerprint=json!({"x":origin.x,"y":origin.y,"width":width,"height":height,"scale":scale,"opened":state["floatingKeyboard"],"buttonHidden":state["saved"]["hideKeyboardButton"],"modifiers":state["keyboardModifiers"],"simple":state["saved"]["simpleKeyboard"],"pinned":state["saved"]["pinnedShortcuts"],"positions":state["saved"]["buttonPositions"]});
        if previous["geometry"]!=fingerprint||previous["visible"]!=true{window.set_keys(model(&keys));}
        window.set_busy(state["controlAction"]["busy"]==true||state["connecting"]==true||state["statistics"]["firstFrame"]!=true);
        window.global::<crate::Palette>().set_dark(state["saved"]["dark"]==true);
        let mut handle=None;window.window().with_winit_window(|native|{if let Ok(value)=native.window_handle(){if let RawWindowHandle::Win32(value)=value.as_raw(){handle=Some(HWND(value.hwnd.get() as *mut _));}}});
        let Some(hwnd)=handle else{let _=window.hide();return json!({"visible":false,"error":"悬浮窗口不可用","errorCode":"handle"});};
        let fresh=previous["shape"]["key"]["hwnd"]!=json!(format!("{}",hwnd.0 as usize));
        if fresh{let empty=CreateRectRgn(0,0,0,0);if empty.is_invalid()||SetWindowRgn(hwnd,Some(empty),false)==0{if !empty.is_invalid(){let _=DeleteObject(empty.into());}let _=window.hide();return json!({"visible":false,"error":"无法初始化悬浮按键区域","errorCode":"region-init","source":source});}}
        let style=GetWindowLongPtrW(hwnd,GWL_EXSTYLE);let desired=(style&!(WS_EX_APPWINDOW.0 as isize))|(WS_EX_TOOLWINDOW.0|WS_EX_NOACTIVATE.0|WS_EX_LAYERED.0)as isize;
        if style!=desired{SetWindowLongPtrW(hwnd,GWL_EXSTYLE,desired);}
        // Opt in only this owned keyboard to the software renderer's layered
        // presenter. Global alpha would fade the glyphs along with the key fill.
        // Do not call SetLayeredWindowAttributes: it disables UpdateLayeredWindow.
        let opacity=SetPropW(hwnd,w!("RHRemote.KeyboardPerPixel"),Some(HANDLE(1usize as *mut _))).is_ok()
            &&!GetPropW(hwnd,w!("RHRemote.KeyboardPerPixel")).is_invalid();
        if fresh{window.window().request_redraw();}
        if previous["geometry"]!=fingerprint||fresh{window.window().set_size(slint::PhysicalSize::new(width as u32,height as u32));window.window().set_position(slint::PhysicalPosition::new(origin.x,origin.y));}
        if GetWindowLongPtrW(hwnd,GWLP_HWNDPARENT)!=owner.0 as isize{SetWindowLongPtrW(hwnd,GWLP_HWNDPARENT,owner.0 as isize);}
        if previous["geometry"]!=fingerprint||fresh{let _=SetWindowPos(hwnd,Some(HWND_TOP),origin.x,origin.y,width,height,SWP_NOACTIVATE|SWP_SHOWWINDOW|SWP_NOZORDER);}
        let shape=crate::ui_rendering::keyboard_shape(hwnd,scale,&keys,&previous["shape"]);
        if !opacity||shape["applied"]!=true{let _=window.hide();return json!({"visible":false,"error":"悬浮键盘点击区域或透明度未能设置","errorCode":"region","shape":shape,"source":source});}
        return json!({"visible":true,"geometry":fingerprint,"shape":shape,"alpha":255,"backgroundAlpha":80,"transparency":"per-pixel","contrast":"dual-outline","backgroundSampling":false,"keys":keys.iter().map(|k|json!({"id":k.id,"x":k.x,"y":k.y,"width":k.width,"height":k.height})).collect::<Vec<_>>()});
    }
    #[cfg(target_os = "linux")]
    {
        let owner=match crate::x11_surface::owned(state){Ok(value)=>value,Err(reason)=>{let _=window.hide();return json!({"visible":false,"reason":reason});}};
        let scale=window.window().scale_factor();
        let keys=scene(owner.width as f32/scale,owner.height as f32/scale,state);
        if keys.is_empty()||state["floatingKeyboard"]==true&&selected_layout(owner.width as f32/scale,owner.height as f32/scale,state).is_empty(){let _=window.hide();return json!({"visible":false,"error":"远程窗口太小，无法放置悬浮按键","errorCode":"small"});}
        window.set_keys(model(&keys));window.set_busy(state["controlAction"]["busy"]==true||state["connecting"]==true||state["statistics"]["firstFrame"]!=true);
        let regions=keys.iter().map(|key|((key.x*scale).round()as i16,(key.y*scale).round()as i16,(key.width*scale).round()as u16,(key.height*scale).round()as u16)).collect::<Vec<_>>();
        let shape=match crate::x11_surface::place(window.window(),owner,(owner.x,owner.y,owner.width as i32,owner.height as i32),&regions,166){Ok(value)=>value,Err(error)=>{let _=window.hide();return json!({"visible":false,"error":error,"errorCode":"region"});}};
        let _=previous;
        json!({"visible":true,"geometry":[owner.x,owner.y,owner.width,owner.height],"shape":shape,"alpha":166,"keys":keys.iter().map(|k|json!({"id":k.id,"x":k.x,"y":k.y,"width":k.width,"height":k.height})).collect::<Vec<_>>()})
    }
    #[cfg(not(any(windows,target_os = "linux")))]
    {let _=(previous,model(&[]));json!({"visible":false,"error":"悬浮键盘目前需要 Windows","errorCode":"unsupported"})}
}

// A brief missing HWND during handoff is expected. A settled stream must not silently
// leave a requested keyboard invisible. Intentional hiding never starts this timer.
#[derive(Default)]
pub(crate) struct Feedback { waiting:Option<(Value,std::time::Instant)>, last_trace:Value }
impl Feedback {
    pub(crate) fn report(&mut self,state:&Value,local:&Value,now:std::time::Instant)->Value {
        let mut report=local.clone();
        report["session"]=state["floatingKeyboardSession"].clone();
        report["request"]=state["floatingKeyboardRequest"].clone();
        let code=local["errorCode"].as_str().unwrap_or("");
        let eligible=state["floatingKeyboard"]==true && state["streaming"]==true
            && state["connecting"]!=true && state["connection"]["stopping"]!=true
            && state["statistics"]["firstFrame"]==true && state["controls"]!=true
            && state["streamMenu"].as_str().unwrap_or("").is_empty();
        let unavailable=matches!(local["reason"].as_str(),Some("native-window-unavailable"|"native-hidden"|"native-empty"));
        let mut feedback=code;
        if eligible && unavailable && code.is_empty() {
            let key=json!([report["session"],report["request"],state["statistics"]["streamProcessId"]]);
            if !self.waiting.as_ref().is_some_and(|(old,_)|*old==key) {self.waiting=Some((key,now));}
            if self.waiting.as_ref().is_some_and(|(_,start)|now.saturating_duration_since(*start)>=std::time::Duration::from_secs(2)) {feedback="window-unavailable";}
        }else{self.waiting=None;}
        report["feedbackCode"]=json!(feedback);
        report
    }
    pub(crate) fn transition(&mut self,state:&Value,report:&Value)->Option<Value> {
        // Metadata only. Never copy diagnostic text, arguments, tokens or raw state.
        let allowed=|key:&str,values:&[&str]|report[key].as_str().filter(|v|values.contains(v)).unwrap_or("").to_owned();
        let trace=json!({"session":report["session"].as_u64(),"request":report["request"].as_u64(),"processId":state["statistics"]["streamProcessId"].as_u64(),"requested":state["floatingKeyboard"]==true,"visible":report["visible"]==true,
            "reason":allowed("reason",&["disabled","not-streaming","stopping","controls-open","menu-open","native-window-unavailable","native-minimized","native-hidden","native-empty"]),
            "feedbackCode":allowed("feedbackCode",&["small","window-unavailable","geometry","open","handle","region-init","region","unsupported"])});
        if trace==self.last_trace{return None;}self.last_trace=trace.clone();Some(trace)
    }
}

#[cfg(test)]mod tests{
    use super::*;
    fn state()->Value{json!({"floatingKeyboard":true,"floatingKeyboardSession":5,"floatingKeyboardRequest":1,"streaming":true,"statistics":{"firstFrame":true,"streamProcessId":42}})}
    #[test]fn missing_window_feedback_waits_and_does_not_carry_between_attempts(){
        let mut feedback=Feedback::default();let mut state=state();let t=std::time::Instant::now();let local=json!({"visible":false,"reason":"native-window-unavailable"});
        assert_eq!(feedback.report(&state,&local,t)["feedbackCode"],"");
        assert_eq!(feedback.report(&state,&local,t+std::time::Duration::from_millis(1999))["feedbackCode"],"");
        assert_eq!(feedback.report(&state,&local,t+std::time::Duration::from_secs(2))["feedbackCode"],"window-unavailable");
        for field in ["floatingKeyboardRequest","floatingKeyboardSession"]{state[field]=json!(state[field].as_u64().unwrap()+1);assert_eq!(feedback.report(&state,&local,t+std::time::Duration::from_secs(3))["feedbackCode"],"");}
        assert_eq!(feedback.report(&state,&json!({"visible":true}),t+std::time::Duration::from_secs(8))["feedbackCode"],"");
        assert_eq!(feedback.report(&state,&local,t+std::time::Duration::from_secs(8))["feedbackCode"],"");
    }
    #[test]fn intentional_hiding_or_unconfirmed_connection_never_reports_failure(){
        let t=std::time::Instant::now();let local=json!({"visible":false,"reason":"native-hidden"});
        for patch in [json!({"controls":true}),json!({"connecting":true}),json!({"floatingKeyboard":false}),json!({"connection":{"stopping":true}}),json!({"streamMenu":"shortcuts"}),json!({"statistics":{"firstFrame":false}})]{
            let mut feedback=Feedback::default();let mut state=state();for(k,v)in patch.as_object().unwrap(){state[k]=v.clone();}
            for time in [t,t+std::time::Duration::from_secs(5)]{assert_eq!(feedback.report(&state,&local,time)["feedbackCode"],"");}
        }
        let mut feedback=Feedback::default();for time in [t,t+std::time::Duration::from_secs(5)]{assert_eq!(feedback.report(&state(),&json!({"visible":false,"reason":"native-minimized"}),time)["feedbackCode"],"");}
    }
    #[test]fn error_feedback_is_immediate_and_trace_is_deduplicated_and_allowlisted(){
        let mut feedback=Feedback::default();let state=state();let report=feedback.report(&state,&json!({"visible":false,"errorCode":"region","error":"secret must not be logged","reason":"arbitrary secret"}),std::time::Instant::now());
        assert_eq!(report["feedbackCode"],"region");let trace=feedback.transition(&state,&report).unwrap();assert!(!trace.to_string().contains("secret"));assert_eq!(trace["reason"],"");assert!(feedback.transition(&state,&report).is_none());
    }
    #[test]fn full_keyboard_has_108_keys_and_standard_groups_without_overlap(){
        for(w,h)in[(840.,560.),(640.,360.),(1280.,720.),(1920.,1080.)]{let keys=layout(w,h);assert_eq!(keys.len(),108);
            for(a,k)in keys.iter().enumerate(){assert!(k.x>=0.&&k.y>=0.&&k.x+k.width<=w&&k.y+k.height<=h);for other in keys.iter().skip(a+1){assert!(!(k.x<other.x+other.width&&k.x+k.width>other.x&&k.y<other.y+other.height&&k.y+k.height>other.y));}}
            let get=|id|keys.iter().find(|k|k.id==id).unwrap();assert!(get("tab").x<get("a").x);assert_eq!(get("arrow-up").x,get("arrow-down").x);assert_eq!(get("arrow-left").y,get("arrow-right").y);assert!(get("num-0").x>get("arrow-right").x);
        }
    }
    #[test]fn toggle_stays_at_the_same_location_and_can_be_hidden_independently(){
        let mut state=state();let open=scene(840.,560.,&state);state["floatingKeyboard"]=json!(false);let closed=scene(840.,560.,&state);assert_eq!(closed.len(),1);
        let a=open.last().unwrap();let b=closed.last().unwrap();assert_eq!((a.x,a.y,a.width,a.height),(b.x,b.y,b.width,b.height));assert_eq!(a.argument,"false");assert_eq!(b.argument,"true");
        state["saved"]=json!({"hideKeyboardButton":true});assert!(scene(840.,560.,&state).is_empty());state["floatingKeyboard"]=json!(true);assert_eq!(scene(840.,560.,&state).len(),109);
        state["keyboardModifiers"]=json!(["ctrl"]);assert_eq!(scene(840.,560.,&state).iter().filter(|k|k.selected).count(),2);
    }
    #[test]fn simplified_keyboard_has_navigation_and_a_standalone_win_key(){
        for(w,h)in[(840.,560.),(360.,800.),(640.,360.),(320.,240.)]{let mut state=state();state["saved"]=json!({"simpleKeyboard":true});let keys=scene(w,h,&state);
            for(a,key)in keys.iter().enumerate(){assert!(key.x>=0.&&key.y>=0.&&key.x+key.width<=w&&key.y+key.height<=h,"{}",key.id);for other in keys.iter().skip(a+1){assert!(!(key.x<other.x+other.width&&key.x+key.width>other.x&&key.y<other.y+other.height&&key.y+key.height>other.y),"{} overlaps {} at {w}x{h}",key.id,other.id);}}
            let get=|id|keys.iter().find(|k|k.id==id).unwrap();assert_eq!(get("meta-left").argument,"meta-left|tap");assert_eq!(get("shift-tab").action,"stream.shortcut");assert_eq!(get("keyboard-layout").argument,"full");assert!(get("tab").x<w/3.);assert!(get("arrow-down").x>w/2.);
        }
    }
    #[test]fn invalid_or_too_small_geometry_has_no_full_keyboard(){for(w,h)in[(0.,600.),(360.,800.),(900.,120.),(f32::NAN,600.)]{assert!(layout(w,h).is_empty());}}
    #[test]fn pinned_shortcuts_and_custom_positions_apply_and_sort_by_drag_order(){
        let mut state=state();
        state["floatingKeyboard"]=json!(false);
        state["saved"]=json!({
            "pinnedShortcuts":["ctrl-c","ctrl-v"],
            "buttonPositions":{
                "shortcut:ctrl-c":{"x":0.5,"y":0.5,"order":1},
                "shortcut:ctrl-v":{"x":0.5,"y":0.5,"order":2}
            }
        });
        let keys=scene(840.,560.,&state);
        assert_eq!(keys.len(),3);
        let c=keys.iter().find(|k|k.id=="shortcut:ctrl-c").unwrap();
        let v=keys.iter().find(|k|k.id=="shortcut:ctrl-v").unwrap();
        assert_eq!(c.label,"复制");
        assert_eq!(c.action,"stream.shortcut");
        assert_eq!(c.argument,"ctrl-c");
        assert_eq!((c.x,c.y),(v.x,v.y));
        assert_eq!(keys.last().unwrap().id,"shortcut:ctrl-v");
    }

}
