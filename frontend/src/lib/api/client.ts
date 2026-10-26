'use client';

import { authClient } from '@/lib/auth';
import type {
  InstrumentSpec,
  ListInstrumentsResponse,
  BookUpdate,
  Trade,
  Metrics,
  HealthResponse,
  SnapshotRequest,
  CreateOrderRequest,
  OrderPreviewRequest,
  OrderPreviewResponse,
  OrderResponse,
  OrderListResponse,
  FillsResponse,
  PortfolioResponse,
  LedgerResponse,
  CreateAlertRequest,
  UpdateAlertRequest,
  AlertResponse,
  AlertListResponse,
  CreateBacktestRequest,
  BacktestResult,
  BacktestListResponse,
  BacktestTradeLogResponse,
  BacktestSummary,
} from './types';
import { parseBalancesResponse } from './balances';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';

async function request<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<T> {
  return authClient.requestWithAuth<T>(endpoint, options);
}

// Market Data
export const marketApi = {
  listInstruments: () => request<ListInstrumentsResponse>('/instruments'),

  getSnapshot: (symbol: string, depth = 20) =>
    request<BookUpdate>(`/market/${encodeURIComponent(symbol)}/snapshot?depth=${depth}`),

  getMetrics: (symbol: string) =>
    request<Metrics>(`/market/${encodeURIComponent(symbol)}/metrics`),

  getTrades: (symbol: string, limit = 50) =>
    request<Trade[]>(`/market/${encodeURIComponent(symbol)}/trades?limit=${limit}`),

  getHealth: () => request<HealthResponse>('/system/health'),
};

// Trading
export const tradingApi = {
  createOrder: (order: CreateOrderRequest, idempotencyKey: string) =>
    request<OrderResponse>('/orders', {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(order),
    }),

  previewOrder: (order: OrderPreviewRequest) =>
    request<OrderPreviewResponse>('/orders/preview', {
      method: 'POST',
      body: JSON.stringify(order),
    }),

  listOrders: (params?: {
    status?: string;
    symbol?: string;
    limit?: number;
    cursor?: string;
  }) => {
    const search = new URLSearchParams();
    if (params?.status) search.set('status', params.status);
    if (params?.symbol) search.set('symbol', params.symbol);
    if (params?.limit) search.set('limit', params.limit.toString());
    if (params?.cursor) search.set('cursor', params.cursor);
    return request<OrderListResponse>(`/orders?${search.toString()}`);
  },

  getOrder: (orderId: string) =>
    request<OrderResponse>(`/orders/${orderId}`),

  cancelOrder: (orderId: string) =>
    request<OrderResponse>(`/orders/${orderId}`, {
      method: 'DELETE',
    }),

  listFills: (limit = 50, cursor?: string) => {
    const search = new URLSearchParams();
    search.set('limit', limit.toString());
    if (cursor) search.set('cursor', cursor);
    return request<FillsResponse>(`/fills?${search.toString()}`);
  },
};

// Portfolio
export const portfolioApi = {
  getPortfolio: () => request<PortfolioResponse>('/portfolio'),
  getBalances: async () => parseBalancesResponse(await request<unknown>('/portfolio/balances')),
  getLedger: (params?: { asset?: string; limit?: number; cursor?: string }) => {
    const search = new URLSearchParams();
    if (params?.asset) search.set('asset', params.asset);
    if (params?.limit) search.set('limit', params.limit.toString());
    if (params?.cursor) search.set('cursor', params.cursor);
    return request<LedgerResponse>(`/ledger?${search.toString()}`);
  },
};

// Alerts
export const alertsApi = {
  createAlert: (alert: CreateAlertRequest) =>
    request<AlertResponse>('/alerts', {
      method: 'POST',
      body: JSON.stringify(alert),
    }),

  listAlerts: (limit = 50, cursor?: string) => {
    const search = new URLSearchParams();
    search.set('limit', limit.toString());
    if (cursor) search.set('cursor', cursor);
    return request<AlertListResponse>(`/alerts?${search.toString()}`);
  },

  getAlert: (alertId: string) =>
    request<AlertResponse>(`/alerts/${alertId}`),

  updateAlert: (alertId: string, update: UpdateAlertRequest) =>
    request<AlertResponse>(`/alerts/${alertId}`, {
      method: 'PATCH',
      body: JSON.stringify(update),
    }),

  deleteAlert: (alertId: string) =>
    request<void>(`/alerts/${alertId}`, {
      method: 'DELETE',
    }),
};

// Research / Backtesting
export const researchApi = {
  createBacktest: (backtestRequest: CreateBacktestRequest, idempotencyKey: string) =>
    request<{ backtest_id: string; status: string; message: string }>('/backtests', {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(backtestRequest),
    }),

  listBacktests: (limit = 50, cursor?: string) => {
    const search = new URLSearchParams();
    search.set('limit', limit.toString());
    if (cursor) search.set('cursor', cursor);
    return request<BacktestListResponse>(`/backtests?${search.toString()}`);
  },

  getBacktest: (backtestId: string) =>
    request<BacktestResult>(`/backtests/${backtestId}`),

  getBacktestResult: (backtestId: string) =>
    request<BacktestResult>(`/backtests/${backtestId}/result`),

  getTradeLog: (backtestId: string, limit = 50, cursor?: string) => {
    const search = new URLSearchParams();
    search.set('limit', limit.toString());
    if (cursor) search.set('cursor', cursor);
    return request<BacktestTradeLogResponse>(`/backtests/${backtestId}/trades?${search.toString()}`);
  },

  // Job management
  submitJob: (type: 'backtest' | 'walkforward' | 'sensitivity', payload: unknown) =>
    request<{ job_id: string; status: string; message: string }>(`/backtests/${type}`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  getJobStatus: (jobId: string) =>
    request<{ job_id: string; status: string; progress: number; message: string }>(`/backtests/${jobId}`),

  listJobs: (status?: string, limit = 50, offset = 0) => {
    const search = new URLSearchParams();
    if (status) search.set('status', status);
    search.set('limit', limit.toString());
    search.set('offset', offset.toString());
    return request<{ jobs: Array<{ job_id: string; type: string; status: string; created_at: string }> }>(
      `/backtests?${search.toString()}`
    );
  },
};

// WebSocket ticket
export const wsApi = {
  getTicket: () =>
    request<{ ticket: string }>('/auth/ws/ticket', {
      method: 'POST',
    }),
};

// Utility to generate idempotency keys
export function generateIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).substring(2, 15)}`;
}
