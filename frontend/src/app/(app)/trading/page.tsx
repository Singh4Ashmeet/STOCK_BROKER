'use client';

import { useState, useEffect, useCallback } from 'react';
import { useAuth } from '@/lib/auth';
import { useMarketStore, selectBook } from '@/lib/store';
import { useWebSocketWorker } from '@/lib/hooks/useWebSocketWorker';
import { OrderBookCanvas, DepthChartCanvas, TradesTapeCanvas, CandlesCanvas, MetricsPanel, LatencyOverlay } from '@/components/charts';
import { tradingApi, marketApi, wsApi } from '@/lib/api';
import { generateIdempotencyKey, formatCurrency, formatNumber, formatPercent } from '@/lib/utils';

const WATCHLIST_SYMBOLS = ['BINANCE:BTC-USDT', 'BINANCE:ETH-USDT', 'BINANCE:SOL-USDT', 'BINANCE:BNB-USDT'];

export default function TradingPage() {
  const { user } = useAuth();
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

  useEffect(() => {
    if (orderForm.side && orderForm.type && orderForm.qty) {
      const debounce = setTimeout(() => updatePreview(), 300);
      return () => clearTimeout(debounce);
    }
  }, [orderForm.side, orderForm.type, orderForm.qty, orderForm.limitPrice, orderForm.tif, activeSymbol]);

  const updatePreview = async () => {
    if (!orderForm.qty || (orderForm.type === 'LIMIT' && !orderForm.limitPrice)) {
      setPreview(null);
      return;
    }
    setPreviewLoading(true);
    try {
      const result = await tradingApi.previewOrder({
        symbol: activeSymbol,
        side: orderForm.side,
        type: orderForm.type,
        qty: orderForm.qty,
        limit_price: orderForm.limitPrice || undefined,
        tif: orderForm.tif,
      });
      setPreview(result);
    } catch (error) {
      console.error('Preview error:', error);
      setPreview(null);
    } finally {
      setPreviewLoading(false);
    }
  };

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
        limit_price: orderForm.limitPrice || undefined,
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
    <div className="min-h-screen bg-surface-50 dark:bg-surface-950">
      {showLatency && <LatencyOverlay position="top-right" showDetails={false} compact />}
      
      <div className="p-4 sm:p-6 lg:p-8">
        {/* Header */}
        <div className="mb-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">Trading</h1>
            <p className="mt-1 text-surface-600 dark:text-surface-400">Place and manage orders</p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-surface-100 dark:bg-surface-800">
              <span className={`w-2 h-2 rounded-full animate-pulse ${
                connectionStatus.status === 'connected' ? 'bg-green-500' :
                connectionStatus.status === 'connecting' ? 'bg-yellow-500' :
                connectionStatus.status === 'resyncing' ? 'bg-blue-500' :
                'bg-red-500'
              }`}></span>
              <span className="text-sm font-medium text-surface-700 dark:text-surface-300 capitalize">
                {connectionStatus.status}
              </span>
            </div>
            <button
              onClick={() => setShowLatency(!showLatency)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                showLatency ? 'bg-primary-600 text-white' : 'bg-surface-100 dark:bg-surface-800 text-surface-700 dark:text-surface-300'
              }`}
            >
              {showLatency ? 'Hide Latency' : 'Show Latency'}
            </button>
          </div>
        </div>

        {/* Resync Banner */}
        {useMarketStore.getState().isResyncing(activeSymbol) && (
          <div className="mb-4 p-3 bg-blue-50 dark:bg-blue-900/30 border border-blue-200 dark:border-blue-800 rounded-lg flex items-center justify-between">
            <div className="flex items-center gap-2 text-blue-800 dark:text-blue-200">
              <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none"/>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
              </svg>
              <span className="font-medium">Resyncing order book for {activeSymbol}...</span>
            </div>
            <button onClick={() => resync(activeSymbol)} className="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700">
              Force Resync
            </button>
          </div>
        )}

        {/* Main Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* Left Sidebar */}
          <div className="lg:col-span-3 space-y-6">
            {/* Symbol Selector */}
            <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
              <h3 className="font-semibold text-surface-900 dark:text-surface-50 mb-3">Symbol</h3>
              <div className="space-y-1">
                {WATCHLIST_SYMBOLS.map(symbol => (
                  <button
                    key={symbol}
                    onClick={() => setActiveSymbol(symbol)}
                    className={`w-full flex items-center justify-between p-2 rounded-lg text-left transition-colors ${
                      activeSymbol === symbol
                        ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300'
                        : 'text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800'
                    }`}
                  >
                    <span className="font-medium">{symbol.split(':')[1]?.split('-')[0] || symbol}</span>
                    <span className="text-xs text-surface-500 dark:text-surface-400">{symbol}</span>
                  </button>
                ))}
              </div>
            </div>

            {/* Order Form */}
            <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
              <h3 className="font-semibold text-surface-900 dark:text-surface-50 mb-4">New Order</h3>
              <form onSubmit={handleSubmit} className="space-y-4">
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Side</label>
                    <select
                      value={orderForm.side}
                      onChange={(e) => setOrderForm({ ...orderForm, side: e.target.value as OrderSide })}
                      className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                    >
                      <option value="BUY">Buy</option>
                      <option value="SELL">Sell</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Type</label>
                    <select
                      value={orderForm.type}
                      onChange={(e) => setOrderForm({ ...orderForm, type: e.target.value as OrderType })}
                      className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                    >
                      <option value="LIMIT">Limit</option>
                      <option value="MARKET">Market</option>
                    </select>
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Quantity</label>
                    <input
                      type="number"
                      step="0.0001"
                      min="0"
                      value={orderForm.qty}
                      onChange={(e) => setOrderForm({ ...orderForm, qty: e.target.value })}
                      className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                      placeholder="0.001"
                      required
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">
                      {orderForm.type === 'LIMIT' ? 'Limit Price' : 'Price (est.)'}
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      min="0"
                      value={orderForm.limitPrice}
                      onChange={(e) => setOrderForm({ ...orderForm, limitPrice: e.target.value })}
                      className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                      placeholder={orderForm.type === 'LIMIT' ? '50000' : 'Market'}
                      disabled={orderForm.type === 'MARKET'}
                      required={orderForm.type === 'LIMIT'}
                    />
                  </div>
                </div>

                <div>
                  <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Time in Force</label>
                  <select
                    value={orderForm.tif}
                    onChange={(e) => setOrderForm({ ...orderForm, tif: e.target.value as TimeInForce })}
                    className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                  >
                    <option value="GTC">GTC</option>
                    <option value="IOC">IOC</option>
                    <option value="FOK">FOK</option>
                    <option value="DAY">DAY</option>
                  </select>
                </div>

                {/* Slippage Preview */}
                {preview && (
                  <div className="bg-surface-50 dark:bg-surface-800/50 rounded-lg p-4 border border-surface-200 dark:border-surface-700">
                    <h4 className="font-medium text-surface-900 dark:text-surface-50 mb-3">Slippage Preview</h4>
                    <div className="grid grid-cols-2 gap-3 text-sm">
                      <div>
                        <span className="text-surface-500">Est. Fill Price</span>
                        <p className="font-mono font-bold text-surface-900 dark:text-surface-50">{preview.est_fill_price}</p>
                      </div>
                      <div>
                        <span className="text-surface-500">Est. Fees</span>
                        <p className="font-mono font-bold text-surface-900 dark:text-surface-50">{preview.est_fees}</p>
                      </div>
                      <div>
                        <span className="text-surface-500">Est. Slippage</span>
                        <p className="font-mono font-bold text-orange-600">{preview.est_slippage_bps} bps</p>
                      </div>
                      <div>
                        <span className="text-surface-500">Book Valid</span>
                        <p className="font-mono font-bold {preview.book_valid ? 'text-green-600' : 'text-red-600'}">
                          {preview.book_valid ? 'Yes' : 'No'}
                        </p>
                      </div>
                    </div>
                  </div>
                )}

                <button
                  type="submit"
                  disabled={submitting || previewLoading || !orderForm.qty || (orderForm.type === 'LIMIT' && !orderForm.limitPrice)}
                  className="w-full rounded-lg bg-primary-600 py-3 text-base font-medium text-white hover:bg-primary-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
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

            {/* Preview Chart */}
            <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
              <h3 className="font-semibold text-surface-900 dark:text-surface-50 mb-3">Chart</h3>
              <CandlesCanvas 
                symbol={activeSymbol} 
                height={300}
                timeframe={timeframe}
              />
              <div className="flex gap-1 mt-3">
                {(['1m', '5m', '15m', '1h', '4h', '1d'] as const).map(tf => (
                  <button
                    key={tf}
                    onClick={() => setTimeframe(tf)}
                    className={`px-2 py-1 text-xs rounded transition-colors ${
                      timeframe === tf
                        ? 'bg-primary-600 text-white'
                        : 'bg-surface-100 dark:bg-surface-800 text-surface-600 dark:text-surface-400 hover:bg-surface-200 dark:hover:bg-surface-700'
                    }`}
                  >
                    {tf}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Main Content */}
          <div className="lg:col-span-9 space-y-6">
            {/* Active Symbol Header */}
            <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-full bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
                    <span className="text-lg font-bold text-primary-700 dark:text-primary-400">
                      {activeSymbol.split(':')[1]?.split('-')[0] || activeSymbol}
                    </span>
                  </div>
                  <div>
                    <h2 className="text-xl font-bold text-surface-900 dark:text-surface-50">
                      {activeSymbol.split(':')[1] || activeSymbol}
                    </h2>
                    <p className="text-sm text-surface-500 dark:text-surface-400">{activeSymbol}</p>
                  </div>
                  {activeBook && (
                    <div className="flex items-center gap-4 ml-auto">
                      <div className="text-right">
                        <p className="text-xs text-surface-500 dark:text-surface-400">Mid</p>
                        <p className="font-bold text-surface-900 dark:text-surface-50">
                          {activeBook.bids[0] && activeBook.asks[0] 
                            ? ((parseFloat(activeBook.bids[0].price) + parseFloat(activeBook.asks[0].price)) / 2).toLocaleString()
                            : '—'}
                        </p>
                      </div>
                      <div className="text-right">
                        <p className="text-xs text-surface-500 dark:text-surface-400">Spread</p>
                        <p className="font-bold text-surface-900 dark:text-surface-50">
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
  tif: 'GTC' | 'IOC' | 'FOK' | 'DAY';
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
type TimeInForce = 'GTC' | 'IOC' | 'FOK' | 'DAY';