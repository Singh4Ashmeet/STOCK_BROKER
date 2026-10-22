'use client';

import { useEffect, useRef, useMemo, useCallback } from 'react';
import { useMarketStore, selectBook } from '@/lib/store';
import type { Level } from '@/lib/api';

interface DepthChartCanvasProps {
  symbol: string;
  height?: number;
  maxLevels?: number;
  width?: number;
}

export function DepthChartCanvas({
  symbol,
  height = 200,
  maxLevels = 50,
  width,
}: DepthChartCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animationRef = useRef<number>();
  const book = useMarketStore(selectBook(symbol));

  const depthData = useMemo(() => {
    if (!book) return null;
    
    const bids = book.bids.slice(0, maxLevels);
    const asks = book.asks.slice(0, maxLevels);
    
    // Cumulative quantities
    let cumBidQty = 0;
    const bidPoints = bids.map(bid => {
      cumBidQty += parseFloat(bid.qty);
      return { price: parseFloat(bid.price), qty: cumBidQty };
    });
    
    let cumAskQty = 0;
    const askPoints = asks.map(ask => {
      cumAskQty += parseFloat(ask.qty);
      return { price: parseFloat(ask.price), qty: cumAskQty };
    });
    
    const allPrices = [...bids.map(b => parseFloat(b.price)), ...asks.map(a => parseFloat(a.price))];
    const minPrice = Math.min(...allPrices);
    const maxPrice = Math.max(...allPrices);
    const maxQty = Math.max(cumBidQty, cumAskQty);
    
    return {
      bidPoints,
      askPoints,
      minPrice,
      maxPrice,
      maxQty,
      bestBid: bids[0] ? parseFloat(bids[0].price) : 0,
      bestAsk: asks[0] ? parseFloat(asks[0].price) : 0,
    };
  }, [book, maxLevels]);

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
    
    const data = depthData;
    if (!data) {
      ctx.fillStyle = '#52525b';
      ctx.font = '14px Inter, system-ui';
      ctx.textAlign = 'center';
      ctx.fillText('Loading depth chart...', cw / 2, ch / 2);
      return;
    }
    
    const { bidPoints, askPoints, minPrice, maxPrice, maxQty, bestBid, bestAsk } = data;
    const spread = bestBid && bestAsk ? bestAsk - bestBid : 0;
    const midPrice = bestBid && bestAsk ? (bestBid + bestAsk) / 2 : 0;
    
    // Colors
    const bidColor = '#22c55e';
    const askColor = '#ef4444';
    const bidFill = 'rgba(34, 197, 94, 0.15)';
    const askFill = 'rgba(239, 68, 68, 0.15)';
    const midColor = '#3b82f6';
    const gridColor = 'rgba(113, 113, 122, 0.1)';
    const textColor = '#e4e4e7';
    const dimColor = '#71717a';
    
    const padding = 40;
    const leftPad = 60;
    const rightPad = 60;
    const plotWidth = cw - leftPad - rightPad;
    const plotHeight = ch - padding * 2;
    
    // Price to Y coordinate
    const priceToY = (price: number) => {
      return padding + (1 - (price - minPrice) / (maxPrice - minPrice)) * plotHeight;
    };
    
    // Qty to X coordinate (inverted for bids)
    const qtyToXBid = (qty: number) => {
      return leftPad + (1 - qty / maxQty) * plotWidth / 2;
    };
    
    const qtyToXAsk = (qty: number) => {
      return leftPad + plotWidth / 2 + (qty / maxQty) * plotWidth / 2;
    };
    
    // Draw grid
    ctx.strokeStyle = gridColor;
    ctx.lineWidth = 1;
    
    // Horizontal grid lines
    for (let i = 0; i <= 4; i++) {
      const y = padding + (i / 4) * plotHeight;
      ctx.beginPath();
      ctx.moveTo(leftPad, y);
      ctx.lineTo(cw - rightPad, y);
      ctx.stroke();
      
      // Price labels
      const price = maxPrice - (i / 4) * (maxPrice - minPrice);
      ctx.fillStyle = dimColor;
      ctx.font = '10px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(price.toFixed(2), 4, y + 3);
      ctx.textAlign = 'right';
      ctx.fillText(price.toFixed(2), cw - 4, y + 3);
    }
    
    // Vertical center line (mid price)
    const midX = leftPad + plotWidth / 2;
    ctx.strokeStyle = midColor;
    ctx.setLineDash([4, 4]);
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(midX, padding);
    ctx.lineTo(midX, ch - padding);
    ctx.stroke();
    ctx.setLineDash([]);
    
    // Draw bid area (left side - green)
    if (bidPoints.length > 1) {
      ctx.beginPath();
      ctx.moveTo(midX, priceToY(bidPoints[0].price));
      
      bidPoints.forEach(point => {
        const x = qtyToXBid(point.qty);
        const y = priceToY(point.price);
        ctx.lineTo(x, y);
      });
      
      // Close the path at bottom
      ctx.lineTo(midX, priceToY(bidPoints[bidPoints.length - 1].price));
      ctx.closePath();
      
      // Fill
      const gradient = ctx.createLinearGradient(0, padding, 0, ch - padding);
      gradient.addColorStop(0, 'rgba(34, 197, 94, 0.25)');
      gradient.addColorStop(1, 'rgba(34, 197, 94, 0.02)');
      ctx.fillStyle = gradient;
      ctx.fill();
      
      // Stroke
      ctx.strokeStyle = bidColor;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(midX, priceToY(bidPoints[0].price));
      bidPoints.forEach(point => {
        const x = qtyToXBid(point.qty);
        const y = priceToY(point.price);
        ctx.lineTo(x, y);
      });
      ctx.stroke();
    }
    
    // Draw ask area (right side - red)
    if (askPoints.length > 1) {
      ctx.beginPath();
      ctx.moveTo(midX, priceToY(askPoints[0].price));
      
      askPoints.forEach(point => {
        const x = qtyToXAsk(point.qty);
        const y = priceToY(point.price);
        ctx.lineTo(x, y);
      });
      
      // Close the path at bottom
      ctx.lineTo(midX, priceToY(askPoints[askPoints.length - 1].price));
      ctx.closePath();
      
      // Fill
      const gradient = ctx.createLinearGradient(0, padding, 0, ch - padding);
      gradient.addColorStop(0, 'rgba(239, 68, 68, 0.25)');
      gradient.addColorStop(1, 'rgba(239, 68, 68, 0.02)');
      ctx.fillStyle = gradient;
      ctx.fill();
      
      // Stroke
      ctx.strokeStyle = askColor;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(midX, priceToY(askPoints[0].price));
      askPoints.forEach(point => {
        const x = qtyToXAsk(point.qty);
        const y = priceToY(point.price);
        ctx.lineTo(x, y);
      });
      ctx.stroke();
    }
    
    // Spread highlight
    if (bestBid && bestAsk) {
      const bidY = priceToY(bestBid);
      const askY = priceToY(bestAsk);
      
      ctx.fillStyle = 'rgba(59, 130, 246, 0.1)';
      ctx.fillRect(leftPad, bidY, plotWidth, askY - bidY);
    }
    
    // Labels
    ctx.font = '11px JetBrains Mono, monospace';
    
    // Best bid
    if (bestBid) {
      ctx.fillStyle = bidColor;
      ctx.textAlign = 'right';
      ctx.fillText(bestBid.toLocaleString(), midX - 8, priceToY(bestBid) - 4);
    }
    
    // Best ask
    if (bestAsk) {
      ctx.fillStyle = askColor;
      ctx.textAlign = 'left';
      ctx.fillText(bestAsk.toLocaleString(), midX + 8, priceToY(bestAsk) + 14);
    }
    
    // Mid price
    if (midPrice) {
      ctx.fillStyle = midColor;
      ctx.textAlign = 'center';
      ctx.font = 'bold 12px Inter, system-ui';
      ctx.fillText(midPrice.toLocaleString(), midX, priceToY(midPrice) + 4);
    }
    
    // Spread
    if (spread > 0) {
      ctx.fillStyle = midColor;
      ctx.font = '12px Inter, system-ui';
      ctx.textAlign = 'center';
      ctx.fillText(`${(spread / midPrice * 10000).toFixed(1)} bps`, midX, ch - 10);
    }
    
    // Axis labels
    ctx.fillStyle = dimColor;
    ctx.font = '10px Inter, system-ui';
    ctx.textAlign = 'center';
    ctx.fillText('Cumulative Quantity', cw / 2, ch - 4);
  }, [depthData]);

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
      aria-label={`Depth chart for ${symbol}`}
    />
  );
}