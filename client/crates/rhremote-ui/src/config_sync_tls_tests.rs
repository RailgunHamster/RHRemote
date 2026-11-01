use super::tests::{config, fixture};
use super::*;
use std::{
    collections::{BTreeMap, VecDeque},
    io::{BufRead, BufReader, Write},
    net::{SocketAddr, TcpListener},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};
use ureq::rustls;

#[derive(Debug)]
struct Request {
    method: String,
    headers: BTreeMap<String, String>,
    body: Vec<u8>,
}
struct Reply {
    status: u16,
    body: String,
    etag: Option<&'static str>,
    location: bool,
    delay: Duration,
}
impl Reply {
    fn empty(status: u16) -> Self {
        Self {
            status,
            body: String::new(),
            etag: None,
            location: false,
            delay: Duration::ZERO,
        }
    }
    fn config(status: u16, config: &SharedConfig) -> Self {
        Self {
            body: serde_json::to_string(config).unwrap(),
            etag: Some("\"fixture-1\""),
            ..Self::empty(status)
        }
    }
    fn redirect() -> Self {
        Self {
            location: true,
            ..Self::empty(302)
        }
    }
}
struct TlsFixture {
    address: SocketAddr,
    certificate: rustls::pki_types::CertificateDer<'static>,
    requests: Arc<Mutex<Vec<Request>>>,
    stop: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}
impl TlsFixture {
    fn new(replies: Vec<Reply>) -> Self {
        let identity = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let certificate = identity.cert.der().clone();
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let server = Arc::new(
            rustls::ServerConfig::builder_with_provider(provider)
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(
                    vec![certificate.clone()],
                    rustls::pki_types::PrivatePkcs8KeyDer::from(
                        identity.signing_key.serialize_der(),
                    )
                    .into(),
                )
                .unwrap(),
        );
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let stopped = stop.clone();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let recorded = requests.clone();
        let worker = std::thread::spawn(move || {
            let mut replies = VecDeque::from(replies);
            while !stopped.load(Ordering::SeqCst) {
                let (socket, _) = match listener.accept() {
                    Ok(value) => value,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(1));
                        continue;
                    }
                    Err(error) => panic!("TLS listener failed: {error}"),
                };
                // Windows accepted sockets inherit the listener's nonblocking mode.
                socket.set_nonblocking(false).unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(10)))
                    .unwrap();
                socket
                    .set_write_timeout(Some(Duration::from_secs(10)))
                    .unwrap();
                let connection = rustls::ServerConnection::new(server.clone()).unwrap();
                let stream = rustls::StreamOwned::new(connection, socket);
                let mut reader = BufReader::new(stream);
                let mut first = String::new();
                match reader.read_line(&mut first) {
                    Ok(0) => continue,
                    Err(error) => {
                        eprintln!("TLS fixture peer closed before HTTP: {error:?}");
                        continue;
                    }
                    Ok(_) => {}
                }
                let method = first.split_whitespace().next().unwrap().to_owned();
                let mut headers = BTreeMap::new();
                loop {
                    let mut line = String::new();
                    reader.read_line(&mut line).unwrap();
                    if line.trim().is_empty() {
                        break;
                    }
                    let (key, value) = line.split_once(':').unwrap();
                    headers.insert(key.to_ascii_lowercase(), value.trim().to_owned());
                }
                let length: usize = headers
                    .get("content-length")
                    .map(|value| value.parse().unwrap())
                    .unwrap_or(0);
                assert!(length <= LIMIT as usize);
                let mut body = vec![0; length];
                reader.read_exact(&mut body).unwrap();
                recorded.lock().unwrap().push(Request {
                    method,
                    headers,
                    body,
                });
                let reply = replies.pop_front().unwrap_or_else(|| Reply::empty(500));
                std::thread::sleep(reply.delay);
                let response = format!(
                    "HTTP/1.1 {} Fixture\r\nContent-Length: {}\r\nConnection: close\r\n{}{}\r\n{}",
                    reply.status,
                    reply.body.len(),
                    reply
                        .etag
                        .map(|tag| format!("ETag: {tag}\r\n"))
                        .unwrap_or_default(),
                    if reply.location {
                        format!(
                            "Location: https://localhost:{}/redirect-target\r\n",
                            address.port()
                        )
                    } else {
                        String::new()
                    },
                    reply.body
                );
                // A timeout/certificate test may deliberately close its peer first.
                let _ = reader.get_mut().write_all(response.as_bytes());
                reader.get_mut().conn.send_close_notify();
                let _ = reader.get_mut().flush();
            }
        });
        Self {
            address,
            certificate,
            requests,
            stop,
            worker: Some(worker),
        }
    }
    fn url(&self, host: &str) -> String {
        format!("https://{host}:{}/dav", self.address.port())
    }
    fn agent(&self) -> ureq::Agent {
        self.agent_with_timeout(Duration::from_secs(5))
    }
    fn agent_with_timeout(&self, timeout: Duration) -> ureq::Agent {
        let mut roots = rustls::RootCertStore::empty();
        roots.add(self.certificate.clone()).unwrap();
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_root_certificates(roots)
        .with_no_client_auth();
        ureq::AgentBuilder::new()
            .redirects(0)
            .timeout(timeout)
            .tls_config(Arc::new(tls))
            .build()
    }
}
impl Drop for TlsFixture {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        self.worker.take().unwrap().join().unwrap();
    }
}
fn cleanup(sync: ConfigSync) {
    let root = std::env::temp_dir();
    assert!(sync.directory.starts_with(&root));
    assert!(
        sync.directory
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("rhremote-sync-")
    );
    fs::remove_dir_all(sync.directory).unwrap();
}

