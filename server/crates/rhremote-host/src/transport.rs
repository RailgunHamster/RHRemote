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
    let target = url::Url::parse(&relay).map_err(|e| e.to_string())?;
    // The signed enrollment listener is adjacent to the relay port. No secrets
    // are put in its URL or HTTP body; the signature binds the root and client.
    let port = target.port_or_known_default().ok_or("Invalid relay port")?;
    let host = target.host_str().ok_or("Invalid relay host")?;
    let authority = if host.contains(':') {
        format!("[{host}]")
    } else {
        host.to_owned()
    };
    let url = format!(
        "{}://{}:{}/v1/grants",
        target.scheme(),
        authority,
        port.checked_add(1).ok_or("Invalid relay port")?
    );
    let response = ureq::post(&url)
        .timeout(std::time::Duration::from_secs(3))
        .send_json(serde_json::to_value(ticket).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    if response.status() != 200 {
        return Err("Relay enrollment rejected".into());
    }
    Ok(())
}
