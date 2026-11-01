//! One state-to-controls binding shared by the application and headless workflow checks.
use crate::{
    CopyField, DeviceRow, MainWindow, Palette, ProfileSource, RemoteDisplay, RouteRow, SettingRow,
    devices, form_models, network, updates,
};
use rhremote_core::Profile;
use serde_json::Value;
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::rc::Rc;

fn apply_diagnostics(ui:&MainWindow,state:&Value){
    let job=&state["diagnostics"];let selected=state["selected"].as_str().unwrap_or("");let own=!selected.is_empty()&&job["uuid"]==selected;
    let busy=job["busy"]==true;let phase=job["phase"].as_str().unwrap_or("idle");let retry=phase=="cleanup-error";let failed=own&&matches!(phase,"error"|"cleanup-error");
    let stale=own&&!busy&&job["requestIntent"]!=state["diagnosticRequestIntent"];
    let summary=if own&&busy{match phase{"cleanup-error"=>job["error"].as_str().unwrap_or("诊断清理失败"),"cancelling"=>"正在取消诊断，等待通道清理…","cleaning"=>"正在清理诊断通道…",_=>"正在诊断连接…"}.to_owned()}
        else if busy{format!("{} 正在诊断连接，完成或取消后可重试",job["name"].as_str().unwrap_or("另一台设备"))}
        else if failed{job["error"].as_str().unwrap_or("诊断失败，请重试").to_owned()}
        else if own&&phase=="completed"{format!("{}\n{}",if stale{"上次诊断结果（连接设置已更改）"}else{"诊断完成"},if !job["result"]["selectedAddress"].is_string(){"未找到可用地址，请查看详情"}else if job["result"]["hostError"].is_string(){"设备信息可达，辅助服务未通过"}else{"设备信息可达，视频尚未验证"})}
        else if own&&phase=="cancelled"{"已取消诊断".into()}else{"尚未诊断".into()};
    ui.set_diagnostic_start_disabled(busy||state["closing"]==true||state["streaming"]==true||state["connecting"]==true||state["connection"]["busy"]==true||state["updates"]["phase"]=="installing");
    ui.set_diagnostic_summary(summary.into());ui.set_diagnostic_busy(busy);ui.set_diagnostic_own(own);ui.set_diagnostic_failed(failed);
    ui.set_diagnostic_start_label(if own&&busy{if phase=="running"{"诊断中…"}else{"正在清理…"}}else{"诊断连接"}.into());
    ui.set_diagnostic_start_args(serde_json::json!({"uuid":selected,"draftId":state["profileDraftId"],"expectedIntent":state["diagnosticRequestIntent"],"expectedOperationId":job["operationId"]}).to_string().into());
    ui.set_diagnostic_can_cancel(job["canCancel"]==true);ui.set_diagnostic_cancel_label(if retry{"重试清理"}else{"取消诊断"}.into());
    ui.set_diagnostic_cancel_args(serde_json::json!({"operationId":job["operationId"],"expectedAction":job["cancelAction"]}).to_string().into());
    ui.set_diagnostic_details_visible(own&&!busy&&matches!(phase,"completed"|"error"));ui.set_diagnostic_details_expanded(job["detailsExpanded"]==true);
    ui.set_diagnostic_details_args(serde_json::json!({"uuid":selected,"operationId":job["operationId"],"expectedExpanded":job["detailsExpanded"]==true}).to_string().into());
    ui.set_network_report(if job["result"].is_object(){network::report_text(&job["result"])}else{job["error"].as_str().unwrap_or("").to_owned()}.into());
    ui.set_diagnostic_banner(busy&&!(own&&state["page"]=="settings"&&state["profileTab"]=="network"));
    ui.set_diagnostic_banner_text(format!("{} · {}",job["name"].as_str().unwrap_or(""),if retry{"诊断清理失败"}else if phase=="cancelling"{"正在取消诊断"}else{"正在诊断连接"}).into());
    ui.set_diagnostic_banner_failed(retry);
    ui.set_diagnostic_reveal_key(format!("{}|{}|{}|{}",job["uuid"],job["operationId"],phase,job["detailsExpanded"]).into());
}
fn sync_summary(state:&Value)->&'static str{
    let sync=&state["sync"];
    if state["syncBusy"]==true {"同步进行中"}
    else if sync["conflicts"].as_array().is_some_and(|items|!items.is_empty()) {"配置存在冲突，请选择保留项"}
    else if sync["automaticBlocked"]==true {"上次同步结果待确认 · 自动同步已暂停"}
    else if state["syncError"].as_str().is_some_and(|error|!error.is_empty()) {"同步失败，请查看下方提示"}
    else if sync["hasPassword"]!=true {"请先保存 WebDAV 设置"}
    else if sync["lastSyncMs"].as_u64().unwrap_or(0)>0 {"上次同步已完成"}
    else {"尚未同步"}
}
#[cfg(test)]
#[test]
fn sync_summary_prioritizes_the_current_outcome_over_an_old_success(){
    let mut state=serde_json::json!({"sync":{"hasPassword":true,"lastSyncMs":42,"automaticBlocked":true},"syncError":"结果未确认"});
    assert_eq!(sync_summary(&state),"上次同步结果待确认 · 自动同步已暂停");
    state["sync"]["conflicts"]=serde_json::json!(["deviceOrder"]);assert_eq!(sync_summary(&state),"配置存在冲突，请选择保留项");
    state["syncBusy"]=serde_json::json!(true);assert_eq!(sync_summary(&state),"同步进行中");
    state["syncBusy"]=serde_json::json!(false);state["sync"]["conflicts"]=serde_json::json!([]);state["sync"]["automaticBlocked"]=serde_json::json!(false);assert_eq!(sync_summary(&state),"同步失败，请查看下方提示");
    state["syncError"]=serde_json::json!("");assert_eq!(sync_summary(&state),"上次同步已完成");
}