#[test]
fn real_https_validates_certificate_and_name_before_sending_auth() {
    let server = TlsFixture::new(vec![Reply::empty(404), Reply::empty(201)]);
    let mut sync = fixture();
    let error = sync
        .exchange(
            &server.url("localhost"),
            "Basic fixture-only",
            config(),
            Resolution::Review,
        )
        .unwrap_err();
    assert!(error.contains("证书"), "{error}");
    assert!(server.requests.lock().unwrap().is_empty());
    assert_eq!(sync.local.last_sync_ms, 0);
    // Only this Agent trusts the ephemeral fixture certificate; production defaults and OS roots stay unchanged.
    let agent = server.agent();
    let error = sync
        .exchange_with_agent(
            &agent,
            &server.url("127.0.0.1"),
            "Basic fixture-only",
            config(),
            Resolution::Review,
        )
        .unwrap_err();
    assert!(error.contains("证书"), "{error}");
    assert!(server.requests.lock().unwrap().is_empty());
    let result = sync
        .exchange_with_agent(
            &agent,
            &server.url("localhost"),
            "Basic fixture-only",
            config(),
            Resolution::Review,
        )
        .unwrap();
    assert!(result.get("shared").is_some());
    assert_eq!(sync.local.last_sync_ms,0);
    let merged:SharedConfig=serde_json::from_value(result["shared"].clone()).unwrap();
    let preferences=super::tests::preferences(&sync,&merged);
    sync.applied(&merged,&preferences).unwrap();
    assert!(sync.local.last_sync_ms > 0);
    let requests = server.requests.lock().unwrap();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0].method, "GET");
    assert_eq!(requests[1].method, "PUT");
    assert_eq!(requests[1].headers["if-none-match"], "*");
    assert_eq!(requests[1].headers["authorization"], "Basic fixture-only");
    let uploaded: SharedConfig = serde_json::from_slice(&requests[1].body).unwrap();
    assert_eq!(uploaded, config());
    drop(requests);
    cleanup(sync);
}

