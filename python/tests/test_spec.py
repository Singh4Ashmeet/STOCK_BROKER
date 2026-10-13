"""Tests for pybook.InstrumentSpec."""

from pybook import InstrumentSpec


def test_instrument_spec_properties() -> None:
    spec = InstrumentSpec(price_scale=2, qty_scale=5, tick_size=10, lot_size=100, min_notional=0)
    assert spec.price_scale == 2
    assert spec.qty_scale == 5
    assert spec.tick_size == 10
    assert spec.lot_size == 100
    assert spec.min_notional == 0


def test_instrument_spec_float_conversions() -> None:
    spec = InstrumentSpec(2, 5, 10, 100)
    # Price scale 2: 60000.50 -> 6000050 units
    assert spec.price_from_float(60000.50) == 6000050
    assert spec.price_to_float(6000050) == 60000.50

    # Qty scale 5: 1.23456 -> 123456 units
    assert spec.qty_from_float(1.23456) == 123456
    assert spec.qty_to_float(123456) == 1.23456
