use crate::MainWindow;
use rhremote_core::Controller;
use serde_json::json;
use slint::ComponentHandle;

/// The OS event hook and the application test API share the same modal rules.
pub(crate) fn modal(
    ui: &MainWindow,
    controller: &Controller,
    key: &str,
    pressed: bool,
    repeat: bool,
    shift: bool,
) -> Option<Option<&'static str>> {
    if ui.get_sync_clear_prompt() {
        let mut action = None;
        if pressed && !repeat {
            match key {
                "Escape" => {
                    controller.dispatch("sync.clear.resolve", json!({"choice":"cancel"}));
                    action = Some("sync.clear.resolve");
                }
                "Tab" => ui.set_sync_clear_choice(1 - ui.get_sync_clear_choice()),
                "Enter" => {
                    controller.dispatch(
                        "sync.clear.resolve",
                        json!({"choice":if ui.get_sync_clear_choice()==0{"cancel"}else{"remove"}}),
                    );
                    action = Some("sync.clear.resolve");
                }
                _ => {}
            }
        }
        return Some(action);
    }
    if ui.get_navigation_prompt() {
        let mut action = None;
        if pressed && !repeat {
            match key {
                "Escape" => {
                    controller.dispatch("navigation.resolve", json!({"choice":"stay"}));
                    action = Some("navigation.resolve");
                }
                "Tab" => ui.set_navigation_choice(
                    (ui.get_navigation_choice() + if shift { 2 } else { 1 }) % 3,
                ),
                "Enter" => {
                    let choice = ["stay", "discard", "save"][ui.get_navigation_choice() as usize];
                    controller.dispatch("navigation.resolve", json!({"choice":choice}));
                    action = Some("navigation.resolve");
                }
                _ => {}
            }
        }
        return Some(action);
    }
    None
}

pub(crate) fn send(
    ui: &MainWindow,
    controller: &Controller,
    key: &str,
    shift: bool,
    repeat: bool,
) -> Option<&'static str> {
    if let Some(action) = modal(ui, controller, key, true, repeat, shift) {
        return action;
    }
    send_to_window(ui.window(), key, shift, repeat);
    None
}
pub(crate) fn send_to_window(window: &slint::Window, key: &str, shift: bool, repeat: bool) {
    use slint::platform::{Key, WindowEvent};
    let text: slint::SharedString = match key {
        "Tab" => Key::Tab.into(),
        "Enter" => Key::Return.into(),
        "Escape" => Key::Escape.into(),
        "Space" => " ".into(),
        "Up" => Key::UpArrow.into(),
        "Down" => Key::DownArrow.into(),
        "Left" => Key::LeftArrow.into(),
        "Right" => Key::RightArrow.into(),
        "Home" => Key::Home.into(),
        "End" => Key::End.into(),
        _ => return,
    };
    if shift {
        window.dispatch_event(WindowEvent::KeyPressed {
            text: Key::Shift.into(),
        });
    }
    window.dispatch_event(if repeat {
        WindowEvent::KeyPressRepeated { text: text.clone() }
    } else {
        WindowEvent::KeyPressed { text: text.clone() }
    });
    window.dispatch_event(WindowEvent::KeyReleased { text });
    if shift {
        window.dispatch_event(WindowEvent::KeyReleased {
            text: Key::Shift.into(),
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rhremote_core::{Device, Platform};
    use std::sync::Arc;
    struct Host;
    impl Platform for Host {
        fn execute(&self, _: &str, _: &serde_json::Value) -> Result<serde_json::Value, String> {
            Ok(json!({}))
        }
    }
    #[test]
    fn modal_tab_repeat_and_escape_use_the_shared_decision_actions() {
        i_slint_backend_testing::init_no_event_loop();
        let ui = MainWindow::new().unwrap();
        let path = std::env::temp_dir().join(format!(
            "rhremote-modal-key-{}.json",
            rhremote_core::random_token().unwrap()
        ));
        let c = Controller::new("test", path.clone(), Arc::new(Host)).unwrap();
        c.replace_devices(vec![Device {
            uuid: "host".into(),
            name: "winserver".into(),
            address: "192.168.0.104".into(),
            addresses: vec![],
            display_name: String::new(),
            online: true,
            paired: true,
            is_self: false,
        }]);
        c.dispatch("device.settings.open", json!({"uuid":"host"}));
        c.dispatch("settings.input", json!({"key":"fps","value":"24"}));
        c.dispatch("devices.open", json!({}));
        ui.set_navigation_prompt(true);
        assert_eq!(modal(&ui, &c, "Tab", true, false, false), Some(None));
        assert_eq!(ui.get_navigation_choice(), 1);
        modal(&ui, &c, "Tab", true, false, true);
        assert_eq!(ui.get_navigation_choice(), 0);
        modal(&ui, &c, "Enter", true, true, false);
        assert!(c.state()["navigationPrompt"].is_object());
        assert_eq!(
            modal(&ui, &c, "Escape", true, false, false),
            Some(Some("navigation.resolve"))
        );
        assert!(c.state()["navigationPrompt"].is_null());
        assert_eq!(c.state()["settingsInputs"]["fps"], "24");
        let _ = std::fs::remove_file(path);
    }
}
