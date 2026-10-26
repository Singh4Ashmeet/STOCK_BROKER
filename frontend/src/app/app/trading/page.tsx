'use client';

import { useState, useEffect, useCallback } from 'react';
import { useMarketStore, selectBook } from '@/lib/store';
import { useWebSocketWorker } from '@/lib/hooks/useWebSocketWorker';
import { OrderBookCanvas, DepthChartCanvas, TradesTapeCanvas, CandlesCanvas, MetricsPanel, LatencyOverlay } from '@/components/charts';
import { tradingApi } from '@/lib/api';
import { generateIdempotencyKey, formatCurrency, formatNumber, formatPercent } from '@/lib/utils';

const WATCHLIST_SYMBOLS = ['BINANCE:BTC-USDT', 'BINANCE:ETH-USDT', 'BINANCE:SOL-USDT', 'BINANCE:BNB-USDT'];

export default function TradingPage() {
  const { connect, subscribe, unsubscribe, resync, connectionStatus } = useWebSocketWorker();
  const [activeSymbol, setActiveSymbol] = useState(WATCHLIST_SYMBOLS[0]);
  const [showLatency, setShowLatency] = useState(true);
  const [orderForm, setOrderForm] = useState<OrderFormState>({
    side: 'BUY',
    type: 'LIMIT',
    qty: '',
    limitPrice: '',
    tif: 'GTC',
  });
  const [preview, setPreview] = useState<OrderPreviewResponse | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);
  const [timeframe, setTimeframe] = useState<'1m' | '5m' | '15m' | '1h' | '4h' | '1d'>('5m');

  const activeBook = useMarketStore(selectBook(activeSymbol));
  const isResyncing = useMarketStore((state) => state.isResyncing(activeSymbol));

  useEffect(() => {
    connect();
    WATCHLIST_SYMBOLS.forEach(symbol => {
      subscribe('book', symbol, 20);
      subscribe('trades', symbol);
      subscribe('metrics', symbol);
    });
    return () => {
      WATCHLIST_SYMBOLS.forEach(symbol => {
        unsubscribe('book', symbol);
        unsubscribe('trades', symbol);
        unsubscribe('metrics', symbol);
      });
    };
  }, [connect, subscribe, unsubscribe]);

  const updatePreview = useCallback(async () => {
    if (!orderForm.qty || (orderForm.type === 'LIMIT' && !orderForm.limitPrice)) {
      setPreview(null);
      return;
    }
    setPreviewLoading(true);
    try {
      const result = await tradingApi.previewOrder({
        symbol: activeSymbol,
        side: orderForm.side,
        qty: orderForm.qty,
        limitPrice: orderForm.limitPrice || undefined,
      });
      setPreview(result);
      setPreviewError(null);
    } catch (error) {
      console.error('Preview error:', error);
      setPreview(null);
      setPreviewError(error instanceof Error ? error.message : 'Order preview is unavailable.');
    } finally {
      setPreviewLoading(false);
    }
  }, [orderForm, activeSymbol]);

  useEffect(() => {
    if (orderForm.side && orderForm.type && orderForm.qty) {
      const debounce = setTimeout(() => updatePreview(), 300);
      return () => clearTimeout(debounce);
    }
  }, [orderForm.side, orderForm.type, orderForm.qty, orderForm.limitPrice, orderForm.tif, activeSymbol, updatePreview]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!orderForm.qty || (orderForm.type === 'LIMIT' && !orderForm.limitPrice)) return;
    
    setSubmitting(true);
    const idempotencyKey = generateIdempotencyKey();
    try {
      const result = await tradingApi.createOrder({
        symbol: activeSymbol,
        side: orderForm.side,
        type: orderForm.type,
        qty: orderForm.qty,
        limitPrice: orderForm.limitPrice || undefined,
        tif: orderForm.tif,
      }, idempotencyKey);
      
      showToast('success', `Order placed: ${result.order_id}`);
      setOrderForm(prev => ({ ...prev, qty: '', limitPrice: '' }));
      setPreview(null);
    } catch (error: any) {
      showToast('error', error.message || 'Order failed');
    } finally {
      setSubmitting(false);
    }
  };

  const showToast = (type: 'success' | 'error', message: string) => {
    setToast({ type, message });
    setTimeout(() => setToast(null), 5000);
  };

  return (
    <div className="min-h-screen bg-surface-950">
      {showLatency && <LatencyOverlay position="top-right" showDetails={false} compact />}
      
      <div className="mx-auto max-w-[1800px] p-4 sm:p-6 lg:p-8">
        {/* Header */}
        <div className="mb-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <p className="terminal-label text-primary-400">Execution workspace</p>
            <h1 className="mt-1 font-display text-3xl font-semibold tracking-tight text-surface-50">Trading terminal</h1>
            <p className="mt-2 text-sm text-surface-300">Live order-book views and a pre-trade order ticket.</p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2 border border-surface-600 bg-surface-800 px-3 py-1.5">
              <span className={`h-2 w-2 ${
                connectionStatus.status === 'connected' ? 'bg-positive' :
                connectionStatus.status === 'connecting' ? 'bg-warning' :
                connectionStatus.status === 'resyncing' ? 'bg-info' :
                'bg-negative'
              }`}></span>
              <span className="font-mono text-xs font-medium uppercase tracking-wide text-surface-100">
                {connectionStatus.status}
              </span>
            </div>
            <button
              onClick={() => setShowLatency(!showLatency)}
              className={`border px-3 py-1.5 text-xs font-semibold transition-colors ${
                showLatency ? 'border-primary-500 bg-primary-500 text-surface-950' : 'border-surface-600 bg-surface-800 text-surface-100 hover:bg-surface-700'
              }`}
            >
              {showLatency ? 'Hide Latency' : 'Show Latency'}
            </button>
          </div>
        </div>

        {/* Resync Banner */}
        {useMarketStore.getState().isResyncing(activeSymbol) && (
          <div className="mb-4 flex items-center justify-between border border-info/60 bg-info/10 p-3">
            <div className="flex items-center gap-2 text-surface-100">
              <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none"/>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
              </svg>
              <span className="font-medium">Resyncing order book for {activeSymbol}...</span>
            </div>
            <button onClick={() => resync(activeSymbol)} className="terminal-button-secondary min-h-8 px-3 py-1 text-xs">
              Force Resync
            </button>
          </div>
        )}

        {/* Main Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* Left Sidebar */}
          <div className="lg:col-span-3 space-y-6">
            {/* Symbol Selector */}
            <div className="terminal-panel p-4">
              <p className="terminal-label">Market watch</p><h3 className="mt-1 font-display text-lg font-semibold text-surface-50">Symbol</h3>
              <div className="space-y-1">
                {WATCHLIST_SYMBOLS.map(symbol => (
                  <button
                    key={symbol}
                    onClick={() => setActiveSymbol(symbol)}
                    className={`mt-3 flex w-full items-center justify-between border p-2.5 text-left transition-colors ${
                      activeSymbol === symbol
                        ? 'border-primary-500 bg-primary-500/10 text-primary-300'
                        : 'border-transparent text-surface-200 hover:border-surface-600 hover:bg-surface-700'
                    }`}
                  >
                    <span className="font-medium">{symbol.split(':')[1]?.split('-')[0] || symbol}</span>
                    <span className="text-xs text-surface-500 dark:text-surface-400">{symbol}</span>
                  </button>
                ))}
              </div>
            </div>

            {/* Order Form */}
            <div className="terminal-panel p-4">
              <p className="terminal-label text-primary-400">Pre-trade</p><h3 className="mt-1 font-display text-lg font-semibold text-surface-50">New order</h3>
              <form onSubmit={handleSubmit} className="space-y-4">
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="terminal-label mb-1 block">Side</label>
                    <select
                      value={orderForm.side}
                      onChange={(e) => setOrderForm({ ...orderForm, side: e.target.value as OrderSide })}
                      className="terminal-input"
                    >
                      <option value="BUY">Buy</option>
                      <option value="SELL">Sell</option>
                    </select>
                  </div>
                  <div>
                    <label className="terminal-label mb-1 block">Type</label>
                    <select
                      value={orderForm.type}
                      onChange={(e) => setOrderForm({ ...orderForm, type: e.target.value as OrderType })}
                      className="terminal-input"
                    >
                      <option value="LIMIT">Limit</option>
                      <option value="MARKET">Market</option>
                    </select>
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="terminal-label mb-1 block">Quantity</label>
                    <input
                      type="number"
                      step="0.0001"
                      min="0"
                      value={orderForm.qty}
                      onChange={(e) => setOrderForm({ ...orderForm, qty: e.target.value })}
                      className="terminal-input"
                      placeholder="0.001"
                      required
                    />
                  </div>
                  <div>
                    <label className="terminal-label mb-1 block">
                      {orderForm.type === 'LIMIT' ? 'Limit Price' : 'Price (est.)'}
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      min="0"
                      value={orderForm.limitPrice}
                      onChange={(e) => setOrderForm({ ...orderForm, limitPrice: e.target.value })}
                      className="terminal-input disabled:cursor-not-allowed disabled:bg-surface-800"
                      placeholder={orderForm.type === 'LIMIT' ? '50000' : 'Market'}
                      disabled={orderForm.type === 'MARKET'}
                      required={orderForm.type === 'LIMIT'}
                    />
                  </div>
                </div>

                <div>
                  <label className="terminal-label mb-1 block">Time in force</label>
                  <select
                    value={orderForm.tif}
                    onChange={(e) => setOrderForm({ ...orderForm, tif: e.target.value as TimeInForce })}
                    className="terminal-input"
                  >
                    <option value="GTC">GTC</option>
                    <option value="IOC">IOC</option>
                  </select>
                </div>

                {/* Slippage Preview */}
                {preview && (
                  <div className="terminal-panel-raised p-4">
                    <p className="terminal-label">Estimated execution</p><h4 className="mt-1 font-display font-semibold text-surface-50">Order preview</h4>
                    <div className="grid grid-cols-2 gap-3 text-sm">
                      <div>
                        <span className="terminal-label">Est. fill price</span>
                        <p className="terminal-value mt-1 font-bold">{preview.est_fill_price}</p>
                      </div>
                      <div>
                        <span className="terminal-label">Est. fees</span>
                        <p className="terminal-value mt-1 font-bold">{preview.est_fees}</p>
                      </div>
                      <div>
                        <span className="terminal-label">Est. slippage</span>
                        <p className="mt-1 font-mono font-bold text-warning">{preview.est_slippage_bps} bps</p>
                      </div>
                      <div>
                        <span className="terminal-label">Book valid</span>
                        <p className={`mt-1 font-mono font-bold ${preview.book_valid ? 'text-positive' : 'text-negative'}`}>
                          {preview.book_valid ? 'Yes' : 'No'}
                        </p>
                      </div>
                    </div>
                  </div>
                )}

                {previewError ? <p role="alert" className="border border-negative/60 bg-negative/10 p-3 text-sm text-surface-100">{previewError}</p> : null}
                <button
                  type="submit"
                  disabled={submitting || previewLoading || !orderForm.qty || (orderForm.type === 'LIMIT' && !orderForm.limitPrice)}
                  className="terminal-button w-full py-3 text-base"
                >
                  {submitting ? (
                    <>
                      <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none"/><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/></svg>
                      Placing...
                    </>
                  ) : (
                    `Place ${orderForm.side} Order`
                  )}
                </button>
              </form>
            </div>

          </div>

          {/* Main Content */}
          <div className="lg:col-span-9 space-y-6">
            {/* Active Symbol Header */}
            <div className="terminal-panel p-4">
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center border border-primary-500 bg-primary-500/10">
                    <span className="font-mono text-lg font-bold text-primary-300">
                      {activeSymbol.split(':')[1]?.split('-')[0] || activeSymbol}
                    </span>
                  </div>
                  <div>
                    <h2 className="font-display text-xl font-bold text-surface-50">
                      {activeSymbol.split(':')[1] || activeSymbol}
                    </h2>
                    <p className="font-mono text-xs text-surface-300">{activeSymbol}</p>
                  </div>
                  {activeBook && (
                    <div className="flex items-center gap-4 ml-auto">
                      <div className="text-right">
                        <p className="terminal-label">Mid</p>
                        <p className="terminal-value mt-1 font-bold">
                          {activeBook.bids[0] && activeBook.asks[0] 
                            ? ((parseFloat(activeBook.bids[0].price) + parseFloat(activeBook.asks[0].price)) / 2).toLocaleString()
                            : '—'}
                        </p>
                      </div>
                      <div className="text-right">
                        <p className="terminal-label">Spread</p>
                        <p className="terminal-value mt-1 font-bold">
                          {activeBook.bids[0] && activeBook.asks[0] 
                            ? `${((parseFloat(activeBook.asks[0].price) - parseFloat(activeBook.bids[0].price)) / ((parseFloat(activeBook.bids[0].price) + parseFloat(activeBook.asks[0].price)) / 2) * 10000).toFixed(1)} bps`
                            : '—'}
                        </p>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* Charts Row */}
            <div className="grid grid-cols-1 lg:grid-cols-8 gap-6">
              <div className="lg:col-span-5">
                <CandlesCanvas 
                  symbol={activeSymbol} 
                  height={400}
                  timeframe={timeframe}
                />
              </div>
              <div className="lg:col-span-3 space-y-4">
                <OrderBookCanvas 
                  symbol={activeSymbol} 
                  height={300}
                  maxLevels={20}
                />
                <DepthChartCanvas 
                  symbol={activeSymbol} 
                  height={200}
                  maxLevels={30}
                />
              </div>
            </div>

            {/* Order Book + Trades */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <OrderBookCanvas 
                symbol={activeSymbol} 
                height={350}
                maxLevels={25}
              />
              <TradesTapeCanvas 
                symbol={activeSymbol} 
                height={350}
                maxTrades={50}
              />
            </div>

            {/* Metrics */}
            <MetricsPanel symbol={activeSymbol} compact={false} />
          </div>
        </div>
      </div>
    </div>
  );
}

interface OrderFormState {
  side: 'BUY' | 'SELL';
  type: 'LIMIT' | 'MARKET';
  qty: string;
  limitPrice: string;
  tif: 'GTC' | 'IOC';
}

interface OrderPreviewResponse {
  est_fill_price: string;
  est_fees: string;
  est_slippage_bps: number;
  book_valid: boolean;
  levels_consumed: number;
}

interface Toast {
  type: 'success' | 'error';
  message: string;
}

type OrderSide = 'BUY' | 'SELL';
type OrderType = 'LIMIT' | 'MARKET';
type TimeInForce = 'GTC' | 'IOC';
