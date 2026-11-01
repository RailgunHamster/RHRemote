//! Restore cards from local saved configuration without a startup network probe.
use rhremote_core::Platform;
use serde_json::{Value, json};

pub(crate) fn restore(saved: &Value, platform: &dyn Platform) -> Result<(), String> {
    let devices = saved["devices"].as_array().cloned().unwrap_or_default();
    let authoritative = saved["deviceMembershipAuthoritative"] == true;
    if authoritative || !devices.is_empty() {
        let rows:Vec<_>=devices.iter().map(|d|json!({"uuid":d["uuid"],"name":d["name"],"address":d["address"],"addresses":d["addresses"].as_array().cloned().unwrap_or_default(),"online":false,"paired":false,"isSelf":false})).collect();
        platform.execute(
            "devices.restore",
            &json!({"devices":rows,"localOnly":true,"replace":authoritative}),
        )?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::platform::NativePlatform;
    use std::{fs, path::PathBuf, sync::Arc};
    #[test]
    fn legacy_identity_cache_is_still_imported_before_a_shared_list_has_been_applied() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-legacy-membership-{}",
            rhremote_core::random_token().unwrap()
        ));
        let ini = root.join("engine-state/RHRemote/RHRemote.ini");
        fs::create_dir_all(ini.parent().unwrap()).unwrap();
        fs::write(
            &ini,
            b"[hosts]\n1\\uuid=legacy\n1\\hostname=Legacy PC\n1\\manualaddress=192.0.2.9\n",
        )
        .unwrap();
        let platform = Arc::new(
            NativePlatform::new(
                PathBuf::from("unused-legacy.exe"),
                "fixture-token".into(),
                root.clone(),
            )
            .unwrap(),
        );
        let controller =
            rhremote_core::Controller::new("test", root.join("preferences.json"), platform.clone())
                .unwrap();
        assert_eq!(
            controller.state()["saved"]["deviceMembershipAuthoritative"],
            false
        );
        restore(&controller.state()["saved"], &*platform).unwrap();
        assert_eq!(platform.devices.lock().unwrap().len(), 1);
        assert_eq!(platform.devices.lock().unwrap()[0].uuid, "legacy");
        drop(controller);
        drop(platform);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn authoritative_import_membership_survives_two_cold_reloads_including_an_empty_list() {
        let mut counts = Vec::new();
        for keep in [false, true] {
            let root = std::env::temp_dir().join(format!(
                "rhremote-import-reload-{}",
                rhremote_core::random_token().unwrap()
            ));
            let ini = root.join("engine-state/RHRemote/RHRemote.ini");
            fs::create_dir_all(ini.parent().unwrap()).unwrap();
            let identity=b"[General]\nfixture=test-owned identity sentinel\n[hosts]\n1\\uuid=removed\n1\\hostname=Old PC\n1\\manualaddress=192.0.2.9\n";
            fs::write(&ini, identity).unwrap();
            let records = if keep {
                json!([{"uuid":"kept","name":"Kept PC","address":"192.0.2.1"}])
            } else {
                json!([])
            };
            let preferences = root.join("preferences.json");
            fs::write(&preferences,json!({"deviceOrderInitialized":true,"deviceMembershipAuthoritative":true,"devices":records}).to_string()).unwrap();
            for _ in 0..2 {
                let platform = Arc::new(
                    NativePlatform::new(
                        PathBuf::from("unused-reload.exe"),
                        "fixture-token".into(),
                        root.clone(),
                    )
                    .unwrap(),
                );
                let controller =
                    rhremote_core::Controller::new("test", preferences.clone(), platform.clone())
                        .unwrap();
                restore(&controller.state()["saved"], &*platform).unwrap();
                counts.push(platform.devices.lock().unwrap().len());
                assert_eq!(
                    fs::read(&ini).unwrap(),
                    identity,
                    "card membership must not erase native identities"
                );
                drop(controller);
                drop(platform);
            }
            fs::remove_dir_all(root).unwrap();
        }
        assert_eq!(
            counts,
            vec![0, 0, 1, 1],
            "an imported removal returned from the legacy native INI after restart"
        );
    }
}
