// A reconnect burst is bounded independently of individual transport timeouts.
// Only a previously rendered session can arm recovery; explicit disconnect drops it.
#[derive(Default)]
pub struct Policy {
    pub attempts: u8,
    armed: bool,
    started: Option<u64>,
    next: Option<u64>,
    healthy: Option<u64>,
    admission_attempt: Option<u32>,
    pub admission_waits: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FailedRoute {
    pub route: String,
    pub address: String,
}

/// A media failure excludes only that route/address for the current burst.
/// Certificate, decoder and explicit-close failures never admit a fallback.
#[derive(Default)]
pub struct Routes {
    pub failed: Vec<FailedRoute>,
    started: Option<u64>,
    healthy_since: Option<u64>,
}

/// Separate launch preparation from waiting for media after the protocol is ready.
/// A ready milestone grants one media budget, within an absolute 30-second cap.
#[derive(Default)]
pub struct FirstFrameWait {
    protocol_ready_ms: Option<u64>,
    disconnect_ms: Option<u64>,
}
#[derive(Debug, PartialEq)]
pub enum FirstFrameDecision {
    Wait,
    Disconnect,
    ForceExit,
}
impl FirstFrameWait {
    pub fn observe(&mut self, elapsed_ms: u64, protocol_ready: bool) -> FirstFrameDecision {
        if let Some(sent) = self.disconnect_ms {
            return if elapsed_ms.saturating_sub(sent) >= 2_000 {
                FirstFrameDecision::ForceExit
            } else {
                FirstFrameDecision::Wait
            };
        }
        if protocol_ready {
            self.protocol_ready_ms.get_or_insert(elapsed_ms);
        }
        let deadline = self.protocol_ready_ms
            .map(|ready| ready.saturating_add(15_000).min(30_000))
            .unwrap_or(15_000);
        if elapsed_ms >= deadline {
            self.disconnect_ms = Some(elapsed_ms);
            FirstFrameDecision::Disconnect
        } else {
            FirstFrameDecision::Wait
        }
    }
}
impl Routes {
    pub fn healthy(&mut self, now: u64, rendered: bool) {
        if rendered {
            let since = *self.healthy_since.get_or_insert(now);
            if now.saturating_sub(since) >= 10_000 {
                self.failed.clear();
                self.started = None;
            }
        } else {
            self.healthy_since = None;
        }
    }
    pub fn admit(&mut self, now: u64, failed: FailedRoute, eligible: bool) -> bool {
        if !eligible || failed.route.is_empty() || failed.address.is_empty() {
            return false;
        }
        let started = *self.started.get_or_insert(now);
        if now.saturating_sub(started) >= 60_000
            || self.failed.len() >= 8
            || self.failed.contains(&failed)
        {
            return false;
        }
        self.failed.push(failed);
        true
    }
}
#[derive(Debug, PartialEq)]
pub enum Decision {
    Idle,
    Waiting,
    Launch,
    Exhausted,
}
impl Policy {
    pub fn admission_busy(&mut self, now:u64, native_attempt:u32)->bool {
        if !self.armed || self.attempts==0 || self.admission_attempt==Some(native_attempt)
            || self.started.is_none_or(|start|now.saturating_sub(start)>=60_000) { return false; }
        self.admission_attempt=Some(native_attempt);
        self.admission_waits=self.admission_waits.saturating_add(1);
        self.attempts-=1;
        self.next=Some(now.saturating_add(1500));
        true
    }
    pub fn armed(&self) -> bool {
        self.armed
    }
    pub fn prefer_session_retry(&self) -> bool {
        self.armed && self.attempts < 3
    }
    pub fn observe(&mut self, now: u64, alive: bool, rendered: bool, eligible: bool) -> Decision {
        if alive && rendered {
            self.armed = true;
            let healthy = *self.healthy.get_or_insert(now);
            if now.saturating_sub(healthy) >= 10_000 {
                self.attempts = 0;
                self.started = None;
                self.next = None;
            }
            return Decision::Idle;
        }
        self.healthy = None;
        if !self.armed || (!eligible && self.attempts == 0) {
            return Decision::Idle;
        }
        let started = *self.started.get_or_insert(now);
        if now.saturating_sub(started) >= 60_000 {
            return Decision::Exhausted;
        }
        if alive {
            return Decision::Waiting;
        }
        if self.attempts >= 3 {
            return Decision::Exhausted;
        }
        let delay = [500, 1500, 3000][self.attempts as usize];
        let next = *self.next.get_or_insert(now + delay);
        if now < next {
            return Decision::Waiting;
        }
        self.next = None;
        self.attempts += 1;
        Decision::Launch
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn admission_busy_does_not_spend_media_attempts_or_extend_the_recovery_deadline(){
        let mut p=Policy::default();assert!(!p.admission_busy(0,1));
        p.observe(0,true,true,false);p.observe(100,false,false,true);
        assert_eq!(p.observe(600,false,false,true),Decision::Launch);
        assert!(p.admission_busy(700,1));assert_eq!(p.attempts,0);
        assert!(!p.admission_busy(800,1));assert_eq!(p.admission_waits,1);
        assert_eq!(p.observe(2199,false,false,true),Decision::Waiting);
        assert_eq!(p.observe(2200,false,false,true),Decision::Launch);
        assert!(p.admission_busy(2300,2));
        assert_eq!(p.observe(3800,false,false,true),Decision::Launch);
        assert_eq!(p.attempts,1);
        assert!(!p.admission_busy(60_100,3));
        assert_eq!(p.observe(60_100,false,false,true),Decision::Exhausted);
    }
    #[test]
    fn transient_media_loss_retries_the_working_session_before_excluding_its_route(){
        let mut p=Policy::default();assert!(!p.prefer_session_retry());
        p.observe(0,true,true,false);assert!(p.prefer_session_retry());
        p.observe(100,false,false,true);assert_eq!(p.observe(600,false,false,true),Decision::Launch);
        assert!(p.prefer_session_retry());
        p.observe(1000,false,false,false);assert_eq!(p.observe(2500,false,false,false),Decision::Launch);
        assert!(p.prefer_session_retry());
        p.observe(3000,false,false,false);assert_eq!(p.observe(6000,false,false,false),Decision::Launch);
        assert!(!p.prefer_session_retry(),"Persistent media failure must still allow route fallback after the burst");
        p.observe(7000,true,true,false);p.observe(17000,true,true,false);assert!(p.prefer_session_retry());
    }
    #[test]
    fn slow_launch_does_not_consume_the_entire_post_protocol_frame_budget() {
        let mut wait = FirstFrameWait::default();
        assert_eq!(wait.observe(14_000, false), FirstFrameDecision::Wait);
        assert_eq!(wait.observe(14_500, true), FirstFrameDecision::Wait);
        assert_eq!(wait.observe(15_500, true), FirstFrameDecision::Wait);
        assert_eq!(wait.observe(29_499, true), FirstFrameDecision::Wait);
        assert_eq!(wait.observe(29_500, true), FirstFrameDecision::Disconnect);
        assert_eq!(wait.observe(31_499, true), FirstFrameDecision::Wait);
        assert_eq!(wait.observe(31_500, true), FirstFrameDecision::ForceExit);
    }
    #[test]
    fn repeated_progress_cannot_extend_the_absolute_deadline_or_revive_a_timeout() {
        let mut wait = FirstFrameWait::default();
        assert_eq!(wait.observe(16_000, true), FirstFrameDecision::Wait);
        assert_eq!(wait.observe(29_999, true), FirstFrameDecision::Wait);
        assert_eq!(wait.observe(30_000, true), FirstFrameDecision::Disconnect);
        assert_eq!(wait.observe(30_001, true), FirstFrameDecision::Wait);
        assert_eq!(wait.observe(32_000, true), FirstFrameDecision::ForceExit);
        let mut hung = FirstFrameWait::default();
        assert_eq!(hung.observe(15_000, false), FirstFrameDecision::Disconnect);
        assert_eq!(hung.observe(15_001, true), FirstFrameDecision::Wait);
        assert_eq!(hung.observe(17_000, true), FirstFrameDecision::ForceExit);
    }
    #[test]
    fn media_fallback_is_bounded_and_does_not_retry_identity_failures() {
        let mut routes = Routes::default();
        let lan = FailedRoute {
            route: "lan".into(),
            address: "192.168.0.104".into(),
        };
        assert!(!routes.admit(0, lan.clone(), false));
        assert!(routes.failed.is_empty());
        assert!(routes.admit(100, lan.clone(), true));
        assert!(!routes.admit(200, lan.clone(), true));
        assert!(routes.admit(
            300,
            FailedRoute {
                route: "relay".into(),
                address: "rhr:host".into()
            },
            true
        ));
        assert!(!routes.admit(
            60_100,
            FailedRoute {
                route: "public_direct".into(),
                address: "example.test".into()
            },
            true
        ));
        routes.healthy(61_000, true);
        routes.healthy(71_000, true);
        assert!(routes.admit(72_000, lan, true));
    }
    #[test]
    fn initial_failure_and_local_close_never_reconnect() {
        let mut p = Policy::default();
        assert_eq!(p.observe(0, false, false, true), Decision::Idle);
        p.observe(0, true, true, false);
        assert_eq!(p.observe(100, false, false, false), Decision::Idle);
    }
    #[test]
    fn desktop_interruption_recovers_with_a_bounded_burst() {
        let mut p = Policy::default();
        p.observe(0, true, true, false);
        assert_eq!(p.observe(100, false, false, true), Decision::Waiting);
        assert_eq!(p.observe(600, false, false, true), Decision::Launch);
        assert_eq!(p.observe(1000, true, false, false), Decision::Waiting);
        assert_eq!(p.observe(1100, false, false, false), Decision::Waiting);
        assert_eq!(p.observe(2600, false, false, false), Decision::Launch);
        p.observe(3000, false, false, false);
        assert_eq!(p.observe(6000, false, false, false), Decision::Launch);
        assert_eq!(p.observe(7000, false, false, false), Decision::Exhausted);
    }
    #[test]
    fn hung_retry_times_out_and_a_healthy_session_rearms() {
        let mut p = Policy::default();
        p.observe(0, true, true, false);
        p.observe(100, false, false, true);
        p.observe(600, false, false, true);
        assert_eq!(p.observe(60_100, true, false, false), Decision::Exhausted);
        p.observe(61_000, true, true, false);
        p.observe(71_000, true, true, false);
        assert_eq!(p.attempts, 0);
        assert_eq!(p.observe(72_000, false, false, true), Decision::Waiting);
    }
}
