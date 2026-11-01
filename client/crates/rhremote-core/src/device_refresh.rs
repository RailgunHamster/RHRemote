//! Refresh results must not replace device state changed after the request began.

use crate::{Controller,Device,DeviceObservation,State};
use serde_json::{Value,json};

struct Snapshot {devices:Vec<Device>,stream:u64,pair:u64,profiles:serde_json::Map<String,Value>,target:Option<String>}
impl Snapshot{
    fn apply(&mut self,state:&mut State,observation:DeviceObservation)->bool{
        if state.stream_epoch!=self.stream || state.pair_attempt!=self.pair{return false;}
        let uuid=&observation.device.uuid;
        if self.target.as_ref().is_some_and(|target|target!=uuid){return false;}
        if self.profiles.get(uuid)!=Some(&serde_json::to_value(state.saved.effective_profile(uuid)).unwrap()){return false;}
        let Some(index)=state.devices.iter().position(|device|&device.uuid==uuid)else{return false;};
        if !self.devices.iter().any(|before|before==&state.devices[index]){return false;}
        let mut update=observation.device;
        if !observation.pairing_verified{update.paired=state.devices[index].paired||observation.check.as_ref().is_some_and(|check|check.has_certificate);}
        update.display_name=state.saved.devices.iter().find(|stored|stored.uuid==update.uuid).map(|stored|stored.display_name.clone()).unwrap_or_default();
        if observation.pairing_verified{state.devices_pending.retain(|uuid|uuid!=&update.uuid);}else if !state.devices_pending.contains(&update.uuid){state.devices_pending.push(update.uuid.clone());}
        state.update_device_check(&update.uuid,observation.pairing_verified,observation.check);
        if let Some(expected)=self.devices.iter_mut().find(|expected|expected.uuid==update.uuid){*expected=update.clone();}
        state.devices[index]=update;true
    }
}
fn ids(result:&Value,key:&str)->Result<Option<std::collections::HashSet<String>>,String>{result.get(key).map(|value|serde_json::from_value::<Vec<String>>(value.clone()).map(|ids|ids.into_iter().collect()).map_err(|e|e.to_string())).transpose()}

impl Controller {
    pub(crate) fn refresh_devices(&self,mut args:Value)->Result<Value,String>{
        let target=args.get("uuid").map(|value|value.as_str().filter(|uuid|!uuid.is_empty()).map(str::to_owned).ok_or("设备标识无效")).transpose()?;
        let mut snapshot={
            let mut state=self.state.lock().unwrap();
            if target.as_ref().is_some_and(|uuid|!state.devices.iter().any(|device|&device.uuid==uuid)){return Err("设备不存在".into());}
            if state.devices_refreshing { return Ok(json!({"skipped":true,"refreshing":true})); }
            state.devices_refreshing=true;state.devices_refresh_error.clear();state.revision+=1;
            let profiles:serde_json::Map<String,Value>=state.devices.iter().map(|device|(device.uuid.clone(),serde_json::to_value(state.saved.effective_profile(&device.uuid)).unwrap())).collect();
            Snapshot{devices:state.devices.clone(),stream:state.stream_epoch,pair:state.pair_attempt,profiles,target}
        };
        // A list observation must use the same saved route policy as a connection.
        // Never trust caller-supplied profiles in this shared action.
        if !args.is_object(){args=json!({});}
        args["profiles"]=Value::Object(snapshot.profiles.clone());
        let mut publish=|observation|{let mut state=self.state.lock().unwrap();if snapshot.apply(&mut state,observation){state.revision+=1;}};
        let outcome=self.platform.observe_devices(&args,&mut publish).and_then(|result|{
            let rows=result.get("devices").map(|rows|serde_json::from_value::<Vec<Device>>(rows.clone()).map_err(|e|e.to_string())).transpose()?;
            let checks=result.get("checks").map(|checks|serde_json::from_value::<std::collections::BTreeMap<String,crate::DeviceCheck>>(checks.clone()).map_err(|e|e.to_string())).transpose()?.unwrap_or_default();
            Ok((rows,ids(&result,"observedUuids")?,ids(&result,"verifiedUuids")?,checks))
        });
        let mut state=self.state.lock().unwrap();state.devices_refreshing=false;state.revision+=1;
        let (refreshed,observed,verified,checks)=match outcome {Ok(rows)=>rows,Err(error)=>{state.devices_refresh_error=error.clone();return Err(error);}};
        let stale=state.stream_epoch!=snapshot.stream || state.pair_attempt!=snapshot.pair;
        if !stale {
            if let Some(refreshed)=refreshed {
                // Only records unchanged since admission can accept this observation.
                // Iterate the current list so removal, addition and edits survive.
                for row in refreshed {
                    if snapshot.target.as_ref().is_some_and(|uuid|uuid!=&row.uuid){continue;}
                    if observed.as_ref().is_some_and(|ids|!ids.contains(&row.uuid)){continue;}
                    let pairing_verified=verified.as_ref().is_none_or(|ids|ids.contains(&row.uuid));
                    let check=checks.get(&row.uuid).cloned();
                    if !snapshot.devices.iter().any(|old|old.uuid==row.uuid) && !state.devices.iter().any(|current|current.uuid==row.uuid){if !pairing_verified{state.devices_pending.push(row.uuid.clone());}state.update_device_check(&row.uuid,pairing_verified,check);state.devices.push(row);}else{snapshot.apply(&mut state,DeviceObservation{device:row,pairing_verified,check});}
                }
                let saved=state.saved.devices.clone();
                for row in &mut state.devices {row.display_name=saved.iter().find(|stored|stored.uuid==row.uuid).map(|stored|stored.display_name.clone()).unwrap_or_default();}
                state.devices.sort_by_key(|row|saved.iter().position(|stored|stored.uuid==row.uuid).unwrap_or(usize::MAX));
            }
        }
        Ok(json!({"revision":state.revision,"refreshing":false,"stale":stale}))
    }
}

