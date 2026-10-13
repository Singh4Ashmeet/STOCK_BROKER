"""Tests for the data quality validator."""

from __future__ import annotations

from pathlib import Path

import polars as pl
import pytest

from stockbroker_dq.core import DataQualityConfig, IssueSeverity
from stockbroker_dq.loader import ParquetLoader, load_events
from stockbroker_dq.validator import EventValidator, validate_events


class TestParquetLoader:
    """Tests for the Parquet loader."""

    def test_load_valid_file(self, tmp_path: Path) -> None:
        """Test loading a valid Parquet file."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000],
            "exchange_ts_ns": [1000, 2000, 3000],
            "event_type": ["book_snapshot", "book_delta", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
            "bid_prices": [[100, 99], [101, 100], None],
            "bid_qtys": [[10, 20], [15, 25], None],
            "ask_prices": [[101, 102], [102, 103], None],
            "ask_qtys": [[10, 20], [15, 25], None],
            "trade_price": [None, None, 50000],
            "trade_qty": [None, None, 100],
            "trade_side": [None, None, "buy"],
            "trade_id": [None, None, "trade_1"],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "test.parquet"
        df.write_parquet(file_path)

        loader = ParquetLoader()
        loaded = loader.load_file(str(file_path))

        assert len(loaded) == 3
        assert "recv_ts_ns" in loaded.columns
        assert "__index" in loaded.columns

    def test_load_missing_file(self) -> None:
        """Test loading a non-existent file raises error."""
        loader = ParquetLoader()
        with pytest.raises(FileNotFoundError):
            loader.load_file("nonexistent.parquet")

    def test_load_missing_columns(self, tmp_path: Path) -> None:
        """Test loading file with missing required columns raises error."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            # Missing exchange_ts_ns, event_type, sequence, epoch
        })
        df = df.with_row_index("__index")
        file_path = tmp_path / "bad.parquet"
        df.write_parquet(file_path)

        loader = ParquetLoader()
        with pytest.raises(ValueError, match="Missing required columns"):
            loader.load_file(str(file_path))

    def test_load_partition(self, tmp_path: Path) -> None:
        """Test loading a partition with multiple files."""
        partition_dir = tmp_path / "partition"
        partition_dir.mkdir()

        df1 = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            "exchange_ts_ns": [1000, 2000],
            "event_type": ["book_snapshot", "trade"],
            "sequence": [1, 1],
            "epoch": [1, 1],
        })
        df1 = df1.with_row_index("__index")
        df2 = pl.DataFrame({
            "recv_ts_ns": [3000, 4000],
            "exchange_ts_ns": [3000, 4000],
            "event_type": ["book_delta", "trade"],
            "sequence": [1, 2],
            "epoch": [1, 1],
        })
        df2 = df2.with_row_index("__index")

        (tmp_path / "partition" / "file1.parquet").parent.mkdir(parents=True, exist_ok=True)
        df1.write_parquet(tmp_path / "partition" / "file1.parquet")
        df2.write_parquet(tmp_path / "partition" / "file2.parquet")

        loader = ParquetLoader()
        loaded = loader.load_partition(str(tmp_path / "partition"))

        assert len(loaded) == 4


