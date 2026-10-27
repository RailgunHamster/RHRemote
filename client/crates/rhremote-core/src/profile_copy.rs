use crate::{Controller, Profile, State};
use serde::Serialize;
use serde_json::{Value, json};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Preview {
    pub client_id: String,
    pub source_name: String,
    pub target_uuid: String,
    pub target_name: String,
    pub profile: Profile,
    pub fields: Vec<Field>,
}
#[derive(Clone, Debug, Serialize)]
pub struct Field {
    pub label: String,
    pub value: String,
}
fn fields(profile: &Profile, platform: &str) -> Vec<Field> {
    let values = serde_json::to_value(profile).unwrap();
    let mut fields = Vec::new();
    for meta in crate::settings_catalog().as_array().unwrap() {
        let key = meta["key"].as_str().unwrap();
        if key == "swapButtons"
            || meta["platforms"]
                .as_array()
                .is_some_and(|items| !items.iter().any(|item| item == platform))
        {
            continue;
        }
        let value = &values[key];
        let text = if let Some(flag) = value.as_bool() {
            if flag { "开启" } else { "关闭" }.into()
        } else if let Some(option) = meta["options"]
            .as_array()
            .and_then(|items| items.iter().find(|option| option[0] == *value))
        {
            option[1].as_str().unwrap_or("").into()
        } else {
            value
                .as_str()
                .map(str::to_owned)
                .unwrap_or_else(|| value.to_string())
        };
        let tab = match meta["tab"].as_str().unwrap_or("") {
            "video" => "画面",
            "audio" => "声音",
            "input" => "输入",
            "network" => "网络",
            _ => "高级",
        };
        fields.push(Field {
            label: format!("{} · {}", tab, meta["label"].as_str().unwrap()),
            value: text,
        });
    }
    fields.push(Field {
        label: "网络 · 连接顺序".into(),
        value: profile
            .route_order
            .iter()
            .map(|key| {
                crate::network::ROUTES
                    .iter()
                    .find(|(id, _)| id == key)
                    .map(|(_, label)| *label)
                    .unwrap_or(key)
            })
            .collect::<Vec<_>>()
            .join(" → "),
    });
    fields.push(Field {
        label: "网络 · 中继服务器".into(),
        value: profile.relay_url.clone(),
    });
    fields
}
fn source(s: &State, client: &str) -> Result<Preview, String> {
    if s.draft.is_none() {
        return Err("请先打开连接设置".into());
    }
    if client == s.saved.client_id {
        return Err("请选择其他客户端".into());
    }
    let profile = s
        .saved
        .client_profiles
        .get(client)
        .and_then(|profiles| profiles.get(&s.selected))
        .ok_or("该客户端没有此设备的配置")?
        .to_profile();
    profile.validate()?;
    let name = s
        .saved
        .clients
        .get(client)
        .map(|info| info.name.as_str())
        .filter(|name| !name.is_empty())
        .unwrap_or(client);
    let suffix = client
        .chars()
        .rev()
        .take(4)
        .collect::<String>()
        .chars()
        .rev()
        .collect::<String>();
    let target = s
        .devices
        .iter()
        .find(|host| host.uuid == s.selected)
        .map(|host| {
            if host.display_name.is_empty() {
                host.name.clone()
            } else {
                host.display_name.clone()
            }
        })
        .unwrap_or_else(|| s.selected.clone());
    let fields = fields(&profile, &s.platform);
    Ok(Preview {
        client_id: client.into(),
        source_name: format!("{name} · {suffix}"),
        target_uuid: s.selected.clone(),
        target_name: target,
        profile,
        fields,
    })
}
pub(crate) fn apply(s: &mut State, preview: Preview) -> Result<(), String> {
    let current = s.draft.as_ref().ok_or("请先打开连接设置")?;
    if preview.target_uuid != s.selected {
        return Err("复制目标已改变，请重新选择配置".into());
    }
    let mut profile = preview.profile;
    profile.validate()?;
    profile.swap_buttons = current.swap_buttons;
    s.settings_inputs.clear();
    s.settings_error.clear();
    s.settings_error_field.clear();
    s.draft_relay = profile.relay_url.clone();
    s.draft = Some(profile);
    s.profile_copy_notice = if crate::interaction::dirty(s) {
        format!("已从 {} 复制，保存后生效", preview.source_name)
    } else {
        "与当前配置相同".into()
    };
    s.profile_sources_expanded = false;
    s.profile_copy = None;
    s.profile_copy_error.clear();
    s.page = "settings".into();
    Ok(())
}
pub(crate) fn copy_from(s: &mut State, client: &str) -> Result<(), String> {
    let preview = source(s, client)?;
    apply(s, preview)
}

