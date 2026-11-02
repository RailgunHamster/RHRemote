//! A small, independently running transition window during Velopack replacement.
//! Built from the reviewed C# source, embedded in the Rust executable, and
//! extracted into a new directory outside the installation before every handoff.
use std::os::windows::process::CommandExt;
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::OnceLock,
    time::{Duration, Instant},
};

const HELPER: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/RHRemote.UpdateSplash.exe"));
const LOGO: &[u8] = include_bytes!("../../../shared/icons/rhremote.png");
struct Bundle {
    directory: PathBuf,
    executable: PathBuf,
    logo: PathBuf,
}
static BUNDLE: OnceLock<Result<Bundle, String>> = OnceLock::new();
fn create_file(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|e| e.to_string())?;
    file.write_all(bytes)
        .and_then(|_| file.sync_all())
        .map_err(|e| e.to_string())
}
fn extract(base: &Path) -> Result<Bundle, String> {
    fs::create_dir_all(base).map_err(|e| e.to_string())?;
    let directory = base.join(rhremote_core::random_token()?);
    fs::create_dir(&directory).map_err(|e| e.to_string())?;
    let executable = directory.join("RHRemote.UpdateSplash.exe");
    let logo = directory.join("rhremote.png");
    create_file(&executable, HELPER)?;
    create_file(&logo, LOGO)?;
    Ok(Bundle {
        directory,
        executable,
        logo,
    })
}
fn bundle() -> Result<&'static Bundle, String> {
    BUNDLE
        .get_or_init(|| extract(&std::env::temp_dir().join("RHRemote-UpdateTransition")))
        .as_ref()
        .map_err(Clone::clone)
}
pub fn warm_up() {
    std::thread::spawn(|| {
        let _ = bundle();
    });
}

/// On an immediate updater-launch failure the old application remains usable;
/// cancel the transient window through its own file protocol rather than kill it.
pub struct Transition {
    cancel: PathBuf,
    committed: bool,
}
impl Transition {
    pub fn commit(mut self) {
        self.committed = true;
    }
}
impl Drop for Transition {
    fn drop(&mut self) {
        if !self.committed {
            let _ = fs::write(&self.cancel, b"");
        }
    }
}

fn foreground_monitor() -> Option<String> {
    use windows::Win32::{Graphics::Gdi::*, UI::WindowsAndMessaging::*};
    unsafe {
        let window = GetForegroundWindow();
        let mut pid = 0;
        GetWindowThreadProcessId(window, Some(&mut pid));
        if pid != std::process::id() {
            return None;
        }
        let mut info = MONITORINFOEXW::default();
        info.monitorInfo.cbSize = std::mem::size_of::<MONITORINFOEXW>() as u32;
        if !GetMonitorInfoW(
            MonitorFromWindow(window, MONITOR_DEFAULTTONULL),
            &mut info.monitorInfo,
        )
        .as_bool()
        {
            return None;
        }
        let length = info
            .szDevice
            .iter()
            .position(|c| *c == 0)
            .unwrap_or(info.szDevice.len());
        Some(String::from_utf16_lossy(&info.szDevice[..length]))
    }
}
pub fn launch(from: &str, to: &str, root: &Path) -> Option<Transition> {
    if cfg!(test) {
        return None;
    }
    let bundle = bundle().ok()?;
    let installation=fs::canonicalize(root).ok()?.to_string_lossy().to_lowercase();
    let outside=fs::canonicalize(&bundle.directory).ok()?.to_string_lossy().to_lowercase();
    if outside==installation || outside.starts_with(&(installation+"\\")){return None;}
    let cancel = bundle
        .directory
        .join(format!("cancel-{}", rhremote_core::random_token().ok()?));
    let ready = cancel.with_extension("ready");
    let dark = crate::proxy::data_directory()
        .ok()
        .and_then(|data| fs::read(data.join("preferences.json")).ok())
        .and_then(|bytes| serde_json::from_slice::<rhremote_core::Saved>(&bytes).ok())
        .is_some_and(|saved| saved.dark);
    let monitor = std::env::var("RHREMOTE_TEST_MONITOR")
        .ok()
        .filter(|s| !s.is_empty())
        .or_else(foreground_monitor);
    let mut command = Command::new(&bundle.executable);
    command
        .current_dir(&bundle.directory)
        .args([
            "--title",
            "RHRemote",
            "--from-version",
            from,
            "--to-version",
            to,
            "--theme",
            if dark { "Dark" } else { "Light" },
            "--wait-pid",
            &std::process::id().to_string(),
            "--main-exe",
            "RHRemote.exe",
        ])
        .arg("--root-dir")
        .arg(root)
        .arg("--logo")
        .arg(&bundle.logo)
        .arg("--cancel-file")
        .arg(&cancel)
        .arg("--ready-file")
        .arg(&ready)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(0x08000000);
    if let Some(monitor) = monitor {
        command.arg("--monitor").arg(monitor);
    }
    if std::env::var("RHREMOTE_TEST_MODE").as_deref()==Ok("1"){command.arg("--passive");}
    let mut child = command.spawn().ok()?;
    let transition = Transition {
        cancel,
        committed: false,
    };
    let until = Instant::now() + Duration::from_secs(2);
    while Instant::now() < until {
        if ready.is_file() {
            return Some(transition);
        }
        if child.try_wait().ok().flatten().is_some() {
            return None;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bundled_helper_is_outside_the_installation_and_rejects_wrong_main_executables() {
        let base = std::env::temp_dir().join(format!(
            "rhremote-splash-check-{}",
            rhremote_core::random_token().unwrap()
        ));
        let one = extract(&base).unwrap();
        let two = extract(&base).unwrap();
        assert_ne!(one.directory, two.directory);
        assert_eq!(fs::read(&one.executable).unwrap(), HELPER);
        assert_eq!(fs::read(&one.logo).unwrap(), LOGO);
        let status = Command::new(&one.executable)
            .arg("--self-test")
            .creation_flags(0x08000000)
            .status()
            .unwrap();
        assert!(status.success());
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn failed_handoff_cancels_its_own_transition_and_success_leaves_it_for_the_new_window() {
        let base = std::env::temp_dir().join(format!(
            "rhremote-splash-cancel-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir(&base).unwrap();
        let failure = base.join("failed");
        drop(Transition {
            cancel: failure.clone(),
            committed: false,
        });
        assert!(failure.is_file());
        let success = base.join("success");
        Transition {
            cancel: success.clone(),
            committed: false,
        }
        .commit();
        assert!(!success.exists());
        fs::remove_dir_all(base).unwrap();
    }
}
