//! Event-log file reader and decompressor.
//!
//! Reads length-delimited Protobuf records from zstd-compressed `.pbz` files,
//! validating magic bytes and schema versions (supports N and N-1).

use std::fs::File;
use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};

use prost::Message;

use crate::error::{ReplayError, Result};
use crate::schema::{
    LogHeader, LogRecord, MAGIC_BYTES, MIN_SUPPORTED_SCHEMA_VERSION, SCHEMA_VERSION,
};

/// Reader for a single `.pbz` event-log file.
pub struct EventLogReader {
    path: PathBuf,
    header: LogHeader,
    decoder: zstd::Decoder<'static, BufReader<File>>,
    record_buffer: Vec<u8>,
}

impl EventLogReader {
    /// Open and initialize an `EventLogReader` from a file path.
    ///
    /// Reads and validates magic bytes and `LogHeader`.
    pub fn open<P: AsRef<Path>>(path: P) -> Result<Self> {
        let path_buf = path.as_ref().to_path_buf();
        let file = File::open(&path_buf)?;
        let mut decoder = zstd::Decoder::new(file)?;

        // 1. Verify 4-byte magic
        let mut magic = [0u8; 4];
        decoder.read_exact(&mut magic)?;
        if &magic != MAGIC_BYTES {
            return Err(ReplayError::InvalidMagic(magic));
        }

        // 2. Read length-delimited LogHeader
        let mut len_buf = [0u8; 4];
        decoder.read_exact(&mut len_buf)?;
        #[allow(clippy::as_conversions)]
        let header_len = u32::from_le_bytes(len_buf) as usize;

        let mut header_bytes = vec![0u8; header_len];
        decoder.read_exact(&mut header_bytes)?;
        let header = LogHeader::decode(&header_bytes[..])?;

        // 3. Validate schema version (supports N and N-1)
        if header.schema_version < MIN_SUPPORTED_SCHEMA_VERSION
            || header.schema_version > SCHEMA_VERSION
        {
            return Err(ReplayError::UnsupportedSchemaVersion {
                version: header.schema_version,
                max_supported: SCHEMA_VERSION,
            });
        }

        Ok(Self {
            path: path_buf,
            header,
            decoder,
            record_buffer: Vec::with_capacity(4096),
        })
    }

    /// Access the file's `LogHeader`.
    #[must_use]
    pub fn header(&self) -> &LogHeader {
        &self.header
    }

    /// Access the file's path.
    #[must_use]
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Read the next `LogRecord` from the file. Returns `Ok(None)` on clean EOF.
    pub fn next_record(&mut self) -> Result<Option<LogRecord>> {
        let mut len_buf = [0u8; 4];
        match self.decoder.read_exact(&mut len_buf) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                return Ok(None);
            }
            Err(e) => return Err(ReplayError::Io(e)),
        }

        #[allow(clippy::as_conversions)]
        let record_len = u32::from_le_bytes(len_buf) as usize;
        self.record_buffer.resize(record_len, 0);
        self.decoder.read_exact(&mut self.record_buffer)?;

        let record = LogRecord::decode(&self.record_buffer[..])?;
        Ok(Some(record))
    }
}

impl Iterator for EventLogReader {
    type Item = Result<LogRecord>;

    fn next(&mut self) -> Option<Self::Item> {
        match self.next_record() {
            Ok(Some(rec)) => Some(Ok(rec)),
            Ok(None) => None,
            Err(e) => Some(Err(e)),
        }
    }
}
