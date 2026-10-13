//! Zero-allocation verification for sb-metrics hot path.
//!
//! Uses a custom CountingAllocator to prove that none of the metric
//! update or snapshot functions allocate memory on the hot path after construction.

#![allow(unsafe_code)]
#![allow(unsafe_op_in_unsafe_fn)]

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

use sb_book::{Book, BookConfig};
use sb_core::{Aggressor, InstrumentSpec, Level, Price, Qty, TsNs};
use sb_metrics::engine::{MetricsConfig, MetricsEngine};
use sb_metrics::{compute_bbo, compute_depth};

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

fn spec() -> InstrumentSpec {
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

fn book_config() -> BookConfig {
    BookConfig::new(1024, 100, 50).unwrap()
}

fn metrics_config() -> MetricsConfig {
    MetricsConfig {
        depth_levels: 5,
        vwap_window_ns: 60_000_000_000,
        trade_flow_window_ns: 60_000_000_000,
        vwap_capacity: 1024,
        trade_flow_capacity: 1024,
    }
}

#[test]
fn metrics_hot_path_zero_alloc() {
    let bid = Level::new(
        Price::from_units(1000).unwrap(),
        Qty::from_units(100).unwrap(),
    );
    let ask = Level::new(
        Price::from_units(1010).unwrap(),
        Qty::from_units(200).unwrap(),
    );

    // Warm up pure functions
    let _ = compute_bbo(Some(bid), Some(ask));
    let _ = compute_depth(
        Qty::from_units(500).unwrap(),
        Qty::from_units(600).unwrap(),
        5,
    );

    ALLOCATOR.reset();
    for _ in 0..1000 {
        let bbo = compute_bbo(Some(bid), Some(ask));
        assert!(bbo.valid);
    }
    let allocs = ALLOCATOR.count();
    assert_eq!(allocs, 0, "compute_bbo must not allocate on hot path");

    ALLOCATOR.reset();
    for _ in 0..1000 {
        let depth = compute_depth(
            Qty::from_units(500).unwrap(),
            Qty::from_units(600).unwrap(),
            5,
        );
        assert!(depth.valid);
    }
    let allocs = ALLOCATOR.count();
    assert_eq!(allocs, 0, "compute_depth must not allocate on hot path");

    // Engine tests
    let s = spec();
    let bc = book_config();
    let mc = metrics_config();

    let mut book = Book::new(bc, s).unwrap();
    let mut engine = MetricsEngine::new(mc);

    // Initial snapshot populates the book and warms internal buffers
    let _ = book.apply_snapshot(&[bid], &[ask]);
    engine.on_book_update(&book);

    let price = Price::from_units(1005).unwrap();
    let qty = Qty::from_units(500).unwrap();
    let ts = TsNs::from_nanos(1_000_000_000);

    // Warm up trade
    engine.on_trade(ts, price, qty, Aggressor::Buy);

    // Test on_book_update hot path
    ALLOCATOR.reset();
    for _ in 0..1000 {
        engine.on_book_update(&book);
    }
    let allocs = ALLOCATOR.count();
    assert_eq!(allocs, 0, "on_book_update must not allocate");

    // Test on_trade hot path
    ALLOCATOR.reset();
    for i in 1..=1000i64 {
        let ts_next = TsNs::from_nanos(1_000_000_000 + i * 1_000_000);
        engine.on_trade(ts_next, price, qty, Aggressor::Sell);
    }
    let allocs = ALLOCATOR.count();
    assert_eq!(allocs, 0, "on_trade must not allocate");

    // Test snapshot read hot path
    ALLOCATOR.reset();
    for _ in 0..1000 {
        let snap = engine.snapshot();
        assert!(snap.bbo.valid);
    }
    let allocs = ALLOCATOR.count();
    assert_eq!(allocs, 0, "snapshot() must not allocate");
}
