//! Portable endpoint validation and connection policy. Never carries credentials.
use crate::Profile;
use serde::{Deserialize, Serialize};
use std::net::IpAddr;

pub const MAX_ADDRESSES: usize = 8;

pub fn validate_address(value: &str) -> Result<(), String> {
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
    mode == "auto" || kind(address) == "auto" || mode == kind(address)
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EffectivePolicy {
    pub network: String,
    pub packet_size: u32,
    pub bitrate_mbps: u32,
}
pub fn policy(profile: &Profile, address: &str) -> EffectivePolicy {
    let network = if profile.network_mode == "auto" {
        kind(address)
    } else {
        &profile.network_mode
    };
    let remote = ["tailscale", "internet"].contains(&network);
    EffectivePolicy {
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
