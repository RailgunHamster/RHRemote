//! One diagnostic owns its requests and cleanup; navigation never waits for it.
use crate::{Controller, Device, Platform, Profile, State, connection_device, network};
use serde::Serialize;
use serde_json::{Value,json};
use std::sync::{Arc,Mutex,atomic::{AtomicBool,Ordering}};

/// Constructing a task is local and fast. Its worker retains ownership after a
/// cleanup failure, so retrying cleanup cannot start another network request.
pub trait DiagnosticTask:Send {
    fn run(&mut self,cancel:Arc<AtomicBool>)->Result<Value,String>;
    fn cleanup(&mut self)->Result<(),String>;
}
struct LegacyTask {platform:Arc<dyn Platform>,args:Value}
impl DiagnosticTask for LegacyTask {
    fn run(&mut self,cancel:Arc<AtomicBool>)->Result<Value,String>{checkpoint(&cancel)?;self.platform.execute("device.network.diagnose",&self.args)}
    fn cleanup(&mut self)->Result<(),String>{Ok(())}
}
struct Flight {cancel:Arc<AtomicBool>,task:Mutex<Box<dyn DiagnosticTask>>,result:Mutex<Result<Value,String>>}
impl std::fmt::Debug for Flight {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{f.write_str("DiagnosticFlight")}}
#[derive(Clone,Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct DiagnosticState {
    pub operation_id:u64,pub uuid:String,pub name:String,pub busy:bool,pub phase:String,
    pub error:String,pub result:Value,pub request_intent:String,pub details_expanded:bool,
    #[serde(skip)] flight:Option<Arc<Flight>>,
    #[serde(skip)] close_requested:bool,
}
impl Default for DiagnosticState {
    fn default()->Self{Self{operation_id:0,uuid:String::new(),name:String::new(),busy:false,phase:"idle".into(),error:String::new(),result:Value::Null,request_intent:String::new(),details_expanded:false,flight:None,close_requested:false}}
}
pub fn checkpoint(cancel:&AtomicBool)->Result<(),String>{if cancel.load(Ordering::SeqCst){Err("已取消诊断".into())}else{Ok(())}}
pub(crate) fn request(s:&State,args:&Value)->Result<(Device,Profile,String),String>{
    let uuid=args["uuid"].as_str().ok_or("uuid required")?;
    let mut device=connection_device(s,uuid)?;
    let editing=s.selected==uuid&&s.draft.is_some();
    if editing {device.address=s.draft_address.trim().into();device.addresses=s.draft_addresses.split([',',';','，','\n']).map(str::trim).filter(|s|!s.is_empty()).map(str::to_owned).collect();}
    if let Some(primary)=args.get("address"){device.address=primary.as_str().ok_or("address requires a string")?.into();}
    if let Some(extra)=args.get("addresses"){device.addresses=serde_json::from_value(extra.clone()).map_err(|_|"addresses requires an array")?;}
    network::addresses(&device.address,&device.addresses)?;
    let mut profile=if editing{s.draft.clone().unwrap()}else{s.saved.effective_profile(uuid)};
    if editing{profile.relay_url=s.draft_relay.trim().into();}network::validate_routes(&profile.route_order,&profile.relay_url)?;
    let intent=json!({"uuid":uuid,"address":device.address,"addresses":device.addresses,"networkMode":profile.network_mode,"routeOrder":profile.route_order,"relayUrl":profile.relay_url}).to_string();
    Ok((device,profile,intent))
}
pub(crate) fn enrich(s:&State,value:&mut Value){
    let job=&s.diagnostics;let cancelled=job.flight.as_ref().is_some_and(|f|f.cancel.load(Ordering::SeqCst));
    value["diagnostics"]["cancelRequested"]=json!(job.busy&&cancelled);
    value["diagnostics"]["canCancel"]=json!(job.busy&&(!cancelled||job.phase=="cleanup-error"));
    value["diagnostics"]["cancelAction"]=json!(if job.phase=="cleanup-error"{"cleanup"}else{"cancel"});
    value["diagnosticDetailsExpanded"]=json!(job.details_expanded);
    value["diagnosticRequestIntent"]=json!(request(s,&json!({"uuid":s.selected})).map(|(_,_,i)|i).unwrap_or_default());
}
fn input_error(s:&mut State,args:&Value,error:String)->String{
    if s.page=="settings"&&args["uuid"]==s.selected&&args.get("draftId").is_none_or(|v|v.as_str()==Some(s.profile_draft_id.as_str())){s.settings_error=error.clone();s.settings_error_field.clear();s.profile_tab="network".into();s.revision+=1;}error
}
impl Controller {
    pub(crate) fn diagnostic_gate(&self,id:&str)->Option<Result<Value,String>>{
        let s=self.state.lock().unwrap();
        if s.diagnostics.busy&&matches!(id,"device.connect"|"device.pair"|"connection.retry"){return Some(Err("请先完成或取消连接诊断".into()));}
        if s.diagnostics.close_requested&&!matches!(id,"window.close"|"window.minimize"|"device.network.cancel"|"network.state"|"updates.state"|"sync.state"|"statistics.state")&&!id.starts_with("view."){return Some(Err("应用正在取消诊断并等待清理".into()));}None
    }
    pub(crate) fn dispatch_diagnostic(&self,id:&str,args:Value)->Result<Value,String>{
        if id=="device.network.diagnose" {
            let operation={let mut s=self.state.lock().unwrap();
                if s.diagnostics.busy{return Err("连接诊断正在进行，请先取消或等待完成".into());}
                if s.closing||s.installing_update||s.streaming||s.connecting||s.connection.busy()||s.connect_in_flight||s.page=="pair"{return Err("请先完成或断开当前连接，再运行诊断".into());}
                let(device,profile,intent)=match request(&s,&args){Ok(value)=>value,Err(error)=>return Err(input_error(&mut s,&args,error))};
                if let Some(draft)=args.get("draftId"){if draft.as_str()!=Some(s.profile_draft_id.as_str())||s.page!="settings"||s.profile_tab!="network"||s.selected!=device.uuid{return Err("连接设置已切换，请使用当前页面的诊断操作".into());}}
                if let Some(expected)=args.get("expectedIntent"){if expected.as_str()!=Some(intent.as_str()){return Err("连接设置已变化，请重新点击诊断".into());}}
                if let Some(expected)=args.get("expectedOperationId"){if expected.as_u64()!=Some(s.diagnostics.operation_id){return Err("诊断任务已变化，请重新点击".into());}}
                let task=match self.platform.prepare_diagnostic(&device,&profile){Some(Ok(task))=>task,Some(Err(error))=>return Err(input_error(&mut s,&args,error)),None=>Box::new(LegacyTask{platform:self.platform.clone(),args:json!({"device":device,"profile":profile})})};
                let operation=s.diagnostics.operation_id.checked_add(1).ok_or("诊断编号已耗尽，请重新启动应用")?;
                s.diagnostics=DiagnosticState{operation_id:operation,uuid:device.uuid,name:if device.display_name.is_empty(){device.name}else{device.display_name},busy:true,phase:"running".into(),request_intent:intent,flight:Some(Arc::new(Flight{cancel:Arc::new(AtomicBool::new(false)),task:Mutex::new(task),result:Mutex::new(Ok(Value::Null))})),..Default::default()};s.revision+=1;operation};
            self.spawn_diagnostic(operation,false)?;return Ok(json!({"accepted":true,"operationId":operation}));
        }
        let mut s=self.state.lock().unwrap();
        if id=="device.network.cancel" {
            let job=&mut s.diagnostics;let retry=job.phase=="cleanup-error";let action=if retry{"cleanup"}else{"cancel"};
            if args["operationId"].as_u64()!=Some(job.operation_id)||args.get("expectedAction").is_some_and(|v|v.as_str()!=Some(action)){return Err("诊断任务已变化，未执行取消".into());}
            if !job.busy{return Err("当前没有可取消的诊断".into());}
            job.flight.as_ref().unwrap().cancel.store(true,Ordering::SeqCst);job.phase=if retry{"cleaning"}else{"cancelling"}.into();job.error.clear();let operation=job.operation_id;s.revision+=1;drop(s);
            if retry{self.spawn_diagnostic(operation,true)?;}return Ok(json!({"accepted":true,"operationId":operation}));
        }
        if id=="device.network.details.toggle" {
            if s.page!="settings"||s.profile_tab!="network"||args["uuid"].as_str()!=Some(s.selected.as_str())||s.diagnostics.uuid!=s.selected||args["operationId"].as_u64()!=Some(s.diagnostics.operation_id)||s.diagnostics.busy||!matches!(s.diagnostics.phase.as_str(),"completed"|"error"){return Err("诊断结果已变化，请使用当前设备的结果".into());}
            if args.get("expectedExpanded").is_some_and(|v|v.as_bool()!=Some(s.diagnostics.details_expanded)){return Err("详情状态已变化，请重新点击".into());}
            s.diagnostics.details_expanded=!s.diagnostics.details_expanded;s.revision+=1;return Ok(json!({"expanded":s.diagnostics.details_expanded}));
        }Err("unknown diagnostic action".into())
    }
    fn spawn_diagnostic(&self,operation:u64,cleanup_only:bool)->Result<(),String>{
        let owner=self.owner.upgrade().ok_or("应用已关闭")?;
        let flight={let s=self.state.lock().unwrap();if s.diagnostics.operation_id!=operation||!s.diagnostics.busy{return Err("诊断任务已变化".into());}s.diagnostics.flight.as_ref().unwrap().clone()};
        let result=std::thread::Builder::new().name("RHRemote diagnostic".into()).spawn(move||{
            let mut task=flight.task.lock().unwrap();
            if !cleanup_only{*flight.result.lock().unwrap()=checkpoint(&flight.cancel).and_then(|_|task.run(flight.cancel.clone()));}
            {let mut s=owner.state.lock().unwrap();if s.diagnostics.operation_id!=operation{return;}if !flight.cancel.load(Ordering::SeqCst){s.diagnostics.phase="cleaning".into();}s.revision+=1;}
            let cleaned=task.cleanup();drop(task);
            let close={let mut s=owner.state.lock().unwrap();let job=&mut s.diagnostics;if job.operation_id!=operation{return;}
                let close=match cleaned{Err(error)=>{job.phase="cleanup-error".into();job.error=format!("诊断通道清理失败，请重试清理：{error}");false},Ok(())=>{
                    job.busy=false;let result=flight.result.lock().unwrap().clone();job.result=result.as_ref().ok().cloned().unwrap_or(Value::Null);
                    let cancelled=flight.cancel.load(Ordering::SeqCst);job.phase=if cancelled{"cancelled"}else if result.is_ok(){"completed"}else{"error"}.into();job.error=if cancelled{String::new()}else{result.err().unwrap_or_default()};
                    let close=job.close_requested;job.close_requested=false;job.flight=None;if job.result.is_object(){s.network=job.result.clone();}if close{s.closing=true;}close
                }};s.revision+=1;close};
            if close{let result=owner.dispatch("window.close",json!({}));if result["ok"]!=true{owner.message(result["error"].as_str().unwrap_or("关闭失败").into());}}
        });
        if let Err(error)=result{let mut s=self.state.lock().unwrap();if s.diagnostics.operation_id==operation{s.diagnostics.phase="cleanup-error".into();s.diagnostics.error="诊断任务无法启动，请重试清理".into();s.revision+=1;}return Err(error.to_string());}Ok(())
    }
}
pub(crate) fn defer_close(s:&mut State)->bool{
    if !s.diagnostics.busy{return false;}
    s.diagnostics.close_requested=true;s.diagnostics.flight.as_ref().unwrap().cancel.store(true,Ordering::SeqCst);
    if s.diagnostics.phase!="cleanup-error"{s.diagnostics.phase="cancelling".into();}s.window_action.clear();s.revision+=1;true
}
#[cfg(test)]
#[path="diagnostic_tests.rs"]
mod tests;
