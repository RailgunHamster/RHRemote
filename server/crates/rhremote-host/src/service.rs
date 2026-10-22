use std::{
    ffi::c_void,
    sync::atomic::{AtomicBool, AtomicUsize, Ordering},
};
use windows::{
    Win32::{
        Foundation::{CloseHandle, FreeLibrary, HANDLE},
        System::{LibraryLoader::*, Registry::*, Services::*, Threading::*},
    },
    core::{PCWSTR, PWSTR, s, w},
};
pub static STOP: AtomicBool = AtomicBool::new(false);
static STATUS_HANDLE: AtomicUsize = AtomicUsize::new(0);
static STOP_EVENT: AtomicUsize = AtomicUsize::new(0);
static SESSION_EVENT: AtomicUsize = AtomicUsize::new(0);
static IS_SERVICE: AtomicBool = AtomicBool::new(false);
static UNIFIED: AtomicBool = AtomicBool::new(false);

pub fn stop_event() -> HANDLE {
    HANDLE(STOP_EVENT.load(Ordering::SeqCst) as _)
}
pub fn session_event() -> HANDLE {
    HANDLE(SESSION_EVENT.load(Ordering::SeqCst) as _)
}
pub fn unified() -> bool {
    UNIFIED.load(Ordering::SeqCst)
}
pub fn request_stop() {
    STOP.store(true, Ordering::SeqCst);
    let handle = stop_event();
    if !handle.is_invalid() {
        unsafe {
            let _ = SetEvent(handle);
        }
    }
}
fn status(state: SERVICE_STATUS_CURRENT_STATE, code: u32) {
    let value = SERVICE_STATUS {
        dwServiceType: SERVICE_WIN32_OWN_PROCESS,
        dwCurrentState: state,
        dwControlsAccepted: if state == SERVICE_RUNNING {
            SERVICE_ACCEPT_STOP
                | SERVICE_ACCEPT_SHUTDOWN
                | SERVICE_ACCEPT_PRESHUTDOWN
                | if unified() {
                    SERVICE_ACCEPT_SESSIONCHANGE
                } else {
                    0
                }
        } else {
            0
        },
        dwWin32ExitCode: code,
        dwServiceSpecificExitCode: 0,
        dwCheckPoint: 0,
        dwWaitHint: if state == SERVICE_START_PENDING || state == SERVICE_STOP_PENDING {
            30000
        } else {
            0
        },
    };
    unsafe {
        let _ = SetServiceStatus(
            SERVICE_STATUS_HANDLE(STATUS_HANDLE.load(Ordering::SeqCst) as _),
            &value,
        );
    }
}
unsafe extern "system" fn control(
    code: u32,
    event: u32,
    _data: *mut c_void,
    _context: *mut c_void,
) -> u32 {
    match code {
        SERVICE_CONTROL_STOP | SERVICE_CONTROL_SHUTDOWN | SERVICE_CONTROL_PRESHUTDOWN => {
            status(SERVICE_STOP_PENDING, 0);
            request_stop();
        }
        SERVICE_CONTROL_SESSIONCHANGE if event == 1 => unsafe {
            let _ = SetEvent(session_event());
        },
        SERVICE_CONTROL_INTERROGATE => {}
        _ => return 120,
    }
    0
}
unsafe extern "system" fn entry(_: u32, _: *mut PWSTR) {
    let name = if unified() {
        w!("SunshineService")
    } else {
        w!("RHRemoteHost")
    };
    let handle = unsafe { RegisterServiceCtrlHandlerExW(name, Some(control), None) };
    let Ok(handle) = handle else { return };
    STATUS_HANDLE.store(handle.0 as usize, Ordering::SeqCst);
    IS_SERVICE.store(true, Ordering::SeqCst);
    status(SERVICE_START_PENDING, 0);
    let events = unsafe {
        CreateEventW(None, true, false, PCWSTR::null()).and_then(|stop| {
            CreateEventW(None, false, false, PCWSTR::null())
                .map(|session| (stop, session))
                .inspect_err(|_| {
                    let _ = CloseHandle(stop);
                })
        })
    };
    let Ok((stop, session)) = events else {
        status(SERVICE_STOPPED, 1);
        return;
    };
    STOP_EVENT.store(stop.0 as usize, Ordering::SeqCst);
    SESSION_EVENT.store(session.0 as usize, Ordering::SeqCst);
    let worker = if unified() {
        Some(std::thread::spawn(|| {
            let result = crate::runtime::parse(&std::env::args().skip(1).collect::<Vec<_>>())
                .and_then(|options| {
                    crate::supervisor::run(options.sunshine.as_ref().unwrap(), &options.config)
                });
            if let Err(error) = &result {
                crate::supervisor::log("supervisor_failed", error);
                request_stop();
            }
            result
        }))
    } else {
        None
    };
    crate::supervisor::log(
        "service_start",
        if unified() {
            "RHRemote-Sunshine"
        } else {
            "legacy host"
        },
    );
    let result = crate::run_with_ready(|| {
        if !STOP.load(Ordering::SeqCst) {
            status(SERVICE_RUNNING, 0);
        }
    });
    if let Err(error) = &result {
        crate::supervisor::log("service_failed", error);
    }
    request_stop();
    let worker_ok = worker.is_none_or(|worker| worker.join().is_ok_and(|result| result.is_ok()));
    crate::supervisor::log(
        "service_stop",
        format!("success={}", result.is_ok() && worker_ok),
    );
    unsafe {
        let _ = CloseHandle(stop);
        let _ = CloseHandle(session);
    }
    STOP_EVENT.store(0, Ordering::SeqCst);
    SESSION_EVENT.store(0, Ordering::SeqCst);
    status(
        SERVICE_STOPPED,
        if result.is_ok() && worker_ok { 0 } else { 1 },
    );
}
pub fn dispatch(is_unified: bool) -> Result<(), Box<dyn std::error::Error>> {
    UNIFIED.store(is_unified, Ordering::SeqCst);
    let name = if is_unified {
        w!("SunshineService")
    } else {
        w!("RHRemoteHost")
    };
    let table = [
        SERVICE_TABLE_ENTRYW {
            lpServiceName: PWSTR(name.as_ptr() as _),
            lpServiceProc: Some(entry),
        },
        SERVICE_TABLE_ENTRYW::default(),
    ];
    unsafe {
        StartServiceCtrlDispatcherW(table.as_ptr())?;
    }
    Ok(())
}
pub fn secure_attention() -> Result<serde_json::Value, String> {
    if !IS_SERVICE.load(Ordering::SeqCst) {
        return Err("Ctrl+Alt+Delete 需要以 Windows 服务运行的 RHRemote-Sunshine".into());
    }
    let mut policy = 0u32;
    let mut size = 4u32;
    let result = unsafe {
        RegGetValueW(
            HKEY_LOCAL_MACHINE,
            w!("SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System"),
            w!("SoftwareSASGeneration"),
            RRF_RT_REG_DWORD,
            None,
            Some((&mut policy as *mut u32).cast()),
            Some(&mut size),
        )
    };
    if result.is_err() || ![1, 3].contains(&policy) {
        return Err(
            "主机未启用服务发送安全操作序列，请在服务端安装程序中启用 Ctrl+Alt+Delete".into(),
        );
    }
    unsafe {
        let module = LoadLibraryExW(w!("sas.dll"), None, LOAD_LIBRARY_SEARCH_SYSTEM32)
            .map_err(|_| "Windows 安全操作接口不可用")?;
        if let Some(proc) = GetProcAddress(module, s!("SendSAS")) {
            let call: unsafe extern "system" fn(i32) = std::mem::transmute(proc);
            call(0);
            let _ = FreeLibrary(module);
            Ok(serde_json::json!({"accepted":true,"method":"Windows SendSAS","verified":false}))
        } else {
            let _ = FreeLibrary(module);
            Err("Windows 安全操作接口不可用".into())
        }
    }
}
