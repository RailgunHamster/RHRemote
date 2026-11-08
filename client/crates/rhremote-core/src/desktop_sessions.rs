//! The browser owns configuration; each desktop session owns only its transport and controls.
use crate::{Controller, Device};
use serde_json::{Value, json};

pub(crate) fn session_action(id: &str) -> bool {
    id.starts_with("stream.")
        || id.starts_with("view.")
        || matches!(
            id,
            "device.connect"
                | "connection.retry"
                | "window.close"
                | "window.minimize"
                | "window.maximize"
                | "window.activate"
                | "statistics.state"
                | "network.state"
                | "settings.catalog"
                | "input.gesture"
        )
}
impl Controller {
    pub(crate) fn desktop_installation_hint(&self) -> Option<&'static str> {
        self.platform
            .sessions_state()
            .filter(|s| s["busy"] == true)
            .map(|_| "请先关闭所有远程窗口")
    }
    pub(crate) fn detached_session_block(&self, id: &str) -> Option<String> {
        if !matches!(id, "updates.primary" | "updates.install" | "proxy.save") {
            return None;
        }
        if self
            .platform
            .sessions_state()
            .is_none_or(|s| s["busy"] != true)
        {
            return None;
        }
        if id == "proxy.save" {
            return Some("请先关闭所有远程窗口再修改代理".into());
        }
        if id == "updates.install"
            || crate::update_primary_action(&self.platform.update_state().unwrap_or_default())
                == "updates.install"
        {
            Some("请先关闭所有远程窗口再安装更新".into())
        } else {
            None
        }
    }
    /// Only the authenticated parent-generated launch envelope can seed a session window.
    /// Ordinary API connect requests still pass the usual pairing/identity/window checks.
    pub fn start_desktop_session(&self, device: Device, window: Option<Value>) -> Value {
        if !self.platform.session_only() {
            return json!({"ok":false,"error":"not a desktop session window"});
        }
        let uuid = device.uuid.clone();
        self.replace_devices(vec![device]);
        let mut args = json!({"uuid":uuid});
        if let Some(window) = window {
            let mut s = self.state.lock().unwrap();
            s.window_picker.uuid = uuid;
            s.window_picker.rows = vec![window.clone()];
            args["window"] = window;
        }
        self.dispatch("device.connect", args)
    }
    pub(crate) fn apply_session_preferences(&self, args: &Value) -> Result<Value, String> {
        self.platform
            .execute("session.preferences.validate", args)?;
        let changes = args["changes"].as_object().ok_or("changes required")?;
        if changes.is_empty()
            || changes.len() > 4
            || changes.iter().any(|(key, value)| match key.as_str() {
                "hideKeyboardButton" | "simpleKeyboard" => !value.is_boolean(),
                "pinnedShortcuts" => value.as_array().is_none_or(|items| {
                    items.len() > 64
                        || items.iter().any(|item| {
                            item.as_str().is_none_or(|id| {
                                !crate::shortcuts()
                                    .as_array()
                                    .unwrap()
                                    .iter()
                                    .any(|s| s["id"].as_str() == Some(id))
                            })
                        })
                }),
                "buttonPositions" => serde_json::from_value::<
                    std::collections::BTreeMap<String, crate::ButtonPosition>,
                >(value.clone())
                .is_err_and(|_| true)
                    || value.as_object().is_none_or(|map| {
                        map.len() > 128
                            || map.iter().any(|(id, pos)| {
                                id.is_empty()
                                    || id.len() > 64
                                    || !id.bytes().all(|b| {
                                        b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b':')
                                    })
                                    || serde_json::from_value::<crate::ButtonPosition>(pos.clone())
                                        .is_ok_and(|p| !p.valid())
                            })
                    }),
                _ => true,
            })
        {
            return Err("无效的会话偏好设置".into());
        }
        let mut s = self.state.lock().unwrap();
        let mut next = s.saved.clone();
        if let Some(value) = changes.get("hideKeyboardButton") {
            next.hide_keyboard_button = value.as_bool().unwrap();
        }
        if let Some(value) = changes.get("simpleKeyboard") {
            next.simple_keyboard = value.as_bool().unwrap();
        }
        if let Some(value) = changes.get("pinnedShortcuts") {
            next.pinned_shortcuts = serde_json::from_value(value.clone()).map_err(|e| e.to_string())?;
        }
        if let Some(value) = changes.get("buttonPositions") {
            next.button_positions = serde_json::from_value(value.clone()).map_err(|e| e.to_string())?;
        }
        self.persist(&next)?;
        s.saved = next;
        s.revision += 1;
        Ok(json!({"saved":true}))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Platform, random_token};
    use std::sync::{Arc, Mutex};
    struct Host {
        session: bool,
        calls: Mutex<Vec<(String, Value)>>,
    }
    impl Platform for Host {
        fn separate_sessions(&self) -> bool {
            !self.session
        }
        fn session_only(&self) -> bool {
            self.session
        }
        fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
            self.calls.lock().unwrap().push((id.into(), args.clone()));
            Ok(json!({"accepted":true,"cleanupPending":false}))
        }
    }
    fn fixture(session: bool) -> (Arc<Controller>, Arc<Host>) {
        let host = Arc::new(Host {
            session,
            calls: Mutex::new(vec![]),
        });
        let c = Controller::new(
            "windows",
            std::env::temp_dir().join(format!("rhremote-multi-{}.json", random_token().unwrap())),
            host.clone(),
        )
        .unwrap();
        c.replace_devices(serde_json::from_value(json!([
            {"uuid":"a","name":"A","address":"192.0.2.1","online":true,"paired":true},
            {"uuid":"b","name":"B","address":"192.0.2.2","online":true,"paired":true},
            {"uuid":"self","name":"Self","address":"192.0.2.3","online":true,"paired":true,"isSelf":true},
            {"uuid":"unpaired","name":"Unpaired","address":"192.0.2.4","online":true,"paired":false}
        ])).unwrap());
        (c, host)
    }
    #[test]
    fn browser_delegates_two_validated_connections_without_taking_a_transport() {
        let (c, p) = fixture(false);
        for uuid in ["a", "b"] {
            assert_eq!(
                c.dispatch("device.connect", json!({"uuid":uuid}))["ok"],
                true
            );
        }
        let state = c.state();
        assert_eq!(state["page"], "devices");
        assert_eq!(state["streaming"], false);
        assert_eq!(state["connecting"], false);
        let calls = p.calls.lock().unwrap();
        let opens: Vec<_> = calls
            .iter()
            .filter(|(id, _)| id == "session.open")
            .collect();
        assert_eq!(opens.len(), 2);
        assert_eq!(opens[0].1["device"]["uuid"], "a");
        assert_eq!(opens[1].1["device"]["uuid"], "b");
        assert!(opens[0].1["saved"]["clientId"].is_string());
        assert!(!calls.iter().any(|(id, _)| id == "device.connect"));
        let _ = std::fs::remove_file(&c.storage);
    }
    #[test]
    fn delegation_preserves_pairing_identity_self_and_draft_gates() {
        let (c, p) = fixture(false);
        for uuid in ["self", "unpaired", "unknown"] {
            assert_eq!(
                c.dispatch("device.connect", json!({"uuid":uuid}))["ok"],
                false
            );
        }
        c.dispatch("device.settings.open", json!({"uuid":"a"}));
        c.dispatch("settings.set", json!({"key":"fps","value":40}));
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"b"}))["result"]["pendingNavigation"],
            true
        );
        assert!(
            !p.calls
                .lock()
                .unwrap()
                .iter()
                .any(|(id, _)| id == "session.open")
        );
        c.dispatch("navigation.resolve", json!({"choice":"discard"}));
        assert_eq!(
            p.calls
                .lock()
                .unwrap()
                .iter()
                .filter(|(id, _)| id == "session.open")
                .count(),
            1
        );
        let _ = std::fs::remove_file(&c.storage);
    }
    #[test]
    fn session_preferences_patch_does_not_overwrite_browser_edits() {
        let (c, _) = fixture(false);
        c.dispatch("device.settings.open", json!({"uuid":"b"}));
        c.dispatch("settings.set", json!({"key":"fps","value":42}));
        c.dispatch("settings.save", json!({}));
        let before = c.state()["saved"].clone();
        assert_eq!(
            c.dispatch(
                "session.preferences",
                json!({"sessionId":"owned","changes":{"simpleKeyboard":true}})
            )["ok"],
            true
        );
        assert_eq!(c.profile("b").fps, 42);
        assert_eq!(c.state()["saved"]["devices"], before["devices"]);
        assert_eq!(c.state()["saved"]["simpleKeyboard"], true);
        assert_eq!(
            c.dispatch("session.preferences", json!({"changes":{"profiles":{}}}))["ok"],
            false
        );
        let _ = std::fs::remove_file(&c.storage);
    }
    #[test]
    fn session_keyboard_preferences_do_not_depend_on_the_browser_draft_dialog() {
        let (c, _)=fixture(false);
        c.dispatch("device.settings.open",json!({"uuid":"a"}));
        c.dispatch("settings.set",json!({"key":"fps","value":41}));
        c.dispatch("device.select",json!({"uuid":"b"}));
        let before=c.state();assert!(before["navigationPrompt"].is_object());
        assert_eq!(c.dispatch("session.preferences",json!({"sessionId":"owned","changes":{"simpleKeyboard":true}}))["ok"],true);
        let after=c.state();assert_eq!(after["navigationPrompt"],before["navigationPrompt"]);assert_eq!(after["draft"],before["draft"]);assert_eq!(after["saved"]["simpleKeyboard"],true);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]
    fn returning_from_a_session_closes_only_that_session_window() {
        let (c, p) = fixture(true);
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"a"}))["ok"],
            true
        );
        assert_eq!(
            c.dispatch("device.settings.open", json!({"uuid":"b"}))["ok"],
            false
        );
        assert_eq!(c.dispatch("devices.open", json!({}))["ok"], true);
        assert_eq!(c.take_window_action(), "window.close");
        assert!(
            p.calls
                .lock()
                .unwrap()
                .iter()
                .any(|(id, _)| id == "session.return")
        );
        let _ = std::fs::remove_file(&c.storage);
    }
    #[test]
    fn an_admitted_window_open_blocks_installation_until_registration_completes() {
        use std::sync::mpsc;
        use std::time::Duration;
        struct Pending {
            entered: mpsc::Sender<()>,
            release: Mutex<mpsc::Receiver<()>>,
        }
        impl Platform for Pending {
            fn separate_sessions(&self) -> bool {
                true
            }
            fn execute(&self, id: &str, _: &Value) -> Result<Value, String> {
                if id == "session.open" {
                    self.entered.send(()).unwrap();
                    self.release
                        .lock()
                        .unwrap()
                        .recv_timeout(Duration::from_secs(3))
                        .unwrap();
                }
                assert_ne!(
                    id, "updates.install",
                    "Installation raced an admitted session launch"
                );
                Ok(if id == "updates.state" {
                    json!({"pendingRestart":true,"managed":true})
                } else {
                    json!({"accepted":true})
                })
            }
        }
        let (entered, waiting) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let c = Controller::new(
            "windows",
            std::env::temp_dir().join(format!(
                "rhremote-launch-admission-{}.json",
                random_token().unwrap()
            )),
            Arc::new(Pending {
                entered,
                release: Mutex::new(released),
            }),
        )
        .unwrap();
        c.replace_devices(vec![Device {
            uuid: "host".into(),
            name: "Host".into(),
            display_name: String::new(),
            address: "192.0.2.1".into(),
            addresses: vec![],
            online: true,
            paired: true,
            is_self: false,
        }]);
        let worker = c.clone();
        let pending =
            std::thread::spawn(move || worker.dispatch("device.connect", json!({"uuid":"host"})));
        waiting.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_eq!(c.dispatch("updates.install", json!({}))["ok"], false);
        release.send(()).unwrap();
        assert_eq!(pending.join().unwrap()["ok"], true);
        assert_eq!(c.state.lock().unwrap().desktop_open_requests, 0);
        let _ = std::fs::remove_file(&c.storage);
    }
}
