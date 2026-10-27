use crate::{Controller, Device, Platform};
use serde_json::{Value, json};
use std::{
    fs,
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc,
    },
};

struct TestDirectory(PathBuf);
impl TestDirectory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "rhremote-sync-commit-{}",
            crate::random_token().unwrap()
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for TestDirectory {
    fn drop(&mut self) {
        let root = self.0.canonicalize().unwrap();
        let temp = std::env::temp_dir().canonicalize().unwrap();
        assert!(root.starts_with(&temp) && root != temp);
        fs::remove_dir_all(root).unwrap();
    }
}
struct Transfer {
    snapshot: Mutex<Value>,
    fail_ack: AtomicBool,
    fail_restore: AtomicBool,
    confirmations: AtomicUsize,
    gate: Mutex<Option<(mpsc::Sender<()>, mpsc::Receiver<()>)>>,
}
impl Default for Transfer {
    fn default() -> Self {
        Self {
            snapshot: Mutex::new(
                json!({"lastSyncMs":1,"automatic":true,"intervalMinutes":5,"hasPassword":true}),
            ),
            fail_ack: AtomicBool::new(false),
            fail_restore: AtomicBool::new(false),
            confirmations: AtomicUsize::new(0),
            gate: Mutex::new(None),
        }
    }
}
impl Platform for Transfer {
    fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        match id {
            "sync.state" => Ok(self.snapshot.lock().unwrap().clone()),
            "updates.state" => Ok(json!({})),
            "sync.run" => {
                let mut shared = args["shared"].clone();
                shared["devices"][0]["displayName"] = json!("远端新名");
                let mut state = self.snapshot.lock().unwrap();
                state["automaticBlocked"] = json!(true);
                state["phase"] = json!("applying");
                Ok(json!({"shared":shared,"sync":*state,"conflicts":[]}))
            }
            "devices.restore" => {
                if self.fail_restore.load(Ordering::SeqCst) {
                    Err("Test-owned restore failure".into())
                } else {
                    Ok(args.clone())
                }
            }
            _ => Err("No external transport in this test".into()),
        }
    }
    fn sync_applied(
        &self,
        shared: &crate::sync_model::SharedConfig,
        preferences: &std::path::Path,
    ) -> Result<Option<Value>, String> {
        self.confirmations.fetch_add(1, Ordering::SeqCst);
        let persisted: Value = serde_json::from_slice(&fs::read(preferences).unwrap()).unwrap();
        assert_eq!(
            persisted["devices"][0]["displayName"],
            shared.devices[0].display_name
        );
        if let Some((entered, release)) = &*self.gate.lock().unwrap() {
            entered.send(()).unwrap();
            release
                .recv_timeout(std::time::Duration::from_secs(5))
                .unwrap();
        }
        if self.fail_ack.load(Ordering::SeqCst) {
            return Err("Test-owned acknowledgement failure".into());
        }
        let mut state = self.snapshot.lock().unwrap();
        state["lastSyncMs"] = json!(2);
        state["automaticBlocked"] = json!(false);
        state["phase"] = json!("completed");
        state["error"] = json!("");
        Ok(Some(state.clone()))
    }
}
fn controller(directory: &TestDirectory) -> Arc<Controller> {
    controller_with(directory, Arc::new(Transfer::default()))
}
fn controller_with(directory: &TestDirectory, platform: Arc<Transfer>) -> Arc<Controller> {
    let controller =
        Controller::new("test", directory.0.join("preferences.json"), platform).unwrap();
    {
        let mut state = controller.state.lock().unwrap();
        state.devices = vec![Device {
            uuid: "host".into(),
            name: "workstation".into(),
            display_name: "本机旧名".into(),
            address: "192.0.2.10".into(),
            addresses: vec![],
            online: false,
            paired: false,
            is_self: false,
        }];
        state.saved.devices = crate::sync_model::from_state(&state).devices;
        controller.persist(&state.saved).unwrap();
    }
    controller
}
#[test]
fn commit_local_storage_failure_is_retained_as_an_actionable_sync_error() {
    let directory = TestDirectory::new();
    let controller = controller(&directory);
    let before = fs::read(&controller.storage).unwrap();
    fs::write(directory.0.join("sync-backups"), b"test-owned blocker").unwrap();
    assert_eq!(controller.dispatch("sync.run", json!({}))["ok"], false);
    let state = controller.state();
    assert!(!state["syncError"].as_str().unwrap().is_empty());
    assert_eq!(state["syncBusy"], false);
    assert_eq!(state["sync"]["lastSyncMs"], 1);
    assert_eq!(fs::read(&controller.storage).unwrap(), before);
}

