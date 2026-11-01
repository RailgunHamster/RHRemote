//! Local proxy preferences. Credentials, machine-local addresses and proxy choices
//! never enter the shared WebDAV document.
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{fs, io::Write, path::Path};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    pub enabled: bool,
    pub protocol: String,
    pub host: String,
    pub port: u16,
    pub updates: bool,
    pub sync: bool,
    pub remote: bool,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            enabled: false,
            protocol: "http".into(),
            host: "127.0.0.1".into(),
            port: 7890,
            updates: true,
            sync: true,
            remote: true,
        }
    }
}
impl Settings {
    pub fn validate(&self) -> Result<(), String> {
        if !matches!(self.protocol.as_str(), "http" | "https") {
            return Err("请选择 HTTP 或 HTTPS".into());
        }
        if self.host.is_empty()
            || self.host.len() > 253
            || self.host != self.host.trim()
            || self.host.bytes().any(|b| {
                b <= 32
                    || b >= 127
                    || matches!(b, b'/' | b'\\' | b'@' | b'?' | b'#' | b'%' | b'[' | b']')
            })
        {
            return Err("代理地址只填写主机名或 IP，不含协议和端口".into());
        }
        if self.port == 0 {
            return Err("代理端口应为 1–65535".into());
        }
        if self.host.contains(':') && self.host.parse::<std::net::Ipv6Addr>().is_err() {
            return Err("代理地址中不能包含端口".into());
        }
        let parsed = url::Url::parse(&self.address()).map_err(|_| "代理地址无效")?;
        if parsed.host_str().is_none() {
            return Err("代理地址无效".into());
        }
        Ok(())
    }
    pub fn address(&self) -> String {
        format!(
            "{}://{}:{}",
            self.protocol,
            if self.host.contains(':') {
                format!("[{}]", self.host)
            } else {
                self.host.clone()
            },
            self.port
        )
    }
    pub fn for_scope(&self, scope: &str) -> Option<String> {
        (self.enabled
            && match scope {
                "updates" => self.updates,
                "sync" => self.sync,
                "remote" => self.remote,
                _ => false,
            })
        .then(|| self.address())
    }
    pub fn form(&self) -> Value {
        let mut value = serde_json::to_value(self).unwrap();
        value["port"] = json!(self.port.to_string());
        value
    }
    pub fn from_form(value: &Value) -> Result<Self, String> {
        let mut value = value.clone();
        if let Some(text) = value["port"].as_str() {
            if text.is_empty() || !text.bytes().all(|b| b.is_ascii_digit()) {
                return Err("代理端口应为 1–65535".into());
            }
            value["port"] = json!(text.parse::<u16>().map_err(|_| "代理端口应为 1–65535")?);
        }
        let result: Self = serde_json::from_value(value).map_err(|_| "代理设置格式无效")?;
        result.validate()?;
        Ok(result)
    }
    pub fn load(directory: &Path) -> Result<Self, String> {
        let path = directory.join("proxy-local.json");
        let metadata = match fs::symlink_metadata(&path) {
            Ok(m) => m,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Self::default()),
            Err(_) => return Err("代理设置无法读取".into()),
        };
        if !metadata.file_type().is_file() || metadata.len() > 4096 {
            return Err("代理配置文件无效".into());
        }
        let value: Self = serde_json::from_slice(&fs::read(path).map_err(|_| "代理设置无法读取")?)
            .map_err(|_| "代理设置已损坏，请重新保存")?;
        value.validate()?;
        Ok(value)
    }
    pub fn save(&self, directory: &Path) -> Result<(), String> {
        self.validate()?;
        fs::create_dir_all(directory).map_err(|_| "代理设置目录不可写")?;
        let temporary = directory.join("proxy-local.json.tmp");
        let mut file = fs::File::create(&temporary).map_err(|_| "代理设置无法保存")?;
        file.write_all(&serde_json::to_vec(self).unwrap())
            .and_then(|_| file.sync_all())
            .map_err(|_| "代理设置无法保存")?;
        drop(file);
        fs::rename(&temporary, directory.join("proxy-local.json"))
            .map_err(|_| "代理设置无法保存")?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn scopes_and_protocols_are_explicit() {
        let mut s = Settings::default();
        assert!(s.for_scope("updates").is_none());
        s.enabled = true;
        s.sync = false;
        assert_eq!(s.for_scope("updates").unwrap(), "http://127.0.0.1:7890");
        assert!(s.for_scope("sync").is_none());
        s.protocol = "https".into();
        s.host = "::1".into();
        assert_eq!(s.for_scope("remote").unwrap(), "https://[::1]:7890");
        s.validate().unwrap();
    }
    #[test]
    fn invalid_hosts_and_ports_never_reach_network() {
        for host in [
            "http://127.0.0.1",
            "a:7890",
            "a/b",
            "u@host",
            "host\r\nX:y",
            "",
            "a?b",
        ] {
            let s = Settings {
                host: host.into(),
                ..Settings::default()
            };
            assert!(s.validate().is_err(), "{host}");
        }
        for port in ["", "-1", "65536", "7890x"] {
            let mut value = Settings::default().form();
            value["port"] = json!(port);
            assert!(Settings::from_form(&value).is_err());
        }
    }
    #[test]
    fn saved_configuration_survives_restart() {
        let directory = std::env::temp_dir().join(format!(
            "rhremote-proxy-{}-{}",
            std::process::id(),
            crate::now_ms()
        ));
        let s = Settings {
            enabled: true,
            sync: false,
            ..Settings::default()
        };
        s.save(&directory).unwrap();
        assert_eq!(Settings::load(&directory).unwrap(), s);
        fs::remove_file(directory.join("proxy-local.json")).unwrap();
        fs::remove_dir(directory).unwrap();
    }
    #[test]
    fn controller_keeps_invalid_drafts_and_blocks_busy_changes() {
        use std::sync::{Arc, Mutex};
        struct Native(Mutex<Settings>);
        impl crate::Platform for Native {
            fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
                match id {
                    "proxy.state" => Ok(self.0.lock().unwrap().form()),
                    "proxy.configure" => {
                        let s = Settings::from_form(args)?;
                        let form = s.form();
                        *self.0.lock().unwrap() = s;
                        Ok(form)
                    }
                    _ => Ok(json!({})),
                }
            }
        }
        let native = Arc::new(Native(Mutex::new(Settings::default())));
        let path = std::env::temp_dir().join(format!(
            "rhremote-proxy-controller-{}-{}.json",
            std::process::id(),
            crate::now_ms()
        ));
        let c = crate::Controller::new("windows", path.clone(), native.clone()).unwrap();
        assert_eq!(
            c.dispatch("app.settings.tab", json!({"tab":"proxy"}))["ok"],
            true
        );
        assert_eq!(
            c.dispatch("proxy.edit", json!({"key":"port","value":"65536"}))["ok"],
            true
        );
        assert_eq!(c.dispatch("proxy.save", json!({}))["ok"], false);
        assert_eq!(c.state()["proxyDraft"]["port"], "65536");
        assert!(!c.state()["proxyError"].as_str().unwrap().is_empty());
        assert_eq!(native.0.lock().unwrap().port, 7890);
        c.dispatch("proxy.edit", json!({"key":"port","value":"7891"}));
        c.dispatch("proxy.save", json!({}));
        assert_eq!(native.0.lock().unwrap().port, 7891);
        assert_eq!(c.state()["proxyDirty"], false);
        c.state.lock().unwrap().sync_busy = true;
        assert_eq!(
            c.dispatch("proxy.edit", json!({"key":"enabled","value":true}))["ok"],
            false
        );
        assert!(!native.0.lock().unwrap().enabled);
        assert!(!fs::read_to_string(&path).unwrap().contains("proxy"));
        fs::remove_file(path).unwrap();
    }
}
