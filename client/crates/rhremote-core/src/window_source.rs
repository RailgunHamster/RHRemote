//! Ephemeral, peer-bound window picker. No source token is persisted or synced.
use crate::{Controller, DiagnosticTask, Platform, connection_device};
use serde::Serialize;
use serde_json::{Value,json};
use std::sync::{Arc,Mutex,atomic::{AtomicBool,Ordering}};

pub fn valid(source:&str)->bool {
    source.strip_prefix("rhrwindow:").is_some_and(|id|id.len()==32&&id.bytes().all(|c|c.is_ascii_digit()||(b'a'..=b'f').contains(&c)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{thread,time::{Duration,Instant}};
    const SOURCE:&str="rhrwindow:0123456789abcdef0123456789abcdef";
    fn row()->Value{json!({"id":SOURCE,"title":"Editor","application":"editor.exe","width":1200,"height":800,"state":"ready","available":true})}
    struct Host {calls:Mutex<Vec<(String,Value)>>,rows:Value}
    impl Platform for Host{
        fn execute(&self,id:&str,args:&Value)->Result<Value,String>{self.calls.lock().unwrap().push((id.into(),args.clone()));Ok(match id{"devices.restore"=>json!({"devices":args["devices"]}),"device.windows.open"=>json!({"supported":true,"windows":self.rows}),_=>json!({"accepted":true})})}
    }
    struct Fixture{controller:Arc<Controller>,host:Arc<Host>}
    impl Drop for Fixture{fn drop(&mut self){let _=std::fs::remove_file(&self.controller.storage);}}
    fn fixture(rows:Value)->Fixture{
        let host=Arc::new(Host{calls:Mutex::new(vec![]),rows});
        let c=Controller::new("test",std::env::temp_dir().join(format!("rhremote-window-{}.json",crate::random_token().unwrap())),host.clone()).unwrap();
        c.replace_devices(serde_json::from_value(json!([{"uuid":"host","name":"Host","address":"192.0.2.1","paired":true,"online":true},{"uuid":"other","name":"Other","address":"192.0.2.2","paired":true,"online":true},{"uuid":"self","name":"Self","address":"192.0.2.3","paired":true,"online":true,"isSelf":true}])).unwrap());
        Fixture{controller:c,host}
    }
    fn settled(c:&Controller){let until=Instant::now()+Duration::from_secs(3);while c.state()["windowPicker"]["busy"]==true{assert!(Instant::now()<until,"{}",c.state()["windowPicker"]);thread::sleep(Duration::from_millis(5));}}
    #[test]fn tokens_are_canonical_and_resolution_preserves_aspect(){
        assert!(valid(SOURCE));for bad in ["0x123", "rhrwindow:", "rhrwindow:0123456789ABCDEF0123456789abcdef", "\\\\.\\DISPLAY1"]{assert!(!valid(bad));}
        assert_eq!(resolution(&row(),1920,1080).unwrap(),(1200,800));assert_eq!(resolution(&row(),600,1000).unwrap(),(600,400));
        let mut portrait=row();portrait["width"]=json!(800);portrait["height"]=json!(1200);assert_eq!(resolution(&portrait,1920,1080).unwrap(),(720,1080));portrait["width"]=json!(0);assert!(resolution(&portrait,1920,1080).is_err());
    }
    #[test]fn selection_uses_only_the_listed_host_and_never_writes_the_source_to_sync(){
        let f=fixture(json!([row()]));let c=&f.controller;let before=c.state()["saved"].clone();assert_eq!(c.dispatch("device.windows.open",json!({"uuid":"host"}))["ok"],true);settled(c);
        assert_eq!(c.dispatch("device.window.connect",json!({"uuid":"other","source":SOURCE}))["ok"],false);
        assert_eq!(c.dispatch("device.window.connect",json!({"uuid":"host","source":"0x123"}))["ok"],false);
        assert_eq!(c.dispatch("device.window.connect",json!({"uuid":"host","source":SOURCE}))["ok"],true);
        let calls=f.host.calls.lock().unwrap();let connect=calls.iter().find(|(id,_)|id=="device.connect").unwrap();assert_eq!(connect.1["window"],row());assert!(connect.1["profile"]["width"].as_u64().unwrap()<=1200);assert!(connect.1["profile"]["height"].as_u64().unwrap()<=800);drop(calls);
        assert_eq!(c.state()["saved"],before);assert_eq!(c.state()["windowPicker"]["open"],false);
        {let mut s=c.state.lock().unwrap();s.connecting=false;s.connection.fail("fixture disconnected".into());}
        assert_eq!(c.dispatch("connection.retry",json!({}))["ok"],true);let calls=f.host.calls.lock().unwrap();assert_eq!(calls.iter().filter(|(id,_)|id=="device.connect").count(),2);assert_eq!(calls.last().unwrap().1["window"]["id"],SOURCE);
    }
    #[test]fn unsupported_or_invalid_window_list_is_an_error_with_no_desktop_launch(){
        let mut invalid=row();invalid["width"]=json!(0);let f=fixture(json!([invalid]));let c=&f.controller;
        assert_eq!(c.dispatch("device.windows.open",json!({"uuid":"host"}))["ok"],true);settled(c);assert!(!c.state()["windowPicker"]["error"].as_str().unwrap().is_empty());assert_eq!(c.state()["windowPicker"]["rows"],json!([]));assert!(!f.host.calls.lock().unwrap().iter().any(|(id,_)|id=="device.connect"));
    }
    #[test]fn self_or_unlisted_selection_never_reaches_the_transport(){let f=fixture(json!([]));let c=&f.controller;assert_eq!(c.dispatch("device.windows.open",json!({"uuid":"self"}))["ok"],false);assert_eq!(c.dispatch("device.window.connect",json!({"uuid":"host","source":SOURCE}))["ok"],false);assert!(!f.host.calls.lock().unwrap().iter().any(|(id,_)|id=="device.windows.open"||id=="device.connect"));}
}
pub fn resolution(row:&Value,width:u32,height:u32)->Result<(u32,u32),String>{
    let w=row["width"].as_u64().filter(|v|*v>0&&*v<=16384).ok_or("无效窗口尺寸")? as f64;
    let h=row["height"].as_u64().filter(|v|*v>0&&*v<=16384).ok_or("无效窗口尺寸")? as f64;
    if width<2||height<2{return Err("无效分辨率".into());}
    let scale=1f64.min(width as f64/w).min(height as f64/h);
    Ok((((w*scale) as u32&!1).max(2),((h*scale) as u32&!1).max(2)))
}
struct Flight {cancel:Arc<AtomicBool>,task:Mutex<Box<dyn DiagnosticTask>>}
impl std::fmt::Debug for Flight{fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{f.write_str("WindowListFlight")}}
#[derive(Clone,Debug,Default,Serialize)]
#[serde(rename_all="camelCase")]
pub struct WindowPicker {
    pub uuid:String,pub open:bool,pub busy:bool,pub operation:u64,pub error:String,pub rows:Vec<Value>,
    #[serde(skip)] flight:Option<Arc<Flight>>,
    #[serde(skip)] close_requested:bool,
}
struct Legacy {platform:Arc<dyn Platform>,args:Value}
impl DiagnosticTask for Legacy{
    fn run(&mut self,cancel:Arc<AtomicBool>)->Result<Value,String>{if cancel.load(Ordering::SeqCst){return Err("已取消".into());}self.platform.execute("device.windows.open",&self.args)}
    fn cleanup(&mut self)->Result<(),String>{Ok(())}
}
impl Controller {
    pub(crate) fn window_picker_gate(&self,id:&str)->Option<Result<Value,String>>{
        let mut s=self.state.lock().unwrap();
        if !s.window_picker.busy{return None;}
        if id=="window.close"{s.window_picker.close_requested=true;let job=&mut s.window_picker;job.open=false;if let Some(f)=&job.flight{f.cancel.store(true,Ordering::SeqCst);}s.revision+=1;return Some(Ok(json!({"cleanupPending":true})));}
        if matches!(id,"device.connect"|"device.window.connect"|"device.pair"|"device.network.diagnose"|"updates.install"|"updates.primary"){return Some(Err("窗口列表请求尚未清理，请稍候或取消".into()));}
        None
    }
    pub(crate) fn dispatch_window_picker(&self,id:&str,args:Value)->Result<Value,String>{
        if id=="device.window.connect"{
            let window={let s=self.state.lock().unwrap();let p=&s.window_picker;
                if !p.open||p.busy||args["uuid"]!=p.uuid{return Err("请重新选择窗口".into());}
                p.rows.iter().find(|r|r["id"]==args["source"]&&r["available"]==true).cloned().ok_or("窗口已不可用，请刷新列表")?};
            let reply=self.dispatch("device.connect",json!({"uuid":args["uuid"],"window":window}));
            if reply["ok"]==true{let mut s=self.state.lock().unwrap();s.window_picker.open=false;s.revision+=1;return Ok(reply["result"].clone());}
            return Err(reply["error"].as_str().unwrap_or("窗口连接失败").into());
        }
        let (operation,flight,cleanup_only)={let mut s=self.state.lock().unwrap();
            if id=="device.windows.close"{
                s.window_picker.open=false;s.revision+=1;
                if let Some(f)=s.window_picker.flight.clone(){f.cancel.store(true,Ordering::SeqCst);if s.window_picker.error.starts_with("清理失败"){s.window_picker.error.clear();(s.window_picker.operation,f,true)}else{return Ok(json!({"cleanupPending":s.window_picker.busy}));}}
                else{return Ok(json!({"closed":true}));}
            }else{
                if s.window_picker.busy||s.diagnostics.busy||s.closing||s.installing_update||s.streaming||s.connecting||s.connection.busy()||s.connect_in_flight||s.control_requests>0{return Err("请先结束当前连接或操作".into());}
                let uuid=args["uuid"].as_str().ok_or("uuid required")?;let device=connection_device(&s,uuid)?;
                if device.is_self||!device.paired{return Err("请先配对远程设备".into());}
                if s.device_trust_checks.get(uuid).or_else(||s.device_checks.get(uuid)).is_some_and(|c|c.blocks_connection()||c.repair_required()){return Err("请先检查设备连接地址与配对".into());}
                let profile=s.saved.effective_profile(uuid);
                let task=match self.platform.prepare_windows(&device,&profile){Some(result)=>result?,None=>Box::new(Legacy{platform:self.platform.clone(),args:json!({"device":device,"profile":profile})})};
                let operation=s.window_picker.operation.checked_add(1).ok_or("窗口列表编号已耗尽")?;
                let flight=Arc::new(Flight{cancel:Arc::new(AtomicBool::new(false)),task:Mutex::new(task)});
                s.window_picker=WindowPicker{uuid:device.uuid,open:true,busy:true,operation,flight:Some(flight.clone()),..Default::default()};s.revision+=1;(operation,flight,false)
            }};
        let owner=self.owner.upgrade().ok_or("应用已关闭")?;
        let failed_owner=owner.clone();
        let spawn=std::thread::Builder::new().name("RHRemote window list".into()).spawn(move||{
            let mut task=flight.task.lock().unwrap();let result=if cleanup_only{Err("已取消".into())}else{task.run(flight.cancel.clone())};
            let cleaned=task.cleanup();drop(task);
            let close={let mut s=owner.state.lock().unwrap();let p=&mut s.window_picker;if p.operation!=operation{return;}
                if let Err(error)=cleaned{p.open=true;p.error=format!("清理失败，请重试取消：{error}");false}else{
                    p.busy=false;p.flight=None;
                    if !flight.cancel.load(Ordering::SeqCst){match result{Ok(data)=>{
                        let rows=data["windows"].as_array().cloned().unwrap_or_default();
                        if data["supported"]!=true||rows.len()>128||rows.iter().any(|r|r["available"]==true&&(!valid(r["id"].as_str().unwrap_or(""))||resolution(r,16384,16384).is_err())){p.error="无效窗口列表或服务端尚不支持窗口串流".into();}
                        else{p.rows=rows;p.error.clear();}
                    },Err(error)=>p.error=error}}else{p.error.clear();}
                    p.close_requested
                }};
            {let mut s=owner.state.lock().unwrap();s.revision+=1;}
            if close{owner.dispatch("window.close",json!({}));}
        });
        if let Err(error)=spawn{let mut s=failed_owner.state.lock().unwrap();s.window_picker.error=format!("清理失败，任务未启动：{error}");s.revision+=1;return Err(error.to_string());}
        Ok(json!({"accepted":true,"operation":operation}))
    }
}
