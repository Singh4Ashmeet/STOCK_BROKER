import { NextResponse } from 'next/server';

const UNAVAILABLE_NETWORK_CODES = new Set([
  'ECONNREFUSED',
  'ECONNRESET',
  'EHOSTUNREACH',
  'ENETUNREACH',
  'ENOTFOUND',
  'ETIMEDOUT',
]);

/**
 * Returns whether a failed fetch was caused by an unavailable upstream service.
 *
 * Node wraps some connection failures in an AggregateError, with the socket
 * errors stored in `cause.errors`, so checking only `error.cause.code` turns a
 * transient upstream outage into an incorrect 500 response.
 */
export function isUpstreamUnavailable(error: unknown): boolean {
  const visited = new Set<object>();
  const pending: unknown[] = [error];

  while (pending.length > 0) {
    const candidate = pending.pop();
    if (candidate === null || typeof candidate !== 'object' || visited.has(candidate)) {
      continue;
    }
    visited.add(candidate);

    const record = candidate as Record<string, unknown>;
    if (typeof record.code === 'string' && UNAVAILABLE_NETWORK_CODES.has(record.code)) {
      return true;
    }

    pending.push(record.cause);

    if (Array.isArray(record.errors)) {
      pending.push(...record.errors);
    }
  }

  return false;
}

/** Creates the standard problem response for a Java API connection failure. */
export function upstreamUnavailableResponse(): NextResponse {
  return NextResponse.json(
    {
      type: 'urn:stockbroker:error:UPSTREAM_UNAVAILABLE',
      title: 'API service unavailable',
      status: 503,
      detail: 'The API service is starting or unavailable. Please try again shortly.',
      code: 'UPSTREAM_UNAVAILABLE',
    },
    { status: 503 }
  );
}
