"""pybook: Fast PyO3 bindings for stock_broker order book, metrics and event log reader."""

from __future__ import annotations

try:
    from pybook._pybook import (
        __version__,
        build_info,
        InstrumentSpec,
        OrderBook,
        EventReader,
        EventBatch,
        PyBookError,
        DataGapError,
        SchemaVersionError,
    )
except ImportError:
    # Allow importing version before compilation if needed
    __version__ = "0.1.0"

    def build_info() -> dict[str, str]:
        return {"version": __version__, "status": "uncompiled"}

__all__ = [
    "__version__",
    "build_info",
    "InstrumentSpec",
    "OrderBook",
    "EventReader",
    "EventBatch",
    "PyBookError",
    "DataGapError",
    "SchemaVersionError",
]
