'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';

export default function AppLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { isAuthenticated, isLoading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      router.push('/login');
    }
  }, [isAuthenticated, isLoading, router]);

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary-600 border-t-transparent"></div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return null;
  }

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-950">
      <header className="border-b border-surface-200 dark:border-surface-800 bg-white/80 dark:bg-surface-950/80 backdrop-blur-sm sticky top-0 z-50">
        <nav className="mx-auto max-w-full px-4 sm:px-6 lg:px-8" aria-label="App navigation">
          <div className="flex h-14 items-center justify-between">
            <div className="flex items-center gap-8">
              <a href="/app/dashboard" className="flex items-center gap-2 text-xl font-bold text-primary-600 dark:text-primary-400">
                <svg className="h-8 w-8" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
                </svg>
                Stock Broker
              </a>
              <div className="hidden md:flex md:gap-6">
                <a href="/app/dashboard" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  Dashboard
                </a>
                <a href="/app/trading" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  Trading
                </a>
                <a href="/app/portfolio" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  Portfolio
                </a>
                <a href="/app/research" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  Research
                </a>
                <a href="/app/alerts" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  Alerts
                </a>
              </div>
            </div>
            <div className="flex items-center gap-4">
              <div className="hidden sm:flex sm:items-center sm:gap-2">
                <span className="text-sm text-surface-600 dark:text-surface-400">Paper Trading</span>
                <span className="inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800 dark:bg-green-900/30 dark:text-green-400">
                  <span className="relative flex h-1.5 w-1.5">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-green-500"></span>
                  </span>
                  Live
                </span>
              </div>
              <button
                onClick={() => {
                  // Handle logout
                  fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
                  router.push('/login');
                  router.refresh();
                }}
                className="rounded-lg border border-surface-300 dark:border-surface-700 px-4 py-2 text-sm font-medium text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800 transition-colors"
              >
                Sign out
              </button>
            </div>
          </div>
        </nav>
      </header>
      <main className="flex-1">{children}</main>
    </div>
  );
}