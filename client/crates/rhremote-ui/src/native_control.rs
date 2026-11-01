//! Metadata-only observations of the authenticated localhost state endpoint.
use serde_json::Value;
use std::{error::Error, io, time::{Duration, Instant}};

#[derive(Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RequestTrace {
    pub result: &'static str,
    stage: &'static str,
    pub http_status: Option<u16>,
    transport_kind: Option<String>,
    io_kind: Option<String>,
    os_error: Option<i32>,
    headers_us: Option<u64>,
    elapsed_us: u64,
}
pub(crate) fn synthetic_unavailable() -> RequestTrace {
    RequestTrace { result: "test_unavailable", stage: "headers", ..Default::default() }
}

fn io_category(error: &(dyn Error + 'static)) -> (Option<String>, Option<i32>) {
    let mut source = Some(error);
    // Error text can contain URLs or JSON fragments. Only store typed enums.
    for _ in 0..8 {
        let Some(error) = source else { break };
        if let Some(error) = error.downcast_ref::<io::Error>() {
            return (Some(format!("{:?}", error.kind())), error.raw_os_error());
        }
        source = error.source();
    }
    (None, None)
}

pub(crate) fn read_state(agent: &ureq::Agent, port: u16, token: &str, budget: Duration) -> (Option<Value>, RequestTrace) {
    let started = Instant::now();
    let mut trace = RequestTrace { stage: "headers", ..Default::default() };
    let response = agent.get(&format!("http://127.0.0.1:{port}/v1/state"))
        .set("Authorization", &format!("Bearer {token}"))
        .timeout(budget)
        .call();
    let value = match response {
        Ok(response) => {
            trace.headers_us = Some(started.elapsed().as_micros() as u64);
            trace.http_status = Some(response.status());
            trace.stage = "body";
            match response.into_json() {
                Ok(value) => { trace.result = "decoded"; trace.stage = "complete"; Some(value) }
                Err(error) => {
                    trace.result = "invalid_json";
                    (trace.io_kind, trace.os_error) = io_category(&error);
                    None
                }
            }
        }
        Err(ureq::Error::Status(status, _)) => {
            trace.result = "http_error";
            trace.http_status = Some(status);
            None
        }
        Err(ureq::Error::Transport(error)) => {
            trace.result = "transport_error";
            trace.transport_kind = Some(format!("{:?}", error.kind()));
            (trace.io_kind, trace.os_error) = io_category(&error);
            None
        }
    };
    trace.elapsed_us = started.elapsed().as_micros() as u64;
    (value, trace)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{io::{Read, Write}, net::TcpListener, thread};

    fn agent() -> ureq::Agent {
        ureq::AgentBuilder::new().try_proxy_from_env(false).redirects(0)
            .timeout_connect(Duration::from_millis(100)).build()
    }
    fn reply(response: &'static [u8], delay: Duration) -> (u16, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let worker = thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut data = Vec::new();
            let mut byte = [0];
            while !data.ends_with(b"\r\n\r\n") {
                socket.read_exact(&mut byte).unwrap();
                data.push(byte[0]);
            }
            assert!(data.starts_with(b"GET /v1/state HTTP/1.1\r\n"));
            socket.write_all(response).unwrap();
            thread::sleep(delay);
        });
        (port, worker)
    }
    fn no_private_data(trace: &RequestTrace) {
        let text = serde_json::to_string(trace).unwrap();
        for private in ["fixture-private-token", "fixture-private-body", "Authorization", "http://", "/v1/state"] {
            assert!(!text.contains(private), "Request metadata leaked private input");
        }
    }
    #[test]
    fn successful_json_is_decoded_without_copying_response_content_into_metadata() {
        let (port, worker) = reply(b"HTTP/1.1 200 OK\r\nContent-Length: 34\r\nConnection: close\r\n\r\n{\"private\":\"fixture-private-body\"}", Duration::ZERO);
        let (value, trace) = read_state(&agent(), port, "fixture-private-token", Duration::from_millis(500));
        worker.join().unwrap();
        assert_eq!(value.unwrap()["private"], "fixture-private-body");
        assert_eq!(trace.result, "decoded"); assert_eq!(trace.stage, "complete");
        assert_eq!(trace.http_status, Some(200)); assert!(trace.headers_us.is_some());
        no_private_data(&trace);
    }
    #[test]
    fn an_http_refusal_is_distinct_from_a_connection_failure() {
        let (port, worker) = reply(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 20\r\nConnection: close\r\n\r\nfixture-private-body", Duration::ZERO);
        let (value, trace) = read_state(&agent(), port, "fixture-private-token", Duration::from_millis(500));
        worker.join().unwrap();
        assert!(value.is_none()); assert_eq!(trace.result, "http_error");
        assert_eq!(trace.http_status, Some(403)); assert!(trace.transport_kind.is_none());
        no_private_data(&trace);
    }
    #[test]
    fn malformed_json_records_the_body_stage_without_the_fragment() {
        let (port, worker) = reply(b"HTTP/1.1 200 OK\r\nContent-Length: 20\r\nConnection: close\r\n\r\nfixture-private-body", Duration::ZERO);
        let (value, trace) = read_state(&agent(), port, "fixture-private-token", Duration::from_millis(500));
        worker.join().unwrap();
        assert!(value.is_none()); assert_eq!(trace.result, "invalid_json"); assert_eq!(trace.stage, "body");
        no_private_data(&trace);
    }
    #[test]
    fn a_stalled_status_line_records_a_header_timeout() {
        let (port, worker) = reply(b"", Duration::from_millis(400));
        let (value, trace) = read_state(&agent(), port, "fixture-private-token", Duration::from_millis(120));
        worker.join().unwrap();
        assert!(value.is_none()); assert_eq!(trace.result, "transport_error"); assert_eq!(trace.stage, "headers");
        assert_eq!(trace.io_kind.as_deref(), Some("TimedOut")); assert!(trace.http_status.is_none());
        no_private_data(&trace);
    }
    #[test]
    fn a_stalled_body_is_not_reported_as_a_header_timeout() {
        let (port, worker) = reply(b"HTTP/1.1 200 OK\r\nContent-Length: 30\r\nConnection: close\r\n\r\n{", Duration::from_millis(400));
        let (value, trace) = read_state(&agent(), port, "fixture-private-token", Duration::from_millis(120));
        worker.join().unwrap();
        assert!(value.is_none()); assert_eq!(trace.result, "invalid_json"); assert_eq!(trace.stage, "body");
        assert_eq!(trace.io_kind.as_deref(), Some("TimedOut")); assert_eq!(trace.http_status, Some(200));
        no_private_data(&trace);
    }
    #[test]
    fn a_closed_listener_preserves_the_os_result_instead_of_guessing_refusal() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port(); drop(listener);
        let (value, trace) = read_state(&agent(), port, "fixture-private-token", Duration::from_millis(500));
        assert!(value.is_none()); assert_eq!(trace.result, "transport_error");
        assert_eq!(trace.transport_kind.as_deref(), Some("ConnectionFailed"));
        // A closed port may produce an OS refusal or exhaust the caller's
        // connect budget (observed on Windows). Both are connection failures,
        // and the diagnostic must retain the actual enum without relabeling it.
        assert!(matches!(trace.io_kind.as_deref(), Some("ConnectionRefused" | "TimedOut")));
        if trace.io_kind.as_deref() == Some("ConnectionRefused") { assert!(trace.os_error.is_some()); }
        no_private_data(&trace);
    }
}
