/**
 * k6 WebSocket Load Test - 1,000 Simulated Users
 * 
 * Tests WebSocket fan-out latency with realistic market data patterns.
 * Measures: connection time, message latency (server timestamp -> client receipt),
 * fan-out overhead, connection success rate, and resource utilization.
 * 
 * Usage:
 *   k6 run --vus 1000 --duration 10m k6/websocket_fanout_load.js
 *   k6 run --vus 500 --duration 5m --out influxdb=http://localhost:8086/k6 k6/websocket_fanout_load.js
 * 
 * Prerequisites:
 * - Java API running on localhost:8081 (or SB_API_URL)
 * - Rust feed server running on localhost:9000 (or SB_FEED_URL)
 * - WebSocket ticket endpoint: /api/v1/ws/ticket
 * - WebSocket endpoint: /ws/v1?ticket=<ticket>
 * - Market data for BINANCE:BTC-USDT being published
 */

import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ============================================
// Configuration
// ============================================
const API_BASE_URL = __ENV.SB_API_URL || 'http://localhost:8081';
const WS_BASE_URL = __ENV.SB_WS_URL || 'ws://localhost:8081';
const TEST_SYMBOL = __ENV.TEST_SYMBOL || 'BINANCE:BTC-USDT';
const TEST_DURATION = __ENV.TEST_DURATION || '10m';
const RAMP_UP_TIME = __ENV.RAMP_UP_TIME || '2m';

// Custom metrics
const wsConnections = new Counter('ws_connections_total');
const wsConnectionFailures = new Counter('ws_connection_failures_total');
const wsMessagesReceived = new Counter('ws_messages_received_total');
const wsMessageLatency = new Trend('ws_message_latency_ms'); // fan-out latency
const wsConnectionTime = new Trend('ws_connection_time_ms');
const wsSubscriptionTime = new Trend('ws_subscription_time_ms');
const wsActiveConnections = new Gauge('ws_active_connections');
const wsErrors = new Rate('ws_error_rate');
const wsReconnects = new Counter('ws_reconnects_total');
const wsConflationDrops = new Counter('ws_conflation_drops_total');

// Track connected VUs for concurrency gauge
let activeVUs = 0;

// Test stages: ramp up, sustain, ramp down
export const options = {
  scenarios: {
    websocket_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP_TIME, target: 1000 },  // Ramp up to 1000 VUs
        { duration: '5m', target: 1000 },           // Sustain for 5 minutes
        { duration: '2m', target: 0 },              // Ramp down
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    // Success criteria
    'ws_connection_failures_total': ['count<50'],           // <5% connection failure rate
    'ws_message_latency_ms': ['p(99)<50', 'p(95)<20'],      // Fan-out latency budgets
    'ws_connection_time_ms': ['p(99)<2000', 'p(95)<500'],   // Connection establishment
    'ws_subscription_time_ms': ['p(99)<500', 'p(95)<200'],  // Subscribe ack
    'ws_error_rate': ['rate<0.01'],                          // <1% error rate
  },
  // Export for CI/CD
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
};

// ============================================
// Helper Functions
// ============================================

