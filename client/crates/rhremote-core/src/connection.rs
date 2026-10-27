use crate::{Controller, Device, State};
use serde::Serialize;
use serde_json::{Value, json};

/// Presentation of one user request. Transport ownership remains with the platform.
#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Connection {
    pub uuid: String,
    pub name: String,
    pub operation: String,
    pub attempt: u64,
    pub phase: String,
    pub error: String,
    pub first_frame: bool,
    #[serde(skip)]
    pub stopping: bool,
}
impl Connection {
    pub fn begin(&mut self, device: &Device) {
        *self = Self { uuid: device.uuid.clone(), name: if device.display_name.is_empty() {device.name.clone()} else {device.display_name.clone()},
            operation: "connect".into(), attempt: self.attempt + 1, phase: "resolving".into(), ..Self::default() };
    }
    pub fn busy(&self) -> bool {
        matches!(self.phase.as_str(), "resolving"|"pairing"|"starting"|"first-frame"|"connected"|"reconnecting"|"cancelling"|"cleaning")
    }
    pub fn title(&self) -> &str {
        if self.operation=="pair" {return match self.phase.as_str(){"pairing"=>"等待主机确认配对","paired"=>"配对完成","failed"=>"配对失败","cancelling"=>"正在取消配对","cleaning"=>"正在清理配对","cancelled"=>"已取消配对",_=>"配对"};}
        match self.phase.as_str() {
            "resolving" => "检查连接地址", "starting" => "建立串流", "first-frame" => "等待远程画面",
            "connected" => "已连接", "reconnecting" => "正在恢复连接", "cancelling" => "正在断开",
            "cleaning" => "正在清理连接", "failed" => "连接失败", "cancelled" => "已取消连接", "ended" => "已断开", _ => "连接",
        }
    }
    pub fn fail(&mut self, error: String) { self.phase="failed".into(); self.error=error; }
}

pub(crate) fn observe(s: &mut State, streaming: bool, data: &Value) {
    let c=&mut s.connection;
    if c.uuid.is_empty() || !c.busy() { return; }
    if c.stopping {
        if streaming || s.connect_in_flight || data["nativeCleanupPending"]!=false {
            c.phase="cancelling".into(); s.connecting=true; s.status=c.title().into();
        } else {
            c.phase=if c.first_frame {"ended"} else {"cancelled"}.into();
            c.error.clear(); s.status=c.title().into();
            if s.page=="connection" || s.page=="pair" {s.page="devices".into();}
        }
        return;
    }
    if c.operation=="pair" {
        if c.phase=="cleaning" && data["nativeCleanupPending"]==false {c.phase="failed".into();}
        s.connecting=c.busy();
        s.status=if c.error.is_empty(){c.title().into()}else{c.error.clone()};
        return;
    }
    if data["phase"]=="reconnecting" {
        c.phase="reconnecting".into(); return;
    }
    if let Some(error)=data["error"].as_str().filter(|v|!v.is_empty()) { c.error=error.into(); }
    if !c.error.is_empty() && !streaming {
        c.phase=if s.connecting {"cleaning"} else {"failed"}.into();
    } else if streaming && data["firstFrame"]==true {
        c.first_frame=true; c.phase="connected".into(); c.error.clear();
    } else if s.connecting || streaming {
        c.phase=if streaming {"first-frame"} else {"starting"}.into();
    } else if c.first_frame {
        c.fail("远程连接已结束".into());
    } else {
        c.fail("连接在收到远程画面前结束".into());
    }
}

impl Controller {
    pub(crate) fn connection_gate(&self, id:&str) -> Option<Result<Value,String>> {
        let s=self.state.lock().unwrap();
        if navigation_locked(&s) && matches!(id,
            "devices.open"|"device.select"|"device.add.open"|"device.settings.open"|"device.info.open"|"app.settings.open"|"app.settings.tab"|"appearance.toggle") {
            return Some(Err(navigation_hint(&s).into()));
        }
        None
    }
    pub(crate) fn connection_action(&self,id:&str)->Result<Value,String> {
        let (uuid,busy,pairing)={let s=self.state.lock().unwrap();if s.page!="connection" {return Err("请先打开连接页".into());}(s.connection.uuid.clone(),s.connection.busy(),s.connection.operation=="pair")};
        if uuid.is_empty(){return Err("没有可操作的连接".into());}
        if busy{return Err("上一连接尚未结束".into());}
        let response=match id {
            "connection.retry"=>self.dispatch(if pairing{"device.pair"}else{"device.connect"},json!({"uuid":uuid})),
            "connection.dismiss"=>self.dispatch("devices.open",json!({})),
            _=>return Err("unknown connection action".into()),
        };
        if response["ok"]==true {Ok(response["result"].clone())} else {Err(response["error"].as_str().unwrap_or("连接操作失败").into())}
    }
}

