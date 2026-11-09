//! SYSTEM-only updater. The external worker survives the service it replaces.
use super::{
    net,
    protocol::{self, Package, Settings, Source},
    transaction::{self as tx, Journal},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    fs,
    mem::size_of,
    os::windows::{ffi::OsStrExt, process::CommandExt},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use windows::{
    Win32::{
        Foundation::*,
        Security::{Authorization::*, *},
        System::{Services::*, Threading::*},
    },
    core::{PCWSTR, w},
};

fn error(e: impl std::fmt::Display) -> String {
    e.to_string()
}
fn wide(value: &std::ffi::OsStr) -> Vec<u16> {
    value.encode_wide().chain(Some(0)).collect()
}
pub fn root() -> Result<PathBuf, String> {
    Ok(
        PathBuf::from(std::env::var_os("ProgramData").ok_or("ProgramData unavailable")?)
            .join("RHRemoteHost/updates"),
    )
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn read(path: &Path) -> Result<Vec<u8>, String> {
    tx::no_links(path)?;
    if fs::metadata(path).map_err(error)?.len() > 1_048_576 {
        return Err("Update state exceeds limit".into());
    }
    fs::read(path).map_err(error)
}
fn json_file(path: &Path) -> Result<Value, String> {
    serde_json::from_slice(&read(path)?).map_err(error)
}
fn write(path: &Path, value: &impl Serialize) -> Result<(), String> {
    tx::atomic(path, &serde_json::to_vec_pretty(value).map_err(error)?)
}
fn system() -> Result<(), String> {
    unsafe {
        let mut token = HANDLE::default();
        OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token).map_err(error)?;
        let mut bytes = vec![0usize; 256];
        let mut needed = 0;
        let result = GetTokenInformation(
            token,
            TokenUser,
            Some(bytes.as_mut_ptr().cast()),
            (bytes.len() * size_of::<usize>()) as u32,
            &mut needed,
        );
        let _ = CloseHandle(token);
        result.map_err(error)?;
        if !IsWellKnownSid(
            (*(bytes.as_ptr().cast::<TOKEN_USER>())).User.Sid,
            WinLocalSystemSid,
        )
        .as_bool()
        {
            return Err("Server updater requires SYSTEM".into());
        }
    }
    Ok(())
}
fn protect(directory: &Path) -> Result<(), String> {
    tx::no_links(directory)?;
    fs::create_dir_all(directory).map_err(error)?;
    unsafe {
        let mut descriptor = PSECURITY_DESCRIPTOR::default();
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            w!("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"),
            1,
            &mut descriptor,
            None,
        )
        .map_err(error)?;
        let path = wide(directory.as_os_str());
        let ok = SetFileSecurityW(
            PCWSTR(path.as_ptr()),
            DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
            descriptor,
        )
        .as_bool();
        let _ = LocalFree(Some(HLOCAL(descriptor.0)));
        if !ok {
            return Err("Cannot protect update directory".into());
        }
    }
    Ok(())
}
fn lock(directory: &Path) -> Result<fs::File, String> {
    let path = directory.join("transaction.lock");
    tx::no_links(&path)?;
    let file = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(path)
        .map_err(error)?;
    file.try_lock()
        .map_err(|_| "Another update worker is active")?;
    Ok(file)
}
fn status(
    directory: &Path,
    state: &str,
    current: &str,
    latest: Option<&str>,
    source: Option<Source>,
    message: Option<&str>,
) -> Result<(), String> {
    write(
        &directory.join("status.json"),
        &json!({"schema":"rhremote.server-update-status/v1","state":state,"currentVersion":current,"latestVersion":latest,"source":source,"message":message,"updatedAt":now(),"servicePid":std::process::id()}),
    )
}
fn settings(directory: &Path) -> Result<Settings, String> {
    let path = directory.join("settings.json");
    if !path.try_exists().map_err(error)? {
        return Ok(Settings::default());
    }
    let value: Settings = serde_json::from_slice(&read(&path)?).map_err(error)?;
    value.validate()?;
    Ok(value)
}
fn installed(install: &Path) -> Result<Package, String> {
    let value: Package =
        serde_json::from_slice(&read(&install.join("server-package.json"))?).map_err(error)?;
    value.validate()?;
    Ok(value)
}
fn field<'a>(xml: &'a str, key: &str) -> Option<&'a str> {
    xml.split_once(&format!("<{key}>"))?
        .1
        .split_once(&format!("</{key}>"))
        .map(|v| v.0)
}
#[derive(Debug)]
struct Idle {
    uuid: String,
    quiet: u64,
}
fn idle(xml: &str, held: bool) -> Result<Idle, String> {
    for (name, value) in [
        ("RHUpdateCoordinated", "1"),
        ("RHUpdateHeld", if held { "1" } else { "0" }),
        ("RHUpdatePreparing", "0"),
        ("RHUpdateSessions", "0"),
        ("RHUpdateLaunchPending", "0"),
    ] {
        if field(xml, name) != Some(value) {
            return Err("Remote session or connection preparation is active".into());
        }
    }
    let quiet = field(xml, "RHUpdateQuietMs")
        .and_then(|v| v.parse::<u64>().ok())
        .ok_or("Update coordination unavailable")?;
    if quiet < 120_000 {
        return Err("Waiting for the recent connection handshake to retire".into());
    }
    let uuid = field(xml, "uniqueid")
        .filter(|v| !v.is_empty() && v.len() <= 64)
        .ok_or("Server identity unavailable")?
        .to_owned();
    Ok(Idle { uuid, quiet })
}
fn info(port: u16) -> Result<String, String> {
    let agent = ureq::AgentBuilder::new()
        .redirects(0)
        .timeout(Duration::from_secs(2))
        .build();
    let response = agent
        .get(&format!(
            "http://127.0.0.1:{port}/serverinfo?uniqueid=RHRemoteUpdate1"
        ))
        .call()
        .map_err(|_| "Local Sunshine is not ready")?;
    let mut value = String::new();
    use std::io::Read;
    response
        .into_reader()
        .take(65_537)
        .read_to_string(&mut value)
        .map_err(error)?;
    if value.len() > 65_536 {
        return Err("Local server response exceeds limit".into());
    }
    Ok(value)
}
fn port(config: &Path) -> Result<u16, String> {
    let conf = fs::read_to_string(config.join("sunshine.conf")).unwrap_or_default();
    for line in conf.lines() {
        if let Some((key, value)) = line.split_once('=') {
            if key.trim() == "port" {
                return value
                    .trim()
                    .parse::<u16>()
                    .ok()
                    .filter(|v| *v >= 1024 && *v <= 65514)
                    .ok_or("Unsupported Sunshine port".into());
            }
        }
    }
    Ok(47989)
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Transaction {
    install: PathBuf,
    port: u16,
    previous_version: String,
    uuid: String,
    created_at: u64,
}
struct Service(SC_HANDLE);
impl Drop for Service {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseServiceHandle(self.0);
        }
    }
}
fn service() -> Result<Service, String> {
    unsafe {
        let manager = Service(OpenSCManagerW(None, None, SC_MANAGER_CONNECT).map_err(error)?);
        Ok(Service(
            OpenServiceW(
                manager.0,
                w!("SunshineService"),
                SERVICE_QUERY_STATUS | SERVICE_QUERY_CONFIG | SERVICE_START | SERVICE_STOP,
            )
            .map_err(error)?,
        ))
    }
}
fn service_status(service: &Service) -> Result<SERVICE_STATUS_PROCESS, String> {
    let mut result = SERVICE_STATUS_PROCESS::default();
    let mut needed = 0;
    let bytes = unsafe {
        std::slice::from_raw_parts_mut(
            (&mut result as *mut SERVICE_STATUS_PROCESS).cast::<u8>(),
            size_of::<SERVICE_STATUS_PROCESS>(),
        )
    };
    unsafe {
        QueryServiceStatusEx(service.0, SC_STATUS_PROCESS_INFO, Some(bytes), &mut needed)
            .map_err(error)?;
    }
    Ok(result)
}
fn validate_service(service: &Service, install: &Path) -> Result<(), String> {
    unsafe {
        let mut needed = 0;
        let _ = QueryServiceConfigW(service.0, None, 0, &mut needed);
        if needed == 0 || needed > 65_536 {
            return Err("Cannot inspect server service ownership".into());
        }
        let mut bytes = vec![0usize; (needed as usize).div_ceil(size_of::<usize>())];
        let config = bytes.as_mut_ptr().cast::<QUERY_SERVICE_CONFIGW>();
        QueryServiceConfigW(service.0, Some(config), needed, &mut needed).map_err(error)?;
        let config = &*config;
        let binary = config.lpBinaryPathName.to_string().map_err(error)?;
        let account = config.lpServiceStartName.to_string().map_err(error)?;
        let expected = format!(
            "\"{}\" --sunshine-service ",
            install.join("host/RHRemoteHost.exe").display()
        );
        if account != "LocalSystem"
            || !binary
                .to_lowercase()
                .starts_with(&expected.replace('/', "\\").to_lowercase())
        {
            return Err("Server service belongs to another installation".into());
        }
    }
    Ok(())
}
fn wait(
    service: &Service,
    state: SERVICE_STATUS_CURRENT_STATE,
    seconds: u64,
) -> Result<u32, String> {
    let deadline = Instant::now() + Duration::from_secs(seconds);
    loop {
        let s = service_status(service)?;
        if s.dwCurrentState == state {
            return Ok(s.dwProcessId);
        }
        if Instant::now() > deadline {
            return Err("Server service transition timed out".into());
        }
        thread::sleep(Duration::from_millis(200));
    }
}
fn stop(service: &Service) -> Result<(), String> {
    let before = service_status(service)?;
    if before.dwCurrentState == SERVICE_STOPPED {
        return Ok(());
    }
    let process = unsafe { OpenProcess(PROCESS_SYNCHRONIZE, false, before.dwProcessId) }.ok();
    let mut result = SERVICE_STATUS::default();
    unsafe {
        ControlService(service.0, SERVICE_CONTROL_STOP, &mut result).map_err(error)?;
    }
    wait(service, SERVICE_STOPPED, 45)?;
    if let Some(handle) = process {
        let state = unsafe { WaitForSingleObject(handle, 10_000) };
        unsafe {
            let _ = CloseHandle(handle);
        }
        if state != WAIT_OBJECT_0 {
            return Err("Old service process has not exited".into());
        }
    }
    Ok(())
}
fn start(service: &Service) -> Result<(), String> {
    if service_status(service)?.dwCurrentState == SERVICE_STOPPED {
        unsafe {
            StartServiceW(service.0, None).map_err(error)?;
        }
    }
    wait(service, SERVICE_RUNNING, 45)?;
    Ok(())
}
fn health(
    directory: &Path,
    service: &Service,
    transaction: &Transaction,
    version: &str,
) -> Result<(), String> {
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let good = || -> Result<bool, String> {
            let s = service_status(service)?;
            if s.dwCurrentState != SERVICE_RUNNING {
                return Ok(false);
            }
            let ready = json_file(&directory.join("ready.json"))?;
            if ready["servicePid"].as_u64() != Some(s.dwProcessId as u64)
                || ready["currentVersion"].as_str() != Some(version)
            {
                return Ok(false);
            }
            Ok(field(&info(transaction.port)?, "uniqueid") == Some(transaction.uuid.as_str()))
        };
        if good().unwrap_or(false) {
            return Ok(());
        }
        if Instant::now() > deadline {
            return Err("Updated service failed identity/version health check".into());
        }
        thread::sleep(Duration::from_millis(300));
    }
}
fn hold(directory: &Path, id: &str) -> Result<(), String> {
    write(
        &directory.join("hold.json"),
        &json!({"schema":"rhremote.update-hold/v1","transactionId":id,"expiresAt":now()+600}),
    )
}
fn release_hold(directory: &Path, id: &str) -> Result<(), String> {
    let path = directory.join("hold.json");
    if !path.try_exists().map_err(error)? {
        return Ok(());
    }
    if json_file(&path)?["transactionId"].as_str() != Some(id) {
        return Err("Maintenance hold belongs to another transaction".into());
    }
    fs::remove_file(path).map_err(error)
}
fn valid_id(id: &str) -> bool {
    id.len() == 32
        && id
            .bytes()
            .all(|v| v.is_ascii_digit() || (b'a'..=b'f').contains(&v))
}
fn launch(directory: &Path, id: &str) -> Result<(), String> {
    let worker_dir = directory.join("transactions").join(id);
    tx::no_links(&worker_dir)?;
    let worker = worker_dir.join("update-worker.exe");
    if !worker.try_exists().map_err(error)? {
        fs::copy(std::env::current_exe().map_err(error)?, &worker).map_err(error)?;
    }
    std::process::Command::new(worker)
        .args(["--apply-update", id])
        .creation_flags(0x08000000 | 0x00000008)
        .spawn()
        .map_err(error)?;
    Ok(())
}

