import type { NextRequest } from 'next/server';

/**
 * Determines whether the response reaches the browser over HTTPS.
 *
 * A local `next start` server is intentionally reachable over HTTP even when
 * Node runs in production mode. Conversely, TLS is commonly terminated at a
 * reverse proxy, which forwards the original scheme in X-Forwarded-Proto.
 */
export function shouldUseSecureRefreshCookie(request: NextRequest): boolean {
  if (request.nextUrl.protocol === 'https:') {
    return true;
  }

  const forwardedProto = request.headers.get('x-forwarded-proto');
  return forwardedProto?.split(',', 1)[0]?.trim().toLowerCase() === 'https';
}
