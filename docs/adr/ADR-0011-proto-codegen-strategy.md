# ADR-0011: Protobuf codegen strategy — buf for lint/format/build/breaking; native per-language codegen

**Status:** Accepted
**Date:** 2026-10-03

## Context

We need a consistent, version-pinned way to validate Protobuf schemas across the polyglot codebase (Rust, Java, Python, potentially TypeScript). The schema lives in `proto/stockbroker/market/v1/market.proto`.

Key constraints:
- Free tooling only (no paid Buf Schema Registry).
- No generated code committed to git (each language generates at build time).
- CI must run without local tool installs (Docker-based).
- Lint rules must be strict but allow intentional deviations (e.g., shared `SubscribeRequest` across three streaming RPCs).

## Decision

1. **buf** (v1.73.0, pinned) is used for:
   - `buf lint` (STANDARD ruleset with explicit exceptions)
   - `buf format` (canonical formatting)
   - `buf build` (validates compilation)
   - `buf breaking` (against `main` branch on PRs)

2. **Code generation is native per language** (not `buf generate`):
   - Rust: `build.rs` with `tonic-build` / `prost` (Phase 1)
   - Java: Gradle protobuf plugin (Phase 4)
   - Python: `grpcio-tools` via `python -m grpc_tools.protoc` (Phase 3/6)
   - TypeScript: `protobuf-es` / `connect-es` if needed (Phase 9)

3. **Lint exceptions** (documented in `proto/buf.yaml`):
    - `RPC_REQUEST_RESPONSE_UNIQUE`: `SubscribeRequest` intentionally shared across `SubscribeBook`, `SubscribeTrades`, `SubscribeMetrics`.
    - `RPC_REQUEST_STANDARD_NAME`: `SubscribeRequest` is generic, not RPC-specific.
    - `RPC_RESPONSE_STANDARD_NAME`: Responses use domain messages (`BookUpdate`, `Trade`, `Metrics`), not RPC-specific names.
    - `SERVICE_SUFFIX`: Service named `MarketData` per docs, not `MarketDataService`.
    - `RPC_NO_CLIENT_STREAMING` / `RPC_NO_SERVER_STREAMING`: **Removed from exceptions** — these rules belong to the `UNARY_RPC` category, not `STANDARD`. Since we use `STANDARD` (not `DEFAULT` or `UNARY_RPC`), they do not apply and no exception is needed.

4. **Breaking change detection**: `buf breaking` against `main` branch using `FILE` policy (proto file layout changes are breaking).

5. **Proto path**: Moved to `proto/stockbroker/market/v1/market.proto` to match package `stockbroker.market.v1` (fixes `PACKAGE_DIRECTORY_MATCH`).

## Consequences

+ Lint/format/build/breaking are consistent and runnable in CI via Docker (no local installs).
+ Each language controls its own codegen version and plugins.
+ No generated code in git avoids merge conflicts and drift.
+ Explicit lint exceptions are documented and reviewable.
+ Breaking changes are caught on PRs.

- Must maintain buf version pin across CI, Makefile, and developer docs.
- Each language's build system needs a proto compilation step (already standard).

## Alternatives Considered

- **buf generate + committed generated code**: Rejected — causes merge conflicts, drift, and language-specific plugin version conflicts.
- **buf generate without committing**: Rejected — adds a required local step (`buf generate`) before building; native per-language codegen is more idiomatic (e.g., `tonic-build` in Rust, Gradle plugin in Java).
- **No buf, just protoc**: Rejected — `protoc` alone lacks lint/format/breaking rules and consistent CI integration.
