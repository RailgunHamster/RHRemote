//! Portable endpoint validation and connection policy. Never carries credentials.
use crate::Profile;
use serde::{Deserialize, Serialize};
use std::net::IpAddr;

pub const MAX_ADDRESSES: usize = 8;
pub const DEFAULT_RELAY: &str = "http://101.43.203.147:33447/";
pub const ROUTES: &[(&str,&str)] = &[("lan","局域网"),("public_direct","公网直连"),("punch_direct","内置打洞直连"),("tailscale_direct","Tailscale 直连"),("relay","自建中继"),("tailscale_relay","Tailscale 中继 / 未确认路径")];
pub fn default_route_order()->Vec<String>{ROUTES.iter().map(|(id,_)|(*id).into()).collect()}
pub fn validate_routes(order:&[String], relay:&str)->Result<(),String>{
    if order.is_empty()||order.len()>ROUTES.len()||order.iter().enumerate().any(|(i,s)|!ROUTES.iter().any(|(id,_)|s==id)||order[..i].contains(s)){return Err("连接路径至少启用一项，且不能重复或包含未知类型".into());}
    let parsed=url::Url::parse(relay).map_err(|_|"中继服务器地址无效")?;
    if !matches!(parsed.scheme(),"http"|"https")||parsed.host_str().is_none()||!parsed.username().is_empty()||parsed.password().is_some()||parsed.query().is_some()||parsed.fragment().is_some()||parsed.path()!="/"{return Err("中继地址须为 HTTP(S) 服务器根地址，不包含凭据、查询或路径".into());}
    Ok(())
}
pub fn transport_id(address:&str)->Option<&str>{address.strip_prefix("rhr:").filter(|s|s.len()==64&&s.bytes().all(|b|b.is_ascii_hexdigit()))}

pub fn validate_address(value: &str) -> Result<(), String> {
    if transport_id(value).is_some(){return Ok(());}
    if value.is_empty()
        || value.len() > 253
        || value != value.trim()
        || value.chars().any(|c| c.is_control() || c.is_whitespace())
        || value.contains(['/', '\\', '@', '?', '#', '%'])
    {
        return Err("请输入 IP 或主机名，不包含协议、端口或路径".into());
    }
    let bare = value
        .strip_prefix('[')
        .and_then(|s| s.strip_suffix(']'))
        .unwrap_or(value);
    if bare.parse::<IpAddr>().is_ok() {
        return Ok(());
    }
    if value.contains([':', '[', ']'])
        || !value.is_ascii()
        || value.split('.').any(|label| {
            label.is_empty()
                || label.len() > 63
                || label.starts_with('-')
                || label.ends_with('-')
                || !label
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || c == b'-')
        })
    {
        return Err("IP 或主机名无效".into());
    }
    Ok(())
}

pub fn addresses(primary: &str, extra: &[String]) -> Result<Vec<String>, String> {
    let mut result = Vec::<String>::new();
    for value in std::iter::once(primary).chain(extra.iter().map(String::as_str)) {
        validate_address(value)?;
        if !result.iter().any(|old| old.eq_ignore_ascii_case(value)) {
            result.push(value.into());
        }
    }
    if result.len() > MAX_ADDRESSES {
        return Err("每台设备最多保存 8 个地址".into());
    }
    Ok(result)
}

/// Keep saved preference order while remembering fresh, UUID-verified routes.
pub fn merge_extra(primary: &str, saved: &[String], observed: &[String]) -> Vec<String> {
    let mut result = Vec::<String>::new();
    for a in saved.iter().chain(observed) {
        if result.len() >= MAX_ADDRESSES - 1 {
            break;
        }
        if validate_address(a).is_ok()
            && !a.eq_ignore_ascii_case(primary)
            && !result.iter().any(|old| old.eq_ignore_ascii_case(a))
        {
            result.push(a.clone());
        }
    }
    result
}

pub fn kind(address: &str) -> &'static str {
    if transport_id(address).is_some(){return "transport";}
    let bare = address.trim_matches(['[', ']']);
    match bare.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) if ip.octets()[0] == 100 && (64..=127).contains(&ip.octets()[1]) => {
            "tailscale"
        }
        Ok(IpAddr::V6(ip)) if ip.segments()[..3] == [0xfd7a, 0x115c, 0xa1e0] => "tailscale",
        Ok(IpAddr::V4(ip)) if ip.is_private() || ip.is_loopback() || ip.is_link_local() => "lan",
        Ok(IpAddr::V6(ip))
            if ip.is_loopback() || ip.is_unique_local() || ip.is_unicast_link_local() =>
        {
            "lan"
        }
        Ok(_) => "internet",
        Err(_) => "auto",
    }
}

