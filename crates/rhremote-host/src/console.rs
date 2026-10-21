use std::{ffi::c_void, mem::size_of, path::Path};
use windows::{
    Win32::{
        Foundation::*,
        Security::*,
        System::{RemoteDesktop::*, Threading::*},
    },
    core::{PCWSTR, PWSTR, w},
};
struct Handle(HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}
fn wide(value: &std::ffi::OsStr) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    value.encode_wide().chain(Some(0)).collect()
}
pub fn helper(script: &Path, input: &Path, output: &Path) -> Result<(), String> {
    use windows::Win32::System::StationsAndDesktops::*;
    unsafe {
        let desktop = OpenInputDesktop(
            DF_ALLOWOTHERACCOUNTHOOK,
            false,
            DESKTOP_ACCESS_FLAGS(0x10000000),
        )
        .map_err(|e| e.to_string())?;
        let mut name = [0u16; 128];
        let mut needed = 0;
        let result = GetUserObjectInformationW(
            HANDLE(desktop.0),
            UOI_NAME,
            Some(name.as_mut_ptr().cast()),
            256,
            Some(&mut needed),
        );
        let _ = CloseDesktop(desktop);
        result.map_err(|e| e.to_string())?;
        let length = name
            .iter()
            .position(|n| *n == 0)
            .ok_or("Invalid desktop name")?;
        let name = String::from_utf16_lossy(&name[..length]);
        let mut desktop: Vec<u16> = format!("winsta0\\{name}")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let windows = std::env::var_os("SystemRoot").ok_or("Windows directory missing")?;
        let exe = std::path::PathBuf::from(windows)
            .join("System32/WindowsPowerShell/v1.0/powershell.exe");
        let app = wide(exe.as_os_str());
        let mut command=wide(format!("\"{}\" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"{}\" -RequestFile \"{}\" -OutputFile \"{}\"",exe.display(),script.display(),input.display(),output.display()).as_ref());
        let startup = STARTUPINFOW {
            cb: size_of::<STARTUPINFOW>() as u32,
            lpDesktop: PWSTR(desktop.as_mut_ptr()),
            dwFlags: STARTF_USESHOWWINDOW,
            wShowWindow: 0,
            ..Default::default()
        };
        let mut process = PROCESS_INFORMATION::default();
        CreateProcessW(
            PCWSTR(app.as_ptr()),
            Some(PWSTR(command.as_mut_ptr())),
            None,
            None,
            false,
            CREATE_NO_WINDOW,
            None,
            w!("C:\\Windows\\System32"),
            &startup,
            &mut process,
        )
        .map_err(|e| e.to_string())?;
        let process_handle = Handle(process.hProcess);
        let _thread = Handle(process.hThread);
        if WaitForSingleObject(process_handle.0, 18000) != WAIT_OBJECT_0 {
            let _ = TerminateProcess(process_handle.0, 1);
            return Err("Display helper timed out".into());
        }
        let mut code = 0;
        GetExitCodeProcess(process_handle.0, &mut code).map_err(|e| e.to_string())?;
        if code == 0 {
            Ok(())
        } else {
            Err("Display helper failed".into())
        }
    }
}
/// Only launches this signed-in installation's own bounded display worker as SYSTEM in the console session.
/// Does not log on, unlock, reconnect an RDP user, or alter other sessions.
pub fn worker(request: &Path, response: &Path) -> Result<(), String> {
    unsafe {
        let session = WTSGetActiveConsoleSessionId();
        if session == u32::MAX {
            return Err("Windows 当前没有控制台会话".into());
        }
        let mut token = HANDLE::default();
        OpenProcessToken(
            GetCurrentProcess(),
            TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ASSIGN_PRIMARY | TOKEN_ADJUST_SESSIONID,
            &mut token,
        )
        .map_err(|e| e.to_string())?;
        let token = Handle(token);
        let mut primary = HANDLE::default();
        DuplicateTokenEx(
            token.0,
            TOKEN_ALL_ACCESS,
            None,
            SecurityImpersonation,
            TokenPrimary,
            &mut primary,
        )
        .map_err(|e| e.to_string())?;
        let primary = Handle(primary);
        SetTokenInformation(
            primary.0,
            TokenSessionId,
            (&session as *const u32).cast::<c_void>(),
            4,
        )
        .map_err(|e| e.to_string())?;
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        let application = wide(exe.as_os_str());
        let command = format!(
            "\"{}\" --display-worker \"{}\" \"{}\"",
            exe.display(),
            request.display(),
            response.display()
        );
        let mut command = wide(command.as_ref());
        let mut desktop: Vec<u16> = "winsta0\\default".encode_utf16().chain(Some(0)).collect();
        let startup = STARTUPINFOW {
            cb: size_of::<STARTUPINFOW>() as u32,
            lpDesktop: PWSTR(desktop.as_mut_ptr()),
            dwFlags: STARTF_USESHOWWINDOW,
            wShowWindow: 0,
            ..Default::default()
        };
        let mut process = PROCESS_INFORMATION::default();
        CreateProcessAsUserW(
            Some(primary.0),
            PCWSTR(application.as_ptr()),
            Some(PWSTR(command.as_mut_ptr())),
            None,
            None,
            false,
            CREATE_NO_WINDOW,
            None,
            w!("C:\\Windows\\System32"),
            &startup,
            &mut process,
        )
        .map_err(|e| e.to_string())?;
        let process_handle = Handle(process.hProcess);
        let _thread = Handle(process.hThread);
        if WaitForSingleObject(process_handle.0, 20000) != WAIT_OBJECT_0 {
            let _ = TerminateProcess(process_handle.0, 1);
            let _ = WaitForSingleObject(process_handle.0, 1000);
            return Err("显示操作超时".into());
        }
        let mut code = 0;
        GetExitCodeProcess(process_handle.0, &mut code).map_err(|e| e.to_string())?;
        if code != 0 {
            return Err(format!("显示操作失败 ({code})"));
        }
        Ok(())
    }
}
