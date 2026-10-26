'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { useAuth } from '@/lib/auth';

const navigation = [
  { href: '/app/dashboard', label: 'Overview' },
  { href: '/app/trading', label: 'Trading' },
  { href: '/app/portfolio', label: 'Portfolio' },
  { href: '/app/research', label: 'Research' },
  { href: '/app/alerts', label: 'Alerts' },
  { href: '/app/health', label: 'System health' },
];

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  const [isSigningOut, setIsSigningOut] = useState(false);

  useEffect(() => {
    if (!isLoading && !isAuthenticated) router.replace('/login');
  }, [isAuthenticated, isLoading, router]);

  const signOut = async () => {
    setIsSigningOut(true);
    try {
      await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
    } finally {
      router.replace('/login');
      router.refresh();
    }
  };

  if (isLoading) {
    return <div className="grid min-h-screen place-items-center bg-surface-950"><span className="h-7 w-7 animate-spin border-2 border-primary-400 border-t-transparent" aria-label="Loading application" /></div>;
  }
  if (!isAuthenticated) return null;

  return (
    <div className="min-h-screen bg-surface-950 text-surface-50">
      <header className="sticky top-0 z-50 border-b border-surface-600 bg-surface-950">
        <div className="flex h-14 items-center justify-between gap-4 px-4 sm:px-6">
          <Link href="/app/dashboard" className="flex shrink-0 items-center gap-2" aria-label="Stock Broker overview">
            <span className="grid h-7 w-7 place-items-center border border-primary-500 bg-primary-500 font-display text-xs font-bold text-surface-950">SB</span>
            <span className="hidden font-mono text-xs font-bold tracking-[0.18em] text-primary-400 sm:block">STOCK_BROKER</span>
          </Link>

          <div className="hidden items-center gap-2 border-x border-surface-600 px-4 xl:flex" aria-label="Environment">
            <span className="h-1.5 w-1.5 bg-positive" aria-hidden="true" />
            <span className="font-mono text-[11px] uppercase tracking-[0.1em] text-surface-200">Paper environment</span>
          </div>

          <div className="flex items-center gap-3">
            <span className="hidden font-mono text-[11px] text-surface-300 md:block">AUTHENTICATED SESSION</span>
            <button onClick={() => void signOut()} disabled={isSigningOut} className="terminal-button-secondary min-h-8 px-3 py-1.5 text-xs">
              {isSigningOut ? 'Signing out…' : 'Sign out'}
            </button>
          </div>
        </div>
        <nav className="terminal-scrollbar flex overflow-x-auto border-t border-surface-700 px-2 sm:px-4" aria-label="Application navigation">
          {navigation.map((item) => {
            const active = pathname === item.href;
            return <Link key={item.href} href={item.href} aria-current={active ? 'page' : undefined}
              className={`shrink-0 border-b-2 px-3 py-2.5 text-sm transition-colors ${active ? 'border-primary-400 bg-surface-800 font-semibold text-primary-300' : 'border-transparent text-surface-300 hover:bg-surface-800 hover:text-surface-50'}`}>
              {item.label}
            </Link>;
          })}
        </nav>
      </header>
      <main>{children}</main>
    </div>
  );
}
