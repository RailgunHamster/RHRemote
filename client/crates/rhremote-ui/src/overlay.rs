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
    pending: bool,
    failed: bool,
) -> (i32, i32, i32, i32) {
    let width = ((if expanded || pending || failed {
        560.0
    } else {
        72.0
    }) * scale) as i32;
    let width = width.min((client_width - 16).max(1));
    let height = ((if failed {
        248.0
    } else if expanded {
        292.0
    } else if pending {
        if (width as f32) / scale < 400.0 {
            160.0
        } else {
            116.0
        }
    } else {
        28.0
    }) * scale) as i32;
    (left + (client_width - width) / 2, top + 4, width, height)
}

#[derive(Clone, Debug, PartialEq)]
struct Session {
    uuid: String,
    attempt: u64,
}
impl Session {
    fn read(state: &Value) -> Option<Self> {
        let connection = &state["connection"];
        if state["page"] != "connection"
            || connection["operation"] != "connect"
            || !matches!(
                connection["phase"].as_str(),
                Some(
                    "resolving"
                        | "starting"
                        | "first-frame"
                        | "connected"
                        | "reconnecting"
                        | "cancelling"
                        | "cleaning"
                        | "failed"
                )
            )
        {
            return None;
        }
        let uuid = connection["uuid"].as_str()?.to_owned();
        let attempt = connection["attempt"].as_u64()?;
        (!uuid.is_empty() && attempt > 0).then_some(Self { uuid, attempt })
    }
}

#[derive(Clone, Debug)]
struct Anchor {
    session: Session,
    phase: String,
    client: (i32, i32, i32),
    monitor: usize,
    bounds: (i32, i32, i32, i32),
}

/// The recovery controls belong to the user's connection, not one native child.
/// Keep only a verified window location; never pick a replacement monitor.
#[derive(Default)]
pub struct Follower {
    anchor: Option<Anchor>,
    detached: bool,
}
impl Follower {
    fn reconcile(&mut self, state: &Value) -> Option<Session> {
        let session = Session::read(state);
        if let Some(anchor) = self.anchor.as_mut() {
            let retry = session.as_ref().is_some_and(|next| {
                anchor.phase == "failed"
                    && next.uuid == anchor.session.uuid
                    && anchor.session.attempt.checked_add(1) == Some(next.attempt)
                    && state["connection"]["busy"] == true
            });
            if session.as_ref() != Some(&anchor.session) && !retry {
                self.anchor = None;
            } else {
                anchor.session = session.clone().unwrap();
                anchor.phase = state["connection"]["phase"].as_str().unwrap_or("").into();
            }
        }
        if self.anchor.is_none() {
            self.detached = false;
        }
        session
    }

