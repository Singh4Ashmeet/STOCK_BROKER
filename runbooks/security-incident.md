# Security Incident Runbook

## Overview
This runbook covers the response procedure for security incidents in the Stock Broker platform.

**Classification**: CONFIDENTIAL - Internal use only
**Last Updated**: $(date +%Y-%m-%d)
**Owner**: Security Team / Platform Engineering

---

## Incident Classification

| Severity | Definition | Response Time | Escalation |
|----------|------------|---------------|------------|
| **P0 - Critical** | Active breach, data exfiltration, RCE, auth bypass | < 15 min | Page Security Lead + CTO |
| **P1 - High** | Vulnerability with exploit available, privilege escalation | < 1 hour | Page Security Lead |
| **P2 - Medium** | Vulnerability without known exploit, DoS risk | < 4 hours | Assign to Security Engineer |
| **P3 - Low** | Security hygiene, config drift, minor findings | Next sprint | Track in backlog |

---

## Phase 1: Detection & Triage (0-15 min)

### 1.1 Detection Sources
- [ ] Automated alerts (Prometheus/Grafana: `security_*` alerts)
- [ ] SIEM/log aggregation (failed login spikes, token reuse)
- [ ] External report (bug bounty, user report)
- [ ] Dependency scan (cargo audit, dependency-check)
- [ ] Internal discovery (code review, pentest)

### 1.2 Initial Triage Checklist
```
[ ] Confirm incident is real (not false positive)
[ ] Classify severity (P0/P1/P2/P3)
[ ] Identify affected systems/components
[ ] Determine if data exposed (PII, keys, trading data)
[ ] Check if attack is active or historical
[ ] Create incident ticket (GitHub Issue with `security` label)
[ ] Notify Security Lead (Slack #security-incidents)
```

### 1.3 Evidence Preservation
```
[ ] Snapshot affected systems (disk, memory if P0)
[ ] Export relevant logs (last 72 hours minimum)
[ ] Preserve network captures (if available)
[ ] Document timeline with timestamps (UTC)
[ ] Hash all evidence (SHA-256) and store in secure location
```

---

## Phase 2: Containment (15-60 min)

### 2.1 Immediate Actions by Severity

#### P0 - Critical
```
[ ] Isolate affected service (remove from load balancer)
[ ] Revoke compromised credentials (JWT keys, DB passwords, API keys)
[ ] Block attacker IPs at WAF/firewall
[ ] Enable enhanced logging on all services
[ ] Consider maintenance mode for user-facing services
```

#### P1 - High
```
[ ] Deploy mitigation (WAF rule, config change, feature flag)
[ ] Rotate potentially exposed secrets
[ ] Increase monitoring sensitivity
[ ] Prepare rollback plan for mitigation
```

#### P2/P3
```
[ ] Schedule fix deployment
[ ] Apply compensating controls
[ ] Document workaround
```

### 2.2 Containment Checklist
```
[ ] Attack vector identified and blocked
[ ] No further unauthorized access possible
[ ] Affected systems isolated or mitigated
[ ] Credentials rotated if compromised
[ ] Monitoring alerts tuned for attack pattern
```

---

## Phase 3: Eradication (1-24 hours)

### 3.1 Root Cause Analysis
```
[ ] How did attacker gain access?
[ ] What vulnerability was exploited?
[ ] What permissions did they obtain?
[ ] What data was accessed/modified?
[ ] How long was access maintained?
```

### 3.2 Remediation Actions
```
[ ] Patch vulnerability (code, config, dependency)
[ ] Remove any persistence mechanisms (backdoors, scheduled tasks)
[ ] Verify no lateral movement to other systems
[ ] Rebuild compromised systems from clean images
[ ] Rotate ALL credentials that could have been exposed
```

### 3.3 Verification
```
[ ] Vulnerability scanner shows clean
[ ] Penetration test of fix (if P0/P1)
[ ] Regression tests pass
[ ] Security tests pass (CI pipeline green)
```

---

## Phase 4: Recovery (1-72 hours)

### 4.1 Service Restoration
```
[ ] Deploy fixed version to staging
[ ] Run full test suite including security tests
[ ] Deploy to production with canary (5% → 25% → 100%)
[ ] Monitor error rates, latency, security alerts
[ ] Remove containment measures (WAF blocks, etc.)
```

