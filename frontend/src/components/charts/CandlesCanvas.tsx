'use client';

import { useEffect, useRef, useMemo, useCallback, useState } from 'react';
import { useMarketStore, selectTrades } from '@/lib/store';
import type { Trade } from '@/lib/api';

interface OHLC {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

interface CandlesCanvasProps {
  symbol: string;
  height?: number;
  timeframe?: '1m' | '5m' | '15m' | '1h' | '4h' | '1d';
  maxCandles?: number;
  width?: number;
  showVolume?: boolean;
}

export function CandlesCanvas({
  symbol,
  height = 400,
  timeframe = '5m',
  maxCandles = 100,
  width,
  showVolume = true,
}: CandlesCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animationRef = useRef<number>();
  const trades = useMarketStore(selectTrades(symbol));
  const [timeframeState, setTimeframeState] = useState(timeframe);
  const [crosshair, setCrosshair] = useState<{ x: number; y: number; candle: OHLC | null } | null>(null);

  // Generate OHLC candles from trades
  const candles = useMemo(() => {
    if (!trades.length) return [];
    
    const intervalMs = timeframeToMs(timeframeState);
    const candleMap = new Map<number, OHLC>();
    
    // Process trades (newest first, but we need chronological)
    const sortedTrades = [...trades].reverse();
    
    sortedTrades.forEach(trade => {
      const tradeTime = Number(trade.timing.recv_ts_ns) / 1_000_000;
      const bucket = Math.floor(tradeTime / intervalMs) * intervalMs;
      const price = parseFloat(trade.price);
      const qty = parseFloat(trade.qty);
      
      const existing = candleMap.get(bucket);
      if (existing) {
        existing.high = Math.max(existing.high, price);
        existing.low = Math.min(existing.low, price);
        existing.close = price;
        existing.volume += qty;
      } else {
        candleMap.set(bucket, {
          time: bucket,
          open: price,
          high: price,
          low: price,
          close: price,
          volume: qty,
        });
      }
    });
    
    // Convert to sorted array
    return Array.from(candleMap.values())
      .sort((a, b) => a.time - b.time)
      .slice(-maxCandles);
  }, [trades, timeframeState, maxCandles]);

  const timeToX = useCallback((index: number): number => {
    const canvas = canvasRef.current;
    if (!canvas) return 0;
    const leftPad = 60;
    const rightPad = 60;
    const plotWidth = canvas.width - leftPad - rightPad;
    const candleSpacing = 2;
    const availableWidth = plotWidth;
    const candleWidth = Math.max(1, (availableWidth / candles.length) - candleSpacing);
    return leftPad + index * (candleWidth + candleSpacing) + candleWidth / 2;
  }, [candles.length]);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d', { alpha: false });
    if (!ctx) return;
    
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    const w = rect.width * dpr;
    const h = rect.height * dpr;
    
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    
    ctx.scale(dpr, dpr);
    const cw = rect.width;
    const ch = rect.height;
    
    // Clear
    ctx.fillStyle = '#0f0f0f';
    ctx.fillRect(0, 0, cw, ch);
    
    if (candles.length === 0) {
      ctx.fillStyle = '#52525b';
      ctx.font = '14px Inter, system-ui';
      ctx.textAlign = 'center';
      ctx.fillText(`Loading ${timeframeState} candles...`, cw / 2, ch / 2);
      return;
    }
    
    // Colors
    const upColor = '#22c55e';
    const downColor = '#ef4444';
    const upFill = 'rgba(34, 197, 94, 0.8)';
    const downFill = 'rgba(239, 68, 68, 0.8)';
    const upWick = '#22c55e';
    const downWick = '#ef4444';
    const volumeUpFill = 'rgba(34, 197, 94, 0.3)';
    const volumeDownFill = 'rgba(239, 68, 68, 0.3)';
    const gridColor = 'rgba(113, 113, 122, 0.1)';
    const textColor = '#e4e4e7';
    const dimColor = '#71717a';
    
    // Layout
    const padding = 10;
    const volumeHeight = showVolume ? 60 : 0;
    const priceHeight = ch - 40 - volumeHeight;
    const leftPad = 60;
    const rightPad = 60;
    const topPad = 30;
    const bottomPad = 10;
    const plotWidth = cw - leftPad - rightPad;
    const priceTop = topPad;
    const priceBottom = topPad + priceHeight;
    const volumeTop = priceBottom + 5;
    const volumeBottom = ch - bottomPad;
    
    // Price range
    const allPrices = candles.flatMap(c => [c.high, c.low]);
    const minPrice = Math.min(...allPrices);
    const maxPrice = Math.max(...allPrices);
    const priceRange = maxPrice - minPrice || 1;
    
    // Volume range
    const maxVolume = Math.max(...candles.map(c => c.volume));
    
    // Candle width
    const candleSpacing = 2;
    const availableWidth = plotWidth;
    const candleWidth = Math.max(1, (availableWidth / candles.length) - candleSpacing);
    
    // Price to Y
    const priceToY = (price: number) => {
      return topPad + (1 - (price - minPrice) / priceRange) * priceHeight;
    };
    
    // Time to X
    const timeToX = (index: number) => {
      return leftPad + index * (candleWidth + candleSpacing) + candleWidth / 2;
    };
    
    // Draw grid
    ctx.strokeStyle = gridColor;
    ctx.lineWidth = 1;
    
    // Horizontal grid lines
    for (let i = 0; i <= 4; i++) {
      const y = topPad + (i / 4) * priceHeight;
      ctx.beginPath();
      ctx.moveTo(leftPad, y);
      ctx.lineTo(cw - rightPad, y);
      ctx.stroke();
      
      const price = maxPrice - (i / 4) * (maxPrice - minPrice);
      ctx.fillStyle = '#71717a';
      ctx.font = '10px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(price.toFixed(2), 4, y + 3);
      ctx.textAlign = 'right';
      ctx.fillText(price.toFixed(2), cw - 4, y + 3);
    }
    
    // Volume grid
    if (showVolume) {
      for (let i = 0; i <= 2; i++) {
        const y = volumeTop + (i / 2) * (volumeBottom - volumeTop);
        ctx.beginPath();
        ctx.moveTo(leftPad, y);
        ctx.lineTo(cw - rightPad, y);
        ctx.stroke();
        
        const vol = maxVolume - (i / 2) * maxVolume;
        ctx.fillStyle = '#71717a';
        ctx.font = '9px JetBrains Mono, monospace';
        ctx.textAlign = 'left';
        ctx.fillText(formatVolume(vol), 4, y + 3);
      }
    }
    
    // Draw candles
    candles.forEach((candle, i) => {
      const x = timeToX(i);
      const isUp = candle.close >= candle.open;
      const color = isUp ? upColor : downColor;
      const fillColor = isUp ? upFill : downFill;
      const wickColor = isUp ? upWick : downWick;
      
      const openY = priceToY(candle.open);
      const closeY = priceToY(candle.close);
      const highY = priceToY(candle.high);
      const lowY = priceToY(candle.low);
      const bodyTop = Math.min(openY, closeY);
      const bodyHeight = Math.max(1, Math.abs(openY - closeY));
      
      // Wick
      ctx.strokeStyle = wickColor;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x, highY);
      ctx.lineTo(x, lowY);
      ctx.stroke();
      
      // Body
      ctx.fillStyle = fillColor;
      ctx.beginPath();
      ctx.roundRect(x - candleWidth / 2, bodyTop, candleWidth, bodyHeight, 1);
      ctx.fill();
      
      // Volume bar
      if (showVolume) {
        const volHeight = (candle.volume / maxVolume) * (volumeBottom - volumeTop);
        ctx.fillStyle = isUp ? volumeUpFill : volumeDownFill;
        ctx.fillRect(
          x - candleWidth / 2,
          volumeBottom - volHeight,
          candleWidth - candleSpacing,
          volHeight
        );
      }
    });
    
