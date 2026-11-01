//! Read-only thread state for a window whose process has already been admitted.
use serde_json::{Value,json};
use windows::Win32::{Foundation::HWND,UI::WindowsAndMessaging::*};

pub(crate) fn read_thread_state(window:HWND,expected_pid:u32)->Result<Value,String>{
    unsafe {
        let mut actual_pid=0;
        let thread_id=GetWindowThreadProcessId(window,Some(&mut actual_pid));
        if expected_pid==0||thread_id==0||actual_pid!=expected_pid{return Err("窗口线程不属于本次子进程".into());}
        let mut info=GUITHREADINFO{cbSize:std::mem::size_of::<GUITHREADINFO>()as u32,..Default::default()};
        let available=GetGUIThreadInfo(thread_id,&mut info).is_ok();
        let foreground=GetForegroundWindow();let mut foreground_pid=0;
        if !foreground.0.is_null(){GetWindowThreadProcessId(foreground,Some(&mut foreground_pid));}
        Ok(json!({"threadId":thread_id,"available":available,
            "activeWindowIsStream":available&&info.hwndActive==window,
            "focusWindowIsStream":available&&info.hwndFocus==window,
            "foregroundWindowIsStream":foreground==window,
            "foregroundProcessIsNative":foreground_pid==expected_pid}))
    }
}

#[cfg(test)]
mod tests{
    use super::*;
    use windows::{core::w,Win32::System::Threading::{GetCurrentProcessId,GetCurrentThreadId}};
    struct Hidden(HWND);
    impl Drop for Hidden{fn drop(&mut self){unsafe{let _=DestroyWindow(self.0);}}}
    fn window()->Hidden{Hidden(unsafe{CreateWindowExW(WINDOW_EX_STYLE::default(),w!("STATIC"),w!("RHRemote thread metadata fixture"),WS_POPUP,0,0,40,40,None,None,None,None)}.unwrap())}
    #[test]
    fn reads_the_owned_gui_thread_without_showing_or_changing_the_window(){
        let window=window();let pid=unsafe{GetCurrentProcessId()};
        let before=unsafe{GetWindowLongPtrW(window.0,GWL_STYLE)};
        let state=read_thread_state(window.0,pid).unwrap();
        assert_eq!(state["threadId"],unsafe{GetCurrentThreadId()});assert_eq!(state["available"],true);
        assert_eq!(state["foregroundWindowIsStream"],false);
        for key in ["activeWindowIsStream","focusWindowIsStream","foregroundProcessIsNative"]{assert!(state[key].is_boolean());}
        assert_eq!(unsafe{GetWindowLongPtrW(window.0,GWL_STYLE)},before);assert!(!unsafe{IsWindowVisible(window.0)}.as_bool());
        assert!(!state.to_string().contains("hwnd"));assert!(!state.to_string().contains("fixture"));
    }
    #[test]
    fn refuses_a_window_outside_the_expected_process(){
        let window=window();let pid=unsafe{GetCurrentProcessId()};
        assert!(read_thread_state(window.0,0).is_err());assert!(read_thread_state(window.0,pid+1).is_err());
        assert!(!unsafe{IsWindowVisible(window.0)}.as_bool());
    }
    #[test]
    fn a_destroyed_window_has_no_thread_snapshot(){
        let window=window();let handle=window.0;drop(window);
        assert!(read_thread_state(handle,unsafe{GetCurrentProcessId()}).is_err());
    }
}
