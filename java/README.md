# Stock Broker Java API

Spring Boot 3.3+ product API (multi-user) built with Java 21.

## Architecture

Modular monolith with packages by capability:

```
com.stockbroker
├── auth          # Authentication & authorization (JWT, registration, login)
├── market        # Market data gateway (gRPC client, state, fan-out)
├── portfolio     # Portfolio management (positions, ledger, P&L)
├── trading       # Order management & execution
├── alerts        # Price & metric alerts
├── research      # Backtesting & strategy research
├── admin         # Administrative endpoints
└── common        # Shared utilities, config, error handling, security
```

## Features

- **Java 21** with virtual threads (`spring.threads.virtual.enabled=true`)
- **Spring Boot 3.3** with Spring Security (JWT resource server)
- **Flyway** migrations for PostgreSQL
- **jOOQ** for type-safe SQL (ledger/trading code)
- **Testcontainers** for integration tests (PostgreSQL, Redis)
- **Protobuf/gRPC** codegen from shared `proto/` via buf
- **Problem+JSON** (RFC 9457) error responses with stable codes
- **Micrometer + Prometheus** metrics with percentile histograms
- **OpenAPI** via springdoc (client generated from it)
- **Docker** multi-stage build (non-root, healthchecks)
- **ArchUnit** architecture tests

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+ (or use `./mvnw`)
- Docker & Docker Compose
- PostgreSQL 16, Redis 7 (via docker-compose)

### Local Development

```bash
# Start infrastructure (PostgreSQL, Redis)
docker compose --profile core up -d

# Build and run
cd java
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The API will be available at `http://localhost:8081/api/v1`

### With Docker Compose (full stack)

```bash
# From project root
docker compose --profile core --profile app up -d --build
```

### Run Tests

```bash
cd java

# Unit tests only
./mvnw test

# Integration tests (requires Docker)
./mvnw verify

# With coverage report
./mvnw verify jacoco:report
```

## Configuration

Configuration via `application.yml` + environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `SB_POSTGRES_HOST` | PostgreSQL host | localhost |
| `SB_POSTGRES_PORT` | PostgreSQL port | 5432 |
| `SB_POSTGRES_DB` | Database name | stockbroker |
| `SB_POSTGRES_USER` | DB user | sb_app |
| `SB_POSTGRES_PASSWORD` | DB password | (required) |
| `SB_REDIS_HOST` | Redis host | localhost |
| `SB_REDIS_PORT` | Redis port | 6379 |
| `SB_REDIS_PASSWORD` | Redis password | (required) |
| `SB_FEED_HOST` | Rust feed gRPC host | localhost |
| `SB_FEED_GRPC_PORT` | Rust feed gRPC port | 9000 |
| `SB_JWT_PRIVATE_KEY_PATH` | Private key path | classpath:keys/jwt_private.key |
| `SB_JWT_PUBLIC_KEY_PATH` | Public key path | classpath:keys/jwt_public.key |

See `application.yml` for all options.

## API Endpoints

Base path: `/api/v1`

### Auth
- `POST /auth/register` - Register new user
- `POST /auth/login` - Login (returns access token + refresh cookie)
- `POST /auth/refresh` - Rotate refresh token
- `POST /auth/logout` - Revoke refresh token
- `GET /me` - Current user profile
- `POST /ws/ticket` - Get WebSocket ticket

### Market Data (read-only, authenticated)
- `GET /instruments` - List all instruments with specs
- `GET /market/{symbol}/snapshot?depth=20` - Order book snapshot
- `GET /market/{symbol}/metrics` - Derived metrics
- `GET /market/{symbol}/trades?limit=100` - Recent trades
- `GET /market/{symbol}/candles?interval=1m&from=&to=` - OHLCV candles

### Trading & Portfolio
- `POST /orders` - Place order (requires `Idempotency-Key` header)
- `GET /orders` - List orders (paginated)
- `GET /orders/{id}` - Get order
- `DELETE /orders/{id}` - Cancel order
- `GET /fills` - List fills (paginated)
- `GET /portfolio` - Portfolio summary
- `GET /ledger` - Ledger entries (paginated)
- `POST /orders/preview` - Slippage preview

