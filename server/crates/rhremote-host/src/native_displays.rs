//! Read-only display preparation in the existing interactive Rust worker.
//! Virtual monitor creation and mode changes retain the transactional helper.
//! No display eligibility or capture ordering is cached across requests.
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{fs, mem::size_of, path::Path, time::Instant};
use windows::{
    Win32::{Devices::Display::*, Foundation::*, System::Registry::*},
    core::{PCWSTR, w},
};

#[derive(Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Display {
    id: String,
    name: String,
    gdi: String,
    capture_index: usize,
    available: bool,
    #[serde(rename = "virtual")]
    is_virtual: bool,
    owned: bool,
    width: u32,
    height: u32,
    x: i32,
    y: i32,
    rotation: i32,
    refresh_numerator: u32,
    refresh_denominator: u32,
    primary: bool,
}
fn string(value: &[u16]) -> String {
    String::from_utf16_lossy(&value[..value.iter().position(|c| *c == 0).unwrap_or(value.len())])
}
fn header(
    kind: DISPLAYCONFIG_DEVICE_INFO_TYPE,
    size: usize,
    adapter: LUID,
    id: u32,
) -> DISPLAYCONFIG_DEVICE_INFO_HEADER {
    DISPLAYCONFIG_DEVICE_INFO_HEADER {
        r#type: kind,
        size: size as u32,
        adapterId: adapter,
        id,
    }
}
fn hardware_id(adapter: &str) -> String {
    let path = adapter.strip_prefix("\\\\?\\").unwrap_or(adapter);
    let path = path
        .rsplit_once("#{")
        .map(|(p, _)| p)
        .unwrap_or(path)
        .replace('#', "\\");
    let key: Vec<u16> = format!("SYSTEM\\CurrentControlSet\\Enum\\{path}")
        .encode_utf16()
        .chain(Some(0))
        .collect();
    let mut buffer = vec![0u16; 4096];
    let mut bytes = (buffer.len() * 2) as u32;
    let status = unsafe {
        RegGetValueW(
            HKEY_LOCAL_MACHINE,
            PCWSTR(key.as_ptr()),
            w!("HardwareID"),
            RRF_RT_REG_MULTI_SZ | RRF_RT_REG_SZ,
            None,
            Some(buffer.as_mut_ptr().cast()),
            Some(&mut bytes),
        )
    };
    if status != ERROR_SUCCESS {
        return String::new();
    }
    String::from_utf16_lossy(&buffer[..(bytes as usize / 2).min(buffer.len())])
        .replace('\0', " ")
        .to_ascii_lowercase()
}
fn query(owned: &str) -> Result<Vec<Display>, String> {
    unsafe {
        for _ in 0..3 {
            let (mut np, mut nm) = (0, 0);
            GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &mut np, &mut nm)
                .ok()
                .map_err(|e| e.to_string())?;
            if np > 256 || nm > 1024 {
                return Err("Display topology exceeds bounded inventory".into());
            }
            let mut paths = vec![DISPLAYCONFIG_PATH_INFO::default(); np as usize];
            let mut modes = vec![DISPLAYCONFIG_MODE_INFO::default(); nm as usize];
            let status = QueryDisplayConfig(
                QDC_ONLY_ACTIVE_PATHS,
                &mut np,
                paths.as_mut_ptr(),
                &mut nm,
                modes.as_mut_ptr(),
                None,
            );
            if status == ERROR_INSUFFICIENT_BUFFER {
                continue;
            }
            status.ok().map_err(|e| e.to_string())?;
            let mut result = Vec::new();
            for path in &paths[..np as usize] {
                let mut source = DISPLAYCONFIG_SOURCE_DEVICE_NAME {
                    header: header(
                        DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME,
                        size_of::<DISPLAYCONFIG_SOURCE_DEVICE_NAME>(),
                        path.sourceInfo.adapterId,
                        path.sourceInfo.id,
                    ),
                    ..Default::default()
                };
                let mut target = DISPLAYCONFIG_TARGET_DEVICE_NAME {
                    header: header(
                        DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME,
                        size_of::<DISPLAYCONFIG_TARGET_DEVICE_NAME>(),
                        path.targetInfo.adapterId,
                        path.targetInfo.id,
                    ),
                    ..Default::default()
                };
                let mut adapter = DISPLAYCONFIG_ADAPTER_NAME {
                    header: header(
                        DISPLAYCONFIG_DEVICE_INFO_GET_ADAPTER_NAME,
                        size_of::<DISPLAYCONFIG_ADAPTER_NAME>(),
                        path.targetInfo.adapterId,
                        0,
                    ),
                    ..Default::default()
                };
                for h in [&mut source.header, &mut target.header, &mut adapter.header] {
                    let rc = DisplayConfigGetDeviceInfo(h);
                    if rc != 0 {
                        return Err(format!("DisplayConfigGetDeviceInfo failed: {rc}"));
                    }
                }
                let index = path.sourceInfo.Anonymous.modeInfoIdx as usize;
                let Some(mode) = modes
                    .get(index)
                    .filter(|m| m.infoType == DISPLAYCONFIG_MODE_INFO_TYPE_SOURCE)
                else {
                    return Err("Active display source mode unavailable".into());
                };
                let mode = mode.Anonymous.sourceMode;
                let id = string(&target.monitorDevicePath);
                let is_owned =
                    !owned.is_empty() && id.replace('#', "\\").to_ascii_lowercase().contains(owned);
                let hardware = hardware_id(&string(&adapter.adapterDevicePath));
                let is_virtual = is_owned
                    || ["usbmmidd", "parsec", "iddsample", "virtualdisplay"]
                        .iter()
                        .any(|v| hardware.contains(v));
                result.push(Display {
                    id,
                    name: string(&target.monitorFriendlyDeviceName),
                    gdi: string(&source.viewGdiDeviceName),
                    available: path.targetInfo.targetAvailable.as_bool(),
                    is_virtual,
                    owned: is_owned,
                    width: mode.width,
                    height: mode.height,
                    x: mode.position.x,
                    y: mode.position.y,
                    rotation: path.targetInfo.rotation.0,
                    refresh_numerator: path.targetInfo.refreshRate.Numerator,
                    refresh_denominator: path.targetInfo.refreshRate.Denominator,
                    primary: mode.position.x == 0 && mode.position.y == 0,
                    ..Default::default()
                });
            }
            return Ok(result);
        }
    }
    Err("Display topology changed repeatedly".into())
}
fn select<'a>(
    displays: &'a [Display],
    mode: &str,
    id: &str,
    width: u32,
    height: u32,
) -> Option<&'a Display> {
    if mode == "physical" {
        return displays.iter().find(|d| d.available && d.id == id);
    }
    if mode == "auto" {
        if let Some(display) = displays
            .iter()
            .filter(|d| d.available && !d.is_virtual)
            .min_by_key(|d| (!d.primary, d.capture_index))
        {
            return Some(display);
        }
    }
    displays
        .iter()
        .find(|d| d.available && d.owned && d.width == width && d.height == height)
}
fn topology(displays: &[Display]) -> Vec<(&str, &str, u32, u32, i32, i32, i32, u32, u32)> {
    displays
        .iter()
        .map(|d| {
            (
                d.id.as_str(),
                d.gdi.as_str(),
                d.width,
                d.height,
                d.x,
                d.y,
                d.rotation,
                d.refresh_numerator,
                d.refresh_denominator,
            )
        })
        .collect()
}
pub fn prepare(request: &Value, root: &Path) -> Result<Option<Value>, String> {
    let mode = request["mode"].as_str().ok_or("Display mode missing")?;
    if !["list", "auto", "physical", "virtual"].contains(&mode) {
        return Err("Unknown display mode".into());
    }
    let started = Instant::now();
    let desktop = crate::console::sync_input_desktop()?;
    let owned = fs::read(root.join("owned-monitor.json"))
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|v| v["device_id"].as_str().map(str::to_ascii_lowercase))
        .unwrap_or_default();
    let before = query(&owned)?;
    let query_ms = started.elapsed().as_secs_f64() * 1000.0;
    let capture_started = Instant::now();
    let capture = crate::displays::capture_order()?;
    let capture_ms = capture_started.elapsed().as_secs_f64() * 1000.0;
    let mut displays = query(&owned)?;
    if topology(&before) != topology(&displays) {
        return Err("Display topology changed during capture enumeration".into());
    }
    let names = capture["names"]
        .as_array()
        .ok_or("Invalid capture inventory")?;
    for display in &mut displays {
        display.capture_index = names
            .iter()
            .position(|v| v.as_str() == Some(&display.gdi))
            .map(|n| n + 1)
            .unwrap_or(0);
        display.available &= display.capture_index > 0;
    }
    let selected = if mode == "list" {
        None
    } else {
        select(
            &displays,
            mode,
            request["displayId"].as_str().unwrap_or(""),
            request["width"].as_u64().unwrap_or(0) as u32,
            request["height"].as_u64().unwrap_or(0) as u32,
        )
    };
    if mode != "list" && selected.is_none() {
        if mode == "physical" {
            return Ok(Some(
                json!({"ok":false,"error":"The selected monitor is unavailable. Refresh the monitor list."}),
            ));
        }
        return Ok(None);
    }
    let settings: Value = fs::read(root.join("host-settings.json"))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or(Value::Null);
    let config = settings["sunshineConfig"]
        .as_str()
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| {
            std::path::PathBuf::from(std::env::var_os("ProgramFiles").unwrap_or_default())
                .join("Sunshine/config")
        });
    let text = fs::read_to_string(config.join("sunshine.conf")).map_err(|e| e.to_string())?;
    let output = text
        .lines()
        .filter_map(|s| s.trim().split_once('='))
        .filter(|(key, _)| key.trim() == "output_name")
        .map(|(_, v)| v.trim())
        .last()
        .unwrap_or("");
    Ok(Some(
        json!({"ok":true,"schema":"rhremote.displays/v1","displays":displays,"selected":selected,"created":false,"captureDiagnostics":capture["diagnostics"],"inputDesktop":desktop,"initialCaptureIndex":if output.is_empty(){1}else{0},"timings":{"path":"native","queryMs":query_ms,"captureMs":capture_ms,"nativeMs":started.elapsed().as_secs_f64()*1000.0}}),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn display(id: &str, index: usize, primary: bool, owned: bool, available: bool) -> Display {
        Display {
            id: id.into(),
            capture_index: index,
            primary,
            owned,
            available,
            is_virtual: owned,
            width: 2520,
            height: 1680,
            ..Default::default()
        }
    }
    #[test]
    fn auto_prefers_available_physical_primary_then_capture_order() {
        let mut d = vec![
            display("virtual", 1, true, true, true),
            display("physical2", 3, false, false, true),
            display("physical1", 2, true, false, true),
        ];
        assert_eq!(select(&d, "auto", "", 1920, 1080).unwrap().id, "physical1");
        d[2].available = false;
        assert_eq!(select(&d, "auto", "", 1920, 1080).unwrap().id, "physical2");
        d[1].available = false;
        assert!(select(&d, "auto", "", 1920, 1080).is_none());
        assert_eq!(select(&d, "auto", "", 2520, 1680).unwrap().id, "virtual");
    }
    #[test]
    fn explicit_selection_never_substitutes_a_different_display() {
        let d = vec![
            display("primary", 1, true, false, true),
            display("virtual", 2, false, true, true),
            display("removed", 3, false, false, false),
        ];
        assert_eq!(
            select(&d, "physical", "virtual", 1920, 1080).unwrap().id,
            "virtual"
        );
        assert!(select(&d, "physical", "removed", 1920, 1080).is_none());
        assert!(select(&d, "virtual", "", 1920, 1080).is_none());
        assert_eq!(select(&d, "virtual", "", 2520, 1680).unwrap().id, "virtual");
    }
}
