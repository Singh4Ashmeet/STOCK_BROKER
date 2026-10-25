'use client';

import { useEffect, useState } from 'react';
import { useAuth } from '@/lib/auth';
import { useMarketStore, selectBook } from '@/lib/store';
import { useWebSocketWorker } from '@/lib/hooks/useWebSocketWorker';
import { marketApi } from '@/lib/api';
import { OrderBookCanvas, DepthChartCanvas, TradesTapeCanvas, CandlesCanvas, MetricsPanel, LatencyOverlay } from '@/components/charts';
import { formatNumber, formatPercent } from '@/lib/utils';
import Link from 'next/link';

const DEFAULT_WATCHLIST = ['BINANCE:BTC-USDT', 'BINANCE:ETH-USDT', 'BINANCE:SOL-USDT', 'BINANCE:BNB-USDT', 'BINANCE:ADA-USDT', 'BINANCE:DOT-USDT', 'BINANCE:LINK-USDT', 'BINANCE:MATIC-USDT'];

export default function WatchlistPage() {
  const { user } = useAuth();
  const { connect, subscribe, unsubscribe, connectionStatus } = useWebSocketWorker();
  const [watchlist, setWatchlist] = useState<string[]>(DEFAULT_WATCHLIST);
  const [activeSymbol, setActiveSymbol] = useState<string | null>(null);
  const [showDetail, setShowDetail] = useState(false);
  const [showLatency, setShowLatency] = useState(true);
  const [searchSymbol, setSearchSymbol] = useState('');
  const [searchResults, setSearchResults] = useState<string[]>([]);
  const [searching, setSearching] = useState(false);

  const books = useMarketStore((state) => state.books);
  const trades = useMarketStore((state) => state.trades);
  const metrics = useMarketStore((state) => state.metrics);

  useEffect(() => {
    connect();
    watchlist.forEach(symbol => {
      subscribe('book', symbol, 10);
      subscribe('trades', symbol);
      subscribe('metrics', symbol);
    });
    return () => {
      watchlist.forEach(symbol => {
        unsubscribe('book', symbol);
        unsubscribe('trades', symbol);
        unsubscribe('metrics', symbol);
      });
    };
  }, [connect, subscribe, unsubscribe, watchlist]);

  const handleSearch = async () => {
    if (!searchSymbol.trim()) return;
    setSearching(true);
    try {
      const instruments = await marketApi.listInstruments();
      const filtered = instruments.instruments
        .filter(inst => inst.symbol.toLowerCase().includes(searchSymbol.toLowerCase()))
        .map(inst => inst.symbol)
        .slice(0, 10);
      setSearchResults(filtered);
    } catch (error) {
      console.error('Search error:', error);
      setSearchResults([]);
    } finally {
      setSearching(false);
    }
  };

  const addToWatchlist = (symbol: string) => {
    if (!watchlist.includes(symbol)) {
      setWatchlist([...watchlist, symbol]);
      subscribe('book', symbol, 10);
      subscribe('trades', symbol);
      subscribe('metrics', symbol);
    }
  };

  const removeFromWatchlist = (symbol: string) => {
    setWatchlist(watchlist.filter(s => s !== symbol));
    unsubscribe('book', symbol);
    unsubscribe('trades', symbol);
    unsubscribe('metrics', symbol);
    if (activeSymbol === symbol) {
      setActiveSymbol(watchlist.filter(s => s !== symbol)[0] || null);
    }
  };

  if (!activeSymbol && watchlist.length > 0) {
    setActiveSymbol(watchlist[0]);
  }

  const activeBook = activeSymbol ? books.get(activeSymbol) : null;
  const activeTrades = activeSymbol ? trades.get(activeSymbol) || [] : [];
  const activeMetrics = activeSymbol ? metrics.get(activeSymbol)?.metrics : null;

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-950">
      {showLatency && <LatencyOverlay position="top-right" showDetails={false} compact />}
      
      <div className="p-4 sm:p-6 lg:p-8">
        {/* Header */}
        <div className="mb-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">Watchlist</h1>
            <p className="mt-1 text-surface-600 dark:text-surface-400">Monitor your favorite markets</p>
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

        <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
          {/* Left Sidebar - Watchlist */}
          <div className="lg:col-span-1 space-y-6">
            {/* Search & Add */}
            <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
              <h3 className="font-semibold text-surface-900 dark:text-surface-50 mb-3">Add Symbol</h3>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={searchSymbol}
                  onChange={(e) => setSearchSymbol(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                  placeholder="Search (e.g. BTC-USDT)"
                  className="flex-1 rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                />
                <button
                  onClick={handleSearch}
                  disabled={searching}
                  className="px-4 py-2 rounded-lg bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
                >
                  {searching ? '...' : 'Search'}
                </button>
              </div>
              {searchResults.length > 0 && (
                <div className="mt-3 space-y-1">
                  {searchResults.map(symbol => (
                    <button
                      key={symbol}
                      onClick={() => addToWatchlist(symbol)}
                      className={`w-full flex items-center justify-between p-2 rounded-lg text-left transition-colors ${
                        watchlist.includes(symbol)
                          ? 'bg-green-50 dark:bg-green-900/30 text-green-700 dark:text-green-300'
                          : 'text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800'
                      }`}
                      disabled={watchlist.includes(symbol)}
                    >
                      <span>{symbol}</span>
                      {watchlist.includes(symbol) && <span className="text-xs text-green-600">Added</span>}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Watchlist */}
            <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800">
              <div className="p-4 border-b border-surface-200 dark:border-surface-800 flex items-center justify-between">
                <h3 className="font-semibold text-surface-900 dark:text-surface-50">My Watchlist</h3>
                <span className="text-xs text-surface-500">{watchlist.length} symbols</span>
              </div>
              <div className="divide-y divide-surface-200 dark:divide-surface-800">
                {watchlist.length === 0 ? (
                  <div className="p-8 text-center text-surface-500">
                    <p>Your watchlist is empty</p>
                    <p className="text-sm mt-1">Search and add symbols above</p>
                  </div>
                ) : (
                  watchlist.map(symbol => (
                    <WatchlistItem
                      key={symbol}
                      symbol={symbol}
                      book={books.get(symbol)}
                      active={activeSymbol === symbol}
                      onClick={() => { setActiveSymbol(symbol); setShowDetail(true); }}
                      onRemove={() => removeFromWatchlist(symbol)}
                    />
                  ))
                )}
              </div>
            </div>
          </div>

          {/* Detail View */}
          <div className="lg:col-span-3 space-y-6">
            {showDetail && activeSymbol ? (
              <div className="space-y-6">
                {/* Symbol Header */}
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
                              {activeMetrics.spread ? `${parseFloat(activeMetrics.spread).toFixed(1)} bps` : '—'}
                            </p>
                          </div>
                          <div className="text-right">
                            <p className="text-xs text-surface-500 dark:text-surface-400">Imbalance</p>
                            <p className={`font-bold ${activeMetrics.imbalance_top_n > 0 ? 'text-green-600' : activeMetrics.imbalance_top_n < 0 ? 'text-red-600' : 'text-surface-900 dark:text-surface-50'}`}>
                              {activeMetrics.imbalance_top_n !== undefined ? activeMetrics.imbalance_top_n.toFixed(3) : '—'}
                            </p>
                          </div>
                        </div>
                      )}
                    </div>
                    <div className="flex gap-2 mt-4 sm:mt-0 sm:ml-auto">
                      <button
                        onClick={() => setShowDetail(false)}
                        className="px-4 py-2 rounded-lg border border-surface-300 dark:border-surface-700 text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800"
                      >
                        Close Detail
                      </button>
                      <Link
                        href={`/app/trading?symbol=${encodeURIComponent(activeSymbol)}`}
                        className="px-4 py-2 rounded-lg bg-primary-600 text-white hover:bg-primary-700"
                      >
                        Trade
                      </Link>
                    </div>
                  </div>
                </div>

                {/* Charts */}
                <div className="grid grid-cols-1 lg:grid-cols-8 gap-6">
                  <div className="lg:col-span-5">
                    <CandlesCanvas 
                      symbol={activeSymbol} 
                      height={400}
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
            ) : (
              <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-12 text-center">
                <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
                  <svg className="w-8 h-8 text-primary-600 dark:text-primary-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                  </svg>
                </div>
                <h2 className="text-2xl font-bold text-surface-900 dark:text-surface-50 mb-2">Select a symbol</h2>
                <p className="text-surface-600 dark:text-surface-400 mb-6">Click on a symbol in your watchlist to view detailed charts and metrics</p>
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 max-w-2xl mx-auto">
                  {watchlist.slice(0, 8).map(symbol => (
                    <button
                      key={symbol}
                      onClick={() => { setActiveSymbol(symbol); setShowDetail(true); }}
                      className="p-4 rounded-lg border border-surface-200 dark:border-surface-800 bg-white dark:bg-surface-900 hover:bg-surface-50 dark:hover:bg-surface-800/50 transition-colors text-left"
                    >
                      <p className="font-semibold text-surface-900 dark:text-surface-50">
                        {symbol.split(':')[1]?.split('-')[0] || symbol}
                      </p>
                      <p className="text-sm text-surface-500 dark:text-surface-400">{symbol}</p>
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function WatchlistItem({ symbol, book, active, onClick, onRemove }: { 
  symbol: string; 
  book: any; 
  active: boolean;
  onClick: () => void;
  onRemove: () => void;
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
      <div className="flex items-center gap-3 flex-1">
        <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
          <span className="text-xs font-bold text-primary-700 dark:text-primary-400">
            {base.charAt(0)}
          </span>
        </div>
        <div className="min-w-0">
          <p className="font-medium text-surface-900 dark:text-surface-50 truncate">{base}/{quote}</p>
          <p className="text-xs text-surface-500 dark:text-surface-500 truncate">{symbol}</p>
        </div>
      </div>
      <div className="flex items-center gap-2">
        <div className="text-right">
          <p className="font-mono font-medium text-surface-900 dark:text-surface-50">
            {mid ? `$${mid.toLocaleString()}` : '—'}
          </p>
          {spread && (
            <p className="text-xs text-surface-500 dark:text-surface-500">Spread: {spread} bps</p>
          )}
        </div>
        <button
          onClick={(e) => { e.stopPropagation(); onRemove(); }}
          className="p-1 rounded hover:bg-red-100 dark:hover:bg-red-900/30 text-red-500"
          aria-label="Remove from watchlist"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
    </button>
  );
}
