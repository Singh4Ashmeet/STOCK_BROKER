# Operational Runbook

## Overview
Day-to-day operations for the Stock Broker platform. Covers deployments, scaling, monitoring, and common operational tasks.

**Audience**: Platform Engineers, On-Call Engineers
**Last Updated**: $(date +%Y-%m-%d)

---

## System Architecture Summary

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Users     │────▶│  Cloudflare │────▶│   NGINX     │
│  (Browser)  │     │  (WAF/DDoS) │     │  (Edge)     │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    ▼                          ▼                          ▼
            ┌─────────────┐            ┌─────────────┐            ┌─────────────┐
            │   Java API  │            │  Rust Feed  │            │  Python     │
            │  (Port 8081)│            │  (Port 9000)│            │  Research   │
            └──────┬──────┘            └──────┬──────┘            └──────┬──────┘
                   │                          │                          │
                   ▼                          ▼                          ▼
            ┌─────────────┐            ┌─────────────┐            ┌─────────────┐
            │  PostgreSQL │            │   Redis     │            │  ClickHouse │
            │  (Port 5432)│            │  (Port 6379)│            │  (Port 8123)│
            └─────────────┘            └─────────────┘            └─────────────┘
```

---

## Service Endpoints

| Service | Internal URL | External URL | Health Check |
|---------|--------------|--------------|--------------|
| Java API | `http://api:8081` | `https://api.example.com` | `/api/v1/system/health` |
| Rust Feed | `http://feed:9000` (gRPC) | N/A | `/health` (9100) |
| Python Research | `http://research:9101` | N/A | `/health` |
| PostgreSQL | `postgres:5432` | N/A | `pg_isready` |
| Redis | `redis:6379` | N/A | `redis-cli ping` |
| ClickHouse | `clickhouse:8123` | N/A | `/ping` |
| Prometheus | `prometheus:9090` | `https://metrics.example.com` | `/-/healthy` |
| Grafana | `grafana:3000` | `https://grafana.example.com` | `/api/health` |

---

## Common Operations

### 1. Deploy New Version

#### Java API
```bash
# 1. Build and push image
cd java
./mvnw -DskipTests package docker:build docker:push -Ddocker.image.tag=v1.2.3

# 2. Update deployment (canary)
kubectl set image deployment/api api=stockbroker/api:v1.2.3 -n prod
kubectl rollout status deployment/api -n prod --timeout=5m

# 3. Verify
curl -f https://api.example.com/api/v1/system/health
# Check error rates in Grafana for 10 min
```

#### Rust Feed
```bash
# 1. Build and push
cd rust
cargo build --release -p sb-server
docker build -t stockbroker/feed:v1.2.3 -f infra/feed/Dockerfile .
docker push stockbroker/feed:v1.2.3

# 2. Deploy (rolling restart)
kubectl set image deployment/feed feed=stockbroker/feed:v1.2.3 -n prod
kubectl rollout status deployment/feed -n prod --timeout=5m

# 3. Verify gRPC health
grpc_health_probe -addr=feed:9000 -rpc-timeout=5s
```

#### Python Research
```bash
cd python
docker build -t stockbroker/research:v1.2.3 -f infra/research/Dockerfile .
docker push stockbroker/research:v1.2.3

kubectl set image deployment/research research=stockbroker/research:v1.2.3 -n prod
kubectl rollout status deployment/research -n prod
```

### 2. Rollback Deployment
```bash
# Java API
kubectl rollout undo deployment/api -n prod
kubectl rollout status deployment/api -n prod

# Verify old version running
kubectl get pods -n prod -l app=api -o jsonpath='{.items[*].spec.containers[0].image}'
```

### 3. Scale Services

#### Horizontal Scaling (API)
```bash
# Manual scale
kubectl scale deployment api --replicas=10 -n prod

# Check HPA status
kubectl get hpa api-hpa -n prod
```

#### Vertical Scaling (Feed - requires restart)
```bash
# Update resource limits
kubectl patch deployment feed -n prod -p '
spec:
  template:
    spec:
      containers:
      - name: feed
        resources:
          limits:
            memory: "2Gi"
            cpu: "2000m"
          requests:
            memory: "1Gi"
            cpu: "1000m"
'
kubectl rollout restart deployment/feed -n prod
```

### 4. Database Operations

