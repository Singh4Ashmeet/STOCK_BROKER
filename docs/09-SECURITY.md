# Security

Scope: multi-user web application with authentication, user data, simulated financial state, public-facing WebSocket and REST APIs. Baseline: OWASP ASVS Level 1 for v1 with selected Level 2 controls for auth and session management. This document is engineering guidance, not legal advice.

## 1. Assets

| Asset | Why it matters |
|---|---|
| Credentials (password hashes, refresh tokens) | Account takeover |
| Access/refresh signing keys | Forge sessions |
| User portfolio and ledger data | Integrity of the product's core promise |
| Service availability | Public demo can be abused |
| Event logs and research data | Intellectual effort; low sensitivity |
| Infrastructure credentials (DB, Redis, host) | Full compromise |

## 2. Trust boundaries

1. Internet to reverse proxy / Next.js / Java API.
2. Browser to Java WebSocket.
3. Java to Rust gRPC (private network).
4. Java to Python job API (private network).
5. Services to databases.
6. Rust to exchange (outbound only; untrusted input).

## 3. Threat model (STRIDE, condensed)

| Threat | Example | Controls |
|---|---|---|
| **Spoofing** | Credential stuffing, stolen tokens | Argon2id, login throttling, short-lived access tokens, rotating refresh tokens with reuse detection, optional email verification, breach-password check |
| **Tampering** | Modify another user's order; tamper with ledger | Ownership checks in one layer, isolation tests, append-only ledger (no UPDATE/DELETE grants), request signing not needed (TLS), idempotency checks |
| **Repudiation** | User disputes actions | Audit log of auth events and order actions with request ids |
| **Information disclosure** | IDOR, verbose errors, logs with secrets, token in URL | `user_id` scoping, generic errors, structured logs with redaction, single-use WS ticket (not the access token) in the URL, security headers |
| **Denial of service** | WebSocket floods, expensive queries, resource exhaustion, malformed exchange frames | Rate limits, per-user caps, bounded queues, max frame sizes, query limits/timeouts, parser fuzzing, circuit breakers |
| **Elevation of privilege** | Access admin endpoints | Role checks at method level, deny by default, admin endpoints on internal network or behind extra auth |

Additional abuse cases: using the platform as a free compute service (backtest abuse: quotas and queue limits); automated account creation (rate limits, optional CAPTCHA); alert spam (cooldowns, caps).

## 4. Authentication and sessions

- **Passwords:** Argon2id with parameters at or above OWASP's current minimum recommendation (tune to about 50 to 100 ms on the target host); unique salt; optional pepper from secret store. Minimum length 12; allow long passphrases; no composition rules; reject known-breached passwords (offline list or k-anonymity API).
- **Access token:** JWT, signed with an asymmetric key (EdDSA or RS256), `kid` for rotation, lifetime about 15 minutes, claims: `sub`, `role`, `iat`, `exp`, `jti`. Validate signature, `exp`, `iss`, `aud`. No sensitive data in claims.
- **Refresh token:** random 256-bit opaque value; only a SHA-256 hash stored; lifetime 7 to 30 days (sliding limit); **rotate on every use**; reuse of a rotated token revokes the entire family and forces re-login.
- **Browser storage:** access token in memory only; refresh token in `httpOnly; Secure; SameSite=Strict` cookie set by Next.js (same site as the UI). Refresh endpoint requires a CSRF defense (custom header or double-submit token) in addition to SameSite.
- **Logout:** revoke family; clear cookie.
- **Brute force:** per-account and per-IP counters in Redis; exponential lockout; uniform error and timing for unknown users vs wrong password.
- **Account enumeration:** same response for register-with-existing-email (email verification flow handles the notice) where feasible.

## 5. Authorization and isolation

- Deny by default; role checks via method security annotations.
- **Ownership:** all user-owned repositories take `user_id` explicitly (from the security context, never from request bodies) and include it in every query. No repository method fetches by id alone.
- Automated tests per resource: user A attempts GET/PATCH/DELETE on user B's order, fill, alert, watchlist, backtest, ledger: must get 404 (preferred) or 403.
- Optional defense in depth: Postgres RLS with `SET LOCAL app.user_id`.
- Admin endpoints: separate path prefix, ADMIN role, and network restriction in deployment.

## 6. API and input security

