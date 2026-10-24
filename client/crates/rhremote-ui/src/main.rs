#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
fn main() {
    if let Some(result) = rhremote::run_transport_worker() {
        if let Err(error) = result { eprintln!("RHRemote transport: {error}"); std::process::exit(1); }
        return;
    }
    velopack::VelopackApp::build().run();
    if let Err(error) = rhremote::run() {
        eprintln!("RHRemote: {error}");
    }
}
