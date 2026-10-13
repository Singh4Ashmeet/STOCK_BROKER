import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type { BookUpdate, Trade, Metrics, Level, Timing } from '@/lib/api';

// ============================================
// Types
// ============================================

interface BookState {
  symbol: string;
  epoch: number;
  seq: number;
  bids: Level[];
  asks: Level[];
  timestamp: number;
  bookValid: boolean;
  resyncing: boolean;
  lastResyncAt: number | null;
}

interface TradeState {
  trades: Trade[];
  maxTrades: number;
}

interface MetricsState {
  metrics: Metrics | null;
  timestamp: number;
}

interface LatencySample {
  clientSent: number;
  serverSent: number;
  received: number;
  fanOutLatency: number; // received - serverSent
  roundTripLatency: number; // received - clientSent (if available)
}

interface ConnectionState {
  status: 'disconnected' | 'connecting' | 'connected' | 'error' | 'resyncing';
  error: string | null;
  reconnectAttempts: number;
  lastConnectedAt: number | null;
  lastDisconnectedAt: number | null;
}

interface PerformanceMetrics {
  fps: number;
  frameTimes: number[];
  lastFrameTime: number;
  messageRate: number; // messages/second
  lastMessageCount: number;
  lastMessageTime: number;
  latencySamples: LatencySample[];
  maxLatencySamples: number;
}

interface MarketStore {
  // Books
  books: Map<string, BookState>;
  
  // Trades
  trades: Map<string, TradeState>;
  
  // Metrics
  metrics: Map<string, MetricsState>;
  
  // Connection
  connection: ConnectionState;
  
  // Performance
  performance: PerformanceMetrics;
  
  // Last update timestamp
  lastUpdate: number;
  
  // Subscriptions
  subscriptions: Map<string, { channel: 'book' | 'trades' | 'metrics'; symbol: string; depth?: number }>;
  
  // Actions
  setBook: (symbol: string, book: BookUpdate) => void;
  applyDelta: (symbol: string, delta: BookUpdate) => void;
  setResyncing: (symbol: string, resyncing: boolean) => void;
  addTrade: (symbol: string, trade: Trade) => void;
  setMetrics: (symbol: string, metrics: Metrics) => void;
  setConnectionStatus: (status: ConnectionState['status'], error?: string) => void;
  incrementReconnectAttempts: () => void;
  resetReconnectAttempts: () => void;
  recordConnected: () => void;
  recordDisconnected: () => void;
  addSubscription: (key: string, sub: { channel: 'book' | 'trades' | 'metrics'; symbol: string; depth?: number }) => void;
  removeSubscription: (key: string) => void;
  clearSymbol: (symbol: string) => void;
  clearAll: () => void;
  
  // Performance
  recordFrame: (frameTime: number) => void;
  recordMessage: (message: any, received: number, serverSent?: number, clientSent?: number) => void;
  getLatencyStats: () => { p50: number; p95: number; p99: number; avg: number; max: number };
  getFPS: () => number;
  getMessageRate: () => number;
  
  // Selectors
  getBook: (symbol: string) => BookState | undefined;
  getTrades: (symbol: string) => Trade[];
  getMetrics: (symbol: string) => MetricsState | undefined;
  getConnectionStatus: () => ConnectionState;
  getPerformanceMetrics: () => PerformanceMetrics;
  isResyncing: (symbol: string) => boolean;
}

const MAX_TRADES_PER_SYMBOL = 500;
const MAX_LATENCY_SAMPLES = 1000;
const MAX_FRAME_TIMES = 120; // 2 seconds at 60fps

