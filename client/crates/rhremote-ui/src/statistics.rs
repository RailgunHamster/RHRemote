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
    recording: bool,
    app: Vec<Value>,
    clock: Instant,
    origin: u128,
    pending_connect: Option<u128>,
    startup: Option<PathBuf>,
    startup_written: usize,
    startup_error: String,
}
#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> (PathBuf, Statistics) {
        let data = std::env::temp_dir().join(format!(
            "rhremote-ui-footer-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir_all(&data).unwrap();
        let mut statistics = Statistics::new(&data);
        statistics.configure(true).unwrap();
        (data, statistics)
    }
    fn records(path: &Path) -> Vec<Value> {
        fs::read_to_string(path)
            .unwrap()
            .lines()
            .map(|line| serde_json::from_str(line).unwrap())
            .collect()
    }
    #[test]
    fn finish_is_idempotent_and_late_events_cannot_reopen_a_closed_file() {
        let (data, mut statistics) = fixture();
        let native = statistics.session(&json!({})).unwrap().unwrap();
        let ui = native.parent().unwrap().join("ui.jsonl");
        statistics.event("native_spawn_begin", json!({})).unwrap();
        statistics.finish().unwrap();
        let closed = fs::read(&ui).unwrap();
        statistics.event("late_worker", json!({})).unwrap();
        statistics.finish().unwrap();
        assert_eq!(closed, fs::read(&ui).unwrap());
        let rows = records(&ui);
        assert_eq!(rows.last().unwrap()["event"], "trace_end");
        assert_eq!(rows.iter().filter(|r| r["event"] == "trace_end").count(), 1);
        assert_eq!(
            statistics.state()["latest"],
            json!(native.parent().unwrap())
        );
        assert_eq!(statistics.state()["recording"], false);
        drop(statistics);
        fs::remove_dir_all(data).unwrap();
    }
    #[test]
    fn retry_closes_the_previous_ui_file_before_accepting_new_metadata() {
        let (data, mut statistics) = fixture();
        let first = statistics.session(&json!({})).unwrap().unwrap();
        statistics
            .event("network_resolve_failed", json!({}))
            .unwrap();
        let second = statistics.session(&json!({})).unwrap().unwrap();
        statistics
            .event("network_resolve_begin", json!({}))
            .unwrap();
        let first_rows = records(&first.parent().unwrap().join("ui.jsonl"));
        assert_eq!(first_rows.last().unwrap()["event"], "trace_end");
        assert!(
            !first_rows
                .iter()
                .any(|r| r["event"] == "network_resolve_begin")
        );
        let second_rows = records(&second.parent().unwrap().join("ui.jsonl"));
        assert!(!second_rows.iter().any(|r| r["event"] == "trace_end"));
        drop(statistics);
        assert_eq!(
            records(&second.parent().unwrap().join("ui.jsonl"))
                .last()
                .unwrap()["event"],
            "trace_end"
        );
        fs::remove_dir_all(data).unwrap();
    }
    #[test]
    fn disabling_statistics_finishes_the_existing_file_but_creates_no_new_one() {
        let (data, mut statistics) = fixture();
        let trace = statistics.session(&json!({})).unwrap().unwrap();
        let directories=fs::read_dir(data.join("statistics")).unwrap().count();
        statistics.configure(false).unwrap();
        assert_eq!(
            records(&trace.parent().unwrap().join("ui.jsonl"))
                .last()
                .unwrap()["event"],
            "trace_end"
        );
        assert!(statistics.session(&json!({})).unwrap().is_none());
        assert_eq!(fs::read_dir(data.join("statistics")).unwrap().count(), directories);
        drop(statistics);
        fs::remove_dir_all(data).unwrap();
    }
    #[test]
    fn footer_write_failure_is_reported_and_does_not_keep_appending() {
        let (data, mut statistics) = fixture();
        let trace = statistics.session(&json!({})).unwrap().unwrap();
        let ui = trace.parent().unwrap().join("ui.jsonl");
        fs::remove_file(&ui).unwrap();
        fs::create_dir(&ui).unwrap();
        assert!(statistics.finish().is_err());
        assert_eq!(statistics.state()["recording"], false);
        statistics.event("late_event", json!({})).unwrap();
        assert!(ui.is_dir());
        drop(statistics);
        fs::remove_dir_all(data).unwrap();
    }
    #[test]
    fn fallback_attempts_preserve_the_original_connection_clock() {
        let data = std::env::temp_dir().join(format!(
            "rhremote-fallback-clock-{}",
            rhremote_core::random_token().unwrap()
        ));
        let mut s = Statistics::new(&data);
        s.enabled = true;
        s.connection_requested(None);
        let origin = s.request_origin();
        let first = s.session_at(&json!({}), Some(origin)).unwrap().unwrap();
        s.event("network_resolve_failed", json!({"route":"lan"}))
            .unwrap();
        let second = s.session_at(&json!({}), Some(origin)).unwrap().unwrap();
        assert_ne!(first, second);
        for trace in [first, second] {
            let text = fs::read_to_string(trace.parent().unwrap().join("ui.jsonl")).unwrap();
            let rows: Vec<Value> = text
                .lines()
                .map(|l| serde_json::from_str(l).unwrap())
                .collect();
            assert_eq!(
                rows.iter()
                    .find(|r| r["event"] == "connect_action")
                    .unwrap()["ts_us"],
                json!(origin)
            );
            assert!(!rows.iter().any(|r| r["event"] == "native_spawn_begin"));
        }
        fs::remove_dir_all(data).unwrap();
    }
    #[test]
    fn connection_origin_includes_ui_queue_time() {
        let data = std::env::temp_dir().join(format!(
            "rhremote-origin-{}",
            rhremote_core::random_token().unwrap()
        ));
        let mut s = Statistics::new(&data);
        s.enabled = true;
        let requested = s.now() - 10_000;
        s.connection_requested(Some(rhremote_core::ActionTiming {
            requested_at_us: requested,
            queue_wait_us: 9_000,
        }));
        let trace = s.session(&json!({})).unwrap().unwrap();
        let rows: Vec<Value> = fs::read_to_string(trace.parent().unwrap().join("ui.jsonl"))
            .unwrap()
            .lines()
            .map(|line| serde_json::from_str(line).unwrap())
            .collect();
        let origin = rows
            .iter()
            .find(|row| row["event"] == "connect_action")
            .unwrap();
        assert_eq!(origin["ts_us"].as_u64().unwrap() as u128, requested);
        let accepted = rows
            .iter()
            .find(|row| row["event"] == "connect_request_received")
            .unwrap();
        assert_eq!(accepted["fields"]["source"], "ui");
        assert_eq!(accepted["fields"]["queue_wait_us"], 9_000);
        assert!(!rows.iter().any(|row| row["event"] == "native_spawn_begin"));
        assert_eq!(data.parent(), Some(std::env::temp_dir().as_path()));
        fs::remove_dir_all(data).unwrap();
    }
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
    #[test]
    fn startup_marks_are_readable_before_any_connection_starts(){
        let(data,mut statistics)=fixture();statistics.mark("ui_shown");
        let state=statistics.state();let directory=PathBuf::from(state["startup"].as_str().unwrap());let rows=records(&directory.join("startup.jsonl"));
        assert_eq!(state["recording"],false);assert_eq!(state["startupError"],"");assert!(state["latest"].is_null());
        assert_eq!(rows.first().unwrap()["event"],"application_start");assert_eq!(rows.last().unwrap()["event"],"ui_shown");
        fs::write(directory.join("analysis-complete.json"),b"{}").unwrap();statistics.prune().unwrap();assert!(directory.join("startup.jsonl").is_file(),"an active startup trace was pruned");
        drop(statistics);fs::remove_dir_all(data).unwrap();
    }
    #[test]
    fn a_startup_write_failure_is_observable_and_does_not_break_later_ui_marks(){
        let(data,mut statistics)=fixture();let path=statistics.startup.as_ref().unwrap().join("startup.jsonl");fs::remove_file(&path).unwrap();fs::create_dir(&path).unwrap();
        statistics.mark("ui_shown");assert!(!statistics.state()["startupError"].as_str().unwrap().is_empty());statistics.mark("ui_event_loop_returned");assert!(path.is_dir());
        drop(statistics);fs::remove_dir_all(data).unwrap();
    }
    #[test]
    fn connection_clock_replacement_does_not_drop_or_duplicate_startup_stages(){
        let(data,mut statistics)=fixture();statistics.connection_requested(None);statistics.mark("ui_shown");statistics.connection_requested(None);statistics.mark("devices_check_end");
        let rows=records(&statistics.startup.as_ref().unwrap().join("startup.jsonl"));let events:Vec<_>=rows.iter().map(|row|row["event"].as_str().unwrap()).collect();
        assert_eq!(events,vec!["application_start","ui_shown","devices_check_end"]);drop(statistics);fs::remove_dir_all(data).unwrap();
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
            recording: false,
            app: vec![],
            clock: Instant::now(),
            origin: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_micros(),
            pending_connect: None,
            startup: None,
            startup_written: 0,
            startup_error: String::new(),
        };
        s.mark("application_start");
        s
    }
    pub fn now(&self) -> u128 {
        self.origin + self.clock.elapsed().as_micros()
    }
    pub fn request_origin(&self) -> u128 {
        self.pending_connect.unwrap_or_else(|| self.now())
    }
    pub fn connection_requested(&mut self, timing: Option<rhremote_core::ActionTiming>) {
        let now = self.now();
        let origin = timing
            .map(|t| t.requested_at_us)
            .filter(|t| *t <= now)
            .unwrap_or(now);
        self.pending_connect = Some(origin);
        // Only startup and connection admission metadata are stored here; never action arguments.
        self.app
            .retain(|row| !matches!(row["event"].as_str(), Some("connect_request_received")));
        self.app.push(json!({"event":"connect_request_received","ts_us":now,"frame":-1,"thread":"rust-ui","fields":{"source":if timing.is_some(){"ui"}else{"api"},"queue_wait_us":timing.map(|t|t.queue_wait_us).unwrap_or(0)}}));
    }
    pub fn mark(&mut self, event: &str) {
        self.app.push(
            json!({"event":event,"ts_us":self.now(),"frame":-1,"thread":"rust-ui","fields":{}}),
        );
        if self.enabled && self.startup_error.is_empty(){if let Err(error)=self.write_startup(){self.startup_error=error;}}
    }
    fn write_startup(&mut self)->Result<(),String>{
        use std::io::Write;
        if self.startup.is_none(){
            fs::create_dir_all(&self.root).map_err(|e|e.to_string())?;
            let directory=self.root.join(format!("startup-{}-{}",self.origin,&rhremote_core::random_token()?[..12]));
            fs::create_dir(&directory).map_err(|e|e.to_string())?;
            fs::write(directory.join("manifest.json"),serde_json::to_vec_pretty(&json!({"schema":"rhremote.statistics/v1","component":"ui-startup","appVersion":env!("CARGO_PKG_VERSION"),"clock":"monotonic_anchored_utc_us","files":["startup.jsonl"],"containsPixels":false,"containsAudio":false})).unwrap()).map_err(|e|e.to_string())?;
            self.startup=Some(directory);
        }
        let mut file=fs::OpenOptions::new().create(true).append(true).open(self.startup.as_ref().unwrap().join("startup.jsonl")).map_err(|e|e.to_string())?;
        let stages:Vec<_>=self.app.iter().filter(|row|row["event"]!="connect_request_received").collect();
        for row in &stages[self.startup_written..]{writeln!(file,"{row}").map_err(|e|e.to_string())?;}
        file.flush().map_err(|e|e.to_string())?;self.startup_written=stages.len();Ok(())
    }
    pub(crate) fn recording_session(&self) -> Option<&Path> {
        (self.enabled && self.recording).then_some(self.latest.as_deref()).flatten()
    }
    pub fn event(&self, event: &str, fields: Value) -> Result<(), String> {
        if !self.enabled || !self.recording {
            return Ok(());
        }
        if let Some(dir) = &self.latest {
            use std::io::Write;
            let mut file = fs::OpenOptions::new()
                .append(true)
                .open(dir.join("ui.jsonl"))
                .map_err(|e| e.to_string())?;
            writeln!(file,"{}",json!({"event":event,"ts_us":self.now(),"frame":-1,"thread":"rust-ui","fields":fields})).map_err(|e|e.to_string())?;
        }
        Ok(())
    }
    pub fn state(&self) -> Value {
        json!({"enabled":self.enabled,"recording":self.recording,"directory":self.root,"latest":self.latest,"startup":self.startup,"startupError":self.startup_error,"format":"rhremote.statistics/v1","frameData":"metadata_only","presentSemantics":"Windows render_return is CPU submission completion, not physical scanout"})
    }
    pub fn configure(&mut self, enabled: bool) -> Result<Value, String> {
        let path = self.root.parent().unwrap().join("statistics-settings.json");
        fs::write(
            path,
            serde_json::to_vec(&json!({"enabled":enabled})).unwrap(),
        )
        .map_err(|e| e.to_string())?;
        self.enabled = enabled;
        if enabled{
            if !self.startup_error.is_empty(){self.startup=None;self.startup_written=0;self.startup_error.clear();}
            if let Err(error)=self.write_startup(){self.startup_error=error.clone();return Err(error);}
        } else {
            self.finish()?;
        }
        Ok(self.state())
    }
    /// Close this UI metadata file once; the native process has its own footer.
    pub fn finish(&mut self) -> Result<(), String> {
        if !self.recording {
            return Ok(());
        }
        self.recording = false;
        let Some(dir) = &self.latest else {
            return Ok(());
        };
        use std::io::Write;
        let mut file = fs::OpenOptions::new()
            .append(true)
            .open(dir.join("ui.jsonl"))
            .map_err(|error| error.to_string())?;
        writeln!(file, "{}", json!({"event":"trace_end","ts_us":self.now(),"frame":-1,"thread":"rust-ui","lost_events":0,"size_limit_reached":false}))
            .map_err(|error| error.to_string())?;
        file.sync_data().map_err(|error| error.to_string())
    }
    #[cfg(test)]
    pub fn session(&mut self, profile: &Value) -> Result<Option<PathBuf>, String> {
        self.session_at(profile, None)
    }
    pub fn session_at(
        &mut self,
        profile: &Value,
        origin: Option<u128>,
    ) -> Result<Option<PathBuf>, String> {
        self.finish()?;
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
        let pending = self.pending_connect.take();
        let requested = origin.or(pending).unwrap_or_else(|| self.now());
        rows.push(json!({"event":"connect_action","ts_us":requested,"frame":-1,"thread":"rust-ui","fields":{}}));
        let mut text = rows
            .into_iter()
            .map(|v| v.to_string())
            .collect::<Vec<_>>()
            .join("\n");
        text.push('\n');
        fs::write(dir.join("ui.jsonl"), text).map_err(|e| e.to_string())?;
        fs::write(dir.join("manifest.json"),serde_json::to_vec_pretty(&json!({"schema":"rhremote.statistics/v1","platform":"windows","createdUtcMs":rhremote_core::now_ms(),"profile":profile,"clock":"monotonic_anchored_utc_us","files":["ui.jsonl","native.jsonl"],"containsPixels":false,"containsAudio":false,"networkTimestampsPrecisionUs":1000,"hostStages":"Sunshine log; requires host clock offset measurement"})).unwrap()).map_err(|e|e.to_string())?;
        self.latest = Some(dir.clone());
        self.recording = true;
        Ok(Some(dir.join("native.jsonl")))
    }
    pub fn prune(&self) -> Result<(), String> {
        if !self.root.exists() {
            return Ok(());
        }
        let root = self.root.canonicalize().map_err(|e| e.to_string())?;
        let startup=self.startup.as_ref().and_then(|path|path.canonicalize().ok());
        let active=self.recording.then(||self.latest.as_ref().and_then(|path|path.canonicalize().ok())).flatten();
        for entry in fs::read_dir(&root).map_err(|e| e.to_string())? {
            let entry = entry.map_err(|e| e.to_string())?;
            let p = entry.path();
            if !entry.file_type().map_err(|e| e.to_string())?.is_dir() {
                continue;
            }
            let resolved = p.canonicalize().map_err(|e| e.to_string())?;
            if startup.as_ref()==Some(&resolved)||active.as_ref()==Some(&resolved){continue;}
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

impl Drop for Statistics {
    fn drop(&mut self) {
        let _ = self.finish();
    }
}
