//! Acceptance scenes for the shared controls and renderer, with no native OS windows.
use crate::{
    Commands, CopyField, DeviceRow, MainWindow, Palette, SettingRow, StreamPicker, StreamToolbar,
};
use i_slint_backend_testing::{ElementHandle, TestingBackend, TestingBackendOptions};
use slint::{
    ComponentHandle, LogicalPosition, LogicalSize, ModelRc, VecModel,
    platform::{PointerEventButton, WindowEvent},
};
use std::{cell::RefCell, rc::Rc};

fn capture(ui: &impl ComponentHandle, name: &str) {
    let frame = ui.window().take_snapshot().expect("Software frame failed");
    assert!(frame.width() > 0 && frame.height() > 0);
    if let Some(directory) = std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR") {
        let path = std::path::PathBuf::from(directory).join(format!("{name}.png"));
        assert!(!path.exists(), "Never replace evidence");
        image::save_buffer(
            path,
            frame.as_bytes(),
            frame.width(),
            frame.height(),
            image::ColorType::Rgba8,
        )
        .unwrap();
    }
}
fn click(ui: &impl ComponentHandle, label: &str) {
    let item = ElementHandle::find_by_accessible_label(ui, label)
        .next()
        .unwrap_or_else(|| panic!("Missing {label}"));
    let p = item.absolute_position();
    let s = item.size();
    let bounds = ui.window().size();
    assert!(
        p.x >= 0.
            && p.y >= 0.
            && p.x + s.width <= bounds.width as f32
            && p.y + s.height <= bounds.height as f32,
        "{label} is outside the window"
    );
    let position = LogicalPosition::new(p.x + s.width / 2., p.y + s.height / 2.);
    ui.window().dispatch_event(WindowEvent::PointerPressed {
        position,
        button: PointerEventButton::Left,
    });
    ui.window().dispatch_event(WindowEvent::PointerReleased {
        position,
        button: PointerEventButton::Left,
    });
}
fn model<T: Clone + 'static>(rows: Vec<T>) -> ModelRc<T> {
    ModelRc::new(VecModel::from(rows))
}
fn main_scene(
    kind: &str,
    dark: bool,
    compact: bool,
) -> (MainWindow, &'static str, &'static str, &'static str) {
    let ui = MainWindow::new().unwrap();
    ui.set_dark(dark);
    ui.global::<Palette>().set_dark(dark);
    ui.set_compact(compact);
    ui.set_app_version(env!("CARGO_PKG_VERSION").into());
    let first = DeviceRow {
        uuid: "fixture-winserver".into(),
        name: "工作电脑".into(),
        host_name: "winserver".into(),
        address: "192.0.2.1".into(),
        status: "在线".into(),
        paired: true,
        online: true,
        ..Default::default()
    };
    ui.set_devices(model(vec![
        first.clone(),
        DeviceRow {
            uuid: "fixture-self".into(),
            name: "RailgunHamster".into(),
            host_name: "RailgunHamster".into(),
            address: "192.0.2.2".into(),
            status: "本机".into(),
            is_self: true,
            online: true,
            ..Default::default()
        },
        DeviceRow {
            uuid: "fixture-claw".into(),
            name: "claw".into(),
            host_name: "claw".into(),
            address: "192.0.2.3".into(),
            status: "离线".into(),
            paired: true,
            ..Default::default()
        },
    ]));
    ui.set_selected_device(first);
    ui.set_selected_index(0);
    ui.set_selected_uuid("fixture-winserver".into());
    ui.set_selected_name("工作电脑".into());
    ui.set_device_total(3);
    ui.set_device_online(2);
    let action = match kind {
        "devices" => {
            ui.set_page("devices".into());
            ("连接", "device.connect", "fixture-winserver")
        }
        "appearance" => {
            ui.set_page("global-settings".into());
            ui.set_settings_tab("appearance".into());
            (
                if dark {
                    "切换为浅色"
                } else {
                    "切换为深色"
                },
                "appearance.toggle",
                "",
            )
        }
        "diagnostics" => {
            ui.set_page("global-settings".into());
            ui.set_settings_tab("about".into());
            ui.set_test_mode(true);
            ui.set_trace_path("D:\\RHRemoteTools\\layout-fixture\\statistics".into());
            ("测试统计：开启", "statistics.configure", "false")
        }
        "updates-failed" => {
            ui.set_page("global-settings".into());
            ui.set_settings_tab("updates".into());
            ui.set_updates_managed(true);
            ui.set_update_phase("error".into());
            ui.set_update_primary_text("重新检查".into());
            ui.set_update_details(true);
            ui.set_update_status("无法读取更新信息，请检查网络后重试。".repeat(8).into());
            ("重新检查", "updates.primary", "")
        }
        "add-failed" => {
            ui.set_page("add".into());
            ui.set_add_address("bad/path".into());
            ui.set_add_error("地址无效，请填写 IP 地址或主机名".into());
            ("添加", "device.add.submit", "bad/path")
        }
        "info-failed" => {
            ui.set_page("device-info".into());
            ui.set_info_name("工作电脑".into());
            ui.set_info_dirty(true);
            ui.set_info_error("名称未能保存，请重试".into());
            ("完成", "device.info.save", "")
        }
        "settings-failed" => {
            ui.set_page("settings".into());
            ui.set_profile_tab("video".into());
            ui.set_settings_dirty(true);
            ui.set_settings_error("帧率无效，请填写 1 至 240 之间的整数".into());
            ui.set_profile_fields(model(vec![SettingRow {
                key: "fps".into(),
                label: "帧率".into(),
                kind: "number".into(),
                input_text: "invalid".into(),
                invalid: true,
                minimum: 1,
                maximum: 240,
                step: 1,
                ..Default::default()
            }]));
            ("保存", "settings.save", "")
        }
        "copy-failed" => {
            ui.set_page("profile-copy".into());
            ui.set_copy_source("Android 平板".into());
            ui.set_copy_target("工作电脑".into());
            ui.set_copy_fields(model(vec![
                CopyField {
                    label: "分辨率".into(),
                    value: "1920 × 1080".into(),
                },
                CopyField {
                    label: "码率".into(),
                    value: "25 Mbps".into(),
                },
            ]));
            ui.set_copy_error("来源配置已变化，请重新打开预览".into());
            ("取消", "settings.copy.cancel", "")
        }
        "connection-preparing" | "connection-recovering" | "connection-failed" => {
            let phase = if kind == "connection-preparing" {
                "first-frame"
            } else if kind == "connection-recovering" {
                "reconnecting"
            } else {
                "failed"
            };
            let busy = phase != "failed";
            ui.set_page("connection".into());
            ui.set_connection_phase(phase.into());
            ui.set_connection_operation("connect".into());
            ui.set_connection_name("工作电脑".into());
            ui.set_connection_busy(busy);
            ui.set_connecting(busy);
            ui.set_navigation_locked(busy);
            ui.set_navigation_hint(
                if busy {
                    "连接期间无法切换页面"
                } else {
                    ""
                }
                .into(),
            );
            ui.set_connection_title(
                if phase == "first-frame" {
                    "等待远程画面"
                } else if busy {
                    "正在恢复连接"
                } else {
                    "连接失败"
                }
                .into(),
            );
            if phase != "first-frame" {
                ui.set_connection_error("网络暂不可用，请检查连接后重试。".repeat(4).into());
            }
            if busy {
                ("取消连接", "stream.disconnect", "")
            } else {
                ("重试连接", "connection.retry", "")
            }
        }
        "sync-clear-failed" => {
            ui.set_page("global-settings".into());
            ui.set_settings_tab("sync".into());
            ui.set_sync_has_password(true);
            ui.set_sync_clear_prompt(true);
            ui.set_sync_error("同步设置未能移除，请重试".into());
            ("移除", "sync.clear.resolve", "remove")
        }
        "navigation-save-failed" => {
            ui.set_page("global-settings".into());
            ui.set_settings_tab("sync".into());
            ui.set_sync_dirty(true);
            ui.set_navigation_prompt(true);
            ui.set_navigation_message("WebDAV 地址无效。输入已保留，请继续编辑。".into());
            ("继续编辑", "navigation.resolve", "stay")
        }
        _ => panic!("Unknown scene {kind}"),
    };
    (ui, action.0, action.1, action.2)
}

