//! One native event filter per window, with diagnostics preceding normal routing.
use crate::statistics::Statistics;
use serde_json::{Value, json};
use slint::winit_030::{EventResult, winit::event::WindowEvent};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
};

#[derive(Clone, Copy)]
pub(crate) enum Role {
    Main,
    Toolbar,
    Keyboard,
    Picker,
}
impl Role {
    fn label(self) -> &'static str {
        match self {
            Self::Main => "main",
            Self::Toolbar => "toolbar",
            Self::Keyboard => "keyboard",
            Self::Picker => "picker",
        }
    }
}
fn metadata(role: Role, event: &WindowEvent) -> Option<Value> {
    let mut value = match event {
        WindowEvent::Focused(active) => json!({"kind":"focus","active":active}),
        WindowEvent::MouseInput { state, .. } => {
            json!({"kind":"pointer-button","pressed":state.is_pressed()})
        }
        WindowEvent::KeyboardInput { .. } => json!({"kind":"keyboard-input"}),
        WindowEvent::Moved(_) => json!({"kind":"moved"}),
        WindowEvent::Resized(_) => json!({"kind":"resized"}),
        _ => return None,
    };
    value["role"] = json!(role.label());
    Some(value)
}
pub(crate) struct Recorder {
    enabled: bool,
    role: Role,
    statistics: Arc<Mutex<Statistics>>,
    session: Option<PathBuf>,
    attempts: u8,
}
impl Recorder {
    pub(crate) fn new(enabled: bool, role: Role, statistics: Arc<Mutex<Statistics>>) -> Self {
        Self {
            enabled,
            role,
            statistics,
            session: None,
            attempts: 0,
        }
    }
    pub(crate) fn observe(&mut self, event: &WindowEvent) {
        if !self.enabled {
            return;
        }
        let Some(fields) = metadata(self.role, event) else {
            return;
        };
        // A diagnostic failure must not prevent keyboard handling or menu dismissal.
        let Ok(statistics) = self.statistics.lock() else {
            return;
        };
        let Some(session) = statistics.recording_session() else {
            return;
        };
        if self.session.as_deref() != Some(session) {
            self.session = Some(session.to_owned());
            self.attempts = 0;
        }
        if self.attempts >= 64 {
            return;
        }
        self.attempts += 1;
        let _ = statistics.event("ui_native_window_event", fields);
    }
}
fn dispatch<O, H>(observer: &mut O, handler: &mut H, event: &WindowEvent) -> EventResult
where
    O: FnMut(&WindowEvent),
    H: FnMut(&WindowEvent) -> EventResult,
{
    observer(event);
    handler(event)
}
pub(crate) fn compose<O, H>(
    mut observer: O,
    mut handler: H,
) -> impl FnMut(&slint::Window, &WindowEvent) -> EventResult
where
    O: FnMut(&WindowEvent) + 'static,
    H: FnMut(&WindowEvent) -> EventResult + 'static,
{
    move |_, event| dispatch(&mut observer, &mut handler, event)
}

#[cfg(test)]
mod tests {
    use super::*;
    use slint::winit_030::winit::{
        dpi::{PhysicalPosition, PhysicalSize},
        event::Ime,
    };
    use std::{cell::RefCell, fs, rc::Rc};
    #[test]
    fn handled_events_are_observed_once_before_the_handler_and_keep_the_result() {
        let calls = Rc::new(RefCell::new(vec![]));
        let a = calls.clone();
        let b = calls.clone();
        let mut observe = move |_: &WindowEvent| a.borrow_mut().push("observe");
        let mut handle = move |_: &WindowEvent| {
            b.borrow_mut().push("handle");
            EventResult::PreventDefault
        };
        assert_eq!(
            dispatch(&mut observe, &mut handle, &WindowEvent::Focused(false)),
            EventResult::PreventDefault
        );
        assert_eq!(*calls.borrow(), vec!["observe", "handle"]);
    }
    #[test]
    fn propagation_and_menu_focus_loss_are_not_consumed_by_diagnostics() {
        let mut observed = 0;
        let mut dismissed = 0;
        let mut observe = |_: &WindowEvent| observed += 1;
        let mut handle = |event: &WindowEvent| {
            if matches!(event, WindowEvent::Focused(false)) {
                dismissed += 1;
            }
            EventResult::Propagate
        };
        assert_eq!(
            dispatch(&mut observe, &mut handle, &WindowEvent::Focused(true)),
            EventResult::Propagate
        );
        assert_eq!(
            dispatch(&mut observe, &mut handle, &WindowEvent::Focused(false)),
            EventResult::Propagate
        );
        assert_eq!(observed, 2);
        assert_eq!(dismissed, 1);
    }
    #[test]
    fn metadata_excludes_ime_text_and_geometry_coordinates() {
        assert!(
            metadata(
                Role::Main,
                &WindowEvent::Ime(Ime::Preedit("private text".into(), Some((1, 2))))
            )
            .is_none()
        );
        assert_eq!(
            metadata(
                Role::Main,
                &WindowEvent::Moved(PhysicalPosition::new(-3776, 64))
            ),
            Some(json!({"role":"main","kind":"moved"}))
        );
        assert_eq!(
            metadata(
                Role::Picker,
                &WindowEvent::Resized(PhysicalSize::new(945, 900))
            ),
            Some(json!({"role":"picker","kind":"resized"}))
        );
    }
    #[test]
    fn startup_does_not_spend_the_budget_and_each_recording_gets_its_own_budget() {
        let data = std::env::temp_dir().join(format!(
            "rhremote-window-events-{}",
            rhremote_core::random_token().unwrap()
        ));
        fs::create_dir_all(&data).unwrap();
        let stats = Arc::new(Mutex::new(Statistics::new(&data)));
        stats.lock().unwrap().configure(true).unwrap();
        let mut recorder = Recorder::new(true, Role::Main, stats.clone());
        for _ in 0..100 {
            recorder.observe(&WindowEvent::Focused(false));
        }
        assert_eq!(recorder.attempts, 0);
        let first = stats
            .lock()
            .unwrap()
            .session(&json!({}))
            .unwrap()
            .unwrap()
            .parent()
            .unwrap()
            .to_owned();
        for _ in 0..100 {
            recorder.observe(&WindowEvent::Focused(false));
        }
        assert_eq!(recorder.attempts, 64);
        stats.lock().unwrap().finish().unwrap();
        let count = |dir: &std::path::Path| {
            fs::read_to_string(dir.join("ui.jsonl"))
                .unwrap()
                .lines()
                .filter(|line| {
                    serde_json::from_str::<Value>(line).unwrap()["event"]
                        == "ui_native_window_event"
                })
                .count()
        };
        assert_eq!(count(&first), 64);
        let second = stats
            .lock()
            .unwrap()
            .session(&json!({}))
            .unwrap()
            .unwrap()
            .parent()
            .unwrap()
            .to_owned();
        recorder.observe(&WindowEvent::Focused(true));
        assert_eq!(recorder.attempts, 1);
        let mut disabled = Recorder::new(false, Role::Picker, stats.clone());
        disabled.observe(&WindowEvent::Focused(false));
        stats.lock().unwrap().finish().unwrap();
        assert_eq!(count(&second), 1);
        assert_ne!(first, second);
        drop(recorder);
        drop(stats);
        fs::remove_dir_all(data).unwrap();
    }
}