#[test]
fn https_conditional_failure_and_upload_redirect_never_advance_sync_state() {
    let base = config();
    let mut sync = fixture();
    sync.local.base = Some(base.clone());
    sync.local.last_sync_ms = 42;
    atomic(&sync.directory.join("webdav-local.json"), &sync.local).unwrap();
    let before = fs::read(sync.directory.join("webdav-local.json")).unwrap();
    let server = TlsFixture::new(vec![
        Reply::config(200, &base),
        Reply::empty(412),
        Reply::config(200, &base),
        Reply::redirect(),
    ]);
    let agent = server.agent();
    let mut changed = base.clone();
    changed.dark = true;
    for reason in ["412", "最终目录"] {
        let error = sync
            .exchange_with_agent(
                &agent,
                &server.url("localhost"),
                "Basic fixture-only",
                changed.clone(),
                Resolution::Review,
            )
            .unwrap_err();
        assert!(error.contains(reason), "{error}");
        assert_eq!(sync.local.base, Some(base.clone()));
        assert_eq!(sync.local.last_sync_ms, 42);
        if reason=="412"{
            assert_eq!(before,fs::read(sync.directory.join("webdav-local.json")).unwrap());
            assert_eq!(sync.state()["automaticBlocked"],false);
        }else{
            // A 3xx response is never followed and is not a confirmed rejection.
            assert_eq!(sync.state()["outcomeUncertain"],true);
            assert_eq!(ConfigSync::new(sync.directory.clone()).unwrap().state()["automaticBlocked"],true);
        }
    }
    let requests = server.requests.lock().unwrap();
    assert_eq!(requests.len(), 4);
    for request in requests.iter().filter(|r| r.method == "PUT") {
        assert_eq!(request.headers["if-match"], "\"fixture-1\"");
    }
    drop(requests);
    cleanup(sync);
}

#[test]
fn https_get_redirect_with_valid_json_and_etag_still_stops_before_upload() {
    let mut redirected = Reply::config(302, &config());
    redirected.location = true;
    let server = TlsFixture::new(vec![redirected]);
    let mut sync = fixture();
    let error = sync
        .exchange_with_agent(
            &server.agent(),
            &server.url("localhost"),
            "Basic fixture-only",
            config(),
            Resolution::Review,
        )
        .unwrap_err();
    assert!(error.contains("最终目录"), "{error}");
    assert_eq!(server.requests.lock().unwrap().len(), 1);
    assert_eq!(sync.local.last_sync_ms, 0);
    assert!(sync.local.base.is_none());
    cleanup(sync);
}

#[test]
fn https_authentication_lock_and_timeout_failures_keep_saved_state() {
    let mut delayed = Reply::empty(503);
    delayed.delay = Duration::from_secs(7);
    let server = TlsFixture::new(vec![Reply::empty(401), Reply::empty(423), delayed]);
    let mut sync = fixture();
    sync.local.last_sync_ms = 42;
    let before = serde_json::to_value(&sync.local).unwrap();
    let error = sync
        .exchange_with_agent(
            &server.agent(),
            &server.url("localhost"),
            "Basic fixture-only",
            config(),
            Resolution::Review,
        )
        .unwrap_err();
    assert!(error.contains("登录失败"), "{error}");
    let error = sync
        .exchange_with_agent(
            &server.agent(),
            &server.url("localhost"),
            "Basic fixture-only",
            config(),
            Resolution::Review,
        )
        .unwrap_err();
    assert_eq!(error, "WebDAV 文件被占用，请稍后重试（HTTP 423）");
    assert_eq!(serde_json::to_value(&sync.local).unwrap(), before);
    let error = sync
        .exchange_with_agent(
            &server.agent_with_timeout(Duration::from_secs(5)),
            &server.url("localhost"),
            "Basic fixture-only",
            config(),
            Resolution::Review,
        )
        .unwrap_err();
    assert!(error.contains("超时"), "{error}");
    assert_eq!(serde_json::to_value(&sync.local).unwrap(), before);
    assert_eq!(server.requests.lock().unwrap().len(), 3);
    cleanup(sync);
}
