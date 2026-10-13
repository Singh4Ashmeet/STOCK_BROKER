'use client';

import { useEffect, useMemo } from 'react';
import { useMarketStore, selectMetrics, selectConnectionStatus, selectPerformanceMetrics, selectLatencyStats } from '@/lib/store';
import type { Metrics } from '@/lib/api';
import { formatNumber, formatCurrency, formatPercent } from '@/lib/utils';

interface MetricsPanelProps {
  symbol: string;
  compact?: boolean;
}

export function MetricsPanel({ symbol, compact = false }: MetricsPanelProps) {
  const metricsState = useMarketStore(selectMetrics(symbol));
  const connection = useMarketStore(selectConnectionStatus);
  const performance = useMarketStore(selectPerformanceMetrics);
  const latencyStats = useMarketStore(selectLatencyStats);

  const metrics = metricsState?.metrics;
  const isConnected = connection.status === 'connected';

  // Sparkline data (would come from historical metrics in real app)
  const sparklineData = useMemo(() => {
    // In real app, this would come from a time-series of metrics
    // For now, generate placeholder based on current values
    if (!metrics) return [];
    const mid = metrics.mid || 0;
    const spread = metrics.spread || 0;
    const imbalance = metrics.imbalanceTopN || 0;
    return Array.from({ length: 50 }, (_, i) => ({
      mid: mid + (Math.random() - 0.5) * mid * 0.01,
      spread: spread + (Math.random() - 0.5) * spread * 0.2,
      imbalance: Math.max(-1, Math.min(1, imbalance + (Math.random() - 0.5) * 0.1)),
    }));
  }, [metrics]);

  if (compact) {
    return (
      <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-semibold text-surface-900 dark:text-surface-50">{symbol} Metrics</h3>
          <span className={`flex items-center gap-1.5 text-xs font-medium ${
            isConnected ? 'text-green-600' : 'text-red-600'
          }`}>
            <span className={`w-2 h-2 rounded-full ${isConnected ? 'bg-green-500' : 'bg-red-500'}`}></span>
            {connection.status}
          </span>
        </div>
        <div className="grid grid-cols-3 gap-3">
          {metrics && (
            <>
              <MetricItem label="Mid" value={formatNumber(metrics.mid || 0, 2)} />
              <MetricItem label="Spread" value={`${formatNumber(metrics.spread || 0, 1)} bps`} />
              <MetricItem label="Imbalance" value={formatNumber(metrics.imbalanceTopN || 0, 3)} color={metrics.imbalanceTopN > 0 ? 'green' : metrics.imbalanceTopN < 0 ? 'red' : undefined} />
              <MetricItem label="VWAP" value={formatCurrency(metrics.rollingVwap || 0)} />
              <MetricItem label="TFI" value={formatNumber(metrics.tradeFlowImbalance || 0, 3)} />
              <MetricItem label="Microprice" value={formatNumber(metrics.microprice || 0, 2)} />
            </>
          )}
        </div>
        <LatencyBadge />
      </div>
    );
  }

  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
        <div className="flex items-center gap-3">
          <h2 className="text-xl font-semibold text-surface-900 dark:text-surface-50">
            {symbol} Market Metrics
          </h2>
          <span className={`flex items-center gap-1.5 px-2 py-1 rounded-full text-xs font-medium ${
            isConnected ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400' :
            connection.status === 'connecting' ? 'bg-yellow-100 text-yellow-800' :
            connection.status === 'resyncing' ? 'bg-blue-100 text-blue-800' :
            'bg-red-100 text-red-800'
          }`}>
            <span className={`w-2 h-2 rounded-full ${isConnected ? 'bg-green-500' : 'bg-red-500'}`}></span>
            {connection.status.charAt(0).toUpperCase() + connection.status.slice(1)}
            {connection.reconnectAttempts > 0 && ` (retry ${connection.reconnectAttempts})`}
          </span>
        </div>
        <LatencyBadge />
      </div>

      {/* Main Metrics Grid */}
      {metrics && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          <MetricCard
            label="Mid Price"
            value={formatNumber(metrics.mid, 2)}
            sparkline={sparklineData.map(d => d.mid)}
            unit=""
          />
          <MetricCard
            label="Spread"
            value={`${formatNumber(metrics.spread, 1)} bps`}
            sparkline={sparklineData.map(d => d.spread)}
            unit="bps"
            trend={metrics.spread ? (metrics.spread > 10 ? 'up' : 'down') : undefined}
          />
          <MetricCard
            label="Imbalance"
            value={formatNumber(metrics.imbalanceTopN, 3)}
            sparkline={sparklineData.map(d => d.imbalance)}
            unit=""
            color={metrics.imbalanceTopN > 0 ? 'green' : metrics.imbalanceTopN < 0 ? 'red' : undefined}
          />
          <MetricCard
            label="Microprice"
            value={formatNumber(metrics.microprice, 2)}
            unit=""
          />
          <MetricCard
            label="VWAP"
            value={formatCurrency(metrics.rollingVwap)}
            unit=""
          />
          <MetricCard
            label="Trade Flow Imbalance"
            value={formatNumber(metrics.tradeFlowImbalance, 3)}
            unit=""
          />
          <MetricCard
            label="Best Bid"
            value={metrics.bestBid ? formatNumber(metrics.bestBid, 2) : '—'}
            unit=""
          />
          <MetricCard
            label="Best Ask"
            value={metrics.bestAsk ? formatNumber(metrics.bestAsk, 2) : '—'}
            unit=""
          />
          <MetricCard
            label="Bid Qty"
            value={metrics.bestBidQty ? formatNumber(metrics.bestBidQty, 4) : '—'}
            unit=""
          />
          <MetricCard
            label="Ask Qty"
            value={metrics.bestAskQty ? formatNumber(metrics.bestAskQty, 4) : '—'}
            unit=""
          />
          <MetricCard
            label="Book Valid"
            value={metrics.bookValid ? 'Yes' : 'No'}
            unit=""
            color={metrics.bookValid ? 'green' : 'red'}
          />
        </div>
      )}

      {/* Depth Visualization */}
      {metrics && metrics.depth && metrics.depth.length > 0 && (
        <div className="mb-6">
          <h3 className="text-sm font-medium text-surface-700 dark:text-surface-300 mb-3">Market Depth</h3>
          <div className="space-y-2">
            {metrics.depth.slice(0, 10).map((level, i) => (
              <DepthLevelBar key={i} level={level} maxQty={metrics.depth[0]?.bidQty ? 
                Math.max(...metrics.depth.map(d => parseFloat(d.bidQty || '0'), parseFloat(d.askQty || '0'))) : 1} />
            ))}
          </div>
        </div>
      )}

      {/* Connection & Performance Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 pt-4 border-t border-surface-200 dark:border-surface-800">
        <StatItem
          label="Messages/sec"
          value={formatNumber(performance.messageRate, 1)}
        />
        <StatItem
          label="FPS"
          value={formatNumber(performance.fps, 1)}
        />
        <StatItem
          label="Latency p50"
          value={`${latencyStats.p50.toFixed(1)} ms`}
        />
        <StatItem
          label="Latency p99"
          value={`${latencyStats.p99.toFixed(1)} ms`}
        />
      </div>
    </div>
  );
}

function MetricCard({ 
  label, 
  value, 
  sparkline, 
  unit, 
  trend,
  color 
}: { 
  label: string; 
  value: string; 
  sparkline?: number[]; 
  unit?: string; 
  trend?: 'up' | 'down';
  color?: 'green' | 'red';
}) {
  const canvasRef = React.useRef<HTMLCanvasElement>(null);
  
  React.useEffect(() => {
    if (!sparkline || !canvasRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.width = 100 * dpr;
    const h = canvas.height = 30 * dpr;
    ctx.scale(dpr, dpr);
    
    const min = Math.min(...sparkline);
    const max = Math.max(...sparkline);
    const range = max - min || 1;
    
    ctx.strokeStyle = color === 'green' ? '#22c55e' : color === 'red' ? '#ef4444' : trend === 'up' ? '#22c55e' : trend === 'down' ? '#ef4444' : '#3b82f6';
    ctx.lineWidth = 1.5;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    
    ctx.beginPath();
    sparkline.forEach((val, i) => {
      const x = (i / (sparkline.length - 1)) * 100;
      const y = 30 - ((val - min) / (max - min || 1)) * 30;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
  }, [sparkline, color, trend]);

  return (
    <div className="bg-surface-50 dark:bg-surface-800/50 rounded-lg p-4">
      <div className="flex items-baseline justify-between mb-1">
        <span className="text-xs text-surface-500 dark:text-surface-400">{label}</span>
        {unit && <span className="text-xs text-surface-400">{unit}</span>}
      </div>
      <div className="flex items-end justify-between">
        <span className={`text-2xl font-bold ${color === 'green' ? 'text-green-600' : color === 'red' ? 'text-red-600' : 'text-surface-900 dark:text-surface-50'}`}>
          {value}
        </span>
        {sparkline && <canvas ref={canvasRef} width="100" height="30" className="h-8 w-24" />}
      </div>
    </div>
  );
}

function MetricItem({ label, value, color }: { label: string; value: string; color?: 'green' | 'red' }) {
  return (
    <div className="text-center">
      <p className="text-xs text-surface-500 dark:text-surface-400">{label}</p>
      <p className={`text-lg font-bold ${color === 'green' ? 'text-green-600' : color === 'red' ? 'text-red-600' : 'text-surface-900 dark:text-surface-50'}`}>
        {value}
      </p>
    </div>
  );
}

function LatencyBadge() {
  const latencyStats = useMarketStore(selectLatencyStats);
  const connection = useMarketStore(selectConnectionStatus);
  
  if (!connection.isConnected && connection.status !== 'resyncing') return null;
  
  const statusColor = latencyStats.p99 < 50 ? 'green' : latencyStats.p99 < 100 ? 'yellow' : 'red';
  
  return (
    <div className={`flex items-center gap-1.5 px-2 py-1 rounded-full text-xs font-medium ${
      statusColor === 'green' ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400' :
      statusColor === 'yellow' ? 'bg-yellow-100 text-yellow-800' :
      'bg-red-100 text-red-800'
    }`}>
      <span className="w-1.5 h-1.5 rounded-full bg-current"></span>
      <span>p99: {latencyStats.p99.toFixed(1)}ms</span>
      <span className="text-surface-400">|</span>
      <span>p50: {latencyStats.p50.toFixed(1)}ms</span>
    </div>
  );
}

function StatItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="text-center p-3 bg-surface-50 dark:bg-surface-800/50 rounded-lg">
      <p className="text-xs text-surface-500 dark:text-surface-400">{label}</p>
      <p className="text-lg font-bold text-surface-900 dark:text-surface-50">{value}</p>
    </div>
  );
}

function DepthLevelBar({ level, maxQty }: { level: any; maxQty: number }) {
  const bidQty = parseFloat(level.bidQty || '0');
  const askQty = parseFloat(level.askQty || '0');
  const bidPct = maxQty > 0 ? (bidQty / maxQty) * 100 : 0;
  const askPct = maxQty > 0 ? (askQty / maxQty) * 100 : 0;
  
  return (
    <div className="flex items-center gap-2 text-xs font-mono">
      <span className="text-green-600 w-16 text-right">{bidQty.toFixed(2)}</span>
      <div className="flex-1 h-3 bg-surface-200 dark:bg-surface-700 rounded overflow-hidden relative">
        <div className="absolute left-0 top-0 h-full bg-green-500/30" style={{ width: `${bidPct}%` }} />
      </div>
      <div className="flex-1 h-3 bg-surface-200 dark:bg-surface-700 rounded overflow-hidden relative">
        <div className="absolute right-0 top-0 h-full bg-red-500/30" style={{ width: `${askPct}%` }} />
      </div>
      <span className="text-red-600 w-16">{askQty.toFixed(2)}</span>
    </div>
  );
}