//! Draft lifetime and navigation rules shared by UI and control API.
use crate::{Controller, Profile, State, network, settings_catalog};
use serde_json::{Value, json};

/// Profile-form commands may carry the identifier rendered with their source controls.
/// Callers without an identifier keep the existing immediate API semantics.
pub fn is_profile_form_action(id:&str)->bool{id.starts_with("settings.")&&id!="settings.catalog"}
pub(crate) fn profile_origin_matches(s:&State,id:&str,expected:Option<&Value>)->bool{
    !is_profile_form_action(id)||expected.is_none()||s.draft.is_some()&&!s.profile_draft_id.is_empty()&&expected.and_then(Value::as_str)==Some(s.profile_draft_id.as_str())
}
pub(crate) fn ensure_profile_origin(s:&State,id:&str,args:&Value)->Result<(),String>{
    if profile_origin_matches(s,id,args.get("draftId")){Ok(())}else{Err("该连接设置已关闭或切换，请使用当前页面".into())}
}
pub(crate) fn ignored_profile_action(id:&str)->Value{
    json!({"ok":false,"action":id,"ignored":true,"reason":"stale_profile_draft","error":"该连接设置已关闭或切换，请使用当前页面"})
}

#[derive(Clone, Debug)]
pub(crate) struct PendingNavigation { pub request_id:String, pub action:String, pub args:Value, pub sync:bool, pub info:bool, pub profile_draft_id:Option<String>, pub appearance:Option<AppearanceContext> }

fn prompt(s:&mut State,id:&str,args:&Value,sync:bool,info:bool)->Result<Value,String>{
    let generation=s.decision_generation.checked_add(1).ok_or("确认操作编号已耗尽")?;
    let appearance=AppearanceContext::capture(s,id);s.decision_generation=generation;
    let profile_draft_id=(!sync&&!info&&s.draft.is_some()).then(||s.profile_draft_id.clone());
    s.pending_navigation=Some(PendingNavigation{request_id:generation.to_string(),action:id.into(),args:args.clone(),sync,info,profile_draft_id,appearance});s.revision+=1;Ok(json!({"pendingNavigation":true}))
}

/// Navigation identifiers and a read-only preview, never unsaved form values or credentials.
#[derive(Clone, Debug)]
pub(crate) struct AppearanceContext {
    page:String,selected:String,profile_tab:String,settings_tab:String,sources_expanded:bool,
    preview:Option<crate::profile_copy::Preview>,
}
impl AppearanceContext {
    fn capture(s:&State,id:&str)->Option<Self>{(id=="appearance.toggle").then(||Self{page:s.page.clone(),selected:s.selected.clone(),profile_tab:s.profile_tab.clone(),settings_tab:s.settings_tab.clone(),sources_expanded:s.profile_sources_expanded,preview:s.profile_copy.clone()})}
    fn restore(&self,c:&Controller){
        let (id,args)=match self.page.as_str(){
            "device-info"=>("device.info.open",json!({"uuid":self.selected})),
            "settings"|"profile-copy"=>("device.settings.open",json!({"uuid":self.selected})),
            "global-settings"=>("app.settings.tab",json!({"tab":self.settings_tab})),
            _=>return,
        };
        if c.dispatch(id,args)["ok"]!=true{return;}
        if !matches!(self.page.as_str(),"settings"|"profile-copy"){return;}
        c.dispatch("settings.tab",json!({"tab":self.profile_tab}));
        let mut s=c.state.lock().unwrap();s.profile_sources_expanded=self.sources_expanded;
        if let Some(preview)=&self.preview{
            let available=preview.target_uuid==s.selected&&preview.client_id!=s.saved.client_id&&s.saved.client_profiles.get(&preview.client_id).and_then(|group|group.get(&s.selected)).is_some_and(|profile|profile.to_profile().validate().is_ok());
            if available{s.profile_copy=Some(preview.clone());s.page="profile-copy".into();}
            else{s.profile_copy=None;s.profile_copy_error.clear();s.profile_copy_notice="来源配置不可用".into();s.page="settings".into();}
        }
        s.revision+=1;
    }
}

pub(crate) fn automatic_sync_blocked(s:&State)->bool{
    s.sync_busy || s.diagnostics.busy || s.sync["automaticBlocked"]==true || sync_review_waiting(&s.sync) || s.streaming || s.connecting || s.closing || s.installing_update
        || s.draft.is_some() || s.info_draft.is_some() || s.page=="add" || s.page=="pair"
        || s.pending_navigation.is_some() || s.sync_clear_pending || sync_dirty(s)
}
pub(crate) fn sync_review_waiting(sync:&Value)->bool{sync["phase"]=="conflict"||sync["conflicts"].as_array().is_some_and(|items|!items.is_empty())}
pub(crate) fn validate_sync_review(sync:&Value,id:&str,args:&Value)->Result<(),String>{
    if id=="sync.run"&&args.get("automatic").is_some_and(|value|!value.is_boolean()){return Err("automatic must be a boolean".into());}
    if id=="sync.resolve"&&args.get("reviewId").is_some()&&args["reviewId"].as_str().is_none_or(|expected|expected.is_empty()||sync["reviewId"].as_str()!=Some(expected)){
        Err("同步冲突内容已更新，请重新查看后再选择".into())
    }else{Ok(())}
}

