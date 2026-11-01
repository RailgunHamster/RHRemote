use crate::{Commands, MainWindow, Palette, form_models};
use i_slint_backend_testing::{ElementHandle, TestingBackend, TestingBackendOptions};
use slint::{
    ComponentHandle, LogicalPosition, LogicalSize, Model,
    platform::{PointerEventButton, WindowEvent},
};
use std::{cell::RefCell, rc::Rc, time::Duration};

#[test]
fn a_held_conflict_choice_cannot_resolve_a_replacement_review(){
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{
        let ui=MainWindow::new().unwrap();ui.set_page("global-settings".into());ui.set_settings_tab("sync".into());ui.set_sync_has_password(true);ui.set_compact(width<980.);ui.global::<Palette>().set_dark(dark);ui.window().set_size(LogicalSize::new(width,height));
        let sent=Rc::new(RefCell::new(Vec::new()));let calls=sent.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));
        form_models::update_sync_conflicts(&ui,&serde_json::json!([{"key":"devices.host","label":"远程设备","local":"第一版","remote":"第二版"}]));ui.set_sync_review_id("review-one".into());ui.show().unwrap();render(&ui,&format!("review-intent-{width}-{dark}-first"));
        let element=button(&ui,"冲突项保留远端");let p=element.absolute_position();let size=element.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);visible_in_form(&ui,"冲突项保留远端");
        ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});ui.set_sync_review_id("review-two".into());render(&ui,&format!("review-intent-{width}-{dark}-changed"));ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});assert!(sent.borrow().is_empty(),"Old press selected newer data");
        click(&ui,"冲突项保留远端");assert_eq!(*sent.borrow(),vec![("sync.resolve".into(),"remote|review-two".into())]);let mapped=crate::args_for("sync.resolve","remote|review-one",&serde_json::json!({"sync":{"reviewId":"review-two"}}));assert_eq!(mapped,serde_json::json!({"resolution":"remote","reviewId":"review-one"}));
    }}
}

#[test]
fn actual_profile_differences_wrap_and_remain_scrollable_in_sync_review(){
    use std::collections::BTreeMap;
    use rhremote_core::{Profile,sync_model::{SharedConfig,SharedDevice,ClientInfo,ClientProfile}};
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let profile=Profile{bitrate_auto:true,bitrate_mbps:41,..Profile::default()};
    let local=SharedConfig{schema_version:2,devices:vec![SharedDevice{uuid:"review-host".into(),name:"工作电脑".into(),display_name:String::new(),address:"192.0.2.9".into(),addresses:vec!["100.64.0.1".into()],swap_buttons:false}],clients:BTreeMap::from([("pc".into(),ClientInfo{name:"台式机".into(),platform:"windows".into()})]),profiles:BTreeMap::from([("pc".into(),BTreeMap::from([("review-host".into(),ClientProfile::from_profile(&profile))]))]),dark:false};
    let mut remote=local.clone();let p=remote.profiles.get_mut("pc").unwrap().get_mut("review-host").unwrap();p.bitrate_auto=false;p.bitrate_mbps=6;p.codec="H.264".into();p.audio_enabled=true;p.volume=40;p.route_order=vec!["relay".into(),"lan".into()];remote.devices[0].addresses=vec!["100.64.0.2".into()];
    let items=crate::config_sync::conflict_items(&local,&remote,&["devices.review-host".into(),"profiles.pc.review-host".into()]);
    assert!(items[1]["local"].as_str().unwrap().contains("41 Mbps（手动值，未启用）"));
    for dark in [false,true]{for(width,height)in[(720.,560.),(1180.,780.)]{
        let ui=MainWindow::new().unwrap();ui.set_page("global-settings".into());ui.set_settings_tab("sync".into());ui.set_compact(width<980.);ui.set_dark(dark);ui.global::<Palette>().set_dark(dark);ui.set_sync_has_password(true);ui.window().set_size(LogicalSize::new(width,height));ui.show().unwrap();form_models::update_sync_conflicts(&ui,&serde_json::json!(items));
        let before=ui.get_sync_conflict_items();form_models::update_sync_conflicts(&ui,&serde_json::json!(items));let after=ui.get_sync_conflict_items();assert!(std::ptr::eq(before.as_any().downcast_ref::<slint::VecModel<crate::SyncConflictRow>>().unwrap(),after.as_any().downcast_ref::<slint::VecModel<crate::SyncConflictRow>>().unwrap()),"An unchanged review rebuilt its delegates");
        render(&ui,&format!("review-{width}-{dark}-top"));
        let viewport=ElementHandle::find_by_element_id(&ui,"MainWindow::form-scroll").next().unwrap();
        for side in ["local","remote"]{
            let key=if side=="local"{"bitrateMbps"}else{"routeOrder"};let field=items[1]["fields"].as_array().unwrap().iter().find(|field|field["key"]==key).unwrap();let wanted=format!("sync-review-{side}|profiles.pc.review-host|{key}");let mut found=false;
            for step in 0..30{
                ui.window().take_snapshot().unwrap();
                if let Some(text)=i_slint_backend_testing::ElementQuery::from_root(&ui).find_all().into_iter().find(|item|item.accessible_id().is_some_and(|id|id==wanted.as_str())){
                    assert!(text.size().height>16.,"Comparison collapsed");assert!(text.size().width<=viewport.size().width/2.);assert_eq!(text.accessible_label().unwrap().as_str(),field[side].as_str().unwrap());found=true;break;
                }
                viewport.scroll(0.,-100.);i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));assert!(step<29,"Missing {wanted}");
            }
            assert!(found);render(&ui,&format!("review-{width}-{dark}-{side}"));
        }
        viewport.scroll(0.,-100000.);render(&ui,&format!("review-{width}-{dark}-bottom"));
    }}
}

