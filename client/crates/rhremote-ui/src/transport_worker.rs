//! A private stdio worker keeps QUIC alive when the graphical UI terminates.
use rhremote_transport::{Address, ProxyInfo};
use serde::{Deserialize, Serialize};
use std::os::windows::{fs::OpenOptionsExt, process::CommandExt};
use std::{
    io::{BufRead, BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};

const FLAG: &str = "--rhremote-transport-worker";
#[derive(Serialize, Deserialize)]
struct Request {
    key: PathBuf,
    remote: Address,
    relay_only: bool,
}
#[derive(Serialize, Deserialize)]
struct Reply {
    info: Option<ProxyInfo>,
    error: Option<String>,
}

#[derive(Default)]
pub(crate) struct Client {
    child: Option<Child>,
    input: Option<ChildStdin>,
    failure: Option<String>,
    active: bool,
}
impl Client {
    pub fn new() -> Result<Self, String> {
        Ok(Self::default())
    }
    pub fn start_cancelable(
        &mut self,
        key: &Path,
        remote: Address,
        relay_only: bool,
        cancel: &AtomicBool,
    ) -> Result<ProxyInfo, String> {
        self.stop();
        if let Some(error) = self.failure.take() {
            return Err(error);
        }
        remote.validate().map_err(|e| e.to_string())?;
        if cancel.load(Ordering::SeqCst) {
            return Err("连接已取消".into());
        }
        let mut child = Command::new(std::env::current_exe().map_err(|e| e.to_string())?)
            .arg(FLAG)
            .arg(std::process::id().to_string())
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .map_err(|e| e.to_string())?;
        let mut input = child.stdin.take().ok_or("Transport worker input missing")?;
        let output = child
            .stdout
            .take()
            .ok_or("Transport worker output missing")?;
        self.child = Some(child);
        let request = Request {
            key: key.into(),
            remote,
            relay_only,
        };
        let written = serde_json::to_writer(&mut input, &request)
            .map_err(|e| e.to_string())
            .and_then(|_| input.write_all(b"\n").map_err(|e| e.to_string()))
            .and_then(|_| input.flush().map_err(|e| e.to_string()));
        self.input = Some(input);
        if let Err(error) = written {
            self.stop();
            return Err(error);
        }
        let (send, receive) = mpsc::channel();
        std::thread::spawn(move || {
            let mut line = String::new();
            let reply = BufReader::new(output)
                .take(65_537)
                .read_line(&mut line)
                .map_err(|e| e.to_string())
                .and_then(|_| {
                    if line.len() > 65_536 {
                        return Err("Transport worker response exceeds its bound".into());
                    }
                    serde_json::from_str::<Reply>(&line)
                        .map_err(|_| "Transport worker did not return a valid response".into())
                });
            let _ = send.send(reply);
        });
        let until = Instant::now() + Duration::from_secs(40);
        loop {
            if cancel.load(Ordering::SeqCst) {
                self.stop();
                return Err("连接已取消".into());
            }
            match receive.recv_timeout(Duration::from_millis(20)) {
                Ok(Ok(Reply {
                    info: Some(info),
                    error: None,
                })) => {
                    self.active = true;
                    return Ok(info);
                }
                Ok(Ok(reply)) => {
                    self.stop();
                    return Err(reply
                        .error
                        .unwrap_or("Transport worker returned no route".into()));
                }
                Ok(Err(error)) => {
                    self.stop();
                    return Err(error);
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    self.stop();
                    return Err("Transport worker stopped unexpectedly".into());
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
            }
            if Instant::now() >= until {
                self.stop();
                return Err("Transport worker startup timed out".into());
            }
        }
    }
    pub fn stop(&mut self) {
        let active = std::mem::replace(&mut self.active, false);
        self.input.take();
        if let Some(mut child) = self.child.take() {
            let until = Instant::now() + Duration::from_secs(25);
            loop {
                match child.try_wait() {
                    Ok(Some(status)) => {
                        let _ = child.wait();
                        if active && !status.success() {
                            self.failure =
                                Some("Transport worker could not confirm its cleanup".into());
                        }
                        break;
                    }
                    Ok(None) if Instant::now() < until => {
                        std::thread::sleep(Duration::from_millis(10))
                    }
                    _ => {
                        let _ = child.kill();
                        let _ = child.wait();
                        self.failure =
                            Some("Transport worker did not finish its bounded cleanup".into());
                        break;
                    }
                }
            }
        }
    }
}
impl Drop for Client {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Returns None in the graphical product entry point. No UI or updater starts
/// in the worker; its anonymous pipes are owned only by the spawning UI.
pub fn run_from_args() -> Option<Result<(), String>> {
    let args: Vec<_> = std::env::args().collect();
    if args.get(1).map(String::as_str) != Some(FLAG) {
        return None;
    }
    Some((|| {
        if args.len() != 3 {
            return Err("Invalid transport worker arguments".into());
        }
        let owner = crate::transport_owner::Owner::capture(
            args[2].parse().map_err(|_| "Invalid transport owner")?,
        )?;
        let mut input = BufReader::new(std::io::stdin());
        let mut line = String::new();
        input
            .by_ref()
            .take(65_537)
            .read_line(&mut line)
            .map_err(|e| e.to_string())?;
        if line.len() > 65_536 {
            return Err("Transport worker request exceeds its bound".into());
        }
        let request: Request =
            serde_json::from_str(&line).map_err(|_| "Invalid transport worker request")?;
        request.remote.validate().map_err(|e| e.to_string())?;
        let cancel = Arc::new(AtomicBool::new(false));
        let closed = cancel.clone();
        std::thread::spawn(move || {
            let mut byte = [0u8; 1];
            let _ = input.read(&mut byte);
            closed.store(true, Ordering::SeqCst);
        });
        let lock_path = request.key.with_extension("worker.lock");
        let _identity_lease = identity_lease(&lock_path, &cancel, Duration::from_secs(20))?;
        let mut client = rhremote_transport::Client::new().map_err(|e| e.to_string())?;
        let started = client
            .start_cancelable(&request.key, request.remote, request.relay_only, &cancel)
            .map_err(|e| e.to_string());
        let ready = started.is_ok();
        let reply = match started {
            Ok(info) => Reply {
                info: Some(info),
                error: None,
            },
            Err(error) => Reply {
                info: None,
                error: Some(error),
            },
        };
        let mut output = std::io::stdout();
        let _ = serde_json::to_writer(&mut output, &reply);
        let _ = output.write_all(b"\n");
        let _ = output.flush();
        if ready {
            while !cancel.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_millis(20));
            }
            // Keep the proxy running while the native owner-exit guard sends its
            // paired per-request cancellation over this exact transport.
            let drained = owner.drain(Duration::from_secs(20));
            client.stop();
            drained?;
        }
        Ok(())
    })())
}

fn identity_lease(
    path: &Path,
    cancel: &AtomicBool,
    limit: Duration,
) -> Result<std::fs::File, String> {
    let until = Instant::now() + limit;
    loop {
        if cancel.load(Ordering::SeqCst) {
            return Err("Transport identity wait cancelled".into());
        }
        match std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .share_mode(0)
            .open(path)
        {
            Ok(file) => return Ok(file),
            Err(error) if error.raw_os_error() == Some(32) && Instant::now() < until => {
                std::thread::sleep(Duration::from_millis(20))
            }
            Err(error) => return Err(format!("Cannot acquire transport identity lease: {error}")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identity_cannot_be_reopened_until_the_old_worker_releases_it() {
        let path = std::env::temp_dir().join(format!(
            "rhremote-worker-{}.lock",
            rhremote_core::random_token().unwrap()
        ));
        let cancel = AtomicBool::new(false);
        let first = identity_lease(&path, &cancel, Duration::from_secs(1)).unwrap();
        assert!(identity_lease(&path, &cancel, Duration::from_millis(40)).is_err());
        let (send, receive) = mpsc::channel();
        let release = std::thread::spawn(move || {
            receive.recv().unwrap();
            std::thread::sleep(Duration::from_millis(150));
            drop(first);
        });
        let began = Instant::now();
        send.send(()).unwrap();
        let second = identity_lease(&path, &cancel, Duration::from_secs(2)).unwrap();
        assert!(began.elapsed() >= Duration::from_millis(100));
        drop(second);
        release.join().unwrap();
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn identity_wait_can_be_cancelled_without_disturbing_its_current_owner() {
        let path = std::env::temp_dir().join(format!(
            "rhremote-worker-{}.lock",
            rhremote_core::random_token().unwrap()
        ));
        let cancel = AtomicBool::new(false);
        let first = identity_lease(&path, &cancel, Duration::from_secs(1)).unwrap();
        cancel.store(true, Ordering::SeqCst);
        let began = Instant::now();
        assert!(identity_lease(&path, &cancel, Duration::from_secs(2)).is_err());
        assert!(began.elapsed() < Duration::from_millis(200));
        cancel.store(false, Ordering::SeqCst);
        assert!(identity_lease(&path, &cancel, Duration::from_millis(20)).is_err());
        drop(first);
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn startup_failure_is_not_misclassified_as_a_failed_active_session_cleanup() {
        for active in [false, true] {
            let child = Command::new("cmd.exe")
                .args(["/d", "/c", "exit 7"])
                .creation_flags(0x08000000)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap();
            let mut client = Client::default();
            client.child = Some(child);
            client.active = active;
            client.stop();
            assert_eq!(client.failure.is_some(), active);
        }
    }
}
