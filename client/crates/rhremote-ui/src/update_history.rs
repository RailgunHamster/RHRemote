use serde_json::{Value, json};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicU64, Ordering},
};
type Snapshot = Arc<Mutex<Value>>;

pub(crate) struct History {
    live: Snapshot,
    generation: Arc<AtomicU64>,
    cache: Arc<Mutex<Vec<rhremote_core::release_history::Release>>>,
}
impl History {
    pub(crate) fn new(live: Snapshot) -> Self {
        let cache = Arc::new(Mutex::new(rhremote_core::release_history::bundled()));
        live.lock().unwrap()["history"] = json!({"expanded":false,"expandedVersions":[],"busy":false,"loaded":false,"items":[],"error":"","provider":""});
        Self {
            live,
            generation: Arc::new(AtomicU64::new(0)),
            cache,
        }
    }
    pub(crate) fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
        let mut live = self.live.lock().unwrap();
        let h = &mut live["history"];
        match id {
            "updates.history.toggle" => {
                h["expanded"] = json!(!h["expanded"].as_bool().unwrap_or(false));
                h["items"] = if h["expanded"] == true {
                    json!(*self.cache.lock().unwrap())
                } else {
                    json!([])
                };
            }
            "updates.history.entry.toggle" => {
                if h["expanded"] != true {
                    return Err("请先展开历史更新记录".into());
                }
                let version = args["version"].as_str().ok_or("请选择历史版本")?;
                if !h["items"]
                    .as_array()
                    .is_some_and(|items| items.iter().any(|item| item["version"] == version))
                {
                    return Err("历史版本不存在".into());
                }
                let mut expanded = h["expandedVersions"]
                    .as_array()
                    .cloned()
                    .unwrap_or_default();
                if let Some(index) = expanded.iter().position(|v| v == version) {
                    expanded.remove(index);
                } else {
                    expanded.push(json!(version));
                }
                h["expandedVersions"] = json!(expanded);
            }
            "updates.history.refresh" => {
                if h["expanded"] != true {
                    return Err("请先展开历史更新记录".into());
                }
                if h["busy"] == true {
                    return Err("历史记录正在加载".into());
                }
                h["loaded"] = json!(false);
            }
            _ => return Err("unknown history action".into()),
        }
        let refresh = h["expanded"] == true
            && h["busy"] != true
            && h["loaded"] != true
            && id != "updates.history.entry.toggle";
        drop(live);
        if refresh {
            self.refresh();
        }
        Ok(self.live.lock().unwrap()["history"].clone())
    }
    pub(crate) fn source_changed(&self) {
        self.generation.fetch_add(1, Ordering::SeqCst);
        let refresh = {
            let mut live = self.live.lock().unwrap();
            let h = &mut live["history"];
            h["loaded"] = json!(false);
            h["busy"] = json!(false);
            h["provider"] = json!("");
            h["expanded"] == true
        };
        if refresh {
            self.refresh();
        }
    }
    fn refresh(&self) {
        let (preferred, proxy, serial) = {
            let mut live = self.live.lock().unwrap();
            if live["history"]["busy"] == true {
                return;
            }
            live["history"]["busy"] = json!(true);
            live["history"]["error"] = json!("");
            (
                live["preferredSource"].as_str().unwrap_or("cnb").to_owned(),
                live["proxy"].clone(),
                self.generation.fetch_add(1, Ordering::SeqCst) + 1,
            )
        };
        let live = self.live.clone();
        let generation = self.generation.clone();
        let cache = self.cache.clone();
        std::thread::spawn(move || {
            let result = fetch(&preferred, &proxy, None);
            let mut live = live.lock().unwrap();
            if generation.load(Ordering::SeqCst) != serial {
                return;
            }
            let h = &mut live["history"];
            h["busy"] = json!(false);
            match result {
                Ok((items, provider)) => {
                    h["items"] = if h["expanded"] == true {
                        json!(items)
                    } else {
                        json!([])
                    };
                    *cache.lock().unwrap() = items;
                    h["provider"] = json!(provider);
                    h["loaded"] = json!(true);
                    h["error"] = json!("");
                }
                Err(error) => {
                    h["error"] = json!(format!("{error}；显示已保存的记录。"));
                }
            }
        });
    }
}
fn fetch(
    preferred: &str,
    proxy: &Value,
    test_base: Option<&str>,
) -> Result<(Vec<rhremote_core::release_history::Release>, String), String> {
    if rustls::crypto::CryptoProvider::get_default().is_none() {
        let _ = rustls::crypto::ring::default_provider().install_default();
    }
    let settings = if proxy.is_null() {
        rhremote_core::proxy::Settings::default()
    } else {
        rhremote_core::proxy::Settings::from_form(proxy)?
    };
    let mut builder = reqwest::Client::builder()
        .no_proxy()
        .https_only(test_base.is_none())
        .timeout(std::time::Duration::from_secs(15))
        .user_agent(concat!("RHRemote/", env!("CARGO_PKG_VERSION")));
    if let Some(address) = settings.for_scope("updates") {
        builder = builder.proxy(reqwest::Proxy::all(address).map_err(|_| "更新代理地址无效")?);
    }
    let client = builder.build().map_err(|_| "历史记录网络初始化失败")?;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "历史记录网络初始化失败")?;
    runtime.block_on(async {
        let sources = if preferred == "github" {
            ["github", "cnb"]
        } else {
            ["cnb", "github"]
        };
        for source in sources {
            let base = if source == "github" {
                "https://github.com/RailgunHamster/RHRemote/releases/"
            } else {
                "https://cnb.cool/railgunhamster/RHRemote/-/releases/"
            };
            let url = test_base
                .map(|b| format!("{b}{source}/releases.history.json"))
                .unwrap_or_else(|| format!("{base}latest/download/releases.history.json"));
            let result = async {
                let mut response = client
                    .get(url)
                    .send()
                    .await
                    .and_then(reqwest::Response::error_for_status)
                    .map_err(|_| "历史记录请求失败")?;
                let mut bytes = Vec::new();
                while let Some(chunk) = response.chunk().await.map_err(|_| "历史记录读取失败")?
                {
                    if bytes.len() + chunk.len() > rhremote_core::release_history::LIMIT {
                        return Err("历史记录过大".into());
                    }
                    bytes.extend_from_slice(&chunk);
                }
                rhremote_core::release_history::parse(&bytes)
            }
            .await;
            if let Ok(items) = result {
                return Ok((items, source.into()));
            }
        }
        Err("历史更新记录暂时无法刷新".into())
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn expanding_is_independent_from_update_installation_and_entries_require_real_versions() {
        let live = Arc::new(Mutex::new(
            json!({"phase":"downloading","candidateId":["kept"],"history":{}}),
        ));
        let history = History::new(live.clone());
        assert_eq!(live.lock().unwrap()["history"]["expanded"], false);
        assert_eq!(live.lock().unwrap()["history"]["items"], json!([]));
        live.lock().unwrap()["history"]["loaded"] = json!(true);
        history
            .execute("updates.history.toggle", &json!({}))
            .unwrap();
        history
            .execute("updates.history.entry.toggle", &json!({"version":"0.2.17"}))
            .unwrap();
        assert_eq!(live.lock().unwrap()["phase"], "downloading");
        assert_eq!(live.lock().unwrap()["candidateId"], json!(["kept"]));
        history
            .execute("updates.history.toggle", &json!({}))
            .unwrap();
        assert!(
            history
                .execute("updates.history.entry.toggle", &json!({"version":"0.2.17"}))
                .is_err()
        );
    }
    #[test]
    fn failed_primary_history_source_falls_back_without_credentials() {
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", server.server_addr());
        let worker = std::thread::spawn(move || {
            for index in 0..2 {
                let request = server.recv().unwrap();
                assert_eq!(
                    request.url(),
                    if index == 0 {
                        "/cnb/releases.history.json"
                    } else {
                        "/github/releases.history.json"
                    }
                );
                request
                    .respond(if index == 0 {
                        tiny_http::Response::from_string("unavailable").with_status_code(503)
                    } else {
                        tiny_http::Response::from_string(include_str!(
                            "../../../shared/release-history.json"
                        ))
                    })
                    .unwrap();
            }
        });
        let (items, source) = fetch("cnb", &Value::Null, Some(&base)).unwrap();
        assert_eq!(source, "github");
        assert!(!items.is_empty());
        worker.join().unwrap();
    }
}
