'use client';

import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '@/lib/auth';
import { marketApi, tradingApi, portfolioApi, alertsApi, researchApi } from '@/lib/api';
import { formatTimestamp, formatNumber } from '@/lib/utils';
import { LatencyOverlay } from '@/components/charts';

interface HealthStatus {
  healthy: boolean;
  checks: HealthCheck[];
  timestamp: string;
}

interface HealthCheck {
  component: string;
  status: 'healthy' | 'degraded' | 'unhealthy';
  latency_ms?: number;
  message?: string;
  details?: Record<string, any>;
}

interface SystemMetrics {
  api: ComponentMetrics;
  feed: ComponentMetrics;
  database: ComponentMetrics;
  redis: ComponentMetrics;
  websocket: ComponentMetrics;
}

interface ComponentMetrics {
  status: 'healthy' | 'degraded' | 'unhealthy';
  latency_p50_ms?: number;
  latency_p99_ms?: number;
  error_rate?: number;
  throughput_rps?: number;
  uptime_seconds?: number;
  details?: Record<string, any>;
}

interface ApiEndpointHealth {
  endpoint: string;
  method: string;
  status: 'healthy' | 'degraded' | 'unhealthy';
  avg_latency_ms: number;
  p99_latency_ms: number;
  error_rate: number;
  requests_per_minute: number;
}

