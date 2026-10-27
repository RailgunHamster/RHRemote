//! Real Controller, ConfigSync acknowledgement, preference files and Slint controls.
//! Only the received HTTP result and device discovery are substituted; no sockets or vault.
use crate::{Commands, MainWindow, args_for, config_sync::ConfigSync, view_state::MainView};
use i_slint_backend_testing::{
    AccessibleRole, ElementHandle, TestingBackend, TestingBackendOptions,
};
use rhremote_core::{Controller, Device, Platform, Saved, sync_model::SharedConfig};
use serde_json::{Value, json};
use slint::{
    ComponentHandle, LogicalPosition, LogicalSize,
    platform::{PointerEventButton, WindowEvent},
};
use std::{
    cell::RefCell,
    path::PathBuf,
    rc::Rc,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};

struct Boundary {
    sync: Mutex<ConfigSync>,
    remote: SharedConfig,
    directory: PathBuf,
    block_record: AtomicBool,
    gate: Mutex<Option<(mpsc::Sender<()>, mpsc::Receiver<()>)>>,
}
impl Platform for Boundary {
    fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        match id {
            "sync.state" => Ok(self.sync.lock().unwrap().state()),
            "updates.state" => Ok(json!({"managed":false})),
            "devices.restore" => Ok(args.clone()),
            "sync.run" => {
                let result = self
                    .sync
                    .lock()
                    .unwrap()
                    .transferred_for_test(self.remote.clone())?;
                if self.block_record.load(Ordering::SeqCst) {
                    let path = self.directory.join("webdav-local.pending.json");
                    std::fs::create_dir(&path).unwrap();
                    std::fs::write(path.join("blocker"), b"test-owned blocker").unwrap();
                }
                Ok(result)
            }
            _ => Err(format!(
                "No external operation in the commit UI fixture: {id}"
            )),
        }
    }
    fn sync_applied(
        &self,
        shared: &SharedConfig,
        preferences: &std::path::Path,
    ) -> Result<Option<Value>, String> {
        if let Some((entered, release)) = &*self.gate.lock().unwrap() {
            entered.send(()).unwrap();
            release.recv_timeout(Duration::from_secs(5)).unwrap();
        }
        self.sync
            .lock()
            .unwrap()
            .applied(shared, preferences)
            .map(Some)
    }
}
struct Flow {
    ui: MainWindow,
    view: MainView,
    controller: Arc<Controller>,
    boundary: Arc<Boundary>,
    directory: PathBuf,
    pending: Rc<RefCell<Vec<(String, String)>>>,
}
impl Flow {
    fn new(width: f32, height: f32, dark: bool) -> Self {
        let directory = std::env::temp_dir().join(format!(
            "rhremote-sync-commit-ui-{}",
            rhremote_core::random_token().unwrap()
        ));
        std::fs::create_dir(&directory).unwrap();
        let mut base = ConfigSync::test_configuration();
        base.dark = dark;
        let saved = Saved {
            client_id: "pc".into(),
            profiles: base.profiles["pc"]
                .iter()
                .map(|(key, value)| (key.clone(), value.to_profile()))
                .collect(),
            devices: base.devices.clone(),
            clients: base.clients.clone(),
            client_profiles: base.profiles.clone(),
            dark,
            profile_schema_version: 2,
            relay_defaults_version: 1,
            device_order_initialized: true,
            ..Default::default()
        };
        std::fs::write(
            directory.join("preferences.json"),
            serde_json::to_vec(&saved).unwrap(),
        )
        .unwrap();
        std::fs::write(directory.join("webdav-local.json"),serde_json::to_vec(&json!({"url":"https://example.test/dav/","username":"fixture","credentialId":"test-only-not-in-vault","automatic":true,"intervalMinutes":5,"base":base,"lastSyncMs":1})).unwrap()).unwrap();
        let mut remote = base.clone();
        remote.devices[0].display_name = "远端新名".into();
        let boundary = Arc::new(Boundary {
            sync: Mutex::new(ConfigSync::new(directory.clone()).unwrap()),
            remote,
            directory: directory.clone(),
            block_record: AtomicBool::new(false),
            gate: Mutex::new(None),
        });
        let controller = Controller::new(
            "windows",
            directory.join("preferences.json"),
            boundary.clone(),
        )
        .unwrap();
        controller.replace_devices(vec![Device {
            uuid: "self".into(),
            name: "PC".into(),
            display_name: String::new(),
            address: "127.0.0.1".into(),
            addresses: vec![],
            online: false,
            paired: false,
            is_self: false,
        }]);
        controller.dispatch("app.settings.tab", json!({"tab":"sync"}));
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
            directory,
            pending,
        };
        flow.refresh();
        flow
    }
    fn refresh(&mut self) {
        self.view.refresh(&self.ui, &self.controller.state(), |id| {
            self.controller.profile(id)
        });
        i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
        self.ui.window().take_snapshot().unwrap();
    }
    fn button(&self, label: &str) -> ElementHandle {
        let values: Vec<_> = ElementHandle::find_by_accessible_label(&self.ui, label)
            .filter(|value| value.accessible_role() == Some(AccessibleRole::Button))
            .collect();
        assert_eq!(values.len(), 1, "One button {label}");
        values.into_iter().next().unwrap()
    }
    fn click(&mut self, label: &str) -> Value {
        let button = self.button(label);
        let position = button.absolute_position();
        let size = button.size();
        assert!(
            position.x >= 0.
                && position.y >= 0.
                && position.x + size.width <= self.ui.window().size().width as f32
                && position.y + size.height <= self.ui.window().size().height as f32
        );
        let form = ElementHandle::find_by_element_id(&self.ui, "MainWindow::form-scroll")
            .next()
            .unwrap();
        assert!(
            position.y >= form.absolute_position().y
                && position.y + size.height <= form.absolute_position().y + form.size().height
        );
        let at = LogicalPosition::new(position.x + size.width / 2., position.y + size.height / 2.);
        for event in [
            WindowEvent::PointerPressed {
                position: at,
                button: PointerEventButton::Left,
            },
            WindowEvent::PointerReleased {
                position: at,
                button: PointerEventButton::Left,
            },
        ] {
            self.ui.window().dispatch_event(event);
        }
        let emitted = std::mem::take(&mut *self.pending.borrow_mut());
        assert_eq!(emitted, vec![("sync.run".into(), String::new())]);
        let result = self.controller.dispatch(
            &emitted[0].0,
            args_for(&emitted[0].0, &emitted[0].1, &self.controller.state()),
        );
        self.refresh();
        result
    }
    fn capture(&self, name: &str) {
        if let Some(root) = std::env::var_os("RHREMOTE_UI_EVIDENCE_DIR") {
            let pixels = self.ui.window().take_snapshot().unwrap();
            let path = PathBuf::from(root).join(format!("sync-commit-{name}.png"));
            assert!(!path.exists());
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
    fn metadata(&self) -> Value {
        serde_json::from_slice(&std::fs::read(self.directory.join("webdav-local.json")).unwrap())
            .unwrap()
    }
}
impl Drop for Flow {
    fn drop(&mut self) {
        self.ui.hide().unwrap();
        let path = self.directory.canonicalize().unwrap();
        let temp = std::env::temp_dir().canonicalize().unwrap();
        assert!(path.starts_with(&temp) && path != temp);
        std::fs::remove_dir_all(path).unwrap();
    }
}
fn setup() {
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
        mock_time: true,
        threading: false,
        renderer_name: Some("software".into()),
    })))
    .unwrap();
}
#[test]
fn sync_commit_file_failures_and_retry_use_actual_controls_and_metadata() {
    setup();
    for (width, height) in [(1200., 820.), (760., 640.)] {
        for dark in [false, true] {
            for record in [false, true] {
                let mut flow = Flow::new(width, height, dark);
                let kind = if record { "record" } else { "apply" };
                if record {
                    flow.boundary.block_record.store(true, Ordering::SeqCst);
                } else {
                    std::fs::write(flow.directory.join("sync-backups"), b"test-owned blocker")
                        .unwrap();
                }
                assert_eq!(flow.click("立即同步")["ok"], false);
                let state = flow.controller.state();
                assert_eq!(state["syncBusy"], false);
                assert_eq!(state["sync"]["automaticBlocked"], true);
                assert!(!state["syncError"].as_str().unwrap().is_empty());
                assert_ne!(state["status"], "同步完成");
                assert_eq!(flow.metadata()["lastSyncMs"], 1);
                assert_eq!(flow.metadata()["base"]["devices"][0]["displayName"], "");
                assert_eq!(flow.metadata()["pendingApply"], true);
                assert_eq!(flow.controller.automatic_sync_due(u64::MAX, 0), false);
                flow.capture(&format!("{kind}-error-{width}-{dark}"));
                if record {
                    let path = flow.directory.join("webdav-local.pending.json");
                    std::fs::remove_file(path.join("blocker")).unwrap();
                    std::fs::remove_dir(path).unwrap();
                    flow.boundary.block_record.store(false, Ordering::SeqCst);
                } else {
                    std::fs::remove_file(flow.directory.join("sync-backups")).unwrap();
                }
                assert_eq!(flow.click("立即同步")["ok"], true);
                assert_eq!(flow.controller.state()["syncError"], "");
                assert_eq!(flow.controller.state()["status"], "同步完成");
                assert_eq!(
                    flow.metadata()["base"]["devices"][0]["displayName"],
                    "远端新名"
                );
                assert!(flow.metadata()["lastSyncMs"].as_u64().unwrap() > 1);
                assert_eq!(flow.metadata()["pendingApply"], false);
                flow.capture(&format!("{kind}-recovered-{width}-{dark}"));
            }
        }
    }
}
#[test]
fn sync_commit_pending_acknowledgement_keeps_actual_controls_busy() {
    setup();
    for (width, height) in [(1200., 820.), (760., 640.)] {
        for dark in [false, true] {
            let mut flow = Flow::new(width, height, dark);
            let (entered_tx, entered_rx) = mpsc::channel();
            let (release_tx, release_rx) = mpsc::channel();
            *flow.boundary.gate.lock().unwrap() = Some((entered_tx, release_rx));
            let controller = flow.controller.clone();
            let worker = std::thread::spawn(move || controller.dispatch("sync.run", json!({})));
            entered_rx.recv_timeout(Duration::from_secs(5)).unwrap();
            flow.refresh();
            assert_eq!(flow.ui.get_sync_busy(), true);
            assert_eq!(flow.ui.get_sync_progress_text().as_str(), "正在保存…");
            assert_eq!(flow.metadata()["lastSyncMs"], 1);
            assert_eq!(flow.controller.state()["sync"]["phase"], "recording");
            assert_eq!(flow.button("正在保存…").accessible_enabled(), Some(false));
            flow.capture(&format!("recording-{width}-{dark}"));
            assert_eq!(flow.controller.dispatch("sync.run", json!({}))["ok"], false);
            release_tx.send(()).unwrap();
            assert_eq!(worker.join().unwrap()["ok"], true);
            flow.refresh();
            assert_eq!(flow.ui.get_sync_busy(), false);
            assert_eq!(flow.controller.state()["sync"]["automaticBlocked"], false);
        }
    }
}
