//! Coarse-grained `EventReader` replaying recorded event logs into `Book` and `MetricsEngine`.

use std::path::{Path, PathBuf};

use pyo3::prelude::*;
use pyo3::types::{PyDict, PyType};
use sb_book::{Book, BookConfig};
use sb_core::{Aggressor, BookSide, Epoch, InstrumentSpec, Level, Price, Qty, Seq, TsNs};
use sb_metrics::engine::{MetricsConfig, MetricsEngine};
use sb_replay::reader::EventLogReader;
use sb_replay::schema::{
    RecordKind, decode_book_payload, decode_marker_payload, decode_trade_payload,
};

use crate::batch::PyEventBatch;
use crate::book::compute_book_checksum;
use crate::error::{DataGapError, PyBookError, replay_err_to_py};
use crate::spec::PyInstrumentSpec;

/// Coarse-grained event reader for high-throughput replay.
#[pyclass(name = "EventReader", module = "pybook")]
pub struct PyEventReader {
    paths: Vec<PathBuf>,
    current_idx: usize,
    reader: Option<EventLogReader>,
    spec: InstrumentSpec,
    book: Book,
    metrics: MetricsEngine,
    depth_levels: usize,
    skip_gaps: bool,
    epoch: Epoch,
    seq: Seq,
    book_valid: bool,
    records_processed: u64,
    snapshots_applied: u64,
    deltas_applied: u64,
    trades_applied: u64,
    markers_seen: u64,
}

#[pymethods]
impl PyEventReader {
    /// Create a new `EventReader` over a list of files or a single file path.
    #[new]
    #[pyo3(signature = (files, spec = None, depth_levels = 10, vwap_window_secs = 60, trade_flow_window_secs = 60, skip_gaps = false))]
    pub fn new(
        files: &Bound<'_, PyAny>,
        spec: Option<&PyInstrumentSpec>,
        depth_levels: usize,
        vwap_window_secs: u64,
        trade_flow_window_secs: u64,
        skip_gaps: bool,
    ) -> PyResult<Self> {
        let paths = extract_paths(files)?;
        if paths.is_empty() {
            return Err(PyBookError::new_err("No event-log files provided to EventReader"));
        }

        // Determine InstrumentSpec: either passed explicitly or read from first file header
        let instrument_spec = if let Some(s) = spec {
            s.inner
        } else {
            let first_reader = EventLogReader::open(&paths[0]).map_err(replay_err_to_py)?;
            let h = first_reader.header();
            InstrumentSpec::new(
                h.price_scale as u8,
                h.qty_scale as u8,
                h.tick_size,
                h.lot_size,
                0, // LogHeader does not store min_notional; default to 0
            )
            .map_err(|e| PyBookError::new_err(format!("Invalid InstrumentSpec in file header: {e:?}")))?
        };

        let book_config = BookConfig {
            window_ticks: 4096,
            recenter_threshold_ticks: 1024,
            max_depth_levels: depth_levels.max(50),
        };
        let book = Book::new(book_config, instrument_spec)
            .map_err(|e| PyBookError::new_err(format!("Failed to initialize Book: {e:?}")))?;

        let ns_per_sec: i64 = 1_000_000_000;
        let metrics_config = MetricsConfig {
            depth_levels,
            vwap_window_ns: (vwap_window_secs as i64).saturating_mul(ns_per_sec),
            trade_flow_window_ns: (trade_flow_window_secs as i64).saturating_mul(ns_per_sec),
            vwap_capacity: 8192,
            trade_flow_capacity: 8192,
        };
        let metrics = MetricsEngine::new(metrics_config);

        Ok(Self {
            paths,
            current_idx: 0,
            reader: None,
            spec: instrument_spec,
            book,
            metrics,
            depth_levels,
            skip_gaps,
            epoch: Epoch::new(0),
            seq: Seq::new(0),
            book_valid: false,
            records_processed: 0,
            snapshots_applied: 0,
            deltas_applied: 0,
            trades_applied: 0,
            markers_seen: 0,
        })
    }

    /// Open a single `.pbz` event-log file.
    #[classmethod]
    #[pyo3(signature = (path, spec = None, depth_levels = 10, vwap_window_secs = 60, trade_flow_window_secs = 60, skip_gaps = false))]
    pub fn open_file(
        _cls: &Bound<'_, PyType>,
        path: String,
        spec: Option<&PyInstrumentSpec>,
        depth_levels: usize,
        vwap_window_secs: u64,
        trade_flow_window_secs: u64,
        skip_gaps: bool,
    ) -> PyResult<Self> {
        let files = vec![PathBuf::from(path)];
        Self::create_internal(files, spec, depth_levels, vwap_window_secs, trade_flow_window_secs, skip_gaps)
    }

