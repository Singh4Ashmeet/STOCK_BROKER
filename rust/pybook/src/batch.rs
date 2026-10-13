//! Columnar batch container and NumPy/Arrow array interchange.

use numpy::{PyArray1, PyArray2, PyArrayMethods};
use pyo3::prelude::*;
use pyo3::types::PyDict;

/// Columnar event batch containing up to $N$ market events and derived book states.
#[pyclass(name = "EventBatch", module = "pybook")]
#[derive(Debug, Clone)]
pub struct PyEventBatch {
    pub length: usize,
    pub depth_levels: usize,
    pub recv_ts: Vec<i64>,
    pub exchange_ts: Vec<i64>,
    pub event_kind: Vec<u8>,
    pub book_valid: Vec<bool>,
    pub epoch: Vec<u64>,
    pub seq: Vec<u64>,
    pub best_bid_price: Vec<i64>,
    pub best_bid_qty: Vec<i64>,
    pub best_ask_price: Vec<i64>,
    pub best_ask_qty: Vec<i64>,
    pub spread: Vec<i64>,
    pub mid: Vec<f64>,
    pub microprice: Vec<f64>,
    pub imbalance: Vec<f64>,
    pub vwap: Vec<f64>,
    pub trade_flow_imbalance: Vec<f64>,
    pub bid_prices: Vec<i64>,
    pub bid_qtys: Vec<i64>,
    pub ask_prices: Vec<i64>,
    pub ask_qtys: Vec<i64>,
    pub trade_price: Vec<i64>,
    pub trade_qty: Vec<i64>,
    pub trade_aggressor: Vec<i8>,
}

impl PyEventBatch {
    /// Create a new pre-allocated batch buffer for up to `capacity` events.
    pub fn with_capacity(capacity: usize, depth_levels: usize) -> Self {
        Self {
            length: 0,
            depth_levels,
            recv_ts: Vec::with_capacity(capacity),
            exchange_ts: Vec::with_capacity(capacity),
            event_kind: Vec::with_capacity(capacity),
            book_valid: Vec::with_capacity(capacity),
            epoch: Vec::with_capacity(capacity),
            seq: Vec::with_capacity(capacity),
            best_bid_price: Vec::with_capacity(capacity),
            best_bid_qty: Vec::with_capacity(capacity),
            best_ask_price: Vec::with_capacity(capacity),
            best_ask_qty: Vec::with_capacity(capacity),
            spread: Vec::with_capacity(capacity),
            mid: Vec::with_capacity(capacity),
            microprice: Vec::with_capacity(capacity),
            imbalance: Vec::with_capacity(capacity),
            vwap: Vec::with_capacity(capacity),
            trade_flow_imbalance: Vec::with_capacity(capacity),
            bid_prices: Vec::with_capacity(capacity * depth_levels),
            bid_qtys: Vec::with_capacity(capacity * depth_levels),
            ask_prices: Vec::with_capacity(capacity * depth_levels),
            ask_qtys: Vec::with_capacity(capacity * depth_levels),
            trade_price: Vec::with_capacity(capacity),
            trade_qty: Vec::with_capacity(capacity),
            trade_aggressor: Vec::with_capacity(capacity),
        }
    }
}

#[pymethods]
impl PyEventBatch {
    /// Number of events in this batch.
    #[getter]
    pub fn length(&self) -> usize {
        self.length
    }

    fn __len__(&self) -> usize {
        self.length
    }

