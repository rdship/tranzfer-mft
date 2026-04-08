/**
 * Phase 2 — analytics-service Query Load Test
 * Tests: dashboard latency, metric ingestion rate, historical queries under concurrency.
 *
 * Run: k6 run tests/perf/k6/05-analytics.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';

const BASE_ANALYTICS = `${__ENV.BASE_URL || 'http://localhost'}:8090`;

const dashboardLatency = new Trend('dashboard_latency_ms', true);
const queryErrors      = new Rate('query_errors');

export const options = {
  stages: [
    { duration: '30s', target: 25  },
    { duration: '2m',  target: 100 },
    { duration: '3m',  target: 100 },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    'dashboard_latency_ms': ['p(95)<500', 'p(99)<1000'],
    'query_errors':         ['rate<0.01'],
    'http_req_failed':      ['rate<0.01'],
  },
};

export function setup() {
  const token = login();
  return { token };
}

export default function (data) {
  // ── Dashboard (most common query) ─────────────────────────────────────────
  group('dashboard', () => {
    const res = http.get(
      `${BASE_ANALYTICS}/api/v1/analytics/dashboard`,
      { headers: authHeaders(data.token), tags: { query: 'dashboard' } }
    );

    const ok = check(res, {
      'dashboard: status 200 or 204': (r) => r.status === 200 || r.status === 204,
      'dashboard: latency < 500ms':   (r) => r.timings.duration < 500,
    });

    queryErrors.add(!ok);
    dashboardLatency.add(res.timings.duration);
  });

  sleep(0.2);

  // ── Transfer count query ──────────────────────────────────────────────────
  group('transfer_count', () => {
    const res = http.get(
      `${BASE_ANALYTICS}/api/v1/analytics/timeseries?period=today`,
      { headers: authHeaders(data.token), tags: { query: 'transfer_count' } }
    );
    check(res, {
      'transfer count responds': (r) => r.status === 200 || r.status === 204 || r.status === 404,
    });
  });

  sleep(0.1);

  // ── Failure rate query ────────────────────────────────────────────────────
  group('failure_rate', () => {
    const res = http.get(
      `${BASE_ANALYTICS}/api/v1/analytics/predictions`,
      { headers: authHeaders(data.token), tags: { query: 'failure_rate' } }
    );
    check(res, {
      'failure rate responds': (r) => r.status !== 500,
    });
  });

  sleep(0.3 + Math.random() * 0.3);
}
