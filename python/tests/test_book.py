"""Tests for pybook.OrderBook."""

from pybook import InstrumentSpec, OrderBook


def test_order_book_lifecycle() -> None:
    spec = InstrumentSpec(2, 5, 10, 100)
    book = OrderBook(spec, window_ticks=2048)

    # Empty book
    assert book.best_bid() is None
    assert book.best_ask() is None
    assert book.mid() is None
    assert book.spread() is None

    # Apply snapshot
    bids = [(6000000, 500000), (5999000, 1000000), (5998000, 1500000)]
    asks = [(6001000, 400000), (6002000, 800000), (6003000, 1200000)]
    book.apply_snapshot(bids, asks)

    assert book.best_bid() == (6000000, 500000)
    assert book.best_ask() == (6001000, 400000)
    assert book.spread() == 1000
    assert book.mid() == 6000500.0

    # Depth levels
    bid_levels = book.levels("bid", 2)
    assert len(bid_levels) == 2
    assert bid_levels[0] == (6000000, 500000)
    assert bid_levels[1] == (5999000, 1000000)

    ask_levels = book.levels("ask", 2)
    assert len(ask_levels) == 2
    assert ask_levels[0] == (6001000, 400000)
    assert ask_levels[1] == (6002000, 800000)

    assert book.depth_qty("bid", 2) == 1500000
    assert book.depth_qty("ask", 2) == 1200000

    # Apply delta
    book.apply_delta([(6000050, 200000)], [(6001000, 0)])  # new best bid, removed best ask
    assert book.best_bid() == (6000050, 200000)
    assert book.best_ask() == (6002000, 800000)
    assert book.spread() == 1950

    # Market order walking
    # Buy 500_000: walks asks (6002000 for 500_000)
    filled, notional, fills = book.walk_market("buy", 500000)
    assert filled == 500000
    assert len(fills) == 1
    assert fills[0] == (6002000, 500000)

    # Checksum is non-zero deterministic hash
    chk = book.checksum()
    assert isinstance(chk, int)
    assert chk != 0
