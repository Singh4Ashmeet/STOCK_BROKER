"""Event validator for detecting gaps, duplicates, and out-of-order events."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

import polars as pl

from .core import (
    DataQualityConfig,
    DataQualityReport,
    EventType,
    IssueSeverity,
    QualityIssue,
)
from .loader import ParquetLoader


class EventValidator:
    """Validates event data for quality issues."""

    def __init__(self, config: DataQualityConfig | None = None) -> None:
        self.config = config or DataQualityConfig()
        self.schema = self.config.schema

    def validate_file(self, file_path: str, **kwargs: Any) -> DataQualityReport:
        """Validate a single Parquet file."""
        loader = ParquetLoader(self.config)
        start_time = time.perf_counter()

        df = loader.load_file(file_path)
        # Only add __index if it doesn't already exist
        if "__index" not in df.columns:
            df = df.with_row_index("__index")
        report = self.validate_dataframe(df, file_path)

        report.file_size_bytes = Path(file_path).stat().st_size
        report.validation_duration_ms = (time.perf_counter() - start_time) * 1000
        report.finalize(self.config)

        return report

    def validate_partition(self, partition_dir: str, **kwargs: Any) -> DataQualityReport:
        """Validate all files in a partition directory."""
        loader = ParquetLoader(self.config)
        start_time = time.perf_counter()

        df = loader.load_partition(partition_dir)
        report = self.validate_dataframe(df, partition_dir)

        report.file_size_bytes = sum(f.stat().st_size for f in Path(partition_dir).rglob("*.parquet"))
        report.validation_duration_ms = (time.perf_counter() - start_time) * 1000
        report.finalize(self.config)

        return report

    def validate_dataset(self, root_dir: str, **kwargs: Any) -> DataQualityReport:
        """Validate entire dataset."""
        loader = ParquetLoader(self.config)
        start_time = time.perf_counter()

        df = loader.load_dataset(root_dir)
        report = self.validate_dataframe(df, root_dir)

        report.file_size_bytes = sum(f.stat().st_size for f in Path(root_dir).rglob("*.parquet"))
        report.validation_duration_ms = (time.perf_counter() - start_time) * 1000
        report.finalize(self.config)

        return report

    def validate_dataframe(self, df: pl.DataFrame, source: str) -> DataQualityReport:
        """Validate a Polars DataFrame."""
        report = DataQualityReport(file_path=source)
        report.total_events = len(df)

        if report.total_events == 0:
            report.add_issue(QualityIssue(
                severity=IssueSeverity.CRITICAL,
                code="EMPTY_DATASET",
                message="Dataset contains no events",
            ))
            report.finalize(self.config)
            return report

        # Count events by type
        if self.schema.event_type in df.columns:
            type_counts = df.group_by(self.schema.event_type).len().to_dict()
            report.events_by_type = dict(zip(
                type_counts[self.schema.event_type],
                type_counts["len"]
            ))

        # Run validation checks
        self._check_required_columns(df, report)
        self._check_duplicates(df, report)
        self._check_sequence_gaps(df, report)
        self._check_ordering(df, report)
        self._check_timestamps(df, report)
        self._check_event_specific(df, report)

        # Build event type detail
        if self.schema.event_type in df.columns:
            report.events_by_type_detail = self._build_type_detail(df)

        report.finalize(self.config)
        return report

    def _check_required_columns(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Check for missing required columns."""
        for col in self.schema.required_columns:
            if col not in df.columns:
                report.add_issue(QualityIssue(
                    severity=IssueSeverity.CRITICAL,
                    code="MISSING_REQUIRED_COLUMN",
                    message=f"Required column '{col}' is missing from dataset",
                    column=col,
                ))

    def _check_duplicates(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Check for duplicate events."""
        if not self.config.check_duplicates:
            return

        subset = [c for c in self.config.duplicate_subset if c in df.columns]
        if not subset:
            return

        # Find duplicates using group_by to identify duplicate groups
        subset_cols = [c for c in subset if c in df.columns]
        if not subset_cols:
            return

        # Add row index if not present
        df_with_idx = df
        if "__index" not in df.columns:
            df_with_idx = df.with_row_index("__index")

        # Group by subset columns and find groups with count > 1
        dup_groups = df_with_idx.group_by(subset, maintain_order=True).agg(
            pl.col("__index").alias("indices")
        ).filter(pl.col("indices").list.len() > 1)

        for row in dup_groups.iter_rows(named=True):
            indices = row["indices"][1:]  # Skip first occurrence
            for idx in indices[:self.config.max_errors_per_file]:
                report.add_issue(QualityIssue(
                    severity=IssueSeverity.ERROR,
                    code="DUPLICATE_EVENT",
                    message=f"Duplicate event detected at index {idx}",
                    event_index=idx,
                    event_type=self._get_event_type(pl.DataFrame([df.row(idx, named=True)])),
                    column=",".join(subset),
                    context={"duplicate_indices": [idx]},
                ))

    def _check_sequence_gaps(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Check for sequence gaps per epoch and event type."""
        if self.config.sequence_column not in df.columns or self.schema.epoch not in df.columns:
            return

        # Group by epoch and event type, then check sequence continuity
        group_cols = [self.schema.epoch, self.schema.event_type]
        available_cols = [c for c in group_cols if c in df.columns]

        if not available_cols:
            return

        # Sort by epoch, event_type, sequence
        sort_cols = available_cols + [self.config.sequence_column]
        available_sort = [c for c in sort_cols if c in df.columns]
        if not available_sort:
            return

        df_sorted = df.sort(available_sort)

        # Calculate gaps within each group
        for (epoch_val, event_type_val), group in df_sorted.group_by(available_cols, maintain_order=True):
            seq_col = self.config.sequence_column
            if seq_col not in group.columns:
                continue

            # Filter out null sequences
            sequences = [s for s in group[seq_col].to_list() if s is not None]
            if len(sequences) < 2:
                continue

            for i in range(1, len(sequences)):
                gap = sequences[i] - sequences[i - 1]
                if gap > 1:
                    report.max_sequence_gap = max(report.max_sequence_gap, gap)
                    if gap > self.config.max_sequence_gap:
                        # Find original index
                        mask = (df[self.schema.epoch] == epoch_val) & \
                               (df[self.schema.event_type] == event_type_val) & \
                               (df[self.config.sequence_column] == sequences[i])
                        indices = df.with_row_index("__idx").filter(mask)["__idx"].to_list()
                        idx = indices[0] if indices else None

                        report.add_issue(QualityIssue(
                            severity=IssueSeverity.WARNING if gap <= self.config.max_sequence_gap * 2 else IssueSeverity.ERROR,
                            code="SEQUENCE_GAP",
                            message=f"Sequence gap detected: expected {sequences[i-1]+1}, got {sequences[i]} (gap={gap})",
                            event_index=idx,
                            event_type=EventType(event_type_val) if event_type_val in EventType.__members__ else None,
                            column=self.config.sequence_column,
                            expected=sequences[i-1] + 1,
                            actual=sequences[i],
                            context={
                                "epoch": epoch_val,
                                "event_type": event_type_val,
                                "gap_size": gap,
                            },
                        ))

    def _check_ordering(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Check for out-of-order events by verifying sequence numbers increase with timestamps."""
        if not self.config.check_ordering:
            return

        if self.config.ordering_column not in df.columns:
            return

        if self.schema.epoch not in df.columns or self.schema.event_type not in df.columns:
            return

        if self.config.sequence_column not in df.columns:
            return

        # Sort by epoch, event_type, and timestamp
        sort_cols = [c for c in [self.schema.epoch, self.schema.event_type, self.config.ordering_column] if c in df.columns]
        if len(sort_cols) < 2:
            return

        df_sorted = df.sort(sort_cols)

        # Check ordering within each epoch+event_type group
        # After sorting by timestamp, sequence numbers should be monotonically increasing
        group_cols = [c for c in [self.schema.epoch, self.schema.event_type] if c in df.columns]
        if not group_cols:
            return

        for (epoch_val, event_type_val), group in df_sorted.group_by(group_cols, maintain_order=True):
            sequences = [s for s in group[self.config.sequence_column].to_list() if s is not None]
            if len(sequences) < 2:
                continue

            for i in range(1, len(sequences)):
                if sequences[i] < sequences[i - 1]:
                    # Sequence number decreased - out of order
                    mask = (df[self.schema.epoch] == epoch_val) & \
                           (df[self.schema.event_type] == event_type_val) & \
                           (df[self.config.sequence_column] == sequences[i])
                    indices = df.with_row_index("__idx").filter(mask)["__idx"].to_list()
                    idx = indices[0] if indices else None

                    report.add_issue(QualityIssue(
                        severity=IssueSeverity.ERROR,
                        code="OUT_OF_ORDER",
                        message=f"Out-of-order event: sequence {sequences[i]} arrived before {sequences[i-1]}",
                        event_index=idx,
                        event_type=EventType(event_type_val) if event_type_val in EventType.__members__ else None,
                        column=self.config.sequence_column,
                        expected=f">{sequences[i-1]}",
                        actual=str(sequences[i]),
                        context={"epoch": epoch_val, "event_type": event_type_val},
                    ))
                elif not self.config.allow_equal_timestamps and sequences[i] == sequences[i - 1]:
                    # Equal sequence numbers - also an issue
                    mask = (df[self.schema.epoch] == epoch_val) & \
                           (df[self.schema.event_type] == event_type_val) & \
                           (df[self.config.sequence_column] == sequences[i])
                    indices = df.with_row_index("__idx").filter(mask)["__idx"].to_list()
                    idx = indices[0] if indices else None

                    report.add_issue(QualityIssue(
                        severity=IssueSeverity.WARNING,
                        code="OUT_OF_ORDER",
                        message=f"Duplicate sequence number: {sequences[i]}",
                        event_index=idx,
                        event_type=EventType(event_type_val) if event_type_val in EventType.__members__ else None,
                        column=self.config.sequence_column,
                        expected=f"!={sequences[i-1]}",
                        actual=str(sequences[i]),
                        context={"epoch": epoch_val, "event_type": event_type_val},
                    ))

    def _check_timestamps(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Check for timestamp anomalies."""
        ts_col = self.config.timestamp_column
        recv_col = self.config.recv_ts_column

        if ts_col not in df.columns:
            return

        # Check for negative or zero timestamps
        invalid_ts = df.filter(pl.col(ts_col) <= 0)
        if len(invalid_ts) > 0:
            for idx in invalid_ts.get_column("__index").to_list() if "__index" in invalid_ts.columns else []:
                report.add_issue(QualityIssue(
                    severity=IssueSeverity.ERROR,
                    code="INVALID_TIMESTAMP",
                    message=f"Non-positive timestamp: {invalid_ts[ts_col].to_list()[0]}",
                    event_index=idx,
                    column=ts_col,
                    expected="> 0",
                    actual=invalid_ts[ts_col].to_list()[0],
                ))

        # Check for recv_ts before exchange_ts (excessive latency)
        if recv_col in df.columns and ts_col in df.columns:
            # Filter out null values before computing latency
            valid_latency_df = df.filter(
                pl.col(recv_col).is_not_null() & pl.col(ts_col).is_not_null()
            ).with_columns(
                (pl.col(recv_col) - pl.col(ts_col)).alias("latency")
            )
            if len(valid_latency_df) > 0:
                excessive_latency = valid_latency_df.filter(pl.col("latency") > self.config.max_recv_before_exchange_ns)
                if len(excessive_latency) > 0:
                    for idx in excessive_latency.get_column("__index").to_list() if "__index" in excessive_latency.columns else []:
                        report.add_issue(QualityIssue(
                            severity=IssueSeverity.WARNING,
                            code="EXCESSIVE_LATENCY",
                            message="Excessive recv-to-exchange latency",
                            event_index=idx,
                            column=recv_col,
                            context={"latency_ns": excessive_latency["latency"].to_list()[0]},
                        ))

            # Check for recv_ts significantly before exchange_ts (clock skew)
            negative_latency = valid_latency_df.filter(pl.col("latency") < -self.config.max_timestamp_jitter_ns)
            if len(negative_latency) > 0:
                for idx in negative_latency.get_column("__index").to_list() if "__index" in negative_latency.columns else []:
                    report.add_issue(QualityIssue(
                        severity=IssueSeverity.ERROR,
                        code="CLOCK_SKEW",
                        message="recv_ts before exchange_ts beyond jitter threshold",
                        event_index=idx,
                        column=recv_col,
                        context={"latency_ns": (negative_latency[recv_col] - negative_latency[ts_col]).to_list()[0]},
                    ))

    def _check_event_specific(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Event-type specific validation."""
        if self.schema.event_type not in df.columns:
            return

        # Validate event types for all events
        self._validate_event_types(df, report)

        # Validate book events have price/quantity arrays
        if "book_snapshot" in df[self.schema.event_type].unique():
            self._validate_book_events(df.filter(pl.col(self.schema.event_type) == "book_snapshot"), report)

        if "book_delta" in df[self.schema.event_type].unique():
            self._validate_book_events(df.filter(pl.col(self.schema.event_type) == "book_delta"), report)

        if "trade" in df[self.schema.event_type].unique():
            self._validate_trade_events(df.filter(pl.col(self.schema.event_type) == "trade"), report)

    def _validate_event_types(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Validate event types for all events."""
        if self.schema.event_type not in df.columns:
            return

        valid_event_types = {e.value for e in EventType}
        invalid_events = df.filter(~pl.col(self.schema.event_type).is_in(list(valid_event_types)))
        if len(invalid_events) > 0:
            for idx in invalid_events.get_column("__index").to_list() if "__index" in invalid_events.columns else []:
                report.add_issue(QualityIssue(
                    severity=IssueSeverity.ERROR,
                    code="INVALID_EVENT_TYPE",
                    message="Invalid event type",
                    event_index=idx,
                    column=self.schema.event_type,
                ))

    def _validate_book_events(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Validate book snapshot/delta events."""
        for col in [self.schema.bid_prices, self.schema.bid_qtys, self.schema.ask_prices, self.schema.ask_qtys]:
            if col and col in df.columns:
                # Check for null arrays
                null_count = df[col].null_count()
                if null_count > 0:
                    report.add_issue(QualityIssue(
                        severity=IssueSeverity.WARNING,
                        code="NULL_BOOK_ARRAY",
                        message=f"Null book array in column {col}",
                        column=col,
                    ))

    def _validate_trade_events(self, df: pl.DataFrame, report: DataQualityReport) -> None:
        """Validate trade events."""
        for col in [self.schema.trade_price, self.schema.trade_qty, self.schema.trade_side, self.schema.trade_id]:
            if col and col in df.columns:
                null_count = df[col].null_count()
                if null_count > 0:
                    report.add_issue(QualityIssue(
                        severity=IssueSeverity.ERROR,
                        code="NULL_TRADE_FIELD",
                        message=f"Null trade field: {col}",
                        column=col,
                    ))

        # Validate price is positive
        if self.schema.trade_price in df.columns:
            negative_prices = df.filter(pl.col(self.schema.trade_price) < 0)
            if len(negative_prices) > 0:
                for idx in negative_prices.get_column("__index").to_list() if "__index" in negative_prices.columns else []:
                    report.add_issue(QualityIssue(
                        severity=IssueSeverity.ERROR,
                        code="NEGATIVE_PRICE",
                        message="Negative trade price detected",
                        event_index=idx,
                        column=self.schema.trade_price,
                    ))

        # Validate quantity is positive
        if self.schema.trade_qty in df.columns:
            zero_quantities = df.filter(pl.col(self.schema.trade_qty) <= 0)
            if len(zero_quantities) > 0:
                for idx in zero_quantities.get_column("__index").to_list() if "__index" in zero_quantities.columns else []:
                    report.add_issue(QualityIssue(
                        severity=IssueSeverity.ERROR,
                        code="ZERO_QUANTITY",
                        message="Zero or negative trade quantity",
                        event_index=idx,
                        column=self.schema.trade_qty,
                    ))

        # Validate side values
        if self.schema.trade_side in df.columns:
            invalid_sides = df.filter(~pl.col(self.schema.trade_side).is_in(["buy", "sell", "BUY", "SELL", "1", "2"]))
            if len(invalid_sides) > 0:
                report.add_issue(QualityIssue(
                    severity=IssueSeverity.ERROR,
                    code="INVALID_TRADE_SIDE",
                    message="Invalid trade side value",
                    column=self.schema.trade_side,
                ))

        # Validate event types
        if self.schema.event_type in df.columns:
            valid_event_types = {e.value for e in EventType}
            invalid_events = df.filter(~pl.col(self.schema.event_type).is_in(list(valid_event_types)))
            if len(invalid_events) > 0:
                for idx in invalid_events.get_column("__index").to_list() if "__index" in invalid_events.columns else []:
                    report.add_issue(QualityIssue(
                        severity=IssueSeverity.ERROR,
                        code="INVALID_EVENT_TYPE",
                        message="Invalid event type",
                        event_index=idx,
                        column=self.schema.event_type,
                    ))

    def _get_event_type(self, row: pl.DataFrame) -> EventType | None:
        """Extract event type from a row."""
        if self.schema.event_type in row.columns:
            val = row[self.schema.event_type][0]
            try:
                return EventType(val)
            except ValueError:
                return None
        return None

    def _build_type_detail(self, df: pl.DataFrame) -> dict[str, dict[str, Any]]:
        """Build detailed statistics per event type."""
        if self.schema.event_type not in df.columns:
            return {}

        detail = {}
        for event_type in df[self.schema.event_type].unique():
            if event_type is None:
                continue
            subset = df.filter(pl.col(self.schema.event_type) == event_type)
            event_type_str = str(event_type)
            detail[event_type_str] = {
                "count": len(subset),
                "null_counts": {col: subset[col].null_count() for col in subset.columns},
                "dtypes": {col: str(subset[col].dtype) for col in subset.columns},
            }

            # Add type-specific stats
            if event_type == "trade" and self.schema.trade_price in df.columns:
                prices = subset[self.schema.trade_price]
                min_v = prices.min()
                max_v = prices.max()
                mean_v = prices.mean()
                detail[event_type_str]["price_stats"] = {
                    "min": float(min_v) if min_v is not None else None,  # type: ignore[arg-type]
                    "max": float(max_v) if max_v is not None else None,  # type: ignore[arg-type]
                    "mean": float(mean_v) if mean_v is not None else None,  # type: ignore[arg-type]
                }

            if event_type in ("book_snapshot", "book_delta") and self.schema.ask_prices in df.columns:
                detail[event_type_str]["book_levels"] = df[self.schema.ask_prices].list.len().max()

        return detail


def validate_events(
    path: str,
    config: DataQualityConfig | None = None,
    **kwargs: Any,
) -> DataQualityReport:
    """Convenience function to validate events from a file or directory."""
    validator = EventValidator(config)
    path_obj = Path(path)

    if path_obj.is_file():
        return validator.validate_file(str(path), **kwargs)
    elif path_obj.is_dir():
        parquet_files = list(Path(path).glob("*.parquet"))
        if parquet_files:
            return validator.validate_partition(str(path), **kwargs)
        else:
            return validator.validate_dataset(str(path), **kwargs)
    else:
        raise ValueError(f"Path does not exist: {path}")


def load_events(
    path: str,
    config: DataQualityConfig | None = None,
    **kwargs: Any,
) -> pl.DataFrame:
    """Convenience function to load events from a file or directory."""
    loader = ParquetLoader(config)
    path_obj = Path(path)

    if path_obj.is_file():
        return loader.load_file(str(path), **kwargs)
    elif path_obj.is_dir():
        parquet_files = list(Path(path).glob("*.parquet"))
        if parquet_files:
            return loader.load_partition(str(path), **kwargs)
        else:
            return loader.load_dataset(str(path), **kwargs)
    else:
        raise ValueError(f"Path does not exist: {path}")