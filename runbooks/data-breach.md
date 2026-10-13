# Data Breach Notification Runbook

## Overview
Procedure for assessing, containing, and reporting data breaches involving personal data or sensitive trading information.

**Legal Basis**: 
- India: IT Act 2000, CERT-In Directions (6-hour reporting)
- GDPR: Articles 33-34 (72-hour reporting if EU users affected)
- SEBI: If trading platform classified as MII

**Classification**: CONFIDENTIAL - Legal Privilege

---

## Breach Definition

A **personal data breach** is a breach of security leading to the accidental or unlawful destruction, loss, alteration, unauthorized disclosure of, or access to, personal data transmitted, stored, or otherwise processed.

**Personal Data in Scope**:
- User PII: email, name, phone, KYC documents (hashed/encrypted)
- Trading data: orders, fills, portfolio, P&L (linked to user_id)
- Authentication data: password hashes, refresh tokens, JWT claims
- API keys, WebSocket tickets

**Out of Scope**:
- Aggregated/anonymized analytics
- Public market data
- System metrics, logs (no PII)

---

## Phase 1: Assessment (0-2 hours)

### 1.1 Breach Confirmation
```
[ ] Confirm breach occurred (not false positive)
[ ] Identify data categories affected
[ ] Estimate number of records/users affected
[ ] Determine if breach is ongoing or contained
[ ] Identify root cause (exploit, misconfiguration, insider)
```

### 1.2 Risk Assessment Matrix

| Likelihood of Harm | Severity of Harm | Risk Level | Notification Required |
|-------------------|------------------|------------|----------------------|
| High | High (identity theft, financial fraud) | **Critical** | YES - All authorities + users |
| High | Medium (phishing, targeted attacks) | **High** | YES - Authorities + users |
| Medium | High | **High** | YES - Authorities + users |
| Medium | Medium | **Medium** | Authorities only |
| Low | Low | **Low** | Internal only |

**Harm Types to Consider**:
- Identity theft / account takeover
- Financial fraud / unauthorized trading
- Phishing / social engineering
- Reputational damage
- Regulatory penalties

### 1.3 Jurisdiction Determination
```
[ ] Are affected users in India? → CERT-In, RBI/SEBI if applicable
[ ] Are affected users in EU? → GDPR (DPAs)
[ ] Are affected users in other jurisdictions? → Local laws
```

---

## Phase 2: Authority Notification (Within Legal Deadlines)

### 2.1 India: CERT-In (6 hours from awareness)
**Required Information**:
- Description of breach
- Date/time of breach and discovery
- Types of personal data affected
- Number of data subjects affected (India)
- Likely consequences
- Measures taken/planned
- Contact details of DPO/security lead

**Contact**: 
- CERT-In Incident Reporting: incident@cert-in.org.in
- Portal: https://www.cert-in.org.in/incident-reporting
- Phone: +91-11-24368572

### 2.2 EU: GDPR (72 hours from awareness)
**Supervisory Authority**: Lead DPA (where main establishment is)
- If no EU establishment: DPA of affected users' residence

**Required Information** (Article 33):
- Nature of breach (categories, approximate numbers)
- DPO contact details
- Likely consequences
- Measures taken/proposed

### 2.3 SEBI (if applicable)
- If platform regulated as Market Infrastructure Institution
- Report via SEBI SCORES portal

### 2.4 Notification Template (CERT-In)

```
Subject: Cyber Security Incident Report - Stock Broker Platform - [DATE]

To: incident@cert-in.org.in

1. Organization: Stock Broker Platform (Pseudonym: SB-Platform)
2. Sector: Financial Technology / Paper Trading Platform
3. Date/Time of Incident: [YYYY-MM-DD HH:MM UTC]
4. Date/Time of Discovery: [YYYY-MM-DD HH:MM UTC]
5. Type of Incident: [Unauthorized Access / Data Leak / Ransomware / etc.]
6. Attack Vector: [CVE-XXXX / Misconfiguration / Phishing / etc.]
7. Systems Affected: [API / Database / WebSocket / etc.]
8. Data Categories Compromised:
   - [ ] Email addresses (hashed)
   - [ ] Password hashes (Argon2id)
   - [ ] Trading history (orders, fills)
   - [ ] Portfolio data
   - [ ] API keys
   - [ ] Other: [specify]
9. Number of Affected Data Subjects (India): [count]
10. Number of Affected Data Subjects (Global): [count]
11. Likely Consequences: [Identity theft / Account takeover / Phishing / None]
12. Measures Taken:
    - [ ] Containment: [describe]
    - [ ] Eradication: [describe]
    - [ ] Recovery: [describe]
    - [ ] Credential rotation: [describe]
    - [ ] User notification: [describe/planned]
13. Root Cause: [technical description]
14. Contact: [Security Lead Name, email, phone]
15. DPO Contact: [if appointed]
```

