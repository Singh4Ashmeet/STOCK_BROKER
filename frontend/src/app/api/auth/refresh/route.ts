import { NextRequest, NextResponse } from 'next/server';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';

export async function POST(request: NextRequest) {
  try {
    // Get refresh token from httpOnly cookie
    const refreshToken = request.cookies.get('refresh_token')?.value;

    if (!refreshToken) {
      return NextResponse.json(
        {
          type: 'urn:stockbroker:error:AUTH_TOKEN_INVALID',
          title: 'Invalid token',
          status: 401,
          detail: 'No refresh token available',
          code: 'AUTH_TOKEN_INVALID',
        },
        { status: 401 }
      );
    }

    // Forward to Java API with the refresh token
    const response = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: `refresh_token=${refreshToken}`,
      },
      body: JSON.stringify({ refresh_token: refreshToken }),
    });

    const data = await response.json();

    if (!response.ok) {
      // Clear the cookie on refresh failure
      const nextResponse = NextResponse.json(data, { status: response.status });
      nextResponse.cookies.delete('refresh_token');
      return nextResponse;
    }

    const nextResponse = NextResponse.json({
      accessToken: data.access_token,
      expiresIn: data.expires_in,
      tokenType: data.token_type,
    });

    // Update refresh token cookie if new one provided
    const newRefreshToken = data.refresh_token;
    if (newRefreshToken) {
      nextResponse.cookies.set('refresh_token', newRefreshToken, {
        httpOnly: true,
        secure: process.env.NODE_ENV === 'production',
        sameSite: 'strict',
        maxAge: 30 * 24 * 60 * 60,
        path: '/',
      });
    }

    return nextResponse;
  } catch (error) {
    console.error('Refresh error:', error);
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