class TestEventValidator:
    """Tests for the event validator."""

    def create_valid_df(self) -> pl.DataFrame:
        """Create a valid test DataFrame."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000, 4000, 5000],
            "exchange_ts_ns": [1000, 2000, 3000, 4000, 5000],
            "event_type": ["book_snapshot", "book_delta", "book_delta", "trade", "book_delta"],
            "sequence": [1, 2, 3, 1, 4],
            "epoch": [1, 1, 1, 1, 1],
            "bid_prices": [[100, 99], [101, 100], [102, 101], None, [103, 102]],
            "bid_qtys": [[10, 20], [15, 25], [10, 20], None, [15, 25]],
            "ask_prices": [[101, 102], [102, 103], [103, 104], None, [104, 105]],
            "ask_qtys": [[10, 20], [15, 25], [10, 20], None, [15, 25]],
            "trade_price": [None, None, None, 50000, None],
            "trade_qty": [None, None, None, 100, None],
            "trade_side": [None, None, None, "buy", None],
            "trade_id": [None, None, None, "trade_1", None],
        })
        df = df.with_row_index("__index")
        return df

    def test_valid_data_passes(self) -> None:
        """Test that valid data passes validation."""
        df = self.create_valid_df()
        config = DataQualityConfig()

        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert report.verdict == "pass"
        assert report.total_events == 5
        assert report.total_issues == 0

    def test_empty_dataset_fails(self) -> None:
        """Test that empty dataset fails."""
        df = pl.DataFrame({
            "recv_ts_ns": [],
            "exchange_ts_ns": [],
            "event_type": [],
            "sequence": [],
            "epoch": [],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig()
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "empty.parquet")

        assert report.verdict == "fail"
        assert any(i.code == "EMPTY_DATASET" for i in report.issues)

    def test_missing_columns(self) -> None:
        """Test detection of missing required columns."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            "exchange_ts_ns": [1000, 2000],
            # Missing event_type, sequence, epoch
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig()
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert report.verdict == "fail"
        assert any(i.code == "MISSING_REQUIRED_COLUMN" for i in report.issues)

    def test_duplicate_detection(self) -> None:
        """Test duplicate event detection."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 1000, 3000],
            "exchange_ts_ns": [1000, 1000, 3000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 1, 3],  # Duplicate sequence 1
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(check_duplicates=True)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert report.verdict in ("fail", "conditional")
        assert report.duplicate_events >= 1
        assert any(i.code == "DUPLICATE_EVENT" for i in report.issues)

    def test_sequence_gap_detection(self) -> None:
        """Test sequence gap detection."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 4000, 5000],
            "exchange_ts_ns": [1000, 2000, 4000, 5000],
            "event_type": ["book_delta", "book_delta", "book_delta", "book_delta"],
            "sequence": [1, 2, 5, 6],  # Gap: missing 3, 4 (gap of 3)
            "epoch": [1, 1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(max_sequence_gap=2)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert report.sequence_gaps >= 1
        assert report.max_sequence_gap >= 3
        assert any(i.code == "SEQUENCE_GAP" for i in report.issues)

    def test_out_of_order_detection(self) -> None:
        """Test out-of-order timestamp detection."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 3000, 2000, 4000],  # 2000 comes after 3000
            "exchange_ts_ns": [1000, 3000, 2000, 4000],
            "event_type": ["book_delta", "book_delta", "book_delta", "book_delta"],
            "sequence": [1, 2, 3, 4],
            "epoch": [1, 1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(check_ordering=True, allow_equal_timestamps=False)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert any(i.code == "OUT_OF_ORDER" for i in report.issues)

    def test_timestamp_anomalies(self) -> None:
        """Test timestamp anomaly detection."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000],
            "exchange_ts_ns": [1000, -100, 3000],  # Negative timestamp in exchange_ts
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig()
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert any(i.code == "INVALID_TIMESTAMP" for i in report.issues)

    def test_clock_skew_detection(self) -> None:
        """Test clock skew detection (recv_ts significantly before exchange_ts)."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000],
            "exchange_ts_ns": [2000000000000, 2000, 3000],  # recv_ts way before exchange_ts
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(max_recv_before_exchange_ns=100_000_000)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert any(i.code == "CLOCK_SKEW" for i in report.issues)

    def test_excessive_latency(self) -> None:
        """Test excessive latency detection."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000000000000, 3000],  # 2000s latency
            "exchange_ts_ns": [1000, 2000, 3000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(max_recv_before_exchange_ns=100_000_000)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert any(i.code == "EXCESSIVE_LATENCY" for i in report.issues)

    def test_clock_skew_negative_latency(self) -> None:
        """Test clock skew detection (recv_ts significantly before exchange_ts)."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, -1000000000000, 3000],  # recv_ts way before
            "exchange_ts_ns": [1000, 2000, 3000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(max_timestamp_jitter_ns=100_000_000)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert any(i.code == "CLOCK_SKEW" for i in report.issues)

    def test_sequence_gap_per_epoch(self) -> None:
        """Test sequence gap detection respects epoch boundaries."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000, 4000, 5000],
            "exchange_ts_ns": [1000, 2000, 3000, 4000, 5000],
            "event_type": ["book_delta", "book_delta", "book_delta", "book_delta", "book_delta"],
            "sequence": [1, 2, 1, 2, 3],  # Reset at epoch 2
            "epoch": [1, 1, 2, 2, 2],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(max_sequence_gap=0)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        # Should NOT flag gap between epoch 1 seq 2 and epoch 2 seq 1
        # Since they're in different epochs
        gap_issues = [i for i in report.issues if i.code == "SEQUENCE_GAP"]
        assert len(gap_issues) == 0

    def test_validate_file_integration(self, tmp_path: Path) -> None:
        """Integration test validating a file."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 3000, 2000],  # Out of order
            "exchange_ts_ns": [1000, 3000, 2000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "test.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        assert report.total_events == 3
        assert any(i.code == "OUT_OF_ORDER" for i in report.issues)


class TestConvenienceFunctions:
    """Test convenience functions."""

    def test_load_events_file(self, tmp_path: Path) -> None:
        """Test load_events convenience function for file."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000],
            "exchange_ts_ns": [1000],
            "event_type": ["trade"],
            "sequence": [1],
            "epoch": [1],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "test.parquet"
        df.write_parquet(file_path)

        loaded = load_events(str(file_path))
        assert len(loaded) == 1

    def test_load_events_partition(self, tmp_path: Path) -> None:
        """Test load_events for partition directory."""
        partition_dir = tmp_path / "partition"
        partition_dir.mkdir()

        df1 = pl.DataFrame({
            "recv_ts_ns": [1000],
            "exchange_ts_ns": [1000],
            "event_type": ["trade"],
            "sequence": [1],
            "epoch": [1],
        })
        df1 = df1.with_row_index("__index")
        df2 = pl.DataFrame({
            "recv_ts_ns": [2000],
            "exchange_ts_ns": [2000],
            "event_type": ["trade"],
            "sequence": [2],
            "epoch": [1],
        })
        df2 = df2.with_row_index("__index")

        df1.write_parquet(partition_dir / "file1.parquet")
        df2.write_parquet(partition_dir / "file2.parquet")

        loaded = load_events(str(partition_dir))
        assert len(loaded) == 2


class TestValidateEventsConvenience:
    """Test validate_events convenience function."""

    def test_validate_events_file(self, tmp_path: Path) -> None:
        """Test validate_events for file."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 3000, 2000],  # Out of order
            "exchange_ts_ns": [1000, 3000, 2000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        file_path = tmp_path / "test.parquet"
        df.write_parquet(file_path)

        report = validate_events(str(file_path))

        assert report.total_events == 3
        assert any(i.code == "OUT_OF_ORDER" for i in report.issues)


class TestDataQualityReport:
    """Tests for DataQualityReport functionality."""

    def test_add_issue_updates_counters(self) -> None:
        from stockbroker_dq.core import DataQualityReport, QualityIssue

        report = DataQualityReport(file_path="test.parquet")
        report.add_issue(QualityIssue(
            severity=IssueSeverity.ERROR,
            code="TEST_ERROR",
            message="Test error",
        ))
        report.add_issue(QualityIssue(
            severity=IssueSeverity.WARNING,
            code="TEST_WARNING",
            message="Test warning",
        ))

        assert report.total_issues == 2
        assert report.issues_by_severity[IssueSeverity.ERROR] == 1
        assert report.issues_by_severity[IssueSeverity.WARNING] == 1
        assert report.issues_by_code["TEST_ERROR"] == 1

    def test_issue_counter_types(self) -> None:
        from stockbroker_dq.core import DataQualityReport, QualityIssue

        report = DataQualityReport(file_path="test.parquet")

        # Gap issue
        report.add_issue(QualityIssue(
            severity=IssueSeverity.ERROR,
            code="SEQUENCE_GAP",
            message="Gap detected",
        ))
        assert report.sequence_gaps == 1

        # Duplicate issue
        report.add_issue(QualityIssue(
            severity=IssueSeverity.ERROR,
            code="DUPLICATE_EVENT",
            message="Duplicate detected",
        ))
        assert report.duplicate_events == 1

        # Out of order issue
        report.add_issue(QualityIssue(
            severity=IssueSeverity.ERROR,
            code="OUT_OF_ORDER",
            message="Out of order",
        ))
        assert report.out_of_order_events == 1

        # Timestamp anomaly
        report.add_issue(QualityIssue(
            severity=IssueSeverity.ERROR,
            code="INVALID_TIMESTAMP",
            message="Bad timestamp",
        ))
        assert report.timestamp_anomalies == 1


class TestEdgeCases:
    """Tests for edge cases and boundary conditions."""

    def test_max_sequence_gap_zero(self) -> None:
        """Test with max_sequence_gap=0 (no gaps allowed)."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000],
            "exchange_ts_ns": [1000, 2000],
            "event_type": ["book_delta", "book_delta"],
            "sequence": [1, 2],  # No gap
            "epoch": [1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(max_sequence_gap=0)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        # Should pass with no gaps
        gap_issues = [i for i in report.issues if i.code == "SEQUENCE_GAP"]
        assert len(gap_issues) == 0

    def test_allow_equal_timestamps(self) -> None:
        """Test that equal timestamps are allowed by default."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 1000, 2000],  # Equal timestamps
            "exchange_ts_ns": [1000, 1000, 2000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(allow_equal_timestamps=True)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        # Should NOT flag equal timestamps as out of order when allowed
        order_issues = [i for i in report.issues if i.code == "OUT_OF_ORDER"]
        assert len(order_issues) == 0

    def test_disallow_equal_sequences(self) -> None:
        """Test that equal sequence numbers are flagged when not allowed."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000],
            "exchange_ts_ns": [1000, 2000, 3000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 2],  # Duplicate sequence number
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(allow_equal_timestamps=False)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert any(i.code == "OUT_OF_ORDER" for i in report.issues)

    def test_sequence_gap_at_epoch_boundary(self) -> None:
        """Test that sequence gaps are not reported across epoch boundaries."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 2000, 3000, 4000],
            "exchange_ts_ns": [1000, 2000, 3000, 4000],
            "event_type": ["book_delta", "book_delta", "book_delta", "book_delta"],
            "sequence": [1, 5, 1, 2],  # Gap within epoch 1, reset at epoch 2
            "epoch": [1, 1, 2, 2],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(max_sequence_gap=2)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        # Gap of 4 (5-1) within epoch 1 should be detected
        # Epoch change should not trigger gap
        gap_issues = [i for i in report.issues if i.code == "SEQUENCE_GAP"]
        assert len(gap_issues) == 1
        assert gap_issues[0].context.get("epoch") == 1


class TestConfigOptions:
    """Tests for configuration options."""

    def test_disable_duplicate_check(self) -> None:
        """Test disabling duplicate check."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 1000, 3000],
            "exchange_ts_ns": [1000, 1000, 3000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 1, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(check_duplicates=False)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        dup_issues = [i for i in report.issues if i.code == "DUPLICATE_EVENT"]
        assert len(dup_issues) == 0

    def test_disable_ordering_check(self) -> None:
        """Test disabling ordering check."""
        df = pl.DataFrame({
            "recv_ts_ns": [1000, 3000, 2000],
            "exchange_ts_ns": [1000, 3000, 2000],
            "event_type": ["trade", "trade", "trade"],
            "sequence": [1, 2, 3],
            "epoch": [1, 1, 1],
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(check_ordering=False)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        order_issues = [i for i in report.issues if i.code == "OUT_OF_ORDER"]
        assert len(order_issues) == 0

    def test_max_errors_limit(self) -> None:
        """Test that max_errors_per_file limits reported issues."""
        df = pl.DataFrame({
            "recv_ts_ns": list(range(100)),
            "exchange_ts_ns": list(range(100, 0, -1)),  # All out of order
            "event_type": ["trade"] * 100,
            "sequence": list(range(100)),
            "epoch": [1] * 100,
        })
        df = df.with_row_index("__index")

        config = DataQualityConfig(max_errors_per_file=5, check_ordering=True, allow_equal_timestamps=False)
        validator = EventValidator(config)
        report = validator.validate_dataframe(df, "test.parquet")

        assert len(report.issues) <= 5
        assert report.verdict == "fail"  # Too many errors

