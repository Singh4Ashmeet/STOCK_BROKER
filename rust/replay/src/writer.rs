//! Dedicated background event-log writer thread (ADR-0006).
//!
//! Hot-path worker threads enqueue records through a lock-free channel handle.
//! The writer runs on its own thread, rotating files by hour and symbol,
//! flushing zstd frames, and guaranteeing disk I/O never touches the order-book thread.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::thread::JoinHandle;

use crossbeam_channel::{Receiver, Sender, TrySendError};
use prost::Message;
use sb_core::{Aggressor, InstrumentSpec, Level, Price, Qty, TsNs};

use crate::error::{ReplayError, Result};
use crate::path::{HourBucket, LogPathBuilder, finalize_part_file};
use crate::schema::{
    LogHeader, LogRecord, MAGIC_BYTES, SCHEMA_VERSION, make_delta_record, make_gap_record,
    make_resync_record, make_snapshot_record, make_trade_record,
};

/// Configuration for the background event log writer.
#[derive(Debug, Clone)]
pub struct WriterConfig {
    /// Base directory for logs (e.g. "data/raw")
    pub base_dir: PathBuf,
    /// Exchange venue identifier (e.g. "BINANCE")
    pub venue: String,
    /// Bounded channel buffer capacity (default 65,536)
    pub channel_capacity: usize,
    /// If true, drop records when queue is full (live mode);
    /// if false, block with backpressure (offline recording).
    pub drop_on_full: bool,
    /// Zstd compression level (1-21, default 3)
    pub zstd_level: i32,
    /// Writer service version string
    pub writer_version: String,
}

impl Default for WriterConfig {
    fn default() -> Self {
        Self {
            base_dir: PathBuf::from("data/raw"),
            venue: "BINANCE".to_string(),
            channel_capacity: 65_536,
            drop_on_full: true,
            zstd_level: 3,
            writer_version: env!("CARGO_PKG_VERSION").to_string(),
        }
    }
}

/// Runtime statistics for the writer thread.
#[derive(Debug, Default)]
pub struct WriterStats {
    /// Total records successfully written to disk
    pub written_count: AtomicU64,
    /// Total records dropped due to channel buffer saturation
    pub dropped_count: AtomicU64,
    /// Total compressed bytes written to disk
    pub bytes_written: AtomicU64,
    /// Total completed hour files cleanly closed and renamed
    pub files_completed: AtomicU64,
    /// Total gap markers emitted for dropped records
    pub gap_markers_emitted: AtomicU64,
}

/// Commands sent to the background writer thread.
enum WriterCommand {
    /// Write an event record for a symbol
    WriteRecord {
        symbol: String,
        spec: InstrumentSpec,
        record: LogRecord,
    },
    /// Flush active files without closing
    Flush,
    /// Shut down the writer thread cleanly
    Shutdown,
}

/// Active hour file writer state.
struct ActiveFileWriter {
    symbol: String,
    bucket: HourBucket,
    part_path: PathBuf,
    final_path: PathBuf,
    encoder: zstd::Encoder<'static, BufWriter<File>>,
}

impl ActiveFileWriter {
    fn create(
        path_builder: &LogPathBuilder,
        symbol: &str,
        bucket: HourBucket,
        spec: &InstrumentSpec,
        config: &WriterConfig,
        start_ts_ns: i64,
    ) -> std::io::Result<Self> {
        let part_path = path_builder.part_file_path(symbol, bucket, 0);
        let final_path = path_builder.final_file_path(symbol, bucket, 0);

        if let Some(parent) = part_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let file = File::create(&part_path)?;
        let buf = BufWriter::with_capacity(65_536, file);
        let mut encoder = zstd::Encoder::new(buf, config.zstd_level)?;

        // 1. Write 4-byte magic
        encoder.write_all(MAGIC_BYTES)?;

        // 2. Write LogHeader
        let header = LogHeader {
            schema_version: SCHEMA_VERSION,
            venue: config.venue.clone(),
            symbol: symbol.to_string(),
            price_scale: u32::from(spec.price_scale()),
            qty_scale: u32::from(spec.qty_scale()),
            tick_size: spec.tick_size(),
            lot_size: spec.lot_size(),
            start_ts_ns,
            writer_version: config.writer_version.clone(),
            host_info: std::env::consts::OS.to_string(),
        };

        let header_bytes = header.encode_to_vec();
        #[allow(clippy::as_conversions, clippy::cast_possible_truncation)]
        let len = (header_bytes.len() as u32).to_le_bytes();
        encoder.write_all(&len)?;
        encoder.write_all(&header_bytes)?;
        encoder.flush()?;

        Ok(Self {
            symbol: symbol.to_string(),
            bucket,
            part_path,
            final_path,
            encoder,
        })
    }

    fn write_record(&mut self, record: &LogRecord) -> std::io::Result<()> {
        let bytes = record.encode_to_vec();
        #[allow(clippy::as_conversions, clippy::cast_possible_truncation)]
        let len = (bytes.len() as u32).to_le_bytes();
        self.encoder.write_all(&len)?;
        self.encoder.write_all(&bytes)?;
        Ok(())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.encoder.flush()
    }

