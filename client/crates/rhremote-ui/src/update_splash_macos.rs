use std::{
    fs::{self, DirBuilder, OpenOptions},
    io::Write,
    os::unix::fs::{DirBuilderExt, OpenOptionsExt},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
};

const HELPER: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/RHRemote.UpdateSplash"));
const LOGO: &[u8] = include_bytes!("../../../shared/icons/rhremote.png");

pub struct Transition {
    path: PathBuf,
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
            let _ = fs::write(self.path.join("cancel"), b"");
        }
    }
}

fn write_new(path: &Path, mode: u32, bytes: &[u8]) -> std::io::Result<()> {
    OpenOptions::new().create_new(true).write(true).mode(mode).open(path)?.write_all(bytes)
}

pub fn launch(from: &str, to: &str, root: &Path, packages: &Path) -> Option<Transition> {
    if cfg!(test) {
        return None;
    }
    let root = fs::canonicalize(root).ok()?;
    let temp = fs::canonicalize(std::env::temp_dir()).ok()?;
    if temp.starts_with(&root) {
        return None;
    }
    let path = temp.join(format!("rhremote-update-{}", rhremote_core::random_token().ok()?));
    DirBuilder::new().mode(0o700).create(&path).ok()?;
    let helper = path.join("RHRemote.UpdateSplash");
    let logo = path.join("logo.png");
    let dark = crate::proxy::data_directory()
        .ok()
        .and_then(|data| fs::read(data.join("preferences.json")).ok())
        .and_then(|bytes| serde_json::from_slice::<rhremote_core::Saved>(&bytes).ok())
        .is_some_and(|saved| saved.dark);
    let mut command = Command::new(&helper);
    command
        .arg(&root)
        .arg(packages.join("VelopackTemp"))
        .args([&std::process::id().to_string(), from, to, if dark { "dark" } else { "light" }])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    let child = write_new(&helper, 0o700, HELPER)
        .and_then(|_| write_new(&logo, 0o600, LOGO))
        .and_then(|_| command.spawn());
    let mut child = match child {
        Ok(child) => child,
        Err(_) => {
            let _ = fs::remove_dir_all(&path);
            return None;
        }
    };
    let transition = Transition { path, committed: false };
    let ready = transition.path.join("ready");
    let until = Instant::now() + Duration::from_secs(2);
    while Instant::now() < until {
        if ready.is_file() {
            std::thread::spawn(move || { let _ = child.wait(); });
            return Some(transition);
        }
        if child.try_wait().ok().flatten().is_some() {
            let _ = fs::remove_dir_all(&transition.path);
            return None;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    std::thread::spawn(move || { let _ = child.wait(); });
    None
}