    /// Receive timestamps in nanoseconds UTC.
    #[getter]
    pub fn recv_ts<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.recv_ts)
    }

    /// Exchange timestamps in nanoseconds UTC.
    #[getter]
    pub fn exchange_ts<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.exchange_ts)
    }

    /// Event kind tag: 1=Snapshot, 2=Delta, 3=Trade, 4=GapMarker, 5=ResyncMarker.
    #[getter]
    pub fn event_kind<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<u8>> {
        PyArray1::from_slice_bound(py, &self.event_kind)
    }

    /// Boolean book validity status after each event.
    #[getter]
    pub fn book_valid<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<bool>> {
        PyArray1::from_slice_bound(py, &self.book_valid)
    }

    /// Order book epoch number after each event.
    #[getter]
    pub fn epoch<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<u64>> {
        PyArray1::from_slice_bound(py, &self.epoch)
    }

    /// Order book sequence number after each event.
    #[getter]
    pub fn seq<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<u64>> {
        PyArray1::from_slice_bound(py, &self.seq)
    }

    /// Best bid price units (0 if empty).
    #[getter]
    pub fn best_bid_price<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.best_bid_price)
    }

    /// Best bid quantity units (0 if empty).
    #[getter]
    pub fn best_bid_qty<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.best_bid_qty)
    }

    /// Best ask price units (0 if empty).
    #[getter]
    pub fn best_ask_price<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.best_ask_price)
    }

    /// Best ask quantity units (0 if empty).
    #[getter]
    pub fn best_ask_qty<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.best_ask_qty)
    }

    /// Spread in price units (0 if crossed or empty).
    #[getter]
    pub fn spread<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.spread)
    }

    /// Mid price in price units as float (0.0 if invalid).
    #[getter]
    pub fn mid<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<f64>> {
        PyArray1::from_slice_bound(py, &self.mid)
    }

    /// Microprice in price units as float (0.0 if invalid).
    #[getter]
    pub fn microprice<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<f64>> {
        PyArray1::from_slice_bound(py, &self.microprice)
    }

    /// Order book imbalance ratio in [-1.0, 1.0].
    #[getter]
    pub fn imbalance<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<f64>> {
        PyArray1::from_slice_bound(py, &self.imbalance)
    }

    /// Rolling volume-weighted average price (f64).
    #[getter]
    pub fn vwap<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<f64>> {
        PyArray1::from_slice_bound(py, &self.vwap)
    }

    /// Trade-flow imbalance ratio in [-1.0, 1.0].
    #[getter]
    pub fn trade_flow_imbalance<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<f64>> {
        PyArray1::from_slice_bound(py, &self.trade_flow_imbalance)
    }

    /// 2D array of bid prices with shape `(length, depth_levels)`.
    #[getter]
    pub fn bid_prices<'py>(&self, py: Python<'py>) -> PyResult<Bound<'py, PyArray2<i64>>> {
        let flat = PyArray1::from_slice_bound(py, &self.bid_prices);
        flat.reshape([self.length, self.depth_levels])
    }

    /// 2D array of bid quantities with shape `(length, depth_levels)`.
    #[getter]
    pub fn bid_qtys<'py>(&self, py: Python<'py>) -> PyResult<Bound<'py, PyArray2<i64>>> {
        let flat = PyArray1::from_slice_bound(py, &self.bid_qtys);
        flat.reshape([self.length, self.depth_levels])
    }

    /// 2D array of ask prices with shape `(length, depth_levels)`.
    #[getter]
    pub fn ask_prices<'py>(&self, py: Python<'py>) -> PyResult<Bound<'py, PyArray2<i64>>> {
        let flat = PyArray1::from_slice_bound(py, &self.ask_prices);
        flat.reshape([self.length, self.depth_levels])
    }

    /// 2D array of ask quantities with shape `(length, depth_levels)`.
    #[getter]
    pub fn ask_qtys<'py>(&self, py: Python<'py>) -> PyResult<Bound<'py, PyArray2<i64>>> {
        let flat = PyArray1::from_slice_bound(py, &self.ask_qtys);
        flat.reshape([self.length, self.depth_levels])
    }

    /// Trade price for trade events (0 if not a trade).
    #[getter]
    pub fn trade_price<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.trade_price)
    }

    /// Trade quantity for trade events (0 if not a trade).
    #[getter]
    pub fn trade_qty<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i64>> {
        PyArray1::from_slice_bound(py, &self.trade_qty)
    }

    /// Trade aggressor for trade events: 1=Buy, -1=Sell, 0=None.
    #[getter]
    pub fn trade_aggressor<'py>(&self, py: Python<'py>) -> Bound<'py, PyArray1<i8>> {
        PyArray1::from_slice_bound(py, &self.trade_aggressor)
    }

    /// Return all columns as a Python dictionary of NumPy arrays.
    pub fn to_dict<'py>(&self, py: Python<'py>) -> PyResult<Bound<'py, PyDict>> {
        let dict = PyDict::new_bound(py);
        dict.set_item("recv_ts", self.recv_ts(py))?;
        dict.set_item("exchange_ts", self.exchange_ts(py))?;
        dict.set_item("event_kind", self.event_kind(py))?;
        dict.set_item("book_valid", self.book_valid(py))?;
        dict.set_item("epoch", self.epoch(py))?;
        dict.set_item("seq", self.seq(py))?;
        dict.set_item("best_bid_price", self.best_bid_price(py))?;
        dict.set_item("best_bid_qty", self.best_bid_qty(py))?;
        dict.set_item("best_ask_price", self.best_ask_price(py))?;
        dict.set_item("best_ask_qty", self.best_ask_qty(py))?;
        dict.set_item("spread", self.spread(py))?;
        dict.set_item("mid", self.mid(py))?;
        dict.set_item("microprice", self.microprice(py))?;
        dict.set_item("imbalance", self.imbalance(py))?;
        dict.set_item("vwap", self.vwap(py))?;
        dict.set_item("trade_flow_imbalance", self.trade_flow_imbalance(py))?;
        dict.set_item("bid_prices", self.bid_prices(py)?)?;
        dict.set_item("bid_qtys", self.bid_qtys(py)?)?;
        dict.set_item("ask_prices", self.ask_prices(py)?)?;
        dict.set_item("ask_qtys", self.ask_qtys(py)?)?;
        dict.set_item("trade_price", self.trade_price(py))?;
        dict.set_item("trade_qty", self.trade_qty(py))?;
        dict.set_item("trade_aggressor", self.trade_aggressor(py))?;
        Ok(dict)
    }

    /// Convert this batch into a `pyarrow.Table`.
    pub fn to_arrow(&self, py: Python) -> PyResult<PyObject> {
        let pa = py.import_bound("pyarrow")?;
        let dict = self.to_dict(py)?;
        let names: Vec<&str> = vec![
            "recv_ts", "exchange_ts", "event_kind", "book_valid", "epoch", "seq",
            "best_bid_price", "best_bid_qty", "best_ask_price", "best_ask_qty",
            "spread", "mid", "microprice", "imbalance", "vwap", "trade_flow_imbalance",
            "trade_price", "trade_qty", "trade_aggressor",
        ];
        let mut arrays = Vec::new();
        for &name in &names {
            let item = dict.get_item(name)?.ok_or_else(|| {
                pyo3::exceptions::PyKeyError::new_err(format!("missing {name}"))
            })?;
            let arr = pa.call_method1("array", (item,))?;
            arrays.push(arr);
        }
        let table = pa.getattr("Table")?.call_method1("from_arrays", (arrays, names))?;
        Ok(table.unbind())
    }

    fn __repr__(&self) -> String {
        format!("EventBatch(length={}, depth_levels={})", self.length, self.depth_levels)
    }
}
