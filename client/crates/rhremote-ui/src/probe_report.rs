//! Attempt-bound native evidence. Exit codes alone do not distinguish trust failures.
use rhremote_core::{CheckOutcome, CheckPhase, CheckSource, DeviceCheck};
use serde::Deserialize;
use std::{
    fs,
    path::{Path, PathBuf},
};

pub(crate) struct Binding {
    pub id: String,
    pub path: PathBuf,
    pub uuid: String,
}
impl Binding {
    pub fn new(root: &Path, uuid: &str) -> Result<Self, String> {
        let id = rhremote_core::random_token()?;
        Ok(Self {
            path: root.join(format!("probe-report-{id}.json")),
            id,
            uuid: uuid.into(),
        })
    }
    pub fn read(&self) -> Result<Option<DeviceCheck>, String> {
        match fs::metadata(&self.path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(_) => return Err("native report unavailable".into()),
            Ok(meta) if meta.len() > 8192 => return Err("native report oversized".into()),
            _ => {}
        }
        let bytes = fs::read(&self.path).map_err(|_| "native report unavailable")?;
        parse(&bytes, &self.id, &self.uuid).map(Some)
    }
}
impl Drop for Binding {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Report {
    schema_version: u32,
    probe_id: String,
    target_uuid: String,
    actual_uuid: String,
    source: CheckSource,
    has_certificate: bool,
    identity_resolved: bool,
    #[serde(default)]
    pinned_https_auth_rejected: bool,
    outcome: CheckOutcome,
    elapsed_us: u64,
}
fn parse(bytes: &[u8], id: &str, uuid: &str) -> Result<DeviceCheck, String> {
    if bytes.len() > 8192 {
        return Err("native report oversized".into());
    }
    let r: Report = serde_json::from_slice(bytes).map_err(|_| "native report invalid")?;
    if r.schema_version != 1
        || r.probe_id != id
        || !r.target_uuid.eq_ignore_ascii_case(uuid)
        || r.actual_uuid.len() > 128
        || r.elapsed_us > 60_000_000
    {
        return Err("native report context mismatch".into());
    }
    if r.has_certificate && !r.identity_resolved {
        return Err("native identity lookup incomplete".into());
    }
    let matches = r.actual_uuid.eq_ignore_ascii_case(uuid);
    let https = r.source == CheckSource::Https;
    let http = r.source == CheckSource::Http;
    if r.pinned_https_auth_rejected
        && (r.outcome != CheckOutcome::AuthRejected || !r.has_certificate || !https)
    {
        return Err("native pinned rejection evidence inconsistent".into());
    }
    let valid = match r.outcome {
        CheckOutcome::Paired => matches && r.has_certificate && https,
        CheckOutcome::Unpaired => {
            matches
                && r.identity_resolved
                && (r.has_certificate && https || !r.has_certificate && http)
        }
        CheckOutcome::AuthRejected => {
            r.has_certificate
                && (matches && http
                    || https
                        && r.pinned_https_auth_rejected
                        && (r.actual_uuid.is_empty() || matches))
        }
        CheckOutcome::CertificateMismatch | CheckOutcome::TlsFailure => {
            r.has_certificate
                && r.identity_resolved
                && (http || https)
                && (r.actual_uuid.is_empty() || matches)
        }
        CheckOutcome::IdentityMismatch => !r.actual_uuid.is_empty() && !matches && (http || https),
        CheckOutcome::InvalidResponse => r.actual_uuid.is_empty() && (http || https),
        CheckOutcome::Unavailable => r.actual_uuid.is_empty() || matches,
        _ => false,
    };
    if !valid {
        return Err("native report evidence inconsistent".into());
    }
    Ok(DeviceCheck {
        phase: CheckPhase::ServerInfo,
        outcome: r.outcome,
        source: r.source,
        has_certificate: r.has_certificate,
        elapsed_us: r.elapsed_us,
    })
}
pub(crate) fn verified(check: &DeviceCheck) -> bool {
    matches!(
        check.outcome,
        CheckOutcome::Paired | CheckOutcome::Unpaired | CheckOutcome::AuthRejected
    )
}
pub(crate) fn timed_out(current: &mut Option<DeviceCheck>, elapsed_us: u64) {
    if let Some(check) = current {
        if check.outcome == CheckOutcome::Unavailable {
            check.outcome = CheckOutcome::Timeout;
            check.phase = CheckPhase::NativeProcess;
            check.elapsed_us = elapsed_us;
        }
    }
}
pub(crate) fn observe(
    binding: &Binding,
    device: &mut rhremote_core::Device,
    verified_pairing: &mut bool,
    current: &mut Option<DeviceCheck>,
) -> Result<bool, String> {
    let Some(check) = binding.read()? else {
        return Ok(false);
    };
    if check.outcome == CheckOutcome::Unavailable
        && current
            .as_ref()
            .is_some_and(|check| check.outcome != CheckOutcome::Unavailable)
    {
        return Ok(false);
    }
    *verified_pairing = verified(&check);
    if *verified_pairing {
        device.paired = check.outcome == CheckOutcome::Paired;
    }
    let complete = check.outcome != CheckOutcome::Unavailable;
    *current = Some(check);
    Ok(complete)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};
    #[test]
    fn process_timeout_preserves_every_completed_protocol_outcome() {
        for outcome in [
            CheckOutcome::Paired,
            CheckOutcome::Unpaired,
            CheckOutcome::AuthRejected,
            CheckOutcome::CertificateMismatch,
            CheckOutcome::TlsFailure,
            CheckOutcome::IdentityMismatch,
            CheckOutcome::InvalidResponse,
        ] {
            let before = DeviceCheck {
                phase: CheckPhase::ServerInfo,
                outcome,
                source: CheckSource::Https,
                has_certificate: true,
                elapsed_us: 1234,
            };
            let mut check = Some(before.clone());
            timed_out(&mut check, 5_000_000);
            assert_eq!(check, Some(before));
        }
        let mut check = Some(DeviceCheck {
            phase: CheckPhase::ServerInfo,
            outcome: CheckOutcome::Unavailable,
            source: CheckSource::Https,
            has_certificate: true,
            elapsed_us: 1000,
        });
        timed_out(&mut check, 5_000_000);
        assert_eq!(check.as_ref().unwrap().outcome, CheckOutcome::Timeout);
        assert_eq!(check.as_ref().unwrap().phase, CheckPhase::NativeProcess);
        assert_eq!(check.unwrap().elapsed_us, 5_000_000);
    }
    fn report() -> Value {
        json!({"schemaVersion":1,"probeId":"attempt","targetUuid":"host","actualUuid":"host","source":"https","hasCertificate":true,"identityResolved":true,"outcome":"paired","elapsedUs":1234})
    }
    fn decode(value: Value) -> Result<DeviceCheck, String> {
        parse(value.to_string().as_bytes(), "attempt", "host")
    }
    #[test]
    fn paired_requires_the_current_attempt_uuid_pinned_certificate_and_https() {
        assert_eq!(decode(report()).unwrap().outcome, CheckOutcome::Paired);
        for (key, value) in [
            ("probeId", json!("old")),
            ("targetUuid", json!("other")),
            ("actualUuid", json!("other")),
            ("source", json!("http")),
            ("hasCertificate", json!(false)),
            ("elapsedUs", json!(60_000_001)),
            ("schemaVersion", json!(2)),
        ] {
            let mut r = report();
            r[key] = value;
            assert!(decode(r).is_err(), "{key}");
        }
        let mut r = report();
        r["extra"] = json!(true);
        assert!(decode(r).is_err());
        assert!(parse(b"{}", "attempt", "host").is_err());
        assert!(parse(&vec![b' '; 8193], "attempt", "host").is_err());
    }
    #[test]
    fn first_pair_authentication_rejection_and_identity_errors_have_distinct_evidence() {
        let mut r = report();
        r["outcome"] = json!("unpaired");
        assert!(verified(&decode(r.clone()).unwrap()));
        r["hasCertificate"] = json!(false);
        r["source"] = json!("http");
        r["identityResolved"] = json!(false);
        assert!(decode(r.clone()).is_err());
        r["identityResolved"] = json!(true);
        assert_eq!(decode(r.clone()).unwrap().outcome, CheckOutcome::Unpaired);
        r["outcome"] = json!("auth_rejected");
        assert!(decode(r.clone()).is_err());
        r["hasCertificate"] = json!(true);
        assert_eq!(
            decode(r.clone()).unwrap().outcome,
            CheckOutcome::AuthRejected
        );
        for outcome in ["certificate_mismatch", "tls_failure"] {
            let mut tls = r.clone();
            tls["outcome"] = json!(outcome);
            tls["actualUuid"] = json!("");
            assert!(!verified(&decode(tls.clone()).unwrap()));
            tls["hasCertificate"] = json!(false);
            assert!(decode(tls).is_err());
        }
        r["outcome"] = json!("identity_mismatch");
        assert!(decode(r.clone()).is_err());
        r["actualUuid"] = json!("different");
        assert!(!verified(&decode(r.clone()).unwrap()));
        r["outcome"] = json!("unavailable");
        assert!(decode(r).is_err());
    }
    #[test]
    fn a_pinned_https_401_without_a_uuid_is_repairable_only_with_native_pin_evidence() {
        let mut r = report();
        r["outcome"] = json!("auth_rejected");
        r["actualUuid"] = json!("");
        assert!(decode(r.clone()).is_err());
        r["pinnedHttpsAuthRejected"] = json!(true);
        let check = decode(r.clone()).unwrap();
        assert_eq!(check.outcome, CheckOutcome::AuthRejected);
        assert!(verified(&check) && check.repair_required());
        assert!(!check.blocks_connection());
        for (key, value) in [
            ("hasCertificate", json!(false)),
            ("identityResolved", json!(false)),
            ("source", json!("http")),
            ("actualUuid", json!("other")),
            ("outcome", json!("paired")),
            ("probeId", json!("old")),
        ] {
            let mut invalid = r.clone();
            invalid[key] = value;
            assert!(decode(invalid).is_err(), "{key}");
        }
    }
    #[test]
    #[ignore = "requires explicit owned loopback native protocol evidence directory"]
    fn actual_native_protocol_reports_preserve_device_actions() {
        let root = PathBuf::from(std::env::var_os("RHREMOTE_NATIVE_PROTOCOL_EVIDENCE").expect("explicit fixture evidence directory required"));
        let result: Value = serde_json::from_slice(&fs::read(root.join("result.json")).unwrap()).unwrap();
        let cases = result["cases"].as_array().unwrap();
        assert_eq!(cases.len(), 13);
        for case in cases {
            let expected: CheckOutcome = serde_json::from_value(case["expectedOutcome"].clone()).unwrap();
            let check = parse(
                case["report"].to_string().as_bytes(),
                case["probeId"].as_str().unwrap(),
                case["targetUuid"].as_str().unwrap(),
            ).unwrap();
            assert_eq!(check.outcome, expected, "{}", case["case"]);
            assert_eq!(check.repair_required(), matches!(expected, CheckOutcome::Unpaired | CheckOutcome::AuthRejected | CheckOutcome::CertificateMismatch) && case["report"]["hasCertificate"] == true);
            assert_eq!(check.blocks_connection(), expected == CheckOutcome::IdentityMismatch);
            assert_eq!(verified(&check), matches!(expected, CheckOutcome::Paired | CheckOutcome::Unpaired | CheckOutcome::AuthRejected));
        }
    }
    #[test]
    fn each_owned_report_is_removed_without_removing_other_attempts() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-probe-report-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir(&root).unwrap();
        let a = Binding::new(&root, "host").unwrap();
        let b = Binding::new(&root, "host").unwrap();
        assert_ne!(a.id, b.id);
        fs::write(&a.path, b"{}").unwrap();
        fs::write(&b.path, b"{}").unwrap();
        let other = b.path.clone();
        drop(a);
        assert!(other.exists());
        drop(b);
        assert!(!other.exists());
        fs::remove_dir(root).unwrap();
    }
    #[test]
    fn completed_evidence_survives_a_later_incomplete_native_poll() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-probe-sequence-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir(&root).unwrap();
        let binding = Binding::new(&root, "host").unwrap();
        let mut value = report();
        value["probeId"] = json!(binding.id);
        fs::write(&binding.path, value.to_string()).unwrap();
        let mut device: rhremote_core::Device = serde_json::from_value(
            json!({"uuid":"host","name":"PC","address":"192.0.2.1","online":true,"paired":false}),
        )
        .unwrap();
        let mut paired = false;
        let mut check = None;
        assert_eq!(
            observe(&binding, &mut device, &mut paired, &mut check),
            Ok(true)
        );
        assert!(paired && device.paired);
        value["outcome"] = json!("unavailable");
        value["actualUuid"] = json!("");
        fs::write(&binding.path, value.to_string()).unwrap();
        assert_eq!(
            observe(&binding, &mut device, &mut paired, &mut check),
            Ok(false)
        );
        assert!(paired && device.paired);
        assert_eq!(check.as_ref().unwrap().outcome, CheckOutcome::Paired);
        value["outcome"] = json!("identity_mismatch");
        value["actualUuid"] = json!("other");
        fs::write(&binding.path, value.to_string()).unwrap();
        assert_eq!(
            observe(&binding, &mut device, &mut paired, &mut check),
            Ok(true)
        );
        assert!(!paired);
        assert!(check.as_ref().unwrap().blocks_connection());
        drop(binding);
        fs::remove_dir(root).unwrap();
    }
}
