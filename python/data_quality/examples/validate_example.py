#!/usr/bin/env python3
"""Example script demonstrating the data quality validator."""

import tempfile
from pathlib import Path

import polars as pl

from stockbroker_dq import (
    DataQualityConfig,
    EventValidator,
    ParquetLoader,
    validate_events,
)


# Example 1: Validate a single Parquet file
def validate_single_file() -> None:
    config = DataQualityConfig(
        max_sequence_gap=100,
        check_duplicates=True,
        check_ordering=True,
    )
    _ = config
    # This would be a path to your Parquet file
    # report = validate_events("data/events/venue=BINANCE/symbol=BTC-USDT/date=2026-10-09/hour=10/part-0000.parquet", config=config)
    # print(report.to_json())


# Example 2: Load and validate a partition
def validate_partition() -> None:
    config = DataQualityConfig(
        max_sequence_gap=1000,
        check_duplicates=True,
        check_ordering=True,
        max_errors_per_file=100,
    )
    _loader = ParquetLoader(config)
    _validator = EventValidator(config)
    # df = _loader.load_partition("data/events/venue=BINANCE/symbol=BTC-USDT/date=2026-10-09/")
    # report = _validator.validate_dataframe(df, "BTC-USDT 2026-10-09")
    # print(f"Verdict: {report.verdict}")


# Example 3: Create corrupted test data and validate
def create_corrupted_test_data() -> None:
    # Create a DataFrame with various corruptions
    df = pl.DataFrame({
        "recv_ts_ns": [1000, -100, 3000, 4000, 5000],  # Negative timestamp
        "exchange_ts_ns": [1000, 2000, 3000, 4000, 5000],
        "event_type": ["trade", "trade", "trade", "trade", "trade"],
        "sequence": [1, 2, 4, 5, 5],  # Gap (missing 3), duplicate 5
        "epoch": [1, 1, 1, 1, 1],
        "trade_price": [50000, -100, 50000, 50000, 50000],  # Negative price
        "trade_qty": [100, 0, 100, 100, 100],  # Zero qty
        "trade_side": ["buy", "sell", "buy", "invalid", "sell"],  # Invalid side
        "trade_id": ["t1", "t2", "t3", "t4", "t4"],  # Duplicate ID
    }).with_row_index("__index")

    with tempfile.TemporaryDirectory() as tmpdir:
        file_path = Path(tmpdir) / "corrupted_test.parquet"
        df.write_parquet(file_path)

        # Validate
        config = DataQualityConfig()
        report = validate_events(str(file_path), config=config)

        print(f"Verdict: {report.verdict}")
        print(f"Total events: {report.total_events}")
        print(f"Total issues: {report.total_issues}")
        print(f"Gaps: {report.sequence_gaps}")
        print(f"Duplicates: {report.duplicate_events}")
        print(f"Out of order: {report.out_of_order_events}")
        print(f"Timestamp anomalies: {report.timestamp_anomalies}")

        print("\nIssues:")
        for issue in report.issues[:10]:
            print(f"  [{issue.severity.value}] {issue.code}: {issue.message}")


if __name__ == "__main__":
    create_corrupted_test_data()