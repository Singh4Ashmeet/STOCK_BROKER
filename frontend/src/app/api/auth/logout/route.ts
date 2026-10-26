import { NextRequest, NextResponse } from 'next/server';
import { isUpstreamUnavailable, upstreamUnavailableResponse } from '@/lib/api/upstream';

const API_BASE = process.env.API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';

export async function POST(request: NextRequest) {
  try {
    // Get cookies from request
    const refreshToken = request.cookies.get('refresh_token')?.value;
    const cookieHeader = request.headers.get('cookie');

    // Forward to Java API
    const response = await fetch(`${API_BASE}/api/v1/auth/logout`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(cookieHeader && { Cookie: cookieHeader }),
      },
      body: refreshToken ? JSON.stringify({ refresh_token: refreshToken }) : undefined,
    });

    const data = response.status === 204 ? undefined : await response.json().catch(() => ({ success: true }));

    // HTTP 204 responses must not contain a response body. NextResponse.json()
    // rejects that combination, which previously converted a successful
    // upstream logout into a 500 response.
    const nextResponse = response.status === 204
      ? new NextResponse(null, { status: response.status })
      : NextResponse.json(data, { status: response.status });
    nextResponse.cookies.delete('refresh_token');

    return nextResponse;
  } catch (error) {
    console.error('Logout error:', error);
    // Even on error, clear the cookie
    const nextResponse = isUpstreamUnavailable(error)
      ? upstreamUnavailableResponse()
      : NextResponse.json(
          {
            type: 'urn:stockbroker:error:INTERNAL_ERROR',
            title: 'Internal server error',
            status: 500,
            detail: 'An unexpected error occurred',
            code: 'INTERNAL_ERROR',
          },
          { status: 500 }
        );
    nextResponse.cookies.delete('refresh_token');
    return nextResponse;
  }
}
