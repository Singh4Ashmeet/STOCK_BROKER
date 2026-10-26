import type { Metadata, Viewport } from 'next';
import { Providers } from './providers';
import './globals.css';

// Deployments must set NEXT_PUBLIC_APP_URL to their public HTTPS origin. The
// documented localhost value keeps local builds and social metadata deterministic.
const metadataBase = new URL(process.env.NEXT_PUBLIC_APP_URL || 'http://localhost:3000');

export const metadata: Metadata = {
  metadataBase,
  title: {
    default: 'Stock Broker - Paper Trading Platform',
    template: '%s | Stock Broker',
  },
  description: 'Professional paper trading platform with real-time market data, advanced charting, and backtesting.',
  keywords: ['trading', 'paper trading', 'backtesting', 'market data', 'finance'],
  authors: [{ name: 'Stock Broker Team' }],
  creator: 'Stock Broker',
  publisher: 'Stock Broker',
  robots: 'index, follow',
  openGraph: {
    type: 'website',
    locale: 'en_US',
    url: '/',
    siteName: 'Stock Broker',
    title: 'Stock Broker - Paper Trading Platform',
    description: 'Professional paper trading platform with real-time market data, advanced charting, and backtesting.',
    images: [
      {
        url: '/og-image.png',
        width: 1200,
        height: 630,
        alt: 'Stock Broker Dashboard',
      },
    ],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Stock Broker - Paper Trading Platform',
    description: 'Professional paper trading platform with real-time market data, advanced charting, and backtesting.',
    images: ['/og-image.png'],
  },
  icons: {
    icon: '/icon.svg',
    shortcut: '/icon.svg',
    apple: '/icon.svg',
  },
  manifest: '/site.webmanifest',
};

export const viewport: Viewport = {
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#171916' },
    { media: '(prefers-color-scheme: dark)', color: '#171916' },
  ],
  width: 'device-width',
  initialScale: 1,
  maximumScale: 5,
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
  <html lang="en" className="dark antialiased">
      <body className="min-h-screen bg-surface-50 dark:bg-surface-950 text-surface-900 dark:text-surface-50">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
