//! A list probe may borrow the idle session transport, but must yield before
//! foreground work changes its ownership. No session locks are held while waiting.
use std::sync::{Arc, Condvar, Mutex, atomic::{AtomicBool, Ordering}};

#[derive(Default)]
struct State { probe: Option<Arc<AtomicBool>>, foreground_waiters: usize, foreground: bool }

#[derive(Default)]
pub(crate) struct Admission { state: Mutex<State>, changed: Condvar }

impl Admission {
    pub(crate) fn probe(&self) -> Option<Probe<'_>> {
        let mut state = self.state.lock().unwrap();
        if state.foreground || state.foreground_waiters != 0 || state.probe.is_some() { return None; }
        let cancel = Arc::new(AtomicBool::new(false)); state.probe = Some(cancel.clone());
        Some(Probe { admission: self, cancel })
    }

    pub(crate) fn foreground(&self) -> Foreground<'_> {
        self.foreground_cancelable(&AtomicBool::new(false)).unwrap()
    }

    pub(crate) fn cancel_probe(&self) {
        if let Some(cancel)=&self.state.lock().unwrap().probe {cancel.store(true,Ordering::SeqCst);}
    }

    /// Waiting for an old probe to release the transport is background work.
    /// Cancellation must not have to acquire the very lease it is cancelling.
    pub(crate) fn foreground_cancelable(&self,cancel:&AtomicBool) -> Option<Foreground<'_>> {
        self.acquire_foreground(cancel).then(||Foreground(self))
    }
    pub(crate) fn foreground_owned(self:&Arc<Self>,cancel:&AtomicBool)->Option<OwnedForeground>{
        self.acquire_foreground(cancel).then(||OwnedForeground(self.clone()))
    }
    fn acquire_foreground(&self,cancel:&AtomicBool)->bool {
        let mut state = self.state.lock().unwrap(); state.foreground_waiters += 1;
        if let Some(cancel) = &state.probe { cancel.store(true, Ordering::SeqCst); }
        while state.probe.is_some() || state.foreground {
            if cancel.load(Ordering::SeqCst) {state.foreground_waiters-=1;return false;}
            state = self.changed.wait_timeout(state,std::time::Duration::from_millis(25)).unwrap().0;
        }
        if cancel.load(Ordering::SeqCst) {state.foreground_waiters-=1;return false;}
        state.foreground_waiters -= 1; state.foreground = true;
        true
    }
}

pub(crate) struct Probe<'a> { admission: &'a Admission, pub(crate) cancel: Arc<AtomicBool> }
impl Drop for Probe<'_> {
    fn drop(&mut self) { self.admission.state.lock().unwrap().probe = None; self.admission.changed.notify_all(); }
}
pub(crate) struct Foreground<'a>(&'a Admission);
pub(crate) struct OwnedForeground(Arc<Admission>);
impl Drop for OwnedForeground {fn drop(&mut self){self.0.state.lock().unwrap().foreground=false;self.0.changed.notify_all();}}
impl Drop for Foreground<'_> {
    fn drop(&mut self) { self.0.state.lock().unwrap().foreground = false; self.0.changed.notify_all(); }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{sync::mpsc, time::Duration};

    #[test]
    fn foreground_cancels_probe_and_waits_for_its_cleanup_before_reusing_transport() {
        let admission = Arc::new(Admission::default()); let probe = admission.probe().unwrap();
        let (entered, received) = mpsc::channel(); let (release, released) = mpsc::channel();
        let worker = { let admission=admission.clone(); std::thread::spawn(move || { let _lease=admission.foreground(); entered.send(()).unwrap(); released.recv_timeout(Duration::from_secs(2)).unwrap(); }) };
        let deadline=std::time::Instant::now()+Duration::from_secs(2);
        while !probe.cancel.load(Ordering::SeqCst) && std::time::Instant::now()<deadline { std::thread::yield_now(); }
        assert!(probe.cancel.load(Ordering::SeqCst));
        assert!(admission.probe().is_none(), "a waiting foreground request must stop the next host probe");
        assert!(received.try_recv().is_err(), "foreground reused the tunnel before probe cleanup");
        drop(probe); received.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(admission.probe().is_none(), "foreground ownership did not exclude probes");
        release.send(()).unwrap(); worker.join().unwrap();
        assert!(admission.probe().is_some());
    }

    #[test]
    fn a_second_foreground_request_cannot_overtake_the_current_owner() {
        let admission=Arc::new(Admission::default()); let first=admission.foreground();
        let (entered,received)=mpsc::channel(); let worker={let admission=admission.clone();std::thread::spawn(move||{let _second=admission.foreground();entered.send(()).unwrap();})};
        assert!(received.recv_timeout(Duration::from_millis(50)).is_err()); drop(first);
        received.recv_timeout(Duration::from_secs(2)).unwrap();worker.join().unwrap(); assert!(admission.probe().is_some());
    }

    #[test]
    fn cancelled_waiter_retires_without_stealing_the_held_transport_or_blocking_next_probe(){
        let admission=Arc::new(Admission::default());let first=admission.foreground();
        let cancel=Arc::new(AtomicBool::new(false));let (sent,received)=mpsc::channel();
        let worker={let admission=admission.clone();let cancel=cancel.clone();std::thread::spawn(move||{sent.send(admission.foreground_cancelable(&cancel).is_some()).unwrap();})};
        cancel.store(true,Ordering::SeqCst);
        assert!(!received.recv_timeout(Duration::from_secs(2)).unwrap());worker.join().unwrap();
        assert!(admission.probe().is_none(),"cancel released another foreground owner's lease");
        drop(first);assert!(admission.probe().is_some(),"cancel leaked its waiter count");
    }
}
