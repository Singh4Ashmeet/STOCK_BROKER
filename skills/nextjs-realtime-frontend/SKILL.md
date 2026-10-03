---
name: nextjs-realtime-frontend
description: Building the Next.js (App Router, TypeScript) frontend for a real-time trading dashboard: client-only live app with Web Worker WebSocket handling, requestAnimationFrame batching, canvas order book and depth charts, Zustand selectors, lightweight-charts, auth cookie/middleware flow, performance budgets, accessibility and Playwright tests, plus the static marketing/benchmark site. Use this skill whenever writing or reviewing frontend code, UI components for market data, the trade ticket, charts, auth flows, or frontend performance work.
---

# Next.js real-time frontend

Spec: `docs/02-TRD.md` section 8, API: `docs/04-API-SPEC.md`, ADR-0008. Check current Next.js docs for API details (App Router conventions change); keep TypeScript `strict`.

## Structure
```
app/
  (marketing)/   static/ISR: landing, architecture, benchmarks, docs
  (auth)/        login, register
  (app)/         market, trade, portfolio, research, health, watchlist  (client-only live code)
  api/           route handlers for auth cookie handling only (not market data)
middleware.ts    protects (app) routes using the refresh/session cookie
lib/             fixed-point formatting, API client (generated from OpenAPI), ws protocol types
workers/         market-ws.worker.ts
```
Marketing pages stay tiny: no live code, no charts bundle. Set per-route-group bundle budgets (CI check).

## Live data pipeline
1. **Web Worker** owns the WebSocket: connect with a ticket, subscribe, parse JSON, verify `epoch/seq`, maintain book state (apply snapshot/deltas) in typed arrays, compute display structures.
2. Worker posts **batched** updates to the main thread **at most once per animation frame** (use transferable buffers where possible). The main thread never parses network frames.
3. **Zustand** store with narrow selectors (`useStore(s => s.book.best)`); avoid putting the full book in React state; use refs/imperative canvas drawing for the book and depth chart.
4. **Rendering:** order book and depth chart on `<canvas>` with a single `requestAnimationFrame` loop that draws only when dirty; candles/equity via `lightweight-charts` (lazy loaded); DOM only for forms, tables, and headers.
5. **Latency overlay:** show median/p99 of `now - exchange_ts` (indicative) and of server-side stage deltas from message timestamps; label cross-host numbers as indicative.
6. **Resilience:** reconnect with backoff + jitter and a fresh ticket; "resyncing" badge on `resync`; stale indicator when upstream is down; never show a book marked invalid as live.

## Numbers and formatting
- Prices/quantities arrive as integers (or strings/BigInt when `ws_numeric_safe=false`) with scales from `/instruments`. Convert to display strings **only** in `lib/fixed.ts` using integer arithmetic; never `parseFloat` money. Unit-test this module heavily (rounding, negative values, big values).
- Money from REST is a decimal string; display with a decimal library (e.g. `decimal.js`/`big.js`) or integer math; never JS floats for balances.

## Auth flow
- Access token in memory (module state), refresh token in an `httpOnly; Secure; SameSite=Strict` cookie managed by Next.js route handlers; refresh requires a CSRF header/token.
- Obtain a WS ticket via `POST /ws/ticket` immediately before connecting; never put the access token in the URL.
- Middleware redirects unauthenticated users from `(app)`; the server does the real authorization (never trust the client).
- Security headers (CSP with nonces, `frame-ancestors 'none'`, etc.) configured in `next.config`/middleware.

## Trade ticket
Slippage preview calls `POST /orders/preview` (debounced) and also computes a local estimate from the in-memory book for instant feedback (clearly labeled estimate). Order submission generates a UUID `Idempotency-Key` per user action and reuses it on retry. Disable the button while pending; show server-validated results only. Persistent "Simulated trading" label.

## Performance budgets (targets)
60 fps during full-rate replay; main-thread long tasks under 50 ms and per-update work under ~4 ms; marketing JS under ~150 KB gzip; app shell lazy-loads charts/canvas/worker. Measure with Playwright + Performance API traces and Lighthouse for marketing pages.

## Accessibility and UX
Keyboard-operable trade ticket and tables, ARIA labels, color-independent buy/sell cues, prefers-reduced-motion respected, readable contrast; announce connection state changes politely (`aria-live`).

## Testing
- Vitest: fixed-point formatting, WS protocol reducers (snapshot/delta/resync/gap), store selectors.
- Component tests (Testing Library) for forms and tables.
- Playwright E2E with a replay-fed backend: login, subscribe, place order, see fill, portfolio updates, backtest flow; performance trace test under full-rate stream; axe accessibility checks.

## Anti-patterns
- Parsing WebSocket frames or computing the book on the main thread; setState per message.
- Rendering the book as thousands of DOM nodes; re-rendering React on every tick.
- Server-rendering live data or proxying the live WebSocket through Next.js.
- Floating-point math on prices/balances; storing tokens in localStorage.

## Review checklist
- [ ] Live code is client-only and lazy loaded; marketing bundle budget respected
- [ ] Worker + rAF batching in place; no per-message React renders
- [ ] Fixed-point formatting only via `lib/fixed.ts`; tests added
- [ ] Auth flow follows cookie/ticket rules; no secrets in `NEXT_PUBLIC_*`
- [ ] Simulated-trading disclaimer visible; accessibility checks pass
