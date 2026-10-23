//! Attempt-scoped cancellation. Native code signs; Rust never reads its private key.
use serde::Deserialize;
use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
    time::Duration,
};

pub(crate) struct Cancellation {
    id: String,
    path: PathBuf,
    endpoint: url::Url,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Proof {
    pair_id: String,
    salt: String,
    signature: String,
}
fn hex(value: &str) -> bool {
    value.bytes().all(|b| b.is_ascii_hexdigit())
}
impl Cancellation {
    pub fn new(data: &Path, address: &str, offset: u16) -> Result<Self, String> {
        rhremote_core::network::validate_address(address)?;
        let id = rhremote_core::random_token()?;
        let port = 47989u16
            .checked_add(if address == "127.77.0.2" { offset } else { 0 })
            .ok_or("Invalid pairing port offset")?;
        let authority = if address.contains(':') && !address.starts_with('[') {
            format!("[{address}]")
        } else {
            address.into()
        };
        let endpoint = url::Url::parse(&format!("http://{authority}:{port}/pair"))
            .map_err(|e| e.to_string())?;
        Ok(Self {
            path: data.join(format!("pair-cancel-{id}.json")),
            id,
            endpoint,
        })
    }
    pub fn id(&self) -> &str {
        &self.id
    }
    pub fn path(&self) -> &Path {
        &self.path
    }
    fn read_proof(&self) -> Option<Proof> {
        if fs::metadata(&self.path).ok()?.len() > 8192 {
            return None;
        }
        let proof: Proof = serde_json::from_slice(&fs::read(&self.path).ok()?).ok()?;
        (proof.pair_id == self.id
            && proof.salt.len() == 32
            && hex(&proof.salt)
            && (128..=4096).contains(&proof.signature.len())
            && proof.signature.len() % 2 == 0
            && hex(&proof.signature))
        .then_some(proof)
    }
    fn send(mut endpoint: url::Url, proof: Proof) {
        let mut information = endpoint.clone();
        information.set_path("/serverinfo");
        information.set_query(Some("uniqueid=0123456789ABCDEF"));
        endpoint.query_pairs_mut().extend_pairs([
            ("uniqueid", proof.pair_id.as_str()),
            ("phrase", "rhcancel"),
            ("rhcancel_salt", proof.salt.as_str()),
            ("rhcancel_sig", proof.signature.as_str()),
        ]);
        let agent = ureq::AgentBuilder::new()
            .try_proxy_from_env(false)
            .redirects(0)
            .timeout(Duration::from_secs(1))
            .build();
        // Old Sunshine does not recognize rhcancel. Use only its advertised
        // extension; fresh IDs still allow independent retries on legacy hosts.
        let supported = agent
            .get(information.as_str())
            .call()
            .ok()
            .and_then(|response| {
                let mut body = String::new();
                response
                    .into_reader()
                    .take(65537)
                    .read_to_string(&mut body)
                    .ok()?;
                if body.len() > 65536 {
                    return None;
                }
                let doc = roxmltree::Document::parse(&body).ok()?;
                let root = doc.root_element();
                Some(
                    root.has_tag_name("root")
                        && root.attribute("status_code") == Some("200")
                        && root.children().any(|node| {
                            node.has_tag_name("RHPairingCancel") && node.text() == Some("1")
                        }),
                )
            })
            .unwrap_or(false);
        if !supported {
            return;
        }
        // A just-written initial request may still be entering the host event loop.
        // A delayed proof never matches the new attempt's random ID and salt.
        for attempt in 0..3 {
            if let Ok(response) = agent.get(endpoint.as_str()).call() {
                if response
                    .into_string()
                    .ok()
                    .is_some_and(|body| body.contains("<cancelled>1</cancelled>"))
                {
                    return;
                }
            }
            if attempt < 2 {
                std::thread::sleep(Duration::from_millis(200));
            }
        }
    }
    pub fn finish(self, cancelled: bool) {
        let proof = if cancelled { self.read_proof() } else { None };
        // This exact generated file is owned by the completed native child.
        let _ = fs::remove_file(&self.path);
        if let Some(proof) = proof {
            std::thread::spawn(move || Self::send(self.endpoint, proof));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn attempts_are_unique_and_proofs_cannot_be_reused_for_another_attempt() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-pair-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir(&root).unwrap();
        let a = Cancellation::new(&root, "192.0.2.1", 0).unwrap();
        let b = Cancellation::new(&root, "192.0.2.1", 0).unwrap();
        assert_ne!(a.id(), b.id());
        let proof = json!({"pairId":a.id(),"salt":"00112233445566778899aabbccddeeff","signature":"a".repeat(512)});
        fs::write(a.path(), proof.to_string()).unwrap();
        fs::write(b.path(), proof.to_string()).unwrap();
        assert!(a.read_proof().is_some());
        assert!(b.read_proof().is_none());
        a.finish(false);
        b.finish(true);
        fs::remove_dir(root).unwrap();
    }
    #[test]
    fn cancellation_is_scoped_to_the_selected_endpoint_and_attempt() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-pair-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir(&root).unwrap();
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let endpoint = url::Url::parse(&format!("http://{}/pair", server.server_addr())).unwrap();
        let mut op = Cancellation::new(&root, "127.0.0.1", 0).unwrap();
        op.endpoint = endpoint;
        fs::write(op.path(),json!({"pairId":op.id(),"salt":"00112233445566778899aabbccddeeff","signature":"a".repeat(512)}).to_string()).unwrap();
        let path = op.path().to_owned();
        let id = op.id().to_owned();
        op.finish(true);
        let request = server
            .recv_timeout(Duration::from_secs(3))
            .unwrap()
            .unwrap();
        assert!(request.url().starts_with("/serverinfo?"));
        request
            .respond(tiny_http::Response::from_string(
                "<root status_code=\"200\"><RHPairingCancel>1</RHPairingCancel></root>",
            ))
            .unwrap();
        let request = server
            .recv_timeout(Duration::from_secs(3))
            .unwrap()
            .unwrap();
        let query = url::Url::parse(&format!("http://localhost{}", request.url())).unwrap();
        let params: std::collections::HashMap<_, _> = query.query_pairs().into_owned().collect();
        assert_eq!(params["uniqueid"], id);
        assert_eq!(params["phrase"], "rhcancel");
        request
            .respond(tiny_http::Response::from_string(
                "<root><cancelled>1</cancelled></root>",
            ))
            .unwrap();
        assert!(!path.exists());
        fs::remove_dir(root).unwrap();
    }
    #[test]
    fn signed_cancellation_does_not_follow_redirects() {
        let origin = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let target = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let endpoint = url::Url::parse(&format!("http://{}/pair", origin.server_addr())).unwrap();
        let worker = std::thread::spawn(move || {
            Cancellation::send(
                endpoint,
                Proof {
                    pair_id: "a".repeat(64),
                    salt: "b".repeat(32),
                    signature: "c".repeat(512),
                },
            )
        });
        for _ in 0..1 {
            let request = origin
                .recv_timeout(Duration::from_secs(3))
                .unwrap()
                .unwrap();
            let redirect = tiny_http::Header::from_bytes(
                "Location",
                format!("http://{}/pair", target.server_addr()),
            )
            .unwrap();
            request
                .respond(tiny_http::Response::empty(302).with_header(redirect))
                .unwrap();
        }
        worker.join().unwrap();
        assert!(target.try_recv().unwrap().is_none());
    }
    #[test]
    fn legacy_hosts_receive_no_cancel_extension() {
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let endpoint = url::Url::parse(&format!("http://{}/pair", server.server_addr())).unwrap();
        let worker = std::thread::spawn(move || {
            Cancellation::send(
                endpoint,
                Proof {
                    pair_id: "a".repeat(64),
                    salt: "b".repeat(32),
                    signature: "c".repeat(512),
                },
            )
        });
        let request = server
            .recv_timeout(Duration::from_secs(3))
            .unwrap()
            .unwrap();
        assert!(request.url().starts_with("/serverinfo?"));
        request
            .respond(tiny_http::Response::from_string(
                "<root status_code=\"200\"><appversion>7.1.431.-1</appversion></root>",
            ))
            .unwrap();
        worker.join().unwrap();
        assert!(server.try_recv().unwrap().is_none());
    }
    #[test]
    fn tunnel_port_mapping_is_exact_and_direct_addresses_keep_their_port() {
        let root = std::env::temp_dir();
        assert_eq!(
            Cancellation::new(&root, "127.77.0.2", 2000)
                .unwrap()
                .endpoint
                .port(),
            Some(49989)
        );
        assert_eq!(
            Cancellation::new(&root, "192.0.2.1", 2000)
                .unwrap()
                .endpoint
                .port(),
            Some(47989)
        );
        assert!(Cancellation::new(&root, "127.77.0.2", u16::MAX).is_err());
        assert!(Cancellation::new(&root, "user@example.com", 0).is_err());
    }
}
