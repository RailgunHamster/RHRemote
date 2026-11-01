//! Local causal probe. Synthetic session; no pairing, network, video or remote input.
//! Reuses production widgets, queued widget driver and overlay geometry verbatim.
#![allow(dead_code)]
#![windows_subsystem = "windows"]
use rhremote::{
    Commands, FloatingKeyboardWindow, KeyboardKey, MainWindow, Palette, StreamPicker,
    StreamToolbar, UiTest,
};
use rhremote_core::{Controller, Platform};
use serde_json::{Value, json};
use slint::winit_030::{EventResult, WinitWindowAccessor, winit::event::WindowEvent};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::{
    cell::RefCell,
    fs,
    path::PathBuf,
    process::{Child, Command},
    rc::Rc,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use windows::Win32::{Foundation::HWND, UI::WindowsAndMessaging::*};
#[path = "../src/floating_keyboard.rs"]
mod floating_keyboard;
#[path = "../src/overlay.rs"]
mod overlay;
#[path = "../src/preview_capture.rs"]
mod preview_capture;
#[path = "../src/statistics.rs"]
mod statistics;
#[path = "../src/ui_driver.rs"]
mod ui_driver;
#[path = "../src/ui_keyboard.rs"]
mod ui_keyboard;
#[path = "../src/ui_rendering.rs"]
mod ui_rendering;
#[path = "../src/ui_test.rs"]
mod ui_test;
#[path = "../src/window_events.rs"]
mod window_events;

// Only these session-local actions are exercised. Draft/modal routing is outside
// this probe; the production workflow tests cover that conversion separately.
fn request_owner(_: &MainWindow, _: &str) -> Option<String> {
    None
}
fn queued_args(id: &str, arg: &str, _: &Value, _: Option<String>) -> Value {
    match id {
        "stream.keyboard.floating.set" => json!({"enabled":arg=="true"}),
        "stream.controls.tab" => json!({"tab":arg}),
        "stream.menu.open" => json!({"menu":arg}),
        _ => json!({}),
    }
}
struct Boundary;
impl Platform for Boundary {
    fn execute(&self, id: &str, _: &Value) -> Result<Value, String> {
        if matches!(id, "device.connect" | "stream.disconnect") {
            Ok(json!({"synthetic":true}))
        } else {
            Err(format!("Local fixture forbids platform operation: {id}"))
        }
    }
}
struct Actor {
    child: Child,
    window: HWND,
    pid: u32,
}
impl Drop for Actor {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}
impl Actor {
    fn admitted(&self) -> bool {
        let mut pid = 0;
        unsafe {
            GetWindowThreadProcessId(self.window, Some(&mut pid));
        }
        pid == self.pid && self.pid != 0
    }
    fn stop(&mut self) -> Result<(), String> {
        if self.admitted() {
            unsafe {
                PostMessageW(
                    Some(self.window),
                    WM_CLOSE,
                    Default::default(),
                    Default::default(),
                )
            }
            .map_err(|e| e.to_string())?;
        }
        let until = Instant::now() + Duration::from_secs(4);
        loop {
            if let Some(status) = self.child.try_wait().map_err(|e| e.to_string())? {
                return if status.success() {
                    Ok(())
                } else {
                    Err(format!("Actor exit: {status}"))
                };
            };
            if Instant::now() >= until {
                return Err("Owned actor still live; do not restart it".into());
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }
}
#[derive(Clone)]
enum Step {
    Api(&'static str, &'static str, &'static str),
    Scroll,
    Native(&'static str),
    Wait(&'static str, u64),
    Snapshot(&'static str),
}
fn foreground(windows: &[(&str, &slint::Window)], actor: HWND) -> String {
    let actual = unsafe { GetForegroundWindow() };
    if actual == actor {
        return "native".into();
    }
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    for (role, w) in windows {
        let matches=w.with_winit_window(|w|w.window_handle().ok().is_some_and(|h|matches!(h.as_raw(),RawWindowHandle::Win32(v)if HWND(v.hwnd.get()as *mut _)==actual))).unwrap_or(false);
        if matches {
            return (*role).into();
        }
    }
    "other".into()
}
fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() != 4 || std::env::var("RHREMOTE_TEST_MODE").as_deref() != Ok("1") {
        return Err("Explicit isolated test mode and OUT ACTOR THEME CYCLES required".into());
    }
    let out = PathBuf::from(&args[0]);
    let actor_exe = PathBuf::from(&args[1]);
    let dark = args[2] == "dark";
    let cycles = args[3].to_str().ok_or("cycles")?.parse::<usize>()?;
    if !out.is_absolute()
        || !actor_exe.is_absolute()
        || !out.is_dir()
        || out.join("report.json").exists()
        || out.join("preferences.json").exists()
        || !(1..=3).contains(&cycles)
    {
        return Err("Fresh isolated directory and 1-3 cycles required".into());
    }
    let allowed = [
        PathBuf::from(r"D:\RHRemoteTools\ui-local-overlay105-review-20261029"),
        PathBuf::from(r"D:\RHRemoteTools\ui-window-events106-review-20261029"),
    ];
    let allowed: Vec<_> = allowed
        .iter()
        .filter_map(|p| p.canonicalize().ok())
        .collect();
    if !allowed
        .iter()
        .any(|root| out.canonicalize().is_ok_and(|p| p.starts_with(root)))
        || !allowed
            .iter()
            .any(|root| actor_exe.canonicalize().is_ok_and(|p| p.starts_with(root)))
    {
        return Err("Probe paths outside its evidence root".into());
    }
    let mut child = Command::new(actor_exe)
        .arg(out.join("actor.json"))
        .spawn()?;
    fs::write(
        out.join("actor-process.json"),
        json!({"pid":child.id(),"synthetic":true,"networkStarted":false}).to_string(),
    )?;
    let until = Instant::now() + Duration::from_secs(5);
    while !out.join("actor.json").exists() {
        if child.try_wait()?.is_some() {
            return Err("Actor failed before admission".into());
        }
        if Instant::now() >= until {
            return Err("Actor readiness deadline; inspect recorded PID".into());
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let admitted: Value = serde_json::from_slice(&fs::read(out.join("actor.json"))?)?;
    let hwnd = HWND(
        admitted["window"]
            .as_str()
            .ok_or("actor window")?
            .parse::<usize>()? as *mut _,
    );
    let mut actor = Actor {
        pid: child.id(),
        child,
        window: hwnd,
    };
    if !actor.admitted() {
        return Err("Actor window process mismatch".into());
    }
    let bridge = Arc::new(ui_test::Bridge::new(true));
    let controller = Controller::new("test", out.join("preferences.json"), Arc::new(Boundary))?;
    controller.replace_devices(serde_json::from_value(json!([{"uuid":"local-fixture","name":"Local SDL placeholder (no video)","address":"192.0.2.1","online":true,"paired":true}]))?);
    let connected = controller.dispatch("device.connect", json!({"uuid":"local-fixture"}));
    if connected["ok"] != true {
        actor.stop()?;
        return Err(format!("{connected}").into());
    }
    controller.stream_state(true,json!({"phase":"connected","firstFrame":true,"nativeCleanupPending":false,"processId":actor.pid,"streamProcessId":actor.pid,"streamWindow":admitted["window"],"syntheticSession":true}));
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name("software".into())
        .with_winit_window_attributes_hook(|a| a.with_active(false))
        .select()?;
    let ui = MainWindow::new()?;
    ui.set_isolated_preview(true);
    ui.set_page("connection".into());
    ui.set_selected_name("Local fixture · no video".into());
    ui.set_streaming(true);
    ui.window()
        .set_position(slint::PhysicalPosition::new(-3776, 64));
    let toolbar = StreamToolbar::new()?;
    let keyboard = FloatingKeyboardWindow::new()?;
    let picker = StreamPicker::new()?;
    let shortcuts = rhremote_core::shortcuts();
    toolbar.set_shortcut_labels(ModelRc::new(VecModel::from(
        shortcuts
            .as_array()
            .unwrap()
            .iter()
            .map(|s| s["label"].as_str().unwrap().into())
            .collect::<Vec<slint::SharedString>>(),
    )));
    toolbar.set_shortcut_ids(ModelRc::new(VecModel::from(
        shortcuts
            .as_array()
            .unwrap()
            .iter()
            .map(|s| s["id"].as_str().unwrap().into())
            .collect::<Vec<slint::SharedString>>(),
    )));
    ui.set_dark(dark);
    ui.global::<Palette>().set_dark(dark);
    toolbar.global::<Palette>().set_dark(dark);
    keyboard.global::<Palette>().set_dark(dark);
    picker.global::<Palette>().set_dark(dark);
    let started = Instant::now();
    let events = Arc::new(Mutex::new(Vec::<Value>::new()));
    let observer_data = out.join("observer-statistics");
    fs::create_dir_all(&observer_data)?;
    let observer_statistics = Arc::new(Mutex::new(statistics::Statistics::new(&observer_data)));
    observer_statistics.lock().unwrap().configure(true)?;
    let observer_trace = observer_statistics
        .lock()
        .unwrap()
        .session_at(&json!({"synthetic":true}), None)?
        .ok_or("Observer trace not started")?
        .parent()
        .ok_or("Observer trace directory missing")?
        .to_owned();
    for (role, event_role, w) in [
        ("main", window_events::Role::Main, ui.window()),
        ("toolbar", window_events::Role::Toolbar, toolbar.window()),
        ("keyboard", window_events::Role::Keyboard, keyboard.window()),
        ("picker", window_events::Role::Picker, picker.window()),
    ] {
        let records = events.clone();
        let mut observed = 0;
        let mut recorder =
            window_events::Recorder::new(true, event_role, observer_statistics.clone());
        let dismiss_controller = controller.clone();
        w.on_winit_window_event(window_events::compose(
            move |event| {
                recorder.observe(event);
                let record = match event {
                    WindowEvent::Focused(active) => Some(json!({"kind":"focus","active":active})),
                    WindowEvent::MouseInput { state, .. } => {
                        Some(json!({"kind":"pointer-button","pressed":state.is_pressed()}))
                    }
                    WindowEvent::KeyboardInput { .. } => Some(json!({"kind":"keyboard-input"})),
                    WindowEvent::Moved(p) => Some(json!({"kind":"moved","x":p.x,"y":p.y})),
                    WindowEvent::Resized(s) => {
                        Some(json!({"kind":"resized","width":s.width,"height":s.height}))
                    }
                    _ => None,
                };
                if observed < 128 {
                    if let Some(mut r) = record {
                        observed += 1;
                        r["role"] = json!(role);
                        r["ms"] = json!(started.elapsed().as_millis());
                        records.lock().unwrap().push(r);
                    }
                }
            },
            move |event| {
                if role == "picker" && matches!(event, WindowEvent::Focused(false)) {
                    dismiss_controller.dispatch("stream.menu.close", json!({}));
                }
                EventResult::Propagate
            },
        ));
    }
    for w in [
        ui.window(),
        toolbar.window(),
        keyboard.window(),
        picker.window(),
    ] {
        w.on_close_requested(|| slint::CloseRequestResponse::KeepWindowShown);
    }
    macro_rules! bind {
        ($w:expr) => {{
            let c = controller.clone();
            let b = bridge.clone();
            $w.global::<Commands>().on_trigger(move |id, arg| {
                b.observe_action(id.as_str());
                if !matches!(
                    id.as_str(),
                    "stream.controls.toggle"
                        | "stream.controls.tab"
                        | "stream.keyboard.floating.set"
                        | "stream.menu.open"
                        | "stream.menu.close"
                ) {
                    return;
                }
                c.dispatch(
                    id.as_str(),
                    queued_args(id.as_str(), arg.as_str(), &c.state(), None),
                );
            });
        }};
    }
    bind!(toolbar);
    bind!(keyboard);
    bind!(picker);
    let dismiss = controller.clone();
    let dismiss_bridge = bridge.clone();
    picker.on_dismiss(move || {
        dismiss_bridge.observe_action("stream.menu.close");
        dismiss.dispatch("stream.menu.close", json!({}));
    });
    let mut driver = ui_driver::Driver::new(bridge.clone(), &ui, &toolbar, &picker);
    driver.attach_keyboard(&keyboard);
    let mut steps = vec![
        Step::Wait("toolbar", 300),
        Step::Native("resize-main"),
        Step::Wait("always", 200),
        Step::Snapshot("main"),
    ];
    for _ in 0..cycles {
        steps.extend([
            Step::Api("stream.controls.toggle", "", "toolbar"),
            Step::Api("stream.keyboard.floating.set", "true", "toolbar"),
            Step::Wait("keyboard", 300),
            Step::Snapshot("keyboard"),
            Step::Api("stream.controls.toggle", "", "toolbar"),
            Step::Api("stream.keyboard.floating.set", "false", "toolbar"),
            Step::Native("minimize"),
            Step::Wait("minimized", 120),
            Step::Native("restore"),
            Step::Wait("restored", 1800),
            Step::Native("resize-small"),
            Step::Wait("always", 250),
            Step::Native("resize-normal"),
            Step::Wait("always", 250),
            Step::Api("stream.controls.toggle", "", "toolbar"),
            Step::Api("stream.keyboard.floating.set", "true", "toolbar"),
            Step::Wait("keyboard", 150),
            Step::Native("minimize"),
            Step::Wait("minimized", 120),
            Step::Native("restore"),
            Step::Wait("restored", 1800),
            Step::Api("stream.controls.toggle", "", "toolbar"),
            Step::Api("stream.controls.tab", "input", "toolbar"),
            Step::Wait("panel", 150),
            Step::Snapshot("toolbar"),
            Step::Api("stream.menu.open", "shortcuts", "toolbar"),
            Step::Wait("picker", 150),
            Step::Snapshot("picker"),
            Step::Scroll,
            Step::Api("stream.menu.close", "", "picker"),
            Step::Api("stream.controls.toggle", "", "toolbar"),
            Step::Wait("keyboard", 200),
            Step::Api("stream.controls.toggle", "", "toolbar"),
            Step::Api("stream.keyboard.floating.set", "false", "toolbar"),
        ]);
    }
    steps.extend([
        Step::Native("close"),
        Step::Wait("recovery", 400),
        Step::Snapshot("toolbar"),
    ]);
    let outcome = Rc::new(RefCell::new(
        json!({"passed":false,"failure":"event loop did not finish"}),
    ));
    let result = outcome.clone();
    let mut follower = overlay::Follower::default();
    let mut keyboard_state = Value::Null;
    let mut index = 0usize;
    let mut phase_at = Instant::now();
    let mut pending = None::<String>;
    let mut expected_actor_exit = false;
    let mut recovery_observed = false;
    let trace = events.clone();
    let evidence = out.clone();
    let native_pid = actor.pid;
    ui.show()?;
    let timer = slint::Timer::default();
    timer.start(slint::TimerMode::Repeated,Duration::from_millis(40),move||{
        let tick=(||->Result<bool,String>{
            if started.elapsed()>Duration::from_secs(75){return Err("Local UI deadline".into());}
            let mut pid=0;unsafe{GetWindowThreadProcessId(hwnd,Some(&mut pid));}if pid!=native_pid&&!expected_actor_exit{return Err("Owned native actor is gone".into());}
            let state=controller.state();ui.set_controls(state["controls"]==true);
            toolbar.set_device_name("Local fixture · no video".into());toolbar.set_busy(false);toolbar.set_pending(false);toolbar.set_expanded(state["controls"]==true);toolbar.set_floating_keyboard(state["floatingKeyboard"]==true);toolbar.set_controls_tab(state["controlsTab"].as_str().unwrap_or("input").into());
            let mut local=follower.follow(&toolbar,&state);keyboard_state=floating_keyboard::follow(&keyboard,&state,&keyboard_state);local["floatingKeyboard"]=keyboard_state.clone();
            let kind=state["streamMenu"].as_str().unwrap_or("");picker.set_kind(kind.into());
            if kind=="shortcuts"{picker.set_labels(toolbar.get_shortcut_labels());picker.set_values(toolbar.get_shortcut_ids());picker.set_enabled_items(ModelRc::new(VecModel::from(vec![true;toolbar.get_shortcut_ids().row_count()])));}
            local["picker"]=overlay::follow_picker(&picker,&state,&local);controller.toolbar_state(local.clone());
            driver.tick(&ui,&toolbar,&picker,&controller);
            let fg=foreground(&[("main",ui.window()),("toolbar",toolbar.window()),("keyboard",keyboard.window()),("picker",picker.window())],hwnd);
            if trace.lock().unwrap().len()<4000{trace.lock().unwrap().push(json!({"kind":"sample","ms":started.elapsed().as_millis(),"step":index,"foreground":fg,"toolbar":local["visible"],"keyboard":keyboard_state["visible"],"picker":local["picker"]["visible"]}));}
            if fg!="other"{return Err(format!("Owned foreground: {fg}, step {index}"));}
            if index==steps.len(){return Ok(true);}
            let step=steps[index].clone();let mut complete=false;
            match step{
                Step::Api(_,_,_)|Step::Scroll=>{
                    if let Some(id)=pending.as_ref(){let r=bridge.execute("view.ui.result",&json!({"id":id}))?;if r["status"]=="failed"{return Err(format!("Queued widget failure: {}",r["result"]["error"]));}if r["status"]=="complete"{complete=true;}}
                    else{
                        let args=match step{Step::Api(action,arg,window)=>json!({"window":window,"actionId":action,"argument":arg,"expectedPending":false}),_=>json!({"window":"picker","control":"scroll|picker","pixels":260})};
                        let mut args=args;if args["window"]!="toolbar"{args.as_object_mut().unwrap().remove("expectedPending");}
                        let r=bridge.execute(if matches!(steps[index],Step::Scroll){"view.ui.scroll"}else{"view.ui.activate"},&args)?;pending=Some(r["id"].as_str().ok_or("Missing request ID")?.into());
                    }
                }
                Step::Native(command)=>unsafe{
                    if command=="resize-main" {
                        ui.window().set_size(slint::PhysicalSize::new(2200,1500));
                        complete=true;
                    } else if command=="close" {
                        fs::write(evidence.join("before-native-close.json"),serde_json::to_vec_pretty(&*trace.lock().unwrap()).map_err(|e|e.to_string())?).map_err(|e|e.to_string())?;
                        PostMessageW(Some(hwnd),WM_CLOSE,Default::default(),Default::default()).map_err(|e|e.to_string())?;
                        expected_actor_exit=true;complete=true;
                    }else{
                    match command{"minimize"=>{if !ShowWindowAsync(hwnd,SW_SHOWMINNOACTIVE).as_bool(){return Err("Minimize was not queued".into());}},"restore"=>{if !ShowWindowAsync(hwnd,SW_SHOWNOACTIVATE).as_bool(){return Err("Restore was not queued".into());}},"resize-small"|"resize-normal"=>{let(w,h)=if command=="resize-small"{(720,400)}else{(1948,1159)};SetWindowPos(hwnd,None,0,0,w,h,SWP_ASYNCWINDOWPOS|SWP_NOMOVE|SWP_NOACTIVATE|SWP_NOZORDER).map_err(|e|e.to_string())?;},_=>return Err("Unknown native operation".into())}complete=true;
                    }
                },
                Step::Wait(what,ms)=>{
                    let ready=match what{"toolbar"=>local["visible"]==true,"keyboard"=>keyboard_state["visible"]==true,"minimized"=>(unsafe{IsIconic(hwnd).as_bool()})&&local["visible"]==false,"restored"=>!(unsafe{IsIconic(hwnd).as_bool()})&&local["visible"]==true,"panel"=>local["expanded"]==true&&keyboard_state["visible"]==false,"picker"=>local["picker"]["visible"]==true,"recovery"=>local["detached"]==true&&local["visible"]==true&&local["pending"]==true&&local["shape"]["applied"]==true,"always"=>true,_=>false};
                    complete=ready&&phase_at.elapsed()>=Duration::from_millis(ms);if phase_at.elapsed()>Duration::from_secs(5){return Err(format!("Wait not satisfied: {what}"));}
                    if complete&&what=="recovery"{recovery_observed=true;}
                }
                Step::Snapshot(which)=>{
                    let w=match which{"main"=>ui.window(),"keyboard"=>keyboard.window(),"picker"=>picker.window(),_=>toolbar.window()};
                    preview_capture::capture(w,&evidence.join(format!("step-{index:03}-{which}.png")),json!({"synthetic":true}))?;complete=true;
                }
            }
            if complete{trace.lock().unwrap().push(json!({"kind":"step-complete","step":index,"ms":started.elapsed().as_millis()}));index+=1;pending=None;phase_at=Instant::now();}
            Ok(false)
        })();
        if !matches!(tick,Ok(false)){
            let failure=tick.err();*result.borrow_mut()=json!({"passed":failure.is_none(),"failure":failure,"completedSteps":index,"expectedSteps":steps.len(),"cycles":cycles,"nativeExitRequested":expected_actor_exit,"recoveryObserved":recovery_observed,"syntheticSession":true,"networkStarted":false,"videoDecoded":false,"remoteInputSent":false});
            let _=picker.hide();let _=keyboard.hide();let _=toolbar.hide();let _=ui.hide();let _=slint::quit_event_loop();
        }
    });
    slint::run_event_loop_until_quit()?;
    timer.stop();
    let actor_cleanup = actor.stop();
    let mut report = outcome.borrow().clone();
    report["actorStopped"] = json!(actor_cleanup.is_ok());
    if let Err(e) = actor_cleanup {
        report["passed"] = json!(false);
        report["cleanupError"] = json!(e);
    }
    observer_statistics.lock().unwrap().finish()?;
    let recorded_events = fs::read_to_string(observer_trace.join("ui.jsonl"))?;
    let recorded_events: Vec<Value> = recorded_events
        .lines()
        .map(serde_json::from_str)
        .collect::<Result<_, _>>()?;
    let mut counts = json!({});
    for role in ["main", "toolbar", "keyboard", "picker"] {
        let count = recorded_events
            .iter()
            .filter(|row| row["event"] == "ui_native_window_event" && row["fields"]["role"] == role)
            .count();
        counts[role] = json!(count);
    }
    if counts["main"] == 0 || counts["picker"] == 0 {
        report["passed"] = json!(false);
        report["observerError"] = json!("Expected actual main and picker native events");
    }
    report["observerEventCounts"] = counts;
    report["observerTrace"] = json!(observer_trace);
    fs::write(out.join("report.json"), serde_json::to_vec_pretty(&report)?)?;
    let rows = events
        .lock()
        .unwrap()
        .iter()
        .map(|v| v.to_string())
        .collect::<Vec<_>>()
        .join("\n");
    fs::write(out.join("events.jsonl"), rows + "\n")?;
    if report["passed"] != true {
        return Err("Local overlay probe failed; inspect its report".into());
    }
    Ok(())
}
fn main() -> Result<(), Box<dyn std::error::Error>> {
    run()
}
