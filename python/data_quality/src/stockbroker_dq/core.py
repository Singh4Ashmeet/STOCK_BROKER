"""Core data structures for data quality validation."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class IssueSeverity(str, Enum):
    """Severity level for quality issues."""
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


class EventType(str, Enum):
    """Event types in the event log."""
    BOOK_SNAPSHOT = "book_snapshot"
    BOOK_DELTA = "book_delta"
    TRADE = "trade"
    GAP_MARKER = "gap_marker"
    RESYNC_MARKER = "resync_marker"
    RAW_FRAME = "raw_frame"
    RECONNECT_MARKER = "reconnect_marker"


class QualityIssue(BaseModel):
    """A single data quality issue found during validation."""
    model_config = ConfigDict(frozen=True)

    severity: IssueSeverity
    code: str
    message: str
    event_index: int | None = None
    event_type: EventType | None = None
    column: str | None = None
    expected: Any | None = None
    actual: Any | None = None
    context: dict[str, Any] = Field(default_factory=dict)


class EventSchema(BaseModel):
    """Schema definition for event columns and types."""
    model_config = ConfigDict(frozen=True)

    # Required columns for all events
    recv_ts_ns: str = "recv_ts_ns"
    exchange_ts_ns: str = "exchange_ts_ns"
    event_type: str = "event_type"
    sequence: str = "sequence"
    epoch: str = "epoch"

    # Book-specific columns
    bid_prices: str | None = "bid_prices"
    bid_qtys: str | None = "bid_qtys"
    ask_prices: str | None = "ask_prices"
    ask_qtys: str | None = "ask_qtys"

    # Trade-specific columns
    trade_price: str | None = "trade_price"
    trade_qty: str | None = "trade_qty"
    trade_side: str | None = "trade_side"
    trade_id: str | None = "trade_id"

    # Marker columns
    marker_reason: str | None = "marker_reason"
    marker_epoch: str | None = "marker_epoch"
    marker_expected_seq: str | None = "marker_expected_seq"
    marker_received_seq: str | None = "marker_received_seq"

    @property
    def required_columns(self) -> list[str]:
        return [
            self.recv_ts_ns,
            self.exchange_ts_ns,
            self.event_type,
            self.sequence,
            self.epoch,
        ]

    def get_event_columns(self, event_type: str) -> list[str]:
        """Get columns relevant for a specific event type."""
        base = list(self.required_columns)
        extra: list[str | None] = []
        if event_type in ("book_snapshot", "book_delta"):
            extra = [self.bid_prices, self.bid_qtys, self.ask_prices, self.ask_qtys]
        elif event_type == "trade":
            extra = [self.trade_price, self.trade_qty, self.trade_side, self.trade_id]
        elif event_type in ("gap_marker", "resync_marker"):
            extra = [self.marker_reason, self.marker_epoch, self.marker_expected_seq, self.marker_received_seq]
        return base + [c for c in extra if c is not None]


class DataQualityConfig(BaseModel):
    """Configuration for data quality validation."""
    model_config = ConfigDict(frozen=True, populate_by_name=True)

    # Sequence validation
    max_sequence_gap: int = 1000
    allow_sequence_wrap: bool = False
    sequence_column: str = "sequence"

    # Timestamp validation
    max_timestamp_jitter_ns: int = 1_000_000_000  # 1 second
    max_recv_before_exchange_ns: int = 60_000_000_000  # 60 seconds
    timestamp_column: str = "exchange_ts_ns"
    recv_ts_column: str = "recv_ts_ns"

    # Duplicate detection
    check_duplicates: bool = True
    duplicate_subset: list[str] = Field(default_factory=lambda: ["sequence", "epoch", "event_type"])

    # Ordering
    check_ordering: bool = True
    ordering_column: str = "exchange_ts_ns"
    allow_equal_timestamps: bool = True

    # Event type specific
    event_schema: EventSchema = Field(default_factory=EventSchema, alias="schema")

    @property
    def schema(self) -> EventSchema:  # type: ignore[override]
        return self.event_schema

    # Validation thresholds
    max_warnings_per_event: int = 10
    max_errors_per_file: int = 1000

    # Output
    include_passing_events: bool = False
    sample_size: int | None = None


class DataQualityReport(BaseModel):
    """Complete data quality report for a file or dataset."""
    model_config = ConfigDict(arbitrary_types_allowed=True)

    file_path: str
    validated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    total_events: int = 0
    events_by_type: dict[str, int] = Field(default_factory=dict)

    # Issue counts
    total_issues: int = 0
    issues_by_severity: dict[IssueSeverity, int] = Field(default_factory=dict)
    issues_by_code: dict[str, int] = Field(default_factory=dict)

    # Detailed issues (limited to max_errors_per_file)
    issues: list[QualityIssue] = Field(default_factory=list)

    # Summary statistics
    sequence_gaps: int = 0
    max_sequence_gap: int = 0
    duplicate_events: int = 0
    out_of_order_events: int = 0
    timestamp_anomalies: int = 0

    # Event type distribution
    events_by_type_detail: dict[str, dict[str, Any]] = Field(default_factory=dict)

    # File metadata
    file_size_bytes: int = 0
    validation_duration_ms: float = 0.0

    # Overall verdict
    verdict: str = "pending"  # "pass", "fail", "conditional"

    def add_issue(self, issue: QualityIssue) -> None:
        """Add an issue to the report."""
        self.issues.append(issue)
        self.total_issues += 1
        self.issues_by_severity[issue.severity] = self.issues_by_severity.get(issue.severity, 0) + 1
        self.issues_by_code[issue.code] = self.issues_by_code.get(issue.code, 0) + 1

        # Update counters based on issue code
        if "gap" in issue.code.lower():
            self.sequence_gaps += 1
        elif "duplicate" in issue.code.lower():
            self.duplicate_events += 1
        elif "order" in issue.code.lower() or "sequence" in issue.code.lower():
            self.out_of_order_events += 1
        elif "timestamp" in issue.code.lower() or "time" in issue.code.lower():
            self.timestamp_anomalies += 1

    def finalize(self, config: DataQualityConfig) -> None:
        """Finalize the report and determine verdict."""
        # Determine overall verdict
        error_count = self.issues_by_severity.get(IssueSeverity.ERROR, 0)
        critical_count = self.issues_by_severity.get(IssueSeverity.CRITICAL, 0)

        if critical_count > 0 or error_count > config.max_errors_per_file:
            self.verdict = "fail"
        elif error_count > 0:
            self.verdict = "conditional"
        else:
            self.verdict = "pass"

        # Limit issues to max_errors_per_file
        if len(self.issues) > config.max_errors_per_file:
            self.issues = self.issues[:config.max_errors_per_file]

    def to_dict(self) -> dict[str, Any]:
        """Convert report to dictionary for serialization."""
        return {
            "file_path": self.file_path,
            "validated_at": self.validated_at.isoformat(),
            "total_events": self.total_events,
            "events_by_type": self.events_by_type,
            "total_issues": self.total_issues,
            "issues_by_severity": {k.value: v for k, v in self.issues_by_severity.items()},
            "issues_by_code": self.issues_by_code,
            "issues": [
                {
                    "severity": i.severity.value,
                    "code": i.code,
                    "message": i.message,
                    "event_index": i.event_index,
                    "event_type": i.event_type.value if i.event_type else None,
                    "column": i.column,
                    "expected": i.expected,
                    "actual": i.actual,
                    "context": i.context,
                }
                for i in self.issues
            ],
            "summary": {
                "sequence_gaps": self.sequence_gaps,
                "max_sequence_gap": self.max_sequence_gap,
                "duplicate_events": self.duplicate_events,
                "out_of_order_events": self.out_of_order_events,
                "timestamp_anomalies": self.timestamp_anomalies,
            },
            "events_by_type_detail": self.events_by_type_detail,
            "file_size_bytes": self.file_size_bytes,
            "validation_duration_ms": self.validation_duration_ms,
            "verdict": self.verdict,
        }

    def to_json(self) -> str:
        """Serialize report to JSON."""
        return json.dumps(self.to_dict(), indent=2, default=str)