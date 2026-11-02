use crate::*;
use serde_json::json;
use std::sync::Arc;
struct Host;
impl Platform for Host {fn execute(&self,_:&str,_:&Value)->Result<Value,String>{Ok(json!({}))}}
#[test]
fn dragging_devices_keeps_drafts_and_syncs_the_complete_order_after_restart(){
    let path=std::env::temp_dir().join(format!("rhremote-drag-{}.json",random_token().unwrap()));
    let c=Controller::new("windows",path.clone(),Arc::new(Host)).unwrap();
    let rows:Vec<Device>=serde_json::from_value(json!([
        {"uuid":"a","name":"A","address":"a.local","online":true,"paired":true,"isSelf":true},
        {"uuid":"b","name":"B","address":"b.local","online":true,"paired":true},
        {"uuid":"c","name":"C","address":"c.local","online":true,"paired":true}
    ])).unwrap();c.replace_devices(rows.clone());
    assert_eq!(c.dispatch("device.settings.open",json!({"uuid":"b"}))["ok"],true);
    c.dispatch("settings.input",json!({"key":"fps","value":"23"}));let draft=c.state()["profileDraftId"].clone();
    assert_eq!(c.dispatch("device.move",json!({"uuid":"a","targetUuid":"c","placement":"after"}))["ok"],true);
    let state=c.state();assert_eq!(state["selected"],"b");assert_eq!(state["page"],"settings");assert_eq!(state["profileDraftId"],draft);assert_eq!(state["settingsInputs"]["fps"],"23");
    assert_eq!(c.export_shared().devices.iter().map(|d|d.uuid.as_str()).collect::<Vec<_>>(),["b","c","a"]);
    assert_eq!(c.profile("b").fps,30,"Sorting committed an unrelated draft");
    let saved=std::fs::read(&path).unwrap();for args in [json!({"uuid":"a","targetUuid":"missing","placement":"before"}),json!({"uuid":"a","targetUuid":"b","placement":"invalid"})]{assert_eq!(c.dispatch("device.move",args)["ok"],false);assert_eq!(std::fs::read(&path).unwrap(),saved);}
    let restart=Controller::new("windows",path.clone(),Arc::new(Host)).unwrap();restart.replace_devices(rows);assert_eq!(restart.state()["devices"][0]["uuid"],"b");assert_eq!(restart.state()["devices"][2]["uuid"],"a");std::fs::remove_file(path).unwrap();
}
#[test]
fn dragging_routes_is_draft_only_and_rejects_a_replaced_draft(){
    let path=std::env::temp_dir().join(format!("rhremote-route-drag-{}.json",random_token().unwrap()));let c=Controller::new("windows",path.clone(),Arc::new(Host)).unwrap();
    c.replace_devices(serde_json::from_value(json!([{"uuid":"a","name":"A","address":"a.local","online":true,"paired":true},{"uuid":"b","name":"B","address":"b.local","online":true,"paired":true}])).unwrap());
    c.dispatch("device.settings.open",json!({"uuid":"a"}));let original=c.state()["draft"]["routeOrder"].clone();let source=original[0].as_str().unwrap();let target=original[original.as_array().unwrap().len()-1].as_str().unwrap();let old=c.state()["profileDraftId"].clone();
    assert_eq!(c.dispatch("settings.route.move",json!({"route":source,"targetRoute":target,"placement":"after","draftId":old}))["ok"],true);
    assert_eq!(c.state()["draft"]["routeOrder"].as_array().unwrap().last().unwrap(),source);assert_eq!(json!(c.profile("a").route_order),original);
    c.dispatch("settings.cancel",json!({}));c.dispatch("device.settings.open",json!({"uuid":"b"}));let before=c.state();assert_eq!(c.dispatch("settings.route.move",json!({"route":source,"targetRoute":target,"placement":"after","draftId":old}))["ignored"],true);assert_eq!(c.state(),before);if path.exists(){std::fs::remove_file(path).unwrap();}
}