    /// Open and chronologically sort all hourly `.pbz` files for a recorded day:
    /// `{base_dir}/venue={venue}/symbol={symbol}/date={date}/hour=*/part-*.pbz`
    #[classmethod]
    #[pyo3(signature = (base_dir, venue, symbol, date, spec = None, depth_levels = 10, vwap_window_secs = 60, trade_flow_window_secs = 60, skip_gaps = false))]
    pub fn open_day(
        _cls: &Bound<'_, PyType>,
        base_dir: String,
        venue: String,
        symbol: String,
        date: String,
        spec: Option<&PyInstrumentSpec>,
        depth_levels: usize,
        vwap_window_secs: u64,
        trade_flow_window_secs: u64,
        skip_gaps: bool,
    ) -> PyResult<Self> {
        let day_dir = Path::new(&base_dir)
            .join(format!("venue={venue}"))
            .join(format!("symbol={symbol}"))
            .join(format!("date={date}"));

        if !day_dir.exists() {
            return Err(PyBookError::new_err(format!(
                "Day directory does not exist: {}",
                day_dir.display()
            )));
        }

        let mut files = Vec::new();
        for entry in std::fs::read_dir(&day_dir)
            .map_err(|e| PyBookError::new_err(format!("Cannot read day dir: {e}")))?
        {
            let entry = entry.map_err(|e| PyBookError::new_err(format!("{e}")))?;
            let p = entry.path();
            if p.is_dir() {
                for fe in std::fs::read_dir(&p).map_err(|e| PyBookError::new_err(format!("{e}")))? {
                    let fe = fe.map_err(|e| PyBookError::new_err(format!("{e}")))?;
                    let fp = fe.path();
                    if fp.extension().and_then(|s| s.to_str()) == Some("pbz") {
                        files.push(fp);
                    }
                }
            }
        }
        files.sort();

        if files.is_empty() {
            return Err(PyBookError::new_err(format!(
                "No .pbz files found under day directory: {}",
                day_dir.display()
            )));
        }

        Self::create_internal(files, spec, depth_levels, vwap_window_secs, trade_flow_window_secs, skip_gaps)
    }

    /// Read next batch of up to `batch_size` market events.
    ///
    /// Releases the Python GIL during record decompression, book apply, and metric calculations.
    /// Returns `None` when all files have been completely read.
    #[pyo3(signature = (batch_size = 10000))]
    pub fn next_batch(&mut self, py: Python, batch_size: usize) -> PyResult<Option<PyEventBatch>> {
        // Release the GIL during heavy Rust replay work
        py.allow_threads(|| self.process_batch(batch_size))
    }

    /// Access instrument spec.
    #[getter]
    pub fn spec(&self) -> PyInstrumentSpec {
        PyInstrumentSpec { inner: self.spec }
    }

    /// Return replay session summary.
    pub fn summary<'py>(&self, py: Python<'py>) -> PyResult<Bound<'py, PyDict>> {
        let dict = PyDict::new_bound(py);
        dict.set_item("records_processed", self.records_processed)?;
        dict.set_item("snapshots_applied", self.snapshots_applied)?;
        dict.set_item("deltas_applied", self.deltas_applied)?;
        dict.set_item("trades_applied", self.trades_applied)?;
        dict.set_item("markers_seen", self.markers_seen)?;
        dict.set_item("book_checksum", compute_book_checksum(&self.book))?;
        let snap = self.metrics.snapshot();
        dict.set_item("mid", snap.bbo.mid)?;
        dict.set_item("microprice", snap.bbo.microprice)?;
        dict.set_item("spread", snap.bbo.spread_units)?;
        dict.set_item("imbalance", snap.depth.imbalance)?;
        dict.set_item("vwap", snap.vwap.vwap)?;
        dict.set_item("trade_flow_imbalance", snap.trade_flow.imbalance)?;
        Ok(dict)
    }

    fn __iter__(slf: PyRef<'_, Self>) -> PyRef<'_, Self> {
        slf
    }

    fn __next__(&mut self, py: Python) -> PyResult<Option<PyEventBatch>> {
        self.next_batch(py, 10000)
    }
}

