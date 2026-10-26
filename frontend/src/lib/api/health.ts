import type { HealthResponse } from './types';

export type HealthCheckStatus = 'healthy' | 'degraded' | 'unhealthy';

export interface HealthCheck {
  component: string;
  status: HealthCheckStatus;
  detail: string;
}

export interface HealthSummary {
  healthy: boolean;
  checks: HealthCheck[];
  buildVersion: string;
}

/** Converts the documented system-health response into display-safe status rows. */
export function summarizeHealth(response: HealthResponse): HealthSummary {
  const symbolChecks: HealthCheck[] = response.symbols.map((symbol) => ({
    component: symbol.symbol,
    status: symbol.book_valid ? 'healthy' : 'unhealthy',
    detail: symbol.book_valid
      ? `Book valid; epoch ${symbol.epoch}, ${symbol.last_event_age_ms} ms since the last event`
      : `Book invalid; ${symbol.resync_count} resyncs recorded`,
  }));
  const connected = response.connected;

  return {
    healthy: connected && symbolChecks.every((check) => check.status === 'healthy'),
    checks: [
      {
        component: 'Market feed connection',
        status: connected ? 'healthy' : 'unhealthy',
        detail: connected ? 'Connected' : 'Disconnected',
      },
      ...symbolChecks,
    ],
    buildVersion: response.buildVersion,
  };
}
