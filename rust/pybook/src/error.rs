//! Python exception types and error mappings for `pybook`.

use pyo3::create_exception;
use pyo3::exceptions::PyException;
use pyo3::PyErr;
use sb_replay::ReplayError;

create_exception!(pybook, PyBookError, PyException);
create_exception!(pybook, DataGapError, PyBookError);
create_exception!(pybook, SchemaVersionError, PyBookError);

/// Convert an `sb-replay::ReplayError` into the corresponding PyO3 `PyErr`.
pub fn replay_err_to_py(err: ReplayError) -> PyErr {
    match err {
        ReplayError::UnsupportedSchemaVersion {
            version,
            max_supported,
        } => SchemaVersionError::new_err(format!(
            "Unsupported schema version: {version} (maximum supported is {max_supported})"
        )),
        ReplayError::InvalidMagic(magic) => {
            PyBookError::new_err(format!("Invalid file magic header: {magic:?}"))
        }
        ReplayError::Io(e) => PyBookError::new_err(format!("I/O error reading event log: {e}")),
        ReplayError::CorruptedFile { path, reason } => PyBookError::new_err(format!(
            "Corrupted event log file {}: {reason}",
            path.display()
        )),
        ReplayError::Book(reason) => PyBookError::new_err(format!("Order book error: {reason}")),
        ReplayError::Protobuf(e) => PyBookError::new_err(format!("Protobuf decoding error: {e}")),
        _ => PyBookError::new_err(format!("{err}")),
    }
}
