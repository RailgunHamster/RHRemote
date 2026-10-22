//! Own the Sunshine process from the single RHRemote-Sunshine SCM service.
//! Retains upstream sunshinesvc's SYSTEM console token, per-session job,
//! application breakaway and Ctrl-C shutdown behavior without a second service.
use std::{
    ffi::OsStr,
    fs::{self, OpenOptions},
    io::Write,
    mem::size_of,
    os::windows::{ffi::OsStrExt, io::AsRawHandle},
    path::Path,
    sync::atomic::{AtomicU32, Ordering},
};
use windows::{
    Win32::{
        Foundation::*,
        Security::*,
        System::{Console::*, JobObjects::*, RemoteDesktop::*, Threading::*},
    },
    core::{PCWSTR, PWSTR, w},
};

static PID: AtomicU32 = AtomicU32::new(0);
static SESSION: AtomicU32 = AtomicU32::new(u32::MAX);
static STARTS: AtomicU32 = AtomicU32::new(0);

struct Handle(HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}

fn wide(value: &OsStr) -> Vec<u16> {
    value.encode_wide().chain(Some(0)).collect()
}

pub fn health() -> serde_json::Value {
    serde_json::json!({"pid":PID.load(Ordering::SeqCst),"sessionId":SESSION.load(Ordering::SeqCst),"starts":STARTS.load(Ordering::SeqCst)})
}

pub fn log(event: &str, detail: impl std::fmt::Display) {
    let root = std::path::PathBuf::from(std::env::var_os("ProgramData").unwrap_or_default())
        .join("RHRemoteHost");
    if let Ok(mut file) = OpenOptions::new()
        .create(true)
        .append(true)
        .open(root.join("service-events.log"))
    {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis();
        let _ = writeln!(file, "{now} {event} {detail}");
    }
}

unsafe fn token_for_session(session: u32) -> windows::core::Result<Handle> {
    let mut current = HANDLE::default();
    unsafe {
        OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE, &mut current)?;
    }
    let current = Handle(current);
    let mut primary = HANDLE::default();
    unsafe {
        DuplicateTokenEx(
            current.0,
            TOKEN_ALL_ACCESS,
            None,
            SecurityImpersonation,
            TokenPrimary,
            &mut primary,
        )?;
    }
    let primary = Handle(primary);
    unsafe {
        SetTokenInformation(
            primary.0,
            TokenSessionId,
            (&session as *const u32).cast(),
            4,
        )?;
    }
    Ok(primary)
}

struct Attributes {
    list: LPPROC_THREAD_ATTRIBUTE_LIST,
    _storage: Vec<usize>,
}
impl Attributes {
    fn new() -> windows::core::Result<Self> {
        let mut bytes = 0;
        unsafe {
            let _ = InitializeProcThreadAttributeList(None, 2, None, &mut bytes);
        }
        let mut storage = vec![0usize; bytes.div_ceil(size_of::<usize>())];
        let list = LPPROC_THREAD_ATTRIBUTE_LIST(storage.as_mut_ptr().cast());
        unsafe {
            InitializeProcThreadAttributeList(Some(list), 2, None, &mut bytes)?;
        }
        Ok(Self {
            list,
            _storage: storage,
        })
    }
}
impl Drop for Attributes {
    fn drop(&mut self) {
        unsafe {
            DeleteProcThreadAttributeList(self.list);
        }
    }
}

