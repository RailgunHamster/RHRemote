// A reconnect burst is bounded independently of individual transport timeouts.
// Only a previously rendered session can arm recovery; explicit disconnect drops it.
#[derive(Default)]
pub struct Policy {
    pub attempts: u8,
    armed: bool,
    started: Option<u64>,
    next: Option<u64>,
    healthy: Option<u64>,
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
    pub fn armed(&self) -> bool {
        self.armed
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
