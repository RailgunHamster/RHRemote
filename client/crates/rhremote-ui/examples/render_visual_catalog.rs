//! Render the production Slint components with explicit visual fixtures.
//! This exporter creates no OS windows, connections, updates or sync jobs.
use i_slint_backend_testing::{TestingBackend, TestingBackendOptions};
use rhremote::{
    DeviceRow, MainWindow, Palette, RemoteDisplay, RouteRow, SettingRow, StreamPicker,
    StreamToolbar, UiTest,
};
use serde_json::{Value, json};
use slint::{ComponentHandle, LogicalSize, ModelRc, VecModel};
use std::{error::Error, fs, path::Path};

type Result<T> = std::result::Result<T, Box<dyn Error>>;

fn model<T: Clone + 'static>(rows: Vec<T>) -> ModelRc<T> {
    ModelRc::new(VecModel::from(rows))
}

fn capture(
    ui: &impl ComponentHandle,
    out: &Path,
    name: &str,
    images: &mut Vec<Value>,
) -> Result<()> {
    let path = out.join(format!("{name}.png"));
    if path.exists() {
        return Err(format!("Refusing to replace {}", path.display()).into());
    }
    // Resolve changed bindings and conditional children before saving the stable frame.
    ui.window().take_snapshot()?;
    let frame = ui.window().take_snapshot()?;
    image::save_buffer(
        &path,
        frame.as_bytes(),
        frame.width(),
        frame.height(),
        image::ColorType::Rgba8,
    )?;
    images.push(json!({"name":name,"file":format!("{name}.png"),"width":frame.width(),"height":frame.height(),"fixture":true}));
    Ok(())
}

fn scroll_bottom(ui: &impl ComponentHandle, test: UiTest<'_>, target: &str) -> Result<()> {
    test.set_enabled(true);
    test.set_operation("scroll".into());
    test.set_target(target.into());
    test.set_scroll_delta(-10000.);
    test.set_generation(test.get_generation() + 1);
    ui.window().take_snapshot()?;
    Ok(())
}

fn toolbars(out: &Path, dark: bool, narrow: bool, images: &mut Vec<Value>) -> Result<()> {
    let theme = if dark { "dark" } else { "light" };
    let size = if narrow { "compact" } else { "wide" };
    for scene in [
        "input",
        "displays",
        "audio",
        "busy",
        "error",
        "pending",
        "collapsed",
    ] {
        let ui = StreamToolbar::new()?;
        ui.global::<Palette>().set_dark(dark);
        ui.set_device_name("winserver".into());
        ui.set_controls_tab(
            if scene == "busy" || scene == "error" {
                "displays"
            } else {
                scene
            }
            .into(),
        );
        ui.set_expanded(scene != "pending" && scene != "collapsed");
        ui.set_pending(scene == "pending");
        ui.set_busy(scene == "busy");
        ui.set_failed(scene == "error");
        ui.set_status(
            match scene {
                "pending" => "正在建立串流，等待远程画面…",
                "busy" => "正在切换屏幕…",
                "error" => "所选屏幕暂不可用，请刷新后重试。",
                _ => "",
            }
            .into(),
        );
        ui.set_audio_enabled(true);
        ui.set_audio_volume(40);
        ui.set_display_labels(model(vec![
            "LG FULL HD · 1920 × 1080".into(),
            "平板虚拟屏 · 2520 × 1680".into(),
        ]));
        ui.set_displays(model(vec![
            RemoteDisplay {
                id: "fixture-physical".into(),
                label: "LG FULL HD · 1920 × 1080".into(),
                chosen: true,
                available: true,
            },
            RemoteDisplay {
                id: "fixture-virtual".into(),
                label: "平板虚拟屏 · 2520 × 1680".into(),
                chosen: false,
                available: true,
            },
        ]));
        ui.show()?;
        ui.window().set_size(LogicalSize::new(
            if scene == "collapsed" {
                72.
            } else if narrow {
                360.
            } else {
                560.
            },
            if scene == "collapsed" {
                28.
            } else if scene == "pending" {
                if narrow { 160. } else { 116. }
            } else {
                292.
            },
        ));
        capture(&ui, out, &format!("toolbar-{scene}-{size}-{theme}"), images)?;
        if ["displays", "busy", "error"].contains(&scene) {
            scroll_bottom(&ui, ui.global::<UiTest>(), "scroll|toolbar")?;
            capture(
                &ui,
                out,
                &format!("toolbar-{scene}-bottom-{size}-{theme}"),
                images,
            )?;
        }
        ui.hide()?;
    }
    Ok(())
}

