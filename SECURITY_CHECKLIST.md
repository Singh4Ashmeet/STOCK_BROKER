# Security Checklist

## Overview
This checklist must pass before any release. Each item is verified either automatically (CI/CD) or manually (release engineer sign-off).

---

## 1. Dependency Security (Automated in CI)

| Check | Tool | Frequency | Pass Criteria |
|-------|------|-----------|---------------|
| Rust dependencies | `cargo audit` | Every PR | No unpatched CVEs (CVSS ≥ 7) |
| Java dependencies | `mvn org.owasp:dependency-check-maven:check` | Every PR | No critical/high vulnerabilities |
| Python dependencies | `pip-audit` / `safety` | Every PR | No critical vulnerabilities |
| Node/TS dependencies | `npm audit` | Every PR | No critical/high vulnerabilities |
| Container base images | `trivy image` | Weekly + Release | No critical CVEs in base images |
| License compliance | `cargo deny check licenses` / `license-checker` | Every PR | No GPL/AGPL in production deps |

**Automation**: `.github/workflows/security.yml` runs all checks on PR and scheduled weekly.

---

## 2. Secrets Management (Automated)

| Check | Tool | Pass Criteria |
|-------|------|---------------|
| No secrets in git history | `gitleaks` (pre-commit + CI) | Zero findings |
| No secrets in repository files | `trivy fs --security-checks secret` | Zero findings |
| No hardcoded credentials in code | `semgrep --config=auto` | Zero findings |
| All secrets in `.env` / Vault | Manual review | All `*_PASSWORD`, `*_KEY`, `*_SECRET` in env/secrets |
| JWT keys rotated per release | Manual | New EdDSA keypair generated for each release |

**Files to verify clean**:
- `.env.example` (template only, no real values)
- Native service configuration and `.env` files (no plaintext secrets)

---

## 3. Authentication & Authorization (Automated + Manual)

| Check | Verification Method |
|-------|---------------------|
| JWT access tokens: 15 min expiry, EdDSA, `kid` header | Unit tests + manual token decode |
| Refresh tokens: opaque, SHA-256 stored, rotate on use | Unit tests (`AuthIntegrationTest`) |
| Refresh token reuse detection (invalidates chain) | Unit test: `AuthSecurityTest.refreshReuseDetection` |
| Per-user data isolation (`user_id` in every query) | Architecture test: `ArchitectureTest.userIdInQueries` |
| Role-based access (ADMIN vs USER) | Integration tests |
| WebSocket ticket: single-use, 5 min TTL, bound to user | Integration test: `AuthWebSocketTicketTest` |
| Password hashing: Argon2id (memory-hard) | Code review: `Argon2PasswordEncoder` |

---

## 4. Input Validation & Injection Prevention (Automated)

| Check | Tool/Method |
|-------|-------------|
| SQL injection prevention (parameterized queries / jOOQ) | Code review + `ArchitectureTest.noStringConcatenationInSql` |
| NoSQL injection (ClickHouse parameterized) | Code review |
| XSS prevention (JSON API, no HTML rendering) | Architecture |
| Path traversal prevention (file upload validation) | Unit tests |
| Deserialization safety (Jackson: no default typing) | Code review: `ObjectMapper` config |
| Protobuf schema validation (required fields, size limits) | Protobuf `option (validate.rules) = true` |

---

## 5. Rate Limiting & DoS Protection (Automated Tests)

| Check | Verification |
|-------|--------------|
| Auth endpoints: 20 req/min/IP | `RateLimitFilterTest.authEndpointsStrict` |
| Market data: 600 req/min/user | `RateLimitFilterTest.marketEndpoints` |
| Trading endpoints: 100 req/min/user | `RateLimitFilterTest.tradingEndpoints` |
| WebSocket connections: 5/user, 10/min/IP | `MarketWebSocketHandlerRateLimitTest` |
| WebSocket inbound messages: 20/sec | `SessionContext.checkRateLimit` test |
| Order cap: 100 open orders/user | `OrderServiceLimitTest` |
| Alert cap: 50 active alerts/user | `AlertServiceLimitTest` |
| 429 responses with `Retry-After` header | Integration test |

