# infra/ - Local Development Infrastructure

This directory contains all infrastructure-as-code for local development:
- Docker Compose (at repo root: `docker-compose.yml`) with profiles for different service groups
- Database initialization scripts (Postgres, ClickHouse)
- Observability stack (Prometheus, Grafana) with provisioning
- Bootstrap script for generating local secrets

## Quick Start

```bash
# 1. Generate secrets (run once)
./scripts/bootstrap.sh

# 2. Start the full stack (core + analytics + observability)
make up

# 3. Or start minimal core stack (Postgres + Redis only)
make up-core

# 4. Check service health
make ps
```

All tools run via Docker — no local installs required.

## Compose Profiles

| Profile | Services | Purpose |
|---------|----------|---------|
| `core` | Postgres, Redis | Minimum for Java/API development |
| `analytics` | ClickHouse | Historical analytics & research |
| `obs` | Prometheus, Grafana | Metrics, dashboards, alerting |
| `bus` | Redpanda | Message bus (optional, off by default) |
| `app` | feed, api, research, web | Application services (placeholders, not implemented) |

### Profile Combinations

```bash
# Minimum stack for API development
docker compose --profile core up -d

# Core + observability (recommended for daily dev)
docker compose --profile core --profile obs up -d

# Full stack with analytics
docker compose --profile core --profile analytics --profile obs up -d

# Full stack with message bus (for ClickHouse ingestion testing)
docker compose --profile core --profile analytics --profile obs --profile bus up -d
```

## Service Ports (all bound to 127.0.0.1 only)

| Service | Port | Protocol | Notes |
|---------|------|----------|-------|
| Postgres | 5432 | TCP | User: `sb_app` / `sb_migrator` / `sb_readonly` |
| Redis | 6379 | TCP | Password required |
| ClickHouse HTTP | 8123 | HTTP | Web UI at `http://127.0.0.1:8123/play` |
| ClickHouse Native | 9000 | TCP | Native protocol for clients |
| Prometheus | 9090 | HTTP | `http://127.0.0.1:9090` |
| Grafana | 3000 | HTTP | `http://127.0.0.1:3000` (admin / generated password) |
| Redpanda | 9092 | TCP | Kafka-compatible, only with `--profile bus` |

## Generated Secrets

Running `./scripts/bootstrap.sh` creates:

1. **`.env`** (gitignored) - All runtime configuration:
   - Database passwords (Postgres: migrator, app, readonly; Redis; ClickHouse)
   - Grafana admin password
   - Service-to-service token
   - JWT configuration

2. **`infra/secrets/`** (gitignored, mode 700):
   - `jwt_private.key` (mode 600) - Ed25519 private key for JWT signing
   - `jwt_public.key` (mode 644) - Ed25519 public key for JWT verification

3. **`.gitignore`** - Updated with secret patterns

4. **`.gitattributes`** - Enforces LF line endings for scripts, YAML, SQL, env files

### Regenerating Secrets

```bash
# Force regeneration (backs up existing .env first)
./scripts/bootstrap.sh --force
```

## Database Details

### Postgres (`core` profile)

- **Database**: `stockbroker`
- **Extensions**: `citext`, `pgcrypto`
- **Timezone**: UTC
- **Roles** (created by init script):
  - `sb_migrator` - DDL permissions (Flyway migrations)
  - `sb_app` - DML only, statement timeout 10s, **no UPDATE/DELETE on ledger tables**
  - `sb_readonly` - SELECT only

Application tables are created by Flyway migrations in Phase 4 (not in this init script).

### ClickHouse (`analytics` profile)

- **Database**: `stockbroker`
- **Tables** (created by init script):
  - `trades` - Partitioned by day, ordered by (symbol, ts), TTL 30 days
  - `metrics_1s` - Partitioned by month, ordered by (symbol, ts)
- **Users**:
  - `sb_clickhouse_user` - Admin (password from `SB_CLICKHOUSE_PASSWORD`)
  - `sb_app` - Read-only (password from `SB_CLICKHOUSE_APP_PASSWORD`, configured via `users.d/app-user.xml`)

> **Note**: Candle materialized views (1s, 1m, 5m, 1h, 1d) come in Phase 2+.

### Redis (`core` profile)

- **Configuration**: Password required, maxmemory 256MB, LRU eviction
- **Key patterns** (from docs/05-DATA-MODEL.md):
  - `mkt:{symbol}:book` - Latest order book snapshot
  - `mkt:{symbol}:metrics` - Latest computed metrics
  - `rl:*` - Rate limit buckets
  - `wsticket:{ticket}` - WebSocket auth tickets (30s TTL)
  - `login:fail:*` - Login failure counters
  - `idem:*` - Idempotency keys (24h TTL)

## Observability Stack

### Prometheus (`obs` profile)

- **Config**: `infra/prometheus/prometheus.yml`
- **Scrape targets** (configured for future services):
  - `feed` - `host.docker.internal:9100/metrics` (Rust feed)
  - `api` - `host.docker.internal:8081/actuator/prometheus` (Java API)
  - `research` - `host.docker.internal:9101/metrics` (Python research)
  - Self-monitoring on `localhost:9090`
- **Alert rules**: `infra/prometheus/alerts.yml` (implements docs/11 section 7)
- **Retention**: 15 days / 2GB

### Grafana (`obs` profile)

- **Provisioning**: Automatic via `infra/grafana/provisioning/`
- **Datasource**: Prometheus (pre-configured)
- **Dashboards** (in `infra/grafana/dashboards/`):
  - **Latency** - Per-stage heatmaps, p50/p99/p99.9, budget lines
  - **Feed Health** - Connection state, book validity, resyncs, queue depths
  - **Fan-out** - WS sessions, subscriptions, conflations, gateway latency
  - **Trading** - Orders by status, fills, reconciliation diff, reject reasons