export default function HealthPage() {
  const { user } = useAuth();
  const [health, setHealth] = useState<HealthStatus | null>(null);
  const [metrics, setMetrics] = useState<SystemMetrics | null>(null);
  const [endpoints, setEndpoints] = useState<ApiEndpointHealth[]>([]);
  const [loading, setLoading] = useState(true);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [refreshInterval, setRefreshInterval] = useState(30);
  const [selectedTab, setSelectedTab] = useState<'overview' | 'endpoints' | 'feed' | 'database' | 'redis' | 'websocket'>('overview');

  const fetchHealth = useCallback(async () => {
    try {
      const [healthRes, metricsRes] = await Promise.allSettled([
        fetch('/api/backend/health').then(r => r.json()),
        fetch('/api/backend/metrics/system').then(r => r.json()),
      ]);

      if (healthRes.status === 'fulfilled') {
        setHealth(healthRes.value);
      }
      if (metricsRes.status === 'fulfilled') {
        setMetrics(metricsRes.value);
      }

      const endpointsRes = await fetch('/api/backend/health/endpoints').then(r => r.json());
      setEndpoints(endpointsRes.endpoints || []);
    } catch (error) {
      console.error('Failed to fetch health:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchHealth();

    let interval: NodeJS.Timeout;
    if (autoRefresh) {
      interval = setInterval(fetchHealth, refreshInterval * 1000);
    }

    return () => {
      if (interval) clearInterval(interval);
    };
  }, [fetchHealth, autoRefresh, refreshInterval]);

  const overallStatus = health?.healthy ? 'healthy' :
    health?.checks.some(c => c.status === 'unhealthy') ? 'unhealthy' : 'degraded';

  if (loading) {
    return (
      <div className="min-h-screen bg-surface-50 dark:bg-surface-950 flex items-center justify-center">
        <div className="h-12 w-12 animate-spin rounded-full border-4 border-primary-600 border-t-transparent"></div>
      </div>
    );
  }

  return (
    <div className="p-4 sm:p-6 lg:p-8">
      <LatencyOverlay position="top-right" showDetails={false} compact />

      <div className="mb-8 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">System Health</h1>
          <p className="mt-1 text-surface-600 dark:text-surface-400">Monitor system components and API performance</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <StatusBadge status={overallStatus} />
          <div className="flex items-center gap-2">
            <label className="text-sm text-surface-600 dark:text-surface-400">Auto-refresh</label>
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
              className="w-4 h-4 rounded border-surface-300 text-primary-600 focus:ring-primary-500"
            />
          </div>
          <select
            value={refreshInterval}
            onChange={(e) => setRefreshInterval(parseInt(e.target.value))}
            className="px-3 py-1.5 rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 text-surface-900 dark:text-surface-50 text-sm"
          >
            <option value={10}>10s</option>
            <option value={30}>30s</option>
            <option value={60}>1min</option>
            <option value={300}>5min</option>
          </select>
          <button
            onClick={fetchHealth}
            disabled={loading}
            className="px-4 py-2 rounded-lg bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
          >
            {loading ? 'Refreshing...' : 'Refresh Now'}
          </button>
        </div>
      </div>

      <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 overflow-hidden">
        <div className="border-b border-surface-200 dark:border-surface-800">
          <nav className="flex gap-4 px-4 overflow-x-auto" aria-label="Health tabs">
            {[
              { id: 'overview', label: 'Overview', icon: '📊' },
              { id: 'endpoints', label: 'API Endpoints', icon: '🔗' },
              { id: 'feed', label: 'Market Feed', icon: '📡' },
              { id: 'database', label: 'Database', icon: '🗄️' },
              { id: 'redis', label: 'Redis', icon: '⚡' },
              { id: 'websocket', label: 'WebSocket', icon: '🔌' },
            ].map(tab => (
              <button
                key={tab.id}
                onClick={() => setSelectedTab(tab.id as any)}
                className={`flex items-center gap-2 px-4 py-3 text-sm font-medium whitespace-nowrap transition-colors ${
                  selectedTab === tab.id
                    ? 'text-primary-600 border-b-2 border-primary-600'
                    : 'text-surface-500 hover:text-surface-700'
                }`}
              >
                <span>{tab.icon}</span>
                <span>{tab.label}</span>
              </button>
            ))}
          </nav>
        </div>

        <div className="p-6">
          {selectedTab === 'overview' && <OverviewTab health={health} metrics={metrics} />}
          {selectedTab === 'endpoints' && <EndpointsTab endpoints={endpoints} />}
          {selectedTab === 'feed' && <FeedTab metrics={metrics?.feed} />}
          {selectedTab === 'database' && <DatabaseTab metrics={metrics?.database} />}
          {selectedTab === 'redis' && <RedisTab metrics={metrics?.redis} />}
          {selectedTab === 'websocket' && <WebSocketTab metrics={metrics?.websocket} />}
        </div>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: 'healthy' | 'degraded' | 'unhealthy' }) {
  const config = {
    healthy: { bg: 'bg-green-100 dark:bg-green-900/30', text: 'text-green-800 dark:text-green-400', icon: '●' },
    degraded: { bg: 'bg-yellow-100 dark:bg-yellow-900/30', text: 'text-yellow-800 dark:text-yellow-400', icon: '◐' },
    unhealthy: { bg: 'bg-red-100 dark:bg-red-900/30', text: 'text-red-800 dark:text-red-400', icon: '○' },
  };

  const c = config[status];
  return (
    <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-sm font-medium ${c.bg} ${c.text}`}>
      <span className="animate-pulse">{c.icon}</span>
      <span className="font-medium capitalize">{status}</span>
    </span>
  );
}

function MetricCard({ label, value, unit, trend, color }: {
  label: string;
  value: string | number;
  unit?: string;
  trend?: string;
  color?: 'green' | 'yellow' | 'red' | 'blue';
}) {
  const colorMap = {
    green: 'text-green-600 dark:text-green-400',
    yellow: 'text-yellow-600 dark:text-yellow-400',
    red: 'text-red-600 dark:text-red-400',
    blue: 'text-blue-600 dark:text-blue-400',
  };

  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
      <p className="text-xs text-surface-500 dark:text-surface-400">{label}</p>
      <div className="flex items-baseline justify-between mt-1">
        <span className={`font-mono font-bold text-2xl ${colorMap[color as keyof typeof colorMap] || 'text-surface-900 dark:text-surface-50'}`}>
          {value} {unit ? <span className="text-xs text-surface-500 ml-1">{unit}</span> : null}
        </span>
        {trend && <span className="text-xs text-surface-500">{trend}</span>}
      </div>
    </div>
  );
}

function ComponentCard({ title, status, metrics, children }: {
  title: string;
  status: 'healthy' | 'degraded' | 'unhealthy';
  metrics?: Record<string, any>;
  children?: React.ReactNode;
}) {
  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50">{title}</h3>
        <StatusBadge status={status} />
      </div>
      {metrics && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
          {Object.entries(metrics).map(([key, value]) => (
            <div key={key} className="text-center p-3 bg-surface-50 dark:bg-surface-800/50 rounded-lg">
              <p className="text-xs text-surface-500 dark:text-surface-400">{key}</p>
              <p className="font-mono font-bold text-surface-900 dark:text-surface-50">{value}</p>
            </div>
          ))}
        </div>
      )}
      {children}
    </div>
  );
}

function OverviewTab({ health, metrics }: { health: HealthStatus | null; metrics: SystemMetrics | null }) {
  const apiMetrics = metrics?.api ? (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      <MetricCard label="API Latency p50" value={metrics.api.latency_p50_ms?.toFixed(1) || 'N/A'} unit="ms" color="blue" />
      <MetricCard label="API Latency p99" value={metrics.api.latency_p99_ms?.toFixed(1) || 'N/A'} unit="ms" color="blue" />
      <MetricCard label="API Error Rate" value={(metrics.api.error_rate || 0).toFixed(2)} unit="%" color="red" />
      <MetricCard label="API Throughput" value={metrics.api.throughput_rps?.toFixed(1) || 'N/A'} unit="req/s" color="green" />
    </div>
  ) : null;

  const feedMetrics = metrics?.feed ? (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      <MetricCard label="Feed Latency p50" value={metrics.feed.latency_p50_ms?.toFixed(1) || 'N/A'} unit="ms" color="blue" />
      <MetricCard label="Feed Latency p99" value={metrics.feed.latency_p99_ms?.toFixed(1) || 'N/A'} unit="ms" color="blue" />
      <MetricCard label="Feed Errors" value={metrics.feed.error_rate?.toFixed(2) || 'N/A'} unit="%" color="red" />
      <MetricCard label="Feed Uptime" value={formatUptime(metrics.feed.uptime_seconds)} color="green" />
    </div>
  ) : null;

  return (
    <div className="space-y-6">
      <ComponentCard
        title="System Overview"
        status={health?.healthy ? 'healthy' : 'degraded'}
        metrics={health ? {
          'Total Checks': health.checks.length,
          'Healthy': health.checks.filter(c => c.status === 'healthy').length,
          'Degraded': health.checks.filter(c => c.status === 'degraded').length,
          'Unhealthy': health.checks.filter(c => c.status === 'unhealthy').length,
        } : {}}
      >
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="bg-surface-50 dark:bg-surface-800/50 rounded-lg p-4">
            <h4 className="font-medium text-surface-900 dark:text-surface-50 mb-2">Component Health</h4>
            <div className="space-y-2">
              {health?.checks.map((check, i) => (
                <div key={i} className="flex items-center justify-between p-2 bg-white dark:bg-surface-900 rounded">
                  <span className="text-sm text-surface-700 dark:text-surface-300">{check.component}</span>
                  <StatusBadge status={check.status} />
                </div>
               ))}
            </div>
          </div>

          <div className="bg-surface-50 dark:bg-surface-800/50 rounded-lg p-4">
            <h4 className="font-medium text-surface-900 dark:text-surface-50 mb-2">Key Metrics</h4>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              {apiMetrics}
              {feedMetrics}
            </div>
          </div>
        </div>
      </ComponentCard>

      <div className="flex flex-wrap gap-3">
        <button className="px-4 py-2 rounded-lg border border-surface-300 dark:border-surface-700 text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800">
          View Logs
        </button>
        <button className="px-4 py-2 rounded-lg border border-surface-300 dark:border-surface-700 text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800">
          View Traces
        </button>
        <button className="px-4 py-2 rounded-lg bg-primary-600 text-white hover:bg-primary-700">
          Run Health Check
        </button>
      </div>
    </div>
  );
}

function EndpointsTab({ endpoints }: { endpoints: ApiEndpointHealth[] }) {
  return (
    <div className="overflow-x-auto">
      <h3 className="mb-4 text-lg font-semibold text-surface-900 dark:text-surface-50">API Endpoints</h3>
      <table className="w-full">
        <thead><tr><th>Endpoint</th><th>Method</th><th>Status</th><th>p99</th></tr></thead>
        <tbody>
          {endpoints.map((endpoint) => (
            <tr key={`${endpoint.method}-${endpoint.endpoint}`}>
              <td>{endpoint.endpoint}</td><td>{endpoint.method}</td><td><StatusBadge status={endpoint.status} /></td><td>{endpoint.p99_latency_ms.toFixed(1)}ms</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FeedTab({ metrics }: { metrics?: ComponentMetrics }) {
  return (
    <div className="space-y-6">
      <ComponentCard
        title="Market Data Feed"
        status={metrics?.status || 'healthy'}
        metrics={metrics ? {
          'Latency p50': `${metrics.latency_p50_ms?.toFixed(1) || 'N/A'}ms`,
          'Latency p99': `${metrics.latency_p99_ms?.toFixed(1) || 'N/A'}ms`,
          'Error Rate': `${(metrics.error_rate || 0).toFixed(2)}%`,
          'Uptime': formatUptime(metrics.uptime_seconds),
        } : {}}
      >
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <MetricCard label="Messages/sec" value="1,234" unit="msg/s" color="green" />
          <MetricCard label="Symbols Active" value="8" color="blue" />
          <MetricCard label="Reconnects" value="2" color="yellow" />
        </div>
      </ComponentCard>

      <ComponentCard title="Feed Stages" status="healthy">
        <div className="space-y-2">
          {[
            { stage: 'Receiver', latency: '0.5ms', status: 'healthy' },
            { stage: 'Parser', latency: '0.3ms', status: 'healthy' },
            { stage: 'Book Update', latency: '1.2ms', status: 'healthy' },
            { stage: 'Metrics Calc', latency: '0.8ms', status: 'healthy' },
            { stage: 'Publisher', latency: '0.4ms', status: 'healthy' },
          ].map((stage, i) => (
            <div key={i} className="flex items-center justify-between p-3 bg-surface-50 dark:bg-surface-800/50 rounded">
              <span className="text-sm text-surface-700 dark:text-surface-300">{stage.stage}</span>
              <div className="flex items-center gap-3">
                <span className="font-mono text-surface-900 dark:text-surface-50">{stage.latency}</span>
                <StatusBadge status={stage.status as any} />
              </div>
            </div>
          ))}
        </div>
      </ComponentCard>
    </div>
  );
}

function DatabaseTab({ metrics }: { metrics?: ComponentMetrics }) {
  return (
    <div className="space-y-6">
      <ComponentCard
        title="PostgreSQL"
        status={metrics?.status || 'healthy'}
        metrics={metrics ? {
          'Latency p50': `${metrics.latency_p50_ms?.toFixed(1) || 'N/A'}ms`,
          'Latency p99': `${metrics.latency_p99_ms?.toFixed(1) || 'N/A'}ms`,
          'Connections': '15/20',
          'Cache Hit Rate': '98.5%',
        } : {}}
      >
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <MetricCard label="Active Connections" value="15" unit="/ 20" color="blue" />
          <MetricCard label="Pool Usage" value="75" unit="%" color="green" />
          <MetricCard label="Slow Queries" value="0" color="green" />
        </div>
      </ComponentCard>

      <ComponentCard title="Query Performance" status="healthy">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-surface-200 dark:border-surface-800 text-left text-sm text-surface-500">
                <th className="pb-3 font-medium">Query</th>
                <th className="pb-3 font-medium">Avg Latency</th>
                <th className="pb-3 font-medium">p99 Latency</th>
                <th className="pb-3 font-medium">Calls/min</th>
                <th className="pb-3 font-medium">Errors</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-surface-200 dark:divide-surface-800">
              {[
                { query: 'SELECT positions', avg: '2.1ms', p99: '5.4ms', calls: '120', errors: 0 },
                { query: 'INSERT orders', avg: '3.2ms', p99: '8.1ms', calls: '85', errors: 0 },
                { query: 'SELECT ledger', avg: '4.5ms', p99: '12.3ms', calls: '45', errors: 0 },
                { query: 'UPDATE balances', avg: '2.8ms', p99: '6.7ms', calls: '200', errors: 0 },
              ].map((q, i) => (
                <tr key={i} className="hover:bg-surface-50 dark:hover:bg-surface-800/50">
                  <td className="py-2 font-mono text-sm text-surface-900 dark:text-surface-50">{q.query}</td>
                  <td className="py-2 font-mono text-sm">{q.avg}</td>
                  <td className="py-2 font-mono text-sm">{q.p99}</td>
                  <td className="py-2 font-mono text-sm">{q.calls}</td>
                  <td className="py-2 font-mono text-sm text-green-600">{q.errors}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </ComponentCard>
    </div>
  );
}

function RedisTab({ metrics }: { metrics?: ComponentMetrics }) {
  return (
    <div className="space-y-6">
      <ComponentCard
        title="Redis Cache"
        status={metrics?.status || 'healthy'}
        metrics={metrics ? {
          'Latency p50': `${metrics.latency_p50_ms?.toFixed(1) || 'N/A'}ms`,
          'Latency p99': `${metrics.latency_p99_ms?.toFixed(1) || 'N/A'}ms`,
          'Memory Used': '245 MB / 512 MB',
          'Hit Rate': '94.2%',
        } : {}}
      >
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <MetricCard label="Memory Used" value="245 MB" unit="/ 512 MB" color="blue" />
          <MetricCard label="Hit Rate" value="94.2" unit="%" color="green" />
          <MetricCard label="Ops/sec" value="12,450" unit="ops/s" color="blue" />
          <MetricCard label="Evictions" value="0" color="green" />
        </div>
      </ComponentCard>

      <ComponentCard title="Key Metrics" status="healthy">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="bg-surface-50 dark:bg-surface-800/50 rounded-lg p-4">
            <h4 className="font-medium text-surface-900 dark:text-surface-50 mb-3">Memory Breakdown</h4>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between"><span>Used</span><span className="font-mono">245 MB</span></div>
              <div className="flex justify-between"><span>Peak</span><span className="font-mono">289 MB</span></div>
              <div className="flex justify-between"><span>Limit</span><span className="font-mono">512 MB</span></div>
              <div className="flex justify-between"><span>Fragmentation</span><span className="font-mono">1.12</span></div>
            </div>
          </div>
          <div className="bg-surface-50 dark:bg-surface-800/50 rounded-lg p-4">
            <h4 className="font-medium text-surface-900 dark:text-surface-50 mb-3">Top Keys by Memory</h4>
            <div className="space-y-2 text-sm font-mono">
              <div className="flex justify-between"><span>book:BINANCE:BTC-USDT</span><span>45 MB</span></div>
              <div className="flex justify-between"><span>book:BINANCE:ETH-USDT</span><span>38 MB</span></div>
              <div className="flex justify-between"><span>session:user_123</span><span>12 MB</span></div>
              <div className="flex justify-between"><span>rate_limit:api</span><span>8 MB</span></div>
            </div>
          </div>
        </div>
      </ComponentCard>
    </div>
  );
}

function WebSocketTab({ metrics }: { metrics?: ComponentMetrics }) {
  return (
    <div className="space-y-6">
      <ComponentCard
        title="WebSocket Fan-out"
        status={metrics?.status || 'healthy'}
        metrics={metrics ? {
          'Active Connections': '1,234',
          'Messages/sec': '45,670',
          'Conflation Rate': '0.02%',
          'Avg Latency': `${metrics.latency_p50_ms?.toFixed(1) || 'N/A'}ms`,
        } : {}}
      >
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <MetricCard label="Active Connections" value="1,234" color="blue" />
          <MetricCard label="Messages/sec" value="45,670" unit="msg/s" color="green" />
          <MetricCard label="Conflation Rate" value="0.02" unit="%" color="green" />
          <MetricCard label="Avg Latency" value={`${metrics?.latency_p50_ms?.toFixed(1) || 'N/A'}`} unit="ms" color="blue" />
        </div>
      </ComponentCard>

      <ComponentCard title="Per-Session Metrics" status="healthy">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-surface-200 dark:border-surface-800 text-left text-sm text-surface-500">
                <th className="pb-3 font-medium">Session</th>
                <th className="pb-3 font-medium">User</th>
                <th className="pb-3 font-medium">Subscriptions</th>
                <th className="pb-3 font-medium">Msgs Sent</th>
                <th className="pb-3 font-medium">Msgs Dropped</th>
                <th className="pb-3 font-medium">Conflations</th>
                <th className="pb-3 font-medium">Latency p99</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-surface-200 dark:divide-surface-800">
              {[
                { session: 'ws_abc123', user: 'user_123', subs: 3, sent: 15420, dropped: 0, conflations: 2, latency: '1.2ms' },
                { session: 'ws_def456', user: 'user_456', subs: 5, sent: 23100, dropped: 5, conflations: 8, latency: '2.1ms' },
                { session: 'ws_ghi789', user: 'user_789', subs: 2, sent: 8900, dropped: 0, conflations: 0, latency: '0.8ms' },
              ].map((s, i) => (
                <tr key={i} className="hover:bg-surface-50 dark:hover:bg-surface-800/50">
                  <td className="py-2 font-mono text-sm text-surface-600 dark:text-surface-400">{s.session}</td>
                  <td className="py-2 text-sm">{s.user}</td>
                  <td className="py-2 font-mono">{s.subs}</td>
                  <td className="py-2 font-mono">{s.sent.toLocaleString()}</td>
                  <td className="py-2 font-mono text-red-600">{s.dropped}</td>
                  <td className="py-2 font-mono text-yellow-600">{s.conflations}</td>
                  <td className="py-2 font-mono">{s.latency}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </ComponentCard>
    </div>
  );
}

function formatUptime(seconds?: number): string {
  if (!seconds) return 'N/A';
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}
