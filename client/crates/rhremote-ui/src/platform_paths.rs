//! Desktop identity and configuration locations; never fall back to the launch directory on Linux.
use std::{ffi::OsString, path::{Path, PathBuf}};

pub(crate) const PLATFORM: &str = if cfg!(target_os = "linux") { "linux" } else { "windows" };
pub(crate) const ENGINE_NAME: &str = if cfg!(windows) { "RHRemote.exe" } else { "RHRemote" };

pub(crate) fn default_data() -> Result<PathBuf, String> {
    #[cfg(target_os = "linux")]
    { linux_data(|key| std::env::var_os(key)) }
    #[cfg(not(target_os = "linux"))]
    { Ok(PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap_or_default()).join("RHRemote")) }
}

#[cfg(target_os = "linux")]
fn linux_data(get: impl Fn(&str) -> Option<OsString>) -> Result<PathBuf, String> {
    let absolute = |key| get(key).filter(|value| !value.is_empty()).map(PathBuf::from).filter(|path| path.is_absolute());
    let base = absolute("XDG_CONFIG_HOME").or_else(|| absolute("HOME").map(|path| path.join(".config")))
        .ok_or("HOME or an absolute XDG_CONFIG_HOME is required")?;
    Ok(base.join("rhremote"))
}

pub(crate) fn packaged_engine(executable: &Path) -> PathBuf {
    executable.parent().unwrap_or(Path::new(".")).join("engine").join(ENGINE_NAME)
}

pub(crate) fn prepare_data(path: &Path) -> Result<(), std::io::Error> {
    std::fs::create_dir_all(path)?;
    #[cfg(unix)]
    { use std::os::unix::fs::PermissionsExt; std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))?; }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn packaged_engine_uses_the_native_executable_name() {
        let parent = std::env::temp_dir().join("rhremote-path-layout-test");
        assert_eq!(packaged_engine(&parent.join(ENGINE_NAME)), parent.join("engine").join(ENGINE_NAME));
    }
    #[cfg(target_os = "linux")]
    #[test]
    fn linux_configuration_follows_xdg_and_ignores_relative_overrides() {
        assert_eq!(linux_data(|key| match key {"XDG_CONFIG_HOME"=>Some("/tmp/xdg-config".into()),"HOME"=>Some("/home/fixture".into()),_=>None}).unwrap(), PathBuf::from("/tmp/xdg-config/rhremote"));
        for value in ["", "relative/config"] {
            assert_eq!(linux_data(|key| match key {"XDG_CONFIG_HOME"=>Some(value.into()),"HOME"=>Some("/home/fixture".into()),_=>None}).unwrap(), PathBuf::from("/home/fixture/.config/rhremote"));
        }
        assert!(linux_data(|_| None).is_err());
    }
}
