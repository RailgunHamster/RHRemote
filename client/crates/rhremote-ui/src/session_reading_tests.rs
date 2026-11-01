//! Read session failures through the same widget/keyboard API as the application.
use super::*;
use i_slint_backend_testing::ElementQuery;

fn evidence(name: &str, value: &Value) {
    let Some(root) = std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR").filter(|v| !v.is_empty()) else { return };
    let root = std::path::PathBuf::from(root);
    assert!(root.is_absolute() && root.is_dir());
    let path = root.join(name);
    assert!(!path.exists());
    std::fs::write(path, serde_json::to_vec_pretty(value).unwrap()).unwrap();
}

fn capture(f: &Fixture, window: &str, name: &str) {
    let Some(root) = std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR").filter(|v| !v.is_empty()) else { return };
    let root = std::path::PathBuf::from(root);
    assert!(root.is_absolute() && root.is_dir());
    let path = root.join(format!("{name}.png"));
    assert!(!path.exists());
    let frame = match window { "picker" => f.picker.window(), _ => f.toolbar.window() }.take_snapshot().unwrap();
    image::save_buffer(path, frame.as_bytes(), frame.width(), frame.height(), image::ColorType::Rgba8).unwrap();
}

fn inspect(f: &mut Fixture, window: &str) -> Vec<Value> {
    let result = f.request("view.ui.inspect", json!({"window":window}));
    assert_eq!(result["status"], "complete", "{result}");
    result["result"]["controls"].as_array().unwrap().clone()
}

fn scroll(f: &mut Fixture, window: &str, id: &str) -> Value {
    inspect(f, window).into_iter().find(|r| r["id"] == id).expect("Error scroll area")
}

fn button_id(f: &mut Fixture, window: &str, action: &str) -> String {
    let prefix = format!("action|{action}|");
    let controls = inspect(f, window);
    let ids = controls.iter().filter_map(|r| r["id"].as_str()).filter(|id| id.starts_with(&prefix)).collect::<Vec<_>>();
    assert_eq!(ids.len(), 1, "Expected a unique actual button for {action}");
    ids[0].to_owned()
}

fn set_message(f: &Fixture, window: &str, message: &str) {
    if window == "picker" { f.picker.set_error(message.into()); }
    else { f.toolbar.set_connection_error(message.into()); }
    match window { "picker" => f.picker.window(), _ => f.toolbar.window() }.take_snapshot().unwrap();
}

fn open(f: &mut Fixture, window: &str, width: f32, dark: bool, message: &str) {
    f.controller.stream_state(true, json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}));
    if window == "picker" {
        if f.controller.state()["controls"] != true {
            assert_eq!(f.controller.dispatch("stream.controls.toggle", json!({}))["ok"], true);
        }
        *f.boundary.control_error.lock().unwrap() = Some(message.into());
        assert_eq!(f.controller.dispatch("stream.display.select", json!({"displayId":"fixture"}))["ok"], false);
        assert_eq!(f.controller.state()["controlAction"]["error"], message);
        assert_eq!(f.controller.dispatch("stream.menu.open", json!({"menu":"feedback"}))["ok"], true);
        f.picker.set_kind("feedback".into());
        f.picker.global::<crate::Palette>().set_dark(dark);
        f.picker.window().set_size(LogicalSize::new(width, 248.));
        f.picker.show().unwrap();
    } else {
        f.controller.stream_state(false, json!({"phase":"error","error":message,"firstFrame":false,"nativeCleanupPending":false}));
        assert_eq!(f.controller.state()["connection"]["phase"], "failed");
        let attempt = f.controller.state()["connection"]["attempt"].clone();
        f.controller.toolbar_state(json!({"visible":true,"detached":true,"connectionFailed":true,"uuid":"host","attempt":attempt}));
        f.toolbar.set_connection_failed(true);
        f.toolbar.set_pending(false);
        f.toolbar.global::<crate::Palette>().set_dark(dark);
        f.toolbar.window().set_size(LogicalSize::new(width, 248.));
    }
    set_message(f, window, message);
    f.calls.borrow_mut().clear();
    f.boundary.calls.lock().unwrap().clear();
    f.boundary.requests.lock().unwrap().clear();
}

fn buttons_visible(f: &Fixture, window: &str) -> bool {
    let elements = if window == "picker" { ElementQuery::from_root(&f.picker).find_all() }
        else { ElementQuery::from_root(&f.toolbar).find_all() };
    let size = match window { "picker" => f.picker.window(), _ => f.toolbar.window() }.size();
    let ids: &[&str] = if window == "picker" { &["action|stream.menu.close|"] }
        else { &["action|navigation.back|", "action|connection.dismiss|", "action|connection.retry|"] };
    ids.iter().all(|id| elements.iter().any(|e| {
        if e.accessible_id().is_none_or(|v| !v.starts_with(id)) { return false; }
        let p = e.absolute_position(); let s = e.size();
        e.computed_opacity() > 0. && s.width > 0. && s.height > 0. && p.x >= -1. && p.y >= -1.
            && p.x+s.width <= size.width as f32+1. && p.y+s.height <= size.height as f32+1.
    }))
}

