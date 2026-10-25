'use client';

import { useCallback, useEffect, useState } from 'react';
import { researchApi } from '@/lib/api';

type Summary = { total_return: number; sharpe_ratio: number; max_drawdown: number; total_trades: number };
type Backtest = { backtest_id: string; strategy_name: string; symbol: string; start_date: string; end_date: string; summary: Summary };

export default function ResearchPage() {
  const [backtests, setBacktests] = useState<Backtest[]>([]);
  const [loading, setLoading] = useState(true);
  const load = useCallback(async () => {
    try { const response = await researchApi.listBacktests(50); setBacktests(response.backtests); }
    catch (error) { console.error('Failed to load backtests:', error); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  if (loading) return <main className="p-8">Loading backtests…</main>;
  return <main className="min-h-screen bg-surface-50 p-6 dark:bg-surface-950">
    <h1 className="mb-2 text-2xl font-bold">Research & Backtesting</h1><p className="mb-6 text-surface-500">Recorded, reproducible strategy runs.</p>
    <section className="overflow-x-auto rounded-xl bg-white p-4 dark:bg-surface-900"><table className="w-full text-left"><thead><tr><th>Strategy</th><th>Symbol</th><th>Period</th><th>Return</th><th>Sharpe</th><th>Max drawdown</th><th>Trades</th></tr></thead><tbody>{backtests.map((backtest) => <tr key={backtest.backtest_id}><td>{backtest.strategy_name}</td><td>{backtest.symbol}</td><td>{backtest.start_date} to {backtest.end_date}</td><td>{backtest.summary.total_return.toFixed(2)}%</td><td>{backtest.summary.sharpe_ratio.toFixed(2)}</td><td>{backtest.summary.max_drawdown.toFixed(2)}%</td><td>{backtest.summary.total_trades}</td></tr>)}</tbody></table>{backtests.length === 0 && <p className="py-8 text-center text-surface-500">No backtests yet.</p>}</section>
  </main>;
}
