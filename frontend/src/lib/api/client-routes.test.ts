import assert from 'node:assert/strict';
import test from 'node:test';
import { authClient } from '@/lib/auth';
import { marketApi, portfolioApi, tradingApi, wsApi } from './client';

async function captureRequest(run: () => Promise<unknown>): Promise<string> {
  const original = authClient.requestWithAuth;
  let endpoint = '';
  authClient.requestWithAuth = (async (path: string) => {
    endpoint = path;
    return {};
  }) as typeof authClient.requestWithAuth;

  try {
    await run();
    return endpoint;
  } finally {
    authClient.requestWithAuth = original;
  }
}

test('uses the documented snapshot route', async () => {
  assert.equal(await captureRequest(() => marketApi.getSnapshot('BINANCE:BTC-USDT', 10)),
    '/market/BINANCE%3ABTC-USDT/snapshot?depth=10');
});

test('uses the documented top-level ledger route', async () => {
  assert.equal(await captureRequest(() => portfolioApi.getLedger({ asset: 'USDT', limit: 25 })),
    '/ledger?asset=USDT&limit=25');
});

test('uses the authenticated WebSocket ticket route', async () => {
  assert.equal(await captureRequest(() => wsApi.getTicket()), '/auth/ws/ticket');
});

async function captureRequestBody(run: () => Promise<unknown>): Promise<unknown> {
  const original = authClient.requestWithAuth;
  let body: unknown;
  authClient.requestWithAuth = (async (_path: string, options?: RequestInit) => {
    body = JSON.parse(String(options?.body));
    return {};
  }) as typeof authClient.requestWithAuth;

  try {
    await run();
    return body;
  } finally {
    authClient.requestWithAuth = original;
  }
}

test('serializes the create-order request with the Java DTO field names', async () => {
  assert.deepEqual(await captureRequestBody(() => tradingApi.createOrder({
    symbol: 'BINANCE:BTC-USDT', side: 'BUY', type: 'LIMIT', qty: '0.01', limitPrice: '50000', tif: 'GTC',
  }, 'order-1')), {
    symbol: 'BINANCE:BTC-USDT', side: 'BUY', type: 'LIMIT', qty: '0.01', limitPrice: '50000', tif: 'GTC',
  });
});

test('serializes preview with only fields accepted by the Java preview DTO', async () => {
  assert.deepEqual(await captureRequestBody(() => tradingApi.previewOrder({
    symbol: 'BINANCE:BTC-USDT', side: 'BUY', qty: '0.01', limitPrice: '50000',
  })), {
    symbol: 'BINANCE:BTC-USDT', side: 'BUY', qty: '0.01', limitPrice: '50000',
  });
});
