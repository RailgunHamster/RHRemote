use super::*;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};

const TOKEN: &str = "<1793160000000000000>";
#[derive(Default)]
struct State {
    data: Option<Vec<u8>>,
    locked: bool,
    class_one: bool,
    busy: bool,
    materialize: bool,
    fail_put: bool,
    fail_unlock: bool,
    malformed_token: bool,
    revision: u64,
    methods: Vec<String>,
    conditions: Vec<(Option<String>, Option<String>)>,
}
struct Server {
    state: Arc<Mutex<State>>,
    stop: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
    address: String,
}
impl Server {
    fn new(state: State) -> Self {
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let address = format!("http://{}/rhremote-config-v1.json", server.server_addr());
        let state = Arc::new(Mutex::new(state));
        let owned = state.clone();
        let stop = Arc::new(AtomicBool::new(false));
        let stopping = stop.clone();
        let worker = std::thread::spawn(move || {
            while !stopping.load(Ordering::SeqCst) {
                let Some(mut request) = server.recv_timeout(Duration::from_millis(50)).unwrap()
                else {
                    continue;
                };
                let method = request.method().as_str().to_owned();
                let get = |name: &'static str| {
                    request
                        .headers()
                        .iter()
                        .find(|h| h.field.equiv(name))
                        .map(|h| h.value.as_str().to_owned())
                };
                assert_eq!(get("Authorization").as_deref(), Some("Basic owned-test"));
                let condition = get("If");
                let etag = get("If-Match");
                let token = get("Lock-Token");
                let mut bytes = Vec::new();
                request
                    .as_reader()
                    .take(LIMIT + 1)
                    .read_to_end(&mut bytes)
                    .unwrap();
                let mut state = owned.lock().unwrap();
                state.methods.push(method.clone());
                let mut code = 200;
                let mut data = Vec::new();
                let mut headers = Vec::<(&str, String)>::new();
                match method.as_str() {
                    "OPTIONS" => {
                        headers.push(("DAV", if state.class_one { "1" } else { "1, 2" }.into()))
                    }
                    "LOCK" => {
                        if state.busy || state.locked {
                            code = 423;
                        } else {
                            state.locked = true;
                            if state.materialize && state.data.is_none() {
                                state.data = Some(Vec::new());
                                code = 201;
                            }
                            headers.push((
                                "Lock-Token",
                                if state.malformed_token {
                                    "<urn:bad> <urn:two>"
                                } else {
                                    TOKEN
                                }
                                .into(),
                            ));
                        }
                    }
                    "GET" => match state.data.as_ref() {
                        Some(value) => {
                            data = value.clone();
                            headers.push(("ETag", format!("\"revision-{}\"", state.revision)));
                        }
                        None => code = 404,
                    },
                    "PUT" => {
                        // Deliberately ignores HTTP If-Match, like the observed service.
                        // A class-2 caller must supply the currently held DAV token.
                        state.conditions.push((condition.clone(), etag));
                        if !state.class_one
                            && (!state.locked
                                || condition.as_deref() != Some(&format!("({TOKEN})")))
                        {
                            code = 423;
                        } else if state.fail_put {
                            code = 412;
                        } else {
                            state.data = Some(bytes);
                            state.revision += 1;
                            code = 201;
                        }
                    }
                    "UNLOCK" => {
                        assert_eq!(token.as_deref(), Some(TOKEN));
                        if state.fail_unlock {
                            code = 503;
                        } else {
                            state.locked = false;
                            code = 204;
                        }
                    }
                    _ => panic!("unexpected DAV operation"),
                }
                drop(state);
                let mut response = tiny_http::Response::from_data(data).with_status_code(code);
                for (name, value) in headers {
                    response.add_header(tiny_http::Header::from_bytes(name, value).unwrap());
                }
                request.respond(response).unwrap();
            }
        });
        Self {
            state,
            stop,
            worker: Some(worker),
            address,
        }
    }
    fn document(value: &SharedConfig) -> Self {
        Self::new(State {
            data: Some(serde_json::to_vec(value).unwrap()),
            ..Default::default()
        })
    }
    fn methods(&self) -> Vec<String> {
        self.state.lock().unwrap().methods.clone()
    }
}
impl Drop for Server {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(worker) = self.worker.take() {
            let result = worker.join();
            if !std::thread::panicking() {
                result.unwrap();
            }
        }
    }
}
struct Fixture(ConfigSync);
impl Fixture {
    fn new() -> Self {
        Self(super::tests::fixture())
    }
    fn run(
        &mut self,
        server: &Server,
        local: SharedConfig,
        resolution: Resolution,
    ) -> Result<Value, String> {
        let agent = ureq::AgentBuilder::new()
            .try_proxy_from_env(false)
            .redirects(0)
            .timeout(Duration::from_secs(3))
            .build();
        self.0.exchange_negotiated(
            &agent,
            &server.address,
            "Basic owned-test",
            local,
            resolution,
        )
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let path = self.0.directory.canonicalize().unwrap();
        let temp = std::env::temp_dir().canonicalize().unwrap();
        assert!(
            path.starts_with(&temp)
                && path
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .starts_with("rhremote-sync-")
        );
        fs::remove_dir_all(path).unwrap();
    }
}

