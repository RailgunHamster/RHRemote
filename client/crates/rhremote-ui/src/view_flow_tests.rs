//! The real Controller, command argument mapping and view binding, without native windows.
use crate::{Commands, MainWindow, args_for, view_state::MainView};
use i_slint_backend_testing::{
    AccessibleRole, ElementHandle, TestingBackend, TestingBackendOptions,
};
use rhremote_core::{Controller, Device, Platform, Profile, Saved, random_token, sync_model};
use serde_json::{Value, json};
use slint::{
    ComponentHandle, LogicalPosition, LogicalSize, Model,
    platform::{PointerEventButton, WindowEvent},
};
use std::{
    cell::RefCell,
    collections::BTreeMap,
    path::PathBuf,
    rc::Rc,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

/// Only the external boundary is substituted. No sockets, vault or production data are used.
struct Boundary {
    sync: Mutex<Value>,
    fail_save: AtomicBool,
    calls: Mutex<Vec<String>>,
}
impl Default for Boundary {
    fn default() -> Self {
        Self {
            sync: Mutex::new(
                json!({"url":"https://example.test/dav/","username":"fixture","hasPassword":true,"automatic":false,"intervalMinutes":15}),
            ),
            fail_save: AtomicBool::new(false),
            calls: Mutex::new(Vec::new()),
        }
    }
}
impl Platform for Boundary {
    fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        match id {
            "sync.state" => Ok(self.sync.lock().unwrap().clone()),
            "updates.state" => Ok(json!({"managed":false,"phase":"idle"})),
            "devices.restore" => Ok(json!({"devices":args["devices"]})),
            "sync.configure" => {
                self.calls.lock().unwrap().push(id.into());
                if self.fail_save.load(Ordering::SeqCst) {
                    return Err("WebDAV 地址无效".into());
                }
                let mut saved = args.clone();
                saved.as_object_mut().unwrap().remove("password");
                saved["hasPassword"] = json!(true);
                *self.sync.lock().unwrap() = saved.clone();
                Ok(saved)
            }
            "sync.clear" => {
                self.calls.lock().unwrap().push(id.into());
                *self.sync.lock().unwrap() = json!({"url":"","username":"","hasPassword":false,"automatic":false,"intervalMinutes":5});
                Ok(self.sync.lock().unwrap().clone())
            }
            _ => Err(format!("Unexpected external operation in view flow: {id}")),
        }
    }
}
struct Flow {
    ui: MainWindow,
    view: MainView,
    controller: Arc<Controller>,
    boundary: Arc<Boundary>,
    pending: Rc<RefCell<Vec<(String, String)>>>,
    directory: PathBuf,
    storage: PathBuf,
}
impl Flow {
    fn new(width: f32, height: f32) -> Self {
        let directory =
            std::env::temp_dir().join(format!("rhremote-view-flow-{}", random_token().unwrap()));
        std::fs::create_dir(&directory).unwrap();
        let storage = directory.join("preferences.json");
        let source = Profile {
            width: 1600,
            height: 900,
            bitrate_mbps: 35,
            ..Default::default()
        };
        let saved = Saved {
            client_id: "fixture-windows".into(),
            profile_schema_version: 2,
            device_order_initialized: true,
            clients: BTreeMap::from([
                (
                    "fixture-windows".into(),
                    sync_model::ClientInfo {
                        name: "Windows".into(),
                        platform: "windows".into(),
                    },
                ),
                (
                    "fixture-pad".into(),
                    sync_model::ClientInfo {
                        name: "平板".into(),
                        platform: "android".into(),
                    },
                ),
            ]),
            client_profiles: BTreeMap::from([(
                "fixture-pad".into(),
                BTreeMap::from([(
                    "host".into(),
                    sync_model::ClientProfile::from_profile(&source),
                )]),
            )]),
            ..Default::default()
        };
        std::fs::write(&storage, serde_json::to_vec(&saved).unwrap()).unwrap();
        let boundary = Arc::new(Boundary::default());
        let controller = Controller::new("windows", storage.clone(), boundary.clone()).unwrap();
        let devices:Vec<Device>=serde_json::from_value(json!([
            {"uuid":"host","name":"winserver","address":"192.0.2.1","addresses":["100.64.0.1"],"online":true,"paired":true},
            {"uuid":"self","name":"Local PC","address":"192.0.2.2","online":true,"paired":true,"isSelf":true},
            {"uuid":"claw","name":"claw","address":"192.0.2.3","online":false,"paired":true}
        ])).unwrap();
        controller.replace_devices(devices);
        let ui = MainWindow::new().unwrap();
        ui.window().set_size(LogicalSize::new(width, height));
        ui.set_app_version(env!("CARGO_PKG_VERSION").into());
        let view = MainView::new(&ui);
        let pending = Rc::new(RefCell::new(Vec::new()));
        let queue = pending.clone();
        ui.global::<Commands>()
            .on_trigger(move |id, arg| queue.borrow_mut().push((id.to_string(), arg.to_string())));
        ui.show().unwrap();
        let mut flow = Self {
            ui,
            view,
            controller,
            boundary,
            pending,
            directory,
            storage,
        };
        flow.refresh();
        flow
    }
    fn refresh(&mut self) {
        self.view.refresh(&self.ui, &self.controller.state(), |id| {
            self.controller.profile(id)
        });
        i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
        // Materialize the actual conditional controls before querying or interacting.
        self.ui.window().take_snapshot().unwrap();
        assert_eq!(
            self.ui.get_page().as_str(),
            self.controller.state()["page"].as_str().unwrap()
        );
    }
    fn command(&mut self, id: &str, arg: &str) -> Value {
        let state = self.controller.state();
        let result = self.controller.dispatch(id, args_for(id, arg, &state));
        self.refresh();
        result
    }
    fn input(&mut self, id: &str, arg: &str) {
        let result = self.command(id, arg);
        assert_eq!(result["ok"], true, "{id}: {result}");
    }
    fn button(&self, label: &str, id: &str, arg: &str) -> ElementHandle {
        let action = format!("action|{id}|{arg}|");
        let controls: Vec<_> = ElementHandle::find_by_accessible_label(&self.ui, label)
            .filter(|e| {
                e.accessible_role() == Some(AccessibleRole::Button)
                    && e.accessible_id()
                        .is_some_and(|value| value.starts_with(&action))
            })
            .collect();
        assert_eq!(controls.len(), 1, "Expected one button {label}");
        controls.into_iter().next().unwrap()
    }
    fn click(&mut self, label: &str, id: &str, arg: &str) -> Value {
        let element = self.button(label, id, arg);
        self.activate(element, label, id, arg)
    }
    fn choose_device(&mut self, uuid: &str) -> Value {
        let key = format!("device|{uuid}");
        let element = ElementHandle::find_by_accessible_label(
            &self.ui,
            if uuid == "host" {
                "选择设备 winserver"
            } else {
                "选择设备 claw"
            },
        )
        .find(|e| e.accessible_id().is_some_and(|id| id == key.as_str()))
        .unwrap();
        self.activate(element, uuid, "device.settings.open", uuid)
    }
    fn activate(&mut self, element: ElementHandle, label: &str, id: &str, arg: &str) -> Value {
        let p = element.absolute_position();
        let s = element.size();
        let window = self.ui.window().size();
        assert!(
            p.x >= 0.
                && p.y >= 0.
                && p.x + s.width <= window.width as f32
                && p.y + s.height <= window.height as f32,
            "{label} outside window: {p:?} {s:?}"
        );
        assert!(self.pending.borrow().is_empty());
        let position = LogicalPosition::new(p.x + s.width / 2., p.y + s.height / 2.);
        for event in [
            WindowEvent::PointerPressed {
                position,
                button: PointerEventButton::Left,
            },
            WindowEvent::PointerReleased {
                position,
                button: PointerEventButton::Left,
            },
        ] {
            self.ui.window().dispatch_event(event);
        }
        let emitted = std::mem::take(&mut *self.pending.borrow_mut());
        assert_eq!(
            emitted,
            vec![(id.to_string(), arg.to_string())],
            "Wrong or duplicate shared action for {label}"
        );
        self.command(&emitted[0].0, &emitted[0].1)
    }
    fn capture(&self, name: &str) {
        if let Some(root) = std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR") {
            let frame = self.ui.window().take_snapshot().unwrap();
            let path = PathBuf::from(root).join(format!("flow-{name}.png"));
            assert!(!path.exists(), "Never replace earlier evidence");
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
    fn saved(&self) -> Vec<u8> {
        std::fs::read(&self.storage).unwrap()
    }
}
impl Drop for Flow {
    fn drop(&mut self) {
        let _ = self.ui.hide();
        let root = std::env::temp_dir().canonicalize().unwrap();
        let target = self.directory.canonicalize().unwrap();
        assert!(
            target.parent() == Some(root.as_path())
                && target
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .starts_with("rhremote-view-flow-")
        );
        std::fs::remove_dir_all(target).unwrap();
    }
}
fn backend() {
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
        mock_time: true,
        threading: false,
        renderer_name: Some("software".into()),
    })))
    .unwrap();
}

