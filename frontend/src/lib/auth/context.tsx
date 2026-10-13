'use client';

import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import { authClient } from './client';
import { User, AuthState } from './types';

interface AuthContextType extends AuthState {
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, name: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    user: null,
    accessToken: null,
    isAuthenticated: false,
    isLoading: true,
  });

  const updateAuth = useCallback((user: User | null, accessToken: string | null) => {
    setState({
      user,
      accessToken,
      isAuthenticated: !!user && !!accessToken,
      isLoading: false,
    });
    if (accessToken) {
      authClient.setAccessToken(accessToken);
    }
  }, []);

  const login = async (email: string, password: string) => {
    setState(prev => ({ ...prev, isLoading: true }));
    try {
      const tokens = await authClient.login({ email, password });
      const user = await authClient.getCurrentUser();
      updateAuth(user, tokens.accessToken);
    } catch (error) {
      updateAuth(null, null);
      throw error;
    }
  };

  const register = async (email: string, password: string, name: string) => {
    setState(prev => ({ ...prev, isLoading: true }));
    try {
      await authClient.register({ email, password, name });
      const tokens = await authClient.login({ email, password });
      const user = await authClient.getCurrentUser();
      updateAuth(user, tokens.accessToken);
    } catch (error) {
      updateAuth(null, null);
      throw error;
    }
  };

  const logout = async () => {
    setState(prev => ({ ...prev, isLoading: true }));
    try {
      await authClient.logout();
    } finally {
      updateAuth(null, null);
    }
  };

  const refreshUser = async () => {
    if (!authClient.getAccessToken()) {
      updateAuth(null, null);
      return;
    }

    try {
      const user = await authClient.getCurrentUser();
      if (user) {
        updateAuth(user, authClient.getAccessToken());
      } else {
        // Try to refresh token
        const tokens = await authClient.refreshAccessToken();
        const refreshedUser = await authClient.getCurrentUser();
        updateAuth(refreshedUser, tokens.accessToken);
      }
    } catch {
      updateAuth(null, null);
    }
  };

  // Initialize auth on mount
  useEffect(() => {
    let mounted = true;

    const initAuth = async () => {
      try {
        // Check if we have a valid access token (from memory or previous session)
        const token = authClient.getAccessToken();
        if (token) {
          const user = await authClient.getCurrentUser();
          if (mounted) {
            updateAuth(user, token);
          }
        } else {
          // Try to refresh token from httpOnly cookie
          try {
            const tokens = await authClient.refreshAccessToken();
            const user = await authClient.getCurrentUser();
            if (mounted) {
              updateAuth(user, tokens.accessToken);
            }
          } catch {
            if (mounted) {
              updateAuth(null, null);
            }
          }
        }
      } catch {
        if (mounted) {
          updateAuth(null, null);
        }
      }
    };

    initAuth();

    return () => {
      mounted = false;
    };
  }, [updateAuth]);

  return (
    <AuthContext.Provider value={{ ...state, login, register, logout, refreshUser }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}