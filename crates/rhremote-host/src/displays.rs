use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
};
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Prepare {
    pub mode: String,
    #[serde(default)]
    pub display_id: String,
    pub width: u32,
    pub height: u32,
}
impl Prepare {
    pub fn validate(&self) -> Result<(), String> {
        if !["auto", "virtual", "physical"].contains(&self.mode.as_str())
            || self.display_id.len() > 512
            || self.width < 320
            || self.height < 320
            || self.width > 8192
            || self.height > 8192
            || self.width % 2 != 0
            || self.height % 2 != 0
            || u64::from(self.width) * u64::from(self.height) > 64_000_000
        {
            return Err("Invalid display request".into());
        }
        Ok(())
    }
}
pub fn install_helpers() -> Result<PathBuf, Box<dyn std::error::Error>> {
    Ok(std::env::current_exe()?
        .parent()
        .ok_or("Executable directory missing")?
        .join("scripts/display.ps1"))
}
pub fn run(helper: &Path, request: Value) -> Result<Value, String> {
    if !helper.exists() {
        return Err("主机显示组件未安装".into());
    }
    let root = PathBuf::from(std::env::var_os("ProgramData").ok_or("ProgramData unavailable")?)
        .join("RHRemoteHost");
    if !root.is_dir() {
        return Err("主机显示组件尚未初始化".into());
    }
    let id = format!(
        "{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    );
    let input = root.join(format!("request-{id}.json"));
    let output = root.join(format!("response-{id}.json"));
    fs::write(
        &input,
        serde_json::to_vec(&request).map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    #[cfg(windows)]
    let result = crate::console::worker(&input, &output);
    #[cfg(not(windows))]
    let result: Result<(), String> = Err("Windows host required".into());
    let result = result.and_then(|_| {
        let bytes = fs::read(&output).map_err(|e| e.to_string())?;
        if bytes.len() > 131072 {
            return Err("Display response too large".into());
        }
        let value: Value = serde_json::from_slice(&bytes).map_err(|e| e.to_string())?;
        if value["ok"] == false {
            Err(value["error"]
                .as_str()
                .unwrap_or("Display action failed")
                .into())
        } else {
            Ok(value)
        }
    });
    let _ = fs::remove_file(input);
    let _ = fs::remove_file(output);
    result
}
#[cfg(windows)]
pub fn worker(input: &Path, output: &Path) -> Result<(), String> {
    crate::console::helper(
        &install_helpers().map_err(|e| e.to_string())?,
        input,
        output,
    )
}
#[cfg(windows)]
pub fn capture_order() -> Result<Value, String> {
    use windows::{
        Win32::{
            Foundation::HMODULE,
            Graphics::{Direct3D::*, Direct3D11::*, Dxgi::*},
        },
        core::Interface,
    };
    unsafe {
        use windows::Win32::System::StationsAndDesktops::*;
        let desktop = OpenInputDesktop(
            DF_ALLOWOTHERACCOUNTHOOK,
            false,
            DESKTOP_ACCESS_FLAGS(0x10000000),
        )
        .map_err(|e| e.to_string())?;
        let sync = SetThreadDesktop(desktop);
        let _ = CloseDesktop(desktop);
        sync.map_err(|e| e.to_string())?;
        let factory: IDXGIFactory1 = CreateDXGIFactory1().map_err(|e| e.to_string())?;
        let mut names = Vec::new();
        let mut diagnostics = Vec::new();
        let mut a = 0;
        while let Ok(adapter) = factory.EnumAdapters1(a) {
            a += 1;
            // Creating a D3D device can be expensive. An adapter without attached
            // outputs cannot contribute a capture target, so enumerate outputs first.
            let mut outputs = Vec::new();
            let mut o = 0;
            while let Ok(output) = adapter.EnumOutputs(o) {
                o += 1;
                let desc = output.GetDesc().map_err(|e| e.to_string())?;
                if desc.AttachedToDesktop.as_bool() {
                    outputs.push(output);
                }
            }
            if outputs.is_empty() {
                continue;
            }
            let started = std::time::Instant::now();
            let mut device = None;
            let creation = D3D11CreateDevice(
                &adapter,
                D3D_DRIVER_TYPE_UNKNOWN,
                HMODULE::default(),
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                Some(&[
                    D3D_FEATURE_LEVEL_11_1,
                    D3D_FEATURE_LEVEL_11_0,
                    D3D_FEATURE_LEVEL_10_1,
                    D3D_FEATURE_LEVEL_10_0,
                    D3D_FEATURE_LEVEL_9_3,
                    D3D_FEATURE_LEVEL_9_2,
                    D3D_FEATURE_LEVEL_9_1,
                ]),
                D3D11_SDK_VERSION,
                Some(&mut device),
                None,
                None,
            );
            if let Err(error) = creation {
                diagnostics
                    .push(json!({"adapter":a-1,"createDevice":format!("{:08x}",error.code().0),"createDeviceMs":started.elapsed().as_millis() as u64}));
                continue;
            }
            diagnostics.push(json!({"adapter":a-1,"attachedOutputs":outputs.len(),"createDeviceMs":started.elapsed().as_millis() as u64}));
            let Some(device) = device else { continue };
            for output in outputs {
                let desc = output.GetDesc().map_err(|e| e.to_string())?;
                let Ok(output1) = output.cast::<IDXGIOutput1>() else {
                    continue;
                };
                let duplicate = output1.DuplicateOutput(&device);
                if duplicate.is_ok() {
                    let count = desc
                        .DeviceName
                        .iter()
                        .position(|x| *x == 0)
                        .unwrap_or(desc.DeviceName.len());
                    names.push(String::from_utf16_lossy(&desc.DeviceName[..count]));
                } else if let Err(error) = duplicate {
                    diagnostics.push(json!({"adapter":a-1,"output":String::from_utf16_lossy(&desc.DeviceName).trim_end_matches('\0'),"duplicateOutput":format!("{:08x}",error.code().0)}));
                }
            }
        }
        Ok(json!({"names":names,"diagnostics":diagnostics}))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn validates_resolution_and_rejects_arbitrary_commands() {
        assert!(
            serde_json::from_value::<Prepare>(
                json!({"mode":"virtual","width":1920,"height":1080,"command":"bad"})
            )
            .is_err()
        );
        for mode in ["auto", "virtual", "physical"] {
            assert!(
                Prepare {
                    mode: mode.into(),
                    display_id: String::new(),
                    width: 2520,
                    height: 1680
                }
                .validate()
                .is_ok()
            );
        }
        assert!(
            Prepare {
                mode: "virtual".into(),
                display_id: String::new(),
                width: 8192,
                height: 8192
            }
            .validate()
            .is_err()
        );
    }
}
