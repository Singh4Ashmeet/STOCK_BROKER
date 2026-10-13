//! PyO3 wrapper for `sb_core::InstrumentSpec`.

use pyo3::prelude::*;
use sb_core::InstrumentSpec;

use crate::error::PyBookError;

/// PyO3 exposed Instrument Specification.
#[pyclass(name = "InstrumentSpec", module = "pybook")]
#[derive(Debug, Clone)]
pub struct PyInstrumentSpec {
    pub(crate) inner: InstrumentSpec,
}

#[pymethods]
impl PyInstrumentSpec {
    /// Create a new `InstrumentSpec`.
    #[new]
    #[pyo3(signature = (price_scale, qty_scale, tick_size, lot_size, min_notional = 0))]
    pub fn new(
        price_scale: u8,
        qty_scale: u8,
        tick_size: i64,
        lot_size: i64,
        min_notional: i64,
    ) -> PyResult<Self> {
        let inner = InstrumentSpec::new(price_scale, qty_scale, tick_size, lot_size, min_notional)
            .map_err(|e| PyBookError::new_err(format!("Invalid InstrumentSpec: {e:?}")))?;
        Ok(Self { inner })
    }

    /// Price decimal scale (exponent $10^{-N}$).
    #[getter]
    pub fn price_scale(&self) -> u8 {
        self.inner.price_scale()
    }

    /// Quantity decimal scale (exponent $10^{-N}$).
    #[getter]
    pub fn qty_scale(&self) -> u8 {
        self.inner.qty_scale()
    }

    /// Minimum price tick size in price units.
    #[getter]
    pub fn tick_size(&self) -> i64 {
        self.inner.tick_size()
    }

    /// Minimum quantity lot size in quantity units.
    #[getter]
    pub fn lot_size(&self) -> i64 {
        self.inner.lot_size()
    }

    /// Minimum order notional value.
    #[getter]
    pub fn min_notional(&self) -> i64 {
        self.inner.min_notional()
    }

    /// Convert fixed-point price units to human-readable float.
    pub fn price_to_float(&self, price_units: i64) -> f64 {
        price_units as f64 / 10f64.powi(self.inner.price_scale() as i32)
    }

    /// Convert fixed-point quantity units to human-readable float.
    pub fn qty_to_float(&self, qty_units: i64) -> f64 {
        qty_units as f64 / 10f64.powi(self.inner.qty_scale() as i32)
    }

    /// Convert float price to integer units.
    pub fn price_from_float(&self, price: f64) -> i64 {
        (price * 10f64.powi(self.inner.price_scale() as i32)).round() as i64
    }

    /// Convert float quantity to integer units.
    pub fn qty_from_float(&self, qty: f64) -> i64 {
        (qty * 10f64.powi(self.inner.qty_scale() as i32)).round() as i64
    }

    fn __repr__(&self) -> String {
        format!(
            "InstrumentSpec(price_scale={}, qty_scale={}, tick_size={}, lot_size={}, min_notional={})",
            self.inner.price_scale(),
            self.inner.qty_scale(),
            self.inner.tick_size(),
            self.inner.lot_size(),
            self.inner.min_notional()
        )
    }
}