pub(crate) fn begin_sync(s:&mut State){
    if s.sync_draft.is_none(){
        s.sync_generation+=1;
        s.sync_draft=Some(json!({"url":s.sync["url"].as_str().unwrap_or(""),"username":s.sync["username"].as_str().unwrap_or(""),"password":"","automatic":s.sync["automatic"].as_bool().unwrap_or(false),"intervalMinutes":s.sync["intervalMinutes"].as_u64().unwrap_or(5)}));
        let previous=s.sync["error"].as_str().unwrap_or("");
        s.sync_error=if s.sync_busy{String::new()}else if !previous.is_empty(){previous.into()}else if s.sync["automaticBlocked"]==true{"同步结果尚未确认，请手动同步核对".into()}else{String::new()};
    }
}
pub(crate) fn sync_dirty(s:&State)->bool{
    let Some(d)=&s.sync_draft else{return false};
    d["url"].as_str().unwrap_or("").trim()!=s.sync["url"].as_str().unwrap_or("") || d["username"].as_str().unwrap_or("").trim()!=s.sync["username"].as_str().unwrap_or("") || !d["password"].as_str().unwrap_or("").is_empty() || d["automatic"].as_bool().unwrap_or(false)!=s.sync["automatic"].as_bool().unwrap_or(false) || d["intervalMinutes"].as_u64().unwrap_or(5)!=s.sync["intervalMinutes"].as_u64().unwrap_or(5)
}
pub(crate) fn sync_password_reusable(s:&State)->bool{
    let form=s.sync_draft.as_ref().unwrap_or(&s.sync);
    s.sync["hasPassword"]==true
        && form["url"].as_str().unwrap_or("").trim()==s.sync["url"].as_str().unwrap_or("")
        && form["username"].as_str().unwrap_or("").trim()==s.sync["username"].as_str().unwrap_or("")
}

