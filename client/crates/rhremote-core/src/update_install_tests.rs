//! Preparation, draft decisions and final update admission through the real controller.
use crate::{Controller, Platform, Saved, random_token};
use serde_json::{Value, json};
use std::{
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    thread::JoinHandle,
    time::Duration,
};

struct Boundary {
    first: AtomicBool,
    entered: mpsc::Sender<()>,
    released: Mutex<mpsc::Receiver<()>>,
    apply_gate: Mutex<Option<(mpsc::Sender<()>, mpsc::Receiver<()>)>>,
    calls: Mutex<Vec<String>>,
    fail_prepare: AtomicBool,
    fail_apply: AtomicBool,
    sync: Mutex<Value>,
}
impl Platform for Boundary {
    fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        match id {
            "sync.state" => return Ok(self.sync.lock().unwrap().clone()),
            "sync.configure" => {
                let mut value = args.clone();
                value.as_object_mut().unwrap().remove("password");
                value["hasPassword"] = json!(true);
                *self.sync.lock().unwrap() = value.clone();
                return Ok(value);
            }
            "updates.state" => {
                return Ok(json!({"managed":true,"phase":"available","availableVersion":"9.0.0"}));
            }
            "devices.restore" => return Ok(json!({"devices":args["devices"]})),
            "device.connect" => return Ok(json!({"accepted":true})),
            "updates.prepare" | "updates.install" => {}
            _ => return Err(format!("Unexpected external operation: {id}")),
        }
        self.calls.lock().unwrap().push(id.into());
        if id == "updates.prepare" && self.first.swap(false, Ordering::SeqCst) {
            self.entered.send(()).unwrap();
            self.released
                .lock()
                .unwrap()
                .recv_timeout(Duration::from_secs(5))
                .unwrap();
        }
        if id == "updates.prepare" && self.fail_prepare.load(Ordering::SeqCst) {
            return Err("fixture download failure".into());
        }
        if id == "updates.install" && self.fail_apply.load(Ordering::SeqCst) {
            return Err("fixture updater failure".into());
        }
        if id == "updates.install" {
            if let Some((entered, released)) = self.apply_gate.lock().unwrap().take() {
                entered.send(()).unwrap();
                released.recv_timeout(Duration::from_secs(5)).unwrap();
            }
        }
        Ok(
            json!({"managed":true,"availableVersion":"9.0.0","pendingRestart":true,
            "phase":if id == "updates.prepare" {"downloaded"} else {"applying"}}),
        )
    }
}

