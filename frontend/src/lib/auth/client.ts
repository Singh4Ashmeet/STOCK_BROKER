'use client';

import { TokenPair, LoginRequest, RegisterRequest, User, ApiError } from './types';

const AUTH_BASE = '/api/auth';
const BACKEND_BASE = '/api/backend';

class AuthClient {
  private accessToken: string | null = null;
  private refreshPromise: Promise<TokenPair> | null = null;

  getAccessToken(): string | null {
    return this.accessToken;
  }

  setAccessToken(token: string | null): void {
    this.accessToken = token;
  }

  async login(credentials: LoginRequest): Promise<TokenPair> {
    const response = await fetch(`${AUTH_BASE}/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include', // Important: includes httpOnly refresh cookie
      body: JSON.stringify(credentials),
    });

    if (!response.ok) {
      const error: ApiError = await response.json();
      throw new Error(error.detail || 'Login failed');
    }

    const data: TokenPair = await response.json();
    this.accessToken = data.accessToken;
    return data;
  }

  async register(data: RegisterRequest): Promise<void> {
    const response = await fetch(`${AUTH_BASE}/register`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
      body: JSON.stringify(data),
    });

    if (!response.ok) {
      const error: ApiError = await response.json();
      throw new Error(error.detail || 'Registration failed');
    }

    // Registration deliberately does not create a session. AuthProvider follows
    // with the single login request that establishes the refresh-token cookie.
    await response.json();
  }

  async logout(): Promise<void> {
    try {
      await fetch(`${AUTH_BASE}/logout`, {
        method: 'POST',
        credentials: 'include',
      });
    } finally {
      this.accessToken = null;
    }
  }

  async refreshAccessToken(): Promise<TokenPair> {
    // Prevent multiple simultaneous refresh attempts
    if (this.refreshPromise) {
      return this.refreshPromise;
    }

    this.refreshPromise = (async () => {
      const response = await fetch(`${AUTH_BASE}/refresh`, {
        method: 'POST',
        credentials: 'include', // Sends httpOnly refresh cookie automatically
      });

      if (!response.ok) {
        this.accessToken = null;
        this.refreshPromise = null;
        throw new Error('Token refresh failed');
      }

      const data: TokenPair = await response.json();
      this.accessToken = data.accessToken;
      this.refreshPromise = null;
      return data;
    })();

    return this.refreshPromise;
  }

  async getCurrentUser(): Promise<User | null> {
    if (!this.accessToken) return null;

    const response = await fetch(`${BACKEND_BASE}/auth/me`, {
      headers: {
        Authorization: `Bearer ${this.accessToken}`,
      },
      credentials: 'include',
    });

    if (!response.ok) {
      if (response.status === 401) {
        this.accessToken = null;
      }
      return null;
    }

    const user: {
      user_id: string;
      email: string;
      role: string;
      full_name: string;
      created_at: string;
    } = await response.json();

    return {
      id: user.user_id,
      email: user.email,
      name: user.full_name,
      roles: [user.role],
      createdAt: user.created_at,
    };
  }

  async requestWithAuth<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    if (!this.accessToken) {
      throw new Error('No access token');
    }

    const response = await fetch(`${BACKEND_BASE}${endpoint}`, {
      ...options,
      headers: {
        ...options.headers,
        Authorization: `Bearer ${this.accessToken}`,
        'Content-Type': 'application/json',
      },
      credentials: 'include',
    });

    if (response.status === 401) {
      // Try to refresh token once
      try {
        await this.refreshAccessToken();
        // Retry with new token
        const retryResponse = await fetch(`${BACKEND_BASE}${endpoint}`, {
          ...options,
          headers: {
            ...options.headers,
            Authorization: `Bearer ${this.accessToken}`,
            'Content-Type': 'application/json',
          },
          credentials: 'include',
        });

        if (!retryResponse.ok) {
          const error: ApiError = await retryResponse.json();
          throw new Error(error.detail || 'Request failed');
        }

        return retryResponse.json();
      } catch {
        this.accessToken = null;
        throw new Error('Authentication expired');
      }
    }

    if (!response.ok) {
      const error: ApiError = await response.json();
      throw new Error(error.detail || 'Request failed');
    }

    if (response.status === 204) {
      return undefined as T;
    }

    return response.json();
  }
}

// Singleton instance
export const authClient = new AuthClient();
