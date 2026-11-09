//! Durable file replacement journal; private configuration never enters the replacement list.
use super::protocol::{Package, validate_path};
use serde::{Deserialize, Serialize};
use std::{fs, path::Path};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Entry {
    pub path: String,
    pub existed: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Journal {
    pub phase: String,
    pub entries: Vec<Entry>,
    pub error: Option<String>,
}
impl Default for Journal {
    fn default() -> Self {
        Self {
            phase: "prepared".into(),
            entries: Vec::new(),
            error: None,
        }
    }
}

pub fn no_links(path: &Path) -> Result<(), String> {
    let mut current = Some(path);
    while let Some(item) = current {
        match fs::symlink_metadata(item) {
            Ok(metadata) => {
                #[cfg(windows)]
                {
                    use std::os::windows::fs::MetadataExt;
                    if metadata.file_attributes() & 0x400 != 0 {
                        return Err("Reparse point in update path".into());
                    }
                }
                if metadata.is_symlink() {
                    return Err("Link in update path".into());
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.to_string()),
        }
        current = item.parent();
    }
    Ok(())
}
pub fn atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    no_links(path)?;
    let temporary = path.with_extension(format!("{}.tmp", nonce()?));
    let mut file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temporary)
        .map_err(|e| e.to_string())?;
    file.write_all(bytes)
        .and_then(|_| file.sync_all())
        .map_err(|e| e.to_string())?;
    drop(file);
    replace(&temporary, path)
}
pub fn nonce() -> Result<String, String> {
    use ring::rand::SecureRandom;
    let mut bytes = [0u8; 16];
    ring::rand::SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| "Random source unavailable")?;
    Ok(bytes.iter().map(|v| format!("{v:02x}")).collect())
}
pub fn replace(from: &Path, to: &Path) -> Result<(), String> {
    no_links(from)?;
    no_links(to)?;
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows::{
            Win32::Storage::FileSystem::{
                MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH, MoveFileExW,
            },
            core::PCWSTR,
        };
        let a: Vec<_> = from.as_os_str().encode_wide().chain(Some(0)).collect();
        let b: Vec<_> = to.as_os_str().encode_wide().chain(Some(0)).collect();
        unsafe {
            MoveFileExW(
                PCWSTR(a.as_ptr()),
                PCWSTR(b.as_ptr()),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        }
        .map_err(|e| e.to_string())
    }
    #[cfg(not(windows))]
    {
        fs::rename(from, to).map_err(|e| e.to_string())
    }
}
fn copy_atomic(from: &Path, to: &Path) -> Result<(), String> {
    no_links(from)?;
    no_links(to)?;
    fs::create_dir_all(to.parent().ok_or("Update destination lacks parent")?)
        .map_err(|e| e.to_string())?;
    let temporary = to.with_extension(format!("{}.tmp", nonce()?));
    fs::copy(from, &temporary).map_err(|e| e.to_string())?;
    fs::OpenOptions::new()
        .write(true)
        .open(&temporary)
        .and_then(|v| v.sync_all())
        .map_err(|e| e.to_string())?;
    replace(&temporary, to)
}
pub fn save(directory: &Path, journal: &Journal) -> Result<(), String> {
    atomic(
        &directory.join("journal.json"),
        &serde_json::to_vec_pretty(journal).map_err(|e| e.to_string())?,
    )
}
pub fn load(directory: &Path) -> Result<Journal, String> {
    serde_json::from_slice(&fs::read(directory.join("journal.json")).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())
}
pub fn apply(
    install: &Path,
    directory: &Path,
    package: &Package,
    journal: &mut Journal,
) -> Result<(), String> {
    package.validate()?;
    journal.phase = "installing".into();
    save(directory, journal)?;
    for path in package
        .files
        .iter()
        .map(|f| f.path.as_str())
        .chain(Some("server-package.json"))
    {
        validate_path(path)?;
        let target = install.join(path);
        no_links(&target)?;
        let old = directory.join("backup").join(path);
        let existed = target.try_exists().map_err(|e| e.to_string())?;
        if existed {
            copy_atomic(&target, &old)?;
        } // Opaque backup, never read/hash the installed helper.
        journal.entries.push(Entry {
            path: path.into(),
            existed,
        });
        save(directory, journal)?;
        copy_atomic(&directory.join("package").join(path), &target)?;
    }
    journal.phase = "verifying".into();
    save(directory, journal)
}
pub fn rollback(install: &Path, directory: &Path, journal: &mut Journal) -> Result<(), String> {
    journal.phase = "rollingBack".into();
    save(directory, journal)?;
    for entry in journal.entries.iter().rev() {
        validate_path(&entry.path)?;
        let target = install.join(&entry.path);
        no_links(&target)?;
        if entry.existed {
            copy_atomic(&directory.join("backup").join(&entry.path), &target)?;
        } else if target.try_exists().map_err(|e| e.to_string())? {
            fs::remove_file(target).map_err(|e| e.to_string())?;
        }
    }
    journal.phase = "rolledBack".into();
    save(directory, journal)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn partial_file_replacement_rolls_back_and_leaves_pairings_untouched() {
        let root = std::env::temp_dir().join(format!("rhr-update-{}", nonce().unwrap()));
        let install = root.join("install");
        let tx = root.join("transaction");
        fs::create_dir_all(install.join("config")).unwrap();
        fs::create_dir_all(tx.join("package")).unwrap();
        fs::write(install.join("sunshine.exe"), b"old").unwrap();
        fs::write(
            install.join("config/sunshine_state.json"),
            b"private pairing bytes",
        )
        .unwrap();
        fs::write(tx.join("package/sunshine.exe"), b"new").unwrap();
        let paths = [
            "sunshine.exe",
            "host/RHRemoteHost.exe",
            "host/scripts/display.ps1",
            "host/scripts/RhDisplayConfig.dll",
            "assets/web/index.html",
        ];
        let package = Package {
            schema: "rhremote.server-package/v1".into(),
            product: "RHRemote-Sunshine".into(),
            version: "0.2.23".into(),
            commit: "a".repeat(40),
            engine_commit: "a".repeat(40),
            service: "SunshineService".into(),
            service_mode: "unified".into(),
            files: paths
                .iter()
                .map(|p| super::super::protocol::PackageFile {
                    path: (*p).into(),
                    size: 3,
                    sha256: "a".repeat(64),
                })
                .collect(),
        };
        let mut journal = Journal::default();
        assert!(apply(&install, &tx, &package, &mut journal).is_err());
        assert_eq!(fs::read(install.join("sunshine.exe")).unwrap(), b"new");
        let mut recovered = load(&tx).unwrap();
        rollback(&install, &tx, &mut recovered).unwrap();
        assert_eq!(fs::read(install.join("sunshine.exe")).unwrap(), b"old");
        assert_eq!(
            fs::read(install.join("config/sunshine_state.json")).unwrap(),
            b"private pairing bytes"
        );
        assert_eq!(load(&tx).unwrap().phase, "rolledBack");
        // Only this uniquely-created temporary test tree, never a repository artifact.
        fs::remove_dir_all(root).unwrap();
    }
}
