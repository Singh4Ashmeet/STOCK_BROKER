'use client';

import { useEffect, useState } from 'react';
import { useAuth } from '@/lib/auth';
import { portfolioApi, tradingApi } from '@/lib/api';
import { formatCurrency, formatNumber, formatPercent, formatTimestamp } from '@/lib/utils';
import { MetricsPanel } from '@/components/charts';
import Link from 'next/navigation';

interface Position {
  symbol: string;
  qty: string;
  avg_price: string;
  market_value: string;
  unrealized_pnl: string;
  realized_pnl: string;
}

interface Balance {
  asset: string;
  available: string;
  locked: string;
  total: string;
}

interface PortfolioResponse {
  positions: Position[];
  total_value: string;
  total_pnl: string;
  cash_balance: string;
  currency: string;
}

interface LedgerEntry {
  id: string;
  timestamp: string;
  type: string;
  asset: string;
  amount: string;
  balance_after: string;
  description: string;
  reference_id?: string;
}

interface LedgerResponse {
  entries: LedgerEntry[];
  next_cursor?: string;
}

export default function PortfolioPage() {
  const { user } = useAuth();
  const [portfolio, setPortfolio] = useState<PortfolioResponse | null>(null);
  const [balances, setBalances] = useState<Balance[]>([]);
  const [ledger, setLedger] = useState<LedgerEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [ledgerLoading, setLedgerLoading] = useState(false);
  const [ledgerCursor, setLedgerCursor] = useState<string | undefined>();

  const loadPortfolio = useCallback(async () => {
    try {
      const [portfolioData, balancesData] = await Promise.all([
        portfolioApi.getPortfolio(),
        portfolioApi.getBalances(),
      ]);
      setPortfolio(portfolioData);
      setBalances(balancesData);
    } catch (error) {
      console.error('Failed to load portfolio:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadLedger = useCallback(async (cursor?: string) => {
    setLedgerLoading(true);
    try {
      const response = await portfolioApi.getLedger({ limit: 50, cursor });
      setLedger(prev => cursor ? [...prev, ...response.entries] : response.entries);
      setLedgerCursor(response.next_cursor);
    } catch (error) {
      console.error('Failed to load ledger:', error);
    } finally {
      setLedgerLoading(false);
    }
  }, []);

  useEffect(() => {
    loadPortfolio();
    loadLedger();
  }, [loadPortfolio, loadLedger]);

  if (loading) {
    return (
      <div className="min-h-screen bg-surface-50 dark:bg-surface-950 flex items-center justify-center">
        <div className="h-12 w-12 animate-spin rounded-full border-4 border-primary-600 border-t-transparent"></div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-950 p-4 sm:p-6 lg:p-8">
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">Portfolio</h1>
        <p className="mt-1 text-surface-600 dark:text-surface-400">Your positions, balances, and transaction history</p>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <StatCard title="Total Value" value={portfolio?.total_value || '$0.00'} icon="💰" />
        <StatCard title="Day P&L" value={portfolio?.total_pnl || '$0.00'} 
          change={portfolio?.total_value && portfolio?.total_pnl ? 
            `${((parseFloat(portfolio.total_pnl.replace(/[$,]/g, '')) / (parseFloat(portfolio.total_value.replace(/[$,]/g, '')) - parseFloat(portfolio.total_pnl.replace(/[$,]/g, '')))) * 100).toFixed(2)}%` : '0%'} 
          positive={parseFloat(portfolio?.total_pnl?.replace(/[$,]/g, '') || '0') >= 0} 
          icon="📈" />
        <StatCard title="Cash Balance" value={portfolio?.cash_balance || '$0.00'} icon="💵" />
        <StatCard title="Currency" value={portfolio?.currency || 'USD'} icon="🌍" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Positions */}
        <div className="lg:col-span-2 space-y-6">
          <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800">
            <div className="p-4 border-b border-surface-200 dark:border-surface-800 flex items-center justify-between">
              <h2 className="text-lg font-semibold text-surface-900 dark:text-surface-50">Positions</h2>
              <Link href="/app/trading" className="text-sm text-primary-600 hover:text-primary-500">Trade</Link>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="border-b border-surface-200 dark:border-surface-800 text-left text-sm text-surface-500">
                    <th className="pb-3 font-medium">Symbol</th>
                    <th className="pb-3 font-medium">Quantity</th>
                    <th className="pb-3 font-medium">Avg Price</th>
                    <th className="pb-3 font-medium">Current Price</th>
                    <th className="pb-3 font-medium">Market Value</th>
                    <th className="pb-3 font-medium">Unrealized P&L</th>
                    <th className="pb-3 font-medium">Realized P&L</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-surface-200 dark:divide-surface-800">
                  {portfolio?.positions.length === 0 ? (
                    <tr>
                      <td className="py-8 text-center text-surface-500 dark:text-surface-400" colSpan={7}>
                        No open positions. <Link href="/app/trading" className="text-primary-600 hover:text-primary-500 underline">Start trading</Link>
                      </td>
                    </tr>
                  ) : (
                    portfolio?.positions.map((pos, i) => (
                      <tr key={i} className="hover:bg-surface-50 dark:hover:bg-surface-800/50">
                        <td className="py-3 font-medium text-surface-900 dark:text-surface-50">{pos.symbol}</td>
                        <td className="py-3 font-mono text-surface-600 dark:text-surface-400">{pos.qty}</td>
                        <td className="py-3 font-mono text-surface-600 dark:text-surface-400">${pos.avg_price}</td>
                        <td className="py-3 font-mono text-surface-900 dark:text-surface-50">${pos.avg_price}</td>
                        <td className="py-3 font-bold text-surface-900 dark:text-surface-50">${pos.market_value}</td>
                        <td className="py-3 font-medium text-green-600 dark:text-green-400">{pos.unrealized_pnl}</td>
                        <td className="py-3 font-medium text-green-600 dark:text-green-400">{pos.realized_pnl}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* Balances */}
          <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800">
            <div className="p-4 border-b border-surface-200 dark:border-surface-800">
              <h2 className="text-lg font-semibold text-surface-900 dark:text-surface-50">Balances</h2>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="border-b border-surface-200 dark:border-surface-800 text-left text-sm text-surface-500">
                    <th className="pb-3 font-medium">Asset</th>
                    <th className="pb-3 font-medium">Available</th>
                    <th className="pb-3 font-medium">Locked</th>
                    <th className="pb-3 font-medium">Total</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-surface-200 dark:divide-surface-800">
                  {balances.map((bal, i) => (
                    <tr key={i} className="hover:bg-surface-50 dark:hover:bg-surface-800/50">
                      <td className="py-3 font-medium text-surface-900 dark:text-surface-50">{bal.asset}</td>
                      <td className="py-3 font-mono text-surface-600 dark:text-surface-400">{bal.available}</td>
                      <td className="py-3 font-mono text-surface-600 dark:text-surface-400">{bal.locked}</td>
                      <td className="py-3 font-bold text-surface-900 dark:text-surface-50">{bal.total}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        {/* Ledger / Activity */}
        <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800">
          <div className="p-4 border-b border-surface-200 dark:border-surface-800 flex items-center justify-between">
            <h2 className="text-lg font-semibold text-surface-900 dark:text-surface-50">Activity / Ledger</h2>
            <button
              onClick={() => loadLedger(ledgerCursor)}
              disabled={ledgerLoading || !ledgerCursor}
              className="px-3 py-1 text-sm bg-primary-600 text-white rounded hover:bg-primary-700 disabled:opacity-50"
            >
              {ledgerLoading ? 'Loading...' : 'Load More'}
            </button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-surface-200 dark:border-surface-800 text-left text-sm text-surface-500">
                  <th className="pb-3 font-medium">Time</th>
                  <th className="pb-3 font-medium">Type</th>
                  <th className="pb-3 font-medium">Asset</th>
                  <th className="pb-3 font-medium">Amount</th>
                  <th className="pb-3 font-medium">Balance After</th>
                  <th className="pb-3 font-medium">Description</th>
                  <th className="pb-3 font-medium">Ref ID</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200 dark:divide-surface-800">
                {ledger.length === 0 ? (
                  <tr>
                    <td className="py-8 text-center text-surface-500 dark:text-surface-400" colSpan={7}>
                      No activity yet
                    </td>
                  </tr>
                ) : (
                  ledger.map((entry, i) => (
                    <tr key={i} className="hover:bg-surface-50 dark:hover:bg-surface-800/50">
                      <td className="py-3 text-sm text-surface-600 dark:text-surface-400">{formatTimestamp(entry.timestamp)}</td>
                      <td className="py-3">
                        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                          entry.type.includes('DEPOSIT') || entry.type.includes('CREDIT') ? 'bg-green-100 text-green-800' :
                          entry.type.includes('WITHDRAWAL') || entry.type.includes('DEBIT') ? 'bg-red-100 text-red-800' :
                          entry.type.includes('FEE') ? 'bg-orange-100 text-orange-800' :
                          entry.type.includes('P&L') ? 'bg-blue-100 text-blue-800' :
                          'bg-surface-100 text-surface-800'
                        }`}>
                          {entry.type}
                        </span>
                      </td>
                      <td className="py-3 text-sm text-surface-600 dark:text-surface-400">{entry.asset}</td>
                      <td className="py-3 font-mono text-surface-900 dark:text-surface-50">
                        {entry.amount.startsWith('-') ? '' : '+'}{entry.amount}
                      </td>
                      <td className="py-3 font-mono text-surface-900 dark:text-surface-50">{entry.balance_after}</td>
                      <td className="py-3 text-sm text-surface-600 dark:text-surface-400 max-w-xs truncate">{entry.description}</td>
                      <td className="py-3 text-sm font-mono text-surface-500">{entry.reference_id || '—'}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            {ledgerCursor && !ledgerLoading && (
              <div className="p-4 border-t border-surface-200 dark:border-surface-800 text-center">
                <button
                  onClick={() => loadLedger(ledgerCursor)}
                  className="px-4 py-2 text-sm bg-primary-600 text-white rounded hover:bg-primary-700"
                >
                  Load More
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function StatCard({ title, value, change, positive, icon }: { 
  title: string; 
  value: string; 
  change?: string;
  positive?: boolean;
  icon?: string;
}) {
  return (
    <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm text-surface-600 dark:text-surface-400">{title}</p>
          <p className="mt-1 text-2xl font-bold text-surface-900 dark:text-surface-50">{value}</p>
        </div>
        <div className="p-3 rounded-lg bg-primary-100 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 text-2xl">
          {icon}
        </div>
      </div>
      {change && (
        <div className="mt-4 flex items-center gap-1">
          <span className={`text-sm font-medium ${positive ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'}`}>
            {positive ? '↑' : '↓'} {change}
          </span>
        </div>
      )}
    </div>
  );
}