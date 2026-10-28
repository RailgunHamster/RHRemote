use base64::Engine;
use rhremote_core::sync_model::{Resolution, SharedConfig, merge};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{fs, io::Read, path::PathBuf, time::Duration};
mod dav;
#[cfg(test)]
#[path = "config_sync_lock_tests.rs"]
mod lock_tests;

const LIMIT: u64 = 2 * 1024 * 1024;
#[derive(Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct Local {
    url: String,
    username: String,
    credential_id: String,
    automatic: bool,
    #[serde(default = "default_interval")]
    interval_minutes: u32,
    last_sync_ms: u64,
    base: Option<SharedConfig>,
    pending_apply: bool,
    pending_transfer: bool,
}
struct Pending {
    local: SharedConfig,
    remote: SharedConfig,
    etag: String,
}
struct AwaitingApply { shared:SharedConfig, account:Local }
pub struct ConfigSync {
    directory: PathBuf,
    local: Local,
    pending: Option<Pending>,
    awaiting: Option<AwaitingApply>,
    locking: Option<bool>,
}

fn default_interval() -> u32 {
    5
}
fn interval(args: &Value, current: u32) -> Result<u32, String> {
    match args.get("intervalMinutes") {
        None => Ok(if [5, 15, 30, 60].contains(&current) {
            current
        } else {
            5
        }),
        Some(v) => match v.as_u64() {
            Some(n @ (5 | 15 | 30 | 60)) => Ok(n as u32),
            _ => Err("自动同步间隔只能为 5、15、30 或 60 分钟".into()),
        },
    }
}
fn endpoint(value: &str) -> Result<url::Url, String> {
    let mut url = url::Url::parse(value.trim()).map_err(|_| "WebDAV 地址无效")?;
    if !["http", "https"].contains(&url.scheme()) || url.host_str().is_none() {
        return Err("WebDAV 请使用 HTTP 或 HTTPS 地址".into());
    }
    if !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err("地址中不能包含凭据、查询参数或片段".into());
    }
    if !url.path().ends_with('/') {
        url.set_path(&format!("{}/", url.path()));
    }
    url.join("rhremote-config-v1.json")
        .map_err(|_| "WebDAV 路径无效".into())
}
fn atomic(path: &std::path::Path, value: &impl Serialize) -> Result<(), String> {
    let temp = path.with_extension("pending.json");
    fs::write(
        &temp,
        serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    fs::rename(temp, path).map_err(|e| e.to_string())
}
fn http_error(e: ureq::Error) -> String {
    match e {
        ureq::Error::Status(code @ (401 | 403), _) => {
            format!("WebDAV 登录失败，请检查账户和权限（HTTP {code}）")
        }
        ureq::Error::Status(412, _) => "远端配置已更新，请重新同步后再处理冲突（HTTP 412）".into(),
        ureq::Error::Status(423, _) => "另一台设备正在同步，请稍后重试（HTTP 423）".into(),
        ureq::Error::Status(code @ 300..=399, _) => {
            format!("WebDAV 地址发生重定向，请使用最终目录地址（HTTP {code}）")
        }
        ureq::Error::Status(code, _) => {
            format!("WebDAV 服务器返回错误，请稍后重试（HTTP {code}）")
        }
        ureq::Error::Transport(ref transport) => {
            use std::error::Error;
            let mut cause = e.source();
            while let Some(error) = cause {
                if error.downcast_ref::<ureq::rustls::Error>().is_some() {
                    return "WebDAV 证书或 TLS 连接验证失败，请检查证书和地址".into();
                }
                if error.downcast_ref::<std::io::Error>().is_some_and(|e| {
                    matches!(
                        e.kind(),
                        std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock
                    )
                }) {
                    return "WebDAV 连接超时，请检查网络后重试".into();
                }
                cause = error.source();
            }
            if transport.kind() == ureq::ErrorKind::Dns {
                "无法解析 WebDAV 地址，请检查地址和网络".into()
            } else if transport
                .message()
                .is_some_and(|text| text.starts_with("tls connection"))
            {
                "WebDAV 证书或 TLS 连接验证失败，请检查证书和地址".into()
            } else {
                "WebDAV 网络请求失败，请检查网络后重试".into()
            }
        }
    }
}

fn conflict_items(local: &SharedConfig, remote: &SharedConfig, keys: &[String]) -> Vec<Value> {
    fn device_name(config: &SharedConfig, id: &str) -> String {
        config
            .devices
            .iter()
            .find(|d| d.uuid == id)
            .map(|d| {
                if d.display_name.is_empty() {
                    d.name.clone()
                } else {
                    d.display_name.clone()
                }
            })
            .unwrap_or_else(|| "设备".into())
    }
    fn describe(config: &SharedConfig, key: &str) -> String {
        if key == "appearance.dark" {
            return if config.dark { "深色" } else { "浅色" }.into();
        }
        if key == "deviceOrder" {
            return config
                .devices
                .iter()
                .map(|d| device_name(config, &d.uuid))
                .collect::<Vec<_>>()
                .join(" → ");
        }
        if let Some(id) = key.strip_prefix("devices.") {
            return config
                .devices
                .iter()
                .find(|d| d.uuid == id)
                .map(|d| {
                    format!(
                        "{} · {}{}",
                        device_name(config, id),
                        d.address,
                        if d.swap_buttons {
                            " · 左右键交换"
                        } else {
                            ""
                        }
                    )
                })
                .unwrap_or_else(|| "未设置".into());
        }
        if let Some(id) = key.strip_prefix("clients.") {
            return config
                .clients
                .get(id)
                .map(|c| format!("{} · {}", c.name, c.platform))
                .unwrap_or_else(|| "未设置".into());
        }
        for (client, profiles) in &config.profiles {
            for (host, p) in profiles {
                if key == format!("profiles.{client}.{host}") {
                    return format!(
                        "{} × {} · {} FPS · {} Mbps",
                        p.width, p.height, p.fps, p.bitrate_mbps
                    );
                }
            }
        }
        "配置已修改".into()
    }
    keys.iter().map(|key| {
        let label = if key=="deviceOrder" {"设备顺序".into()}
        else if key=="appearance.dark" {"主题".into()}
        else if let Some(id)=key.strip_prefix("devices.") {format!("远程设备：{}",device_name(local,id))}
        else if key.starts_with("clients.") {"客户端资料".into()}
        else {
            local.profiles.iter().find_map(|(client,profiles)|profiles.keys().find(|host|key==&format!("profiles.{client}.{host}")).map(|host|format!("连接配置：{} → {}",local.clients.get(client).map(|c|c.name.as_str()).unwrap_or("客户端"),device_name(local,host)))).unwrap_or_else(||"其他配置".into())
        };
        json!({"key":key,"label":label,"local":describe(local,key),"remote":describe(remote,key)})
    }).collect()
}
impl ConfigSync {
    pub fn new(directory: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&directory).map_err(|e| e.to_string())?;
        let path = directory.join("webdav-local.json");
        let local = if path.exists() {
            serde_json::from_slice(&fs::read(path).map_err(|e| e.to_string())?)
                .map_err(|_| "WebDAV 本机配置损坏")?
        } else {
            Local::default()
        };
        Ok(Self {
            directory,
            local,
            pending: None,
            awaiting: None,
            locking: None,
        })
    }
    fn credential(id: &str) -> Result<keyring::Entry, String> {
        keyring::Entry::new("RHRemote.WebDAV", id).map_err(|_| "无法访问 Windows 凭据管理器".into())
    }
    pub fn state(&self) -> Value {
        let conflicts = self
            .pending
            .as_ref()
            .map(|p| {
                merge(
                    &p.local,
                    &p.remote,
                    self.local.base.as_ref(),
                    Resolution::Review,
                )
                .conflicts
            })
            .unwrap_or_default();
        let items = self
            .pending
            .as_ref()
            .map(|p| conflict_items(&p.local, &p.remote, &conflicts))
            .unwrap_or_default();
        let blocked=self.local.pending_transfer||self.local.pending_apply||self.awaiting.is_some();
        json!({"url":self.local.url,"username":self.local.username,"automatic":self.local.automatic,"intervalMinutes":interval(&json!({}),self.local.interval_minutes).unwrap(),"hasPassword":!self.local.credential_id.is_empty(),"lastSyncMs":self.local.last_sync_ms,"conflicts":conflicts,"conflictItems":items,"awaitingLocalApply":self.awaiting.is_some(),"outcomeUncertain":self.local.pending_transfer,"automaticBlocked":blocked,"phase":if self.awaiting.is_some(){"applying"}else if self.pending.is_some(){"conflict"}else if blocked{"interrupted"}else if self.local.last_sync_ms>0{"completed"}else{"idle"},"error":if blocked{"上次同步结果尚未确认，自动同步已暂停，请手动同步核对"}else{""}})
    }
    pub fn configure(&mut self, args: &Value) -> Result<Value, String> {
        let address = args["url"].as_str().ok_or("url required")?.trim();
        endpoint(address)?;
        let interval_minutes = interval(args, self.local.interval_minutes)?;
        let user = args["username"].as_str().ok_or("username required")?.trim();
        if user.is_empty() || user.contains([':', '\r', '\n']) {
            return Err("WebDAV 用户名无效".into());
        }
        let password = args["password"].as_str().unwrap_or("");
        let same = self.local.url == address && self.local.username == user;
        if password.is_empty() && (!same || self.local.credential_id.is_empty()) {
            return Err("请填写 WebDAV 密码".into());
        }
        let mut next = self.local.clone();
        if !same {
            next.base = None;
            next.last_sync_ms = 0;
            next.pending_apply=false;
            next.pending_transfer=false;
        }
        next.url = address.into();
        next.username = user.into();
        next.automatic = args["automatic"].as_bool().unwrap_or(false);
        next.interval_minutes = interval_minutes;
        let old_id = next.credential_id.clone();
        if !password.is_empty() {
            next.credential_id = rhremote_core::random_token()?;
            Self::credential(&next.credential_id)?
                .set_password(password)
                .map_err(|_| "保存 WebDAV 密码失败")?;
        }
        atomic(&self.directory.join("webdav-local.json"), &next)?;
        if old_id != next.credential_id && !old_id.is_empty() {
            if let Ok(e) = Self::credential(&old_id) {
                let _ = e.delete_credential();
            }
        }
        self.local = next;
        self.pending = None;
        self.awaiting=None;
        self.locking=None;
        Ok(self.state())
    }
    pub fn clear(&mut self) -> Result<Value, String> {
        atomic(&self.directory.join("webdav-local.json"), &Local::default())?;
        if !self.local.credential_id.is_empty() {
            if let Ok(e) = Self::credential(&self.local.credential_id) {
                let _ = e.delete_credential();
            }
        }
        self.local = Local::default();
        self.pending = None;
        self.awaiting=None;
        self.locking=None;
        Ok(self.state())
    }
    pub fn run(&mut self, local: SharedConfig, resolution: Resolution) -> Result<Value, String> {
        self.awaiting=None;
        local.validate()?;
        let address = endpoint(&self.local.url)?;
        let password = Self::credential(&self.local.credential_id)?
            .get_password()
            .map_err(|_| "请重新保存 WebDAV 密码")?;
        let auth = format!(
            "Basic {}",
            base64::engine::general_purpose::STANDARD
                .encode(format!("{}:{password}", self.local.username))
        );
        let agent = ureq::AgentBuilder::new().redirects(0).timeout(Duration::from_secs(15)).build();
        self.exchange_negotiated(&agent, address.as_str(), &auth, local, resolution)
            .map_err(|error| if self.local.pending_transfer {
                format!("{error}。远端写入结果未确认，自动同步已暂停，请手动同步核对")
            } else {error})
    }
    fn exchange_negotiated(&mut self, agent:&ureq::Agent, address:&str, auth:&str, local:SharedConfig, resolution:Resolution)->Result<Value,String> {
        let locking=match self.locking {Some(value)=>value,None=>{let value=dav::supported(agent,address,auth)?;self.locking=Some(value);value}};
        let mut lease=if locking {Some(dav::Lease::acquire(agent,address,auth)?)}else{None};
        let result=self.exchange_protected(agent,address,auth,local,resolution,lease.as_ref());
        if let Some(lease)=lease.as_mut(){if let Err(error)=lease.release(){if result.is_ok(){return Err(error);}}}
        result
    }
    #[cfg(test)]
    fn exchange(
        &mut self,
        address: &str,
        auth: &str,
        local: SharedConfig,
        resolution: Resolution,
    ) -> Result<Value, String> {
        let agent = ureq::AgentBuilder::new()
            .redirects(0)
            .timeout(Duration::from_secs(15))
            .build();
        self.exchange_with_agent(&agent, address, auth, local, resolution)
    }
    #[cfg(test)]
    fn exchange_with_agent(
        &mut self,
        agent: &ureq::Agent,
        address: &str,
        auth: &str,
        local: SharedConfig,
        resolution: Resolution,
    ) -> Result<Value, String> {
        self.exchange_protected(agent,address,auth,local,resolution,None)
    }
    fn exchange_protected(
        &mut self, agent:&ureq::Agent, address:&str, auth:&str, local:SharedConfig,
        resolution:Resolution, lease:Option<&dav::Lease>,
    ) -> Result<Value,String> {
        let fetched = match agent.get(address).set("Authorization", auth).call() {
            Ok(r) if r.status() == 200 => Some(r),
            Ok(r) => return Err(http_error(ureq::Error::Status(r.status(), r))),
            Err(ureq::Error::Status(404, _)) => None,
            Err(e) => return Err(http_error(e)),
        };
        let (remote, etag) = if let Some(response) = fetched {
            let etag = response
                .header("ETag")
                .filter(|v| !v.starts_with("W/"))
                .ok_or("WebDAV 服务器未提供强 ETag，无法安全合并")?
                .to_owned();
            let mut bytes = Vec::new();
            response
                .into_reader()
                .take(LIMIT + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| "读取 WebDAV 配置失败")?;
            if bytes.len() as u64 > LIMIT {
                return Err("远端配置过大".into());
            }
            // Some DAV servers materialize a locked new URL as a zero-byte file.
            // Initial setup may resume that empty creation, but valid data is never skipped.
            if bytes.is_empty() && lease.is_some_and(|l| l.created_resource || self.local.base.is_none() && self.local.last_sync_ms==0) {
                (None,Some(etag))
            } else {
                let remote: SharedConfig = serde_json::from_slice(&bytes).map_err(|_| "远端配置格式无效")?;
                remote.validate()?;
                (Some(remote), Some(etag))
            }
        } else {
            (None, None)
        };
        if resolution != Resolution::Review {
            let pending = self.pending.as_ref().ok_or("请先检查同步冲突")?;
            if pending.local != local
                || remote.as_ref() != Some(&pending.remote)
                || etag.as_ref() != Some(&pending.etag)
            {
                self.pending = None;
                return Err("配置已变化，请重新同步并检查冲突".into());
            }
        }
        let merged = if let Some(ref remote) = remote {
            let result = merge(&local, remote, self.local.base.as_ref(), resolution);
            if !result.conflicts.is_empty() {
                self.pending = Some(Pending {
                    local,
                    remote: remote.clone(),
                    etag: etag.clone().unwrap(),
                });
                return Ok(json!({"conflicts":result.conflicts,"sync":self.state()}));
            }
            result.merged
        } else {
            local
        };
        merged.validate()?;
        if let Some(ref remote) = remote {
            let backups = self.directory.join("sync-backups");
            fs::create_dir_all(&backups).map_err(|e| e.to_string())?;
            atomic(
                &backups.join(format!("remote-{}.json", rhremote_core::now_ms())),
                remote,
            )?;
            let mut files: Vec<_> = fs::read_dir(&backups)
                .map_err(|e| e.to_string())?
                .filter_map(Result::ok)
                .filter(|e| e.file_name().to_string_lossy().starts_with("remote-"))
                .collect();
            files.sort_by_key(|f| f.file_name());
            let count = files.len().saturating_sub(20);
            for file in files.into_iter().take(count) {
                let _ = fs::remove_file(file.path());
            }
        }
        let previously_pending=self.local.pending_transfer;
        self.record_transfer_started()?;
        let request = agent
            .put(address)
            .set("Authorization", auth)
            .set("Content-Type", "application/json");
        let request = if let Some(ref etag) = etag {
            request.set("If-Match", etag)
        } else {
            request.set("If-None-Match", "*")
        };
        let request = if let Some(lease)=lease {request.set("If",&lease.condition())}else{request};
        let response = match request.send_json(serde_json::to_value(&merged).unwrap()) {
            Ok(response)=>response,
            Err(error)=>{
                if let ureq::Error::Status(code,_) = &error {
                    self.record_rejected_transfer(*code,previously_pending)?;
                }
                return Err(http_error(error));
            }
        };
        if !(200..300).contains(&response.status()) {
            self.record_rejected_transfer(response.status(),previously_pending)?;
            return Err(http_error(ureq::Error::Status(response.status(), response)));
        }
        self.transferred(merged)
    }
    fn transferred(&mut self, merged: SharedConfig) -> Result<Value, String> {
        let mut next = self.local.clone();
        next.pending_apply = true;
        next.pending_transfer = false;
        self.awaiting = Some(AwaitingApply {
            shared: merged.clone(),
            account: next.clone(),
        });
        atomic(&self.directory.join("webdav-local.json"), &next)?;
        self.local = next;
        self.pending = None;
        Ok(json!({"shared":merged,"sync":self.state(),"conflicts":[]}))
    }
    // Persist before any PUT bytes can leave the process. A missing response cannot
    // distinguish a rejected request from an accepted write on the server.
    fn record_transfer_started(&mut self)->Result<(),String>{
        let mut next=self.local.clone();next.pending_transfer=true;
        self.record_transfer_state(next)
    }
    fn record_transfer_state(&mut self,next:Local)->Result<(),String>{
        let path=self.directory.join("webdav-local.json");
        atomic(&path,&next)?;
        let persisted:Local=serde_json::from_slice(&fs::read(path).map_err(|_|"同步任务状态无法读取，请重试")?)
            .map_err(|_|"同步任务状态无效，请重试")?;
        if persisted!=next{return Err("同步任务状态未能保存，请检查存储后重试".into());}
        self.local=next;Ok(())
    }
    fn record_rejected_transfer(&mut self,code:u16,previously_pending:bool)->Result<(),String>{
        // Explicit client rejections did not accept this PUT. They cannot settle an
        // earlier request whose response was lost. Timeouts and 5xx remain uncertain.
        if !previously_pending && matches!(code,400|401|403|404|405|409|412|413|415|423){
            let mut next=self.local.clone();next.pending_transfer=false;
            self.record_transfer_state(next)?;
        }
        Ok(())
    }
    #[cfg(test)]
    pub(super) fn transferred_for_test(&mut self, shared: SharedConfig) -> Result<Value, String> {
        self.transferred(shared)
    }
    #[cfg(test)]
    pub(super) fn test_configuration() -> SharedConfig {
        tests::config()
    }
    pub fn applied(
        &mut self,
        shared: &SharedConfig,
        preferences: &std::path::Path,
    ) -> Result<Value, String> {
        let staged = self
            .awaiting
            .as_ref()
            .ok_or("没有等待应用的同步结果，请重新同步")?;
        if staged.shared != *shared || staged.account != self.local {
            return Err("同步结果或账户已变化，请重新同步".into());
        }
        let saved: rhremote_core::Saved =
            serde_json::from_slice(&fs::read(preferences).map_err(|_| "无法核对本机配置，请重试")?)
                .map_err(|_| "本机配置无法读取，请重试")?;
        if saved.devices != shared.devices
            || saved.clients != shared.clients
            || saved.client_profiles != shared.profiles
            || saved.dark != shared.dark
        {
            return Err("本机配置尚未完整应用，请重新同步".into());
        }
        let path = self.directory.join("webdav-local.json");
        let current: Local =
            serde_json::from_slice(&fs::read(&path).map_err(|_| "无法核对同步账户，请重试")?)
                .map_err(|_| "同步账户无法读取，请重试")?;
        if current != self.local {
            return Err("同步账户已变化，请重新同步".into());
        }
        let mut next = self.local.clone();
        next.base = Some(shared.clone());
        next.last_sync_ms = rhremote_core::now_ms();
        next.pending_apply = false;
        next.pending_transfer = false;
        atomic(&path, &next)?;
        let persisted: Local =
            serde_json::from_slice(&fs::read(path).map_err(|_| "同步完成状态无法读取，请重试")?)
                .map_err(|_| "同步完成状态无效，请重试")?;
        if persisted != next {
            return Err("同步完成状态未能保存，请重试".into());
        }
        self.local = next;
        self.awaiting = None;
        Ok(self.state())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;
    pub(super) fn config() -> SharedConfig {
        SharedConfig {
            schema_version: 2,
            devices: vec![rhremote_core::sync_model::SharedDevice {
                uuid: "self".into(),
                name: "PC".into(),
                address: "127.0.0.1".into(),
                addresses: Vec::new(),
                display_name: String::new(),
                swap_buttons: true,
            }],
            clients: BTreeMap::from([(
                "pc".into(),
                rhremote_core::sync_model::ClientInfo {
                    name: "PC".into(),
                    platform: "windows".into(),
                },
            )]),
            profiles: BTreeMap::from([(
                "pc".into(),
                BTreeMap::from([(
                    "self".into(),
                    rhremote_core::sync_model::ClientProfile::from_profile(
                        &rhremote_core::Profile::default(),
                    ),
                )]),
            )]),
            dark: false,
        }
    }
    pub(super) fn fixture() -> ConfigSync {
        ConfigSync::new(std::env::temp_dir().join(format!(
            "rhremote-sync-{}",
            rhremote_core::random_token().unwrap()
        )))
        .unwrap()
    }
    pub(super) fn preferences(sync:&ConfigSync,shared:&SharedConfig)->PathBuf{
        let saved=rhremote_core::Saved{client_id:"pc".into(),devices:shared.devices.clone(),clients:shared.clients.clone(),client_profiles:shared.profiles.clone(),dark:shared.dark,profile_schema_version:2,device_order_initialized:true,relay_defaults_version:1,..Default::default()};
        let file=sync.directory.join("preferences-apply-test.json");atomic(&file,&saved).unwrap();file
    }
    fn cleanup(sync:ConfigSync){let path=sync.directory.canonicalize().unwrap();let temp=std::env::temp_dir().canonicalize().unwrap();assert!(path.starts_with(&temp)&&path!=temp);fs::remove_dir_all(path).unwrap();}
    #[test]
    fn credentials_and_queries_are_not_accepted_in_urls() {
        assert!(endpoint("https://user:secret@example.com/dav").is_err());
        assert!(endpoint("https://example.com/dav?secret=value").is_err());
        assert_eq!(
            endpoint("http://192.168.0.1/dav").unwrap().as_str(),
            "http://192.168.0.1/dav/rhremote-config-v1.json"
        );
        assert!(endpoint("ftp://example.com/dav").is_err());
        assert_eq!(
            endpoint("https://example.com/dav").unwrap().as_str(),
            "https://example.com/dav/rhremote-config-v1.json"
        );
    }
    #[test]
    fn sync_intervals_validate_and_migrate() {
        let old: Local = serde_json::from_value(json!({"url":"http://192.168.0.1/dav"})).unwrap();
        assert_eq!(old.interval_minutes, 5);
        for n in [5, 15, 30, 60] {
            assert_eq!(interval(&json!({"intervalMinutes":n}), 5).unwrap(), n);
        }
        for n in [json!(0), json!(10), json!("15"), json!(null)] {
            assert!(interval(&json!({"intervalMinutes":n}), 5).is_err());
        }
        assert_eq!(interval(&json!({}), 30).unwrap(), 30);
    }
    #[test]
    fn commit_transfer_keeps_the_original_base_until_local_application() {
        let mut sync=fixture();let base=config();sync.local.base=Some(base.clone());sync.local.last_sync_ms=1;atomic(&sync.directory.join("webdav-local.json"),&sync.local).unwrap();let mut remote=base.clone();remote.devices[0].display_name="远端新名".into();
        sync.transferred(remote).unwrap();assert_eq!(sync.local.base,Some(base));assert_eq!(sync.local.last_sync_ms,1);cleanup(sync);
    }
    #[test]
    fn transfer_intent_survives_a_cold_start_without_advancing_success(){
        let mut sync=fixture();sync.local.automatic=true;sync.local.last_sync_ms=17;atomic(&sync.directory.join("webdav-local.json"),&sync.local).unwrap();
        sync.record_transfer_started().unwrap();let cold=ConfigSync::new(sync.directory.clone()).unwrap();
        assert_eq!(cold.state()["automaticBlocked"],true,"An interrupted PUT cannot silently resume automatic synchronization");
        assert_eq!(cold.local.last_sync_ms,17);assert!(cold.local.base.is_none());cleanup(sync);
    }
    #[test]
    fn a_rejected_put_cannot_settle_a_previous_unknown_write(){
        let mut sync=fixture();sync.record_transfer_started().unwrap();
        sync.record_rejected_transfer(401,false).unwrap();assert_eq!(sync.state()["automaticBlocked"],false);
        sync.record_transfer_started().unwrap();sync.record_rejected_transfer(412,true).unwrap();
        assert_eq!(sync.state()["outcomeUncertain"],true);
        sync.record_rejected_transfer(500,false).unwrap();assert_eq!(sync.state()["automaticBlocked"],true);
        let merged=config();sync.transferred(merged.clone()).unwrap();
        let cold=ConfigSync::new(sync.directory.clone()).unwrap();assert_eq!(cold.state()["outcomeUncertain"],false);assert_eq!(cold.state()["automaticBlocked"],true);
        let file=preferences(&sync,&merged);sync.applied(&merged,&file).unwrap();
        let cold=ConfigSync::new(sync.directory.clone()).unwrap();assert_eq!(cold.state()["automaticBlocked"],false);cleanup(sync);
    }
    #[test]
    fn unrecordable_transfer_intent_stops_before_put(){
        let mut sync=fixture();let pending=sync.directory.join("webdav-local.pending.json");fs::create_dir(&pending).unwrap();fs::write(pending.join("blocker"),b"test-owned blocker").unwrap();
        assert!(sync.record_transfer_started().is_err());assert!(!sync.local.pending_transfer);assert_eq!(sync.local.last_sync_ms,0);cleanup(sync);
    }
    #[test]
    fn commit_cold_retry_does_not_treat_the_old_local_name_as_a_new_edit() {
        let mut sync=fixture();let base=config();sync.local.base=Some(base.clone());sync.local.last_sync_ms=1;atomic(&sync.directory.join("webdav-local.json"),&sync.local).unwrap();let mut remote=base.clone();remote.devices[0].display_name="远端新名".into();
        sync.transferred(remote.clone()).unwrap();let restarted=ConfigSync::new(sync.directory.clone()).unwrap();let retry=merge(&base,&remote,restarted.local.base.as_ref(),Resolution::Review);assert_eq!(retry.merged.devices[0].display_name,"远端新名");assert_eq!(restarted.state()["automaticBlocked"],true);assert_eq!(restarted.state()["phase"],"interrupted");cleanup(sync);
    }
    #[test]
    fn commit_acknowledgement_requires_the_exact_persisted_configuration(){
        let mut sync=fixture();let original=config();let mut remote=original.clone();remote.devices[0].display_name="远端新名".into();sync.transferred(remote.clone()).unwrap();let file=preferences(&sync,&original);
        assert!(sync.applied(&remote,&file).unwrap_err().contains("尚未完整应用"));assert!(sync.local.base.is_none());assert_eq!(sync.local.last_sync_ms,0);assert_eq!(sync.state()["automaticBlocked"],true);
        let mut changed=remote.clone();changed.devices[0].display_name="更改后的结果".into();let file=preferences(&sync,&changed);assert!(sync.applied(&changed,&file).unwrap_err().contains("结果或账户已变化"));cleanup(sync);
    }
    #[test]
    fn commit_failed_metadata_write_keeps_the_base_and_allows_explicit_retry(){
        let mut sync=fixture();let merged=config();sync.transferred(merged.clone()).unwrap();let file=preferences(&sync,&merged);let pending=sync.directory.join("webdav-local.pending.json");fs::create_dir(&pending).unwrap();fs::write(pending.join("blocker"),b"test-owned blocker").unwrap();
        assert!(sync.applied(&merged,&file).is_err());assert!(sync.local.base.is_none());assert_eq!(sync.local.last_sync_ms,0);assert_eq!(sync.state()["automaticBlocked"],true);let saved:Local=serde_json::from_slice(&fs::read(sync.directory.join("webdav-local.json")).unwrap()).unwrap();assert!(saved.base.is_none()&&saved.pending_apply);
        fs::remove_file(pending.join("blocker")).unwrap();fs::remove_dir(&pending).unwrap();sync.applied(&merged,&file).unwrap();assert_eq!(sync.local.base,Some(merged));assert!(sync.local.last_sync_ms>0);assert_eq!(sync.state()["automaticBlocked"],false);cleanup(sync);
    }
    #[test]
    fn commit_old_result_cannot_recreate_a_removed_account(){
        let mut sync=fixture();let merged=config();sync.transferred(merged.clone()).unwrap();let file=preferences(&sync,&merged);sync.clear().unwrap();assert!(sync.applied(&merged,&file).unwrap_err().contains("没有等待应用"));assert!(sync.local.base.is_none());cleanup(sync);
    }
    #[test]
    fn windows_vault_roundtrip_never_exposes_password_in_json() {
        let mut sync = fixture();
        let password = "temporary-test-password-DO-NOT-SYNC";
        let result=sync.configure(&json!({"url":"https://example.com/dav/","username":"fixture","password":password,"automatic":false})).unwrap();
        assert_eq!(result["hasPassword"], true);
        assert!(!result.to_string().contains(password));
        assert!(
            !fs::read_to_string(sync.directory.join("webdav-local.json"))
                .unwrap()
                .contains(password)
        );
        let id = sync.local.credential_id.clone();
        assert_eq!(
            ConfigSync::credential(&id).unwrap().get_password().unwrap(),
            password
        );
        sync.clear().unwrap();
        assert!(ConfigSync::credential(&id).unwrap().get_password().is_err());
        fs::remove_dir_all(sync.directory).unwrap();
    }
    #[test]
    fn initial_upload_uses_conditional_create_and_keeps_self() {
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let address = format!("http://{}/rhremote-config-v1.json", server.server_addr());
        let worker = std::thread::spawn(move || {
            let request = server
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap();
            assert_eq!(request.method(), &tiny_http::Method::Get);
            request.respond(tiny_http::Response::empty(404)).unwrap();
            let mut request = server
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap();
            assert_eq!(request.method(), &tiny_http::Method::Put);
            assert!(
                request
                    .headers()
                    .iter()
                    .any(|h| h.field.equiv("If-None-Match") && h.value.as_str() == "*")
            );
            let mut bytes = Vec::new();
            request.as_reader().read_to_end(&mut bytes).unwrap();
            let sent: Value = serde_json::from_slice(&bytes).unwrap();
            assert_eq!(sent["devices"][0]["uuid"], "self");
            assert!(sent["devices"][0].get("isSelf").is_none());
            request.respond(tiny_http::Response::empty(201)).unwrap();
        });
        let mut sync = fixture();
        let result = sync
            .exchange(&address, "Basic test", config(), Resolution::Review)
            .unwrap();
        assert!(result.get("shared").is_some());
        assert!(sync.local.base.is_none());
        let merged:SharedConfig=serde_json::from_value(result["shared"].clone()).unwrap();let file=preferences(&sync,&merged);sync.applied(&merged,&file).unwrap();assert!(sync.local.base.is_some());
        worker.join().unwrap();
        fs::remove_dir_all(sync.directory).unwrap();
    }
    #[test]
    fn conflicts_do_not_upload_and_changed_remote_cannot_be_forced() {
        let base = config();
        let mut local = base.clone();
        local
            .profiles
            .get_mut("pc")
            .unwrap()
            .get_mut("self")
            .unwrap()
            .fps = 60;
        let mut remote = base.clone();
        remote
            .profiles
            .get_mut("pc")
            .unwrap()
            .get_mut("self")
            .unwrap()
            .fps = 30;
        remote
            .profiles
            .get_mut("pc")
            .unwrap()
            .get_mut("self")
            .unwrap()
            .bitrate_mbps = 40;
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let address = format!("http://{}/dav", server.server_addr());
        let body = serde_json::to_string(&remote).unwrap();
        let worker = std::thread::spawn(move || {
            for version in [1, 2] {
                let request = server
                    .recv_timeout(Duration::from_secs(5))
                    .unwrap()
                    .unwrap();
                assert_eq!(request.method(), &tiny_http::Method::Get);
                request
                    .respond(tiny_http::Response::from_string(body.clone()).with_header(
                        tiny_http::Header::from_bytes("ETag", format!("\"{version}\"")).unwrap(),
                    ))
                    .unwrap();
            }
        });
        let mut sync = fixture();
        sync.local.base = Some(base);
        let result = sync
            .exchange(&address, "Basic test", local.clone(), Resolution::Review)
            .unwrap();
        assert_eq!(result["conflicts"][0], "profiles.pc.self");
        assert!(result.get("shared").is_none());
        assert!(
            sync.exchange(&address, "Basic test", local, Resolution::Remote)
                .unwrap_err()
                .contains("配置已变化")
        );
        worker.join().unwrap();
        fs::remove_dir_all(sync.directory).unwrap();
    }
    #[test]
    fn concurrent_write_rejection_does_not_advance_base() {
        let base = config();
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let address = format!("http://{}/dav", server.server_addr());
        let body = serde_json::to_string(&base).unwrap();
        let worker = std::thread::spawn(move || {
            let request = server
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap();
            request
                .respond(
                    tiny_http::Response::from_string(body)
                        .with_header(tiny_http::Header::from_bytes("ETag", "\"before\"").unwrap()),
                )
                .unwrap();
            let request = server
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap();
            assert!(
                request
                    .headers()
                    .iter()
                    .any(|h| h.field.equiv("If-Match") && h.value.as_str() == "\"before\"")
            );
            request.respond(tiny_http::Response::empty(412)).unwrap();
        });
        let mut sync = fixture();
        sync.local.base = Some(base.clone());
        let mut changed = base.clone();
        changed.dark = true;
        assert!(
            sync.exchange(&address, "Basic test", changed, Resolution::Review)
                .unwrap_err()
                .contains("412")
        );
        assert_eq!(sync.local.base, Some(base));
        worker.join().unwrap();
        fs::remove_dir_all(sync.directory).unwrap();
    }
    #[test]
    fn redirected_upload_is_not_reported_as_a_successful_sync() {
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let address = format!("http://{}/dav", server.server_addr());
        let target = format!("{address}/redirect-target");
        let worker = std::thread::spawn(move || {
            let get = server
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap();
            assert_eq!(get.method(), &tiny_http::Method::Get);
            get.respond(tiny_http::Response::empty(404)).unwrap();
            let put = server
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap();
            assert_eq!(put.method(), &tiny_http::Method::Put);
            put.respond(
                tiny_http::Response::empty(302)
                    .with_header(tiny_http::Header::from_bytes("Location", target).unwrap()),
            )
            .unwrap();
            assert!(
                server
                    .recv_timeout(Duration::from_millis(100))
                    .unwrap()
                    .is_none(),
                "A redirect was followed"
            );
        });
        let mut sync = fixture();
        let result = sync.exchange(&address, "Basic fixture", config(), Resolution::Review);
        worker.join().unwrap();
        assert!(
            result.is_err(),
            "A 302 upload must not advance the sync state: {result:?}"
        );
        assert!(result.unwrap_err().contains("最终目录"));
        assert!(sync.local.base.is_none());
        assert_eq!(sync.local.last_sync_ms, 0);
        // A redirect is not confirmation of whether the first URL applied the PUT.
        assert_eq!(sync.state()["outcomeUncertain"],true);
        assert_eq!(ConfigSync::new(sync.directory.clone()).unwrap().state()["automaticBlocked"],true);
        fs::remove_dir_all(sync.directory).unwrap();
    }
    #[test]
    fn conflict_summaries_show_names_values_and_client_specific_profiles() {
        let mut local = config();
        local.devices[0].display_name = "工作电脑".into();
        local.clients.get_mut("pc").unwrap().name = "台式机".into();
        let mut remote = local.clone();
        remote.devices[0].display_name = "远端名称".into();
        remote
            .profiles
            .get_mut("pc")
            .unwrap()
            .get_mut("self")
            .unwrap()
            .bitrate_mbps = 40;
        remote.dark = true;
        let items = conflict_items(
            &local,
            &remote,
            &[
                "devices.self".into(),
                "profiles.pc.self".into(),
                "appearance.dark".into(),
                "deviceOrder".into(),
            ],
        );
        assert_eq!(items[0]["label"], "远程设备：工作电脑");
        assert!(items[0]["remote"].as_str().unwrap().contains("远端名称"));
        assert_eq!(items[1]["label"], "连接配置：台式机 → 工作电脑");
        assert!(items[1]["local"].as_str().unwrap().contains("25 Mbps"));
        assert!(items[1]["remote"].as_str().unwrap().contains("40 Mbps"));
        assert_eq!(items[2]["local"], "浅色");
        assert_eq!(items[2]["remote"], "深色");
        assert_eq!(items[3]["local"], "工作电脑");
        let mut sync = fixture();
        sync.pending = Some(Pending {
            local,
            remote,
            etag: "\"1\"".into(),
        });
        let state = sync.state();
        assert!(!state["conflictItems"].as_array().unwrap().is_empty());
        assert!(state.get("password").is_none());
        fs::remove_dir_all(sync.directory).unwrap();
    }
}

#[cfg(test)]
#[path = "config_sync_tls_tests.rs"]
mod tls_tests;
