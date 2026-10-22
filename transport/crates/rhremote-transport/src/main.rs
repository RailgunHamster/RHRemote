use anyhow::{Result, bail};
use rhremote_transport::{
    Address, DEFAULT_OFFSET, DEFAULT_RELAY, Grants, Proxy, endpoint, key, serve,
};
use std::{path::PathBuf, time::Duration};

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let command = args.get(1).map(String::as_str).unwrap_or("help");
    if command == "help" {
        println!(
            "RHRemoteTransport identity <key-file> | host <key-file> <paired-endpoint-id> [relay-url] | proxy <key-file> <remote-id> [relay-url] [--relay-only]"
        );
        return Ok(());
    }
    let path = PathBuf::from(
        args.get(2)
            .ok_or_else(|| anyhow::anyhow!("Missing private key file"))?,
    );
    let secret = key(&path)?;
    if command == "identity" {
        println!("{}", secret.public());
        return Ok(());
    }
    if command == "probe" {
        let peer_key =
            key(std::path::Path::new(args.get(3).ok_or_else(|| {
                anyhow::anyhow!("Missing second probe key")
            })?))?;
        let relay = args.get(4).map(String::as_str).unwrap_or(DEFAULT_RELAY);
        let server = endpoint(secret, relay, true).await?;
        let client = endpoint(peer_key, relay, true).await?;
        tokio::time::timeout(Duration::from_secs(15), async {
            tokio::join!(server.online(), client.online());
        })
        .await?;
        let expected = client.id();
        let id = server.id();
        let echo = tokio::spawn(async move {
            let incoming = tokio::time::timeout(Duration::from_secs(15), server.accept())
                .await?
                .ok_or_else(|| anyhow::anyhow!("Probe server closed"))?;
            let connection = incoming.await?;
            anyhow::ensure!(
                connection.remote_id() == expected,
                "Unexpected probe identity"
            );
            let (mut send, mut receive) = connection.accept_bi().await?;
            let bytes = receive.read_to_end(1_048_576).await?;
            send.write_all(&bytes).await?;
            send.finish()?;
            connection.closed().await;
            server.close().await;
            Ok::<_, anyhow::Error>(bytes.len())
        });
        let connection = rhremote_transport::connect(
            &client,
            &Address {
                endpoint_id: id.to_string(),
                relay_url: relay.into(),
            },
        )
        .await?;
        let payload: Vec<u8> = (0..1_048_576).map(|n| (n % 251) as u8).collect();
        let started = std::time::Instant::now();
        let (mut send, mut receive) = connection.open_bi().await?;
        send.write_all(&payload).await?;
        send.finish()?;
        let returned =
            tokio::time::timeout(Duration::from_secs(25), receive.read_to_end(1_048_576)).await??;
        anyhow::ensure!(returned == payload, "Probe payload mismatch");
        println!(
            "{}",
            serde_json::json!({"ok":true,"kind":"synthetic encrypted roundtrip","relayOnly":true,"ipTransportsEnabled":false,"relayUrl":relay,"bytesEachDirection":payload.len(),"roundtripMs":started.elapsed().as_millis(),"peerIdentityVerified":true,"sunshinePortsOpened":false})
        );
        connection.close(0u32.into(), b"probe complete");
        client.close().await;
        echo.await??;
        return Ok(());
    }
    let remote = args
        .get(3)
        .ok_or_else(|| anyhow::anyhow!("Missing remote endpoint id"))?;
    let relay = args.get(4).map(String::as_str).unwrap_or(DEFAULT_RELAY);
    let endpoint = endpoint(secret, relay, args.iter().any(|a| a == "--relay-only")).await?;
    tokio::time::timeout(Duration::from_secs(12), endpoint.online()).await?;
    println!(
        "{}",
        serde_json::json!({"endpointId":endpoint.id().to_string(),"relayUrl":relay,"status":"online"})
    );
    match command {
        // Diagnostic host grants only the explicitly configured peer identity.
        "host" => {
            let grants = Grants::new(|c| c == b"cli-paired");
            grants.authorize(remote, b"cli-paired")?;
            serve(endpoint, grants).await?;
        }
        "proxy" => {
            let proxy = Proxy::start(
                &endpoint,
                &Address {
                    endpoint_id: remote.clone(),
                    relay_url: relay.into(),
                },
                DEFAULT_OFFSET,
            )
            .await?;
            println!(
                "{}",
                serde_json::json!({"address":proxy.address,"offset":proxy.offset,"status":"ready"})
            );
            tokio::signal::ctrl_c().await?;
            drop(proxy);
            endpoint.close().await;
        }
        _ => bail!("Unknown transport command"),
    }
    Ok(())
}
