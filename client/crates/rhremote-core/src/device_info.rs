//! Device identity is shared host metadata, separate from client connection profiles.
use crate::{Controller, State, sync_model};
use serde::Serialize;
use serde_json::{Value, json};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InfoDraft {
    pub uuid: String,
    pub display_name: String,
    pub error: String,
    #[serde(skip)]
    original: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Device, Platform, random_token};
    use std::sync::Arc;

    struct Host;
    impl Platform for Host {
        fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
            Ok(if id == "devices.restore" {
                json!({"devices":args["devices"]})
            } else {
                json!({})
            })
        }
    }
    struct Fixture(Arc<Controller>);
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0.storage);
        }
    }
    fn fixture() -> Fixture {
        let c = Controller::new(
            "test",
            std::env::temp_dir().join(format!("rhremote-info-{}.json", random_token().unwrap())),
            Arc::new(Host),
        )
        .unwrap();
        let rows: Vec<Device> = serde_json::from_value(json!([
            {"uuid":"host","name":"winserver","address":"192.168.0.104","addresses":["100.73.139.109"],"online":true,"paired":true},
            {"uuid":"self","name":"Local PC","address":"192.168.0.100","online":true,"paired":true,"isSelf":true}
        ])).unwrap();
        c.replace_devices(rows);
        c.dispatch("device.settings.open", json!({"uuid":"host"}));
        c.dispatch(
            "settings.input",
            json!({"key":"displayName","value":"工作机"}),
        );
        c.dispatch("settings.input", json!({"key":"fps","value":"24"}));
        assert_eq!(c.dispatch("settings.save", json!({}))["ok"], true);
        c.dispatch("devices.filter", json!({"query":"winserver"}));
        assert_eq!(
            c.dispatch("device.info.open", json!({"uuid":"host"}))["ok"],
            true
        );
        Fixture(c)
    }
    fn input(c: &Controller, name: &str) {
        assert_eq!(
            c.dispatch("device.info.input", json!({"displayName":name}))["ok"],
            true
        );
    }

    #[test]
    fn completed_name_is_shared_metadata_and_does_not_change_profiles_or_connection_facts() {
        let f = fixture();
        let c = &f.0;
        let before = c.state()["saved"].clone();
        input(c, "  渲染机  ");
        assert_eq!(c.state()["infoDirty"], true);
        assert_eq!(c.dispatch("device.info.save", json!({}))["ok"], true);
        let s = c.state();
        let mut expected = before;
        expected["devices"][0]["displayName"] = json!("渲染机");
        assert_eq!(s["saved"], expected);
        assert_eq!(s["devices"][0]["name"], "winserver");
        assert_eq!(s["devices"][0]["paired"], true);
        assert_eq!(s["selected"], "host");
        assert_eq!(s["deviceQuery"], "winserver");
        let restarted = Controller::new("test", c.storage.clone(), Arc::new(Host)).unwrap();
        assert_eq!(
            restarted.state()["saved"]["devices"][0]["displayName"],
            "渲染机"
        );
    }
    #[test]
    fn reset_and_cancel_do_not_write_until_the_edit_is_completed() {
        let f = fixture();
        let c = &f.0;
        let before = std::fs::read(&c.storage).unwrap();
        c.dispatch("device.info.reset", json!({}));
        assert_eq!(c.state()["infoDraft"]["displayName"], "");
        assert_eq!(std::fs::read(&c.storage).unwrap(), before);
        c.dispatch("device.info.cancel", json!({}));
        assert_eq!(std::fs::read(&c.storage).unwrap(), before);
        assert_eq!(c.state()["devices"][0]["displayName"], "工作机");
        c.dispatch("device.info.open", json!({"uuid":"host"}));
        c.dispatch("device.info.reset", json!({}));
        c.dispatch("device.info.save", json!({}));
        assert_eq!(c.state()["saved"]["devices"][0]["displayName"], "");
        assert_eq!(c.state()["devices"][0]["name"], "winserver");
    }
    #[test]
    fn validation_failure_retains_raw_input_and_the_existing_name() {
        let f = fixture();
        let c = &f.0;
        let before = std::fs::read(&c.storage).unwrap();
        for name in ["bad\nname".to_string(), "名".repeat(86)] {
            input(c, &name);
            assert_eq!(c.dispatch("device.info.save", json!({}))["ok"], false);
            let s = c.state();
            assert_eq!(s["page"], "device-info");
            assert_eq!(s["infoDraft"]["displayName"], name);
            assert!(!s["infoDraft"]["error"].as_str().unwrap().is_empty());
            assert_eq!(std::fs::read(&c.storage).unwrap(), before);
        }
    }
    #[test]
    fn disk_failure_preserves_input_and_does_not_publish_a_new_name() {
        let f = fixture();
        let c = &f.0;
        let before = std::fs::read(&c.storage).unwrap();
        let blocked = c.storage.with_extension("json.tmp");
        std::fs::create_dir(&blocked).unwrap();
        input(c, "新名称");
        assert_eq!(c.dispatch("device.info.save", json!({}))["ok"], false);
        assert_eq!(c.state()["infoDraft"]["displayName"], "新名称");
        assert_eq!(c.state()["devices"][0]["displayName"], "工作机");
        assert_eq!(std::fs::read(&c.storage).unwrap(), before);
        std::fs::remove_dir(blocked).unwrap();
    }
    #[test]
    fn leaving_can_stay_discard_or_complete_without_losing_context() {
        for choice in ["stay", "discard", "save"] {
            let f = fixture();
            let c = &f.0;
            input(c, "新名称");
            c.dispatch("app.settings.tab", json!({"tab":"about"}));
            assert_eq!(c.state()["navigationPrompt"]["kind"], "unsaved-info");
            assert_eq!(
                c.dispatch("navigation.resolve", json!({"choice":choice}))["ok"],
                true
            );
            assert_eq!(
                c.state()["devices"][0]["displayName"],
                if choice == "save" {
                    "新名称"
                } else {
                    "工作机"
                }
            );
            assert_eq!(
                c.state()["page"],
                if choice == "stay" {
                    "device-info"
                } else {
                    "global-settings"
                }
            );
            assert_eq!(c.state()["selected"], "host");
            assert_eq!(c.state()["deviceQuery"], "winserver");
        }
    }
    #[test]
    fn invalid_save_before_navigation_keeps_the_editor_and_clean_completion_does_not_write() {
        let f = fixture();
        let c = &f.0;
        let before = std::fs::read(&c.storage).unwrap();
        input(c, "bad\nname");
        c.dispatch("navigation.back", json!({}));
        assert_eq!(
            c.dispatch("navigation.resolve", json!({"choice":"save"}))["ok"],
            false
        );
        assert!(c.state()["navigationPrompt"].is_null());
        assert_eq!(c.state()["page"], "device-info");
        input(c, " 工作机 ");
        assert_eq!(c.state()["infoDirty"], false);
        c.dispatch("device.info.save", json!({}));
        assert_eq!(std::fs::read(&c.storage).unwrap(), before);
    }
    #[test]
    fn self_name_is_editable_and_reopening_the_same_editor_keeps_input() {
        let f = fixture();
        let c = &f.0;
        c.dispatch("device.info.cancel", json!({}));
        c.dispatch("device.info.open", json!({"uuid":"self"}));
        input(c, "我的电脑");
        c.dispatch("device.info.open", json!({"uuid":"self"}));
        assert_eq!(c.state()["infoDraft"]["displayName"], "我的电脑");
        c.dispatch("device.info.save", json!({}));
        assert_eq!(c.state()["devices"][1]["displayName"], "我的电脑");
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"self"}))["ok"],
            false
        );
    }
}

