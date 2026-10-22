use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use windows::{
    Win32::{
        Foundation::FreeLibrary,
        System::{LibraryLoader::*, Registry::*, Services::*},
    },
    core::{PWSTR, s, w},
};
pub static STOP: AtomicBool = AtomicBool::new(false);
static HANDLE: AtomicUsize = AtomicUsize::new(0);
static IS_SERVICE: AtomicBool = AtomicBool::new(false);
fn status(state: SERVICE_STATUS_CURRENT_STATE, code: u32) {
    let value = SERVICE_STATUS {
        dwServiceType: SERVICE_WIN32_OWN_PROCESS,
        dwCurrentState: state,
        dwControlsAccepted: if state == SERVICE_RUNNING {
            SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN
        } else {
            0
        },
        dwWin32ExitCode: code,
        dwServiceSpecificExitCode: 0,
        dwCheckPoint: 0,
        dwWaitHint: if state == SERVICE_STOP_PENDING {
            15000
        } else {
            0
        },
    };
    unsafe {
        let _ = SetServiceStatus(
            SERVICE_STATUS_HANDLE(HANDLE.load(Ordering::SeqCst) as _),
            &value,
        );
    }
}
unsafe extern "system" fn control(code: u32) {
    if code == SERVICE_CONTROL_STOP || code == SERVICE_CONTROL_SHUTDOWN {
        STOP.store(true, Ordering::SeqCst);
        status(SERVICE_STOP_PENDING, 0);
    }
}
unsafe extern "system" fn entry(_: u32, _: *mut PWSTR) {
    let handle = unsafe { RegisterServiceCtrlHandlerW(w!("RHRemoteHost"), Some(control)) };
    let Ok(handle) = handle else { return };
    HANDLE.store(handle.0 as usize, Ordering::SeqCst);
    IS_SERVICE.store(true, Ordering::SeqCst);
    status(SERVICE_RUNNING, 0);
    let result = crate::run();
    status(SERVICE_STOPPED, if result.is_ok() { 0 } else { 1 });
}
pub fn dispatch() -> Result<(), Box<dyn std::error::Error>> {
    let table = [
        SERVICE_TABLE_ENTRYW {
            lpServiceName: PWSTR(w!("RHRemoteHost").as_ptr() as _),
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
        return Err("Ctrl+Alt+Delete 需要以 Windows 服务运行的 RHRemoteHost".into());
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
        return Err("主机未启用服务发送安全操作序列，请在主机运行 RHRemoteHost 安装程序并启用 Ctrl+Alt+Delete".into());
    }
    unsafe {
        let module = LoadLibraryExW(w!("sas.dll"), None, LOAD_LIBRARY_SEARCH_SYSTEM32)
            .map_err(|_| "Windows 安全操作接口不可用")?;
        let proc = GetProcAddress(module, s!("SendSAS"));
        if let Some(proc) = proc {
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
