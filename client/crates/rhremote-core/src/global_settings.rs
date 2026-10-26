use crate::{Controller, now_ms, sync_model};
use serde_json::{Value, json};

impl Controller {
    pub(crate) fn dispatch_update(&self, id: &str) -> Result<Value, String> {
        if id == "updates.state" { return Ok(self.state()["updates"].clone()); }
        let mut s = self.state.lock().unwrap();
        if s.updates_busy {
            return Err("更新操作正在进行".into());
        }
        let id = if id == "updates.primary" {
            if s.updates["availableVersion"].is_string() || s.updates["pendingRestart"] == true { "updates.install" } else { "updates.check" }
        } else { id };
        if id == "updates.install" && (s.streaming || s.connecting) {
            return Err("请先断开远程连接".into());
        }
        s.updates_busy = true;
        s.installing_update = id == "updates.install";
        s.updates["phase"] = json!(if id == "updates.check" { "checking" } else { "preparing" });
        s.updates["status"] = json!(if id == "updates.check" { "正在检查更新" } else { "正在准备更新" });
        s.revision += 1;
        drop(s);
        let result = self.platform.execute(id, &json!({}));
        let mut s = self.state.lock().unwrap();
        s.updates_busy = false;
        s.installing_update = false;
        s.revision += 1;
        let result = match result {
            Ok(v) => v,
            Err(e) => {
                s.status = e.clone();
                s.updates["phase"] = json!("error");
                s.updates["status"] = json!(e);
                return Err(e);
            }
        };
        s.updates = result.clone();
        if id == "updates.install" && result["phase"] == "applying" {
            s.window_action = "window.close".into();
        }
        Ok(result)
    }
    pub(crate) fn dispatch_global(&self, id: &str, args: Value) -> Result<Value, String> {
        if id == "app.settings.open" || id == "app.settings.tab" {
            let mut s = self.state.lock().unwrap();
            if id == "app.settings.tab" {
                let tab = args["tab"].as_str().ok_or("tab required")?;
                if !["appearance", "sync", "updates", "about"].contains(&tab) {
                    return Err("unknown settings tab".into());
                }
                s.settings_tab = tab.into();
            }
            s.page = "global-settings".into();
            s.revision += 1;
            return Ok(json!({"tab":s.settings_tab}));
        }
        let mut s = self.state.lock().unwrap();
        if s.sync_busy {
            return Err("同步操作正在进行".into());
        }
        let mut input = args;
        if id == "sync.run" || id == "sync.resolve" {
            if s.draft.is_some() {
                return Err("请先保存或取消设备设置".into());
            }
            input["shared"] = serde_json::to_value(sync_model::from_state(&s)).unwrap();
        }
        s.sync_busy = true;
        s.revision += 1;
        drop(s);
        let result = self.platform.execute(id, &input);
        let mut s = self.state.lock().unwrap();
        s.sync_busy = false;
        s.revision += 1;
        let result = match result {
            Ok(v) => v,
            Err(e) => {
                s.status = e.clone();
                return Err(e);
            }
        };
        if let Some(sync) = result.get("sync") {
            s.sync = sync.clone();
        } else if id == "sync.configure" || id == "sync.state" || id == "sync.clear" {
            s.sync = result.clone();
        }
        if let Some(shared) = result.get("shared") {
            let shared: sync_model::SharedConfig =
                serde_json::from_value(shared.clone()).map_err(|e| e.to_string())?;
            shared.validate()?;
            let mut next = s.saved.clone();
            next.apply_shared(&shared);
            if self.storage.exists() {
                let dir = self.storage.parent().unwrap().join("sync-backups");
                std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
                std::fs::copy(&self.storage, dir.join(format!("local-{}.json", now_ms())))
                    .map_err(|e| e.to_string())?;
                let mut files: Vec<_> = std::fs::read_dir(&dir)
                    .map_err(|e| e.to_string())?
                    .filter_map(Result::ok)
                    .filter(|e| e.file_name().to_string_lossy().starts_with("local-"))
                    .collect();
                files.sort_by_key(|f| f.file_name());
                let count = files.len().saturating_sub(20);
                for file in files.into_iter().take(count) {
                    let _ = std::fs::remove_file(file.path());
                }
            }
            self.persist(&next)?;
            s.saved = next;
            let devices: Vec<crate::Device> = shared
                .devices
                .into_iter()
                .map(|d| {
                    s.devices
                        .iter()
                        .find(|old| old.uuid == d.uuid)
                        .cloned()
                        .map(|mut old| {
                            old.name = d.name.clone();
                            old.address = d.address.clone();
                            old.display_name = d.display_name.clone();
                            old
                        })
                        .unwrap_or(crate::Device {
                            uuid: d.uuid,
                            name: d.name,
                            display_name: d.display_name,
                            address: d.address,
                            addresses: Vec::new(),
                            online: false,
                            paired: false,
                            is_self: false,
                        })
                })
                .collect();
            // Local identity and pairing are recomputed by the platform, never accepted from WebDAV.
            s.devices = devices.clone();
            s.status = "同步完成".into();
            s.revision += 1;
            drop(s);
            let restored = self
                .platform
                .execute("devices.restore", &json!({"devices":devices}))?;
            if let Some(rows) = restored.get("devices") {
                self.replace_devices(
                    serde_json::from_value(rows.clone()).map_err(|e| e.to_string())?,
                );
            }
            return Ok(json!({"sync":result["sync"],"conflicts":[]}));
        }
        s.status = if result["conflicts"]
            .as_array()
            .is_some_and(|a| !a.is_empty())
        {
            "配置冲突，请选择保留哪一端的冲突项"
        } else if id == "sync.configure" {
            "同步设置已保存"
        } else {
            ""
        }
        .into();
        // Public action results omit the synced document and all credential input.
        Ok(result)
    }
}