    // Crosshair
    if (crosshair) {
      ctx.strokeStyle = 'rgba(113, 113, 122, 0.5)';
      ctx.setLineDash([4, 4]);
      ctx.lineWidth = 1;
      
      // Vertical line
      ctx.beginPath();
      ctx.moveTo(crosshair.x, topPad);
      ctx.lineTo(crosshair.x, priceBottom);
      ctx.stroke();
      
      // Horizontal line
      ctx.beginPath();
      ctx.moveTo(leftPad, crosshair.y);
      ctx.lineTo(cw - rightPad, crosshair.y);
      ctx.stroke();
      
      ctx.setLineDash([]);
      
      // Crosshair tooltip
      if (crosshair.candle) {
        const c = crosshair.candle;
        const tooltipX = crosshair.x + 10;
        const tooltipY = crosshair.y - 60;
        const tooltipW = 180;
        const tooltipH = 80;
        
        // Background
        ctx.fillStyle = 'rgba(15, 15, 15, 0.95)';
        ctx.beginPath();
        ctx.roundRect(tooltipX, tooltipY, tooltipW, tooltipH, 6);
        ctx.fill();
        
        // Border
        ctx.strokeStyle = '#3f3f46';
        ctx.lineWidth = 1;
        ctx.stroke();
        
        // Text
        ctx.fillStyle = '#e4e4e7';
        ctx.font = '11px JetBrains Mono, monospace';
        ctx.textAlign = 'left';
        const lines = [
          `O: ${c.open.toLocaleString()}`,
          `H: ${c.high.toLocaleString()}`,
          `L: ${c.low.toLocaleString()}`,
          `C: ${c.close.toLocaleString()}`,
          `V: ${formatVolume(c.volume)}`,
        ];
        lines.forEach((line, i) => {
          ctx.fillText(line, tooltipX + 10, tooltipY + 16 + i * 14);
        });
      }
      
      // Price label on right axis
      ctx.fillStyle = '#3b82f6';
      ctx.font = 'bold 11px JetBrains Mono, monospace';
      ctx.textAlign = 'right';
      const priceY = crosshair.y;
      const price = maxPrice - (crosshair.y - topPad) / priceHeight * (maxPrice - minPrice);
      ctx.fillText(price.toLocaleString(), cw - rightPad - 4, crosshair.y + 4);
    }
    
