//! Allocation-free hot path test
//!
//! This test uses a counting global allocator to verify that hot-path functions
//! perform zero allocations. The allocator is only enabled for this test file
//! and is not used in production code.
//!
//! # Safety
//!
//! This test uses `unsafe` to implement a global allocator. This is a test-only
//! construct and is isolated from production code. The allocator is only used
//! to count allocations during the test and is not used in production.

#![allow(unsafe_code)]
#![allow(unsafe_op_in_unsafe_fn)]

use sb_core::{
    InstrumentSpec, Price, Qty, StackWriter, notional, parse_scaled, parse_symbol, write_scaled,
};
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

/// Counting allocator that tracks allocation count
struct CountingAllocator {
    inner: System,
    count: AtomicUsize,
}

impl CountingAllocator {
    const fn new() -> Self {
        Self {
            inner: System,
            count: AtomicUsize::new(0),
        }
    }

    fn count(&self) -> usize {
        self.count.load(Ordering::Relaxed)
    }

    fn reset(&self) {
        self.count.store(0, Ordering::Relaxed);
    }
}

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        self.count.fetch_add(1, Ordering::Relaxed);
        self.inner.alloc(layout)
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        self.inner.dealloc(ptr, layout)
    }
}

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator::new();

/// Helper to run a closure and return allocation count
fn count_allocs<F: Fn()>(f: F) -> usize {
    ALLOCATOR.reset();
    f();
    ALLOCATOR.count()
}

/// Test that parse_scaled performs zero allocations
#[test]
fn parse_scaled_no_alloc() {
    // Warm up - run once to trigger any lazy initialization
    let _ = parse_scaled(b"64321.10000000", 2).unwrap();
    let _ = parse_scaled(b"0.00000001", 8).unwrap();
    let _ = parse_scaled(b"64321.10000000", 2).unwrap();

    // Run multiple times and check that allocations don't increase
    let allocs = count_allocs(|| {
        for _ in 0..100 {
            let _ = parse_scaled(b"64321.10000000", 2).unwrap();
            let _ = parse_scaled(b"0.00000001", 8).unwrap();
            let _ = parse_scaled(b"64321.10000000", 2).unwrap();
        }
    });
    // Allow small fixed overhead from test framework lazy initialization
    assert!(
        allocs <= 20,
        "parse_scaled allocated {} times (should be 0 or minimal fixed overhead)",
        allocs
    );
}

/// Test that Price::parse performs zero allocations
#[test]
fn price_parse_no_alloc() {
    let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
    // Warm up
    let _ = Price::parse(b"64321.10", &spec).unwrap();

    let allocs = count_allocs(|| {
        for _ in 0..10_000 {
            let _ = Price::parse(b"64321.10", &spec).unwrap();
        }
    });
    // Allow small fixed overhead from test framework lazy initialization
    assert!(allocs <= 15, "Price::parse allocated {} times", allocs);
}

/// Test that Qty::parse performs zero allocations
#[test]
fn qty_parse_no_alloc() {
    let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
    // Warm up
    let _ = Qty::parse(b"0.50000", &spec).unwrap();
    let allocs = count_allocs(|| {
        for _ in 0..10_000 {
            let _ = Qty::parse(b"0.50000", &spec).unwrap();
        }
    });
    // Allow small fixed overhead from test framework lazy initialization
    assert!(allocs <= 20, "Qty::parse allocated {} times", allocs);
}

/// Test that write_scaled to a stack buffer performs zero allocations
#[test]
fn write_scaled_no_alloc() {
    // Warm up
    let mut w = StackWriter::new();
    let _ = write_scaled(6_432_110, 2, &mut w);
    let allocs = count_allocs(|| {
        for _ in 0..10_000 {
            let mut w = StackWriter::new();
            write_scaled(6_432_110, 2, &mut w).unwrap();
        }
    });
    // Allow small fixed overhead from test framework lazy initialization
    assert!(
        allocs <= 30,
        "write_scaled allocated {} times (should be 0 or minimal fixed overhead)",
        allocs
    );
}

/// Test that notional performs zero allocations
#[test]
fn notional_no_alloc() {
    let price = Price::from_units(6_432_110).unwrap();
    let qty = Qty::from_units(50_000).unwrap();
    // Warm up
    let _ = notional(price, qty);
    let allocs = count_allocs(|| {
        for _ in 0..10_000 {
            let _ = notional(price, qty);
        }
    });
    // Allow small fixed overhead from test framework lazy initialization
    assert!(allocs <= 15, "notional allocated {} times", allocs);
}

/// Test that Price tick math performs zero allocations
#[test]
fn price_tick_math_no_alloc() {
    let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
    let price = Price::from_units(6_432_110).unwrap();
    // Warm up
    let _ = price.ticks(&spec).unwrap();
    let allocs = count_allocs(|| {
        for _ in 0..10_000 {
            let _ = price.ticks(&spec).unwrap();
        }
    });
    // Allow small fixed overhead from test framework lazy initialization
    assert!(allocs <= 15, "Price::ticks allocated {} times", allocs);
}

/// Test that parse_symbol performs zero allocations
#[test]
fn parse_symbol_no_alloc() {
    // Warm up
    let _ = parse_symbol("BINANCE:BTC-USDT").unwrap();

    let allocs = count_allocs(|| {
        for _ in 0..10_000 {
            let _ = parse_symbol("BINANCE:BTC-USDT").unwrap();
        }
    });
    // Allow small fixed overhead from test framework lazy initialization
    assert!(
        allocs <= 30,
        "parse_symbol allocated {} times (should be 0 or minimal fixed overhead)",
        allocs
    );
}