fn pickers(out: &Path, dark: bool, narrow: bool, images: &mut Vec<Value>) -> Result<()> {
    let theme = if dark { "dark" } else { "light" };
    let size = if narrow { "compact" } else { "wide" };
    let shortcuts: Vec<Value> =
        serde_json::from_str(include_str!("../../../shared/shortcuts.json"))?;
    for scene in ["shortcuts", "displays", "empty", "feedback"] {
        let ui = StreamPicker::new()?;
        ui.global::<Palette>().set_dark(dark);
        ui.set_kind(if scene == "empty" { "displays" } else { scene }.into());
        match scene {
            "shortcuts" => {
                ui.set_labels(model(shortcuts.iter().map(|s|s["label"].as_str().unwrap().into()).collect()));
                ui.set_values(model(shortcuts.iter().map(|s|s["id"].as_str().unwrap().into()).collect()));
            }
            "displays" => {
                ui.set_labels(model(vec!["LG FULL HD · 1920 × 1080".into(),"平板虚拟屏 · 2520 × 1680".into(),"未连接的显示器".into()]));
                ui.set_values(model(vec!["fixture-physical".into(),"fixture-virtual".into(),"fixture-offline".into()]));
                ui.set_enabled_items(model(vec![true,true,false]));
                ui.set_chosen_items(model(vec![true,false,false]));
            }
            "feedback" => ui.set_error("屏幕切换未能完成。所选显示器暂不可用，请返回屏幕分类刷新，再选择一块可用的显示器。".into()),
            _ => {}
        }
        ui.show()?;
        ui.window()
            .set_size(LogicalSize::new(if narrow { 320. } else { 420. }, 400.));
        capture(&ui, out, &format!("picker-{scene}-{size}-{theme}"), images)?;
        if scene == "shortcuts" {
            scroll_bottom(&ui, ui.global::<UiTest>(), "scroll|picker")?;
            capture(
                &ui,
                out,
                &format!("picker-shortcuts-bottom-{size}-{theme}"),
                images,
            )?;
        }
        ui.hide()?;
    }
    Ok(())
}

