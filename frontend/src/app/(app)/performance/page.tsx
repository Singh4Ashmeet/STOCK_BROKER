'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { useMarketStore, selectPerformanceMetrics, selectLatencyStats, selectConnectionStatus, selectFPS, selectMessageRate } from '@/lib/store';
import { useWebSocketWorker } from '@/lib/hooks/useWebSocketWorker';
import { LatencyOverlay } from '@/components/charts';

const PERF_TEST_SYMBOLS = ['BINANCE:BTC-USDT', 'BINANCE:ETH-USDT', 'BINANCE:SOL-USDT', 'BINANCE:BNB-USDT', 'BINANCE:ADA-USDT', 'BINANCE:DOT-USDT', 'BINANCE:LINK-USDT', 'BINANCE:MATIC-USDT'];
const NUM_SYMBOLS = 8;
const MSGS_PER_SYMBOL_PER_SEC = 100; // Simulated message rate

export default function PerformanceTestPage() {
  const { connect, disconnect, subscribe } = useWebSocketWorker();
  const fps = useMarketStore(selectFPS);
  const messageRate = useMarketStore(selectMessageRate);
  const latencyStats = useMarketStore(selectLatencyStats);
  const connectionStatus = useMarketStore(selectConnectionStatus);
  const performance = useMarketStore((state) => state.performance);
  
  const [testRunning, setTestRunning] = useState(false);
  const [testDuration, setTestDuration] = useState(60); // seconds
  const [results, setResults] = useState<PerformanceTestResult | null>(null);
  const [currentMetrics, setCurrentMetrics] = useState<FrameMetrics[]>([]);
  
  const frameTimesRef = useRef<number[]>([]);
  const messageCountRef = useRef(0);
  const lastMessageTimeRef = useRef(performance.now());
  const animationRef = useRef<number>();
  const testStartRef = useRef<number>(0);
  const testTimerRef = useRef<NodeJS.Timeout>();

  const connect = useCallback(async () => {
    try {
      const response = await fetch('/api/ws/ticket', {
        method: 'POST',
        credentials: 'include',
      });
      if (!response.ok) throw new Error('Failed to get ticket');
      const { ticket } = await response.json();
      // The worker will connect automatically
    } catch (error) {
      console.error('Connect error:', error);
    }
  }, []);

  const startTest = () => {
    setTestRunning(true);
    setResults(null);
    setCurrentMetrics([]);
    frameTimesRef.current = [];
    messageCountRef.current = 0;
    lastMessageTimeRef.current = performance.now();
    testStartRef.current = performance.now();
    
    // Subscribe to all test symbols
    PERF_TEST_SYMBOLS.slice(0, NUM_SYMBOLS).forEach(symbol => {
      subscribe('book', symbol, 20);
      subscribe('trades', symbol);
      subscribe('metrics', symbol);
    });
    
    // Start animation loop for frame timing
    let lastFrame = performance.now();
    const animate = (timestamp: number) => {
      const frameTime = timestamp - lastFrame;
      frameTimesRef.current.push(frameTime);
      if (frameTimesRef.current.length > 1000) frameTimesRef.current.shift();
      lastFrame = timestamp;
      
      if (testRunning) {
        animationRef.current = requestAnimationFrame(animate);
      }
    };
    animationRef.current = requestAnimationFrame(animate);
    
    // Auto-stop timer
    testTimerRef.current = setTimeout(() => {
      stopTest();
    }, testDuration * 1000);
  };

  const stopTest = () => {
    setTestRunning(false);
    if (animationRef.current) {
      cancelAnimationFrame(animationRef.current);
    }
    if (testTimerRef.current) {
      clearTimeout(testTimerRef.current);
    }
    
    // Calculate results
    const frameTimes = frameTimesRef.current;
    const totalTime = performance.now() - testStartRef.current;
    const avgFrameTime = frameTimes.reduce((a, b) => a + b, 0) / frameTimes.length;
    const avgFps = 1000 / avgFrameTime;
    const minFps = 1000 / Math.max(...frameTimes);
    const maxFps = 1000 / Math.min(...frameTimes);
    const frameTimeP99 = percentile(frameTimes, 99);
    const frameTimeP95 = percentile(frameTimes, 95);
    const frameTimeP50 = percentile(frameTimes, 50);
    
    const framesUnder16ms = frameTimes.filter(t => t <= 16.67).length;
    const framesUnder33ms = frameTimes.filter(t => t <= 33.33).length;
    
    const result: PerformanceTestResult = {
      duration: testDuration,
      totalFrames: frameTimes.length,
      avgFps,
      minFps,
      maxFps,
      avgFrameTime,
      frameTimeP50,
      frameTimeP95,
      frameTimeP99,
      framesUnder16ms: (framesUnder16ms / frameTimes.length) * 100,
      framesUnder33ms: (framesUnder33ms / frameTimes.length) * 100,
      passed60fps: avgFps >= 55 && frameTimeP99 <= 18,
      passed30fps: avgFps >= 28,
      latencyStats: { ...latencyStats },
      messageRate: messageRate,
      connectionStatus: connectionStatus.status,
    };
    
    setResults(result);
    console.log('Performance Test Results:', result);
  };

  useEffect(() => {
    connect();
    return () => {
      if (animationRef.current) cancelAnimationFrame(animationRef.current);
      if (testTimerRef.current) clearTimeout(testTimerRef.current);
    };
  }, [connect]);

  // Record frame metrics periodically
  useEffect(() => {
    if (!testRunning) return;
    const interval = setInterval(() => {
      setCurrentMetrics(prev => [
        ...prev.slice(-599),
        {
          timestamp: performance.now(),
          fps: fps,
          messageRate: messageRate,
          latencyP50: latencyStats.p50,
          latencyP95: latencyStats.p95,
          latencyP99: latencyStats.p99,
        }
      ]);
    }, 1000);
    return () => clearInterval(interval);
  }, [testRunning, fps, messageRate, latencyStats]);

  const timeRemaining = testRunning 
    ? Math.max(0, testDuration - (performance.now() - testStartRef.current) / 1000)
    : 0;

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-950 p-4 sm:p-6 lg:p-8">
      <LatencyOverlay position="top-right" showDetails={false} compact />
      
      <div className="max-w-6xl mx-auto">
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-surface-900 dark:text-surface-50 mb-2">
            60 FPS Performance Test
          </h1>
          <p className="text-surface-600 dark:text-surface-400">
            Stress test the WebSocket fan-out and rendering pipeline under full-rate market data stream.
          </p>
        </div>

        {/* Control Panel */}
        <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6 mb-6">
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-6">
            <div className="flex items-center gap-4">
              <div className={`flex items-center gap-2 px-3 py-1.5 rounded-lg ${
                connectionStatus.status === 'connected' ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400' :
                connectionStatus.status === 'connecting' ? 'bg-yellow-100 text-yellow-800' :
                connectionStatus.status === 'resyncing' ? 'bg-blue-100 text-blue-800' :
                'bg-red-100 text-red-800'
              }`}>
                <span className={`w-2 h-2 rounded-full animate-pulse ${
                  connectionStatus.status === 'connected' ? 'bg-green-500' :
                  connectionStatus.status === 'connecting' ? 'bg-yellow-500' :
                  connectionColor === 'blue' ? 'bg-blue-500' :
                  'bg-red-500'
                }`}></span>
                <span className="font-medium capitalize">{connectionStatus.status}</span>
              </div>
              <div className="flex items-center gap-4 text-sm text-surface-600 dark:text-surface-400">
                <span>Symbols: <span className="font-mono font-bold">{NUM_SYMBOLS}</span></span>
                <span>Test Duration: <span className="font-mono font-bold">{testDuration}s</span></span>
                <span>Target: <span className="font-mono font-bold text-green-600">60 FPS</span></span>
              </div>
            </div>
            <div className="flex flex-wrap gap-3">
              <button
                onClick={startTest}
                disabled={testRunning || connectionStatus.status !== 'connected'}
                className={`px-6 py-3 rounded-lg text-lg font-medium transition-colors ${
                  testRunning 
                    ? 'bg-surface-200 text-surface-500 cursor-not-allowed' 
                    : 'bg-primary-600 text-white hover:bg-primary-700'
                }`}
              >
                {testRunning ? 'Test Running...' : 'Start Performance Test'}
              </button>
              <button
                onClick={() => setTestDuration(d => d === 60 ? 120 : d === 120 ? 300 : 60)}
                className="px-4 py-3 rounded-lg bg-surface-100 dark:bg-surface-800 text-surface-700 dark:text-surface-300 hover:bg-surface-200 dark:hover:bg-surface-700"
              >
                Duration: {testDuration}s
              </button>
              <button
                onClick={() => setResults(null)}
                className="px-4 py-3 rounded-lg bg-surface-100 dark:bg-surface-800 text-surface-700 dark:text-surface-300 hover:bg-surface-200 dark:hover:bg-surface-700"
              >
                Clear Results
              </button>
            </div>
          </div>
        </div>

        {/* Live Metrics */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          <MetricBox 
            label="Current FPS" 
            value={fps.toFixed(1)} 
            unit="fps"
            color={fps >= 55 ? 'green' : fps >= 30 ? 'yellow' : 'red'}
            trend={fps >= 55 ? '✓ Target met' : fps >= 30 ? '⚠ Below 60' : '✗ Below 30'}
          />
          <MetricBox 
            label="Avg Frame Time" 
            value={results ? results.avgFrameTime.toFixed(2) : (1000 / (fps || 1)).toFixed(2)} 
            unit="ms"
            color={fps >= 55 ? 'green' : fps >= 30 ? 'yellow' : 'red'}
          />
          <MetricBox 
            label="Msg Rate" 
            value={messageRate.toFixed(1)} 
            unit="msg/s"
            color={messageRate > 500 ? 'green' : messageRate > 100 ? 'yellow' : 'red'}
          />
          <MetricBox 
            label="Latency p99" 
            value={`${latencyStats.p99.toFixed(1)}ms`} 
            unit="ms"
            color={latencyStats.p99 < 50 ? 'green' : latencyStats.p99 < 100 ? 'yellow' : 'red'}
          />
        </div>

        {/* Test Progress */}
        {testRunning && (
          <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6 mb-6">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50">
                Test in Progress
              </h3>
              <div className="text-lg font-mono font-bold text-primary-600">
                {Math.ceil(timeRemaining)}s remaining
              </div>
            </div>
            <div className="h-4 bg-surface-200 dark:bg-surface-700 rounded-full overflow-hidden">
              <div 
                className="h-full bg-primary-600 rounded-full transition-all duration-300"
                style={{ width: `${((testDuration - timeRemaining) / testDuration) * 100}%` }}
              />
            </div>
            <div className="mt-2 flex justify-between text-sm text-surface-500">
              <span>Frames: <span className="font-mono">{currentMetrics.length}</span></span>
              <span>Avg FPS: <span className="font-mono">{fps.toFixed(1)}</span></span>
              <span>Frame Time: <span className="font-mono">{(1000 / (fps || 1)).toFixed(2)}ms</span></span>
            </div>
          </div>
        )}

        {/* Results */}
        {results && (
          <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6 mb-6">
            <div className="flex items-center justify-between mb-6">
              <h3 className="text-xl font-semibold text-surface-900 dark:text-surface-50">
                Test Results
              </h3>
              <div className={`px-4 py-2 rounded-lg text-lg font-bold ${
                results.passed60fps ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400' :
                results.passed30fps ? 'bg-yellow-100 text-yellow-800' : 'bg-red-100 text-red-800'
              }`}>
                {results.passed60fps ? '✓ PASSED 60 FPS' : results.passed30fps ? '⚠ PASSED 30 FPS' : '✗ FAILED'}
              </div>
            </div>
            
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
              <ResultMetric label="Avg FPS" value={results.avgFps.toFixed(1)} target="≥ 55" pass={results.avgFps >= 55} />
              <ResultMetric label="Min FPS" value={results.minFps.toFixed(1)} target="≥ 30" pass={results.minFps >= 30} />
              <ResultMetric label="Frame Time p50" value={`${results.frameTimeP50.toFixed(2)}ms`} target="≤ 16.67ms" pass={results.frameTimeP50 <= 16.67} />
              <ResultMetric label="Frame Time p99" value={`${results.frameTimeP99.toFixed(2)}ms`} target="≤ 18ms" pass={results.frameTimeP99 <= 18} />
              <ResultMetric label="Frames < 16.67ms" value={`${results.framesUnder16ms.toFixed(1)}%`} target="≥ 95%" pass={results.framesUnder16ms >= 95} />
              <ResultMetric label="Frames < 33.33ms" value={`${results.framesUnder33ms.toFixed(1)}%`} target="≥ 99%" pass={results.framesUnder33ms >= 99} />
              <ResultMetric label="Latency p99" value={`${results.latencyStats.p99.toFixed(1)}ms`} target="< 50ms" pass={results.latencyStats.p99 < 50} />
              <ResultMetric label="Latency p50" value={`${results.latencyStats.p50.toFixed(1)}ms`} target="< 20ms" pass={results.latencyStats.p50 < 20} />
            </div>

            <div className="p-4 bg-surface-50 dark:bg-surface-800/50 rounded-lg">
              <h4 className="font-semibold text-surface-900 dark:text-surface-50 mb-2">Criteria</h4>
              <ul className="space-y-1 text-sm text-surface-600 dark:text-surface-400">
                <li className={`flex items-center gap-2 ${results.avgFps >= 55 ? 'text-green-600' : 'text-red-600'}`}>
                  {results.avgFps >= 55 ? '✓' : '✗'} Average FPS ≥ 55 (current: {results.avgFps.toFixed(1)})
                </li>
                <li className={`flex items-center gap-2 ${results.frameTimeP99 <= 18 ? 'text-green-600' : 'text-red-600'}`}>
                  {results.frameTimeP99 <= 18 ? '✓' : '✗'} Frame time p99 ≤ 18ms (current: {results.frameTimeP99.toFixed(2)}ms)
                </li>
                <li className={`flex items-center gap-2 ${results.framesUnder16ms >= 95 ? 'text-green-600' : 'text-red-600'}`}>
                  {results.framesUnder16ms >= 95 ? '✓' : '✗'} ≥95% frames under 16.67ms (current: {results.framesUnder16ms.toFixed(1)}%)
                </li>
                <li className={`flex items-center gap-2 ${results.latencyStats.p99 < 50 ? 'text-green-600' : 'text-red-600'}`}>
                  {results.latencyStats.p99 < 50 ? '✓' : '✗'} Fan-out latency p99 < 50ms (current: {results.latencyStats.p99.toFixed(1)}ms)
                </li>
              </ul>
            </div>
          </div>
        )}

        {/* FPS Timeline Chart */}
        {currentMetrics.length > 0 && (
          <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6">
            <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50 mb-4">FPS Timeline</h3>
            <FpsChart data={currentMetrics} height={200} />
          </div>
        )}
      </div>
    </div>
  );
}

