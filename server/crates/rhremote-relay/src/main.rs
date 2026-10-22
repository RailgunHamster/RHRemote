//! RHRemote's self-hosted relay. Roots enroll only their paired client identities.
use anyhow::{Result, ensure};
use iroh_base::EndpointId;
use iroh_relay::server::{Access, AccessControl, ClientRequest, RelayConfig, Server, ServerConfig};
use rhremote_transport::grant::Grant;
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    io::Read,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
#[derive(Clone, Debug, Serialize, Deserialize)]
struct Enrollment {
    host: String,
    endpoint: String,
    issued: u64,
    expires: u64,
}
#[derive(Debug)]
struct Gate {
    roots: HashSet<EndpointId>,
    entries: Mutex<Vec<Enrollment>>,
    state: PathBuf,
}
impl Gate {
    fn allowed(&self, id: EndpointId) -> bool {
        self.roots.contains(&id)
            || self.entries.lock().unwrap().iter().any(|e| {
                e.endpoint == id.to_string()
                    && e.expires > now()
                    && e.host
                        .parse::<EndpointId>()
                        .is_ok_and(|h| self.roots.contains(&h))
            })
    }
    fn enroll(&self, grant: Grant) -> Result<()> {
        let host: EndpointId = grant.host_id.parse()?;
        ensure!(self.roots.contains(&host), "Unregistered host identity");
        grant.verify(now())?;
        let mut entries = self.entries.lock().unwrap();
        // Retain recently expired grants as replay tombstones for five minutes.
        entries.retain(|e| {
            e.expires.saturating_add(300) > now() || e.issued.saturating_add(300) > now()
        });
        if let Some(existing) = entries
            .iter_mut()
            .find(|e| e.host == grant.host_id && e.endpoint == grant.endpoint_id)
        {
            ensure!(grant.issued_at >= existing.issued, "Replayed older grant");
            ensure!(
                grant.issued_at != existing.issued || grant.expires_at == existing.expires,
                "Conflicting grant timestamp"
            );
            existing.issued = grant.issued_at;
            existing.expires = grant.expires_at;
        } else {
            ensure!(entries.len() < 512, "Relay enrollment capacity reached");
            entries.push(Enrollment {
                host: grant.host_id,
                endpoint: grant.endpoint_id,
                issued: grant.issued_at,
                expires: grant.expires_at,
            });
        }
        let temporary = self.state.with_extension("tmp");
        std::fs::write(&temporary, serde_json::to_vec(&*entries)?)?;
        std::fs::rename(temporary, &self.state)?;
        Ok(())
    }
}
impl AccessControl for Gate {
    async fn on_connect(&self, request: &ClientRequest) -> Access {
        if self.allowed(request.endpoint_id()) {
            Access::Allow
        } else {
            Access::Deny {
                reason: Some("Endpoint is not enrolled by a paired RHRemote host".into()),
            }
        }
    }
}
fn management(gate: Arc<Gate>, listen: &str) -> Result<()> {
    let server = tiny_http::Server::http(listen).map_err(|e| anyhow::anyhow!(e.to_string()))?;
    std::thread::spawn(move || {
        let mut rates = HashMap::<std::net::IpAddr, (u64, u32)>::new();
        for mut request in server.incoming_requests() {
            let timestamp = now();
            if rates.len() > 1024 {
                rates.retain(|_, (at, _)| *at == timestamp);
            }
            let rate = request
                .remote_addr()
                .map(|a| rates.entry(a.ip()).or_insert((timestamp, 0)));
            let limited = if let Some((at, count)) = rate {
                if *at != timestamp {
                    *at = timestamp;
                    *count = 0;
                }
                *count += 1;
                *count > 12
            } else {
                true
            };
            let result: Result<serde_json::Value> = (|| {
                ensure!(!limited, "Rate limit exceeded");
                if request.method() == &tiny_http::Method::Get && request.url() == "/health" {
                    return Ok(
                        serde_json::json!({"product":"RHRemote-Relay","version":env!("CARGO_PKG_VERSION"),"registeredHosts":gate.roots.len(),"e2eEncryption":true,"outerRelayTls":false}),
                    );
                }
                ensure!(
                    request.method() == &tiny_http::Method::Post && request.url() == "/v1/grants",
                    "Unknown endpoint"
                );
                ensure!(
                    request.body_length().is_some_and(|n| n <= 2048),
                    "Missing bounded body"
                );
                let mut body = Vec::new();
                request.as_reader().take(2049).read_to_end(&mut body)?;
                ensure!(body.len() <= 2048, "Body too large");
                gate.enroll(serde_json::from_slice(&body)?)?;
                Ok(serde_json::json!({"enrolled":true}))
            })();
            let status = if result.is_ok() { 200 } else { 403 };
            let value = match result {
                Ok(result) => serde_json::json!({"ok":true,"result":result}),
                Err(error) => serde_json::json!({"ok":false,"error":error.to_string()}),
            };
            let response = tiny_http::Response::from_string(value.to_string())
                .with_status_code(status)
                .with_header(
                    tiny_http::Header::from_bytes("Content-Type", "application/json").unwrap(),
                );
            let _ = request.respond(response);
        }
    });
    Ok(())
}
#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    let roots = PathBuf::from(
        args.get(1)
            .map(String::as_str)
            .unwrap_or("/etc/rhremote-relay/roots"),
    );
    let state = PathBuf::from(
        args.get(2)
            .map(String::as_str)
            .unwrap_or("/var/lib/rhremote-relay/enrollments.json"),
    );
    let roots: HashSet<EndpointId> = std::fs::read_to_string(roots)?
        .lines()
        .filter(|s| !s.is_empty())
        .map(str::parse)
        .collect::<std::result::Result<_, _>>()?;
    ensure!(
        !roots.is_empty() && roots.len() <= 64,
        "At least one registered host is required"
    );
    if let Some(parent) = state.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let entries: Vec<Enrollment> = if state.exists() {
        let bytes = std::fs::read(&state)?;
        ensure!(bytes.len() <= 1_048_576, "Enrollment state too large");
        serde_json::from_slice(&bytes)?
    } else {
        Vec::new()
    };
    ensure!(entries.len() <= 512, "Enrollment capacity exceeded");
    let gate = Arc::new(Gate {
        roots,
        entries: Mutex::new(entries),
        state,
    });
    management(gate.clone(), "0.0.0.0:33448")?;
    let mut relay = RelayConfig::new(([0, 0, 0, 0], 33447));
    relay.access = gate;
    let mut config = ServerConfig::default();
    config.relay = Some(relay);
    let server = Server::spawn(config).await?;
    tokio::signal::ctrl_c().await?;
    server.shutdown().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn gate(root: EndpointId) -> Gate {
        Gate {
            roots: HashSet::from([root]),
            entries: Mutex::new(Vec::new()),
            state: std::env::temp_dir().join(format!(
                "rhremote-relay-test-{}-{}.json",
                std::process::id(),
                iroh_base::SecretKey::generate().public()
            )),
        }
    }
    #[test]
    fn only_registered_hosts_enroll_and_revocation_resists_replay() {
        let root = iroh_base::SecretKey::generate();
        let other = iroh_base::SecretKey::generate();
        let peer = other.public();
        let gate = gate(root.public());
        let timestamp = now();
        assert!(
            gate.enroll(
                Grant::sign(&other, &peer.to_string(), timestamp, timestamp + 3600).unwrap()
            )
            .is_err()
        );
        let grant = Grant::sign(&root, &peer.to_string(), timestamp, timestamp + 3600).unwrap();
        gate.enroll(grant.clone()).unwrap();
        assert!(gate.allowed(peer));
        let mut forged = grant.clone();
        forged.expires_at += 1;
        assert!(gate.enroll(forged).is_err());
        gate.enroll(Grant::sign(&root, &peer.to_string(), timestamp + 1, timestamp).unwrap())
            .unwrap();
        assert!(!gate.allowed(peer));
        assert!(gate.enroll(grant).is_err());
        assert!(!gate.allowed(peer));
        let stored: Vec<Enrollment> =
            serde_json::from_slice(&std::fs::read(&gate.state).unwrap()).unwrap();
        assert_eq!(stored.len(), 1);
        assert_eq!(stored[0].expires, timestamp);
    }
}