pub(crate) fn navigation_locked(s:&State)->bool {matches!(s.page.as_str(),"connection"|"pair") && s.connection.busy()}
pub(crate) fn navigation_hint(s:&State)->&str {
    if !navigation_locked(s) {""} else if matches!(s.connection.phase.as_str(),"cancelling"|"cleaning") {"正在清理，完成后可切换页面"} else if s.connection.operation=="pair" {"配对期间无法切换页面"} else {"连接期间无法切换页面"}
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Platform,random_token};
    use std::sync::{Arc,atomic::{AtomicUsize,Ordering}};
    struct Host(AtomicUsize);
    impl Platform for Host {
        fn execute(&self,id:&str,_:&Value)->Result<Value,String>{
            if id=="device.connect"{self.0.fetch_add(1,Ordering::SeqCst);}
            Ok(json!({"accepted":true,"cleanupPending":id=="stream.disconnect"}))
        }
    }
    fn fixture()->(Arc<Controller>,Arc<Host>){
        let host=Arc::new(Host(AtomicUsize::new(0)));
        let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-connection-{}.json",random_token().unwrap())),host.clone()).unwrap();
        c.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"winserver","address":"192.168.0.104","online":false,"paired":true},{"uuid":"other","name":"claw","address":"192.168.0.113","online":true,"paired":true}])).unwrap());
        c.dispatch("devices.filter",json!({"query":"work"}));
        assert_eq!(c.dispatch("device.connect",json!({"uuid":"host"}))["ok"],true);
        (c,host)
    }
    fn poll(c:&Controller,streaming:bool,phase:&str,first:bool,error:&str,cleanup:bool){c.stream_state(streaming,json!({"phase":phase,"firstFrame":first,"error":error,"nativeCleanupPending":cleanup}));}
    #[test]fn stages_only_report_connected_after_a_real_first_frame(){
        let(c,_)=fixture();assert_eq!(c.state()["page"],"connection");assert_eq!(c.state()["connection"]["phase"],"starting");
        poll(&c,true,"connected",false,"",false);assert_eq!(c.state()["connection"]["phase"],"first-frame");assert_eq!(c.state()["connection"]["firstFrame"],false);
        poll(&c,true,"connected",true,"",false);assert_eq!(c.state()["connection"]["phase"],"connected");assert_eq!(c.state()["connection"]["firstFrame"],true);
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn navigation_availability_and_api_rejections_share_the_lifecycle_state(){
        let(c,_)=fixture();let query=c.state()["deviceQuery"].clone();
        for(phase,streaming,first,cleanup)in[("starting",false,false,false),("connected",true,true,false),("reconnecting",false,false,true)]{
            poll(&c,streaming,phase,first,"",cleanup);assert_eq!(c.state()["navigation"]["locked"],true);
            let reason=c.state()["navigation"]["reason"].clone();assert!(!reason.as_str().unwrap().is_empty());
            for id in ["devices.open","device.select","device.add.open","device.settings.open","device.info.open","app.settings.open","app.settings.tab","appearance.toggle"]{let response=c.dispatch(id,json!({"uuid":"other","tab":"about"}));assert_eq!(response["ok"],false,"{id}");assert_eq!(response["error"],reason,"{id}");}
        }
        c.dispatch("stream.disconnect",json!({}));assert_eq!(c.state()["navigation"]["reason"],"正在清理，完成后可切换页面");
        poll(&c,false,"transport_closed",false,"",false);assert_eq!(c.state()["navigation"],json!({"locked":false,"reason":""}));assert_eq!(c.state()["deviceQuery"],query);
        assert_eq!(c.dispatch("app.settings.open",json!({}))["ok"],true);assert_eq!(c.state()["page"],"global-settings");let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn unavailable_poll_is_not_evidence_of_a_disconnect(){
        let(c,host)=fixture();let before=c.state();c.stream_state(false,Value::Null);
        assert_eq!(c.state()["connection"],before["connection"]);assert_eq!(c.state()["connecting"],true);
        assert_eq!(c.dispatch("device.connect",json!({"uuid":"host"}))["ok"],false);assert_eq!(host.0.load(Ordering::SeqCst),1);
        poll(&c,true,"connected",true,"",false);c.stream_state(false,Value::Null);assert_eq!(c.state()["streaming"],true);assert_eq!(c.state()["connection"]["phase"],"connected");
        let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn one_request_keeps_its_device_until_failure_and_retry(){
        let(c,host)=fixture();let original=c.state()["saved"].clone();
        for id in ["device.connect","device.select","devices.open","device.settings.open","device.info.open","app.settings.open"]{
            assert_eq!(c.dispatch(id,json!({"uuid":"other"}))["ok"],false,"{id}");
            assert_eq!(c.state()["selected"],"host");
        }
        assert_eq!(host.0.load(Ordering::SeqCst),1);
        poll(&c,false,"error",false,"certificate mismatch",false);
        c.stream_state(false,Value::Null);assert_eq!(c.state()["connection"]["error"],"certificate mismatch");
        assert_eq!(c.dispatch("connection.retry",json!({"uuid":"other"}))["ok"],true);
        assert_eq!(host.0.load(Ordering::SeqCst),2);assert_eq!(c.state()["connection"]["uuid"],"host");assert_eq!(c.state()["connection"]["attempt"],2);
        assert_eq!(c.state()["saved"],original);assert_eq!(c.state()["deviceQuery"],"work");let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn cancel_waits_for_positive_cleanup_and_ignores_late_frames_and_errors(){
        let(c,_)=fixture();assert_eq!(c.dispatch("navigation.back",json!({}))["ok"],true);
        c.stream_state(false,Value::Null);assert_eq!(c.state()["connection"]["phase"],"cancelling");assert_eq!(c.state()["connecting"],true);
        assert_eq!(c.dispatch("connection.retry",json!({}))["ok"],false);
        poll(&c,true,"connected",true,"",false);assert_eq!(c.state()["connection"]["phase"],"cancelling");
        poll(&c,false,"error",false,"cancelled socket",false);
        assert_eq!(c.state()["connection"]["phase"],"cancelled");assert_eq!(c.state()["connection"]["error"],"");assert_eq!(c.state()["page"],"devices");
        assert_eq!(c.state()["selected"],"host");assert_eq!(c.state()["deviceQuery"],"work");let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn automatic_recovery_and_unexpected_end_differ_from_user_disconnect(){
        let(c,_)=fixture();poll(&c,true,"connected",true,"",false);
        poll(&c,false,"reconnecting",false,"temporary network failure",true);assert_eq!(c.state()["connection"]["phase"],"reconnecting");
        poll(&c,true,"connected",true,"",false);assert_eq!(c.state()["connection"]["error"],"");
        poll(&c,false,"transport_closed",false,"",false);assert_eq!(c.state()["connection"]["phase"],"failed");assert!(!c.state()["connection"]["error"].as_str().unwrap().is_empty());
        c.dispatch("connection.retry",json!({}));poll(&c,true,"connected",true,"",false);c.dispatch("stream.disconnect",json!({}));poll(&c,false,"transport_closed",false,"",false);
        assert_eq!(c.state()["connection"]["phase"],"ended");assert_eq!(c.state()["page"],"devices");let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn failure_cannot_be_retried_until_native_cleanup_and_dismiss_does_not_leak_error(){
        let(c,_)=fixture();poll(&c,false,"error",false,"decoder failed",true);
        assert_eq!(c.state()["connection"]["phase"],"cleaning");assert_eq!(c.dispatch("connection.retry",json!({}))["ok"],false);
        poll(&c,false,"idle",false,"",false);assert_eq!(c.state()["connection"]["phase"],"failed");
        c.dispatch("connection.dismiss",json!({}));assert_eq!(c.state()["page"],"devices");assert_eq!(c.state()["status"],"");
        c.dispatch("device.settings.open",json!({"uuid":"host"}));c.dispatch("settings.input",json!({"key":"width","value":""}));
        assert_eq!(c.dispatch("connection.dismiss",json!({}))["ok"],false);assert_eq!(c.state()["settingsInputs"]["width"],"");let _=std::fs::remove_file(&c.storage);
    }
    #[test]fn pairing_poll_cleanup_retry_and_cancellation_stay_with_the_original_device(){
        struct PairHost(std::sync::Mutex<Vec<String>>);
        impl Platform for PairHost {fn execute(&self,id:&str,_:&Value)->Result<Value,String>{self.0.lock().unwrap().push(id.into());Ok(json!({"accepted":true,"cleanupPending":id=="pair.cancel"}))}}
        let host=Arc::new(PairHost(std::sync::Mutex::new(vec![])));
        let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-pair-flow-{}.json",random_token().unwrap())),host.clone()).unwrap();
        c.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"winserver","address":"192.168.0.104","online":true,"paired":true},{"uuid":"other","name":"claw","address":"192.168.0.113","online":true,"paired":true}])).unwrap());
        c.dispatch("devices.filter",json!({"query":"work"}));let saved=c.state()["saved"].clone();
        assert_eq!(c.dispatch("device.pair",json!({"uuid":"host","pin":"1234"}))["ok"],true);
        poll(&c,false,"idle",false,"old transport error",false);
        assert_eq!(c.state()["page"],"pair");assert_eq!(c.state()["connection"]["phase"],"pairing");assert_eq!(c.state()["connecting"],true);
        for id in ["devices.open","device.select","app.settings.open","appearance.toggle","device.connect","device.pair"] {assert_eq!(c.dispatch(id,json!({"uuid":"other"}))["ok"],false,"{id}");}
        let device:Device=serde_json::from_value(c.state()["devices"][0].clone()).unwrap();
        c.pairing_complete(1,device.clone(),"配对码不正确".into(),true);
        assert_eq!(c.state()["page"],"connection");assert_eq!(c.state()["connection"]["phase"],"cleaning");assert_eq!(c.state()["connection"]["title"],"正在清理配对");
        assert_eq!(c.dispatch("connection.retry",json!({}))["ok"],false);
        poll(&c,false,"idle",false,"stale stream error",true);assert_eq!(c.state()["connection"]["error"],"配对码不正确");
        poll(&c,false,"idle",false,"",false);assert_eq!(c.state()["connection"]["phase"],"failed");assert_eq!(c.state()["connection"]["title"],"配对失败");
        assert_eq!(c.dispatch("connection.retry",json!({"uuid":"other"}))["ok"],true);assert_eq!(c.state()["connection"]["uuid"],"host");assert_eq!(c.state()["connection"]["attempt"],2);
        assert_eq!(host.0.lock().unwrap().iter().filter(|id|id.as_str()=="device.pair").count(),2);assert!(!host.0.lock().unwrap().iter().any(|id|id=="device.connect"));
        assert_eq!(c.dispatch("navigation.back",json!({}))["ok"],true);assert_eq!(c.state()["page"],"connection");assert_eq!(c.state()["connection"]["phase"],"cancelling");assert_eq!(c.state()["pairPin"],"");
        c.pairing_complete(2,device,"late pairing error".into(),false);assert_eq!(c.state()["connection"]["phase"],"cancelling");
        assert_eq!(c.dispatch("pair.cancel",json!({}))["ok"],false);poll(&c,false,"cancelled",false,"late pairing error",true);
        assert_eq!(c.state()["page"],"connection");poll(&c,false,"cancelled",false,"late pairing error",false);
        assert_eq!(c.state()["page"],"devices");assert_eq!(c.state()["connection"]["phase"],"cancelled");assert_eq!(c.state()["connection"]["error"],"");assert_eq!(c.state()["status"],"已取消配对");assert_eq!(c.state()["deviceQuery"],"work");assert_eq!(c.state()["saved"],saved);
        let _=std::fs::remove_file(&c.storage);
    }
}