impl PyEventReader {
    /// Create an `EventReader` from explicit paths and spec for Rust tests.
    pub fn from_paths(
        paths: Vec<PathBuf>,
        spec: Option<InstrumentSpec>,
        depth_levels: usize,
        vwap_window_secs: u64,
        trade_flow_window_secs: u64,
        skip_gaps: bool,
    ) -> PyResult<Self> {
        let spec_wrapper = spec.map(|inner| PyInstrumentSpec { inner });
        Self::create_internal(
            paths,
            spec_wrapper.as_ref(),
            depth_levels,
            vwap_window_secs,
            trade_flow_window_secs,
            skip_gaps,
        )
    }

    fn create_internal(
        paths: Vec<PathBuf>,
        spec: Option<&PyInstrumentSpec>,
        depth_levels: usize,
        vwap_window_secs: u64,
        trade_flow_window_secs: u64,
        skip_gaps: bool,
    ) -> PyResult<Self> {
        let first_reader = EventLogReader::open(&paths[0]).map_err(replay_err_to_py)?;
        let instrument_spec = if let Some(s) = spec {
            s.inner
        } else {
            let h = first_reader.header();
            InstrumentSpec::new(
                h.price_scale as u8,
                h.qty_scale as u8,
                h.tick_size,
                h.lot_size,
                0, // LogHeader does not store min_notional; default to 0
            )
            .map_err(|e| PyBookError::new_err(format!("Invalid InstrumentSpec in file header: {e:?}")))?
        };

        let book_config = BookConfig {
            window_ticks: 4096,
            recenter_threshold_ticks: 1024,
            max_depth_levels: depth_levels.max(50),
        };
        let book = Book::new(book_config, instrument_spec)
            .map_err(|e| PyBookError::new_err(format!("Failed to initialize Book: {e:?}")))?;

        let ns_per_sec: i64 = 1_000_000_000;
        let metrics_config = MetricsConfig {
            depth_levels,
            vwap_window_ns: (vwap_window_secs as i64).saturating_mul(ns_per_sec),
            trade_flow_window_ns: (trade_flow_window_secs as i64).saturating_mul(ns_per_sec),
            vwap_capacity: 8192,
            trade_flow_capacity: 8192,
        };
        let metrics = MetricsEngine::new(metrics_config);

        Ok(Self {
            paths,
            current_idx: 1,
            reader: Some(first_reader),
            spec: instrument_spec,
            book,
            metrics,
            depth_levels,
            skip_gaps,
            epoch: Epoch::new(0),
            seq: Seq::new(0),
            book_valid: false,
            records_processed: 0,
            snapshots_applied: 0,
            deltas_applied: 0,
            trades_applied: 0,
            markers_seen: 0,
        })
    }