    pub fn follow(&mut self, toolbar: &crate::StreamToolbar, state: &Value) -> Value {
        let Some(session) = self.reconcile(state) else {
            let _ = toolbar.hide();
            return json!({"visible":false});
        };
        #[cfg(windows)]
        unsafe {
            use raw_window_handle::{HasWindowHandle, RawWindowHandle};
            use slint::winit_030::WinitWindowAccessor;
            use windows::Win32::{
                Foundation::{HWND, POINT, RECT},
                Graphics::Gdi::*,
                UI::WindowsAndMessaging::*,
            };
            let candidate = owned_window(state);
            // An intentional minimize still hides the controls. A window being
            // hidden/destroyed by native cleanup instead needs the saved anchor.
            if candidate.is_some_and(|window| IsIconic(window).as_bool()) {
                let _ = toolbar.hide();
                return json!({"visible":false,"reason":"native-minimized"});
            }
            let mut monitor = MONITORINFO {
                cbSize: std::mem::size_of::<MONITORINFO>() as u32,
                ..Default::default()
            };
            let mut native_window_state = "missing-or-stale-native-window";
            let verified = candidate.and_then(|window| {
                let mut rect = RECT::default();
                let mut origin = POINT::default();
                if !IsWindowVisible(window).as_bool() {
                    native_window_state = "hidden-native-window";
                    return None;
                }
                if GetClientRect(window, &mut rect).is_err()
                    || !ClientToScreen(window, &mut origin).as_bool()
                {
                    native_window_state = "native-geometry-unavailable";
                    return None;
                }
                let monitor_handle = MonitorFromWindow(window, MONITOR_DEFAULTTONULL);
                if !GetMonitorInfoW(monitor_handle, &mut monitor).as_bool() {
                    native_window_state = "native-monitor-unavailable";
                    return None;
                }
                native_window_state = "visible-native-window";
                Some((
                    window,
                    (origin.x, origin.y, rect.right - rect.left),
                    monitor_handle,
                ))
            });
            let owner = verified.map(|(window, _, _)| window);
            let detached = owner.is_none();
            let (left, top, client_width) = if let Some((_, client, monitor_handle)) = verified {
                let r = monitor.rcMonitor;
                self.anchor = Some(Anchor {
                    session: session.clone(),
                    phase: state["connection"]["phase"].as_str().unwrap_or("").into(),
                    client,
                    monitor: monitor_handle.0 as usize,
                    bounds: (r.left, r.top, r.right, r.bottom),
                });
                client
            } else {
                let Some(anchor) = self.anchor.as_ref() else {
                    let _ = toolbar.hide();
                    return json!({"visible":false});
                };
                if !GetMonitorInfoW(HMONITOR(anchor.monitor as *mut _), &mut monitor).as_bool() || {
                    let r = monitor.rcMonitor;
                    (r.left, r.top, r.right, r.bottom) != anchor.bounds
                } {
                    self.anchor = None;
                    let _ = toolbar.hide();
                    return json!({"visible":false});
                }
                // The core observes native termination on its next poll. Cover that
                // short handover as well, before it reports phase=reconnecting.
                toolbar.set_busy(true);
                if !toolbar.get_connection_failed() {
                    toolbar.set_pending(true);
                    if !toolbar.get_canceling() && state["connection"]["phase"] == "connected" {
                        toolbar.set_status("正在恢复连接".into());
                        toolbar.set_pending_cancel_label("取消恢复".into());
                    }
                }
                anchor.client
            };
            // Create without activating: the toolbar must not steal the streamed keyboard focus.
            if !toolbar.window().is_visible() {
                let _ = toolbar.show();
            }
            let (mut x, mut y, mut width, mut height) = placement(
                left,
                top,
                client_width,
                toolbar.window().scale_factor(),
                toolbar.get_expanded(),
                toolbar.get_pending(),
                toolbar.get_connection_failed(),
            );
            if detached {
                let r = monitor.rcWork;
                width = width.min((r.right - r.left - 16).max(1));
                height = height.min((r.bottom - r.top - 12).max(1));
                x = x.clamp(r.left + 8, (r.right - width - 8).max(r.left + 8));
                y = y.clamp(r.top + 4, (r.bottom - height - 8).max(r.top + 4));
            }
            toolbar
                .window()
                .set_size(slint::PhysicalSize::new(width as u32, height as u32));
            toolbar
                .window()
                .set_position(slint::PhysicalPosition::new(x, y));
            let mut shape = serde_json::Value::Null;
            toolbar.window().with_winit_window(|w| {
                if let Ok(handle) = w.window_handle() {
                    if let RawWindowHandle::Win32(handle) = handle.as_raw() {
                        let hwnd = HWND(handle.hwnd.get() as *mut _);
                        let owner_value = owner.map_or(0, |window| window.0 as isize);
                        let new_owner = GetWindowLongPtrW(hwnd, GWLP_HWNDPARENT) != owner_value
                            || self.detached != detached;
                        SetWindowLongPtrW(hwnd, GWLP_HWNDPARENT, owner_value);
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
                        shape = crate::ui_rendering::toolbar_shape(
                            hwnd,
                            toolbar.window().scale_factor(),
                            toolbar.get_expanded()
                                || toolbar.get_pending()
                                || toolbar.get_connection_failed(),
                            &state["toolbar"]["shape"],
                        );
                    }
                }
            });
            self.detached = detached;
            let toggle_width = (72.0 * toolbar.window().scale_factor()) as i32;
            return json!({"visible":true,"expanded":toolbar.get_expanded(),"pending":toolbar.get_pending(),"connectionFailed":toolbar.get_connection_failed(),"detached":detached,"nativeWindowState":native_window_state,"uuid":session.uuid,"attempt":session.attempt,"bodyVisible":toolbar.get_expanded() || toolbar.get_pending() || toolbar.get_connection_failed(),"x":x,"y":y,"width":width,"height":height,"shape":shape,"toggle":{"x":x+(width-toggle_width)/2,"y":y,"width":toggle_width,"height":(28.0*toolbar.window().scale_factor()) as i32},"owner":format!("{}",owner.map_or(0,|window|window.0 as usize))});
        }
        #[cfg(not(windows))]
        {
            let _ = toolbar.hide();
            json!({"visible":false})
        }
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
            if state["streamMenu"] == "feedback" {
                26
            } else {
                picker.get_labels().row_count()
            },
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
    use super::*;
    fn state(uuid: &str, attempt: u64, phase: &str) -> Value {
        json!({"page":"connection", "connection":{"uuid":uuid,"attempt":attempt,"phase":phase,"operation":"connect","busy":phase!="failed"}})
    }
    fn follower() -> Follower {
        Follower {
            anchor: Some(Anchor {
                session: Session {
                    uuid: "host".into(),
                    attempt: 3,
                },
                phase: "connected".into(),
                client: (-3840, 0, 1920),
                monitor: 1,
                bounds: (-3840, 0, 0, 2160),
            }),
            detached: false,
        }
    }
    #[test]
    fn native_child_replacement_keeps_only_the_same_logical_session_anchor() {
        let mut follower = follower();
        for phase in [
            "connected",
            "reconnecting",
            "cancelling",
            "cleaning",
            "failed",
        ] {
            let mut s = state("host", 3, phase);
            s["statistics"] = json!({"streamWindow":"0","streamProcessId":99});
            follower.reconcile(&s);
            assert_eq!(follower.anchor.as_ref().unwrap().client, (-3840, 0, 1920));
        }
        follower.reconcile(&state("other", 3, "starting"));
        assert!(
            follower.anchor.is_none(),
            "another host must never inherit recovery controls"
        );
    }
    #[test]
    fn explicit_retry_can_reuse_a_failed_anchor_but_other_requests_cannot() {
        let mut f = follower();
        f.reconcile(&state("host", 3, "failed"));
        f.reconcile(&state("host", 4, "starting"));
        assert_eq!(f.anchor.as_ref().unwrap().session.attempt, 4);
        for (uuid, attempt, previous) in [
            ("host", 5, "failed"),
            ("other", 4, "failed"),
            ("host", 4, "connected"),
        ] {
            let mut f = follower();
            f.reconcile(&state("host", 3, previous));
            f.reconcile(&state(uuid, attempt, "starting"));
            assert!(f.anchor.is_none(), "unrelated request inherited anchor");
        }
    }
    #[test]
    fn dismissal_end_and_pairing_drop_the_saved_location() {
        for phase in ["ended", "cancelled", ""] {
            let mut f = follower();
            f.reconcile(&state("host", 3, phase));
            assert!(f.anchor.is_none());
        }
        for (key, value) in [("page", "devices"), ("page", "global-settings")] {
            let mut s = state("host", 3, "failed");
            s[key] = json!(value);
            let mut f = follower();
            f.reconcile(&s);
            assert!(f.anchor.is_none());
        }
        let mut s = state("host", 3, "failed");
        s["connection"]["operation"] = json!("pair");
        let mut f = follower();
        f.reconcile(&s);
        assert!(f.anchor.is_none());
    }
    #[test]
    fn failure_card_keeps_the_same_top_handle_at_each_scale() {
        for scale in [1.0, 1.5, 2.25] {
            for client_width in [355, 800, 1920, 3840] {
                let card = placement(-3840, 20, client_width, scale, false, false, true);
                let handle = placement(-3840, 20, client_width, scale, false, false, false);
                assert!((card.0 + (card.2 - handle.2) / 2 - handle.0).abs() <= 1);
                assert_eq!(card.1, handle.1);
                assert_eq!(card.3, (248.0 * scale) as i32);
            }
        }
    }
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
            super::placement(-3840, 0, 1920, 1.0, true, false, false),
            (-3160, 4, 560, 292)
        );
        assert_eq!(
            super::placement(-3840, 0, 1920, 1.0, false, false, false),
            (-2916, 4, 72, 28)
        );
        let p = super::placement(0, 20, 800, 1.5, true, false, false);
        assert!(p.0 >= 0 && p.0 + p.2 <= 800);
        for scale in [1.0, 1.5, 2.25] {
            for client_width in [800, 1920, 3840] {
                let open = super::placement(-3840, 20, client_width, scale, true, false, false);
                let closed = super::placement(-3840, 20, client_width, scale, false, false, false);
                // Rounding may shift the centered hit target by at most one physical pixel.
                let toggle_x = open.0 + (open.2 - closed.2) / 2;
                assert!((toggle_x - closed.0).abs() <= 1);
                assert_eq!(open.1, closed.1);
            }
        }
    }
    #[test]
    fn pending_card_keeps_the_handle_anchored_and_wraps_at_small_widths() {
        for scale in [1.0, 1.5, 2.25] {
            for client_width in [800, 1920, 3840] {
                let card = super::placement(-3840, 20, client_width, scale, false, true, false);
                let collapsed =
                    super::placement(-3840, 20, client_width, scale, false, false, false);
                assert!((card.0 + (card.2 - collapsed.2) / 2 - collapsed.0).abs() <= 1);
                assert_eq!(card.1, collapsed.1);
                let logical_height = if (card.2 as f32) / scale < 400.0 {
                    160.0
                } else {
                    116.0
                };
                assert_eq!(card.3, (logical_height * scale) as i32);
            }
        }
    }
}
