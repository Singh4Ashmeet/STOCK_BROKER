"""CLI commands for data quality validation."""

from __future__ import annotations

import json
from pathlib import Path

import typer
from rich.console import Console
from rich.panel import Panel
from rich.progress import Progress, SpinnerColumn, TextColumn
from rich.table import Table

from .core import DataQualityConfig, DataQualityReport, IssueSeverity
from .validator import validate_events

app = typer.Typer(
    name="stockbroker-dq",
    help="StockBroker Data Quality - Validate Parquet event files",
    add_completion=False,
    no_args_is_help=True,
)

console = Console()


@app.command("validate")
def validate_cmd(
    path: str = typer.Argument(..., help="Path to Parquet file, partition directory, or dataset root"),
    config_file: Path | None = typer.Option(None, "--config", "-c", help="Path to JSON config file"),
    output: Path | None = typer.Option(None, "--output", "-o", help="Output report to JSON file"),
    format: str = typer.Option("rich", "--format", "-f", help="Output format: rich, json, summary"),
    max_errors: int = typer.Option(1000, "--max-errors", help="Maximum errors before failing"),
    max_gap: int = typer.Option(1000, "--max-gap", help="Maximum allowed sequence gap"),
    check_duplicates: bool = typer.Option(True, "--check-duplicates/--no-check-duplicates"),
    check_ordering: bool = typer.Option(True, "--check-ordering/--no-check-ordering"),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Verbose output"),
) -> None:
    """Validate Parquet event files for data quality issues."""
    path_obj = Path(path)
    if not path_obj.exists():
        console.print(f"[red]Error:[/red] Path does not exist: {path}")
        raise typer.Exit(1)

    # Load config
    config = DataQualityConfig(
        max_errors_per_file=max_errors,
        max_sequence_gap=max_gap,
        check_duplicates=check_duplicates,
        check_ordering=check_ordering,
    )

    # Validate
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        console=console,
        transient=True,
    ) as progress:
        task = progress.add_task("Validating events...", total=None)
        report = validate_events(path, config)
        progress.update(task, completed=1)

    # Output report
    if format == "json":
        output_str = report.to_json()
        if output:
            output.write_text(output_str)
        else:
            console.print(output_str)
    elif format == "summary":
        print_summary(report)
    else:
        print_rich_report(report, verbose)

    # Exit code based on verdict
    if report.verdict == "fail":
        console.print("\n[red]VALIDATION FAILED[/red]")
        raise typer.Exit(1)
    elif report.verdict == "conditional":
        console.print("\n[yellow]VALIDATION PASSED WITH WARNINGS[/yellow]")
        raise typer.Exit(0)
    else:
        console.print("\n[green]VALIDATION PASSED[/green]")
        raise typer.Exit(0)


@app.command("report")
def report_cmd(
    path: str = typer.Argument(..., help="Path to validated report JSON file"),
    format: str = typer.Option("rich", "--format", "-f", help="Output format: rich, json"),
) -> None:
    """Display a previously generated validation report."""
    path_obj = Path(path)
    if not path_obj.exists():
        console.print(f"[red]Error:[/red] Report file not found: {path}")
        raise typer.Exit(1)

    with open(path_obj) as f:
        data = json.load(f)

    if format == "json":
        console.print_json(data)
    else:
        report = DataQualityReport(**data)
        print_rich_report(report, True)


@app.command("convert")
def convert_cmd(
    input_path: str = typer.Argument(..., help="Input .pbz file or directory"),
    output_dir: str = typer.Option(..., "--output", "-o", help="Output directory for Parquet files"),
    batch_size: int = typer.Option(10000, "--batch-size", help="Events per Parquet file"),
    compression: str = typer.Option("zstd", "--compression", help="Parquet compression codec"),
    partition_by: str = typer.Option("hour", "--partition-by", help="Partition strategy: hour, day, symbol"),
) -> None:
    """Convert .pbz event log files to Parquet format."""
    console.print("[yellow]Convert command not yet implemented[/yellow]")
    console.print(f"Input: {input_path}")
    console.print(f"Output: {output_dir}")
    raise typer.Exit(1)


