# ADR-0001: Polyglot split - Rust, Java, Python, Next.js

**Status:** Accepted
**Date:** 2026-10-02

## Context
The project is a portfolio piece that must demonstrate low-latency engineering, quantitative research, and product engineering. Each domain has a language that is the natural fit.

## Decision
Rust owns the fast feed (connectors, book, metrics, replay). Java (Spring Boot) owns the product API (auth, portfolios, paper trading, fan-out). Python owns research and backtesting. Next.js owns the frontend. Contracts are Protobuf (gRPC) and OpenAPI/WebSocket.

## Consequences
+ Each service plays to its language's strengths and tells a clear story. + Clear ownership rules (for example Java never recomputes metrics). - More tooling, CI and cognitive load; mitigated by phase gates, shared contracts and strict specs. - Cross-language parity must be tested explicitly (replay and golden vectors).

## Alternatives considered
Single language (Rust or Java) - simpler but loses the demonstration value. Python for everything - not credible for low latency. Go for the feed - GC pauses and less control.
