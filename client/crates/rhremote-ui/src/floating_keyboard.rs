use serde_json::{Value, json};
use slint::{ComponentHandle, ModelRc, VecModel};

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Key {
    pub id: &'static str, pub label: &'static str, pub description: &'static str,
    pub x:f32,pub y:f32,pub width:f32,pub height:f32,
}
pub(crate) fn layout(width:f32,height:f32)->Vec<Key>{
    if !width.is_finite()||!height.is_finite()||width<320.||height<240.{return vec![];}
    let(left,right,top,bottom,k,gap,wide)=(16.,width-16.,16.,height-16.,48.,8.,88.);
    let h=bottom-top;let tab=(top+(h*0.26).max(64.)).min(bottom-2.*k-gap);let pad=right-3.*k-2.*gap;
    let mut result=vec![];
    let mut add=|id,label,description,x,y,w|result.push(Key{id,label,description,x,y,width:w,height:k});
    add("escape","Esc","Esc",left,top,k);add("tab","Tab","Tab",left,tab,wide);add("shift-tab","Shift+Tab","Shift+Tab",left,tab+k+gap,wide);
    if h>=288.{add("backspace","Backspace","Backspace",right-wide,top,wide);add("delete","Del","Delete",right-wide,top+k+gap,wide);add("enter","Enter","Enter",right-wide,top+(h-k)/2.,wide);}
    add("arrow-up","↑","向上",pad+k+gap,bottom-2.*k-gap,k);add("arrow-left","←","向左",pad,bottom-k,k);add("arrow-down","↓","向下",pad+k+gap,bottom-k,k);add("arrow-right","→","向右",pad+2.*(k+gap),bottom-k,k);
    result
}
pub(crate) fn model(keys:&[Key])->ModelRc<crate::KeyboardKey>{ModelRc::new(VecModel::from(keys.iter().map(|key|crate::KeyboardKey{id:key.id.into(),label:key.label.into(),description:key.description.into(),x:key.x,y:key.y,width:key.width,height:key.height}).collect::<Vec<_>>()))}