### 4.2 Data Recovery (if needed)
```
[ ] Verify backup integrity (see backup_restore_test.sh)
[ ] Restore from last known good backup
[ ] Verify data consistency (checksums, row counts)
[ ] Replay any missed transactions from event log
```

### 4.3 Post-Recovery Validation
```
[ ] All health checks green
[ ] Security alerts resolved
[ ] User authentication working
[ ] Trading/API functions operational
[ ] Audit logs writing correctly
```

---

## Phase 5: Post-Incident (1-7 days)

### 5.1 Incident Report
Create `incidents/INC-YYYYMMDD-XXX.md` with:

```
# Incident Report: INC-YYYYMMDD-XXX

## Summary
- **Date**: YYYY-MM-DD HH:MM UTC
- **Duration**: X hours Y minutes
- **Severity**: P0/P1/P2/P3
- **Status**: Resolved / Monitoring

## Timeline
| Time (UTC) | Event |
|------------|-------|
| HH:MM | Detection |
| HH:MM | Containment started |
| HH:MM | Root cause identified |
| HH:MM | Fix deployed |
| HH:MM | Recovery complete |

## Impact
- **Systems affected**: [list]
- **Data exposed**: [none / PII / keys / trading data]
- **Users affected**: [count / "all" / "subset"]
- **Downtime**: [duration]

## Root Cause
[Technical description of vulnerability and exploit]

## Remediation
[What was fixed, how, and verification]

## Action Items
| ID | Action | Owner | Due Date | Status |
|----|--------|-------|----------|--------|
| INC-XXX-1 | | | | |
| INC-XXX-2 | | | | |

## Lessons Learned
[What worked, what didn't, process improvements]
```

### 5.2 Retrospective Meeting (within 5 business days)
- Attendees: Security, Platform, Backend, Product
- Review timeline, decisions, gaps
- Update runbooks, detection rules, tests
- Assign action items with owners/dates

---

## Communication Templates

### Internal Slack (#security-incidents)
```
🚨 SECURITY INCIDENT: INC-YYYYMMDD-XXX
Severity: P0/P1/P2/P3
Summary: [one line]
Status: Investigating / Containing / Recovering / Resolved
Lead: @security-lead
Updates: Thread below
```

### User-Facing (if required)
**Template for P0/P1 with user impact**:
> We detected a security issue affecting [service] on [date]. We have [contained/fixed] the issue and [no user data was exposed / user data may have been exposed]. We are [taking steps / have taken steps] to [prevent recurrence / protect affected users]. For questions, contact security@stockbroker.example.com.

### Regulatory (if applicable)
- India: CERT-In reporting within 6 hours (if critical infra)
- GDPR: 72 hours if personal data breach
- Document decision rationale for reporting/not reporting

---

## Quick Reference Commands

```bash
# Revoke all JWT tokens (rotate keys)
./scripts/rotate_jwt_keys.sh

# Rotate database passwords
./scripts/rotate_db_passwords.sh

# Block IP at Cloudflare/NGINX
cfwaf block-ip --ip 1.2.3.4 --note "Security incident INC-XXX"

# Enable maintenance mode
kubectl patch ingress api -n prod -p '{"spec":{"defaultBackend":{"service":{"name":"maintenance"}}}}'

# Export audit logs
pg_dump -t audit_log -f audit_log_$(date +%Y%m%d).sql

# Verify backup integrity
./scripts/backup_restore_test.sh --verify-only
```

---

## Contacts

| Role | Name | Slack | Phone (P0 only) |
|------|------|-------|-----------------|
| Security Lead | [Name] | @security-lead | +XX-XXX-XXXX |
| Platform Lead | [Name] | @platform-lead | +XX-XXX-XXXX |
| CTO | [Name] | @cto | +XX-XXX-XXXX |
| Legal/Compliance | [Name] | @legal | +XX-XXX-XXXX |
| External IR Firm | [Firm] | N/A | +XX-XXX-XXXX |

---

## Related Documents
- [Key Compromise Procedure](key-compromise.md)
- [Data Breach Notification](data-breach.md)
- [DDoS Mitigation](ddos-mitigation.md)
- [Backup/Restore Test](backup_restore_test.sh)
- [Security Checklist](SECURITY_CHECKLIST.md)