export const useMarketStore = create<MarketStore>()(
  subscribeWithSelector((set, get) => ({
    // Initial state
    books: new Map(),
    trades: new Map(),
    metrics: new Map(),
    connection: {
      status: 'disconnected',
      error: null,
      reconnectAttempts: 0,
      lastConnectedAt: null,
      lastDisconnectedAt: null,
    },
    performance: {
      fps: 0,
      frameTimes: [],
      lastFrameTime: 0,
      messageRate: 0,
      lastMessageCount: 0,
      lastMessageTime: performance.now(),
      latencySamples: [],
      maxLatencySamples: MAX_LATENCY_SAMPLES,
    },
    lastUpdate: 0,
    subscriptions: new Map(),
    
    // Book actions
    setBook: (symbol, book) => {
      const bids = book.snapshot?.bids
        ?.map(l => ({ price: l.price, qty: l.qty }))
        .sort((a, b) => parseFloat(b.price) - parseFloat(a.price)) || [];
      const asks = book.snapshot?.asks
        ?.map(l => ({ price: l.price, qty: l.qty }))
        .sort((a, b) => parseFloat(a.price) - parseFloat(b.price)) || [];
      
      set(state => ({
        books: new Map(state.books).set(symbol, {
          symbol,
          epoch: book.epoch,
          seq: book.seq,
          bids,
          asks,
          timestamp: Date.now(),
          bookValid: book.book_valid,
          resyncing: false,
          lastResyncAt: Date.now(),
        }),
        lastUpdate: Date.now(),
      }));
    },
    
    applyDelta: (symbol, delta) => {
      const existing = get().books.get(symbol);
      if (!existing) return;
      
      const newBids = new Map(existing.bids.map(l => [l.price, l]));
      const newAsks = new Map(existing.asks.map(l => [l.price, l]));
      
      delta.delta?.bids?.forEach(level => {
        const qty = parseFloat(level.qty);
        if (qty === 0) {
          newBids.delete(level.price);
        } else {
          newBids.set(level.price, { price: level.price, qty: level.qty });
        }
      });
      
      delta.delta?.asks?.forEach(level => {
        const qty = parseFloat(level.qty);
        if (qty === 0) {
          newAsks.delete(level.price);
        } else {
          newAsks.set(level.price, { price: level.price, qty: level.qty });
        }
      });
      
      const bids = Array.from(newBids.values())
        .sort((a, b) => parseFloat(b.price) - parseFloat(a.price));
      const asks = Array.from(newAsks.values())
        .sort((a, b) => parseFloat(a.price) - parseFloat(b.price));
      
      set(state => ({
        books: new Map(state.books).set(symbol, {
          ...existing,
          epoch: delta.epoch,
          seq: delta.seq,
          bids,
          asks,
          timestamp: Date.now(),
          bookValid: delta.book_valid,
        }),
        lastUpdate: Date.now(),
      }));
    },
    
    setResyncing: (symbol, resyncing) => {
      set(state => {
        const book = state.books.get(symbol);
        if (!book) return state;
        return {
          books: new Map(state.books).set(symbol, {
            ...book,
            resyncing,
            lastResyncAt: resyncing ? Date.now() : book.lastResyncAt,
          }),
        };
      });
    },
    
    addTrade: (symbol, trade) => {
      set(state => {
        const existing = state.trades.get(symbol) || { trades: [], maxTrades: MAX_TRADES_PER_SYMBOL };
        const newTrades = [trade, ...existing.trades].slice(0, MAX_TRADES_PER_SYMBOL);
        return {
          trades: new Map(state.trades).set(symbol, { trades: newTrades, maxTrades: MAX_TRADES_PER_SYMBOL }),
          lastUpdate: Date.now(),
        };
      });
    },
    
    setMetrics: (symbol, metrics) => {
      set(state => ({
        metrics: new Map(state.metrics).set(symbol, {
          metrics,
          timestamp: Date.now(),
        }),
        lastUpdate: Date.now(),
      }));
    },
    
    // Connection actions
    setConnectionStatus: (status, error = null) => {
      set(state => ({
        connection: {
          ...state.connection,
          status,
          error,
        },
      }));
    },
    
    incrementReconnectAttempts: () => {
      set(state => ({
        connection: {
          ...state.connection,
          reconnectAttempts: state.connection.reconnectAttempts + 1,
        },
      }));
    },
    
    resetReconnectAttempts: () => {
      set(state => ({
        connection: {
          ...state.connection,
          reconnectAttempts: 0,
        },
      }));
    },
    
    recordConnected: () => {
      set(state => ({
        connection: {
          ...state.connection,
          status: 'connected',
          error: null,
          reconnectAttempts: 0,
          lastConnectedAt: Date.now(),
        },
      }));
    },
    
    recordDisconnected: () => {
      set(state => ({
        connection: {
          ...state.connection,
          status: 'disconnected',
          lastDisconnectedAt: Date.now(),
        },
      }));
    },
    
    // Subscription actions
    addSubscription: (key, sub) => {
      set(state => ({
        subscriptions: new Map(state.subscriptions).set(key, sub),
      }));
    },
    
    removeSubscription: (key) => {
      set(state => {
        const subs = new Map(state.subscriptions);
        subs.delete(key);
        return { subscriptions: subs };
      });
    },
    
    // Clear actions
    clearSymbol: (symbol) => {
      set(state => {
        const books = new Map(state.books);
        const trades = new Map(state.trades);
        const metrics = new Map(state.metrics);
        books.delete(symbol);
        trades.delete(symbol);
        metrics.delete(symbol);
        return { books, trades, metrics };
      });
    },
    
    clearAll: () => {
      set({
        books: new Map(),
        trades: new Map(),
        metrics: new Map(),
        subscriptions: new Map(),
        lastUpdate: 0,
      });
    },
    
    // Performance actions
    recordFrame: (frameTime: number) => {
      set(state => {
        const frameTimes = [...state.performance.frameTimes, frameTime].slice(-MAX_FRAME_TIMES);
        const now = performance.now();
        const timeWindow = now - frameTimes[0];
        const fps = timeWindow > 0 ? (frameTimes.length / timeWindow) * 1000 : 0;
        
        return {
          performance: {
            ...state.performance,
            fps,
            frameTimes,
            lastFrameTime: frameTime,
          },
        };
      });
    },
    
    recordMessage: (message, received, serverSent, clientSent) => {
      set(state => {
        const now = performance.now();
        const timeSinceLastMessage = now - state.performance.lastMessageTime;
        const messageRate = timeSinceLastMessage > 0 ? 1000 / timeSinceLastMessage : 0;
        
        const newLatencySamples = [...state.performance.latencySamples];
        if (serverSent) {
          const fanOutLatency = received - serverSent;
          const roundTripLatency = clientSent ? received - clientSent : 0;
          newLatencySamples.push({
            clientSent: clientSent || 0,
            serverSent,
            received,
            fanOutLatency,
            roundTripLatency,
          });
          if (newLatencySamples.length > MAX_LATENCY_SAMPLES) {
            newLatencySamples.shift();
          }
        }
        
        return {
          performance: {
            ...state.performance,
            messageRate,
            lastMessageCount: state.performance.lastMessageCount + 1,
            lastMessageTime: now,
            latencySamples: newLatencySamples,
          },
          lastUpdate: Date.now(),
        };
      });
    },
    
    getLatencyStats: () => {
      const samples = get().performance.latencySamples
        .map(s => s.fanOutLatency)
        .filter(l => l >= 0 && l < 1000)
        .sort((a, b) => a - b);
      
      if (samples.length === 0) {
        return { p50: 0, p95: 0, p99: 0, avg: 0, max: 0 };
      }
      
      const pct = (arr: number[], p: number) => {
        const idx = Math.min(Math.floor(arr.length * p / 100), arr.length - 1);
        return arr[idx];
      };
      
      const avg = samples.reduce((a, b) => a + b, 0) / samples.length;
      
      return {
        p50: pct(samples, 50),
        p95: pct(samples, 95),
        p99: pct(samples, 99),
        avg,
        max: samples[samples.length - 1],
      };
    },
    
    getFPS: () => get().performance.fps,
    
    getMessageRate: () => get().performance.messageRate,
    
    // Selectors
    getBook: (symbol) => get().books.get(symbol),
    
    getTrades: (symbol) => get().trades.get(symbol)?.trades || [],
    
    getMetrics: (symbol) => get().metrics.get(symbol),
    
    getConnectionStatus: () => get().connection,
    
    getPerformanceMetrics: () => get().performance,
    
    isResyncing: (symbol) => get().books.get(symbol)?.resyncing || false,
  }))
);