fn connections(out: &Path, dark: bool, narrow: bool, images: &mut Vec<Value>) -> Result<()> {
    let theme = if dark { "dark" } else { "light" };
    let size = if narrow { "compact" } else { "wide" };
    for scene in ["first-frame", "failed", "reconnecting", "pair", "paired"] {
        let ui = MainWindow::new()?;
        ui.global::<Palette>().set_dark(dark);
        ui.set_dark(dark);
        ui.set_compact(narrow);
        ui.set_isolated_preview(true);
        ui.set_app_version(env!("CARGO_PKG_VERSION").into());
        let first = DeviceRow {
            uuid: "fixture-winserver".into(),
            name: "winserver".into(),
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
                status: "本机".into(),
                is_self: true,
                ..Default::default()
            },
            DeviceRow {
                uuid: "fixture-claw".into(),
                name: "claw".into(),
                status: "离线".into(),
                paired: true,
                ..Default::default()
            },
        ]));
        ui.set_selected_device(first);
        ui.set_selected_uuid("fixture-winserver".into());
        ui.set_selected_name("winserver".into());
        ui.set_connection_name("winserver".into());
        ui.set_page(
            if scene == "pair" {
                "pair"
            } else {
                "connection"
            }
            .into(),
        );
        ui.set_connection_operation(
            if scene == "pair" || scene == "paired" {
                "pair"
            } else {
                "connect"
            }
            .into(),
        );
        ui.set_connection_phase(scene.into());
        ui.set_connection_title(
            match scene {
                "first-frame" => "等待远程画面",
                "failed" => "连接失败",
                "reconnecting" => "正在恢复连接",
                "pair" => "等待主机确认配对",
                _ => "配对完成",
            }
            .into(),
        );
        let busy = ["first-frame", "reconnecting", "pair"].contains(&scene);
        ui.set_connection_busy(busy);
        ui.set_navigation_locked(busy);
        ui.set_pair_pin("1234".into());
        if scene == "failed" {
            ui.set_connection_error("连接超时，请检查设备地址和网络后重试。".into());
        }
        ui.show()?;
        ui.window().set_size(LogicalSize::new(
            if narrow { 760. } else { 1180. },
            if narrow { 640. } else { 780. },
        ));
        capture(
            &ui,
            out,
            &format!("connection-{scene}-{size}-{theme}"),
            images,
        )?;
        ui.hide()?;
    }
    Ok(())
}

