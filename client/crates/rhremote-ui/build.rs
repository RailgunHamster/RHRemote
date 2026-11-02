fn main() {
    println!("cargo:rerun-if-env-changed=SLINT_EMIT_DEBUG_INFO");
    println!("cargo:rerun-if-changed=../../shared/icons/rhremote.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        println!("cargo:rerun-if-changed=src/RHRemoteUpdateSplash.cs");
        let helper=std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("RHRemote.UpdateSplash.exe");
        let compiler=std::path::PathBuf::from(std::env::var_os("SystemRoot").expect("Windows build requires SystemRoot")).join("Microsoft.NET/Framework64/v4.0.30319/csc.exe");
        let mut command=std::process::Command::new(compiler);
        let source=std::path::PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("src").join("RHRemoteUpdateSplash.cs");
        command.args(["/nologo","/utf8output","/codepage:65001","/optimize+","/target:winexe","/r:System.dll","/r:System.Core.dll","/r:System.Drawing.dll","/r:System.Windows.Forms.dll"])
            .arg(format!("/out:{}",helper.display())).arg(source);
        #[cfg(windows)]{use std::os::windows::process::CommandExt;command.creation_flags(0x08000000);}
        let result=command.output().expect("compile the embedded update transition helper");
        assert!(result.status.success(),"update transition helper compilation failed: {} {}",String::from_utf8_lossy(&result.stdout),String::from_utf8_lossy(&result.stderr));
        let icon = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap())
            .join("../../shared/icons/rhremote.ico")
            .canonicalize()
            .unwrap();
        let rc = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("rhremote.rc");
        std::fs::write(
            &rc,
            format!(
                "1 ICON \"{}\"\n",
                icon.display()
                    .to_string()
                    .trim_start_matches("\\\\?\\")
                    .replace('\\', "/")
            ),
        )
        .unwrap();
        embed_resource::compile(rc, embed_resource::NONE)
            .manifest_optional()
            .expect("embed RHRemote icon");
    }
    let config = slint_build::CompilerConfiguration::new()
        .with_debug_info(std::env::var("PROFILE").as_deref() != Ok("release") || std::env::var("SLINT_EMIT_DEBUG_INFO").as_deref()==Ok("1"));
    slint_build::compile_with_config("ui/app.slint", config).expect("compile RHRemote UI");
}
