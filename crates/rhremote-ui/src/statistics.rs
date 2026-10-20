use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    time::{Instant, SystemTime, UNIX_EPOCH},
};

/// Local diagnostics are intentionally excluded from WebDAV profiles.
pub struct Statistics {
    root: PathBuf,
    enabled: bool,
    latest: Option<PathBuf>,
    app: Vec<Value>,
    clock: Instant,
    origin: u128,
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cleanup_keeps_active_and_explicitly_retained_runs() {
        let data = std::env::temp_dir().join(format!(
            "rhremote-statistics-{}",
            rhremote_core::random_token().unwrap()
        ));
        let s = Statistics::new(&data);
        for id in ["used", "active", "retained"] {
            fs::create_dir_all(s.root.join(id)).unwrap();
        }
        fs::write(s.root.join("used/analysis-complete.json"), b"{}").unwrap();
        fs::write(s.root.join("retained/analysis-complete.json"), b"{}").unwrap();
        fs::write(s.root.join("retained/keep"), b"needed").unwrap();
        s.prune().unwrap();
        assert!(!s.root.join("used").exists());
        assert!(s.root.join("active").exists());
        assert!(s.root.join("retained").exists());
        fs::remove_dir_all(data).unwrap();
    }
}
impl Statistics {
    pub fn new(data: &Path) -> Self {
        let enabled = std::env::var("RHREMOTE_TEST_MODE").as_deref() == Ok("1")
            || fs::read(data.join("statistics-settings.json"))
                .ok()
                .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
                .is_some_and(|v| v["enabled"] == true);
        let mut s = Self {
            root: data.join("statistics"),
            enabled,
            latest: None,
            app: vec![],
            clock: Instant::now(),
            origin: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_micros(),
        };
        s.mark("application_start");
        s
    }
    fn now(&self) -> u128 {
        self.origin + self.clock.elapsed().as_micros()
    }
    pub fn mark(&mut self, event: &str) {
        self.app.push(
            json!({"event":event,"ts_us":self.now(),"frame":-1,"thread":"rust-ui","fields":{}}),
        );
    }
    pub fn state(&self) -> Value {
        json!({"enabled":self.enabled,"directory":self.root,"latest":self.latest,"format":"rhremote.statistics/v1","frameData":"metadata_only","presentSemantics":"Windows render_return is CPU submission completion, not physical scanout"})
    }
    pub fn configure(&mut self, enabled: bool) -> Result<Value, String> {
        let path = self.root.parent().unwrap().join("statistics-settings.json");
        fs::write(
            path,
            serde_json::to_vec(&json!({"enabled":enabled})).unwrap(),
        )
        .map_err(|e| e.to_string())?;
        self.enabled = enabled;
        Ok(self.state())
    }
    pub fn session(&mut self, profile: &Value) -> Result<Option<PathBuf>, String> {
        if !self.enabled {
            return Ok(None);
        }
        self.prune()?;
        let id = format!(
            "{}-{}",
            rhremote_core::now_ms(),
            &rhremote_core::random_token()?[..12]
        );
        let dir = self.root.join(id);
        fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let mut rows = self.app.clone();
        rows.push(json!({"event":"connect_action","ts_us":self.now(),"frame":-1,"thread":"rust-ui","fields":{}}));
        let mut text = rows
            .into_iter()
            .map(|v| v.to_string())
            .collect::<Vec<_>>()
            .join("\n");
        text.push('\n');
        fs::write(dir.join("ui.jsonl"), text).map_err(|e| e.to_string())?;
        fs::write(dir.join("manifest.json"),serde_json::to_vec_pretty(&json!({"schema":"rhremote.statistics/v1","platform":"windows","createdUtcMs":rhremote_core::now_ms(),"profile":profile,"clock":"monotonic_anchored_utc_us","files":["ui.jsonl","native.jsonl"],"containsPixels":false,"containsAudio":false,"networkTimestampsPrecisionUs":1000,"hostStages":"Sunshine log; requires host clock offset measurement"})).unwrap()).map_err(|e|e.to_string())?;
        self.latest = Some(dir.clone());
        Ok(Some(dir.join("native.jsonl")))
    }
    pub fn prune(&self) -> Result<(), String> {
        if !self.root.exists() {
            return Ok(());
        }
        let root = self.root.canonicalize().map_err(|e| e.to_string())?;
        for entry in fs::read_dir(&root).map_err(|e| e.to_string())? {
            let entry = entry.map_err(|e| e.to_string())?;
            let p = entry.path();
            if !entry.file_type().map_err(|e| e.to_string())?.is_dir() {
                continue;
            }
            let resolved = p.canonicalize().map_err(|e| e.to_string())?;
            if resolved.parent() != Some(root.as_path()) {
                continue;
            }
            if p.join("analysis-complete.json").is_file() && !p.join("keep").exists() {
                fs::remove_dir_all(&resolved).map_err(|e| e.to_string())?;
            }
        }
        Ok(())
    }
}
