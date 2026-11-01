//! Bounded read-only discovery. ureq's default connect timeout overrides Request::timeout.
use std::{
    io::{self, Read},
    net::{SocketAddr, ToSocketAddrs},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};

fn resolve_with<F>(cancel: &AtomicBool, deadline: Instant, work: F) -> io::Result<Vec<SocketAddr>>
where
    F: FnOnce() -> io::Result<Vec<SocketAddr>> + Send + 'static,
{
    if cancel.load(Ordering::SeqCst) {
        return Err(io::ErrorKind::Interrupted.into());
    }
    if Instant::now() >= deadline {
        return Err(io::ErrorKind::TimedOut.into());
    }
    let (send, receive) = mpsc::sync_channel(1);
    std::thread::Builder::new()
        .name("RHRemote device DNS".into())
        .spawn(move || {
            let _ = send.send(work());
        })?;
    loop {
        if cancel.load(Ordering::SeqCst) {
            return Err(io::ErrorKind::Interrupted.into());
        }
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(io::ErrorKind::TimedOut.into());
        }
        match receive.recv_timeout(left.min(Duration::from_millis(25))) {
            Ok(value) => return value,
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(_) => return Err(io::ErrorKind::Other.into()),
        }
    }
}
struct Resolver {
    cancel: Arc<AtomicBool>,
    deadline: Instant,
}
pub(crate) fn resolve_cancelable(address:&str,port:u16,cancel:&AtomicBool,timeout:Duration)->io::Result<Vec<SocketAddr>>{
    if let Ok(ip)=address.parse::<std::net::IpAddr>(){return Ok(vec![SocketAddr::new(ip,port)]);}
    let address=address.to_owned();resolve_with(cancel,Instant::now()+timeout,move||(address.as_str(),port).to_socket_addrs().map(Iterator::collect))
}
impl ureq::Resolver for Resolver {
    fn resolve(&self, netloc: &str) -> io::Result<Vec<SocketAddr>> {
        if self.cancel.load(Ordering::SeqCst) {
            return Err(io::ErrorKind::Interrupted.into());
        }
        if let Ok(address) = netloc.parse::<SocketAddr>() {
            return Ok(vec![address]);
        }
        let name = netloc.to_owned();
        resolve_with(&self.cancel, self.deadline, move || {
            name.to_socket_addrs().map(Iterator::collect)
        })
    }
}
pub(crate) fn server_info(
    address: &str,
    cancel: Arc<AtomicBool>,
    timeout: Duration,
) -> Result<String, String> {
    server_info_at(address,47989,cancel,timeout,262144)
}
pub(crate) fn server_info_at(address:&str,port:u16,cancel:Arc<AtomicBool>,timeout:Duration,limit:u64)->Result<String,String>{
    if cancel.load(Ordering::SeqCst) {
        return Err("已取消添加".into());
    }
    let host = if address.parse::<std::net::Ipv6Addr>().is_ok() {
        format!("[{address}]")
    } else {
        address.to_owned()
    };
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(timeout)
        .timeout(timeout)
        .redirects(0)
        .resolver(Resolver {
            cancel: cancel.clone(),
            deadline: Instant::now() + timeout,
        })
        .build();
    let response = agent
        .get(&format!(
            "http://{host}:{port}/serverinfo?uniqueid=0123456789ABCDEF"
        ))
        .call()
        .map_err(|error| match error {
            ureq::Error::Transport(ref failure) if failure.kind() == ureq::ErrorKind::Dns => {
                "无法解析主机名，请检查地址和网络".to_string()
            }
            _ => "无法访问设备，请确认地址、网络和 Sunshine 状态".to_string(),
        })?;
    let mut body = String::new();
    response
        .into_reader()
        .take(limit+1)
        .read_to_string(&mut body)
        .map_err(|_| "读取设备信息失败".to_string())?;
    if cancel.load(Ordering::SeqCst) {
        return Err("已取消添加".into());
    }
    Ok(body)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ureq::Resolver as _;
    #[test]
    fn slow_dns_has_a_deadline_and_its_late_result_is_discarded() {
        let cancel = AtomicBool::new(false);
        let start = Instant::now();
        let error = resolve_with(&cancel, start + Duration::from_millis(40), || {
            std::thread::sleep(Duration::from_millis(250));
            Ok(vec![])
        })
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        assert!(start.elapsed() < Duration::from_millis(200));
    }
    #[test]
    fn cancellation_releases_dns_wait_without_waiting_for_os_resolution() {
        let cancel = Arc::new(AtomicBool::new(false));
        let stop = cancel.clone();
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(30));
            stop.store(true, Ordering::SeqCst);
        });
        let start = Instant::now();
        let error = resolve_with(&cancel, start + Duration::from_secs(2), || {
            std::thread::sleep(Duration::from_millis(250));
            Ok(vec![])
        })
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Interrupted);
        assert!(start.elapsed() < Duration::from_millis(200));
    }
    #[test]
    fn numeric_addresses_do_not_start_dns_work() {
        let resolver = Resolver {
            cancel: Arc::new(AtomicBool::new(false)),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        assert_eq!(
            resolver.resolve("100.73.139.109:47989").unwrap()[0],
            "100.73.139.109:47989".parse().unwrap()
        );
        assert_eq!(
            resolver.resolve("[::1]:47989").unwrap()[0],
            "[::1]:47989".parse().unwrap()
        );
    }
}
