import assert from 'node:assert/strict';
import test from 'node:test';
import { summarizeHealth } from './health';

test('reports an empty but connected feed as healthy', () => {
  const summary = summarizeHealth({ connected: true, symbols: [], buildVersion: '0.1.0' });

  assert.equal(summary.healthy, true);
  assert.deepEqual(summary.checks, [{
    component: 'Market feed connection', status: 'healthy', detail: 'Connected',
  }]);
});

test('reports an invalid book without inventing component metrics', () => {
  const summary = summarizeHealth({
    connected: true,
    buildVersion: '0.1.0',
    symbols: [{ symbol: 'BINANCE:BTC-USDT', book_valid: false, epoch: 7, resync_count: 2, last_event_age_ms: 20 }],
  });

  assert.equal(summary.healthy, false);
  assert.deepEqual(summary.checks[1], {
    component: 'BINANCE:BTC-USDT', status: 'unhealthy', detail: 'Book invalid; 2 resyncs recorded',
  });
});
