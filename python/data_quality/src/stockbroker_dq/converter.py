"""Convert .pbz event log files to Parquet format."""

from __future__ import annotations

import struct
from pathlib import Path
from typing import Any, Literal

import polars as pl

try:
    import zstandard as zstd  # type: ignore[import-untyped]
except ImportError:
    zstd = None  # type: ignore[assignment]

try:
    from google.protobuf.message import Message  # type: ignore[import-untyped]
except ImportError:
    Message = None  # type: ignore[assignment,misc]

# Import the Rust protobuf definitions
# Note: In practice, you'd use the Python bindings from the Rust code
# For now, we'll define the minimal protobuf structures here

MAGIC_BYTES = b"SBEL"
SCHEMA_VERSION = 1

# Record kinds
class RecordKind:
    BOOK_SNAPSHOT = 1
    BOOK_DELTA = 2
    TRADE = 3
    GAP_MARKER = 4
    RESYNC_MARKER = 5
    RAW_FRAME = 6
    RECONNECT_MARKER = 7

class LogHeader:
    """Header at the start of each .pbz file."""
    def __init__(self) -> None:
        self.schema_version: int = 0
        self.venue: str = ""
        self.symbol: str = ""
        self.price_scale: int = 0
        self.qty_scale: int = 0
        self.tick_size: int = 0
        self.lot_size: int = 0
        self.start_ts_ns: int = 0
        self.writer_version: str = ""
        self.host_info: str = ""

    @classmethod
    def decode(cls, data: bytes) -> LogHeader:
        # Simplified - in practice use protobuf
        # This is a placeholder
        header = cls()
        return header


def read_pbz_file(file_path: str) -> list[dict[str, Any]]:
    """Read a .pbz file and return list of event dictionaries."""
    events = []

    with open(file_path, 'rb') as f:
        # Read magic bytes
        magic = f.read(4)
        if magic != MAGIC_BYTES:
            raise ValueError(f"Invalid magic bytes: {magic!r}")

        # Read header length (4 bytes little-endian)
        header_len_bytes = f.read(4)
        if len(header_len_bytes) < 4:
            raise ValueError("Truncated file: missing header length")
        header_len = struct.unpack('<I', header_len_bytes)[0]

        # Read header
        header_data = f.read(header_len)
        if len(header_data) < header_len:
            raise ValueError("Truncated file: incomplete header")

        # Parse header (simplified - in practice use protobuf)
        _ = parse_header(header_data)

        # Read records
        while True:
            # Read record length
            len_bytes = f.read(4)
            if len(len_bytes) < 4:
                break  # EOF

            record_len = struct.unpack('<I', len_bytes)[0]
            if record_len == 0:
                continue

            record_data = f.read(record_len)
            if len(record_data) < record_len:
                break

            # Parse record (simplified)
            record = parse_record(record_data)
            if record:
                events.append(record)

    return events


def parse_header(data: bytes) -> dict[str, Any]:
    """Parse LogHeader from bytes (simplified)."""
    # In practice, use protobuf decoding
    return {
        "schema_version": 1,
        "venue": "BINANCE",
        "symbol": "BTC-USDT",
        "price_scale": 8,
        "qty_scale": 8,
        "tick_size": 100000000,
        "lot_size": 100000000,
        "start_ts_ns": 0,
        "writer_version": "0.1.0",
        "host_info": "linux",
    }


def parse_record(data: bytes) -> dict[str, Any] | None:
    """Parse a LogRecord from bytes (simplified)."""
    # In practice, use protobuf decoding
    # This is a placeholder that returns None
    return None


ParquetCompression = Literal["lz4", "uncompressed", "snappy", "gzip", "brotli", "zstd"]


def convert_pbz_to_parquet(
    input_path: str,
    output_dir: str,
    batch_size: int = 10000,
    compression: ParquetCompression = "zstd",
    partition_by: str = "hour",
) -> dict[str, Any]:
    """Convert .pbz files to Parquet format."""
    input_dir = Path(input_path)
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    if input_dir.is_file():
        pbz_files = [input_dir]
    else:
        pbz_files = list(input_dir.rglob("*.pbz"))

    if not pbz_files:
        return {"converted": 0, "files": []}

    results = []
    for pbz_file in pbz_files:
        print(f"Converting {pbz_file}...")
        events = read_pbz_file(str(pbz_file))

        if not events:
            continue

        # Convert to DataFrame
        df = pl.DataFrame(events)

        # Partition by hour (or other strategy)
        # For now, write as single file
        output_file = output_path / f"{pbz_file.stem}.parquet"
        df.write_parquet(output_file, compression=compression)

        results.append({
            "input": str(pbz_file),
            "output": str(output_file),
            "events": len(events),
        })

    return {
        "converted": len(results),
        "files": results,
    }


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser(description="Convert .pbz files to Parquet")
    parser.add_argument("input", help="Input .pbz file or directory")
    parser.add_argument("-o", "--output", required=True, help="Output directory")
    parser.add_argument("--batch-size", type=int, default=10000, help="Events per file")
    parser.add_argument("--compression", default="zstd", choices=["zstd", "snappy", "gzip", "none"])
    parser.add_argument("--partition-by", default="hour", choices=["hour", "day", "symbol"])

    args = parser.parse_args()

    result = convert_pbz_to_parquet(
        args.input,
        args.output,
        batch_size=args.batch_size,
        compression=args.compression,
        partition_by=args.partition_by,
    )

    print(f"Converted {result['converted']} files")
    for r in result["files"]:
        print(f"  {r['input']} -> {r['output']} ({r['events']} events)")


if __name__ == "__main__":
    main()