fn button(ui: &MainWindow, label: &str) -> ElementHandle {
    ElementHandle::find_by_accessible_label(ui, label)
        .next()
        .unwrap_or_else(|| panic!("Missing {label}"))
}
fn click(ui: &MainWindow, label: &str) {
    let element = button(ui, label);
    let p = element.absolute_position();
    let size = element.size();
    let position = LogicalPosition::new(p.x + size.width / 2., p.y + size.height / 2.);
    ui.window().dispatch_event(WindowEvent::PointerPressed {
        position,
        button: PointerEventButton::Left,
    });
    ui.window().dispatch_event(WindowEvent::PointerReleased {
        position,
        button: PointerEventButton::Left,
    });
}
fn visible_in_form(ui: &MainWindow, label: &str) {
    let element = button(ui, label);
    let p = element.absolute_position();
    let size = element.size();
    let form = ElementHandle::find_by_element_id(ui, "MainWindow::form-scroll")
        .next()
        .unwrap();
    let top = form.absolute_position().y;
    assert!(
        p.y >= top && p.y + size.height <= top + form.size().height,
        "{label} is clipped by the form viewport"
    );
    assert!(
        p.x >= 0. && p.x + size.width <= ui.window().size().width as f32,
        "{label} is clipped horizontally"
    );
}
fn render(ui: &MainWindow, name: &str) {
    let pixels = ui
        .window()
        .take_snapshot()
        .expect("Headless software rendering failed");
    assert_eq!(pixels.width(), ui.window().size().width);
    assert_eq!(pixels.height(), ui.window().size().height);
    assert!(
        pixels
            .as_bytes()
            .chunks_exact(4)
            .any(|p| p != &pixels.as_bytes()[..4]),
        "Empty software frame"
    );
    let surface = ui.global::<Palette>().get_surface();
    let edge = pixels.as_slice()[pixels.width() as usize * (pixels.height() as usize / 2) + 5];
    assert_eq!(
        [edge.r, edge.g, edge.b],
        [surface.red(), surface.green(), surface.blue()],
        "The frame does not use the application's actual theme palette"
    );
    if let Some(directory) = std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR") {
        let root = std::path::PathBuf::from(directory);
        assert!(root.is_absolute() && root.is_dir());
        let path = root.join(format!("{name}.png"));
        assert!(!path.exists(), "Never overwrite an earlier frame");
        image::save_buffer(
            path,
            pixels.as_bytes(),
            pixels.width(),
            pixels.height(),
            image::ColorType::Rgba8,
        )
        .unwrap();
    }
}