---

## Phase 3: User Notification (If High Risk)

### 3.1 When to Notify Users (GDPR Article 34)
- **Required**: High risk to rights and freedoms
- **Not Required**: 
  - Data encrypted (unreadable without key)
  - Measures taken to eliminate risk
  - Disproportionate effort (public communication instead)

### 3.2 User Notification Template

**Email Subject**: Important Security Notice - Action Required

**Email Body**:
```
Dear [User Name],

We are writing to inform you of a security incident that may have affected your account on the Stock Broker Platform.

**What Happened**
On [date], we detected [brief description: unauthorized access to our database / misconfigured server / etc.]. We immediately [contained the breach / secured the system / rotated credentials].

**What Information Was Involved**
The following information may have been accessed:
- [Email address]
- [Trading history: orders, fills, P&L]
- [Portfolio balances]
- [API keys - revoked and reissued]
- [Password hash - protected by Argon2id]

**What We Are Doing**
- [Contained the breach within X hours]
- [Rotated all encryption keys and passwords]
- [Enhanced monitoring and access controls]
- [Engaged external security firm for investigation]

**What You Should Do**
1. **Change your password** at https://app.stockbroker.example.com/settings/security
2. **Revoke and regenerate API keys** at https://app.stockbroker.example.com/settings/api
3. **Review recent account activity** for unauthorized actions
4. **Enable 2FA** if not already enabled (coming soon)

**No Financial Risk**
This is a **paper trading platform only**. No real money, custody, or brokerage credentials are involved. All trading is simulated.

**Questions?**
Contact our security team at security@stockbroker.example.com or reply to this email.

We take your privacy seriously and apologize for any concern this may cause.

Sincerely,
The Stock Broker Security Team
```

### 3.3 Notification Channels
- [ ] Email (primary)
- [ ] In-app notification banner
- [ ] Blog post / status page update
- [ ] Social media (if widespread)

---

## Phase 4: Documentation & Evidence

### 4.1 Evidence Collection
```
[ ] System logs (72 hours before/after)
[ ] Access logs (auth, API, WebSocket)
[ ] Database audit logs
[ ] Network captures (if available)
[ ] Memory dumps (if P0)
[ ] Forensic images of affected systems
```

### 4.2 Chain of Custody
```
[ ] Hash all evidence (SHA-256)
[ ] Store in encrypted, access-controlled location
[ ] Document who accessed, when, why
[ ] Preserve for minimum 3 years (legal hold)
```

### 4.3 Breach Register Entry
Maintain `breach-register.csv`:
```csv
Date,Incident_ID,Type,Records_Affected,Users_Affected,Notification_Deadline,Notified_Authorities,Notified_Users,Status
2024-01-15,BR-20240115-001,Unauthorized Access,5000,1200,2024-01-15 14:00,CERT-In,Yes,Closed
```

---

## Phase 5: Post-Breach Actions

### 5.1 Remediation Verification
```
[ ] Root cause fixed and tested
[ ] Compensating controls in place
[ ] Penetration test of fix
[ ] Third-party audit (if P0)
```

### 5.2 User Support
```
[ ] Dedicated support channel for breach inquiries
[ ] Password reset assistance
[ ] API key reissuance support
[ ] Credit monitoring (if financial data exposed - N/A for paper trading)
```

### 5.3 Process Improvement
```
[ ] Update detection rules for breach pattern
[ ] Improve encryption/access controls
[ ] Enhance incident response playbook
[ ] Conduct tabletop exercise
```

---

## Quick Decision Tree

```
BREACH DETECTED
      │
      ▼
Is personal data involved?
      │
      ├── NO ──► Document, fix, monitor (No notification required)
      │
      └── YES
            │
            ▼
Assess risk to individuals (likelihood × severity)
      │
      ├── CRITICAL/HIGH ──► NOTIFY AUTHORITIES (6h/72h) + NOTIFY USERS
      │
      ├── MEDIUM ──► NOTIFY AUTHORITIES (6h/72h)
      │
      └── LOW ──► DOCUMENT ONLY
            │
            ▼
CONTAIN → ERADICATE → RECOVER → POST-INCIDENT REVIEW
```

---

## Contacts

| Authority | Contact | Deadline | Reference |
|-----------|---------|----------|-----------|
| CERT-In | incident@cert-in.org.in | 6 hours | IT Act 2000 |
| Lead DPA (EU) | [varies] | 72 hours | GDPR Art 33 |
| SEBI (if applicable) | SCORES portal | Per regulation | SEBI LODR |
| Legal Counsel | [firm/contact] | Immediate | Privilege |

---

## Related Runbooks
- [Security Incident](security-incident.md)
- [Key Compromise](key-compromise.md)
- [DDoS Mitigation](ddos-mitigation.md)