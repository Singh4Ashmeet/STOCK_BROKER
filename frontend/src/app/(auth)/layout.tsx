export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="grid min-h-screen grid-cols-1 bg-surface-950 px-4 py-8 lg:grid-cols-[minmax(360px,0.9fr)_minmax(520px,1.1fr)] lg:p-0">
      <aside className="hidden border-r border-surface-600 bg-surface-900 p-10 lg:flex lg:flex-col lg:justify-between">
        <div>
          <a href="/" className="inline-flex items-center gap-3" aria-label="Stock Broker home">
            <span className="grid h-9 w-9 place-items-center border border-primary-500 bg-primary-500 font-display text-sm font-bold text-surface-950">SB</span>
            <span className="font-mono text-sm font-bold tracking-[0.18em] text-primary-400">STOCK_BROKER</span>
          </a>
          <div className="mt-24 max-w-md">
            <p className="terminal-label text-primary-400">Paper trading platform</p>
            <h1 className="mt-3 font-display text-5xl font-semibold leading-tight tracking-tight text-surface-50">Decisions need a clear instrument panel.</h1>
            <p className="mt-6 text-base leading-7 text-surface-200">Access your paper-trading workspace, live market-book views, and account-ledger data from one deliberately quiet interface.</p>
          </div>
        </div>
        <p className="max-w-sm border-t border-surface-600 pt-5 font-mono text-xs leading-5 text-surface-300">Simulation only. No real-money custody or order routing is available through this application.</p>
      </aside>
      <div className="flex items-center justify-center py-8 lg:p-10">
        <div className="w-full max-w-md">{children}</div>
      </div>
    </div>
  );
}