impl Controller {
    pub(crate) fn profile_copy_gate(&self, id: &str) -> Option<Result<Value, String>> {
        let s = self.state.lock().unwrap();
        if s.page == "profile-copy"
            && matches!(
                id,
                "settings.input"
                    | "settings.set"
                    | "settings.step"
                    | "settings.network.edit"
                    | "settings.route.move"
                    | "settings.route.toggle"
                    | "settings.sources.toggle"
                    | "settings.tab"
                    | "settings.save"
                    | "settings.cancel"
                    | "settings.copy_from"
            )
        {
            return Some(Err("请先完成或取消配置预览".into()));
        }
        None
    }
    pub(crate) fn dispatch_profile_copy(&self, id: &str, args: Value) -> Result<Value, String> {
        let mut s = self.state.lock().unwrap();
        if s.sync_busy {
            return Err("同步进行中，请稍后复制".into());
        }
        match id {
            "settings.copy.preview" => {
                if !matches!(s.page.as_str(), "settings" | "profile-copy") {
                    return Err("请先打开连接设置".into());
                }
                let client = args["clientId"].as_str().ok_or("clientId required")?;
                let preview = source(&s, client)?;
                s.profile_copy = Some(preview);
                s.profile_copy_error.clear();
                s.page = "profile-copy".into();
            }
            "settings.copy.cancel" => {
                if s.page != "profile-copy" || s.profile_copy.is_none() || s.draft.is_none() {
                    return Err("没有配置预览".into());
                }
                s.profile_copy = None;
                s.profile_copy_error.clear();
                s.page = "settings".into();
            }
            "settings.copy.apply" => {
                if s.page != "profile-copy" {
                    return Err("请先预览要复制的配置".into());
                }
                let preview = s.profile_copy.clone().ok_or("没有配置预览")?;
                apply(&mut s, preview)?;
            }
            _ => return Err("unknown profile copy action".into()),
        }
        s.revision += 1;
        Ok(json!({"page":s.page}))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        Device, Platform,
        sync_model::{ClientInfo, ClientProfile},
    };
    use std::{collections::BTreeMap, fs, sync::Arc};
    struct Host;
    impl Platform for Host {
        fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
            Ok(match id {
                "devices.restore" => json!({"devices":args["devices"]}),
                _ => json!({}),
            })
        }
    }
    struct Fixture(Arc<Controller>);
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_file(&self.0.storage);
        }
    }
    fn fixture() -> Fixture {
        let c = Controller::new(
            "windows",
            std::env::temp_dir().join(format!(
                "rhremote-copy-{}.json",
                crate::random_token().unwrap()
            )),
            Arc::new(Host),
        )
        .unwrap();
        let devices:Vec<Device>=serde_json::from_value(json!([{"uuid":"host","name":"winserver","displayName":"工作机","address":"192.168.0.104","addresses":["100.73.139.109"],"online":true,"paired":true},{"uuid":"other","name":"claw","address":"192.168.0.113","online":true,"paired":true}])).unwrap();
        c.replace_devices(devices);
        {
            let mut s = c.state.lock().unwrap();
            s.saved.devices = s
                .devices
                .iter()
                .map(|d| crate::sync_model::SharedDevice {
                    uuid: d.uuid.clone(),
                    name: d.name.clone(),
                    address: d.address.clone(),
                    addresses: d.addresses.clone(),
                    display_name: d.display_name.clone(),
                    swap_buttons: false,
                })
                .collect();
            s.saved.devices[0].display_name = "工作机".into();
            s.devices[0].display_name = "工作机".into();
            let p = Profile {
                width: 1280,
                height: 720,
                bitrate_mbps: 42,
                codec: "H.264".into(),
                route_order: vec!["relay".into(), "lan".into()],
                relay_url: "https://relay.example:33449/".into(),
                ..Profile::default()
            };
            s.saved.client_profiles.insert(
                "pad-1234".into(),
                BTreeMap::from([("host".into(), ClientProfile::from_profile(&p))]),
            );
            s.saved.clients.insert(
                "pad-1234".into(),
                ClientInfo {
                    name: "OPPO 平板".into(),
                    platform: "android".into(),
                },
            );
            c.persist(&s.saved).unwrap();
        }
        assert_eq!(
            c.dispatch("device.settings.open", json!({"uuid":"host"}))["ok"],
            true
        );
        Fixture(c)
    }
    fn action(c: &Controller, id: &str, args: Value) {
        let r = c.dispatch(id, args);
        assert_eq!(r["ok"], true, "{r}");
    }
    fn preview(c: &Controller) {
        action(c, "settings.copy.preview", json!({"clientId":"pad-1234"}));
    }
    #[test]
    fn preview_back_preserves_invalid_raw_input_errors_and_endpoint_drafts_without_writing() {
        let f = fixture();
        let c = &f.0;
        let disk = fs::read(&c.storage).unwrap();
        action(c, "settings.input", json!({"key":"width","value":"bad"}));
        assert_eq!(c.dispatch("settings.save", json!({}))["ok"], false);
        action(
            c,
            "settings.network.edit",
            json!({"key":"address","value":"other.example"}),
        );
        action(c, "settings.input", json!({"key":"width","value":"bad"}));
        c.dispatch("settings.save", json!({}));
        let before = c.state();
        preview(c);
        let p = c.state();
        assert_eq!(p["page"], "profile-copy");
        assert_eq!(p["profileCopy"]["sourceName"], "OPPO 平板 · 1234");
        assert_eq!(p["profileCopy"]["targetName"], "工作机");
        assert!(p["profileCopy"]["fields"].as_array().unwrap().len() > 20);
        assert_eq!(p["draft"], before["draft"]);
        action(c, "navigation.back", json!({}));
        let after = c.state();
        for key in [
            "draft",
            "settingsInputs",
            "settingsError",
            "settingsErrorField",
            "draftAddress",
            "profileTab",
        ] {
            assert_eq!(after[key], before[key], "{key}");
        }
        assert_eq!(fs::read(&c.storage).unwrap(), disk);
    }
    #[test]
    fn apply_replaces_profile_only_and_clears_stale_validation_but_requires_save() {
        let f = fixture();
        let c = &f.0;
        let disk = fs::read(&c.storage).unwrap();
        let saved = c.state()["saved"].clone();
        action(c, "settings.input", json!({"key":"width","value":"bad"}));
        c.dispatch("settings.save", json!({}));
        action(c, "settings.set", json!({"key":"swapButtons","value":true}));
        action(
            c,
            "settings.network.edit",
            json!({"key":"address","value":"new.example"}),
        );
        action(
            c,
            "settings.input",
            json!({"key":"displayName","value":"新名称"}),
        );
        preview(c);
        // The visible snapshot is what the user accepts even if discovery refreshes metadata.
        {
            let mut s = c.state.lock().unwrap();
            s.saved
                .client_profiles
                .get_mut("pad-1234")
                .unwrap()
                .get_mut("host")
                .unwrap()
                .width = 2560;
        }
        action(c, "settings.copy.apply", json!({}));
        let state = c.state();
        assert_eq!(state["draft"]["width"], 1280);
        assert_eq!(state["draft"]["swapButtons"], true);
        assert_eq!(state["draftAddress"], "new.example");
        assert_eq!(state["draftDisplayName"], "新名称");
        assert_eq!(state["draftRelay"], "https://relay.example:33449/");
        assert_eq!(state["settingsInputs"], json!({}));
        assert_eq!(state["settingsErrorField"], "");
        assert_eq!(state["settingsError"], "");
        assert_eq!(fs::read(&c.storage).unwrap(), disk);
        action(c, "settings.save", json!({}));
        assert_eq!(c.profile("host").width, 1280);
        assert_eq!(
            c.state()["saved"]["devices"][0]["uuid"],
            saved["devices"][0]["uuid"]
        );
        assert_eq!(c.state()["saved"]["devices"][1], saved["devices"][1]);
        assert_eq!(c.state()["saved"]["devices"][0]["address"], "new.example");
        assert_eq!(c.state()["saved"]["devices"][0]["displayName"], "新名称");
    }
    #[test]
    fn applied_copy_can_be_discarded_and_legacy_copy_clears_the_same_marker() {
        for id in ["settings.copy.apply", "settings.copy_from"] {
            let f = fixture();
            let c = &f.0;
            let disk = fs::read(&c.storage).unwrap();
            action(c, "settings.input", json!({"key":"width","value":"bad"}));
            c.dispatch("settings.save", json!({}));
            if id.ends_with("apply") {
                preview(c);
            }
            action(c, id, json!({"clientId":"pad-1234"}));
            assert_eq!(c.state()["settingsErrorField"], "");
            action(c, "settings.cancel", json!({}));
            assert_eq!(fs::read(&c.storage).unwrap(), disk);
            assert_ne!(c.profile("host").width, 1280);
        }
    }
    #[test]
    fn preview_navigation_does_not_implicitly_accept_the_selected_source() {
        for choice in ["stay", "discard", "save"] {
            let f = fixture();
            let c = &f.0;
            action(
                c,
                "settings.input",
                json!({"key":"bitrateMbps","value":"37"}),
            );
            preview(c);
            action(c, "app.settings.tab", json!({"tab":"about"}));
            action(c, "navigation.resolve", json!({"choice":choice}));
            let s = c.state();
            if choice == "stay" {
                assert_eq!(s["page"], "profile-copy");
                assert_eq!(s["settingsInputs"]["bitrateMbps"], "37");
            } else {
                assert_eq!(s["page"], "global-settings");
                assert!(s["profileCopy"].is_null());
                assert_eq!(
                    c.profile("host").bitrate_mbps,
                    if choice == "save" { 37 } else { 25 }
                );
                assert_ne!(c.profile("host").width, 1280);
            }
        }
    }
    #[test]
    fn preview_blocks_stale_form_actions_and_rejects_wrong_source_without_destroying_input() {
        let f = fixture();
        let c = &f.0;
        action(c, "settings.input", json!({"key":"fps","value":"24"}));
        preview(c);
        let p = c.state()["profileCopy"].clone();
        for id in [
            "settings.cancel",
            "settings.save",
            "settings.tab",
            "settings.copy_from",
        ] {
            assert_eq!(
                c.dispatch(id, json!({"clientId":"pad-1234","tab":"audio"}))["ok"],
                false
            );
            assert_eq!(c.state()["settingsInputs"]["fps"], "24");
            assert_eq!(c.state()["profileCopy"], p);
        }
        for client in ["missing", c.state()["saved"]["clientId"].as_str().unwrap()] {
            assert_eq!(
                c.dispatch("settings.copy.preview", json!({"clientId":client}))["ok"],
                false
            );
            assert_eq!(c.state()["profileCopy"], p);
        }
        action(c, "settings.copy.cancel", json!({}));
        action(c, "settings.cancel", json!({}));
        assert_eq!(c.dispatch("settings.copy.apply", json!({}))["ok"], false);
    }
    #[test]
    fn identical_copy_has_no_false_pending_changes_and_failed_save_retains_the_applied_draft() {
        let f = fixture();
        let c = &f.0;
        let current = c.profile("host");
        {
            c.state
                .lock()
                .unwrap()
                .saved
                .client_profiles
                .get_mut("pad-1234")
                .unwrap()
                .insert("host".into(), ClientProfile::from_profile(&current));
        }
        preview(c);
        action(c, "settings.copy.apply", json!({}));
        assert_eq!(c.state()["settingsDirty"], false);
        assert_eq!(c.state()["profileCopyNotice"], "与当前配置相同");
        action(c, "settings.input", json!({"key":"fps","value":"24"}));
        let bytes = fs::read(&c.storage).unwrap();
        fs::remove_file(&c.storage).unwrap();
        fs::create_dir(&c.storage).unwrap();
        assert_eq!(c.dispatch("settings.save", json!({}))["ok"], false);
        assert_eq!(c.state()["page"], "settings");
        assert_eq!(c.state()["settingsInputs"]["fps"], "24");
        assert_eq!(c.profile("host").fps, 30);
        fs::remove_dir(&c.storage).unwrap();
        fs::write(&c.storage, bytes).unwrap();
    }
    #[test]
    fn theme_navigation_protects_the_draft_and_restores_the_inspected_preview() {
        let f = fixture();
        let c = &f.0;
        let dark = c.state()["saved"]["dark"].clone();
        action(c, "settings.input", json!({"key":"fps","value":"24"}));
        action(c, "settings.tab", json!({"tab":"advanced"}));
        preview(c);
        action(c, "appearance.toggle", json!({}));
        assert!(!c.state()["navigationPrompt"].is_null());
        assert_eq!(c.state()["saved"]["dark"], dark);
        action(c, "navigation.resolve", json!({"choice":"stay"}));
        assert_eq!(c.state()["page"], "profile-copy");
        action(c, "appearance.toggle", json!({}));
        action(c, "navigation.resolve", json!({"choice":"discard"}));
        assert_eq!(c.state()["page"], "profile-copy");
        assert_eq!(c.state()["profileCopy"]["clientId"], "pad-1234");
        assert_eq!(c.state()["profileTab"], "advanced");
        assert_eq!(c.state()["settingsDirty"],false);
        assert_ne!(c.state()["saved"]["dark"], dark);
        assert_eq!(c.profile("host").fps, 30);
    }
    #[test]
    fn clean_theme_change_keeps_the_inspected_snapshot_when_the_source_changes() {
        let f=fixture();let c=&f.0;preview(c);let inspected=c.state()["profileCopy"].clone();
        c.state.lock().unwrap().saved.client_profiles.get_mut("pad-1234").unwrap().get_mut("host").unwrap().bitrate_mbps=55;
        action(c,"appearance.toggle",json!({}));assert_eq!(c.state()["page"],"profile-copy");assert_eq!(c.state()["profileCopy"],inspected);assert_eq!(c.state()["settingsDirty"],false);
    }
    #[test]
    fn theme_discard_returns_to_settings_when_the_source_disappears() {
        let f=fixture();let c=&f.0;action(c,"settings.input",json!({"key":"fps","value":"24"}));action(c,"settings.tab",json!({"tab":"advanced"}));preview(c);action(c,"appearance.toggle",json!({}));
        c.state.lock().unwrap().saved.client_profiles.remove("pad-1234");action(c,"navigation.resolve",json!({"choice":"discard"}));assert_eq!(c.state()["page"],"settings");assert_eq!(c.state()["profileTab"],"advanced");assert!(c.state()["profileCopy"].is_null());assert_eq!(c.state()["profileCopyNotice"],"来源配置不可用");assert_eq!(c.state()["settingsDirty"],false);assert_eq!(c.profile("host").fps,30);
    }
}