#[test]
fn controller_bound_forms_save_validate_cancel_and_copy_through_real_controls() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        let mut f = Flow::new(width, height);
        f.input("devices.filter", "100.64.0.1");
        assert_eq!(f.ui.get_devices().row_count(), 1);
        assert_eq!(f.ui.get_selected_uuid(), "host");
        f.click("连接设置", "device.settings.open", "host");
        let original = f.saved();
        f.input("settings.input", "fps=unfinished");
        f.click("网络", "settings.tab", "network");
        f.input("settings.network.edit", "addresses=100.64.0.1, 192.0.2.9");
        assert_eq!(f.saved(), original);
        assert_eq!(f.click("保存", "settings.save", "")["ok"], false);
        assert_eq!(f.ui.get_profile_tab(), "video");
        assert!(!f.ui.get_settings_error().is_empty());
        assert_eq!(f.controller.state()["settingsInputs"]["fps"], "unfinished");
        assert_eq!(f.saved(), original);
        f.capture(&format!("{tag}-invalid-profile"));
        f.click("概览", "device.select", "host");
        assert!(f.ui.get_navigation_prompt());
        f.click("继续编辑", "navigation.resolve", "stay");
        assert!(!f.ui.get_navigation_prompt());
        f.input("settings.input", "fps=24");
        f.click("概览", "device.select", "host");
        f.click("保存并继续", "navigation.resolve", "save");
        assert_eq!(f.ui.get_page(), "devices");
        assert_eq!(f.ui.get_device_query(), "100.64.0.1");
        assert_eq!(f.controller.profile("host").fps, 24);
        assert_eq!(
            f.controller.state()["saved"]["devices"][0]["addresses"],
            json!(["100.64.0.1", "192.0.2.9"])
        );
        let saved = f.saved();
        f.click("连接设置", "device.settings.open", "host");
        f.click("高级", "settings.tab", "advanced");
        f.click("从其他客户端复制配置", "settings.sources.toggle", "");
        f.click("查看配置", "settings.copy.preview", "fixture-pad");
        assert_eq!(f.ui.get_page(), "profile-copy");
        assert!(f.ui.get_copy_source().starts_with("平板 ·"));
        f.capture(&format!("{tag}-copy-preview"));
        f.click("复制到草稿", "settings.copy.apply", "");
        assert_eq!(f.ui.get_page(), "settings");
        assert!(f.ui.get_settings_dirty());
        assert_eq!(f.ui.get_profile_width(), 1600);
        assert_eq!(f.controller.profile("host").fps, 24);
        assert_eq!(f.saved(), saved);
        f.click("取消", "settings.cancel", "");
        assert_eq!(f.saved(), saved);
        f.click("连接设置", "device.settings.open", "host");
        f.input("settings.input", "fps=25");
        f.click("概览", "device.select", "host");
        f.click("放弃更改", "navigation.resolve", "discard");
        assert_eq!(f.saved(), saved);
        assert_eq!(f.ui.get_selected_uuid(), "host");
        let restarted = Controller::new("windows", f.storage.clone(), f.boundary.clone()).unwrap();
        assert_eq!(restarted.profile("host").fps, 24);
        f.capture(&format!("{tag}-restored-devices"));
    }
}

