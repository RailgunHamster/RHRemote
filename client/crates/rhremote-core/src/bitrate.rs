//! Starting-rate selection. Live congestion feedback is a separate capability.
use crate::Profile;

pub(crate) fn is_manual(value:&bool)->bool{!*value}

/// A conservative starting budget, never a measurement of available bandwidth.
/// A resolved route takes precedence over a hostname or a loopback tunnel proxy.
pub fn initial_mbps(profile:&Profile,address:&str,route:Option<&str>)->u32{
    let kind=crate::network::kind(address);
    let remote=route.map_or(kind!="lan"||address=="127.77.0.2",|r|r!="lan");
    let nominal=((u64::from(profile.width)*u64::from(profile.height)*u64::from(profile.fps)*25).div_ceil(1920*1080*30)).clamp(1,25)as u32;
    let cap=if matches!(route,Some("relay"|"tailscale_relay"))||address=="127.77.0.2"&&route.is_none(){3}else if remote{6}else{25};
    let selected=if profile.bitrate_auto{nominal.min(cap)}else{profile.bitrate_mbps};
    if remote&&profile.remote_bitrate_mbps>0{selected.min(profile.remote_bitrate_mbps)}else{selected}
}

#[cfg(test)]
mod tests {
    use crate::{Profile, network, sync_model::ClientProfile};
    use serde_json::{Value,json};

    #[test]
    fn starting_rate_matches_cross_client_cases_and_preserves_manual_configuration() {
        let cases:Vec<Value>=serde_json::from_str(include_str!("../../../shared/bitrate-cases.json")).unwrap();
        for case in cases {
            let profile=Profile{width:case["width"].as_u64().unwrap_or(1920)as u32,height:case["height"].as_u64().unwrap_or(1080)as u32,
                bitrate_auto:case["auto"].as_bool().unwrap_or(true),bitrate_mbps:case["manual"].as_u64().unwrap_or(25)as u32,
                remote_bitrate_mbps:case["remoteCap"].as_u64().unwrap_or(0)as u32,..Profile::default()};
            let before=profile.clone();
            assert_eq!(network::policy_for_route(&profile,case["address"].as_str().unwrap(),case["route"].as_str()).bitrate_mbps,
                case["expected"].as_u64().unwrap()as u32,"{}",case["name"]);
            assert_eq!(profile,before);
        }
    }

    #[test]
    fn automatic_preference_roundtrips_and_manual_profiles_keep_the_legacy_schema() {
        let manual=Profile::default();let legacy=serde_json::to_value(ClientProfile::from_profile(&manual)).unwrap();
        assert!(legacy.get("bitrateAuto").is_none());assert!(!serde_json::from_value::<ClientProfile>(legacy.clone()).unwrap().bitrate_auto);
        let auto=Profile{bitrate_auto:true,bitrate_mbps:41,..manual};let encoded=serde_json::to_value(ClientProfile::from_profile(&auto)).unwrap();
        assert_eq!(encoded["bitrateAuto"],true);assert_eq!(serde_json::from_value::<ClientProfile>(encoded.clone()).unwrap().to_profile(),auto);
        for invalid in [json!("true"),json!(1),Value::Null]{let mut value=encoded.clone();value["bitrateAuto"]=invalid;assert!(serde_json::from_value::<ClientProfile>(value).is_err());}
        let fields=crate::settings_catalog();let fields=fields.as_array().unwrap();let at=fields.iter().position(|f|f["key"]=="bitrateAuto").unwrap();
        assert_eq!(fields[at+1]["key"],"bitrateMbps");assert_eq!(fields.iter().filter(|f|f["key"]=="bitrateAuto").count(),1);
    }
}
