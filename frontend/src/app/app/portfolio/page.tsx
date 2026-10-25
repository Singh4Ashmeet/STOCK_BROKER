'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { portfolioApi } from '@/lib/api';

type Position = { symbol: string; qty: string; avg_price: string; market_value: string; unrealized_pnl: string };
type Balance = { asset: string; available: string; locked: string; total: string };
type Portfolio = { positions: Position[]; total_value: string; total_pnl: string; cash_balance: string; currency: string };

export default function PortfolioPage() {
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [balances, setBalances] = useState<Balance[]>([]);
  const [loading, setLoading] = useState(true);
  const load = useCallback(async () => {
    try {
      const [portfolioData, balanceData] = await Promise.all([portfolioApi.getPortfolio(), portfolioApi.getBalances()]);
      setPortfolio(portfolioData);
      setBalances(balanceData);
    } catch (error) { console.error('Failed to load portfolio:', error); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  if (loading) return <main className="p-8">Loading portfolio…</main>;
  return <main className="min-h-screen bg-surface-50 p-6 dark:bg-surface-950">
    <div className="mb-6 flex items-center justify-between"><div><h1 className="text-2xl font-bold">Portfolio</h1><p className="text-surface-500">Positions and balances</p></div><Link href="/app/trading" className="rounded bg-primary-600 px-4 py-2 text-white">Trade</Link></div>
    <section className="mb-6 grid grid-cols-1 gap-4 md:grid-cols-4"><Card label="Total value" value={portfolio?.total_value ?? '$0.00'} /><Card label="Total P&L" value={portfolio?.total_pnl ?? '$0.00'} /><Card label="Cash" value={portfolio?.cash_balance ?? '$0.00'} /><Card label="Currency" value={portfolio?.currency ?? 'USD'} /></section>
    <section className="mb-6 overflow-x-auto rounded-xl bg-white p-4 dark:bg-surface-900"><h2 className="mb-4 text-lg font-semibold">Positions</h2><table className="w-full text-left"><thead><tr><th>Symbol</th><th>Quantity</th><th>Average price</th><th>Market value</th><th>Unrealized P&L</th></tr></thead><tbody>{portfolio?.positions.map((position) => <tr key={position.symbol}><td>{position.symbol}</td><td>{position.qty}</td><td>{position.avg_price}</td><td>{position.market_value}</td><td>{position.unrealized_pnl}</td></tr>)}</tbody></table></section>
    <section className="overflow-x-auto rounded-xl bg-white p-4 dark:bg-surface-900"><h2 className="mb-4 text-lg font-semibold">Balances</h2><table className="w-full text-left"><thead><tr><th>Asset</th><th>Available</th><th>Locked</th><th>Total</th></tr></thead><tbody>{balances.map((balance) => <tr key={balance.asset}><td>{balance.asset}</td><td>{balance.available}</td><td>{balance.locked}</td><td>{balance.total}</td></tr>)}</tbody></table></section>
  </main>;
}

function Card({ label, value }: { label: string; value: string }) { return <div className="rounded-xl bg-white p-4 dark:bg-surface-900"><p className="text-sm text-surface-500">{label}</p><p className="text-xl font-bold">{value}</p></div>; }
