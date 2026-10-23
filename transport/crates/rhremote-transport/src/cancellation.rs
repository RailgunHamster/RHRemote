//! Monotonic JNI attempts allow cancellation before registration without letting
//! a delayed cancellation stop a newer connection. No client/runtime lock needed.
use anyhow::{Result, ensure};
use std::sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}};

#[derive(Default)]
pub(crate) struct Attempts(Mutex<State>);
#[derive(Default)]
struct State {
    last_started: u64,
    cancelled_through: u64,
    active: Option<(u64, Arc<AtomicBool>)>,
}
pub(crate) struct Attempt<'a> {
    owner: &'a Attempts,
    id: u64,
    cancelled: Arc<AtomicBool>,
}
impl Attempts {
    pub(crate) fn begin(&self, id: u64) -> Result<Attempt<'_>> {
        let mut state = self.0.lock().unwrap_or_else(|e| e.into_inner());
        ensure!(id > state.last_started && id <= i64::MAX as u64, "Invalid transport attempt");
        ensure!(state.active.is_none(), "Previous transport attempt is still running");
        state.last_started = id;
        let cancelled = Arc::new(AtomicBool::new(id <= state.cancelled_through));
        state.active = Some((id, cancelled.clone()));
        Ok(Attempt { owner: self, id, cancelled })
    }
    pub(crate) fn cancel(&self, id: u64) {
        if id == 0 || id > i64::MAX as u64 { return; }
        let mut state = self.0.lock().unwrap_or_else(|e| e.into_inner());
        state.cancelled_through = state.cancelled_through.max(id);
        if let Some((active_id, token)) = &state.active {
            if *active_id == id { token.store(true, Ordering::SeqCst); }
        }
    }
}
impl Attempt<'_> {
    pub(crate) fn token(&self) -> &AtomicBool { &self.cancelled }
}
impl Drop for Attempt<'_> {
    fn drop(&mut self) {
        let mut state = self.owner.0.lock().unwrap_or_else(|e| e.into_inner());
        if state.active.as_ref().is_some_and(|(id, _)| *id == self.id) { state.active = None; }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn cancellation_before_jni_registration_is_not_lost() {
        let attempts=Attempts::default();attempts.cancel(1);
        let attempt=attempts.begin(1).unwrap();assert!(attempt.token().load(Ordering::SeqCst));
    }
    #[test] fn cancellation_does_not_need_the_client_lock_and_old_ids_cannot_stop_new_attempts() {
        let attempts=Attempts::default();
        { let first=attempts.begin(1).unwrap();attempts.cancel(1);assert!(first.token().load(Ordering::SeqCst)); }
        let next=attempts.begin(2).unwrap();attempts.cancel(1);
        assert!(!next.token().load(Ordering::SeqCst));attempts.cancel(2);assert!(next.token().load(Ordering::SeqCst));
    }
    #[test] fn registration_rejects_overlap_and_reused_ids() {
        let attempts=Attempts::default();let first=attempts.begin(1).unwrap();
        assert!(attempts.begin(2).is_err());drop(first);assert!(attempts.begin(1).is_err());
        assert!(attempts.begin(0).is_err());assert!(attempts.begin(2).is_ok());
    }
    #[test] fn repeated_and_invalid_cancel_are_idempotent() {
        let attempts=Attempts::default();attempts.cancel(0);attempts.cancel(u64::MAX);
        let first=attempts.begin(1).unwrap();assert!(!first.token().load(Ordering::SeqCst));
        attempts.cancel(1);attempts.cancel(1);assert!(first.token().load(Ordering::SeqCst));
    }
}
