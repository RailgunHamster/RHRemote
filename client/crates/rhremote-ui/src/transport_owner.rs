//! Holds original process handles; a reused PID can never extend this lease.
use std::{
    path::PathBuf,
    time::{Duration, Instant},
};
use windows::{
    Win32::{
        Foundation::{CloseHandle, FILETIME, HANDLE, WAIT_OBJECT_0},
        System::{
            Diagnostics::ToolHelp::{
                CreateToolhelp32Snapshot, PROCESSENTRY32W, Process32FirstW, Process32NextW,
                TH32CS_SNAPPROCESS,
            },
            Threading::{
                GetProcessTimes, OpenProcess, PROCESS_NAME_WIN32,
                PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE, QueryFullProcessImageNameW,
                WaitForSingleObject,
            },
        },
    },
    core::PWSTR,
};

struct Process(HANDLE);
impl Drop for Process {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}
impl Process {
    fn open(pid: u32) -> Result<Self, String> {
        unsafe {
            OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                false,
                pid,
            )
            .map(Self)
            .map_err(|e| e.to_string())
        }
    }
    fn ended(&self) -> bool {
        unsafe { WaitForSingleObject(self.0, 0) == WAIT_OBJECT_0 }
    }
    fn times(&self) -> Result<(u64, u64), String> {
        let (mut created, mut exited, mut kernel, mut user) = (
            FILETIME::default(),
            FILETIME::default(),
            FILETIME::default(),
            FILETIME::default(),
        );
        unsafe {
            GetProcessTimes(self.0, &mut created, &mut exited, &mut kernel, &mut user)
                .map_err(|e| e.to_string())?;
        }
        let n = |t: FILETIME| (u64::from(t.dwHighDateTime) << 32) | u64::from(t.dwLowDateTime);
        Ok((n(created), n(exited)))
    }
    fn path(&self) -> Result<PathBuf, String> {
        let mut text = vec![0u16; 32768];
        let mut count = text.len() as u32;
        unsafe {
            QueryFullProcessImageNameW(
                self.0,
                PROCESS_NAME_WIN32,
                PWSTR(text.as_mut_ptr()),
                &mut count,
            )
            .map_err(|e| e.to_string())?;
        }
        Ok(PathBuf::from(
            String::from_utf16(&text[..count as usize]).map_err(|e| e.to_string())?,
        ))
    }
}
fn processes() -> Result<Vec<(u32, u32)>, String> {
    let snapshot = Process(unsafe {
        CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0).map_err(|e| e.to_string())?
    });
    let mut entry = PROCESSENTRY32W::default();
    entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
    let mut rows = Vec::new();
    if unsafe { Process32FirstW(snapshot.0, &mut entry) }.is_ok() {
        loop {
            rows.push((entry.th32ProcessID, entry.th32ParentProcessID));
            if unsafe { Process32NextW(snapshot.0, &mut entry) }.is_err() {
                break;
            }
        }
    }
    Ok(rows)
}
fn same_path(a: &std::path::Path, b: &std::path::Path) -> bool {
    let normalized = |p: &std::path::Path| {
        let path = p.as_os_str().to_string_lossy().replace('/', "\\");
        path.strip_prefix("\\\\?\\").unwrap_or(&path).to_lowercase()
    };
    normalized(a) == normalized(b)
}

