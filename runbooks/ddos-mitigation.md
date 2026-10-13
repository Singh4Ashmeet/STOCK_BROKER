# DDoS Mitigation Runbook

## Overview
Procedure for detecting, mitigating, and recovering from Distributed Denial of Service attacks targeting the Stock Broker platform.

**Trigger**: Automated alert (latency spike, error rate, connection exhaustion) or manual report
**RTO**: < 5 minutes for automated mitigation, < 30 minutes for manual escalation

---

## Attack Vectors & Symptoms

| Vector | Target | Symptoms | Detection |
|--------|--------|----------|-----------|
| **Volumetric** | Network bandwidth | High bandwidth, packet loss | Cloud provider / NetFlow |
| **Protocol** | L4 (SYN flood, UDP) | Connection table exhaustion | `ss -s`, `netstat` |
| **Application** | L7 (HTTP/WS flood) | High CPU, latency, 5xx errors | Prometheus alerts |
| **WebSocket** | Connection exhaustion | Max sessions reached, slow fan-out | `ws_active_connections` gauge |
| **API** | Rate limit bypass | 429 spikes, auth endpoint abuse | `http_requests_total{status=429}` |
| **Feed gRPC** | Subscription flood | Feed service OOM, latency | `feed_*_latency` metrics |

---

## Architecture & Defense Layers

```
Internet
    │
    ▼
┌─────────────────────────────────────┐
│  Cloudflare / AWS Shield (L3/4)     │  ← Always-on DDoS protection
│  - WAF (L7 rules)                   │
│  - Rate limiting (IP, JA3 fingerprint)│
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│  NGINX / Envoy (Edge)               │  ← L7 protection
│  - Connection limits                │
│  - Request rate limits              │
│  - JA3 fingerprinting               │
│  - Challenge/JS challenge           │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│  Application Rate Limiting (Java)   │  ← Per-user/IP buckets
│  - Bucket4j + Redis                 │
│  - Auth: 20/min                     │
│  - Market: 600/min                  │
│  - Trading: 100/min                 │
│  - WS: 5 sessions/user, 10/min/IP   │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│  Service-Level Protection           │
│  - Feed: subscription refcounting   │
│  - WS: per-client bounded queues    │
│  - Conflation (drop deltas)         │
│  - Circuit breakers                 │
└─────────────────────────────────────┘
```

---

## Phase 1: Detection (Automated Alerts)

### 1.1 Prometheus Alert Rules (`.github/workflows/prometheus/alerts.yml`)

```yaml
groups:
- name: ddos
  rules:
  # HTTP DDoS
  - alert: HTTPRequestRateHigh
    expr: |
      sum(rate(http_server_requests_seconds_count[1m])) by (job) > 10000
    for: 1m
    labels:
      severity: warning
    annotations:
      summary: "High HTTP request rate on {{ $labels.job }}"
      
  - alert: HTTPErrorRateHigh
    expr: |
      sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m])) by (job)
      / sum(rate(http_server_requests_seconds_count[1m])) by (job) > 0.1
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "High 5xx error rate on {{ $labels.job }}"

  # Rate limiting
  - alert: RateLimitExceeded
    expr: |
      sum(rate(http_server_requests_seconds_count{status="429"}[1m])) by (job) > 100
    for: 1m
    labels:
      severity: warning
    annotations:
      summary: "Rate limit exceeded on {{ $labels.job }}"

  # WebSocket
  - alert: WSConnectionsHigh
    expr: ws_active_connections > 5000
    for: 1m
    labels:
      severity: warning
    annotations:
      summary: "High WebSocket connections: {{ $value }}"

  - alert: WSConnectionFailures
    expr: |
      rate(ws_connection_failures_total[5m]) > 10
    for: 1m
    labels:
      severity: critical
    annotations:
      summary: "WebSocket connection failures spiking"

  # Feed service
  - alert: FeedLatencyHigh
    expr: |
      histogram_quantile(0.99, rate(feed_stage_latency_seconds_bucket[5m])) > 1
    for: 2m
    labels:
      severity: warning
    annotations:
      summary: "Feed p99 latency > 1s"

  - alert: FeedMemoryHigh
    expr: |
      (process_resident_memory_bytes{job="feed"} / process_max_fds) > 0.8
    for: 5m
    labels:
      severity: critical
    annotations:
      summary: "Feed memory usage > 80%"
```

