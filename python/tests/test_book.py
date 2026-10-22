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


def test_fill_plan_multi_level_and_market_impact() -> None:
    """Test walking multiple book levels and verifying market impact / slippage increases monotonically."""
    spec = InstrumentSpec(2, 5, 10, 100)
    book = OrderBook(spec, window_ticks=4096)

    # 4 ask levels:
    # 60010.00: 100,000 (0.1)
    # 60020.00: 200,000 (0.2)
    # 60030.00: 300,000 (0.3)
    # 60040.00: 400,000 (0.4)
    bids = [(6000000, 500000)]
    asks = [
        (6001000, 100000),
        (6002000, 200000),
        (6003000, 300000),
        (6004000, 400000),
    ]
    book.apply_snapshot(bids, asks)

    # Level 1 only: buy 50,000
    f1, n1, fills1 = book.walk_market("buy", 50000)
    assert f1 == 50000
    assert fills1 == [(6001000, 50000)]
    assert n1 == 6001000 * 50000
    avg_price_1 = n1 / f1
    assert avg_price_1 == 6001000.0

    # Cross into Level 2: buy 150,000 (all of L1 + 50,000 of L2)
    f2, n2, fills2 = book.walk_market("buy", 150000)
    assert f2 == 150000
    assert len(fills2) == 2
    assert fills2[0] == (6001000, 100000)
    assert fills2[1] == (6002000, 50000)
    expected_n2 = (6001000 * 100000) + (6002000 * 50000)
    assert n2 == expected_n2
    avg_price_2 = n2 / f2
    assert avg_price_2 > avg_price_1  # Market impact increases avg execution price

    # Cross into Level 3: buy 400,000 (L1: 100k, L2: 200k, L3: 100k)
    f3, n3, fills3 = book.walk_market("buy", 400000)
    assert f3 == 400000
    assert len(fills3) == 3
    assert fills3[0] == (6001000, 100000)
    assert fills3[1] == (6002000, 200000)
    assert fills3[2] == (6003000, 100000)
    expected_n3 = (6001000 * 100000) + (6002000 * 200000) + (6003000 * 100000)
    assert n3 == expected_n3
    avg_price_3 = n3 / f3
    assert avg_price_3 > avg_price_2  # Monotonic increase in effective execution price


def test_walk_market_depth_exhaustion_partial_fill() -> None:
    """When order size exceeds total book depth, book should return partial fill up to total available."""
    spec = InstrumentSpec(2, 5, 10, 100)
    book = OrderBook(spec, window_ticks=2048)

    bids = [(6000000, 100000), (5999000, 150000)]
    asks = [(6001000, 200000)]
    book.apply_snapshot(bids, asks)

    # Total ask depth is 200_000. Request 500_000.
    filled, notional, fills = book.walk_market("buy", 500000)
    assert filled == 200000
    assert len(fills) == 1
    assert fills[0] == (6001000, 200000)
    assert notional == 6001000 * 200000

    # Total bid depth is 250_000. Request 1_000_000 sell.
    filled_sell, notional_sell, fills_sell = book.walk_market("sell", 1000000)
    assert filled_sell == 250000
    assert len(fills_sell) == 2
    assert fills_sell[0] == (6000000, 100000)
    assert fills_sell[1] == (5999000, 150000)
    expected_notional_sell = (6000000 * 100000) + (5999000 * 150000)
    assert notional_sell == expected_notional_sell


def test_crossed_book_rejection() -> None:
    """Applying a crossed snapshot (best bid >= best ask) should be rejected."""
    import pytest
    from pybook import PyBookError

    spec = InstrumentSpec(2, 5, 10, 100)
    book = OrderBook(spec, window_ticks=2048)

    # Crossed snapshot: bid 6001000 >= ask 6000000
    bids = [(6001000, 500000)]
    asks = [(6000000, 500000)]

    with pytest.raises(PyBookError):
        book.apply_snapshot(bids, asks)

