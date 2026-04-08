/**
 * TranzFer MFT — Chaos Background Load Script
 * Generates continuous load DURING chaos injection tests.
 * Used by: startup-safety.sh, graceful-shutdown.sh, scale-surge.sh
 *
 * Each VU loop: login → accounts list → screening scan → sleep(0.5s)
 * Custom metrics: chaos_errors (rate), chaos_latency_ms (trend)
 * Summary lines emitted every 30s via console.log for shell scripts to parse.
 *
 * Run:
 *   k6 run --env DURATION=5m  tests/perf/k6/10-chaos-background.js
 *   k6 run --env DURATION=10m --env VUS=30 tests/perf/k6/10-chaos-background.js
 *   k6 run --env DURATION=2m  --env TARGET_PORT=8080 tests/perf/k6/10-chaos-background.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';

const BASE         = __ENV.BASE_URL      || 'http://localhost';
const ONBOARD      = `${BASE}:8080`;
const SCREEN       = `${BASE}:8092`;
const DURATION     = __ENV.DURATION      || '5m';
const TARGET_VUS   = parseInt(__ENV.VUS  || '20', 10);

// ── Custom metrics (parseable by chaos shell scripts) ─────────────────────────
const chaosErrors    = new Rate('chaos_errors');
const chaosLatency   = new Trend('chaos_latency_ms', true);
const reqSuccess     = new Counter('chaos_requests_ok');
const reqFailed      = new Counter('chaos_requests_failed');
const activeVus      = new Gauge('chaos_active_vus');

// Per-status counters for shell scripts
const status200      = new Counter('chaos_status_200');
const status502      = new Counter('chaos_status_502');
const status503      = new Counter('chaos_status_503');
const status5xx      = new Counter('chaos_status_5xx_other');
const connReset      = new Counter('chaos_conn_reset');
const connRefused    = new Counter('chaos_conn_refused');

export const options = {
  scenarios: {
    chaos_load: {
      executor:  'constant-vus',
      vus:       TARGET_VUS,
      duration:  DURATION,
    },
  },
  thresholds: {
    // Intentionally lenient — chaos IS expected; we measure, not gate
    'chaos_errors':      ['rate<0.50'],
    'chaos_latency_ms':  ['p(95)<10000'],
  },
  // Suppress default summary spam; we emit our own every 30s
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
};

// ── Setup: obtain shared token ────────────────────────────────────────────────
export function setup() {
  const token = login();
  if (!token) {
    console.error('CHAOS-BG: Login failed — is the platform running?');
    return { token: null };
  }
  console.log(`CHAOS-BG: background load starting | VUs=${TARGET_VUS} | duration=${DURATION}`);
  return { token, startEpoch: Date.now() };
}

// ── Per-VU iteration ──────────────────────────────────────────────────────────
export default function (data) {
  if (!data.token) {
    sleep(1);
    return;
  }

  activeVus.add(1);
  const iterStart = Date.now();
  let iterOk = true;

  // ── Step 1: List accounts (read-heavy, stateless) ─────────────────────────
  group('accounts_list', () => {
    const res = http.get(
      `${ONBOARD}/api/accounts?page=0&size=5`,
      {
        headers:  authHeaders(data.token),
        tags:     { step: 'accounts', chaos: 'true' },
        timeout:  '5s',
      }
    );

    classifyResponse(res);
    const ok = check(res, {
      'accounts: not 502':  (r) => r.status !== 502,
      'accounts: not 5xx':  (r) => r.status < 500 || r.status === 503,
    });
    if (!ok) iterOk = false;
  });

  sleep(0.1);

  // ── Step 2: Screening scan (write-path, exercises RabbitMQ) ───────────────
  group('screening_scan', () => {
    const trackId = `CHAOS-BG-${Date.now()}-${__VU}-${__ITER}`;
    const res = http.post(
      `${SCREEN}/api/v1/screening/scan/text`,
      JSON.stringify({
        content:  `COMPANY: ACME CORP\nFILE: chaos-background-test.csv\nTRACK: ${trackId}`,
        filename: `chaos-bg-${trackId}.csv`,
        trackId:  trackId,
      }),
      {
        headers:  authHeaders(data.token),
        tags:     { step: 'screening', chaos: 'true' },
        timeout:  '5s',
      }
    );

    classifyResponse(res);
    const ok = check(res, {
      'screening: not 502':  (r) => r.status !== 502,
      'screening: not 500':  (r) => r.status !== 500,
    });
    if (!ok) iterOk = false;
  });

  // ── Record E2E iter metrics ───────────────────────────────────────────────
  const iterDuration = Date.now() - iterStart;
  chaosLatency.add(iterDuration);
  chaosErrors.add(!iterOk);

  if (iterOk) {
    reqSuccess.add(1);
  } else {
    reqFailed.add(1);
  }

  sleep(0.5);
}

// ── Classify response into specific counters (parseable by shell) ─────────────
function classifyResponse(res) {
  const status = res.status;
  const body   = res.body || '';

  if (status === 200 || status === 201) {
    status200.add(1);
  } else if (status === 502) {
    status502.add(1);
  } else if (status === 503) {
    status503.add(1);
  } else if (status >= 500) {
    status5xx.add(1);
  }

  // Detect connection-level errors from k6 error strings
  if (res.error && res.error.includes('reset')) {
    connReset.add(1);
  }
  if (res.error && (res.error.includes('refused') || status === 0)) {
    connRefused.add(1);
  }
}

// ── Teardown: emit final parseable summary ────────────────────────────────────
export function teardown(data) {
  console.log('CHAOS-BG-SUMMARY: background load test complete');
  // The k6 built-in end-of-test summary will be printed automatically
  // Shell scripts grep for: CHAOS-BG-SUMMARY, chaos_errors, chaos_latency_ms
}

// ── handleSummary: machine-readable output for shell script parsing ───────────
export function handleSummary(data) {
  const metrics  = data.metrics;
  const errRate  = (metrics.chaos_errors?.values?.rate  || 0) * 100;
  const p95      = metrics.chaos_latency_ms?.values?.['p(95)'] || 0;
  const ok       = metrics.chaos_requests_ok?.values?.count   || 0;
  const failed   = metrics.chaos_requests_failed?.values?.count || 0;
  const s502     = metrics.chaos_status_502?.values?.count     || 0;
  const s503     = metrics.chaos_status_503?.values?.count     || 0;
  const s5xx     = metrics.chaos_status_5xx_other?.values?.count || 0;
  const cr       = metrics.chaos_conn_reset?.values?.count     || 0;
  const cref     = metrics.chaos_conn_refused?.values?.count   || 0;

  // Emit key=value lines that shell scripts can source or grep
  const summary = [
    '--- CHAOS-BG-METRICS ---',
    `error_rate_pct=${errRate.toFixed(2)}`,
    `p95_latency_ms=${p95.toFixed(0)}`,
    `requests_ok=${ok}`,
    `requests_failed=${failed}`,
    `status_502=${s502}`,
    `status_503=${s503}`,
    `status_5xx_other=${s5xx}`,
    `conn_reset=${cr}`,
    `conn_refused=${cref}`,
    '--- END-CHAOS-BG-METRICS ---',
  ].join('\n');

  // Write machine-readable summary to file for shell script parsing
  const outFile = __ENV.SUMMARY_FILE || '/tmp/chaos-bg-summary.txt';

  return {
    stdout:   summary + '\n',
    [outFile]: summary + '\n',
  };
}