struct Flow {
    controller: Arc<Controller>,
    boundary: Arc<Boundary>,
    release: Option<mpsc::Sender<()>>,
    worker: Option<JoinHandle<Value>>,
    path: PathBuf,
}
impl Flow {
    fn preparing() -> Self {
        let (entered, ready) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let boundary = Arc::new(Boundary {
            first: AtomicBool::new(true),
            entered,
            released: Mutex::new(released),
            apply_gate: Mutex::new(None),
            calls: Mutex::new(Vec::new()),
            fail_prepare: AtomicBool::new(false),
            fail_apply: AtomicBool::new(false),
            sync: Mutex::new(
                json!({"url":"https://example.test/dav/","username":"fixture","hasPassword":true,"automatic":false,"intervalMinutes":15}),
            ),
        });
        let path = std::env::temp_dir().join(format!(
            "rhremote-update-flow-{}.json",
            random_token().unwrap()
        ));
        std::fs::write(&path, serde_json::to_vec(&Saved::default()).unwrap()).unwrap();
        let controller = Controller::new("windows", path.clone(), boundary.clone()).unwrap();
        controller.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"PC","address":"192.0.2.1","paired":true,"online":true}])).unwrap());
        let updating = controller.clone();
        let worker = std::thread::spawn(move || updating.dispatch("updates.primary", json!({})));
        ready.recv_timeout(Duration::from_secs(5)).unwrap();
        Self {
            controller,
            boundary,
            release: Some(release),
            worker: Some(worker),
            path,
        }
    }
    fn action(&self, id: &str, args: Value) -> Value {
        let result = self.controller.dispatch(id, args);
        assert_eq!(result["ok"], true, "{id}: {result}");
        result
    }
    fn finish(&mut self) -> Value {
        if let Some(release) = self.release.take() {
            release.send(()).unwrap();
        }
        self.worker.take().unwrap().join().unwrap()
    }
    fn calls(&self) -> Vec<String> {
        self.boundary.calls.lock().unwrap().clone()
    }
    fn edit(&self, kind: &str, invalid: bool) {
        match kind {
            "device" => {
                self.action("device.settings.open", json!({"uuid":"host"}));
                self.action(
                    "settings.input",
                    json!({"key":"fps","value":if invalid {"unfinished"} else {"24"}}),
                );
            }
            "info" => {
                self.action("device.info.open", json!({"uuid":"host"}));
                self.action(
                    "device.info.input",
                    json!({"displayName":"Updated workstation"}),
                );
            }
            "sync" => {
                self.action("app.settings.tab", json!({"tab":"sync"}));
                self.action("sync.edit", json!({"key":"username","value":"changed"}));
            }
            _ => unreachable!(),
        }
    }
}
impl Drop for Flow {
    fn drop(&mut self) {
        if let Some(release) = self.release.take() {
            let _ = release.send(());
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        assert_eq!(self.path.parent(), Some(std::env::temp_dir().as_path()));
        assert!(
            self.path
                .file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("rhremote-update-flow-")
        );
        let _ = std::fs::remove_file(&self.path);
    }
}

#[test]
fn prepared_installation_protects_all_draft_types_and_each_decision() {
    for kind in ["device", "info", "sync"] {
        for choice in ["stay", "discard", "save"] {
            let mut f = Flow::preparing();
            let before = std::fs::read(&f.path).unwrap();
            f.edit(kind, false);
            assert_eq!(f.finish()["ok"], true);
            assert!(f.controller.take_window_action().is_empty());
            assert_eq!(f.calls(), ["updates.prepare"]);
            assert_eq!(
                f.controller.state()["navigationPrompt"]["kind"],
                format!("unsaved-{kind}")
            );
            assert_eq!(std::fs::read(&f.path).unwrap(), before);
            f.action("navigation.resolve", json!({"choice":choice}));
            if choice == "stay" {
                assert_eq!(f.calls(), ["updates.prepare"]);
                assert!(f.controller.take_window_action().is_empty());
                assert_eq!(std::fs::read(&f.path).unwrap(), before);
                assert_eq!(f.controller.state()["updates"]["pendingRestart"], true);
            } else {
                assert_eq!(f.calls(), ["updates.prepare", "updates.install"]);
                assert_eq!(f.controller.take_window_action(), "window.close");
                assert_eq!(f.controller.state.lock().unwrap().installing_update, true);
                if choice == "save" {
                    match kind {
                        "device" => assert_eq!(f.controller.profile("host").fps, 24),
                        "info" => assert_eq!(
                            f.controller.state()["saved"]["devices"][0]["displayName"],
                            "Updated workstation"
                        ),
                        "sync" => {
                            assert_eq!(f.boundary.sync.lock().unwrap()["username"], "changed")
                        }
                        _ => unreachable!(),
                    }
                } else {
                    assert_eq!(std::fs::read(&f.path).unwrap(), before);
                }
                assert_eq!(
                    f.controller
                        .dispatch("device.settings.open", json!({"uuid":"host"}))["ok"],
                    false
                );
                assert_eq!(
                    f.controller.dispatch("updates.state", json!({}))["ok"],
                    true
                );
            }
        }
    }
}

#[test]
fn invalid_draft_blocks_installation_and_can_be_corrected_without_another_download() {
    let mut f = Flow::preparing();
    f.edit("device", true);
    assert_eq!(f.finish()["ok"], true);
    assert_eq!(
        f.controller
            .dispatch("navigation.resolve", json!({"choice":"save"}))["ok"],
        false
    );
    assert_eq!(f.controller.state()["settingsInputs"]["fps"], "unfinished");
    assert_eq!(f.calls(), ["updates.prepare"]);
    assert!(f.controller.take_window_action().is_empty());
    f.action("settings.input", json!({"key":"fps","value":"24"}));
    f.action("updates.primary", json!({}));
    f.action("navigation.resolve", json!({"choice":"save"}));
    assert_eq!(f.calls(), ["updates.prepare", "updates.install"]);
    assert_eq!(f.controller.profile("host").fps, 24);
    assert_eq!(f.controller.take_window_action(), "window.close");
}

#[test]
fn preparation_defers_to_a_new_connection_sync_or_add_flow() {
    for active in ["connection", "sync", "add"] {
        let mut f = Flow::preparing();
        assert_eq!(
            f.controller.dispatch("updates.primary", json!({}))["ok"],
            false
        );
        match active {
            "connection" => {
                f.action("device.connect", json!({"uuid":"host"}));
            }
            "sync" => f.controller.state.lock().unwrap().sync_busy = true,
            "add" => {
                f.action("device.add.open", json!({}));
            }
            _ => unreachable!(),
        }
        let result = f.finish();
        assert_eq!(result["ok"], true);
        assert_eq!(result["result"]["installDeferred"], true);
        assert!(
            !f.controller.state()["updateInstallHint"]
                .as_str()
                .unwrap()
                .is_empty()
        );
        assert_eq!(f.calls(), ["updates.prepare"]);
        assert!(f.controller.take_window_action().is_empty());
        match active {
            "connection" => f
                .controller
                .stream_state(false, json!({"phase":"disconnected","streaming":false})),
            "sync" => f.controller.state.lock().unwrap().sync_busy = false,
            "add" => {
                f.action("device.add.cancel", json!({}));
            }
            _ => unreachable!(),
        }
        f.action("updates.primary", json!({}));
        assert_eq!(f.calls(), ["updates.prepare", "updates.install"]);
        assert_eq!(f.controller.take_window_action(), "window.close");
    }
}

#[test]
fn a_previous_navigation_decision_is_not_replaced_when_preparation_finishes() {
    let mut f = Flow::preparing();
    f.edit("device", false);
    f.action("app.settings.tab", json!({"tab":"about"}));
    assert_eq!(f.finish()["result"]["installDeferred"], true);
    assert_eq!(
        f.controller
            .state
            .lock()
            .unwrap()
            .pending_navigation
            .as_ref()
            .unwrap()
            .action,
        "app.settings.tab"
    );
    f.action("navigation.resolve", json!({"choice":"stay"}));
    assert_eq!(f.calls(), ["updates.prepare"]);
    f.action("updates.primary", json!({}));
    f.action("navigation.resolve", json!({"choice":"save"}));
    assert_eq!(f.calls(), ["updates.prepare", "updates.install"]);
}

#[test]
fn failures_preserve_drafts_or_prepared_packages_and_retry_the_correct_stage() {
    let mut f = Flow::preparing();
    f.edit("device", false);
    f.boundary.fail_prepare.store(true, Ordering::SeqCst);
    assert_eq!(f.finish()["ok"], false);
    assert_eq!(f.controller.state()["settingsInputs"]["fps"], "24");
    assert!(f.controller.take_window_action().is_empty());
    assert!(f.controller.state()["navigationPrompt"].is_null());

    let mut f = Flow::preparing();
    f.boundary.fail_apply.store(true, Ordering::SeqCst);
    assert_eq!(f.finish()["ok"], false);
    assert_eq!(f.controller.state()["updates"]["pendingRestart"], true);
    assert_eq!(f.controller.state.lock().unwrap().installing_update, false);
    assert!(f.controller.take_window_action().is_empty());
    f.boundary.fail_apply.store(false, Ordering::SeqCst);
    f.action("updates.primary", json!({}));
    assert_eq!(
        f.calls(),
        ["updates.prepare", "updates.install", "updates.install"]
    );
    assert_eq!(f.controller.take_window_action(), "window.close");
}

#[test]
fn final_installation_rejects_late_mutations_until_the_window_exits() {
    let mut f = Flow::preparing();
    let before = std::fs::read(&f.path).unwrap();
    let (entered, ready) = mpsc::channel();
    let (release, released) = mpsc::channel();
    *f.boundary.apply_gate.lock().unwrap() = Some((entered, released));
    f.release.take().unwrap().send(()).unwrap();
    ready.recv_timeout(Duration::from_secs(5)).unwrap();
    let results: Vec<_> = [
        ("device.settings.open", json!({"uuid":"host"})),
        ("device.info.open", json!({"uuid":"host"})),
        ("device.add.open", json!({})),
        ("sync.edit", json!({"key":"username","value":"late"})),
        ("appearance.toggle", json!({})),
        ("window.close", json!({})),
        ("updates.primary", json!({})),
    ]
    .into_iter()
    .map(|(id, args)| f.controller.dispatch(id, args))
    .collect();
    let state = f.controller.state();
    let reading = f.controller.dispatch("updates.state", json!({}));
    let automatic = f.controller.dispatch("sync.run", json!({"automatic":true}));
    let premature_close = f.controller.take_window_action();
    release.send(()).unwrap();
    assert_eq!(f.finish()["ok"], true);
    for result in results {
        assert_eq!(result["error"], "正在安装更新，请稍候");
    }
    assert_eq!(state["updatesBusy"], true);
    assert_eq!(reading["ok"], true);
    assert_eq!(automatic["result"]["skipped"], true);
    assert!(premature_close.is_empty());
    assert_eq!(std::fs::read(&f.path).unwrap(), before);
    assert_eq!(f.calls(), ["updates.prepare", "updates.install"]);
    assert_eq!(f.controller.take_window_action(), "window.close");
}
