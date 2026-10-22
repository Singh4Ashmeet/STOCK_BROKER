import type { Metadata, Viewport } from 'next';
import { Inter } from 'next/font/google';
import { Providers } from './providers';
import './globals.css';

const inter = Inter({
  subsets: ['latin'],
  variable: '--font-inter',
  display: 'swap',
});

export const metadata: Metadata = {
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
    url: 'https://stockbroker.example.com',
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
    icon: '/favicon.ico',
    shortcut: '/favicon-16x16.png',
    apple: '/apple-touch-icon.png',
  },
  manifest: '/site.webmanifest',
};

export const viewport: Viewport = {
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#ffffff' },
    { media: '(prefers-color-scheme: dark)', color: '#0f0f0f' },
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
    <html lang="en" className={`${inter.variable} antialiased`}>
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
      </head>
      <body className="min-h-screen bg-surface-50 dark:bg-surface-950 text-surface-900 dark:text-surface-50">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}