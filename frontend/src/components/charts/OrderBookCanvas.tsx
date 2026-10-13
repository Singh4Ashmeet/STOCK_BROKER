'use client';

import { useEffect, useRef, useMemo, useCallback } from 'react';
import { useMarketStore, selectBook } from '@/lib/store';
import type { Level } from '@/lib/api';

interface OrderBookCanvasProps {
  symbol: string;
  height?: number;
  maxLevels?: number;
  showSpread?: boolean;
  width?: number;
}

export function OrderBookCanvas({
  symbol,
  height = 400,
  maxLevels = 25,
  showSpread = true,
  width,
}: OrderBookCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animationRef = useRef<number>();
  const lastRenderRef = useRef(0);
  const book = useMarketStore(selectBook(symbol));
  const isResyncing = useMarketStore((state) => state.isResyncing(symbol));

  // Pre-compute render data
  const renderData = useMemo(() => {
    if (!book) return null;
    
    const bids = book.bids.slice(0, maxLevels);
    const asks = book.asks.slice(0, maxLevels);
    
    const maxBidQty = Math.max(...bids.map(b => parseFloat(b.qty)), 1);
    const maxAskQty = Math.max(...asks.map(a => parseFloat(a.qty)), 1);
    const maxQty = Math.max(maxBidQty, maxAskQty);
    
    const bestBid = bids[0] ? parseFloat(bids[0].price) : 0;
    const bestAsk = asks[0] ? parseFloat(asks[0].price) : 0;
    const spread = bestBid && bestAsk ? ((bestAsk - bestBid) / bestBid * 10000).toFixed(1) : '0';
    const mid = bestBid && bestAsk ? (bestBid + bestAsk) / 2 : 0;
    
    return {
      bids,
      asks,
      maxQty,
      bestBid,
      bestAsk,
      spread,
      mid,
      bookValid: book.bookValid,
      epoch: book.epoch,
      seq: book.seq,
      resyncing: book.resyncing,
    };
  }, [book]);

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
    
    const data = renderData;
    if (!data) {
      // Loading state
      ctx.fillStyle = '#52525b';
      ctx.font = '14px Inter, system-ui';
      ctx.textAlign = 'center';
      ctx.fillText('Loading order book...', cw / 2, ch / 2);
      return;
    }
    
    const { bids, asks, maxQty, bestBid, bestAsk, spread, mid, bookValid, resyncing } = data;
    
    // Colors
    const bidColor = '#22c55e';
    const askColor = '#ef4444';
    const midColor = '#3b82f6';
    const textColor = '#e4e4e7';
    const dimColor = '#71717a';
    const gridColor = 'rgba(113, 113, 122, 0.15)';
    const spreadBgColor = 'rgba(59, 130, 246, 0.1)';
    
    // Layout
    const padding = 10;
    const labelWidth = 80;
    const barHeight = Math.max(12, (ch - padding * 2) / (max(bids.length, asks.length) + 2));
    const spreadCenter = cw / 2;
    const maxBarWidth = (cw - labelWidth * 2 - padding * 2) / 2;
    
    // Draw spread zone
    if (showSpread && bestBid && bestAsk) {
      const bidX = spreadCenter - (bestBid - mid) / (mid * 0.02) * maxBarWidth;
      const askX = spreadCenter + (bestAsk - mid) / (mid * 0.02) * maxBarWidth;
      
      ctx.fillStyle = spreadBgColor;
      ctx.fillRect(bidX, padding, askX - bidX, ch - padding * 2);
      
      // Mid price line
      ctx.strokeStyle = midColor;
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(spreadCenter, padding);
      ctx.lineTo(spreadCenter, ch - padding);
      ctx.stroke();
      ctx.setLineDash([]);
    }
    
    // Draw grid lines
    ctx.strokeStyle = gridColor;
    ctx.lineWidth = 1;
    const priceRange = asks.length > 0 && bids.length > 0
      ? (parseFloat(asks[0].price) - parseFloat(bids[bids.length - 1].price))
      : 1;
    const priceStep = priceRange / 5;
    const midPrice = (bestBid + bestAsk) / 2;
    
    for (let i = -2; i <= 2; i++) {
      const price = midPrice + i * priceStep;
      const y = padding + (ch - padding * 2) * (1 - (price - parseFloat(bids[bids.length - 1].price)) / priceRange);
      if (y > padding && y < ch - padding) {
        ctx.beginPath();
        ctx.moveTo(labelWidth, y);
        ctx.lineTo(cw - labelWidth, y);
        ctx.stroke();
        
        ctx.fillStyle = dimColor;
        ctx.font = '10px JetBrains Mono, monospace';
        ctx.textAlign = 'left';
        ctx.fillText(price.toFixed(2), 4, y + 3);
      }
    }
    
    // Draw asks (top half - red)
    const askStartY = padding;
    const askEndY = ch / 2 - 2;
    const askHeight = (askEndY - askStartY) / asks.length;
    
    asks.forEach((ask, i) => {
      const price = parseFloat(ask.price);
      const qty = parseFloat(ask.qty);
      const barWidth = Math.max(1, (qty / maxQty) * maxBarWidth);
      const y = askStartY + i * askHeight;
      
      // Bar
      const gradient = ctx.createLinearGradient(spreadCenter, 0, spreadCenter + barWidth, 0);
      gradient.addColorStop(0, 'rgba(239, 68, 68, 0.3)');
      gradient.addColorStop(1, askColor);
      
      ctx.fillStyle = gradient;
      ctx.fillRect(spreadCenter, y, barWidth, askHeight - 1);
      
      // Price label
      ctx.fillStyle = askColor;
      ctx.font = '11px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(price.toLocaleString(), spreadCenter + barWidth + 4, y + askHeight * 0.7);
      
      // Qty label
      ctx.fillStyle = dimColor;
      ctx.textAlign = 'right';
      ctx.fillText(parseFloat(ask.qty).toFixed(4), spreadCenter - 4, y + askHeight * 0.7);
    });
    
    // Draw bids (bottom half - green)
    const bidStartY = ch / 2 + 2;
    const bidEndY = ch - padding;
    const bidHeight = (bidEndY - bidStartY) / bids.length;
    
    bids.forEach((bid, i) => {
      const price = parseFloat(bid.price);
      const qty = parseFloat(bid.qty);
      const barWidth = Math.max(1, (qty / maxQty) * maxBarWidth);
      const y = bidEndY - (i + 1) * bidHeight;
      
      // Bar
      const gradient = ctx.createLinearGradient(spreadCenter - barWidth, 0, spreadCenter, 0);
      gradient.addColorStop(0, bidColor);
      gradient.addColorStop(1, 'rgba(34, 197, 94, 0.3)');
      
      ctx.fillStyle = gradient;
      ctx.fillRect(spreadCenter - barWidth, y, barWidth, bidHeight - 1);
      
      // Price label
      ctx.fillStyle = bidColor;
      ctx.font = '11px JetBrains Mono, monospace';
      ctx.textAlign = 'right';
      ctx.fillText(price.toLocaleString(), spreadCenter - barWidth - 4, y + bidHeight * 0.7);
      
      // Qty label
      ctx.fillStyle = dimColor;
      ctx.textAlign = 'left';
      ctx.fillText(parseFloat(bid.qty).toFixed(4), spreadCenter + 4, y + bidHeight * 0.7);
    });
    
    // Status indicators
    ctx.font = '12px Inter, system-ui';
    ctx.textAlign = 'left';
    
    // Book valid indicator
    ctx.fillStyle = bookValid ? '#22c55e' : '#ef4444';
    ctx.beginPath();
    ctx.arc(cw - 30, 20, 5, 0, Math.PI * 2);
    ctx.fill();
    
    // Resyncing indicator
    if (resyncing) {
      ctx.fillStyle = '#3b82f6';
      ctx.font = 'bold 12px Inter, system-ui';
      ctx.fillText('RESYNCING', cw - 120, 24);
    }
    
    // Spread display
    if (showSpread) {
      ctx.fillStyle = midColor;
      ctx.font = 'bold 14px Inter, system-ui';
      ctx.textAlign = 'center';
      ctx.fillText(`${spread} bps`, cw / 2, 24);
      
      ctx.fillStyle = textColor;
      ctx.font = '11px JetBrains Mono, monospace';
      ctx.fillText(`Mid: ${mid.toLocaleString()}`, cw / 2, 42);
    }
    
    // Epoch/Seq
    ctx.fillStyle = dimColor;
    ctx.font = '10px JetBrains Mono, monospace';
    ctx.textAlign = 'left';
    ctx.fillText(`E:${book.epoch} S:${book.seq}`, 10, ch - 8);
  }, [renderData]);

  // Animation loop - batched to 60fps
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    let lastFrame = 0;
    
    const animate = (timestamp: number) => {
      // Throttle to 60fps (16.67ms)
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

  // Canvas size observer
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const resizeObserver = new ResizeObserver(() => {
      draw();
    });
    resizeObserver.observe(canvas);
    
    return () => resizeObserver.disconnect();
  }, [draw]);

  return (
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
      }}
      aria-label={`Order book for ${symbol}`}
    />
  );
}

function max(a: number, b: number): number {
  return a > b ? a : b;
}