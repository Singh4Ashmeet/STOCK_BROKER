"""Parity test: Replay recorded day through pybook and assert invariants across all events."""

from pathlib import Path
from pybook import EventReader

REPO_ROOT = Path(__file__).resolve().parents[2]
RECORDED_DAY = REPO_ROOT / "testdata" / "recorded_day"


def test_replay_parity_across_full_day() -> None:
    reader = EventReader.open_day(
        str(RECORDED_DAY),
        "BINANCE",
        "BTC-USDT",
        "2026-10-09",
        depth_levels=10,
        vwap_window_secs=3600,
        trade_flow_window_secs=3600,
        skip_gaps=True,
    )

    total_events = 0
    total_batches = 0

    while True:
        batch = reader.next_batch(10000)
        if batch is None:
            break

        n = len(batch)
        assert n > 0
        total_events += n
        total_batches += 1

        # Check invariants at every single event
        for i in range(n):
            bb = batch.best_bid_price[i]
            ba = batch.best_ask_price[i]

            if bb > 0 and ba > 0 and ba > bb:
                # Spread parity
                expected_spread = ba - bb
                assert batch.spread[i] == expected_spread, f"Spread mismatch at event {i}"

                # Mid parity
                expected_mid = (bb + ba) / 2.0
                assert abs(batch.mid[i] - expected_mid) < 1e-6, f"Mid mismatch at event {i}"

                # Microprice within bid and ask
                assert bb <= batch.microprice[i] <= ba, f"Microprice outside bounds at event {i}"

            # Order book imbalance in [-1.0, 1.0]
            assert -1.0 <= batch.imbalance[i] <= 1.0, f"Imbalance out of bounds at event {i}"

            # Trade flow imbalance in [-1.0, 1.0]
            assert (
                -1.0 <= batch.trade_flow_imbalance[i] <= 1.0
            ), f"Trade flow imbalance out of bounds at event {i}"

            # Depth monotonic ordering
            for d in range(9):
                bp1 = batch.bid_prices[i, d]
                bp2 = batch.bid_prices[i, d + 1]
                if bp2 > 0:
                    assert bp1 >= bp2, f"Bid levels not monotonically descending at event {i}"

                ap1 = batch.ask_prices[i, d]
                ap2 = batch.ask_prices[i, d + 1]
                if ap2 > 0:
                    assert ap1 <= ap2, f"Ask levels not monotonically ascending at event {i}"

            # Timestamp monotonicity enforcement
            if i > 0:
                assert batch.recv_ts[i] >= batch.recv_ts[i - 1], f"recv_ts non-monotonic at event {i}"
                assert batch.exchange_ts[i] >= batch.exchange_ts[i - 1], f"exchange_ts non-monotonic at event {i}"

    # Day summary parity
    summary = reader.summary()
    assert summary["records_processed"] == total_events
    assert summary["snapshots_applied"] == 1
    assert summary["deltas_applied"] == 240
    assert summary["trades_applied"] == 240
    assert summary["markers_seen"] == 3
    assert summary["book_checksum"] != 0
    assert total_events == 484  # 1 snapshot + 240 deltas + 240 trades + 3 resyncs = 484