#### PostgreSQL
```bash
# Connect
kubectl exec -it -n prod postgresql-0 -- psql -U sb_app -d stockbroker

# Run migration
kubectl exec -it -n prod api-xxx -- java -jar /app/migration.jar

# Backup (automated via pgBackRest)
kubectl exec -it -n prod pgbackrest -- pgbackrest --stanza=main backup

# Restore
kubectl exec -it -n prod pgbackrest -- pgbackrest --stanza=main restore --target-time="2024-01-15 10:00:00"
```

#### Redis
```bash
# Flush cache (careful!)
kubectl exec -it -n prod redis-0 -- redis-cli -a $REDIS_PASSWORD FLUSHALL

# Check memory
kubectl exec -it -n prod redis-0 -- redis-cli -a $REDIS_PASSWORD INFO memory

# Backup (RDB snapshot)
kubectl exec -it -n prod redis-0 -- redis-cli -a $REDIS_PASSWORD BGSAVE
```

#### ClickHouse
```bash
# Query
curl "http://clickhouse:8123/?query=SELECT%20count()%20FROM%20stockbroker.trades" \
  -H "X-ClickHouse-User: default" -H "X-ClickHouse-Key: $CH_PASS"

# Backup
curl -X POST "http://clickhouse:8123/backups" -d '{"destination":"file:///backups"}'

# Optimize table
curl "http://clickhouse:8123/?query=OPTIMIZE%20TABLE%20stockbroker.trades%20FINAL" \
  -H "X-ClickHouse-User: default" -H "X-ClickHouse-Key: $CH_PASS"
```

### 5. Certificate Management

#### TLS Certificates (Let's Encrypt via cert-manager)
```bash
# Check certificate status
kubectl get certificates -n prod
kubectl describe certificate api-tls -n prod

# Force renewal
kubectl delete secret api-tls -n prod
# cert-manager will reissue automatically

# Manual certificate (if needed)
kubectl create secret tls api-tls \
  --cert=./cert.pem --key=./key.pem -n prod
```

#### JWT Keys
```bash
# Rotate (see key-compromise.md)
./scripts/rotate_jwt_keys.sh
```

---

## Monitoring & Alerting

### Key Dashboards (Grafana)
| Dashboard | URL | Purpose |
|-----------|-----|---------|
| System Overview | `/d/system-overview` | Global health, error rates, latency |
| API Detail | `/d/api-detail` | Per-endpoint metrics, rate limits |
| WebSocket | `/d/websocket` | Connections, messages, conflation |
| Feed Service | `/d/feed` | gRPC streams, latency stages |
| Database | `/d/postgres` | Connections, query latency, replication |
| Business | `/d/business` | Orders, fills, active users |

### Critical Alerts (Page Immediately)
| Alert | Condition | Runbook |
|-------|-----------|---------|
| `APIHighErrorRate` | 5xx > 5% for 2m | Check logs, rollback if deploy |
| `FeedDown` | gRPC health failing | Restart feed, check Redis |
| `PostgresDown` | pg_isready failing | Check Patroni, failover |
| `RedisDown` | Redis ping failing | Check sentinel, failover |
| `WSHighConflation` | Conflation rate > 100/s | Scale API, check slow clients |
| `DiskSpaceCritical` | > 90% on any volume | Cleanup, expand volume |

### Warning Alerts (Ticket Next Business Day)
| Alert | Condition |
|-------|-----------|
| `APIHighLatency` | p99 > 2s for 5m |
| `RateLimitExceeded` | 429s > 100/min |
| `MemoryGrowth` | RSS growth > 50MB/hour |
| `CertificateExpiring` | < 30 days |

---

## On-Call Procedures

### On-Call Handoff (Every Monday)
```
[ ] Review open incidents from past week
[ ] Check upcoming deployments
[ ] Verify on-call phone/Slack notifications work
[ ] Confirm runbook access
[ ] Update on-call calendar
```

### Incident Response Flow
```
1. ACK alert in PagerDuty/Slack within 5 min
2. Assess severity (P0/P1/P2/P3)
3. Follow appropriate runbook:
   - P0: security-incident.md / ddos-mitigation.md
   - P1: Check logs, recent deploy, rollback if needed
   - P2: Create ticket, investigate next business day
   - P3: Create ticket, backlog
4. Update status in #incidents channel every 15 min (P0) / 1 hour (P1)
5. Resolve alert, document in incident tracker
```

### Post-Incident (Within 24h)
```
[ ] Create incident report (incidents/INC-YYYYMMDD-XXX.md)
[ ] Add action items to backlog
[ ] Schedule retrospective if P0/P1
```

---

## Troubleshooting Guide

