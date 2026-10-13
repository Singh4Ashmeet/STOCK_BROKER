import { NextRequest, NextResponse } from 'next/server';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';

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

    // After registration, auto-login
    const loginResponse = await fetch(`${API_BASE}/api/v1/auth/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ email, password }),
    });

    const loginData = await loginResponse.json();

    if (!loginResponse.ok) {
      return NextResponse.json(
        { ...data, message: 'Registered but auto-login failed. Please log in manually.' },
        { status: 201 }
      );
    }

    const nextResponse = NextResponse.json({
      accessToken: loginData.access_token,
      expiresIn: loginData.expires_in,
      tokenType: loginData.token_type,
    });

    const refreshToken = loginData.refresh_token;
    if (refreshToken) {
      nextResponse.cookies.set('refresh_token', refreshToken, {
        httpOnly: true,
        secure: process.env.NODE_ENV === 'production',
        sameSite: 'strict',
        maxAge: 30 * 24 * 60 * 60,
        path: '/',
      });
    }

    return nextResponse;
  } catch (error) {
    console.error('Register error:', error);
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