---
name: protobuf-grpc-contracts
description: Designing and evolving Protobuf schemas and gRPC services shared by Rust, Java and Python: field numbering rules, fixed-point conventions, oneofs, streaming semantics, epoch/seq contracts, backpressure, deadlines, buf lint/breaking checks and code generation. Use this skill whenever editing files under `proto/`, generating stubs, implementing gRPC servers or clients, adding RPCs or messages, or debugging serialization/compatibility between services.
---

# Protobuf and gRPC contracts

Source: `proto/stockbroker/market/v1/market.proto`, `docs/04-API-SPEC.md`. The proto is the single source of truth; **never hand-edit generated code**.

## Schema rules
1. **Never renumber or reuse field numbers.** Removed fields: `reserved <n>; reserved "<name>";`.
2. Add fields with new numbers; make additions optional-by-default (proto3 semantics). Do not change a field's type or meaning.
3. Use `oneof` for variant payloads (snapshot vs delta). Check the case explicitly in consumers; unknown cases are ignored/logged, not crashed on.
4. Enums: first value is `*_UNSPECIFIED = 0`; treat unknown values safely.
5. **Money/price/qty = `int64` fixed-point** with scales from `InstrumentSpec`. **Never `double`** except documented derived analytics. Timestamps = `int64` nanoseconds UTC.
6. Include `symbol`, `epoch`, `seq`, and `Timing` on every streamed market message.
7. Package versioning: `stockbroker.market.v1`. Breaking changes go to `v2` as a new package/service, not in-place.
8. Document semantics in comments (units, scales, ordering, nullability).

## Tooling (buf)
- `buf lint` (STANDARD) and `buf breaking --against '.git#branch=main'` in CI on every PR touching `proto/`.
- `buf generate` with plugins: Rust (`prost` + `tonic-build` via build.rs or buf plugins), Java (`protoc-gen-grpc-java`/`protobuf-java`), Python (`grpcio-tools` or `betterproto`), TypeScript (`protobuf-es`/`connect-es` if needed for tooling). Commit `buf.gen.yaml`; keep generated code out of git or in clearly marked directories, consistently per language.
- Add a CI check that regenerated code matches what is committed (if committed).

## Streaming semantics (must hold)
- First message per symbol on a subscription is a snapshot.
- Within an `epoch`, `seq` increases by exactly 1; a gap means the consumer must resync (`GetSnapshot` or resubscribe).
- New `epoch` means the next message is a snapshot; consumers discard previous state.
- **Slow consumers are conflated, not buffered without bound:** the server drops queued deltas and sends a fresh snapshot with a new epoch.
- Servers validate `SubscribeRequest` (max symbols, depth bounds) and return `INVALID_ARGUMENT` otherwise.

## gRPC operational practice
- **Deadlines** on unary calls (e.g. 2 s); streams use keepalive (20 s pings, timeouts) and reconnect with backoff on the client.
- Flow control: configure HTTP/2 window sizes for throughput; respect backpressure on the server side with bounded per-subscriber queues.
- Max message size set explicitly (snapshots can be large; limit `depth_levels`).
- Status codes: `INVALID_ARGUMENT`, `NOT_FOUND` (unknown symbol), `UNAVAILABLE` (feed down/resyncing), `RESOURCE_EXHAUSTED` (too many subscribers), `INTERNAL` (bug).
- **Security:** private network or mTLS; service token/metadata check; reflection only in dev; no sensitive data in metadata logs.
- Client in Java: managed channel with `keepAlive`, `retry` via service config, name resolution to a fixed host; a refcounted subscription manager.

## Testing
- Golden serialization tests (encode/decode round trip) per message with edge values (zero qty = remove, max int64 in range, empty lists).
- Contract tests: Java and Python clients against a stub server and against a recorded real stream.
- Compatibility tests: old client vs new server and vice versa when fields are added (use previously generated stubs in CI occasionally).
- Fuzz decoding paths in Rust.

## Review checklist
- [ ] `buf lint` and `buf breaking` pass
- [ ] No reused/renumbered fields; reserved set for removals
- [ ] Units/scales documented; no float money
- [ ] Streaming semantics (snapshot-first, epoch/seq) preserved
- [ ] API spec doc updated for any new RPC/message