fn forms(out: &Path, dark: bool, narrow: bool, images: &mut Vec<Value>) -> Result<()> {
    let theme = if dark { "dark" } else { "light" };
    let size = if narrow { "compact" } else { "wide" };
    let catalog = rhremote_core::settings_catalog();
    let profile = serde_json::to_value(rhremote_core::Profile::default())?;
    for scene in [
        "video",
        "audio",
        "input",
        "network",
        "advanced",
        "updates",
        "sync",
        "appearance",
        "about",
        "add",
        "device-info",
    ] {
        let ui = MainWindow::new()?;
        ui.global::<Palette>().set_dark(dark);
        ui.set_dark(dark);
        ui.set_compact(narrow);
        ui.set_isolated_preview(true);
        ui.set_app_version(env!("CARGO_PKG_VERSION").into());
        ui.set_selected_uuid("fixture-winserver".into());
        ui.set_selected_name("winserver".into());
        let selected=DeviceRow{uuid:"fixture-winserver".into(),name:"winserver".into(),host_name:"winserver".into(),address:"192.0.2.1".into(),status:"在线".into(),paired:true,online:true,..Default::default()};
        ui.set_selected_device(selected.clone());
        ui.set_devices(model(vec![selected,DeviceRow{uuid:"fixture-self".into(),name:"RailgunHamster".into(),status:"本机".into(),is_self:true,..Default::default()},DeviceRow{uuid:"fixture-claw".into(),name:"claw".into(),status:"离线".into(),paired:true,..Default::default()}]));
        ui.set_device_total(3);ui.set_device_online(2);
        ui.set_client_name("Windows · 预览".into());
        let is_profile = ["video", "audio", "input", "network", "advanced"].contains(&scene);
        ui.set_page(
            if is_profile {
                "settings"
            } else if scene == "add" || scene == "device-info" {
                scene
            } else {
                "global-settings"
            }
            .into(),
        );
        ui.set_profile_tab(scene.into());
        ui.set_settings_tab(scene.into());
        ui.set_profile_width(1920);
        ui.set_profile_height(1080);
        ui.set_fps(30);
        ui.set_bitrate(25);
        ui.set_updates_managed(true);
        ui.set_update_primary_text("检查更新".into());
        ui.set_update_status("尚未检查更新".into());
        ui.set_network_address("192.0.2.1".into());
        ui.set_network_addresses("100.64.0.1".into());
        ui.set_network_summary("已保存首选地址和备用地址".into());
        ui.set_relay_url("https://relay.example.com/".into());
        ui.set_network_routes(model(
            rhremote_core::network::ROUTES
                .iter()
                .enumerate()
                .map(|(i, (id, label))| RouteRow {
                    key: (*id).into(),
                    label: (*label).into(),
                    enabled: true,
                    can_move_up: i > 0,
                    can_move_down: i + 1 < rhremote_core::network::ROUTES.len(),
                    can_disable: true,
                })
                .collect(),
        ));
        ui.set_profile_fields(model(
            catalog
                .as_array()
                .unwrap()
                .iter()
                .filter(|f| {
                    f["tab"] == scene
                        && f["platforms"]
                            .as_array()
                            .is_none_or(|p| p.iter().any(|v| v == "windows"))
                })
                .map(|f| {
                    let key = f["key"].as_str().unwrap();
                    let value = &profile[key];
                    let options = f["options"].as_array().cloned().unwrap_or_default();
                    SettingRow {
                        key: key.into(),
                        label: f["label"].as_str().unwrap_or("").into(),
                        kind: f["type"].as_str().unwrap_or("").into(),
                        help: f["help"].as_str().unwrap_or("").into(),
                        flag: value == true,
                        number: value.as_i64().unwrap_or(0) as i32,
                        input_text: value.to_string().into(),
                        minimum: f["min"].as_i64().unwrap_or(0) as i32,
                        maximum: f["max"].as_i64().unwrap_or(100) as i32,
                        step: f["step"].as_i64().unwrap_or(1) as i32,
                        selected_index: options.iter().position(|v| v[0] == *value).unwrap_or(0)
                            as i32,
                        option_labels: model(
                            options
                                .iter()
                                .map(|v| v[1].as_str().unwrap_or("").into())
                                .collect(),
                        ),
                        option_values: model(
                            options
                                .iter()
                                .map(|v| v[0].as_str().unwrap_or("").into())
                                .collect(),
                        ),
                        ..Default::default()
                    }
                })
                .collect(),
        ));
        ui.show()?;
        let width = if narrow { 720. } else { 1180. };
        ui.window()
            .set_size(LogicalSize::new(width, if narrow { 560. } else { 780. }));
        let list_width=if is_profile || scene=="device-info" {if narrow {224.} else {276.}}else{0.};
        ui.set_form_inset(((width - if narrow { 64. } else { 184. } - list_width - 800.) / 2.).max(24.));
        capture(&ui, out, &format!("form-{scene}-{size}-{theme}"), images)?;
        scroll_bottom(&ui, ui.global::<UiTest>(), "scroll|form")?;
        capture(
            &ui,
            out,
            &format!("form-{scene}-bottom-{size}-{theme}"),
            images,
        )?;
        ui.hide()?;
    }
    Ok(())
}

fn main() -> Result<()> {
    let out = std::env::args_os()
        .nth(1)
        .ok_or("A fresh output directory is required")?;
    let out = Path::new(&out);
    if out.exists() {
        return Err("The output directory already exists".into());
    }
    fs::create_dir(out)?;
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
        mock_time: true,
        threading: false,
        renderer_name: Some("software".into()),
    })))?;
    let mut images = Vec::new();
    for dark in [false, true] {
        for narrow in [false, true] {
            toolbars(out, dark, narrow, &mut images)?;
            pickers(out, dark, narrow, &mut images)?;
            connections(out, dark, narrow, &mut images)?;
            forms(out, dark, narrow, &mut images)?;
        }
    }
    fs::write(
        out.join("catalog.json"),
        serde_json::to_vec_pretty(
            &json!({"version":env!("CARGO_PKG_VERSION"),"visualOnly":true,"functionalVerification":false,"osWindowsCreated":false,"systemInputInjected":false,"remoteSessionStarted":false,"syncStarted":false,"updateStarted":false,"images":images}),
        )?,
    )?;
    println!(
        "Exported {} production component fixtures to {}",
        images.len(),
        out.display()
    );
    Ok(())
}