    fn close(self) -> std::io::Result<u64> {
        let mut buf_writer = self.encoder.finish()?;
        buf_writer.flush()?;
        let file = buf_writer.get_ref();
        file.sync_all()?;
        let metadata = file.metadata()?;
        let file_len = metadata.len();
        drop(buf_writer);

        finalize_part_file(&self.part_path, &self.final_path)?;
        Ok(file_len)
    }
}

/// Hot-path handle used by worker threads to enqueue records.
#[derive(Clone)]
pub struct LogWriterHandle {
    sender: Sender<WriterCommand>,
    stats: Arc<WriterStats>,
    drop_on_full: bool,
    is_shutting_down: Arc<AtomicBool>,
}

impl LogWriterHandle {
    /// Enqueue a record to be logged.
    ///
    /// In live mode (`drop_on_full = true`), this will never block the caller;
    /// if the channel is full, the record is dropped and the dropped counter is incremented.
    pub fn log(&self, symbol: &str, spec: &InstrumentSpec, record: LogRecord) -> Result<()> {
        if self.is_shutting_down.load(Ordering::Relaxed) {
            return Err(ReplayError::ChannelDisconnected);
        }

        let cmd = WriterCommand::WriteRecord {
            symbol: symbol.to_string(),
            spec: *spec,
            record,
        };

        if self.drop_on_full {
            match self.sender.try_send(cmd) {
                Ok(()) => Ok(()),
                Err(TrySendError::Full(_)) => {
                    self.stats.dropped_count.fetch_add(1, Ordering::Relaxed);
                    Err(ReplayError::RecordDropped)
                }
                Err(TrySendError::Disconnected(_)) => Err(ReplayError::ChannelDisconnected),
            }
        } else {
            self.sender
                .send(cmd)
                .map_err(|_| ReplayError::ChannelDisconnected)
        }
    }

    /// Convenience helper to log an order-book snapshot.
    pub fn log_snapshot(
        &self,
        symbol: &str,
        spec: &InstrumentSpec,
        recv_ts: TsNs,
        exchange_ts: TsNs,
        bids: &[Level],
        asks: &[Level],
    ) -> Result<()> {
        let rec = make_snapshot_record(recv_ts, exchange_ts, bids, asks);
        self.log(symbol, spec, rec)
    }

    /// Convenience helper to log an order-book delta.
    pub fn log_delta(
        &self,
        symbol: &str,
        spec: &InstrumentSpec,
        recv_ts: TsNs,
        exchange_ts: TsNs,
        bids: &[Level],
        asks: &[Level],
    ) -> Result<()> {
        let rec = make_delta_record(recv_ts, exchange_ts, bids, asks);
        self.log(symbol, spec, rec)
    }

    /// Convenience helper to log a trade.
    pub fn log_trade(
        &self,
        symbol: &str,
        spec: &InstrumentSpec,
        recv_ts: TsNs,
        exchange_ts: TsNs,
        price: Price,
        qty: Qty,
        aggressor: Aggressor,
        trade_id: &str,
    ) -> Result<()> {
        let rec = make_trade_record(recv_ts, exchange_ts, price, qty, aggressor, trade_id);
        self.log(symbol, spec, rec)
    }

    /// Convenience helper to log a gap marker.
    pub fn log_gap(
        &self,
        symbol: &str,
        spec: &InstrumentSpec,
        recv_ts: TsNs,
        exchange_ts: TsNs,
        expected_seq: u64,
        received_seq: u64,
        reason: &str,
    ) -> Result<()> {
        let rec = make_gap_record(recv_ts, exchange_ts, expected_seq, received_seq, reason);
        self.log(symbol, spec, rec)
    }

    /// Convenience helper to log a resync marker.
    pub fn log_resync(
        &self,
        symbol: &str,
        spec: &InstrumentSpec,
        recv_ts: TsNs,
        exchange_ts: TsNs,
        epoch: u64,
        reason: &str,
    ) -> Result<()> {
        let rec = make_resync_record(recv_ts, exchange_ts, epoch, reason);
        self.log(symbol, spec, rec)
    }

    /// Flush active files.
    pub fn flush(&self) -> Result<()> {
        self.sender
            .send(WriterCommand::Flush)
            .map_err(|_| ReplayError::ChannelDisconnected)
    }

    /// Access writer statistics.
    #[must_use]
    pub fn stats(&self) -> &Arc<WriterStats> {
        &self.stats
    }
}

/// The event log writer service running on a dedicated thread.
pub struct EventLogWriter {
    handle: LogWriterHandle,
    join_handle: Option<JoinHandle<Result<()>>>,
}

