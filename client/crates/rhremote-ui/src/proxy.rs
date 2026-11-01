//! A scoped TLS wrapper lets the existing conditional WebDAV implementation use
//! HTTPS proxies without changing its locking, retry or certificate semantics.
use std::{
    path::{Path, PathBuf},
    sync::Arc,
};

pub fn data_directory() -> PathBuf {
    std::env::var_os("RHREMOTE_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap_or_default()).join("RHRemote")
        })
}
pub struct SyncRoute {
    pub address: Option<String>,
    _tls: Option<TlsBridge>,
}
impl SyncRoute {
    pub fn new(directory: &Path) -> Result<Self, String> {
        let settings = rhremote_core::proxy::Settings::load(directory)?;
        let address = settings.for_scope("sync");
        if address.is_some() && settings.protocol == "https" {
            let bridge = TlsBridge::start(&settings)?;
            Ok(Self {
                address: Some(format!("http://{}", bridge.address)),
                _tls: Some(bridge),
            })
        } else {
            Ok(Self {
                address,
                _tls: None,
            })
        }
    }
}
struct TlsBridge {
    address: std::net::SocketAddr,
    stop: Option<tokio::sync::oneshot::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl TlsBridge {
    fn start(settings: &rhremote_core::proxy::Settings) -> Result<Self, String> {
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        for cert in rustls_native_certs::load_native_certs().certs {
            let _ = roots.add(cert);
        }
        let config = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|_| "代理 TLS 初始化失败")?
        .with_root_certificates(roots)
        .with_no_client_auth();
        Self::with_config(settings, Arc::new(config))
    }
    fn with_config(
        settings: &rhremote_core::proxy::Settings,
        config: Arc<rustls::ClientConfig>,
    ) -> Result<Self, String> {
        let host = settings.host.clone();
        let port = settings.port;
        let name =
            rustls::pki_types::ServerName::try_from(host.clone()).map_err(|_| "代理主机名无效")?;
        let listener = std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
            .map_err(|_| "代理本地通道无法启动")?;
        listener
            .set_nonblocking(true)
            .map_err(|_| "代理本地通道无法启动")?;
        let address = listener.local_addr().map_err(|_| "代理本地通道无法启动")?;
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| "代理本地通道无法启动")?;
        let (stop, mut closed) = tokio::sync::oneshot::channel();
        let thread=std::thread::Builder::new().name("RHRemote-DAV-proxy".into()).spawn(move||runtime.block_on(async move{
            let Ok(listener)=tokio::net::TcpListener::from_std(listener)else{return};
            let connector=tokio_rustls::TlsConnector::from(config);let mut jobs=tokio::task::JoinSet::new();
            loop{tokio::select!{
                _=&mut closed=>break,
                accepted=listener.accept(),if jobs.len()<8=>{let Ok((mut local,_))=accepted else{break};let host=host.clone();let name=name.clone();let connector=connector.clone();jobs.spawn(async move{
                    let forward=async{let tcp=tokio::net::TcpStream::connect((host.as_str(),port)).await?;tcp.set_nodelay(true)?;let mut secure=connector.connect(name,tcp).await?;tokio::io::copy_bidirectional(&mut local,&mut secure).await?;Ok::<_,std::io::Error>(())};
                    let _=tokio::time::timeout(std::time::Duration::from_secs(30),forward).await;
                });},
                _=jobs.join_next(),if !jobs.is_empty()=>{}
            }}jobs.abort_all();while jobs.join_next().await.is_some(){}
        })).map_err(|_|"代理本地通道无法启动")?;
        Ok(Self {
            address,
            stop: Some(stop),
            thread: Some(thread),
        })
    }
}
impl Drop for TlsBridge {
    fn drop(&mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{BufRead, BufReader, Read, Write},
        net::{TcpListener, TcpStream},
        time::Duration,
    };
    #[test]
    fn tls_proxy_hop_verifies_its_certificate_and_releases_the_listener() {
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let server = rustls::ServerConfig::builder_with_provider(provider.clone())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(
                vec![cert.cert.der().clone()],
                rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der())
                    .into(),
            )
            .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let worker = std::thread::spawn(move || {
            for trusted in [true, false] {
                let (socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let mut tls = rustls::StreamOwned::new(
                    rustls::ServerConnection::new(Arc::new(server.clone())).unwrap(),
                    socket,
                );
                let mut bytes = [0; 4];
                let result = tls.read_exact(&mut bytes);
                if trusted {
                    result.unwrap();
                    assert_eq!(&bytes, b"ping");
                    tls.write_all(b"pong").unwrap();
                    tls.flush().unwrap();
                } else {
                    assert!(
                        result.is_err(),
                        "An untrusted proxy received application data"
                    );
                }
            }
        });
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert.cert.der().clone()).unwrap();
        let client = rustls::ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let settings = rhremote_core::proxy::Settings {
            protocol: "https".into(),
            host: "localhost".into(),
            port,
            ..Default::default()
        };
        let bridge = TlsBridge::with_config(&settings, Arc::new(client)).unwrap();
        let address = bridge.address;
        let mut local = TcpStream::connect(address).unwrap();
        local
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        local.write_all(b"ping").unwrap();
        let mut reply = [0; 4];
        local.read_exact(&mut reply).unwrap();
        assert_eq!(&reply, b"pong");
        drop(local);
        drop(bridge);
        assert!(TcpStream::connect(address).is_err());
        let bridge = TlsBridge::start(&settings).unwrap();
        let mut local = TcpStream::connect(bridge.address).unwrap();
        local
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        local.write_all(b"ping").unwrap();
        assert!(local.read_exact(&mut reply).is_err());
        drop(local);
        drop(bridge);
        worker.join().unwrap();
    }
    #[test]
    fn webdav_http_proxy_connects_without_resolving_the_origin() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let worker = std::thread::spawn(move || {
            let (socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut read = BufReader::new(socket.try_clone().unwrap());
            let mut write = socket;
            let mut lines = Vec::new();
            loop {
                let mut line = String::new();
                read.read_line(&mut line).unwrap();
                if line == "\r\n" {
                    break;
                }
                lines.push(line);
            }
            assert_eq!(
                lines[0],
                "GET http://does-not-resolve.invalid/dav/config.json HTTP/1.1\r\n"
            );
            assert!(lines.iter().any(|line| {
                line.to_ascii_lowercase()
                    .starts_with("authorization: basic test")
            }));
            write
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                .unwrap();
        });
        let directory = std::env::temp_dir().join(format!(
            "rhremote-dav-proxy-{}-{}",
            std::process::id(),
            rhremote_core::now_ms()
        ));
        let settings = rhremote_core::proxy::Settings {
            enabled: true,
            port,
            ..Default::default()
        };
        settings.save(&directory).unwrap();
        let route = SyncRoute::new(&directory).unwrap();
        let agent = ureq::AgentBuilder::new()
            .proxy(ureq::Proxy::new(route.address.as_ref().unwrap()).unwrap())
            .timeout(Duration::from_secs(5))
            .build();
        assert_eq!(
            agent
                .get("http://does-not-resolve.invalid/dav/config.json")
                .set("Authorization", "Basic test")
                .call()
                .unwrap()
                .into_string()
                .unwrap(),
            "{}"
        );
        worker.join().unwrap();
        std::fs::remove_file(directory.join("proxy-local.json")).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
}
