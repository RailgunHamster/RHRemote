//! Shared configuration is not evidence of this installation's live connection.
use crate::{Device, Saved, State, sync_model::SharedConfig};

pub(crate) fn same_route(before: &crate::Profile, after: &crate::Profile) -> bool {
    before.network_mode == after.network_mode
        && before.route_order == after.route_order
        && before.relay_url == after.relay_url
}

/// Preserve local trust for known UUIDs while invalidating observations whose
/// endpoint or route policy changed. New shared UUIDs always need a local check.
pub(crate) fn apply(state: &mut State, before: &Saved, shared: &SharedConfig) {
    let mut pending = Vec::new();
    let rows = shared
        .devices
        .iter()
        .map(|stored| {
            let old = state.devices.iter().find(|old| old.uuid == stored.uuid);
            let old_endpoints = before
                .devices
                .iter()
                .find(|old| old.uuid == stored.uuid)
                .map(|old| (&old.address, &old.addresses))
                .or_else(|| old.map(|old| (&old.address, &old.addresses)));
            let changed = old_endpoints.is_none_or(|(address, addresses)| {
                address != &stored.address || addresses != &stored.addresses
            }) || !same_route(
                &before.effective_profile(&stored.uuid),
                &state.saved.effective_profile(&stored.uuid),
            );
            // Keep these field borrows disjoint from the current device row.
            if changed {state.device_checks.remove(&stored.uuid);state.device_trust_checks.remove(&stored.uuid);}
            if old.is_none() || changed || state.devices_pending.contains(&stored.uuid) {
                pending.push(stored.uuid.clone());
            }
            let mut row = old.cloned().unwrap_or(Device {
                uuid: stored.uuid.clone(),
                name: String::new(),
                display_name: String::new(),
                address: String::new(),
                addresses: Vec::new(),
                online: false,
                paired: false,
                is_self: false,
            });
            row.name = stored.name.clone();
            row.display_name = stored.display_name.clone();
            row.address = stored.address.clone();
            row.addresses = stored.addresses.clone();
            // Keep locally discovered candidates, but do not resurrect configured
            // endpoints explicitly removed from the shared document.
            if let Some(old) = old {
                let previous = before
                    .devices
                    .iter()
                    .find(|saved| saved.uuid == stored.uuid);
                for address in std::iter::once(&old.address).chain(old.addresses.iter()) {
                    let configured = previous.is_some_and(|previous| {
                        previous.address.eq_ignore_ascii_case(address)
                            || previous
                                .addresses
                                .iter()
                                .any(|a| a.eq_ignore_ascii_case(address))
                    });
                    if !configured
                        && row.addresses.len() < crate::network::MAX_ADDRESSES - 1
                        && !row.address.eq_ignore_ascii_case(address)
                        && !row
                            .addresses
                            .iter()
                            .any(|a| a.eq_ignore_ascii_case(address))
                    {
                        row.addresses.push(address.clone());
                    }
                }
            }
            row
        })
        .collect();
    let present:std::collections::BTreeSet<_>=shared.devices.iter().map(|row|row.uuid.clone()).collect();
    state.device_checks.retain(|uuid,_|present.contains(uuid));
    state.device_trust_checks.retain(|uuid,_|present.contains(uuid));
    state.devices = rows;
    state.devices_pending = pending;
    select_available(state);
}

pub(crate) fn select_available(state: &mut State) {
    if !state.streaming
        && !state.connecting
        && !state.devices.iter().any(|row| row.uuid == state.selected)
    {
        state.selected = state
            .devices
            .iter()
            .find(|row| !row.is_self)
            .or_else(|| state.devices.first())
            .map(|row| row.uuid.clone())
            .unwrap_or_default();
    }
}