#[cfg(test)]
mod extra_tests {
    use super::*;
    #[test]
    fn scheduled_sync_never_leaves_an_editor_or_opens_a_navigation_decision() {
        struct Host;
        impl crate::Platform for Host {
            fn execute(&self, id: &str, _: &Value) -> Result<Value, String> {
                assert_ne!(
                    id, "sync.run",
                    "An automatic sync must not reach the network while editing"
                );
                Ok(json!({}))
            }
        }
        let path = std::env::temp_dir().join(format!(
            "rhremote-info-auto-{}.json",
            crate::random_token().unwrap()
        ));
        let c = Controller::new("test", path.clone(), std::sync::Arc::new(Host)).unwrap();
        {
            let mut s = c.state.lock().unwrap();
            s.sync = json!({"automatic":true,"intervalMinutes":5,"lastSyncMs":0});
            s.page = "device-info".into();
            s.info_draft = Some(InfoDraft {
                uuid: "host".into(),
                display_name: "新名称".into(),
                original: String::new(),
                error: String::new(),
            });
        }
        assert!(!c.automatic_sync_due(300_001, 0));
        assert_eq!(
            c.dispatch("sync.run", json!({"automatic":true}))["result"]["skipped"],
            true
        );
        assert_eq!(
            c.dispatch_global("sync.run", json!({"automatic":true}))
                .unwrap()["skipped"],
            true
        );
        assert!(c.state()["navigationPrompt"].is_null());
        assert_eq!(c.state()["infoDraft"]["displayName"], "新名称");
        c.dispatch("sync.run", json!({}));
        assert_eq!(c.state()["navigationPrompt"]["kind"], "unsaved-info");
        c.dispatch("navigation.resolve", json!({"choice":"stay"}));
        {
            let mut s = c.state.lock().unwrap();
            s.info_draft = None;
            s.page = "add".into();
        }
        assert!(!c.automatic_sync_due(300_001, 0));
        c.state.lock().unwrap().page = "devices".into();
        assert!(c.automatic_sync_due(300_001, 0));
        let _ = std::fs::remove_file(path);
    }
    #[test]
    fn cancellation_without_a_device_info_editor_is_rejected() {
        struct Host;
        impl crate::Platform for Host {
            fn execute(&self, _: &str, _: &Value) -> Result<Value, String> {
                Ok(json!({}))
            }
        }
        let path = std::env::temp_dir().join(format!(
            "rhremote-info-cancel-{}.json",
            crate::random_token().unwrap()
        ));
        let c = Controller::new("test", path.clone(), std::sync::Arc::new(Host)).unwrap();
        assert_eq!(c.dispatch("device.info.cancel", json!({}))["ok"], false);
        assert_eq!(c.state()["page"], "devices");
        let _ = std::fs::remove_file(path);
    }
}

