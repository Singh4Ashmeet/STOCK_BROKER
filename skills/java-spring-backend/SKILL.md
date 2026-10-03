---
name: java-spring-backend
description: Building the Java 21 Spring Boot product API: modular package structure, REST conventions (problem+json, pagination, idempotency), Spring Security with JWT, validation, Flyway/Postgres access (jOOQ or JDBC), transactions and isolation, virtual threads, gRPC client integration, Testcontainers, OpenAPI and configuration. Use this skill whenever writing or reviewing Java code in `java/`, adding endpoints, repositories, migrations, services, security config, or integration tests for the product API.
---

# Java Spring Boot backend

Specs: `docs/02-TRD.md` section 6, `docs/04-API-SPEC.md`, `docs/05-DATA-MODEL.md`, `docs/09-SECURITY.md`. Use current stable Spring Boot and Java 21; pin in the build file.

## Structure (modular monolith)
Packages by capability: `auth`, `market`, `portfolio`, `trading`, `alerts`, `research`, `admin`, `common`. Inside each: `api` (controllers, DTO records) -> `service` -> `repo`. **No cross-module repository access**; modules talk through service interfaces/events. Keep controllers thin (validation + mapping), transactions in services.

## REST conventions
- Base `/api/v1`; JSON; `application/problem+json` (RFC 9457) via `@ControllerAdvice` with stable `code` values (see API spec section 2.6).
- Cursor pagination with caps (`limit` max 200). Never return unbounded lists.
- `Idempotency-Key` required on `POST /orders`; store `(user_id, key, request_hash)` unique; same key + different body => 409.
- Request validation with Bean Validation + explicit `@Size`, `@Pattern`, enum whitelists; reject unknown JSON properties on writes (`FAIL_ON_UNKNOWN_PROPERTIES`).
- OpenAPI via springdoc; the frontend client is generated from it.
- Return `X-Request-Id`; propagate to logs (MDC).

## Security (see `security-hardening` skill)
- Spring Security resource server: validate JWT signature/`exp`/`iss`/`aud`; method security (`@PreAuthorize`) for roles.
- **User scoping:** repositories take `userId` from the `SecurityContext` (never from request params/bodies) and include it in every query. Add isolation tests per resource.
- Argon2id via Spring's `Argon2PasswordEncoder` (needs BouncyCastle) with parameters at or above OWASP minimums; tune to ~50-100 ms.
- Rate limiting filter (Bucket4j + Redis) before auth-heavy endpoints.

## Persistence
- Postgres + Flyway (`V001__...sql`; never edit applied migrations). Use `NUMERIC(38,18)` and `BigDecimal` for money.
- For ledger/trading code prefer **jOOQ or Spring JDBC** (explicit SQL, locking, batch inserts). JPA is fine for simple CRUD; avoid lazy-loading surprises and N+1; never expose entities in APIs.
- Transactions: keep short; `READ COMMITTED` default; use `SELECT ... FOR UPDATE` or `SERIALIZABLE` for balance-affecting operations (plus the single-writer rule - see `paper-trading-ledger`).
- App DB role: no DDL, no UPDATE/DELETE on ledger tables; migrations run with a separate role.

## Concurrency and virtual threads
- Virtual threads (`spring.threads.virtual.enabled=true`) simplify blocking I/O code. Avoid `synchronized` around blocking calls (pinning in older JDKs); prefer `ReentrantLock` or message-passing.
- Don't use virtual threads for CPU-bound hot loops; the fan-out writers use dedicated structures (see `realtime-websocket-fanout`).
- Per-account serialization via keyed single-thread executors or mailboxes.

## gRPC client (market gateway)
- Single managed channel to the Rust feed; keepalive; reconnect with backoff; refcounted symbol subscriptions; update in-memory state atomically (immutable snapshots swapped via `AtomicReference`, or per-symbol single-writer).
- Mirror latest state to Redis at a throttled rate; clients read in-memory first.
- Expose metrics for every stage (`gateway_stage_latency_seconds`).

## Configuration
`application.yml` + env overrides; profiles `dev`/`demo`; secrets only from env or secret files; fail fast on missing required config; typed `@ConfigurationProperties` with validation.

## Testing
- Unit: JUnit 5 + AssertJ; property tests with jqwik for ledger invariants.
- Integration: Testcontainers (Postgres, Redis); `@SpringBootTest` slices; Flyway runs on each test DB.
- Security tests: authorization matrix per endpoint (anonymous, USER, other USER, ADMIN).
- Contract: in-process gRPC server stub emitting recorded streams.
- Static analysis: SpotBugs, Error Prone (optional), `-Xlint:all -Werror`.

## Observability
Micrometer + Prometheus with percentile histograms (not client-side percentiles); structured JSON logs; no PII/secrets in logs; JFR profiles for GC/allocation checks.

## Anti-patterns
- Floats/doubles for money; `Optional` as fields; entity leaks to DTOs.
- Business logic in controllers; transactions spanning external calls.
- Fetching by id without `user_id`.
- Catching `Exception` and swallowing; returning stack traces.
- Unbounded caches/queues.

## Review checklist
- [ ] Every user-owned query scoped by `user_id`; isolation test added
- [ ] Problem+json error codes consistent; validation present
- [ ] Money as BigDecimal/NUMERIC; rounding mode explicit
- [ ] Migration added (not edited); indexes for new queries
- [ ] Metrics and logs for new flows; no secrets logged