function MetricBox({ label, value, unit, color, trend }: { 
  label: string; 
  value: string; 
  unit?: string; 
  color: 'green' | 'yellow' | 'red';
  trend?: string;
}) {
  const colorMap = {
    green: 'text-green-400',
    yellow: 'text-yellow-400',
    red: 'text-red-400',
  };
  
  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
      <p className="text-xs text-surface-500 dark:text-surface-400">{label}</p>
      <div className="flex items-baseline justify-between mt-1">
        <span className={`font-mono font-bold text-2xl ${colorMap[color]}`}>
          {value} {unit ? <span className="text-xs text-surface-500 ml-1">{unit}</span> : null}
        </span>
        {trend && <span className={`text-xs ${color === 'green' ? 'text-green-600' : color === 'yellow' ? 'text-yellow-600' : 'text-red-600'}`}>{trend}</span>}
      </div>
    </div>
  );
}

function ResultMetric({ label, value, target, pass }: { label: string; value: string; target: string; pass: boolean }) {
  return (
    <div className={`p-4 rounded-lg ${pass ? 'bg-green-50 dark:bg-green-900/20' : 'bg-red-50 dark:bg-red-900/20'} border ${pass ? 'border-green-200 dark:border-green-800' : 'border-red-200 dark:border-red-800'}`}>
      <p className="text-xs text-surface-500 dark:text-surface-400">{label}</p>
      <p className="font-mono font-bold text-lg text-surface-900 dark:text-surface-50">{value}</p>
      <p className="text-xs text-surface-500 dark:text-surface-400">Target: {target}</p>
      <div className="flex items-center gap-1 mt-1">
        <span className={pass ? 'text-green-600' : 'text-red-600'}>
          {pass ? '✓ PASS' : '✗ FAIL'}
        </span>
      </div>
    </div>
  );
}

