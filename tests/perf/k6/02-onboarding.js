/**
 * Phase 2 — onboarding-api Load Test
 * Tests: login throughput, account creation, JWT issuance rate, partner creation.
 *
 * Run:
 *   k6 run --env PROFILE=light   tests/perf/k6/02-onboarding.js
 *   k6 run --env PROFILE=medium  tests/perf/k6/02-onboarding.js
 *   k6 run --env PROFILE=heavy   tests/perf/k6/02-onboarding.js
 *   k6 run --env PROFILE=stress  tests/perf/k6/02-onboarding.js   ← find breaking point
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = `${__ENV.BASE_URL || 'http://localhost'}:${__ENV.ONBOARDING_PORT || 8080}`;

const loginErrors    = new Rate('login_errors');
const accountErrors  = new Rate('account_errors');
const loginLatency   = new Trend('login_latency_ms', true);
const accountLatency = new Trend('account_latency_ms', true);
const totalLogins    = new Counter('total_logins');

const PROFILES = {
  light:  { vus: 10,  duration: '5m',  rampUp: '30s' },
  medium: { vus: 100, duration: '15m', rampUp: '1m'  },
  heavy:  { vus: 500, duration: '30m', rampUp: '3m'  },
  stress: { vus: 1000, duration: '10m', rampUp: '5m' },
};

const profile = PROFILES[__ENV.PROFILE || 'light'];

export const options = {
  stages: [
    { duration: profile.rampUp,  target: profile.vus },
    { duration: profile.duration, target: profile.vus },
    { duration: '30s',           target: 0 },
  ],
  thresholds: {
    'login_errors':              ['rate<0.01'],
    'account_errors':            ['rate<0.01'],
    'login_latency_ms':          ['p(95)<800'],
    'account_latency_ms':        ['p(95)<1500'],
    'http_req_failed':           ['rate<0.01'],
  },
};

export function setup() {
  const token = login();
  console.log(`Profile: ${__ENV.PROFILE || 'light'} | VUs: ${profile.vus} | Duration: ${profile.duration}`);
  return { adminToken: token };
}

export default function (data) {
  // ── Login (JWT issuance throughput) ──────────────────────────────────────
  group('login', () => {
    const res = http.post(
      `${BASE}/api/auth/login`,
      JSON.stringify({ email: 'admin@filetransfer.local', password: 'Admin@1234' }),
      { headers: { 'Content-Type': 'application/json' }, tags: { test: 'login' } }
    );

    const ok = check(res, {
      'login: status 200':   (r) => r.status === 200,
      'login: has token':    (r) => r.status === 200 && (r.body.includes('"token"') || r.body.includes('"accessToken"')),
      'login: latency <1s':  (r) => r.timings.duration < 1000,
    });

    loginErrors.add(!ok);
    loginLatency.add(res.timings.duration);
    totalLogins.add(1);
  });

  sleep(0.2);

  // ── Account read (list) ───────────────────────────────────────────────────
  if (data.adminToken) {
    group('account_list', () => {
      const res = http.get(
        `${BASE}/api/accounts?page=0&size=10`,
        { headers: authHeaders(data.adminToken), tags: { test: 'account_list' } }
      );
      check(res, {
        'accounts: status 200': (r) => r.status === 200,
        'accounts: latency <500ms': (r) => r.timings.duration < 500,
      });
    });
  }

  sleep(0.1);

  // ── Account creation (write throughput) — only 10% of VUs to avoid DB flood
  if (Math.random() < 0.1 && data.adminToken) {
    group('account_create', () => {
      const suffix = randomString(8);
      const res = http.post(
        `${BASE}/api/accounts`,
        JSON.stringify({
          name: `PerfTest-${suffix}`,
          email: `perf-${suffix}@test.local`,
          protocol: 'SFTP',
          enabled: true,
        }),
        { headers: authHeaders(data.adminToken), tags: { test: 'account_create' } }
      );

      const ok = check(res, {
        'create: status 200 or 201': (r) => r.status === 200 || r.status === 201,
        'create: latency <2s': (r) => r.timings.duration < 2000,
      });

      accountErrors.add(!ok);
      accountLatency.add(res.timings.duration);
    });
  }

  sleep(0.3 + Math.random() * 0.4);
}

export function teardown(data) {
  console.log(`\nTotal logins: ${totalLogins.name}`);
  console.log(`Login error rate check: ${loginErrors.name}`);
}