#[test]
fn controller_bound_sync_keeps_failed_input_and_separates_cancel_from_remove() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        let mut f = Flow::new(width, height);
        f.input("app.settings.tab", "sync");
        let before = f.boundary.sync.lock().unwrap().clone();
        let prefs = f.saved();
        f.input("sync.edit", "url=http://example.test/dav/new/");
        f.input("sync.edit", "password=fixture=only");
        f.input("sync.edit", "intervalMinutes=60");
        assert!(!f.controller.state().to_string().contains("fixture=only"));
        f.click("更新", "app.settings.tab", "updates");
        assert!(f.ui.get_navigation_prompt());
        f.click("继续编辑", "navigation.resolve", "stay");
        f.boundary.fail_save.store(true, Ordering::SeqCst);
        assert_eq!(f.click("保存", "sync.save", "")["ok"], false);
        assert_eq!(f.ui.get_sync_url(), "http://example.test/dav/new/");
        assert_eq!(f.ui.get_sync_error(), "WebDAV 地址无效");
        assert_eq!(*f.boundary.sync.lock().unwrap(), before);
        assert_eq!(f.saved(), prefs);
        f.capture(&format!("{tag}-sync-failed"));
        f.click("取消", "sync.cancel", "");
        assert_eq!(f.ui.get_sync_url(), "https://example.test/dav/");
        assert!(!f.ui.get_sync_dirty());
        assert!(f.ui.get_sync_error().is_empty());
        f.input("sync.edit", "username=unfinished");
        f.click("移除同步设置", "sync.clear", "");
        assert!(f.ui.get_sync_clear_prompt());
        f.click("取消", "sync.clear.resolve", "cancel");
        assert!(!f.ui.get_sync_clear_prompt());
        assert_eq!(f.ui.get_sync_username(), "unfinished");
        assert_eq!(*f.boundary.sync.lock().unwrap(), before);
        f.boundary.fail_save.store(false, Ordering::SeqCst);
        f.click("保存", "sync.save", "");
        assert!(!f.ui.get_sync_dirty());
        assert_eq!(f.boundary.sync.lock().unwrap()["username"], "unfinished");
        f.capture(&format!("{tag}-sync-saved"));
        assert_eq!(
            f.saved(),
            prefs,
            "Sync credentials must not enter shared preferences"
        );
    }
}

