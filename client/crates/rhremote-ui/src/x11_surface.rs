//! X11 placement and input regions for RHRemote's own session controls.
use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use serde_json::{Value, json};
use slint::winit_030::WinitWindowAccessor;
use std::cell::RefCell;
use x11rb::{connection::Connection, protocol::{shape, xproto::{self, ConnectionExt}}, rust_connection::RustConnection, wrapper::ConnectionExt as _};
use x11rb::protocol::shape::ConnectionExt as _;
use x11rb::protocol::randr::ConnectionExt as _;

thread_local! {
    static CONNECTION: RefCell<Option<RustConnection>> = const { RefCell::new(None) };
    static APPLIED: RefCell<std::collections::HashMap<u32, Value>> = RefCell::new(std::collections::HashMap::new());
}
fn connection<T>(f: impl FnOnce(&RustConnection) -> Result<T, String>) -> Result<T, String> {
    CONNECTION.with(|slot| {
        let mut slot = slot.borrow_mut();
        if slot.is_none() { *slot = Some(x11rb::connect(None).map_err(|e|e.to_string())?.0); }
        f(slot.as_ref().unwrap())
    })
}
fn atom(c:&RustConnection,name:&str)->Result<u32,String>{c.intern_atom(false,name.as_bytes()).map_err(|e|e.to_string())?.reply().map(|v|v.atom).map_err(|e|e.to_string())}

pub(crate) fn verify_output_origin(name:&str,position:(i32,i32))->Result<(),String>{
    connection(|c|{
        let root=c.setup().roots.first().ok_or("X11 根窗口不可用")?.root;
        let resources=c.randr_get_screen_resources_current(root).map_err(|e|e.to_string())?.reply().map_err(|e|e.to_string())?;
        for output in resources.outputs {
            let output=c.randr_get_output_info(output,0).map_err(|e|e.to_string())?.reply().map_err(|e|e.to_string())?;
            if output.name!=name.as_bytes()||output.crtc==0{continue;}
            let bounds=c.randr_get_crtc_info(output.crtc,0).map_err(|e|e.to_string())?.reply().map_err(|e|e.to_string())?;
            if position.0>=i32::from(bounds.x)&&position.1>=i32::from(bounds.y)&&position.0<i32::from(bounds.x)+i32::from(bounds.width)&&position.1<i32::from(bounds.y)+i32::from(bounds.height){return Ok(());}
        }
        Err("指定的 X11 测试屏未活动，或预览位置超出该屏幕".into())
    })
}

#[derive(Clone, Copy)]
pub(crate) struct Geometry { pub xid:u32, pub root:u32, pub x:i32, pub y:i32, pub width:u32, pub height:u32, pub bounds:(i32,i32,i32,i32) }

pub(crate) fn owned(state:&Value)->Result<Geometry,String> {
    let xid=state["statistics"]["streamWindow"].as_str().and_then(|v|v.parse::<u32>().ok()).filter(|v|*v!=0).ok_or("native-window-unavailable")?;
    let pid=state["statistics"]["streamProcessId"].as_u64().and_then(|v|u32::try_from(v).ok()).filter(|v|*v!=0).ok_or("native-window-unavailable")?;
    connection(|c| {
        let property=c.get_property(false,xid,atom(c,"_NET_WM_PID")?,xproto::AtomEnum::CARDINAL,0,1).map_err(|e|e.to_string())?.reply().map_err(|e|e.to_string())?;
        if property.value32().and_then(|mut v|v.next())!=Some(pid){return Err("native-window-unavailable".into());}
        let attributes=c.get_window_attributes(xid).map_err(|e|e.to_string())?.reply().map_err(|e|e.to_string())?;
        if attributes.map_state!=xproto::MapState::VIEWABLE {return Err("native-hidden".into());}
        let geometry=c.get_geometry(xid).map_err(|e|e.to_string())?.reply().map_err(|e|e.to_string())?;
        let root=c.get_geometry(geometry.root).map_err(|e|e.to_string())?.reply().map_err(|e|e.to_string())?;
        let position=c.translate_coordinates(xid,geometry.root,0,0).map_err(|e|e.to_string())?.reply().map_err(|e|e.to_string())?;
        if geometry.width==0||geometry.height==0{return Err("native-empty".into());}
        Ok(Geometry{xid,root:geometry.root,x:position.dst_x.into(),y:position.dst_y.into(),width:geometry.width.into(),height:geometry.height.into(),bounds:(0,0,root.width.into(),root.height.into())})
    }).map_err(|reason|if matches!(reason.as_str(),"native-hidden"|"native-empty"){reason}else{"native-window-unavailable".into()})
}

fn xid(window:&slint::Window)->Result<u32,String>{
    window.with_winit_window(|w|match w.window_handle().map_err(|e|e.to_string())?.as_raw(){
        RawWindowHandle::Xlib(h)=>u32::try_from(h.window).map_err(|e|e.to_string()),
        RawWindowHandle::Xcb(h)=>Ok(h.window.get()),
        _=>Err("此悬浮按键需要 X11 会话".into()),
    }).ok_or("悬浮窗口句柄不可用")?
}