    // Timeframe label
    ctx.fillStyle = '#71717a';
    ctx.font = '11px Inter, system-ui';
    ctx.textAlign = 'left';
    ctx.fillText(timeframeState.toUpperCase(), leftPad, topPad - 8);
    
    // Price scale labels
    ctx.fillStyle = '#71717a';
    ctx.font = '10px JetBrains Mono, monospace';
    ctx.textAlign = 'left';
    for (let i = 0; i <= 4; i++) {
      const y = topPad + (i / 4) * priceHeight;
      const price = maxPrice - (i / 4) * priceRange;
      ctx.fillText(price.toFixed(2), 4, y + 3);
    }
    
  }, [candles, crosshair, timeframeState, showVolume]);

  // Animation loop
  useEffect(() => {
    let lastFrame = 0;
    
    const animate = (timestamp: number) => {
      if (timestamp - lastFrame >= 16.67) {
        lastFrame = timestamp;
        draw();
        useMarketStore.getState().recordFrame(timestamp);
      }
      animationRef.current = requestAnimationFrame(animate);
    };
    
    animationRef.current = requestAnimationFrame(animate);
    
    return () => {
      if (animationRef.current) {
        cancelAnimationFrame(animationRef.current);
      }
    };
  }, [draw]);

  // Mouse handlers
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      
      const padding = 10;
      const volumeHeight = showVolume ? 60 : 0;
      const priceHeight = canvas.height - 40 - (showVolume ? 60 : 0);
      const leftPad = 60;
      const rightPad = 60;
      const topPad = 30;
      const plotWidth = canvas.width - leftPad - rightPad;
      const topPadAdj = topPad;
      
      if (x >= 60 && x <= canvas.width - 60 && y >= 30 && y <= canvas.height - 70) {
        const index = Math.round((x - 60) / (plotWidth / candles.length));
        const clampedIndex = Math.max(0, Math.min(candles.length - 1, index));
        setCrosshair({
          x: timeToX(clampedIndex),
          y,
          candle: candles[clampedIndex],
        });
      } else {
        setCrosshair(null);
      }
    };
    
    const handleMouseLeave = () => {
      setCrosshair(null);
    };
    
    const canvasEl = canvasRef.current;
    if (canvasEl) {
      canvasEl.addEventListener('mousemove', handleMouseMove as any);
      canvasEl.addEventListener('mouseleave', handleMouseLeave);
    }
    
    return () => {
      if (canvasEl) {
        canvasEl.removeEventListener('mousemove', handleMouseMove as any);
        canvasEl.removeEventListener('mouseleave', handleMouseLeave);
      }
    };
  }, [candles, showVolume, timeToX]);

  // Resize observer
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const resizeObserver = new ResizeObserver(() => draw());
    resizeObserver.observe(canvas);
    
    return () => resizeObserver.disconnect();
  }, [draw]);

  // Helper functions
  function timeframeToMs(tf: string): number {
    const map: Record<string, number> = {
      '1m': 60_000,
      '5m': 300_000,
      '15m': 900_000,
      '1h': 3_600_000,
      '4h': 14_400_000,
      '1d': 86_400_000,
    };
    return map[tf] || 300_000;
  }
  
  function formatVolume(vol: number): string {
    if (vol >= 1e9) return (vol / 1e9).toFixed(2) + 'B';
    if (vol >= 1e6) return (vol / 1e6).toFixed(2) + 'M';
    if (vol >= 1e3) return (vol / 1e3).toFixed(2) + 'K';
    return vol.toFixed(2);
  }

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%' }}>
      <canvas
        ref={canvasRef}
        width={width}
        height={height}
        style={{
          width: '100%',
          height: '100%',
          display: 'block',
          borderRadius: '8px',
          background: '#0f0f0f',
          cursor: 'crosshair',
        }}
        aria-label={`${timeframeState} candles for ${symbol}`}
        onMouseMove={(e) => {
          const rect = e.currentTarget.getBoundingClientRect();
          const x = e.clientX - rect.left;
          const y = e.clientY - rect.top;
          // Crosshair handled in effect
        }}
        onMouseLeave={() => setCrosshair(null)}
      />
      <div className="absolute top-2 right-2 flex gap-1">
        {(['1m', '5m', '15m', '1h', '4h', '1d'] as const).map(tf => (
          <button
            key={tf}
            onClick={() => setTimeframeState(tf)}
            className={`px-2 py-1 text-xs rounded transition-colors ${
              timeframeState === tf
                ? 'bg-primary-600 text-white'
                : 'bg-surface-800 text-surface-400 hover:bg-surface-700'
            }`}
          >
            {tf}
          </button>
        ))}
      </div>
    </div>
  );
}

function formatVolume(vol: number): string {
  if (vol >= 1e9) return (vol / 1e9).toFixed(2) + 'B';
  if (vol >= 1e6) return (vol / 1e6).toFixed(2) + 'M';
  if (vol >= 1e3) return (vol / 1e3).toFixed(2) + 'K';
  return vol.toFixed(2);
}