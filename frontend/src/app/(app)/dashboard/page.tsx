'use client';

import { useEffect, useState } from 'react';
import { useAuth } from '@/lib/auth';
import { useWebSocketWorker } from '@/lib/hooks/useWebSocketWorker';
import { useMarketStore } from '@/lib/store';
import { 
  OrderBookCanvas, 
  DepthChartCanvas, 
  TradesTapeCanvas, 
  CandlesCanvas, 
  MetricsPanel, 
  LatencyOverlay 
} from '@/components/charts';
import Link from 'next/link';
import { formatCurrency, formatPercent, formatTimestamp } from '@/lib/utils';

const WATCHLIST_SYMBOLS = ['BINANCE:BTC-USDT', 'BINANCE:ETH-USDT', 'BINANCE:SOL-USDT', 'BINANCE:BNB-USDT'];
const DEFAULT_SYMBOL = 'BINANCE:BTC-USDT';

export default function DashboardPage() {
  const { user } = useAuth();
  const { 
    connect, 
    disconnect, 
    subscribe, 
    unsubscribe, 
    resync, 
    ping, 
    connectionStatus, 
    isResyncing 
  } = useWebSocketWorker();
  const books = useMarketStore((state) => state.books);
  const trades = useMarketStore((state) => state.trades);
  const metrics = useMarketStore((state) => state.metrics);
  const [activeSymbol, setActiveSymbol] = useState(DEFAULT_SYMBOL);
  const [showLatency, setShowLatency] = useState(true);

  useEffect(() => {
    connect();
    
    // Subscribe to watchlist
    WATCHLIST_SYMBOLS.forEach(symbol => {
      subscribe('book', symbol, 10);
      subscribe('trades', symbol);
      subscribe('metrics', symbol);
    });
    
    // Handle incoming messages
    const unsubscribe = useMarketStore.subscribe(
      (state) => state.books,
      (books) => {
        // Books are updated via WebSocket worker
      }
    );

    return () => {
      unsubscribe();
      // Don't disconnect on unmount - keep WebSocket alive
      // disconnect();
      // WATCHLIST_SYMBOLS.forEach(symbol => {
      //   unsubscribe('book', symbol);
      //   unsubscribe('trades', symbol);
      //   unsubscribe('metrics', symbol);
      // });
    };
  }, [connect, subscribe]);

  const activeBook = books.get(activeSymbol);
  const activeTrades = trades.get(activeSymbol) || [];
  const activeMetrics = metrics.get(activeSymbol)?.metrics;
  const isActiveResyncing = isResyncing(activeSymbol);

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-950">
      {/* Latency Overlay */}
      {showLatency && <LatencyOverlay position="top-right" showDetails={false} compact />}

      <div className="p-4 sm:p-6 lg:p-8">
        {/* Header */}
        <div className="mb-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">
              Welcome back, {user?.name || 'Trader'}
            </h1>
            <p className="mt-1 text-surface-600 dark:text-surface-400">
              Real-time market data & trading dashboard
            </p>
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
                showLatency 
                  ? 'bg-primary-600 text-white' 
                  : 'bg-surface-100 dark:bg-surface-800 text-surface-700 dark:text-surface-300'
              }`}
            >
              {showLatency ? 'Hide Latency' : 'Show Latency'}
            </button>
            <Link
              href="/app/trading"
              className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 transition-colors"
            >
              New Order
            </Link>
          </div>
        </div>

        {/* Connection Status & Resync Banner */}
        {(connectionStatus.status === 'resyncing' || isActiveResyncing) && (
          <div className="mb-4 p-3 bg-blue-50 dark:bg-blue-900/30 border border-blue-200 dark:border-blue-800 rounded-lg flex items-center justify-between">
            <div className="flex items-center gap-2 text-blue-800 dark:text-blue-200">
              <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none"/>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
              </svg>
              <span className="font-medium">Resyncing order book for {activeSymbol}...</span>
            </div>
            <button
              onClick={() => resync(activeSymbol)}
              className="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700"
            >
              Force Resync
            </button>
          </div>
        )}

        {/* Portfolio Summary Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
          <StatCard
            title="Total Value"
            value="$125,432.10"
            change="+2.34%"
            changePositive={true}
            icon={<DollarSignIcon />}
          />
          <StatCard
            title="Day P&L"
            value="+$1,234.56"
            change="+1.89%"
            changePositive={true}
            icon={<TrendingUpIcon />}
          />
          <StatCard
            title="Open Positions"
            value="12"
            change="3 new today"
            changePositive={true}
            icon={<BriefcaseIcon />}
          />
          <StatCard
            title="Win Rate"
            value="68.5%"
            change="vs 65% avg"
            changePositive={true}
            icon={<ChartBarIcon />}
          />
        </div>

        {/* Main Content Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 mb-6">
          {/* Left Sidebar - Watchlist & Symbol Selector */}
          <div className="lg:col-span-3 space-y-6">
            {/* Symbol Selector */}
            <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
              <h3 className="font-semibold text-surface-900 dark:text-surface-50 mb-3">Select Symbol</h3>
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

            {/* Watchlist */}
            <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800">
              <div className="p-4 border-b border-surface-200 dark:border-surface-800 flex items-center justify-between">
                <h3 className="font-semibold text-surface-900 dark:text-surface-50">Watchlist</h3>
                <span className="text-xs text-surface-500">Live</span>
              </div>
              <div className="divide-y divide-surface-200 dark:divide-surface-800">
                {WATCHLIST_SYMBOLS.map((symbol) => (
                  <WatchlistItem 
                    key={symbol} 
                    symbol={symbol} 
                    book={books.get(symbol)} 
                    active={activeSymbol === symbol}
                    onClick={() => setActiveSymbol(symbol)}
                  />
                ))}
              </div>
            </div>
          </div>

          {/* Main Content Area */}
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
                  {activeMetrics && (
                    <div className="flex items-center gap-4 ml-auto">
                      <div className="text-right">
                        <p className="text-xs text-surface-500 dark:text-surface-400">Mid</p>
                        <p className="font-bold text-surface-900 dark:text-surface-50">
                          {activeMetrics.mid ? activeMetrics.mid.toLocaleString() : '—'}
                        </p>
                      </div>
                      <div className="text-right">
                        <p className="text-xs text-surface-500 dark:text-surface-400">Spread</p>
                        <p className="font-bold text-surface-900 dark:text-surface-50">
                          {activeMetrics.spread ? `${activeMetrics.spread.toFixed(1)} bps` : '—'}
                        </p>
                      </div>
                      <div className="text-right">
                        <p className="text-xs text-surface-500 dark:text-surface-400">Imbalance</p>
                        <p className={`font-bold ${activeMetrics.imbalanceTopN > 0 ? 'text-green-600' : activeMetrics.imbalanceTopN < 0 ? 'text-red-600' : 'text-surface-900 dark:text-surface-50'}`}>
                          {activeMetrics.imbalanceTopN !== undefined ? activeMetrics.imbalanceTopN.toFixed(3) : '—'}
                        </p>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* Main Chart Row */}
            <div className="grid grid-cols-1 lg:grid-cols-8 gap-6">
              {/* Candles Chart - 5 cols */}
              <div className="lg:col-span-5">
                <CandlesCanvas 
                  symbol={activeSymbol} 
                  height={400}
                  timeframe="5m"
                />
              </div>

              {/* Order Book + Depth - 3 cols */}
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

            {/* Order Book Detail + Trades Tape */}
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

            {/* Metrics Panel */}
            <MetricsPanel 
              symbol={activeSymbol} 
              compact={false}
            />
          </div>
        </div>
      </div>
    </div>
  );
}

// Components
function StatCard({ title, value, change, changePositive, icon }: {
  title: string;
  value: string;
  change: string;
  changePositive: boolean;
  icon: React.ReactNode;
}) {
  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm text-surface-600 dark:text-surface-400">{title}</p>
          <p className="mt-1 text-2xl font-bold text-surface-900 dark:text-surface-50">{value}</p>
        </div>
        <div className="p-3 rounded-lg bg-primary-100 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400">
          {icon}
        </div>
      </div>
      <div className="mt-4 flex items-center gap-1">
        <span className={`text-sm font-medium ${changePositive ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'}`}>
          {changePositive ? '↑' : '↓'} {change}
        </span>
        <span className="text-sm text-surface-500 dark:text-surface-500 ml-auto">vs 24h ago</span>
      </div>
    </div>
  );
}

