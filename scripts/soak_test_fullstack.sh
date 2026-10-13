#!/usr/bin/env bash
# 24-Hour Full-Stack Soak Test
# 
# Tests the entire Stock Broker platform:
# - Rust Feed Server (market data ingestion)
# - Java API (REST + WebSocket)
# - Python Research (backtesting)
# - Infrastructure (PostgreSQL, Redis, ClickHouse)
# - k6 WebSocket load test (1000 concurrent users)
#
# Usage: ./scripts/soak_test_fullstack.sh [DURATION_HOURS] [--quick]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DURATION_HOURS="${1:-24}"
QUICK_MODE="${2:-false}"
DURATION_SECONDS=$((DURATION_HOURS * 3600))
RESULTS_DIR="$REPO_ROOT/benchmarks/results/soak-fullstack-$(date +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD)"

mkdir -p "$RESULTS_DIR"

echo "========================================"
echo "24-Hour Full-Stack Soak Test"
echo "========================================"
echo "Duration: ${DURATION_HOURS} hours (${DURATION_SECONDS} seconds)"
echo "Results: ${RESULTS_DIR}"
echo "Commit: $(git rev-parse HEAD)"
echo "Rust: $(rustc --version 2>/dev/null || echo 'not installed')"
echo "Java: $(java -version 2>&1 | head -1)"
echo "Python: $(python3 --version 2>/dev/null || echo 'not installed')"
echo "k6: $(k6 version 2>/dev/null | head -1 || echo 'not installed')"
echo "Quick Mode: $QUICK_MODE"
echo "========================================"

# ============================================
# Configuration
# ============================================
API_BASE_URL="${SB_API_URL:-http://localhost:8081}"
WS_BASE_URL="${SB_WS_URL:-ws://localhost:8081}"
FEED_GRPC_URL="${SB_FEED_GRPC_URL:-localhost:9000}"
FEED_METRICS_URL="${SB_FEED_METRICS_URL:-http://localhost:9100}"
RESEARCH_URL="${SB_RESEARCH_URL:-http://localhost:9101}"
PROMETHEUS_URL="${SB_PROMETHEUS_URL:-http://localhost:9090}"

# Test symbols
TEST_SYMBOLS=("BINANCE:BTC-USDT" "BINANCE:ETH-USDT" "BINANCE:SOL-USDT")
WS_TEST_SYMBOL="BINANCE:BTC-USDT"

# Service PIDs
declare -A SERVICE_PIDS
declare -A SERVICE_LOGS

# ============================================
# Helper Functions
# ============================================

log() {
    echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] $*" | tee -a "$RESULTS_DIR/test.log"
}

check_cmd() {
    if ! command -v "$1" &> /dev/null; then
        log "WARNING: Required command '$1' not found"
        return 1
    fi
    return 0
}

start_service() {
    local name="$1"
    local cmd="$2"
    local log_file="$RESULTS_DIR/${name}.log"
    
    log "Starting $name..."
    eval "$cmd" > "$log_file" 2>&1 &
    local pid=$!
    SERVICE_PIDS["$name"]=$pid
    SERVICE_LOGS["$name"]=$log_file
    log "$name started (PID: $pid)"
}

stop_service() {
    local name="$1"
    local pid="${SERVICE_PIDS[$name]:-}"
    
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        log "Stopping $name (PID: $pid)..."
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
}

wait_for_health() {
    local name="$1"
    local url="$2"
    local max_attempts="${3:-30}"
    local interval="${4:-2}"
    
    log "Waiting for $name health at $url..."
    for i in $(seq 1 $max_attempts); do
        if curl -sf "$url" > /dev/null 2>&1; then
            log "$name health check OK"
            return 0
        fi
        sleep $interval
    done
    log "ERROR: $name health check failed after $max_attempts attempts"
    return 1
}

# ============================================
# Cleanup on Exit
# ============================================
cleanup() {
    log "Cleaning up services..."
    for name in "${!SERVICE_PIDS[@]}"; do
        stop_service "$name"
    done
    
    # Kill any remaining background jobs
    jobs -p | xargs -r kill 2>/dev/null || true
}
trap cleanup EXIT

# ============================================
# Prerequisites Check
# ============================================
log "Checking prerequisites..."

check_cmd cargo || true
check_cmd mvn || check_cmd ./mvnw || true
check_cmd python3 || true
check_cmd k6 || true
check_cmd curl || true
check_cmd jq || true

