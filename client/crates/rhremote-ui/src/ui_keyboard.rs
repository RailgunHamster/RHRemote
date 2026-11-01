use crate::MainWindow;
#[cfg(test)]
use rhremote_core::Controller;
#[cfg(test)]
use serde_json::json;
use slint::ComponentHandle;

pub(crate) fn native_key_name(key: &slint::winit_030::winit::keyboard::Key) -> &'static str {
    use slint::winit_030::winit::keyboard::{Key, NamedKey};
    match key {
        Key::Named(NamedKey::Escape) => "Escape",
        Key::Named(NamedKey::Tab) => "Tab",
        Key::Named(NamedKey::Enter) => "Enter",
        Key::Named(NamedKey::ArrowUp) => "Up",
        Key::Named(NamedKey::ArrowDown) => "Down",
        Key::Named(NamedKey::Space) => "Space",
        Key::Character(value) if value.as_str() == " " => "Space",
        _ => "Other",
    }
}

/// The OS event hook and the application test API share the same modal rules.
pub(crate) fn modal(
    ui: &MainWindow,
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
                    ui.global::<crate::Commands>().invoke_trigger("sync.clear.resolve".into(), "cancel".into());
                    action = Some("sync.clear.resolve");
                }
                "Tab" => ui.set_sync_clear_choice(1 - ui.get_sync_clear_choice()),
                "Enter" | "Space" => {
                    ui.global::<crate::Commands>().invoke_trigger(
                        "sync.clear.resolve".into(),
                        if ui.get_sync_clear_choice()==0{"cancel"}else{"remove"}.into(),
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
                    ui.global::<crate::Commands>().invoke_trigger("navigation.resolve".into(), "stay".into());
                    action = Some("navigation.resolve");
                }
                "Tab" => ui.set_navigation_choice(
                    (ui.get_navigation_choice() + if shift { 2 } else { 1 }) % 3,
                ),
                "Enter" | "Space" => {
                    let choice = ["stay", "discard", "save"][ui.get_navigation_choice() as usize];
                    ui.global::<crate::Commands>().invoke_trigger("navigation.resolve".into(), choice.into());
                    action = Some("navigation.resolve");
                }
                _ => {}
            }
        }
        return Some(action);
    }
    if ui.get_window_picker_open(){
        use slint::Model;
        let rows=ui.get_window_picker_rows();let mut choices=vec![0];
        if !ui.get_window_picker_busy(){for i in 0..rows.row_count(){if rows.row_data(i).is_some_and(|row|row.available){choices.push(i as i32+1);}}choices.push(rows.row_count() as i32+1);}
        let selected=ui.get_window_picker_choice();let current=choices.iter().position(|value|*value==selected).unwrap_or(0);let mut action=None;
        if pressed&&!repeat{match key{
            "Escape"=>{ui.global::<crate::Commands>().invoke_trigger("device.windows.close".into(),"".into());action=Some("device.windows.close");},
            "Tab"|"Down"|"Up"=>{let backwards=key=="Up"||key=="Tab"&&shift;let next=(current+if backwards{choices.len()-1}else{1})%choices.len();ui.set_window_picker_choice(choices[next]);},
            "Enter"|"Space"=>{let selected=choices[current];let(id,arg)=if selected==0{("device.windows.close",String::new())}else if selected==rows.row_count() as i32+1{("device.windows.open",ui.get_window_picker_uuid().to_string())}else{let row=rows.row_data((selected-1) as usize).unwrap();("device.window.connect",format!("{}|{}",ui.get_window_picker_uuid(),row.id))};ui.global::<crate::Commands>().invoke_trigger(id.into(),arg.into());action=Some(id);},
            _=>{}
        }}
        return Some(action);
    }
    None
}

pub(crate) fn send(
    ui: &MainWindow,
    key: &str,
    shift: bool,
    repeat: bool,
) -> Option<&'static str> {
    if let Some(action) = modal(ui, key, true, repeat, shift) {
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
        "PageUp" => Key::PageUp.into(),
        "PageDown" => Key::PageDown.into(),
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
    #[test]
    fn native_space_spellings_share_the_modal_key_without_matching_text_or_other_keys() {
        use slint::winit_030::winit::keyboard::{Key, NamedKey};
        for key in [Key::Named(NamedKey::Space), Key::Character(" ".into())] {
            assert_eq!(native_key_name(&key), "Space");
        }
        for key in [Key::Character("a".into()), Key::Character("Space".into()), Key::Named(NamedKey::ArrowRight)] {
            assert_eq!(native_key_name(&key), "Other");
        }
        for (key, name) in [(NamedKey::Tab, "Tab"), (NamedKey::Enter, "Enter"), (NamedKey::Escape, "Escape")] {
            assert_eq!(native_key_name(&Key::Named(key)), name);
        }
    }
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
        let queued=std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));let commands=queued.clone();let weak=ui.as_weak();
        ui.global::<crate::Commands>().on_trigger(move|id,arg|{let ui=weak.upgrade().unwrap();commands.borrow_mut().push((id.to_string(),arg.to_string(),crate::request_owner(&ui,id.as_str())));});
        ui.set_navigation_prompt(true);
        ui.set_navigation_request_id(c.state()["navigationPrompt"]["requestId"].as_str().unwrap().into());
        assert_eq!(modal(&ui, "Tab", true, false, false), Some(None));
        assert_eq!(ui.get_navigation_choice(), 1);
        modal(&ui, "Tab", true, false, true);
        assert_eq!(ui.get_navigation_choice(), 0);
        modal(&ui, "Enter", true, true, false);
        assert!(c.state()["navigationPrompt"].is_object());
        assert_eq!(
            modal(&ui, "Escape", true, false, false),
            Some(Some("navigation.resolve"))
        );
        assert!(c.state()["navigationPrompt"].is_object());
        assert_eq!(queued.borrow().len(),1);let(id,arg,owner)=queued.borrow_mut().remove(0);assert_eq!(c.dispatch(&id,crate::queued_args(&id,&arg,&c.state(),owner))["ok"],true);
        assert!(c.state()["navigationPrompt"].is_null());
        assert_eq!(c.state()["settingsInputs"]["fps"], "24");
        let _ = std::fs::remove_file(path);
    }
}
