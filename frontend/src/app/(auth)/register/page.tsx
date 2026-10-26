'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';

export default function RegisterPage() {
  const router = useRouter();
  const { register } = useAuth();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const validatePassword = (value: string) => {
    if (value.length < 12 || value.length > 128) return 'Password must be between 12 and 128 characters.';
    if (!/[A-Z]/.test(value) || !/[a-z]/.test(value) || !/\d/.test(value) || !/[!@#$%^&*()_+\-=[\]{};':"\\|,.<>/?]/.test(value)) return 'Password needs uppercase, lowercase, number, and special characters.';
    return null;
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError('');
    if (password !== confirmPassword) { setError('Passwords do not match.'); return; }
    const passwordError = validatePassword(password);
    if (passwordError) { setError(passwordError); return; }
    setIsLoading(true);
    try {
      await register(email, password, name);
      router.replace('/app/dashboard');
      router.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Account creation could not be completed.');
    } finally {
      setIsLoading(false);
    }
  };

  return <section className="terminal-panel p-6 sm:p-8">
    <Link href="/" className="inline-flex items-center gap-2 lg:hidden"><span className="grid h-7 w-7 place-items-center border border-primary-500 bg-primary-500 font-display text-xs font-bold text-surface-950">SB</span><span className="font-mono text-xs font-bold tracking-[0.16em] text-primary-400">STOCK_BROKER</span></Link>
    <header className="mt-7 lg:mt-0"><p className="terminal-label text-primary-400">Paper trading access</p><h1 className="mt-2 font-display text-3xl font-semibold tracking-tight text-surface-50">Create account</h1><p className="mt-2 text-sm leading-6 text-surface-300">Set up a workspace for simulated orders and market-data tools.</p></header>
    {error ? <div role="alert" className="mt-6 border border-negative/60 bg-negative/10 p-3 text-sm text-surface-100">{error}</div> : null}
    <form onSubmit={handleSubmit} className="mt-7 space-y-5">
      <div><label htmlFor="name" className="terminal-label mb-1.5 block">Full name</label><input id="name" name="name" type="text" autoComplete="name" required value={name} onChange={(event) => setName(event.target.value)} className="terminal-input" placeholder="Your name" disabled={isLoading} /></div>
      <div><label htmlFor="email" className="terminal-label mb-1.5 block">Email address</label><input id="email" name="email" type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} className="terminal-input" placeholder="you@example.com" disabled={isLoading} /></div>
      <div><label htmlFor="password" className="terminal-label mb-1.5 block">Password</label><input id="password" name="password" type="password" autoComplete="new-password" required minLength={12} value={password} onChange={(event) => setPassword(event.target.value)} className="terminal-input" placeholder="12+ characters" disabled={isLoading} /><p className="mt-2 text-xs leading-5 text-surface-300">Use uppercase, lowercase, a number, and a special character.</p></div>
      <div><label htmlFor="confirmPassword" className="terminal-label mb-1.5 block">Confirm password</label><input id="confirmPassword" name="confirmPassword" type="password" autoComplete="new-password" required value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} className="terminal-input" placeholder="Repeat password" disabled={isLoading} /></div>
      <label className="flex items-start gap-3 border border-surface-600 bg-surface-900 p-3 text-sm leading-5 text-surface-200"><input type="checkbox" required className="mt-0.5 h-4 w-4 border-surface-500 bg-surface-950 accent-primary-500" /><span>I understand this is a paper-trading platform. No real-money custody or order routing is available.</span></label>
      <button type="submit" disabled={isLoading} className="terminal-button w-full py-3">{isLoading ? 'Creating account…' : 'Create paper-trading account'}</button>
    </form>
    <p className="mt-6 border-t border-surface-600 pt-5 text-sm text-surface-300">Already have an account? <Link href="/login" className="font-semibold text-primary-300 hover:text-primary-200">Sign in</Link></p>
  </section>;
}
