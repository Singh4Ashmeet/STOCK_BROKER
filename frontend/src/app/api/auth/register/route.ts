import { NextRequest, NextResponse } from 'next/server';
import { isUpstreamUnavailable, upstreamUnavailableResponse } from '@/lib/api/upstream';

// API_URL is server-only so a deployment does not have to expose its internal
// API host to the browser. Keep NEXT_PUBLIC_API_URL as the local-development
// fallback used by the existing client-side API helpers.
const API_BASE = process.env.API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { email, password, name } = body;

    if (!email || !password || !name) {
      return NextResponse.json(
        {
          type: 'urn:stockbroker:error:VALIDATION_FAILED',
          title: 'Validation failed',
          status: 400,
          detail: 'Email, password, and name are required',
          code: 'VALIDATION_FAILED',
        },
        { status: 400 }
      );
    }

    if (password.length < 8) {
      return NextResponse.json(
        {
          type: 'urn:stockbroker:error:VALIDATION_FAILED',
          title: 'Validation failed',
          status: 400,
          detail: 'Password must be at least 8 characters',
          code: 'VALIDATION_FAILED',
        },
        { status: 400 }
      );
    }

    // Forward to Java API
    const response = await fetch(`${API_BASE}/api/v1/auth/register`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ email, password, name }),
    });

    const data = await response.json();

    if (!response.ok) {
      return NextResponse.json(data, { status: response.status });
    }

    // The client logs in after this response. Keeping registration and login
    // as separate operations avoids issuing and immediately abandoning two
    // refresh tokens for every newly registered user.
    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    console.error('Register error:', error);
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
