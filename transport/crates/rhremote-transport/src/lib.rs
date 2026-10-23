//! Application-scoped, authenticated QUIC tunnels. No VPN adapter or system routes.
//! The native Sunshine pairing remains authoritative for media authorization.
#[cfg(target_os = "android")]
mod android;
#[cfg(any(target_os = "android", test))]
mod cancellation;
pub mod grant;
use anyhow::{Context, Result, bail, ensure};
use iroh::{
    Endpoint, EndpointAddr, EndpointId, RelayMode, RelayUrl, SecretKey,
    endpoint::{Connection, presets},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::Path,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpSocket, UdpSocket},
    sync::Semaphore,
    task::JoinSet,
};

pub const ALPN: &[u8] = b"rhremote/tunnel/1";
pub const DEFAULT_RELAY: &str = "https://101.43.203.147:33449/";
pub const TCP_PORTS: &[u16] = &[47984, 47989, 47995, 48010];
pub const UDP_PORTS: &[u16] = &[47995, 47998, 47999, 48000, 48002, 48010];
pub const DEFAULT_OFFSET: u16 = 1000;
const MAX_FLOWS: usize = 32;
const MAX_PACKET: usize = 1150;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Address {
    pub endpoint_id: String,
    pub relay_url: String,
}
impl Address {
    pub fn validate(&self) -> Result<(EndpointId, RelayUrl)> {
        let id = self
            .endpoint_id
            .parse()
            .context("Invalid transport endpoint identity")?;
        let url: RelayUrl = self.relay_url.parse().context("Invalid relay URL")?;
        ensure!(
            matches!(url.scheme(), "https" | "http")
                && url.username().is_empty()
                && url.password().is_none(),
            "Relay must be an HTTP(S) URL without embedded credentials"
        );
        Ok((id, url))
    }
}
/// A certificate grant is only inserted after a successful paired mTLS request.
/// The callback rechecks Sunshine revocation for established connections.
pub struct Grants {
    entries: Mutex<HashMap<EndpointId, (Vec<u8>, Instant)>>,
    paired: Arc<dyn Fn(&[u8]) -> bool + Send + Sync>,
    persistence: Option<std::path::PathBuf>,
}
impl Grants {
    pub fn new(paired: impl Fn(&[u8]) -> bool + Send + Sync + 'static) -> Arc<Self> {
        Arc::new(Self {
            entries: Mutex::new(HashMap::new()),
            paired: Arc::new(paired),
            persistence: None,
        })
    }
    pub fn persistent(
        path: std::path::PathBuf,
        paired: impl Fn(&[u8]) -> bool + Send + Sync + 'static,
    ) -> Result<Arc<Self>> {
        let mut entries = HashMap::new();
        if path.exists() {
            let bytes = std::fs::read(&path)?;
            ensure!(bytes.len() <= 262144, "Transport grants file too large");
            let stored: HashMap<String, Vec<u8>> = serde_json::from_slice(&bytes)?;
            ensure!(stored.len() <= 64, "Too many stored transport grants");
            for (id, cert) in stored {
                let id: EndpointId = id.parse()?;
                if paired(&cert) {
                    entries.insert(id, (cert, Instant::now()));
                }
            }
        }
        Ok(Arc::new(Self {
            entries: Mutex::new(entries),
            paired: Arc::new(paired),
            persistence: Some(path),
        }))
    }
    pub fn authorize(&self, id: &str, certificate: &[u8]) -> Result<()> {
        let id: EndpointId = id.parse()?;
        ensure!(
            (self.paired)(certificate),
            "Sunshine pairing is disabled or revoked"
        );
        let mut entries = self.entries.lock().unwrap();
        entries.retain(|_, (_, at)| at.elapsed() < Duration::from_secs(3600));
        ensure!(
            entries.contains_key(&id) || entries.len() < 64,
            "Too many active transport grants"
        );
        entries.insert(id, (certificate.into(), Instant::now()));
        if let Some(path) = &self.persistence {
            let stored: HashMap<String, Vec<u8>> = entries
                .iter()
                .map(|(id, (cert, _))| (id.to_string(), cert.clone()))
                .collect();
            let temporary = path.with_extension("tmp");
            std::fs::write(&temporary, serde_json::to_vec(&stored)?)?;
            std::fs::rename(temporary, path)?;
        }
        Ok(())
    }
    pub fn allowed(&self, id: EndpointId) -> bool {
        let entry = self.entries.lock().unwrap().get(&id).cloned();
        let allowed = entry.is_some_and(|(cert, at)| {
            at.elapsed() < Duration::from_secs(3600) && (self.paired)(&cert)
        });
        if allowed {
            if let Some((_, at)) = self.entries.lock().unwrap().get_mut(&id) {
                *at = Instant::now();
            }
        }
        allowed
    }
    pub fn enrolled(&self) -> Vec<String> {
        let ids: Vec<_> = self.entries.lock().unwrap().keys().copied().collect();
        ids.into_iter()
            .filter(|id| self.allowed(*id))
            .map(|id| id.to_string())
            .collect()
    }
}
pub fn key(path: &Path) -> Result<SecretKey> {
    if let Ok(bytes) = std::fs::read(path) {
        let bytes: [u8; 32] = bytes
            .try_into()
            .map_err(|_| anyhow::anyhow!("Invalid transport key length"))?;
        return Ok(SecretKey::from_bytes(&bytes));
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let secret = SecretKey::generate();
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    use std::io::Write;
    options.open(path)?.write_all(&secret.to_bytes())?;
    Ok(secret)
}
pub async fn endpoint(secret: SecretKey, relay: &str, relay_only: bool) -> Result<Endpoint> {
    let url: RelayUrl = relay.parse()?;
    let mut builder = Endpoint::builder(presets::Minimal)
        .secret_key(secret)
        .alpns(vec![ALPN.to_vec()])
        .relay_mode(RelayMode::Custom(url.into()));
    if relay_only {
        builder = builder.clear_ip_transports();
    }
    Ok(builder.bind().await?)
}
pub async fn connect(endpoint: &Endpoint, remote: &Address) -> Result<Connection> {
    let (id, url) = remote.validate()?;
    Ok(tokio::time::timeout(
        Duration::from_secs(10),
        endpoint.connect(EndpointAddr::new(id).with_relay_url(url), ALPN),
    )
    .await??)
}
fn frame(port: u16, client: u16, data: &[u8]) -> bytes::Bytes {
    let mut bytes = Vec::with_capacity(data.len() + 4);
    bytes.extend_from_slice(&port.to_be_bytes());
    bytes.extend_from_slice(&client.to_be_bytes());
    bytes.extend_from_slice(data);
    bytes.into()
}
fn parse_frame(bytes: &[u8]) -> Result<(u16, u16, &[u8])> {
    ensure!(
        bytes.len() >= 4 && bytes.len() <= MAX_PACKET + 4,
        "Invalid datagram length"
    );
    let port = u16::from_be_bytes([bytes[0], bytes[1]]);
    let client = u16::from_be_bytes([bytes[2], bytes[3]]);
    ensure!(
        UDP_PORTS.contains(&port) && client != 0,
        "Forbidden UDP destination"
    );
    Ok((port, client, &bytes[4..]))
}
/// Only the existing authenticated HTTPS services and server-info are reachable
/// before pairing authorization. Management port 47990 is never tunnelled.
fn tcp_allowed(port: u16, authorized: bool) -> bool {
    TCP_PORTS.contains(&port) && (matches!(port, 47984 | 47989 | 47995) || authorized)
}
async fn forward_tcp(
    connection: Connection,
    mut socket: tokio::net::TcpStream,
    port: u16,
) -> Result<()> {
    socket.set_nodelay(true)?;
    let (mut send, mut receive) = connection.open_bi().await?;
    send.write_all(&port.to_be_bytes()).await?;
    let (mut reader, mut writer) = socket.split();
    tokio::try_join!(
        async {
            tokio::io::copy(&mut reader, &mut send).await?;
            send.finish()?;
            Ok::<_, anyhow::Error>(())
        },
        async {
            // Pipeline the native request with its port header. The host still
            // authorizes before connecting to Sunshine; consume its status before
            // exposing any response. Waiting here avoids an extra RTT per socket.
            ensure!(
                receive.read_u8().await? == 0,
                "Remote transport rejected TCP channel"
            );
            tokio::io::copy(&mut receive, &mut writer).await?;
            writer.shutdown().await?;
            Ok::<_, anyhow::Error>(())
        }
    )?;
    Ok(())
}
/// Loopback ports use an offset so the client can also run its own Sunshine.
/// A proxy lives exactly as long as its handle and never binds a LAN interface.
pub struct Proxy {
    pub address: String,
    pub endpoint_id: String,
    pub offset: u16,
    pub connection: Connection,
    jobs: JoinSet<()>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProxyInfo {
    pub address: String,
    pub port_offset: u16,
    pub endpoint_id: String,
    pub path: String,
    pub rtt_ms: f64,
    pub relay_url: String,
}
/// A per-application runtime retained between native engine processes.
pub struct Client {
    runtime: tokio::runtime::Runtime,
    current: Option<(Endpoint, Proxy, Address, bool)>,
}
impl Client {
    pub fn new() -> Result<Self> {
        Ok(Self {
            runtime: tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()?,
            current: None,
        })
    }
    pub fn start(
        &mut self,
        key_file: &Path,
        remote: Address,
        relay_only: bool,
    ) -> Result<ProxyInfo> {
        self.start_cancelable(
            key_file,
            remote,
            relay_only,
            &std::sync::atomic::AtomicBool::new(false),
        )
    }
    pub fn start_cancelable(
        &mut self,
        key_file: &Path,
        remote: Address,
        relay_only: bool,
        cancel: &std::sync::atomic::AtomicBool,
    ) -> Result<ProxyInfo> {
        ensure!(
            !cancel.load(std::sync::atomic::Ordering::SeqCst),
            "Connection cancelled"
        );
        remote.validate()?;
        let reusable = self.current.as_ref().is_some_and(|(_, p, a, r)| {
            a.endpoint_id == remote.endpoint_id
                && a.relay_url == remote.relay_url
                && *r == relay_only
                && p.connection.close_reason().is_none()
        });
        if !reusable {
            self.stop();
            let secret = key(key_file)?;
            let (endpoint, proxy) = self.runtime.block_on(async {
                let cancelled = async {
                    while !cancel.load(std::sync::atomic::Ordering::SeqCst) {
                        tokio::time::sleep(Duration::from_millis(20)).await;
                    }
                };
                tokio::pin!(cancelled);
                let endpoint = tokio::select! {
                    result=endpoint(secret, &remote.relay_url, relay_only)=>result?,
                    _=&mut cancelled=>bail!("Connection cancelled"),
                };
                let result = async {
                    let proxy = tokio::select! {
                        proxy=Proxy::start(&endpoint,&remote,DEFAULT_OFFSET)=>proxy?,
                        _=&mut cancelled=>bail!("Connection cancelled"),
                    };
                    if !relay_only {
                        let until = Instant::now() + Duration::from_millis(1200);
                        while !proxy.connection.paths().iter().any(|p| p.is_selected() && p.is_ip())
                            && Instant::now() < until
                        {
                            ensure!(!cancel.load(std::sync::atomic::Ordering::SeqCst), "Connection cancelled");
                            tokio::time::sleep(Duration::from_millis(30)).await;
                        }
                    }
                    ensure!(!cancel.load(std::sync::atomic::Ordering::SeqCst), "Connection cancelled");
                    Ok::<_, anyhow::Error>(proxy)
                }.await;
                match result {
                    Ok(proxy)=>Ok((endpoint, proxy)),
                    Err(error)=>{endpoint.close().await;Err(error)},
                }
            })?;
            self.current = Some((endpoint, proxy, remote, relay_only));
        }
        let (_, proxy, address, _) = self.current.as_ref().unwrap();
        let selected = proxy.connection.paths();
        let selected = selected.iter().find(|p| p.is_selected());
        let (path, rtt) = selected
            .map(|p| {
                (
                    if p.is_ip() {
                        "direct"
                    } else if p.is_relay() {
                        "relay"
                    } else {
                        "unknown"
                    },
                    p.rtt().as_secs_f64() * 1000.,
                )
            })
            .unwrap_or(("unknown", 0.));
        Ok(ProxyInfo {
            address: proxy.address.clone(),
            port_offset: proxy.offset,
            endpoint_id: proxy.endpoint_id.clone(),
            path: path.into(),
            rtt_ms: rtt,
            relay_url: address.relay_url.clone(),
        })
    }
    pub fn stop(&mut self) {
        if let Some((endpoint, proxy, _, _)) = self.current.take() {
            drop(proxy);
            self.runtime.block_on(endpoint.close());
        }
    }
}
impl Drop for Client {
    fn drop(&mut self) {
        self.stop();
    }
}
impl Drop for Proxy {
    fn drop(&mut self) {
        self.jobs.abort_all();
        self.connection.close(0u32.into(), b"proxy closed");
    }
}
impl Proxy {
    pub async fn start(endpoint: &Endpoint, remote: &Address, offset: u16) -> Result<Self> {
        let (offset, tcp, udp) = bind_proxy_ports(offset).await?;
        let connection = connect(endpoint, remote).await?;
        let local = Ipv4Addr::new(127, 77, 0, 2);
        let mut jobs = JoinSet::new();
        for (port, listener) in tcp {
            let connection = connection.clone();
            jobs.spawn(async move {
                let limit=Arc::new(Semaphore::new(16));let mut flows=JoinSet::new();
                loop {tokio::select!{
                    accepted=listener.accept()=>{let Ok((socket,_))=accepted else {break};let Ok(permit)=limit.clone().try_acquire_owned()else{continue};let connection=connection.clone();flows.spawn(async move{let _permit=permit;let _=forward_tcp(connection,socket,port).await;});},
                    _=connection.closed()=>break,
                    _=flows.join_next(),if !flows.is_empty()=>{}
                }}
            });
        }
        for (&port, socket) in &udp {
            let socket = socket.clone();
            let connection = connection.clone();
            jobs.spawn(async move {
                let mut buffer=[0;65536];
                loop {tokio::select!{
                    packet=socket.recv_from(&mut buffer)=>{let Ok((len,client))=packet else{break};if !client.ip().is_loopback()||len>MAX_PACKET{continue;}let _=connection.send_datagram(frame(port,client.port(),&buffer[..len]));},
                    _=connection.closed()=>break
                }}
            });
        }
        let receiver = connection.clone();
        jobs.spawn(async move {
            while let Ok(packet) = receiver.read_datagram().await {
                if let Ok((port, client, data)) = parse_frame(&packet) {
                    if let Some(socket) = udp.get(&port) {
                        let _ = socket.send_to(data, (Ipv4Addr::LOCALHOST, client)).await;
                    }
                }
            }
        });
        Ok(Self {
            address: local.to_string(),
            endpoint_id: endpoint.id().to_string(),
            offset,
            connection,
            jobs,
        })
    }
}
type BoundPorts = (u16, Vec<(u16, TcpListener)>, HashMap<u16, Arc<UdpSocket>>);
async fn bind_proxy_ports(preferred: u16) -> Result<BoundPorts> {
    ensure!(
        preferred > 0 && preferred <= u16::MAX - 48010,
        "Invalid port offset"
    );
    // Hyper-V may reserve an entire TCP range, even on loopback. Try complete
    // application-local port sets; never change the system's reserved ranges.
    let mut offsets = vec![preferred];
    offsets.extend((1000..=15000).step_by(1000).filter(|o| *o != preferred));
    let mut last = None;
    for offset in offsets {
        let bound: Result<BoundPorts> = async {
            let local = Ipv4Addr::new(127, 77, 0, 2);
            let mut tcp = Vec::new();
            let mut udp = HashMap::new();
            for &port in TCP_PORTS {
                tcp.push((port, TcpListener::bind((local, port + offset)).await?));
            }
            for &port in UDP_PORTS {
                udp.insert(
                    port,
                    Arc::new(UdpSocket::bind((local, port + offset)).await?),
                );
            }
            Ok((offset, tcp, udp))
        }
        .await;
        match bound {
            Ok(ports) => return Ok(ports),
            Err(error) => last = Some(error),
        }
    }
    Err(last.unwrap()).context("No complete local transport port set is available")
}
/// Serve only local Sunshine ports. The peer cannot choose an arbitrary host.
pub async fn serve(endpoint: Endpoint, grants: Arc<Grants>) -> Result<()> {
    let limit = Arc::new(Semaphore::new(8));
    let mut sessions = JoinSet::new();
    let mut next = 2u8;
    loop {
        tokio::select! {
            incoming=endpoint.accept()=>{
                let Some(incoming)=incoming else{break};
                let Ok(permit)=limit.clone().try_acquire_owned()else{incoming.refuse();continue};
                let grants=grants.clone();let local=Ipv4Addr::new(127,88,0,next);next=next.wrapping_add(1).max(2);
                sessions.spawn(async move{let _permit=permit;if let Ok(connection)=incoming.await{let _=serve_connection(connection,grants,local).await;}});
            },
            _=sessions.join_next(),if !sessions.is_empty()=>{}
        }
    }
    Ok(())
}
async fn serve_connection(
    connection: Connection,
    grants: Arc<Grants>,
    local: Ipv4Addr,
) -> Result<()> {
    let peer = connection.remote_id();
    let mut sockets = HashMap::<u16, Arc<UdpSocket>>::new();
    let mut jobs = JoinSet::new();
    let limit = Arc::new(Semaphore::new(32));
    let mut check = tokio::time::interval(Duration::from_secs(1));
    let started = Instant::now();
    let mut was_authorized = false;
    loop {
        tokio::select! {
            stream=connection.accept_bi()=>{
                let Ok((mut send,mut receive))=stream else{break};let grants=grants.clone();
                let Ok(permit)=limit.clone().try_acquire_owned()else{continue};
                jobs.spawn(async move{
                    let _permit=permit;
                    let result:Result<()>=async {
                        let port=tokio::time::timeout(Duration::from_secs(3),receive.read_u16()).await??;
                        if !tcp_allowed(port,grants.allowed(peer)){send.write_all(&[1]).await?;send.finish()?;bail!("Forbidden TCP destination");}
                        let tcp=TcpSocket::new_v4()?;tcp.bind(SocketAddr::new(IpAddr::V4(local),0))?;
                        let mut tcp=tokio::time::timeout(Duration::from_secs(3),tcp.connect((Ipv4Addr::LOCALHOST,port).into())).await??;
                        tcp.set_nodelay(true)?;send.write_all(&[0]).await?;
                        let (mut reader,mut writer)=tcp.split();
                        tokio::try_join!(async{tokio::io::copy(&mut receive,&mut writer).await?;writer.shutdown().await?;Ok::<_,anyhow::Error>(())},async{tokio::io::copy(&mut reader,&mut send).await?;send.finish()?;Ok::<_,anyhow::Error>(())})?;
                        Ok(())
                    }.await;let _=result;
                });
            },
            packet=connection.read_datagram()=>{
                let Ok(packet)=packet else{break};
                // Certificate revocation is checked once per second, avoiding disk
                // I/O for every video packet.
                if !was_authorized {was_authorized=grants.allowed(peer);if !was_authorized{continue;}}
                let Ok((port,client,data))=parse_frame(&packet)else{continue};
                if !sockets.contains_key(&client){
                    if sockets.len()>=MAX_FLOWS{continue;}
                    // Sunshine learns the source port from its authenticated PING.
                    // The native client's local port may already be in use here.
                    let Ok(socket)=UdpSocket::bind((local,0)).await else{continue};let socket=Arc::new(socket);
                    sockets.insert(client,socket.clone());let connection=connection.clone();
                    jobs.spawn(async move{let mut buffer=[0;65536];while let Ok((len,source))=socket.recv_from(&mut buffer).await{if source.ip()!=IpAddr::V4(Ipv4Addr::LOCALHOST)||!UDP_PORTS.contains(&source.port())||len>MAX_PACKET{continue;}let _=connection.send_datagram(frame(source.port(),client,&buffer[..len]));}});
                }
                if let Some(socket)=sockets.get(&client){let _=socket.send_to(data,(Ipv4Addr::LOCALHOST,port)).await;}
            },
            _=check.tick()=>{
                let authorized=grants.allowed(peer);
                if was_authorized && !authorized || !authorized && started.elapsed()>Duration::from_secs(60){break;}
                was_authorized|=authorized;
            },
            _=jobs.join_next(),if !jobs.is_empty()=>{}
        }
    }
    connection.close(0u32.into(), b"session closed");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancelled_transport_connect_reaps_the_endpoint_before_returning() {
        use std::sync::atomic::{AtomicBool, Ordering};
        let cancel=Arc::new(AtomicBool::new(false));let signal=cancel.clone();
        let path=std::env::temp_dir().join(format!("rhremote-cancel-{}.key",SecretKey::generate().public()));
        let worker_path=path.clone();let began=Instant::now();
        let worker=std::thread::spawn(move || {
            let mut client=Client::new().unwrap();
            let result=client.start_cancelable(&worker_path,Address {
                endpoint_id:SecretKey::generate().public().to_string(),relay_url:"http://127.0.0.1:9/".into(),
            },true,&signal);
            assert!(result.unwrap_err().to_string().contains("cancelled"));
            assert!(client.current.is_none());
        });
        std::thread::sleep(Duration::from_millis(150));cancel.store(true,Ordering::SeqCst);
        worker.join().unwrap();let _=std::fs::remove_file(path);
        assert!(began.elapsed()<Duration::from_secs(4));
    }
    #[tokio::test]
    async fn tcp_request_does_not_wait_for_the_channel_acknowledgement() {
        let build = || {
            Endpoint::builder(presets::Minimal)
                .alpns(vec![ALPN.to_vec()])
                .relay_mode(RelayMode::Disabled)
                .bind_addr(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
                .unwrap()
        };
        let server = build().bind().await.unwrap();
        let client = build().bind().await.unwrap();
        let remote = EndpointAddr::new(server.id()).with_ip_addr(server.bound_sockets()[0]);
        let receiver = tokio::spawn(async move {
            let connection = server.accept().await.unwrap().await.unwrap();
            let (mut send, mut receive) = connection.accept_bi().await.unwrap();
            assert_eq!(receive.read_u16().await.unwrap(), 47989);
            // Deliberately require the request before returning the channel ACK.
            // The old sequential handshake deadlocks on this condition.
            let mut payload = [0; 5];
            receive.read_exact(&mut payload).await.unwrap();
            assert_eq!(&payload, b"hello");
            send.write_all(b"\0reply").await.unwrap();
            send.finish().unwrap();
            send.stopped().await.unwrap();
            server.close().await;
        });
        let connection = client.connect(remote, ALPN).await.unwrap();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let mut native = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (socket, _) = listener.accept().await.unwrap();
        let forward = tokio::spawn(forward_tcp(connection, socket, 47989));
        native.write_all(b"hello").await.unwrap();
        native.shutdown().await.unwrap();
        let mut response = Vec::new();
        tokio::time::timeout(Duration::from_secs(3), native.read_to_end(&mut response))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(response, b"reply");
        forward.await.unwrap().unwrap();
        receiver.await.unwrap();
        client.close().await;
    }
    #[tokio::test]
    async fn local_port_conflicts_select_another_complete_set() {
        let occupied = TcpListener::bind((Ipv4Addr::new(127, 77, 0, 2), TCP_PORTS[0] + 500))
            .await
            .unwrap();
        let (offset, tcp, udp) = bind_proxy_ports(500).await.unwrap();
        assert_ne!(offset, 500);
        assert_eq!(tcp.len(), TCP_PORTS.len());
        assert_eq!(udp.len(), UDP_PORTS.len());
        for (port, listener) in tcp {
            assert_eq!(listener.local_addr().unwrap().port(), port + offset);
        }
        drop(occupied);
        assert!(
            TcpListener::bind((Ipv4Addr::new(127, 77, 0, 2), TCP_PORTS[0] + 500))
                .await
                .is_ok()
        );
    }
    #[test]
    fn management_and_unpaired_media_are_denied() {
        assert!(!tcp_allowed(47990, true));
        assert!(!tcp_allowed(48010, false));
        assert!(tcp_allowed(47995, false));
        assert!(tcp_allowed(48010, true));
        assert!(parse_frame(&frame(47990, 40000, b"x")).is_err());
    }
    #[test]
    fn grants_require_pairing_and_recheck_revocation() {
        let enabled = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let flag = enabled.clone();
        let grants = Grants::new(move |cert| {
            flag.load(std::sync::atomic::Ordering::SeqCst) && cert == b"paired"
        });
        let id = SecretKey::generate().public();
        assert!(grants.authorize(&id.to_string(), b"unknown").is_err());
        assert!(grants.authorize(&id.to_string(), b"paired").is_ok());
        assert!(grants.allowed(id));
        enabled.store(false, std::sync::atomic::Ordering::SeqCst);
        assert!(!grants.allowed(id));
    }
    #[test]
    fn datagram_validation_and_boundaries() {
        assert!(parse_frame(&[0; 3]).is_err());
        assert!(parse_frame(&frame(47998, 0, b"x")).is_err());
        assert!(parse_frame(&frame(47998, 40000, &vec![0; MAX_PACKET + 1])).is_err());
        let packet = frame(47998, 40000, b"payload");
        let (port, client, data) = parse_frame(&packet).unwrap();
        assert_eq!((port, client, data), (47998, 40000, b"payload".as_slice()));
    }
}
