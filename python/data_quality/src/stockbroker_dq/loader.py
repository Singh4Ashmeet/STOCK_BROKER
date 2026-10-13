"""Parquet event loader using Polars and DuckDB."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import polars as pl
import pyarrow.parquet as pq  # type: ignore[import-untyped]

from .core import DataQualityConfig


class ParquetLoader:
    """Loads and parses Parquet event files using Polars."""

    def __init__(self, config: DataQualityConfig | None = None) -> None:
        self.config = config or DataQualityConfig()
        self.schema = self.config.schema

    def load_file(self, file_path: str | Path, **kwargs: Any) -> pl.DataFrame:
        """Load a single Parquet file."""
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"File not found: {file_path}")

        # Use Polars to read Parquet
        df = pl.read_parquet(path, **kwargs)

        # Validate required columns exist
        missing = [c for c in self.schema.required_columns if c not in df.columns]
        if missing:
            raise ValueError(f"Missing required columns: {missing}")

        return df

    def load_partition(self, partition_dir: str | Path, **kwargs: Any) -> pl.DataFrame:
        """Load all Parquet files in a partition directory."""
        dir_path = Path(partition_dir)
        if not dir_path.exists() or not dir_path.is_dir():
            raise NotADirectoryError(f"Partition directory not found: {partition_dir}")

        parquet_files = list(dir_path.glob("*.parquet"))
        if not parquet_files:
            # Check for partitioned structure (venue=*/symbol=*/date=*/hour=*/*.parquet)
            parquet_files = list(dir_path.rglob("*.parquet"))

        if not parquet_files:
            raise ValueError(f"No Parquet files found in {partition_dir}")

        # Read all files and concatenate
        dfs = [self.load_file(f) for f in parquet_files]
        return pl.concat(dfs, how="vertical_relaxed")

    def load_dataset(self, root_dir: str | Path, **kwargs: Any) -> pl.DataFrame:
        """Load entire dataset from partitioned Parquet structure."""
        root = Path(root_dir)
        if not root.exists():
            raise FileNotFoundError(f"Dataset root not found: {root_dir}")

        # Find all parquet files recursively
        parquet_files = list(root.rglob("*.parquet"))
        if not parquet_files:
            raise ValueError(f"No Parquet files found in {root_dir}")

        dfs = [self.load_file(f) for f in parquet_files]
        return pl.concat(dfs, how="vertical_relaxed")

    def scan_file(self, file_path: str | Path) -> pl.LazyFrame:
        """Create a lazy scan of a Parquet file for large datasets."""
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"File not found: {file_path}")
        return pl.scan_parquet(path)

    def scan_partition(self, partition_dir: str | Path) -> pl.LazyFrame:
        """Create a lazy scan of a partition directory."""
        dir_path = Path(partition_dir)
        if not dir_path.exists() or not dir_path.is_dir():
            raise NotADirectoryError(f"Partition directory not found: {partition_dir}")

        parquet_files = list(dir_path.rglob("*.parquet"))
        if not parquet_files:
            raise ValueError(f"No Parquet files found in {partition_dir}")

        return pl.scan_parquet(parquet_files)

    def scan_dataset(self, root_dir: str | Path) -> pl.LazyFrame:
        """Create a lazy scan of entire dataset."""
        root = Path(root_dir)
        if not root.exists():
            raise FileNotFoundError(f"Dataset root not found: {root_dir}")

        parquet_files = list(root.rglob("*.parquet"))
        if not parquet_files:
            raise ValueError(f"No Parquet files found in {root_dir}")

        return pl.scan_parquet(parquet_files)

    def get_file_info(self, file_path: str | Path) -> dict[str, Any]:
        """Get metadata about a Parquet file without loading data."""
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"File not found: {file_path}")

        stat = path.stat()
        pq_file = pq.ParquetFile(path)
        meta = pq_file.metadata
        schema = pq_file.schema_arrow

        return {
            "file_path": str(path),
            "file_size_bytes": stat.st_size,
            "num_rows": meta.num_rows,
            "num_columns": meta.num_columns,
            "schema": {field.name: str(field.type) for field in schema},
            "row_groups": meta.num_row_groups,
        }


def load_events(
    path: str | Path,
    config: DataQualityConfig | None = None,
    **kwargs: Any,
) -> pl.DataFrame:
    """Convenience function to load events from a file or directory."""
    loader = ParquetLoader(config)
    path_obj = Path(path)

    if path_obj.is_file():
        return loader.load_file(path, **kwargs)
    elif path_obj.is_dir():
        # Check if it's a single partition or full dataset
        parquet_files = list(Path(path).glob("*.parquet"))
        if parquet_files:
            return loader.load_partition(path, **kwargs)
        else:
            return loader.load_dataset(path, **kwargs)
    else:
        raise ValueError(f"Path does not exist: {path}")