// Selector hooks for optimized re-renders
export const selectBook = (symbol: string) => (state: MarketStore) => state.books.get(symbol);
export const selectTrades = (symbol: string) => (state: MarketStore) => state.trades.get(symbol)?.trades || [];
export const selectMetrics = (symbol: string) => (state: MarketStore) => state.metrics.get(symbol);
export const selectConnectionStatus = (state: MarketStore) => state.connection;
export const selectPerformanceMetrics = (state: MarketStore) => state.performance;
export const selectIsResyncing = (symbol: string) => (state: MarketStore) => state.books.get(symbol)?.resyncing || false;
export const selectFPS = (state: MarketStore) => state.performance.fps;
export const selectMessageRate = (state: MarketStore) => state.performance.messageRate;
export const selectLatencyStats = (state: MarketStore) => {
  const samples = state.performance.latencySamples
    .map(s => s.fanOutLatency)
    .filter(l => l >= 0 && l < 1000)
    .sort((a, b) => a - b);
  
  if (samples.length === 0) return { p50: 0, p95: 0, p99: 0, avg: 0, max: 0 };
  
  const pct = (arr: number[], p: number) => arr[Math.min(Math.floor(arr.length * p / 100), arr.length - 1)];
  const avg = samples.reduce((a, b) => a + b, 0) / samples.length;
  
  return { p50: pct(samples, 50), p95: pct(samples, 95), p99: pct(samples, 99), avg, max: samples[samples.length - 1] };
};