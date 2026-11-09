//! Signed release metadata and bounded, private-state-free server packages.
use ring::{digest, signature};
use semver::Version;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    fs,
    io::{Read, Write},
    path::Path,
};

pub const PRODUCT: &str = "RHRemote-Sunshine";
pub const FEED: &str = "releases.server.win.json";
pub const KEY_ID: &str = "release-rsa3072-20261109";
pub const PUBLIC_KEY: &[u8] =
    include_bytes!("../../../../update-keys/release-rsa3072-20261109.der");
pub const MAX_MANIFEST: usize = 16_384;
pub const MAX_ARCHIVE: u64 = 512 * 1024 * 1024;
pub const MAX_UNPACKED: u64 = 2 * 1024 * 1024 * 1024;

#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    #[default]
    Cnb,
    Github,
}
impl Source {
    pub fn fallback(self) -> Self {
        match self {
            Self::Cnb => Self::Github,
            Self::Github => Self::Cnb,
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            Self::Cnb => "CNB",
            Self::Github => "GitHub",
        }
    }
    pub fn url(self, tag: &str, name: &str) -> Result<String, String> {
        if tag != "latest" {
            stable_tag(tag)?;
        }
        if name.is_empty()
            || !name
                .bytes()
                .all(|v| v.is_ascii_alphanumeric() || b"._-".contains(&v))
            || name.contains("..")
        {
            return Err("Invalid release asset name".into());
        }
        Ok(match (self, tag) {
            (Self::Cnb, "latest") => format!(
                "https://cnb.cool/railgunhamster/RHRemote/-/releases/latest/download/{name}"
            ),
            (Self::Cnb, _) => {
                format!("https://cnb.cool/railgunhamster/RHRemote/-/releases/download/{tag}/{name}")
            }
            (Self::Github, "latest") => format!(
                "https://github.com/RailgunHamster/RHRemote/releases/latest/download/{name}"
            ),
            (Self::Github, _) => {
                format!("https://github.com/RailgunHamster/RHRemote/releases/download/{tag}/{name}")
            }
        })
    }
}