pub(crate) fn dirty(s: &State) -> bool {
    s.info_draft
        .as_ref()
        .is_some_and(|d| d.display_name.trim() != d.original)
}

impl Controller {
    pub(crate) fn dispatch_info(&self, id: &str, args: Value) -> Result<Value, String> {
        let mut s = self.state.lock().unwrap();
        if s.sync_busy || s.installing_update {
            return Err("请先完成当前操作".into());
        }
        match id {
            "device.info.open" => {
                if s.streaming || s.connecting || s.page == "pair" {
                    return Err("请先完成或取消当前连接".into());
                }
                let uuid = args["uuid"].as_str().ok_or("uuid required")?;
                if s.info_draft.as_ref().is_some_and(|d| d.uuid == uuid) {
                    return Ok(json!({"reused":true}));
                }
                let host = s
                    .devices
                    .iter()
                    .find(|d| d.uuid == uuid)
                    .ok_or("unknown device")?;
                let alias = s
                    .saved
                    .devices
                    .iter()
                    .find(|d| d.uuid == uuid)
                    .map(|d| &d.display_name)
                    .unwrap_or(&host.display_name)
                    .clone();
                s.info_draft = Some(InfoDraft {
                    uuid: uuid.into(),
                    display_name: alias.clone(),
                    original: alias,
                    error: String::new(),
                });
                s.selected = uuid.into();
                s.page = "device-info".into();
                s.status.clear();
            }
            "device.info.input" => {
                let d = s.info_draft.as_mut().ok_or("请先打开设备资料")?;
                d.display_name = args["displayName"]
                    .as_str()
                    .ok_or("displayName must be a string")?
                    .into();
                d.error.clear();
            }
            "device.info.reset" => {
                let d = s.info_draft.as_mut().ok_or("请先打开设备资料")?;
                d.display_name.clear();
                d.error.clear();
            }
            "device.info.cancel" => {
                if s.info_draft.is_none() {
                    return Err("请先打开设备资料".into());
                }
                s.info_draft = None;
                s.page = "devices".into();
                s.status.clear();
            }
            "device.info.save" => {
                let draft = s.info_draft.as_ref().ok_or("请先打开设备资料")?.clone();
                let name = sync_model::display_name(&draft.display_name)?;
                if dirty(&s) {
                    let mut next = s.saved.clone();
                    next.devices = sync_model::from_state(&s).devices;
                    next.devices
                        .iter_mut()
                        .find(|d| d.uuid == draft.uuid)
                        .ok_or("设备已不存在")?
                        .display_name = name.clone();
                    self.persist(&next)?;
                    s.saved = next;
                    if let Some(device) = s.devices.iter_mut().find(|d| d.uuid == draft.uuid) {
                        device.display_name = name;
                    }
                    s.status = "设备名称已保存".into();
                } else {
                    s.status.clear();
                }
                s.info_draft = None;
                s.page = "devices".into();
            }
            _ => return Err("unknown device info action".into()),
        }
        s.revision += 1;
        Ok(json!({"accepted":true}))
    }
}
