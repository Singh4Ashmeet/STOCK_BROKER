//! `sb-pybook`: PyO3 bridge exposing the shared Rust order book, metrics and event log reader to Python.
//!
//! Decision record: `docs/adr/ADR-0005-pyo3-shared-book.md`.

use pyo3::prelude::*;
use pyo3::types::PyDict;

pub mod batch;
pub mod book;
pub mod error;
pub mod reader;
pub mod spec;

pub use batch::PyEventBatch;
pub use book::PyOrderBook;
pub use error::{DataGapError, PyBookError, SchemaVersionError};
pub use reader::PyEventReader;
pub use spec::PyInstrumentSpec;

/// Return detailed build and version information about `pybook`.
#[pyfunction]
fn build_info<'py>(py: Python<'py>) -> PyResult<Bound<'py, PyDict>> {
    let dict = PyDict::new_bound(py);
    dict.set_item("version", env!("CARGO_PKG_VERSION"))?;
    dict.set_item("schema_version", sb_replay::schema::SCHEMA_VERSION)?;
    dict.set_item(
        "min_supported_schema_version",
        sb_replay::schema::MIN_SUPPORTED_SCHEMA_VERSION,
    )?;
    dict.set_item("rustc_version", "rustc 1.99+")?;
    dict.set_item("features", "abi3-py310, numpy")?;
    Ok(dict)
}

/// PyO3 C-extension module entry point.
#[pymodule]
fn _pybook(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add("__version__", env!("CARGO_PKG_VERSION"))?;
    m.add_function(wrap_pyfunction!(build_info, m)?)?;

    // Exceptions
    m.add("PyBookError", m.py().get_type_bound::<PyBookError>())?;
    m.add("DataGapError", m.py().get_type_bound::<DataGapError>())?;
    m.add(
        "SchemaVersionError",
        m.py().get_type_bound::<SchemaVersionError>(),
    )?;

    // Classes
    m.add_class::<PyInstrumentSpec>()?;
    m.add_class::<PyOrderBook>()?;
    m.add_class::<PyEventBatch>()?;
    m.add_class::<PyEventReader>()?;

    Ok(())
}