unsafe fn spawn(
    token: HANDLE,
    exe: &Path,
    config: &Path,
    directory: &Path,
    job: HANDLE,
    output: HANDLE,
) -> Result<Handle, String> {
    let attributes = Attributes::new().map_err(|e| e.to_string())?;
    unsafe {
        UpdateProcThreadAttribute(
            attributes.list,
            0,
            PROC_THREAD_ATTRIBUTE_HANDLE_LIST as usize,
            Some((&output as *const HANDLE).cast()),
            size_of::<HANDLE>(),
            None,
            None,
        )
        .map_err(|e| e.to_string())?;
        // The job is assigned as part of process creation: no running orphan can
        // escape if the service exits between CreateProcess and AssignProcess.
        UpdateProcThreadAttribute(
            attributes.list,
            0,
            PROC_THREAD_ATTRIBUTE_JOB_LIST as usize,
            Some((&job as *const HANDLE).cast()),
            size_of::<HANDLE>(),
            None,
            None,
        )
        .map_err(|e| e.to_string())?;
    }
    let app = wide(exe.as_os_str());
    let cwd = wide(directory.as_os_str());
    let mut command = wide(OsStr::new(&format!(
        "\"{}\" \"{}\"",
        exe.display(),
        config.join("sunshine.conf").display()
    )));
    let startup = STARTUPINFOEXW {
        StartupInfo: STARTUPINFOW {
            cb: size_of::<STARTUPINFOEXW>() as u32,
            lpDesktop: PWSTR(w!("winsta0\\default").as_ptr() as _),
            dwFlags: STARTF_USESTDHANDLES,
            hStdOutput: output,
            hStdError: output,
            ..Default::default()
        },
        lpAttributeList: attributes.list,
    };
    let mut process = PROCESS_INFORMATION::default();
    unsafe {
        CreateProcessAsUserW(
            Some(token),
            PCWSTR(app.as_ptr()),
            Some(PWSTR(command.as_mut_ptr())),
            None,
            None,
            true,
            CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT,
            None,
            PCWSTR(cwd.as_ptr()),
            &startup.StartupInfo,
            &mut process,
        )
        .map_err(|e| e.to_string())?;
        let _ = CloseHandle(process.hThread);
    }
    PID.store(process.dwProcessId, Ordering::SeqCst);
    STARTS.fetch_add(1, Ordering::SeqCst);
    log(
        "sunshine_started",
        format!(
            "pid={} session={}",
            process.dwProcessId,
            SESSION.load(Ordering::SeqCst)
        ),
    );
    Ok(Handle(process.hProcess))
}

unsafe fn graceful_stop(token: HANDLE, process: HANDLE, pid: u32) {
    // Ctrl-C must be sent by a helper in the same interactive session.
    if let Ok(exe) = std::env::current_exe() {
        let app = wide(exe.as_os_str());
        let mut command = wide(OsStr::new(&format!(
            "\"{}\" --terminate-sunshine {pid}",
            exe.display()
        )));
        let startup = STARTUPINFOW {
            cb: size_of::<STARTUPINFOW>() as u32,
            lpDesktop: PWSTR(w!("winsta0\\default").as_ptr() as _),
            ..Default::default()
        };
        let mut helper = PROCESS_INFORMATION::default();
        if unsafe {
            CreateProcessAsUserW(
                Some(token),
                PCWSTR(app.as_ptr()),
                Some(PWSTR(command.as_mut_ptr())),
                None,
                None,
                false,
                CREATE_UNICODE_ENVIRONMENT | DETACHED_PROCESS,
                None,
                PCWSTR::null(),
                &startup,
                &mut helper,
            )
        }
        .is_ok()
        {
            let helper_process = Handle(helper.hProcess);
            let _helper_thread = Handle(helper.hThread);
            if unsafe { WaitForSingleObject(helper_process.0, 3000) } != WAIT_OBJECT_0 {
                unsafe {
                    let _ = TerminateProcess(helper_process.0, 1);
                }
            }
        }
    }
    if unsafe { WaitForSingleObject(process, 15000) } != WAIT_OBJECT_0 {
        unsafe {
            let _ = TerminateProcess(process, ERROR_PROCESS_ABORTED.0);
            let _ = WaitForSingleObject(process, 1000);
        }
    }
}

pub fn terminate_console(pid: u32) -> Result<(), String> {
    unsafe {
        let target = Handle(
            OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
                .map_err(|e| e.to_string())?,
        );
        let mut path = [0u16; 32768];
        let mut length = path.len() as u32;
        QueryFullProcessImageNameW(
            target.0,
            PROCESS_NAME_WIN32,
            PWSTR(path.as_mut_ptr()),
            &mut length,
        )
        .map_err(|e| e.to_string())?;
        let actual = std::path::PathBuf::from(String::from_utf16_lossy(&path[..length as usize]))
            .canonicalize()
            .map_err(|e| e.to_string())?;
        let current = std::env::current_exe().map_err(|e| e.to_string())?;
        let expected = current
            .parent()
            .and_then(Path::parent)
            .ok_or("Server installation unavailable")?
            .join("sunshine.exe")
            .canonicalize()
            .map_err(|e| e.to_string())?;
        if !actual
            .to_string_lossy()
            .eq_ignore_ascii_case(&expected.to_string_lossy())
        {
            return Err("Refusing to terminate an unrelated process".into());
        }
        AttachConsole(pid).map_err(|e| e.to_string())?;
        SetConsoleCtrlHandler(None, true).map_err(|e| e.to_string())?;
        GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0).map_err(|e| e.to_string())?;
    }
    Ok(())
}

