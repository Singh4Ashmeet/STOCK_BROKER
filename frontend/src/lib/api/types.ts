// Generated from Java OpenAPI spec
// Run: npm run generate:api to regenerate

export interface InstrumentSpec {
  symbol: string;
  venue: string;
  base_asset: string;
  quote_asset: string;
  price_scale: number;
  qty_scale: number;
  tick_size: string;
  lot_size: string;
  min_notional?: string;
  ws_numeric_safe: boolean;
}

export interface ListInstrumentsResponse {
  instruments: InstrumentSpec[];
}

export interface Level {
  price: string;
  qty: string;
}

export interface BookSnapshot {
  bids: Level[];
  asks: Level[];
}

export interface BookDelta {
  bids: Level[];
  asks: Level[];
}

export interface Timing {
  exchange_ts_ns: string;
  recv_ts_ns: string;
  publish_ts_ns: string;
  gateway_send_ts_ns?: string;
}

export interface BookUpdate {
  symbol: string;
  epoch: number;
  seq: number;
  timing: Timing;
  snapshot: BookSnapshot;
  delta: BookDelta;
  book_valid: boolean;
}

export interface Trade {
  symbol: string;
  epoch: number;
  seq: number;
  timing: Timing;
  price: string;
  qty: string;
  side: string;
  venue_trade_id: string;
}

export interface DepthPoint {
  levels: number;
  bid_qty?: string;
  ask_qty?: string;
}

export interface Metrics {
  symbol: string;
  epoch: number;
  seq: number;
  timing: Timing;
  best_bid?: string;
  best_ask?: string;
  best_bid_qty?: string;
  best_ask_qty?: string;
  spread?: string;
  mid: number;
  microprice: number;
  imbalance_top_n: number;
  rolling_vwap: number;
  trade_flow_imbalance: number;
  depth: DepthPoint[];
  book_valid: boolean;
}

export interface SnapshotRequest {
  symbol: string;
  depth_levels: number;
}

export interface SymbolHealth {
  symbol: string;
  book_valid: boolean;
  epoch: number;
  resync_count: number;
  last_event_age_ms: number;
}

export interface HealthResponse {
  connected: boolean;
  symbols: SymbolHealth[];
  build_version: string;
}

export interface SubscribeRequest {
  symbols: string[];
  depth_levels: number;
  metrics_levels: number;
}

// Trading types
export type OrderSide = 'BUY' | 'SELL';
export type OrderType = 'LIMIT' | 'MARKET';
export type OrderStatus = 'NEW' | 'ACCEPTED' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELED' | 'REJECTED';
export type TimeInForce = 'GTC' | 'IOC' | 'FOK' | 'DAY';

export interface CreateOrderRequest {
  symbol: string;
  side: OrderSide;
  type: OrderType;
  qty: string;
  limit_price?: string;
  tif: TimeInForce;
}

export interface OrderPreviewRequest {
  symbol: string;
  side: OrderSide;
  type: OrderType;
  qty: string;
  limit_price?: string;
  tif: TimeInForce;
}

export interface OrderPreviewResponse {
  est_fill_price: string;
  est_fees: string;
  est_slippage_bps: number;
  book_valid: boolean;
  levels_consumed: number;
}

export interface Level {
  price: string;
  qty: string;
}

export interface FillResponse {
  fill_id: string;
  price: string;
  qty: string;
  fee_asset: string;
  fee_amount: string;
  liquidity: string;
  book_epoch: number;
  book_seq: number;
  filled_at: string;
}

export interface OrderResponse {
  order_id: string;
  symbol: string;
  side: OrderSide;
  type: OrderType;
  status: OrderStatus;
  qty: string;
  filled_qty: string;
  avg_fill_price?: string;
  limit_price?: string;
  tif: TimeInForce;
  reject_reason?: string;
  created_at: string;
  updated_at: string;
  fills: FillResponse[];
}

export interface OrderListResponse {
  orders: OrderResponse[];
  next_cursor?: string;
}

export interface FillsResponse {
  fills: FillResponse[];
  next_cursor?: string;
}

// Portfolio types
export interface Position {
  symbol: string;
  qty: string;
  avg_price: string;
  market_value: string;
  unrealized_pnl: string;
  realized_pnl: string;
}

