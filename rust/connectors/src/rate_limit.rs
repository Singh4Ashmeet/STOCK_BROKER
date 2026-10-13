//! Rate limiting utilities for REST requests and WebSocket control messages.

use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

/// Token-bucket rate limiter.
#[derive(Debug, Clone)]
pub struct RateLimiter {
    state: Arc<Mutex<RateLimiterState>>,
}

#[derive(Debug)]
struct RateLimiterState {
    capacity: f64,
    tokens: f64,
    refill_rate_per_sec: f64,
    last_refill: Instant,
}

impl RateLimiter {
    /// Create a new `RateLimiter` with a given capacity (burst) and refill rate per second.
    #[must_use]
    pub fn new(capacity: u32, refill_rate_per_sec: f64) -> Self {
        Self {
            state: Arc::new(Mutex::new(RateLimiterState {
                capacity: f64::from(capacity),
                tokens: f64::from(capacity),
                refill_rate_per_sec,
                last_refill: Instant::now(),
            })),
        }
    }

    /// Try to acquire `tokens` without waiting. Returns true if acquired.
    #[allow(clippy::float_arithmetic)]
    pub async fn try_acquire(&self, tokens: f64) -> bool {
        let mut state = self.state.lock().await;
        state.refill();
        if state.tokens >= tokens {
            state.tokens -= tokens;
            true
        } else {
            false
        }
    }

    /// Wait until `tokens` are available and consume them.
    #[allow(
        clippy::float_arithmetic,
        clippy::as_conversions,
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss
    )]
    pub async fn acquire(&self, tokens: f64) {
        loop {
            let wait_duration = {
                let mut state = self.state.lock().await;
                state.refill();
                if state.tokens >= tokens {
                    state.tokens -= tokens;
                    return;
                }
                let needed = tokens - state.tokens;
                let secs = needed / state.refill_rate_per_sec;
                Duration::from_secs_f64(secs)
            };
            tokio::time::sleep(wait_duration).await;
        }
    }
}

impl RateLimiterState {
    #[allow(clippy::float_arithmetic)]
    fn refill(&mut self) {
        let now = Instant::now();
        let elapsed = now.duration_since(self.last_refill).as_secs_f64();
        let added = elapsed * self.refill_rate_per_sec;
        self.tokens = (self.tokens + added).min(self.capacity);
        self.last_refill = now;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn try_acquire_basics() {
        let limiter = RateLimiter::new(10, 5.0);
        assert!(limiter.try_acquire(5.0).await);
        assert!(limiter.try_acquire(5.0).await);
        assert!(!limiter.try_acquire(1.0).await);
    }

    #[tokio::test]
    async fn acquire_waits_for_refill() {
        let limiter = RateLimiter::new(1, 10.0); // 10 tokens/sec = 100ms per token
        assert!(limiter.try_acquire(1.0).await);
        let start = Instant::now();
        limiter.acquire(1.0).await;
        assert!(start.elapsed() >= Duration::from_millis(50));
    }
}
