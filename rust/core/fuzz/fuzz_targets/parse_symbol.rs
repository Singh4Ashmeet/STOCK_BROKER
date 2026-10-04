//! Fuzz target for parse_symbol

#![no_main]

use libfuzzer_sys::fuzz_target;
use sb_core::parse_symbol;

fuzz_target!(|data: &[u8]| {
    if let Ok(s) = std::str::from_utf8(data) {
        let _ = parse_symbol(s);
    }
});