pub(crate) struct Owner {
    pid: u32,
    process: Process,
    created: u64,
    native: PathBuf,
}
impl Owner {
    pub fn capture(pid: u32) -> Result<Self, String> {
        let current = std::process::id();
        if processes()?.iter().find(|r| r.0 == current).map(|r| r.1) != Some(pid) {
            return Err("Transport owner is not this worker's actual parent".into());
        }
        let owner = Process::open(pid)?;
        let worker = Process::open(current)?;
        let created = owner.times()?.0;
        if owner.ended()
            || created >= worker.times()?.0
            || !same_path(
                &owner.path()?,
                &std::env::current_exe().map_err(|e| e.to_string())?,
            )
        {
            return Err("Transport owner identity is invalid".into());
        }
        let native = std::env::current_exe()
            .map_err(|e| e.to_string())?
            .parent()
            .ok_or("Worker directory missing")?
            .join("engine")
            .join("RHRemote.exe");
        Ok(Self {
            pid,
            process: owner,
            created,
            native,
        })
    }
    fn children(&self) -> Result<Vec<Process>, String> {
        let exited = self.process.times()?.1;
        let mut result = Vec::new();
        for (pid, parent) in processes()? {
            if parent != self.pid {
                continue;
            }
            let child = match unsafe {
                OpenProcess(
                    PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                    false,
                    pid,
                )
            } {
                Ok(handle) => Process(handle),
                // A process may have exited between the snapshot and OpenProcess.
                Err(error) if error.code() == windows::core::HRESULT::from_win32(87) => continue,
                Err(error) => {
                    return Err(format!(
                        "Cannot inspect a child of the original UI: {error}"
                    ));
                }
            };
            if child.ended() {
                continue;
            }
            let (created, _) = child.times()?;
            if created >= self.created
                && (exited == 0 || created <= exited)
                && same_path(&child.path()?, &self.native)
            {
                result.push(child);
            }
        }
        Ok(result)
    }
    /// On EOF the UI cannot create another child. Give in-flight creation a
    /// small settling window, then retain the exact handles through cleanup.
    pub fn drain(&self, limit: Duration) -> Result<usize, String> {
        let until = Instant::now() + limit;
        std::thread::sleep(Duration::from_millis(75));
        let children = match self.children() {
            Ok(v) => v,
            Err(error) => {
                while Instant::now() < until {
                    std::thread::sleep(Duration::from_millis(25));
                }
                return Err(error);
            }
        };
        while children.iter().any(|p| !p.ended()) {
            if Instant::now() >= until {
                return Err(
                    "Native transport cleanup lease expired; child was not terminated".into(),
                );
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        Ok(children.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        os::windows::process::CommandExt,
        process::{Child, Command, Stdio},
    };
    struct ChildGuard(Child);
    impl Drop for ChildGuard {
        fn drop(&mut self) {
            if self.0.try_wait().ok().flatten().is_none() {
                let _ = self.0.kill();
            }
            let _ = self.0.wait();
        }
    }
    fn child(seconds: u32) -> ChildGuard {
        ChildGuard(
            Command::new("cmd.exe")
                .args([
                    "/d",
                    "/c",
                    &format!("ping -n {} 127.0.0.1 >nul", seconds + 1),
                ])
                .creation_flags(0x08000000)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap(),
        )
    }
    fn owner(native: PathBuf) -> Owner {
        let process = Process::open(std::process::id()).unwrap();
        let created = process.times().unwrap().0;
        Owner {
            pid: std::process::id(),
            process,
            created,
            native,
        }
    }
    fn selected(owner: &Owner, pid: u32) -> bool {
        owner
            .children()
            .unwrap()
            .iter()
            .any(|p| unsafe { windows::Win32::System::Threading::GetProcessId(p.0) } == pid)
    }
    #[test]
    fn invalid_or_self_owner_cannot_claim_the_worker() {
        assert!(Owner::capture(0).is_err());
        assert!(Owner::capture(std::process::id()).is_err());
    }
    #[test]
    fn native_path_comparison_accepts_windows_separator_and_prefix_variants() {
        assert!(same_path(
            std::path::Path::new("D:/RHRemote/engine/RHRemote.exe"),
            std::path::Path::new(r"D:\RHRemote\engine\RHRemote.exe")
        ));
        assert!(same_path(
            std::path::Path::new(r"\\?\D:\RHRemote\engine\RHRemote.exe"),
            std::path::Path::new(r"d:\rhremote\engine\rhremote.exe")
        ));
        assert!(!same_path(
            std::path::Path::new(r"D:\other\engine\RHRemote.exe"),
            std::path::Path::new(r"D:\RHRemote\engine\RHRemote.exe")
        ));
    }
    #[test]
    fn native_lease_waits_for_actual_exit_without_terminating_the_child() {
        let mut child = child(1);
        let process = Process::open(child.0.id()).unwrap();
        let owner = owner(process.path().unwrap());
        assert!(selected(&owner, child.0.id()));
        let began = Instant::now();
        assert!(owner.drain(Duration::from_secs(5)).is_ok());
        assert!(began.elapsed() > Duration::from_millis(300));
        assert!(child.0.wait().unwrap().success());
    }
    #[test]
    fn expired_grace_does_not_kill_a_native_child() {
        let mut child = child(2);
        let process = Process::open(child.0.id()).unwrap();
        let owner = owner(process.path().unwrap());
        assert!(owner.drain(Duration::from_millis(50)).is_err());
        assert!(child.0.try_wait().unwrap().is_none());
        assert!(child.0.wait().unwrap().success());
    }
    #[test]
    fn path_parent_and_creation_boundaries_exclude_other_processes() {
        let mut child = child(1);
        let process = Process::open(child.0.id()).unwrap();
        let native = process.path().unwrap();
        let mut owner = owner(native.clone());
        owner.native = native.with_file_name("not-the-native-engine.exe");
        assert!(!selected(&owner, child.0.id()));
        owner.native = native;
        owner.pid = u32::MAX;
        assert!(!selected(&owner, child.0.id()));
        owner.pid = std::process::id();
        owner.created = process.times().unwrap().0 + 1;
        assert!(!selected(&owner, child.0.id()));
        assert!(child.0.wait().unwrap().success());
    }
}
