use crate::{State, now_ms, random_token};
use serde_json::{Value, json};

#[derive(Clone, Debug)]
pub struct PreviewRequest {
    pub id: String,
    pub window: String,
}

pub(crate) fn request(s: &mut State, action: &str, args: &Value) -> Result<Value, String> {
    let window = match action {
        "view.snapshot" => "main",
        "view.toolbar.snapshot" => "toolbar",
        "view.picker.snapshot" => "picker",
        _ => return Err("未知截图窗口".into()),
    };
    let id = match args.get("requestId") {
        None => random_token()?,
        Some(Value::String(id)) if !id.is_empty() && id.len() <= 80
            && id.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_') => id.clone(),
        _ => return Err("requestId 须为 1–80 位字母、数字、横线或下划线".into()),
    };
    if s.preview["id"] == id {
        return if s.preview["window"] == window { Ok(s.preview.clone()) }
            else { Err("同一截图 requestId 不能更换窗口".into()) };
    }
    if matches!(s.preview["status"].as_str(), Some("queued" | "running")) {
        return Err("上一项截图尚未完成".into());
    }
    s.preview = json!({"id":id,"window":window,"status":"queued","requestedAtMs":now_ms()});
    s.preview_request = Some(PreviewRequest { id, window: window.into() });
    Ok(s.preview.clone())
}

pub(crate) fn cancel(s: &mut State) {
    if matches!(s.preview["status"].as_str(), Some("queued" | "running")) {
        s.preview["status"] = json!("cancelled");
        s.preview["ok"] = json!(false);
        s.preview["error"] = json!("窗口正在关闭");
        s.preview["completedAtMs"] = json!(now_ms());
    }
    s.preview_request = None;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Controller, Platform};
    use std::sync::Arc;

    struct Host;
    impl Platform for Host {
        fn execute(&self, _: &str, _: &Value) -> Result<Value, String> { Ok(json!({})) }
    }
    fn fixture() -> Arc<Controller> {
        Controller::new("test", std::env::temp_dir().join(format!("rhremote-preview-{}.json", random_token().unwrap())), Arc::new(Host)).unwrap()
    }

    #[test]
    fn queued_and_running_retries_are_idempotent_and_other_requests_are_bounded() {
        let c=fixture();
        let first=c.dispatch("view.snapshot",json!({"requestId":"first"}));
        assert_eq!(first["result"]["status"],"queued");
        assert_eq!(c.dispatch("view.snapshot",json!({"requestId":"first"}))["result"],first["result"]);
        assert_eq!(c.dispatch("view.picker.snapshot",json!({"requestId":"first"}))["ok"],false);
        assert_eq!(c.dispatch("view.toolbar.snapshot",json!({"requestId":"second"}))["ok"],false);
        let request=c.take_preview_request().unwrap();
        assert!(c.take_preview_request().is_none());
        assert_eq!(c.dispatch("view.snapshot",json!({"requestId":"first"}))["result"]["status"],"running");
        c.preview_complete(&request,Ok(json!({"path":"main.png","width":100,"height":50})));
        assert_eq!(c.dispatch("view.snapshot",json!({"requestId":"first"}))["result"]["status"],"complete");
        assert!(c.take_preview_request().is_none());
    }

    #[test]
    fn a_snapshot_cannot_overwrite_window_actions_or_be_overwritten_by_them() {
        let c=fixture();c.dispatch("window.minimize",json!({}));
        c.dispatch("view.snapshot",json!({"requestId":"main"}));
        assert_eq!(c.take_window_action(),"window.minimize");
        let request=c.take_preview_request().unwrap();assert_eq!(request.window,"main");
        c.dispatch("window.maximize",json!({}));
        c.preview_complete(&request,Ok(json!({"path":"main.png"})));
        assert_eq!(c.take_window_action(),"window.maximize");
        assert_eq!(c.state()["preview"]["status"],"complete");
    }

    #[test]
    fn failed_rendering_is_terminal_and_late_results_cannot_replace_the_next_request() {
        let c=fixture();c.dispatch("view.toolbar.snapshot",json!({"requestId":"hidden"}));
        let hidden=c.take_preview_request().unwrap();
        c.preview_complete(&hidden,Err("控制栏未显示".into()));
        assert_eq!(c.state()["preview"]["status"],"failed");assert_eq!(c.state()["preview"]["ok"],false);
        assert_eq!(c.state()["status"],"");
        c.dispatch("view.snapshot",json!({"requestId":"next"}));
        c.preview_complete(&hidden,Ok(json!({"path":"stale.png"})));
        assert_eq!(c.state()["preview"]["id"],"next");assert!(c.state()["preview"]["path"].is_null());
        let next=c.take_preview_request().unwrap();
        c.preview_complete(&next,Ok(json!({"path":"new.png","width":5})));
        c.preview_complete(&next,Err("late duplicate".into()));
        assert_eq!(c.state()["preview"]["path"],"new.png");assert_eq!(c.state()["preview"]["status"],"complete");
    }

    #[test]
    fn closing_cancels_the_exact_pending_request_and_rejects_its_late_result() {
        let c=fixture();c.dispatch("view.snapshot",json!({"requestId":"pending"}));
        let request=c.take_preview_request().unwrap();
        c.dispatch("window.close",json!({}));assert_eq!(c.take_window_action(),"window.close");
        assert_eq!(c.state()["preview"]["status"],"cancelled");
        c.preview_complete(&request,Ok(json!({"path":"late.png"})));
        assert_eq!(c.state()["preview"]["status"],"cancelled");assert!(c.state()["preview"]["path"].is_null());
    }

    #[test]
    fn invalid_ids_do_not_enqueue_and_missing_ids_are_generated() {
        let c=fixture();
        for id in [json!(""),json!("../file"),json!(null),json!(42),json!("a".repeat(81))] {
            assert_eq!(c.dispatch("view.snapshot",json!({"requestId":id}))["ok"],false);
            assert!(c.take_preview_request().is_none());
        }
        let result=c.dispatch("view.snapshot",json!({}));assert!(result["result"]["id"].as_str().is_some_and(|v|!v.is_empty()));
    }
}
