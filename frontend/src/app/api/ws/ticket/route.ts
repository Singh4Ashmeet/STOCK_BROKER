import { NextRequest, NextResponse } from 'next/server';
import { isUpstreamUnavailable, upstreamUnavailableResponse } from '@/lib/api/upstream';

const API_BASE = process.env.API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';

export async function POST(request: NextRequest) {
  try {
    // Get authorization header
    const authHeader = request.headers.get('authorization');
    const cookieHeader = request.headers.get('cookie');

    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json(
        {
          type: 'urn:stockbroker:error:AUTH_TOKEN_INVALID',
          title: 'Invalid token',
          status: 401,
          detail: 'Authorization header required',
          code: 'AUTH_TOKEN_INVALID',
        },
        { status: 401 }
      );
    }

    // Forward to Java API
    const response = await fetch(`${API_BASE}/api/v1/auth/ws/ticket`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: authHeader,
        ...(request.headers.get('cookie') && { Cookie: request.headers.get('cookie')! }),
      },
    });

    const data = await response.json();

    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    console.error('WS ticket error:', error);
    if (isUpstreamUnavailable(error)) {
      return upstreamUnavailableResponse();
    }
    return NextResponse.json(
      {
        type: 'urn:stockbroker:error:INTERNAL_ERROR',
        title: 'Internal server error',
        status: 500,
        detail: 'An unexpected error occurred',
        code: 'INTERNAL_ERROR',
      },
      { status: 500 }
    );
  }
}