def print_rich_report(report: DataQualityReport, verbose: bool = False) -> None:
    """Print a rich-formatted validation report."""
    # Verdict panel
    verdict_color = {
        "pass": "green",
        "conditional": "yellow",
        "fail": "red",
        "pending": "blue",
    }.get(report.verdict, "white")

    console.print(Panel(
        f"[{verdict_color}]{report.verdict.upper()}[/{verdict_color}]",
        title="Validation Verdict",
        border_style=verdict_color,
    ))

    # Summary table
    table = Table(title="Validation Summary")
    table.add_column("Metric", style="cyan")
    table.add_column("Value", style="white")

    table.add_row("File", report.file_path)
    table.add_row("Total Events", str(report.total_events))
    table.add_row("File Size", f"{report.file_size_bytes:,} bytes")
    table.add_row("Validation Time", f"{report.validation_duration_ms:.1f} ms")
    table.add_row("Total Issues", str(report.total_issues))
    table.add_row("Verdict", f"[{verdict_color}]{report.verdict.upper()}[/{verdict_color}]")

    console.print(table)

    # Issues by severity
    if report.issues_by_severity:
        sev_table = Table(title="Issues by Severity")
        sev_table.add_column("Severity", style="cyan")
        sev_table.add_column("Count", style="white")

        for sev in [IssueSeverity.CRITICAL, IssueSeverity.ERROR, IssueSeverity.WARNING, IssueSeverity.INFO]:
            count = report.issues_by_severity.get(sev, 0)
            if count > 0:
                color = {
                    IssueSeverity.CRITICAL: "red",
                    IssueSeverity.ERROR: "red",
                    IssueSeverity.WARNING: "yellow",
                    IssueSeverity.INFO: "blue",
                }.get(sev, "white")
                sev_table.add_row(f"[{color}]{sev.value.upper()}[/{color}]", str(count))

        console.print(sev_table)

    # Issues by code
    if report.issues_by_code:
        code_table = Table(title="Top Issue Codes")
        code_table.add_column("Code", style="cyan")
        code_table.add_column("Count", style="white")
        code_table.add_column("Severity", style="white")

        for code, count in sorted(report.issues_by_code.items(), key=lambda x: -x[1])[:20]:
            # Find severity for this code
            sev = next((i.severity for i in report.issues if i.code == code), IssueSeverity.INFO)
            color = {
                IssueSeverity.CRITICAL: "red",
                IssueSeverity.ERROR: "red",
                IssueSeverity.WARNING: "yellow",
                IssueSeverity.INFO: "blue",
            }.get(sev, "white")
            code_table.add_row(code, str(count), f"[{color}]{sev.value.upper()}[/{color}]")

        console.print(code_table)

    # Summary stats
    stats_table = Table(title="Data Quality Metrics")
    stats_table.add_column("Metric", style="cyan")
    stats_table.add_column("Value", style="white")

    stats_table.add_row("Sequence Gaps", str(report.sequence_gaps))
    stats_table.add_row("Max Sequence Gap", str(report.max_sequence_gap))
    stats_table.add_row("Duplicate Events", str(report.duplicate_events))
    stats_table.add_row("Out-of-Order Events", str(report.out_of_order_events))
    stats_table.add_row("Timestamp Anomalies", str(report.timestamp_anomalies))

    console.print(stats_table)

    # Event type breakdown
    if report.events_by_type:
        type_table = Table(title="Events by Type")
        type_table.add_column("Event Type", style="cyan")
        type_table.add_column("Count", style="white")

        for etype, count in sorted(report.events_by_type.items()):
            type_table.add_row(etype, str(count))

        console.print(type_table)

    # Show issues if verbose
    if verbose and report.issues:
        issue_table = Table(title="Detailed Issues (first 50)")
        issue_table.add_column("Severity", style="cyan")
        issue_table.add_column("Code", style="white")
        issue_table.add_column("Message", style="white")
        issue_table.add_column("Event Index", style="dim")
        issue_table.add_column("Type", style="dim")
        issue_table.add_column("Column", style="dim")

        for issue in report.issues[:50]:
            color = {
                IssueSeverity.CRITICAL: "red",
                IssueSeverity.ERROR: "red",
                IssueSeverity.WARNING: "yellow",
                IssueSeverity.INFO: "blue",
            }.get(issue.severity, "white")
            issue_table.add_row(
                f"[{color}]{issue.severity.value.upper()}[/{color}]",
                issue.code,
                issue.message[:80] + "..." if len(issue.message) > 80 else issue.message,
                str(issue.event_index) if issue.event_index else "-",
                issue.event_type.value if issue.event_type else "-",
                issue.column or "-",
            )

        console.print(issue_table)


def print_summary(report: DataQualityReport) -> None:
    """Print a concise summary of the validation report."""
    print(f"File: {report.file_path}")
    print(f"Events: {report.total_events}")
    print(f"Issues: {report.total_issues} ({report.issues_by_severity.get(IssueSeverity.ERROR, 0)} errors, {report.issues_by_severity.get(IssueSeverity.WARNING, 0)} warnings)")
    print(f"Gaps: {report.sequence_gaps} (max gap: {report.max_sequence_gap})")
    print(f"Duplicates: {report.duplicate_events}")
    print(f"Out-of-order: {report.out_of_order_events}")
    print(f"Verdict: {report.verdict}")


if __name__ == "__main__":
    app()