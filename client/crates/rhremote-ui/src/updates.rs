use serde_json::{Value, json};
use std::{
    fs,
    io::{Read, Write},
    path::Path,
    sync::{Mutex, mpsc::Sender},
    time::Duration,
};
use velopack::{
    Error, UpdateCheck, UpdateInfo, UpdateManager, VelopackAsset, VelopackAssetFeed,
    bundle::Manifest, sources::UpdateSource,
};

const GITEE: &str = "https://gitee.com/railgunhamster/RHRemote/releases/download/";
const GITHUB: &str = "https://github.com/RailgunHamster/RHRemote/releases/";
fn filename(name: &str) -> Result<(), Error> {
    if name.is_empty() || name == "." || name == ".." || name.contains(['/', '\\', ':', '\0']) {
        Err(Error::Other("Invalid update asset name".into()))
    } else {
        Ok(())
    }
}
struct Mirrors {
    preferred: String,
    selected: Mutex<String>,
}
impl Mirrors {
    fn providers(&self) -> [&str; 2] {
        if self.preferred == "github" {
            ["github", "gitee"]
        } else {
            ["gitee", "github"]
        }
    }
    fn feed(provider: &str, name: &str) -> String {
        if provider == "github" {
            format!("{GITHUB}latest/download/{name}")
        } else {
            format!("{GITEE}latest/{name}")
        }
    }
    fn asset(provider: &str, asset: &VelopackAsset) -> Result<String, Error> {
        filename(&asset.Version)?;
        filename(&asset.FileName)?;
        Ok(if provider == "github" {
            format!(
                "{GITHUB}download/v{}/{name}",
                asset.Version,
                name = asset.FileName
            )
        } else {
            format!("{GITEE}v{}/{name}", asset.Version, name = asset.FileName)
        })
    }
}
impl UpdateSource for Mirrors {
    fn get_release_feed(
        &self,
        channel: &str,
        _: &Manifest,
        _: &str,
    ) -> Result<VelopackAssetFeed, Error> {
        let name = format!("releases.{channel}.json");
        filename(&name)?;
        for provider in self.providers() {
            let result = (|| {
                let response = ureq::get(&Self::feed(provider, &name))
                    .timeout(Duration::from_secs(15))
                    .call()
                    .map_err(|_| ())?;
                let mut bytes = Vec::new();
                response
                    .into_reader()
                    .take(2 * 1024 * 1024 + 1)
                    .read_to_end(&mut bytes)
                    .map_err(|_| ())?;
                if bytes.len() > 2 * 1024 * 1024 {
                    return Err(());
                }
                serde_json::from_slice::<VelopackAssetFeed>(&bytes).map_err(|_| ())
            })();
            if let Ok(feed) = result {
                *self.selected.lock().unwrap() = provider.into();
                return Ok(feed);
            }
        }
        Err(Error::Other("GitHub 与 Gitee 更新源均不可用".into()))
    }
    fn download_release_entry(
        &self,
        asset: &VelopackAsset,
        path: &Path,
        progress: Option<Sender<i16>>,
    ) -> Result<(), Error> {
        if asset.Size == 0 || asset.Size > 2 * 1024 * 1024 * 1024 {
            return Err(Error::Other("Invalid update package size".into()));
        }
        let selected = self.selected.lock().unwrap().clone();
        let providers = if selected == "github" {
            ["github", "gitee"]
        } else {
            ["gitee", "github"]
        };
        for provider in providers {
            let address = Self::asset(provider, asset)?;
            let result = (|| -> Result<(), Error> {
                let response = ureq::get(&address)
                    .timeout(Duration::from_secs(180))
                    .call()
                    .map_err(|_| Error::Other("更新包下载失败".into()))?;
                let mut source = response.into_reader().take(asset.Size + 1);
                let mut file = fs::File::create(path)?;
                let mut bytes = [0u8; 65536];
                let mut total = 0u64;
                loop {
                    let count = source.read(&mut bytes)?;
                    if count == 0 {
                        break;
                    }
                    file.write_all(&bytes[..count])?;
                    total += count as u64;
                    if let Some(tx) = &progress {
                        let _ = tx.send(((total * 100 / asset.Size).min(100)) as i16);
                    }
                }
                if total != asset.Size {
                    return Err(Error::Other("更新包长度不符".into()));
                }
                Ok(())
            })();
            if result.is_ok() {
                return result;
            }
        }
        Err(Error::Other("更新包下载失败".into()))
    }
}
pub struct Updates {
    manager: Option<UpdateManager>,
    available: Option<UpdateInfo>,
    status: String,
}
impl Updates {
    pub fn new() -> Self {
        Self {
            manager: UpdateManager::new(
                Mirrors {
                    preferred: "gitee".into(),
                    selected: Mutex::new(String::new()),
                },
                None,
                None,
            )
            .ok(),
            available: None,
            status: String::new(),
        }
    }
    pub fn state(&self) -> Value {
        json!({"managed":self.manager.is_some(),"currentVersion":env!("CARGO_PKG_VERSION"),"installedVersion":self.manager.as_ref().map(|m|m.get_current_version_as_string()),"availableVersion":self.available.as_ref().map(|u|u.TargetFullRelease.Version.clone()),"notes":self.available.as_ref().map(|u|u.TargetFullRelease.NotesMarkdown.clone()).unwrap_or_default(),"pendingRestart":self.manager.as_ref().is_some_and(|m|m.get_update_pending_restart().is_some()),"status":self.status})
    }
    pub fn execute(&mut self, id: &str) -> Result<Value, String> {
        if id == "updates.state" {
            return Ok(self.state());
        }
        let manager = self
            .manager
            .as_ref()
            .ok_or("当前是开发版，请使用 Velopack 安装包启用自动更新")?;
        match id {
            "updates.check" => {
                self.available = match manager.check_for_updates().map_err(|e| e.to_string())? {
                    UpdateCheck::UpdateAvailable(update) => Some(*update),
                    _ => None,
                };
                self.status = if self.available.is_some() {
                    "发现新版本"
                } else {
                    "已是最新版本"
                }
                .into();
            }
            "updates.download" => {
                let update = self.available.as_ref().ok_or("请先检查更新")?;
                manager
                    .download_updates(update, None)
                    .map_err(|e| e.to_string())?;
                self.status = "更新已下载，重启后安装".into();
            }
            "updates.install" => {
                let pending = manager.get_update_pending_restart().ok_or("尚未下载更新")?;
                manager
                    .wait_exit_then_apply_updates(pending, false, true, Vec::<String>::new())
                    .map_err(|e| e.to_string())?;
            }
            _ => return Err("unknown update action".into()),
        }
        Ok(self.state())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn package_paths_cannot_escape_release_directory() {
        for name in ["../evil", "..", "C:\\evil", "a/b", "file:stream"] {
            assert!(filename(name).is_err())
        }
        assert!(filename("RHRemote-0.2.0-full.nupkg").is_ok());
    }
}
