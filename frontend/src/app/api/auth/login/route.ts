import { NextRequest, NextResponse } from 'next/server';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { email, password } = body;

    if (!email || !password) {
      return NextResponse.json(
        {
          type: 'urn:stockbroker:error:VALIDATION_FAILED',
          title: 'Validation failed',
          status: 400,
          detail: 'Email and password are required',
          code: 'VALIDATION_FAILED',
        },
        { status: 400 }
      );
    }

    // Forward to Java API
    const response = await fetch(`${API_BASE}/api/v1/auth/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ email, password }),
    });

    const data = await response.json();

    if (!response.ok) {
      return NextResponse.json(data, { status: response.status });
    }

    // Create response with httpOnly refresh cookie
    const nextResponse = NextResponse.json({
      accessToken: data.access_token,
      expiresIn: data.expires_in,
      tokenType: data.token_type,
    });

    // Set httpOnly refresh token cookie
    // The Java API returns the refresh token in the response body and also sets the cookie
    // We need to extract and set it here
    const refreshToken = data.refresh_token;
    if (refreshToken) {
      nextResponse.cookies.set('refresh_token', refreshToken, {
        httpOnly: true,
        secure: process.env.NODE_ENV === 'production',
        sameSite: 'strict',
        maxAge: 30 * 24 * 60 * 60, // 30 days
        path: '/',
      });
    }

    return nextResponse;
  } catch (error) {
    console.error('Login error:', error);
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