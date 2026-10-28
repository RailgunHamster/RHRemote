use serde_json::{Value, json};
use std::{
    fs,
    io::Write,
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
fn download_progress(live:&mut Value,percent:i16){
    let percent=percent.clamp(0,100);live["progress"]=json!(percent);
    live["phase"]=json!(if percent==100{"verifying"}else{"downloading"});
    live["status"]=json!(if percent==100{"正在校验更新包".into()}else{format!("正在下载更新（{percent}%）")});
}
struct Mirrors {
    live: Snapshot,
    preferred: String,
    selected: Mutex<String>,
    test_base: Option<String>,
    cancel: Arc<crate::update_cancel::Control>,
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
    fn client(&self,seconds:u64)->Result<reqwest::Client,Error>{
        if rustls::crypto::CryptoProvider::get_default().is_none(){let _=rustls::crypto::ring::default_provider().install_default();}
        reqwest::Client::builder().https_only(self.test_base.is_none()).timeout(Duration::from_secs(seconds))
            .user_agent(concat!("RHRemote/",env!("CARGO_PKG_VERSION"))).build().map_err(|_|Error::Other("更新网络初始化失败".into()))
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
            let token=self.cancel.token();if token.is_cancelled(){return Err(Error::Other("Update operation cancelled".into()));}
            { let mut live=self.live.lock().unwrap(); live["provider"]=json!(provider); live["status"]=json!(format!("正在从 {} 检查更新",provider)); }
            let result = crate::update_cancel::run(&token,async {
                let mut response = self.client(15)?.get(self.feed_address(provider,&name)).send().await
                    .and_then(reqwest::Response::error_for_status).map_err(|_|Error::Other("更新源请求失败".into()))?;
                let mut bytes = Vec::new();
                while let Some(chunk)=response.chunk().await.map_err(|_|Error::Other("更新信息读取失败".into()))? {
                    if bytes.len().saturating_add(chunk.len())>2*1024*1024 {return Err(Error::Other("更新信息过大".into()));}
                    bytes.extend_from_slice(&chunk);
                }
                serde_json::from_slice::<VelopackAssetFeed>(&bytes).map_err(|_|Error::Other("更新信息无效".into()))
            });
            if token.is_cancelled(){return Err(Error::Other("Update operation cancelled".into()));}
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
            let token=self.cancel.token();if token.is_cancelled(){return Err(Error::Other("Update operation cancelled".into()));}
            self.live.lock().unwrap()["provider"]=json!(provider);
            let address = self.asset_address(provider, asset)?;
            let result = crate::update_cancel::run(&token,async {
                let mut response = self.client(180)?.get(&address).send().await.and_then(reqwest::Response::error_for_status)
                    .map_err(|_|Error::Other("更新包下载失败".into()))?;
                let mut file = fs::File::create(path)?;
                let mut total = 0u64;
                while let Some(bytes)=response.chunk().await.map_err(|_|Error::Other("更新包读取失败".into()))? {
                    total=total.saturating_add(bytes.len() as u64);
                    if total>asset.Size {return Err(Error::Other("更新包长度不符".into()));}
                    file.write_all(&bytes)?;
                    if let Some(tx) = &progress {
                        let _ = tx.send(((total * 100 / asset.Size).min(100)) as i16);
                    }
                }
                if total != asset.Size {
                    return Err(Error::Other("更新包长度不符".into()));
                }
                Ok(())
            });
            if result.is_ok() {
                return result;
            }
            // The source owns this SDK-provided partial file, never the verified
            // package. Drop the cancelled future/file before removing it.
            match fs::remove_file(path){Ok(())=>{},Err(error) if error.kind()==std::io::ErrorKind::NotFound=>{},Err(error)=>return Err(error.into())}
            if token.is_cancelled(){return result;}
        }
        Err(Error::Other("更新包下载失败".into()))
    }
}
pub struct Updates {
    manager: Option<UpdateManager>,
    available: Option<UpdateInfo>,
    live: Snapshot,
    cancel: Arc<crate::update_cancel::Control>,
}
impl Updates {
    pub fn new() -> Self {
        let live=Arc::new(Mutex::new(json!({"phase":"idle","status":"尚未检查更新","progress":0,"provider":"","failedAction":""})));
        let cancel=Arc::new(crate::update_cancel::Control::new(live.clone()));
        let value=Self {
            manager: UpdateManager::new(Mirrors {live:live.clone(),preferred:"gitee".into(),selected:Mutex::new(String::new()),cancel:cancel.clone(),test_base:local_test_source(&std::env::var("RHREMOTE_TEST_MODE").unwrap_or_default(),&std::env::var("RHREMOTE_TEST_UPDATE_SOURCE").unwrap_or_default())},None,None).ok(),
            available:None,live,cancel,
        };
        value.refresh();
        if value.state()["pendingRestart"] == true { value.status("downloaded", "更新已下载，可安装更新"); }
        value
    }
    pub fn snapshot(&self) -> Snapshot { self.live.clone() }
    pub fn cancellation(&self)->Arc<crate::update_cancel::Control>{self.cancel.clone()}
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
        let progress=std::thread::spawn(move||{for percent in rx {download_progress(&mut live.lock().unwrap(),percent);}});
        let result=self.manager.as_ref().unwrap().download_updates(update,Some(tx)).map_err(|e|e.to_string());
        let _=progress.join();result?;self.refresh();self.status("downloaded","更新已下载，可安装更新");Ok(())
    }
    pub fn execute(&mut self,id:&str)->Result<Value,String> {
        if id=="updates.state" {return Ok(self.state());}
        self.live.lock().unwrap()["failedAction"]=json!("");
        let result:Result<(),String>=(||{
            if self.manager.is_none(){return Err("当前是开发版，请使用 Velopack 安装包启用自动更新".into());}
            match id {
                "updates.check"=>self.check()?,
                "updates.download"=>self.download()?,
                "updates.prepare"=>{
                    if self.manager.as_ref().unwrap().get_update_pending_restart().is_none(){
                        if self.available.is_none(){self.check()?;}
                        if self.available.is_none(){return Ok(());}
                        self.download()?;
                    }
                    self.refresh();
                    self.status("downloaded","更新已下载，可安装更新");
                },
                "updates.install"=>{
                    let manager=self.manager.as_ref().unwrap();
                    let pending=manager.get_update_pending_restart().ok_or("更新包尚未准备好，请重新下载")?;
                    self.status("applying","正在重启 RHRemote 并安装更新");
                    manager.wait_exit_then_apply_updates(pending,false,true,Vec::<String>::new()).map_err(|e|e.to_string())?;
                },
                _=>return Err("unknown update action".into()),
            }
            Ok(())
        })();
        self.refresh();
        if let Err(error)=&result {let mut live=self.live.lock().unwrap();live["phase"]=json!("error");live["status"]=json!(error);live["failedAction"]=json!(if id=="updates.prepare"{"updates.install"}else{id});}
        if self.cancel.finish(){return Ok(self.state());}
        result?;
        Ok(self.state())
    }
}
/// A single primary action; advanced HTTP actions remain available for diagnostics.
pub fn primary_label(state:&Value,busy:bool)->String {
    let phase=state["phase"].as_str().unwrap_or("");
    if busy {return match phase {"cancelling"=>"正在取消…".into(),"checking"=>"检查中…".into(),"downloading"=>format!("下载中 {}%",state["progress"].as_i64().unwrap_or(0)),"verifying"=>"正在校验…".into(),"applying"=>"正在安装…".into(),_=>"正在准备…".into()};}
    let ready=state["pendingRestart"]==true;let available=state["availableVersion"].is_string();let failed=phase=="error";
    if rhremote_core::update_primary_action(state)=="updates.check" {if failed{"重新检查"}else{"检查更新"}}
    else if ready {if failed{"重试安装"}else{"重启并安装"}} else if available {if failed{"重试下载并安装"}else{"下载并安装"}} else {"检查更新"}.into()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "Explicit public release network verification; no installation"]
    fn published_download_cancels_during_transfer_and_retries(){
        let root=std::path::PathBuf::from(std::env::var_os("RHREMOTE_UPDATE_NETWORK_EVIDENCE_DIR").expect("fresh owned evidence directory required"));
        assert!(root.is_dir());let partial=root.join("public-update.partial");let retry=root.join("public-update.nupkg");assert!(!partial.exists()&&!retry.exists());
        let live=Arc::new(Mutex::new(json!({})));let cancel=Arc::new(crate::update_cancel::Control::new(live.clone()));
        let source=Arc::new(Mirrors{live,preferred:"gitee".into(),selected:Mutex::new(String::new()),test_base:None,cancel:cancel.clone()});
        let manifest:Manifest=Default::default();cancel.begin(1,"updates.check",true).unwrap();
        let feed=source.get_release_feed("win",&manifest,"").unwrap();cancel.finish();
        let asset=feed.Assets.into_iter().find(|asset|asset.Type=="Full").expect("published full package");
        fs::write(root.join("selected-public-asset.json"),serde_json::to_vec_pretty(&asset).unwrap()).unwrap();
        cancel.begin(2,"updates.download",true).unwrap();let (progress,received)=mpsc::channel();let downloader=source.clone();let selected=asset.clone();let owned=partial.clone();
        let worker=std::thread::spawn(move||downloader.download_release_entry(&selected,&owned,Some(progress)));
        let first_percent=received.recv_timeout(Duration::from_secs(60)).unwrap();let bytes_before_cancel=fs::metadata(&partial).unwrap().len();assert!(bytes_before_cancel>0&&bytes_before_cancel<asset.Size);
        let clock=std::time::Instant::now();cancel.cancel(2).unwrap();assert!(worker.join().unwrap().is_err());let cancel_ms=clock.elapsed().as_millis();assert!(cancel.finish());assert!(!partial.exists(),"cancel left the SDK partial file");
        cancel.begin(3,"updates.download",true).unwrap();source.download_release_entry(&asset,&retry,None).unwrap();assert!(!cancel.finish());assert_eq!(fs::metadata(&retry).unwrap().len(),asset.Size);
        fs::write(root.join("network-result.json"),serde_json::to_vec_pretty(&json!({"cancelled":true,"firstProgressPercent":first_percent,"bytesBeforeCancel":bytes_before_cancel,"cancelCompletionMs":cancel_ms,"partialRemoved":true,"retryDownloaded":true,"expectedSha256":asset.SHA256,"size":asset.Size,"version":asset.Version,"provider":source.selected.lock().unwrap().clone(),"installed":false})).unwrap()).unwrap();
    }
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
        assert_eq!(primary_label(&json!({"phase":"verifying","progress":100}),true),"正在校验…");
        for ready in [false,true]{assert_eq!(primary_label(&json!({"phase":"error","failedAction":"updates.check","availableVersion":"9.0.0","pendingRestart":ready}),false),"重新检查");}
    }
    #[test]
    fn mirror_fallback_returns_from_verification_to_download_progress(){
        let mut live=json!({});
        for(percent,label)in[(11,"下载中 11%"),(100,"正在校验…"),(4,"下载中 4%"),(100,"正在校验…")]{download_progress(&mut live,percent);assert_eq!(primary_label(&live,true),label);}
    }
    #[test]
    fn package_paths_cannot_escape_release_directory() {
        for name in ["../evil", "..", "C:\\evil", "a/b", "file:stream"] {
            assert!(filename(name).is_err())
        }
        assert!(filename("RHRemote-0.2.0-full.nupkg").is_ok());
    }
}
