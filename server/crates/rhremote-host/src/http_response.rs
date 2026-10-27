use rustls::{ServerConnection, StreamOwned};
use serde_json::Value;
use std::io::{self, Read, Write};

pub fn send<S: Read + Write>(
    stream: &mut StreamOwned<ServerConnection, S>,
    status: u16,
    body: &Value,
) -> io::Result<()> {
    let bytes = serde_json::to_vec(body)?;
    let mut response = format!(
        "HTTP/1.1 {status} Result\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        bytes.len()
    ).into_bytes();
    response.extend_from_slice(&bytes);
    // Keep the header and its bounded body in the same plaintext write. End the
    // TLS stream explicitly, so a peer can distinguish completion from truncation.
    stream.write_all(&response)?;
    stream.conn.send_close_notify();
    stream.flush()
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::{ClientConfig, ClientConnection, RootCertStore, ServerConfig};
    use std::{
        net::{TcpListener, TcpStream},
        sync::Arc,
        time::Duration,
    };

    fn response(status: u16, body: Value) {
        let identity = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let server = ServerConfig::builder_with_provider(provider.clone())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(
                vec![identity.cert.der().clone()],
                rustls::pki_types::PrivatePkcs8KeyDer::from(identity.signing_key.serialize_der())
                    .into(),
            )
            .unwrap();
        let mut roots = RootCertStore::empty();
        roots.add(identity.cert.der().clone()).unwrap();
        let client = ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let expected = serde_json::to_vec(&body).unwrap();
        let worker = std::thread::spawn(move || {
            let (socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut stream =
                StreamOwned::new(ServerConnection::new(Arc::new(server)).unwrap(), socket);
            let mut request = [0; 4];
            stream.read_exact(&mut request).unwrap();
            assert_eq!(&request, b"GET\n");
            send(&mut stream, status, &body).unwrap();
        });
        let socket = TcpStream::connect(address).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let mut stream = StreamOwned::new(
            ClientConnection::new(Arc::new(client), "localhost".try_into().unwrap()).unwrap(),
            socket,
        );
        stream.write_all(b"GET\n").unwrap();
        stream.flush().unwrap();
        let mut bytes = Vec::new();
        let result = stream.read_to_end(&mut bytes);
        worker.join().unwrap();
        assert!(
            result.is_ok(),
            "Connection: close response must end with a complete TLS close, not {result:?}; received {} bytes",
            bytes.len()
        );
        let split = bytes
            .windows(4)
            .position(|part| part == b"\r\n\r\n")
            .unwrap();
        let headers = std::str::from_utf8(&bytes[..split]).unwrap();
        assert!(headers.starts_with(&format!("HTTP/1.1 {status} ")));
        assert!(headers.contains(&format!("Content-Length: {}\r\n", expected.len())));
        assert_eq!(&bytes[split + 4..], expected.as_slice());
    }

    #[test]
    fn utf8_response_spanning_tls_records_is_complete_and_closed() {
        response(
            200,
            serde_json::json!({"ok":true,"result":{"name":"物理屏与虚拟屏".repeat(3500)}}),
        );
    }
    #[test]
    fn error_response_has_the_same_complete_tls_boundary() {
        response(
            409,
            serde_json::json!({"ok":false,"error":"The selected monitor is unavailable"}),
        );
    }
}
