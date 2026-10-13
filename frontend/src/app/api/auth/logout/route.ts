import { NextRequest, NextResponse } from 'next/server';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';

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
    });

    const data = await response.json();

    // Clear the refresh token cookie
    const nextResponse = NextResponse.json(data, { status: response.status });
    nextResponse.cookies.delete('refresh_token');

    return nextResponse;
  } catch (error) {
    console.error('Logout error:', error);
    // Even on error, clear the cookie
    const nextResponse = NextResponse.json(
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