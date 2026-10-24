//! Address selection is a UUID preflight, never a replacement for paired TLS.
use rhremote_core::{
    Device, Profile,
    network::{self, EffectivePolicy},
};
use serde_json::{Value, json};
#[cfg(windows)]
use crate::transport_worker::Client as TransportClient;
#[cfg(not(windows))]
use rhremote_transport::Client as TransportClient;
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::{
    net::{TcpStream, ToSocketAddrs},
    process::{Command, Stdio},
    sync::{Mutex, mpsc},
    time::{Duration, Instant},
};

#[derive(Default)]
pub struct Network {
    pub last: Mutex<Value>,
    peers: Mutex<Option<(Instant, Value)>>,
    tunnel: Mutex<Option<TransportClient>>,
}
fn tailscale() -> Option<std::path::PathBuf> {
    let path = std::path::PathBuf::from(
        std::env::var_os("ProgramFiles").unwrap_or_else(|| "C:\\Program Files".into()),
    )
    .join("Tailscale/tailscale.exe");
    path.is_file().then_some(path)
}
fn cli(args: &[&str]) -> Option<String> {
    let mut command = Command::new(tailscale()?);
    command
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let mut child = command.spawn().ok()?;
    let output = child.stdout.take()?;
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        use std::io::Read;
        let mut data = Vec::new();
        let _ = output.take(2_000_001).read_to_end(&mut data);
        let _ = tx.send(data);
    });
    let end = Instant::now() + Duration::from_millis(2600);
    loop {
        if child.try_wait().ok()?.is_some() {
            let data = rx.recv_timeout(Duration::from_millis(100)).ok()?;
            if data.len() > 2_000_000 {
                return None;
            }
            return String::from_utf8(data).ok();
        }
        if Instant::now() >= end {
            let _ = child.kill();
            let _ = child.wait();
            return None;
        }
        std::thread::sleep(Duration::from_millis(15));
    }
}
fn probe(address: &str, uuid: &str) -> Result<(), &'static str> {
    probe_at(address, uuid, 47989)
}
fn probe_at(address: &str, uuid: &str, port: u16) -> Result<(), &'static str> {
    let authority = if address.contains(':') && !address.starts_with('[') {
        format!("[{address}]")
    } else {
        address.into()
    };
    let agent = ureq::AgentBuilder::new()
        .redirects(0)
        .timeout(Duration::from_millis(1100))
        .build();
    let response = agent
        .get(&format!(
            "http://{authority}:{port}/serverinfo?uniqueid=0123456789ABCDEF"
        ))
        .call()
        .map_err(|_| "serverinfo_unreachable")?;
    if response.status() != 200 {
        return Err("serverinfo_unreachable");
    }
    use std::io::Read;
    let mut text = String::new();
    response
        .into_reader()
        .take(65537)
        .read_to_string(&mut text)
        .map_err(|_| "invalid_response")?;
    if text.len() > 65536 {
        return Err("invalid_response");
    }
    let doc = roxmltree::Document::parse(&text).map_err(|_| "invalid_response")?;
    let found = doc
        .descendants()
        .find(|n| n.has_tag_name("uniqueid"))
        .and_then(|n| n.text())
        .ok_or("uuid_missing")?;
    if !found.eq_ignore_ascii_case(uuid) {
        return Err("uuid_mismatch");
    }
    Ok(())
}
pub fn report_text(value: &Value) -> String {
    if value.is_null() {
        return "尚未诊断".into();
    }
    let address = value["selectedAddress"].as_str().unwrap_or("无可用地址");
    let path = match (value["route"].as_str(), value["path"].as_str()) {
        (Some("tailscale_direct"), Some("direct")) => "Tailscale 直连",
        (Some("tailscale_relay"), Some("relay")) => "Tailscale 中继",
        (Some("punch_direct"), Some("direct")) => "内置打洞直连",
        (Some("relay"), Some("relay")) => "RHRemote 中继",
        (Some("lan"), _) => "局域网",
        (Some("public_direct"), _) => "公网直连",
        _ => "路径未确认",
    };
    let udp = match value["host"]["mediaUdp"].as_str() {
        Some("reachable") => "诊断 UDP 双向可达（视频待串流确认）",
        Some("timeout") => "诊断 UDP 超时，请检查两端防火墙 / VPN 访问权限",
        _ => "视频 UDP 尚未验证",
    };
    let mut lines = vec![format!("地址：{address} · {path}"), udp.into()];
    for item in value["attempts"].as_array().into_iter().flatten() {
        lines.push(format!(
            "{}：{}（{} ms）",
            item["address"].as_str().unwrap_or(""),
            if item["ok"] == true {
                "UUID 匹配"
            } else {
                item["error"].as_str().unwrap_or("失败")
            },
            item["elapsedMs"]
        ));
    }
    for item in value["tcpPorts"].as_array().into_iter().flatten() {
        lines.push(format!(
            "TCP {}：{}",
            item["port"],
            if item["ok"] == true {
                "可达"
            } else {
                "不可达"
            }
        ));
    }
    if let Some(error) = value["hostError"].as_str() {
        lines.push(error.into());
    }
    lines.join("\n")
}
impl Network {
    pub fn release_transport(&self) {
        if let Some(client) = self.tunnel.lock().unwrap().as_mut() {
            client.stop();
        }
    }
    pub fn try_release_transport(&self) {
        if let Ok(mut slot) = self.tunnel.try_lock() {
            if let Some(client) = slot.as_mut() {
                client.stop();
            }
        }
    }
    pub fn native_environment(&self) -> (u16, String) {
        let last = self.last.lock().unwrap();
        let offset = last["policy"]["portOffset"].as_u64().unwrap_or(0) as u16;
        let id = last["policy"]["clientEndpointId"]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned);
        drop(last);
        let id = id
            .or_else(|| {
                let data = std::env::var_os("RHREMOTE_DATA_DIR")
                    .map(std::path::PathBuf::from)
                    .unwrap_or_else(|| {
                        std::path::PathBuf::from(
                            std::env::var_os("LOCALAPPDATA").unwrap_or_default(),
                        )
                        .join("RHRemote")
                    });
                rhremote_transport::key(&data.join("transport.key"))
                    .ok()
                    .map(|k| k.public().to_string())
            })
            .unwrap_or_default();
        (offset, id)
    }
    fn inventory(&self) -> Value {
        let mut cache = self.peers.lock().unwrap();
        if let Some((at, value)) = cache
            .as_ref()
            .filter(|(at, _)| at.elapsed() < Duration::from_secs(30))
        {
            let _ = at;
            return value.clone();
        }
        let value = cli(&["status", "--json"])
            .and_then(|s| serde_json::from_str::<Value>(&s).ok())
            .unwrap_or(Value::Null);
        *cache = Some((Instant::now(), value.clone()));
        value
    }
    pub fn discovered(&self, device: &Device) -> Vec<String> {
        let inventory = self.inventory();
        inventory["Peer"]
            .as_object()
            .into_iter()
            .flat_map(|p| p.values())
            .filter(|peer| {
                let name = peer["HostName"].as_str().unwrap_or("");
                let dns = peer["DNSName"].as_str().unwrap_or("").trim_end_matches('.');
                name.eq_ignore_ascii_case(&device.name) || dns.eq_ignore_ascii_case(&device.name)
            })
            .flat_map(|peer| {
                peer["TailscaleIPs"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(Value::as_str)
                    .map(str::to_owned)
            })
            .collect()
    }
    pub fn resolve(
        &self,
        device: &Device,
        profile: &Profile,
    ) -> Result<(String, EffectivePolicy), String> {
        self.resolve_excluding(
            device,
            profile,
            &[],
            &std::sync::atomic::AtomicBool::new(false),
        )
    }
    pub fn resolve_excluding(
        &self,
        device: &Device,
        profile: &Profile,
        failed: &[crate::recovery::FailedRoute],
        cancel: &std::sync::atomic::AtomicBool,
    ) -> Result<(String, EffectivePolicy), String> {
        let started = Instant::now();
        let mut candidates = network::addresses(&device.address, &device.addresses)?;
        candidates.retain(|a| network::accepts(&profile.network_mode, a));
        let mut attempts = Vec::new();
        let mut tailnet_paths = std::collections::HashMap::<String, String>::new();
        let mut tailnet_loaded = false;
        for route in &profile.route_order {
            if cancel.load(std::sync::atomic::Ordering::SeqCst) {
                return Err("连接已取消".into());
            }
            // A reachable LAN route must not wait for the Tailscale CLI.
            if route.starts_with("tailscale") && !tailnet_loaded {
                tailnet_loaded = true;
                if !candidates.iter().any(|a| network::kind(a) == "tailscale") {
                    for address in self.discovered(device) {
                        if candidates.len() < network::MAX_ADDRESSES
                            && !candidates.contains(&address)
                            && network::accepts(&profile.network_mode, &address)
                        {
                            candidates.push(address);
                        }
                    }
                }
            }
            if matches!(route.as_str(), "punch_direct" | "relay") {
                for address in candidates
                    .iter()
                    .filter(|a| network::transport_id(a).is_some())
                {
                    if failed
                        .iter()
                        .any(|f| f.route == *route && f.address == *address)
                    {
                        continue;
                    }
                    let attempt = Instant::now();
                    let id = network::transport_id(address).unwrap();
                    let result = (|| -> Result<_, String> {
                        let mut slot = self.tunnel.lock().unwrap();
                        if slot.is_none() {
                            *slot =
                                Some(TransportClient::new().map_err(|e| e.to_string())?);
                        }
                        let data = std::env::var_os("RHREMOTE_DATA_DIR")
                            .map(std::path::PathBuf::from)
                            .unwrap_or_else(|| {
                                std::path::PathBuf::from(
                                    std::env::var_os("LOCALAPPDATA").unwrap_or_default(),
                                )
                                .join("RHRemote")
                            });
                        let info = slot
                            .as_mut()
                            .unwrap()
                            .start_cancelable(
                                &data.join("transport.key"),
                                rhremote_transport::Address {
                                    endpoint_id: id.into(),
                                    relay_url: profile.relay_url.clone(),
                                },
                                route == "relay",
                                cancel,
                            )
                            .map_err(|e| e.to_string())?;
                        if route == "punch_direct" && info.path != "direct" {
                            return Err("打洞未形成直连，保留后续中继回退".into());
                        }
                        probe_at(&info.address, &device.uuid, 47989 + info.port_offset)
                            .map_err(str::to_owned)?;
                        let mut policy = network::policy(profile, address);
                        policy.network = "transport".into();
                        policy.packet_size = 1024;
                        policy.port_offset = info.port_offset;
                        policy.client_endpoint_id = info.endpoint_id.clone();
                        Ok((info, policy))
                    })();
                    attempts.push(json!({"address":address,"route":route,"ok":result.is_ok(),"elapsedMs":attempt.elapsed().as_millis() as u64,"error":result.as_ref().err()}));
                    if let Ok((info, policy)) = result {
                        *self.last.lock().unwrap() = json!({"uuid":device.uuid,"selectedAddress":info.address,"remoteAddress":address,"route":route,"path":info.path,"rttMs":info.rtt_ms,"policy":policy,"routeOrder":profile.route_order,"attempts":attempts,"elapsedMs":started.elapsed().as_millis() as u64,"mediaUdp":"not_tested"});
                        return Ok((info.address, policy));
                    }
                }
                continue;
            }
            let mut group = Vec::new();
            for address in candidates
                .iter()
                .filter(|a| network::transport_id(a).is_none())
            {
                if failed
                    .iter()
                    .any(|f| f.route == *route && f.address == *address)
                {
                    continue;
                }
                let kind = network::kind(address);
                if kind == "tailscale"
                    && matches!(route.as_str(), "tailscale_direct" | "tailscale_relay")
                {
                    let path = tailnet_paths.entry(address.clone()).or_insert_with(|| {
                        let output = cli(&[
                            "ping",
                            "--c",
                            "1",
                            "--timeout",
                            "1s",
                            "--until-direct=false",
                            address,
                        ])
                        .unwrap_or_default();
                        if output.contains(" via DERP(") || output.contains(" via peer-relay") {
                            "relay"
                        } else if output.contains("pong from ") && output.contains(" via ") {
                            "direct"
                        } else {
                            "unknown"
                        }
                        .into()
                    });
                    if route == "tailscale_direct" && path == "direct"
                        || route == "tailscale_relay" && path != "direct"
                    {
                        group.push(address.clone());
                    }
                } else if network::address_route(address) == route {
                    group.push(address.clone());
                }
            }
            if group.is_empty() {
                continue;
            }
            let (tx, rx) = mpsc::channel();
            for address in group {
                let (tx, uuid) = (tx.clone(), device.uuid.clone());
                std::thread::spawn(move || {
                    let at = Instant::now();
                    let result = probe(&address, &uuid);
                    let _ = tx.send((address, at.elapsed().as_millis() as u64, result));
                });
            }
            drop(tx);
            let deadline = Instant::now() + Duration::from_millis(1250);
            let mut best = None;
            let mut grace = deadline;
            while let Some(wait) = deadline.min(grace).checked_duration_since(Instant::now()) {
                if cancel.load(std::sync::atomic::Ordering::SeqCst) {
                    return Err("连接已取消".into());
                }
                let (address, ms, result) =
                    match rx.recv_timeout(wait.min(Duration::from_millis(50))) {
                        Ok(result) => result,
                        Err(mpsc::RecvTimeoutError::Timeout) => continue,
                        Err(mpsc::RecvTimeoutError::Disconnected) => break,
                    };
                attempts.push(json!({"address":address,"route":route,"elapsedMs":ms,"ok":result.is_ok(),"error":result.err()}));
                if result.is_ok() && best.as_ref().is_none_or(|(_, old)| ms < *old) {
                    best = Some((address, ms));
                    grace = Instant::now() + Duration::from_millis(50);
                }
            }
            if let Some((address, ms)) = best {
                let policy = network::policy(profile, &address);
                let path = tailnet_paths
                    .get(&address)
                    .map(String::as_str)
                    .unwrap_or("direct");
                *self.last.lock().unwrap() = json!({"uuid":device.uuid,"selectedAddress":address,"route":route,"path":path,"preflightMs":ms,"policy":policy,"routeOrder":profile.route_order,"attempts":attempts,"elapsedMs":started.elapsed().as_millis() as u64,"mediaUdp":"not_tested"});
                return Ok((address, policy));
            }
        }
        *self.last.lock().unwrap() = json!({"uuid":device.uuid,"attempts":attempts,"routeOrder":profile.route_order,"path":"unknown","elapsedMs":started.elapsed().as_millis() as u64,"mediaUdp":"not_tested"});
        Err(if failed.is_empty() {
            "已启用的连接路径均不可达或 UUID 不符；请打开连接设置 → 网络 → 诊断"
        } else {
            "本轮媒体连接失败，已无可用的备用线路；请查看网络诊断"
        }.into())
    }
    pub fn diagnose(&self, device: &Device, profile: &Profile) -> Value {
        let _ = self.resolve(device, profile);
        let mut result = self.last.lock().unwrap().clone();
        if let Some(address) = result["selectedAddress"].as_str().map(str::to_owned) {
            let mut ports = Vec::new();
            for port in [47984u16, 47989, 47995, 48010] {
                let start = Instant::now();
                let offset = result["policy"]["portOffset"].as_u64().unwrap_or(0) as u16;
                let ok = (address.trim_matches(['[', ']']), port + offset)
                    .to_socket_addrs()
                    .ok()
                    .into_iter()
                    .flatten()
                    .take(2)
                    .any(|a| TcpStream::connect_timeout(&a, Duration::from_millis(500)).is_ok());
                ports.push(
                    json!({"port":port,"ok":ok,"elapsedMs":start.elapsed().as_millis() as u64}),
                );
            }
            result["tcpPorts"] = json!(ports);
            if network::kind(&address) == "tailscale" {
                // Relay in `status` is the home region, not actual path evidence.
                if let Some(output) = cli(&[
                    "ping",
                    "--c=1",
                    "--timeout=2s",
                    "--until-direct=false",
                    &address,
                ]) {
                    let path =
                        if output.contains(" via DERP(") || output.contains(" via peer-relay") {
                            "relay"
                        } else if output.contains(" via ") && output.contains("pong from ") {
                            "direct"
                        } else {
                            "unknown"
                        };
                    result["path"] = json!(path);
                    result["pathEvidence"] =
                        json!("tailscale ping; time of diagnosis only, may change");
                }
            }
        }
        *self.last.lock().unwrap() = result.clone();
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exhausted_media_routes_are_not_reported_as_an_identity_failure() {
        let device=Device{uuid:"host".into(),name:"fixture".into(),display_name:String::new(),
            address:"192.168.10.20".into(),addresses:vec![],online:true,paired:true,is_self:false};
        let profile=Profile{route_order:vec!["lan".into()],..Default::default()};
        let network=Network::default();
        let error=network.resolve_excluding(&device,&profile,
            &[crate::recovery::FailedRoute{route:"lan".into(),address:device.address.clone()}],
            &std::sync::atomic::AtomicBool::new(false)).unwrap_err();
        assert!(error.contains("媒体连接失败"));
        assert!(!error.contains("UUID"));
        assert_eq!(network.last.lock().unwrap()["attempts"],json!([]));
    }
    #[test]
    fn refuses_another_sunshine_identity_and_reports_no_media_claim() {
        // Invalid host is rejected before networking, including URL injection.
        let d = Device {
            uuid: "host".into(),
            name: "fixture".into(),
            display_name: String::new(),
            address: "http://127.0.0.1".into(),
            addresses: vec![],
            online: false,
            paired: false,
            is_self: false,
        };
        assert!(Network::default().resolve(&d, &Profile::default()).is_err());
    }
    #[test]
    fn probes_refuse_uuid_mismatch_redirects_and_oversized_responses() {
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let port = server.server_addr().to_ip().unwrap().port();
        let worker = std::thread::spawn(move || {
            let bodies = [
                "<root><uniqueid>other</uniqueid></root>".into(),
                "<root><uniqueid>HOST</uniqueid></root>".into(),
                "x".repeat(65537),
            ];
            for body in bodies {
                server
                    .recv()
                    .unwrap()
                    .respond(tiny_http::Response::from_string(body))
                    .unwrap();
            }
            server
                .recv()
                .unwrap()
                .respond(tiny_http::Response::empty(302).with_header(
                    tiny_http::Header::from_bytes("Location", "http://127.0.0.1:1/").unwrap(),
                ))
                .unwrap();
        });
        assert_eq!(probe_at("127.0.0.1", "host", port), Err("uuid_mismatch"));
        assert_eq!(probe_at("127.0.0.1", "host", port), Ok(()));
        assert_eq!(probe_at("127.0.0.1", "host", port), Err("invalid_response"));
        assert_eq!(
            probe_at("127.0.0.1", "host", port),
            Err("serverinfo_unreachable")
        );
        worker.join().unwrap();
    }
}
