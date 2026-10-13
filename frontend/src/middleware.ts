import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

// Public paths that don't require authentication
const publicPaths = [
  '/',
  '/login',
  '/register',
  '/forgot-password',
  '/reset-password',
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
];

// Paths that require authentication
const protectedPaths = [
  '/app',
  '/api/app',
  '/api/v1',
];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // Check if path is public
  const isPublic = publicPaths.some(path => pathname.startsWith(path));
  const isProtected = protectedPaths.some(path => pathname.startsWith(path));

  // Get refresh token from cookies
  const refreshToken = request.cookies.get('refresh_token')?.value;

  // For API routes, check Authorization header
  const authHeader = request.headers.get('authorization');
  const hasAccessToken = authHeader?.startsWith('Bearer ');

  // Allow public paths
  if (isPublic) {
    return NextResponse.next();
  }

  // For protected paths, require authentication
  if (isProtected) {
    // For browser requests, check refresh token cookie
    // For API requests, check Authorization header
    if (!refreshToken && !hasAccessToken) {
      // Redirect to login for browser requests
      if (!pathname.startsWith('/api/')) {
        const loginUrl = new URL('/login', request.url);
        loginUrl.searchParams.set('redirect', pathname);
        return NextResponse.redirect(loginUrl);
      }

      // Return 401 for API requests
      return new NextResponse(
        JSON.stringify({
          type: 'urn:stockbroker:error:AUTH_TOKEN_INVALID',
          title: 'Unauthorized',
          status: 401,
          detail: 'Authentication required',
          code: 'AUTH_TOKEN_INVALID',
        }),
        {
          status: 401,
          headers: { 'Content-Type': 'application/problem+json' },
        }
      );
    }
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    /*
     * Match all request paths except for the ones starting with:
     * - _next/static (static files)
     * - _next/image (image optimization files)
     * - favicon.ico (favicon file)
     * - public folder
     */
    '/((?!_next/static|_next/image|favicon.ico|public/).*)',
  ],
};