#[test]
fn class_two_round_trips_use_the_lease_and_cache_only_capability_discovery() {
    let config = super::tests::config();
    let server = Server::document(&config);
    let mut f = Fixture::new();
    let result = f.run(&server, config.clone(), Resolution::Review).unwrap();
    assert!(result["shared"].is_object());
    assert_eq!(
        server.methods(),
        ["OPTIONS", "LOCK", "GET", "PUT", "UNLOCK"]
    );
    let file = super::tests::preferences(&f.0, &config);
    f.0.applied(&config, &file).unwrap();
    f.run(&server, config, Resolution::Review).unwrap();
    assert_eq!(
        server.methods(),
        [
            "OPTIONS", "LOCK", "GET", "PUT", "UNLOCK", "LOCK", "GET", "PUT", "UNLOCK"
        ]
    );
    let state = server.state.lock().unwrap();
    assert!(!state.locked);
    assert!(
        state
            .conditions
            .iter()
            .all(|(token, etag)| token.as_deref() == Some(&format!("({TOKEN})")) && etag.is_some())
    );
}

#[test]
fn a_new_locked_empty_resource_is_initialized_without_weakening_normal_json_validation() {
    let server = Server::new(State {
        materialize: true,
        ..Default::default()
    });
    let mut f = Fixture::new();
    f.run(&server, super::tests::config(), Resolution::Review)
        .unwrap();
    let state = server.state.lock().unwrap();
    let document: SharedConfig = serde_json::from_slice(state.data.as_ref().unwrap()).unwrap();
    document.validate().unwrap();
    assert!(!state.locked);
    assert_eq!(state.conditions[0].1.as_deref(), Some("\"revision-0\""));
    drop(state);
    let invalid = Server::new(State {
        data: Some(b"invalid json".to_vec()),
        ..Default::default()
    });
    let mut failed = Fixture::new();
    assert!(
        failed
            .run(&invalid, super::tests::config(), Resolution::Review)
            .unwrap_err()
            .contains("格式无效")
    );
    assert_eq!(invalid.methods(), ["OPTIONS", "LOCK", "GET", "UNLOCK"]);
    assert!(!invalid.state.lock().unwrap().locked);
}

