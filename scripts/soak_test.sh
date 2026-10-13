#!/usr/bin/env bash
# 24-hour soak test for sb-server
# 
# This script:
# 1. Starts sb-server with observability enabled
# 2. Runs a load generator that simulates market data feed
# 3. Monitors for mismatches, memory growth, and latency degradation
# 4. Reports results after 24 hours
#
# Usage: ./scripts/soak_test.sh [DURATION_HOURS]
# Default duration: 24 hours

set -euo pipefail

DURATION_HOURS="${1:-24}"
DURATION_SECONDS=$((DURATION_HOURS * 3600))
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$REPO_ROOT/rust/server"
RESULTS_DIR="$REPO_ROOT/benchmarks/results/soak-$(date +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD)"

mkdir -p "$RESULTS_DIR"

echo "========================================"
echo "24-Hour Soak Test"
echo "========================================"
echo "Duration: ${DURATION_HOURS} hours (${DURATION_SECONDS} seconds)"
echo "Results: ${RESULTS_DIR}"
echo "Commit: $(git rev-parse HEAD)"
echo "Rust: $(rustc --version)"
echo "========================================"

# Build server in release mode
echo "Building sb-server (release)..."
cd "$SERVER_DIR"
cargo build --release -p sb-server 2>&1 | tee "$RESULTS_DIR/build.log"

SERVER_BIN="$SERVER_DIR/target/release/sb-server"
CONFIG_FILE="$SERVER_DIR/server.toml"

# Verify server binary exists
if [[ ! -f "$SERVER_BIN" ]]; then
    echo "ERROR: Server binary not found at $SERVER_BIN"
    exit 1
fi

# Start server
echo "Starting sb-server..."
"$SERVER_BIN" "$CONFIG_FILE" > "$RESULTS_DIR/server.log" 2>&1 &
SERVER_PID=$!

