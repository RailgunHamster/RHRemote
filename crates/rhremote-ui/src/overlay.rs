use serde_json::{Value, json};
use slint::ComponentHandle;

pub fn focus_stream(state: &Value) {
    #[cfg(windows)]
    unsafe {
        if let Some(owner) = owned_window(state) {
            let _ = windows::Win32::UI::WindowsAndMessaging::SetForegroundWindow(owner);
        }
    }
}

#[cfg(windows)]
unsafe fn owned_window(state: &Value) -> Option<windows::Win32::Foundation::HWND> {
    use windows::Win32::{Foundation::HWND, UI::WindowsAndMessaging::*};
    let value = state["statistics"]["streamWindow"]
        .as_str()?
        .parse::<usize>()
        .ok()?;
    let expected = state["statistics"]["streamProcessId"].as_u64()? as u32;
    if value == 0 || expected == 0 {
        return None;
    }
    let hwnd = HWND(value as *mut _);
    let mut pid = 0;
    unsafe {
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
    }
    (pid == expected).then_some(hwnd)
}

fn placement(
    left: i32,
    top: i32,
    client_width: i32,
    scale: f32,
    expanded: bool,
) -> (i32, i32, i32, i32) {
    let width = ((if expanded { 1020.0 } else { 72.0 }) * scale) as i32;
    let width = width.min((client_width - 16).max(1));
    let height = ((if expanded { 116.0 } else { 28.0 }) * scale) as i32;
    (left + (client_width - width) / 2, top + 4, width, height)
}

pub fn follow(toolbar: &crate::StreamToolbar, state: &Value) -> Value {
    #[cfg(windows)]
    unsafe {
        use raw_window_handle::{HasWindowHandle, RawWindowHandle};
        use slint::winit_030::WinitWindowAccessor;
        use windows::Win32::{
            Foundation::{HWND, POINT, RECT},
            Graphics::Gdi::ClientToScreen,
            UI::WindowsAndMessaging::*,
        };
        let Some(owner) = owned_window(state) else {
            let _ = toolbar.hide();
            return json!({"visible":false});
        };
        let mut rect = RECT::default();
        let mut origin = POINT::default();
        if GetClientRect(owner, &mut rect).is_err()
            || !ClientToScreen(owner, &mut origin).as_bool()
            || IsIconic(owner).as_bool()
            || !IsWindowVisible(owner).as_bool()
        {
            let _ = toolbar.hide();
            return json!({"visible":false});
        }
        // Create without activating: the toolbar must not steal the streamed keyboard focus.
        if !toolbar.window().is_visible() {
            let _ = toolbar.show();
        }
        let (x, y, width, height) = placement(
            origin.x,
            origin.y,
            rect.right - rect.left,
            toolbar.window().scale_factor(),
            toolbar.get_expanded(),
        );
        toolbar
            .window()
            .set_size(slint::PhysicalSize::new(width as u32, height as u32));
        toolbar
            .window()
            .set_position(slint::PhysicalPosition::new(x, y));
        toolbar.window().with_winit_window(|w| {
            if let Ok(handle) = w.window_handle() {
                if let RawWindowHandle::Win32(handle) = handle.as_raw() {
                    let hwnd = HWND(handle.hwnd.get() as *mut _);
                    let new_owner = GetWindowLongPtrW(hwnd, GWLP_HWNDPARENT) != owner.0 as isize;
                    SetWindowLongPtrW(hwnd, GWLP_HWNDPARENT, owner.0 as isize);
                    let style = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
                    SetWindowLongPtrW(
                        hwnd,
                        GWL_EXSTYLE,
                        (style & !(WS_EX_APPWINDOW.0 as isize)) | WS_EX_TOOLWINDOW.0 as isize,
                    );
                    let _ = SetWindowPos(
                        hwnd,
                        Some(HWND_TOP),
                        x,
                        y,
                        width,
                        height,
                        SWP_NOACTIVATE
                            | SWP_SHOWWINDOW
                            | if new_owner {
                                SWP_NOSENDCHANGING
                            } else {
                                SWP_NOZORDER
                            },
                    );
                }
            }
        });
        return json!({"visible":true,"expanded":toolbar.get_expanded(),"x":x,"y":y,"width":width,"height":height,"owner":format!("{}",owner.0 as usize)});
    }
    #[cfg(not(windows))]
    {
        let _ = toolbar.hide();
        json!({"visible":false})
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn placement_stays_on_negative_secondary_and_collapses_in_place() {
        assert_eq!(
            super::placement(-3840, 0, 1920, 1.0, true),
            (-3390, 4, 1020, 116)
        );
        assert_eq!(
            super::placement(-3840, 0, 1920, 1.0, false),
            (-2916, 4, 72, 28)
        );
        let p = super::placement(0, 20, 800, 1.5, true);
        assert!(p.0 >= 0 && p.0 + p.2 <= 800);
    }
}
