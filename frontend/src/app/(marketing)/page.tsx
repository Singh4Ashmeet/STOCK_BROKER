import Link from 'next/link';

export default function HomePage() {
  return (
    <div className="flex flex-col">
      {/* Hero Section */}
      <section className="relative overflow-hidden bg-gradient-to-b from-primary-50 to-white dark:from-surface-900 dark:to-surface-950 py-20 sm:py-32">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="text-center">
            <span className="inline-flex items-center gap-2 rounded-full bg-primary-50 px-3 py-1 text-sm font-medium text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-primary-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-primary-500"></span>
              </span>
              Live market data • Paper trading • Backtesting
            </span>
            <h1 className="mt-6 text-4xl font-bold tracking-tight text-surface-900 dark:text-surface-50 sm:text-6xl">
              Professional Paper Trading
              <br />
              <span className="text-primary-600 dark:text-primary-400">Platform</span>
            </h1>
            <p className="mt-6 text-lg text-surface-600 dark:text-surface-400 max-w-2xl mx-auto">
              Real-time market data, advanced order types, portfolio management, and strategy backtesting.
              All simulated. No real money at risk.
            </p>
            <div className="mt-10 flex flex-col sm:flex-row items-center justify-center gap-4">
              <Link
                href="/register"
                className="rounded-lg bg-primary-600 px-8 py-3 text-base font-medium text-white hover:bg-primary-700 transition-colors shadow-lg shadow-primary-600/25"
              >
                Start Paper Trading Free
              </Link>
              <Link
                href="/docs"
                className="rounded-lg border border-surface-300 dark:border-surface-700 px-8 py-3 text-base font-medium text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800 transition-colors"
              >
                Read Documentation
              </Link>
            </div>
          </div>
        </div>
      </section>

      {/* Features Section */}
      <section className="py-20 sm:py-28 bg-white dark:bg-surface-950">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-2xl mx-auto mb-16">
            <h2 className="text-3xl font-bold tracking-tight text-surface-900 dark:text-surface-50 sm:text-4xl">
              Everything you need to trade like a pro
            </h2>
            <p className="mt-4 text-lg text-surface-600 dark:text-surface-400">
              Built for serious traders who want to test strategies without risk.
            </p>
          </div>
          <div className="grid grid-cols-1 gap-8 sm:grid-cols-2 lg:grid-cols-3">
            {features.map((feature) => (
              <FeatureCard key={feature.title} feature={feature} />
            ))}
          </div>
        </div>
      </section>

      {/* Live Demo Section */}
      <section className="py-20 sm:py-28 bg-surface-50 dark:bg-surface-900">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="grid lg:grid-cols-2 gap-12 items-center">
            <div>
              <h2 className="text-3xl font-bold tracking-tight text-surface-900 dark:text-surface-50">
                Real-time market data & execution
              </h2>
              <p className="mt-4 text-lg text-surface-600 dark:text-surface-400">
                Connect to live Binance feeds via our Rust-powered market data gateway. Sub-millisecond
                latency from exchange to your browser. WebSocket fan-out handles thousands of concurrent
                connections with automatic conflation for slow clients.
              </p>
              <ul className="mt-8 space-y-4">
                {[
                  'Order book snapshots & deltas (up to 100 levels)',
                  'Real-time trades & metrics (VWAP, imbalance, microprice)',
                  'Sub-millisecond fan-out latency (p99 < 50ms)',
                  'Automatic reconnection & resync on network issues',
                ].map((item) => (
                  <li key={item} className="flex items-center gap-3">
                    <svg className="h-5 w-5 text-primary-600 dark:text-primary-400 flex-shrink-0" viewBox="0 0 20 20" fill="currentColor">
                      <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                    </svg>
                    <span className="text-surface-700 dark:text-surface-300">{item}</span>
                  </li>
                ))}
              </ul>
            </div>
            <div className="relative">
              <div className="aspect-video bg-surface-900 dark:bg-surface-950 rounded-xl border border-surface-200 dark:border-surface-800 overflow-hidden">
                <div className="absolute inset-0 flex items-center justify-center text-surface-500">
                  <span className="text-center px-8">Live Dashboard Preview</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Backtesting Section */}
      <section className="py-20 sm:py-28 bg-white dark:bg-surface-950">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="grid lg:grid-cols-2 gap-12 items-center">
            <div className="relative">
              <div className="aspect-video bg-surface-900 dark:bg-surface-950 rounded-xl border border-surface-200 dark:border-surface-800 overflow-hidden">
                <div className="absolute inset-0 flex items-center justify-center text-surface-500">
                  <span className="text-center px-8">Backtesting Engine Preview</span>
                </div>
              </div>
            </div>
            <div>
              <h2 className="text-3xl font-bold tracking-tight text-surface-900 dark:text-surface-50">
                Institutional-grade backtesting
              </h2>
              <p className="mt-4 text-lg text-surface-600 dark:text-surface-400">
                Run strategies on historical data with our event-driven engine. Walk-forward testing,
                parameter sensitivity analysis, and deflated Sharpe ratios (Bailey & Lopez de Prado 2014).
                Golden vector testing ensures Python/Java parity.
              </p>
              <ul className="mt-8 space-y-4">
                {[
                  'Event-driven engine with no-lookahead enforcement',
                  'Walk-forward testing (rolling/anchored windows)',
                  'Parameter sensitivity (grid, random, Sobol)',
                  'Deflated Sharpe ratio (PSR, DSR, p-values)',
                  'Golden vector format shared with Java engine',
                ].map((item) => (
                  <li key={item} className="flex items-center gap-3">
                    <svg className="h-5 w-5 text-primary-600 dark:text-primary-400 flex-shrink-0" viewBox="0 0 20 20" fill="currentColor">
                      <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                    </svg>
                    <span className="text-surface-700 dark:text-surface-300">{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </div>
      </section>

      {/* CTA Section */}
      <section className="py-20 sm:py-28 bg-primary-600 dark:bg-primary-700">
        <div className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8 text-center">
          <h2 className="text-3xl font-bold tracking-tight text-white sm:text-4xl">
            Ready to start paper trading?
          </h2>
          <p className="mt-4 text-lg text-primary-100">
            Create your free account in seconds. No credit card required.
          </p>
          <div className="mt-8 flex flex-col sm:flex-row items-center justify-center gap-4">
            <Link
              href="/register"
              className="rounded-lg bg-white px-8 py-3 text-base font-medium text-primary-700 hover:bg-primary-50 transition-colors shadow-lg"
            >
              Create Free Account
            </Link>
            <Link
              href="/docs"
              className="rounded-lg border-2 border-white px-8 py-3 text-base font-medium text-white hover:bg-white/10 transition-colors"
            >
              View Documentation
            </Link>
          </div>
        </div>
      </section>
    </div>
  );
}

const features = [
  {
    title: 'Real-time Market Data',
    description: 'Sub-millisecond latency from exchange to your browser via Rust feed → Java API → WebSocket fan-out.',
    icon: (
      <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 005.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
      </svg>
    ),
  },
  {
    title: 'Advanced Order Types',
    description: 'Limit, market, stop-loss, take-profit with GTC, IOC, FOK, DAY time-in-force. Full order lifecycle management.',
    icon: (
      <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
      </svg>
    ),
  },
  {
    title: 'Portfolio & Risk Management',
    description: 'Real-time P&L, position tracking, risk metrics, and automated risk controls per account.',
    icon: (
      <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
      </svg>
    ),
  },
  {
    title: 'Alerts & Notifications',
    description: 'Price, spread, and order book imbalance alerts with configurable thresholds and cooldowns.',
    icon: (
      <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
      </svg>
    ),
  },
  {
    title: 'Walk-forward & Sensitivity',
    description: 'Rolling/anchored windows with grid, random, and Sobol optimization. Deflated Sharpe ratios.',
    icon: (
      <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
      </svg>
    ),
  },
  {
    title: 'Golden Vector Parity',
    description: 'Identical fill simulation in Python (backtest) and Java (live) using shared golden vectors.',
    icon: (
      <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    ),
  },
];

function FeatureCard({ feature }: { feature: typeof features[0] }) {
  return (
    <div className="rounded-xl border border-surface-200 dark:border-surface-800 bg-white dark:bg-surface-950 p-6 hover:shadow-lg transition-shadow">
      <div className="text-primary-600 dark:text-primary-400 mb-4">{feature.icon}</div>
      <h3 className="text-lg font-semibold text-surface-900 dark:text-surface-50 mb-2">{feature.title}</h3>
      <p className="text-surface-600 dark:text-surface-400">{feature.description}</p>
    </div>
  );
}