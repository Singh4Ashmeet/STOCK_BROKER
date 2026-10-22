//! PyO3 wrapper for `sb_book::Book`.

use std::hash::{Hash, Hasher};

use pyo3::prelude::*;
use sb_book::{Book, BookConfig};
use sb_core::{BookSide, Level, Price, Qty};

use crate::error::PyBookError;
use crate::spec::PyInstrumentSpec;

type WalkMarketResult = (i64, i64, Vec<(i64, i64)>);

/// PyO3 exposed OrderBook.
#[pyclass(name = "OrderBook", module = "pybook")]
pub struct PyOrderBook {
    inner: Book,
}

#[pymethods]
impl PyOrderBook {
    /// Create a new `OrderBook` with given `InstrumentSpec` and window size in ticks.
    #[new]
    #[pyo3(signature = (spec, window_ticks = 2000))]
    pub fn new(spec: &PyInstrumentSpec, window_ticks: usize) -> PyResult<Self> {
        let config = BookConfig {
            window_ticks,
            recenter_threshold_ticks: window_ticks / 4,
            max_depth_levels: 50,
        };
        let inner = Book::new(config, spec.inner)
            .map_err(|e| PyBookError::new_err(format!("Failed to initialize Book: {e:?}")))?;
        Ok(Self { inner })
    }

    /// Apply an order-book snapshot.
    pub fn apply_snapshot(&mut self, bids: Vec<(i64, i64)>, asks: Vec<(i64, i64)>) {
        let bid_levels = parse_levels(bids).unwrap();
        let ask_levels = parse_levels(asks).unwrap();
        self.inner.apply_snapshot(&bid_levels, &ask_levels).unwrap();
    }

    /// Apply incremental order-book deltas.
    pub fn apply_delta(&mut self, bids: Vec<(i64, i64)>, asks: Vec<(i64, i64)>) {
        let bid_levels = parse_levels(bids).unwrap();
        let ask_levels = parse_levels(asks).unwrap();
        let _ = self.inner.apply_delta(&bid_levels, &ask_levels);
    }

    /// Best bid level `(price_units, qty_units)` or None if empty.
    pub fn best_bid(&self) -> Option<(i64, i64)> {
        self.inner
            .best_bid()
            .map(|l| (l.price.units(), l.qty.units()))
    }

    /// Best ask level `(price_units, qty_units)` or None if empty.
    pub fn best_ask(&self) -> Option<(i64, i64)> {
        self.inner
            .best_ask()
            .map(|l| (l.price.units(), l.qty.units()))
    }

    /// Mid price in price units as float, or None if book is not two-sided.
    pub fn mid(&self) -> Option<f64> {
        let bb = self.inner.best_bid()?;
        let ba = self.inner.best_ask()?;
        if bb.price >= ba.price {
            return None;
        }
        Some((bb.price.units() as f64 + ba.price.units() as f64) / 2.0)
    }

    /// Spread in price units, or None if book is not two-sided.
    pub fn spread(&self) -> Option<i64> {
        let bb = self.inner.best_bid()?;
        let ba = self.inner.best_ask()?;
        if bb.price >= ba.price {
            return None;
        }
        Some(ba.price.units() - bb.price.units())
    }

    /// Cumulative quantity across top $N$ levels on the specified side ("bid" or "ask").
    pub fn depth_qty(&self, side: &str, n: usize) -> i64 {
        let book_side = parse_side(side).unwrap();
        let qty = self.inner.depth_qty(book_side, n).unwrap();
        qty.units()
    }

    /// Top $N$ levels on the specified side as list of `(price_units, qty_units)`.
    pub fn levels(&self, side: &str, n: usize) -> Vec<(i64, i64)> {
        let book_side = parse_side(side).unwrap();
        let mut buf = vec![Level::new(Price::ZERO, Qty::ZERO); n];
        let written = self.inner.levels(book_side, n, &mut buf);
        buf.truncate(written);
        buf.into_iter()
            .map(|l| (l.price.units(), l.qty.units()))
            .collect()
    }

