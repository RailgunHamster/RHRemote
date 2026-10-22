//! Paired-only network discovery and single-use UDP reachability tickets.
use serde_json::{Value, json};
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::{
    net::{IpAddr, UdpSocket},
    process::{Command, Stdio},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

pub struct Probe {
    socket: Option<UdpSocket>,
    tickets: Mutex<Vec<(IpAddr, Vec<u8>, Instant)>>,
}
impl Probe {
    pub fn start(listen: &str) -> Arc<Self> {
        let socket = UdpSocket::bind(listen).ok();
        if let Some(s) = &socket {
            let _ = s.set_read_timeout(Some(Duration::from_millis(250)));
        }
        let service = Arc::new(Self {
            socket,
            tickets: Mutex::new(Vec::new()),
        });
        if service.socket.is_some() {
            let weak = Arc::downgrade(&service);
            std::thread::spawn(move || {
                let mut buffer = [0u8; 128];
                while let Some(service) = weak.upgrade() {
                    let socket = service.socket.as_ref().unwrap();
                    if let Ok((length, peer)) = socket.recv_from(&mut buffer) {
                        if service.consume(peer.ip(), &buffer[..length]) {
                            let _ = socket.send_to(&buffer[..length], peer);
                        }
                    }
                }
            });
        }
        service
    }
    pub fn ticket(&self, peer: IpAddr) -> Result<Value, String> {
        let socket = self
            .socket
            .as_ref()
            .ok_or("UDP diagnostic listener unavailable")?;
        let mut random = [0u8; 16];
        rustls::crypto::ring::default_provider()
            .secure_random
            .fill(&mut random)
            .map_err(|_| "Random generator unavailable")?;
        let token = format!(
            "RHRNET1:{}",
            random
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>()
        );
        let mut tickets = self.tickets.lock().unwrap();
        tickets.retain(|(ip, _, at)| *ip != peer && at.elapsed() < Duration::from_secs(8));
        if tickets.len() >= 32 {
            return Err("Too many pending diagnostics".into());
        }
        tickets.push((peer, token.as_bytes().to_vec(), Instant::now()));
        Ok(
            json!({"token":token,"udpPort":socket.local_addr().map_err(|e|e.to_string())?.port(),"expiresSeconds":8}),
        )
    }
    fn consume(&self, peer: IpAddr, packet: &[u8]) -> bool {
        let mut tickets = self.tickets.lock().unwrap();
        tickets.retain(|(_, _, at)| at.elapsed() < Duration::from_secs(8));
        if let Some(index) = tickets
            .iter()
            .position(|(ip, token, _)| *ip == peer && token == packet)
        {
            tickets.swap_remove(index);
            true
        } else {
            false
        }
    }
}
pub fn information() -> Value {
    let executable = std::path::PathBuf::from(
        std::env::var_os("ProgramFiles").unwrap_or_else(|| "C:\\Program Files".into()),
    )
    .join("Tailscale/tailscale.exe");
    let mut command = Command::new(executable);
    command
        .args(["status", "--json"])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let mut value = Value::Null;
    if let Ok(mut child) = command.spawn() {
        let (tx, rx) = std::sync::mpsc::channel();
        if let Some(output) = child.stdout.take() {
            std::thread::spawn(move || {
                use std::io::Read;
                let mut data = Vec::new();
                let _ = output.take(2_000_001).read_to_end(&mut data);
                let _ = tx.send(data);
            });
        }
        let end = Instant::now() + Duration::from_secs(2);
        loop {
            match child.try_wait() {
                Ok(Some(_)) => {
                    if let Ok(data) = rx.recv_timeout(Duration::from_millis(100)) {
                        if data.len() <= 2_000_000 {
                            value = serde_json::from_slice(&data).unwrap_or(Value::Null);
                        }
                    }
                    break;
                }
                Ok(None) if Instant::now() < end => std::thread::sleep(Duration::from_millis(15)),
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                    break;
                }
            }
        }
    }
    let addresses: Vec<String> = value["Self"]["TailscaleIPs"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .filter(|s| s.parse::<IpAddr>().is_ok())
        .map(str::to_owned)
        .collect();
    json!({"addresses":addresses,"tailscaleRunning":value["BackendState"]=="Running","relayPolicy":"tailscale-managed","mediaUdp":"not_tested"})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn udp_ticket_is_bound_to_source_and_single_use() {
        let probe = Probe::start("127.0.0.1:0");
        let ip = "127.0.0.1".parse().unwrap();
        let ticket = probe.ticket(ip).unwrap();
        let token = ticket["token"].as_str().unwrap().as_bytes();
        assert!(!probe.consume("127.0.0.2".parse().unwrap(), token));
        assert!(!probe.consume(ip, b"unsolicited"));
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        client
            .set_read_timeout(Some(Duration::from_millis(500)))
            .unwrap();
        client
            .send_to(token, format!("127.0.0.1:{}", ticket["udpPort"]))
            .unwrap();
        let mut data = [0u8; 128];
        let n = client.recv(&mut data).unwrap();
        assert_eq!(&data[..n], token);
        assert!(!probe.consume(ip, token));
    }
    #[test]
    fn diagnostics_are_bounded_and_do_not_amplify_unknown_traffic() {
        let probe = Probe::start("127.0.0.1:0");
        for i in 1..=32 {
            probe.ticket(IpAddr::from([127, 0, 0, i])).unwrap();
        }
        assert!(probe.ticket(IpAddr::from([127, 0, 0, 33])).is_err());
        assert!(!probe.consume(IpAddr::from([127, 0, 0, 1]), b"RHRNET1:wrong"));
    }
}
