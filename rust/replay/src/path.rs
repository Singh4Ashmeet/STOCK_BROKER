//! Path resolution and hour partitioning utilities for the event log.
//!
//! Layout per ADR-0006 & docs/05-DATA-MODEL.md:
//! `data/raw/venue={VENUE}/symbol={SYMBOL}/date={YYYY-MM-DD}/hour={HH}/part-{NNNN}.pbz`

use std::path::{Path, PathBuf};

/// Hour partition identifier for roll-over tracking.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct HourBucket {
    /// Year (e.g. 2026)
    pub year: i32,
    /// Month (1..=12)
    pub month: u32,
    /// Day of month (1..=31)
    pub day: u32,
    /// Hour of day (0..=23)
    pub hour: u32,
}

impl HourBucket {
    /// Compute the UTC `HourBucket` for a given nanosecond timestamp.
    #[must_use]
    #[allow(
        clippy::as_conversions,
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss
    )]
    pub fn from_ts_ns(ts_ns: i64) -> Self {
        let secs = ts_ns.max(0) / 1_000_000_000;
        let days = secs / 86400;
        let day_secs = secs % 86400;
        let hour = (day_secs / 3600) as u32;

        // Civil calendar date from days since 1970-01-01
        let z = days + 719_468;
        let era = (if z >= 0 { z } else { z - 146_096 }) / 146_097;
        let doe = (z - era * 146_097) as u32;
        let yoe = (doe - doe / 1020 + doe / 1461 - doe / 146_096) / 365;
        let y = yoe as i64 + era * 400;
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        let mp = (5 * doy + 2) / 153;
        let d = doy - (153 * mp + 2) / 5 + 1;
        let m = if mp < 10 { mp + 3 } else { mp - 9 };
        let year = (if m <= 2 { y + 1 } else { y }) as i32;

        Self {
            year,
            month: m,
            day: d,
            hour,
        }
    }

    /// Format date as "YYYY-MM-DD".
    #[must_use]
    pub fn date_string(&self) -> String {
        format!("{:04}-{:02}-{:02}", self.year, self.month, self.day)
    }

    /// Format hour as "HH".
    #[must_use]
    pub fn hour_string(&self) -> String {
        format!("{:02}", self.hour)
    }
}

/// Helper to construct canonical event log directory and file paths.
pub struct LogPathBuilder {
    base_dir: PathBuf,
    venue: String,
}

impl LogPathBuilder {
    /// Create a new `LogPathBuilder`.
    #[must_use]
    pub fn new<P: Into<PathBuf>>(base_dir: P, venue: &str) -> Self {
        Self {
            base_dir: base_dir.into(),
            venue: venue.to_string(),
        }
    }

    /// Return directory path for a specific symbol, date, and hour:
    /// `{base}/venue={VENUE}/symbol={SYMBOL}/date={YYYY-MM-DD}/hour={HH}`
    #[must_use]
    pub fn hour_dir(&self, symbol: &str, bucket: HourBucket) -> PathBuf {
        self.base_dir
            .join(format!("venue={}", self.venue))
            .join(format!("symbol={}", symbol))
            .join(format!("date={}", bucket.date_string()))
            .join(format!("hour={}", bucket.hour_string()))
    }

    /// Return the final completed `.pbz` file path for a part index:
    /// `.../hour={HH}/part-{NNNN}.pbz`
    #[must_use]
    pub fn final_file_path(&self, symbol: &str, bucket: HourBucket, part_idx: u32) -> PathBuf {
        self.hour_dir(symbol, bucket)
            .join(format!("part-{:04}.pbz", part_idx))
    }

    /// Return the temporary `.part` file path used during writing:
    /// `.../hour={HH}/part-{NNNN}.pbz.part`
    #[must_use]
    pub fn part_file_path(&self, symbol: &str, bucket: HourBucket, part_idx: u32) -> PathBuf {
        self.hour_dir(symbol, bucket)
            .join(format!("part-{:04}.pbz.part", part_idx))
    }
}

/// Atomically finalize a `.part` file to its target path by renaming it.
pub fn finalize_part_file(part_path: &Path, final_path: &Path) -> std::io::Result<()> {
    if let Some(parent) = final_path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::rename(part_path, final_path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hour_bucket_calculation() {
        // 2026-10-09 12:30:00 UTC = 1791549000 seconds
        let ts_ns = 1_791_549_000_000_000_000i64;
        let bucket = HourBucket::from_ts_ns(ts_ns);
        assert_eq!(bucket.year, 2026);
        assert_eq!(bucket.month, 10);
        assert_eq!(bucket.day, 9);
        assert_eq!(bucket.hour, 12);
        assert_eq!(bucket.date_string(), "2026-10-09");
        assert_eq!(bucket.hour_string(), "12");
    }

    #[test]
    fn test_log_path_builder() {
        let builder = LogPathBuilder::new("data/raw", "BINANCE");
        let bucket = HourBucket {
            year: 2026,
            month: 10,
            day: 9,
            hour: 5,
        };
        let final_path = builder.final_file_path("BTC-USDT", bucket, 0);
        let path_str = final_path.to_string_lossy().replace('\\', "/");
        assert_eq!(
            path_str,
            "data/raw/venue=BINANCE/symbol=BTC-USDT/date=2026-10-09/hour=05/part-0000.pbz"
        );
    }
}