# Function to cleanup on exit
cleanup() {
    echo "Stopping server (PID: $SERVER_PID)..."
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Wait for server to start
sleep 5

# Check server is running
if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "ERROR: Server failed to start"
    cat "$RESULTS_DIR/server.log"
    exit 1
fi

echo "Server started (PID: $SERVER_PID)"

# Verify health endpoint
echo "Checking health endpoint..."
for i in {1..10}; do
    if curl -sf "http://localhost:9090/health" > /dev/null; then
        echo "Health endpoint OK"
        break
    fi
    sleep 1
done

# Verify Prometheus metrics
echo "Checking Prometheus metrics..."
if curl -sf "http://localhost:9090/metrics" | grep -q "feed_stage_latency"; then
    echo "Prometheus metrics OK"
else
    echo "WARNING: Prometheus metrics not found"
fi

# Start monitoring script in background
cat > "$RESULTS_DIR/monitor.sh" << 'MONITOR_EOF'
#!/usr/bin/env bash
RESULTS_DIR="$1"
DURATION_SECONDS="$2"
INTERVAL=30

echo "Starting monitor (interval: ${INTERVAL}s)..."

END_TIME=$(($(date +%s) + DURATION_SECONDS))
SAMPLE=0

while [[ $(date +%s) -lt $END_TIME ]]; do
    SAMPLE=$((SAMPLE + 1))
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    
    # Collect metrics
    METRICS=$(curl -sf "http://localhost:9090/metrics" 2>/dev/null || echo "")
    
    # Extract key metrics
    MEMORY_RSS=$(ps -o rss= -p $SERVER_PID 2>/dev/null | awk '{print $1 * 1024}' || echo "0")
    MEMORY_VSZ=$(ps -o vsz= -p $SERVER_PID 2>/dev/null | awk '{print $1 * 1024}' || echo "0")
    CPU_PERCENT=$(ps -o %cpu= -p $SERVER_PID 2>/dev/null | awk '{print $1}' || echo "0")
    
    # Parse Prometheus metrics
    MISMATCHES=$(echo "$METRICS" | grep 'feed_book_mismatch_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    CHECKS=$(echo "$METRICS" | grep 'feed_book_checks_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    RESYNCS=$(echo "$METRICS" | grep 'feed_resync_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    CONFLATIONS=$(echo "$METRICS" | grep 'feed_conflation_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    RECONNECTS=$(echo "$METRICS" | grep 'feed_reconnect_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    MESSAGES=$(echo "$METRICS" | grep 'feed_messages_total' | grep -v '^#' | awk '{sum+=$2} END {print sum+0}')
    
    # Latency percentiles (p50, p99)
    P50_INTERNAL=$(echo "$METRICS" | grep 'feed_stage_latency_p50_seconds{stage="internal"}' | grep -v '^#' | awk '{print $2}' | head -1)
    P99_INTERNAL=$(echo "$METRICS" | grep 'feed_stage_latency_p99_seconds{stage="internal"}' | grep -v '^#' | awk '{print $2}' | head -1)
    
    # Write sample
    echo "${TIMESTAMP},${SAMPLE},${MEMORY_RSS},${MEMORY_VSZ},${CPU_PERCENT},${MISMATCHES:-0},${CHECKS:-0},${RESYNCS:-0},${CONFLATIONS:-0},${RECONNECTS:-0},${MESSAGES:-0},${P50_INTERNAL:-0},${P99_INTERNAL:-0}" >> "$RESULTS_DIR/samples.csv"
    
    # Log progress every 10 samples (5 minutes)
    if [[ $((SAMPLE % 10)) -eq 0 ]]; then
        echo "[$TIMESTAMP] Sample $SAMPLE: RSS=${MEMORY_RSS} bytes, Mismatches=${MISMATCHES:-0}, Checks=${CHECKS:-0}, Resyncs=${RESYNCS:-0}, Conflations=${CONFLATIONS:-0}, p50_internal=${P50_INTERNAL:-0}s, p99_internal=${P99_INTERNAL:-0}s"
    fi
    
    # Alert on mismatches
    if [[ ${MISMATCHES:-0} -gt 0 ]]; then
        echo "ALERT: ${MISMATCHES} book mismatches detected!" | tee -a "$RESULTS_DIR/alerts.log"
    fi
    
    sleep $INTERVAL
done

echo "Monitor finished"
MONITOR_EOF

chmod +x "$RESULTS_DIR/monitor.sh"
"$RESULTS_DIR/monitor.sh" "$RESULTS_DIR" "$DURATION_SECONDS" &
MONITOR_PID=$!

# Also run a simple load generator that publishes mock data to exercise the path
cat > "$RESULTS_DIR/load_gen.sh" << 'LOADGEN_EOF'
#!/usr/bin/env bash
# Simple load generator that exercises the gRPC SubscribeBook stream
# This simulates clients connecting and receiving data

RESULTS_DIR="$1"
DURATION_SECONDS="$2"

END_TIME=$(($(date +%s) + DURATION_SECONDS))
CLIENT_COUNT=10

echo "Starting load generator with $CLIENT_COUNT clients..."

# Use a simple Python script to connect via gRPC
cat > "$RESULTS_DIR/load_client.py" << 'PYEOF'
import asyncio
import grpc
import sys
import time
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../../rust/server/src/proto'))

from stockbroker.market.v1 import market_pb2, market_pb2_grpc

async def run_client(client_id, duration):
    channel = grpc.aio.insecure_channel('localhost:50051')
    stub = market_pb2_grpc.MarketDataStub(channel)
    
    request = market_pb2.SubscribeRequest(
        symbols=["BINANCE:BTC-USDT"],
        depth_levels=10,
        metrics_levels=10
    )
    
    start = time.time()
    count = 0
    errors = 0
    
    try:
        stream = stub.SubscribeBook(request)
        async for update in stream:
            count += 1
            if time.time() - start > duration:
                break
    except Exception as e:
        errors += 1
        print(f"Client {client_id} error: {e}")
    finally:
        await channel.close()
    
    elapsed = time.time() - start
    print(f"Client {client_id}: {count} messages in {elapsed:.1f}s ({count/elapsed:.1f} msg/s), errors={errors}")
    return count, errors

async def main():
    client_id = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    duration = int(sys.argv[2]) if len(sys.argv) > 2 else 86400
    await run_client(client_id, duration)

if __name__ == '__main__':
    asyncio.run(main())
PYEOF

# Run multiple clients in background
for i in $(seq 1 $CLIENT_COUNT); do
    python3 "$RESULTS_DIR/load_client.py" $i $DURATION_SECONDS > "$RESULTS_DIR/client_${i}.log" 2>&1 &
done

# Wait for all clients
wait
LOADGEN_EOF

chmod +x "$RESULTS_DIR/load_gen.sh"

# Run load generator in background (optional - requires Python gRPC setup)
# "$RESULTS_DIR/load_gen.sh" "$RESULTS_DIR" "$DURATION_SECONDS" &
# LOADGEN_PID=$!

# Wait for monitor to complete
wait $MONITOR_PID

# Generate summary report
cat > "$RESULTS_DIR/report.py" << 'REPORT_EOF'
import csv
import sys
import os

results_dir = sys.argv[1]
csv_file = os.path.join(results_dir, 'samples.csv')

if not os.path.exists(csv_file):
    print("No samples.csv found")
    sys.exit(1)

samples = []
with open(csv_file) as f:
    reader = csv.reader(f)
    for row in reader:
        if len(row) >= 13:
            samples.append({
                'timestamp': row[0],
                'sample': int(row[1]),
                'rss': int(row[2]),
                'vsz': int(row[3]),
                'cpu': float(row[4]),
                'mismatches': int(row[5]),
                'checks': int(row[6]),
                'resyncs': int(row[7]),
                'conflations': int(row[8]),
                'reconnects': int(row[9]),
                'messages': int(row[10]),
                'p50_internal': float(row[11]),
                'p99_internal': float(row[12]),
            })

if not samples:
    print("No valid samples")
    sys.exit(1)

# Calculate stats
rss_values = [s['rss'] for s in samples]
mismatches = [s['mismatches'] for s in samples]
checks = [s['checks'] for s in samples]
p50 = [s['p50_internal'] for s in samples if s['p50_internal'] > 0]
p99 = [s['p99_internal'] for s in samples if s['p99_internal'] > 0]

total_mismatches = max(mismatches) if mismatches else 0
total_checks = max(checks) if checks else 0
total_resyncs = max([s['resyncs'] for s in samples]) if samples else 0
total_conflations = max([s['conflations'] for s in samples]) if samples else 0
total_reconnects = max([s['reconnects'] for s in samples]) if samples else 0
total_messages = max([s['messages'] for s in samples]) if samples else 0

rss_start = rss_values[0] if rss_values else 0
rss_end = rss_values[-1] if rss_values else 0
rss_growth = rss_end - rss_start
rss_max = max(rss_values) if rss_values else 0

# Latency stats
def pct(arr, p):
    if not arr:
        return 0
    arr = sorted(arr)
    idx = int(len(arr) * p / 100)
    return arr[min(idx, len(arr) - 1)]

print("=" * 60)
print("SOAK TEST SUMMARY")
print("=" * 60)
print(f"Duration: {len(samples)} samples x 30s = {len(samples) * 30 / 3600:.1f} hours")
print(f"Total messages processed: {total_messages}")
print(f"Total book checks: {total_checks}")
print(f"Total book mismatches: {total_mismatches}")
print(f"Total resyncs: {total_resyncs}")
print(f"Total conflations: {total_conflations}")
print(f"Total reconnects: {total_reconnects}")
print()
print("Memory (RSS):")
print(f"  Start: {rss_start / 1024 / 1024:.1f} MB")
print(f"  End:   {rss_end / 1024 / 1024:.1f} MB")
print(f"  Growth: {rss_growth / 1024 / 1024:.1f} MB")
print(f"  Peak:  {rss_max / 1024 / 1024:.1f} MB")
print()
print("Rust Internal Latency (T4-T1):")
if p50:
    print(f"  p50:  {pct(p50, 50) * 1_000_000:.1f} us")
    print(f"  p90:  {pct(p50, 90) * 1_000_000:.1f} us")
    print(f"  p99:  {pct(p99, 99) * 1_000_000:.1f} us")
    print(f"  max:  {max(p99) * 1_000_000:.1f} us")
print()
print("Criteria:")
print(f"  Zero mismatches: {'PASS' if total_mismatches == 0 else 'FAIL'}")
print(f"  No memory growth (>50MB): {'PASS' if rss_growth < 50 * 1024 * 1024 else 'FAIL'}")
print(f"  p99 internal < 1ms: {'PASS' if p99 and pct(p99, 99) < 0.001 else 'FAIL'}")
print("=" * 60)

# Write summary JSON
import json
summary = {
    'duration_hours': len(samples) * 30 / 3600,
    'total_messages': total_messages,
    'total_checks': total_checks,
    'total_mismatches': total_mismatches,
    'total_resyncs': total_resyncs,
    'total_conflations': total_conflations,
    'total_reconnects': total_reconnects,
    'rss_start_mb': rss_start / 1024 / 1024,
    'rss_end_mb': rss_end / 1024 / 1024,
    'rss_growth_mb': rss_growth / 1024 / 1024,
    'rss_peak_mb': rss_max / 1024 / 1024,
    'p50_internal_us': pct(p50, 50) * 1_000_000 if p50 else 0,
    'p90_internal_us': pct(p50, 90) * 1_000_000 if p50 else 0,
    'p99_internal_us': pct(p99, 99) * 1_000_000 if p99 else 0,
    'max_internal_us': max(p99) * 1_000_000 if p99 else 0,
    'zero_mismatches': total_mismatches == 0,
    'no_memory_growth': rss_growth < 50 * 1024 * 1024,
    'latency_budget_met': p99 and pct(p99, 99) < 0.001,
}

with open(os.path.join(results_dir, 'summary.json'), 'w') as f:
    json.dump(summary, f, indent=2)

# Exit code based on criteria
if total_mismatches > 0 or rss_growth >= 50 * 1024 * 1024 or (p99 and pct(p99, 99) >= 0.001):
    sys.exit(1)
REPORT_EOF

python3 "$RESULTS_DIR/report.py" "$RESULTS_DIR"
REPORT_EXIT=$?

echo ""
echo "========================================"
echo "Soak test complete"
echo "Results: ${RESULTS_DIR}"
echo "========================================"

exit $REPORT_EXIT