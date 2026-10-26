import assert from 'node:assert/strict';
import test from 'node:test';
import { NextRequest } from 'next/server';
import { POST } from './route';

const upstreamLoginResponse = {
  access_token: 'test-access-token',
  expires_in: 900,
  refresh_token: 'test-refresh-token',
};

async function login(request: NextRequest) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json(upstreamLoginResponse);

  try {
    return await POST(request);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

test('does not mark the refresh cookie Secure for a local HTTP request', async () => {
  const response = await login(new NextRequest('http://localhost/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: 'audit@example.invalid', password: 'AuditPass123!' }),
    headers: { 'content-type': 'application/json' },
  }));

  assert.equal(response.status, 200);
  assert.doesNotMatch(response.headers.get('set-cookie') ?? '', /; Secure(?:;|$)/);
});

test('marks the refresh cookie Secure when HTTPS was terminated by a proxy', async () => {
  const response = await login(new NextRequest('http://localhost/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: 'audit@example.invalid', password: 'AuditPass123!' }),
    headers: {
      'content-type': 'application/json',
      'x-forwarded-proto': 'https',
    },
  }));

  assert.equal(response.status, 200);
  assert.match(response.headers.get('set-cookie') ?? '', /; Secure(?:;|$)/);
});
