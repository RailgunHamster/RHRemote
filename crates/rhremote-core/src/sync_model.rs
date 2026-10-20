use crate::{Controller, Profile, Saved, State};
use serde::{Deserialize, Deserializer, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Resolution {
    Review,
    Local,
    Remote,
}
pub struct MergeResult {
    pub merged: SharedConfig,
    pub conflicts: Vec<String>,
}

fn choose<T: Clone + PartialEq>(
    local: Option<&T>,
    remote: Option<&T>,
    base: Option<&T>,
    key: String,
    resolution: Resolution,
    conflicts: &mut Vec<String>,
) -> Option<T> {
    match (local, remote) {
        (None, r) => r.cloned(),
        (l, None) => l.cloned(),
        (Some(l), Some(r)) if l == r => Some(l.clone()),
        (Some(l), Some(r)) if Some(l) == base => Some(r.clone()),
        (Some(l), Some(r)) if Some(r) == base => Some(l.clone()),
        (Some(l), Some(r)) => {
            if resolution == Resolution::Review {
                conflicts.push(key);
            }
            Some(
                if resolution == Resolution::Remote {
                    r
                } else {
                    l
                }
                .clone(),
            )
        }
    }
}
fn merge_map<T: Clone + PartialEq>(
    local: &BTreeMap<String, T>,
    remote: &BTreeMap<String, T>,
    base: Option<&BTreeMap<String, T>>,
    prefix: &str,
    resolution: Resolution,
    conflicts: &mut Vec<String>,
) -> BTreeMap<String, T> {
    local
        .keys()
        .chain(remote.keys())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .filter_map(|key| {
            choose(
                local.get(key),
                remote.get(key),
                base.and_then(|b| b.get(key)),
                format!("{prefix}.{key}"),
                resolution,
                conflicts,
            )
            .map(|v| (key.clone(), v))
        })
        .collect()
}

/// Missing entries never implicitly delete hosts or another client's profiles.
pub fn merge(
    local: &SharedConfig,
    remote: &SharedConfig,
    base: Option<&SharedConfig>,
    resolution: Resolution,
) -> MergeResult {
    let mut conflicts = Vec::new();
    let mut profiles = BTreeMap::new();
    let empty = BTreeMap::new();
    for client in local
        .profiles
        .keys()
        .chain(remote.profiles.keys())
        .collect::<BTreeSet<_>>()
    {
        profiles.insert(
            client.clone(),
            merge_map(
                local.profiles.get(client).unwrap_or(&empty),
                remote.profiles.get(client).unwrap_or(&empty),
                base.and_then(|b| b.profiles.get(client)),
                &format!("profiles.{client}"),
                resolution,
                &mut conflicts,
            ),
        );
    }
    let map = |devices: &[SharedDevice]| {
        devices
            .iter()
            .map(|d| (d.uuid.clone(), d.clone()))
            .collect::<BTreeMap<_, _>>()
    };
    let bd = base.map(|b| map(&b.devices));
    let devices = merge_map(
        &map(&local.devices),
        &map(&remote.devices),
        bd.as_ref(),
        "devices",
        resolution,
        &mut conflicts,
    )
    .into_values()
    .collect();
    let clients = merge_map(
        &local.clients,
        &remote.clients,
        base.map(|b| &b.clients),
        "clients",
        resolution,
        &mut conflicts,
    );
    let dark = choose(
        Some(&local.dark),
        Some(&remote.dark),
        base.map(|b| &b.dark),
        "appearance.dark".into(),
        resolution,
        &mut conflicts,
    )
    .unwrap();
    MergeResult {
        merged: SharedConfig {
            schema_version: 2,
            devices,
            clients,
            profiles,
            dark,
        },
        conflicts,
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SharedDevice {
    pub uuid: String,
    pub name: String,
    pub address: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub swap_buttons: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientInfo {
    pub name: String,
    pub platform: String,
}

/// Host mouse-button swapping belongs to SharedDevice, never to a client profile.
#[derive(Clone, Debug, PartialEq)]
pub struct ClientProfile(Profile);
impl std::ops::Deref for ClientProfile {
    type Target = Profile;
    fn deref(&self) -> &Profile {
        &self.0
    }
}
impl std::ops::DerefMut for ClientProfile {
    fn deref_mut(&mut self) -> &mut Profile {
        &mut self.0
    }
}
impl Serialize for ClientProfile {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut v = serde_json::to_value(&self.0).map_err(serde::ser::Error::custom)?;
        v.as_object_mut().unwrap().remove("swapButtons");
        v.serialize(serializer)
    }
}
impl<'de> Deserialize<'de> for ClientProfile {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let v = serde_json::Value::deserialize(deserializer)?;
        if v.get("swapButtons").is_some() {
            return Err(serde::de::Error::custom(
                "host facts do not belong in client profiles",
            ));
        }
        for key in [
            "width",
            "height",
            "fps",
            "bitrateMbps",
            "codec",
            "trackpad",
            "statistics",
        ] {
            if v.get(key).is_none() {
                return Err(serde::de::Error::custom(format!("missing {key}")));
            }
        }
        let p: Profile = serde_json::from_value(v).map_err(serde::de::Error::custom)?;
        p.validate().map_err(serde::de::Error::custom)?;
        Ok(Self(p))
    }
}
impl ClientProfile {
    pub fn from_profile(p: &Profile) -> Self {
        let mut p = p.clone();
        p.swap_buttons = false;
        Self(p)
    }
    pub fn to_profile(&self) -> Profile {
        self.0.clone()
    }
}

/// Portable allowlist. The current client ID, credentials, pairing and isSelf stay local.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SharedConfig {
    pub schema_version: u32,
    pub devices: Vec<SharedDevice>,
    pub clients: BTreeMap<String, ClientInfo>,
    pub profiles: BTreeMap<String, BTreeMap<String, ClientProfile>>,
    pub dark: bool,
}
impl<'de> Deserialize<'de> for SharedConfig {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = serde_json::Value::deserialize(deserializer)?;
        let result = match value["schemaVersion"].as_u64() {
            Some(1) => {
                #[derive(Deserialize)]
                #[serde(rename_all = "camelCase", deny_unknown_fields)]
                struct Legacy {
                    schema_version: u32,
                    devices: Vec<SharedDevice>,
                    profiles: BTreeMap<String, Profile>,
                    dark: bool,
                }
                let old: Legacy =
                    serde_json::from_value(value).map_err(serde::de::Error::custom)?;
                let _ = old.schema_version;
                let mut devices = old.devices;
                for d in &mut devices {
                    if let Some(p) = old.profiles.get(&d.uuid) {
                        d.swap_buttons = p.swap_buttons;
                    }
                }
                // A legacy remote profile has no reliable owner. Keep it available to copy.
                Self {
                    schema_version: 2,
                    devices,
                    clients: BTreeMap::from([(
                        "legacy-v1".into(),
                        ClientInfo {
                            name: "旧版配置".into(),
                            platform: "legacy".into(),
                        },
                    )]),
                    profiles: BTreeMap::from([(
                        "legacy-v1".into(),
                        old.profiles
                            .iter()
                            .map(|(id, p)| (id.clone(), ClientProfile::from_profile(p)))
                            .collect(),
                    )]),
                    dark: old.dark,
                }
            }
            Some(2) => {
                #[derive(Deserialize)]
                #[serde(rename_all = "camelCase", deny_unknown_fields)]
                struct V2 {
                    schema_version: u32,
                    devices: Vec<SharedDevice>,
                    clients: BTreeMap<String, ClientInfo>,
                    profiles: BTreeMap<String, BTreeMap<String, ClientProfile>>,
                    dark: bool,
                }
                let v: V2 = serde_json::from_value(value).map_err(serde::de::Error::custom)?;
                Self {
                    schema_version: v.schema_version,
                    devices: v.devices,
                    clients: v.clients,
                    profiles: v.profiles,
                    dark: v.dark,
                }
            }
            _ => return Err(serde::de::Error::custom("unsupported sync schema")),
        };
        result.validate().map_err(serde::de::Error::custom)?;
        Ok(result)
    }
}
fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 && !id.chars().any(char::is_control)
}
pub(crate) fn display_name(value: &str) -> Result<String, String> {
    let name = value.trim();
    if name.len() > 256 || name.chars().any(char::is_control) {
        return Err("别名不能超过 256 字节，且不能包含控制字符".into());
    }
    Ok(name.into())
}
impl SharedConfig {
    pub fn validate(&self) -> Result<(), String> {
        if self.schema_version != 2 {
            return Err("unsupported sync schema".into());
        }
        if self.devices.len() > 1000
            || self.clients.len() > 100
            || self.profiles.len() > 100
            || self.profiles.values().map(|g| g.len()).sum::<usize>() > 10_000
        {
            return Err("configuration exceeds supported limits".into());
        }
        let mut ids = BTreeSet::new();
        for d in &self.devices {
            display_name(&d.display_name)?;
            if !valid_id(&d.uuid) || !ids.insert(d.uuid.to_ascii_lowercase()) {
                return Err("empty or duplicate device UUID".into());
            }
            if d.name.len() > 256
                || d.address.is_empty()
                || d.address.len() > 253
                || d.address.contains(['/', '\\', '@', '\r', '\n', '?', '#'])
            {
                return Err("invalid device metadata".into());
            }
        }
        for (id, c) in &self.clients {
            if !valid_id(id)
                || c.name.is_empty()
                || c.name.len() > 256
                || !["windows", "android", "legacy"].contains(&c.platform.as_str())
            {
                return Err("invalid client metadata".into());
            }
        }
        for (client, group) in &self.profiles {
            if !self.clients.contains_key(client) {
                return Err("profile client is missing".into());
            }
            for (id, p) in group {
                if !valid_id(id) {
                    return Err("invalid profile UUID".into());
                }
                p.to_profile().validate()?;
            }
        }
        Ok(())
    }
}
impl Saved {
    pub(crate) fn initialize_client(&mut self, platform: &str) -> Result<(), String> {
        if self.client_id.is_empty() {
            self.client_id = format!("client-{}", &crate::random_token()?[..32]);
        }
        self.clients
            .entry(self.client_id.clone())
            .or_insert_with(|| ClientInfo {
                name: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows".into()),
                platform: if platform == "android" {
                    "android"
                } else {
                    "windows"
                }
                .into(),
            });
        if self.profile_schema_version < 2 {
            for d in &mut self.devices {
                if let Some(p) = self.profiles.get(&d.uuid) {
                    d.swap_buttons = p.swap_buttons;
                }
            }
            self.client_profiles.insert(
                self.client_id.clone(),
                self.profiles
                    .iter()
                    .map(|(id, p)| (id.clone(), ClientProfile::from_profile(p)))
                    .collect(),
            );
            self.profile_schema_version = 2;
        }
        Ok(())
    }
    pub fn effective_profile(&self, uuid: &str) -> Profile {
        let mut p = self.profiles.get(uuid).cloned().unwrap_or_default();
        if let Some(d) = self.devices.iter().find(|d| d.uuid == uuid) {
            p.swap_buttons = d.swap_buttons;
        }
        p
    }
    pub(crate) fn save_profile(&mut self, uuid: &str, p: Profile) {
        if let Some(d) = self.devices.iter_mut().find(|d| d.uuid == uuid) {
            d.swap_buttons = p.swap_buttons;
        }
        self.client_profiles
            .entry(self.client_id.clone())
            .or_default()
            .insert(uuid.into(), ClientProfile::from_profile(&p));
        self.profiles.insert(uuid.into(), p);
    }
    pub(crate) fn apply_shared(&mut self, shared: &SharedConfig) {
        self.devices = shared.devices.clone();
        self.clients = shared.clients.clone();
        self.client_profiles = shared.profiles.clone();
        self.dark = shared.dark;
        self.profiles = shared
            .profiles
            .get(&self.client_id)
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|(id, p)| {
                let mut value = p.to_profile();
                value.swap_buttons = shared
                    .devices
                    .iter()
                    .find(|d| d.uuid == id)
                    .is_some_and(|d| d.swap_buttons);
                (id, value)
            })
            .collect();
    }
}
pub(crate) fn from_state(s: &State) -> SharedConfig {
    let mut devices: BTreeMap<_, _> = s
        .saved
        .devices
        .iter()
        .map(|d| (d.uuid.clone(), d.clone()))
        .collect();
    for d in &s.devices {
        let swap_buttons = devices
            .get(&d.uuid)
            .map(|d| d.swap_buttons)
            .unwrap_or_else(|| {
                s.saved
                    .profiles
                    .get(&d.uuid)
                    .is_some_and(|p| p.swap_buttons)
            });
        devices.insert(
            d.uuid.clone(),
            SharedDevice {
                uuid: d.uuid.clone(),
                name: d.name.clone(),
                address: d.address.clone(),
                display_name: devices
                    .get(&d.uuid)
                    .map(|h| h.display_name.clone())
                    .unwrap_or_else(|| d.display_name.clone()),
                swap_buttons,
            },
        );
    }
    let mut profiles = s.saved.client_profiles.clone();
    profiles.insert(
        s.saved.client_id.clone(),
        s.saved
            .profiles
            .iter()
            .map(|(id, p)| (id.clone(), ClientProfile::from_profile(p)))
            .collect(),
    );
    SharedConfig {
        schema_version: 2,
        devices: devices.into_values().collect(),
        clients: s.saved.clients.clone(),
        profiles,
        dark: s.saved.dark,
    }
}
impl Controller {
    pub fn export_shared(&self) -> SharedConfig {
        from_state(&self.state.lock().unwrap())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Device, Platform};
    use serde_json::{Value, json};
    use std::sync::Arc;
    struct Stub;
    impl Platform for Stub {
        fn execute(&self, _: &str, _: &Value) -> Result<Value, String> {
            Err("unused".into())
        }
    }
    fn sample() -> SharedConfig {
        SharedConfig {
            schema_version: 2,
            devices: vec![SharedDevice {
                uuid: "self".into(),
                name: "PC".into(),
                address: "local".into(),
                display_name: String::new(),
                swap_buttons: true,
            }],
            clients: BTreeMap::from([
                (
                    "pc".into(),
                    ClientInfo {
                        name: "PC".into(),
                        platform: "windows".into(),
                    },
                ),
                (
                    "pad".into(),
                    ClientInfo {
                        name: "OPPO".into(),
                        platform: "android".into(),
                    },
                ),
            ]),
            profiles: BTreeMap::from([
                (
                    "pc".into(),
                    BTreeMap::from([(
                        "self".into(),
                        ClientProfile::from_profile(&Profile::default()),
                    )]),
                ),
                (
                    "pad".into(),
                    BTreeMap::from([(
                        "self".into(),
                        ClientProfile::from_profile(&Profile::default()),
                    )]),
                ),
            ]),
            dark: false,
        }
    }
    #[test]
    fn different_clients_merge_without_overwriting_their_resolution() {
        let base = sample();
        let mut local = base.clone();
        let mut remote = base.clone();
        local
            .profiles
            .get_mut("pc")
            .unwrap()
            .get_mut("self")
            .unwrap()
            .fps = 60;
        let p = remote
            .profiles
            .get_mut("pad")
            .unwrap()
            .get_mut("self")
            .unwrap();
        p.width = 2520;
        p.height = 1680;
        remote.devices.clear();
        let merged = merge(&local, &remote, Some(&base), Resolution::Review);
        assert!(merged.conflicts.is_empty());
        assert_eq!(merged.merged.profiles["pc"]["self"].width, 1920);
        assert_eq!(merged.merged.profiles["pad"]["self"].width, 2520);
        assert_eq!(merged.merged.devices[0].uuid, "self");
        let mut saved = Saved::default();
        saved.client_id = "pc".into();
        saved.apply_shared(&merged.merged);
        assert_eq!(saved.effective_profile("self").width, 1920);
        assert!(saved.effective_profile("self").swap_buttons);
    }
    #[test]
    fn same_client_host_conflict_requires_explicit_choice() {
        let base = sample();
        let mut local = base.clone();
        let mut remote = base.clone();
        local
            .profiles
            .get_mut("pc")
            .unwrap()
            .get_mut("self")
            .unwrap()
            .bitrate_mbps = 40;
        remote
            .profiles
            .get_mut("pc")
            .unwrap()
            .get_mut("self")
            .unwrap()
            .bitrate_mbps = 15;
        assert_eq!(
            merge(&local, &remote, Some(&base), Resolution::Review).conflicts,
            vec!["profiles.pc.self"]
        );
        assert_eq!(
            merge(&local, &remote, Some(&base), Resolution::Remote)
                .merged
                .profiles["pc"]["self"]
                .bitrate_mbps,
            15
        );
    }
    #[test]
    fn legacy_remote_profiles_are_preserved_for_copy_only() {
        let old = json!({"schemaVersion":1,"devices":[{"uuid":"self","name":"PC","address":"local"}],"profiles":{"self":Profile{width:3840,height:2160,swap_buttons:true,..Profile::default()}},"dark":false});
        let migrated: SharedConfig = serde_json::from_value(old).unwrap();
        assert_eq!(migrated.profiles["legacy-v1"]["self"].width, 3840);
        let mut saved = Saved::default();
        saved.client_id = "new-pc".into();
        saved.apply_shared(&migrated);
        assert_eq!(saved.effective_profile("self").width, 1920);
        assert!(saved.effective_profile("self").swap_buttons);
    }
    #[test]
    fn local_migration_identity_restart_and_copy_cancel() {
        let path = std::env::temp_dir().join(format!(
            "rhremote-migrate-{}.json",
            crate::random_token().unwrap()
        ));
        let old = json!({"profiles":{"self":Profile{width:2520,height:1680,swap_buttons:true,..Profile::default()}},"devices":[{"uuid":"self","name":"PC","address":"local"}],"dark":false});
        std::fs::write(&path, old.to_string()).unwrap();
        let c = Controller::new("windows", path.clone(), Arc::new(Stub)).unwrap();
        let id = c.state()["saved"]["clientId"].as_str().unwrap().to_owned();
        assert_eq!(c.profile("self").width, 2520);
        assert!(c.profile("self").swap_buttons);
        assert_eq!(
            Controller::new("windows", path.clone(), Arc::new(Stub))
                .unwrap()
                .state()["saved"]["clientId"],
            id
        );
        c.replace_devices(vec![Device {
            uuid: "self".into(),
            name: "PC".into(),
            address: "local".into(),
            display_name: String::new(),
            online: true,
            paired: true,
            is_self: true,
        }]);
        {
            let mut s = c.state.lock().unwrap();
            s.saved.client_profiles.insert(
                "pad".into(),
                BTreeMap::from([(
                    "self".into(),
                    ClientProfile::from_profile(&Profile::default()),
                )]),
            );
        }
        assert_eq!(
            c.dispatch("device.settings.open", json!({"uuid":"self"}))["ok"],
            true
        );
        assert_eq!(
            c.dispatch("settings.copy_from", json!({"clientId":"pad"}))["ok"],
            true
        );
        assert_eq!(c.state()["draft"]["width"], 1920);
        assert_eq!(c.profile("self").width, 2520);
        c.dispatch("settings.cancel", json!({}));
        assert_eq!(c.profile("self").width, 2520);
        let exported = c.export_shared();
        assert!(exported.devices[0].swap_buttons);
        let v = serde_json::to_value(exported).unwrap();
        assert!(v["devices"][0].get("isSelf").is_none());
        assert!(v["profiles"][&id]["self"].get("swapButtons").is_none());
        let _ = std::fs::remove_file(path);
    }
}