#[cfg(test)]
mod tests {
    use crate::{Controller, Platform, random_token};
    use serde_json::{Value, json};
    use std::sync::{Arc, Mutex};
    struct Import {
        change: Mutex<Box<dyn Fn(&mut Value) + Send>>,
        restores: Mutex<Vec<Value>>,
    }
    impl Platform for Import {
        fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
            Ok(match id {
                "sync.run" => {
                    let mut shared = args["shared"].clone();
                    (self.change.lock().unwrap())(&mut shared);
                    json!({"shared":shared,"sync":{"phase":"applying"}})
                }
                "devices.restore" => {
                    self.restores.lock().unwrap().push(args.clone());
                    json!({"devices":args["devices"]})
                }
                _ => json!({}),
            })
        }
    }
    #[test]
    fn sync_import_keeps_local_trust_and_marks_only_new_or_changed_routes_unconfirmed() {
        let path =
            std::env::temp_dir().join(format!("rhremote-import-{}", random_token().unwrap()));
        std::fs::create_dir(&path).unwrap();
        let platform = Arc::new(Import {
            change: Mutex::new(Box::new(|shared| {
                shared["devices"][0]["displayName"] = json!("New alias");
                shared["devices"][1]["address"] = json!("100.64.0.9");
                shared["devices"][1]["addresses"] = json!(["10.1.2.3"]);
                shared["devices"].as_array_mut().unwrap().push(json!({"uuid":"new","name":"New PC","address":"192.0.2.8","displayName":"","swapButtons":false}));
            })),
            restores: Mutex::new(Vec::new()),
        });
        let c = Controller::new("test", path.join("preferences.json"), platform.clone()).unwrap();
        c.replace_devices(serde_json::from_value(json!([
            {"uuid":"known","name":"Known PC","address":"192.0.2.1","online":true,"paired":true,"isSelf":true},
            {"uuid":"changed","name":"Changed PC","address":"192.0.2.2","online":true,"paired":true},
            {"uuid":"pending","name":"Pending PC","address":"192.0.2.3","online":false,"paired":false}
        ])).unwrap());
        {
            let mut state = c.state.lock().unwrap();
            state.saved.devices = crate::sync_model::from_state(&state).devices;
            state.devices_pending = vec!["pending".into()];
        }
        let result = c.dispatch("sync.run", json!({}));
        let state = c.state();
        let saved: Value = serde_json::from_slice(&std::fs::read(&c.storage).unwrap()).unwrap();
        let shared_export =
            serde_json::to_value(crate::sync_model::from_state(&c.state.lock().unwrap())).unwrap();
        assert!(
            shared_export.get("deviceMembershipAuthoritative").is_none(),
            "membership bookkeeping is local to this installation"
        );
        drop(c);
        std::fs::remove_dir_all(path).unwrap();
        assert_eq!(result["ok"], true, "{result}");
        assert_eq!(
            state["devicesPending"],
            json!(["changed", "pending", "new"])
        );
        assert_eq!(state["devices"][0]["paired"], true);
        assert_eq!(state["devices"][0]["isSelf"], true);
        assert_eq!(state["devices"][0]["displayName"], "New alias");
        assert_eq!(
            state["devices"][1]["paired"], true,
            "route changes must not delete local trust"
        );
        assert_eq!(state["devices"][1]["addresses"], json!(["10.1.2.3"]));
        assert_eq!(state["devices"][3]["paired"], false);
        assert_eq!(state["devices"][3]["online"], false);
        assert_eq!(
            platform.restores.lock().unwrap()[0]["replace"],
            true,
            "removed rows must not return from native cache"
        );
        assert!(saved.get("devicesPending").is_none());
        assert_eq!(
            saved["deviceMembershipAuthoritative"], true,
            "cold start must not restore removed cards from the legacy identity cache"
        );
        assert!(saved["devices"][0].get("paired").is_none());
    }
    #[test]
    fn local_edits_invalidate_route_checks_but_keep_alias_video_and_failed_saves_local() {
        let path = std::env::temp_dir().join(format!(
            "rhremote-edit-readiness-{}",
            random_token().unwrap()
        ));
        std::fs::create_dir(&path).unwrap();
        let platform = Arc::new(Import {
            change: Mutex::new(Box::new(|_| {})),
            restores: Mutex::new(Vec::new()),
        });
        let c = Controller::new("test", path.join("preferences.json"), platform).unwrap();
        c.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"PC","address":"192.0.2.1","online":true,"paired":true}])).unwrap());
        assert_eq!(
            c.dispatch("device.settings.open", json!({"uuid":"host"}))["ok"],
            true
        );
        assert_eq!(
            c.dispatch("settings.set", json!({"key":"bitrateMbps","value":28}))["ok"],
            true
        );
        assert_eq!(
            c.dispatch("settings.save", json!({"displayName":"New alias"}))["ok"],
            true
        );
        assert_eq!(
            c.state()["devicesPending"],
            json!([]),
            "video and alias edits do not change reachability"
        );
        assert_eq!(
            c.dispatch("device.settings.open", json!({"uuid":"host"}))["ok"],
            true
        );
        assert_eq!(
            c.dispatch(
                "settings.set",
                json!({"key":"networkMode","value":"tailscale"})
            )["ok"],
            true
        );
        assert_eq!(c.dispatch("settings.save", json!({}))["ok"], true);
        assert_eq!(c.state()["devicesPending"], json!(["host"]));
        assert_eq!(c.state()["devices"][0]["paired"], true);
        c.state.lock().unwrap().devices_pending.clear();
        let arguments = json!({"uuid":"host","address":"192.0.2.1","addresses":["100.64.0.2"]});
        assert_eq!(
            c.dispatch("device.network.configure", arguments.clone())["ok"],
            true
        );
        assert_eq!(c.state()["devicesPending"], json!(["host"]));
        c.state.lock().unwrap().devices_pending.clear();
        assert_eq!(
            c.dispatch("device.network.configure", arguments)["ok"],
            true
        );
        assert_eq!(
            c.state()["devicesPending"],
            json!([]),
            "saving identical endpoints is not a new route"
        );
        std::fs::rename(&c.storage, path.join("before.json")).unwrap();
        std::fs::create_dir(&c.storage).unwrap();
        assert_eq!(
            c.dispatch(
                "device.network.configure",
                json!({"uuid":"host","address":"192.0.2.9","addresses":[]})
            )["ok"],
            false
        );
        assert_eq!(c.state()["devicesPending"], json!([]));
        assert_eq!(c.state()["devices"][0]["address"], "192.0.2.1");
        drop(c);
        std::fs::remove_dir_all(path).unwrap();
    }
    #[test]
    fn removing_the_selected_shared_device_leaves_a_valid_card_and_no_stale_pending_id() {
        let path = std::env::temp_dir().join(format!(
            "rhremote-import-remove-{}",
            random_token().unwrap()
        ));
        std::fs::create_dir(&path).unwrap();
        let platform = Arc::new(Import {
            change: Mutex::new(Box::new(|shared| {
                shared["devices"].as_array_mut().unwrap().remove(0);
            })),
            restores: Mutex::new(Vec::new()),
        });
        let c = Controller::new("test", path.join("preferences.json"), platform).unwrap();
        c.replace_devices(serde_json::from_value(json!([
            {"uuid":"removed","name":"Old PC","address":"192.0.2.1","online":true,"paired":true},
            {"uuid":"kept","name":"Kept PC","address":"192.0.2.2","online":true,"paired":true}
        ])).unwrap());
        {
            let mut state = c.state.lock().unwrap();
            state.saved.devices = crate::sync_model::from_state(&state).devices;
            state.devices_pending = vec!["removed".into()];
        }
        assert_eq!(c.dispatch("app.settings.open", json!({}))["ok"], true);
        assert_eq!(c.dispatch("sync.run", json!({}))["ok"], true);
        assert_eq!(c.state()["selected"], "kept");
        assert_eq!(c.state()["devicesPending"], json!([]));
        assert_eq!(c.state()["devices"].as_array().unwrap().len(), 1);
        assert_eq!(c.dispatch("devices.open", json!({}))["ok"], true);
        assert_eq!(c.state()["selected"], "kept");
        drop(c);
        std::fs::remove_dir_all(path).unwrap();
    }
}
