//! Cancellation belongs to one admitted update, independently of its manager lock.
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use tokio_util::sync::CancellationToken;

pub(crate) type Snapshot = Arc<Mutex<Value>>;
struct Flight {
    id: u64,
    token: CancellationToken,
    cancellable: bool,
    running: bool,
    action: String,
}
pub(crate) struct Control {
    flight: Mutex<Option<Flight>>,
    live: Snapshot,
}
impl Control {
    pub fn new(live: Snapshot) -> Self {
        Self {
            flight: Mutex::new(None),
            live,
        }
    }
    pub fn begin(&self, id: u64, action: &str, cancellable: bool) -> Result<(), String> {
        let mut slot = self.flight.lock().unwrap();
        if slot.as_ref().is_some_and(|old| old.running) {
            return Err("上一更新尚未清理完成".into());
        }
        *slot = Some(Flight {
            id,
            token: CancellationToken::new(),
            cancellable,
            running: true,
            action: action.into(),
        });
        let mut live = self.live.lock().unwrap();
        live["operationId"] = json!(id);
        live["cancelRequested"] = json!(false);
        live["cancelledAction"] = json!("");
        Ok(())
    }
    pub fn token(&self) -> CancellationToken {
        self.flight
            .lock()
            .unwrap()
            .as_ref()
            .expect("update must be admitted before executing")
            .token
            .clone()
    }
    pub fn cancel(&self, id: u64) -> Result<(), String> {
        let slot = self.flight.lock().unwrap();
        let flight = slot
            .as_ref()
            .filter(|flight| flight.id == id)
            .ok_or("更新操作已变化，未执行取消")?;
        if !flight.cancellable {
            return Err("安装交接已开始，无法取消".into());
        }
        flight.token.cancel();
        let mut live = self.live.lock().unwrap();
        live["cancelRequested"] = json!(true);
        if flight.running {
            live["phase"] = json!("cancelling");
            live["status"] = json!("正在取消更新…");
        } else {
            cancelled(&mut live, &flight.action);
        }
        Ok(())
    }
    pub fn finish(&self) -> bool {
        let mut slot = self.flight.lock().unwrap();
        let flight = slot.as_mut().unwrap();
        flight.running = false;
        let cancelled_now = flight.token.is_cancelled();
        if cancelled_now {
            cancelled(&mut self.live.lock().unwrap(), &flight.action);
        }
        cancelled_now
    }
}
fn cancelled(live: &mut Value, action: &str) {
    live["phase"] = json!("cancelled");
    live["failedAction"] = json!("");
    live["cancelledAction"] = json!(action);
    if live["pendingRestart"] != true {
        live["progress"] = json!(0);
    }
    live["status"] = json!(if live["pendingRestart"] == true {
        "操作已取消，更新包已保留"
    } else if action == "updates.check" {
        "已取消检查更新"
    } else {
        "已取消下载，未安装更新"
    });
}

pub(crate) fn run<T>(
    token: &CancellationToken,
    future: impl std::future::Future<Output = Result<T, velopack::Error>>,
) -> Result<T, velopack::Error> {
    if token.is_cancelled() {
        return Err(velopack::Error::Other("Update operation cancelled".into()));
    }
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let result = runtime.block_on(async {
        tokio::select! {
            biased;
            _=token.cancelled()=>Err(velopack::Error::Other("Update operation cancelled".into())),
            value=future=>value,
        }
    });
    // The OS resolver may still finish a blocking DNS call; it owns no update
    // file. Do not hold cancellation completion until that system call returns.
    runtime.shutdown_background();
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancelling_pending_io_drops_the_future_and_cannot_cancel_the_next_request() {
        let live = Arc::new(Mutex::new(json!({})));
        let control = Arc::new(Control::new(live.clone()));
        control.begin(4, "updates.install", true).unwrap();
        let worker = control.clone();
        let (ready, started) = std::sync::mpsc::channel();
        let (dropped, observed) = std::sync::mpsc::channel();
        struct Pending {
            dropped: std::sync::mpsc::Sender<()>,
        }
        impl Drop for Pending {
            fn drop(&mut self) {
                self.dropped.send(()).unwrap();
            }
        }
        let thread = std::thread::spawn(move || {
            let pending = Pending { dropped };
            run(&worker.token(), async move {
                let _pending = pending;
                ready.send(()).unwrap();
                std::future::pending::<Result<(), velopack::Error>>().await
            })
        });
        started
            .recv_timeout(std::time::Duration::from_secs(2))
            .unwrap();
        control.cancel(4).unwrap();
        observed
            .recv_timeout(std::time::Duration::from_secs(2))
            .unwrap();
        assert!(thread.join().unwrap().is_err());
        assert!(control.finish());
        assert_eq!(live.lock().unwrap()["phase"], "cancelled");
        control.begin(5, "updates.check", true).unwrap();
        assert!(control.cancel(4).is_err());
        assert!(!control.token().is_cancelled());
        control.finish();
        control.begin(6, "updates.install", false).unwrap();
        assert!(control.cancel(6).is_err());
        assert!(!control.token().is_cancelled());
        control.finish();
    }
    #[test]
    fn accepted_cancel_after_worker_completion_preserves_the_verified_package() {
        let live = Arc::new(Mutex::new(json!({"pendingRestart":true})));
        let control = Control::new(live.clone());
        control.begin(1, "updates.install", true).unwrap();
        assert!(!control.finish());
        control.cancel(1).unwrap();
        let state = live.lock().unwrap();
        assert_eq!(state["phase"], "cancelled");
        assert_eq!(state["pendingRestart"], true);
    }
}
