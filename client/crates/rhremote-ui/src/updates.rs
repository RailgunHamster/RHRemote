use serde_json::{Value, json};
use std::{
    fs,
    io::{Read, Write},
    path::Path,
    sync::{Arc, Mutex, mpsc::{self, Sender}},
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
type Snapshot = Arc<Mutex<Value>>;
struct Mirrors {
    live: Snapshot,
    preferred: String,
    selected: Mutex<String>,
    test_base: Option<String>,
}
// Development fixtures cannot redirect the normal updater to a remote server.
fn local_test_source(mode: &str, address: &str) -> Option<String> {
    if mode!="1" {return None;}
    let url=url::Url::parse(address).ok()?;
    if url.scheme()!="http" || !matches!(url.host_str(),Some("127.0.0.1"|"[::1]")) || url.port().is_none_or(|p|p<=1024)
        || !url.username().is_empty() || url.password().is_some() || url.path()!="/" || url.query().is_some() || url.fragment().is_some() {return None;}
    Some(url.to_string())
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
    fn feed_address(&self,provider:&str,name:&str)->String {
        self.test_base.as_ref().map(|base|format!("{base}{provider}/{name}")).unwrap_or_else(||Self::feed(provider,name))
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
    fn asset_address(&self,provider:&str,asset:&VelopackAsset)->Result<String,Error> {
        let normal=Self::asset(provider,asset)?;
        Ok(self.test_base.as_ref().map(|base|format!("{base}{provider}/{}",asset.FileName)).unwrap_or(normal))
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
            { let mut live=self.live.lock().unwrap(); live["provider"]=json!(provider); live["status"]=json!(format!("正在从 {} 检查更新",provider)); }
            let result = (|| {
                let response = ureq::get(&self.feed_address(provider, &name))
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
            self.live.lock().unwrap()["provider"]=json!(provider);
            let address = self.asset_address(provider, asset)?;
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
    live: Snapshot,
}
impl Updates {
    pub fn new() -> Self {
        let live=Arc::new(Mutex::new(json!({"phase":"idle","status":"尚未检查更新","progress":0,"provider":""})));
        let value=Self {
            manager: UpdateManager::new(Mirrors {live:live.clone(),preferred:"gitee".into(),selected:Mutex::new(String::new()),test_base:local_test_source(&std::env::var("RHREMOTE_TEST_MODE").unwrap_or_default(),&std::env::var("RHREMOTE_TEST_UPDATE_SOURCE").unwrap_or_default())},None,None).ok(),
            available:None,live,
        };
        value.refresh();value
    }
    pub fn snapshot(&self) -> Snapshot { self.live.clone() }
    pub fn state(&self) -> Value { self.live.lock().unwrap().clone() }
    fn status(&self,phase:&str,text:&str) {
        let mut live=self.live.lock().unwrap();live["phase"]=json!(phase);live["status"]=json!(text);
    }
    fn refresh(&self) {
        let pending=self.manager.as_ref().and_then(|m|m.get_update_pending_restart());
        let mut live=self.live.lock().unwrap();
        live["managed"]=json!(self.manager.is_some());live["currentVersion"]=json!(env!("CARGO_PKG_VERSION"));
        live["installedVersion"]=json!(self.manager.as_ref().map(|m|m.get_current_version_as_string()));
        live["availableVersion"]=json!(self.available.as_ref().map(|u|u.TargetFullRelease.Version.clone()).or_else(||pending.as_ref().map(|a|a.Version.clone())));
        live["notes"]=json!(self.available.as_ref().map(|u|u.TargetFullRelease.NotesMarkdown.clone()).unwrap_or_default());
        live["pendingRestart"]=json!(pending.is_some());
    }
    fn check(&mut self)->Result<(),String> {
        self.status("checking","正在检查更新");
        self.available=match self.manager.as_ref().ok_or("更新管理器不可用")?.check_for_updates().map_err(|e|e.to_string())? {
            UpdateCheck::UpdateAvailable(update)=>Some(*update),_=>None,
        };
        self.refresh();
        let pending=self.state()["pendingRestart"]==true;
        self.status(if pending{"downloaded"}else if self.available.is_some(){"available"}else{"current"},if pending{"更新已下载，可安装更新"}else if self.available.is_some(){"发现新版本"}else{"已是最新版本"});
        Ok(())
    }
    fn download(&self)->Result<(),String> {
        let update=self.available.as_ref().ok_or("请先检查更新")?;
        self.status("downloading","正在下载更新（0%）");self.live.lock().unwrap()["progress"]=json!(0);
        let (tx,rx)=mpsc::channel::<i16>();let live=self.live.clone();
        let progress=std::thread::spawn(move||{for percent in rx {let percent=percent.clamp(0,100);let mut value=live.lock().unwrap();value["progress"]=json!(percent);value["status"]=json!(if percent==100{"正在校验更新包".into()}else{format!("正在下载更新（{percent}%）")});}});
        let result=self.manager.as_ref().unwrap().download_updates(update,Some(tx)).map_err(|e|e.to_string());
        let _=progress.join();result?;self.refresh();self.status("downloaded","更新已下载，可安装更新");Ok(())
    }
    pub fn execute(&mut self,id:&str)->Result<Value,String> {
        if id=="updates.state" {return Ok(self.state());}
        let result:Result<(),String>=(||{
            if self.manager.is_none(){return Err("当前是开发版，请使用 Velopack 安装包启用自动更新".into());}
            match id {
                "updates.check"=>self.check()?,
                "updates.download"=>self.download()?,
                "updates.install"=>{
                    if self.manager.as_ref().unwrap().get_update_pending_restart().is_none(){
                        if self.available.is_none(){self.check()?;}
                        if self.available.is_none(){return Ok(());}
                        self.download()?;
                    }
                    let manager=self.manager.as_ref().unwrap();
                    let pending=manager.get_update_pending_restart().ok_or("更新包尚未准备好")?;
                    self.status("applying","正在重启 RHRemote 并安装更新");
                    manager.wait_exit_then_apply_updates(pending,false,true,Vec::<String>::new()).map_err(|e|e.to_string())?;
                },
                _=>return Err("unknown update action".into()),
            }
            Ok(())
        })();
        if let Err(error)=result {self.status("error",&error);return Err(error);}
        Ok(self.state())
    }
}
/// A single primary action; advanced HTTP actions remain available for diagnostics.
pub fn primary_label(state:&Value,busy:bool)->String {
    let phase=state["phase"].as_str().unwrap_or("");
    if busy {return match phase {"checking"=>"检查中…".into(),"downloading"=>format!("下载中 {}%",state["progress"].as_i64().unwrap_or(0)),"applying"=>"正在安装…".into(),_=>"正在准备…".into()};}
    let ready=state["pendingRestart"]==true;let available=state["availableVersion"].is_string();let failed=phase=="error";
    if ready {if failed{"重试安装"}else{"重启并安装"}} else if available {if failed{"重试下载并安装"}else{"下载并安装"}} else if failed{"重新检查"}else{"检查更新"}.into()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn update_fixture_requires_explicit_test_mode_and_a_plain_loopback_origin(){
        assert_eq!(local_test_source("1","http://127.0.0.1:48766/"),Some("http://127.0.0.1:48766/".into()));
        for (mode,url) in [("","http://127.0.0.1:48766/"),("1","https://example.com/"),("1","http://192.168.0.104:48766/"),("1","http://127.0.0.1:80/"),("1","http://u:p@127.0.0.1:48766/"),("1","http://127.0.0.1:48766/path"),("1","http://127.0.0.1:48766/?x=y")] {assert!(local_test_source(mode,url).is_none(),"{mode} {url}");}
    }
    #[test]
    fn primary_label_follows_state_without_exposing_internal_steps(){
        assert_eq!(primary_label(&json!({}),false),"检查更新");
        assert_eq!(primary_label(&json!({"availableVersion":"9.0.0"}),false),"下载并安装");
        assert_eq!(primary_label(&json!({"pendingRestart":true}),false),"重启并安装");
        assert_eq!(primary_label(&json!({"phase":"error"}),false),"重新检查");
        assert_eq!(primary_label(&json!({"phase":"error","availableVersion":"9.0.0"}),false),"重试下载并安装");
        assert_eq!(primary_label(&json!({"phase":"downloading","progress":37}),true),"下载中 37%");
    }
    #[test]
    fn package_paths_cannot_escape_release_directory() {
        for name in ["../evil", "..", "C:\\evil", "a/b", "file:stream"] {
            assert!(filename(name).is_err())
        }
        assert!(filename("RHRemote-0.2.0-full.nupkg").is_ok());
    }
}
