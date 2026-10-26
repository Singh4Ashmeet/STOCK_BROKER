'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { portfolioApi, type Balance, type LedgerEntry, type PortfolioResponse } from '@/lib/api';
import { formatCurrency, formatTimestamp } from '@/lib/utils';

function money(value: string | undefined, currency = 'USD') {
  const amount = Number(value);
  return Number.isFinite(amount) ? formatCurrency(amount, currency) : '—';
}

function signedMoney(value: string | undefined, currency = 'USD') {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '—';
  return `${amount > 0 ? '+' : ''}${formatCurrency(amount, currency)}`;
}

export default function PortfolioPage() {
  const [portfolio, setPortfolio] = useState<PortfolioResponse | null>(null);
  const [balances, setBalances] = useState<Balance[]>([]);
  const [ledger, setLedger] = useState<LedgerEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [nextPortfolio, nextBalances, nextLedger] = await Promise.all([
        portfolioApi.getPortfolio(), portfolioApi.getBalances(), portfolioApi.getLedger({ limit: 8 }),
      ]);
      setPortfolio(nextPortfolio);
      setBalances(nextBalances);
      setLedger(nextLedger.entries);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The portfolio service is unavailable.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const currency = portfolio?.currency ?? 'USD';
  const pnl = Number(portfolio?.total_pnl);
  const pnlClass = Number.isFinite(pnl) && pnl !== 0 ? (pnl > 0 ? 'text-positive' : 'text-negative') : 'text-surface-50';

  return (
    <div className="mx-auto max-w-[1680px] p-4 sm:p-6 lg:p-8">
      <header className="mb-6 flex flex-col justify-between gap-4 border-b border-surface-600 pb-5 lg:flex-row lg:items-end">
        <div>
          <p className="terminal-label text-primary-400">Capital overview</p>
          <h1 className="mt-1 font-display text-3xl font-semibold tracking-tight text-surface-50">Portfolio ledger</h1>
          <p className="mt-2 max-w-2xl text-sm text-surface-300">Live positions, cash balances, and the most recent entries returned by your account ledger.</p>
        </div>
        <div className="flex flex-wrap gap-3">
          <button className="terminal-button-secondary" onClick={() => void load()} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh data'}</button>
          <Link href="/app/trading" className="terminal-button">Open order ticket</Link>
        </div>
      </header>

      {error ? <div role="alert" className="mb-6 border border-negative/70 bg-negative/10 p-4 text-sm text-surface-100"><strong className="font-semibold text-negative">Portfolio unavailable. </strong>{error}</div> : null}

      <section className="mb-6 grid grid-cols-2 gap-px overflow-hidden border border-surface-600 bg-surface-600 lg:grid-cols-4" aria-label="Portfolio summary">
        <Metric label="Account value" value={money(portfolio?.total_value, currency)} loading={loading} />
        <Metric label="Total P&amp;L" value={signedMoney(portfolio?.total_pnl, currency)} valueClass={pnlClass} loading={loading} />
        <Metric label="Cash balance" value={money(portfolio?.cash_balance, currency)} loading={loading} />
        <Metric label="Open positions" value={portfolio ? String(portfolio.positions.length) : '—'} loading={loading} />
      </section>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
        <section className="terminal-panel overflow-hidden">
          <div className="flex items-center justify-between border-b border-surface-600 px-4 py-3 sm:px-5">
            <div><p className="terminal-label">Inventory</p><h2 className="mt-1 font-display text-lg font-semibold">Open positions</h2></div>
            <span className="font-mono text-xs text-surface-300">{portfolio?.positions.length ?? 0} records</span>
          </div>
          <div className="terminal-scrollbar overflow-x-auto">
            <table className="min-w-[720px] w-full text-left text-sm">
              <thead className="bg-surface-900 text-left"><tr className="terminal-label"><th className="px-4 py-3 font-medium">Instrument</th><th className="px-4 py-3 text-right font-medium">Quantity</th><th className="px-4 py-3 text-right font-medium">Average</th><th className="px-4 py-3 text-right font-medium">Market value</th><th className="px-4 py-3 text-right font-medium">Unrealized P&amp;L</th></tr></thead>
              <tbody className="divide-y divide-surface-600">
                {portfolio?.positions.map((position) => {
                  const value = Number(position.unrealized_pnl);
                  return <tr key={position.symbol} className="hover:bg-surface-700/60"><td className="px-4 py-3 font-mono font-semibold text-primary-300">{position.symbol}</td><td className="terminal-value px-4 py-3 text-right">{position.qty}</td><td className="terminal-value px-4 py-3 text-right">{money(position.avg_price, currency)}</td><td className="terminal-value px-4 py-3 text-right">{money(position.market_value, currency)}</td><td className={`px-4 py-3 text-right font-mono tabular-nums ${value > 0 ? 'text-positive' : value < 0 ? 'text-negative' : 'text-surface-50'}`}>{signedMoney(position.unrealized_pnl, currency)}</td></tr>;
                })}
                {!loading && !portfolio?.positions.length ? <tr><td colSpan={5} className="px-4 py-10 text-center text-surface-300">No open positions are reported for this account.</td></tr> : null}
              </tbody>
            </table>
          </div>
        </section>

        <aside className="space-y-6">
          <section className="terminal-panel">
            <div className="border-b border-surface-600 px-4 py-3"><p className="terminal-label">Settlement</p><h2 className="mt-1 font-display text-lg font-semibold">Balances</h2></div>
            <ul className="divide-y divide-surface-600">
              {balances.map((balance) => <li className="flex items-center justify-between px-4 py-3" key={balance.asset}><span className="font-mono text-sm text-primary-300">{balance.asset}</span><span className="terminal-value text-sm">{balance.total}</span></li>)}
              {!loading && !balances.length ? <li className="px-4 py-8 text-sm text-surface-300">No balances are reported.</li> : null}
            </ul>
          </section>
          <section className="terminal-panel">
            <div className="border-b border-surface-600 px-4 py-3"><p className="terminal-label">Latest activity</p><h2 className="mt-1 font-display text-lg font-semibold">Ledger entries</h2></div>
            <ul className="divide-y divide-surface-600">
              {ledger.map((entry) => <li key={entry.id} className="px-4 py-3"><div className="flex justify-between gap-3"><span className="font-mono text-xs text-surface-200">{entry.type}</span><span className="font-mono text-xs text-surface-300">{entry.asset} {entry.amount}</span></div><p className="mt-1 truncate text-sm text-surface-50" title={entry.description}>{entry.description}</p><time className="mt-1 block text-xs text-surface-300" dateTime={entry.timestamp}>{formatTimestamp(entry.timestamp)}</time></li>)}
              {!loading && !ledger.length ? <li className="px-4 py-8 text-sm text-surface-300">No recent ledger entries are available.</li> : null}
            </ul>
          </section>
        </aside>
      </div>
    </div>
  );
}

function Metric({ label, value, valueClass = 'text-surface-50', loading }: { label: string; value: string; valueClass?: string; loading: boolean }) {
  return <div className="bg-surface-800 p-4 sm:p-5"><p className="terminal-label">{label}</p><p className={`mt-2 font-mono text-xl font-semibold tabular-nums sm:text-2xl ${loading ? 'text-surface-400' : valueClass}`}>{loading ? '—' : value}</p></div>;
}
