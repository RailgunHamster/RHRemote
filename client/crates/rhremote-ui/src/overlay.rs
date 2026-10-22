use serde_json::{Value, json};
use slint::{ComponentHandle, Model};

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
    let height = ((if expanded { 144.0 } else { 28.0 }) * scale) as i32;
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
        let toggle_width = (72.0 * toolbar.window().scale_factor()) as i32;
        return json!({"visible":true,"expanded":toolbar.get_expanded(),"x":x,"y":y,"width":width,"height":height,"toggle":{"x":x+(width-toggle_width)/2,"y":y,"width":toggle_width,"height":(28.0*toolbar.window().scale_factor()) as i32},"owner":format!("{}",owner.0 as usize)});
    }
    #[cfg(not(windows))]
    {
        let _ = toolbar.hide();
        json!({"visible":false})
    }
}

fn picker_placement(
    anchor: (i32, i32, i32, i32),
    work: (i32, i32, i32, i32),
    scale: f32,
    rows: usize,
) -> (i32, i32, i32, i32) {
    let (left, top, right, bottom) = work;
    let width = ((420.0 * scale) as i32).min((right - left - 16).max(1));
    let content_height = (62.0 + rows.min(26) as f32 * 42.0).min(400.0);
    let height = ((content_height * scale) as i32).min((bottom - top - 16).max(1));
    let x = (anchor.0 + (anchor.2 - width) / 2).clamp(left + 8, (right - width - 8).max(left + 8));
    let below = anchor.1 + anchor.3 + 4;
    let y = if below + height <= bottom - 8 {
        below
    } else {
        (anchor.1 - height - 4).max(top + 8)
    };
    (x, y, width, height)
}

pub fn follow_picker(picker: &crate::StreamPicker, state: &Value, toolbar: &Value) -> Value {
    if state["streamMenu"].as_str().unwrap_or("").is_empty()
        || toolbar["visible"] != true
        || state["controls"] != true
    {
        let _ = picker.hide();
        return json!({"visible":false});
    }
    #[cfg(windows)]
    unsafe {
        use raw_window_handle::{HasWindowHandle, RawWindowHandle};
        use slint::winit_030::WinitWindowAccessor;
        use windows::Win32::{Foundation::HWND, Graphics::Gdi::*, UI::WindowsAndMessaging::*};
        let Some(owner) = owned_window(state) else {
            let _ = picker.hide();
            return json!({"visible":false});
        };
        let mut monitor = MONITORINFO {
            cbSize: std::mem::size_of::<MONITORINFO>() as u32,
            ..Default::default()
        };
        if !GetMonitorInfoW(
            MonitorFromWindow(owner, MONITOR_DEFAULTTONEAREST),
            &mut monitor,
        )
        .as_bool()
        {
            let _ = picker.hide();
            return json!({"visible":false});
        }
        let anchor = (
            toolbar["x"].as_i64().unwrap_or(0) as i32,
            toolbar["y"].as_i64().unwrap_or(0) as i32,
            toolbar["width"].as_i64().unwrap_or(1) as i32,
            toolbar["height"].as_i64().unwrap_or(1) as i32,
        );
        let r = monitor.rcWork;
        let (x, y, width, height) = picker_placement(
            anchor,
            (r.left, r.top, r.right, r.bottom),
            picker.window().scale_factor(),
            picker.get_labels().row_count(),
        );
        picker
            .window()
            .set_size(slint::PhysicalSize::new(width as u32, height as u32));
        picker
            .window()
            .set_position(slint::PhysicalPosition::new(x, y));
        if !picker.window().is_visible() {
            let _ = picker.show();
        }
        picker.window().with_winit_window(|w| {
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
        return json!({"visible":true,"kind":state["streamMenu"],"x":x,"y":y,"width":width,"height":height});
    }
    #[cfg(not(windows))]
    {
        let _ = picker.hide();
        json!({"visible":false})
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn picker_stays_in_secondary_work_area_and_can_extend_beyond_toolbar() {
        let p = super::picker_placement((-2872, 544, 1904, 324), (-3840, 0, 0, 2120), 2.25, 26);
        assert_eq!(p, (-2393, 872, 945, 900));
        let p = super::picker_placement((-2872, 1800, 1904, 324), (-3840, 0, 0, 2120), 2.25, 26);
        assert!(p.1 < 1800 && p.1 + p.3 <= 2120);
        let small = super::picker_placement((-2872, 544, 1904, 324), (-3840, 0, 0, 2120), 2.25, 2);
        assert_eq!(small.3, 328);
    }
    #[test]
    fn placement_stays_on_negative_secondary_and_collapses_in_place() {
        assert_eq!(
            super::placement(-3840, 0, 1920, 1.0, true),
            (-3390, 4, 1020, 144)
        );
        assert_eq!(
            super::placement(-3840, 0, 1920, 1.0, false),
            (-2916, 4, 72, 28)
        );
        let p = super::placement(0, 20, 800, 1.5, true);
        assert!(p.0 >= 0 && p.0 + p.2 <= 800);
        for scale in [1.0, 1.5, 2.25] {
            for client_width in [800, 1920, 3840] {
                let open = super::placement(-3840, 20, client_width, scale, true);
                let closed = super::placement(-3840, 20, client_width, scale, false);
                // Rounding may shift the centered hit target by at most one physical pixel.
                let toggle_x = open.0 + (open.2 - closed.2) / 2;
                assert!((toggle_x - closed.0).abs() <= 1);
                assert_eq!(open.1, closed.1);
            }
        }
    }
}
