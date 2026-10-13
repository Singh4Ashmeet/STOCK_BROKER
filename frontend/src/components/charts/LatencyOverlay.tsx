'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useMarketStore, selectLatencyStats, selectPerformanceMetrics, selectConnectionStatus } from '@/lib/store';

interface LatencyOverlayProps {
  position?: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left';
  showDetails?: boolean;
  compact?: boolean;
}

export function LatencyOverlay({ 
  position = 'top-right', 
  showDetails = true, 
  compact = false 
}: LatencyOverlayProps) {
  const latencyStats = useMarketStore(selectLatencyStats);
  const performance = useMarketStore(selectPerformanceMetrics);
  const connection = useMarketStore(selectConnectionStatus);
  const [expanded, setExpanded] = useState(false);
  const sparklineRef = useRef<HTMLCanvasElement>(null);
  const histogramRef = useRef<HTMLCanvasElement>(null);

  const positionClasses = {
    'top-right': 'top-4 right-4',
    'top-left': 'top-4 left-4',
    'bottom-right': 'bottom-4 right-4',
    'bottom-left': 'bottom-4 left-4',
  };

  const compactContent = compact ? (
      <div className={`fixed ${positionClasses[position]} z-50`}>
        <div className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-mono ${
          latencyStats.p99 < 50 ? 'bg-green-900/90 text-green-100' :
          latencyStats.p99 < 100 ? 'bg-yellow-900/90 text-yellow-100' :
          'bg-red-900/90 text-red-100'
        } backdrop-blur-sm border ${
          latencyStats.p99 < 50 ? 'border-green-500/30' :
          latencyStats.p99 < 100 ? 'border-yellow-500/30' :
          'border-red-500/30'
        }`}>
          <span className="flex items-center gap-1">
            <span className={`w-1.5 h-1.5 rounded-full animate-pulse ${
              latencyStats.p99 < 50 ? 'bg-green-400' :
              latencyStats.p99 < 100 ? 'bg-yellow-400' :
              'bg-red-400'
            }`}></span>
            <span>p99: {latencyStats.p99.toFixed(1)}ms</span>
            {showDetails && (
              <>
                <span className="text-surface-400">|</span>
                <span>p50: {latencyStats.p50.toFixed(1)}ms</span>
              </>
            )}
          </span>
        </div>
      </div>
  ) : null;

  const latencyHistory = useMemo(() => {
    // Generate recent latency samples for sparkline
    // In real app, this would come from a rolling buffer
    const samples = Array.from({ length: 60 }, (_, i) => {
      const base = latencyStats.avg || 10;
      const variance = (Math.random() - 0.5) * base * 0.5;
      return Math.max(0, base + variance);
    });
    return samples;
  }, [latencyStats.avg]);

  const connectionColor = connection.status === 'connected' ? 'green' :
    connection.status === 'connecting' ? 'yellow' :
    connection.status === 'resyncing' ? 'blue' : 'red';

  const statusIcon = connection.status === 'connected' ? '●' :
    connection.status === 'connecting' ? '◐' :
    connection.status === 'resyncing' ? '⟳' : '○';

  const fullContent = (
    <div className={`fixed ${positionClasses[position]} z-50`}>
      <div 
        className="bg-surface-950/95 dark:bg-surface-950/95 backdrop-blur-sm rounded-xl border shadow-xl overflow-hidden"
        style={{ borderColor: connectionColor === 'green' ? '#22c55e40' : connectionColor === 'yellow' ? '#eab30840' : connectionColor === 'blue' ? '#3b82f640' : '#ef444440' }}
        onMouseEnter={() => setExpanded(true)}
        onMouseLeave={() => setExpanded(false)}
      >
        {/* Header */}
        <div className="flex items-center justify-between p-3 border-b border-surface-800">
          <div className="flex items-center gap-2">
            <span className={`w-2 h-2 rounded-full animate-pulse ${connectionColor === 'green' ? 'bg-green-500' : connectionColor === 'yellow' ? 'bg-yellow-500' : connectionColor === 'blue' ? 'bg-blue-500' : 'bg-red-500'}`}></span>
            <span className="text-xs font-medium text-surface-300">Latency Monitor</span>
            {connection.status === 'resyncing' && (
              <span className="px-1.5 py-0.5 text-xs bg-blue-500/20 text-blue-400 rounded">RESYNCING</span>
            )}
          </div>
          <button
            onClick={() => setExpanded(!expanded)}
            className="p-1 text-surface-500 hover:text-surface-200 transition-colors"
            aria-label={expanded ? 'Collapse' : 'Expand'}
          >
            {expanded ? '▲' : '▼'}
          </button>
        </div>

        {/* Main Metrics */}
        <div className="px-3 pb-3">
          <div className="grid grid-cols-2 gap-2 mb-2">
            <StatBox 
              label="Fan-out p50" 
              value={`${latencyStats.p50.toFixed(1)}ms`} 
              unit="ms"
              color={latencyStats.p50 < 20 ? 'green' : latencyStats.p50 < 50 ? 'yellow' : 'red'}
            />
            <StatBox 
              label="Fan-out p95" 
              value={`${latencyStats.p95.toFixed(1)}ms`} 
              unit="ms"
              color={latencyStats.p95 < 50 ? 'green' : latencyStats.p95 < 100 ? 'yellow' : 'red'}
            />
            <StatBox 
              label="Fan-out p99" 
              value={`${latencyStats.p99.toFixed(1)}ms`} 
              unit="ms"
              color={latencyStats.p99 < 50 ? 'green' : latencyStats.p99 < 100 ? 'yellow' : 'red'}
            />
            <StatBox 
              label="Max" 
              value={`${latencyStats.max.toFixed(1)}ms`} 
              unit="ms"
              color='red'
            />
          </div>

          {showDetails && expanded && (
            <div className="space-y-3 border-t border-surface-800 pt-3">
              {/* Performance Metrics */}
              <div className="grid grid-cols-2 gap-2">
                <StatBox 
                  label="FPS" 
                  value={performance.fps.toFixed(1)} 
                  unit="fps"
                  color={performance.fps >= 55 ? 'green' : performance.fps >= 30 ? 'yellow' : 'red'}
                />
                <StatBox 
                  label="Msg Rate" 
                  value={performance.messageRate.toFixed(1)} 
                  unit="msg/s"
                  color={performance.messageRate > 100 ? 'green' : performance.messageRate > 50 ? 'yellow' : 'red'}
                />
                <StatBox 
                  label="Latency Avg" 
                  value={`${latencyStats.avg.toFixed(1)}ms`} 
                  unit="ms"
                  color='blue'
                />
                <StatBox 
                  label="Latency Max" 
                  value={`${latencyStats.max.toFixed(1)}ms`} 
                  unit="ms"
                  color='red'
                />
              </div>

              {/* Latency Sparkline */}
              <div className="h-24">
                <canvas 
                  ref={sparklineRef} 
                  width={300} 
                  height={80} 
                  className="w-full h-full" 
                  style={{ background: 'rgba(0,0,0,0.2)', borderRadius: '4px' }}
                />
              </div>

              {/* Connection Info */}
              <div className="grid grid-cols-3 gap-2 text-xs">
                <div className="text-center p-2 bg-surface-800/50 rounded">
                  <div className="text-surface-500">Status</div>
                  <div className={`font-mono font-medium ${connectionColor === 'green' ? 'text-green-400' : connectionColor === 'yellow' ? 'text-yellow-400' : connectionColor === 'blue' ? 'text-blue-400' : 'text-red-400'}`}>
                    {statusIcon} {connection.status}
                  </div>
                </div>
                <div className="text-center p-2 bg-surface-800/50 rounded">
                  <div className="text-surface-500">Reconnects</div>
                  <div className="font-mono font-medium text-surface-300">{connection.reconnectAttempts}</div>
                </div>
                <div className="text-center p-2 bg-surface-800/50 rounded">
                  <div className="text-surface-500">Samples</div>
                  <div className="font-mono font-medium text-surface-300">{latencyStats.count}</div>
                </div>
              </div>

              {/* Latency Distribution */}
              <div className="mt-2">
                <div className="text-xs text-surface-500 mb-1">Latency Distribution (fan-out)</div>
                <div className="h-16">
                  <canvas 
                    ref={histogramRef} 
                    width={300} 
                    height={64} 
                    className="w-full h-full" 
                  />
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );

  // Draw sparkline
  useEffect(() => {
    const canvas = sparklineRef.current;
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.width = 300 * dpr;
    const h = canvas.height = 80 * dpr;
    ctx.scale(dpr, dpr);
    
    // Clear
    ctx.fillStyle = 'rgba(0,0,0,0.3)';
    ctx.fillRect(0, 0, 300, 80);
    
    // Grid
    ctx.strokeStyle = 'rgba(113, 113, 122, 0.1)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = (i / 4) * 80;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(300, y);
      ctx.stroke();
    }
    
    // Sparkline
    const data = latencyHistory;
    const min = Math.min(...data);
    const max = Math.max(...data);
    const range = max - min || 1;
    
    const gradient = ctx.createLinearGradient(0, 0, 0, 80);
    gradient.addColorStop(0, 'rgba(34, 197, 94, 0.4)');
    gradient.addColorStop(1, 'rgba(34, 197, 94, 0)');
    
    ctx.strokeStyle = '#22c55e';
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    
    ctx.beginPath();
    data.forEach((val, i) => {
      const x = (i / (data.length - 1)) * 300;
      const y = 80 - ((val - min) / range) * 80;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
    
    // Fill
    ctx.lineTo(300, 80);
    ctx.lineTo(0, 80);
    ctx.closePath();
    ctx.fillStyle = gradient;
    ctx.fill();
  }, [latencyHistory]);

  // Draw histogram
  useEffect(() => {
    const canvas = histogramRef.current;
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.width = 300 * dpr;
    const h = canvas.height = 64 * dpr;
    ctx.scale(dpr, dpr);
    
    // Clear
    ctx.fillStyle = 'rgba(0,0,0,0.3)';
    ctx.fillRect(0, 0, 300, 64);
    
    // Generate histogram data from latency samples
    const samples = useMarketStore.getState().performance.latencySamples
      .map(s => s.fanOutLatency)
      .filter(l => l >= 0 && l < 200);
    
    if (samples.length === 0) return;
    
    const bins = 20;
    const maxLatency = Math.max(...samples);
    const binWidth = maxLatency / bins;
    const histogram = new Array(bins).fill(0);
    
    samples.forEach(lat => {
      const bin = Math.min(Math.floor(lat / binWidth), bins - 1);
      histogram[bin]++;
    });
    
    const maxCount = Math.max(...histogram);
    const barWidth = 300 / bins - 1;
    
    histogram.forEach((count, i) => {
      const height = (count / maxCount) * 56;
      const x = i * (barWidth + 1);
      const y = 64 - height;
      
      const hue = 120 - (i / bins) * 120; // Green to red
      ctx.fillStyle = `hsl(${hue}, 70%, 50%)`;
      ctx.fillRect(x, y, barWidth, height);
    });
  }, []);

  return compactContent ?? fullContent;
}

function StatBox({ label, value, unit, color }: { label: string; value: string; unit?: string; color: 'green' | 'yellow' | 'red' | 'blue' }) {
  const colorMap = {
    green: 'text-green-400',
    yellow: 'text-yellow-400',
    red: 'text-red-400',
    blue: 'text-blue-400',
  };
  
  return (
    <div className="text-center p-2 bg-surface-800/50 rounded">
      <div className="text-xs text-surface-500">{label}</div>
      <div className={`font-mono font-bold text-lg ${colorMap[color]}`}>
        {value} {unit ? <span className="text-xs text-surface-500 ml-1">{unit}</span> : null}
      </div>
    </div>
  );
}

const statusIcon = '●';
const connectionColor = 'green';