### 1.2 Grafana Dashboards
- **DDoS Overview**: Request rates, error rates, latency percentiles by endpoint
- **WebSocket Health**: Active connections, messages/sec, conflation rate, latency
- **Rate Limiting**: 429 responses by endpoint, user, IP
- **Feed Health**: gRPC streams, subscription counts, latency stages

---

## Phase 2: Automated Mitigation (0-5 min)

### 2.1 Cloudflare/AWS Shield (Always-On)
```bash
# No action needed - automatic
# - Volumetric: absorbed at edge
# - Known bad IPs: blocked via threat intelligence
# - WAF: OWASP rules + custom rules
```

### 2.2 NGINX/Envoy Auto-Mitigation
```nginx
# Rate limiting zones (defined in nginx.conf)
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=100r/s;
limit_req_zone $binary_remote_addr zone=auth_limit:10m rate=5r/s;
limit_req_zone $http_authorization zone=user_limit:10m rate=200r/m;

# Applied in location blocks
location /api/v1/auth/ {
    limit_req zone=auth_limit burst=10 nodelay;
    limit_req_status 429;
}

location /api/v1/ {
    limit_req zone=api_limit burst=50 nodelay;
    limit_req zone=user_limit burst=200 nodelay;
    limit_req_status 429;
}

# WebSocket specific
location /ws/v1 {
    limit_conn $binary_remote_addr 100;
    limit_req zone=api_limit burst=20 nodelay;
}
```

### 2.3 Application-Level (Java Bucket4j)
```java
// Already implemented in RateLimitFilter + MarketWebSocketHandler
// Automatic per-user/IP bucket exhaustion returns 429/CloseStatus
```

### 2.4 Auto-Scale (If Kubernetes)
```yaml
# HPA for API deployment
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Pods
    pods:
      metric:
        name: http_requests_per_second
      target:
        type: AverageValue
        averageValue: "1000"
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Percent
        value: 100
        periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300
```

---

## Phase 3: Manual Escalation (5-30 min)

### 3.1 Escalation Triggers
- [ ] Automated mitigation insufficient (latency > 5s, error rate > 20%)
- [ ] Attack bypassing WAF (new pattern)
- [ ] Legitimate traffic being blocked (false positives)
- [ ] Attack targeting specific user/account (targeted)

### 3.2 Escalation Actions

#### Step 1: Analyze Attack Pattern (2 min)
```bash
# Check top offending IPs
kubectl logs -n prod -l app=nginx --tail=10000 | \
  awk '{print $1}' | sort | uniq -c | sort -nr | head -20

# Check top offending users (by auth header)
kubectl logs -n prod -l app=api --tail=10000 | \
  grep -oP 'Authorization: Bearer \K[^ ]+' | sort | uniq -c | sort -nr | head -20

# Check WebSocket connections
kubectl exec -n prod deployment/api -- \
  curl -s localhost:8081/actuator/metrics/ws_active_connections
```

#### Step 2: Apply Targeted Blocks (5 min)
```bash
# Cloudflare: Block specific IPs/ASNs
cfwaf create-rule --ip 1.2.3.4/32 --action block --note "DDoS INC-XXX"

# Or: Challenge suspicious IPs (JS challenge)
cfwaf create-rule --ip 1.2.3.0/24 --action challenge --note "DDoS INC-XXX"

# NGINX: Add to deny list (configmap reload)
kubectl patch configmap nginx-config -n prod --patch '
data:
  nginx.conf: |
    deny 1.2.3.4;
    deny 5.6.7.0/24;
'
kubectl rollout restart daemonset/nginx -n prod
```

