//! The tunnel is owned by the unified service; startup never waits for WAN access.
use crate::auth::SunshineClients;
use rhremote_transport::{DEFAULT_RELAY, Grants};
use serde_json::{Value, json};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
};

struct State {
    information: Mutex<Value>,
    grants: Arc<Grants>,
    config: PathBuf,
    enrollment: Mutex<std::collections::HashMap<String, (std::time::Instant, bool)>>,
}
static STATE: OnceLock<Arc<State>> = OnceLock::new();

pub fn start(config: PathBuf, clients: Arc<SunshineClients>) {
    let check_clients = clients.clone();
    let grants = match Grants::persistent(config.join("transport-grants.json"), move |cert| {
        check_clients.allowed(cert)
    }) {
        Ok(grants) => grants,
        Err(_) => Grants::new(move |cert| clients.allowed(cert)),
    };
    let state = Arc::new(State {
        information: Mutex::new(json!({"status":"starting"})),
        grants,
        config: config.clone(),
        enrollment: Mutex::new(std::collections::HashMap::new()),
    });
    if STATE.set(state.clone()).is_err() {
        return;
    }
    std::thread::spawn(move || {
        let result: Result<(), anyhow::Error> = (|| {
            let options: Value = std::fs::read(config.join("rhremote-network.json"))
                .ok()
                .and_then(|b| serde_json::from_slice(&b).ok())
                .unwrap_or(json!({}));
            if options["enabled"] == false {
                *state.information.lock().unwrap() = json!({"status":"disabled"});
                return Ok(());
            }
            let relay = options["relayUrl"]
                .as_str()
                .unwrap_or(DEFAULT_RELAY)
                .to_owned();
            let secret = rhremote_transport::key(&config.join("transport.key"))?;
            let id = secret.public().to_string();
            *state.information.lock().unwrap() =
                json!({"status":"binding","endpointId":id,"relayUrl":relay});
            tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build()?.block_on(async{
                let endpoint=rhremote_transport::endpoint(secret,&relay,false).await?;
                let renew=state.clone();
                // Enrollment contains only signed public identities. Renewal
                // continues for enabled Sunshine peers while they are offline.
                std::thread::spawn(move||loop{for id in renew.grants.enrolled(){queue_enroll(renew.clone(),id);}std::thread::sleep(std::time::Duration::from_secs(1200));});
                *state.information.lock().unwrap()=json!({"status":"listening","endpointId":id,"relayUrl":relay,"address":format!("rhr:{id}")});
                rhremote_transport::serve(endpoint,state.grants.clone()).await
            })
        })();
        if let Err(error) = result {
            *state.information.lock().unwrap() =
                json!({"status":"error","error":error.to_string()});
        }
    });
}
pub fn information() -> Value {
    STATE
        .get()
        .map(|s| s.information.lock().unwrap().clone())
        .unwrap_or(json!({"status":"unavailable"}))
}
pub fn authorize(id: &str, cert: &[u8]) -> Result<Value, String> {
    let state = STATE.get().ok_or("Tunnel service is not available")?;
    state
        .grants
        .authorize(id, cert)
        .map_err(|e| e.to_string())?;
    let enrolled = queue_enroll(state.clone(), id.to_owned());
    Ok(
        json!({"authorized":true,"endpointId":id,"expiresInSeconds":3600,"transport":information(),"relayEnrolled":enrolled}),
    )
}
fn queue_enroll(state: Arc<State>, id: String) -> bool {
    {
        let mut cache = state.enrollment.lock().unwrap();
        if let Some((at, success)) = cache.get(&id) {
            if at.elapsed() < std::time::Duration::from_secs(if *success { 600 } else { 10 }) {
                return *success;
            }
        }
        if cache.len() >= 64 && !cache.contains_key(&id) {
            return false;
        }
        cache.insert(id.clone(), (std::time::Instant::now(), false));
    }
    std::thread::spawn(move || {
        let success = enroll(&state, &id).is_ok();
        state
            .enrollment
            .lock()
            .unwrap()
            .insert(id, (std::time::Instant::now(), success));
    });
    false
}
fn enroll(state: &State, id: &str) -> Result<(), String> {
    let secret =
        rhremote_transport::key(&state.config.join("transport.key")).map_err(|e| e.to_string())?;
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_secs();
    let ticket = rhremote_transport::grant::Grant::sign(&secret, id, timestamp, timestamp + 172800)
        .map_err(|e| e.to_string())?;
    let relay = state.information.lock().unwrap()["relayUrl"]
        .as_str()
        .unwrap_or(DEFAULT_RELAY)
        .to_owned();
    let target = enrollment_url(&relay)?;
    let response = ureq::post(target.as_str())
        .timeout(std::time::Duration::from_secs(3))
        .send_json(serde_json::to_value(ticket).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    if response.status() != 200 {
        return Err("Relay enrollment rejected".into());
    }
    Ok(())
}

// HTTPS uses one public origin for media and signed enrollment. Keep the
// adjacent HTTP listener for compatibility with already deployed clients.
fn enrollment_url(relay: &str) -> Result<url::Url, String> {
    let mut target = url::Url::parse(relay).map_err(|e| e.to_string())?;
    if !matches!(target.scheme(), "http" | "https")
        || target.host_str().is_none()
        || !target.username().is_empty()
        || target.password().is_some()
        || target.query().is_some()
        || target.fragment().is_some()
        || !matches!(target.path(), "" | "/")
    {
        return Err("Relay requires an HTTP(S) origin without credentials or a path".into());
    }
    if target.scheme() == "https" {
        target.set_path("/rhremote/v1/grants");
    } else {
        let port = target.port_or_known_default().ok_or("Invalid relay port")?;
        target
            .set_port(Some(port.checked_add(1).ok_or("Invalid relay port")?))
            .map_err(|_| "Invalid relay port")?;
        target.set_path("/v1/grants");
    }
    Ok(target)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn https_enrollment_uses_the_same_origin() {
        assert_eq!(
            enrollment_url("https://relay.example/").unwrap().as_str(),
            "https://relay.example/rhremote/v1/grants"
        );
        assert_eq!(
            enrollment_url("https://101.43.203.147:33449/")
                .unwrap()
                .as_str(),
            "https://101.43.203.147:33449/rhremote/v1/grants"
        );
        assert_eq!(
            enrollment_url("https://[2001:db8::1]:8443/")
                .unwrap()
                .as_str(),
            "https://[2001:db8::1]:8443/rhremote/v1/grants"
        );
    }
    #[test]
    fn http_keeps_legacy_port_and_rejects_ambiguous_origins() {
        assert_eq!(
            enrollment_url("http://101.43.203.147:33447/")
                .unwrap()
                .as_str(),
            "http://101.43.203.147:33448/v1/grants"
        );
        for value in [
            "http://host:65535/",
            "https://user:secret@host/",
            "https://host/prefix",
            "https://host/?token=secret",
            "https://host/#fragment",
            "ftp://host/",
        ] {
            assert!(enrollment_url(value).is_err(), "{value}");
        }
    }
}
