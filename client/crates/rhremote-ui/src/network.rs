//! Address selection is a UUID preflight, never a replacement for paired TLS.
use rhremote_core::{
    Device, Profile,
    network::{self, EffectivePolicy},
};
use serde_json::{Value, json};
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
    let path = match value["path"].as_str() {
        Some("direct") => "Tailscale 直连",
        Some("relay") => "Tailscale 中继",
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
        let started = Instant::now();
        let mut candidates = network::addresses(&device.address, &device.addresses)?;
        // Explicit configured routes avoid any dependency on the CLI. Otherwise
        // discover exact host-name matches; every result must still match UUID.
        if !candidates.iter().any(|a| network::kind(a) == "tailscale") {
            for a in self.discovered(device) {
                if candidates.len() < network::MAX_ADDRESSES && !candidates.contains(&a) {
                    candidates.push(a);
                }
            }
        }
        candidates.retain(|a| network::accepts(&profile.network_mode, a));
        candidates.sort_by_key(|a| match network::kind(a) {
            "lan" => 0,
            "tailscale" => 1,
            "auto" => 2,
            _ => 3,
        });
        let (tx, rx) = mpsc::channel();
        for (index, address) in candidates.iter().enumerate() {
            let (tx, address, uuid) = (tx.clone(), address.clone(), device.uuid.clone());
            std::thread::spawn(move || {
                let start = Instant::now();
                let result = probe(&address, &uuid);
                let _ = tx.send((index, address, start.elapsed().as_millis() as u64, result));
            });
        }
        drop(tx);
        let deadline = Instant::now() + Duration::from_millis(1250);
        let mut selected: Option<(usize, String)> = None;
        let mut grace = deadline;
        let mut attempts = Vec::new();
        while let Some(wait) = deadline.min(grace).checked_duration_since(Instant::now()) {
            let Ok((index, address, ms, result)) = rx.recv_timeout(wait) else {
                break;
            };
            attempts.push(json!({"address":address,"stage":"serverinfo","elapsedMs":ms,"ok":result.is_ok(),"error":result.err()}));
            if result.is_ok() && selected.as_ref().is_none_or(|(old, _)| index < *old) {
                selected = Some((index, address));
                grace = Instant::now() + Duration::from_millis(180);
                if index == 0 {
                    break;
                }
            }
        }
        let address = selected.map(|(_, a)| a);
        let effective = address.as_ref().map(|a| network::policy(profile, a));
        *self.last.lock().unwrap() = json!({"uuid":device.uuid,"selectedAddress":address,"policy":effective,"attempts":attempts,"elapsedMs":started.elapsed().as_millis() as u64,"mediaUdp":"not_tested","path":"unknown","hint":if address.is_some(){"地址已匹配 UUID；配对证书与视频数据仍由串流阶段验证"}else{"未找到可用地址：检查两端 Tailscale、访问权限、Android 分应用 VPN 和主机防火墙"}});
        address
            .zip(effective)
            .ok_or_else(|| "所有连接地址均不可达或 UUID 不符；请打开连接设置 → 网络 → 诊断".into())
    }
    pub fn diagnose(&self, device: &Device, profile: &Profile) -> Value {
        let _ = self.resolve(device, profile);
        let mut result = self.last.lock().unwrap().clone();
        if let Some(address) = result["selectedAddress"].as_str().map(str::to_owned) {
            let mut ports = Vec::new();
            for port in [47984, 47989, 47995, 48010] {
                let start = Instant::now();
                let ok = (address.trim_matches(['[', ']']), port)
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
                    let path = if output.contains(" via DERP(") || output.contains(" via peer-relay") {
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