#[cfg(test)]
mod tests {
    use crate::{Controller, Device, Platform, random_token};
    use serde_json::{Value, json};
    use std::sync::{Arc, Mutex, atomic::{AtomicUsize, Ordering}, mpsc};
    use std::time::Duration;

    struct Progressive {
        first:crate::DeviceObservation,later:Option<crate::DeviceObservation>,fail:bool,
        entered:mpsc::Sender<()>,release:Mutex<mpsc::Receiver<()>>,
    }
    impl Platform for Progressive{
        fn execute(&self,_:&str,_:&Value)->Result<Value,String>{Ok(json!({"accepted":true}))}
        fn observe_devices(&self,_:&Value,publish:&mut dyn FnMut(crate::DeviceObservation))->Result<Value,String>{
            publish(self.first.clone());self.entered.send(()).unwrap();self.release.lock().unwrap().recv_timeout(Duration::from_secs(4)).unwrap();
            if let Some(later)=&self.later{publish(later.clone());}if self.fail{Err("其余设备检查超时".into())}else{Ok(json!({}))}
        }
    }
    fn progressive(first:crate::DeviceObservation,later:Option<crate::DeviceObservation>,fail:bool)->(Arc<Controller>,mpsc::Receiver<()>,mpsc::Sender<()>,std::path::PathBuf){
        let(entered,started)=mpsc::channel();let(release,released)=mpsc::channel();let platform=Arc::new(Progressive{first,later,fail,entered,release:Mutex::new(released)});
        let path=std::env::temp_dir().join(format!("rhremote-progress-{}.json",random_token().unwrap()));let c=Controller::new("test",path.clone(),platform).unwrap();
        c.replace_devices_pending(vec![device("host",false,false),device("other",false,false)]);(c,started,release,path)
    }
    #[test]
    fn the_first_verified_device_is_connectable_while_the_rest_are_still_being_checked(){
        let(c,started,release,path)=progressive(crate::DeviceObservation{device:device("host",true,true),pairing_verified:true,check:None},None,true);
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};started.recv_timeout(Duration::from_secs(2)).unwrap();
        let during=c.state();let accepted=c.dispatch("device.connect",json!({"uuid":"host"}));
        release.send(()).unwrap();let result=worker.join().unwrap();
        assert_eq!(during["devicesRefreshing"],true);assert_eq!(during["devicesPending"],json!(["other"]));assert_eq!(during["devices"][0]["paired"],true);assert_eq!(accepted["ok"],true);
        assert_eq!(result["ok"],false);assert_eq!(c.state()["devicesPending"],json!(["other"]));assert_eq!(c.state()["devices"][0]["paired"],true);assert_eq!(c.state()["devicesRefreshing"],false);
        if path.exists(){std::fs::remove_file(path).unwrap();}
    }
    #[test]
    fn an_unverified_failure_preserves_pairing_and_does_not_enable_automatic_repair(){
        let(c,started,release,path)=progressive(crate::DeviceObservation{device:device("host",false,false),pairing_verified:false,check:None},None,false);
        c.replace_devices(vec![device("host",true,true),device("other",false,false)]);
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};started.recv_timeout(Duration::from_secs(2)).unwrap();
        let during=c.state();let repair=c.dispatch("device.pair",json!({"uuid":"host"}));release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);
        assert_eq!(during["devices"][0]["paired"],true);assert_eq!(during["devices"][0]["online"],false);assert!(during["devicesPending"].as_array().unwrap().contains(&json!("host")));assert_eq!(repair["ok"],false);
        if path.exists(){std::fs::remove_file(path).unwrap();}
    }
    #[test]
    fn metadata_can_follow_early_pairing_evidence_but_cannot_undo_a_later_address_edit(){
        for edit in [false,true]{
            let first=device("host",true,true);let mut later=first.clone();later.addresses=vec!["100.64.0.9".into()];
            let(c,started,release,path)=progressive(crate::DeviceObservation{device:first,pairing_verified:true,check:None},Some(crate::DeviceObservation{device:later,pairing_verified:true,check:None}),false);
            let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};started.recv_timeout(Duration::from_secs(2)).unwrap();
            assert_eq!(c.state()["devices"][0]["paired"],true);
            if edit{assert_eq!(c.dispatch("device.network.configure",json!({"uuid":"host","address":"192.0.2.44","addresses":[]}))["ok"],true);}
            release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);let state=c.state();
            if path.exists(){std::fs::remove_file(path).unwrap();}
            if edit{assert_eq!(state["devices"][0]["address"],"192.0.2.44");assert_eq!(state["devices"][0]["addresses"],json!([]));}
            else{assert_eq!(state["devices"][0]["addresses"],json!(["100.64.0.9"]),"the same check's optional metadata was rejected as if it were an obsolete external result");}
        }
    }
    #[test]
    fn structured_checks_separate_rejected_identity_and_temporarily_unverified_local_trust(){
        use crate::{CheckOutcome as O,CheckSource as S,DeviceCheck};
        for (outcome,source,certificate,verified,previous,paired,pending,blocked) in [
            (O::AuthRejected,S::Http,true,true,true,false,false,false),
            (O::CertificateMismatch,S::Http,true,false,true,true,true,false),
            (O::TlsFailure,S::Https,true,false,true,true,true,false),
            (O::Unpaired,S::Http,false,true,true,false,false,false),
            (O::Unavailable,S::Https,true,false,false,true,true,false),
            (O::IdentityMismatch,S::Https,true,false,true,true,true,true),
        ]{
            let check=DeviceCheck{phase:crate::CheckPhase::ServerInfo,outcome,source,has_certificate:certificate,elapsed_us:1000};
            let(c,started,release,path)=progressive(crate::DeviceObservation{device:device("host",true,false),pairing_verified:verified,check:Some(check)},None,false);
            c.replace_devices(vec![device("host",true,previous),device("other",false,false)]);
            let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};started.recv_timeout(Duration::from_secs(2)).unwrap();
            let state=c.state();release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);
            assert_eq!(state["devices"][0]["paired"],paired,"{outcome:?}");assert_eq!(state["devicesPending"].as_array().unwrap().contains(&json!("host")),pending,"{outcome:?}");
            assert_eq!(state["deviceChecks"]["host"]["outcome"],serde_json::to_value(outcome).unwrap());assert!(state["saved"].get("deviceChecks").is_none());
            if blocked{
                assert_eq!(c.dispatch("device.connect",json!({"uuid":"host"}))["ok"],false);
                assert_eq!(c.dispatch("device.network.configure",json!({"uuid":"host","address":"192.0.2.44","addresses":[]}))["ok"],true);
                assert!(c.state()["deviceChecks"].get("host").is_none());
            }
            if path.exists(){std::fs::remove_file(path).unwrap();}
        }
    }
    #[test]
    fn a_late_progress_event_does_not_verify_a_device_after_its_saved_route_changes(){
        let(c,started,release,path)=progressive(crate::DeviceObservation{device:device("other",true,true),pairing_verified:true,check:None},Some(crate::DeviceObservation{device:device("host",true,true),pairing_verified:true,check:None}),false);
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};started.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_eq!(c.dispatch("device.settings.open",json!({"uuid":"host"}))["ok"],true);assert_eq!(c.dispatch("settings.set",json!({"key":"networkMode","value":"tailscale"}))["ok"],true);assert_eq!(c.dispatch("settings.save",json!({}))["ok"],true);
        release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);let state=c.state();assert_eq!(state["devices"][0]["paired"],false);assert_eq!(state["devicesPending"],json!(["host"]));assert_eq!(c.profile("host").network_mode,"tailscale");
        if path.exists(){std::fs::remove_file(path).unwrap();}
    }
    #[test]
    fn checking_one_device_cannot_verify_or_update_the_other_saved_devices(){
        let(c,host,started,release,path)=fixture(vec![device("host",true,true),device("other",true,true)]);
        c.replace_devices_pending(vec![device("host",false,false),device("other",false,false)]);
        assert_eq!(c.dispatch("devices.refresh",json!({"uuid":"missing"}))["ok"],false);assert_eq!(host.calls.load(Ordering::SeqCst),0);assert_eq!(c.state()["devicesRefreshing"],false);
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({"uuid":"host"})))};started.recv_timeout(Duration::from_secs(2)).unwrap();release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);
        let state=c.state();assert_eq!(state["devices"][0]["paired"],true);assert_eq!(state["devices"][1]["paired"],false);assert_eq!(state["devicesPending"],json!(["other"]));std::fs::remove_file(path).unwrap();
    }

    struct Host {
        result: Mutex<Result<Value,String>>,
        gate: Mutex<Option<(mpsc::Sender<()>,mpsc::Receiver<()>)>>,
        calls: AtomicUsize,
        requests: Mutex<Vec<Value>>,
    }
    impl Platform for Host {
        fn execute(&self,id:&str,args:&Value)->Result<Value,String>{
            if id!="devices.refresh" { return Ok(json!({"accepted":true})); }
            self.requests.lock().unwrap().push(args.clone());
            self.calls.fetch_add(1,Ordering::SeqCst);
            let gate=self.gate.lock().unwrap().take();
            if let Some((entered,released))=gate { entered.send(()).unwrap();released.recv_timeout(Duration::from_secs(5)).map_err(|e|e.to_string())?; }
            self.result.lock().unwrap().clone()
        }
    }
    fn device(id:&str,online:bool,paired:bool)->Device {
        Device{uuid:id.into(),name:id.into(),display_name:String::new(),address:"192.0.2.1".into(),addresses:Vec::new(),online,paired,is_self:false}
    }
    fn fixture(rows:Vec<Device>)->(Arc<Controller>,Arc<Host>,mpsc::Receiver<()>,mpsc::Sender<()>,std::path::PathBuf){
        let(entered,started)=mpsc::channel();let(release,released)=mpsc::channel();
        let host=Arc::new(Host{result:Mutex::new(Ok(json!({"devices":rows}))),gate:Mutex::new(Some((entered,released))),calls:AtomicUsize::new(0),requests:Mutex::new(Vec::new())});
        let path=std::env::temp_dir().join(format!("rhremote-refresh-{}.json",random_token().unwrap()));
        let c=Controller::new("test",path.clone(),host.clone()).unwrap();c.replace_devices(vec![device("host",false,false),device("other",false,false)]);
        (c,host,started,release,path)
    }
    #[test]
    fn refresh_started_before_pairing_cannot_revoke_the_new_pairing(){
        let(c,_,started,release,path)=fixture(vec![device("host",false,false),device("other",true,false)]);
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};
        started.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_eq!(c.dispatch("device.pair",json!({"uuid":"host","pin":"1234"}))["ok"],true);
        let attempt=c.state.lock().unwrap().pair_attempt;c.pairing_complete(attempt,device("host",true,true),String::new(),false);
        release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);
        let state=c.state();assert_eq!(state["connection"]["phase"],"paired");
        assert_eq!(state["devices"][0]["paired"],true,"an older refresh revoked a completed pairing");assert_eq!(state["devices"][0]["online"],true);
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn refresh_preserves_removed_and_new_devices_and_updates_untouched_rows(){
        let(c,_,started,release,path)=fixture(vec![device("host",true,false),device("other",true,false)]);
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};
        started.recv_timeout(Duration::from_secs(2)).unwrap();
        c.replace_devices(vec![device("other",false,false),device("new",true,true)]);
        let before=c.state();release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);
        let state=c.state();let ids:Vec<_>=state["devices"].as_array().unwrap().iter().map(|d|d["uuid"].as_str().unwrap()).collect();
        assert_eq!(ids,vec!["other","new"],"an older result resurrected or lost a device");assert_eq!(state["devices"][0]["online"],true);assert_eq!(state["devices"][1]["paired"],true);assert_eq!(state["selected"],before["selected"]);
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn refresh_does_not_replace_a_device_address_edited_while_probing(){
        let(c,_,started,release,path)=fixture(vec![device("host",true,false),device("other",true,false)]);
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};
        started.recv_timeout(Duration::from_secs(2)).unwrap();
        let mut edited=device("host",true,true);edited.address="100.64.0.2".into();edited.addresses=vec!["host.example.test".into()];edited.name="new-hostname".into();
        c.replace_devices(vec![edited.clone(),device("other",false,false)]);
        release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);
        let state=c.state();let actual:Device=serde_json::from_value(state["devices"][0].clone()).unwrap();assert_eq!(actual,edited);assert_eq!(state["devices"][1]["online"],true);
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn refresh_failure_clears_busy_and_preserves_the_list_for_a_real_retry(){
        let(c,host,started,release,path)=fixture(vec![device("host",true,true),device("other",true,false)]);
        *host.result.lock().unwrap()=Err("设备状态请求超时".into());
        let before=c.state();let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};
        started.recv_timeout(Duration::from_secs(2)).unwrap();let busy=c.state()["devicesRefreshing"].clone();
        release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],false);
        let state=c.state();assert_eq!(busy,true);assert_eq!(state["devicesRefreshing"],false);assert_eq!(state["devicesRefreshError"],"设备状态请求超时");assert_eq!(state["devices"],before["devices"]);assert_eq!(state["saved"],before["saved"]);
        *host.result.lock().unwrap()=Ok(json!({"devices":[device("host",true,true),device("other",true,false)]}));
        assert_eq!(c.dispatch("devices.refresh",json!({}))["ok"],true);assert_eq!(c.state()["devicesRefreshError"],"");assert_eq!(c.state()["devices"][0]["paired"],true);
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn concurrent_refresh_requests_share_the_pending_probe_instead_of_starting_another(){
        let(c,host,started,release,path)=fixture(vec![device("host",true,true),device("other",true,false)]);
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({})))};
        started.recv_timeout(Duration::from_secs(2)).unwrap();let duplicate=c.dispatch("devices.refresh",json!({}));let probes=host.calls.load(Ordering::SeqCst);
        release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);
        assert_eq!(duplicate["ok"],true);assert_eq!(duplicate["result"]["skipped"],true);assert_eq!(probes,1);assert_eq!(c.state()["devicesRefreshing"],false);
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn refresh_uses_each_saved_policy_and_discards_observations_after_a_policy_change(){
        let(c,host,started,release,path)=fixture(vec![device("host",true,true),device("other",true,false)]);
        assert_eq!(c.dispatch("device.settings.open",json!({"uuid":"host"}))["ok"],true);
        assert_eq!(c.dispatch("settings.set",json!({"key":"networkMode","value":"tailscale"}))["ok"],true);
        assert_eq!(c.dispatch("settings.save",json!({}))["ok"],true);
        let expected=serde_json::to_value(c.profile("host")).unwrap();
        let worker={let c=c.clone();std::thread::spawn(move||c.dispatch("devices.refresh",json!({"profiles":{"host":{"networkMode":"lan"}}})))};
        started.recv_timeout(Duration::from_secs(2)).unwrap();
        let request=host.requests.lock().unwrap()[0].clone();
        assert_eq!(c.dispatch("device.settings.open",json!({"uuid":"host"}))["ok"],true);
        assert_eq!(c.dispatch("settings.set",json!({"key":"networkMode","value":"lan"}))["ok"],true);
        assert_eq!(c.dispatch("settings.save",json!({}))["ok"],true);
        release.send(()).unwrap();assert_eq!(worker.join().unwrap()["ok"],true);
        assert_eq!(request["profiles"]["host"],expected,"caller input overrode the saved route policy");
        assert_eq!(request["profiles"]["other"],serde_json::to_value(c.profile("other")).unwrap());
        assert_eq!(c.state()["devices"][0]["online"],false,"an old route observation replaced the new policy's unverified state");
        assert_eq!(c.state()["devices"][1]["online"],true);
        assert_eq!(c.profile("host").network_mode,"lan");std::fs::remove_file(path).unwrap();
    }
}