    /// Process up to `batch_size` records and return the filled `PyEventBatch`.
    pub fn process_batch(&mut self, batch_size: usize) -> PyResult<Option<PyEventBatch>> {
        let mut batch = PyEventBatch::with_capacity(batch_size, self.depth_levels);

        let mut bid_buf = vec![Level::new(Price::ZERO, Qty::ZERO); self.depth_levels];
        let mut ask_buf = vec![Level::new(Price::ZERO, Qty::ZERO); self.depth_levels];

        while batch.length < batch_size {
            // Ensure we have an open file reader
            if self.reader.is_none() {
                if self.current_idx >= self.paths.len() {
                    break; // All files processed
                }
                let reader = EventLogReader::open(&self.paths[self.current_idx])
                    .map_err(replay_err_to_py)?;
                self.reader = Some(reader);
                self.current_idx += 1;
            }

            let record_opt = self.reader.as_mut().unwrap().next_record().map_err(replay_err_to_py)?;
            let record = match record_opt {
                Some(r) => r,
                None => {
                    // Current file EOF; move to next file
                    self.reader = None;
                    continue;
                }
            };

            let kind = match RecordKind::from_i32(record.kind) {
                Some(k) => k,
                None => continue,
            };

            self.records_processed += 1;

            let mut trade_p = 0i64;
            let mut trade_q = 0i64;
            let mut trade_agg = 0i8;

            let exchange_ts = TsNs::from_nanos(record.exchange_ts_ns);

            match kind {
                RecordKind::BookSnapshot => {
                    let (bids, asks) = decode_book_payload(&record.payload)
                        .map_err(replay_err_to_py)?;
                    self.book
                        .apply_snapshot(&bids, &asks)
                        .map_err(|e| PyBookError::new_err(format!("apply_snapshot failed: {e:?}")))?;
                    self.metrics.on_book_update(&self.book);
                    self.snapshots_applied += 1;
                    self.book_valid = true;
                    self.epoch = self.epoch.increment();
                    self.seq = Seq::new(0);
                }
                RecordKind::BookDelta => {
                    let (bids, asks) = decode_book_payload(&record.payload)
                        .map_err(replay_err_to_py)?;
                    let _ = self.book.apply_delta(&bids, &asks);
                    self.metrics.on_book_update(&self.book);
                    self.deltas_applied += 1;
                    self.seq = self.seq.next().unwrap_or(self.seq);
                }
                RecordKind::Trade => {
                    let trade = decode_trade_payload(&record.payload)
                        .map_err(replay_err_to_py)?;
                    self.metrics.on_trade(exchange_ts, trade.price, trade.qty, trade.aggressor);
                    self.trades_applied += 1;
                    trade_p = trade.price.units();
                    trade_q = trade.qty.units();
                    trade_agg = match trade.aggressor {
                        Aggressor::Buy => 1,
                        Aggressor::Sell => -1,
                    };
                }
                RecordKind::GapMarker | RecordKind::ResyncMarker | RecordKind::ReconnectMarker => {
                    let _ = decode_marker_payload(&record.payload).map_err(replay_err_to_py)?;
                    self.markers_seen += 1;
                    if !self.skip_gaps {
                        return Err(DataGapError::new_err(format!(
                            "Unsafe data gap marker encountered at exchange_ts={}: {:?} (enable skip_gaps=True to proceed)",
                            record.exchange_ts_ns, kind
                        )));
                    }
                    self.book_valid = false;
                    self.epoch = self.epoch.increment();
                    self.seq = Seq::new(0);
                }
                RecordKind::RawFrame => {
                    continue;
                }
            }

            // Capture state after event
            let snap = self.metrics.snapshot();
            let bb = self.book.best_bid();
            let ba = self.book.best_ask();

            batch.recv_ts.push(record.recv_ts_ns);
            batch.exchange_ts.push(record.exchange_ts_ns);
            batch.event_kind.push(kind as u8);
            batch.book_valid.push(self.book_valid);
            batch.epoch.push(self.epoch.id());
            batch.seq.push(self.seq.id());

            batch.best_bid_price.push(bb.map(|l| l.price.units()).unwrap_or(0));
            batch.best_bid_qty.push(bb.map(|l| l.qty.units()).unwrap_or(0));
            batch.best_ask_price.push(ba.map(|l| l.price.units()).unwrap_or(0));
            batch.best_ask_qty.push(ba.map(|l| l.qty.units()).unwrap_or(0));
            batch.spread.push(snap.bbo.spread_units);
            batch.mid.push(snap.bbo.mid);
            batch.microprice.push(snap.bbo.microprice);
            batch.imbalance.push(snap.depth.imbalance);
            batch.vwap.push(snap.vwap.vwap);
            batch.trade_flow_imbalance.push(snap.trade_flow.imbalance);

            // Fetch top K levels for depth arrays
            let n_bids = self.book.levels(BookSide::Bid, self.depth_levels, &mut bid_buf);
            for i in 0..self.depth_levels {
                if i < n_bids {
                    batch.bid_prices.push(bid_buf[i].price.units());
                    batch.bid_qtys.push(bid_buf[i].qty.units());
                } else {
                    batch.bid_prices.push(0);
                    batch.bid_qtys.push(0);
                }
            }

            let n_asks = self.book.levels(BookSide::Ask, self.depth_levels, &mut ask_buf);
            for i in 0..self.depth_levels {
                if i < n_asks {
                    batch.ask_prices.push(ask_buf[i].price.units());
                    batch.ask_qtys.push(ask_buf[i].qty.units());
                } else {
                    batch.ask_prices.push(0);
                    batch.ask_qtys.push(0);
                }
            }

            batch.trade_price.push(trade_p);
            batch.trade_qty.push(trade_q);
            batch.trade_aggressor.push(trade_agg);

            batch.length += 1;
        }

        if batch.length == 0 {
            Ok(None)
        } else {
            Ok(Some(batch))
        }
    }
}

fn extract_paths(files: &Bound<'_, PyAny>) -> PyResult<Vec<PathBuf>> {
    if let Ok(s) = files.extract::<String>() {
        return Ok(vec![PathBuf::from(s)]);
    }
    if let Ok(list) = files.extract::<Vec<String>>() {
        return Ok(list.into_iter().map(PathBuf::from).collect());
    }
    Err(PyBookError::new_err("files must be a file path string or list of file paths"))
}