/// Only this mode may stop the fixed product service; peers cannot supply installation paths.
pub fn apply_update(id: &str) -> Result<(), String> {
    system()?;
    if !valid_id(id) {
        return Err("Invalid update transaction".into());
    }
    let directory = root()?;
    protect(&directory)?;
    let txdir = directory.join("transactions").join(id);
    tx::no_links(&txdir)?;
    if std::env::current_exe()
        .map_err(error)?
        .canonicalize()
        .map_err(error)?
        != txdir
            .join("update-worker.exe")
            .canonicalize()
            .map_err(error)?
    {
        return Err("Updater worker is outside its protected transaction".into());
    }
    let mut acquired = None;
    for _ in 0..50 {
        if let Ok(value) = lock(&directory) {
            acquired = Some(value);
            break;
        }
        thread::sleep(Duration::from_millis(100));
    }
    let _lock = acquired.ok_or("Another update transaction remains active")?;
    let transaction: Transaction =
        serde_json::from_slice(&read(&txdir.join("transaction.json"))?).map_err(error)?;
    tx::no_links(&transaction.install)?;
    let service = service()?;
    validate_service(&service, &transaction.install)?;
    let mut journal = tx::load(&txdir)?;
    if ["complete", "rolledBack", "deferred"].contains(&journal.phase.as_str()) {
        return Ok(());
    }
    let operation = || -> Result<(), String> {
        if journal.phase != "prepared" {
            // An interrupted transaction may be discovered after clients have returned.
            // Never roll back beneath an active stream, even during recovery.
            let state = info(transaction.port)?;
            let already_held = field(&state, "RHUpdateHeld") == Some("1");
            idle(&state, already_held)?;
            hold(&directory, id)?;
            idle(&info(transaction.port)?, true)?;
            return Err("Interrupted transaction requires rollback".into());
        }
        let verified = protocol::verify(
            &read(&txdir.join("manifest.json"))?,
            &read(&txdir.join("manifest.sig"))?,
        )?;
        let current = installed(&transaction.install)?;
        if current.version != transaction.previous_version
            || !verified.newer_than(&current.version)?
        {
            return Err("Installed version changed before update".into());
        }
        let archive = txdir.join("server.zip");
        if fs::metadata(&archive).map_err(error)?.len() != verified.get().size
            || !protocol::file_sha256(&archive)?.eq_ignore_ascii_case(&verified.get().sha256)
        {
            return Err("Staged archive changed after validation".into());
        }
        let mut zip =
            zip::ZipArchive::new(fs::File::open(&archive).map_err(error)?).map_err(error)?;
        use std::io::Read;
        let mut bytes = Vec::new();
        zip.by_name("server-package.json")
            .map_err(error)?
            .take(1_048_577)
            .read_to_end(&mut bytes)
            .map_err(error)?;
        if bytes.len() > 1_048_576 || bytes != read(&txdir.join("package/server-package.json"))? {
            return Err("Staged package metadata changed".into());
        }
        let package: Package = serde_json::from_slice(&bytes).map_err(error)?;
        package.validate()?;
        if package.version != verified.get().version
            || package.commit.to_lowercase() != verified.get().source_commit.to_lowercase()
        {
            return Err("Staged package identity changed".into());
        }
        for file in &package.files {
            let path = txdir.join("package").join(&file.path);
            tx::no_links(&path)?;
            if fs::metadata(&path).map_err(error)?.len() != file.size
                || !protocol::file_sha256(&path)?.eq_ignore_ascii_case(&file.sha256)
            {
                return Err("Staged update file changed".into());
            }
        }
        let prior = idle(&info(transaction.port)?, false)?;
        if prior.uuid != transaction.uuid {
            return Err("Server identity changed before maintenance".into());
        }
        hold(&directory, id)?;
        let latest = idle(&info(transaction.port)?, true)?;
        if latest.uuid != transaction.uuid {
            return Err("Server identity changed during maintenance".into());
        }
        status(
            &directory,
            "installing",
            &current.version,
            Some(&package.version),
            None,
            None,
        )?;
        stop(&service)?;
        tx::apply(&transaction.install, &txdir, &package, &mut journal)?;
        start(&service)?;
        health(&directory, &service, &transaction, &package.version)?;
        journal.phase = "complete".into();
        tx::save(&txdir, &journal)?;
        release_hold(&directory, id)?;
        status(
            &directory,
            "upToDate",
            &package.version,
            Some(&package.version),
            None,
            None,
        )
    };
    let mut operation = operation;
    if let Err(failure) = operation() {
        journal.error = Some(failure.clone());
        if journal.entries.is_empty() {
            journal.phase = "deferred".into();
            tx::save(&txdir, &journal)?;
            let _ = start(&service);
            let _ = release_hold(&directory, id);
            status(
                &directory,
                "deferred",
                &transaction.previous_version,
                None,
                None,
                Some(&failure),
            )?;
        } else {
            hold(&directory, id)?;
            stop(&service)?;
            tx::rollback(&transaction.install, &txdir, &mut journal)?;
            start(&service)?;
            health(
                &directory,
                &service,
                &transaction,
                &transaction.previous_version,
            )?;
            release_hold(&directory, id)?;
            status(
                &directory,
                "rolledBack",
                &transaction.previous_version,
                None,
                None,
                Some(&failure),
            )?;
        }
        return Err(failure);
    }
    Ok(())
}