#[test]
fn session_error_details_are_keyboard_readable_without_hiding_recovery_actions() {
    let _backend = Fixture::new();
    let message = format!("{}\n最后一行：END-OF-SESSION-ERROR", "远程操作失败：网络响应未完整到达，请检查详细原因。\n".repeat(100));
    let mut records = vec![];
    for window in ["picker", "toolbar"] { for width in [300., 560.] { for dark in [false, true] {
        let mut f = Fixture::in_current_backend();
        open(&mut f, window, width, dark, &message);
        let id = if window == "picker" { "scroll|picker" } else { "scroll|connection-error" };
        let original = f.controller.state()["saved"].clone();
        let start = scroll(&mut f, window, id);
        let visible_before = buttons_visible(&f, window);
        // Enter the text by normal Tab navigation from the fixed header button.
        let handle = button_id(&mut f, window, if window == "picker" { "stream.menu.close" } else { "navigation.back" });
        assert_eq!(f.request("view.ui.focus", json!({"window":window,"control":handle}))["status"], "complete");
        let tab = f.request("view.ui.key", json!({"window":window,"key":"Tab"}));
        let tab_reaches_text = tab["result"]["focused"].as_array().is_some_and(|rows| rows.iter().any(|v| v == id));
        let focused = f.request("view.ui.focus", json!({"window":window,"control":id}));
        let actual_focus = scroll(&mut f, window, id)["focused"] == true;
        let mut steps = vec![];
        for key in ["PageDown", "Down", "End", "Up", "PageUp", "Home", "End"] {
            let result = f.request("view.ui.key", json!({"window":window,"key":key}));
            steps.push(json!({"key":key,"status":result["status"],"area":scroll(&mut f,window,id)}));
        }
        let end = scroll(&mut f, window, id);
        let bottom = end["scroll"]["minimumY"].as_f64().unwrap();
        let at_bottom = bottom < -100. && (end["scroll"]["y"].as_f64().unwrap()-bottom).abs() < 1.;
        let page_down = steps[0]["area"]["scroll"]["y"].as_f64().unwrap() < -1.;
        let down = steps[1]["area"]["scroll"]["y"].as_f64().unwrap() < steps[0]["area"]["scroll"]["y"].as_f64().unwrap();
        let up = steps[3]["area"]["scroll"]["y"].as_f64().unwrap() > bottom;
        let page_up = steps[4]["area"]["scroll"]["y"].as_f64().unwrap() > steps[3]["area"]["scroll"]["y"].as_f64().unwrap();
        let home = steps[5]["area"]["scroll"]["y"].as_f64().unwrap().abs() < 1.;
        let no_actions = f.calls.borrow().is_empty() && f.boundary.requests.lock().unwrap().is_empty();
        let name = format!("session-reading-{window}-{width}-{dark}");
        capture(&f, window, &name);
        set_message(&f, window, &message);
        let same_retained = scroll(&mut f, window, id)["scroll"]["y"] == end["scroll"]["y"];
        set_message(&f, window, &format!("新的错误\n{message}"));
        let new_reset = scroll(&mut f, window, id)["scroll"]["y"].as_f64().unwrap().abs() < 1.;
        let recovery = if window == "picker" { "stream.menu.close" } else { "connection.retry" };
        let recovery_control = button_id(&mut f, window, recovery);
        assert_eq!(f.request("view.ui.focus", json!({"window":window,"control":recovery_control}))["status"], "complete");
        assert_eq!(f.request("view.ui.key", json!({"window":window,"key":"Enter"}))["status"], "complete");
        let calls = std::mem::take(&mut *f.calls.borrow_mut());
        let response = if calls == vec![(recovery.into(), "".into())] {
            f.controller.dispatch(recovery, crate::args_for(recovery, "", &f.controller.state()))
        } else { json!({"ok":false}) };
        let recovered = response["ok"] == true && f.controller.state()["saved"] == original
            && if window == "picker" { f.controller.state()["streamMenu"] == "" && f.controller.state()["controls"] == true }
            else { f.controller.state()["connection"]["uuid"] == "host" && f.controller.state()["connecting"] == true };
        let passed = tab_reaches_text && focused["status"] == "complete" && actual_focus && at_bottom
            && page_down && down && up && page_up && home && no_actions && visible_before
            && buttons_visible(&f, window) && same_retained && new_reset && recovered;
        records.push(json!({"name":name,"window":window,"width":width,"dark":dark,"start":start,"tabReachesText":tab_reaches_text,
            "focused":actual_focus,"steps":steps,"atBottom":at_bottom,"noBusinessActionsWhileReading":no_actions,"fixedActionsVisible":visible_before,
            "sameMessageRetainsScroll":same_retained,"newMessageStartsAtTop":new_reset,"recoveryCallbacks":calls,"recoveryResponse":response,"recovered":recovered,"passed":passed}));
    }}}
    evidence("session-reading-results188.json", &json!(records));
    let failed = records.iter().filter(|r| r["passed"] != true).map(|r| r["name"].as_str().unwrap()).collect::<Vec<_>>();
    assert!(failed.is_empty(), "Session error reading failed: {failed:?}");
}

#[test]
fn short_session_errors_do_not_add_an_unnecessary_tab_stop() {
    let _backend = Fixture::new();
    for window in ["picker", "toolbar"] { for dark in [false, true] {
        let mut f = Fixture::in_current_backend();
        open(&mut f, window, 420., dark, "操作失败");
        let handle = button_id(&mut f, window, if window == "picker" { "stream.menu.close" } else { "navigation.back" });
        assert_eq!(f.request("view.ui.focus", json!({"window":window,"control":handle}))["status"], "complete");
        let tab = f.request("view.ui.key", json!({"window":window,"key":"Tab"}));
        let id = if window == "picker" { "scroll|picker" } else { "scroll|connection-error" };
        assert!(tab["result"]["focused"].as_array().is_some_and(|rows| !rows.iter().any(|v| v == id)));
        assert!(f.calls.borrow().is_empty());
    }}
}
