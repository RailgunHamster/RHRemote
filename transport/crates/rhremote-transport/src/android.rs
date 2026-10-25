//! JNI exposes application transport operations; the Android UI stays Java/View.
use crate::{Address, Client};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::jstring,
};
use std::sync::{Mutex, OnceLock};
static CLIENT: OnceLock<Mutex<Option<Client>>> = OnceLock::new();
static ATTEMPTS: OnceLock<crate::cancellation::Attempts> = OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_limelight_rhremote_RhTunnel_identityNative(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let result = (|| -> anyhow::Result<String> {
        let path: String = env.get_string(&path)?.into();
        Ok(crate::key(std::path::Path::new(&path))?
            .public()
            .to_string())
    })();
    let value = match result {
        Ok(id) => serde_json::json!({"ok":true,"endpointId":id}),
        Err(error) => serde_json::json!({"ok":false,"error":error.to_string()}),
    };
    env.new_string(value.to_string())
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_limelight_rhremote_RhTunnel_startNative(
    mut env: JNIEnv,
    _class: JClass,
    key: JString,
    peer: JString,
    relay: JString,
    relay_only: jni::sys::jboolean,
    attempt_id: jni::sys::jlong,
) -> jstring {
    let result = (|| -> anyhow::Result<serde_json::Value> {
        let key: String = env.get_string(&key)?.into();
        let peer: String = env.get_string(&peer)?.into();
        let relay: String = env.get_string(&relay)?.into();
        let attempt = ATTEMPTS.get_or_init(Default::default).begin(attempt_id as u64)?;
        let mut slot = CLIENT
            .get_or_init(|| Mutex::new(None))
            .lock()
            .map_err(|_| anyhow::anyhow!("Transport unavailable"))?;
        if slot.is_none() {
            *slot = Some(Client::new()?);
        }
        let info = slot.as_mut().unwrap().start_cancelable(
            std::path::Path::new(&key),
            Address {
                endpoint_id: peer,
                relay_url: relay,
            },
            relay_only != 0,
            attempt.token(),
        )?;
        Ok(serde_json::json!({"ok":true,"result":info}))
    })();
    let reply =
        result.unwrap_or_else(|error| serde_json::json!({"ok":false,"error":error.to_string(),"retryable":crate::startup_retryable(&error)}));
    env.new_string(reply.to_string())
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_limelight_rhremote_RhTunnel_cancelStartNative(
    _env: JNIEnv,
    _class: JClass,
    attempt_id: jni::sys::jlong,
) {
    ATTEMPTS.get_or_init(Default::default).cancel(attempt_id as u64);
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_limelight_rhremote_RhTunnel_stopNative(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(slot) = CLIENT.get() {
        if let Ok(mut client) = slot.lock() {
            if let Some(client) = client.as_mut() {
                client.stop();
            }
        }
    }
}
