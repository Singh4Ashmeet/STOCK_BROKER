'use client';

import { useEffect, useState } from 'react';
import { useAuth } from '@/lib/auth';
import { researchApi } from '@/lib/api';
import { formatNumber, formatCurrency, formatPercent, formatTimestamp } from '@/lib/utils';

interface BacktestResult {
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

interface BacktestSummary {
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

interface BacktestListResponse {
  backtests: BacktestResult[];
  next_cursor?: string;
}

interface TradeLogEntry {
  timestamp: string;
  symbol: string;
  side: string;
  price: string;
  qty: string;
  fee: string;
  pnl: string;
}

interface BacktestTradeLogResponse {
  trades: TradeLogEntry[];
  next_cursor?: string;
}

interface EquityPoint {
  timestamp: number;
  equity: number;
  drawdown: number;
}

interface CostBreakdown {
  total_fees: number;
  total_slippage: number;
  total_commission: number;
  fees_by_asset: Record<string, number>;
  slippage_by_symbol: Record<string, number>;
}

export default function ResearchPage() {
  const { user } = useAuth();
  const [backtests, setBacktests] = useState<BacktestResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedBacktest, setSelectedBacktest] = useState<BacktestResult | null>(null);
  const [tradeLog, setTradeLog] = useState<any[]>([]);
  const [equityCurve, setEquityCurve] = useState<EquityPoint[]>([]);
  const [costBreakdown, setCostBreakdown] = useState<CostBreakdown | null>(null);
  const [cursor, setCursor] = useState<string | undefined>();
  const [logCursor, setLogCursor] = useState<string | undefined>();
  const [logLoading, setLogLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadBacktests = useCallback(async (cursor?: string) => {
    try {
      const response = await researchApi.listBacktests(50, cursor);
      if (cursor) {
        setBacktests(prev => [...prev, ...response.backtests]);
      } else {
        setBacktests(response.backtests);
      }
      setCursor(response.next_cursor);
    } catch (error) {
      console.error('Failed to load backtests:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDetail = async (backtest: BacktestResult) => {
    setSelectedBacktest(backtest);
    setDetailLoading(true);
    try {
      const [log, equity] = await Promise.all([
        researchApi.getTradeLog(backtest.backtest_id, 1000),
        generateEquityCurve(backtest.backtest_id),
      ]);
      setTradeLog(log.trades);
      setEquityCurve(equity);
      setCostBreakdown(computeCostBreakdown(log.trades));
    } catch (error) {
      console.error('Failed to load detail:', error);
    } finally {
      setDetailLoading(false);
    }
  };

  useEffect(() => {
    loadBacktests();
  }, [loadBacktests]);

  // Load more trades when scrolling
  const loadMoreTrades = async () => {
    if (logLoading || !selectedBacktest || !logCursor) return;
    setLogLoading(true);
    try {
      const response = await researchApi.getTradeLog(selectedBacktest.backtest_id, 1000, logCursor);
      setTradeLog(prev => [...prev, ...response.trades]);
      setLogCursor(response.next_cursor);
    } catch (error) {
      console.error('Failed to load more trades:', error);
    } finally {
      setLogLoading(false);
    }
  }, [selectedBacktest, logCursor, logLoading],);

  if (loading) {
    return (
      <div className="min-h-screen bg-surface-50 dark:bg-surface-950 flex items-center justify-center">
        <div className="h-12 w-12 animate-spin rounded-full border-4 border-primary-600 border-t-transparent"></div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-950 p-4 sm:p-6 lg:p-8">
      <div className="mb-8 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">Research & Backtesting</h1>
          <p className="mt-1 text-surface-600 dark:text-surface-400">Analyze strategy performance with equity curves, drawdowns, and cost breakdowns</p>
        </div>
        <button className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 transition-colors">
          New Backtest
        </button>
      </div>

      {selectedBacktest ? (
        <BacktestDetailView 
          backtest={selectedBacktest}
          tradeLog={tradeLog}
          equityCurve={equityCurve}
          costBreakdown={costBreakdown}
          logLoading={logLoading}
          logCursor={logCursor}
          onLoadMore={loadMoreTrades}
          onClose={() => { setSelectedBacktest(null); setTradeLog([]); setEquityCurve([]); setCostBreakdown(null); }}
        />
      ) : (
        <BacktestListView 
          backtests={backtests}
          loading={loading}
          cursor={cursor}
          onLoadMore={() => loadBacktests(cursor)}
          onSelect={setSelectedBacktest}
        />
      )}
    </div>
  );
}

function BacktestListView({ 
  backtests, 
  loading, 
  cursor, 
  onLoadMore, 
  onSelect 
}: { 
  backtests: BacktestResult[];
  loading: boolean;
  cursor?: string;
  onLoadMore: () => void;
  onSelect: (bt: BacktestResult) => void;
}) {
  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800">
      <div className="p-4 border-b border-surface-200 dark:border-surface-800">
        <h2 className="text-lg font-semibold text-surface-900 dark:text-surface-50">Backtest Runs</h2>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-surface-200 dark:border-surface-800 text-left text-sm text-surface-500">
              <th className="pb-3 font-medium">Strategy</th>
              <th className="pb-3 font-medium">Symbol</th>
              <th className="pb-3 font-medium">Period</th>
              <th className="pb-3 font-medium">Status</th>
              <th className="pb-3 font-medium">Total Return</th>
              <th className="pb-3 font-medium">Sharpe</th>
              <th className="pb-3 font-medium">Max DD</th>
              <th className="pb-3 font-medium">Win Rate</th>
              <th className="pb-3 font-medium">Trades</th>
              <th className="pb-3 font-medium">Created</th>
              <th className="pb-3 font-medium">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-surface-200 dark:divide-surface-800">
            {loading ? (
              <tr>
                <td className="py-8 text-center text-surface-500" colSpan={11}>Loading...</td>
              </tr>
            ) : backtests.length === 0 ? (
              <tr>
                <td className="py-8 text-center text-surface-500" colSpan={11}>
                  No backtests yet. Click "New Backtest" to run your first strategy.
                </td>
              </tr>
            ) : (
              backtests.map((bt, i) => (
                <tr key={i} className="hover:bg-surface-50 dark:hover:bg-surface-800/50 cursor-pointer"
                  onClick={() => onSelect(bt)}>
                  <td className="py-3 font-medium text-surface-900 dark:text-surface-50">{bt.strategy_name}</td>
                  <td className="py-3 text-surface-600 dark:text-surface-400">{bt.symbol}</td>
                  <td className="py-3 text-surface-600 dark:text-surface-400">{bt.start_date} to {bt.end_date}</td>
                  <td className="py-3">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                      bt.summary.total_return > 0 ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
                    }`}>
                      {bt.summary.total_return > 0 ? 'Profitable' : 'Loss'}
                    </span>
                  </td>
                  <td className="py-3 font-mono text-surface-900 dark:text-surface-50">
                    {bt.summary.total_return.toFixed(2)}%
                  </td>
                  <td className="py-3 font-mono text-surface-900 dark:text-surface-50">
                    {bt.summary.sharpe_ratio.toFixed(2)}
                  </td>
                  <td className="py-3 font-mono text-red-600 dark:text-red-400">
                    {bt.summary.max_drawdown.toFixed(2)}%
                  </td>
                  <td className="py-3 font-mono text-surface-900 dark:text-surface-50">
                    {bt.summary.win_rate.toFixed(1)}%
                  </td>
                  <td className="py-3 font-mono text-surface-900 dark:text-surface-50">
                    {bt.summary.total_trades}
                  </td>
                  <td className="py-3 text-surface-600 dark:text-surface-400">
                    {new Date(bt.created_at).toLocaleDateString()}
                  </td>
                  <td className="py-3">
                    <span className="text-primary-600 hover:text-primary-500 underline">View</span>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {cursor && !loading && (
          <div className="p-4 border-t border-surface-200 dark:border-surface-800 text-center">
            <button onClick={() => {}} disabled className="px-4 py-2 text-sm bg-primary-600 text-white rounded hover:bg-primary-700 disabled:opacity-50">
              Load More
            </button>
          </div>
        )}
      </div>
    );
  );
}

function BacktestDetailView({ 
  backtest, 
  tradeLog, 
  equityCurve, 
  costBreakdown, 
  logLoading, 
  logCursor,
  onLoadMore,
  onClose 
}: { 
  backtest: any;
  tradeLog: any[];
  equityCurve: any[];
  costBreakdown: any;
  logLoading: boolean;
  logCursor?: string;
  onLoadMore: () => void;
  onClose: () => void;
}) {
  const summary = backtest.summary;
  const totalReturn = summary.total_return;
  const isProfitable = totalReturn > 0;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
        <div>
          <button 
            onClick={onClose}
            className="mb-3 inline-flex items-center gap-2 text-sm text-primary-600 hover:text-primary-500"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 19l-7-7m0 0l7-7m-7 7h18" />
            </svg>
            Back to List
          </button>
          <div>
            <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">{backtest.strategy_name}</h1>
            <p className="text-surface-600 dark:text-surface-400">{backtest.symbol} • {backtest.start_date} to {backtest.end_date}</p>
          </div>
        </div>
        <div className="flex gap-3">
          <span className={`px-3 py-1 rounded-full text-sm font-medium ${
            isProfitable ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400' :
            'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400'
          }`}>
            {isProfitable ? 'Profitable' : 'Loss'}
          </span>
          <span className="px-3 py-1 rounded-full text-sm font-medium bg-primary-100 text-primary-800 dark:bg-primary-900/30 dark:text-primary-400">
            {backtest.summary.total_trades} trades
          </span>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <SummaryCard label="Total Return" value={`${summary.total_return.toFixed(2)}%`} positive={isProfitable} />
        <SummaryCard label="Sharpe Ratio" value={summary.sharpe_ratio.toFixed(2)} positive={summary.sharpe_ratio > 1} />
        <SummaryCard label="Max Drawdown" value={`${summary.max_drawdown.toFixed(2)}%`} positive={summary.max_drawdown < 15} />
        <SummaryCard label="Win Rate" value={`${summary.win_rate.toFixed(1)}%`} positive={summary.win_rate > 50} />
        <SummaryCard label="Profit Factor" value={summary.profit_factor.toFixed(2)} positive={summary.profit_factor > 1.5} />
        <SummaryCard label="Sortino" value={summary.sortino_ratio.toFixed(2)} positive={summary.sortino_ratio > 1} />
        <SummaryCard label="Calmar" value={summary.calmar_ratio.toFixed(2)} positive={summary.calmar_ratio > 1} />
        <SummaryCard label="Avg Trade" value={`${formatCurrency(summary.avg_trade_pnl)}`} positive={summary.avg_trade_pnl > 0} />
      </div>

      {/* Tabs */}
      <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800">
        <div className="border-b border-surface-200 dark:border-surface-800">
          <nav className="flex gap-4 px-4" aria-label="Tabs">
            <button className="tab-active px-4 py-3 text-sm font-medium text-primary-600 border-b-2 border-primary-600">Equity Curve</button>
            <button className="px-4 py-3 text-sm font-medium text-surface-500 hover:text-surface-700">Drawdown</button>
            <button className="px-4 py-3 text-sm font-medium text-surface-500 hover:text-surface-700">Cost Breakdown</button>
            <button className="px-4 py-3 text-sm font-medium text-surface-500 hover:text-surface-700">Trade Log</button>
          </nav>
        </div>

        <div className="p-6">
          {/* Equity Curve Chart */}
          <EquityCurveChart equityCurve={equityCurve} initialEquity={100000} />

          {/* Drawdown Chart */}
          <DrawdownChart equityCurve={equityCurve} />

          {/* Cost Breakdown */}
          <CostBreakdownPanel costBreakdown={costBreakdown} />

          {/* Trade Log */}
          <TradeLogTable trades={tradeLog} loading={logLoading} hasMore={!!logCursor} onLoadMore={onLoadMore} />
        </div>
      </div>
    </div>
  );
}

function SummaryCard({ label, value, positive }: { label: string; value: string; positive: boolean }) {
  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
      <p className="text-sm text-surface-600 dark:text-surface-400">{label}</p>
      <p className={`mt-1 text-2xl font-bold ${positive ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'}`}>
        {value}
      </p>
    </div>
  );
}

function EquityCurveChart({ equityCurve, initialEquity }: { equityCurve: any[]; initialEquity: number }) {
  const canvasRef = React.useRef<HTMLCanvasElement>(null);
  
  React.useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || equityCurve.length === 0) return;
    
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.width = canvas.clientWidth * dpr;
    const h = canvas.height = 300 * dpr;
    ctx.scale(dpr, dpr);
    const cw = canvas.clientWidth;
    const ch = 300;
    
    ctx.fillStyle = '#0f0f0f';
    ctx.fillRect(0, 0, cw, ch);
    
    if (equityCurve.length < 2) return;
    
    const values = equityCurve.map(e => e.equity);
    const minVal = Math.min(...values);
    const maxVal = Math.max(...values);
    const range = maxVal - minVal || 1;
    
    // Grid
    ctx.strokeStyle = 'rgba(113, 113, 122, 0.1)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = (i / 4) * 300;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(canvas.clientWidth, y);
      ctx.stroke();
      
      const val = maxVal - (i / 4) * (maxVal - minVal);
      ctx.fillStyle = '#71717a';
      ctx.font = '10px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.fillText('$' + (val/1000).toFixed(0) + 'k', 4, y + 3);
    }
    
    // Initial equity line
    const initialY = 300 - ((initialEquity - Math.min(...values)) / (Math.max(...values) - Math.min(...values) || 1)) * 300;
    if (initialY >= 0 && initialY <= 300) {
      ctx.strokeStyle = 'rgba(113, 113, 122, 0.3)';
      ctx.setLineDash([4, 4]);
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, initialY);
      ctx.lineTo(canvas.clientWidth, initialY);
      ctx.stroke();
      ctx.setLineDash([]);
    }
    
    // Equity line
    const gradient = ctx.createLinearGradient(0, 0, 0, 300);
    gradient.addColorStop(0, 'rgba(34, 197, 94, 0.4)');
    gradient.addColorStop(1, 'rgba(34, 197, 94, 0)');
    
    ctx.strokeStyle = '#22c55e';
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    
    ctx.beginPath();
    equityCurve.forEach((point, i) => {
      const x = (i / (equityCurve.length - 1)) * canvas.clientWidth;
      const y = 300 - ((point.equity - Math.min(...equityCurve.map(e => e.equity))) / 
        (Math.max(...equityCurve.map(e => e.equity)) - Math.min(...equityCurve.map(e => e.equity)) || 1)) * 300;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
    
    // Fill
    ctx.lineTo(canvas.clientWidth, 300);
    ctx.lineTo(0, 300);
    ctx.closePath();
    const gradient2 = ctx.createLinearGradient(0, 0, 0, 300);
    gradient2.addColorStop(0, 'rgba(34, 197, 94, 0.3)');
    gradient2.addColorStop(1, 'rgba(34, 197, 94, 0)');
    ctx.fillStyle = gradient2;
    ctx.fill();
  }, [equityCurve]);

  return (
    <div className="mb-6">
      <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50 mb-3">Equity Curve</h3>
      <canvas ref={canvasRef} width={800} height={300} className="w-full h-80" style={{ background: '#0f0f0f', borderRadius: '8px' }} />
    </div>
  );
}

function DrawdownChart({ equityCurve }: { equityCurve: any[] }) {
  const canvasRef = React.useRef<HTMLCanvasElement>(null);
  
  React.useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || equityCurve.length === 0) return;
    
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.width = canvas.clientWidth * dpr;
    const h = canvas.height = 200 * dpr;
    ctx.scale(dpr, dpr);
    const cw = canvas.clientWidth;
    const ch = 200;
    
    ctx.fillStyle = '#0f0f0f';
    ctx.fillRect(0, 0, cw, ch);
    
    if (equityCurve.length === 0) return;
    
    // Calculate drawdown
    let peak = equityCurve[0].equity;
    const drawdowns = equityCurve.map(point => {
      if (point.equity > peak) peak = point.equity;
      return ((point.equity - peak) / peak) * 100;
    });
    
    const minDD = Math.min(...drawdowns);
    const maxDD = Math.max(...drawdowns);
    const range = maxDD - minDD || 1;
    
    // Grid
    ctx.strokeStyle = 'rgba(113, 113, 122, 0.1)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = (i / 4) * 200;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(canvas.clientWidth, y);
      ctx.stroke();
      
      const dd = maxDD - (i / 4) * (maxDD - minDD);
      ctx.fillStyle = '#71717a';
      ctx.font = '10px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(dd.toFixed(1) + '%', 4, y + 3);
    }
    
    // Drawdown area
    const gradient = ctx.createLinearGradient(0, 0, 0, 200);
    gradient.addColorStop(0, 'rgba(239, 68, 68, 0.3)');
    gradient.addColorStop(1, 'rgba(239, 68, 68, 0)');
    
    ctx.strokeStyle = '#ef4444';
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    
    ctx.beginPath();
    drawdowns.forEach((dd, i) => {
      const x = (i / (drawdowns.length - 1)) * canvas.clientWidth;
      const y = 200 - ((dd - minDD) / (maxDD - minDD || 1)) * 200;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
    
    // Fill
    ctx.lineTo(canvas.clientWidth, 200);
    ctx.lineTo(0, 200);
    ctx.closePath();
    const gradient2 = ctx.createLinearGradient(0, 0, 0, 200);
    gradient2.addColorStop(0, 'rgba(239, 68, 68, 0.2)');
    gradient2.addColorStop(1, 'rgba(239, 68, 68, 0)');
    ctx.fillStyle = gradient2;
    ctx.fill();
  }, [equityCurve]);

  return (
    <div className="mb-6">
      <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50 mb-3">Drawdown</h3>
      <canvas ref={canvasRef} width={800} height={200} className="w-full h-64" style={{ background: '#0f0f0f', borderRadius: '8px' }} />
    </div>
  );
}

function CostBreakdownPanel({ costBreakdown }: { costBreakdown: any }) {
  if (!costBreakdown) return (
    <div className="p-6 bg-surface-50 dark:bg-surface-800/50 rounded-lg text-center text-surface-500">
      No cost data available
    </div>
  );

  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50">Cost Breakdown</h3>
      
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
        <CostCard label="Total Fees" value={formatCurrency(costBreakdown.total_fees)} color="red" />
        <CostCard label="Total Slippage" value={formatCurrency(costBreakdown.total_slippage)} color="orange" />
        <CostCard label="Total Commission" value={formatCurrency(costBreakdown.total_commission)} color="blue" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
          <h4 className="font-semibold text-surface-900 dark:text-surface-50 mb-3">Fees by Asset</h4>
          <div className="space-y-2">
            {Object.entries(costBreakdown.fees_by_asset).map(([asset, amount]) => (
              <div key={asset} className="flex justify-between text-sm">
                <span className="text-surface-600 dark:text-surface-400">{asset}</span>
                <span className="font-mono font-medium text-red-600 dark:text-red-400">
                  {formatCurrency(amount)}
                </span>
              </div>
            )}
          </div>
        </div>

        <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-4">
          <h4 className="font-semibold text-surface-900 dark:text-surface-50 mb-3">Slippage by Symbol</h4>
          <div className="space-y-2">
            {Object.entries(costBreakdown.slippage_by_symbol).map(([symbol, amount]) => (
              <div key={symbol} className="flex justify-between text-sm">
                <span className="text-surface-600 dark:text-surface-400">{symbol}</span>
                <span className="font-mono font-medium text-orange-600 dark:text-orange-400">
                  {formatCurrency(amount)}
                </span>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function CostCard({ label, value, color }: { label: string; value: string; color: string }) {
  const colorMap = {
    red: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
    orange: 'bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-400',
    blue: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
  };
  
  return (
    <div className={`p-4 rounded-xl ${({ red: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400', orange: 'bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-400', blue: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400' })[color as keyof typeof colorMap] || 'bg-surface-100'}`}>
      <p className="text-sm text-surface-600 dark:text-surface-400">{label}</p>
      <p className="mt-1 text-xl font-bold text-surface-900 dark:text-surface-50">{value}</p>
    </div>
  );
}

function TradeLogTable({ trades, loading, hasMore, onLoadMore }: { trades: any[]; loading: boolean; hasMore: boolean; onLoadMore: () => void }) {
  return (
    <div>
      <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50 mb-3">Trade Log</h3>
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-surface-200 dark:border-surface-800 text-left text-sm text-surface-500">
              <th className="pb-3 font-medium">Time</th>
              <th className="pb-3 font-medium">Symbol</th>
              <th className="pb-3 font-medium">Side</th>
              <th className="pb-3 font-medium">Price</th>
              <th className="pb-3 font-medium">Qty</th>
              <th className="pb-3 font-medium">Fee</th>
              <th className="pb-3 font-medium">P&L</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-surface-200 dark:divide-surface-800">
            {trades.length === 0 ? (
              <tr>
                <td className="py-8 text-center text-surface-500" colSpan={7}>No trades</td>
              </tr>
            ) : (
              trades.slice(0, 100).map((trade, i) => (
                <tr key={i} className="hover:bg-surface-50 dark:hover:bg-surface-800/50">
                  <td className="py-2 text-sm text-surface-600 dark:text-surface-400">{trade.timestamp}</td>
                  <td className="py-2 text-sm text-surface-600 dark:text-surface-400">{trade.symbol}</td>
                  <td className="py-2">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                      trade.side === 'BUY' ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
                    }`}>
                      {trade.side}
                    </span>
                  </td>
                  <td className="py-2 font-mono text-surface-900 dark:text-surface-50">{trade.price}</td>
                  <td className="py-2 font-mono text-surface-600 dark:text-surface-400">{trade.qty}</td>
                  <td className="py-2 font-mono text-surface-600 dark:text-surface-400">{trade.fee}</td>
                  <td className="py-2 font-mono text-green-600 dark:text-green-400">{trade.pnl}</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {hasMore && !loading && (
          <div className="text-center pt-4">
            <button onClick={onLoadMore} className="px-4 py-2 text-sm bg-primary-600 text-white rounded hover:bg-primary-700">
              Load More
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

// Helper functions
function generateEquityCurve(backtestId: string): Promise<any[]> {
  // In real app, this would call the API
  // For now, generate mock data based on trade log
  return Promise.resolve([]);
}

function computeCostBreakdown(trades: any[]): any {
  let totalFees = 0;
  let totalSlippage = 0;
  let totalCommission = 0;
  const feesByAsset: Record<string, number> = {};
  const slippageBySymbol: Record<string, number> = {};
  
  trades.forEach(trade => {
    const fee = parseFloat(trade.fee);
    totalFees += fee;
    feesByAsset[trade.fee_asset || 'USD'] = (feesByAsset[trade.fee_asset || 'USD'] || 0) + fee;
    
    slippageBySymbol[trade.symbol] = (slippageBySymbol[trade.symbol] || 0) + 10; // mock
  });
  
  return { total_fees, total_slippage, total_commission: 0, fees_by_asset, slippage_by_symbol };
}

function formatCurrency(value: string | number): string {
  const num = typeof value === 'string' ? parseFloat(value) : value;
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(num);
}