export interface PortfolioResponse {
  positions: Position[];
  total_value: string;
  total_pnl: string;
  cash_balance: string;
  currency: string;
}

export interface Balance {
  asset: string;
  available: string;
  locked: string;
  total: string;
}

export interface LedgerEntry {
  id: string;
  timestamp: string;
  type: string;
  asset: string;
  amount: string;
  balance_after: string;
  description: string;
  reference_id?: string;
}

export interface LedgerResponse {
  entries: LedgerEntry[];
  next_cursor?: string;
}

// Alerts types
export type AlertMetric = 'PRICE' | 'SPREAD' | 'IMBALANCE';
export type AlertOperator = 'GT' | 'LT';
export type AlertStatus = 'ACTIVE' | 'TRIGGERED' | 'DISABLED';

export interface CreateAlertRequest {
  symbol: string;
  metric: AlertMetric;
  op: AlertOperator;
  threshold: string;
  cooldown_s: number;
}

export interface UpdateAlertRequest {
  status?: AlertStatus;
  cooldown_s?: number;
}

export interface AlertResponse {
  alert_id: string;
  symbol: string;
  metric: AlertMetric;
  op: AlertOperator;
  threshold: string;
  status: AlertStatus;
  cooldown_s: number;
  last_triggered_at?: string;
  created_at: string;
}

export interface AlertListResponse {
  alerts: AlertResponse[];
  next_cursor?: string;
}

// WebSocket types
export type WsClientMessage =
  | { op: 'subscribe'; channel: 'book' | 'trades' | 'metrics'; symbol: string; depth?: number }
  | { op: 'unsubscribe'; channel: 'book' | 'trades' | 'metrics'; symbol: string }
  | { op: 'resync'; channel: 'book'; symbol: string }
  | { op: 'ping'; id?: string };

export type WsServerMessage =
  | { t: 'book.snap'; ch: 'book'; s: string; ep: number; sq: number; tm: Timing; d: { b: [string, string][]; a: [string, string][] } }
  | { t: 'book.delta'; ch: 'book'; s: string; ep: number; sq: number; tm: Timing; d: { b: [string, string][]; a: [string, string][] } }
  | { t: 'trade'; ch: 'trades'; s: string; ep: number; sq: number; tm: Timing; d: { p: string; q: string; side: string; id: string } }
  | { t: 'metrics'; ch: 'metrics'; s: string; ep: number; sq: number; tm: Timing; d: Metrics }
  | { t: 'error'; d: { code: string; message: string } }
  | { t: 'pong'; id?: string };

// Research types
export type BacktestStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type OptimizationMethod = 'GRID' | 'RANDOM' | 'SOBOL';
export type WindowType = 'ROLLING' | 'ANCHORED';

export interface CreateBacktestRequest {
  strategy_name: string;
  symbol: string;
  start_date: string;
  end_date: string;
  params?: Record<string, string>;
  idempotency_key: string;
  config_hash?: string;
  data_fingerprint?: string;
  git_commit?: string;
}

export interface BacktestSummary {
  total_return: number;
  annualized_return: number;
  volatility: number;
  sharpe_ratio: number;
  sortino_ratio: number;
  max_drawdown: number;
  calmar_ratio: number;
  win_rate: number;
  profit_factor: number;
  total_trades: number;
  avg_trade_pnl: number;
}

export interface BacktestResult {
  backtest_id: string;
  symbol: string;
  strategy_name: string;
  start_date: string;
  end_date: string;
  config_hash: string;
  data_fingerprint: string;
  code_version: string;
  pybook_version: string;
  seed: number;
  summary: BacktestSummary;
  artifact_path: string;
  created_at: string;
  finished_at: string;
}

export interface BacktestListResponse {
  backtests: BacktestResult[];
  next_cursor?: string;
}

export interface TradeLogEntry {
  timestamp: string;
  symbol: string;
  side: string;
  price: string;
  qty: string;
  fee: string;
  pnl: string;
}

export interface BacktestTradeLogResponse {
  trades: TradeLogEntry[];
  next_cursor?: string;
}

// Error types
export interface ApiError {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  parameters?: Record<string, unknown>;
}