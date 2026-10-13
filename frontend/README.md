# Stock Broker Frontend

Next.js 14 App Router frontend for the Stock Broker platform.

## Architecture

- **Framework**: Next.js 14 (App Router)
- **Language**: TypeScript (strict mode)
- **Styling**: Tailwind CSS
- **State Management**: Zustand with selector-based subscriptions
- **Real-time**: WebSocket in Web Worker (connects directly to Java API)
- **Authentication**: Access token in memory, refresh token in httpOnly cookie
- **API Client**: Generated from Java OpenAPI spec (`npm run generate:api`)

## Route Groups

```
src/app/
├── (marketing)/          # Public pages (no auth)
│   ├── layout.tsx        # Marketing layout with header/footer
│   ├── page.tsx          # Landing page
│   ├── features/page.tsx
│   ├── pricing/page.tsx
│   └── docs/page.tsx
├── (auth)/               # Auth pages (no layout, centered)
│   ├── layout.tsx
│   ├── login/page.tsx
│   └── register/page.tsx
├── (app)/                # Protected app pages (requires auth)
│   ├── layout.tsx        # App shell with header/navigation
│   ├── dashboard/page.tsx          # Real-time dashboard
│   ├── trading/page.tsx            # Trade ticket with slippage preview
│   ├── portfolio/page.tsx          # Positions, balances, ledger
│   ├── watchlist/page.tsx          # Watchlist with detail view
│   ├── alerts/page.tsx             # Price/spread/imbalance alerts
│   ├── research/page.tsx           # Backtesting (equity curve, drawdown, costs)
│   ├── performance/page.tsx        # 60 FPS stress test
│   ├── health/page.tsx             # System health monitoring
│   └── layout.tsx
├── api/                  # API routes (Next.js server actions)
│   ├── auth/
│   │   ├── login/route.ts
│   │   ├── register/route.ts
│   │   ├── refresh/route.ts
│   │   └── logout/route.ts
│   └── ws/ticket/route.ts
├── layout.tsx            # Root layout with providers
├── page.tsx              # Redirects to /app/dashboard or /login
├── globals.css           # Tailwind + custom styles
└── providers.tsx         # Context providers (AuthProvider)
```

## Key Features

### Authentication Flow

1. **Login/Register** → POST to `/api/auth/login` or `/api/auth/register`
2. **Java API** returns access token + sets httpOnly refresh cookie
3. **Next.js API route** forwards access token to client, sets refresh cookie
4. **Client** stores access token in memory (Zustand/auth context)
5. **Subsequent requests** include `Authorization: Bearer <access_token>`
6. **Token refresh** → Automatic via `/api/auth/refresh` (uses httpOnly cookie)
6. **Logout** → Clears memory token, calls Java logout, deletes cookie

### WebSocket Connection

1. Client requests ticket via `/api/ws/ticket` (with Bearer token)
2. Java API returns single-use ticket
3. Client connects directly to Java WebSocket: `ws://localhost:8081/ws/v1?ticket=<ticket>`
2. Messages batched in Web Worker, posted to main thread at animation frame rate
3. Zustand store updated via message handlers

### Route Protection

- **Middleware** (`src/middleware.ts`) checks:
  - Public paths: `/`, `/login`, `/register`, `/api/auth/*`
  - Protected paths: `/app/*`, `/api/app/*`
- **Redirects** unauthenticated browser requests to `/login?redirect=...`
- **Returns 401** for unauthenticated API requests

### API Client

- Generated from Java OpenAPI spec: `npm run generate:api`
- Output: `src/lib/api/schema.ts`
- Wrapper: `src/lib/api/client.ts` with auth-aware fetch

### Real-time Market Data

- WebSocket messages: `book.snap`, `book.delta`, `trade`, `metrics`
- Zustand store (`src/lib/store/market.ts`) with selector-based subscriptions
- Order book conflation handled by Java; client receives latest snapshot/delta
- Canvas-based rendering for order book depth chart

### App Pages

| Page | Route | Description |
|------|-------|-------------|
| Dashboard | `/app/dashboard` | Real-time overview with watchlist, charts, order book, trades |
| Trading | `/app/trading` | Order ticket with slippage preview, order book, trades tape |
| Portfolio | `/app/portfolio` | Positions, balances, ledger, P&L |
| Watchlist | `/app/watchlist` | Symbol watchlist with detail view (charts, order book, trades) |
| Alerts | `/app/alerts` | Price/spread/imbalance alerts with CRUD |
| Research | `/app/research` | Backtests list, equity curve, drawdown, cost breakdown, trade log |
| Performance | `/app/performance` | 60 FPS stress test with FPS timeline |
| Health | `/app/health` | System health (API, feed, DB, Redis, WebSocket) |

## Development

```bash
# Install dependencies
npm install

# Generate API client from Java OpenAPI spec
# (requires Java API running on localhost:8081)
npm run generate:api

# Start dev server
npm run dev

# Type check
npm run type-check

# Lint
npm run lint

# Build for production
npm run build
npm start
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `NEXT_PUBLIC_API_URL` | Java API base URL | `http://localhost:8081` |
| `NEXT_PUBLIC_WS_URL` | Java WebSocket base URL | `ws://localhost:8081` |
| `NEXT_PUBLIC_APP_URL` | Frontend URL | `http://localhost:3000` |

## Project Structure

```
src/
├── app/                    # App Router pages
│   ├── (marketing)/        # Public marketing pages
│   ├── (auth)/             # Auth pages
│   ├── (app)/              # Protected app pages
│   └── api/                # API routes
├── lib/
│   ├── auth/               # Auth context, client, types
│   ├── api/                # API client, types, WebSocket
│   ├── store/              # Zustand stores
│   └── utils.ts            # Shared utilities
├── middleware.ts           # Route protection
└── globals.css             # Global styles
```

## Testing

```bash
# Unit tests (when added)
npm test

# E2E tests (when added)
npm run test:e2e
```

## Deployment

### Docker

```bash
docker build -t stockbroker-web .
docker run -p 3000:3000 stockbroker-web
```

### Vercel (Recommended)

1. Connect repository to Vercel
2. Set environment variables
3. Deploy

### Static Export (for CDN)

```bash
npm run build
# Output in .next/
```

## Security

- **httpOnly cookies** for refresh tokens
- **SameSite=Strict** CSRF protection
- **Content Security Policy** headers
- **Rate limiting** via Java API
- **CSP** and security headers in `next.config.js`

## Contributing

1. Follow TypeScript strict mode
2. Use `clsx` + `tailwind-merge` for class names
3. Keep components small and focused
4. Use selector-based Zustand subscriptions
5. Run `npm run type-check` before committing