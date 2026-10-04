# Architecture Decision Records

Format: Status, Context, Decision, Consequences, Alternatives. Write a new ADR (next number) when a decision changes or a spec is deliberately deviated from. Never edit an accepted ADR's decision; supersede it with a new one.

| ADR | Title | Status |
|---|---|---|
| 0001 | Polyglot split: Rust, Java, Python, Next.js | Accepted |
| 0002 | Direct gRPC live path; message bus optional | Accepted |
| 0003 | Fixed-point integers across all boundaries | Accepted |
| 0004 | Optimized dense-window order book with an oracle | Accepted |
| 0005 | Reuse the Rust book in Python via PyO3 | Accepted |
| 0006 | Raw event log as framed Protobuf + zstd, compacted to Parquet | Accepted |
| 0007 | Multi-user auth with JWT access + rotating refresh tokens | Accepted |
| 0008 | Next.js with client-only live app; WebSocket straight to Java | Accepted |
| 0009 | Paper trading only | Accepted |
| 0010 | JSON over WebSocket for v1 | Accepted |
| 0011 | Protobuf codegen strategy: buf for lint/format/build/breaking; native per-language codegen | Accepted |
| 0012 | Crate name prefix `sb-` for all workspace packages | Accepted |
