//! Trust failures and current reachability are separate runtime facts.
use crate::{CheckOutcome as O, CheckPhase, CheckSource, Controller, Device, DeviceCheck, DeviceObservation, Platform};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};

#[derive(Default)]
struct Host {
    next: Mutex<Option<DeviceObservation>>,
    calls: Mutex<Vec<String>>,
    sync_change: Mutex<String>,
}
impl Platform for Host {
    fn observe_devices(&self, _: &Value, publish: &mut dyn FnMut(DeviceObservation)) -> Result<Value, String> {
        publish(self.next.lock().unwrap().take().expect("One actual observation"));
        Ok(json!({}))
    }
    fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        self.calls.lock().unwrap().push(id.into());
        Ok(match id {
            "devices.restore" => json!({"devices": args["devices"]}),
            "sync.run" => {
                let mut shared = args["shared"].clone();
                match self.sync_change.lock().unwrap().as_str() {
                    "address" => shared["devices"][0]["address"] = json!("192.0.2.44"),
                    "alias" => shared["devices"][0]["displayName"] = json!("New alias"),
                    "remove" => shared["devices"] = json!([]),
                    _ => {}
                }
                json!({"shared": shared})
            }
            _ => json!({}),
        })
    }
}
struct Fixture {
    c: Arc<Controller>,
    host: Arc<Host>,
    path: std::path::PathBuf,
}
impl Fixture {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("rhremote-trust-{}.json", crate::random_token().unwrap()));
        let host = Arc::new(Host::default());
        let c = Controller::new("test", path.clone(), host.clone()).unwrap();
        c.replace_devices(serde_json::from_value(json!([
            {"uuid":"host","name":"Remote PC","address":"192.0.2.1","online":true,"paired":true},
            {"uuid":"other","name":"Other PC","address":"192.0.2.2","online":true,"paired":true}
        ])).unwrap());
        Self {c, host, path}
    }
    fn observe(&self, outcome: O) {
        let mut device: Device = serde_json::from_value(self.c.state()["devices"][0].clone()).unwrap();
        device.online = outcome != O::Unreachable;
        device.paired = outcome == O::Paired;
        *self.host.next.lock().unwrap() = Some(DeviceObservation {
            device,
            pairing_verified: matches!(outcome, O::Paired | O::Unpaired | O::AuthRejected),
            check: Some(DeviceCheck {phase: CheckPhase::ServerInfo, outcome,
                source: if outcome == O::Unreachable {CheckSource::None} else {CheckSource::Https},
                has_certificate: outcome != O::Unreachable, elapsed_us: 1000}),
        });
        assert_eq!(self.c.dispatch("devices.refresh", json!({"uuid":"host"}))["ok"], true);
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        if self.path.exists() {std::fs::remove_file(&self.path).unwrap();}
        let backup = self.path.with_extension("json.pre-sync.bak");
        if backup.exists() {std::fs::remove_file(backup).unwrap();}
    }
}

#[test]
fn device_trust_survives_every_weaker_network_failure_without_hiding_its_reason() {
    for prior in [O::AuthRejected, O::CertificateMismatch, O::Unpaired, O::IdentityMismatch, O::InvalidResponse] {
        for latest in [O::Unreachable, O::Timeout, O::Unavailable, O::TlsFailure] {
            let f = Fixture::new();
            f.observe(prior);
            f.observe(latest);
            let state = f.c.state();
            assert_eq!(state["deviceTrustChecks"]["host"]["outcome"], serde_json::to_value(prior).unwrap(), "A weaker {latest:?} erased {prior:?}");
            assert_eq!(state["deviceChecks"]["host"]["outcome"], serde_json::to_value(latest).unwrap(), "Current network cause was hidden");
            assert!(state["saved"].get("deviceTrustChecks").is_none());
            assert!(state["saved"].get("deviceChecks").is_none());
            assert_eq!(f.c.dispatch("device.connect", json!({"uuid":"host"}))["ok"], false, "Direct API bypassed {prior:?}");
            assert!(!f.host.calls.lock().unwrap().iter().any(|id|id == "device.connect"));
        }
    }
}

