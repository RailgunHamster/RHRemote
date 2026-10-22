#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
mod auth;
#[cfg(windows)]
mod console;
mod displays;
mod runtime;
#[cfg(windows)]
mod service;
#[cfg(windows)]
mod supervisor;
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use serde_json::{Value, json};
use std::{
    fs,
    io::{BufReader, Read, Write},
    net::{TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

fn tls(
    config: &Path,
    clients: Arc<auth::SunshineClients>,
) -> Result<ServerConfig, Box<dyn std::error::Error>> {
    let cert = rustls_pemfile::certs(&mut BufReader::new(fs::File::open(
        config.join("credentials/cacert.pem"),
    )?))
    .collect::<Result<Vec<_>, _>>()?;
    let key = rustls_pemfile::private_key(&mut BufReader::new(fs::File::open(
        config.join("credentials/cakey.pem"),
    )?))?
    .ok_or("Sunshine TLS key unavailable")?;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    Ok(ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .with_client_cert_verifier(clients)
        .with_single_cert(cert, key)?)
}
fn line(stream: &mut impl Read) -> std::io::Result<String> {
    let mut bytes = Vec::new();
    loop {
        let mut b = [0];
        stream.read_exact(&mut b)?;
        if b[0] == b'\n' {
            break;
        }
        if b[0] != b'\r' {
            bytes.push(b[0]);
        }
        if bytes.len() > 8192 {
            return Err(std::io::Error::other("Header too large"));
        }
    }
    String::from_utf8(bytes).map_err(std::io::Error::other)
}
fn handle(
    socket: TcpStream,
    tls: Arc<ServerConfig>,
    gate: Arc<Mutex<()>>,
    helper: PathBuf,
    clients: Arc<auth::SunshineClients>,
) -> Result<(), Box<dyn std::error::Error>> {
    socket.set_nonblocking(false)?;
    socket.set_read_timeout(Some(Duration::from_secs(5)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    let mut stream = StreamOwned::new(ServerConnection::new(tls)?, socket);
    let first = line(&mut stream)?;
    let parts: Vec<_> = first.split_whitespace().collect();
    if parts.len() != 3 {
        return Err("Invalid request".into());
    }
    let mut length = None;
    let mut headers = 0;
    loop {
        let header = line(&mut stream)?;
        if header.is_empty() {
            break;
        }
        headers += 1;
        if headers > 32 {
            return Err("Too many headers".into());
        }
        let (key, value) = header.split_once(':').ok_or("Invalid header")?;
        if key.eq_ignore_ascii_case("origin") || key.eq_ignore_ascii_case("transfer-encoding") {
            return Err("Unsupported request".into());
        }
        if key.eq_ignore_ascii_case("content-length") {
            if length.is_some() {
                return Err("Duplicate length".into());
            }
            length = Some(value.trim().parse::<usize>()?);
        }
    }
    // The TLS handshake above is mandatory. Anonymous requests never reach a display action.
    // Check enabled peers again so TLS session resumption cannot keep a revoked peer authorized.
    if !stream
        .conn
        .peer_certificates()
        .and_then(|chain| chain.first())
        .is_some_and(|leaf| clients.allowed(leaf.as_ref()))
    {
        return Err("Client is no longer an enabled Sunshine peer".into());
    }
    let result = match (parts[0], parts[1]) {
        #[cfg(windows)]
        ("GET", "/v1/health") => Ok(
            json!({"product":"RHRemote-Sunshine","serviceMode":if service::unified(){"unified"}else{"legacy"},"servicePid":std::process::id(),"sunshine":supervisor::health()}),
        ),
        ("GET", "/v1/displays") => {
            let _guard = gate.lock().unwrap();
            displays::run(&helper, json!({"mode":"list"}))
        }
        #[cfg(windows)]
        ("POST", "/v1/input/secure-attention") => {
            let length = length.ok_or("Missing bounded body")?;
            if length > 128 {
                return Err("Body too large".into());
            }
            let mut body = vec![0; length];
            stream.read_exact(&mut body)?;
            if serde_json::from_slice::<Value>(&body)? != json!({"action":"ctrl-alt-delete"}) {
                return Err("Unknown secure attention action".into());
            }
            let _guard = gate.lock().unwrap();
            service::secure_attention()
        }
        ("POST", "/v1/display/prepare") => {
            let length = length.ok_or("Missing bounded body")?;
            if length > 4096 {
                return Err("Body too large".into());
            }
            let mut body = vec![0; length];
            stream.read_exact(&mut body)?;
            let request: displays::Prepare = serde_json::from_slice(&body)?;
            request.validate()?;
            let _guard = gate.lock().unwrap();
            displays::run(&helper, serde_json::to_value(request)?)
        }
        _ => Err("Unsupported endpoint".into()),
    };
    let (status, body) = match result {
        Ok(value) => (200, json!({"ok":true,"result":value})),
        Err(error) => (409, json!({"ok":false,"error":error})),
    };
    let bytes = serde_json::to_vec(&body)?;
    write!(
        stream,
        "HTTP/1.1 {status} Result\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        bytes.len()
    )?;
    stream.write_all(&bytes)?;
    stream.flush()?;
    Ok(())
}
fn run() -> Result<(), Box<dyn std::error::Error>> {
    run_with_ready(|| {})
}
fn run_with_ready(on_ready: impl FnOnce()) -> Result<(), Box<dyn std::error::Error>> {
    let options = runtime::parse(&std::env::args().skip(1).collect::<Vec<_>>())?;
    let config = options.config;
    let helper = displays::install_helpers()?;
    let clients = Arc::new(auth::SunshineClients::new(
        config.join("sunshine_state.json"),
    ));
    let started = std::time::Instant::now();
    let tls = loop {
        match tls(&config, clients.clone()) {
            Ok(tls) => break Arc::new(tls),
            Err(_) if options.unified && started.elapsed() < Duration::from_secs(20) => {
                #[cfg(windows)]
                if service::STOP.load(Ordering::SeqCst) {
                    return Ok(());
                }
                std::thread::sleep(Duration::from_millis(250));
            }
            Err(error) => return Err(error),
        }
    };
    let listener = TcpListener::bind(options.listen)?;
    let gate = Arc::new(Mutex::new(()));
    let active = Arc::new(AtomicUsize::new(0));
    listener.set_nonblocking(true)?;
    on_ready();
    loop {
        #[cfg(windows)]
        if service::STOP.load(Ordering::SeqCst) {
            break;
        }
        let socket = match listener.accept() {
            Ok((socket, _)) => socket,
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(50));
                continue;
            }
            Err(error) => return Err(error.into()),
        };
        if active.fetch_add(1, Ordering::SeqCst) >= 8 {
            active.fetch_sub(1, Ordering::SeqCst);
            drop(socket);
            continue;
        }
        let tls = tls.clone();
        let gate = gate.clone();
        let helper = helper.clone();
        let active = active.clone();
        let clients = clients.clone();
        std::thread::spawn(move || {
            if let Err(error) = handle(socket, tls, gate, helper, clients) {
                use std::io::Write;
                let root = PathBuf::from(std::env::var_os("ProgramData").unwrap_or_default())
                    .join("RHRemoteHost");
                let path = root.join("transport-errors.log");
                if fs::metadata(&path).is_ok_and(|m| m.len() > 65536) {
                    let _ = fs::write(&path, "");
                }
                if let Ok(mut file) = fs::OpenOptions::new().create(true).append(true).open(path) {
                    let safe: String = error
                        .to_string()
                        .chars()
                        .filter(|c| !c.is_control())
                        .take(180)
                        .collect();
                    let _ = writeln!(
                        file,
                        "{} {safe}",
                        std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_secs()
                    );
                }
            }
            active.fetch_sub(1, Ordering::SeqCst);
        });
    }
    Ok(())
}
fn main() {
    #[cfg(windows)]
    {
        let args: Vec<_> = std::env::args_os().collect();
        if args.get(1).is_some_and(|v| v == "--terminate-sunshine") && args.len() == 3 {
            let result = args[2]
                .to_str()
                .and_then(|v| v.parse::<u32>().ok())
                .filter(|pid| *pid > 0)
                .ok_or("Invalid PID".to_string())
                .and_then(supervisor::terminate_console);
            if result.is_err() {
                std::process::exit(1);
            }
            return;
        }
        if args.get(1).is_some_and(|v| v == "--display-worker") && args.len() == 4 {
            if displays::worker(Path::new(&args[2]), Path::new(&args[3])).is_err() {
                std::process::exit(1)
            }
            return;
        }
        if args.get(1).is_some_and(|v| v == "--capture-order") && args.len() == 3 {
            let result = displays::capture_order();
            if let Ok(names) = result {
                let _ = fs::write(Path::new(&args[2]), serde_json::to_vec(&names).unwrap());
            } else {
                std::process::exit(1)
            }
            return;
        }
    }
    #[cfg(windows)]
    if std::env::args().any(|v| v == "--service" || v == "--sunshine-service") {
        if service::dispatch(std::env::args().any(|v| v == "--sunshine-service")).is_err() {
            std::process::exit(1)
        }
        return;
    }
    if run().is_err() {
        eprintln!(
            "RHRemoteHost could not start; check its configured Sunshine directory and interactive Windows session."
        );
        std::process::exit(1)
    }
}