#[test]
fn page_errors_connection_stages_and_session_popups_render_and_keep_their_actions() {
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
        mock_time: true,
        threading: false,
        renderer_name: Some("software".into()),
    })))
    .unwrap();
    for dark in [false, true] {
        for compact in [false, true] {
            for kind in [
                "devices",
                "appearance",
                "diagnostics",
                "updates-failed",
                "add-failed",
                "info-failed",
                "settings-failed",
                "copy-failed",
                "connection-preparing",
                "connection-recovering",
                "connection-failed",
                "sync-clear-failed",
                "navigation-save-failed",
            ] {
                let (ui, label, id, arg) = main_scene(kind, dark, compact);
                let calls = Rc::new(RefCell::new(Vec::new()));
                let received = calls.clone();
                ui.global::<Commands>().on_trigger(move |id, arg| {
                    received
                        .borrow_mut()
                        .push((id.to_string(), arg.to_string()))
                });
                ui.show().unwrap();
                ui.window().set_size(LogicalSize::new(
                    if compact { 720. } else { 1180. },
                    if compact { 560. } else { 780. },
                ));
                capture(
                    &ui,
                    &format!(
                        "page-{kind}-{}-{}",
                        if compact { "compact" } else { "wide" },
                        if dark { "dark" } else { "light" }
                    ),
                );
                calls.borrow_mut().clear();
                click(&ui, label);
                assert_eq!(
                    &*calls.borrow(),
                    &[(id.to_string(), arg.to_string())],
                    "Wrong action in {kind}"
                );
                ui.hide().unwrap();
            }
            let width = if compact { 360. } else { 560. };
            for tab in ["input", "displays", "audio"] {
                let ui = StreamToolbar::new().unwrap();
                ui.global::<Palette>().set_dark(dark);
                ui.set_device_name("工作电脑".into());
                ui.set_controls_tab(tab.into());
                ui.set_expanded(true);
                ui.set_audio_enabled(true);
                ui.set_audio_volume(70);
                ui.set_failed(true);
                ui.set_status("操作失败，请查看详情".into());
                let calls = Rc::new(RefCell::new(Vec::new()));
                let received = calls.clone();
                ui.global::<Commands>().on_trigger(move |id, arg| {
                    received
                        .borrow_mut()
                        .push((id.to_string(), arg.to_string()))
                });
                ui.show().unwrap();
                ui.window().set_size(LogicalSize::new(width, 292.));
                capture(
                    &ui,
                    &format!(
                        "toolbar-{tab}-{}-{}",
                        if compact { "compact" } else { "wide" },
                        if dark { "dark" } else { "light" }
                    ),
                );
                calls.borrow_mut().clear();
                click(&ui, "详情");
                assert_eq!(
                    &*calls.borrow(),
                    &[("stream.menu.open".to_owned(), "feedback".to_owned())]
                );
                ui.hide().unwrap();
            }
            for kind in ["feedback", "displays", "shortcuts"] {
                let ui = StreamPicker::new().unwrap();
                ui.global::<Palette>().set_dark(dark);
                ui.set_kind(kind.into());
                ui.set_error("操作未能完成，请检查远程设备后重试。".repeat(6).into());
                if kind != "feedback" {
                    ui.set_labels(model(vec![
                        "选项一 · 长名称检查".into(),
                        "选项二 · 暂不可用".into(),
                    ]));
                    ui.set_values(model(vec!["fixture-1".into(), "fixture-2".into()]));
                    ui.set_enabled_items(model(vec![true, false]));
                    ui.set_chosen_items(model(vec![true, false]));
                }
                let calls = Rc::new(RefCell::new(Vec::new()));
                let received = calls.clone();
                ui.global::<Commands>().on_trigger(move |id, arg| {
                    received
                        .borrow_mut()
                        .push((id.to_string(), arg.to_string()))
                });
                ui.show().unwrap();
                ui.window()
                    .set_size(LogicalSize::new(if compact { 300. } else { 420. }, 300.));
                capture(
                    &ui,
                    &format!(
                        "picker-{kind}-{}-{}",
                        if compact { "compact" } else { "wide" },
                        if dark { "dark" } else { "light" }
                    ),
                );
                calls.borrow_mut().clear();
                click(&ui, "关闭");
                assert_eq!(
                    &*calls.borrow(),
                    &[("stream.menu.close".to_owned(), "".to_owned())]
                );
                ui.hide().unwrap();
            }
        }
    }
}
