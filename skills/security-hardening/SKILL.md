---
name: security-hardening
description: Security engineering checklist and patterns for a multi-user fintech-style web platform: password hashing, JWT and rotating refresh tokens, authorization and per-user data isolation, rate limiting, input validation, WebSocket security, secrets management, container and dependency security, logging and incident response, and regulatory guardrails for paper trading. Use this skill whenever touching authentication, sessions, authorization, user data access, public endpoints, WebSockets, secrets, dependencies, Docker/deployment configuration, or when asked to "make it secure", review security, or prepare for release.
---

# Security hardening

Spec: `docs/09-SECURITY.md` (threat model, controls, release gate). Baseline: OWASP ASVS L1 plus selected L2 for auth/session. Not legal advice.

## Top rules
1. **Deny by default;** authenticate every non-public endpoint; authorize every resource access.
2. **User isolation in one place:** repositories take `userId` from the security context and filter every query by it. Never fetch user-owned rows by id alone. Return 404 for foreign resources.
3. **No secrets in git, logs, URLs, or client bundles.** Use env/secret stores; gitleaks in CI and pre-commit.
4. **Validate at the edge:** types, sizes, enums, ranges; reject unknown fields on writes; cap pagination.
5. **Paper trading only** - no real funds or broker credentials anywhere (ADR-0009).

## Authentication
- Argon2id with parameters at or above OWASP minimums (tune ~50-100 ms); unique salts; min length 12; no composition rules; reject known-breached passwords.
- JWT access (~15 min, asymmetric, `kid` rotation; validate `iss/aud/exp`); opaque refresh token (256-bit random, store SHA-256 only), **rotate on every use**, detect reuse => revoke the whole family.
- Browser: access token in memory; refresh in `httpOnly; Secure; SameSite=Strict` cookie; CSRF header/token on the refresh endpoint.
- Login throttling per account and per IP; uniform errors/timing; lockouts with exponential backoff; audit log of auth events.
- WebSocket: single-use 30 s ticket (never the access token in the URL); `Origin` allowlist; per-user caps.

## Authorization
- Method-level role checks; admin endpoints on a separate prefix and network-restricted in deployment.
- Automated **authorization matrix tests**: anonymous / USER / other USER / ADMIN for every endpoint and every user-owned resource (orders, fills, ledger, alerts, watchlists, backtests).
- Optional Postgres RLS (`SET LOCAL app.user_id`) as defense in depth.

## Abuse and DoS controls
Rate limits (Bucket4j + Redis) per user and per IP; caps on open orders, alerts, WS sessions/subscriptions, backtest jobs; request and frame size limits; timeouts on all outbound calls; bounded queues; parser fuzzing; quotas for expensive endpoints.

## Web hardening
HSTS; CSP with nonces (no inline scripts); `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `frame-ancestors 'none'`; strict CORS allowlist; cookies `Secure`/`HttpOnly`/`SameSite`; no sensitive data in `NEXT_PUBLIC_*`.

## Data protection
Parameterized SQL only; app DB role without DDL and without UPDATE/DELETE on ledger tables; encrypted backups off-host; collect minimal PII (email); redact `password/token/authorization/refresh` in logs; privacy notice and deletion path if inviting real users (DPDP Act considerations in India).

## Infrastructure
TLS on all public edges; internal services on private networks (gRPC and job API not published); containers run non-root with minimal base images and no extra capabilities; Postgres/Redis/ClickHouse not exposed; SSH keys only; firewall; automatic security updates; secrets via Docker secrets or host secret store.

## Supply chain
Rust: `cargo-audit`, `cargo-deny`. Java: OWASP Dependency-Check, SpotBugs. Python: `pip-audit`, `bandit`. Node: `npm/pnpm audit`, lockfiles. All: Trivy for images, SBOM (CycloneDX) per release, Dependabot/Renovate. CI blocks critical/high issues without a documented exception.

## Logging and response
Structured JSON logs with request/user ids; log security events (login success/failure, token reuse, lockout, admin access, rate-limit hits); alerts on anomalies; incident playbook: revoke keys/tokens, rotate secrets, take demo offline, restore, write a post-mortem. Include `SECURITY.md` with a reporting contact.

## Regulatory guardrails (paper trading)
Label all UI and charts "simulated; not investment advice"; no claims of expected returns; respect exchange data terms (no redistribution beyond permitted fixtures); keep the product educational (SEBI sensitivity).

## Common vulnerabilities to hunt in review
IDOR (missing `user_id`), mass assignment (binding entities from requests), JWT `alg` confusion or missing `aud/iss` checks, refresh token reuse not detected, tokens/tickets in logs or URLs, permissive CORS with credentials, unbounded inputs, SSRF (none expected: no user-controlled URLs), race conditions in balance updates, verbose errors, default credentials in compose files, secrets in Docker layers.

## Release gate checklist
Auth flow tests (lockout, rotation, reuse, logout); isolation tests pass; no critical/high dependency issues + SBOM; gitleaks clean; security headers verified; WS limits and fuzz tests pass; rate limits verified under load; backups restore-tested; admin endpoints not public; disclaimers present.
