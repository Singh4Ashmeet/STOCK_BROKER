# Key Compromise Procedure

## Overview
Procedure for responding to compromised cryptographic keys (JWT signing keys, TLS certificates, database encryption keys, API keys).

**Classification**: CONFIDENTIAL
**Trigger**: Confirmed or suspected private key exposure
**RTO**: < 30 minutes for JWT keys, < 4 hours for TLS/DB keys

---

## Key Inventory

| Key Type | Purpose | Rotation Schedule | Storage |
|----------|---------|-------------------|---------|
| JWT Access (EdDSA) | Sign access tokens (15 min) | Per release + on compromise | Vault / Docker secrets |
| JWT Refresh (opaque) | Refresh token validation | On reuse detection | PostgreSQL (SHA-256) |
| TLS Certificates | HTTPS/WSS termination | 90 days (Let's Encrypt) | Cert-manager / Vault |
| DB Encryption (AES-256) | Column-level PII encryption | Quarterly | Vault |
| Feed gRPC mTLS | Service-to-service auth | 1 year | Vault |
| Backup Encryption | Backup file encryption | Per backup | Vault |

---

## Detection Signals

| Signal | Source | Action |
|--------|--------|--------|
| JWT `kid` not in current key set | Auth service logs | Investigate immediately |
| Token validation failures spike | Prometheus `auth_token_invalid_total` | Check key rotation |
| Unauthorized access with valid token | Audit logs | Presume key compromise |
| Key material in git history | Gitleaks alert | Rotate immediately |
| Key material in logs | Log search | Rotate immediately |
| External report (bug bounty) | Security email | Validate and rotate |

---

## Phase 1: Immediate Response (0-15 min)

### 1.1 Confirm Compromise
```
[ ] Verify key material actually exposed (not false positive)
[ ] Identify which key(s) compromised
[ ] Determine exposure scope (when, where, who)
[ ] Classify severity (see table below)
```

| Key Type | Severity if Compromised |
|----------|------------------------|
| JWT Access Private Key | **P0 - Critical** (full auth bypass) |
| TLS Private Key | **P0 - Critical** (MITM possible) |
| DB Encryption Key | **P1 - High** (data at risk) |
| Feed mTLS Key | **P1 - High** (service impersonation) |
| Backup Encryption Key | **P2 - Medium** (historical data) |
| Refresh Token (single) | **P3 - Low** (single user) |

### 1.2 Initial Containment
```
[ ] P0: Page Security Lead + Platform Lead immediately
[ ] Document key ID, exposure time, vector
[ ] Begin key rotation procedure for affected key type
```

---

## Phase 2: Key Rotation Procedures

### 2.1 JWT Access Key Compromise (P0)

**Impact**: All existing access tokens become untrustworthy. Attacker can mint valid tokens.

**Procedure**:
```bash
# 1. Generate new EdDSA keypair
cd infra/secrets
openssl genpkey -algorithm Ed25519 -out jwt_private.key.new
openssl pkey -in jwt_private.key.new -pubout -out jwt_public.key.new

# 2. Update Vault/Docker secrets
docker secret create jwt_private_key_v2 jwt_private.key.new
docker secret create jwt_public_key_v2 jwt_public.key.new

# 3. Deploy new public key to API service (zero-downtime)
#    - API reads public key from secret on startup
#    - Rolling restart API pods
kubectl rollout restart deployment/api -n prod

# 4. Verify new tokens work
curl -H "Authorization: Bearer <new_token>" https://api/health

# 3. Invalidate ALL existing access tokens
#    - Option A: Shorten expiry in config (15m → 1m) temporarily
#    - Option B: Add key version check in token validation (reject old kid)
#    - Option C: Force re-login via refresh token revocation

# 4. Force refresh token rotation for all users
#    - Mark all refresh tokens as "key_rotated" requiring re-auth
#    - Or: deploy middleware that rejects tokens with old kid

# 5. Monitor for authentication failures
#    - Alert if auth failure rate > 10%
#    - Watch for lockout storms
```

**Rollback Plan**: Keep old key for 24h in validation allowlist (verify only, don't sign)

### 2.2 TLS Certificate Compromise (P0)

**Impact**: Attacker can MITM all HTTPS/WSS traffic.

**Procedure**:
```bash
# 1. Revoke compromised certificate
certbot revoke --cert-path /etc/letsencrypt/live/api.example.com/cert.pem \
    --reason keycompromise

# 2. Request new certificate
certbot certonly --force-renewal -d api.example.com -d ws.example.com

# 3. Deploy new cert to load balancer/reverse proxy
#    - Nginx: reload config with new cert paths
#    - Or: cert-manager handles automatically

# 4. Verify TLS handshake
openssl s_client -connect api.example.com:443 -servername api.example.com

# 5. Update certificate pinning in mobile apps (if applicable)
#    - Ship app update with new pin
#    - Grace period: 7 days with both pins
```

### 2.3 Database Encryption Key Compromise (P1)

**Impact**: Encrypted PII columns can be decrypted.

**Procedure**:
```bash
# 1. Generate new DEK (Data Encryption Key)
#    - DEK encrypted with KEK (Key Encryption Key) in Vault
#    - Rotate DEK only, keep KEK

# 2. Re-encrypt affected columns
#    - For each table with encrypted columns:
#      UPDATE table SET encrypted_col = pgp_sym_encrypt(
#          pgp_sym_decrypt(encrypted_col, old_dek), new_dek
#      )

# 3. Update application to use new DEK
#    - Config change + rolling restart

# 4. Verify decryption works
SELECT pgp_sym_decrypt(encrypted_col, new_dek) FROM table LIMIT 10;

# 5. Schedule old DEK destruction after verification period (7 days)
```

### 2.4 Feed gRPC mTLS Key Compromise (P1)

**Impact**: Attacker can impersonate feed service or API.

**Procedure**:
```bash
# 1. Generate new mTLS certificates for feed and API
./scripts/generate_mtls_certs.sh --rotate

# 2. Deploy to both services simultaneously
#    - Feed: new server cert + trust new API client CA
#    - API: new client cert + trust new Feed server CA

# 3. Rolling restart both services
#    - Coordinate to avoid connection failures

# 4. Verify gRPC health checks pass
grpc_health_probe -addr=feed:9000
```

---

## Phase 3: Post-Rotation Validation

### 3.1 Functional Tests
```
[ ] User login works (new tokens issued)
[ ] Refresh token flow works
[ ] WebSocket connections work (new tickets)
[ ] API authentication works
[ ] Service-to-service gRPC works
[ ] Database read/write works (decryption)
[ ] Backup restore works (new backup key)
```

### 3.2 Security Tests
```
[ ] Old JWT tokens rejected (kid mismatch)
[ ] Old TLS cert rejected (revoked)
[ ] Old DB key cannot decrypt new data
[ ] No authentication bypass possible
```

### 3.3 Monitoring
```
[ ] Auth success rate > 99%
[ ] Token validation errors < 1%
[ ] No spike in 401/403 responses
[ ] Latency within normal bounds
```

---

## Phase 4: Communication

### 4.1 Internal
- Security team: Immediate notification
- Engineering: "Key rotation in progress, expect brief auth disruptions"
- Support: "Users may need to re-login"

### 4.2 External (if user data at risk)
- Users: "We rotated security keys as a precaution. Please log in again."
- Regulators: If PII encryption key compromised → 72-hour notification

---

## Automation

### Key Rotation Script (`scripts/rotate_jwt_keys.sh`)
```bash
#!/usr/bin/env bash
# Automated JWT key rotation with zero-downtime deployment
set -euo pipefail

NEW_KID="kid-$(date +%Y%m%d-%H%M%S)"
# 1. Generate keys
# 2. Store in Vault with versioning
# 3. Deploy public key to API config
# 4. Rolling restart
# 5. Update validation allowlist (keep old for 24h)
# 6. Verify
# 7. Remove old key from allowlist after 24h
```

### Scheduled Rotation (CronJob)
```yaml
# .github/workflows/key-rotation.yml
on:
  schedule:
    - cron: '0 3 * * 0'  # Weekly on Sunday 3 AM
jobs:
  rotate-jwt-keys:
  rotate-tls-certs:
  rotate-db-dek:
```

---

## Verification Checklist

| Check | JWT Keys | TLS Certs | DB Encryption | mTLS |
|-------|----------|-----------|---------------|------|
| New key generated | ✓ | ✓ | ✓ | ✓ |
| Old key revoked/invalidated | ✓ | ✓ | N/A | ✓ |
| Services restarted | ✓ | ✓ | ✓ | ✓ |
| Health checks pass | ✓ | ✓ | ✓ | ✓ |
| Auth/logins work | ✓ | N/A | N/A | N/A |
| gRPC connections work | N/A | N/A | N/A | ✓ |
| Data decrypts correctly | N/A | N/A | ✓ | N/A |
| Monitoring green | ✓ | ✓ | ✓ | ✓ |
| Old key destroyed | After 24h | After 24h | After 7d | After 24h |

---

## Incident Report Template

```
# Key Compromise Incident: KC-YYYYMMDD

## Key Details
- **Key Type**: JWT Access / TLS / DB / mTLS
- **Key ID**: [kid / serial / alias]
- **Exposure Vector**: [git / logs / memory / unknown]
- **Exposure Window**: [start] to [detection]

## Impact Assessment
- **Tokens/Certs Issued During Exposure**: [count]
- **Potentially Affected Users**: [count]
- **Data at Risk**: [none / PII / trading data]

## Actions Taken
- [ ] New key generated: [timestamp]
- [ ] Old key revoked: [timestamp]
- [ ] Services rotated: [timestamp]
- [ ] Validation updated: [timestamp]
- [ ] Monitoring verified: [timestamp]

## Verification
- [ ] All functional tests pass
- [ ] Security tests pass
- [ ] No auth bypass possible

## Lessons Learned
[Root cause, prevention measures]
```

---

## Related Runbooks
- [Security Incident](security-incident.md)
- [Data Breach Notification](data-breach.md)