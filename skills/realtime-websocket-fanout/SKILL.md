---
name: realtime-websocket-fanout
description: Designing and implementing the real-time WebSocket gateway that fans out market data from one upstream stream to thousands of clients: ticket auth, snapshot-then-delta protocol, epoch/seq gap handling, bounded per-session queues, conflation, slow-consumer policy, limits, heartbeats, and load testing. Use this skill whenever working on WebSocket handlers, the market gateway in Java, client-side WebSocket handling, subscription management, backpressure or latency under many clients.
---

# Real-time WebSocket fan-out

Protocol: `docs/04-API-SPEC.md` section 3. Principle: **one slow client must never slow anyone else or grow memory without bound.**

## Architecture
```
gRPC stream -> market state (per symbol) -> subscription registry -> per-session bounded queue -> session writer -> socket
```
- **One upstream subscription per symbol** (refcounted), shared by all sessions.
- Per-session **single writer** (virtual thread or event-loop task) drains that session's queue; no other thread writes to the socket.
- Messages are serialized **once per symbol per message type** and the bytes are shared across sessions (do not re-serialize per client). Per-session personalization (private channels) is separate.

## Connect and auth
- Client gets a single-use, 30 s ticket via authenticated REST; connects `wss://.../ws/v1?ticket=...`. Validate and **consume atomically** (Redis `GETDEL` or equivalent). Check `Origin` allowlist.
- Enforce caps: sessions per user, subscriptions per session, inbound msgs/s, max inbound frame size; close with a clear code/reason when violated.
- Close the session when the user's access is revoked (listen for revocation events) or after a max session age.

## Snapshot-then-delta
- On `subscribe`, send the **current snapshot** (from in-memory state) then deltas. Ensure no gap between snapshot and the first delta: take the snapshot and register the session's queue under the same lock/epoch check, or enqueue snapshot first and drop deltas with `seq <= snapshot.seq`.
- Messages carry `epoch` and `seq`. Client rules: `seq` must increase by exactly 1 within an epoch; otherwise send `resync`.
- Epoch change from upstream: send `resync` then snapshot for affected symbols; mark symbols `stale` if the upstream is down.

## Backpressure and conflation
- Per-session queue is bounded (e.g. 256 messages) with **per-(channel,symbol) conflation**: for book updates, if the queue is full, discard queued deltas for that symbol and enqueue a fresh snapshot with a new session-level epoch marker (`resync`). For metrics/ticker-like channels keep only the latest value.
- Never block the upstream consumer thread on a slow session: enqueue must be non-blocking (`offer`), with drop/conflate on failure.
- If a session stays lagging beyond a threshold (e.g. 5 conflations in 10 s), close it with reason `lagging`.
- Track metrics: queue depth histogram, conflations, drops, close reasons, serialization time, write time.

## Latency discipline
- Stamp `gateway_send_ts` just before writing; expose stage metrics.
- Avoid per-message allocation: reuse buffers, pre-size, prefer `ByteBuffer`/Netty `ByteBuf` pooling; avoid boxing; avoid logging per message.
- Choose the server stack deliberately: Spring WebSocket (simple) vs Reactor Netty/Netty direct (more control). Decide with a load test, not intuition; note the choice in an ADR if it differs from the default.
- Tune GC (try G1 vs ZGC); size heaps; pre-touch memory; verify with JFR.

## Heartbeats and lifecycle
Server ping every 20 s; close if no pong in 60 s; client auto-reconnects with backoff + jitter and a fresh ticket; on reconnect the client resubscribes and receives snapshots.

## Client side (browser worker)
- WebSocket lives in a **Web Worker**; parse in the worker; post compact structured updates to the main thread at most once per animation frame; keep the last snapshot + deltas applied in the worker or store.
- Verify `epoch/seq` continuity; on gap send `resync` and show a "resyncing" badge.
- Reconnect with backoff; show connection state; never block rendering on network events.

## Load testing (required before claiming numbers)
- Open-loop generator (k6/Gatling or custom) with 100/500/1,000/2,000 clients; vary subscription mix; include slow and stalled clients (some that never read).
- Measure added latency (`T6 - T5`), CPU, memory, GC pauses, conflation rate; publish methodology and hardware.
- Chaos: kill upstream gRPC; verify `resync`/`stale` behavior and recovery.

## Anti-patterns
- Broadcasting by iterating all sessions and writing synchronously on the upstream thread.
- Unbounded queues or `ConcurrentLinkedQueue` per session without caps.
- Serializing the same JSON per client; sending full snapshots every tick.
- Using the access token in the URL or logging URLs with tickets.
- Ignoring `epoch`; assuming TCP ordering makes `seq` checks unnecessary.

## Review checklist
- [ ] Non-blocking enqueue; bounded queues with conflation
- [ ] Snapshot/delta ordering proven by a test (no gaps, no duplicates)
- [ ] Caps and Origin check present; ticket single-use
- [ ] Metrics for queue depth, drops, closes, stage latency
- [ ] Load test results recorded with environment details