- **Auth**: Admin user from `SB_GRAFANA_ADMIN_USER/PASSWORD`, anonymous disabled
- **Telemetry**: Disabled (`GF_ANALYTICS_REPORTING_ENABLED=false`)

## Message Bus (Optional)

### Redpanda (`bus` profile)

- Single node, development mode
- Memory limited to 512MB
- Kafka-compatible on port 9092
- **Off by default** - only started with `--profile bus`
- Per ADR-0002: The message bus is optional; live path uses direct gRPC

## Makefile Targets

| Target | Description |
|--------|-------------|
| `help` | Show all targets (default) |
| `bootstrap` | Run bootstrap script |
| `up` | Start core + analytics + obs |
| `up-core` | Start core only |
| `up-bus` | Start core + analytics + obs + bus |
| `down` | Stop all services |
| `ps` | Show service status |
| `logs` | Follow all logs |
| `reset-data` | Destroy all volumes (with confirmation) |
| `proto` | Run lint, format-check, build, compile-check |
| `proto-lint` | Run buf lint |
| `proto-format` | Format proto files in-place |
| `proto-format-check` | Check proto formatting |
| `proto-build` | Build proto files |
| `proto-breaking` | Check breaking changes vs main |
| `proto-compile-check` | Compile with grpcio-tools (Python) |
| `lint` | proto-lint + shellcheck |
| `test` | Placeholder (no code yet) |

## Resetting Data

```bash
# Destroys ALL data volumes (Postgres, Redis, ClickHouse, Prometheus, Grafana, Redpanda)
make reset-data
# Type 'yes' to confirm
```

## Rotating Secrets

```bash
# 1. Stop stack
make down

# 2. Regenerate secrets
./scripts/bootstrap.sh --force

# 3. Restart stack
make up
```

## Troubleshooting

### Windows / WSL2

1. **Docker Desktop WSL Integration**: Enable in Settings > Resources > WSL Integration
2. **Line endings**: `.gitattributes` enforces LF. If scripts fail with `$'\r': command not found`:
   ```bash
   dos2unix scripts/bootstrap.sh
   # Or configure git: git config core.autocrlf input
   ```
3. **host.docker.internal**: Works automatically with Docker Desktop. On Linux without Docker Desktop, add to `/etc/hosts`:
   ```
   172.17.0.1 host.docker.internal
   ```

### Common Issues

| Issue | Solution |
|-------|----------|
| `docker compose` not found | Install Docker Compose v2 (included with Docker Desktop) |
| `openssl` not found | Install OpenSSL (WSL: `sudo apt install openssl`) |
| Port already in use | Check `ss -ltn` or `netstat -an`; stop conflicting services |
| ClickHouse healthcheck fails | Wait longer (start_period 20s); check `docker logs sb_clickhouse` |
| Grafana "Data source not found" | Ensure Prometheus is healthy first (`make ps`) |
| Postgres connection refused | Wait for healthcheck; check `docker logs sb_postgres` |

### Verifying Port Bindings

```bash
# Should show only 127.0.0.1 addresses
docker compose ps
ss -ltn | grep -E '(5432|6379|8123|9000|9090|3000|9092)'
```

### Checking Logs

```bash
# All services
make logs

# Specific service
docker logs sb_postgres -f
docker logs sb_clickhouse -f
docker logs sb_prometheus -f
docker logs sb_grafana -f
```

## Security Notes

- **No secrets in git**: `.env`, `infra/secrets/`, `*.key`, `*.pem` are all gitignored
- **No default passwords**: All passwords generated by bootstrap.sh
- **Ports bound to 127.0.0.1**: No external exposure
- **Internal network**: All services communicate via `stockbroker_internal` bridge network
- **Non-root containers**: All images run as non-root users
- **Resource limits**: CPU/memory limits prevent resource exhaustion on laptop

## Verifying Configuration

```bash
# Validate compose syntax for each profile
docker compose --profile core config --quiet
docker compose --profile core --profile analytics config --quiet
docker compose --profile core --profile analytics --profile obs config --quiet
docker compose --profile core --profile bus config --quiet

# Check no secrets in config output
docker compose --profile core --profile analytics --profile obs config | grep -i password
# Should only show variable references like ${SB_POSTGRES_APP_PASSWORD}
```

## File Structure

```
infra/
├── docker-compose.yml          # Main compose file with profiles
├── postgres/
│   └── init/
│       └── 01-init-roles-and-db.sh    # Postgres roles, extensions, defaults
├── clickhouse/
│   ├── init/
│   │   └── 01-init.sql              # Database, tables, read-only role
│   └── users.d/
│       └── app-user.xml             # Low-privilege app user config
├── prometheus/
│   ├── prometheus.yml              # Scrape config + alert rules
│   └── alerts.yml                  # Alerting rules (docs/11 section 7)
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/datasources.yml
│   │   └── dashboards/dashboards.yml
│   └── dashboards/
│       ├── latency.json
│       ├── feed_health.json
│       ├── fanout.json
│       └── trading.json
└── README.md                       # This file
```

## Next Steps

After infrastructure is running:

1. **Phase 0.2**: Run `make proto` to generate gRPC/Protobuf code
2. **Phase 1**: Implement Rust feed service (connects to Redis, exposes metrics on :9100)
3. **Phase 4**: Implement Java API (connects to Postgres/Redis, exposes metrics on :8081/actuator/prometheus)
4. **Phase 6**: Implement Python research (connects to ClickHouse, exposes metrics on :9101)

See `docs/14-ROADMAP.md` for full phase details.