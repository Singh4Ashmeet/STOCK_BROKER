"""Tests for pybook.EventBatch container and conversions."""

import numpy as np
import pyarrow as pa
from pybook import EventReader


def test_batch_properties_and_arrays() -> None:
    reader = EventReader.open_day(
        "../testdata/recorded_day",
        "BINANCE",
        "BTC-USDT",
        "2026-10-09",
        depth_levels=10,
        skip_gaps=True,
    )
    batch = reader.next_batch(1000)
    assert batch is not None
    assert len(batch) > 0
    assert batch.length == len(batch)

    # 1D arrays
    assert isinstance(batch.recv_ts, np.ndarray)
    assert batch.recv_ts.dtype == np.int64
    assert len(batch.recv_ts) == batch.length

    assert isinstance(batch.exchange_ts, np.ndarray)
    assert batch.exchange_ts.dtype == np.int64

    assert isinstance(batch.event_kind, np.ndarray)
    assert batch.event_kind.dtype == np.uint8

    assert isinstance(batch.book_valid, np.ndarray)
    assert batch.book_valid.dtype == bool

    assert isinstance(batch.epoch, np.ndarray)
    assert isinstance(batch.seq, np.ndarray)

    assert isinstance(batch.best_bid_price, np.ndarray)
    assert isinstance(batch.best_bid_qty, np.ndarray)
    assert isinstance(batch.best_ask_price, np.ndarray)
    assert isinstance(batch.best_ask_qty, np.ndarray)
    assert isinstance(batch.spread, np.ndarray)
    assert isinstance(batch.mid, np.ndarray)
    assert isinstance(batch.microprice, np.ndarray)
    assert isinstance(batch.imbalance, np.ndarray)
    assert isinstance(batch.vwap, np.ndarray)
    assert isinstance(batch.trade_flow_imbalance, np.ndarray)

    # 2D depth arrays
    assert batch.bid_prices.shape == (batch.length, 10)
    assert batch.bid_qtys.shape == (batch.length, 10)
    assert batch.ask_prices.shape == (batch.length, 10)
    assert batch.ask_qtys.shape == (batch.length, 10)


def test_batch_to_dict_and_to_arrow() -> None:
    reader = EventReader.open_day(
        "../testdata/recorded_day",
        "BINANCE",
        "BTC-USDT",
        "2026-10-09",
        depth_levels=10,
        skip_gaps=True,
    )
    batch = reader.next_batch(500)
    assert batch is not None

    # to_dict
    d = batch.to_dict()
    assert isinstance(d, dict)
    assert "recv_ts" in d
    assert "best_bid_price" in d
    assert "vwap" in d
    assert len(d["recv_ts"]) == batch.length

    # to_arrow
    table = batch.to_arrow()
    assert isinstance(table, pa.Table)
    assert table.num_rows == batch.length
    assert "best_bid_price" in table.column_names
    assert "trade_price" in table.column_names
    assert "vwap" in table.column_names