pub(crate) fn dirty(s:&State)->bool {
    let Some(draft)=&s.draft else{return false};
    if draft!=&s.saved.effective_profile(&s.selected){return true;}
    let host=s.saved.devices.iter().find(|d|d.uuid==s.selected);
    let current=s.devices.iter().find(|d|d.uuid==s.selected);
    let alias=host.map(|d|d.display_name.as_str()).or_else(||current.map(|d|d.display_name.as_str())).unwrap_or("");
    let address=host.map(|d|d.address.as_str()).or_else(||current.map(|d|d.address.as_str())).unwrap_or("");
    let extra=host.map(|d|d.addresses.as_slice()).or_else(||current.map(|d|d.addresses.as_slice())).unwrap_or(&[]);
    let entered:Vec<_>=s.draft_addresses.split([',',';','，','\n']).map(str::trim).filter(|a|!a.is_empty()).collect();
    if s.draft_display_name.trim()!=alias || s.draft_address.trim()!=address || entered!=extra.iter().map(String::as_str).collect::<Vec<_>>() || s.draft_relay.trim()!=draft.relay_url {return true;}
    let profile=serde_json::to_value(draft).unwrap();
    s.settings_inputs.iter().any(|(key,value)|value.trim()!=profile[key].to_string())
}
pub(crate) fn clear(s:&mut State) {
    s.profile_copy=None;s.profile_copy_notice.clear();s.profile_copy_error.clear();
    s.draft=None;s.profile_draft_id.clear();s.settings_inputs.clear();s.settings_error.clear();s.settings_error_field.clear();
    s.draft_display_name.clear();s.draft_address.clear();s.draft_addresses.clear();s.draft_relay.clear();
}
fn error<T>(s:&mut State,tab:&str,key:&str,message:String)->Result<T,String>{
    s.profile_tab=tab.into();s.settings_error=message.clone();s.settings_error_field=key.into();s.revision+=1;s.settings_error_request=s.revision;Err(message)
}
pub(crate) fn validated_profile(s:&mut State)->Result<Profile,String> {
    let mut value=serde_json::to_value(s.draft.as_ref().ok_or("请先打开连接设置")?).unwrap();
    for field in settings_catalog().as_array().unwrap() {
        let key=field["key"].as_str().unwrap();if field["type"]!="number"{continue;}
        if key=="bitrateMbps"&&value["bitrateAuto"]==true{continue;}
        let text=s.settings_inputs.get(key).cloned().unwrap_or_else(||value[key].to_string());
        let tab=field["tab"].as_str().unwrap();let label=field["label"].as_str().unwrap();
        let number=match text.trim().parse::<i64>(){Ok(n)=>n,Err(_)=>return error(s,tab,key,format!("{label}：请输入整数"))};
        let min=field["min"].as_i64().unwrap();let max=field["max"].as_i64().unwrap();
        if number<min||number>max{return error(s,tab,key,format!("{label}：范围为 {min}–{max}"));}
        if ["width","height"].contains(&key)&&number%2!=0{return error(s,tab,key,format!("{label}必须为偶数"));}
        value[key]=json!(number);
    }
    value["relayUrl"]=json!(s.draft_relay.trim());
    if value["width"].as_u64().unwrap()*value["height"].as_u64().unwrap()>64_000_000{return error(s,"video","width","画面总像素不能超过 6400 万".into());}
    let profile:Profile=serde_json::from_value(value).map_err(|_|"连接设置格式无效".to_string())?;
    if let Err(e)=network::validate_routes(&profile.route_order,&profile.relay_url){return error(s,"network","relayUrl",e);}
    if let Err(e)=profile.validate(){return error(s,"advanced","",format!("连接设置无效：{e}"));}
    Ok(profile)
}
pub(crate) fn endpoints(s:&mut State)->Result<(String,Vec<String>),String>{
    let primary=s.draft_address.trim().to_owned();
    let extra:Vec<String>=s.draft_addresses.split([',',';','，','\n']).map(str::trim).filter(|a|!a.is_empty()).map(str::to_owned).collect();
    let all=match network::addresses(&primary,&extra){Ok(v)=>v,Err(e)=>return error(s,"network","address",e)};
    Ok((primary.clone(),all.into_iter().filter(|a|!a.eq_ignore_ascii_case(&primary)).collect()))
}
impl Controller {
    pub(crate) fn update_installation_gate(&self, id: &str, args: &Value) -> Option<Result<Value, String>> {
        let s = self.state.lock().unwrap();
        if !s.installing_update { return None; }
        if id == "sync.run" && args["automatic"] == true {
            return Some(Ok(json!({"skipped":true,"reason":"update_installing"})));
        }
        if id.ends_with(".state") || id.starts_with("view.ui.") || id.ends_with(".snapshot")
            || matches!(id, "settings.catalog" | "window.minimize" | "window.maximize") { return None; }
        Some(Err("正在安装更新，请稍候".into()))
    }
    /// Return Some when a modal consumed the action; never persist just by navigating.
    pub(crate) fn navigation_gate(&self,id:&str,args:&Value)->Option<Result<Value,String>>{
        let observed=if args.get("expectedIntent").is_some(){self.platform.update_state()}else{None};
        let mut s=self.state.lock().unwrap();
        if let Err(error)=validate_sync_review(&s.sync,id,args){return Some(Err(error));}
        if let Err(error)=crate::global_settings::validate_update_intent(id,args,observed.as_ref().unwrap_or(&s.updates),s.update_operation){return Some(Err(error));}
        if id=="sync.run" && args["automatic"]==true && s.sync["automaticBlocked"]==true{return Some(Ok(json!({"skipped":true,"reason":"sync_interrupted"})));}
        if id=="sync.run" && args["automatic"]==true && sync_review_waiting(&s.sync){return Some(Ok(json!({"skipped":true,"reason":"sync_conflict"})));}
        if id=="sync.run" && args["automatic"]==true && (automatic_sync_blocked(&s)||s.sync["automatic"]!=true){return Some(Ok(json!({"skipped":true})));}
        if s.sync_clear_pending && !id.starts_with("view.ui.") && !matches!(id,"sync.clear.resolve"|"sync.clear"|"settings.catalog"|"updates.state"|"updates.cancel"|"device.network.cancel"|"statistics.state"|"network.state"|"sync.state"|"view.snapshot"){return Some(Err("请先确认是否移除同步设置".into()));}
        if s.pending_navigation.is_some() && !id.starts_with("view.ui.") && !matches!(id,"navigation.resolve"|"settings.catalog"|"updates.state"|"updates.cancel"|"device.network.cancel"|"statistics.state"|"network.state"|"sync.state"|"view.snapshot"|"view.toolbar.snapshot"|"view.picker.snapshot") {
            return Some(Err("请先处理未保存的更改".into()));
        }
        let primary_installs=id=="updates.primary"&&crate::update_primary_action(observed.as_ref().unwrap_or(&s.updates))=="updates.install";
        let leaving_info=matches!(id,"devices.open"|"device.select"|"device.settings.open"|"device.info.open"|"device.add.open"|"app.settings.open"|"app.settings.tab"|"device.connect"|"device.windows.open"|"device.window.connect"|"device.pair"|"window.close"|"appearance.toggle"|"sync.run"|"sync.resolve"|"updates.install") || primary_installs;
        if leaving_info && s.info_draft.is_some(){
            if id=="device.info.open" && args["uuid"]==s.selected{return Some(Ok(json!({"reused":true})));}
            if crate::device_info::dirty(&s){return Some(prompt(&mut s,id,args,false,true));}
            if id!="appearance.toggle"{s.info_draft=None;s.page="devices".into();}
        }
        let leaving_sync=matches!(id,"devices.open"|"device.settings.open"|"device.info.open"|"device.add.open"|"device.select"|"device.connect"|"device.windows.open"|"device.window.connect"|"device.pair"|"window.close"|"appearance.toggle"|"sync.run"|"sync.resolve"|"updates.install") || id=="app.settings.tab"&&args["tab"]!="sync" || primary_installs;
        if leaving_sync && s.sync_draft.is_some(){
            if sync_dirty(&s){return Some(prompt(&mut s,id,args,true,false));}
            if id!="appearance.toggle"{s.sync_draft=None;s.sync_error.clear();}
        }
        let leaving=matches!(id,"devices.open"|"app.settings.open"|"app.settings.tab"|"device.settings.open"|"device.info.open"|"device.add.open"|"device.select"|"device.connect"|"device.windows.open"|"device.window.connect"|"device.pair"|"window.close"|"appearance.toggle"|"updates.install")
            || primary_installs;
        if !leaving || s.draft.is_none(){return None;}
        if id=="device.settings.open"&&args["uuid"]==s.selected{return Some(Ok(json!({"reused":true})));}
        if dirty(&s){
            return Some(prompt(&mut s,id,args,false,false));
        }
        if id=="appearance.toggle"{return None;}
        clear(&mut s);
        if matches!(id,"device.select"|"appearance.toggle"){s.page="devices".into();}
        None
    }
    pub(crate) fn resolve_navigation(&self,args:Value)->Result<Value,String>{
        let choice=args["choice"].as_str().ok_or("choice required")?;
        if !["save","discard","stay"].contains(&choice){return Err("unknown navigation choice".into());}
        let pending={let mut s=self.state.lock().unwrap();let current=s.pending_navigation.as_ref().ok_or("没有等待处理的更改")?;if let Some(expected)=args.get("requestId"){if expected.as_str()!=Some(current.request_id.as_str()){return Err("该确认操作已失效，请使用当前对话框".into());}}let pending=s.pending_navigation.take().unwrap();s.revision+=1;if choice=="discard"{if pending.info{s.info_draft=None;s.page="devices".into();}else if pending.sync{s.sync_draft=None;s.sync_error.clear();}else{clear(&mut s);s.page="devices".into();}}pending};
        if choice=="stay"{return Ok(json!({"continuedEditing":true}));}
        if choice=="save"{
            {let mut s=self.state.lock().unwrap();if s.page=="profile-copy"{s.profile_copy=None;s.page="settings".into();}}
            let args=pending.profile_draft_id.as_ref().map(|id|json!({"draftId":id})).unwrap_or_else(||json!({}));
            let result=self.dispatch(if pending.info{"device.info.save"}else if pending.sync{"sync.save"}else{"settings.save"},args);
            if result["ignored"]==true{return Ok(json!({"ignored":true,"reason":"stale_profile_draft","continuedEditing":true}));}
            if result["ok"]!=true{return Err(result["error"].as_str().unwrap_or("保存失败").into());}
        }
        if let Some(appearance)=&pending.appearance{appearance.restore(self);}
        let result=self.dispatch(&pending.action,pending.args);
        if result["ok"]==true{Ok(result["result"].clone())}else{Err(result["error"].as_str().unwrap_or("导航失败").into())}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Device,Platform,random_token};
    use std::sync::Arc;
    struct Host;
    impl Platform for Host {fn execute(&self,id:&str,args:&Value)->Result<Value,String>{Ok(match id{"devices.restore"=>json!({"devices":args["devices"]}),"updates.state"=>json!({"managed":false}),_=>json!({})})}}
    fn fixture()->Arc<Controller>{
        let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-settings-flow-{}.json",random_token().unwrap())),Arc::new(Host)).unwrap();
        let devices:Vec<Device>=serde_json::from_value(json!([{"uuid":"host","name":"winserver","displayName":"工作机","address":"192.168.0.104","addresses":["100.73.139.109"],"online":true,"paired":true},{"uuid":"second","name":"Claw","address":"192.168.0.113","online":false,"paired":true}])).unwrap();c.replace_devices(devices);c.dispatch("device.settings.open",json!({"uuid":"host"}));c
    }
    fn input(c:&Controller,key:&str,value:&str){assert_eq!(c.dispatch("settings.input",json!({"key":key,"value":value}))["ok"],true);}
    #[test]fn profile_error_focus_receipts_belong_to_actual_current_validation_attempts(){
        let c=fixture();input(&c,"volume","invalid");assert_eq!(c.dispatch("settings.save",json!({}))["ok"],false);
        let first=c.state()["settingsErrorRequest"].as_u64().expect("A failed validation needs a focus receipt");assert!(first>0);
        c.dispatch("updates.state",json!({}));c.dispatch("settings.catalog",json!({}));assert_eq!(c.state()["settingsErrorRequest"],first);
        assert_eq!(c.dispatch("settings.save",json!({}))["ok"],false);let next=c.state()["settingsErrorRequest"].as_u64().unwrap();assert!(next>first);
        let old=c.state()["profileDraftId"].clone();c.dispatch("settings.cancel",json!({}));c.dispatch("device.settings.open",json!({"uuid":"second"}));let before=c.state();
        assert_eq!(c.dispatch("settings.save",json!({"draftId":old}))["ignored"],true);assert_eq!(c.state(),before);let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn profile_draft_identifiers_reject_late_form_actions_without_changing_the_current_state(){
        let c=fixture();let old=c.state()["profileDraftId"].clone();assert!(!old.as_str().unwrap().is_empty());
        c.dispatch("device.settings.open",json!({"uuid":"host"}));c.dispatch("settings.tab",json!({"tab":"audio"}));assert_eq!(c.state()["profileDraftId"],old);
        c.dispatch("settings.cancel",json!({}));assert_eq!(c.state()["profileDraftId"],"");c.dispatch("device.settings.open",json!({"uuid":"host"}));assert_ne!(c.state()["profileDraftId"],old);input(&c,"fps","23");let before=c.state();let file=std::fs::read(&c.storage).unwrap();
        for id in ["settings.input","settings.set","settings.step","settings.network.edit","settings.route.move","settings.route.toggle","settings.tab","settings.sources.toggle","settings.copy_from","settings.copy.preview","settings.copy.cancel","settings.copy.apply","settings.save","settings.cancel"]{
            let result=c.dispatch(id,json!({"draftId":old}));assert_eq!(result["ok"],false,"{id}");assert_eq!(result["reason"],"stale_profile_draft","{id}");assert_eq!(result["ignored"],true);assert_eq!(c.state(),before,"{id} changed a replacement form");assert_eq!(std::fs::read(&c.storage).unwrap(),file);
        }
        for malformed in [json!(null),json!(0),json!(""),json!({})]{assert_eq!(c.dispatch("settings.save",json!({"draftId":malformed}))["ignored"],true);assert_eq!(c.state(),before);}
        let current=c.state()["profileDraftId"].clone();assert_eq!(c.dispatch("settings.save",json!({"draftId":current}))["ok"],true);assert_eq!(c.profile("host").fps,23);let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn profile_mutation_rechecks_the_origin_inside_its_own_state_lock(){
        let c=fixture();let old=c.state()["profileDraftId"].clone();c.dispatch("device.settings.open",json!({"uuid":"second"}));let before=c.state();
        // Simulate a draft being replaced after dispatch admission. These are the
        // real mutation entry points, so an admission-only check cannot pass.
        assert!(c.dispatch_inner("settings.input",json!({"draftId":old,"key":"fps","value":"24"}),None).is_err());assert_eq!(c.state(),before);
        assert!(c.dispatch_profile_copy("settings.copy.preview",json!({"draftId":old,"clientId":"unavailable"})).unwrap_err().contains("已关闭或切换"));assert_eq!(c.state(),before);let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn untouched_settings_leave_without_a_hidden_draft(){
        let c=fixture();assert_eq!(c.state()["settingsDirty"],false);
        assert_eq!(c.dispatch("app.settings.open",json!({}))["ok"],true);assert_eq!(c.state()["page"],"global-settings");assert!(c.state()["draft"].is_null());assert!(c.state()["navigationPrompt"].is_null());let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn appearance_keeps_clean_forms_and_their_categories(){
        for page in ["settings","device-info","global-settings"]{
            let c=fixture();
            match page {"settings"=>{c.dispatch("settings.tab",json!({"tab":"audio"}));},"device-info"=>{c.dispatch("device.info.open",json!({"uuid":"host"}));},_=>{c.dispatch("app.settings.tab",json!({"tab":"sync"}));}}
            let dark=c.state()["saved"]["dark"].clone();assert_eq!(c.dispatch("appearance.toggle",json!({}))["ok"],true);assert_eq!(c.state()["page"],page);assert_ne!(c.state()["saved"]["dark"],dark);
            if page=="settings"{assert_eq!(c.state()["profileTab"],"audio");assert!(!c.state()["draft"].is_null());}else if page=="device-info"{assert!(!c.state()["infoDraft"].is_null());}else{assert_eq!(c.state()["settingsTab"],"sync");assert!(!c.state()["syncDraft"].is_null());}
            let _=std::fs::remove_file(&c.storage);
        }
    }
    #[test] fn appearance_decisions_return_to_the_same_form_after_save_or_discard(){
        for page in ["settings","device-info"]{for choice in ["stay","discard","save"]{
            let c=fixture();if page=="settings"{c.dispatch("settings.tab",json!({"tab":"audio"}));input(&c,"fps","24");}else{c.dispatch("device.info.open",json!({"uuid":"host"}));c.dispatch("device.info.input",json!({"displayName":"主题测试"}));}
            let dark=c.state()["saved"]["dark"].clone();assert_eq!(c.dispatch("appearance.toggle",json!({}))["ok"],true);assert!(!c.state()["navigationPrompt"].is_null());assert_eq!(c.state()["saved"]["dark"],dark);assert_eq!(c.dispatch("navigation.resolve",json!({"choice":choice}))["ok"],true);assert_eq!(c.state()["page"],page);assert_eq!(c.state()["selected"],"host");
            if choice=="stay"{assert_eq!(c.state()["saved"]["dark"],dark);assert_eq!(c.state()[if page=="settings"{"settingsDirty"}else{"infoDirty"}],true);}else{assert_ne!(c.state()["saved"]["dark"],dark);assert_eq!(c.state()[if page=="settings"{"settingsDirty"}else{"infoDirty"}],false);}
            if page=="settings"{assert_eq!(c.state()["profileTab"],"audio");assert_eq!(c.profile("host").fps,if choice=="save"{24}else{30});}
            let _=std::fs::remove_file(&c.storage);
        }}
    }
    #[test] fn failed_theme_save_keeps_the_error_input_and_original_theme(){
        let c=fixture();input(&c,"fps","invalid");let dark=c.state()["saved"]["dark"].clone();c.dispatch("appearance.toggle",json!({}));assert_eq!(c.dispatch("navigation.resolve",json!({"choice":"save"}))["ok"],false);assert_eq!(c.state()["saved"]["dark"],dark);assert_eq!(c.state()["page"],"settings");assert_eq!(c.state()["settingsInputs"]["fps"],"invalid");assert_eq!(c.state()["settingsErrorField"],"fps");assert!(c.state()["navigationPrompt"].is_null());let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn category_switch_retains_partial_input_and_save_locates_the_error(){
        let c=fixture();let before=std::fs::read(&c.storage).unwrap();input(&c,"width","");
        assert_eq!(c.dispatch("settings.tab",json!({"tab":"audio"}))["ok"],true);assert_eq!(c.state()["settingsInputs"]["width"],"");
        assert_eq!(c.dispatch("settings.save",json!({}))["ok"],false);assert_eq!(c.state()["page"],"settings");assert_eq!(c.state()["profileTab"],"video");assert_eq!(c.state()["settingsErrorField"],"width");assert_eq!(std::fs::read(&c.storage).unwrap(),before);let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn leaving_unsaved_settings_can_stay_discard_or_save_before_continuing(){
        for choice in ["stay","discard","save"]{
            let c=fixture();input(&c,"bitrateMbps","37");assert_eq!(c.dispatch("app.settings.tab",json!({"tab":"about"}))["ok"],true);
            assert_eq!(c.state()["page"],"settings");assert_eq!(c.state()["navigationPrompt"]["kind"],"unsaved-device");
            assert_eq!(c.dispatch("navigation.resolve",json!({"choice":choice}))["ok"],true);
            assert!(c.state()["navigationPrompt"].is_null());
            if choice=="stay"{assert_eq!(c.state()["page"],"settings");assert_eq!(c.state()["settingsInputs"]["bitrateMbps"],"37");}
            else {assert_eq!(c.state()["page"],"global-settings");assert!(c.state()["draft"].is_null());assert_eq!(c.profile("host").bitrate_mbps,if choice=="save"{37}else{25});}
            let _=std::fs::remove_file(&c.storage);
        }
    }
    #[test] fn invalid_save_dismisses_the_decision_and_keeps_the_form_and_input(){
        let c=fixture();input(&c,"fps","zero");c.dispatch("devices.open",json!({}));assert_eq!(c.dispatch("navigation.resolve",json!({"choice":"save"}))["ok"],false);
        assert!(c.state()["navigationPrompt"].is_null());assert_eq!(c.state()["page"],"settings");assert_eq!(c.state()["settingsInputs"]["fps"],"zero");assert_eq!(c.state()["settingsErrorField"],"fps");let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn a_pending_decision_blocks_background_mutations_but_allows_observation(){
        let c=fixture();input(&c,"fps","24");c.dispatch("navigation.back",json!({}));
        assert_eq!(c.dispatch("settings.set",json!({"key":"codec","value":"H.264"}))["ok"],false);assert_eq!(c.dispatch("updates.state",json!({}))["ok"],true);
        assert_eq!(c.dispatch("device.settings.open",json!({"uuid":"second"}))["ok"],false);assert_eq!(c.state()["selected"],"host");let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn ui_test_requests_do_not_dismiss_a_decision_or_bypass_business_gates(){
        let c=fixture();input(&c,"fps","24");c.dispatch("navigation.back",json!({}));let before=c.state()["saved"].clone();
        for id in ["view.ui.inspect","view.ui.focus","view.ui.key","view.ui.result"]{assert_eq!(c.dispatch(id,json!({}))["ok"],true);assert_eq!(c.state()["navigationPrompt"]["kind"],"unsaved-device");}
        assert_eq!(c.dispatch("settings.save",json!({}))["ok"],false);assert_eq!(c.state()["saved"],before);assert_eq!(c.state()["settingsInputs"]["fps"],"24");let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn reopening_the_same_settings_does_not_erase_changes_and_cancel_is_explicit(){
        let c=fixture();input(&c,"volume","42");c.dispatch("device.settings.open",json!({"uuid":"host"}));assert_eq!(c.state()["settingsInputs"]["volume"],"42");
        c.dispatch("settings.cancel",json!({}));assert!(c.state()["draft"].is_null());assert_eq!(c.profile("host").volume,100);let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn preset_replaces_its_own_raw_inputs_and_step_is_bounded(){
        let c=fixture();input(&c,"width","bad");c.dispatch("settings.set",json!({"key":"resolution","value":[1280,720]}));assert!(c.state()["settingsInputs"]["width"].is_null());
        input(&c,"fps","240");c.dispatch("settings.step",json!({"key":"fps","direction":"up"}));assert_eq!(c.state()["settingsInputs"]["fps"],"240");assert_eq!(c.dispatch("settings.save",json!({}))["ok"],true);assert_eq!(c.profile("host").width,1280);let _=std::fs::remove_file(&c.storage);
    }
    #[test] fn reverting_a_raw_field_clears_dirty_state(){let c=fixture();input(&c,"fps","24");assert_eq!(c.state()["settingsDirty"],true);input(&c,"fps","30");assert_eq!(c.state()["settingsDirty"],false);let _=std::fs::remove_file(&c.storage);}
    #[test] fn back_from_a_decision_keeps_the_api_action_and_the_input(){let c=fixture();input(&c,"fps","24");c.dispatch("app.settings.open",json!({}));let r=c.dispatch("navigation.back",json!({}));assert_eq!(r["ok"],true);assert_eq!(r["action"],"navigation.back");assert!(c.state()["navigationPrompt"].is_null());assert_eq!(c.state()["settingsInputs"]["fps"],"24");let _=std::fs::remove_file(&c.storage);}
    #[derive(Default)] struct Dav {saved:std::sync::Mutex<Value>,calls:std::sync::Mutex<Vec<(String,Value)>>,fail:std::sync::atomic::AtomicBool}
    impl Platform for Dav{fn execute(&self,id:&str,args:&Value)->Result<Value,String>{
        if id=="sync.state"{return Ok(self.saved.lock().unwrap().clone());}if id=="updates.state"{return Ok(json!({}));}
        self.calls.lock().unwrap().push((id.into(),args.clone()));
        if self.fail.load(std::sync::atomic::Ordering::SeqCst){return Err("WebDAV 地址无效".into());}
        if id=="sync.clear"{*self.saved.lock().unwrap()=json!({"url":"","hasPassword":false,"automatic":false});return Ok(self.saved.lock().unwrap().clone());}
        if id=="sync.configure"{let mut saved=args.clone();saved.as_object_mut().unwrap().remove("password");saved["hasPassword"]=json!(true);*self.saved.lock().unwrap()=saved.clone();return Ok(saved);}Ok(json!({}))
    }}
    fn dav()->(Arc<Controller>,Arc<Dav>){let p=Arc::new(Dav::default());let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-dav-flow-{}.json",random_token().unwrap())),p.clone()).unwrap();c.dispatch("app.settings.tab",json!({"tab":"sync"}));(c,p)}
    struct SyncFeedback {
        saved:std::sync::Mutex<Value>,
        transfer_fails:std::sync::atomic::AtomicBool,
        configure_fails:std::sync::atomic::AtomicBool,
    }
    impl Platform for SyncFeedback {
        fn execute(&self,id:&str,args:&Value)->Result<Value,String>{
            use std::sync::atomic::Ordering;
            match id {
                "sync.state"=>Ok(self.saved.lock().unwrap().clone()),
                "sync.run" if self.transfer_fails.load(Ordering::SeqCst)=>Err("WebDAV 服务器返回错误（HTTP 503）".into()),
                "sync.run"=>Ok(json!({"sync":self.saved.lock().unwrap().clone(),"conflicts":[]})),
                "sync.configure" if self.configure_fails.load(Ordering::SeqCst)=>Err("用户名无效".into()),
                "sync.configure"=>{let mut saved=self.saved.lock().unwrap();for key in ["url","username","automatic","intervalMinutes"]{saved[key]=args[key].clone();}Ok(saved.clone())},
                _=>Ok(json!({})),
            }
        }
    }
    fn sync_feedback_fixture()->(Arc<Controller>,Arc<SyncFeedback>){
        let p=Arc::new(SyncFeedback{saved:std::sync::Mutex::new(json!({"url":"https://example.test/dav","username":"fixture","hasPassword":true,"automatic":false,"intervalMinutes":5,"lastSyncMs":1,"phase":"completed","error":""})),transfer_fails:std::sync::atomic::AtomicBool::new(true),configure_fails:std::sync::atomic::AtomicBool::new(false)});
        let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-sync-feedback-{}.json",random_token().unwrap())),p.clone()).unwrap();
        c.dispatch("app.settings.tab",json!({"tab":"sync"}));(c,p)
    }
    #[test]fn sync_transfer_error_survives_navigation_and_discarded_validation(){
        let(c,p)=sync_feedback_fixture();
        assert_eq!(c.dispatch("sync.run",json!({}))["ok"],false);
        let error=c.state()["syncError"].clone();assert!(error.as_str().unwrap().contains("503"));
        assert_eq!(c.dispatch("app.settings.tab",json!({"tab":"about"}))["ok"],true);
        assert_eq!(c.state()["status"],"");
        c.dispatch("app.settings.tab",json!({"tab":"sync"}));
        assert_eq!(c.state()["syncError"],error,"The latest failed transfer must remain visible when returning to sync");
        p.configure_fails.store(true,std::sync::atomic::Ordering::SeqCst);
        c.dispatch("sync.edit",json!({"key":"username","value":"draft"}));
        assert_eq!(c.dispatch("sync.save",json!({}))["ok"],false);
        assert_eq!(c.state()["syncError"],"用户名无效");
        c.dispatch("sync.cancel",json!({}));
        assert_eq!(c.state()["syncDraft"]["username"],"fixture");
        assert_eq!(c.state()["syncError"],error,"Discarding invalid input restores the last transfer result, not its validation error");
        assert_ne!(c.state()["sync"]["automaticBlocked"],true);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn sync_transfer_error_clears_after_success_or_a_saved_account_change(){
        let(c,p)=sync_feedback_fixture();
        c.dispatch("sync.run",json!({}));
        p.transfer_fails.store(false,std::sync::atomic::Ordering::SeqCst);
        assert_eq!(c.dispatch("sync.run",json!({}))["ok"],true);
        c.dispatch("app.settings.tab",json!({"tab":"about"}));c.dispatch("app.settings.tab",json!({"tab":"sync"}));
        assert_eq!(c.state()["syncError"],"");
        p.transfer_fails.store(true,std::sync::atomic::Ordering::SeqCst);c.dispatch("sync.run",json!({}));
        c.dispatch("sync.edit",json!({"key":"username","value":"new-account"}));
        assert_eq!(c.dispatch("sync.save",json!({}))["ok"],true);
        c.dispatch("app.settings.tab",json!({"tab":"about"}));c.dispatch("app.settings.tab",json!({"tab":"sync"}));
        assert_eq!(c.state()["syncError"],"");
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn sync_saved_password_is_reusable_only_for_an_unchanged_account(){
        let(c,_)=sync_feedback_fixture();
        assert_eq!(c.state()["syncPasswordReusable"],true);
        c.dispatch("sync.edit",json!({"key":"url","value":"  https://example.test/dav  "}));
        assert_eq!(c.state()["syncPasswordReusable"],true);
        c.dispatch("sync.edit",json!({"key":"username","value":"another-account"}));
        assert_eq!(c.state()["syncPasswordReusable"],false);
        c.dispatch("sync.cancel",json!({}));assert_eq!(c.state()["syncPasswordReusable"],true);
        c.dispatch("sync.edit",json!({"key":"url","value":"https://another.test/dav"}));
        c.dispatch("sync.edit",json!({"key":"password","value":"private-fixture-input"}));
        assert_eq!(c.state()["syncPasswordReusable"],false);
        assert!(!c.state().to_string().contains("private-fixture-input"));
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn webdav_editing_is_a_redacted_draft_and_cancel_does_not_persist(){
        let(c,p)=dav();assert_eq!(c.state()["syncDirty"],false);
        for(key,value)in [("url",json!("http://example.test/dav/")),("password",json!("fixture-secret")),("intervalMinutes",json!(15))]{assert_eq!(c.dispatch("sync.edit",json!({"key":key,"value":value}))["ok"],true);}
        assert_eq!(c.state()["syncDirty"],true);assert!(!c.state().to_string().contains("fixture-secret"));assert_eq!(c.state()["syncDraft"]["intervalMinutes"],15);
        assert!(p.calls.lock().unwrap().is_empty());c.dispatch("sync.cancel",json!({}));assert_eq!(c.state()["syncDirty"],false);assert_eq!(c.state()["syncDraft"]["url"],"");assert!(p.calls.lock().unwrap().is_empty());let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn leaving_webdav_requires_a_decision_and_can_save_before_navigating(){
        for choice in ["stay","discard","save"]{let(c,p)=dav();c.dispatch("sync.edit",json!({"key":"url","value":"http://example.test/dav/"}));c.dispatch("app.settings.tab",json!({"tab":"updates"}));assert_eq!(c.state()["settingsTab"],"sync");assert_eq!(c.state()["navigationPrompt"]["kind"],"unsaved-sync");
            assert_eq!(c.dispatch("navigation.resolve",json!({"choice":choice}))["ok"],true);
            assert_eq!(c.state()["settingsTab"],if choice=="stay"{"sync"}else{"updates"});assert_eq!(p.calls.lock().unwrap().len(),if choice=="save"{1}else{0});
            if choice=="save"{assert_eq!(c.state()["sync"]["url"],"http://example.test/dav/");assert!(c.state()["syncDraft"].is_null());}let _=std::fs::remove_file(&c.storage);
        }
    }
    #[test]fn failed_webdav_save_keeps_input_without_persisting_or_leaving(){
        let(c,p)=dav();p.fail.store(true,std::sync::atomic::Ordering::SeqCst);c.dispatch("sync.edit",json!({"key":"url","value":"invalid"}));c.dispatch("devices.open",json!({}));assert_eq!(c.dispatch("navigation.resolve",json!({"choice":"save"}))["ok"],false);
        assert_eq!(c.state()["settingsTab"],"sync");assert_eq!(c.state()["syncDraft"]["url"],"invalid");assert_eq!(c.state()["syncDirty"],true);assert_eq!(c.state()["syncError"],"WebDAV 地址无效");assert!(c.state()["navigationPrompt"].is_null());assert!(p.saved.lock().unwrap().is_null());let _=std::fs::remove_file(&c.storage);
    }

    fn configured_dav()->(Arc<Controller>,Arc<Dav>){
        let(c,p)=dav();assert_eq!(c.dispatch("sync.configure",json!({"url":"https://example.test/dav/","username":"fixture","password":"fixture-secret","automatic":true,"intervalMinutes":15}))["ok"],true);(c,p)
    }
    #[test]fn sync_removal_requires_confirmation_and_cancel_preserves_unsaved_inputs(){
        let(c,p)=configured_dav();let before=p.saved.lock().unwrap().clone();let calls=p.calls.lock().unwrap().len();
        c.dispatch("sync.edit",json!({"key":"username","value":"unfinished edit"}));assert_eq!(c.state()["status"],"");
        assert_eq!(c.dispatch("sync.clear",json!({}))["result"]["confirmationRequired"],true);
        assert_eq!(*p.saved.lock().unwrap(),before);assert_eq!(p.calls.lock().unwrap().len(),calls);
        assert!(c.state()["syncClearPrompt"].is_object());assert!(!c.state().to_string().contains("fixture-secret"));
        assert_eq!(c.dispatch("sync.save",json!({}))["ok"],false);
        assert_eq!(c.dispatch("sync.run",json!({"automatic":true}))["result"]["skipped"],true);
        assert_eq!(c.dispatch("sync.clear",json!({}))["ok"],true);assert_eq!(p.calls.lock().unwrap().len(),calls);
        let back=c.dispatch("navigation.back",json!({}));assert_eq!(back["ok"],true);assert_eq!(back["action"],"navigation.back");
        assert!(c.state()["syncClearPrompt"].is_null());assert_eq!(c.state()["syncDraft"]["username"],"unfinished edit");assert_eq!(*p.saved.lock().unwrap(),before);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn confirmed_sync_removal_only_clears_local_sync_account_once(){
        let(c,p)=configured_dav();let shared=c.state()["saved"].clone();let calls=p.calls.lock().unwrap().len();
        assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"remove"}))["ok"],false);assert_eq!(p.calls.lock().unwrap().len(),calls);
        c.dispatch("sync.clear",json!({}));assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"invalid"}))["ok"],false);assert!(c.state()["syncClearPrompt"].is_object());
        assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"remove"}))["ok"],true);
        assert_eq!(p.calls.lock().unwrap().last().unwrap().0,"sync.clear");assert_eq!(p.calls.lock().unwrap().len(),calls+1);
        assert_eq!(c.state()["sync"]["hasPassword"],false);assert_eq!(c.state()["sync"]["automatic"],false);assert_eq!(c.state()["syncDirty"],false);assert_eq!(c.state()["saved"],shared);
        assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"remove"}))["ok"],false);assert_eq!(p.calls.lock().unwrap().len(),calls+1);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn failed_sync_removal_keeps_the_account_and_confirmation_available(){
        let(c,p)=configured_dav();let before=p.saved.lock().unwrap().clone();c.dispatch("sync.clear",json!({}));p.fail.store(true,std::sync::atomic::Ordering::SeqCst);
        assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"remove"}))["ok"],false);assert_eq!(*p.saved.lock().unwrap(),before);assert!(c.state()["syncClearPrompt"].is_object());assert!(!c.state()["syncError"].as_str().unwrap().is_empty());assert_eq!(c.state()["status"],"");
        assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"cancel"}))["ok"],true);assert_eq!(*p.saved.lock().unwrap(),before);
        p.fail.store(false,std::sync::atomic::Ordering::SeqCst);c.dispatch("sync.clear",json!({}));assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"remove"}))["ok"],true);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn retired_decision_cannot_save_a_new_navigation(){
        let c=fixture();input(&c,"fps","24");c.dispatch("devices.open",json!({}));let old=c.state()["navigationPrompt"]["requestId"].clone();
        c.dispatch("navigation.resolve",json!({"choice":"stay"}));c.dispatch("app.settings.open",json!({}));let current=c.state()["navigationPrompt"].clone();let saved=c.state()["saved"].clone();
        assert_eq!(c.dispatch("navigation.resolve",json!({"choice":"save","requestId":old}))["ok"],false,"An old confirmation saved the current draft and followed a different destination");
        assert_eq!(c.state()["saved"],saved);assert_eq!(c.state()["navigationPrompt"],current);assert_eq!(c.state()["settingsInputs"]["fps"],"24");
        assert_ne!(old,current["requestId"]);assert_eq!(c.dispatch("navigation.resolve",json!({"choice":"save","requestId":current["requestId"]}))["ok"],true);assert_eq!(c.profile("host").fps,24);assert_eq!(c.state()["page"],"global-settings");let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn retired_decision_cannot_remove_the_current_sync_account(){
        let(c,p)=configured_dav();c.dispatch("sync.clear",json!({}));let old=c.state()["syncClearPrompt"]["requestId"].clone();c.dispatch("sync.clear.resolve",json!({"choice":"cancel"}));c.dispatch("sync.clear",json!({}));let current=c.state()["syncClearPrompt"].clone();let saved=p.saved.lock().unwrap().clone();let calls=p.calls.lock().unwrap().len();
        assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"remove","requestId":old}))["ok"],false,"An old removal decision cleared the current account");assert_eq!(*p.saved.lock().unwrap(),saved);assert_eq!(p.calls.lock().unwrap().len(),calls);assert_eq!(c.state()["syncClearPrompt"],current);
        assert_ne!(old,current["requestId"]);assert_eq!(c.dispatch("sync.clear.resolve",json!({"choice":"remove","requestId":current["requestId"]}))["ok"],true);assert_eq!(c.state()["sync"]["hasPassword"],false);let _=std::fs::remove_file(&c.storage);
    }

}