function WatchlistItem({ symbol, book, active, onClick }: { 
  symbol: string; 
  book: any; 
  active: boolean;
  onClick: () => void;
}) {
  const base = symbol.split(':')[1]?.split('-')[0] || symbol;
  const quote = symbol.split('-')[1] || 'USDT';
  const mid = book?.bids[0] && book?.asks[0]
    ? (parseFloat(book.bids[0].price) + parseFloat(book.asks[0].price)) / 2
    : null;
  const spread = book?.bids[0] && book?.asks[0]
    ? ((parseFloat(book.asks[0].price) - parseFloat(book.bids[0].price)) / mid! * 10000).toFixed(1)
    : null;

  return (
    <button
      onClick={onClick}
      className={`w-full p-3 hover:bg-surface-50 dark:hover:bg-surface-800/50 flex items-center justify-between transition-colors ${
        active ? 'bg-primary-50 dark:bg-primary-900/30' : ''
      }`}
    >
      <div className="flex items-center gap-3">
        <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
          <span className="text-xs font-bold text-primary-700 dark:text-primary-400">
            {base.charAt(0)}
          </span>
        </div>
        <div>
          <p className="font-medium text-surface-900 dark:text-surface-50">{base}/{quote}</p>
          <p className="text-xs text-surface-500 dark:text-surface-500">{symbol}</p>
        </div>
      </div>
      <div className="text-right">
        <p className="font-mono font-medium text-surface-900 dark:text-surface-50">
          {mid ? `$${mid.toLocaleString()}` : '—'}
        </p>
        {spread && (
          <p className="text-xs text-surface-500 dark:text-surface-500">Spread: {spread} bps</p>
        )}
      </div>
    </button>
  );
}

// Icons
function DollarSignIcon() {
  return (
    <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.799 1.121l.717-.717a17.53 17.53 0 00-7.596 0l-.717.717A17.54 17.54 0 006 13.5c0 1.547.486 2.996 1.321 4.243l.717.717a17.54 17.54 0 005.479 0l.717-.717c.835-1.247 1.321-2.696 1.321-4.243 0-1.547-.486-2.996-1.321-4.243l-.717-.717A17.54 17.54 0 0012 5.5z" />
    </svg>
  );
}

function TrendingUpIcon() {
  return (
    <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
    </svg>
  );
}

function BriefcaseIcon() {
  return (
    <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 13.255A23.931 23.931 0 0112 15c-3.183 0-6.22-.62-9-1.745M16 6V4a2 2 0 00-2-2h-4a2 2 0 00-2 2v2m4 6h.01M5 20h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
    </svg>
  );
}

function ChartBarIcon() {
  return (
    <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
    </svg>
  );
}