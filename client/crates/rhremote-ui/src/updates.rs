use serde_json::{Value, json};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock, mpsc::{self, Sender}},
    time::Duration,
};
use velopack::{
    Error, UpdateCheck, UpdateInfo, UpdateManager, VelopackAsset, VelopackAssetFeed,
    bundle::Manifest, sources::UpdateSource,
};

const CNB: &str = "https://cnb.cool/railgunhamster/RHRemote/-/releases/";
const GITHUB: &str = "https://github.com/RailgunHamster/RHRemote/releases/";
fn filename(name: &str) -> Result<(), Error> {
    if name.is_empty() || name == "." || name == ".." || name.contains(['/', '\\', ':', '\0']) {
        Err(Error::Other("Invalid update asset name".into()))
    } else {
        Ok(())
    }
}
type Snapshot = Arc<Mutex<Value>>;
// Identity follows the selected package, independent of mirror/progress/notes.
fn candidate_id(asset: &VelopackAsset) -> Value {
    json!([asset.PackageId,asset.Version,asset.Type,asset.FileName,asset.Size,
        asset.SHA1.to_ascii_lowercase(),asset.SHA256.to_ascii_lowercase()])
}
fn cached_path(directory:&Path,asset:&VelopackAsset)->Result<PathBuf,String>{
    filename(&asset.FileName).map_err(|e|e.to_string())?;
    if !asset.FileName.ends_with(".nupkg"){return Err("更新包文件名无效".into());}
    Ok(directory.join(&asset.FileName))
}
fn verify_cached(directory:&Path,asset:&VelopackAsset)->Result<bool,String>{
    let digest=if !asset.SHA256.is_empty(){(&asset.SHA256,64)}else{(&asset.SHA1,40)};
    if asset.Size==0 || asset.Size>2*1024*1024*1024 || digest.0.len()!=digest.1 || !digest.0.bytes().all(|b|b.is_ascii_hexdigit()) {
        return Err("更新包缺少有效的校验信息".into());
    }
    let path=cached_path(directory,asset)?;
    let metadata=match fs::symlink_metadata(&path){Ok(value)=>value,Err(e) if e.kind()==std::io::ErrorKind::NotFound=>return Ok(false),Err(e)=>return Err(e.to_string())};
    if !metadata.file_type().is_file(){return Err("更新缓存不是普通文件".into());}
    if metadata.len()!=asset.Size{return Ok(false);}
    let (sha1,sha256)=velopack::misc::calculate_sha1_sha256(&path).map_err(|e|e.to_string())?;
    Ok(if asset.SHA256.len()==64 {sha256.eq_ignore_ascii_case(&asset.SHA256)}
       else if asset.SHA256.is_empty()&&asset.SHA1.len()==40 {sha1.eq_ignore_ascii_case(&asset.SHA1)}
       else {return Err("更新包缺少有效的校验信息".into());})
}
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
        if self.live.lock().unwrap()["preferredSource"].as_str().unwrap_or(&self.preferred) == "github" {
            ["github", "cnb"]
        } else {
            ["cnb", "github"]
        }
    }
    fn feed(provider: &str, name: &str) -> String {
        if provider == "github" {
            format!("{GITHUB}latest/download/{name}")
        } else {
            format!("{CNB}latest/download/{name}")
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
            format!("{CNB}download/v{}/{name}", asset.Version, name = asset.FileName)
        })
    }
    fn asset_address(&self,provider:&str,asset:&VelopackAsset)->Result<String,Error> {
        let normal=Self::asset(provider,asset)?;
        Ok(self.test_base.as_ref().map(|base|format!("{base}{provider}/{}",asset.FileName)).unwrap_or(normal))
    }
    fn client(&self,seconds:u64)->Result<reqwest::Client,Error>{
        if rustls::crypto::CryptoProvider::get_default().is_none(){let _=rustls::crypto::ring::default_provider().install_default();}
        let value=self.live.lock().unwrap()["proxy"].clone();
        let settings=if value.is_null(){rhremote_core::proxy::Settings::default()}else{rhremote_core::proxy::Settings::from_form(&value).map_err(Error::Other)?};
        let mut builder=reqwest::Client::builder().no_proxy().https_only(self.test_base.is_none()).timeout(Duration::from_secs(seconds)).user_agent(concat!("RHRemote/",env!("CARGO_PKG_VERSION")));
        if let Some(address)=settings.for_scope("updates"){builder=builder.proxy(reqwest::Proxy::all(address).map_err(|_|Error::Other("更新代理地址无效".into()))?);}
        builder.build().map_err(|_|Error::Other("更新网络初始化失败".into()))
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
        let providers=self.providers();
        for provider in providers {
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
                self.live.lock().unwrap()["checkedPreferredSource"]=json!(providers[0]);
                let mut live=self.live.lock().unwrap();live["releaseNotes"]=json!(if feed.Assets.len()==1{feed.Assets[0].NotesMarkdown.as_str()}else{""});
                return Ok(feed);
            }
        }
        Err(Error::Other("GitHub 与 CNB 更新源均不可用".into()))
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
        let preferred=self.providers();
        let checked=self.live.lock().unwrap()["checkedPreferredSource"].as_str()==Some(preferred[0]);
        let providers = if !checked {preferred} else if selected == "github" {
            ["github", "cnb"]
        } else {
            ["cnb", "github"]
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
    verified: Option<Value>,
    directory: OnceLock<PathBuf>,
}
impl Updates {
    pub fn new() -> Self {
        let live=Arc::new(Mutex::new(json!({"phase":"idle","status":"尚未检查更新","progress":0,"provider":"","preferredSource":"cnb","failedAction":""})));
        let cancel=Arc::new(crate::update_cancel::Control::new(live.clone()));
        let value=Self {
            manager: UpdateManager::new(Mirrors {live:live.clone(),preferred:"cnb".into(),selected:Mutex::new(String::new()),cancel:cancel.clone(),test_base:local_test_source(&std::env::var("RHREMOTE_TEST_MODE").unwrap_or_default(),&std::env::var("RHREMOTE_TEST_UPDATE_SOURCE").unwrap_or_default())},None,None).ok(),
            available:None,live,cancel,verified:None,directory:OnceLock::new(),
        };
        value.refresh();
        if value.state()["pendingRestart"] == true { value.status("downloaded", "更新已下载，可安装更新"); }
        value
    }
    pub fn snapshot(&self) -> Snapshot { self.live.clone() }
    pub fn cancellation(&self)->Arc<crate::update_cancel::Control>{self.cancel.clone()}
    pub fn state(&self) -> Value { self.live.lock().unwrap().clone() }
    pub fn load_source(&self,data:&Path) {
        let path=data.join("update-source.json");
        let source=fs::symlink_metadata(&path).ok().filter(|m|m.file_type().is_file()&&m.len()<=1024)
            .and_then(|_|fs::read(&path).ok()).and_then(|bytes|serde_json::from_slice::<Value>(&bytes).ok())
            .and_then(|value|value["preferredSource"].as_str().map(str::to_owned)).filter(|source|matches!(source.as_str(),"cnb"|"github")).unwrap_or_else(||"cnb".into());
        self.live.lock().unwrap()["preferredSource"]=json!(source);
        self.live.lock().unwrap()["proxy"]=rhremote_core::proxy::Settings::load(data).map(|s|s.form()).unwrap_or_else(|error|json!({"error":error}));
    }
    pub fn set_source(&self,data:&Path,source:&str)->Result<Value,String> {
        if !matches!(source,"cnb"|"github"){return Err("请选择 CNB 或 GitHub".into());}
        fs::create_dir_all(data).map_err(|e|e.to_string())?;
        let temporary=data.join("update-source.json.tmp");
        let mut file=fs::File::create(&temporary).map_err(|e|e.to_string())?;
        file.write_all(serde_json::to_vec(&json!({"schemaVersion":1,"preferredSource":source})).unwrap().as_slice()).and_then(|_|file.sync_all()).map_err(|e|e.to_string())?;
        drop(file);
        fs::rename(&temporary,data.join("update-source.json")).map_err(|e|e.to_string())?;
        {let mut live=self.live.lock().unwrap();live["preferredSource"]=json!(source);live["provider"]=json!("");if !live["availableVersion"].is_string()&&live["pendingRestart"]!=true{live["phase"]=json!("idle");live["status"]=json!("尚未检查更新");live["failedAction"]=json!("");}}
        Ok(self.state())
    }
    fn status(&self,phase:&str,text:&str) {
        let mut live=self.live.lock().unwrap();live["phase"]=json!(phase);live["status"]=json!(text);
    }
    fn refresh(&self) {
        let pending=self.manager.as_ref().and_then(|m|m.get_update_pending_restart());
        let mut live=self.live.lock().unwrap();
        live["managed"]=json!(self.manager.is_some());live["currentVersion"]=json!(env!("CARGO_PKG_VERSION"));
        live["installedVersion"]=json!(self.manager.as_ref().map(|m|m.get_current_version_as_string()));
        live["availableVersion"]=json!(self.available.as_ref().map(|u|u.TargetFullRelease.Version.clone()).or_else(||pending.as_ref().map(|a|a.Version.clone())));
        let notes=self.available.as_ref().map(|u|u.TargetFullRelease.NotesMarkdown.clone()).or_else(||live["releaseNotes"].as_str().map(str::to_owned)).unwrap_or_default();live["notes"]=json!(notes);
        let target=self.available.as_ref().map(|u|&u.TargetFullRelease).or(pending.as_ref());
        live["candidateId"]=target.map(candidate_id).unwrap_or(Value::Null);
        live["pendingRestart"]=json!(if self.available.is_some() {
            target.is_some_and(|selected|self.verified.as_ref()==Some(&candidate_id(selected)))
        }else{pending.is_some()});
    }
    fn check(&mut self)->Result<(),String> {
        self.status("checking","正在检查更新");
        self.available=match self.manager.as_ref().ok_or("更新管理器不可用")?.check_for_updates().map_err(|e|e.to_string())? {
            UpdateCheck::UpdateAvailable(update)=>Some(*update),_=>None,
        };
        self.verified=None;
        if let Some(selected)=self.available.as_ref().map(|u|&u.TargetFullRelease) {
            let directory=self.packages_dir()?;
            self.status("verifying","正在核对缓存更新包");
            if verify_cached(&directory,selected)?{self.verified=Some(candidate_id(selected));}
            self.check_cancelled()?;
        }
        self.refresh();
        let pending=self.state()["pendingRestart"]==true;
        self.status(if pending{"downloaded"}else if self.available.is_some(){"available"}else{"current"},if pending{"更新已下载，可安装更新"}else if self.available.is_some(){"发现新版本"}else{"已是最新版本"});
        Ok(())
    }
    fn packages_dir(&self)->Result<PathBuf,String>{
        if let Some(directory)=self.directory.get(){return Ok(directory.clone());}
        let directory=velopack::locator::auto_locate_app_manifest(velopack::locator::LocationContext::FromCurrentExe)
            .map(|locator|locator.get_packages_dir()).map_err(|e|e.to_string())?;
        let _=self.directory.set(directory.clone());Ok(directory)
    }
    fn check_cancelled(&self)->Result<(),String>{
        if self.cancel.token().is_cancelled(){Err("Update operation cancelled".into())}else{Ok(())}
    }
    fn download(&mut self)->Result<(),String> {
        let update=self.available.as_ref().ok_or("请先检查更新")?;
        let directory=self.packages_dir()?;
        let target=&update.TargetFullRelease;
        self.status("verifying","正在核对缓存更新包");
        self.check_cancelled()?;
        // The pinned SDK trusts an existing final filename. Verify it first;
        // only discard this exact updater-owned cache after it fails verification.
        if verify_cached(&directory,target)? {
            self.check_cancelled()?;
            self.verified=Some(candidate_id(target));self.refresh();self.status("downloaded","更新已下载，可安装更新");return Ok(());
        }else{
            self.check_cancelled()?;
            let path=cached_path(&directory,target)?;
            match fs::remove_file(&path){Ok(())=>{},Err(e) if e.kind()==std::io::ErrorKind::NotFound=>{},Err(e)=>return Err(e.to_string())}
        }
        self.check_cancelled()?;
        self.status("downloading","正在下载更新（0%）");self.live.lock().unwrap()["progress"]=json!(0);
        let (tx,rx)=mpsc::channel::<i16>();let live=self.live.clone();
        let progress=std::thread::spawn(move||{for percent in rx {download_progress(&mut live.lock().unwrap(),percent);}});
        let result=self.manager.as_ref().unwrap().download_updates(update,Some(tx)).map_err(|e|e.to_string());
        let _=progress.join();result?;
        self.check_cancelled()?;
        self.status("verifying","正在校验更新包");
        if !verify_cached(&directory,target)?{return Err("更新缓存与所选安装包不一致，请重试下载".into());}
        self.check_cancelled()?;
        self.verified=Some(candidate_id(target));
        self.refresh();self.status("downloaded","更新已下载，可安装更新");Ok(())
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
                    if self.state()["pendingRestart"]!=true {
                        if self.available.is_none(){self.check()?;}
                        if self.available.is_none(){return Ok(());}
                        self.download()?;
                    }
                    self.refresh();
                    self.status("downloaded","更新已下载，可安装更新");
                },
                "updates.install"=>{
                    let manager=self.manager.as_ref().unwrap();
                    let pending=self.available.as_ref().map(|u|u.TargetFullRelease.clone()).or_else(||manager.get_update_pending_restart()).ok_or("更新包尚未准备好，请重新下载")?;
                    if self.available.is_some() && !verify_cached(&self.packages_dir()?,&pending)? {
                        self.verified=None;
                        return Err("更新缓存与所选安装包不一致，请重试下载".into());
                    }
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
    fn selected_proxy_carries_both_feed_fallback_and_package_download_without_origin_dns(){
        let server=tiny_http::Server::http("127.0.0.1:0").unwrap();let port=server.server_addr().to_ip().unwrap().port();
        let worker=std::thread::spawn(move||{let mut requests=Vec::new();for(status,body)in[(503,b"unavailable".as_slice()),(200,b"{\"Assets\":[]}".as_slice()),(200,b"abc".as_slice())]{let request=server.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();requests.push(request.url().to_owned());request.respond(tiny_http::Response::from_data(body).with_status_code(status)).unwrap();}requests});
        let settings=rhremote_core::proxy::Settings{enabled:true,port,..Default::default()};
        let live=Arc::new(Mutex::new(json!({"preferredSource":"cnb","proxy":settings.form()})));let cancel=Arc::new(crate::update_cancel::Control::new(live.clone()));
        let source=Mirrors{live,preferred:"cnb".into(),selected:Mutex::new(String::new()),test_base:Some("http://does-not-resolve.invalid/".into()),cancel:cancel.clone()};
        cancel.begin(1,"updates.check",true).unwrap();source.get_release_feed("win",&Manifest::default(),"").unwrap();cancel.finish();
        let asset=VelopackAsset{Version:"9.0.0".into(),FileName:"RHRemote-9.0.0-full.nupkg".into(),Size:3,..Default::default()};let fixture=CacheFixture::new();let path=fixture.directory.join(&asset.FileName);
        cancel.begin(2,"updates.download",true).unwrap();source.download_release_entry(&asset,&path,None).unwrap();cancel.finish();assert_eq!(fs::read(path).unwrap(),b"abc");
        let requests=worker.join().unwrap();assert!(requests[0].contains("/cnb/releases.win.json"));assert!(requests[1].contains("/github/releases.win.json"));assert!(requests[2].contains("/github/RHRemote-9.0.0-full.nupkg"));
    }
    #[test]
    fn preferred_source_survives_restart_and_rejects_remote_addresses() {
        let fixture=CacheFixture::new();
        let updates=Updates::new();
        assert_eq!(updates.set_source(&fixture.directory,"github").unwrap()["preferredSource"],"github");
        let restored=Updates::new();restored.load_source(&fixture.directory);
        assert_eq!(restored.state()["preferredSource"],"github");
        for invalid in ["gitee","http://192.168.0.1/","https://example.org/",""]{assert!(restored.set_source(&fixture.directory,invalid).is_err());}
        let again=Updates::new();again.load_source(&fixture.directory);
        assert_eq!(again.state()["preferredSource"],"github");
    }
    #[test]
    fn preferred_github_requests_it_first_and_falls_back_to_cnb() {
        let server=tiny_http::Server::http("127.0.0.1:0").unwrap();
        let base=format!("http://{}/",server.server_addr());
        let worker=std::thread::spawn(move||{
            let mut paths=Vec::new();
            for status in [503,200] {
                let request=server.recv_timeout(Duration::from_secs(5)).unwrap().expect("expected update request");
                paths.push(request.url().to_string());
                request.respond(tiny_http::Response::from_string("{\"Assets\":[]}").with_status_code(status)).unwrap();
            }
            paths
        });
        let live=Arc::new(Mutex::new(json!({"preferredSource":"github"})));
        let cancel=Arc::new(crate::update_cancel::Control::new(live.clone()));cancel.begin(1,"updates.check",true).unwrap();
        let source=Mirrors {live,preferred:"cnb".into(),selected:Mutex::new(String::new()),test_base:Some(base),cancel};
        source.get_release_feed("win",&Manifest::default(),"").unwrap();
        assert_eq!(worker.join().unwrap(),["/github/releases.win.json","/cnb/releases.win.json"]);
        assert_eq!(*source.selected.lock().unwrap(),"cnb");
    }
    #[test]
    fn cnb_is_primary_and_packages_are_version_pinned() {
        let live=Arc::new(Mutex::new(json!({})));
        let source=Mirrors {live:live.clone(),preferred:"cnb".into(),selected:Mutex::new(String::new()),test_base:None,cancel:Arc::new(crate::update_cancel::Control::new(live))};
        assert_eq!(source.providers(),["cnb","github"]);
        assert_eq!(Mirrors::feed("cnb","releases.win.json"),"https://cnb.cool/railgunhamster/RHRemote/-/releases/latest/download/releases.win.json");
        let asset=VelopackAsset {Version:"0.2.11".into(),FileName:"RHRemote-0.2.11-full.nupkg".into(),..Default::default()};
        assert_eq!(Mirrors::asset("cnb",&asset).unwrap(),"https://cnb.cool/railgunhamster/RHRemote/-/releases/download/v0.2.11/RHRemote-0.2.11-full.nupkg");
        assert!(Mirrors::asset("cnb",&VelopackAsset {FileName:"../escape.nupkg".into(),..asset}).is_err());
    }
    #[test]
    #[ignore = "Explicit public CNB download acceptance; requires RHREMOTE_CNB_ACCEPTANCE_DIR"]
    fn cnb_public_feed_and_full_package_are_anonymously_usable() {
        let directory=PathBuf::from(std::env::var_os("RHREMOTE_CNB_ACCEPTANCE_DIR").expect("dedicated download directory"));
        fs::create_dir_all(&directory).unwrap();
        let live=Arc::new(Mutex::new(json!({})));
        let source=Mirrors {live:live.clone(),preferred:"cnb".into(),selected:Mutex::new(String::new()),test_base:None,cancel:Arc::new(crate::update_cancel::Control::new(live))};
        source.cancel.begin(1,"updates.check",true).unwrap();
        let feed=source.get_release_feed("win",&Manifest::default(),"").unwrap();
        source.cancel.finish();
        assert_eq!(source.selected.lock().unwrap().as_str(),"cnb");
        let asset=feed.Assets.iter().find(|asset|asset.PackageId=="RHRemote"&&asset.Type=="Full").expect("full package");
        assert_eq!(asset.SHA256.len(),64);
        let path=cached_path(&directory,asset).unwrap();
        source.cancel.begin(2,"updates.download",true).unwrap();
        source.download_release_entry(asset,&path,None).unwrap();
        source.cancel.finish();
        assert!(verify_cached(&directory,asset).unwrap());
        fs::write(directory.join("conclusion.json"),serde_json::to_vec_pretty(&json!({"provider":"cnb","version":asset.Version,"fileName":asset.FileName,"size":asset.Size,"sha256":asset.SHA256,"authenticated":false,"verified":true,"installed":false})).unwrap()).unwrap();
    }
    struct CacheFixture{directory:PathBuf,asset:VelopackAsset}
    impl CacheFixture{
        fn new()->Self{
            let directory=std::env::temp_dir().join(format!("rhremote-update-cache-{}",rhremote_core::random_token().unwrap()));fs::create_dir(&directory).unwrap();
            let file="RHRemote-9.0.0-full.nupkg";fs::write(directory.join(file),b"selected package").unwrap();
            let (sha1,sha256)=velopack::misc::calculate_sha1_sha256(directory.join(file)).unwrap();
            Self{directory,asset:VelopackAsset{PackageId:"RHRemote".into(),Version:"9.0.0".into(),Type:"Full".into(),FileName:file.into(),Size:16,SHA1:sha1,SHA256:sha256,..Default::default()}}
        }
    }
    impl Drop for CacheFixture{fn drop(&mut self){let _=fs::remove_dir_all(&self.directory);}}
    #[test] fn selected_package_identity_ignores_notes_but_catches_equal_version_replacements(){
        let fixture=CacheFixture::new();let mut replacement=fixture.asset.clone();replacement.NotesMarkdown="New description".into();replacement.SHA256=replacement.SHA256.to_ascii_lowercase();
        assert_eq!(candidate_id(&fixture.asset),candidate_id(&replacement));
        replacement.SHA256="0".repeat(64);assert_ne!(candidate_id(&fixture.asset),candidate_id(&replacement));
        replacement=fixture.asset.clone();replacement.FileName="other.nupkg".into();assert_ne!(candidate_id(&fixture.asset),candidate_id(&replacement));
    }
    #[test] fn cached_package_with_same_name_and_size_must_match_the_selected_digest(){
        let fixture=CacheFixture::new();assert!(verify_cached(&fixture.directory,&fixture.asset).unwrap());
        fs::write(fixture.directory.join(&fixture.asset.FileName),b"obsolete package").unwrap();
        assert_eq!(fs::metadata(fixture.directory.join(&fixture.asset.FileName)).unwrap().len(),fixture.asset.Size);
        assert!(!verify_cached(&fixture.directory,&fixture.asset).unwrap());
        fs::write(fixture.directory.join(&fixture.asset.FileName),b"selected package").unwrap();assert!(verify_cached(&fixture.directory,&fixture.asset).unwrap());
    }
    #[test] fn cached_package_validation_preserves_sha1_fallback_and_rejects_unsafe_files(){
        let mut fixture=CacheFixture::new();fixture.asset.SHA256.clear();assert!(verify_cached(&fixture.directory,&fixture.asset).unwrap());
        fixture.asset.SHA1.clear();assert!(verify_cached(&fixture.directory,&fixture.asset).is_err());
        fixture.asset.FileName="../outside.nupkg".into();assert!(verify_cached(&fixture.directory,&fixture.asset).is_err());
        fixture.asset.FileName="directory.nupkg".into();fs::create_dir(fixture.directory.join("directory.nupkg")).unwrap();assert!(verify_cached(&fixture.directory,&fixture.asset).is_err());
    }
    #[cfg(windows)]
    #[test] fn actual_sdk_replaces_a_wrong_equal_length_cache_and_reuses_the_verified_selected_package(){
        use std::sync::atomic::{AtomicUsize,Ordering};
        struct Source{calls:Arc<AtomicUsize>}
        impl UpdateSource for Source{
            fn get_release_feed(&self,_:&str,_:&Manifest,_:&str)->Result<VelopackAssetFeed,Error>{unreachable!()}
            fn download_release_entry(&self,_:&VelopackAsset,path:&Path,_:Option<Sender<i16>>)->Result<(),Error>{self.calls.fetch_add(1,Ordering::SeqCst);fs::write(path,b"selected package")?;Ok(())}
        }
        let fixture=CacheFixture::new();let current=fixture.directory.join("current");fs::create_dir(&current).unwrap();fs::write(fixture.directory.join("Update.exe"),b"not executed").unwrap();
        fs::write(current.join("sq.version"),"<package><metadata><id>RHRemote</id><version>1.0.0</version><title>Fixture</title><authors>Fixture</authors><mainExe>fixture.exe</mainExe></metadata></package>").unwrap();
        let calls=Arc::new(AtomicUsize::new(0));let mut config=velopack::locator::create_config_from_root_dir(&fixture.directory);config.PackagesDir=fixture.directory.clone();
        let manager=UpdateManager::new(Source{calls:calls.clone()},None,Some(config)).unwrap();
        let live=Arc::new(Mutex::new(json!({})));let cancel=Arc::new(crate::update_cancel::Control::new(live.clone()));
        let directory=OnceLock::new();directory.set(fixture.directory.clone()).unwrap();
        let mut updates=Updates{manager:Some(manager),available:Some(UpdateInfo{TargetFullRelease:fixture.asset.clone(),..Default::default()}),live,cancel,verified:None,directory};
        fs::write(fixture.directory.join(&fixture.asset.FileName),b"obsolete package").unwrap();updates.refresh();assert_eq!(updates.state()["pendingRestart"],false);
        updates.cancel.begin(1,"updates.install",true).unwrap();updates.execute("updates.prepare").unwrap();
        assert_eq!(calls.load(Ordering::SeqCst),1);assert_eq!(updates.state()["pendingRestart"],true);assert!(verify_cached(&fixture.directory,&fixture.asset).unwrap());
        updates.cancel.begin(2,"updates.download",true).unwrap();updates.execute("updates.download").unwrap();assert_eq!(calls.load(Ordering::SeqCst),1,"The verified selected file should not download again");
        updates.available.as_mut().unwrap().TargetFullRelease.SHA256="0".repeat(64);updates.refresh();assert_eq!(updates.state()["pendingRestart"],false);
        updates.cancel.begin(3,"updates.install",false).unwrap();assert!(updates.execute("updates.install").unwrap_err().contains("缓存与所选安装包不一致"));assert_ne!(updates.state()["phase"],"applying");
    }
    #[test]
    #[ignore = "Explicit public release network verification; no installation"]
    fn published_download_cancels_during_transfer_and_retries(){
        let root=std::path::PathBuf::from(std::env::var_os("RHREMOTE_UPDATE_NETWORK_EVIDENCE_DIR").expect("fresh owned evidence directory required"));
        assert!(root.is_dir());let partial=root.join("public-update.partial");let retry=root.join("public-update.nupkg");assert!(!partial.exists()&&!retry.exists());
        let live=Arc::new(Mutex::new(json!({})));let cancel=Arc::new(crate::update_cancel::Control::new(live.clone()));
        let source=Arc::new(Mirrors{live,preferred:"cnb".into(),selected:Mutex::new(String::new()),test_base:None,cancel:cancel.clone()});
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