#[test]
fn commit_confirmation_is_after_preferences_and_before_busy_is_released() {
    let directory = TestDirectory::new();
    let platform = Arc::new(Transfer::default());
    let (entered_tx, entered_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    *platform.gate.lock().unwrap() = Some((entered_tx, release_rx));
    let controller = controller_with(&directory, platform.clone());
    let current = controller.clone();
    let worker = std::thread::spawn(move || current.dispatch("sync.run", json!({})));
    entered_rx
        .recv_timeout(std::time::Duration::from_secs(5))
        .unwrap();
    let state = controller.state();
    assert_eq!(state["syncBusy"], true);
    assert_eq!(state["sync"]["phase"], "recording");
    assert_eq!(state["sync"]["lastSyncMs"], 1);
    assert_ne!(state["status"], "同步完成");
    assert_eq!(controller.dispatch("sync.run", json!({}))["ok"], false);
    release_tx.send(()).unwrap();
    assert_eq!(worker.join().unwrap()["ok"], true);
    assert_eq!(controller.state()["syncBusy"], false);
    assert_eq!(controller.state()["sync"]["lastSyncMs"], 2);
    assert_eq!(platform.confirmations.load(Ordering::SeqCst), 1);
}
#[test]
fn commit_ack_failure_keeps_applied_data_and_preserves_the_warning_through_cancel() {
    let directory = TestDirectory::new();
    let platform = Arc::new(Transfer::default());
    platform.fail_ack.store(true, Ordering::SeqCst);
    let controller = controller_with(&directory, platform.clone());
    assert_eq!(controller.dispatch("sync.run", json!({}))["ok"], false);
    let state = controller.state();
    assert_eq!(state["saved"]["devices"][0]["displayName"], "远端新名");
    assert_eq!(state["sync"]["lastSyncMs"], 1);
    assert_eq!(state["syncBusy"], false);
    assert_eq!(state["sync"]["automaticBlocked"], true);
    assert!(!controller.automatic_sync_due(u64::MAX, 0));
    controller.dispatch("app.settings.tab", json!({"tab":"sync"}));
    controller.dispatch("sync.edit", json!({"key":"username","value":"draft"}));
    controller.dispatch("sync.cancel", json!({}));
    assert!(
        controller.state()["syncError"]
            .as_str()
            .unwrap()
            .contains("自动同步已暂停")
    );
    assert_eq!(
        controller.dispatch("sync.run", json!({"automatic":true}))["result"]["reason"],
        "sync_interrupted"
    );
    platform.fail_ack.store(false, Ordering::SeqCst);
    assert_eq!(controller.dispatch("sync.run", json!({}))["ok"], true);
    assert_eq!(controller.state()["syncError"], "");
    assert_eq!(controller.state()["sync"]["automaticBlocked"], false);
}
#[test]
fn commit_device_restore_failure_does_not_confirm_the_transfer() {
    let directory = TestDirectory::new();
    let platform = Arc::new(Transfer::default());
    platform.fail_restore.store(true, Ordering::SeqCst);
    let controller = controller_with(&directory, platform.clone());
    assert_eq!(controller.dispatch("sync.run", json!({}))["ok"], false);
    assert_eq!(platform.confirmations.load(Ordering::SeqCst), 0);
    assert_eq!(controller.state()["syncBusy"], false);
    assert_eq!(controller.state()["sync"]["automaticBlocked"], true);
    assert!(
        controller.state()["syncError"]
            .as_str()
            .unwrap()
            .contains("设备状态刷新失败")
    );
}