#[test]
fn software_rendered_sync_controls_keep_their_scope_and_completion_returns_to_the_result() {
    // A TestingWindow has a real software renderer and no native OS window.
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
        mock_time: true,
        threading: false,
        renderer_name: Some("software".into()),
    })))
    .unwrap();
    let ui = MainWindow::new().unwrap();
    ui.set_page("global-settings".into());
    ui.set_settings_tab("sync".into());
    ui.set_sync_has_password(true);
    ui.set_sync_password_reusable(true);
    ui.set_sync_url("https://example.test/dav/".into());
    ui.set_sync_username("fixture".into());
    ui.set_sync_last("上次同步：2026-10-28 00:00".into());
    let received = Rc::new(RefCell::new(Vec::new()));
    let calls = received.clone();
    ui.global::<Commands>()
        .on_trigger(move |id, arg| calls.borrow_mut().push((id.to_string(), arg.to_string())));
    ui.show().unwrap();
    let items = serde_json::json!([{"key":"devices.fixture","label":"远程设备：工作电脑","local":"本机名称 · 192.0.2.1","remote":"远端名称 · 192.0.2.2"},{"key":"profiles.pc.fixture","label":"连接配置：台式机 → 工作电脑","local":"1920 × 1080 · 30 FPS · 25 Mbps","remote":"1920 × 1080 · 30 FPS · 40 Mbps"}]);
    for dark in [false, true] {
        for (width, height, compact) in [(1180., 780., false), (720., 560., true)] {
            ui.set_dark(dark);
            ui.global::<Palette>().set_dark(dark);
            ui.set_compact(compact);
            ui.window().set_size(LogicalSize::new(width, height));
            ui.set_sync_busy(false);
            ui.set_sync_error("".into());
            form_models::update_sync_conflicts(&ui, &items);
            let tag = format!(
                "sync-{}-{}",
                if compact { "compact" } else { "wide" },
                if dark { "dark" } else { "light" }
            );
            render(&ui, &format!("{tag}-review"));
            for label in ["重新同步", "冲突项保留本机", "冲突项保留远端"] {
                visible_in_form(&ui, label);
            }
            received.borrow_mut().clear();
            click(&ui, "冲突项保留远端");
            assert_eq!(
                &*received.borrow(),
                &[("sync.resolve".into(), "remote".into())]
            );
            ui.set_sync_busy(true);
            received.borrow_mut().clear();
            for label in ["同步中…", "冲突项保留本机", "冲突项保留远端"] {
                click(&ui, label);
            }
            assert!(
                received.borrow().is_empty(),
                "Disabled sync controls dispatched an action"
            );
            render(&ui, &format!("{tag}-running"));
            let form = ElementHandle::find_by_element_id(&ui, "MainWindow::form-scroll")
                .next()
                .unwrap();
            form.scroll(0., -100000.);
            i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
            ui.set_sync_busy(false);
            i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
            visible_in_form(&ui, "重新同步");
            form_models::update_sync_conflicts(&ui, &serde_json::json!([]));
            ui.set_sync_error("WebDAV 证书或 TLS 连接验证失败，请检查证书和地址".into());
            render(&ui, &format!("{tag}-failed"));
            visible_in_form(&ui, "立即同步");
            let save = button(&ui, "保存");
            assert!(
                save.absolute_position().y + save.size().height <= height,
                "The fixed save footer is clipped"
            );
        }
    }
    ui.hide().unwrap();
}