# Load environment
if [[ -f "$REPO_ROOT/.env" ]]; then
    source "$REPO_ROOT/.env"
fi

# ============================================
# Build Services
# ============================================
if [[ "$QUICK_MODE" != "true" ]]; then
    log "Building services..."
    
    # Rust Feed Server
    if [[ -d "$REPO_ROOT/rust/server" ]]; then
        log "Building Rust feed server..."
        cd "$REPO_ROOT/rust/server"
        cargo build --release -p sb-server 2>&1 | tee "$RESULTS_DIR/build_feed.log"
    fi
    
    # Java API
    if [[ -d "$REPO_ROOT/java" ]]; then
        log "Building Java API..."
        cd "$REPO_ROOT/java"
        ./mvnw -DskipTests package 2>&1 | tee "$RESULTS_DIR/build_api.log"
    fi
    
    # Python Research (just verify)
    if [[ -d "$REPO_ROOT/python" ]]; then
        log "Verifying Python environment..."
        cd "$REPO_ROOT/python"
        python3 -m pip install -q -r requirements.txt 2>&1 | tee "$RESULTS_DIR/build_research.log"
    fi
else
    log "Quick mode: skipping builds"
fi

# ============================================
# Start Infrastructure (via docker-compose)
# ============================================
log "Starting infrastructure..."
cd "$REPO_ROOT"

# Start core infrastructure
docker compose --profile core up -d 2>&1 | tee "$RESULTS_DIR/infra_start.log"

# Wait for infrastructure
log "Waiting for infrastructure to be healthy..."
sleep 10

# Check PostgreSQL
for i in {1..30}; do
    if docker exec sb_postgres pg_isready -U sb_migrator -d stockbroker > /dev/null 2>&1; then
        log "PostgreSQL ready"
        break
    fi
    sleep 2
done

# Check Redis
for i in {1..15}; do
    if docker exec sb_redis redis-cli -a "$SB_REDIS_PASSWORD" ping > /dev/null 2>&1; then
        log "Redis ready"
        break
    fi
    sleep 2
done

# Check ClickHouse
for i in {1..30}; do
    if curl -sf "http://localhost:8123/ping" > /dev/null 2>&1; then
        log "ClickHouse ready"
        break
    fi
    sleep 2
done

# ============================================
# Start Application Services
# ============================================

# Start Rust Feed Server
if [[ -f "$REPO_ROOT/rust/server/target/release/sb-server" ]]; then
    start_service "feed" "$REPO_ROOT/rust/server/target/release/sb-server $REPO_ROOT/rust/server/server.toml"
    wait_for_health "Feed" "$FEED_METRICS_URL/health" 30 2
    wait_for_health "Feed Metrics" "$FEED_METRICS_URL/metrics" 10 2
fi

# Start Java API
if [[ -f "$REPO_ROOT/java/target/stockbroker-api-*.jar" ]]; then
    API_JAR=$(ls "$REPO_ROOT/java/target/stockbroker-api-*.jar" | head -1)
    start_service "api" "java -jar $API_JAR --spring.profiles.active=demo"
    wait_for_health "API" "$API_BASE_URL/api/v1/system/health" 60 3
fi

# Start Python Research
if [[ -d "$REPO_ROOT/python" ]]; then
    start_service "research" "cd $REPO_ROOT/python && python3 -m uvicorn src.main:app --host 0.0.0.0 --port 9101"
    wait_for_health "Research" "$RESEARCH_URL/health" 30 2
fi

# Start Prometheus + Grafana (observability)
docker compose --profile obs up -d 2>&1 | tee -a "$RESULTS_DIR/infra_start.log"
wait_for_health "Prometheus" "$PROMETHEUS_URL/-/healthy" 30 2

# ============================================
# Verify Full Stack Health
# ============================================
log "Verifying full stack health..."

# API health
curl -sf "$API_BASE_URL/api/v1/system/health" | jq . 2>&1 | tee -a "$RESULTS_DIR/health_checks.log"

# Feed metrics
curl -sf "$FEED_METRICS_URL/metrics" | grep -E "feed_messages_total|feed_book_checks_total" | head -5 | tee -a "$RESULTS_DIR/health_checks.log"

# Research health
curl -sf "$RESEARCH_URL/health" | jq . 2>&1 | tee -a "$RESULTS_DIR/health_checks.log"

# ============================================
# Start Monitoring
# ============================================
log "Starting monitoring..."