function FpsChart({ data, height }: { data: FrameMetrics[]; height: number }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.width = canvas.clientWidth * dpr;
    const h = canvas.height = height * dpr;
    ctx.scale(dpr, dpr);
    const cw = canvas.clientWidth;
    const ch = height;
    
    // Clear
    ctx.fillStyle = '#0f0f0f';
    ctx.fillRect(0, 0, cw, ch);
    
    if (data.length < 2) return;
    
    // Extract FPS values
    const fpsValues = data.map(d => d.fps);
    const minFps = Math.min(...fpsValues);
    const maxFps = Math.max(...fpsValues);
    const range = maxFps - minFps || 1;
    
    // Grid
    ctx.strokeStyle = 'rgba(113, 113, 122, 0.1)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = (i / 4) * height;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(cw, y);
      ctx.stroke();
      
      const fps = maxFps - (i / 4) * range;
      ctx.fillStyle = '#71717a';
      ctx.font = '10px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(`${fps.toFixed(0)}`, 4, y + 3);
    }
    
    // 60 FPS target line
    const targetY = height - ((60 - minFps) / range) * height;
    if (targetY >= 0 && targetY <= height) {
      ctx.strokeStyle = 'rgba(34, 197, 94, 0.5)';
      ctx.setLineDash([4, 4]);
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, targetY);
      ctx.lineTo(cw, targetY);
      ctx.stroke();
      ctx.setLineDash([]);
      
      ctx.fillStyle = '#22c55e';
      ctx.font = '10px Inter, system-ui';
      ctx.fillText('60 FPS target', cw - 80, targetY - 4);
    }
    
    // FPS line
    const gradient = ctx.createLinearGradient(0, 0, 0, height);
    gradient.addColorStop(0, 'rgba(34, 197, 94, 0.4)');
    gradient.addColorStop(1, 'rgba(34, 197, 94, 0)');
    
    ctx.strokeStyle = '#22c55e';
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    
    ctx.beginPath();
    data.forEach((point, i) => {
      const x = (i / (data.length - 1)) * cw;
      const y = height - ((point.fps - Math.min(...data.map(d => d.fps))) / 
        (Math.max(...data.map(d => d.fps)) - Math.min(...data.map(d => d.fps)) || 1)) * cw;
      if (i === 0) ctx.moveTo(i / (data.length - 1) * cw, height - ((point.fps - Math.min(...data.map(d => d.fps))) / 
        (Math.max(...data.map(d => d.fps)) - Math.min(...data.map(d => d.fps)) || 1)) * cw);
      else ctx.lineTo(i / (data.length - 1) * cw, height - ((point.fps - Math.min(...data.map(d => d.fps))) / 
        (Math.max(...data.map(d => d.fps)) - Math.min(...data.map(d => d.fps)) || 1)) * cw);
    });
    ctx.stroke();
    
    // Fill
    ctx.lineTo(cw, height);
    ctx.lineTo(0, height);
    ctx.closePath();
    const gradient2 = ctx.createLinearGradient(0, 0, 0, height);
    gradient2.addColorStop(0, 'rgba(34, 197, 94, 0.3)');
    gradient2.addColorStop(1, 'rgba(34, 197, 94, 0)');
    ctx.fillStyle = gradient2;
    ctx.fill();
  }, [currentMetrics]);

  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6">
      <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50 mb-4">FPS Timeline</h3>
      <canvas 
        ref={canvasRef} 
        width={800} 
        height={200} 
        className="w-full h-64" 
        style={{ background: '#0f0f0f', borderRadius: '4px' }}
      />
    </div>
  );
}

function percentile(arr: number[], p: number): number {
  if (arr.length === 0) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  const idx = Math.ceil(p / 100 * arr.length) - 1;
  return sorted[Math.max(0, Math.min(idx, arr.length - 1))];
}

const connectionColor = 'green';