/// These operations only validate and mutate in-memory drafts. Commit them before
/// a render can copy an older value into a controlled editor. Persistence, network
/// work and native stream commands must stay on the asynchronous command queue.
pub(crate) fn dispatch_local_input(
    controller: &rhremote_core::Controller, id: &str, text: &str, owner: Option<String>,
) -> Option<Value> {
    if !matches!(id, "devices.filter" | "devices.filter.clear" | "device.add.input" | "device.info.input" | "device.info.reset"
        | "sync.edit" | "settings.input" | "settings.network.edit" | "settings.set"
        | "settings.step" | "settings.route.move" | "settings.route.toggle") { return None; }
    // Only boolean toggles need the current draft to resolve their next value.
    let state = if id == "settings.set" && text.split_once('=').is_some_and(|(_, value)| value == "toggle") {
        controller.state()
    } else { Value::Null };
    Some(controller.dispatch(id, crate::queued_args(id, text, &state, owner)))
}

pub(crate) struct MainView {
    revision: u64,
    profile_model: Rc<VecModel<SettingRow>>,
    profile_fingerprint: String,
    profile_control_values: Vec<(slint::SharedString, slint::SharedString, i32)>,
}
impl MainView {
    pub fn new(ui: &MainWindow) -> Self {
        let profile_model = Rc::new(VecModel::default());
        ui.set_profile_fields(ModelRc::from(profile_model.clone()));
        Self {
            revision: u64::MAX,
            profile_model,
            profile_fingerprint: String::new(),
            profile_control_values: Vec::new(),
        }
    }
    pub fn refresh(
        &mut self,
        ui: &MainWindow,
        state: &Value,
        fallback: impl FnOnce(&str) -> Profile,
    ) {
        let logical_width = ui.window().size().width as f32 / ui.window().scale_factor();
        let compact = logical_width < 980.0;
        let device_context = matches!(
            state["page"].as_str(),
            Some("devices" | "settings" | "profile-copy" | "device-info")
        ) && state["streaming"] != true && state["connecting"] != true;
        let navigation_width = if compact { 64.0 } else { 184.0 };
        let list_width = if device_context {
            if compact { 224.0 } else { 276.0 }
        } else { 0.0 };
        ui.set_form_inset(((logical_width - navigation_width - list_width - 800.0) / 2.0).max(24.0));
        let next = state["revision"].as_u64().unwrap_or(0);
        ui.set_compact(ui.window().size().width as f32 / ui.window().scale_factor() < 980.0);
        ui.set_update_primary_text(
            updates::primary_label(&state["updates"], state["updatesBusy"] == true).into(),
        );
        ui.set_update_primary_installs(
            rhremote_core::update_primary_action(&state["updates"]) == "updates.install",
        );
        ui.set_update_intent(state["updates"]["intent"].as_str().unwrap_or("").into());
        ui.set_update_install_hint(state["updateInstallHint"].as_str().unwrap_or("").into());
        ui.set_update_phase(state["updates"]["phase"].as_str().unwrap_or("").into());
        ui.set_update_progress(state["updates"]["progress"].as_i64().unwrap_or(0) as i32);
        ui.set_update_can_cancel(state["updates"]["canCancel"]==true);
        ui.set_update_cancelling(state["updates"]["cancelRequested"]==true);
        ui.set_update_operation(state["updates"]["operationId"].as_u64().unwrap_or(0).to_string().into());
        ui.set_update_status(state["updates"]["status"].as_str().unwrap_or("").into());
        ui.set_update_details(state["updateDetails"] == true);
        if next != self.revision {
            apply_diagnostics(ui,state);
            ui.set_test_mode(state["statistics"]["tracing"]["enabled"] == true);
            ui.set_trace_path(
                state["statistics"]["tracing"]["latest"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            self.revision = next;
            ui.set_page(state["page"].as_str().unwrap_or("devices").into());
            let windows=&state["windowPicker"];
            ui.set_window_picker_open(windows["open"]==true);
            ui.set_window_picker_busy(windows["busy"]==true);
            ui.set_window_picker_uuid(windows["uuid"].as_str().unwrap_or("").into());
            ui.set_window_picker_error(windows["error"].as_str().unwrap_or("").into());
            ui.set_window_picker_rows(ModelRc::new(VecModel::from(windows["rows"].as_array().into_iter().flatten().map(|row|RemoteDisplay{id:row["id"].as_str().unwrap_or("").into(),label:format!("{} · {} · {} × {}{}",row["title"].as_str().unwrap_or("窗口"),row["application"].as_str().unwrap_or(""),row["width"],row["height"],if row["state"]=="minimized"{" · 已最小化"}else if row["available"]!=true{" · 不可用"}else{""}).into(),chosen:false,available:row["available"]==true}).collect::<Vec<_>>())));

            ui.set_profile_draft_id(state["profileDraftId"].as_str().unwrap_or("").into());
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
            let navigation_id=state["navigationPrompt"]["requestId"].as_str().unwrap_or("");
            if state["navigationPrompt"].is_object() && (!ui.get_navigation_prompt() || ui.get_navigation_request_id()!=navigation_id) {
                ui.set_navigation_choice(0);
            }
            ui.set_navigation_request_id(navigation_id.into());
            ui.set_navigation_prompt(state["navigationPrompt"].is_object());
            ui.set_navigation_message(
                state["navigationPrompt"]["message"]
                    .as_str()
                    .unwrap_or("")
                    .into(),
            );
            let clear_id=state["syncClearPrompt"]["requestId"].as_str().unwrap_or("");
            if state["syncClearPrompt"].is_object() && (!ui.get_sync_clear_prompt() || ui.get_sync_clear_request_id()!=clear_id) {
                ui.set_sync_clear_choice(0);
            }
            ui.set_sync_clear_request_id(clear_id.into());
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
            ui.set_sync_password_reusable(state["syncPasswordReusable"]==true);
            ui.set_sync_busy(state["syncBusy"] == true);
            ui.set_sync_review_id(state["sync"]["reviewId"].as_str().unwrap_or("").into());
            ui.set_sync_progress_text(match state["sync"]["phase"].as_str(){Some("applying")=>"正在应用…",Some("restoring")=>"正在刷新…",Some("recording")=>"正在保存…",_=>"同步中…"}.into());
            form_models::update_sync_conflicts(ui, &state["sync"]["conflictItems"]);
            ui.set_sync_last(sync_summary(state).into());
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
            ui.set_devices_refreshing(state["devicesRefreshing"]==true);
            ui.set_devices_refresh_error(state["devicesRefreshError"].as_str().unwrap_or("").into());
            let selected = state["selected"].as_str().unwrap_or("");
            let pending=state["devicesPending"].as_array();
            let rows: Vec<DeviceRow> = devices
                .iter()
                .map(|d| {
                    let uuid=d["uuid"].as_str().unwrap_or("");
                    let current=state["deviceChecks"].get(uuid).and_then(|value|serde_json::from_value::<rhremote_core::DeviceCheck>(value.clone()).ok());
                    let retained=state["deviceTrustChecks"].get(uuid).and_then(|value|serde_json::from_value::<rhremote_core::DeviceCheck>(value.clone()).ok());
                    let check=retained.as_ref().or(current.as_ref());
                    let hint=match (&current,&retained){
                        (Some(current),Some(previous)) if current!=previous=>format!("本次检查：{}。上次检查：{}",current.label(),previous.hint()),
                        _=>check.map(|check|check.hint()).unwrap_or("").to_owned(),
                    };
                    DeviceRow {
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
                    } else if let Some(check)=&check {check.label()
                    } else if pending.is_some_and(|ids|ids.contains(&d["uuid"])) {
                        if state["devicesRefreshing"]==true{"检查中…"}else{"待检查"}
                    } else if d["online"] != true {
                        "离线"
                    } else if d["paired"] == true {
                        "已配对"
                    } else {
                        "待配对"
                    }
                    .into(),
                    paired: d["paired"] == true,
                    online: d["online"] == true && !check.as_ref().is_some_and(|check|check.blocks_connection()),
                    is_self: d["isSelf"] == true,
                    pending: pending.is_some_and(|ids|ids.contains(&d["uuid"])),
                    check_retry:check.is_some_and(|check|check.blocks_connection() || check.repair_required() && d["online"]!=true && pending.is_some_and(|ids|ids.contains(&d["uuid"]))),
                    check_failed:check.as_ref().is_some_and(|check|check.repair_required()||!matches!(check.outcome,rhremote_core::CheckOutcome::Paired|rhremote_core::CheckOutcome::Unpaired)),
                    check_hint:hint.into(),
                    repair_required:check.as_ref().is_some_and(|check|check.repair_required()),
                }})
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
                        toggle_blocked: order.len() <= 1,
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
            // Two-way widgets can change a row before validation. A rejected edit
            // must restore the authoritative value even when its draft is unchanged.
            let control_values: Vec<_> = self.profile_model.iter()
                .map(|row| (row.key, row.input_text, row.selected_index)).collect();
            if fingerprint != self.profile_fingerprint || control_values != self.profile_control_values {
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
                        let disabled = key=="bitrateMbps" && state["draft"]["bitrateAuto"]==true;
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
                            disabled,
                            number: value.as_i64().unwrap_or(0) as i32,
                            input_text: state["settingsInputs"][key].as_str().filter(|_|!disabled)
                                .map(str::to_owned)
                                .unwrap_or_else(|| value.to_string())
                                .into(),
                            invalid: !disabled && state["settingsErrorField"] == key,
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
                self.profile_control_values = rows.iter()
                    .map(|row| (row.key.clone(), row.input_text.clone(), row.selected_index)).collect();
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
            ui.set_bitrate_auto(p.bitrate_auto);
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
            let error_key=state["settingsErrorField"].as_str().unwrap_or("");
            let failed=state["page"]=="settings" && !error_key.is_empty()
                && state["settingsError"].as_str().is_some_and(|value|!value.is_empty());
            let focus=ui.global::<crate::ProfileErrorFocus>();
            focus.set_target_key(if failed{error_key}else{""}.into());
            focus.set_allowed(failed && !state["navigationPrompt"].is_object()
                && !state["syncClearPrompt"].is_object() && state["navigation"]["locked"]!=true);
            let request=if failed{state["settingsErrorRequest"].as_u64().filter(|id|*id>0)
                .map(|id|format!("{}:{id}",state["profileDraftId"].as_str().unwrap_or(""))).unwrap_or_default()}else{String::new()};
            focus.set_request_id(request.into());
        }
    }
}
