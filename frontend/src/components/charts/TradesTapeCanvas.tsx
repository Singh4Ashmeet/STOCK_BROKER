'use client';

import { useEffect, useRef, useMemo, useCallback } from 'react';
import { useMarketStore, selectTrades } from '@/lib/store';
import type { Trade } from '@/lib/api';

interface TradesTapeCanvasProps {
  symbol: string;
  height?: number;
  maxTrades?: number;
  width?: number;
}

export function TradesTapeCanvas({
  symbol,
  height = 300,
  maxTrades = 100,
  width,
}: TradesTapeCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animationRef = useRef<number>();
  const trades = useMarketStore(selectTrades(symbol));

  const tradeData = useMemo(() => {
    return trades.slice(0, maxTrades);
  }, [trades]);

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
    
    if (tradeData.length === 0) {
      ctx.fillStyle = '#52525b';
      ctx.font = '14px Inter, system-ui';
      ctx.textAlign = 'center';
      ctx.fillText('Waiting for trades...', cw / 2, ch / 2);
      return;
    }
    
    // Colors
    const buyColor = '#22c55e';
    const sellColor = '#ef4444';
    const textColor = '#e4e4e7';
    const dimColor = '#71717a';
    const gridColor = 'rgba(113, 113, 122, 0.1)';
    
    // Layout
    const padding = 12;
    const rowHeight = 28;
    const timeWidth = 70;
    const sideWidth = 50;
    const priceWidth = 100;
    const qtyWidth = 80;
    const startY = padding + 24;
    
    // Header
    ctx.fillStyle = dimColor;
    ctx.font = '11px Inter, system-ui';
    ctx.textAlign = 'left';
    ctx.fillText('TIME', padding, padding + 16);
    ctx.fillText('SIDE', padding + timeWidth, padding + 16);
    ctx.fillText('PRICE', padding + timeWidth + sideWidth, padding + 16);
    ctx.textAlign = 'right';
    ctx.fillText('QTY', cw - padding, padding + 16);
    
    // Divider
    ctx.strokeStyle = gridColor;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(padding, startY - 4);
    ctx.lineTo(cw - padding, startY - 4);
    ctx.stroke();
    
    // Draw trades (newest at top)
    tradeData.forEach((trade, index) => {
      const y = startY + index * rowHeight;
      if (y > ch - padding) return; // Don't draw beyond canvas
      
      const isBuy = trade.side === 'BUY';
      const sideColor = isBuy ? buyColor : sellColor;
      
      // Alternating row background
      if (index % 2 === 0) {
        ctx.fillStyle = 'rgba(113, 113, 122, 0.03)';
        ctx.fillRect(padding, y - 4, cw - padding * 2, rowHeight);
      }
      
      // Time
      const time = new Date(Number(trade.timing.recv_ts_ns) / 1_000_000);
      const timeStr = time.toLocaleTimeString('en-US', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        fractionalSecondDigits: 3,
      });
      
      ctx.fillStyle = textColor;
      ctx.font = '11px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(timeStr, padding, y + 4);
      
      // Side badge
      const sideX = padding + timeWidth;
      const badgeWidth = 40;
      const badgeHeight = 16;
      const badgeY = y - badgeHeight / 2 + 2;
      
      ctx.fillStyle = sideColor;
      ctx.beginPath();
      ctx.roundRect(sideX, badgeY, badgeWidth, badgeHeight, 3);
      ctx.fill();
      
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 9px Inter, system-ui';
      ctx.textAlign = 'center';
      ctx.fillText(isBuy ? 'BUY' : 'SELL', sideX + badgeWidth / 2, badgeY + 11);
      
      // Price
      ctx.fillStyle = textColor;
      ctx.font = '12px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(parseFloat(trade.price).toLocaleString(), sideX + badgeWidth + 8, y + 4);
      
      // Quantity
      ctx.fillStyle = textColor;
      ctx.font = '11px JetBrains Mono, monospace';
      ctx.textAlign = 'right';
      ctx.fillText(parseFloat(trade.qty).toFixed(4), cw - padding, y + 4);
      
      // Venue trade ID (small)
      ctx.fillStyle = dimColor;
      ctx.font = '9px Inter, system-ui';
      ctx.fillText(trade.venue_trade_id.slice(0, 12), cw - padding - qtyWidth - 4, y + 4);
    });
    
    // Scroll indicator if more trades than visible
    const maxVisible = Math.floor((ch - startY - padding) / rowHeight);
    if (tradeData.length > maxVisible) {
      ctx.fillStyle = dimColor;
      ctx.font = '10px Inter, system-ui';
      ctx.textAlign = 'center';
      ctx.fillText(
        `+${tradeData.length - maxVisible} more trades`,
        cw / 2,
        ch - 6
      );
    }
  }, [tradeData]);

  // Animation loop - lower frequency for trades tape
  useEffect(() => {
    let lastFrame = 0;
    
    const animate = (timestamp: number) => {
      if (timestamp - lastFrame >= 100) { // 10fps is enough for trades
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

  // Resize observer
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const resizeObserver = new ResizeObserver(() => draw());
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
      aria-label={`Trades tape for ${symbol}`}
    />
  );
}