#[test]
fn device_trust_direct_api_obeys_the_same_repair_requirement_as_the_button() {
    let f = Fixture::new();
    f.observe(O::CertificateMismatch);
    assert_eq!(f.c.state()["devices"][0]["paired"], true, "Stored identity remains available");
    let result = f.c.dispatch("device.connect", json!({"uuid":"host"}));
    assert_eq!(result["ok"], false, "A connection API bypassed the visible re-pair requirement");
    assert!(!f.host.calls.lock().unwrap().iter().any(|id|id == "device.connect"));
}

#[test]
fn device_trust_clears_only_after_verified_recovery_and_keeps_other_devices_unchanged() {
    let f = Fixture::new();
    let other = f.c.state()["devices"][1].clone();
    f.observe(O::AuthRejected);
    f.observe(O::Timeout);
    assert_eq!(f.c.state()["deviceTrustChecks"]["host"]["outcome"], "auth_rejected");
    f.observe(O::Paired);
    let state = f.c.state();
    assert!(state["deviceTrustChecks"].get("host").is_none());
    assert_eq!(state["deviceChecks"]["host"]["outcome"], "paired");
    assert_eq!(state["devices"][1], other);
    assert_eq!(f.c.dispatch("device.connect", json!({"uuid":"host"}))["ok"], true);
    assert_eq!(f.host.calls.lock().unwrap().iter().filter(|id|*id == "device.connect").count(), 1);
}

#[test]
fn device_trust_successful_explicit_repair_clears_both_reports() {
    let f = Fixture::new();
    f.observe(O::CertificateMismatch);
    f.observe(O::Timeout);
    assert_eq!(f.c.dispatch("device.pair", json!({"uuid":"host","pin":"1234"}))["ok"], true);
    let attempt = f.c.state.lock().unwrap().pair_attempt;
    let mut device: Device = serde_json::from_value(f.c.state()["devices"][0].clone()).unwrap();
    device.online = true;
    device.paired = true;
    f.c.pairing_complete(attempt, device, String::new(), false);
    let state = f.c.state();
    assert!(state["deviceTrustChecks"].get("host").is_none());
    assert!(state["deviceChecks"].get("host").is_none());
    assert_eq!(state["connection"]["phase"], "paired");
    assert_eq!(f.c.dispatch("device.connect", json!({"uuid":"host"}))["ok"], true);
}

#[test]
fn device_trust_route_and_import_changes_invalidate_evidence_but_alias_edits_do_not() {
    for change in ["address", "settings", "sync-address", "sync-alias", "sync-remove", "replace-remove"] {
        let f = Fixture::new();
        f.observe(O::CertificateMismatch);
        f.observe(O::Timeout);
        assert!(f.c.state()["deviceTrustChecks"].get("host").is_some());
        let response = match change {
            "address" => f.c.dispatch("device.network.configure", json!({"uuid":"host","address":"192.0.2.44","addresses":[]})),
            "settings" => {
                assert_eq!(f.c.dispatch("device.settings.open", json!({"uuid":"host"}))["ok"], true);
                assert_eq!(f.c.dispatch("settings.set", json!({"key":"networkMode","value":"tailscale"}))["ok"], true);
                f.c.dispatch("settings.save", json!({}))
            }
            "replace-remove" => {f.c.replace_devices(vec![]);json!({"ok":true})}
            change => {
                *f.host.sync_change.lock().unwrap() = change.strip_prefix("sync-").unwrap().into();
                f.c.dispatch("sync.run", json!({}))
            }
        };
        assert_eq!(response["ok"], true, "{change}: {response}");
        let state = f.c.state();
        if change == "sync-alias" {
            assert_eq!(state["deviceTrustChecks"]["host"]["outcome"], "certificate_mismatch");
            assert_eq!(state["deviceChecks"]["host"]["outcome"], "timeout");
        } else {
            assert!(state["deviceTrustChecks"].get("host").is_none(), "Stale trust survived {change}");
            assert!(state["deviceChecks"].get("host").is_none(), "Stale check survived {change}");
        }
    }
}
