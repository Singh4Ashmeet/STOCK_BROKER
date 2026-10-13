# StockBroker Data Quality

Data quality loader and validator for Parquet event files. Catches gaps, duplicates, out-of-order events, and other data quality issues before they reach backtesting.

## Features

- **Parquet Loader**: Load single files, partitions, or entire datasets using Polars
- **Gap Detection**: Finds sequence gaps per epoch and event type
- **Duplicate Detection**: Identifies exact and near-duplicate events
- **Ordering Validation**: Detects out-of-order timestamps within epoch/event_type groups
- **Timestamp Validation**: Catches clock skew, excessive latency, invalid timestamps
- **Event-Type Validation**: Validates book snapshots, deltas, trades, and markers
- **Data Quality Reports**: Rich console output or JSON for CI/CD integration
- **Corruption Detection**: Catches deliberately corrupted files

## Installation

```bash
cd python/data_quality
pip install -e ".[test]"
```

## Quick Start

```bash
# Validate a single file
sb-dq-validate path/to/events.parquet

# Validate a partition directory
sb-dq-validate data/events/venue=BINANCE/symbol=BTC-USDT/date=2026-10-09/

# Validate entire dataset
sb-dq-validate data/events/

# Output as JSON for CI/CD
sb-dq-validate data/events/ --format json --output report.json

# Custom thresholds
sb-dq-validate data/events/ --max-gap 100 --max-errors 100
```

## Configuration

Create a `dq_config.json`:

```json
{
  "max_sequence_gap": 1000,
  "allow_sequence_wrap": false,
  "max_timestamp_jitter_ns": 1000000000,
  "max_recv_before_exchange_ns": 60000000000,
  "check_duplicates": true,
  "duplicate_subset": ["sequence", "epoch", "event_type"],
  "check_ordering": true,
  "ordering_column": "exchange_ts_ns",
  "allow_equal_timestamps": true,
  "max_errors_per_file": 1000
}
```

Use with: `sb-dq-validate data/events/ --config dq_config.json`

## Data Quality Checks

| Check | Code | Severity | Description |
|-------|------|----------|-------------|
| Sequence gaps | `SEQUENCE_GAP` | WARNING/ERROR | Gap > max_sequence_gap |
| Duplicates | `DUPLICATE_EVENT` | ERROR | Exact duplicate on subset |
| Out of order | `OUT_OF_ORDER` | WARNING/ERROR | Timestamps not monotonically increasing |
| Invalid timestamp | `INVALID_TIMESTAMP` | ERROR | <= 0 |
| Clock skew | `CLOCK_SKEW` | ERROR | recv_ts significantly before exchange_ts |
| Excessive latency | `EXCESSIVE_LATENCY` | WARNING | recv_ts - exchange_ts > threshold |
| Missing columns | `MISSING_REQUIRED_COLUMN` | CRITICAL | Required column missing |
| Empty dataset | `EMPTY_DATASET` | CRITICAL | Zero events |
| Null trade fields | `NULL_TRADE_FIELD` | ERROR | Null in required trade field |
| Invalid trade side | `INVALID_TRADE_SIDE` | ERROR | Trade side not buy/sell |

## Verdicts

| Verdict | Meaning | Exit Code |
|---------|---------|-----------|
| `pass` | No errors, only warnings/info | 0 |
| `conditional` | Errors but within threshold | 0 |
| `fail` | Too many errors or critical issues | 1 |

## Python API

```python
from stockbroker_dq import validate_events, DataQualityConfig

config = DataQualityConfig(max_sequence_gap=100, check_duplicates=True)
report = validate_events("data/events/", config)

if report.verdict == "fail":
    print("Data quality check failed!")
    for issue in report.issues:
        print(f"  {issue.severity.value}: {issue.code} - {issue.message}")

print(report.to_json())  # JSON for CI/CD
```

## Programmatic Usage

```python
from stockbroker_dq import ParquetLoader, EventValidator, DataQualityConfig
import polars as pl

# Load data
loader = ParquetLoader()
df = loader.load_partition("data/events/venue=BINANCE/symbol=BTC-USDT/date=2026-10-09/")

# Validate
config = DataQualityConfig(max_sequence_gap=100)
validator = EventValidator(config)
report = validator.validate_dataframe(df, "my_partition")

# Access results
print(f"Verdict: {report.verdict}")
print(f"Total events: {report.total_events}")
print(f"Issues: {report.total_issues}")
print(f"Gaps: {report.sequence_gaps} (max: {report.max_sequence_gap})")

# Get issues as Polars DataFrame for analysis
issues_df = pl.DataFrame([
    {
        "severity": i.severity.value,
        "code": i.code,
        "message": i.message,
        "event_index": i.event_index,
        "event_type": i.event_type.value if i.event_type else None,
        "column": i.column,
        "expected": i.expected,
        "actual": i.actual,
    }
    for i in report.issues
])
```

## Converting .pbz to Parquet

```bash
# Not yet implemented
sb-dq-convert data/raw/venue=BINANCE/ --output data/parquet/
```

## Test Corrupted Files

```bash
pytest tests/test_corrupted_files.py -v
```

This runs tests against deliberately corrupted Parquet files to verify the validator catches them.

## Architecture

```
stockbroker_dq/
├── core.py          # Data models (config, report, issues, schema)
├── loader.py        # Parquet loading (Polars + DuckDB)
├── validator.py     # Validation logic (gaps, duplicates, ordering, timestamps)
├── cli.py           # Typer CLI with rich output
└── tests/
    ├── test_validator.py      # Unit tests for validator logic
    └── test_corrupted_files.py # Tests against corrupted files
```

## Dependencies

- **Polars**: Fast DataFrame operations
- **DuckDB**: SQL queries on Parquet (planned for complex queries)
- **PyArrow**: Parquet I/O
- **Pydantic**: Configuration and report validation
- **Rich**: Beautiful console output
- **Typer**: CLI framework

## CI/CD Integration

```yaml
# .github/workflows/data-quality.yml
- name: Validate event data
  run: |
    pip install -e python/data_quality
    sb-dq-validate data/events/ --format json --output dq_report.json
    # Fail if verdict is fail
    python -c "
import json
with open('dq_report.json') as f:
    report = json.load(f)
if report['verdict'] == 'fail':
    exit(1)
"
```

## Performance

- **Lazy scanning**: Use `loader.scan_dataset()` for large datasets
- **Streaming validation**: Process in chunks for memory efficiency
- **Parallel validation**: Partition-level parallelism (planned)

## Contributing

1. Add new validation rules in `validator.py`
2. Add test cases in `tests/test_corrupted_files.py`
3. Update this README

## License

MIT OR Apache-2.0