---

## 6. Transport Security (Manual Verification)

| Check | Requirement |
|-------|-------------|
| TLS 1.2+ only (enforced by reverse proxy) | Nginx/Envoy config: `ssl_protocols TLSv1.2 TLSv1.3` |
| Certificate pinning (mobile/client) | Documented in client SDK |
| HSTS header (1 year, includeSubDomains) | Reverse proxy config |
| Secure cookies (HttpOnly, SameSite=Strict) | Spring Security config |
| CSP header (restrictive) | Reverse proxy config |
| No mixed content | All resources HTTPS |

**Local dev exception**: `plaintext: true` in feed config, bound to 127.0.0.1 only.

---

## 7. Data Protection (Automated + Manual)

| Check | Verification |
|-------|--------------|
| PII encrypted at rest (Postgres `pgcrypto` / app-level) | Schema review: `email` hashed, `pii` encrypted |
| No PII in logs (structured logging, no `toString()`) | `ArchitectureTest.noPiiInLogs` |
| Audit logs: security events (login, token refresh, 401, 403) | `AuditLogIntegrationTest` |
| Audit logs immutable (append-only table, no DELETE) | DB migration review |
| Backup encryption (AES-256) | Backup script verification |
| Key rotation: JWT keys per release, DB encryption keys quarterly | Runbook: `key-rotation.md` |

---

## 8. Infrastructure Security (Automated)

| Check | Tool |
|-------|------|
| Least-privilege service accounts | Native service configuration and deployment manifests |
| Resource limits (CPU, memory) | Host or platform service limits |
| Network segmentation | Firewall and platform network policy |
| Secrets externalized from source | Environment variables or a secret manager |
| Release provenance | CI release attestation or package signatures |

---

## 9. Observability Security (Automated)

| Check | Verification |
|-------|--------------|
| No sensitive data in metrics labels | Prometheus config review |
| No sensitive data in traces | Jaeger/OTel config review |
| Alert on security events (brute force, token reuse, 401 spikes) | Prometheus alerts: `alerts.yml` |
| Log retention (90 days hot, 1 year cold) | Loki/Grafana config |
| Audit log integrity (hash chain) | `AuditLogService` implementation |

---

## 10. Release Security Gate (Manual Sign-off)

Before tagging a release, the Release Engineer must verify:

```
[ ] All CI security checks pass (green badge)
[ ] Dependency audit: zero critical/high unpatched CVEs
[ ] Container scan: zero critical CVEs in final images
[ ] JWT keys rotated and stored in Vault/secrets manager
[ ] Database migration reviewed for security implications
[ ] Changelog includes security fixes (if any)
[ ] Rollback plan tested in staging
[ ] Incident response contacts updated
```

**Sign-off**: Release Engineer + Security Reviewer (if critical changes)

---

## 11. Incident Response References

| Document | Location |
|----------|----------|
| Security Incident Runbook | `runbooks/security-incident.md` |
| Key Compromise Procedure | `runbooks/key-compromise.md` |
| Data Breach Notification | `runbooks/data-breach.md` |
| DDoS Mitigation | `runbooks/ddos-mitigation.md` |

---

## Automation Summary

**GitHub Actions Workflow** (`.github/workflows/security.yml`):
```yaml
# Runs on: PR, push to main, weekly schedule
jobs:
  cargo-audit:
  dependency-check:
  pip-audit:
  npm-audit:
  gitleaks:
  trivy-fs:
  trivy-image:
  semgrep:
  license-check:
  architecture-tests:
  integration-security-tests:
```

**Pre-commit hooks** (`.pre-commit-config.yaml`):
- `gitleaks` (fast, local)
- `cargo fmt --check` / `mvn fmt:check` / `black --check`
- `cargo clippy` / `spotbugs`

---

## Checklist Status Tracker

| Release | Date | Dependency Audit | Container Scan | Auth Tests | Rate Limit Tests | Manual Sign-off |
|---------|------|------------------|----------------|------------|------------------|-----------------|
| v0.1.0 | TBD | ☐ | ☐ | ☐ | ☐ | ☐ |

*Update this table for each release.*