// Get auth token for ticket request
function getAuthToken() {
  const loginRes = http.post(`${API_BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: `loadtest-${__VU}@example.com`,
    password: 'LoadTest123!',
  }), {
    headers: { 'Content-Type': 'application/json' },
  });
  
  check(loginRes, { 'login successful': (r) => r.status === 200 });
  return loginRes.json('access_token');
}

// Get WebSocket ticket
function getWsTicket(token) {
  const ticketRes = http.post(`${API_BASE_URL}/api/v1/ws/ticket`, '', {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  
  check(ticketRes, { 'ticket obtained': (r) => r.status === 200 });
  return ticketRes.json('ticket');
}

// Measure round-trip latency for a message
function measureLatency(sentTime, receivedTime) {
  return receivedTime - sentTime;
}

// ============================================
// Main VU Function
// ============================================

export default function () {
  // Track active VUs
  activeVUs++;
  wsActiveConnections.add(activeVUs);
  
  const connStartTime = new Date().getTime();
  
  // 1. Get auth token
  const token = getAuthToken();
  if (!token) {
    wsConnectionFailures.add(1);
    wsErrors.add(1);
    activeVUs--;
    wsActiveConnections.add(activeVUs);
    return;
  }
  
  // 2. Get WebSocket ticket
  const ticket = getWsTicket(token);
  if (!ticket) {
    wsConnectionFailures.add(1);
    wsErrors.add(1);
    activeVUs--;
    wsActiveConnections.add(activeVUs);
    return;
  }
  
  // 3. Connect to WebSocket with timing
  const wsUrl = `${WS_BASE_URL}/ws/v1?ticket=${ticket}`;
  
  ws.connect(wsUrl, {}, function (socket) {
    const connectedTime = new Date().getTime();
    wsConnectionTime.add(connectedTime - connStartTime);
    wsConnections.add(1);
    
    // Track connection lifecycle
    socket.on('open', () => {
      // 4. Subscribe to book channel
      const subStartTime = new Date().getTime();
      socket.send(JSON.stringify({
        op: 'subscribe',
        channel: 'book',
        symbol: TEST_SYMBOL,
        depth: 20,
      }));
      
      socket.on('message', (message) => {
        const recvTime = new Date().getTime();
        wsMessagesReceived.add(1);
        
        try {
          const msg = JSON.parse(message);
          
          // Measure subscription acknowledgment latency
          if (msg.t === 'book.snap' && subStartTime > 0) {
            wsSubscriptionTime.add(recvTime - subStartTime);
            subStartTime = 0; // Only measure first snapshot
          }
          
          // Measure fan-out latency: server timestamp -> client receipt
          if (msg.tm && msg.tm.gateway_send_ts_ns) {
            const serverSendNs = parseInt(msg.tm.gateway_send_ts_ns);
            const serverSendMs = serverSendNs / 1_000_000;
            const latencyMs = recvTime - serverSendMs;
            if (latencyMs > 0 && latencyMs < 10000) { // Sanity check
              wsMessageLatency.add(latencyMs);
            }
          }
          
          // Track conflation events
          if (msg.t === 'error' && msg.d && msg.d.code === 'CONFLATION') {
            wsConflationDrops.add(1);
          }
          
        } catch (e) {
          // Ignore parse errors
        }
      });
      
      socket.on('close', () => {
        activeVUs--;
        wsActiveConnections.add(activeVUs);
      });
      
      socket.on('error', (e) => {
        wsErrors.add(1);
      });
      
      // Keep connection alive with periodic pings
      socket.setInterval(() => {
        if (socket.readyState === 1) { // OPEN
          const pingId = `ping-${Date.now()}`;
          const pingStart = new Date().getTime();
          socket.send(JSON.stringify({
            op: 'ping',
            id: pingId,
          }));
          
          // Could track ping latency here if needed
        }
      }, 10000); // Ping every 10 seconds
      
      // Keep VU alive for test duration
      socket.setTimeout(() => {
        socket.close();
      }, parseDuration(TEST_DURATION) * 1000);
    });
    
    socket.on('error', (e) => {
      wsConnectionFailures.add(1);
      wsErrors.add(1);
      activeVUs--;
      wsActiveConnections.add(activeVUs);
    });
    
    socket.on('close', () => {
      activeVUs--;
      wsActiveConnections.add(activeVUs);
    });
  });
  
  // Small sleep to prevent overwhelming the event loop
  sleep(0.1);
}

// ============================================
// Helper: Parse duration string (e.g., "10m" -> 600)
// ============================================
function parseDuration(str) {
  const match = str.match(/^(\d+)([smh])$/);
  if (!match) return 600; // default 10m
  const value = parseInt(match[1]);
  const unit = match[2];
  switch (unit) {
    case 's': return value;
    case 'm': return value * 60;
    case 'h': return value * 3600;
    default: return 600;
  }
}

// ============================================
// HTTP client for auth/ticket (k6 doesn't have built-in http in ws module)
// ============================================
import http from 'k6/http';

// Note: k6 WS module doesn't include HTTP, need to import separately
// This is handled by the http import above