### System
- `GET /system/health` - Public health check
- `GET /system/latency` - ADMIN: latency metrics
- `GET /actuator/prometheus` - Prometheus metrics (internal)

## Error Handling

All errors follow RFC 9457 (Problem+JSON):

```json
{
  "type": "urn:stockbroker:error:VALIDATION_FAILED",
  "title": "Validation failed",
  "status": 400,
  "detail": "email: must be a valid email address",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "errors": [
    {"field": "email", "message": "must be a valid email address"}
  ]
}
```

Stable error codes (see `docs/04-API-SPEC.md`):
- `AUTH_INVALID_CREDENTIALS`, `AUTH_TOKEN_EXPIRED`, `AUTH_REFRESH_REUSED`
- `RATE_LIMITED`, `VALIDATION_FAILED`
- `ORDER_INSUFFICIENT_FUNDS`, `ORDER_BOOK_INVALID`, `ORDER_DUPLICATE_KEY_MISMATCH`
- `INSTRUMENT_NOT_FOUND`, `BACKTEST_DATA_GAP`

## Database Migrations

Flyway migrations in `src/main/resources/db/migration/`:

```bash
# Run migrations
./mvnw flyway:migrate

# Generate jOOQ code (after migrations)
./mvnw generate-sources -Pjooq-codegen
```

## Protobuf/gRPC Codegen

Protobuf definitions in `../proto/stockbroker/market/v1/market.proto`.

```bash
# Generate Java stubs
./mvnw protobuf:compile protobuf:compile-custom

# Check for breaking changes (run in CI)
buf breaking --against '.git#branch=main' ../proto
```

## Observability

- **Metrics**: `/actuator/prometheus` (Prometheus format)
- **Health**: `/actuator/health` (liveness/readiness)
- **Logs**: Structured JSON with `X-Request-Id` correlation
- **Tracing**: Micrometer Tracing with Brave (W3C traceparent)

Key metrics:
- `http.server.requests` - HTTP latency histograms
- `gateway.stage.*` - Market gateway stage latencies
- `gateway.resync.total` - Feed resync count
- `gateway.connection.failures` - Connection failures

## Security

- JWT RS256 with rotating refresh tokens (family-based reuse detection)
- Argon2id password hashing (OWASP parameters)
- Rate limiting via Bucket4j + Redis
- Method-level `@PreAuthorize` for role checks
- User-scoped repositories (enforced by `CurrentUser` utility)

## Code Quality

```bash
# Static analysis
./mvnw spotbugs:check checkstyle:check

# Architecture tests
./mvnw test -Dtest=*ArchitectureTest

# Dependency vulnerability scan
./mvnw org.owasp:dependency-check-maven:check
```

## CI/CD

GitHub Actions workflow (`.github/workflows/java.yml`):
1. `proto-check` - buf lint & breaking
2. `build` - compile, unit tests, integration tests, SpotBugs, Checkstyle, JaCoCo
3. `docker` - build & test Docker image
4. `dependency-check` - OWASP dependency check

## Project Structure

```
java/
├── pom.xml
├── Dockerfile
├── .dockerignore
├── checkstyle.xml
├── spotbugs-include.xml
├── spotbugs-exclude.xml
├── header.txt
├── mvnw / mvnw.cmd
├── .mvn/wrapper/
└── src/
    ├── main/
    │   ├── java/com/stockbroker/
    │   │   ├── StockBrokerApiApplication.java
    │   │   ├── auth/
    │   │   ├── market/
    │   │   ├── portfolio/
    │   │   ├── trading/
    │   │   ├── alerts/
    │   │   ├── research/
    │   │   ├── admin/
    │   │   └── common/
    │   └── resources/
    │       ├── application.yml
    │       ├── application-dev.yml
    │       └── db/migration/V001__initial_schema.sql
    └── test/
        └── java/com/stockbroker/
            ├── common/TestcontainersConfiguration.java
            └── auth/AuthIntegrationTest.java
```

## License

MIT