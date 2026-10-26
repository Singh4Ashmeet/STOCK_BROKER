'use client';

import { useCallback, useEffect, useState } from 'react';
import { summarizeHealth, type HealthSummary } from '@/lib/api/health';
import type { HealthResponse } from '@/lib/api';
import { LatencyOverlay } from '@/components/charts';

const REFRESH_INTERVAL_MS = 30_000;

export default function HealthPage() {
  const [health, setHealth] = useState<HealthSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchHealth = useCallback(async () => {
    try {
      const response = await fetch('/api/backend/system/health');
      if (!response.ok) {
        throw new Error(`Health endpoint returned ${response.status}`);
      }
      setHealth(summarizeHealth(await response.json() as HealthResponse));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to load system health');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchHealth();
    const interval = window.setInterval(() => void fetchHealth(), REFRESH_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [fetchHealth]);

  return (
    <div className="p-4 sm:p-6 lg:p-8">
      <LatencyOverlay position="top-right" showDetails={false} compact />
      <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">System Health</h1>
          <p className="mt-1 text-surface-600 dark:text-surface-400">
            Current market-feed connection and order-book validity. Refreshes every 30 seconds.
          </p>
        </div>
        <button onClick={() => void fetchHealth()} disabled={loading}
          className="rounded-lg bg-primary-600 px-4 py-2 text-white hover:bg-primary-700 disabled:opacity-50">
          {loading ? 'Refreshing…' : 'Refresh now'}
        </button>
      </div>

      {error ? <p role="alert" className="rounded-xl border border-red-300 bg-red-50 p-4 text-red-800 dark:border-red-900 dark:bg-red-950/30 dark:text-red-300">{error}</p> : null}

      {health ? <section className="rounded-xl border border-surface-200 bg-white p-6 dark:border-surface-800 dark:bg-surface-900">
        <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">Reported checks</h2>
          <StatusBadge status={health.healthy ? 'healthy' : 'unhealthy'} />
        </div>
        <div className="space-y-3">
          {health.checks.map((check) => <div key={check.component} className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-surface-50 p-4 dark:bg-surface-800/50">
            <div><p className="font-medium">{check.component}</p><p className="text-sm text-surface-600 dark:text-surface-400">{check.detail}</p></div>
            <StatusBadge status={check.status} />
          </div>)}
        </div>
        <p className="mt-6 text-sm text-surface-500">Build version: {health.buildVersion}</p>
      </section> : !loading && !error ? <p className="text-surface-600 dark:text-surface-400">No health response is available.</p> : null}

      <section className="mt-6 rounded-xl border border-surface-200 bg-white p-6 dark:border-surface-800 dark:bg-surface-900">
        <h2 className="text-lg font-semibold">Unavailable diagnostics</h2>
        <p className="mt-2 text-surface-600 dark:text-surface-400">
          The current API does not expose database, Redis, WebSocket, or endpoint latency metrics to this page. They are intentionally not estimated or displayed as live values.
        </p>
      </section>
    </div>
  );
}

function StatusBadge({ status }: { status: 'healthy' | 'degraded' | 'unhealthy' }) {
  const colors = {
    healthy: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
    degraded: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
    unhealthy: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  };
  return <span className={`rounded-full px-3 py-1 text-sm font-medium capitalize ${colors[status]}`}>{status}</span>;
}