/// Only key rectangles receive input; empty pixels pass through to the stream.
pub(crate) fn place(window:&slint::Window,owner:Geometry,rect:(i32,i32,i32,i32),regions:&[(i16,i16,u16,u16)],alpha:u8)->Result<Value,String>{
    if rect.2<1||rect.3<1||regions.is_empty(){return Err("悬浮窗口范围无效".into());}
    window.show().map_err(|e|e.to_string())?;
    let target=xid(window)?;
    let key=json!([owner.xid,rect,regions,alpha]);
    if APPLIED.with(|s|s.borrow().get(&target)==Some(&key)){return Ok(json!({"applied":true,"xid":target,"alpha":alpha}));}
    window.set_position(slint::PhysicalPosition::new(rect.0,rect.1));
    window.set_size(slint::PhysicalSize::new(rect.2 as u32,rect.3 as u32));
    connection(|c|{
        c.change_property32(xproto::PropMode::REPLACE,target,xproto::AtomEnum::WM_TRANSIENT_FOR,xproto::AtomEnum::WINDOW,&[owner.xid]).map_err(|e|e.to_string())?.check().map_err(|e|e.to_string())?;
        c.change_property32(xproto::PropMode::REPLACE,target,atom(c,"_NET_WM_WINDOW_TYPE")?,xproto::AtomEnum::ATOM,&[atom(c,"_NET_WM_WINDOW_TYPE_UTILITY")?]).map_err(|e|e.to_string())?.check().map_err(|e|e.to_string())?;
        c.change_property32(xproto::PropMode::REPLACE,target,atom(c,"WM_HINTS")?,atom(c,"WM_HINTS")?,&[1,0,0,0,0,0,0,0,0]).map_err(|e|e.to_string())?.check().map_err(|e|e.to_string())?;
        c.change_property32(xproto::PropMode::REPLACE,target,atom(c,"_NET_WM_WINDOW_OPACITY")?,xproto::AtomEnum::CARDINAL,&[u32::from(alpha)*0x01010101]).map_err(|e|e.to_string())?.check().map_err(|e|e.to_string())?;
        let rectangles=regions.iter().map(|&(x,y,width,height)|xproto::Rectangle{x,y,width,height}).collect::<Vec<_>>();
        for kind in [shape::SK::BOUNDING,shape::SK::INPUT]{c.shape_rectangles(shape::SO::SET,kind,xproto::ClipOrdering::UNSORTED,target,0,0,&rectangles).map_err(|e|e.to_string())?.check().map_err(|e|e.to_string())?;}
        c.flush().map_err(|e|e.to_string())
    })?;
    APPLIED.with(|s|{let mut s=s.borrow_mut();if s.len()>16{s.clear();}s.insert(target,key);});
    Ok(json!({"applied":true,"xid":target,"alpha":alpha,"rectangles":regions.len()}))
}

pub(crate) fn focus(state:&Value){
    let Ok(owner)=owned(state)else{return};
    let _=connection(|c|{
        let event=xproto::ClientMessageEvent::new(32,owner.xid,atom(c,"_NET_ACTIVE_WINDOW")?,[1,0,0,0,0]);
        c.send_event(false,owner.root,xproto::EventMask::SUBSTRUCTURE_REDIRECT|xproto::EventMask::SUBSTRUCTURE_NOTIFY,event).map_err(|e|e.to_string())?;
        c.flush().map_err(|e|e.to_string())
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requires the isolated X11 acceptance desktop"]
    fn only_a_mapped_window_with_the_expected_process_is_an_overlay_owner(){
        let (c,screen)=x11rb::connect(None).unwrap();let root=c.setup().roots[screen].root;
        let window=c.generate_id().unwrap();
        c.create_window(x11rb::COPY_DEPTH_FROM_PARENT,window,root,20,30,640,480,0,xproto::WindowClass::INPUT_OUTPUT,0,&xproto::CreateWindowAux::new()).unwrap().check().unwrap();
        c.change_property32(xproto::PropMode::REPLACE,window,atom(&c,"_NET_WM_PID").unwrap(),xproto::AtomEnum::CARDINAL,&[std::process::id()]).unwrap().check().unwrap();
        let mut state=json!({"statistics":{"streamWindow":window.to_string(),"streamProcessId":std::process::id()}});
        assert_eq!(owned(&state).err().unwrap(),"native-hidden");
        c.map_window(window).unwrap().check().unwrap();
        let geometry=owned(&state).unwrap();assert_eq!((geometry.width,geometry.height),(640,480));
        state["statistics"]["streamProcessId"]=json!(std::process::id()+1);
        assert_eq!(owned(&state).err().unwrap(),"native-window-unavailable");
        c.destroy_window(window).unwrap().check().unwrap();
        state["statistics"]["streamProcessId"]=json!(std::process::id());
        assert_eq!(owned(&state).err().unwrap(),"native-window-unavailable");
    }
    #[test]
    #[ignore = "requires DUMMY0 on the isolated X11 acceptance desktop"]
    fn isolated_preview_cannot_silently_move_to_another_output(){
        assert!(verify_output_origin("DUMMY0",(16,16)).is_ok());
        assert!(verify_output_origin("missing-output",(16,16)).is_err());
        assert!(verify_output_origin("DUMMY0",(-10000,16)).is_err());
    }
}
