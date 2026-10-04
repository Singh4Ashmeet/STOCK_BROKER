//! Timestamp and timing types

use core::fmt;
use core::time::Duration;
use std::time::Instant;

use crate::error::CoreError;

/// UTC timestamp in nanoseconds since Unix epoch.
///
/// Uses i64 to allow for timestamps before epoch (mapped to 0).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct TsNs(i64);

impl TsNs {
    /// Create from nanoseconds since Unix epoch.
    ///
    /// Values before epoch are clamped to 0.
    #[must_use]
    pub fn from_nanos(nanos: i64) -> Self {
        Self(nanos.max(0))
    }

    /// Create from milliseconds since Unix epoch.
    ///
    /// # Errors
    ///
    /// Returns `CoreError::TimestampOutOfRange` if multiplication overflows.
    pub fn from_millis(millis: i64) -> Result<Self, CoreError> {
        let nanos = millis
            .checked_mul(1_000_000)
            .ok_or(CoreError::TimestampOutOfRange)?;
        Ok(Self::from_nanos(nanos))
    }

    /// Get the timestamp as nanoseconds.
    #[must_use]
    pub fn as_nanos(self) -> i64 {
        self.0
    }

    /// Get the timestamp as milliseconds (truncated).
    #[must_use]
    pub fn as_millis(self) -> i64 {
        self.0 / 1_000_000
    }

    /// Compute the difference in nanoseconds between this and an earlier timestamp.
    ///
    /// Returns 0 if `earlier` is after `self`.
    #[must_use]
    pub fn delta_ns(self, earlier: Self) -> i64 {
        if self.0 >= earlier.0 {
            self.0 - earlier.0
        } else {
            0
        }
    }

    /// Create from a `Duration` since Unix epoch.
    #[must_use]
    pub fn from_duration(d: Duration) -> Self {
        Self::from_nanos(i64::try_from(d.as_nanos()).unwrap_or(i64::MAX))
    }
}

impl fmt::Display for TsNs {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// Timing information carried on every streamed market message.
///
/// Mirrors the proto `Timing` message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Timing {
    /// Venue event time (often milliseconds from exchange, converted to ns)
    pub exchange_ts: TsNs,
    /// Local time when the frame was received by the Rust process
    pub recv_ts: TsNs,
    /// Local time just before the message was handed to the gRPC stream
    pub publish_ts: TsNs,
}

impl Timing {
    /// Internal processing latency in nanoseconds (publish - recv).
    #[must_use]
    pub fn internal_latency_ns(&self) -> i64 {
        self.publish_ts.delta_ns(self.recv_ts)
    }

    /// End-to-end latency from exchange to publish (wall clock, indicative).
    #[must_use]
    pub fn e2e_latency_ns(&self) -> i64 {
        self.publish_ts.delta_ns(self.exchange_ts)
    }
}

/// Clock trait for obtaining timestamps.
///
/// Hot logic takes timestamps as arguments; only the reader calls a Clock.
pub trait Clock: Send + Sync {
    /// Wall-clock UTC nanoseconds since epoch.
    fn wall_ns(&self) -> TsNs;

    /// Monotonic nanoseconds since an arbitrary base (for measuring intervals).
    fn mono_ns(&self) -> u64;
}

/// System clock implementation.
#[derive(Debug)]
pub struct SystemClock {
    mono_base: Instant,
}

impl SystemClock {
    /// Create a new system clock, capturing the monotonic base.
    #[must_use]
    pub fn new() -> Self {
        Self {
            mono_base: Instant::now(),
        }
    }
}

impl Default for SystemClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for SystemClock {
    fn wall_ns(&self) -> TsNs {
        match std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH) {
            Ok(d) => TsNs::from_nanos(i64::try_from(d.as_nanos()).unwrap_or(i64::MAX)),
            Err(_) => TsNs(0), // before epoch
        }
    }

    fn mono_ns(&self) -> u64 {
        u64::try_from(self.mono_base.elapsed().as_nanos()).unwrap_or(u64::MAX)
    }
}

/// Manual clock for testing (deterministic time control).
#[derive(Debug)]
pub struct ManualClock {
    wall: std::sync::Mutex<TsNs>,
    mono: std::sync::Mutex<u64>,
}

impl ManualClock {
    /// Create a new manual clock at epoch.
    #[must_use]
    pub fn new() -> Self {
        Self {
            wall: std::sync::Mutex::new(TsNs(0)),
            mono: std::sync::Mutex::new(0),
        }
    }

    /// Set the wall clock to a specific timestamp.
    pub fn set_wall(&self, ts: TsNs) {
        let mut guard = self.wall.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = ts;
    }

    /// Advance the monotonic clock by nanoseconds.
    pub fn advance_mono(&self, ns: u64) {
        let mut guard = self.mono.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = guard.checked_add(ns).unwrap_or(u64::MAX);
    }

    /// Advance both wall and monotonic clocks.
    pub fn advance(&self, wall_ns: i64, mono_ns: u64) {
        *self.wall.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = TsNs::from_nanos(wall_ns);
        let mut guard = self.mono.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = guard.checked_add(mono_ns).unwrap_or(u64::MAX);
    }
}

impl Default for ManualClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for ManualClock {
    fn wall_ns(&self) -> TsNs {
        *self.wall.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn mono_ns(&self) -> u64 {
        *self.mono.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tsns_basic() {
        let ts = TsNs::from_nanos(1_000_000_000);
        assert_eq!(ts.as_nanos(), 1_000_000_000);
        assert_eq!(ts.as_millis(), 1_000);
    }

    #[test]
    fn tsns_before_epoch() {
        let ts = TsNs::from_nanos(-100);
        assert_eq!(ts.as_nanos(), 0);
    }

    #[test]
    fn tsns_from_millis() {
        let ts = TsNs::from_millis(1_000).unwrap();
        assert_eq!(ts.as_nanos(), 1_000_000_000);
    }

    #[test]
    fn tsns_overflow() {
        // i64::MAX / 1_000_000 is safe
        assert!(TsNs::from_millis(i64::MAX / 1_000_000).is_ok());
        assert!(TsNs::from_millis(i64::MAX).is_err());
    }

    #[test]
    fn tsns_delta() {
        let a = TsNs::from_nanos(1_000_000_000);
        let b = TsNs::from_nanos(2_000_000_000);
        assert_eq!(b.delta_ns(a), 1_000_000_000);
        // Saturates
        assert_eq!(a.delta_ns(b), 0);
    }

    #[test]
    fn timing_latency() {
        let timing = Timing {
            exchange_ts: TsNs::from_nanos(1_000_000_000),
            recv_ts: TsNs::from_nanos(1_000_100_000),
            publish_ts: TsNs::from_nanos(1_000_150_000),
        };
        assert_eq!(timing.internal_latency_ns(), 50_000);
        assert_eq!(timing.e2e_latency_ns(), 150_000);
    }

    #[test]
    fn manual_clock() {
        let clock = ManualClock::new();
        clock.set_wall(TsNs::from_nanos(42));
        clock.advance_mono(100);
        assert_eq!(clock.wall_ns(), TsNs::from_nanos(42));
        assert_eq!(clock.mono_ns(), 100);
    }
}
