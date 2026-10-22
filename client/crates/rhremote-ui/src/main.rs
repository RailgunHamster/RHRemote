#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
fn main() {
    velopack::VelopackApp::build().run();
    if let Err(error) = rhremote::run() {
        eprintln!("RHRemote: {error}");
    }
}
