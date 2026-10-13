import Link from 'next/link';

export default function MarketingLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col min-h-screen">
      <header className="border-b border-surface-200 dark:border-surface-800 bg-white/80 dark:bg-surface-950/80 backdrop-blur-sm sticky top-0 z-50">
        <nav className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8" aria-label="Main navigation">
          <div className="flex h-16 items-center justify-between">
            <div className="flex items-center gap-8">
              <Link href="/" className="flex items-center gap-2 text-xl font-bold text-primary-600 dark:text-primary-400">
                <svg className="h-8 w-8" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
                </svg>
                Stock Broker
              </Link>
              <div className="hidden md:flex md:gap-6">
                <Link href="/features" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  Features
                </Link>
                <Link href="/pricing" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  Pricing
                </Link>
                <Link href="/docs" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  Docs
                </Link>
                <Link href="/about" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                  About
                </Link>
              </div>
            </div>
            <div className="flex items-center gap-4">
              <Link href="/login" className="text-sm font-medium text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
                Sign in
              </Link>
              <Link
                href="/register"
                className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 transition-colors"
              >
                Get Started
              </Link>
            </div>
          </div>
        </nav>
      </header>
      <main className="flex-1">{children}</main>
      <footer className="border-t border-surface-200 dark:border-surface-800 bg-white dark:bg-surface-950">
        <div className="mx-auto max-w-7xl px-4 py-12 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 gap-8 md:grid-cols-4">
            <div className="col-span-1 md:col-span-2">
              <Link href="/" className="flex items-center gap-2 text-xl font-bold text-primary-600 dark:text-primary-400">
                <svg className="h-8 w-8" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
                </svg>
                Stock Broker
              </Link>
              <p className="mt-4 text-sm text-surface-600 dark:text-surface-400 max-w-xs">
                Professional paper trading platform with real-time market data, advanced charting, and backtesting capabilities.
              </p>
            </div>
            <div>
              <h3 className="text-sm font-semibold text-surface-900 dark:text-surface-100">Product</h3>
              <ul className="mt-4 space-y-3">
                <li><Link href="/features" className="text-sm text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400">Features</Link></li>
                <li><Link href="/pricing" className="text-sm text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400">Pricing</Link></li>
                <li><Link href="/changelog" className="text-sm text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400">Changelog</Link></li>
                <li><Link href="/roadmap" className="text-sm text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400">Roadmap</Link></li>
              </ul>
            </div>
            <div>
              <h3 className="text-sm font-semibold text-surface-900 dark:text-surface-100">Resources</h3>
              <ul className="mt-4 space-y-3">
                <li><Link href="/docs" className="text-sm text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400">Documentation</Link></li>
                <li><Link href="/api-docs" className="text-sm text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400">API Reference</Link></li>
                <li><Link href="/community" className="text-sm text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400">Community</Link></li>
                <li><Link href="/blog" className="text-sm text-surface-600 dark:text-surface-400 hover:text-primary-600 dark:hover:text-primary-400">Blog</Link></li>
              </ul>
            </div>
          </div>
          <div className="mt-12 border-t border-surface-200 dark:border-surface-800 pt-8">
            <p className="text-sm text-surface-500 dark:text-surface-500 text-center">
              This is a paper trading platform only. No real money, no custody, no order routing, no brokerage credentials.
              All trading is simulated. The UI and all charts are labeled simulated / backtested; not investment advice.
            </p>
          </div>
        </div>
      </footer>
    </div>
  );
}