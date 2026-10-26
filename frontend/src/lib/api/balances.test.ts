import assert from 'node:assert/strict';
import test from 'node:test';
import { parseBalancesResponse } from './balances';

test('accepts an empty balance list for a new account', () => {
  assert.deepEqual(parseBalancesResponse([]), []);
});

test('preserves ledger totals as decimal strings', () => {
  const response = [{ asset: 'USDT', total: '100.2500' }];

  assert.deepEqual(parseBalancesResponse(response), response);
});

test('rejects unsupported available and locked fields', () => {
  assert.throws(() => parseBalancesResponse([
    { asset: 'USDT', available: '10', locked: '0', total: '10' },
  ]));
});