fn schema_version() -> u32 {
    1
}
fn interval() -> u32 {
    15
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    #[serde(default = "schema_version")]
    pub schema_version: u32,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub preferred_source: Source,
    #[serde(default = "interval")]
    pub check_interval_minutes: u32,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            schema_version: 1,
            enabled: false,
            preferred_source: Source::Cnb,
            check_interval_minutes: 15,
        }
    }
}
impl Settings {
    pub fn validate(&self) -> Result<(), String> {
        if self.schema_version != 1 || ![5, 15, 30, 60].contains(&self.check_interval_minutes) {
            return Err("Unsupported server update settings".into());
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Manifest {
    pub schema: String,
    pub product: String,
    pub platform: String,
    pub key_id: String,
    pub version: String,
    pub release_tag: String,
    pub file_name: String,
    pub size: u64,
    pub sha256: String,
    pub source_commit: String,
    pub updater_protocol: u32,
    pub published_at: String,
}
#[derive(Clone, Debug)]
pub struct VerifiedManifest(Manifest);
impl VerifiedManifest {
    pub fn get(&self) -> &Manifest {
        &self.0
    }
    pub fn newer_than(&self, current: &str) -> Result<bool, String> {
        let current = Version::parse(current).map_err(|_| "Installed server version is invalid")?;
        Ok(stable_version(&self.0.version)? > current)
    }
}
fn hex(value: &str, length: usize) -> bool {
    value.len() == length && value.bytes().all(|v| v.is_ascii_hexdigit())
}
pub fn stable_version(value: &str) -> Result<Version, String> {
    let version = Version::parse(value).map_err(|_| "Invalid stable server version")?;
    if !version.pre.is_empty() || !version.build.is_empty() {
        return Err("Server update target must be stable".into());
    }
    Ok(version)
}
fn stable_tag(value: &str) -> Result<(), String> {
    stable_version(value.strip_prefix('v').ok_or("Invalid release tag")?)?;
    Ok(())
}
impl Manifest {
    pub fn validate(&self) -> Result<(), String> {
        stable_version(&self.version)?;
        stable_tag(&self.release_tag)?;
        if self.schema != "rhremote.server-update/v1"
            || self.product != PRODUCT
            || self.platform != "windows-x64"
            || self.key_id != KEY_ID
            || self.updater_protocol != 1
        {
            return Err("Unsupported signed server release".into());
        }
        if self.file_name != format!("RHRemote-Sunshine-{}-win-x64.zip", self.version)
            || self.size == 0
            || self.size > MAX_ARCHIVE
            || !hex(&self.sha256, 64)
            || !hex(&self.source_commit, 40)
        {
            return Err("Invalid signed server package identity".into());
        }
        if self.published_at.is_empty() || self.published_at.len() > 64 {
            return Err("Invalid server publication time".into());
        }
        Ok(())
    }
}
fn verify_with_key(bytes: &[u8], signed: &[u8], key: &[u8]) -> Result<VerifiedManifest, String> {
    if bytes.is_empty() || bytes.len() > MAX_MANIFEST || signed.len() != 384 {
        return Err("Invalid server manifest envelope".into());
    }
    signature::UnparsedPublicKey::new(&signature::RSA_PKCS1_2048_8192_SHA256, key)
        .verify(bytes, signed)
        .map_err(|_| "Server release signature rejected")?;
    let manifest: Manifest =
        serde_json::from_slice(bytes).map_err(|_| "Invalid signed server manifest")?;
    manifest.validate()?;
    Ok(VerifiedManifest(manifest))
}
pub fn verify(bytes: &[u8], signed: &[u8]) -> Result<VerifiedManifest, String> {
    verify_with_key(bytes, signed, PUBLIC_KEY)
}
#[cfg(test)]
pub fn sha256(bytes: &[u8]) -> String {
    digest::digest(&digest::SHA256, bytes)
        .as_ref()
        .iter()
        .map(|v| format!("{v:02x}"))
        .collect()
}
pub fn file_sha256(path: &Path) -> Result<String, String> {
    let mut file = fs::File::open(path).map_err(|e| e.to_string())?;
    let mut context = digest::Context::new(&digest::SHA256);
    let mut buffer = [0u8; 131_072];
    loop {
        let count = file.read(&mut buffer).map_err(|e| e.to_string())?;
        if count == 0 {
            break;
        }
        context.update(&buffer[..count]);
    }
    Ok(context
        .finish()
        .as_ref()
        .iter()
        .map(|v| format!("{v:02x}"))
        .collect())
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PackageFile {
    pub path: String,
    pub size: u64,
    pub sha256: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Package {
    pub schema: String,
    pub product: String,
    pub version: String,
    pub commit: String,
    pub engine_commit: String,
    pub service: String,
    pub service_mode: String,
    pub files: Vec<PackageFile>,
}
pub fn validate_path(value: &str) -> Result<(), String> {
    if value.is_empty()
        || value.len() > 240
        || !value.is_ascii()
        || value.contains('\\')
        || value.contains(':')
        || value.starts_with('/')
    {
        return Err("Unsafe server package path".into());
    }
    for part in value.split('/') {
        let lower = part.to_ascii_lowercase();
        let stem = lower.split('.').next().unwrap_or("");
        if part.is_empty()
            || part == "."
            || part == ".."
            || part.ends_with(['.', ' '])
            || part.bytes().any(|v| v < 32 || b"<>\"|?*".contains(&v))
            || ["config", "credentials", ".git"].contains(&lower.as_str())
            || ["con", "prn", "aux", "nul"].contains(&stem)
            || (stem.len() == 4
                && (stem.starts_with("com") || stem.starts_with("lpt"))
                && (b'1'..=b'9').contains(&stem.as_bytes()[3]))
        {
            return Err("Unsafe or private server package path".into());
        }
    }
    Ok(())
}
impl Package {
    pub fn validate(&self) -> Result<(), String> {
        Version::parse(&self.version).map_err(|_| "Invalid installed server version")?;
        if self.schema != "rhremote.server-package/v1"
            || self.product != PRODUCT
            || self.service != "SunshineService"
            || self.service_mode != "unified"
            || !hex(&self.commit, 40)
            || !hex(&self.engine_commit, 40)
            || self.files.is_empty()
            || self.files.len() > 4096
        {
            return Err("Unexpected server package metadata".into());
        }
        let mut seen = HashSet::new();
        let mut size = 0u64;
        for file in &self.files {
            validate_path(&file.path)?;
            if file.path.eq_ignore_ascii_case("server-package.json")
                || !seen.insert(file.path.to_ascii_lowercase())
                || !hex(&file.sha256, 64)
            {
                return Err("Duplicate or invalid server package entry".into());
            }
            size = size
                .checked_add(file.size)
                .ok_or("Server package size overflow")?;
        }
        if size > MAX_UNPACKED
            || ![
                "sunshine.exe",
                "host/rhremotehost.exe",
                "host/scripts/display.ps1",
                "host/scripts/rhdisplayconfig.dll",
                "assets/web/index.html",
            ]
            .iter()
            .all(|v| seen.contains(*v))
        {
            return Err("Incomplete or oversized server package".into());
        }
        Ok(())
    }
}

/// Validate everything before creating an extraction directory. Never touch installed private state.
pub fn extract(path: &Path, target: &Path, release: &VerifiedManifest) -> Result<Package, String> {
    let expected = release.get();
    if fs::metadata(path).map_err(|e| e.to_string())?.len() != expected.size
        || !file_sha256(path)?.eq_ignore_ascii_case(&expected.sha256)
    {
        return Err("Server archive checksum differs from signed release".into());
    }
    let mut archive = zip::ZipArchive::new(fs::File::open(path).map_err(|e| e.to_string())?)
        .map_err(|_| "Invalid server ZIP")?;
    if archive.len() > 8192 {
        return Err("Too many server archive entries".into());
    }
    let mut bytes = Vec::new();
    archive
        .by_name("server-package.json")
        .map_err(|_| "Server package manifest missing")?
        .take(1_048_577)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() > 1_048_576 {
        return Err("Server package metadata too large".into());
    }
    let package: Package =
        serde_json::from_slice(&bytes).map_err(|_| "Invalid server package metadata")?;
    package.validate()?;
    if package.version != expected.version
        || !package.commit.eq_ignore_ascii_case(&expected.source_commit)
    {
        return Err("Signed archive metadata identity differs".into());
    }
    let listed: std::collections::HashMap<_, _> = package
        .files
        .iter()
        .map(|v| (v.path.to_ascii_lowercase(), v))
        .collect();
    let mut seen = HashSet::new();
    let mut total = 0u64;
    for i in 0..archive.len() {
        let entry = archive
            .by_index(i)
            .map_err(|_| "Invalid server ZIP entry")?;
        let name = entry.name().trim_end_matches('/');
        validate_path(name)?;
        if entry.unix_mode().is_some_and(|v| v & 0o170000 == 0o120000) {
            return Err("Server ZIP links are forbidden".into());
        }
        if entry.is_dir() {
            continue;
        }
        if !seen.insert(name.to_ascii_lowercase()) {
            return Err("Duplicate server ZIP entry".into());
        }
        total = total
            .checked_add(entry.size())
            .ok_or("Server ZIP size overflow")?;
        if total > MAX_UNPACKED + 1_048_576 {
            return Err("Server ZIP exceeds extraction budget".into());
        }
        if name != "server-package.json"
            && listed
                .get(&name.to_ascii_lowercase())
                .is_none_or(|v| v.path != name || v.size != entry.size())
        {
            return Err("Unlisted or changed server ZIP entry".into());
        }
    }
    if seen.len() != listed.len() + 1 || !listed.keys().all(|v| seen.contains(v)) {
        return Err("Server ZIP lacks required files".into());
    }
    fs::create_dir(target).map_err(|_| "Extraction directory must be new")?;
    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|_| "Invalid server ZIP entry")?;
        if entry.is_dir() {
            continue;
        }
        let name = entry.name().to_owned();
        let destination = target.join(&name);
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let mut output = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&destination)
            .map_err(|e| e.to_string())?;
        let mut context = digest::Context::new(&digest::SHA256);
        let mut buffer = [0u8; 131_072];
        let mut count = 0u64;
        loop {
            let n = entry
                .read(&mut buffer)
                .map_err(|_| "Server ZIP entry read failed")?;
            if n == 0 {
                break;
            }
            count += n as u64;
            if count > entry.size() {
                return Err("ZIP entry exceeds its declared size".into());
            }
            context.update(&buffer[..n]);
            output.write_all(&buffer[..n]).map_err(|e| e.to_string())?;
        }
        output.sync_all().map_err(|e| e.to_string())?;
        if name != "server-package.json" {
            let expected = listed
                .get(&name.to_ascii_lowercase())
                .ok_or("Unlisted package file")?;
            let hash: String = context
                .finish()
                .as_ref()
                .iter()
                .map(|v| format!("{v:02x}"))
                .collect();
            if count != expected.size || !hash.eq_ignore_ascii_case(&expected.sha256) {
                return Err("Extracted server file checksum mismatch".into());
            }
        }
    }
    Ok(package)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn authentic_metadata_is_required_before_semver_selection() {
        let bytes = include_bytes!("testdata/release.json");
        let sig = include_bytes!("testdata/release.sig");
        let key = include_bytes!("testdata/test-only-public.der");
        let verified = verify_with_key(bytes, sig, key).unwrap();
        assert!(verified.newer_than("0.2.23-preview.1").unwrap());
        assert!(!verified.newer_than("0.2.23").unwrap());
        assert!(!verified.newer_than("0.2.24").unwrap());
        let mut altered = bytes.to_vec();
        let position = altered.windows(6).position(|v| v == b"0.2.23").unwrap();
        altered[position + 5] = b'4';
        assert!(verify_with_key(&altered, sig, key).is_err());
        let mut changed = sig.to_vec();
        changed[0] ^= 1;
        assert!(verify_with_key(bytes, &changed, key).is_err());
        assert!(
            verify(bytes, sig).is_err(),
            "A test-only signing key can never authorize production updates"
        );
    }
    struct Tree(std::path::PathBuf);
    impl Tree {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "rhr-package-{}",
                super::super::transaction::nonce().unwrap()
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
    }
    impl Drop for Tree {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn archive(root: &Path, extra: Option<&str>, bad_file: bool) -> VerifiedManifest {
        use zip::write::SimpleFileOptions;
        let names = [
            "sunshine.exe",
            "host/RHRemoteHost.exe",
            "host/scripts/display.ps1",
            "host/scripts/RhDisplayConfig.dll",
            "assets/web/index.html",
        ];
        let files = names
            .iter()
            .map(|name| PackageFile {
                path: (*name).into(),
                size: 3,
                sha256: sha256(b"new"),
            })
            .collect();
        let package = Package {
            schema: "rhremote.server-package/v1".into(),
            product: PRODUCT.into(),
            version: "0.2.23".into(),
            commit: "b".repeat(40),
            engine_commit: "b".repeat(40),
            service: "SunshineService".into(),
            service_mode: "unified".into(),
            files,
        };
        let path = root.join("server.zip");
        let mut output = zip::ZipWriter::new(fs::File::create(&path).unwrap());
        output
            .start_file("server-package.json", SimpleFileOptions::default())
            .unwrap();
        output
            .write_all(&serde_json::to_vec(&package).unwrap())
            .unwrap();
        for name in names {
            output
                .start_file(name, SimpleFileOptions::default())
                .unwrap();
            output
                .write_all(if bad_file && name == "sunshine.exe" {
                    b"bad"
                } else {
                    b"new"
                })
                .unwrap();
        }
        if let Some(extra) = extra {
            output
                .start_file(extra, SimpleFileOptions::default())
                .unwrap();
            output.write_all(b"new").unwrap();
        }
        output.finish().unwrap();
        let mut manifest: Manifest =
            serde_json::from_slice(include_bytes!("testdata/release.json")).unwrap();
        manifest.size = fs::metadata(&path).unwrap().len();
        manifest.sha256 = file_sha256(&path).unwrap();
        VerifiedManifest(manifest)
    }
    #[test]
    fn signed_archive_identity_and_each_extracted_file_are_checked() {
        let root = Tree::new();
        let release = archive(&root.0, None, false);
        let package = extract(
            &root.0.join("server.zip"),
            &root.0.join("extracted"),
            &release,
        )
        .unwrap();
        assert_eq!(package.files.len(), 5);
        assert_eq!(
            fs::read(root.0.join("extracted/host/RHRemoteHost.exe")).unwrap(),
            b"new"
        );
        assert!(
            extract(
                &root.0.join("server.zip"),
                &root.0.join("extracted"),
                &release
            )
            .is_err()
        );
        let mut changed = fs::OpenOptions::new()
            .append(true)
            .open(root.0.join("server.zip"))
            .unwrap();
        changed.write_all(b"tampered").unwrap();
        drop(changed);
        assert!(
            extract(
                &root.0.join("server.zip"),
                &root.0.join("altered"),
                &release
            )
            .is_err()
        );
        assert!(!root.0.join("altered").exists());
    }
    #[test]
    fn unsafe_or_unlisted_zip_entries_are_rejected_before_extraction() {
        for extra in [
            "../outside.exe",
            "config/sunshine_state.json",
            "host/other.exe",
            "HOST/RHREMOTEHOST.EXE",
        ] {
            let root = Tree::new();
            let release = archive(&root.0, Some(extra), false);
            assert!(
                extract(
                    &root.0.join("server.zip"),
                    &root.0.join("extracted"),
                    &release
                )
                .is_err(),
                "{extra}"
            );
            assert!(!root.0.join("extracted").exists());
        }
        let root = Tree::new();
        let release = archive(&root.0, None, true);
        assert!(
            extract(
                &root.0.join("server.zip"),
                &root.0.join("extracted"),
                &release
            )
            .unwrap_err()
            .contains("checksum")
        );
    }
    #[test]
    fn release_urls_are_fixed_to_the_owned_repositories() {
        assert_eq!(
            Source::Cnb.url("latest", FEED).unwrap(),
            "https://cnb.cool/railgunhamster/RHRemote/-/releases/latest/download/releases.server.win.json"
        );
        assert_eq!(
            Source::Github.url("v0.2.23", "server.zip").unwrap(),
            "https://github.com/RailgunHamster/RHRemote/releases/download/v0.2.23/server.zip"
        );
        assert_eq!(
            Source::Cnb.url("v0.2.23", "server.zip").unwrap(),
            "https://cnb.cool/railgunhamster/RHRemote/-/releases/download/v0.2.23/server.zip"
        );
        for tag in ["v0.2.23-preview.1", "https://example.org", "../latest"] {
            assert!(Source::Cnb.url(tag, FEED).is_err());
        }
        for name in ["../a", "https://evil.org/a", "a?token=b", "a/b"] {
            assert!(Source::Github.url("latest", name).is_err());
        }
    }
    #[test]
    fn package_paths_cannot_write_private_state_or_windows_aliases() {
        for path in [
            "../sunshine.exe",
            "/absolute",
            "C:/Windows/a",
            "host\\a.exe",
            "config/a",
            "CREDENTIALS/a",
            "a/../b",
            "host/CON.exe",
            "a/nul",
            "a/com1.txt",
            "a/file. ",
            "a//b",
            "a/.git/config",
            "a/b:",
            "host/é.exe",
        ] {
            assert!(validate_path(path).is_err(), "{path}");
        }
        for path in [
            "sunshine.exe",
            "host/RHRemoteHost.exe",
            "host/scripts/display.ps1",
            "assets/web/index.html",
        ] {
            assert!(validate_path(path).is_ok(), "{path}");
        }
    }
    #[test]
    fn invalid_or_unsigned_metadata_never_reaches_version_selection() {
        assert!(verify(b"{}", &[0; 384]).is_err());
        assert!(verify(&vec![0; MAX_MANIFEST + 1], &[0; 384]).is_err());
        assert!(verify(b"{}", &[0; 383]).is_err());
    }
    #[test]
    fn settings_cannot_supply_execution_or_download_paths() {
        assert!(
            serde_json::from_str::<Settings>(
                r#"{"enabled":true,"downloadUrl":"https://evil.org"}"#
            )
            .is_err()
        );
        let mut settings = Settings::default();
        for n in [0, 1, 120] {
            settings.check_interval_minutes = n;
            assert!(settings.validate().is_err());
        }
        for n in [5, 15, 30, 60] {
            settings.check_interval_minutes = n;
            assert!(settings.validate().is_ok());
        }
    }
}