pub struct Manager {
    stop: Arc<AtomicBool>,
    thread: Option<thread::JoinHandle<()>>,
}
impl Drop for Manager {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(handle) = self.thread.take() {
            if handle.is_finished() {
                let _ = handle.join();
            }
        }
    }
}
struct Staging(PathBuf);
impl Drop for Staging {
    fn drop(&mut self) {
        if !self.0.join("journal.json").exists() && tx::no_links(&self.0).is_ok() {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
}
pub fn start_manager(install: PathBuf, config: PathBuf) -> Result<Manager, String> {
    system()?;
    let directory = root()?;
    protect(&directory)?;
    let current = installed(&install)?.version;
    let port = port(&config)?;
    write(
        &directory.join("ready.json"),
        &json!({"currentVersion":current,"servicePid":std::process::id(),"startedAt":now()}),
    )?;
    let stop = Arc::new(AtomicBool::new(false));
    let cancel = stop.clone();
    let thread = thread::spawn(move || {
        let stopped =
            || cancel.load(Ordering::SeqCst) || crate::service::STOP.load(Ordering::SeqCst);
        let mut next = Instant::now() + Duration::from_secs(60);
        let mut pending = None;
        let mut install_requested = false;
        let _ = status(&directory, "idle", &current, None, None, None);
        while !stopped() {
            let turn = || -> Result<(), String> {
                let _lock = match lock(&directory) {
                    Ok(v) => v,
                    Err(_) => return Ok(()),
                };
                // Recover a durable interrupted write before doing any new work.
                let transactions = directory.join("transactions");
                fs::create_dir_all(&transactions).map_err(error)?;
                let mut finished = Vec::new();
                let mut failed_versions = std::collections::HashSet::new();
                for entry in fs::read_dir(&transactions).map_err(error)? {
                    let entry = entry.map_err(error)?;
                    let id = entry.file_name().to_string_lossy().into_owned();
                    if !valid_id(&id) {
                        continue;
                    }
                    tx::no_links(&entry.path())?;
                    if let Ok(journal) = tx::load(&entry.path()) {
                        if !["complete", "rolledBack", "deferred"].contains(&journal.phase.as_str())
                        {
                            let state = info(port)?;
                            let held = field(&state, "RHUpdateHeld") == Some("1");
                            if idle(&state, held).is_err() {
                                return status(
                                    &directory,
                                    "waitingForIdle",
                                    &current,
                                    None,
                                    None,
                                    Some(
                                        "Interrupted update is waiting for active connections to end",
                                    ),
                                );
                            }
                            launch(&directory, &id)?;
                            next = Instant::now() + Duration::from_secs(60);
                            return Ok(());
                        }
                        if journal.phase == "rolledBack" {
                            if let Ok(package) =
                                json_file(&entry.path().join("package/server-package.json"))
                            {
                                if let Some(version) = package["version"].as_str() {
                                    failed_versions.insert(version.to_owned());
                                }
                            }
                        }
                        finished.push(entry.path());
                    }
                }
                finished.sort_by_key(|v| {
                    fs::metadata(v.join("journal.json"))
                        .and_then(|m| m.modified())
                        .ok()
                });
                let remove = finished.len().saturating_sub(2);
                for path in finished.into_iter().take(remove) {
                    tx::no_links(&path)?;
                    fs::remove_dir_all(path).map_err(error)?;
                }
                let settings = settings(&directory)?;
                let request_path = directory.join("request.json");
                let request = if request_path.try_exists().map_err(error)? {
                    let value = json_file(&request_path)?;
                    fs::remove_file(&request_path).map_err(error)?;
                    value["action"].as_str().unwrap_or("").to_owned()
                } else {
                    String::new()
                };
                let manual = request == "check" || request == "install";
                if request == "install" {
                    install_requested = true;
                }
                if !manual && !settings.enabled && !(pending.is_some() && install_requested) {
                    return Ok(());
                }
                if manual || Instant::now() >= next {
                    next = Instant::now()
                        + Duration::from_secs(settings.check_interval_minutes as u64 * 60);
                    status(&directory, "checking", &current, None, None, None)?;
                    let mut found = None;
                    let mut errors = Vec::new();
                    for source in [
                        settings.preferred_source,
                        settings.preferred_source.fallback(),
                    ] {
                        if stopped() {
                            return Ok(());
                        }
                        match net::check(source) {
                            Ok(release) => {
                                found = Some((source, release));
                                break;
                            }
                            Err(e) => errors.push(format!("{}: {e}", source.name())),
                        }
                    }
                    let (source, (release, bytes, signature)) = found.ok_or(errors.join("; "))?;
                    if !release.newer_than(&current)? {
                        pending = None;
                        install_requested = false;
                        return status(
                            &directory,
                            "upToDate",
                            &current,
                            Some(&release.get().version),
                            Some(source),
                            None,
                        );
                    }
                    if failed_versions.contains(&release.get().version) && request != "install" {
                        pending = None;
                        return status(
                            &directory,
                            "rolledBack",
                            &current,
                            Some(&release.get().version),
                            Some(source),
                            Some(
                                "This version failed its health check; automatic retries are disabled until another version is released",
                            ),
                        );
                    }
                    status(
                        &directory,
                        "available",
                        &current,
                        Some(&release.get().version),
                        Some(source),
                        None,
                    )?;
                    pending = Some((source, release, bytes, signature));
                }
                if let Some((source, release, bytes, signature)) = &pending {
                    if !settings.enabled && !install_requested {
                        return Ok(());
                    }
                    let snapshot = match idle(&info(port)?, false) {
                        Ok(v) => v,
                        Err(e) => {
                            status(
                                &directory,
                                "waitingForIdle",
                                &current,
                                Some(&release.get().version),
                                Some(*source),
                                Some(&e),
                            )?;
                            return Ok(());
                        }
                    };
                    let _quiet = snapshot.quiet;
                    let id = tx::nonce()?;
                    let txdir = transactions.join(&id);
                    protect(&txdir)?;
                    let _staging = Staging(txdir.clone());
                    write(
                        &txdir.join("transaction.json"),
                        &Transaction {
                            install: install.clone(),
                            port,
                            previous_version: current.clone(),
                            uuid: snapshot.uuid,
                            created_at: now(),
                        },
                    )?;
                    tx::atomic(&txdir.join("manifest.json"), bytes)?;
                    tx::atomic(&txdir.join("manifest.sig"), signature)?;
                    status(
                        &directory,
                        "downloading",
                        &current,
                        Some(&release.get().version),
                        Some(*source),
                        None,
                    )?;
                    let archive = txdir.join("server.zip");
                    if let Err(first) = net::download(*source, release, &archive, &stopped) {
                        if archive.try_exists().map_err(error)? {
                            fs::remove_file(&archive).map_err(error)?;
                        }
                        if stopped() {
                            return Err(first);
                        }
                        net::download(source.fallback(), release, &archive, &stopped)?;
                    }
                    protocol::extract(&archive, &txdir.join("package"), release)?;
                    // Only after the archive is validated is this transaction recoverable/installable.
                    tx::save(&txdir, &Journal::default())?;
                    launch(&directory, &id)?;
                    pending = None;
                    install_requested = false;
                    next = Instant::now() + Duration::from_secs(60);
                }
                Ok(())
            };
            let mut turn = turn;
            if let Err(failure) = turn() {
                let _ = status(&directory, "error", &current, None, None, Some(&failure));
                next = Instant::now() + Duration::from_secs(900);
                pending = None;
            }
            for _ in 0..20 {
                if stopped() {
                    break;
                }
                thread::sleep(Duration::from_millis(250));
            }
        }
    });
    Ok(Manager {
        stop,
        thread: Some(thread),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn live_and_inflight_connections_or_recent_handshakes_never_count_as_idle() {
        let good = "<root><uniqueid>host</uniqueid><RHUpdateCoordinated>1</RHUpdateCoordinated><RHUpdateHeld>0</RHUpdateHeld><RHUpdatePreparing>0</RHUpdatePreparing><RHUpdateSessions>0</RHUpdateSessions><RHUpdateLaunchPending>0</RHUpdateLaunchPending><RHUpdateQuietMs>120000</RHUpdateQuietMs></root>";
        assert!(idle(good, false).is_ok());
        assert!(idle(good, true).is_err());
        for (from, to) in [
            ("Sessions>0", "Sessions>1"),
            ("Preparing>0", "Preparing>1"),
            ("LaunchPending>0", "LaunchPending>1"),
            ("QuietMs>120000", "QuietMs>119999"),
            ("Coordinated>1", "Coordinated>0"),
        ] {
            assert!(idle(&good.replace(from, to), false).is_err());
        }
        assert!(idle("<root><state>SUNSHINE_SERVER_FREE</state></root>", false).is_err());
    }
}
