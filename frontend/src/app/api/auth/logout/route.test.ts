import assert from 'node:assert/strict';
import test from 'node:test';
import { NextRequest } from 'next/server';
import { POST } from './route';

test('returns an empty 204 response and clears the refresh cookie after successful logout', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(null, { status: 204 });

  try {
    const response = await POST(new NextRequest('http://localhost/api/auth/logout', { method: 'POST' }));

    assert.equal(response.status, 204);
    assert.equal(await response.text(), '');
    assert.match(response.headers.get('set-cookie') ?? '', /refresh_token=;/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
