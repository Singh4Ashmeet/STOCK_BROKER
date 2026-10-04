# ADR-0012: Crate name prefix `sb-` for all workspace packages

**Status:** Accepted
**Date:** 2026-10-04

## Context

The Rust workspace for the feed service will contain multiple crates. The crate `core` was initially considered, but `core` is the name of Rust's built-in `core` library (the platform-agnostic subset of `std`). Using `core` as a crate name would cause naming conflicts and confusion when importing.

## Decision

All workspace packages (Cargo package names) will use the `sb-` prefix:

| Directory | Package name | Library name |
|---|---|---|
| `core` | `sb-core` | `sb_core` |
| `book` | `sb-book` | `sb_book` |
| `metrics` | `sb-metrics` | `sb_metrics` |
| `connectors` | `sb-connectors` | `sb_connectors` |
| `server` | `sb-feed` | `sb_feed` (binary) |
| `replay` | `sb-replay` | `sb_replay` |
| `pybook` | `sb-pybook` | `sb_pybook` |

Directory names remain unchanged (e.g., `rust/core/`). Only the Cargo `name` field in `Cargo.toml` uses the `sb-` prefix.

## Consequences

+ No collision with Rust's `core` library.
+ Clear ownership/namespace (`sb` = stock_broker).
+ Consistent naming across all crates.
- External users must use `sb-core` in `Cargo.toml` dependencies.
- Library names use underscore (`sb_core`) per Rust convention.

## Alternatives Considered

- **Use `core` anyway**: Rejected — causes import ambiguity with `std::core` / `core::`.
- **Use `stockbroker_core`**: Rejected — verbose; `sb-` is shorter and still unique.
- **No prefix, rename `core` to `types`**: Rejected — `core` is the established term in the architecture docs (TRD section 5.1).