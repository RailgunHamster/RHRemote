//! Bounded HTTPS downloads. Redirect destinations cannot downgrade TLS.
use super::protocol::{self, Source, VerifiedManifest};
use std::{
    fs,
    io::{Read, Write},
    path::Path,
    time::{Duration, Instant},
};

fn response(url: &str, seconds: u64) -> Result<ureq::Response, String> {
    let agent = ureq::AgentBuilder::new()
        .redirects(0)
        .timeout(Duration::from_secs(seconds))
        .timeout_connect(Duration::from_secs(5))
        .timeout_read(Duration::from_secs(3))
        .timeout_write(Duration::from_secs(3))
        .build();
    let mut target = url::Url::parse(url).map_err(|_| "Invalid release address")?;
    for _ in 0..7 {
        if target.scheme() != "https"
            || !target.username().is_empty()
            || target.password().is_some()
        {
            return Err("Release redirect must use HTTPS".into());
        }
        let result = agent
            .get(target.as_str())
            .set("User-Agent", "RHRemote-Sunshine-Updater/1")
            .call();
        let result = match result {
            Ok(v) => v,
            Err(ureq::Error::Status(_, v)) => v,
            Err(_) => return Err("Release source request failed".into()),
        };
        if [301, 302, 303, 307, 308].contains(&result.status()) {
            target = target
                .join(
                    result
                        .header("Location")
                        .ok_or("Release redirect lacks a location")?,
                )
                .map_err(|_| "Invalid release redirect")?;
        } else if result.status() == 200 {
            return Ok(result);
        } else {
            return Err(format!("Release source returned HTTP {}", result.status()));
        }
    }
    Err("Too many release redirects".into())
}
fn small(url: &str, limit: usize) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    response(url, 15)?
        .into_reader()
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Release metadata read failed")?;
    if bytes.len() > limit {
        return Err("Release metadata exceeds limit".into());
    }
    Ok(bytes)
}
pub fn check(source: Source) -> Result<(VerifiedManifest, Vec<u8>, Vec<u8>), String> {
    let bytes = small(
        &source.url("latest", protocol::FEED)?,
        protocol::MAX_MANIFEST,
    )?;
    let signature = small(
        &source.url("latest", &format!("{}.sig", protocol::FEED))?,
        384,
    )?;
    Ok((protocol::verify(&bytes, &signature)?, bytes, signature))
}
pub fn download(
    source: Source,
    release: &VerifiedManifest,
    path: &Path,
    stopped: impl Fn() -> bool,
) -> Result<(), String> {
    let expected = release.get();
    let mut input = response(
        &source.url(&expected.release_tag, &expected.file_name)?,
        240,
    )?
    .into_reader();
    let mut output = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|e| e.to_string())?;
    let deadline = Instant::now() + Duration::from_secs(240);
    let mut buffer = [0u8; 131_072];
    let mut total = 0u64;
    loop {
        if stopped() || Instant::now() > deadline {
            return Err("Server package download interrupted or timed out".into());
        }
        let n = input
            .read(&mut buffer)
            .map_err(|_| "Server package download failed")?;
        if n == 0 {
            break;
        }
        total += n as u64;
        if total > expected.size {
            return Err("Server package exceeds signed size".into());
        }
        output.write_all(&buffer[..n]).map_err(|e| e.to_string())?;
    }
    output.sync_all().map_err(|e| e.to_string())?;
    if total != expected.size
        || !protocol::file_sha256(path)?.eq_ignore_ascii_case(&expected.sha256)
    {
        return Err("Server package checksum rejected".into());
    }
    Ok(())
}
