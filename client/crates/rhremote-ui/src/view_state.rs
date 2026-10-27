//! One state-to-controls binding shared by the application and headless workflow checks.
use crate::{
    CopyField, DeviceRow, MainWindow, Palette, ProfileSource, RemoteDisplay, RouteRow, SettingRow,
    devices, form_models, network, updates,
};
use rhremote_core::Profile;
use serde_json::Value;
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::rc::Rc;

pub(crate) struct MainView {
    revision: u64,
    profile_model: Rc<VecModel<SettingRow>>,
    profile_fingerprint: String,
}
impl MainView {
    pub fn new(ui: &MainWindow) -> Self {
        let profile_model = Rc::new(VecModel::default());
        ui.set_profile_fields(ModelRc::from(profile_model.clone()));
        Self {
            revision: u64::MAX,
            profile_model,
            profile_fingerprint: String::new(),
        }
    }
    pub fn refresh(
        &mut self,
        ui: &MainWindow,
        state: &Value,
        fallback: impl FnOnce(&str) -> Profile,
    ) {
        let logical_width = ui.window().size().width as f32 / ui.window().scale_factor();
        ui.set_form_inset(
            ((logical_width - if logical_width < 980.0 { 64.0 } else { 184.0 } - 800.0) / 2.0)
                .max(24.0),
        );
        let next = state["revision"].as_u64().unwrap_or(0);
        ui.set_compact(ui.window().size().width as f32 / ui.window().scale_factor() < 980.0);
        ui.set_update_primary_text(
            updates::primary_label(&state["updates"], state["updatesBusy"] == true).into(),
        );
        ui.set_update_primary_installs(
            rhremote_core::update_primary_action(&state["updates"]) == "updates.install",
        );
        ui.set_update_phase(state["updates"]["phase"].as_str().unwrap_or("").into());
        ui.set_update_progress(state["updates"]["progress"].as_i64().unwrap_or(0) as i32);
        ui.set_update_status(state["updates"]["status"].as_str().unwrap_or("").into());
        ui.set_update_details(state["updateDetails"] == true);
        if next != self.revision {
            ui.set_test_mode(state["statistics"]["tracing"]["enabled"] == true);
            ui.set_trace_path(
                state["statistics"]["tracing"]["latest"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            self.revision = next;
            ui.set_page(state["page"].as_str().unwrap_or("devices").into());
            ui.set_info_name(
                state["infoDraft"]["displayName"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            ui.set_info_error(state["infoDraft"]["error"].as_str().unwrap_or("").into());
            ui.set_info_dirty(state["infoDirty"] == true);
            ui.set_status(state["status"].as_str().unwrap_or("").into());
            ui.set_dark(state["saved"]["dark"].as_bool().unwrap_or(false));
            ui.global::<Palette>()
                .set_dark(state["saved"]["dark"].as_bool().unwrap_or(false));
            ui.set_connection_phase(state["connection"]["phase"].as_str().unwrap_or("").into());
            ui.set_connection_operation(
                state["connection"]["operation"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            ui.set_navigation_locked(state["navigation"]["locked"] == true);
            ui.set_navigation_hint(state["navigation"]["reason"].as_str().unwrap_or("").into());
            ui.set_connection_title(state["connection"]["title"].as_str().unwrap_or("").into());
            ui.set_connection_name(state["connection"]["name"].as_str().unwrap_or("").into());
            ui.set_connection_error(state["connection"]["error"].as_str().unwrap_or("").into());
            ui.set_connection_busy(state["connection"]["busy"] == true);
            ui.set_control_requests_pending(state["controlRequests"].as_u64().unwrap_or(0) > 0);
            ui.set_settings_dirty(state["settingsDirty"] == true);
            ui.set_settings_error(state["settingsError"].as_str().unwrap_or("").into());
            if !ui.get_navigation_prompt() && state["navigationPrompt"].is_object() {
                ui.set_navigation_choice(0);
            }
            ui.set_navigation_prompt(state["navigationPrompt"].is_object());
            ui.set_navigation_message(
                state["navigationPrompt"]["message"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            if !ui.get_sync_clear_prompt() && state["syncClearPrompt"].is_object() {
                ui.set_sync_clear_choice(0);
            }
            ui.set_sync_clear_prompt(state["syncClearPrompt"].is_object());
            let sync_form = if state["syncDraft"].is_object() {
                &state["syncDraft"]
            } else {
                &state["sync"]
            };
            ui.set_sync_generation(state["syncGeneration"].as_u64().unwrap_or(0) as i32);
            ui.set_sync_dirty(state["syncDirty"] == true);
            ui.set_sync_error(state["syncError"].as_str().unwrap_or("").into());
            ui.set_add_address(state["add"]["address"].as_str().unwrap_or("").into());
            ui.set_add_phase(state["add"]["phase"].as_str().unwrap_or("editing").into());
            ui.set_add_error(state["add"]["error"].as_str().unwrap_or("").into());
            ui.set_settings_tab(state["settingsTab"].as_str().unwrap_or("updates").into());
            ui.set_sync_url(sync_form["url"].as_str().unwrap_or("").into());
            ui.set_sync_username(sync_form["username"].as_str().unwrap_or("").into());
            ui.set_sync_automatic(sync_form["automatic"] == true);
            ui.set_sync_interval_index(match sync_form["intervalMinutes"].as_u64().unwrap_or(5) {
                15 => 1,
                30 => 2,
                60 => 3,
                _ => 0,
            });
            ui.set_sync_has_password(state["sync"]["hasPassword"] == true);
            ui.set_sync_busy(state["syncBusy"] == true);
            form_models::update_sync_conflicts(ui, &state["sync"]["conflictItems"]);
            ui.set_sync_last(
                if state["sync"]["lastSyncMs"].as_u64().unwrap_or(0) > 0 {
                    "已同步"
                } else {
                    "尚未同步"
                }
                .into(),
            );
            ui.set_updates_managed(state["updates"]["managed"] == true);
            ui.set_updates_busy(state["updatesBusy"] == true);
            ui.set_updates_available(state["updates"]["availableVersion"].as_str().is_some());
            ui.set_updates_pending(state["updates"]["pendingRestart"] == true);
            ui.set_update_version(
                state["updates"]["availableVersion"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            ui.set_update_status(state["updates"]["status"].as_str().unwrap_or("").into());
            let devices = state["devices"].as_array().cloned().unwrap_or_default();
            let selected = state["selected"].as_str().unwrap_or("");
            let rows: Vec<DeviceRow> = devices
                .iter()
                .map(|d| DeviceRow {
                    uuid: d["uuid"].as_str().unwrap_or("").into(),
                    name: d["displayName"]
                        .as_str()
                        .filter(|n| !n.is_empty())
                        .or_else(|| d["name"].as_str())
                        .unwrap_or("")
                        .into(),
                    host_name: d["name"].as_str().unwrap_or("").into(),
                    address: d["address"].as_str().unwrap_or("").into(),
                    search_addresses: devices::address_summary(
                        state["saved"]["devices"]
                            .as_array()
                            .into_iter()
                            .flatten()
                            .find(|h| h["uuid"] == d["uuid"])
                            .or(Some(d)),
                    )
                    .into(),
                    status: if d["isSelf"] == true {
                        "本机"
                    } else if d["online"] != true {
                        "离线"
                    } else if d["paired"] == true {
                        "已配对"
                    } else {
                        "待配对"
                    }
                    .into(),
                    paired: d["paired"] == true,
                    online: d["online"] == true,
                    is_self: d["isSelf"] == true,
                })
                .collect();
            ui.set_selected_device(
                rows.iter()
                    .find(|d| d.uuid == selected)
                    .cloned()
                    .unwrap_or_default(),
            );
            ui.set_selected_index(
                rows.iter()
                    .position(|d| d.uuid == selected)
                    .map(|i| i as i32)
                    .unwrap_or(-1),
            );
            ui.set_device_total(rows.len() as i32);
            ui.set_device_online(rows.iter().filter(|d| d.online).count() as i32);
            let query = state["deviceQuery"].as_str().unwrap_or("");
            ui.set_device_query(query.into());
            ui.set_selected_name(
                devices
                    .iter()
                    .find(|d| d["uuid"] == selected)
                    .and_then(|d| {
                        d["displayName"]
                            .as_str()
                            .filter(|n| !n.is_empty())
                            .or_else(|| d["name"].as_str())
                    })
                    .unwrap_or("")
                    .into(),
            );
            ui.set_draft_display_name(state["draftDisplayName"].as_str().unwrap_or("").into());
            devices::update_devices(ui, devices::filter_devices(rows, query));
            let host = state["saved"]["devices"]
                .as_array()
                .into_iter()
                .flatten()
                .find(|d| d["uuid"] == selected)
                .or_else(|| devices.iter().find(|d| d["uuid"] == selected));
            ui.set_selected_uuid(selected.into());
            ui.set_network_summary(devices::address_summary(host).into());
            ui.set_network_current(devices::last_route(&state["network"], selected).into());
            ui.set_network_address(
                if state["draft"].is_object() {
                    state["draftAddress"].as_str().unwrap_or("")
                } else {
                    host.and_then(|d| d["address"].as_str()).unwrap_or("")
                }
                .into(),
            );
            ui.set_network_addresses(
                if state["draft"].is_object() {
                    state["draftAddresses"].as_str().unwrap_or("").to_owned()
                } else {
                    host.and_then(|d| d["addresses"].as_array())
                        .map(|a| {
                            a.iter()
                                .filter_map(Value::as_str)
                                .collect::<Vec<_>>()
                                .join(", ")
                        })
                        .unwrap_or_default()
                }
                .into(),
            );
            ui.set_network_report(
                if state["network"]["uuid"] == selected {
                    network::report_text(&state["network"])
                } else {
                    "尚未诊断".into()
                }
                .into(),
            );
            let client_id = state["saved"]["clientId"].as_str().unwrap_or("");
            ui.set_profile_tab(state["profileTab"].as_str().unwrap_or("video").into());
            let order: Vec<&str> = state["draft"]["routeOrder"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
                .collect();
            let mut route_rows = Vec::new();
            for (index, key) in order.iter().enumerate() {
                if let Some((_, label)) = rhremote_core::network::ROUTES
                    .iter()
                    .find(|(id, _)| id == key)
                {
                    route_rows.push(RouteRow {
                        key: (*key).into(),
                        label: (*label).into(),
                        enabled: true,
                        can_move_up: index > 0,
                        can_move_down: index + 1 < order.len(),
                        can_disable: order.len() > 1,
                    });
                }
            }
            for (key, label) in rhremote_core::network::ROUTES {
                if !order.contains(key) {
                    route_rows.push(RouteRow {
                        key: (*key).into(),
                        label: (*label).into(),
                        enabled: false,
                        ..Default::default()
                    });
                }
            }
            form_models::update_routes(ui, route_rows);
            ui.set_relay_url(
                state["draftRelay"]
                    .as_str()
                    .unwrap_or(rhremote_core::network::DEFAULT_RELAY)
                    .into(),
            );
            ui.set_profile_sources_expanded(state["profileSourcesExpanded"] == true);
            ui.set_copy_source(
                state["profileCopy"]["sourceName"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            ui.set_copy_target(
                state["profileCopy"]["targetName"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            ui.set_copy_notice(state["profileCopyNotice"].as_str().unwrap_or("").into());
            ui.set_copy_error(state["profileCopyError"].as_str().unwrap_or("").into());
            ui.set_copy_fields(ModelRc::new(VecModel::from(
                state["profileCopy"]["fields"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .map(|field| CopyField {
                        label: field["label"].as_str().unwrap_or("").into(),
                        value: field["value"].as_str().unwrap_or("").into(),
                    })
                    .collect::<Vec<_>>(),
            )));

            ui.set_audio_enabled(state["audioEnabled"] == true);
            ui.set_audio_volume(state["volume"].as_i64().unwrap_or(100) as i32);
            let remote: Vec<RemoteDisplay> = state["statistics"]["displays"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|d| RemoteDisplay {
                    id: d["id"].as_str().unwrap_or("").into(),
                    label: format!(
                        "{} · {}×{}",
                        if d["virtual"] == true {
                            "虚拟屏"
                        } else {
                            d["name"]
                                .as_str()
                                .filter(|v| !v.is_empty())
                                .unwrap_or("屏幕")
                        },
                        d["width"],
                        d["height"]
                    )
                    .into(),
                    chosen: d["id"] == state["statistics"]["selectedDisplay"]["id"],
                    available: d["available"] == true,
                })
                .collect();
            ui.set_remote_displays(ModelRc::new(VecModel::from(remote)));
            ui.set_display_error(
                state["statistics"]["displayError"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            let fingerprint = format!(
                "{}:{}:{}:{}",
                state["profileTab"],
                state["draft"],
                state["settingsInputs"],
                state["settingsErrorField"]
            );
            if fingerprint != self.profile_fingerprint {
                self.profile_fingerprint = fingerprint;
                let rows: Vec<SettingRow> = state["settingsCatalog"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter(|f| {
                        f["tab"] == state["profileTab"]
                            && f["platforms"]
                                .as_array()
                                .is_none_or(|p| p.iter().any(|v| v == "windows"))
                    })
                    .map(|f| {
                        let key = f["key"].as_str().unwrap();
                        let value = &state["draft"][key];
                        let opts = f["options"].as_array().cloned().unwrap_or_default();
                        let labels: Vec<slint::SharedString> = opts
                            .iter()
                            .map(|v| v[1].as_str().unwrap_or("").into())
                            .collect();
                        let values: Vec<slint::SharedString> = opts
                            .iter()
                            .map(|v| v[0].as_str().unwrap_or("").into())
                            .collect();
                        SettingRow {
                            key: key.into(),
                            label: f["label"].as_str().unwrap_or("").into(),
                            kind: f["type"].as_str().unwrap_or("").into(),
                            help: f["help"].as_str().unwrap_or("").into(),
                            flag: value == true,
                            number: value.as_i64().unwrap_or(0) as i32,
                            input_text: state["settingsInputs"][key]
                                .as_str()
                                .map(str::to_owned)
                                .unwrap_or_else(|| value.to_string())
                                .into(),
                            invalid: state["settingsErrorField"] == key,
                            minimum: f["min"].as_i64().unwrap_or(0) as i32,
                            maximum: f["max"].as_i64().unwrap_or(100) as i32,
                            step: f["step"].as_i64().unwrap_or(1) as i32,
                            selected_index: opts.iter().position(|o| o[0] == *value).unwrap_or(0)
                                as i32,
                            option_labels: ModelRc::new(VecModel::from(labels)),
                            option_values: ModelRc::new(VecModel::from(values)),
                        }
                    })
                    .collect();
                if self.profile_model.row_count() == rows.len()
                    && (rows.is_empty()
                        || self
                            .profile_model
                            .row_data(0)
                            .is_some_and(|r| r.key == rows[0].key))
                {
                    for (i, row) in rows.into_iter().enumerate() {
                        self.profile_model.set_row_data(i, row);
                    }
                } else {
                    self.profile_model.set_vec(rows);
                }
            }
            let client_name = state["saved"]["clients"][client_id]["name"]
                .as_str()
                .unwrap_or("Windows");
            ui.set_client_name(
                format!(
                    "{} · {}",
                    client_name,
                    client_id
                        .chars()
                        .rev()
                        .take(4)
                        .collect::<String>()
                        .chars()
                        .rev()
                        .collect::<String>()
                )
                .into(),
            );
            let sources: Vec<ProfileSource> = state["saved"]["clientProfiles"]
                .as_object()
                .into_iter()
                .flatten()
                .filter(|(id, profiles)| {
                    id.as_str() != client_id && profiles.get(selected).is_some()
                })
                .map(|(id, profiles)| {
                    let p = &profiles[selected];
                    let name = state["saved"]["clients"][id]["name"].as_str().unwrap_or(id);
                    let suffix = id
                        .chars()
                        .rev()
                        .take(4)
                        .collect::<String>()
                        .chars()
                        .rev()
                        .collect::<String>();
                    ProfileSource {
                        client_id: id.into(),
                        label: format!(
                            "{} · {} · {} × {} · {} 帧",
                            name, suffix, p["width"], p["height"], p["fps"]
                        )
                        .into(),
                    }
                })
                .collect();
            let existing = ui.get_profile_sources();
            let same = existing.row_count() == sources.len()
                && sources.iter().enumerate().all(|(index, row)| {
                    existing
                        .row_data(index)
                        .is_some_and(|old| old.client_id == row.client_id && old.label == row.label)
                });
            if !same {
                ui.set_profile_sources(ModelRc::new(VecModel::from(sources)));
            }
            let p: Profile = serde_json::from_value(state["draft"].clone())
                .unwrap_or_else(|_| fallback(selected));
            ui.set_profile_width(p.width as i32);
            ui.set_profile_height(p.height as i32);
            ui.set_fps(p.fps as i32);
            ui.set_bitrate(p.bitrate_mbps as i32);
            ui.set_codec(p.codec.into());
            ui.set_profile_audio_enabled(p.audio_enabled);
            ui.set_profile_display(p.display_mode.into());
            ui.set_swapped(p.swap_buttons);
            ui.set_trackpad(p.trackpad);
            ui.set_statistics(if state["streaming"] == true {
                state["showStats"] == true
            } else {
                p.statistics
            });
            ui.set_pair_pin(state["pairPin"].as_str().unwrap_or("").into());
            ui.set_streaming(state["streaming"] == true);
            ui.set_connecting(state["connecting"] == true);
            ui.set_controls(state["controls"] == true);
            ui.set_stream_statistics(
                state["statistics"]["statistics"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
        }
    }
}
