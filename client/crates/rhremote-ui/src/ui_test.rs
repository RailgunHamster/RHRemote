use serde_json::{Value, json};
use std::{collections::VecDeque, path::Path, sync::Mutex};

const HISTORY: usize = 64;
const QUEUED: usize = 8;
pub(crate) const KEYS: &[&str] = &[
    "Tab", "Enter", "Escape", "Space", "Up", "Down", "Left", "Right", "Home", "End",
];

#[derive(Clone, Debug)]
pub(crate) struct Request {
    pub id: String,
    pub operation: String,
    pub window: String,
    pub control: String,
    pub action_id: String,
    pub argument: String,
    pub expected_pending: Option<bool>,
    pub key: String,
    pub shift: bool,
    pub repeat: bool,
    pub activate_window: bool,
    pub pixels: f64,
}
impl Request {
    fn parse(operation: &str, args: &Value) -> Result<Self, String> {
        if !["inspect", "focus", "key", "scroll", "activate"].contains(&operation) {
            return Err("未知界面测试操作".into());
        }
        let window = args["window"].as_str().unwrap_or("main");
        if !["main", "toolbar", "picker"].contains(&window) {
            return Err("window 须为 main / toolbar / picker".into());
        }
        let control = args["control"].as_str().unwrap_or("");
        let action_id = args["actionId"].as_str().unwrap_or("");
        let argument = args["argument"].as_str().unwrap_or("");
        if operation == "activate" {
            if action_id.is_empty() || action_id.len() > 128 || !action_id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-')) {
                return Err("actionId 须为可见按钮的动作标识".into());
            }
            if !args["argument"].is_null() && !args["argument"].is_string() || argument.len() > 512 || argument.contains(['|', '\0', '\r', '\n']) {
                return Err("argument 须为有效的按钮参数".into());
            }
            if !control.is_empty() || !args["key"].is_null() || args["shift"] == true || args["repeat"] == true {
                return Err("activate 通过动作定位按钮，不能同时指定控件或按键".into());
            }
        }
        if !args["expectedPending"].is_null() && (operation != "activate" || window != "toolbar" || !args["expectedPending"].is_boolean()) {
            return Err("expectedPending 仅支持 toolbar 的 activate 布尔条件".into());
        }
        if ["focus", "scroll"].contains(&operation) && (control.is_empty() || control.len() > 512) {
            return Err("请填写 inspect 返回的 control 标识".into());
        }
        let key = args["key"].as_str().unwrap_or("");
        if operation == "key" && !KEYS.contains(&key) {
            return Err("不支持该测试按键".into());
        }
        for flag in ["shift", "repeat", "activateWindow"] {
            if !args[flag].is_null() && !args[flag].is_boolean() {
                return Err(format!("{flag} 须为布尔值"));
            }
        }
        if args["activateWindow"]==true && (operation!="focus" || window!="main") {
            return Err("窗口激活只支持 main 窗口的显式 focus 请求".into());
        }
        let pixels = args["pixels"].as_f64().unwrap_or(0.);
        if operation == "scroll"
            && (!args["pixels"].is_number() || !pixels.is_finite() || pixels.abs() > 10000.)
        {
            return Err("pixels 须为 -10000 至 10000 的数值".into());
        }
        Ok(Self {
            id: String::new(),
            operation: operation.into(),
            window: window.into(),
            control: control.into(),
            action_id: action_id.into(),
            argument: argument.into(),
            expected_pending: args["expectedPending"].as_bool(),
            key: key.into(),
            shift: args["shift"] == true,
            repeat: args["repeat"] == true,
            activate_window: args["activateWindow"] == true,
            pixels,
        })
    }
    fn signature(&self) -> Value {
        json!({"operation":self.operation,"window":self.window,"control":self.control,"actionId":self.action_id,"argument":self.argument,"expectedPending":self.expected_pending,"key":self.key,"shift":self.shift,"repeat":self.repeat,"activateWindow":self.activate_window,"pixels":self.pixels})
    }
}
struct Record {
    request: Request,
    status: &'static str,
    result: Value,
}
impl Record {
    fn state(&self) -> Value {
        json!({"id":self.request.id,"operation":self.request.operation,"window":self.request.window,"status":self.status,"result":self.result})
    }
}
#[derive(Default)]
struct Inner {
    next: u64,
    pending: VecDeque<String>,
    records: VecDeque<Record>,
    active: Option<String>,
    actions: Vec<String>,
}
pub(crate) struct Bridge {
    pub enabled: bool,
    inner: Mutex<Inner>,
}
impl Bridge {
    pub fn new(enabled: bool) -> Self {
        Self {
            enabled,
            inner: Mutex::new(Inner::default()),
        }
    }
    pub fn execute(&self, action: &str, args: &Value) -> Result<Value, String> {
        if !self.enabled {
            return Err("界面测试接口仅在隔离测试预览中启用".into());
        }
        if action == "view.ui.result" {
            let id = args["id"].as_str().ok_or("id required")?;
            return self
                .inner
                .lock()
                .unwrap()
                .records
                .iter()
                .find(|r| r.request.id == id)
                .map(Record::state)
                .ok_or("没有该界面测试请求".into());
        }
        let mut request = Request::parse(action.strip_prefix("view.ui.").unwrap_or(""), args)?;
        let supplied = args["requestId"].as_str();
        if !args["requestId"].is_null() && supplied.is_none() {
            return Err("requestId 须为字符串".into());
        }
        if let Some(id) = supplied {
            if id.is_empty()
                || id.len() > 80
                || !id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
            {
                return Err("requestId 须为 1–80 位字母、数字、横线或下划线".into());
            }
        }
        let mut inner = self.inner.lock().unwrap();
        if let Some(id) = supplied {
            if let Some(existing) = inner.records.iter().find(|r| r.request.id == id) {
                return if existing.request.signature() == request.signature() {
                    Ok(existing.state())
                } else {
                    Err("同一 requestId 不能用于不同操作".into())
                };
            }
        }
        if inner.pending.len() >= QUEUED {
            return Err("界面测试队列已满".into());
        }
        while inner.records.len() >= HISTORY {
            let index = inner
                .records
                .iter()
                .position(|r| matches!(r.status, "complete" | "failed"))
                .ok_or("界面测试记录队列已满")?;
            inner.records.remove(index);
        }
        inner.next += 1;
        request.id = supplied
            .map(str::to_owned)
            .unwrap_or_else(|| format!("ui-{}", inner.next));
        if inner.records.iter().any(|r| r.request.id == request.id) {
            return Err("请求标识冲突，请指定新的 requestId".into());
        }
        inner.pending.push_back(request.id.clone());
        inner.records.push_back(Record {
            request,
            status: "queued",
            result: Value::Null,
        });
        Ok(inner.records.back().unwrap().state())
    }
    pub fn take(&self) -> Option<Request> {
        let mut inner = self.inner.lock().unwrap();
        if inner.active.is_some() {
            return None;
        }
        let id = inner.pending.pop_front()?;
        let record = inner.records.iter_mut().find(|r| r.request.id == id)?;
        record.status = "running";
        let request = record.request.clone();
        inner.active = Some(id);
        inner.actions.clear();
        Some(request)
    }
    pub fn observe_action(&self, id: &str) {
        if !self.enabled {
            return;
        }
        let mut inner = self.inner.lock().unwrap();
        if inner.active.is_some() && inner.actions.len() < 32 {
            inner.actions.push(id.into());
        }
    }
    pub fn finish(&self, id: &str, result: Result<Value, String>) {
        let mut inner = self.inner.lock().unwrap();
        if inner.active.as_deref() != Some(id) {
            return;
        }
        let actions = std::mem::take(&mut inner.actions);
        inner.active = None;
        if let Some(record) = inner.records.iter_mut().find(|r| r.request.id == id) {
            match result {
                Ok(mut value) => {
                    value["commandIds"] = json!(actions);
                    record.result = value;
                    record.status = "complete";
                }
                Err(error) => {
                    record.result = json!({"error":error,"commandIds":actions});
                    record.status = "failed";
                }
            }
        }
    }
}
pub(crate) fn isolated(data: &Path, default: &Path, mode: &str) -> bool {
    if mode != "1" {
        return false;
    }
    match (data.canonicalize(), default.canonicalize()) {
        (Ok(data), Ok(default)) => !data
            .to_string_lossy()
            .eq_ignore_ascii_case(&default.to_string_lossy()),
        (Ok(_), Err(_)) => data.is_absolute() && default.is_absolute() && data != default,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn semantic_activation_rejects_ambiguous_parameters_and_keeps_the_pending_guard_in_its_identity() {
        let plain = Request::parse("activate", &json!({"window":"toolbar","actionId":"stream.disconnect"})).unwrap();
        let guarded = Request::parse("activate", &json!({"window":"toolbar","actionId":"stream.disconnect","expectedPending":true})).unwrap();
        assert_ne!(plain.signature(), guarded.signature());
        for args in [json!({}), json!({"actionId":"bad|id"}), json!({"actionId":"x","argument":7}), json!({"actionId":"x","argument":"bad|argument"}), json!({"actionId":"x","control":"old-caption"}), json!({"actionId":"x","key":"Enter"}), json!({"actionId":"x","expectedPending":true}), json!({"actionId":"x","window":"toolbar","expectedPending":"true"}), json!({"actionId":"x","activateWindow":true})] {
            assert!(Request::parse("activate", &args).is_err(), "{args}");
        }
    }
    #[test]
    fn activation_is_explicit_main_focus_only_and_part_of_the_request_identity() {
        let passive=Request::parse("focus",&json!({"control":"page"})).unwrap();assert!(!passive.activate_window);
        let active=Request::parse("focus",&json!({"control":"page","activateWindow":true})).unwrap();assert!(active.activate_window);
        assert_ne!(passive.signature(),active.signature());
        for (operation,args) in [("inspect",json!({"activateWindow":true})),("key",json!({"key":"Tab","activateWindow":true})),("focus",json!({"window":"toolbar","control":"x","activateWindow":true})),("focus",json!({"control":"x","activateWindow":"true"}))] {
            assert!(Request::parse(operation,&args).is_err());
        }
        let bridge=Bridge::new(true);
        bridge.execute("view.ui.focus",&json!({"requestId":"focus","control":"page"})).unwrap();
        assert!(bridge.execute("view.ui.focus",&json!({"requestId":"focus","control":"page","activateWindow":true})).is_err());
    }
    #[test]
    fn disabled_interfaces_cannot_queue_or_read_requests() {
        let b = Bridge::new(false);
        for id in [
            "view.ui.inspect",
            "view.ui.activate",
            "view.ui.focus",
            "view.ui.key",
            "view.ui.result",
        ] {
            assert!(b.execute(id, &json!({})).is_err());
        }
        assert!(b.take().is_none());
    }
    #[test]
    fn retries_are_idempotent_and_cannot_change_the_request() {
        let b = Bridge::new(true);
        let args = json!({"requestId":"one","key":"Enter"});
        let first = b.execute("view.ui.key", &args).unwrap();
        assert_eq!(b.execute("view.ui.key", &args).unwrap(), first);
        assert!(
            b.execute("view.ui.key", &json!({"requestId":"one","key":"Escape"}))
                .is_err()
        );
        let job = b.take().unwrap();
        assert!(b.take().is_none());
        b.observe_action("settings.save");
        b.finish(&job.id, Ok(json!({"delivered":true})));
        let result = b.execute("view.ui.key", &args).unwrap();
        assert_eq!(result["status"], "complete");
        assert_eq!(result["result"]["commandIds"], json!(["settings.save"]));
        assert!(b.take().is_none());
    }
    #[test]
    fn queue_is_bounded_and_fails_without_losing_an_earlier_job() {
        let b = Bridge::new(true);
        for _ in 0..QUEUED {
            b.execute("view.ui.inspect", &json!({})).unwrap();
        }
        assert!(b.execute("view.ui.inspect", &json!({})).is_err());
        let job = b.take().unwrap();
        b.finish(&job.id, Ok(json!({})));
        assert!(b.execute("view.ui.inspect", &json!({})).is_ok());
        assert_eq!(
            b.execute("view.ui.result", &json!({"id":job.id})).unwrap()["status"],
            "complete"
        );
    }
    #[test]
    fn only_explicit_app_keys_and_valid_targets_are_accepted() {
        let b = Bridge::new(true);
        for args in [
            json!({"key":"Win+R"}),
            json!({"key":"Enter","window":"other-app"}),
            json!({"key":"Enter","shift":"true"}),
        ] {
            assert!(b.execute("view.ui.key", &args).is_err());
        }
        assert!(b.execute("view.ui.focus", &json!({})).is_err());
        assert!(
            b.execute(
                "view.ui.scroll",
                &json!({"control":"scroll|form","pixels":10001})
            )
            .is_err()
        );
        assert!(
            b.execute("view.ui.key", &json!({"key":"Tab","shift":true}))
                .is_ok()
        );
    }
    #[test]
    fn results_do_not_expose_input_values_or_request_payloads() {
        let b = Bridge::new(true);
        let result = b
            .execute(
                "view.ui.inspect",
                &json!({"password":"not-a-control-value"}),
            )
            .unwrap();
        assert!(!result.to_string().contains("not-a-control-value"));
    }
    #[test]
    fn a_test_flag_cannot_enable_testing_against_the_normal_data_directory() {
        let root = std::env::temp_dir().join(format!(
            "rhremote-ui-isolation-{}",
            rhremote_core::random_token().unwrap()
        ));
        let normal = root.join("normal");
        let preview = root.join("preview");
        std::fs::create_dir_all(&normal).unwrap();
        std::fs::create_dir(&preview).unwrap();
        assert!(!isolated(&preview, &normal, "0"));
        assert!(!isolated(&normal, &normal, "1"));
        assert!(!isolated(&normal.join("."), &normal, "1"));
        assert!(isolated(&preview, &normal, "1"));
        std::fs::remove_dir(&preview).unwrap();
        std::fs::remove_dir(&normal).unwrap();
        std::fs::remove_dir(&root).unwrap();
    }
    #[test]
    fn old_completed_records_are_pruned_without_reexecuting_recent_jobs() {
        let b = Bridge::new(true);
        for _ in 0..HISTORY + 10 {
            b.execute("view.ui.inspect", &json!({})).unwrap();
            let job = b.take().unwrap();
            b.finish(&job.id, Ok(json!({})));
        }
        assert_eq!(b.inner.lock().unwrap().records.len(), HISTORY);
        assert!(b.execute("view.ui.result", &json!({"id":"ui-1"})).is_err());
    }
}
