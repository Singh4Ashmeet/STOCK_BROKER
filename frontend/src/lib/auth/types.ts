export interface User {
  id: string;
  email: string;
  name: string;
  roles: string[];
  createdAt: string;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: 'Bearer';
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  name: string;
}

export interface AuthState {
  user: User | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
}

export interface ApiError {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  parameters?: Record<string, unknown>;
}

export const AUTH_COOKIE_NAME = 'refresh_token';
export const ACCESS_TOKEN_HEADER = 'Authorization';