#[cfg(test)]
mod interaction_tests {
    use super::*;
    use crate::{Device,Platform,random_token};
    use std::sync::{Arc,Mutex};
    #[derive(Default)] struct Updates {calls:Mutex<Vec<String>>,fail:Mutex<bool>}
    impl Platform for Updates {
        fn execute(&self,id:&str,_:&Value)->Result<Value,String>{
            if id=="sync.state"||id=="devices.restore" {return Ok(json!({}));}
            if id=="updates.state"{return Ok(json!({"managed":true,"phase":"idle"}));}
            self.calls.lock().unwrap().push(id.into());
            if *self.fail.lock().unwrap(){return Err("fixture network unavailable".into());}
            Ok(if id=="updates.check"{json!({"availableVersion":"9.0.0","phase":"available"})}else{json!({"availableVersion":"9.0.0","pendingRestart":true,"phase":"applying"})})
        }
    }
    fn controller(platform:Arc<Updates>)->Arc<Controller>{
        Controller::new("test",std::env::temp_dir().join(format!("rhremote-interaction-{}.json",random_token().unwrap())),platform).unwrap()
    }
    #[test] fn primary_checks_first_and_only_installs_on_the_next_explicit_click(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());
        assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);assert!(c.take_window_action().is_empty());
        assert_eq!(*platform.calls.lock().unwrap(),["updates.check"]);
        assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);assert_eq!(c.take_window_action(),"window.close");
        assert_eq!(*platform.calls.lock().unwrap(),["updates.check","updates.install"]);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn failure_keeps_the_candidate_for_retry_and_never_closes_the_window(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());c.dispatch("updates.primary",json!({}));*platform.fail.lock().unwrap()=true;
        assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],false);assert_eq!(c.state()["updates"]["phase"],"error");assert_eq!(c.state()["updates"]["availableVersion"],"9.0.0");assert!(c.take_window_action().is_empty());
        *platform.fail.lock().unwrap()=false;assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn state_reads_and_navigation_are_available_while_an_update_is_busy(){
        let platform=Arc::new(Updates::default());let c=controller(platform);c.state.lock().unwrap().updates_busy=true;
        assert_eq!(c.dispatch("updates.state",json!({}))["ok"],true);assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],false);
        assert_eq!(c.dispatch("app.settings.tab",json!({"tab":"about"}))["ok"],true);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn update_http_request_does_not_serialize_state_and_navigation_behind_download(){
        use std::{io::{Read,Write},net::{TcpListener,TcpStream},sync::mpsc,time::Duration};
        struct Slow {entered:mpsc::Sender<()>,release:Mutex<mpsc::Receiver<()>>}
        impl Platform for Slow {fn execute(&self,id:&str,_:&Value)->Result<Value,String>{if id=="updates.check"{self.entered.send(()).unwrap();self.release.lock().unwrap().recv_timeout(Duration::from_secs(5)).unwrap();}Ok(json!({"managed":true,"phase":"current"}))}}
        fn request(port:u16,token:&str,path:&str,body:Option<&str>)->String {
            let mut socket=TcpStream::connect(("127.0.0.1",port)).unwrap();socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let body=body.unwrap_or("");write!(socket,"{} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {token}\r\nConnection: close\r\nContent-Length: {}\r\n\r\n{body}",if body.is_empty(){"GET"}else{"POST"},body.len()).unwrap();let mut value=String::new();socket.read_to_string(&mut value).unwrap();value
        }
        let (entered,wait)=mpsc::channel();let (release,released)=mpsc::channel();let token="fixture-token-012345678901234567890123456789";
        let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-http-{}.json",random_token().unwrap())),Arc::new(Slow{entered,release:Mutex::new(released)})).unwrap();
        let listener=TcpListener::bind(("127.0.0.1",0)).unwrap();let port=listener.local_addr().unwrap().port();drop(listener);crate::start_http(c.clone(),port,token.into()).unwrap();
        let worker=std::thread::spawn(move||request(port,token,"/v1/action",Some(r#"{"id":"updates.check","args":{}}"#)));
        wait.recv_timeout(Duration::from_secs(2)).unwrap();
        let state=request(port,token,"/v1/state",None);assert!(state.contains("\"updatesBusy\":true"),"{state}");
        let navigation=request(port,token,"/v1/action",Some(r#"{"id":"app.settings.tab","args":{"tab":"about"}}"#));assert!(navigation.contains("\"ok\":true"));
        release.send(()).unwrap();assert!(worker.join().unwrap().contains("\"ok\":true"));let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn device_address_changes_are_drafts_until_one_valid_save(){
        let c=controller(Arc::new(Updates::default()));
        let d:Device=serde_json::from_value(json!({"uuid":"host","name":"winserver","address":"192.168.0.104","addresses":["100.73.139.109"],"online":false,"paired":false,"isSelf":false})).unwrap();c.replace_devices(vec![d]);
        c.dispatch("device.settings.open",json!({"uuid":"host"}));let before=std::fs::read(&c.storage).unwrap();
        c.dispatch("settings.network.edit",json!({"key":"address","value":"bad/path"}));
        assert_eq!(c.dispatch("settings.save",json!({}))["ok"],false);assert_eq!(std::fs::read(&c.storage).unwrap(),before);
        c.dispatch("settings.network.edit",json!({"key":"address","value":"100.73.139.109"}));c.dispatch("settings.network.edit",json!({"key":"addresses","value":"192.168.0.104"}));
        assert_eq!(c.dispatch("settings.save",json!({"displayName":"主机"}))["ok"],true);
        assert_eq!(c.state()["saved"]["devices"][0]["address"],"100.73.139.109");assert_eq!(c.state()["saved"]["devices"][0]["addresses"],json!(["192.168.0.104"]));
        let saved=std::fs::read(&c.storage).unwrap();c.dispatch("device.settings.open",json!({"uuid":"host"}));c.dispatch("settings.network.edit",json!({"key":"address","value":"10.0.0.9"}));c.dispatch("settings.cancel",json!({}));assert_eq!(std::fs::read(&c.storage).unwrap(),saved);
        let _=std::fs::remove_file(&c.storage);
    }
}
