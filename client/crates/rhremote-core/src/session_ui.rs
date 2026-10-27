use crate::{Controller, State, shortcuts};
use serde::Serialize;
use serde_json::Value;

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ControlAction {
    pub sequence: u64,
    pub action: String,
    pub group: String,
    pub busy: bool,
    pub message: String,
    pub error: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Device, Platform};
    use serde_json::json;
    use std::{
        sync::{Arc, Mutex, mpsc},
        time::Duration,
    };
    struct Host {
        pending: Option<(mpsc::Sender<()>, Mutex<mpsc::Receiver<()>>)>,
        fail: std::sync::atomic::AtomicBool,
    }
    impl Platform for Host {
        fn execute(&self, id: &str, args: &Value) -> Result<Value, String> {
            if id == "stream.audio.volume" {
                if let Some((started, release)) = &self.pending {
                    started.send(()).unwrap();
                    release
                        .lock()
                        .unwrap()
                        .recv_timeout(Duration::from_secs(5))
                        .unwrap();
                }
                return Ok(json!({"volume":args["volume"]}));
            }
            if self.fail.load(std::sync::atomic::Ordering::SeqCst) && is_control_action(id) {
                return Err("主机拒绝切换此屏幕".into());
            }
            Ok(match id {
                "stream.audio.toggle" => json!({"enabled":true}),
                _ => json!({}),
            })
        }
    }
    fn fixture(platform: Arc<Host>) -> Arc<Controller> {
        let c = Controller::new(
            "windows",
            std::env::temp_dir().join(format!(
                "rhremote-session-ui-{}.json",
                crate::random_token().unwrap()
            )),
            platform,
        )
        .unwrap();
        c.replace_devices(serde_json::from_value::<Vec<Device>>(json!([{"uuid":"host","name":"winserver","address":"192.168.0.104","paired":true,"online":true}])).unwrap());
        connect(&c);
        c.dispatch("stream.controls.toggle", json!({}));
        c
    }
    fn connect(c: &Controller) {
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"host"}))["ok"],
            true
        );
        c.stream_state(
            true,
            json!({"phase":"connected","firstFrame":true,"audioEnabled":false,"volume":100}),
        );
    }
    #[test]
    fn failed_selection_stays_open_for_retry_and_feedback_survives_stream_polls() {
        let p = Arc::new(Host {
            pending: None,
            fail: std::sync::atomic::AtomicBool::new(true),
        });
        let c = fixture(p.clone());
        c.dispatch("stream.controls.tab", json!({"tab":"displays"}));
        c.dispatch("stream.menu.open", json!({"menu":"displays"}));
        assert_eq!(
            c.dispatch("stream.display.select", json!({"displayId":"auto"}))["ok"],
            false
        );
        assert_eq!(c.state()["streamMenu"], "displays");
        assert_eq!(c.state()["controlAction"]["busy"], false);
        assert_eq!(c.state()["controlAction"]["error"], "主机拒绝切换此屏幕");
        assert_eq!(c.state()["controlAction"]["message"], "屏幕切换失败");
        c.stream_state(true, json!({"phase":"connected","firstFrame":true}));
        assert_eq!(c.state()["controlAction"]["error"], "主机拒绝切换此屏幕");
        assert_eq!(c.state()["status"], "");
        p.fail.store(false, std::sync::atomic::Ordering::SeqCst);
        assert_eq!(
            c.dispatch("stream.display.select", json!({"displayId":"auto"}))["ok"],
            true
        );
        assert_eq!(c.state()["streamMenu"], "");
        assert_eq!(c.state()["controlAction"]["error"], "");
        assert_eq!(c.state()["controlAction"]["message"], "已发送屏幕切换");
        let _ = std::fs::remove_file(&c.storage);
    }
    #[test]
    fn a_pending_control_rejects_duplicates_but_keeps_navigation_and_disconnect_available() {
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let p = Arc::new(Host {
            pending: Some((started_tx, Mutex::new(release_rx))),
            fail: std::sync::atomic::AtomicBool::new(false),
        });
        let c = fixture(p);
        let task = c.clone();
        let pending =
            std::thread::spawn(move || task.dispatch("stream.audio.volume", json!({"volume":37})));
        started_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(c.state()["controlAction"]["busy"], true);
        assert_eq!(c.dispatch("stream.stats.toggle", json!({}))["ok"], false);
        assert_eq!(
            c.dispatch("stream.controls.tab", json!({"tab":"displays"}))["ok"],
            true
        );
        assert_eq!(c.dispatch("stream.disconnect", json!({}))["ok"], true);
        c.stream_state(
            false,
            json!({"phase":"disconnected","nativeCleanupPending":false}),
        );
        assert_eq!(c.state()["controlRequests"], 1);
        assert_eq!(
            c.dispatch("device.connect", json!({"uuid":"host"}))["ok"],
            false
        );
        release_tx.send(()).unwrap();
        assert_eq!(pending.join().unwrap()["ok"], false);
        assert_eq!(c.state()["controlRequests"], 0);
        connect(&c);
        assert_eq!(c.state()["volume"], 100);
        assert_eq!(c.state()["controlAction"]["action"], "");
        assert_eq!(c.state()["controlAction"]["busy"], false);
        let _ = std::fs::remove_file(&c.storage);
    }
    #[test]
    fn invalid_arguments_report_inside_their_group_without_mutating_playback() {
        let c = fixture(Arc::new(Host {
            pending: None,
            fail: std::sync::atomic::AtomicBool::new(false),
        }));
        let before = c.state();
        for (id, args) in [
            ("stream.audio.volume", json!({"volume":101})),
            ("stream.shortcut", json!({"shortcut":"invalid"})),
            ("stream.display.select", json!({"displayId":""})),
        ] {
            assert_eq!(c.dispatch(id, args)["ok"], false);
            assert_eq!(c.state()["controlAction"]["busy"], false);
            assert!(
                c.state()["controlAction"]["error"]
                    .as_str()
                    .is_some_and(|s| !s.is_empty())
            );
            assert_eq!(c.state()["volume"], before["volume"]);
            assert_eq!(c.state()["audioEnabled"], before["audioEnabled"]);
        }
        assert_eq!(
            c.dispatch("stream.menu.open", json!({"menu":"feedback"}))["ok"],
            true
        );
        assert_eq!(c.dispatch("stream.menu.close", json!({}))["ok"], true);
        assert_eq!(c.state()["controlAction"]["error"], "displayId required");
        let _ = std::fs::remove_file(&c.storage);
    }
    #[test]
    fn groups_validate_tabs_and_only_matching_success_closes_a_picker() {
        let c = fixture(Arc::new(Host {
            pending: None,
            fail: std::sync::atomic::AtomicBool::new(false),
        }));
        for tab in ["input", "displays", "audio"] {
            assert_eq!(
                c.dispatch("stream.controls.tab", json!({"tab":tab}))["ok"],
                true
            );
            assert_eq!(c.state()["controlsTab"], tab);
        }
        assert_eq!(
            c.dispatch("stream.controls.tab", json!({"tab":"bad"}))["ok"],
            false
        );
        c.dispatch("stream.menu.open", json!({"menu":"displays"}));
        assert_eq!(c.dispatch("stream.displays.refresh", json!({}))["ok"], true);
        assert_eq!(c.state()["streamMenu"], "displays");
        c.dispatch("stream.shortcuts.open", json!({}));
        assert_eq!(c.state()["controlsTab"], "input");
        assert_eq!(c.state()["streamMenu"], "shortcuts");
        assert_eq!(
            c.dispatch("stream.shortcut", json!({"shortcut":"ctrl-alt-delete"}))["ok"],
            true
        );
        assert_eq!(c.state()["streamMenu"], "");
        let _ = std::fs::remove_file(&c.storage);
    }

    #[test]
    fn slow_session_http_control_keeps_state_tabs_and_disconnect_responsive() {
        use std::{io::{Read,Write},net::{TcpListener,TcpStream}};
        fn request(port:u16,token:&str,body:Option<&str>,timeout:Duration)->std::io::Result<String>{
            let mut socket=TcpStream::connect(("127.0.0.1",port))?;socket.set_read_timeout(Some(timeout))?;
            let (method,path)=if body.is_some(){("POST","/v1/action")}else{("GET","/v1/state")};let body=body.unwrap_or("");
            write!(socket,"{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {token}\r\nConnection: close\r\nContent-Length: {}\r\n\r\n{body}",body.len())?;
            let mut response=String::new();socket.read_to_string(&mut response)?;Ok(response)
        }
        struct Release(Option<mpsc::Sender<()>>);impl Drop for Release{fn drop(&mut self){if let Some(tx)=self.0.take(){let _=tx.send(());}}}
        let (entered,wait)=mpsc::channel();let (release,released)=mpsc::channel();let mut release=Release(Some(release));
        let c=fixture(Arc::new(Host{pending:Some((entered,Mutex::new(released))),fail:std::sync::atomic::AtomicBool::new(false)}));
        let listener=TcpListener::bind(("127.0.0.1",0)).unwrap();let port=listener.local_addr().unwrap().port();drop(listener);
        let token=crate::random_token().unwrap();crate::start_http(c.clone(),port,token.clone()).unwrap();
        let caller_token=token.clone();let worker=std::thread::spawn(move||request(port,&caller_token,Some(r#"{"id":"stream.audio.volume","args":{"volume":37}}"#),Duration::from_secs(5)));
        wait.recv_timeout(Duration::from_secs(2)).unwrap();
        let state=request(port,&token,None,Duration::from_secs(1)).expect("state HTTP blocked behind the pending session control");
        assert!(state.contains("\"busy\":true"),"{state}");
        let duplicate=request(port,&token,Some(r#"{"id":"stream.stats.toggle","args":{}}"#),Duration::from_secs(1)).unwrap();
        assert!(duplicate.contains("422")&&duplicate.contains("上一项会话操作尚未完成"),"{duplicate}");
        let tab=request(port,&token,Some(r#"{"id":"stream.controls.tab","args":{"tab":"displays"}}"#),Duration::from_secs(1)).unwrap();
        assert!(tab.contains("\"ok\":true"),"{tab}");assert_eq!(c.state()["controlsTab"],"displays");
        let stopped=request(port,&token,Some(r#"{"id":"stream.disconnect","args":{}}"#),Duration::from_secs(1)).unwrap();
        assert!(stopped.contains("\"ok\":true"),"{stopped}");
        c.stream_state(false,json!({"phase":"disconnected","nativeCleanupPending":false}));assert_eq!(c.state()["controlRequests"],1);
        assert_eq!(c.dispatch("device.connect",json!({"uuid":"host"}))["ok"],false);
        release.0.take().unwrap().send(()).unwrap();let response=worker.join().unwrap().unwrap();
        assert!(response.contains("422")&&response.contains("会话已改变，操作结果已忽略"),"{response}");
        assert_eq!(c.state()["controlRequests"],0);assert_eq!(c.state()["volume"],100);
        let _=std::fs::remove_file(&c.storage);
    }
}

pub fn is_control_action(id: &str) -> bool {
    matches!(
        id,
        "stream.displays.refresh"
            | "stream.display.select"
            | "stream.shortcut"
            | "stream.window.move"
            | "stream.stats.toggle"
            | "stream.audio.toggle"
            | "stream.audio.volume"
    )
}
pub(crate) fn clear(s: &mut State) {
    s.control_action = ControlAction {
        sequence: s.control_action.sequence + 1,
        ..Default::default()
    };
}
fn group(id: &str) -> &'static str {
    match id {
        "stream.displays.refresh" | "stream.display.select" | "stream.window.move" => "displays",
        "stream.audio.toggle" | "stream.audio.volume" => "audio",
        _ => "input",
    }
}
fn waiting(id: &str) -> &'static str {
    match id {
        "stream.displays.refresh" => "正在读取屏幕…",
        "stream.display.select" => "正在切换屏幕…",
        "stream.shortcut" | "stream.window.move" => "正在发送快捷键…",
        "stream.stats.toggle" => "正在设置统计…",
        _ => "正在设置声音…",
    }
}
fn failed(id: &str) -> &'static str {
    match id {
        "stream.displays.refresh" => "屏幕列表读取失败",
        "stream.display.select" => "屏幕切换失败",
        "stream.window.move" => "窗口移动失败",
        "stream.shortcut" => "快捷键发送失败",
        "stream.stats.toggle" => "统计设置失败",
        _ => "声音设置失败",
    }
}
impl Controller {
    pub(crate) fn dispatch_session_control(&self, id: &str, args: Value) -> Result<Value, String> {
        let (epoch, sequence) = {
            let mut s = self.state.lock().unwrap();
            if !s.streaming || s.connection.stopping {
                return Err("没有可操作的串流".into());
            }
            if s.control_action.busy {
                return Err("上一项会话操作尚未完成".into());
            }
            let sequence = s.control_action.sequence + 1;
            s.control_action = ControlAction {
                sequence,
                action: id.into(),
                group: group(id).into(),
                busy: true,
                message: waiting(id).into(),
                error: String::new(),
            };
            s.control_requests += 1;
            s.revision += 1;
            (s.stream_epoch, sequence)
        };
        let result = (|| {
            match id {
                "stream.shortcut"
                    if !shortcuts().as_array().unwrap().iter().any(|shortcut| {
                        shortcut["id"] == args["shortcut"] && args["shortcut"].is_string()
                    }) =>
                {
                    return Err("unknown remote shortcut".into());
                }
                "stream.window.move"
                    if !matches!(args["direction"].as_str(), Some("left" | "right")) =>
                {
                    return Err("direction must be left or right".into());
                }
                "stream.display.select"
                    if !args["displayId"]
                        .as_str()
                        .is_some_and(|value| !value.is_empty() && value.len() <= 512) =>
                {
                    return Err("displayId required".into());
                }
                "stream.audio.volume"
                    if !args["volume"].as_u64().is_some_and(|value| value <= 100) =>
                {
                    return Err("volume must be 0..100".into());
                }
                _ => {}
            }
            self.platform.execute(id, &args)
        })();
        let mut s = self.state.lock().unwrap();
        s.control_requests = s.control_requests.saturating_sub(1);
        s.revision += 1;
        if s.stream_epoch != epoch
            || s.control_action.sequence != sequence
            || !s.streaming
            || s.connection.stopping
        {
            return Err("会话已改变，操作结果已忽略".into());
        }
        s.control_action.busy = false;
        match &result {
            Err(error) => {
                s.control_action.message = failed(id).into();
                s.control_action.error = error.clone();
            }
            Ok(value) => {
                s.control_action.message = match id {
                    "stream.displays.refresh" => "屏幕列表已刷新".into(),
                    "stream.display.select" => "已发送屏幕切换".into(),
                    "stream.shortcut" | "stream.window.move" => "已发送快捷键".into(),
                    "stream.stats.toggle" => {
                        s.show_stats = !s.show_stats;
                        if s.show_stats {
                            "统计已开启"
                        } else {
                            "统计已关闭"
                        }
                        .into()
                    }
                    "stream.audio.toggle" => {
                        s.audio_enabled = value["enabled"] == true;
                        if s.audio_enabled {
                            "声音已开启"
                        } else {
                            "声音已关闭"
                        }
                        .into()
                    }
                    "stream.audio.volume" => {
                        s.volume = value["volume"]
                            .as_u64()
                            .unwrap_or_else(|| args["volume"].as_u64().unwrap())
                            as u32;
                        format!("音量 {}%", s.volume)
                    }
                    _ => String::new(),
                };
                if id == "stream.shortcut" && s.stream_menu == "shortcuts"
                    || id == "stream.display.select" && s.stream_menu == "displays"
                {
                    s.stream_menu.clear();
                }
            }
        }
        s.revision += 1;
        result
    }
}