pub fn run(exe: &Path, config: &Path) -> Result<(), String> {
    if !exe.is_absolute() || !exe.is_file() || !config.is_absolute() || !config.is_dir() {
        return Err("Invalid Sunshine service paths".into());
    }
    let directory = exe.parent().ok_or("Sunshine directory unavailable")?;
    let root =
        std::path::PathBuf::from(std::env::var_os("ProgramData").ok_or("ProgramData unavailable")?)
            .join("RHRemoteHost");
    let log_path = root.join("sunshine-service.log");
    if fs::metadata(&log_path).is_ok_and(|m| m.len() > 5_000_000) {
        let _ = fs::rename(&log_path, root.join("sunshine-service.previous.log"));
    }
    let output = OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_path)
        .map_err(|e| e.to_string())?;
    let output_handle = HANDLE(output.as_raw_handle());
    unsafe {
        SetHandleInformation(output_handle, HANDLE_FLAG_INHERIT.0, HANDLE_FLAG_INHERIT)
            .map_err(|e| e.to_string())?;
    }
    let stop = crate::service::stop_event();
    let changed = crate::service::session_event();
    loop {
        if unsafe { WaitForSingleObject(stop, 0) } == WAIT_OBJECT_0 {
            break;
        }
        let session = unsafe { WTSGetActiveConsoleSessionId() };
        if session == u32::MAX {
            unsafe {
                let _ = WaitForSingleObject(stop, 250);
            }
            continue;
        }
        SESSION.store(session, Ordering::SeqCst);
        let token = match unsafe { token_for_session(session) } {
            Ok(token) => token,
            Err(e) => {
                log("console_token_failed", e);
                unsafe {
                    let _ = WaitForSingleObject(stop, 2000);
                }
                continue;
            }
        };
        let job =
            Handle(unsafe { CreateJobObjectW(None, PCWSTR::null()) }.map_err(|e| e.to_string())?);
        let mut limits = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
        limits.BasicLimitInformation.LimitFlags =
            JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_BREAKAWAY_OK;
        unsafe {
            SetInformationJobObject(
                job.0,
                JobObjectExtendedLimitInformation,
                (&limits as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION).cast(),
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
            .map_err(|e| e.to_string())?;
        }
        let process = match unsafe { spawn(token.0, exe, config, directory, job.0, output_handle) }
        {
            Ok(process) => process,
            Err(e) => {
                log("sunshine_spawn_failed", e);
                unsafe {
                    let _ = WaitForSingleObject(stop, 2000);
                }
                continue;
            }
        };
        loop {
            let event =
                unsafe { WaitForMultipleObjects(&[stop, process.0, changed], false, INFINITE) };
            if event.0 == WAIT_OBJECT_0.0 + 2
                && unsafe { WTSGetActiveConsoleSessionId() } == session
            {
                continue;
            }
            if event == WAIT_OBJECT_0 || event.0 == WAIT_OBJECT_0.0 + 2 {
                unsafe {
                    graceful_stop(token.0, process.0, PID.load(Ordering::SeqCst));
                }
            } else if event.0 == WAIT_OBJECT_0.0 + 1 {
                let mut code = 0;
                unsafe {
                    let _ = GetExitCodeProcess(process.0, &mut code);
                }
                log("sunshine_exited", format!("code={code}"));
                if code == ERROR_SHUTDOWN_IN_PROGRESS.0 {
                    crate::service::request_stop();
                }
            } else {
                return Err("Sunshine supervisor wait failed".into());
            }
            break;
        }
        PID.store(0, Ordering::SeqCst);
        drop(process);
        drop(job);
        drop(token);
        // No initial three-second delay. Only an actual exit is backed off.
        unsafe {
            let _ = WaitForSingleObject(stop, 1000);
        }
    }
    SESSION.store(u32::MAX, Ordering::SeqCst);
    Ok(())
}
