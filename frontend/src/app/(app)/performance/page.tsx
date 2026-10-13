'use client';

import { LatencyOverlay } from '@/components/charts';
import { selectLatencyStats, selectPerformanceMetrics, useMarketStore } from '@/lib/store';

export default function PerformanceTestPage() {
  const latency = useMarketStore(selectLatencyStats);
  const performance = useMarketStore(selectPerformanceMetrics);
  return <main className="min-h-screen bg-surface-50 p-6 dark:bg-surface-950">
    <LatencyOverlay />
    <h1 className="mb-2 text-2xl font-bold">Performance</h1>
    <p className="mb-6 text-surface-500">Current browser-side market-data performance.</p>
    <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
      <Metric label="Frames per second" value={performance.fps.toFixed(1)} />
      <Metric label="Messages per second" value={performance.messageRate.toFixed(1)} />
      <Metric label="Fan-out p50" value={`${latency.p50.toFixed(1)} ms`} />
      <Metric label="Fan-out p99" value={`${latency.p99.toFixed(1)} ms`} />
    </div>
  </main>;
}

function Metric({ label, value }: { label: string; value: string }) { return <div className="rounded-xl bg-white p-4 shadow-sm dark:bg-surface-900"><p className="text-sm text-surface-500">{label}</p><p className="mt-1 text-2xl font-bold">{value}</p></div>; }
