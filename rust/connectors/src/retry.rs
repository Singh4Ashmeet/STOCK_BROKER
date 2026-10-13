//! Exponential backoff with full jitter for connection retry.

use std::time::Duration;

/// Configuration for exponential backoff with full jitter.
#[derive(Debug, Clone, Copy)]
pub struct BackoffConfig {
    /// Minimum initial backoff duration
    pub min_backoff: Duration,
    /// Maximum backoff duration cap
    pub max_backoff: Duration,
    /// Multiplier factor per attempt (typically 2.0)
    pub factor: f64,
}

impl Default for BackoffConfig {
    fn default() -> Self {
        Self {
            min_backoff: Duration::from_millis(250),
            max_backoff: Duration::from_secs(30),
            factor: 2.0,
        }
    }
}

/// Exponential backoff generator with full jitter.
#[derive(Debug, Clone)]
pub struct Backoff {
    config: BackoffConfig,
    attempt: u32,
}

impl Backoff {
    /// Create a new `Backoff` with default configuration.
    #[must_use]
    pub fn new() -> Self {
        Self::with_config(BackoffConfig::default())
    }

    /// Create a new `Backoff` with explicit configuration.
    #[must_use]
    pub fn with_config(config: BackoffConfig) -> Self {
        Self { config, attempt: 0 }
    }

    /// Reset attempt counter back to 0 (e.g. after a connection was stable).
    pub fn reset(&mut self) {
        self.attempt = 0;
    }

    /// Get current attempt count.
    #[must_use]
    pub fn attempt(&self) -> u32 {
        self.attempt
    }

    /// Compute the next delay using full jitter and increment the attempt counter.
    ///
    /// Formula:
    /// `cap = min(max_backoff, min_backoff * factor^attempt)`
    /// `jittered_delay = uniform_random(0, cap)` (or with a lower bound `min_backoff / 2`)
    pub fn next_delay(&mut self) -> Duration {
        let delay = self.compute_delay_with_rng(&mut rand::rng());
        self.attempt = self.attempt.saturating_add(1);
        delay
    }

    /// Internal delay computation with an arbitrary RNG (for deterministic testing).
    #[allow(
        clippy::as_conversions,
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        clippy::float_arithmetic
    )]
    pub fn compute_delay_with_rng<R: rand::Rng + ?Sized>(&self, rng: &mut R) -> Duration {
        let min_ms = self.config.min_backoff.as_millis() as f64;
        let max_ms = self.config.max_backoff.as_millis() as f64;

        // Exponential backoff ceiling: min_ms * factor^attempt
        let exp_factor = self.config.factor.powi(self.attempt.min(16) as i32);
        let ceiling_ms = (min_ms * exp_factor).min(max_ms);

        // Full jitter: uniform between min_ms and ceiling_ms (or 0 and ceiling_ms)
        let chosen_ms = rng.random_range(min_ms..=ceiling_ms);
        Duration::from_millis(chosen_ms as u64)
    }
}

impl Default for Backoff {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::SeedableRng;
    use rand::rngs::StdRng;

    #[test]
    fn backoff_caps_at_max() {
        let config = BackoffConfig {
            min_backoff: Duration::from_millis(100),
            max_backoff: Duration::from_millis(1000),
            factor: 2.0,
        };
        let mut backoff = Backoff::with_config(config);
        for _ in 0..20 {
            let delay = backoff.next_delay();
            assert!(delay <= config.max_backoff);
            assert!(delay >= config.min_backoff);
        }
    }

    #[test]
    fn backoff_reset() {
        let mut backoff = Backoff::new();
        let _ = backoff.next_delay();
        let _ = backoff.next_delay();
        assert_eq!(backoff.attempt(), 2);
        backoff.reset();
        assert_eq!(backoff.attempt(), 0);
    }

    #[test]
    fn deterministic_backoff_step() {
        let config = BackoffConfig {
            min_backoff: Duration::from_millis(100),
            max_backoff: Duration::from_millis(1000),
            factor: 2.0,
        };
        let backoff = Backoff::with_config(config);
        let mut rng = StdRng::seed_from_u64(12345);
        let delay = backoff.compute_delay_with_rng(&mut rng);
        assert!(delay >= Duration::from_millis(100));
        assert!(delay <= Duration::from_millis(1000));
    }
}