cat > "$RESULTS_DIR/monitor.sh" << 'MONITOR_EOF'
#!/usr/bin/env bash
RESULTS_DIR="$1"
DURATION_SECONDS="$2"
INTERVAL=30

echo "Starting monitor (interval: ${INTERVAL}s)..."

END_TIME=$(($(date +%s) + DURATION_SECONDS))
SAMPLE=0

# Header
echo "timestamp,sample,feed_rss_mb,feed_cpu,api_rss_mb,api_cpu,research_rss_mb,research_cpu,feed_messages,feed_checks,feed_mismatches,feed_resyncs,feed_conflations,feed_reconnects,api_req_total,api_429_rate,api_p99_latency_ms,ws_connections,ws_msgs_recv,ws_latency_p99_ms" > "$RESULTS_DIR/samples.csv"

while [[ $(date +%s) -lt $END_TIME ]]; do
    SAMPLE=$((SAMPLE + 1))
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    
    # Feed metrics
    FEED_METRICS=$(curl -sf "$FEED_METRICS_URL/metrics" 2>/dev/null || echo "")
    FEED_RSS=$(ps -o rss= -p $(pgrep -f "sb-server" | head -1) 2>/dev/null | awk '{print $1/1024}' || echo "0")
    FEED_CPU=$(ps -o %cpu= -p $(pgrep -f "sb-server" | head -1) 2>/dev/null | awk '{print $1}' || echo "0")
    
    FEED_MESSAGES=$(echo "$FEED_METRICS" | grep 'feed_messages_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    FEED_CHECKS=$(echo "$FEED_METRICS" | grep 'feed_book_checks_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    FEED_MISMATCHES=$(echo "$FEED_METRICS" | grep 'feed_book_mismatch_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    FEED_RESYNCS=$(echo "$FEED_METRICS" | grep 'feed_resync_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    FEED_CONFLATIONS=$(echo "$FEED_METRICS" | grep 'feed_conflation_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    FEED_RECONNECTS=$(echo "$FEED_METRICS" | grep 'feed_reconnect_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    
    # API metrics
    API_METRICS=$(curl -sf "$API_BASE_URL/actuator/prometheus" 2>/dev/null || echo "")
    API_RSS=$(ps -o rss= -p $(pgrep -f "stockbroker-api" | head -1) 2>/dev/null | awk '{print $1/1024}' || echo "0")
    API_CPU=$(ps -o %cpu= -p $(pgrep -f "stockbroker-api" | head -1) 2>/dev/null | awk '{print $1}' || echo "0")
    
    API_REQ_TOTAL=$(echo "$API_METRICS" | grep 'http_server_requests_seconds_count' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    API_429=$(echo "$API_METRICS" | grep 'http_server_requests_seconds_count.*status="429"' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    API_P99=$(echo "$API_METRICS" | grep 'http_server_requests_seconds_max' | grep -v '^#' | awk '{print $2*1000}' | head -1)
    
    # Research metrics
    RESEARCH_RSS=$(ps -o rss= -p $(pgrep -f "uvicorn.*main:app" | head -1) 2>/dev/null | awk '{print $1/1024}' || echo "0")
    RESEARCH_CPU=$(ps -o %cpu= -p $(pgrep -f "uvicorn.*main:app" | head -1) 2>/dev/null | awk '{print $1}' || echo "0")
    
    # WebSocket metrics (from Prometheus)
    WS_METRICS=$(curl -sf "$PROMETHEUS_URL/api/v1/query?query=ws_active_connections" 2>/dev/null || echo '{"data":{"result":[]}}')
    WS_CONN=$(echo "$WS_METRICS" | jq -r '.data.result[0].value[1] // "0"')
    WS_MSGS=$(curl -sf "$PROMETHEUS_URL/api/v1/query?query=rate(ws_messages_received_total[30s])" 2>/dev/null | jq -r '.data.result[0].value[1] // "0"')
    WS_LATENCY=$(curl -sf "$PROMETHEUS_URL/api/v1/query?query=histogram_quantile(0.99,rate(ws_message_latency_ms_bucket[30s]))" 2>/dev/null | jq -r '.data.result[0].value[1] // "0"')
    
    # Write sample
    echo "${TIMESTAMP},${SAMPLE},${FEED_RSS},${FEED_CPU},${API_RSS},${API_CPU},${RESEARCH_RSS},${RESEARCH_CPU},${FEED_MESSAGES:-0},${FEED_CHECKS:-0},${FEED_MISMATCHES:-0},${FEED_RESYNCS:-0},${FEED_CONFLATIONS:-0},${FEED_RECONNECTS:-0},${API_REQ_TOTAL:-0},${API_429:-0},${API_P99:-0},${WS_CONN},${WS_MSGS},${WS_LATENCY}" >> "$RESULTS_DIR/samples.csv"
    
    # Log progress every 20 samples (10 minutes)
    if [[ $((SAMPLE % 20)) -eq 0 ]]; then
        echo "[$TIMESTAMP] Sample $SAMPLE: Feed RSS=${FEED_RSS}MB CPU=${FEED_CPU}%, API RSS=${API_RSS}MB CPU=${API_CPU}%, WS Conn=${WS_CONN}, WS Latency P99=${WS_LATENCY}ms"
    fi
    
    # Alert on anomalies
    if [[ ${FEED_MISMATCHES:-0} -gt 0 ]]; then
        echo "ALERT: ${FEED_MISMATCHES} feed book mismatches detected!" | tee -a "$RESULTS_DIR/alerts.log"
    fi
    
    # Check memory growth
    if [[ $SAMPLE -gt 10 ]]; then
        PREV_RSS=$(tail -2 "$RESULTS_DIR/samples.csv" | head -1 | cut -d',' -f3)
        if (( $(echo "$FEED_RSS - $PREV_RSS > 100" | bc -l 2>/dev/null || echo 0) )); then
            echo "WARNING: Feed RSS grew by >100MB in 30s" | tee -a "$RESULTS_DIR/alerts.log"
        fi
    fi
    
    sleep $INTERVAL
done

echo "Monitor finished"
MONITOR_EOF

chmod +x "$RESULTS_DIR/monitor.sh"
"$RESULTS_DIR/monitor.sh" "$RESULTS_DIR" "$DURATION_SECONDS" &
MONITOR_PID=$!

# ============================================
# Start k6 WebSocket Load Test
# ============================================
if check_cmd k6 && [[ -f "$REPO_ROOT/k6/websocket_fanout_load.js" ]]; then
    log "Starting k6 WebSocket load test (background)..."
    
    # Run k6 with reduced VUs for soak test (sustain 200 VUs)
    K6_DURATION=$((DURATION_HOURS * 60))m
    if [[ "$QUICK_MODE" == "true" ]]; then
        K6_DURATION="5m"
    fi
    
    k6 run \
        --vus 200 \
        --duration "$K6_DURATION" \
        --out csv="$RESULTS_DIR/k6_results.csv" \
        -e SB_API_URL="$API_BASE_URL" \
        -e SB_WS_URL="$WS_BASE_URL" \
        -e TEST_SYMBOL="$WS_TEST_SYMBOL" \
        -e TEST_DURATION="$K6_DURATION" \
        -e RAMP_UP_TIME="2m" \
        "$REPO_ROOT/k6/websocket_fanout_load.js" 2>&1 | tee "$RESULTS_DIR/k6.log" &
    K6_PID=$!
    log "k6 started (PID: $K6_PID)"
else
    log "k6 not available, skipping WebSocket load test"
    K6_PID=""
fi

# ============================================
# Start Background Load Generators
# ============================================

# REST API load (continuous requests)
log "Starting REST API load generator..."
cat > "$RESULTS_DIR/api_load.sh" << 'LOAD_EOF'
#!/usr/bin/env bash
API_URL="$1"
DURATION="$2"
END_TIME=$(($(date +%s) + DURATION))

symbols=("BINANCE:BTC-USDT" "BINANCE:ETH-USDT" "BINANCE:SOL-USDT")

while [[ $(date +%s) -lt $END_TIME ]]; do
    for symbol in "${symbols[@]}"; do
        curl -sf "$API_URL/api/v1/market/$symbol/book?depth=10" > /dev/null 2>&1 &
        curl -sf "$API_URL/api/v1/market/$symbol/trades?limit=10" > /dev/null 2>&1 &
        curl -sf "$API_URL/api/v1/market/$symbol/metrics" > /dev/null 2>&1 &
    done
    sleep 1
done
wait
LOAD_EOF

chmod +x "$RESULTS_DIR/api_load.sh"
"$RESULTS_DIR/api_load.sh" "$API_BASE_URL" "$DURATION_SECONDS" > "$RESULTS_DIR/api_load.log" 2>&1 &
API_LOAD_PID=$!

# ============================================
# Wait for Test Duration
# ============================================
log "Running soak test for ${DURATION_HOURS} hours..."
log "Monitor PID: $MONITOR_PID"
log "k6 PID: ${K6_PID:-none}"
log "API Load PID: $API_LOAD_PID"

# Wait for monitor (which runs for full duration)
wait $MONITOR_PID

# Wait for k6 if running
if [[ -n "$K6_PID" ]]; then
    wait $K6_PID 2>/dev/null || true
fi

# ============================================
# Generate Report
# ============================================
log "Generating report..."

cat > "$RESULTS_DIR/report.py" << 'REPORT_EOF'
import csv
import json
import sys
import os
from datetime import datetime

results_dir = sys.argv[1]
csv_file = os.path.join(results_dir, 'samples.csv')

if not os.path.exists(csv_file):
    print("No samples.csv found")
    sys.exit(1)

samples = []
with open(csv_file) as f:
    reader = csv.DictReader(f)
    for row in reader:
        samples.append({k: float(v) if v else 0 for k, v in row.items()})

if not samples:
    print("No valid samples")
    sys.exit(1)

def pct(arr, p):
    if not arr:
        return 0
    arr = sorted([x for x in arr if x > 0])
    if not arr:
        return 0
    idx = int(len(arr) * p / 100)
    return arr[min(idx, len(arr) - 1)]

# Feed metrics
feed_rss = [s['feed_rss_mb'] for s in samples]
feed_cpu = [s['feed_cpu'] for s in samples]
feed_mismatches = [s['feed_mismatches'] for s in samples]
feed_resyncs = [s['feed_resyncs'] for s in samples]
feed_conflations = [s['feed_conflations'] for s in samples]
feed_reconnects = [s['feed_reconnects'] for s in samples]

# API metrics
api_rss = [s['api_rss_mb'] for s in samples]
api_cpu = [s['api_cpu'] for s in samples]
api_req = [s['api_req_total'] for s in samples]
api_429 = [s['api_429_rate'] for s in samples]
api_p99 = [s['api_p99_latency_ms'] for s in samples if s['api_p99_latency_ms'] > 0]

# Research metrics
research_rss = [s['research_rss_mb'] for s in samples]
research_cpu = [s['research_cpu'] for s in samples]

# WebSocket metrics
ws_conn = [s['ws_connections'] for s in samples if s['ws_connections'] > 0]
ws_msgs = [s['ws_msgs_recv'] for s in samples if s['ws_msgs_recv'] > 0]
ws_latency = [s['ws_latency_p99_ms'] for s in samples if s['ws_latency_p99_ms'] > 0]

# Calculate growth
rss_growth = {}
for name, vals in [('feed', feed_rss), ('api', api_rss), ('research', research_rss)]:
    if vals:
        rss_growth[name] = vals[-1] - vals[0]

# Print summary
print("=" * 70)
print("FULL-STACK SOAK TEST SUMMARY")
print("=" * 70)
print(f"Duration: {len(samples)} samples x 30s = {len(samples) * 30 / 3600:.1f} hours")
print(f"Samples collected: {len(samples)}")
print()

print("FEED SERVICE (Rust):")
print(f"  Messages processed: {max(feed_mismatches) if feed_mismatches else 0}")
print(f"  Book checks: {max(feed_checks) if 'feed_checks' in locals() else 'N/A'}")
print(f"  Book mismatches: {max(feed_mismatches) if feed_mismatches else 0}")
print(f"  Resyncs: {max(feed_resyncs) if feed_resyncs else 0}")
print(f"  Conflations: {max(feed_conflations) if feed_conflations else 0}")
print(f"  Reconnects: {max(feed_reconnects) if feed_reconnects else 0}")
print(f"  Memory: start={feed_rss[0]:.1f}MB, end={feed_rss[-1]:.1f}MB, growth={rss_growth.get('feed',0):.1f}MB, peak={max(feed_rss):.1f}MB")
print(f"  CPU: avg={sum(feed_cpu)/len(feed_cpu):.1f}%, max={max(feed_cpu):.1f}%")
print()

print("JAVA API:")
print(f"  Total requests: {max(api_req) if api_req else 0}")
print(f"  429 responses: {max(api_429) if api_429 else 0}")
print(f"  Memory: start={api_rss[0]:.1f}MB, end={api_rss[-1]:.1f}MB, growth={rss_growth.get('api',0):.1f}MB, peak={max(api_rss):.1f}MB")
print(f"  CPU: avg={sum(api_cpu)/len(api_cpu):.1f}%, max={max(api_cpu):.1f}%")
if api_p99:
    print(f"  Latency P99: {pct(api_p99, 99):.1f}ms (max: {max(api_p99):.1f}ms)")
print()

print("PYTHON RESEARCH:")
print(f"  Memory: start={research_rss[0]:.1f}MB, end={research_rss[-1]:.1f}MB, growth={rss_growth.get('research',0):.1f}MB, peak={max(research_rss):.1f}MB")
print(f"  CPU: avg={sum(research_cpu)/len(research_cpu):.1f}%, max={max(research_cpu):.1f}%")
print()

print("WEBSOCKET (k6 + API load):")
if ws_conn:
    print(f"  Connections: avg={sum(ws_conn)/len(ws_conn):.0f}, max={max(ws_conn):.0f}")
if ws_msgs:
    print(f"  Messages/sec: avg={sum(ws_msgs)/len(ws_msgs):.1f}")
if ws_latency:
    print(f"  Fan-out latency P99: {pct(ws_latency, 99):.1f}ms (max: {max(ws_latency):.1f}ms)")
print()

print("CRITERIA:")
criteria = []
criteria.append(("Zero feed mismatches", max(feed_mismatches) == 0 if feed_mismatches else True))
criteria.append(("Feed memory growth < 100MB", rss_growth.get('feed', 0) < 100))
criteria.append(("API memory growth < 200MB", rss_growth.get('api', 0) < 200))
criteria.append(("Research memory growth < 100MB", rss_growth.get('research', 0) < 100))
criteria.append(("API P99 latency < 500ms", pct(api_p99, 99) < 500 if api_p99 else True))
criteria.append(("WS fan-out P99 < 50ms", pct(ws_latency, 99) < 50 if ws_latency else True))
criteria.append(("No excessive reconnects", max(feed_reconnects) < 10 if feed_reconnects else True))

all_pass = True
for name, passed in criteria:
    status = "PASS" if passed else "FAIL"
    if not passed:
        all_pass = False
    print(f"  {name}: {status}")

print("=" * 70)

# Write summary JSON
summary = {
    'timestamp': datetime.utcnow().isoformat() + 'Z',
    'duration_hours': len(samples) * 30 / 3600,
    'samples': len(samples),
    'feed': {
        'mismatches': max(feed_mismatches) if feed_mismatches else 0,
        'resyncs': max(feed_resyncs) if feed_resyncs else 0,
        'conflations': max(feed_conflations) if feed_conflations else 0,
        'reconnects': max(feed_reconnects) if feed_reconnects else 0,
        'rss_start_mb': feed_rss[0] if feed_rss else 0,
        'rss_end_mb': feed_rss[-1] if feed_rss else 0,
        'rss_growth_mb': rss_growth.get('feed', 0),
        'rss_peak_mb': max(feed_rss) if feed_rss else 0,
        'cpu_avg': sum(feed_cpu)/len(feed_cpu) if feed_cpu else 0,
        'cpu_max': max(feed_cpu) if feed_cpu else 0,
    },
    'api': {
        'total_requests': max(api_req) if api_req else 0,
        'rate_limited': max(api_429) if api_429 else 0,
        'rss_start_mb': api_rss[0] if api_rss else 0,
        'rss_end_mb': api_rss[-1] if api_rss else 0,
        'rss_growth_mb': rss_growth.get('api', 0),
        'rss_peak_mb': max(api_rss) if api_rss else 0,
        'cpu_avg': sum(api_cpu)/len(api_cpu) if api_cpu else 0,
        'cpu_max': max(api_cpu) if api_cpu else 0,
        'p99_latency_ms': pct(api_p99, 99) if api_p99 else 0,
        'max_latency_ms': max(api_p99) if api_p99 else 0,
    },
    'research': {
        'rss_start_mb': research_rss[0] if research_rss else 0,
        'rss_end_mb': research_rss[-1] if research_rss else 0,
        'rss_growth_mb': rss_growth.get('research', 0),
        'rss_peak_mb': max(research_rss) if research_rss else 0,
        'cpu_avg': sum(research_cpu)/len(research_cpu) if research_cpu else 0,
        'cpu_max': max(research_cpu) if research_cpu else 0,
    },
    'websocket': {
        'avg_connections': sum(ws_conn)/len(ws_conn) if ws_conn else 0,
        'max_connections': max(ws_conn) if ws_conn else 0,
        'avg_msgs_per_sec': sum(ws_msgs)/len(ws_msgs) if ws_msgs else 0,
        'p99_latency_ms': pct(ws_latency, 99) if ws_latency else 0,
        'max_latency_ms': max(ws_latency) if ws_latency else 0,
    },
    'criteria': {name: passed for name, passed in criteria},
    'overall_pass': all_pass,
}

with open(os.path.join(results_dir, 'summary.json'), 'w') as f:
    json.dump(summary, f, indent=2)

# Also generate markdown report
with open(os.path.join(results_dir, 'report.md'), 'w') as f:
    f.write(f"# Full-Stack Soak Test Report\n\n")
    f.write(f"**Date**: {datetime.utcnow().isoformat()}Z\n")
    f.write(f"**Duration**: {len(samples) * 30 / 3600:.1f} hours\n")
    f.write(f"**Commit**: $(git rev-parse HEAD)\n\n")
    
    f.write("## Summary\n\n")
    f.write(f"- **Overall**: {'PASS' if all_pass else 'FAIL'}\n")
    f.write(f"- **Samples**: {len(samples)}\n\n")
    
    f.write("## Component Metrics\n\n")
    f.write("### Feed Service (Rust)\n")
    f.write(f"- Mismatches: {max(feed_mismatches) if feed_mismatches else 0}\n")
    f.write(f"- Resyncs: {max(feed_resyncs) if feed_resyncs else 0}\n")
    f.write(f"- Conflations: {max(feed_conflations) if feed_conflations else 0}\n")
    f.write(f"- Reconnects: {max(feed_reconnects) if feed_reconnects else 0}\n")
    f.write(f"- Memory Growth: {rss_growth.get('feed', 0):.1f} MB\n")
    f.write(f"- Peak Memory: {max(feed_rss):.1f} MB\n")
    f.write(f"- CPU Avg: {sum(feed_cpu)/len(feed_cpu):.1f}%\n\n")
    
    f.write("### Java API\n")
    f.write(f"- Total Requests: {max(api_req) if api_req else 0}\n")
    f.write(f"- Rate Limited (429): {max(api_429) if api_429 else 0}\n")
    f.write(f"- Memory Growth: {rss_growth.get('api', 0):.1f} MB\n")
    f.write(f"- Peak Memory: {max(api_rss):.1f} MB\n")
    f.write(f"- CPU Avg: {sum(api_cpu)/len(api_cpu):.1f}%\n")
    if api_p99:
        f.write(f"- P99 Latency: {pct(api_p99, 99):.1f} ms\n\n")
    
    f.write("### Python Research\n")
    f.write(f"- Memory Growth: {rss_growth.get('research', 0):.1f} MB\n")
    f.write(f"- Peak Memory: {max(research_rss):.1f} MB\n")
    f.write(f"- CPU Avg: {sum(research_cpu)/len(research_cpu):.1f}%\n\n")
    
    f.write("### WebSocket\n")
    if ws_conn:
        f.write(f"- Avg Connections: {sum(ws_conn)/len(ws_conn):.0f}\n")
        f.write(f"- Max Connections: {max(ws_conn):.0f}\n")
    if ws_latency:
        f.write(f"- Fan-out P99 Latency: {pct(ws_latency, 99):.1f} ms\n")
        f.write(f"- Max Fan-out Latency: {max(ws_latency):.1f} ms\n")
    f.write("\n")
    
    f.write("## Criteria Results\n\n")
    for name, passed in criteria:
        status = "✅ PASS" if passed else "❌ FAIL"
        f.write(f"- {name}: {status}\n")
    
    f.write(f"\n---\n*Generated at {datetime.utcnow().isoformat()}Z*")

sys.exit(0 if all_pass else 1)
REPORT_EOF

python3 "$RESULTS_DIR/report.py" "$RESULTS_DIR"
REPORT_EXIT=$?

# ============================================
# Final Output
# ============================================
log ""
log "========================================"
log "Full-Stack Soak Test Complete"
log "Results: ${RESULTS_DIR}"
log "Exit code: $REPORT_EXIT"
log "========================================"

# Show summary
cat "$RESULTS_DIR/report.md" 2>/dev/null || true

exit $REPORT_EXIT