#[test]
fn changing_category_starts_at_the_top_and_retains_the_controller_draft() {
    backend();
    let mut f = Flow::new(720., 560.);
    f.click("连接设置", "device.settings.open", "host");
    f.input("settings.input", "fps=24");
    assert!(
        ElementHandle::find_by_accessible_label(&f.ui, "画面宽度")
            .next()
            .is_some()
    );
    let form = ElementHandle::find_by_element_id(&f.ui, "MainWindow::form-scroll")
        .next()
        .unwrap();
    form.scroll(0., -100000.);
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
    f.refresh();
    assert!(
        ElementHandle::find_by_accessible_label(&f.ui, "画面宽度")
            .next()
            .is_none(),
        "The fixture must actually scroll the first field outside the viewport"
    );
    f.input("settings.input", "fps=25");
    assert!(
        ElementHandle::find_by_accessible_label(&f.ui, "画面宽度")
            .next()
            .is_none(),
        "Editing the current draft must retain its scroll position"
    );
    f.capture("compact-scrolled-video");
    f.click("网络", "settings.tab", "network");
    assert_eq!(f.ui.get_profile_tab(), "network");
    f.capture("compact-network-category");
    let editor_before = ElementHandle::find_by_accessible_label(&f.ui, "首选 IP 或主机名").next();
    let address = match editor_before {
        Some(editor) => editor,
        None => {
            form.scroll(0., 100000.);
            f.refresh();
            assert!(
                ElementHandle::find_by_accessible_label(&f.ui, "首选 IP 或主机名")
                    .next()
                    .is_some(),
                "The network category did not create its address editor"
            );
            f.capture("compact-network-manually-scrolled-top");
            panic!(
                "The new category retained the old scroll offset; scrolling back to the top restored its address editor"
            );
        }
    };
    assert!(
        address.absolute_position().y >= form.absolute_position().y,
        "New category retained the previous scroll offset: address {:?}, form {:?}",
        address.absolute_position(),
        form.absolute_position()
    );
    assert_eq!(f.controller.state()["settingsInputs"]["fps"], "25");
    f.click("画面", "settings.tab", "video");
    assert!(
        ElementHandle::find_by_accessible_label(&f.ui, "画面宽度")
            .next()
            .is_some()
    );
    assert_eq!(f.controller.state()["settingsInputs"]["fps"], "25");
}

