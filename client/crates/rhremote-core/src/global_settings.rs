use crate::{Controller, now_ms, sync_model};
use serde_json::{Value, json};

/// UI and API must retry the operation that failed, even with a cached candidate.
pub fn update_primary_action(state:&Value)->&'static str {
    if state["phase"]=="error" && state["failedAction"]=="updates.check" || state["phase"]=="cancelled" && state["cancelledAction"]=="updates.check" {"updates.check"}
    else if state["availableVersion"].is_string() || state["pendingRestart"]==true {"updates.install"}
    else {"updates.check"}
}

pub(crate) fn update_primary_intent(state:&Value,operation:u64)->String {
    json!([update_primary_action(state),operation,state["availableVersion"],state["pendingRestart"]==true,state["candidateId"]]).to_string()
}
pub(crate) fn validate_update_intent(id:&str,args:&Value,state:&Value,operation:u64)->Result<(),String>{
    if matches!(id,"updates.primary"|"updates.check"|"updates.download"|"updates.install") {
        if let Some(expected)=args.get("expectedIntent") {
            if expected.as_str()!=Some(update_primary_intent(state,operation).as_str()) {
                return Err("更新状态已变化，请重新点击当前操作".into());
            }
        }
    }
    Ok(())
}

pub(crate) fn update_installation_blocked(s: &crate::State) -> Option<&'static str> {
    if s.streaming || s.connecting || s.connection.busy() || s.page == "pair" || s.desktop_open_requests>0 {
        Some("请先完成或断开当前远程连接")
    } else if s.diagnostics.busy {
        Some("请先完成或取消连接诊断")
    } else if s.sync_busy {
        Some("请先完成配置同步")
    } else if s.page == "add" || s.add.busy() {
        Some("请先完成或取消添加设备")
    } else if s.pending_navigation.is_some() || s.sync_clear_pending {
        Some("请先处理当前确认")
    } else if s.closing {
        Some("应用正在关闭")
    } else { None }
}

fn sync_feedback_visible(s: &crate::State) -> bool {
    s.page == "devices" || (s.page == "global-settings" && s.settings_tab == "sync")
}