pub fn accepts(mode: &str, address: &str) -> bool {
    mode == "auto" || kind(address) == "auto" || mode == kind(address) || mode=="internet"&&kind(address)=="transport"
}
pub fn route_rank(profile:&Profile, route:&str)->Option<usize>{profile.route_order.iter().position(|r|r==route)}
pub fn address_route(address:&str)->&'static str{match kind(address){"lan"|"auto"=>"lan","tailscale"=>"tailscale_relay","transport"=>"punch_direct",_=>"public_direct"}}

#[cfg(test)]mod route_tests{
    use super::*;
    #[test]fn order_is_explicit_unique_and_nonempty(){let mut p=Profile::default();assert!(p.validate().is_ok());p.route_order=vec!["relay".into(),"lan".into()];assert!(p.validate().is_ok());assert_eq!(route_rank(&p,"relay"),Some(0));assert_eq!(route_rank(&p,"public_direct"),None);p.route_order.push("relay".into());assert!(p.validate().is_err());p.route_order.clear();assert!(p.validate().is_err());}
    #[test]fn relay_settings_reject_credentials_and_paths(){for url in ["http://127.0.0.1:33447/","https://relay.example.com"]{assert!(validate_routes(&default_route_order(),url).is_ok());}for url in ["file:///tmp/relay","https://user:secret@relay.example.com","http://relay.example.com/admin","https://relay.example.com/?token=secret"]{assert!(validate_routes(&default_route_order(),url).is_err());}}
    #[test]fn transport_addresses_sync_without_private_material(){let id="a".repeat(64);let address=format!("rhr:{id}");assert!(validate_address(&address).is_ok());assert_eq!(transport_id(&address),Some(id.as_str()));assert_eq!(kind(&address),"transport");assert!(accepts("internet",&address));assert!(!accepts("lan",&address));assert!(validate_address("rhr:invalid").is_err());let p=Profile::default();let mut stored=serde_json::to_value(p).unwrap();stored["routeOrder"]=serde_json::json!(["relay","lan"]);let profile:Profile=serde_json::from_value(stored).unwrap();assert_eq!(profile.route_order,["relay","lan"]);assert!(profile.validate().is_ok());}
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EffectivePolicy {
    pub network: String,
    pub packet_size: u32,
    pub bitrate_mbps: u32,
    pub port_offset: u16,
    pub client_endpoint_id: String,
}
pub fn policy(profile: &Profile, address: &str) -> EffectivePolicy {
    let network = if profile.network_mode == "auto" {
        kind(address)
    } else {
        &profile.network_mode
    };
    let remote = ["tailscale", "internet", "transport"].contains(&network);
    EffectivePolicy {
        port_offset: 0,
        client_endpoint_id: String::new(),
        network: network.into(),
        // Zero delegates route/VPN detection to the native client. A tailnet
        // destination is conservative even if its underlay is the local LAN.
        packet_size: if profile.packet_size_auto {
            if remote { 1024 } else { 0 }
        } else {
            profile.packet_size
        },
        bitrate_mbps: if remote && profile.remote_bitrate_mbps > 0 {
            profile.bitrate_mbps.min(profile.remote_bitrate_mbps)
        } else {
            profile.bitrate_mbps
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn endpoints_are_bounded_and_cannot_inject_urls_or_ports() {
        for value in [
            "100.73.139.109",
            "winserver",
            "pc.example.com",
            "[fd7a:115c:a1e0::1]",
        ] {
            validate_address(value).unwrap();
        }
        for value in [
            "",
            "pc:47989",
            "http://pc",
            "pc/path",
            "pc@other",
            "pc\nother",
            " a ",
            "pc%zone",
            "[abc]",
            "pc..com",
        ] {
            assert!(validate_address(value).is_err(), "{value}");
        }
        assert_eq!(
            addresses("PC", &["pc".into(), "100.73.139.109".into()])
                .unwrap()
                .len(),
            2
        );
        assert!(addresses("pc", &(0..8).map(|i| format!("pc{i}")).collect::<Vec<_>>()).is_err());
    }
    #[test]
    fn vpn_policy_does_not_overwrite_quality_and_manual_override_is_explicit() {
        let mut p = Profile::default();
        assert_eq!(policy(&p, "192.168.0.104").packet_size, 0);
        assert_eq!(policy(&p, "100.73.139.109").packet_size, 1024);
        assert_eq!(policy(&p, "100.73.139.109").bitrate_mbps, 25);
        p.remote_bitrate_mbps = 15;
        assert_eq!(policy(&p, "100.73.139.109").bitrate_mbps, 15);
        assert_eq!(policy(&p, "192.168.0.104").bitrate_mbps, 25);
        p.packet_size_auto = false;
        p.packet_size = 1200;
        assert_eq!(policy(&p, "100.73.139.109").packet_size, 1200);
        assert_eq!(p.bitrate_mbps, 25);
    }
}