- Validate everything at the edge (Bean Validation, size limits, enum whitelists). Reject unknown fields on write endpoints.
- Parameterized queries only; no string-built SQL.
- Request body limit (e.g. 64 KB), timeouts on all outbound calls, pagination caps.
- Errors: `problem+json` without stack traces; correlation id returned.
- CORS: explicit origin allowlist; credentials only where needed.
- Security headers on Next.js and API: `Strict-Transport-Security`, `Content-Security-Policy` (no inline scripts; nonces), `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `frame-ancestors 'none'`.
- WebSocket: `Origin` allowlist; single-use ticket; per-user session/subscription/message caps; max message size; close with codes on violations.
- SSRF: no user-controlled URLs are fetched server-side. The only outbound targets are configured exchange hosts.
- File uploads: none in v1.

## 7. Secrets and configuration

- No secrets in git (gitleaks or similar in CI and pre-commit).
- `.env.example` documents variables; real values from environment or Docker secrets.
- Separate keys per environment; rotation procedure documented (JWT `kid` rollover; DB password rotation).
- Principle of least privilege for DB roles: `app_rw` (no DDL, no UPDATE/DELETE on ledger), `migrator` (DDL, used only by Flyway), `readonly` (analytics).

## 8. Transport and infrastructure

- TLS everywhere on public edges (Caddy/Nginx with automatic certificates); HSTS.
- Internal services bound to private interfaces; gRPC and Python job API not published.
- Containers: non-root users, read-only root filesystem where possible, no unnecessary capabilities, minimal base images, pinned digests for releases.
- Postgres and Redis not exposed publicly; Redis requires a password; ClickHouse restricted.
- Host hardening basics: SSH keys only, firewall allowing 80/443 (and SSH from known IPs), automatic security updates.
- Backups: nightly Postgres dump encrypted and stored off-host; restore tested.

## 9. Supply chain and code security

| Language | Tools |
|---|---|
| Rust | `cargo-audit`, `cargo-deny` (licenses, advisories, bans), `clippy`, `cargo-geiger` (optional) |
| Java | OWASP Dependency-Check or Gradle/Maven audit plugins, SpotBugs, `-Xlint`, Spring Security tests |
| Python | `pip-audit`, `bandit`, `ruff`, `mypy` |
| Node | `npm audit`/`pnpm audit`, `eslint-plugin-security` (optional), lockfile enforcement |
| All | `gitleaks`, GitHub Dependabot/Renovate, SBOM generation (CycloneDX) at release, container scan (Trivy) |

CI blocks merges on critical/high vulnerabilities without a documented exception.

## 10. Logging, monitoring, and incident response

- Structured JSON logs with request id and user id (not email); redaction filters for `password`, `token`, `authorization`, `refresh`.
- Security events logged: login success/failure, token reuse, lockout, role change, admin access, rate-limit hits, WS ticket failures.
- Alerts (Grafana/Alertmanager): spikes in auth failures, 5xx rates, queue conflation spikes, unusual connection counts.
- Incident playbook (short): revoke keys/tokens, rotate secrets, take the demo offline, restore from backup, write a post-mortem (also a portfolio asset).

## 11. Regulatory and legal notes (not legal advice)

- **Paper trading only.** No real money, no custody, no order routing, no brokerage credentials. This avoids being a broker/dealer or investment adviser function. The UI and README state this clearly, and every performance chart is labeled **simulated / backtested; not investment advice**.
- In India, giving investment advice or running trading services is regulated by SEBI; keep the product educational and do not market backtest results as predictions of returns.
- **Data licensing:** respect the exchange's market-data terms (public API terms of use, redistribution limits, rate limits). Do not redistribute raw exchange data in the public repo beyond small test fixtures permitted by the terms (**VERIFY**).
- **Privacy:** collect minimal personal data (email). If real users are invited, provide a privacy notice and deletion path. India's Digital Personal Data Protection Act, 2023 may apply to personal data of users; consult current guidance if you go beyond a personal demo.
- **Security disclosure:** include `SECURITY.md` in the repo root with a contact for reports.

## 12. Security acceptance checklist (release gate)

- [ ] Auth flow tests: lockout, token rotation, reuse detection, logout
- [ ] Isolation tests pass for every user-owned resource
- [ ] No critical/high dependency vulnerabilities; SBOM produced
- [ ] gitleaks clean; no secrets in history
- [ ] Security headers verified (e.g. via an automated check)
- [ ] WS limits and fuzz tests pass
- [ ] Rate limits verified under load test
- [ ] Backups restore-tested
- [ ] Admin endpoints not publicly reachable
- [ ] README and UI disclaimers present