impl EventLogWriter {
    /// Spawn a new `EventLogWriter` on a background thread.
    pub fn start(config: WriterConfig) -> Self {
        let (sender, receiver) = crossbeam_channel::bounded(config.channel_capacity);
        let stats = Arc::new(WriterStats::default());
        let is_shutting_down = Arc::new(AtomicBool::new(false));

        let handle = LogWriterHandle {
            sender,
            stats: Arc::clone(&stats),
            drop_on_full: config.drop_on_full,
            is_shutting_down: Arc::clone(&is_shutting_down),
        };

        let stats_clone = Arc::clone(&stats);
        let join_handle = std::thread::Builder::new()
            .name("sb-event-log-writer".to_string())
            .spawn(move || run_writer_loop(receiver, config, stats_clone))
            .ok();

        Self {
            handle,
            join_handle,
        }
    }

    /// Get a cloneable lightweight handle to enqueue records.
    #[must_use]
    pub fn handle(&self) -> LogWriterHandle {
        self.handle.clone()
    }

    /// Shut down the writer thread cleanly, ensuring all queued records
    /// are written, flushed, fsynced, and all `.part` files are renamed.
    pub fn shutdown(mut self) -> Result<()> {
        self.handle.is_shutting_down.store(true, Ordering::SeqCst);
        let _ = self.handle.sender.send(WriterCommand::Shutdown);

        if let Some(jh) = self.join_handle.take() {
            match jh.join() {
                Ok(res) => res,
                Err(_) => Err(ReplayError::Book("Writer thread panicked".to_string())),
            }
        } else {
            Ok(())
        }
    }
}

/// Background worker loop for writing event records.
fn run_writer_loop(
    receiver: Receiver<WriterCommand>,
    config: WriterConfig,
    stats: Arc<WriterStats>,
) -> Result<()> {
    let path_builder = LogPathBuilder::new(&config.base_dir, &config.venue);
    let mut active_writer: Option<ActiveFileWriter> = None;

    while let Ok(cmd) = receiver.recv() {
        match cmd {
            WriterCommand::WriteRecord {
                symbol,
                spec,
                record,
            } => {
                let bucket = HourBucket::from_ts_ns(record.exchange_ts_ns);

                // Rotate file if symbol or hour changed
                let rotate_needed = match &active_writer {
                    Some(aw) => aw.symbol != symbol || aw.bucket != bucket,
                    None => true,
                };

                if rotate_needed {
                    if let Some(old_writer) = active_writer.take() {
                        if let Ok(bytes) = old_writer.close() {
                            stats.bytes_written.fetch_add(bytes, Ordering::Relaxed);
                            stats.files_completed.fetch_add(1, Ordering::Relaxed);
                        }
                    }

                    match ActiveFileWriter::create(
                        &path_builder,
                        &symbol,
                        bucket,
                        &spec,
                        &config,
                        record.recv_ts_ns,
                    ) {
                        Ok(writer) => {
                            active_writer = Some(writer);
                        }
                        Err(e) => {
                            tracing::error!("Failed to open log file: {}", e);
                            continue;
                        }
                    }
                }

                if let Some(writer) = active_writer.as_mut() {
                    match writer.write_record(&record) {
                        Ok(()) => {
                            stats.written_count.fetch_add(1, Ordering::Relaxed);
                        }
                        Err(e) => {
                            tracing::error!("Failed to write log record: {}", e);
                        }
                    }
                }
            }
            WriterCommand::Flush => {
                if let Some(writer) = active_writer.as_mut() {
                    let _ = writer.flush();
                }
            }
            WriterCommand::Shutdown => {
                break;
            }
        }
    }

    // Drain any remaining records in channel before closing
    while let Ok(cmd) = receiver.try_recv() {
        if let WriterCommand::WriteRecord {
            symbol,
            spec,
            record,
        } = cmd
        {
            let bucket = HourBucket::from_ts_ns(record.exchange_ts_ns);
            let rotate = match &active_writer {
                Some(aw) => aw.symbol != symbol || aw.bucket != bucket,
                None => true,
            };
            if rotate {
                if let Some(old) = active_writer.take() {
                    if let Ok(bytes) = old.close() {
                        stats.bytes_written.fetch_add(bytes, Ordering::Relaxed);
                        stats.files_completed.fetch_add(1, Ordering::Relaxed);
                    }
                }
                if let Ok(w) = ActiveFileWriter::create(
                    &path_builder,
                    &symbol,
                    bucket,
                    &spec,
                    &config,
                    record.recv_ts_ns,
                ) {
                    active_writer = Some(w);
                }
            }
            if let Some(w) = active_writer.as_mut() {
                if w.write_record(&record).is_ok() {
                    stats.written_count.fetch_add(1, Ordering::Relaxed);
                }
            }
        }
    }

    // Final clean close and rename
    if let Some(writer) = active_writer.take() {
        if let Ok(bytes) = writer.close() {
            stats.bytes_written.fetch_add(bytes, Ordering::Relaxed);
            stats.files_completed.fetch_add(1, Ordering::Relaxed);
        }
    }

    Ok(())
}
