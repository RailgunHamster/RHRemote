use super::*;
use std::sync::{
    Condvar,
    atomic::{AtomicBool, Ordering},
};

#[derive(Default)]
struct Boundary {
    entered: (Mutex<bool>, Condvar),
    release: (Mutex<bool>, Condvar),
    cancelled: AtomicBool,
    apply: AtomicBool,
}
impl Platform for Boundary {
    fn begin_update(&self, _: u64, _: &str, _: bool) -> Result<(), String> {
        self.cancelled.store(false, Ordering::SeqCst);
        Ok(())
    }
    fn cancel_update(&self, _: u64) -> Result<(), String> {
        self.cancelled.store(true, Ordering::SeqCst);
        Ok(())
    }
    fn execute(&self, id: &str, _: &Value) -> Result<Value, String> {
        if id == "updates.prepare" || id == "updates.check" {
            *self.entered.0.lock().unwrap() = true;
            self.entered.1.notify_all();
            let held = self.release.0.lock().unwrap();
            let (guard, result) = self
                .release
                .1
                .wait_timeout_while(held, std::time::Duration::from_secs(3), |held| !*held)
                .unwrap();
            drop(guard);
            assert!(!result.timed_out());
            // Deliberately simulate successful verification racing with cancel.
            return Ok(
                json!({"managed":true,"phase":"downloaded","pendingRestart":true,"availableVersion":"9.0.0"}),
            );
        }
        if id == "updates.install" {
            self.apply.store(true, Ordering::SeqCst);
            return Ok(json!({"phase":"applying"}));
        }
        Ok(json!({"managed":true,"phase":"available","availableVersion":"9.0.0"}))
    }
}
#[test]
fn accepted_cancel_waits_for_cleanup_and_suppresses_even_a_successful_install_handoff() {
    let boundary = Arc::new(Boundary::default());
    let path = std::env::temp_dir().join(format!(
        "rhremote-update-cancel-{}.json",
        random_token().unwrap()
    ));
    let controller = Controller::new("test", path.clone(), boundary.clone()).unwrap();
    let owner = controller.clone();
    let worker = std::thread::spawn(move || owner.dispatch("updates.primary", json!({})));
    let held = boundary.entered.0.lock().unwrap();
    let (guard, result) = boundary
        .entered
        .1
        .wait_timeout_while(held, std::time::Duration::from_secs(3), |held| !*held)
        .unwrap();
    drop(guard);
    assert!(!result.timed_out());
    let operation = controller.state()["updates"]["operationId"]
        .as_u64()
        .unwrap();
    assert_eq!(
        controller.dispatch("updates.cancel", json!({"operationId":operation+1}))["ok"],
        false
    );
    assert!(!boundary.cancelled.load(Ordering::SeqCst));
    assert_eq!(
        controller.dispatch("updates.cancel", json!({"operationId":operation}))["ok"],
        true
    );
    assert_eq!(
        controller.dispatch("updates.cancel", json!({"operationId":operation}))["ok"],
        true
    );
    let state = controller.state();
    assert_eq!(state["updatesBusy"], true);
    assert_eq!(state["updates"]["canCancel"], false);
    assert_eq!(state["updates"]["phase"], "cancelling");
    assert_eq!(
        controller.dispatch("updates.primary", json!({}))["ok"],
        false
    );
    *boundary.release.0.lock().unwrap() = true;
    boundary.release.1.notify_all();
    assert_eq!(worker.join().unwrap()["ok"], true);
    assert!(!boundary.apply.load(Ordering::SeqCst));
    assert!(controller.take_window_action().is_empty());
    let state = controller.state();
    assert_eq!(state["updatesBusy"], false);
    assert_eq!(state["updates"]["phase"], "cancelled");
    assert_eq!(state["updates"]["pendingRestart"], true);
    assert_eq!(
        controller.dispatch("updates.cancel", json!({"operationId":operation}))["ok"],
        false
    );
    assert_eq!(
        controller.dispatch("updates.primary", json!({}))["ok"],
        true
    );
    assert!(boundary.apply.load(Ordering::SeqCst));
    assert_eq!(controller.take_window_action(), "window.close");
    assert_eq!(
        controller.dispatch("updates.cancel", json!({"operationId":operation}))["ok"],
        false
    );
    let _ = std::fs::remove_file(path);
}
