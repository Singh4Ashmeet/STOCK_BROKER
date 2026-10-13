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

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (password !== confirmPassword) {
      setError('Passwords do not match');
      return;
    }

    if (password.length < 8) {
      setError('Password must be at least 8 characters');
      return;
    }

    setIsLoading(true);

    try {
      await register(email, password, name);
      router.push('/app/dashboard');
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Registration failed');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-8 shadow-sm">
      <div className="text-center mb-8">
        <a href="/" className="inline-flex items-center gap-2 text-2xl font-bold text-primary-600 dark:text-primary-400 mb-6">
          <svg className="h-10 w-10" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
          </svg>
          Stock Broker
        </a>
        <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">Create your account</h1>
        <p className="mt-2 text-surface-600 dark:text-surface-400">Start paper trading in seconds</p>
      </div>

      {error && (
        <div className="mb-6 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-4 text-red-700 dark:text-red-300 text-sm" role="alert">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-6">
        <div>
          <label htmlFor="name" className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1.5">
            Full Name
          </label>
          <input
            id="name"
            name="name"
            type="text"
            autoComplete="name"
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-4 py-2.5 text-surface-900 dark:text-surface-50 placeholder-surface-400 dark:placeholder-surface-600 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/20 transition-colors"
            placeholder="John Doe"
            disabled={isLoading}
          />
        </div>

        <div>
          <label htmlFor="email" className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1.5">
            Email
          </label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-4 py-2.5 text-surface-900 dark:text-surface-50 placeholder-surface-400 dark:placeholder-surface-600 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/20 transition-colors"
            placeholder="you@example.com"
            disabled={isLoading}
          />
        </div>

        <div>
          <label htmlFor="password" className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1.5">
            Password
          </label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-4 py-2.5 text-surface-900 dark:text-surface-50 placeholder-surface-400 dark:placeholder-surface-600 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/20 transition-colors"
            placeholder="••••••••"
            disabled={isLoading}
          />
        </div>

        <div>
          <label htmlFor="confirmPassword" className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1.5">
            Confirm Password
          </label>
          <input
            id="confirmPassword"
            name="confirmPassword"
            type="password"
            autoComplete="new-password"
            required
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-4 py-2.5 text-surface-900 dark:text-surface-50 placeholder-surface-400 dark:placeholder-surface-600 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/20 transition-colors"
            placeholder="••••••••"
            disabled={isLoading}
          />
        </div>

        <div className="flex items-start gap-2">
          <input
            type="checkbox"
            id="terms"
            required
            className="mt-1 h-4 w-4 rounded border-surface-300 dark:border-surface-700 text-primary-600 focus:ring-primary-500"
          />
          <label htmlFor="terms" className="text-sm text-surface-600 dark:text-surface-400">
            I agree to the{' '}
            <Link href="/terms" className="text-primary-600 hover:text-primary-500 underline">
              Terms of Service
            </Link>{' '}
            and{' '}
            <Link href="/privacy" className="text-primary-600 hover:text-primary-500 underline">
              Privacy Policy
            </Link>
            . This is a paper trading platform only. No real money involved.
          </label>
        </div>

        <button
          type="submit"
          disabled={isLoading}
          className="w-full rounded-lg bg-primary-600 py-2.5 text-base font-medium text-white hover:bg-primary-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {isLoading ? 'Creating account...' : 'Create account'}
        </button>
      </form>

      <p className="mt-6 text-center text-sm text-surface-600 dark:text-surface-400">
        Already have an account?{' '}
        <Link href="/login" className="font-medium text-primary-600 hover:text-primary-500">
          Sign in
        </Link>
      </p>
    </div>
  );
}