    /// Walk the book for a marketable market order.
    ///
    /// Side is "bid" (buying: walks asks) or "ask" (selling: walks bids).
    /// Returns `(total_filled_qty, total_notional, fills)` where fills is `[(price_units, qty_units)]`.
    pub fn walk_market(&self, side: &str, target_qty: i64) -> WalkMarketResult {
        let target = Qty::from_units(target_qty).unwrap();
        let book_side = match side.to_lowercase().as_str() {
            "buy" | "bid" => BookSide::Ask,  // Buying walks asks
            "sell" | "ask" => BookSide::Bid, // Selling walks bids
            other => {
                panic!("Invalid side: {other} (use 'buy' or 'sell')")
            }
        };
        let plan = self.inner.walk_market(book_side, target).unwrap();
        let fills: Vec<(i64, i64)> = plan
            .levels
            .iter()
            .map(|lvl| (lvl.price.units(), lvl.filled_qty.units()))
            .collect();
        let total_notional = i64::try_from(plan.total_notional).unwrap();
        (plan.total_filled.units(), total_notional, fills)
    }

    /// Deterministic 64-bit checksum fingerprint of the current book state.
    pub fn checksum(&self) -> u64 {
        compute_book_checksum(&self.inner)
    }

    fn __repr__(&self) -> String {
        let bb = self
            .best_bid()
            .map(|(p, q)| format!("{p}@{q}"))
            .unwrap_or_else(|| "none".to_string());
        let ba = self
            .best_ask()
            .map(|(p, q)| format!("{p}@{q}"))
            .unwrap_or_else(|| "none".to_string());
        format!("OrderBook(best_bid={bb}, best_ask={ba})")
    }
}

fn parse_side(side: &str) -> PyResult<BookSide> {
    match side.to_lowercase().as_str() {
        "bid" | "bids" | "buy" => Ok(BookSide::Bid),
        "ask" | "asks" | "sell" => Ok(BookSide::Ask),
        other => Err(PyBookError::new_err(format!(
            "Invalid side '{other}'. Expected 'bid' or 'ask'"
        ))),
    }
}

fn parse_levels(tuples: Vec<(i64, i64)>) -> PyResult<Vec<Level>> {
    let mut out = Vec::with_capacity(tuples.len());
    for (p, q) in tuples {
        let price = Price::from_units(p)
            .map_err(|e| PyBookError::new_err(format!("Invalid price {p}: {e:?}")))?;
        let qty = Qty::from_units(q)
            .map_err(|e| PyBookError::new_err(format!("Invalid qty {q}: {e:?}")))?;
        out.push(Level::new(price, qty));
    }
    Ok(out)
}

/// Compute a deterministic 64-bit fingerprint of the current book state.
///
/// Mirrors the logic in `sb_replay::ReplayEngine::compute_book_checksum`.
#[allow(clippy::arithmetic_side_effects)]
pub(crate) fn compute_book_checksum(book: &Book) -> u64 {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();

    if let Some(bid) = book.best_bid() {
        1u8.hash(&mut hasher);
        bid.price.units().hash(&mut hasher);
        bid.qty.units().hash(&mut hasher);
    } else {
        0u8.hash(&mut hasher);
    }

    if let Some(ask) = book.best_ask() {
        1u8.hash(&mut hasher);
        ask.price.units().hash(&mut hasher);
        ask.qty.units().hash(&mut hasher);
    } else {
        0u8.hash(&mut hasher);
    }

    let p_zero = match Price::from_units(0) {
        Ok(p) => p,
        Err(_) => return hasher.finish(),
    };
    let q_zero = match Qty::from_units(0) {
        Ok(q) => q,
        Err(_) => return hasher.finish(),
    };
    let dummy = Level::new(p_zero, q_zero);

    let mut bid_levels = [dummy; 50];
    let bid_count = book.levels(BookSide::Bid, 50, &mut bid_levels);
    bid_count.hash(&mut hasher);
    for l in bid_levels.iter().take(bid_count) {
        l.price.units().hash(&mut hasher);
        l.qty.units().hash(&mut hasher);
    }

    let mut ask_levels = [dummy; 50];
    let ask_count = book.levels(BookSide::Ask, 50, &mut ask_levels);
    ask_count.hash(&mut hasher);
    for l in ask_levels.iter().take(ask_count) {
        l.price.units().hash(&mut hasher);
        l.qty.units().hash(&mut hasher);
    }

    hasher.finish()
}
