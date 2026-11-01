fn main() {
    println!("cargo:rerun-if-env-changed=SLINT_EMIT_DEBUG_INFO");
    println!("cargo:rerun-if-changed=../../shared/icons/rhremote.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
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
