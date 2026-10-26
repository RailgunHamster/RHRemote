//! Finding a host is cancellable and read-only; only the accepted result is saved.
use crate::{Controller, Device, network, sync_model};
use serde::Serialize;
use serde_json::{Value, json};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AddState {
    pub address: String,
    pub phase: String,
    pub error: String,
    #[serde(skip)]
    epoch: u64,
    #[serde(skip)]
    cancel: Option<Arc<AtomicBool>>,
}
impl Default for AddState {
    fn default() -> Self {
        Self {
            address: String::new(),
            phase: "editing".into(),
            error: String::new(),
            epoch: 0,
            cancel: None,
        }
    }
}
impl AddState {
    pub fn busy(&self) -> bool {
        self.phase == "probing"
    }
}

pub(crate) fn validate_address(address: &str) -> Result<(), String> {
    network::validate_address(address)?;
    if network::transport_id(address).is_some() {
        return Err("这里填写 IP 或主机名；内置中继地址请在连接设置里配置".into());
    }
    Ok(())
}
impl Controller {
    pub(crate) fn add_gate(&self, id: &str, _args: &Value) -> Option<Result<Value, String>> {
        let mut s = self.state.lock().unwrap();
        if !s.add.busy() {
            return None;
        }
        if id.starts_with("stream.")
            || id.starts_with("view.")
            || matches!(
                id,
                "input.gesture"
                    | "window.minimize"
                    | "window.maximize"
                    | "device.add.cancel"
                    | "settings.catalog"
                    | "updates.state"
                    | "sync.state"
                    | "statistics.state"
                    | "network.state"
            )
        {
            return None;
        }
        if id == "device.add.open" {
            return Some(Ok(json!({"reused":true})));
        }
        if matches!(
            id,
            "devices.open"
                | "device.select"
                | "app.settings.open"
                | "app.settings.tab"
                | "device.settings.open"
                | "device.info.open"
                | "window.close"
        ) {
            s.add.cancel.take().unwrap().store(true, Ordering::SeqCst);
            s.add.epoch += 1;
            s.add.phase = "editing".into();
            s.add.address.clear();
            s.add.error.clear();
            s.page = "devices".into();
            s.revision += 1;
            return None;
        }
        Some(Err("正在查找设备，请先取消当前请求".into()))
    }
    pub(crate) fn dispatch_add(&self, id: &str, args: Value) -> Result<Value, String> {
        let mut s = self.state.lock().unwrap();
        match id {
            "device.add.open" => {
                if s.installing_update || s.page == "pair" || s.connecting {
                    return Err("请先完成或取消当前操作".into());
                }
                if s.page != "add" {
                    let epoch = s.add.epoch;
                    s.add = AddState::default();
                    s.add.epoch = epoch;
                }
                s.page = "add".into();
                s.status.clear();
            }
            "device.add.input" => {
                if s.page != "add" {
                    return Err("请先打开添加电脑页面".into());
                }
                let value = args["address"].as_str().ok_or("address required")?;
                s.add.address = value.into();
                s.add.error.clear();
                s.status.clear();
                s.add.phase = "editing".into();
            }
            "device.add.cancel" => {
                if let Some(cancel) = s.add.cancel.take() {
                    cancel.store(true, Ordering::SeqCst);
                    s.add.epoch += 1;
                }
                s.add.address.clear();
                s.add.error.clear();
                s.add.phase = "editing".into();
                s.page = "devices".into();
                s.status.clear();
            }
            "device.add.submit" => {
                if s.page != "add" {
                    return Err("请先打开添加电脑页面".into());
                }
                if s.sync_busy || s.installing_update {
                    return Err("请先完成当前操作".into());
                }
                if let Some(value) = args.get("address") {
                    s.add.address = value.as_str().ok_or("address must be a string")?.into();
                }
                let address = s.add.address.trim().to_owned();
                if let Err(error) = validate_address(&address) {
                    s.add.error = error.clone();
                    s.add.phase = "editing".into();
                    return Err(error);
                }
                s.add.epoch += 1;
                let epoch = s.add.epoch;
                let cancel = Arc::new(AtomicBool::new(false));
                s.add.cancel = Some(cancel.clone());
                s.add.phase = "probing".into();
                s.add.error.clear();
                s.status.clear();
                s.revision += 1;
                let controller = self.owner.upgrade().ok_or("界面已关闭")?;
                drop(s);
                if let Err(error) = std::thread::Builder::new()
                    .name("RHRemote add device".into())
                    .spawn(move || {
                        let result = controller.platform.probe_device(&address, cancel);
                        controller.finish_add(epoch, result);
                    })
                {
                    self.finish_add(epoch, Err(format!("查找任务无法启动：{error}")));
                    return Err("查找任务无法启动".into());
                }
                return Ok(json!({"accepted":true,"attemptId":epoch}));
            }
            _ => return Err("unknown add action".into()),
        }
        s.revision += 1;
        Ok(json!({"accepted":true}))
    }
    fn finish_add(&self, epoch: u64, result: Result<Device, String>) {
        let mut s = self.state.lock().unwrap();
        if s.add.epoch != epoch {
            return;
        }
        let cancelled = s
            .add
            .cancel
            .take()
            .is_some_and(|c| c.load(Ordering::SeqCst));
        s.add.phase = "editing".into();
        s.revision += 1;
        if cancelled {
            s.add.error.clear();
            s.add.address.clear();
            s.page = "devices".into();
            return;
        }
        let result = (|| {
            let mut device = result?;
            if device.uuid.is_empty()
                || device.uuid.len() > 128
                || device
                    .uuid
                    .chars()
                    .any(|c| !c.is_ascii_alphanumeric() && c != '-')
                || device.name.trim().is_empty()
            {
                return Err("主机返回的设备信息无效".into());
            }
            let requested = s.add.address.trim().to_owned();
            validate_address(&requested)?;
            device.address = requested.clone();
            device.addresses.clear();
            if let Some(old) = s
                .devices
                .iter()
                .find(|d| d.uuid.eq_ignore_ascii_case(&device.uuid))
            {
                device.uuid = old.uuid.clone();
                device.display_name = old.display_name.clone();
                device.paired = old.paired;
                device.is_self = old.is_self || device.is_self;
            }
            if let Some(old) = s
                .saved
                .devices
                .iter()
                .find(|d| d.uuid.eq_ignore_ascii_case(&device.uuid))
            {
                device.display_name = old.display_name.clone();
                device.address = old.address.clone();
                device.addresses = network::addresses(&old.address, &old.addresses)?;
                if let Some(live) = s.devices.iter().find(|d| d.uuid == device.uuid) {
                    for address in std::iter::once(&live.address).chain(live.addresses.iter()) {
                        if !device
                            .addresses
                            .iter()
                            .any(|a| a.eq_ignore_ascii_case(address))
                        {
                            device.addresses.push(address.clone());
                        }
                    }
                }
                if !device
                    .addresses
                    .iter()
                    .any(|a| a.eq_ignore_ascii_case(&requested))
                {
                    device.addresses.push(requested);
                }
                device.addresses = network::addresses(&device.address, &device.addresses)?
                    .into_iter()
                    .filter(|a| !a.eq_ignore_ascii_case(&device.address))
                    .collect();
            }
            let mut candidate = s.clone();
            candidate
                .devices
                .retain(|d| !d.uuid.eq_ignore_ascii_case(&device.uuid));
            let index = s
                .devices
                .iter()
                .position(|d| d.uuid.eq_ignore_ascii_case(&device.uuid))
                .unwrap_or(candidate.devices.len());
            candidate.devices.insert(index, device.clone());
            let mut next = s.saved.clone();
            next.devices = sync_model::from_state(&candidate).devices;
            sync_model::from_state(&candidate).validate()?;
            self.persist(&next)?;
            let existed = s.devices.iter().any(|d| d.uuid == device.uuid);
            self.platform.added_device(&device);
            s.saved = next;
            s.devices = candidate.devices;
            s.selected = device.uuid.clone();
            s.device_query.clear();
            s.page = "devices".into();
            s.add.address.clear();
            s.add.error.clear();
            s.status = if existed {
                "设备地址已保存"
            } else {
                "设备已添加"
            }
            .into();
            Ok(())
        })();
        if let Err(error) = result {
            s.add.error = error;
            s.page = "add".into();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Platform, random_token};
    use std::{
        sync::{Mutex, mpsc},
        time::{Duration, Instant},
    };
    struct Host {
        entered: mpsc::Sender<()>,
        release: Mutex<mpsc::Receiver<Result<Device, String>>>,
        published: Mutex<Vec<Device>>,
    }
    impl Platform for Host {
        fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
            Ok(if id == "devices.restore" {
                json!({"devices":args["devices"]})
            } else {
                json!({})
            })
        }
        fn probe_device(&self, _: &str, _: Arc<AtomicBool>) -> Result<Device, String> {
            let gate = self.release.lock().unwrap();
            self.entered.send(()).unwrap();
            gate.recv_timeout(Duration::from_secs(3)).unwrap()
        }
        fn added_device(&self, device: &Device) {
            self.published.lock().unwrap().push(device.clone());
        }
    }
    struct Fixture {
        c: Arc<Controller>,
        host: Arc<Host>,
        entered: mpsc::Receiver<()>,
        release: mpsc::Sender<Result<Device, String>>,
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.c.storage);
        }
    }
    fn fixture() -> Fixture {
        let (et, er) = mpsc::channel();
        let (rt, rr) = mpsc::channel();
        let host = Arc::new(Host {
            entered: et,
            release: Mutex::new(rr),
            published: Mutex::new(vec![]),
        });
        let c = Controller::new(
            "test",
            std::env::temp_dir().join(format!("rhremote-add-{}.json", random_token().unwrap())),
            host.clone(),
        )
        .unwrap();
        c.dispatch("device.add.open", json!({}));
        Fixture {
            c,
            host,
            entered: er,
            release: rt,
        }
    }
    fn device() -> Device {
        serde_json::from_value(json!({"uuid":"new-host","name":"New server","address":"192.168.0.104","online":true,"paired":false})).unwrap()
    }
    fn start(f: &Fixture, address: &str) {
        assert_eq!(
            f.c.dispatch("device.add.submit", json!({"address":address}))["ok"],
            true
        );
        f.entered.recv_timeout(Duration::from_secs(1)).unwrap();
    }
    fn finish(f: &Fixture, result: Result<Device, String>) {
        f.release.send(result).unwrap();
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            if f.c.state()["add"]["phase"] == "editing" && Arc::strong_count(&f.c) == 1 {
                break;
            }
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(2));
        }
    }
    #[test]
    fn invalid_addresses_stay_in_the_form_without_network_or_persistence() {
        let f = fixture();
        let before = std::fs::read(&f.c.storage).unwrap();
        for address in ["bad/path", "http://192.168.0.104", "host:47989", ""] {
            assert_eq!(
                f.c.dispatch("device.add.submit", json!({"address":address}))["ok"],
                false
            );
            assert_eq!(f.c.state()["add"]["address"], address);
            assert_eq!(f.c.state()["page"], "add");
        }
        assert!(f.entered.try_recv().is_err());
        assert_eq!(std::fs::read(&f.c.storage).unwrap(), before);
    }
    #[test]
    fn lookup_is_observable_deduplicated_and_selects_the_saved_device() {
        let f = fixture();
        f.c.dispatch("devices.filter", json!({"query":"old search"}));
        start(&f, "192.168.0.104");
        assert_eq!(f.c.state()["add"]["phase"], "probing");
        assert_eq!(
            f.c.dispatch("device.add.submit", json!({"address":"other"}))["ok"],
            false
        );
        assert!(
            f.c.state()["saved"]["devices"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        finish(&f, Ok(device()));
        assert_eq!(f.c.state()["selected"], "new-host");
        assert_eq!(f.c.state()["deviceQuery"], "");
        assert_eq!(f.c.state()["page"], "devices");
        assert_eq!(f.host.published.lock().unwrap().len(), 1);
    }
    #[test]
    fn failure_keeps_input_and_can_retry_without_creating_a_duplicate() {
        let f = fixture();
        let before = f.c.state()["saved"].clone();
        start(&f, "192.168.0.104");
        finish(&f, Err("设备不可达".into()));
        assert_eq!(f.c.state()["add"]["address"], "192.168.0.104");
        assert_eq!(f.c.state()["add"]["error"], "设备不可达");
        assert_eq!(f.c.state()["saved"], before);
        start(&f, "100.73.139.109");
        finish(&f, Ok(device()));
        assert_eq!(f.c.state()["devices"].as_array().unwrap().len(), 1);
        assert_eq!(f.c.state()["devices"][0]["address"], "100.73.139.109");
    }
    #[test]
    fn cancellation_does_not_publish_a_late_success() {
        let f = fixture();
        let before = std::fs::read(&f.c.storage).unwrap();
        start(&f, "192.168.0.104");
        assert_eq!(f.c.dispatch("device.add.cancel", json!({}))["ok"], true);
        assert_eq!(f.c.state()["page"], "devices");
        assert_eq!(
            f.c.dispatch("device.add.submit", json!({"address":"other"}))["ok"],
            false
        );
        finish(&f, Ok(device()));
        assert_eq!(f.c.state()["page"], "devices");
        assert!(f.host.published.lock().unwrap().is_empty());
        assert_eq!(std::fs::read(&f.c.storage).unwrap(), before);
    }
    #[test]
    fn sidebar_navigation_leaves_immediately_and_ignores_the_retired_lookup() {
        let f = fixture();
        start(&f, "192.168.0.104");
        f.c.dispatch("app.settings.tab", json!({"tab":"updates"}));
        assert_eq!(f.c.state()["page"], "global-settings");
        finish(&f, Err("cancelled".into()));
        let deadline = Instant::now() + Duration::from_secs(1);
        while f.c.state()["page"] != "global-settings" {
            assert!(Instant::now() < deadline);
            std::thread::yield_now();
        }
        assert_eq!(f.c.state()["settingsTab"], "updates");
        assert!(f.host.published.lock().unwrap().is_empty());
    }
    #[test]
    fn readding_preserves_alias_pairing_profile_order_and_primary_address() {
        let f = fixture();
        let mut known = device();
        known.paired = true;
        f.c.replace_devices(vec![known]);
        f.c.dispatch("device.settings.open", json!({"uuid":"new-host"}));
        f.c.dispatch(
            "settings.input",
            json!({"key":"displayName","value":"工作机"}),
        );
        f.c.dispatch("settings.input", json!({"key":"fps","value":"24"}));
        f.c.dispatch("settings.save", json!({}));
        f.c.dispatch("device.add.open", json!({}));
        let profile = f.c.profile("new-host");
        start(&f, "100.73.139.109");
        finish(&f, Ok(device()));
        assert_eq!(f.c.profile("new-host"), profile);
        let s = f.c.state();
        assert_eq!(s["devices"].as_array().unwrap().len(), 1);
        assert_eq!(s["devices"][0]["paired"], true);
        assert_eq!(s["devices"][0]["displayName"], "工作机");
        assert_eq!(s["saved"]["devices"][0]["address"], "192.168.0.104");
        assert_eq!(s["saved"]["devices"][0]["addresses"][0], "100.73.139.109");
    }
    #[test]
    fn persistence_failure_keeps_the_form_and_never_publishes_success() {
        let f = fixture();
        let before = std::fs::read(&f.c.storage).unwrap();
        let blocked = f.c.storage.with_extension("json.tmp");
        std::fs::create_dir(&blocked).unwrap();
        start(&f, "192.168.0.104");
        finish(&f, Ok(device()));
        assert_eq!(f.c.state()["page"], "add");
        assert!(!f.c.state()["add"]["error"].as_str().unwrap().is_empty());
        assert!(f.host.published.lock().unwrap().is_empty());
        assert_eq!(std::fs::read(&f.c.storage).unwrap(), before);
        std::fs::remove_dir(blocked).unwrap();
    }
    #[test]
    fn reopening_an_editing_form_retains_input() {
        let f = fixture();
        f.c.dispatch("device.add.input", json!({"address":"100.73."}));
        f.c.dispatch("device.add.open", json!({}));
        assert_eq!(f.c.state()["add"]["address"], "100.73.");
        f.c.dispatch("device.add.cancel", json!({}));
        assert_eq!(f.c.state()["add"]["address"], "");
    }
    #[test]
    fn lookup_does_not_block_the_active_session_controls_or_window_buttons() {
        let f = fixture();
        start(&f, "192.168.0.104");
        f.c.state.lock().unwrap().streaming = true;
        for id in [
            "stream.controls.toggle",
            "stream.shortcuts.list",
            "window.minimize",
            "window.maximize",
            "stream.disconnect",
        ] {
            assert_eq!(f.c.dispatch(id, json!({}))["ok"], true, "{id}");
        }
        assert_eq!(f.c.state()["add"]["phase"], "probing");
        f.c.dispatch("device.add.cancel", json!({}));
        finish(&f, Ok(device()));
        assert!(f.host.published.lock().unwrap().is_empty());
    }
    #[test]
    fn a_retired_lookup_cannot_commit_into_a_new_attempt() {
        let f = fixture();
        let before = f.c.state()["saved"].clone();
        start(&f, "192.168.0.104");
        f.c.dispatch("device.add.cancel", json!({}));
        f.c.dispatch("device.add.open", json!({}));
        assert_eq!(
            f.c.dispatch("device.add.submit", json!({"address":"100.73.139.109"}))["ok"],
            true
        );
        f.release.send(Ok(device())).unwrap();
        f.entered.recv_timeout(Duration::from_secs(1)).unwrap();
        let deadline = Instant::now() + Duration::from_secs(1);
        while Arc::strong_count(&f.c) > 2 {
            assert!(Instant::now() < deadline);
            std::thread::yield_now();
        }
        assert_eq!(f.c.state()["saved"], before);
        assert_eq!(f.c.state()["add"]["phase"], "probing");
        let mut accepted = device();
        accepted.uuid = "second-host".into();
        finish(&f, Ok(accepted));
        assert_eq!(f.host.published.lock().unwrap().len(), 1);
        assert_eq!(f.c.state()["selected"], "second-host");
        assert_eq!(
            f.c.state()["saved"]["devices"][0]["address"],
            "100.73.139.109"
        );
    }
}
