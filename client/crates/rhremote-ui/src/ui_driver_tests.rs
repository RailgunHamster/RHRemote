//! Exercise the queued test API against real software-rendered Slint buttons.
use crate::{Commands, MainWindow, StreamPicker, StreamToolbar, ui_driver::Driver, ui_test::Bridge};
use i_slint_backend_testing::{TestingBackend, TestingBackendOptions};
use rhremote_core::{Controller, Platform};
use serde_json::{Value, json};
use slint::{ComponentHandle, LogicalSize};
use std::{cell::RefCell, rc::Rc, sync::Arc, time::Duration};

struct Boundary;
impl Platform for Boundary {
    fn execute(&self, _: &str, _: &Value) -> Result<Value, String> { Ok(json!({})) }
}
struct Fixture {
    ui: MainWindow, toolbar: StreamToolbar, picker: StreamPicker,
    controller: Arc<Controller>, bridge: Arc<Bridge>, driver: Driver,
    calls: Rc<RefCell<Vec<(String, String)>>>, storage: std::path::PathBuf,
}
impl Fixture {
    fn new() -> Self {
        slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
            mock_time: true, threading: false, renderer_name: Some("software".into()),
        }))).unwrap();
        let storage = std::env::temp_dir().join(format!("rhremote-button-api-{}.json", rhremote_core::random_token().unwrap()));
        let controller = Controller::new("test", storage.clone(), Arc::new(Boundary)).unwrap();
        controller.replace_devices(serde_json::from_value(json!([
            {"uuid":"host","name":"Server","address":"192.0.2.1","online":true,"paired":true}
        ])).unwrap());
        assert_eq!(controller.dispatch("device.connect", json!({"uuid":"host"}))["ok"], true);
        controller.stream_state(true, json!({"phase":"connected","firstFrame":false,"nativeCleanupPending":false}));
        let ui = MainWindow::new().unwrap();
        let toolbar = StreamToolbar::new().unwrap();
        let picker = StreamPicker::new().unwrap();
        ui.window().set_size(LogicalSize::new(900., 700.));
        toolbar.window().set_size(LogicalSize::new(560., 292.));
        toolbar.set_expanded(true); toolbar.set_pending(true);
        ui.show().unwrap(); toolbar.show().unwrap();
        let bridge = Arc::new(Bridge::new(true));
        let driver = Driver::new(bridge.clone(), &ui, &toolbar, &picker);
        let calls = Rc::new(RefCell::new(Vec::new()));
        macro_rules! bind {($window:expr) => {{
            let trace = bridge.clone(); let calls = calls.clone();
            $window.global::<Commands>().on_trigger(move |id, arg| {
                trace.observe_action(id.as_str()); calls.borrow_mut().push((id.to_string(), arg.to_string()));
            });
        }}}
        bind!(ui); bind!(toolbar); bind!(picker);
        i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
        Self { ui, toolbar, picker, controller, bridge, driver, calls, storage }
    }
    fn drain(&mut self, id: &str) -> Value {
        for _ in 0..12 {
            self.driver.tick(&self.ui, &self.toolbar, &self.picker, &self.controller);
            i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
            let value = self.bridge.execute("view.ui.result", &json!({"id":id})).unwrap();
            if value["status"] == "complete" || value["status"] == "failed" { return value; }
        }
        panic!("Button request did not complete");
    }
    fn activate(&mut self, args: Value) -> Value {
        let request = self.bridge.execute("view.ui.activate", &args).unwrap();
        self.drain(request["id"].as_str().unwrap())
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.toolbar.hide(); let _ = self.ui.hide();
        if self.storage.exists() { std::fs::remove_file(&self.storage).unwrap(); }
    }
}

#[test]
fn semantic_button_activation_survives_a_label_change_after_queueing() {
    let mut f = Fixture::new();
    let args = json!({"requestId":"changing-label","window":"toolbar","actionId":"stream.disconnect"});
    f.bridge.execute("view.ui.activate", &args).unwrap();
    f.toolbar.set_pending(false);
    f.controller.stream_state(true, json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false}));
    let result = f.drain("changing-label");
    assert_eq!(result["status"], "complete", "{result}");
    assert_eq!(result["result"]["activatedLabel"], "断开");
    assert_eq!(result["result"]["commandIds"], json!(["stream.disconnect"]));
    assert_eq!(&*f.calls.borrow(), &[("stream.disconnect".into(), "".into())]);
    assert_eq!(result["result"]["systemInputInjected"], false);
    assert_eq!(result["result"]["windowActivationRequested"], false);
}

#[test]
fn pending_precondition_is_rechecked_when_the_queued_button_executes() {
    let mut f = Fixture::new();
    let args = json!({"requestId":"pending-changed","window":"toolbar","actionId":"stream.disconnect","expectedPending":true});
    f.bridge.execute("view.ui.activate", &args).unwrap();
    f.toolbar.set_pending(false);
    let result = f.drain("pending-changed");
    assert_eq!(result["status"], "failed"); assert!(f.calls.borrow().is_empty());
    f.toolbar.set_pending(true);
    assert_eq!(f.bridge.execute("view.ui.activate", &args).unwrap()["status"], "failed");
    let result = f.activate(json!({"requestId":"pending-current","window":"toolbar","actionId":"stream.disconnect","expectedPending":true}));
    assert_eq!(result["status"], "complete", "{result}");
    assert_eq!(result["result"]["toolbarPendingBefore"], true);
    assert_eq!(f.calls.borrow().len(), 1);
}

#[test]
fn a_disabled_button_does_not_dispatch_and_completed_requests_do_not_replay() {
    let mut f = Fixture::new(); f.toolbar.set_canceling(true);
    let result = f.activate(json!({"requestId":"disabled","window":"toolbar","actionId":"stream.disconnect"}));
    assert_eq!(result["status"], "failed"); assert!(f.calls.borrow().is_empty());
    f.toolbar.set_canceling(false);
    let args = json!({"requestId":"once","window":"toolbar","actionId":"stream.disconnect"});
    assert_eq!(f.activate(args.clone())["status"], "complete");
    assert_eq!(f.bridge.execute("view.ui.activate", &args).unwrap()["status"], "complete");
    assert_eq!(f.calls.borrow().len(), 1);
    assert!(f.bridge.execute("view.ui.activate", &json!({"requestId":"once","window":"toolbar","actionId":"stream.disconnect","expectedPending":true})).is_err());
}

#[test]
fn modal_dialogs_keep_background_buttons_blocked_for_semantic_activation() {
    let mut f = Fixture::new(); f.ui.set_page("add".into());
    f.ui.set_add_address("192.0.2.1".into()); f.ui.set_navigation_prompt(true);
    let result = f.activate(json!({"window":"main","actionId":"device.add.submit","argument":"192.0.2.1"}));
    assert_eq!(result["status"], "failed"); assert!(f.calls.borrow().is_empty());
    let result = f.activate(json!({"window":"main","actionId":"navigation.resolve","argument":"stay"}));
    assert_eq!(result["status"], "complete", "{result}");
    assert_eq!(&*f.calls.borrow(), &[("navigation.resolve".into(), "stay".into())]);
}