#[test]
fn conflict_review_releases_the_lock_and_rechecks_a_changed_remote_before_resolving() {
    let base = super::tests::config();
    let mut local = base.clone();
    local.devices[0].display_name = "本机修改".into();
    let mut remote = base.clone();
    remote.devices[0].display_name = "远端修改".into();
    let server = Server::document(&remote);
    let mut f = Fixture::new();
    f.0.local.base = Some(base);
    f.0.local.last_sync_ms = 1;
    let reviewed = f.run(&server, local.clone(), Resolution::Review).unwrap();
    assert!(!reviewed["conflicts"].as_array().unwrap().is_empty());
    assert!(!server.state.lock().unwrap().locked);
    assert!(!server.methods().contains(&"PUT".into()));
    remote.devices[0].display_name = "远端又修改".into();
    {
        let mut state = server.state.lock().unwrap();
        state.data = Some(serde_json::to_vec(&remote).unwrap());
        state.revision += 1;
    }
    assert!(
        f.run(&server, local, Resolution::Remote)
            .unwrap_err()
            .contains("配置已变化")
    );
    assert_eq!(f.0.local.last_sync_ms, 1);
    assert!(!server.methods().contains(&"PUT".into()));
    assert!(!server.state.lock().unwrap().locked);
}

#[test]
fn failed_unlock_after_a_confirmed_put_preserves_an_unapplied_pending_result() {
    let config = super::tests::config();
    let server = Server::new(State {
        data: Some(serde_json::to_vec(&config).unwrap()),
        fail_unlock: true,
        ..Default::default()
    });
    let mut f = Fixture::new();
    let error = f.run(&server, config, Resolution::Review).unwrap_err();
    assert!(error.contains("未能释放"));
    assert!(!error.contains(TOKEN));
    assert_eq!(f.0.local.last_sync_ms, 0);
    assert!(f.0.local.base.is_none());
    assert_eq!(f.0.state()["automaticBlocked"], true);
    let reopened = ConfigSync::new(f.0.directory.clone()).unwrap();
    assert_eq!(reopened.state()["automaticBlocked"], true);
    assert_eq!(
        server
            .methods()
            .iter()
            .filter(|v| v.as_str() == "UNLOCK")
            .count(),
        1
    );
}

#[test]
fn lock_contention_or_rejected_put_never_advances_the_merge_base() {
    for busy in [true, false] {
        let config = super::tests::config();
        let server = Server::new(State {
            data: Some(serde_json::to_vec(&config).unwrap()),
            busy,
            fail_put: !busy,
            ..Default::default()
        });
        let mut f = Fixture::new();
        let error = f.run(&server, config, Resolution::Review).unwrap_err();
        assert!(error.contains(if busy {
            "另一台设备"
        } else {
            "重新同步"
        }));
        assert_eq!(f.0.local.last_sync_ms, 0);
        assert!(f.0.local.base.is_none());
        assert!(f.0.awaiting.is_none());
        assert_eq!(server.methods().contains(&"UNLOCK".into()), !busy);
    }
}

#[test]
fn class_one_keeps_conditional_writes_without_introducing_lock_requests() {
    let config = super::tests::config();
    let server = Server::new(State {
        data: Some(serde_json::to_vec(&config).unwrap()),
        class_one: true,
        ..Default::default()
    });
    let mut f = Fixture::new();
    f.run(&server, config, Resolution::Review).unwrap();
    assert_eq!(server.methods(), ["OPTIONS", "GET", "PUT"]);
    assert_eq!(
        server.state.lock().unwrap().conditions[0],
        (None, Some("\"revision-0\"".into()))
    );
}

#[test]
fn malformed_lock_header_is_rejected_without_exposing_or_using_its_value() {
    let config = super::tests::config();
    let server = Server::new(State {
        data: Some(serde_json::to_vec(&config).unwrap()),
        malformed_token: true,
        ..Default::default()
    });
    let mut f = Fixture::new();
    let error = f.run(&server, config, Resolution::Review).unwrap_err();
    assert!(error.contains("凭据"));
    assert!(!error.contains("urn:bad"));
    assert_eq!(server.methods(), ["OPTIONS", "LOCK"]);
    assert!(f.0.awaiting.is_none());
}