impl Controller {
    fn sync_progress(&self, phase: &str, message: &str) {
        let mut s = self.state.lock().unwrap();
        s.sync["phase"] = json!(phase);
        if sync_feedback_visible(&s) {
            s.status = message.into();
        }
        s.revision += 1;
    }
    fn sync_completion_failed(&self, message: &str) -> String {
        let mut s = self.state.lock().unwrap();
        s.sync_busy = false;
        if !s.sync.is_object() {
            s.sync = json!({});
        }
        s.sync["phase"] = json!("error");
        s.sync["automaticBlocked"] = json!(true);
        s.sync["error"] = json!(message);
        s.sync_error = message.into();
        if sync_feedback_visible(&s) {
            s.status = message.into();
        }
        s.revision += 1;
        message.into()
    }
    pub fn automatic_sync_due(&self,now:u64,last_attempt:u64)->bool{
        let s=self.state.lock().unwrap();
        s.sync["automatic"]==true && !crate::interaction::automatic_sync_blocked(&s)
            && s.sync["conflicts"].as_array().is_none_or(|a|a.is_empty())
            && now.saturating_sub(last_attempt.max(s.sync["lastSyncMs"].as_u64().unwrap_or(0)))>=s.sync["intervalMinutes"].as_u64().unwrap_or(5)*60_000
    }
    pub(crate) fn dispatch_update(&self, id: &str, args: &Value) -> Result<Value, String> {
        if id == "updates.state" { return Ok(self.state()["updates"].clone()); }
        let observed=if args.get("expectedIntent").is_some(){self.platform.update_state()}else{None};
        let mut s = self.state.lock().unwrap();
        if id=="updates.cancel" {
            let operation=args["operationId"].as_u64().filter(|id|*id==s.update_operation).ok_or("更新操作已变化，未执行取消")?;
            if !s.updates_busy || s.installing_update {return Err("当前没有可取消的更新操作".into());}
            if !s.update_cancel_requested {self.platform.cancel_update(operation)?;s.update_cancel_requested=true;s.revision+=1;}
            return Ok(json!({"accepted":true,"operationId":operation,"cleanupPending":true}));
        }
        if id=="updates.details.toggle" {
            if s.page!="global-settings" || s.settings_tab!="updates" {return Err("请先打开更新页".into());}
            s.update_details=!s.update_details;s.revision+=1;return Ok(json!({"expanded":s.update_details}));
        }
        if id.starts_with("updates.history."){
            if s.page!="global-settings"||s.settings_tab!="updates"{return Err("请先打开更新页".into());}
            drop(s);let history=self.platform.execute(id,args)?;let mut s=self.state.lock().unwrap();s.updates["history"]=history.clone();s.revision+=1;return Ok(history);
        }
        if id=="updates.notes.open"{drop(s);return self.platform.execute(id,args);}
        validate_update_intent(id,args,observed.as_ref().unwrap_or(&s.updates),s.update_operation)?;
        if s.updates_busy {
            return Err("更新操作正在进行".into());
        }
        if id=="updates.source.set" {
            if s.closing{return Err("应用正在关闭".into());}
            let source=args["source"].as_str().filter(|value|matches!(*value,"cnb"|"github")).ok_or("请选择 CNB 或 GitHub")?;
            let updated=self.platform.execute(id,&json!({"source":source}))?;
            s.updates=updated.clone();s.revision+=1;return Ok(updated);
        }
        if let Some(observed)=observed{s.updates=observed;}
        let id = if id == "updates.primary" {
            update_primary_action(&s.updates)
        } else { id };
        if id == "updates.install" {
            if let Some(reason) = update_installation_blocked(&s).or_else(||self.desktop_installation_hint()) { return Err(reason.into()); }
            // Navigation and admission are separate lock acquisitions. Recheck here
            // so a concurrent edit cannot slip between the decision and installation.
            if crate::interaction::dirty(&s) || crate::interaction::sync_dirty(&s) || crate::device_info::dirty(&s) {
                drop(s);
                if let Some(result) = self.navigation_gate("updates.install", args) { return result; }
                return self.dispatch_update("updates.install",args);
            }
        }
        let preparing = id == "updates.install" && s.updates["pendingRestart"] != true;
        let operation=s.update_operation.checked_add(1).ok_or("更新操作编号已耗尽")?;
        self.platform.begin_update(operation,id,id!="updates.install" || preparing)?;
        s.update_operation=operation;s.update_cancel_requested=false;
        s.updates_busy = true;
        s.update_details = false;
        s.installing_update = id == "updates.install" && !preparing;
        s.updates["failedAction"]=json!("");
        s.updates["phase"] = json!(if id == "updates.check" { "checking" } else { "preparing" });
        s.updates["status"] = json!(if id == "updates.check" { "正在检查更新" } else { "正在准备更新" });
        s.revision += 1;
        drop(s);
        // Preparation may take minutes. Do not launch the updater until the
        // subsequent install request passes the current draft/session guards.
        let result = self.platform.execute(if preparing { "updates.prepare" } else { id }, &json!({}));
        let mut s = self.state.lock().unwrap();
        s.updates_busy = false;
        s.installing_update = false;
        s.revision += 1;
        if s.update_cancel_requested || result.as_ref().is_ok_and(|value|value["phase"]=="cancelled") {
            let mut cancelled=self.platform.update_state().unwrap_or_else(||result.as_ref().ok().cloned().unwrap_or_else(||s.updates.clone()));
            cancelled["phase"]=json!("cancelled");cancelled["cancelledAction"]=json!(id);cancelled["failedAction"]=json!("");
            cancelled["status"]=json!(if cancelled["pendingRestart"]==true{"操作已取消，更新包已保留"}else if id=="updates.check"{"已取消检查更新"}else{"已取消下载，未安装更新"});
            s.updates=cancelled.clone();return Ok(cancelled);
        }
        let mut result = match result {
            Ok(v) => v,
            Err(e) => {
                s.updates["phase"] = json!("error");
                s.updates["status"] = json!(e);
                s.updates["failedAction"]=json!(id);
                return Err(e);
            }
        };
        s.updates = result.clone();
        if preparing && result["pendingRestart"] == true {
            if let Some(reason) = update_installation_blocked(&s).or_else(||self.desktop_installation_hint()) {
                result["installDeferred"] = json!(true);
                result["deferredReason"] = json!(reason);
                return Ok(result);
            }
            let intent=update_primary_intent(&result,s.update_operation);
            drop(s);
            let continued = self.dispatch("updates.install", json!({"expectedIntent":intent}));
            if continued["ok"] == true { return Ok(continued["result"].clone()); }
            let current = self.state.lock().unwrap();
            if current.updates_busy || update_installation_blocked(&current).is_some() || self.desktop_installation_hint().is_some() {
                let mut ready = current.updates.clone();
                ready["installDeferred"] = json!(true);
                return Ok(ready);
            }
            return Err(continued["error"].as_str().unwrap_or("安装更新失败").into());
        }
        if !preparing && id == "updates.install" && result["phase"] == "applying" {
            s.installing_update = true;
            s.window_action = "window.close".into();
        }
        Ok(result)
    }
    pub(crate) fn dispatch_global(&self, id: &str, args: Value) -> Result<Value, String> {
        if id.starts_with("proxy.") {
            let mut s=self.state.lock().unwrap();
            if id=="proxy.state"{return Ok(s.proxy.clone());}
            if s.page!="global-settings"||s.settings_tab!="proxy"{return Err("请先打开代理设置页".into());}
            if s.streaming||s.connecting||s.connection.busy()||s.desktop_open_requests>0||s.sync_busy||s.updates_busy||s.installing_update||s.diagnostics.busy||self.desktop_installation_hint().is_some(){return Err("请先结束连接、同步或更新操作，再修改代理".into());}
            s.proxy_error.clear();
            match id {
                "proxy.edit"=>{let key=args["key"].as_str().ok_or("代理字段缺失")?;let value=args["value"].clone();match key{"enabled"|"updates"|"sync"|"remote" if value.is_boolean()=>{},"host"|"protocol"|"port" if value.as_str().is_some_and(|s|s.len()<=253)=>{},_=>return Err("代理字段无效".into())};let mut draft=s.proxy_draft.clone().unwrap_or_else(||s.proxy.clone());draft[key]=value;s.proxy_draft=Some(draft);},
                "proxy.cancel"=>s.proxy_draft=None,
                "proxy.save"=>{let settings=crate::proxy::Settings::from_form(s.proxy_draft.as_ref().unwrap_or(&s.proxy))?;let updated=self.platform.execute("proxy.configure",&serde_json::to_value(settings).unwrap())?;s.proxy=updated;s.proxy_draft=None;},
                _=>return Err("未知代理操作".into()),
            }s.revision+=1;return Ok(s.proxy.clone());
        }
        if id == "app.settings.open" || id == "app.settings.tab" {
            let mut s = self.state.lock().unwrap();
            if id == "app.settings.tab" {
                let tab = args["tab"].as_str().ok_or("tab required")?;
                if !["appearance", "proxy", "sync", "updates", "about"].contains(&tab) {
                    return Err("unknown settings tab".into());
                }
                s.settings_tab = tab.into();
            }
            s.page = "global-settings".into();
            s.status.clear();
            if s.settings_tab=="sync"{crate::interaction::begin_sync(&mut s);}
            s.revision += 1;
            return Ok(json!({"tab":s.settings_tab}));
        }
        let mut s = self.state.lock().unwrap();
        if id=="sync.state" {return Ok(s.sync.clone());}
        crate::interaction::validate_sync_review(&s.sync,id,&args)?;
        if id=="sync.run" && args["automatic"]==true && s.sync["automaticBlocked"]==true{return Ok(json!({"skipped":true,"reason":"sync_interrupted"}));}
        if id=="sync.run" && args["automatic"]==true && crate::interaction::sync_review_waiting(&s.sync){return Ok(json!({"skipped":true,"reason":"sync_conflict"}));}
        if id=="sync.run" && args["automatic"]==true && (crate::interaction::automatic_sync_blocked(&s)||s.sync["automatic"]!=true){return Ok(json!({"skipped":true}));}
        if s.sync_busy {
            return Err("同步操作正在进行".into());
        }
        if id=="sync.cancel"{s.sync_draft=None;s.sync_error.clear();s.status.clear();crate::interaction::begin_sync(&mut s);s.revision+=1;return Ok(json!({"cancelled":true}));}
        if id=="sync.edit"{
            let key=args["key"].as_str().ok_or("key required")?;let value=&args["value"];
            match key{"url"|"username"|"password"=>{if !value.is_string()||value.as_str().unwrap().len()>4096{return Err("输入过长或类型无效".into());}},"automatic"=>{if !value.is_boolean(){return Err("automatic must be boolean".into());}},"intervalMinutes"=>{if ![json!(5),json!(15),json!(30),json!(60)].contains(value){return Err("自动同步间隔只能为 5、15、30 或 60 分钟".into());}},_=>return Err("unknown sync setting".into())}
            crate::interaction::begin_sync(&mut s);s.sync_draft.as_mut().unwrap()[key]=value.clone();if s.sync["automaticBlocked"]!=true{s.sync_error.clear();}s.status.clear();s.revision+=1;return Ok(json!({"edited":true}));
        }
        if id == "sync.clear" {
            if s.page != "global-settings" || s.settings_tab != "sync" { return Err("请先打开配置同步页".into()); }
            if s.sync["hasPassword"] != true && s.sync["url"].as_str().unwrap_or("").is_empty() { return Err("没有已保存的同步设置".into()); }
            if !s.sync_clear_pending {s.decision_generation=s.decision_generation.checked_add(1).ok_or("确认操作编号已耗尽")?;s.sync_clear_request_id=s.decision_generation.to_string();}
            s.sync_clear_pending = true;
            s.sync_error.clear();
            s.status.clear();
            s.revision += 1;
            return Ok(json!({"confirmationRequired":true}));
        }
        if id == "sync.clear.resolve" {
            if !s.sync_clear_pending { return Err("没有等待确认的移除操作".into()); }
            if let Some(expected)=args.get("requestId"){if expected.as_str()!=Some(s.sync_clear_request_id.as_str()){return Err("该移除确认已失效，请使用当前对话框".into());}}
            match args["choice"].as_str() {
                Some("cancel") => { s.sync_clear_pending=false;s.sync_error=if s.sync["automaticBlocked"]==true{s.sync["error"].as_str().unwrap_or("").into()}else{String::new()};s.revision+=1;return Ok(json!({"cancelled":true})); }
                Some("remove") => {}
                _ => return Err("unknown removal choice".into()),
            }
        }
        let id=if id=="sync.save"{"sync.configure"}else if id=="sync.clear.resolve"{"sync.clear"}else{id};
        let args=if id=="sync.configure"&&args.as_object().is_none_or(|v|v.is_empty()){s.sync_draft.clone().ok_or("请先编辑 WebDAV 设置")?}else{args};
        let mut input = args;
        if id == "sync.run" || id == "sync.resolve" {
            if s.draft.is_some() {
                return Err("请先保存或取消设备设置".into());
            }
            input["shared"] = serde_json::to_value(sync_model::from_state(&s)).unwrap();
        }
        let previous_transfer_error=s.sync["error"].as_str().unwrap_or("").to_owned();
        let previous_account=json!([s.sync["url"],s.sync["username"],s.sync["hasPassword"]]);
        s.sync_busy = true;
        s.sync_error.clear();
        s.revision += 1;
        drop(s);
        let result = self.platform.execute(id, &input);
        let failed_state = if result.is_err() {
            self.platform.execute("sync.state", &json!({})).ok()
        } else {
            None
        };
        let mut s = self.state.lock().unwrap();
        s.revision += 1;
        let result = match result {
            Ok(v) => v,
            Err(e) => {
                s.sync_busy = false;
                if let Some(sync) = failed_state {
                    s.sync = sync;
                }
                if id == "sync.clear" {
                    s.status.clear();
                } else if sync_feedback_visible(&s) {
                    s.status = e.clone();
                }
                s.sync_error = e.clone();
                if s.sync["automaticBlocked"] == true || matches!(id,"sync.run"|"sync.resolve") {
                    s.sync["error"] = json!(e);
                } else if !previous_transfer_error.is_empty()
                    && previous_account==json!([s.sync["url"],s.sync["username"],s.sync["hasPassword"]]) {
                    // Invalid draft input must not replace the last transfer result.
                    s.sync["error"]=json!(previous_transfer_error);
                }
                return Err(e);
            }
        };
        if matches!(id,"sync.run"|"sync.resolve") {s.sync["error"]=json!("");}
        if let Some(sync) = result.get("sync") {
            s.sync = sync.clone();
        } else if id == "sync.configure" || id == "sync.state" || id == "sync.clear" {
            s.sync = result.clone();
        }
        if id == "sync.clear" {
            s.sync_clear_pending = false;
        }
        if id == "sync.configure" || id == "sync.clear" {
            s.sync_draft = None;
            s.sync_error.clear();
            if s.page == "global-settings" && s.settings_tab == "sync" {
                crate::interaction::begin_sync(&mut s);
            }
        }
        if let Some(shared) = result.get("shared") {
            s.sync["phase"] = json!("applying");
            if sync_feedback_visible(&s) {
                s.status = "正在应用本机配置…".into();
            }
            s.revision += 1;
            let applied = (|| -> Result<(sync_model::SharedConfig, Vec<crate::Device>), String> {
                let shared: sync_model::SharedConfig =
                    serde_json::from_value(shared.clone()).map_err(|e| e.to_string())?;
                shared.validate()?;
                let before = s.saved.clone();
                let mut next = before.clone();
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
                let actual: Value = serde_json::from_slice(
                    &std::fs::read(&self.storage).map_err(|e| e.to_string())?,
                )
                .map_err(|e| e.to_string())?;
                if actual != serde_json::to_value(&next).map_err(|e| e.to_string())? {
                    return Err("本机配置回读不匹配".into());
                }
                s.saved = next;
                crate::device_import::apply(&mut s,&before,&shared);
                let devices=s.devices.clone();
                Ok((shared, devices))
            })();
            let (shared, devices) = match applied {
                Ok(value) => value,
                Err(_) => {
                    drop(s);
                    return Err(self.sync_completion_failed(
                        "同步已传输，但本机配置保存失败。自动同步已暂停，请检查存储后重试",
                    ));
                }
            };
            drop(s);
            self.sync_progress("restoring", "正在刷新设备状态…");
            let restored = match self
                .platform
                .execute("devices.restore", &json!({"devices":devices,"replace":true}))
            {
                Ok(value) => value,
                Err(_) => {
                    return Err(self.sync_completion_failed(
                        "配置已保存，但设备状态刷新失败。自动同步已暂停，请重试",
                    ));
                }
            };
            if let Some(rows) = restored.get("devices") {
                let devices = match serde_json::from_value(rows.clone()) {
                    Ok(value) => value,
                    Err(_) => {
                        return Err(self.sync_completion_failed(
                            "配置已保存，但设备状态无效。自动同步已暂停，请重试",
                        ));
                    }
                };
                self.replace_devices(devices);
            }
            self.sync_progress("recording", "正在保存同步完成状态…");
            let acknowledged = match self.platform.sync_applied(&shared, &self.storage) {
                Ok(value) => value,
                Err(_) => {
                    return Err(self.sync_completion_failed(
                        "配置已应用，但同步完成状态保存失败。自动同步已暂停，请检查存储后重试",
                    ));
                }
            };
            let mut s = self.state.lock().unwrap();
            if let Some(sync) = acknowledged {
                s.sync = sync;
            } else {
                s.sync["phase"] = json!("completed");
            }
            s.sync_busy = false;
            s.sync_error.clear();
            if sync_feedback_visible(&s) {
                s.status = "同步完成".into();
            }
            s.revision += 1;
            return Ok(json!({"sync":s.sync,"conflicts":[]}));
        }
        s.sync_busy = false;
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
            Ok(if id=="updates.check"{json!({"availableVersion":"9.0.0","phase":"available"})}else{json!({"availableVersion":"9.0.0","pendingRestart":true,"phase":if id=="updates.prepare"{"downloaded"}else{"applying"}})})
        }
    }
    fn controller(platform:Arc<Updates>)->Arc<Controller>{
        Controller::new("test",std::env::temp_dir().join(format!("rhremote-interaction-{}.json",random_token().unwrap())),platform).unwrap()
    }
    #[test]
    fn automatic_sync_and_stale_review_requests_cannot_replace_waiting_content_or_open_a_draft_prompt(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());
        {let mut s=c.state.lock().unwrap();s.sync=json!({"url":"http://fixture.test/dav","username":"fixture","automatic":true,"hasPassword":true,"conflicts":["devices.host"],"phase":"conflict","reviewId":"new-review"});}
        assert_eq!(c.dispatch("app.settings.tab",json!({"tab":"sync"}))["ok"],true);
        assert_eq!(c.dispatch("sync.edit",json!({"key":"username","value":"unsaved"}))["ok"],true);
        let automatic=c.dispatch("sync.run",json!({"automatic":true}));assert_eq!(automatic["ok"],true);assert_eq!(automatic["result"]["skipped"],true,"{automatic}");
        for invalid in [json!("true"),json!(1),Value::Null]{assert_eq!(c.dispatch("sync.run",json!({"automatic":invalid}))["ok"],false);}
        for invalid in [json!("old-review"),json!(""),json!(1)]{assert_eq!(c.dispatch("sync.resolve",json!({"resolution":"remote","reviewId":invalid}))["ok"],false);}
        assert!(c.state()["navigationPrompt"].is_null());assert_eq!(c.state()["sync"]["reviewId"],"new-review");assert_eq!(c.state()["syncDraft"]["username"],"unsaved");assert!(platform.calls.lock().unwrap().is_empty());
        let current=c.dispatch("sync.resolve",json!({"resolution":"remote","reviewId":"new-review"}));assert_eq!(current["ok"],true,"{current}");assert!(c.state()["navigationPrompt"].is_object());assert!(platform.calls.lock().unwrap().is_empty());let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn fresh_update_intent_uses_the_live_candidate_instead_of_the_older_controller_cache(){
        struct Live{snapshot:Mutex<Value>,calls:Mutex<Vec<String>>}
        impl Platform for Live{
            fn update_state(&self)->Option<Value>{Some(self.snapshot.lock().unwrap().clone())}
            fn execute(&self,id:&str,_:&Value)->Result<Value,String>{
                if id=="updates.state"{return Ok(self.snapshot.lock().unwrap().clone());}
                if id.starts_with("updates."){self.calls.lock().unwrap().push(id.into());return Ok(self.snapshot.lock().unwrap().clone());}
                Ok(json!({}))
            }
        }
        let platform=Arc::new(Live{snapshot:Mutex::new(json!({"managed":true,"phase":"idle"})),calls:Mutex::new(vec![])});
        let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-update-live-{}.json",random_token().unwrap())),platform.clone()).unwrap();
        *platform.snapshot.lock().unwrap()=json!({"managed":true,"phase":"available","availableVersion":"9.0.0","candidateId":"package-a"});
        let expected=c.state()["updates"]["intent"].clone();let result=c.dispatch("updates.primary",json!({"expectedIntent":expected}));assert_eq!(result["ok"],true,"{result}");
        assert_eq!(*platform.calls.lock().unwrap(),["updates.prepare"]);
        let expected=c.state()["updates"]["intent"].clone();platform.snapshot.lock().unwrap()["candidateId"]=json!("replacement-same-version");
        assert_eq!(c.dispatch("updates.primary",json!({"expectedIntent":expected}))["ok"],false);assert_eq!(platform.calls.lock().unwrap().len(),1);let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn update_intent_ignores_progress_notes_and_mirror_but_not_the_selected_package_or_task(){
        let a=json!({"phase":"available","availableVersion":"9.0.0","candidateId":"package-a"});let mut b=a.clone();b["notes"]=json!("New notes");b["provider"]=json!("cnb");b["progress"]=json!(43);
        assert_eq!(update_primary_intent(&a,4),update_primary_intent(&b,4));assert_ne!(update_primary_intent(&a,4),update_primary_intent(&b,5));b["candidateId"]=json!("package-b");assert_ne!(update_primary_intent(&a,4),update_primary_intent(&b,4));
        for malformed in [Value::Null,json!(4),json!([]),json!({})]{assert!(validate_update_intent("updates.primary",&json!({"expectedIntent":malformed}),&a,4).is_err());}
    }
    #[test]
    fn download_completion_must_not_close_a_draft_opened_while_install_was_preparing() {
        use std::sync::{atomic::{AtomicBool, Ordering}, mpsc};
        struct Preparing {
            first: AtomicBool,
            entered: mpsc::Sender<()>,
            release: Mutex<mpsc::Receiver<()>>,
        }
        impl Platform for Preparing {
            fn execute(&self, id: &str, _: &Value) -> Result<Value, String> {
                if id == "sync.state" || id == "devices.restore" { return Ok(json!({})); }
                if id == "updates.state" { return Ok(json!({"managed":true,"phase":"available","availableVersion":"9.0.0"})); }
                if matches!(id, "updates.prepare" | "updates.install") {
                    if self.first.swap(false, Ordering::SeqCst) {
                        self.entered.send(()).unwrap();
                        self.release.lock().unwrap().recv_timeout(std::time::Duration::from_secs(5)).unwrap();
                    }
                    return Ok(json!({"managed":true,"availableVersion":"9.0.0","pendingRestart":true,
                        "phase":if id == "updates.prepare" {"downloaded"} else {"applying"}}));
                }
                Err(format!("Unexpected external operation: {id}"))
            }
        }
        let (entered, ready) = mpsc::channel();
        let (release, wait) = mpsc::channel();
        let platform = Arc::new(Preparing { first: AtomicBool::new(true), entered, release: Mutex::new(wait) });
        let path = std::env::temp_dir().join(format!("rhremote-update-draft-{}.json", random_token().unwrap()));
        let c = Controller::new("windows", path.clone(), platform).unwrap();
        c.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"PC","address":"192.0.2.1","paired":true,"online":true}])).unwrap());
        let updating = c.clone();
        let worker = std::thread::spawn(move || updating.dispatch("updates.install", json!({})));
        ready.recv_timeout(std::time::Duration::from_secs(5)).unwrap();
        let opened = c.dispatch("device.settings.open", json!({"uuid":"host"}));
        let edited = c.dispatch("settings.input", json!({"key":"fps","value":"24"}));
        release.send(()).unwrap();
        let finished = worker.join().unwrap();
        assert_eq!(opened["ok"], true);
        assert_eq!(edited["ok"], true);
        assert_eq!(finished["ok"], true);
        let close = c.take_window_action();
        let current = c.state();
        let _ = std::fs::remove_file(path);
        assert!(close.is_empty(), "Downloading must not bypass the draft decision: {close}");
        assert_eq!(current["navigationPrompt"]["kind"], "unsaved-device");
        assert_eq!(current["settingsInputs"]["fps"], "24");
    }
    #[test] fn primary_checks_first_and_only_installs_on_the_next_explicit_click(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());
        assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);assert!(c.take_window_action().is_empty());
        assert_eq!(*platform.calls.lock().unwrap(),["updates.check"]);
        assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);assert_eq!(c.take_window_action(),"window.close");
        assert_eq!(*platform.calls.lock().unwrap(),["updates.check","updates.prepare","updates.install"]);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn failure_keeps_the_candidate_for_retry_and_never_closes_the_window(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());c.dispatch("updates.primary",json!({}));*platform.fail.lock().unwrap()=true;
        assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],false);assert_eq!(c.state()["updates"]["phase"],"error");assert_eq!(c.state()["updates"]["availableVersion"],"9.0.0");assert!(c.take_window_action().is_empty());
        *platform.fail.lock().unwrap()=false;assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn failed_recheck_retries_checking_without_turning_into_installation(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());c.dispatch("updates.primary",json!({}));
        *platform.fail.lock().unwrap()=true;assert_eq!(c.dispatch("updates.check",json!({}))["ok"],false);assert_eq!(c.state()["updates"]["availableVersion"],"9.0.0");
        *platform.fail.lock().unwrap()=false;assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);
        assert_eq!(*platform.calls.lock().unwrap(),["updates.check","updates.check","updates.check"]);assert!(c.take_window_action().is_empty());
        assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);assert_eq!(platform.calls.lock().unwrap().last().unwrap(),"updates.install");let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn rechecking_a_cached_version_does_not_discard_or_prompt_for_an_unrelated_draft(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());
        c.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"PC","address":"192.168.0.104","paired":true,"online":true}])).unwrap());
        c.dispatch("device.settings.open",json!({"uuid":"host"}));c.dispatch("settings.input",json!({"key":"width","value":"half"}));
        c.state.lock().unwrap().updates=json!({"availableVersion":"9.0.0","phase":"error","failedAction":"updates.check"});
        assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],true);assert!(c.state()["navigationPrompt"].is_null());assert_eq!(c.state()["page"],"settings");assert_eq!(c.state()["settingsInputs"]["width"],"half");assert_eq!(*platform.calls.lock().unwrap(),["updates.check"]);
        c.dispatch("updates.primary",json!({}));assert_eq!(c.state()["navigationPrompt"]["kind"],"unsaved-device");assert_eq!(*platform.calls.lock().unwrap(),["updates.check"]);let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn state_reads_and_navigation_are_available_while_an_update_is_busy(){
        let platform=Arc::new(Updates::default());let c=controller(platform);c.state.lock().unwrap().updates_busy=true;
        assert_eq!(c.dispatch("updates.state",json!({}))["ok"],true);assert_eq!(c.dispatch("updates.primary",json!({}))["ok"],false);
        assert_eq!(c.dispatch("app.settings.tab",json!({"tab":"about"}))["ok"],true);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn stale_update_intent_is_rejected_without_starting_work(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());
        let result=c.dispatch("updates.primary",json!({"expectedIntent":"obsolete-update-state"}));
        assert_eq!(result["ok"],false,"{result}");assert_eq!(result["error"],"更新状态已变化，请重新点击当前操作");
        assert!(platform.calls.lock().unwrap().is_empty());assert!(c.take_window_action().is_empty());let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn stale_update_intent_does_not_open_a_draft_confirmation(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());
        c.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"PC","address":"192.0.2.10","paired":true,"online":true}])).unwrap());
        c.dispatch("device.settings.open",json!({"uuid":"host"}));c.dispatch("settings.input",json!({"key":"width","value":"unfinished"}));
        c.state.lock().unwrap().updates=json!({"availableVersion":"9.0.0","phase":"available"});
        let result=c.dispatch("updates.primary",json!({"expectedIntent":"obsolete-update-state"}));
        assert_eq!(result["ok"],false,"{result}");assert!(c.state()["navigationPrompt"].is_null());assert_eq!(c.state()["settingsInputs"]["width"],"unfinished");
        assert!(platform.calls.lock().unwrap().is_empty());let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn update_details_are_a_page_action_and_do_not_start_another_job(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());
        assert_eq!(c.dispatch("updates.details.toggle",json!({}))["ok"],false);c.dispatch("app.settings.tab",json!({"tab":"updates"}));
        assert_eq!(c.dispatch("updates.details.toggle",json!({}))["ok"],true);assert_eq!(c.state()["updateDetails"],true);
        c.state.lock().unwrap().updates_busy=true;assert_eq!(c.dispatch("updates.details.toggle",json!({}))["ok"],true);assert_eq!(c.state()["updateDetails"],false);assert!(platform.calls.lock().unwrap().is_empty());
        c.state.lock().unwrap().updates_busy=false;c.dispatch("updates.primary",json!({}));assert_eq!(c.state()["updateDetails"],false);assert_eq!(*platform.calls.lock().unwrap(),["updates.check"]);let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn a_background_update_error_does_not_replace_another_pages_message(){
        let platform=Arc::new(Updates::default());let c=controller(platform.clone());c.dispatch("app.settings.tab",json!({"tab":"about"}));c.message("原页面提示".into());*platform.fail.lock().unwrap()=true;
        assert_eq!(c.dispatch("updates.check",json!({}))["ok"],false);assert_eq!(c.state()["settingsTab"],"about");assert_eq!(c.state()["status"],"原页面提示");assert_eq!(c.state()["updates"]["phase"],"error");
        c.dispatch("app.settings.tab",json!({"tab":"updates"}));assert_eq!(c.state()["updates"]["status"],"fixture network unavailable");let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn slow_network_requests_do_not_serialize_state_and_navigation(){
        use std::{io::{Read,Write},net::{TcpListener,TcpStream},sync::mpsc,time::Duration};
        struct Slow {kind:&'static str,entered:mpsc::Sender<()>,release:Mutex<mpsc::Receiver<()>>}
        impl Platform for Slow {fn execute(&self,id:&str,_:&Value)->Result<Value,String>{if id==self.kind{self.entered.send(()).unwrap();self.release.lock().unwrap().recv_timeout(Duration::from_secs(5)).unwrap();}Ok(json!({"managed":true,"phase":"current"}))}}
        fn request(port:u16,token:&str,path:&str,body:Option<&str>)->String {
            let mut socket=TcpStream::connect(("127.0.0.1",port)).unwrap();socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let body=body.unwrap_or("");write!(socket,"{} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {token}\r\nConnection: close\r\nContent-Length: {}\r\n\r\n{body}",if body.is_empty(){"GET"}else{"POST"},body.len()).unwrap();let mut value=String::new();socket.read_to_string(&mut value).unwrap();value
        }
        for (kind,busy) in [("updates.check","updatesBusy"),("sync.run","syncBusy")]{
            let(entered,wait)=mpsc::channel();let(release,released)=mpsc::channel();let token="fixture-token-012345678901234567890123456789";
            let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-http-{}.json",random_token().unwrap())),Arc::new(Slow{kind,entered,release:Mutex::new(released)})).unwrap();
            let listener=TcpListener::bind(("127.0.0.1",0)).unwrap();let port=listener.local_addr().unwrap().port();drop(listener);crate::start_http(c.clone(),port,token.into()).unwrap();
            let worker=std::thread::spawn(move||request(port,token,"/v1/action",Some(&json!({"id":kind,"args":{}}).to_string())));wait.recv_timeout(Duration::from_secs(2)).unwrap();
            let state=request(port,token,"/v1/state",None);assert!(state.contains(&format!("\"{busy}\":true")),"{state}");
            let duplicate=request(port,token,"/v1/action",Some(&json!({"id":kind,"args":{}}).to_string()));assert!(duplicate.contains("\"ok\":false"));
            let navigation=request(port,token,"/v1/action",Some(r#"{"id":"app.settings.tab","args":{"tab":"about"}}"#));assert!(navigation.contains("\"ok\":true"));
            release.send(()).unwrap();assert!(worker.join().unwrap().contains("\"ok\":true"));let _=std::fs::remove_file(&c.storage);
        }
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
