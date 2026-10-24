//! Graceful teardown owns the actual child and its transport lease until exit.
use serde_json::{Value, json};
use std::{
    fs,
    path::PathBuf,
    process::Child,
    sync::{Arc, Mutex},
    thread::JoinHandle,
    time::{Duration, Instant},
};

#[derive(Clone)]
pub(crate) struct Context {
    pub port: u16,
    pub token: String,
    pub status: PathBuf,
    pub limit: Duration,
}

pub(crate) struct Report {
    pub kind: &'static str,
    pub pid: u32,
    pub forced: bool,
    pub confirmed: bool,
    pub error: Option<String>,
}

#[derive(Clone, Default)]
pub(crate) struct Queue {
    jobs: Arc<Mutex<Vec<Task>>>,
    failure: Arc<Mutex<Option<String>>>,
}

enum Task {
    Native(JoinHandle<Report>),
    Pairing {
        job: JoinHandle<bool>,
        _lease: Box<dyn Send>,
    },
}
impl Task {
    fn is_finished(&self) -> bool {
        match self {
            Self::Native(job) => job.is_finished(),
            Self::Pairing { job, .. } => job.is_finished(),
        }
    }
    fn finish(self) -> Report {
        match self {
            Self::Native(job) => job.join().unwrap_or(Report {
                kind: "native",
                pid: 0,
                forced: false,
                confirmed: false,
                error: Some("原生清理任务异常结束，服务端清理未确认".into()),
            }),
            Self::Pairing { job, _lease } => {
                let confirmed = job.join().unwrap_or(false);
                Report {
                    kind: "pairing",
                    pid: 0,
                    forced: false,
                    confirmed,
                    error: (!confirmed).then(|| "配对取消未确认".into()),
                }
            }
        }
    }
}

// Also closes the process if starting the cleanup thread fails. Never looks up
// an arbitrary PID or sends a command to an unauthenticated control endpoint.
struct Guard<L> {
    child: Option<Child>,
    _lease: L,
}
impl<L> Guard<L> {
    fn stop(&mut self, context: &Context) -> Report {
        stop(self.child.as_mut().unwrap(), context)
    }
}
impl<L> Drop for Guard<L> {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            if child.try_wait().ok().flatten().is_none() {
                let _ = child.kill();
            }
            let _ = child.wait();
        }
    }
}

impl Queue {
    pub fn coordinate<L: Send + 'static, F: FnOnce() + Send + 'static>(
        &self,
        lease: L,
        operation: F,
    ) -> bool {
        match std::thread::Builder::new()
            .name("cleanup-coordinator".into())
            .spawn(move || {
                let _lease = lease;
                operation();
                Report {
                    kind: "shutdown",
                    pid: 0,
                    forced: false,
                    confirmed: true,
                    error: None,
                }
            }) {
            Ok(job) => {
                self.jobs.lock().unwrap().push(Task::Native(job));
                true
            }
            Err(_) => {
                *self.failure.lock().unwrap() = Some("无法启动清理协调任务".into());
                false
            }
        }
    }
    pub fn enqueue<L: Send + 'static>(&self, child: Child, context: Context, lease: L) {
        let mut guard = Guard {
            child: Some(child),
            _lease: lease,
        };
        let job = std::thread::Builder::new()
            .name("native-cleanup".into())
            .spawn(move || guard.stop(&context));
        match job {
            Ok(job) => self.jobs.lock().unwrap().push(Task::Native(job)),
            Err(_) => {
                *self.failure.lock().unwrap() =
                    Some("无法启动原生清理任务，服务端清理未确认".into())
            }
        }
    }

    pub fn track_pairing<L: Send + 'static>(&self, job: JoinHandle<bool>, lease: L) {
        self.jobs.lock().unwrap().push(Task::Pairing {
            job,
            _lease: Box::new(lease),
        });
    }

    pub fn reap(&self) -> Vec<Report> {
        let mut jobs = self.jobs.lock().unwrap();
        let mut reports = Vec::new();
        let mut index = 0;
        while index < jobs.len() {
            if jobs[index].is_finished() {
                let report = jobs.swap_remove(index).finish();
                if let Some(error) = &report.error {
                    *self.failure.lock().unwrap() = Some(error.clone());
                }
                reports.push(report);
            } else {
                index += 1;
            }
        }
        reports
    }

    pub fn pending(&self) -> bool {
        !self.jobs.lock().unwrap().is_empty()
    }

    pub fn failure(&self) -> Option<String> {
        self.failure.lock().unwrap().clone()
    }

    pub fn clear_failure(&self) {
        *self.failure.lock().unwrap() = None;
    }
}

fn status(context: &Context, pid: u32) -> Option<Value> {
    if fs::metadata(&context.status).ok()?.len() > 65_536 {
        return None;
    }
    let value: Value = serde_json::from_slice(&fs::read(&context.status).ok()?).ok()?;
    (value["processId"].as_u64() == Some(u64::from(pid))).then_some(value)
}

fn stop(child: &mut Child, context: &Context) -> Report {
    let pid = child.id();
    let deadline = Instant::now() + context.limit;
    let agent = ureq::AgentBuilder::new()
        .try_proxy_from_env(false)
        .redirects(0)
        .timeout_connect(Duration::from_millis(100))
        .timeout(Duration::from_millis(250))
        .build();
    let mut dispatched = false;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => {
                let _ = child.wait();
                let final_state = status(context, pid);
                let confirmed = final_state.as_ref().is_some_and(|s| {
                    s["transportReleased"] == true
                        && s["phase"] == "transport_closed"
                        && matches!(
                            s["launchCleanupStatus"].as_str(),
                            Some(
                                "none"
                                    | "unsupported"
                                    | "cancelled"
                                    | "finished"
                                    | "handed_off"
                                    | "expired"
                            )
                        )
                });
                return Report {
                    kind: "native",
                    pid,
                    forced: false,
                    confirmed,
                    error: (!confirmed).then(|| "原生进程已退出，服务端清理未确认".into()),
                };
            }
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return Report {
                    kind: "native",
                    pid,
                    forced: true,
                    confirmed: false,
                    error: Some("无法确认本次原生进程是否结束".into()),
                };
            }
            Ok(None) => {}
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            let error = "原生清理超时，已结束本次进程；服务端清理未确认";
            let _ = fs::write(&context.status, json!({"processId":pid,"streaming":false,"phase":"error","error":error,"routeFatal":true,"nativeCleanupConfirmed":false}).to_string());
            return Report {
                kind: "native",
                pid,
                forced: true,
                confirmed: false,
                error: Some(error.into()),
            };
        }
        if !dispatched {
            let state: Option<Value> = agent
                .get(&format!("http://127.0.0.1:{}/v1/state", context.port))
                .set("Authorization", &format!("Bearer {}", context.token))
                .call()
                .ok()
                .and_then(|r| r.into_json().ok());
            if state
                .as_ref()
                .is_some_and(|s| s["processId"].as_u64() == Some(u64::from(pid)))
            {
                dispatched = agent
                    .post(&format!("http://127.0.0.1:{}/v1/disconnect", context.port))
                    .set("Authorization", &format!("Bearer {}", context.token))
                    .send_json(json!({}))
                    .ok()
                    .and_then(|r| r.into_json::<Value>().ok())
                    .is_some_and(|v| v["ok"] == true);
            }
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

#[cfg(all(test, windows))]
#[path = "native_cleanup_tests.rs"]
pub(crate) mod tests;