#### Step 3: Adjust Rate Limits (5 min)
```bash
# Temporarily tighten limits via ConfigMap
kubectl patch configmap rate-limit-config -n prod --patch '
data:
  sb.rate-limit.default-requests-per-minute: "100"
  sb.rate-limit.trading-requests-per-minute: "30"
  sb.rate-limit.market-requests-per-minute: "200"
'
kubectl rollout restart deployment/api -n prod
```

#### Step 4: Enable Maintenance Mode (If Severe)
```bash
# Return 503 for non-critical endpoints
kubectl patch configmap nginx-config -n prod --patch '
data:
  nginx.conf: |
    location /api/v1/backtests/ { return 503; }
    location /api/v1/strategies/ { return 503; }
'
kubectl rollout restart daemonset/nginx -n prod
```

#### Step 5: Engage Upstream Provider
```bash
# If volumetric > 10Gbps or sustained > 1hr
# Contact: Cloudflare Enterprise support / AWS Shield Advanced
# Provide: Attack vectors, timestamps, impacted services
```

---

## Phase 4: Recovery (30 min - 4 hours)

### 4.1 Gradual Limit Relaxation
```bash
# Monitor for 30 min after attack subsides
# Then gradually restore limits:
# 1. Remove IP blocks (after 1 hour clean)
# 2. Restore rate limits to normal (over 30 min)
# 3. Disable maintenance mode
# 4. Verify all health checks green
```

### 4.2 Post-Attack Validation
```bash
# Verify legitimate traffic flows
curl -H "Authorization: Bearer $TOKEN" https://api/api/v1/market/BTC-USDT/book
# Should return 200 with valid data

# Verify WebSocket
wscat -c "wss://api/ws/v1?ticket=$TICKET" -x '{"op":"subscribe","channel":"book","symbol":"BINANCE:BTC-USDT"}'
# Should receive book.snap then book.delta
```

---

## Phase 5: Post-Incident

### 5.1 Attack Analysis
```bash
# Export logs for analysis
kubectl logs -n prod -l app=nginx --since=2h > nginx_attack.log
kubectl logs -n prod -l app=api --since=2h > api_attack.log

# Analyze patterns
python3 analyze_ddos.py nginx_attack.log
```

### 5.2 Update Defenses
```
[ ] Add new WAF rules for attack signatures
[ ] Update rate limit thresholds based on legitimate peak
[ ] Add new Prometheus alerts for blind spots
[ ] Update runbook with lessons learned
```

### 5.3 Capacity Planning
```
[ ] Review if current capacity handles peak + attack
[ ] Consider: larger instances, more replicas, CDN caching
[ ] Update HPA thresholds
```

---

## Quick Reference Card

| Action | Command | Time |
|--------|---------|------|
| View live HTTP metrics | `kubectl top pods -n prod -l app=api` | 30s |
| View WS connections | `curl -s localhost:8081/actuator/metrics/ws_active_connections` | 10s |
| Block IP at Cloudflare | `cfwaf block-ip --ip 1.2.3.4` | 1 min |
| Tighten rate limits | `kubectl patch cm rate-limit-config...` | 2 min |
| Enable maintenance | `kubectl patch cm nginx-config...` | 2 min |
| Scale API replicas | `kubectl scale deployment api --replicas=10 -n prod` | 2 min |
| Check feed health | `grpc_health_probe -addr=feed:9000` | 5s |

---

## Runbook Testing

**Quarterly Tabletop Exercise**:
1. Simulate volumetric attack (10Gbps)
2. Simulate L7 API flood (50k RPS)
3. Simulate WebSocket exhaustion (10k connections)
4. Verify alerts fire, auto-mitigation works, manual steps documented

**Annual Red Team Exercise**:
- Authorized penetration testing including DDoS simulation
- Measure: Time to detect, time to mitigate, false positive rate

---

## Related Runbooks
- [Security Incident](security-incident.md)
- [Key Compromise](key-compromise.md)
- [Data Breach](data-breach.md)