### Symptom: High API Latency
```
1. Check recent deploy → rollback if correlated
2. Check DB: slow queries (pg_stat_statements)
3. Check Redis: latency, memory, evictions
4. Check Feed: gRPC latency, subscription count
5. Check JVM: GC pauses, heap usage
6. Check network: iperf3 between pods
```

### Symptom: WebSocket Disconnections
```
1. Check ws_active_connections gauge
2. Check conflation rate (ws_conflation_total)
3. Check token expiry (sessionsClosedByTokenExpiry)
4. Check rate limits (429 responses)
4. Check feed health (upstream data flow)
```

### Symptom: Feed Service Not Publishing
```
1. Check gRPC health: grpc_health_probe -addr=feed:9000
2. Check Redis connection: feed logs for "redis"
3. Check subscription manager: active symbols
4. Check upstream (mock/external feed) connectivity
5. Check metrics: feed_stage_latency_*
```

### Symptom: Database Connection Exhaustion
```
1. Check active connections: SELECT count(*) FROM pg_stat_activity
2. Check pool settings (Hikari: max 20)
3. Check for connection leaks (long-running transactions)
4. Kill idle: SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state='idle' AND state_change < now() - interval '10 min'
5. Scale pool or add read replicas
```

---

## Maintenance Windows

| Task | Frequency | Window | Automation |
|------|-----------|--------|------------|
| PostgreSQL vacuum | Daily | 03:00 UTC | pg_cron |
| PostgreSQL backup | Continuous | N/A | pgBackRest |
| Redis backup (RDB) | Hourly | N/A | BGSAVE cron |
| ClickHouse optimize | Weekly | Sun 04:00 UTC | Cron |
| Certificate check | Daily | 06:00 UTC | cert-manager |
| Log rotation | Daily | 00:00 UTC | logrotate |
| Security scan | Weekly | Mon 02:00 UTC | CI pipeline |
| Dependency update | Monthly | 1st Mon | Dependabot |

---

## Capacity Planning

### Current Limits (Per Instance)
| Service | CPU | Memory | Max Connections | Max RPS |
|---------|-----|--------|-----------------|---------|
| Java API | 1 core | 1 GB | 500 (Hikari) | 2000 |
| Rust Feed | 2 cores | 2 GB | 1000 gRPC | 50k msgs/s |
| PostgreSQL | 2 cores | 4 GB | 200 | 5000 qps |
| Redis | 1 core | 512 MB | 10000 | 100k ops/s |
| ClickHouse | 4 cores | 8 GB | 100 | 1M rows/s |

### Scaling Triggers
- **API**: CPU > 70% for 5m → scale out (max 20)
- **Feed**: Memory > 80% → investigate (vertical scale)
- **PostgreSQL**: Connections > 150 → add read replica
- **Redis**: Memory > 80% → eviction policy check, scale
- **ClickHouse**: Query latency > 1s → add shard/replica

---

## Useful Commands Reference

```bash
# Kubernetes
kubectl get pods -n prod -o wide
kubectl logs -n prod -l app=api --tail=100 -f
kubectl exec -it -n prod api-xxx -- bash
kubectl port-forward -n prod svc/api 8081:8081

# Docker (local)
docker compose --profile core --profile obs up -d
docker compose logs -f api
docker exec -it sb_api bash

# Database
psql "postgresql://sb_app:pass@localhost:5432/stockbroker"
redis-cli -h localhost -p 6379 -a $REDIS_PASSWORD
clickhouse-client -h localhost --port 9000 --user default --password $CH_PASS

# Metrics
curl -s localhost:9090/metrics | grep feed_stage_latency
curl -s localhost:8081/actuator/prometheus | grep jvm_memory

# Debug
kubectl debug -it api-xxx --image=busybox --target=api -n prod
nsenter -t $(docker inspect -f '{{.State.Pid}}' sb_api) -n netstat -tulpn
```

---

## Contacts

| Role | Primary | Backup |
|------|---------|--------|
| Platform Lead | [Name] | [Name] |
| Backend Lead | [Name] | [Name] |
| Security Lead | [Name] | [Name] |
| Database Admin | [Name] | [Name] |
| Vendor Support | Cloudflare Enterprise | AWS Support |

---

## Related Documents
- [Security Incident](security-incident.md)
- [Key Compromise](key-compromise.md)
- [Data Breach](data-breach.md)
- [DDoS Mitigation](ddos-mitigation.md)
- [Backup/Restore Test](backup_restore_test.sh)
- [Security Checklist](SECURITY_CHECKLIST.md)