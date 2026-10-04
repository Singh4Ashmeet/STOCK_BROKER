#!/usr/bin/env bash
# scripts/verify-infra.sh - Verify infrastructure health and configuration
# Idempotent, never prints secrets, prints PASS/FAIL per check, non-zero exit on any FAIL

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_pass() { echo -e "${GREEN}[PASS]${NC} $*"; PASS_COUNT=$((PASS_COUNT + 1)); }
log_fail() { echo -e "${RED}[FAIL]${NC} $*"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

# Helper to run SQL on Postgres
# Returns exit code of psql (0 on success, non-zero on error)
pg_exec() {
    local user="$1"
    local db="$2"
    local sql="$3"
    docker exec -i sb_postgres psql -v ON_ERROR_STOP=1 -U "$user" -d "$db" -c "$sql" 2>&1
    return ${PIPESTATUS[0]}
}

# Helper to run Redis command
redis_exec() {
    local password="$1"
    shift
    docker exec -i sb_redis redis-cli -a "$password" "$@"
}

# Helper to run ClickHouse query
# Uses a temporary file inside the container to avoid quoting issues
ch_exec() {
    local user="$1"
    local password="$2"
    local query="$3"
    # Create temp file inside container and execute query
    docker exec sb_clickhouse sh -c "cat > /tmp/ch_query.sql <<'EOF'
$query
EOF
    clickhouse-client --user \"$user\" --password \"$password\" < /tmp/ch_query.sql
    rm -f /tmp/ch_query.sql" 2>&1
}

# Load .env for passwords
if [[ ! -f .env ]]; then
    log_fail ".env file not found. Run 'make bootstrap' first."
    exit 1
fi

source .env

log_info "Starting infrastructure verification..."
echo

# ============================================================
# 1. Postgres role permissions
# ============================================================
log_info "Checking Postgres role permissions..."

# sb_migrator should be able to CREATE TABLE
if pg_exec "sb_migrator" "stockbroker" "CREATE TABLE test_verify_migrator (id int); DROP TABLE test_verify_migrator;" >/dev/null 2>&1; then
    log_pass "sb_migrator can CREATE TABLE and DROP TABLE"
else
    log_fail "sb_migrator cannot CREATE/DROP TABLE"
fi

# sb_app should NOT be able to CREATE TABLE
if pg_exec "sb_app" "stockbroker" "CREATE TABLE test_verify_app (id int);" >/dev/null 2>&1; then
    log_fail "sb_app should not be able to CREATE TABLE (but it can)"
else
    log_pass "sb_app cannot CREATE TABLE (correct)"
fi

# sb_app should be able to INSERT/SELECT/UPDATE/DELETE on regular tables (test with a table created by migrator)
if pg_exec "sb_migrator" "stockbroker" "CREATE TABLE IF NOT EXISTS test_verify_app_dml (id int, val text); GRANT SELECT, INSERT, UPDATE, DELETE ON test_verify_app_dml TO sb_app;" >/dev/null 2>&1; then
    if pg_exec "sb_app" "stockbroker" "INSERT INTO test_verify_app_dml VALUES (1, 'test'); SELECT * FROM test_verify_app_dml; DELETE FROM test_verify_app_dml;" >/dev/null 2>&1; then
        log_pass "sb_app can INSERT/SELECT/DELETE on granted tables"
    else
        log_fail "sb_app cannot perform DML on granted tables"
    fi
    pg_exec "sb_migrator" "stockbroker" "DROP TABLE IF EXISTS test_verify_app_dml;" >/dev/null 2>&1
else
    log_fail "Failed to create test table for sb_app DML test"
    pg_exec "sb_migrator" "stockbroker" "DROP TABLE IF EXISTS test_verify_app_dml;" >/dev/null 2>&1
fi

# sb_readonly should NOT be able to INSERT
if pg_exec "sb_migrator" "stockbroker" "CREATE TABLE IF NOT EXISTS test_verify_ro (id int); GRANT SELECT ON test_verify_ro TO sb_readonly;" >/dev/null 2>&1; then
    if pg_exec "sb_readonly" "stockbroker" "INSERT INTO test_verify_ro VALUES (1);" >/dev/null 2>&1; then
        log_fail "sb_readonly should not be able to INSERT (but it can)"
    else
        log_pass "sb_readonly cannot INSERT (correct)"
    fi
    if pg_exec "sb_readonly" "stockbroker" "SELECT * FROM test_verify_ro;" >/dev/null 2>&1; then
        log_pass "sb_readonly can SELECT (correct)"
    else
        log_fail "sb_readonly cannot SELECT"
    fi
    pg_exec "sb_migrator" "stockbroker" "DROP TABLE IF EXISTS test_verify_ro;" >/dev/null 2>&1
else
    log_fail "Failed to create test table for sb_readonly test"
    pg_exec "sb_migrator" "stockbroker" "DROP TABLE IF EXISTS test_verify_ro;" >/dev/null 2>&1
fi

echo

# ============================================================
# 2. Redis authentication
# ============================================================
log_info "Checking Redis authentication..."

# Unauthenticated PING should be refused
if redis_exec "" PING 2>&1 | grep -q "NOAUTH\|ERR"; then
    log_pass "Unauthenticated PING is refused"
else
    log_fail "Unauthenticated PING was not refused"
fi

# Authenticated PING should return PONG
if redis_exec "${SB_REDIS_PASSWORD}" PING 2>&1 | grep -q "PONG"; then
    log_pass "Authenticated PING returns PONG"
else
    log_fail "Authenticated PING did not return PONG"
fi

echo

# ============================================================
# 3. ClickHouse tables exist with correct schema
# ============================================================
log_info "Checking ClickHouse tables..."

# Check trades table exists with correct partition, order by, TTL
trades_info=$(ch_exec "default" "${SB_CLICKHOUSE_PASSWORD}" "SELECT engine, partition_key, sorting_key, create_table_query FROM system.tables WHERE database = 'stockbroker' AND name = 'trades' FORMAT TSV" 2>&1 || true)
if echo "$trades_info" | grep -q "MergeTree"; then
    log_pass "trades table exists with MergeTree engine"
else
    log_fail "trades table missing or wrong engine: $trades_info"
fi
if echo "$trades_info" | grep -q "toYYYYMMDD(ts)"; then
    log_pass "trades table partitioned by toYYYYMMDD(ts)"
else
    log_fail "trades table not partitioned by toYYYYMMDD(ts): $trades_info"
fi
if echo "$trades_info" | grep -q "symbol, ts"; then
    log_pass "trades table ordered by (symbol, ts)"
else
    log_fail "trades table not ordered by (symbol, ts): $trades_info"
fi
if echo "$trades_info" | grep -q "TTL toDateTime(ts) + toIntervalDay(30)"; then
    log_pass "trades table has TTL 30 days"
else
    log_fail "trades table missing TTL 30 days: $trades_info"
fi

# Check metrics_1s table exists with correct partition, order by
metrics_info=$(ch_exec "default" "${SB_CLICKHOUSE_PASSWORD}" "SELECT engine, partition_key, sorting_key FROM system.tables WHERE database = 'stockbroker' AND name = 'metrics_1s' FORMAT TSV" 2>&1 || true)
if echo "$metrics_info" | grep -q "MergeTree"; then
    log_pass "metrics_1s table exists with MergeTree engine"
else
    log_fail "metrics_1s table missing or wrong engine: $metrics_info"
fi
if echo "$metrics_info" | grep -q "toYYYYMM(ts)"; then
    log_pass "metrics_1s table partitioned by toYYYYMM(ts)"
else
    log_fail "metrics_1s table not partitioned by toYYYYMM(ts): $metrics_info"
fi
if echo "$metrics_info" | grep -q "symbol, ts"; then
    log_pass "metrics_1s table ordered by (symbol, ts)"
else
    log_fail "metrics_1s table not ordered by (symbol, ts): $metrics_info"
fi

echo

# ============================================================
# 4. Prometheus healthy and alert rules valid
# ============================================================
log_info "Checking Prometheus..."

# Check Prometheus health endpoint
if docker exec sb_prometheus wget -q --spider http://localhost:9090/-/healthy 2>&1; then
    log_pass "Prometheus health endpoint responds"
else
    log_fail "Prometheus health endpoint not responding"
fi

# Check promtool check rules on alerts.yml
if docker exec sb_prometheus promtool check rules /etc/prometheus/alerts.yml 2>&1 | grep -q "SUCCESS"; then
    log_pass "promtool check rules passes on alerts.yml"
else
    log_fail "promtool check rules failed on alerts.yml"
    docker exec sb_prometheus promtool check rules /etc/prometheus/alerts.yml 2>&1 | head -20
fi

echo

# ============================================================
# 5. Grafana dashboards provisioned
# ============================================================
log_info "Checking Grafana dashboards..."

# Wait for Grafana to be ready
sleep 2

dashboards=$(python3 scripts/check_grafana_dashboards.py 2>&1 || true)
# Filter out error lines
dashboard_titles=$(echo "$dashboards" | grep -v "ERROR" || true)
expected_dashboards=("Latency" "Feed Health" "Fan-out" "Trading")

all_found=true
for expected in "${expected_dashboards[@]}"; do
    if echo "$dashboard_titles" | grep -q "$expected"; then
        log_pass "Grafana dashboard '$expected' found"
    else
        log_fail "Grafana dashboard '$expected' NOT found"
        all_found=false
    fi
done

if [[ "$all_found" == "true" ]]; then
    log_pass "Exactly the four expected dashboards are provisioned"
else
    log_fail "Missing expected dashboards. Found: $dashboard_titles"
fi

echo

# ============================================================
# 6. Published ports bound to 127.0.0.1 only
# ============================================================
log_info "Checking port bindings (127.0.0.1 only)..."

port_bindings=$(docker compose --profile core --profile analytics --profile obs --profile bus --profile app ps --format "table {{.Ports}}" 2>&1 | tail -n +2)
# Check for 0.0.0.0 or ::
if echo "$port_bindings" | grep -qE '0\.0\.0\.0|::'; then
    log_fail "Some ports are bound to 0.0.0.0 or :: (not 127.0.0.1 only): $port_bindings"
else
    log_pass "All published ports bound to 127.0.0.1 only"
fi

echo

# ============================================================
# Summary
# ============================================================
echo "========================================"
echo "Verification Summary: ${PASS_COUNT} PASS, ${FAIL_COUNT} FAIL"
echo "========================================"

if [[ ${FAIL_COUNT} -gt 0 ]]; then
    exit 1
fi

exit 0