#[test]
fn device_settings_keep_the_list_and_guard_cross_device_drafts() {
    backend();
    for (width, height, tag) in [(1180., 780., "wide"), (720., 560., "compact")] {
        for dark in [false, true] {
            let mut f = Flow::new(width, height);
            f.ui.global::<crate::Palette>().set_dark(dark);
            f.click("连接设置", "device.settings.open", "host");
            f.click("音频", "settings.tab", "audio");
            let before = f.saved();
            f.input("settings.input", "volume=42");
            f.choose_device("claw");
            assert!(f.ui.get_navigation_prompt());
            f.click("继续编辑", "navigation.resolve", "stay");
            assert_eq!(f.ui.get_selected_uuid(), "host");
            assert_eq!(f.controller.state()["settingsInputs"]["volume"], "42");
            assert_eq!(f.saved(), before);
            f.choose_device("claw");
            f.click("放弃更改", "navigation.resolve", "discard");
            assert_eq!(f.ui.get_page(), "settings");
            assert_eq!(f.ui.get_selected_uuid(), "claw");
            assert_eq!(f.ui.get_profile_tab(), "audio");
            assert!(!f.ui.get_settings_dirty());
            assert_eq!(f.saved(), before);
            f.capture(&format!("navigation-{tag}-{dark}-claw-audio"));
            f.input("settings.input", "volume=75");
            f.choose_device("host");
            f.click("保存并继续", "navigation.resolve", "save");
            assert_eq!(f.ui.get_selected_uuid(), "host");
            assert_eq!(f.ui.get_profile_tab(), "audio");
            assert_eq!(f.controller.profile("claw").volume, 75);
            assert_eq!(f.controller.profile("host").volume, 100);
            let saved = f.saved();
            f.input("settings.input", "volume=unfinished");
            f.choose_device("claw");
            assert_eq!(
                f.click("保存并继续", "navigation.resolve", "save")["ok"],
                false
            );
            assert_eq!(f.ui.get_selected_uuid(), "host");
            assert_eq!(
                f.controller.state()["settingsInputs"]["volume"],
                "unfinished"
            );
            assert!(!f.ui.get_settings_error().is_empty());
            assert_eq!(f.saved(), saved);
            f.click("概览", "device.select", "host");
            f.click("放弃更改", "navigation.resolve", "discard");
            assert_eq!(f.ui.get_page(), "devices");
            assert_eq!(f.ui.get_selected_uuid(), "host");
            assert_eq!(f.saved(), saved);
        }
    }
}