pub(crate) fn follow(window:&crate::FloatingKeyboardWindow,state:&Value,previous:&Value)->Value{
    let hidden=if state["floatingKeyboard"]!=true{"disabled"}else if state["streaming"]!=true{"not-streaming"}else if state["connection"]["stopping"]==true{"stopping"}else if state["controls"]==true{"controls-open"}else if !state["streamMenu"].as_str().unwrap_or("").is_empty(){"menu-open"}else{""};
    if !hidden.is_empty(){let _=window.hide();return json!({"visible":false,"reason":hidden});}
    #[cfg(windows)]
    unsafe{
        use raw_window_handle::{HasWindowHandle,RawWindowHandle};
        use slint::winit_030::WinitWindowAccessor;
        use windows::Win32::{Foundation::{COLORREF,HWND,POINT,RECT},Graphics::Gdi::{ClientToScreen,CreateRectRgn,DeleteObject,SetWindowRgn},UI::WindowsAndMessaging::*};
        let Some(owner)=crate::overlay::owned_window(state)else{let _=window.hide();return json!({"visible":false,"reason":"native-window-unavailable"});};
        if IsIconic(owner).as_bool(){let _=window.hide();return json!({"visible":false,"reason":"native-minimized"});}
        if !IsWindowVisible(owner).as_bool(){let _=window.hide();return json!({"visible":false,"reason":"native-hidden"});}
        let(mut client,mut origin)=(RECT::default(),POINT::default());
        if GetClientRect(owner,&mut client).is_err()||!ClientToScreen(owner,&mut origin).as_bool(){let _=window.hide();return json!({"visible":false,"error":"无法读取远程窗口区域","errorCode":"geometry"});}
        let(width,height)=(client.right-client.left,client.bottom-client.top);
        if width<1||height<1{let _=window.hide();return json!({"visible":false,"reason":"native-empty"});}
        let source=json!({"owner":format!("{}",owner.0 as usize),"request":state["floatingKeyboardRequest"],"session":state["floatingKeyboardSession"],"x":origin.x,"y":origin.y,"width":width,"height":height,"scale":window.window().scale_factor()});
        if previous["error"].is_string()&&previous["source"]==source{return previous.clone();}
        // The first native allocation is one pixel at the owned stream, never an opaque full-screen flash.
        if !window.window().is_visible(){window.window().set_position(slint::PhysicalPosition::new(origin.x,origin.y));window.window().set_size(slint::PhysicalSize::new(1,1));if window.show().is_err(){return json!({"visible":false,"error":"无法打开悬浮键盘","errorCode":"open"});}}
        let scale=window.window().scale_factor();let keys=layout(width as f32/scale,height as f32/scale);
        if keys.is_empty(){let _=window.hide();return json!({"visible":false,"error":"远程窗口过小，无法显示悬浮键盘","errorCode":"small","source":source});}
        let fingerprint=json!({"x":origin.x,"y":origin.y,"width":width,"height":height,"scale":scale});
        if previous["geometry"]!=fingerprint||previous["visible"]!=true{window.set_keys(model(&keys));}
        window.set_busy(state["controlAction"]["busy"]==true);
        window.global::<crate::Palette>().set_dark(state["saved"]["dark"]==true);
        let mut handle=None;window.window().with_winit_window(|native|{if let Ok(value)=native.window_handle(){if let RawWindowHandle::Win32(value)=value.as_raw(){handle=Some(HWND(value.hwnd.get() as *mut _));}}});
        let Some(hwnd)=handle else{let _=window.hide();return json!({"visible":false,"error":"悬浮窗口不可用","errorCode":"handle"});};
        let fresh=previous["shape"]["key"]["hwnd"]!=json!(format!("{}",hwnd.0 as usize));
        if fresh{let empty=CreateRectRgn(0,0,0,0);if empty.is_invalid()||SetWindowRgn(hwnd,Some(empty),false)==0{if !empty.is_invalid(){let _=DeleteObject(empty.into());}let _=window.hide();return json!({"visible":false,"error":"无法初始化悬浮按键区域","errorCode":"region-init","source":source});}}
        let style=GetWindowLongPtrW(hwnd,GWL_EXSTYLE);let desired=(style&!(WS_EX_APPWINDOW.0 as isize))|(WS_EX_TOOLWINDOW.0|WS_EX_NOACTIVATE.0|WS_EX_LAYERED.0)as isize;
        if style!=desired{SetWindowLongPtrW(hwnd,GWL_EXSTYLE,desired);}
        let mut alpha=0u8;let mut alpha_flags=LAYERED_WINDOW_ATTRIBUTES_FLAGS::default();
        let opacity=(if fresh||previous["alpha"]!=166{SetLayeredWindowAttributes(hwnd,COLORREF(0),166,LWA_ALPHA).is_ok()}else{true})
            &&GetLayeredWindowAttributes(hwnd,None,Some(&mut alpha),Some(&mut alpha_flags)).is_ok()
            &&alpha==166&&(alpha_flags&LWA_ALPHA)==LWA_ALPHA;
        if previous["geometry"]!=fingerprint||fresh{window.window().set_size(slint::PhysicalSize::new(width as u32,height as u32));window.window().set_position(slint::PhysicalPosition::new(origin.x,origin.y));}
        if GetWindowLongPtrW(hwnd,GWLP_HWNDPARENT)!=owner.0 as isize{SetWindowLongPtrW(hwnd,GWLP_HWNDPARENT,owner.0 as isize);}
        if previous["geometry"]!=fingerprint||fresh{let _=SetWindowPos(hwnd,Some(HWND_TOP),origin.x,origin.y,width,height,SWP_NOACTIVATE|SWP_SHOWWINDOW|SWP_NOZORDER);}
        let shape=crate::ui_rendering::keyboard_shape(hwnd,scale,&keys,&previous["shape"]);
        if !opacity||shape["applied"]!=true{let _=window.hide();return json!({"visible":false,"error":"悬浮键盘点击区域或透明度未能设置","errorCode":"region","shape":shape,"source":source});}
        return json!({"visible":true,"geometry":fingerprint,"shape":shape,"alpha":166,"keys":keys.iter().map(|k|json!({"id":k.id,"x":k.x,"y":k.y,"width":k.width,"height":k.height})).collect::<Vec<_>>()});
    }
    #[cfg(target_os = "linux")]
    {
        let owner=match crate::x11_surface::owned(state){Ok(value)=>value,Err(reason)=>{let _=window.hide();return json!({"visible":false,"reason":reason});}};
        let scale=window.window().scale_factor();
        let keys=layout(owner.width as f32/scale,owner.height as f32/scale);
        if keys.is_empty(){let _=window.hide();return json!({"visible":false,"error":"远程窗口太小，无法放置悬浮按键","errorCode":"small"});}
        window.set_keys(model(&keys));window.set_busy(state["controlAction"]["busy"]==true);
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
    #[test]fn keyboard_habits_and_non_overlapping_regions_survive_resize(){for(w,h)in[(840.,560.),(360.,800.),(640.,360.),(320.,240.)]{let keys=layout(w,h);for(a,k)in keys.iter().enumerate(){assert!(k.x>=0.&&k.y>=0.&&k.x+k.width<=w&&k.y+k.height<=h);for other in keys.iter().skip(a+1){assert!(!(k.x<other.x+other.width&&k.x+k.width>other.x&&k.y<other.y+other.height&&k.y+k.height>other.y));}}let get=|id|keys.iter().find(|k|k.id==id).unwrap();assert!(get("tab").x<w/3.);assert!(get("arrow-down").x>w/2.);assert_eq!(get("arrow-up").x,get("arrow-down").x);assert_eq!(get("arrow-left").y,get("arrow-right").y);}}
    #[test]fn invalid_or_too_small_geometry_has_no_input_region(){for(w,h)in[(0.,600.),(300.,600.),(900.,120.),(f32::NAN,600.)]{assert!(layout(w,h).is_empty());}}
}
