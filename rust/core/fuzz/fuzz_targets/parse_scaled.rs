//! Fuzz target for parse_scaled

#![no_main]

use libfuzzer_sys::fuzz_target;
use sb_core::parse_scaled;

fuzz_target!(|data: &[u8]| {
    // Try parsing with various scales
    for scale in 0..=18 {
        let _ = parse_scaled(data, scale);
    }
});