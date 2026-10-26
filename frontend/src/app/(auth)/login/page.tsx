'use client';

import { Suspense, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useAuth } from '@/lib/auth';

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { login } = useAuth();
  const redirect = searchParams.get('redirect') || '/app/dashboard';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError('');
    setIsLoading(true);
    try {
      await login(email, password);
      router.replace(redirect);
      router.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Sign in could not be completed.');
    } finally {
      setIsLoading(false);
    }
  };

  return <section className="terminal-panel p-6 sm:p-8">
    <Link href="/" className="inline-flex items-center gap-2 lg:hidden"><span className="grid h-7 w-7 place-items-center border border-primary-500 bg-primary-500 font-display text-xs font-bold text-surface-950">SB</span><span className="font-mono text-xs font-bold tracking-[0.16em] text-primary-400">STOCK_BROKER</span></Link>
    <header className="mt-7 lg:mt-0"><p className="terminal-label text-primary-400">Authenticated access</p><h1 className="mt-2 font-display text-3xl font-semibold tracking-tight text-surface-50">Sign in</h1><p className="mt-2 text-sm leading-6 text-surface-300">Enter the credentials for your paper-trading workspace.</p></header>
    {error ? <div role="alert" className="mt-6 border border-negative/60 bg-negative/10 p-3 text-sm text-surface-100">{error}</div> : null}
    <form onSubmit={handleSubmit} className="mt-7 space-y-5">
      <div><label htmlFor="email" className="terminal-label mb-1.5 block">Email address</label><input id="email" name="email" type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} className="terminal-input" placeholder="you@example.com" disabled={isLoading} /></div>
      <div><label htmlFor="password" className="terminal-label mb-1.5 block">Password</label><input id="password" name="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} className="terminal-input" placeholder="Enter your password" disabled={isLoading} /></div>
      <button type="submit" disabled={isLoading} className="terminal-button w-full py-3">{isLoading ? 'Signing in…' : 'Sign in to workspace'}</button>
    </form>
    <p className="mt-6 border-t border-surface-600 pt-5 text-sm text-surface-300">New to the platform? <Link href="/register" className="font-semibold text-primary-300 hover:text-primary-200">Create an account</Link></p>
  </section>;
}

export default function LoginPage() {
  return <Suspense fallback={<div className="terminal-panel p-8 text-center text-sm text-surface-300">Loading sign in…</div>}><LoginForm /></Suspense>;
}
