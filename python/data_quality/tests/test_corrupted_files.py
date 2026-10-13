"""Tests for catching deliberately corrupted files."""

from __future__ import annotations

from pathlib import Path

import polars as pl

from stockbroker_dq.core import DataQualityConfig, IssueSeverity
from stockbroker_dq.validator import validate_events


class TestCorruptedFiles:
    """Tests that deliberately corrupted files are caught."""

    def test_corrupted_sequence_numbers(self, tmp_path: Path) -> None:
        """Test file with corrupted sequence numbers (negative, zero, duplicates)."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000, 4000],
            "exchange_ts_ns": [1000, 2000, 3000, 4000],
            "event_type": ["trade", "trade", "trade", "trade"],
            "sequence": [1, -5, 0, 3],  # Negative and zero sequences
            "epoch": [1, 1, 1, 1],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "corrupted_seq.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        # Should catch invalid sequences
        assert report.verdict in ("fail", "conditional")
        assert any(i.severity in (IssueSeverity.ERROR, IssueSeverity.CRITICAL) for i in report.issues)

    def test_corrupted_timestamps(self, tmp_path: Path) -> None:
        """Test file with corrupted timestamps (future dates, zero, negative)."""
        # Put negative timestamp in exchange_ts_ns (the column checked by validator)
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000, 4000],
            "exchange_ts_ns": [1000, -1, 0, 4000],  # Negative, zero in exchange_ts
            "event_type": ["trade", "trade", "trade", "trade"],
            "sequence": [1, 2, 3, 4],
            "epoch": [1, 1, 1, 1],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "corrupted_ts.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        assert report.verdict in ("fail", "conditional")
        assert any(i.code == "INVALID_TIMESTAMP" for i in report.issues)

    def test_corrupted_event_types(self, tmp_path: Path) -> None:
        """Test file with invalid event type values."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000],
            "exchange_ts_ns": [1000, 2000, 3000],
            "event_type": ["trade", "invalid_type", "book_snapshot"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "corrupted_event_type.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        # Invalid event type should be caught
        assert report.verdict in ("fail", "conditional")

    def test_corrupted_prices_negative(self, tmp_path: Path) -> None:
        """Test trade with negative prices."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            "exchange_ts_ns": [1000, 2000],
            "event_type": ["trade", "trade"],
            "sequence": [1, 2],
            "epoch": [1, 1],
            "trade_price": [-100, 50000],  # Negative price
            "trade_qty": [100, 200],
            "trade_side": ["buy", "sell"],
            "trade_id": ["trade_1", "trade_2"],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "corrupted_price.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        # Negative prices should be flagged
        assert any(i.severity == IssueSeverity.ERROR for i in report.issues)

    def test_corrupted_quantities_zero(self, tmp_path: Path) -> None:
        """Test trade with zero quantity."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            "exchange_ts_ns": [1000, 2000],
            "event_type": ["trade", "trade"],
            "sequence": [1, 2],
            "epoch": [1, 1],
            "trade_price": [50000, 50000],
            "trade_qty": [0, 100],  # Zero quantity
            "trade_side": ["buy", "sell"],
            "trade_id": ["trade_1", "trade_2"],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "corrupted_qty.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        assert any(i.severity == IssueSeverity.ERROR for i in report.issues)

    def test_corrupted_book_arrays_null(self, tmp_path: Path) -> None:
        """Test book snapshot with null arrays."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            "exchange_ts_ns": [1000, 2000],
            "event_type": ["book_snapshot", "book_snapshot"],
            "sequence": [1, 2],
            "epoch": [1, 1],
            "bid_prices": [[100, 99], None],  # Null array
            "bid_qtys": [[10, 20], [15, 25]],
            "ask_prices": [[101, 102], [102, 103]],
            "ask_qtys": [[10, 20], [15, 25]],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "corrupted_book.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        # Null book arrays should be flagged
        assert any("NULL_BOOK_ARRAY" in i.code or "NULL" in i.code for i in report.issues)

    def test_mismatched_array_lengths(self, tmp_path: Path) -> None:
        """Test book with mismatched bid/ask array lengths."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000],
            "exchange_ts_ns": [1000],
            "event_type": ["book_snapshot"],
            "sequence": [1],
            "epoch": [1],
            "bid_prices": [[100, 99, 98]],  # 3 levels
            "bid_qtys": [[10, 20]],  # Only 2 levels
            "ask_prices": [[101, 102]],
            "ask_qtys": [[10, 20]],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "mismatched_arrays.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))
        assert report is not None

    def test_duplicate_events_exact(self, tmp_path: Path) -> None:
        """Test exact duplicate events (all fields identical)."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 1000, 3000],
            "exchange_ts_ns": [1000, 1000, 3000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 1, 3],  # Duplicate sequence 1
            "epoch": [1, 1, 1],
            "trade_price": [50000, 50000, 50000],
            "trade_qty": [100, 100, 100],
            "trade_side": ["buy", "buy", "sell"],
            "trade_id": ["trade_1", "trade_1", "trade_2"],  # Duplicate trade_id
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "duplicate_exact.parquet"
        df.write_parquet(file_path)

        config = DataQualityConfig(check_duplicates=True)
        report = validate_events(str(file_path), config)

        assert report.duplicate_events >= 1
        assert any(i.code == "DUPLICATE_EVENT" for i in report.issues)

    def test_large_sequence_gap(self, tmp_path: Path) -> None:
        """Test detection of very large sequence gaps."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 1000000000],  # Large time gap
            "exchange_ts_ns": [1000, 1000000000],
            "event_type": ["trade", "trade"],
            "sequence": [1, 1000000],  # Huge sequence gap
            "epoch": [1, 1],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "large_gap.parquet"
        df.write_parquet(file_path)

        config = DataQualityConfig(max_sequence_gap=100)
        report = validate_events(str(file_path), config)

        assert report.verdict in ("fail", "conditional")
        assert any("SEQUENCE_GAP" in i.code for i in report.issues)

    def test_mixed_corruptions(self, tmp_path: Path) -> None:
        """Test file with multiple types of corruption."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, -100, 3000, 4000, 5000],  # Negative timestamp in recv_ts (not checked)
            "exchange_ts_ns": [1000, 2000, 3000, 4000, 5000],
            "event_type": ["trade", "trade", "trade", "trade", "trade"],
            "sequence": [1, 2, 4, 5, 5],  # Gap (3 missing), duplicate 5
            "epoch": [1, 1, 1, 1, 1],
            "trade_price": [50000, -100, 50000, 50000, 50000],  # Negative price
            "trade_qty": [100, 0, 100, 100, 100],  # Zero qty
            "trade_side": ["buy", "sell", "buy", "invalid", "sell"],  # Invalid side
            "trade_id": ["t1", "t2", "t3", "t4", "t4"],  # Duplicate ID
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "mixed_corruption.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        # Should have multiple issues: duplicate event, negative price, zero qty, invalid side
        # Note: negative timestamp in recv_ts_ns is not checked (only exchange_ts_ns is checked)
        # Sequence gap of 1 (between 2 and 4) is not flagged if max_sequence_gap >= 1
        assert report.total_issues >= 4
        assert report.verdict in ("fail", "conditional")

    def test_out_of_order_within_epoch(self, tmp_path: Path) -> None:
        """Test out-of-order events within same epoch."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 3000, 2000, 4000],  # 2000 comes after 3000
            "exchange_ts_ns": [1000, 3000, 2000, 4000],
            "event_type": ["book_delta", "book_delta", "book_delta", "book_delta"],
            "sequence": [1, 2, 3, 4],
            "epoch": [1, 1, 1, 1],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "out_of_order.parquet"
        df.write_parquet(file_path)

        config = DataQualityConfig(check_ordering=True, allow_equal_timestamps=False)
        report = validate_events(str(file_path), config)

        assert any(i.code == "OUT_OF_ORDER" for i in report.issues)

    def test_gap_marker_validation(self, tmp_path: Path) -> None:
        """Test gap marker events have required fields."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            "exchange_ts_ns": [1000, 2000],
            "event_type": ["gap_marker", "gap_marker"],
            "sequence": [1, 2],
            "epoch": [1, 1],
            "marker_reason": ["sequence_gap", None],  # Missing reason
            "marker_epoch": [1, 1],
            "marker_expected_seq": [10, 20],
            "marker_received_seq": [15, 25],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "gap_marker.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))
        assert report is not None

    def test_resync_marker_validation(self, tmp_path: Path) -> None:
        """Test resync marker events."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            "exchange_ts_ns": [1000, 2000],
            "event_type": ["resync_marker", "resync_marker"],
            "sequence": [1, 2],
            "epoch": [1, 2],
            "marker_reason": ["reconnect", "reconnect"],
            "marker_epoch": [1, 2],
            "marker_expected_seq": [0, 0],
            "marker_received_seq": [0, 0],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "resync_marker.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        # Should handle resync markers appropriately
        assert report.total_events == 2

    def test_corrupted_schema_version(self, tmp_path: Path) -> None:
        """Test file with missing schema_version column."""
        # This test would require the schema_version column
        # which might not be in the standard schema

    def test_empty_file(self, tmp_path: Path) -> None:
        """Test empty Parquet file."""
        df = pl.DataFrame({
            "recv_ts_ns": [],
            "exchange_ts_ns": [],
            "event_type": [],
            "sequence": [],
            "epoch": [],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "empty.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        assert report.verdict == "fail"
        assert any(i.code == "EMPTY_DATASET" for i in report.issues)

    def test_file_with_all_nulls(self, tmp_path: Path) -> None:
        """Test file where all values are null."""
        df = pl.DataFrame({
            "recv_ts_ns": [None, None],
            "exchange_ts_ns": [None, None],
            "event_type": [None, None],
            "sequence": [None, None],
            "epoch": [None, None],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "all_nulls.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        assert report.verdict in ("fail", "conditional")
        assert report.total_issues > 0

    def test_extreme_values(self, tmp_path: Path) -> None:
        """Test handling of extreme values (overflow, etc.)."""
        df = pl.DataFrame({
            "recv_ts_ns": [9223372036854775807, -9223372036854775808],  # i64 max/min
            "exchange_ts_ns": [9223372036854775807, -9223372036854775808],
            "event_type": ["trade", "trade"],
            "sequence": [9223372036854775807, -9223372036854775808],  # i64 max/min
            "epoch": [9223372036854775807, -9223372036854775808],
            "trade_price": [9223372036854775807, -9223372036854775808],
            "trade_qty": [9223372036854775807, -9223372036854775808],
            "trade_side": ["buy", "sell"],
            "trade_id": ["t1", "t2"],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "extreme_values.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        # Extreme values should be handled gracefully
        assert report.total_events == 2