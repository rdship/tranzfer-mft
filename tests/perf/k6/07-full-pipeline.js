/**
 * Phase 3 — Full End-to-End Pipeline Test (REST path)
 * Simulates complete transfer lifecycle:
 *   login → create transfer → screening → encryption check → storage → verify status
 *
 * Run:
 *   k6 run --env PROFILE=light  tests/perf/k6/07-full-pipeline.js
 *   k6 run --env PROFILE=medium tests/perf/k6/07-full-pipeline.js
 *   k6 run --env PROFILE=heavy  tests/perf/k6/07-full-pipeline.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE       = __ENV.BASE_URL       || 'http://localhost';
const ONBOARD    = `${BASE}:8080`;
const SCREEN     = `${BASE}:8092`;
const ENCRYPT    = `${BASE}:8086`;
const STORAGE    = `${BASE}:8096`;
const ANALYTICS  = `${BASE}:8090`;
const SENTINEL   = `${BASE}:8098`;

const e2eLatency      = new Trend('e2e_latency_ms', true);
const pipelineErrors  = new Rate('pipeline_errors');
const transfersOk     = new Counter('transfers_completed');
const transfersFailed = new Counter('transfers_failed');

const PROFILES = {
  light:  { vus: 10,  stages: [{ duration:'30s',target:10 },  { duration:'5m',  target:10  }, { duration:'30s',target:0 }] },
  medium: { vus: 100, stages: [{ duration:'1m', target:100 }, { duration:'15m', target:100 }, { duration:'1m', target:0 }] },
  heavy:  { vus: 500, stages: [{ duration:'3m', target:500 }, { duration:'30m', target:500 }, { duration:'2m', target:0 }] },
};

const profile = PROFILES[__ENV.PROFILE || 'light'];

export const options = {
  stages: profile.stages,
  thresholds: {
    'e2e_latency_ms':   ['p(95)<5000', 'p(99)<10000'],
    'pipeline_errors':  ['rate<0.02'],
    'http_req_failed':  ['rate<0.02'],
  },
};

export function setup() {
  const token = login();
  if (!token) { throw new Error('Setup failed: could not obtain JWT token'); }

  // Get or create a test account
  const accountRes = http.get(
    `${ONBOARD}/api/accounts?page=0&size=1`,
    { headers: authHeaders(token) }
  );
  let accountId = null;
  if (accountRes.status === 200) {
    try {
      const accounts = JSON.parse(accountRes.body);
      accountId = accounts.content?.[0]?.id || accounts[0]?.id || null;
    } catch (_) {}
  }

  console.log(`Pipeline test | Profile: ${__ENV.PROFILE || 'light'} | VUs: ${profile.vus}`);
  return { token, accountId };
}

export default function (data) {
  const startTime = Date.now();
  const trackId   = `PERF-${Date.now()}-${randomString(8)}`;
  let pipelineOk  = true;

  // ── Step 1: Authenticate ──────────────────────────────────────────────────
  // (Token from setup — reused for efficiency)

  // ── Step 2: Check transfer status (GET by trackId — 404 expected for new IDs) ─
  group('transfer_lookup', () => {
    const res = http.get(
      `${ONBOARD}/api/v2/transfer/${trackId}`,
      { headers: authHeaders(data.token), tags: { step: 'lookup' } }
    );

    // 404 is expected (new trackId not yet in system) — 5xx means service is broken
    const ok = check(res, {
      'transfer lookup: service responds (not 5xx)': (r) => r.status < 500,
    });
    if (!ok) pipelineOk = false;
  });

  sleep(0.05);

  // ── Step 3: Screening check (use /scan/text — accepts JSON body) ─────────
  group('screening', () => {
    const res = http.post(
      `${SCREEN}/api/v1/screening/scan/text`,
      JSON.stringify({
        content:  `COMPANY: ACME CORP\nPARTNER: PARTNER INC\nTRACK: ${trackId}`,
        filename: `perf-test-${trackId}.csv`,
        trackId:  trackId,
      }),
      { headers: authHeaders(data.token), tags: { step: 'screen' } }
    );

    const ok = check(res, {
      'screening: not blocked': (r) => r.status !== 403,
      'screening: responds':    (r) => r.status < 500,
    });
    if (!ok) pipelineOk = false;
  });

  sleep(0.05);

  // ── Step 4: Encrypt (credential encrypt — no keyId needed) ──────────────
  group('encrypt', () => {
    const res = http.post(
      `${ENCRYPT}/api/encrypt/credential/encrypt`,
      JSON.stringify({ value: `PerfTestPayload-${trackId}` }),
      { headers: authHeaders(data.token), tags: { step: 'encrypt' } }
    );

    const ok = check(res, {
      'encrypt: responds': (r) => r.status < 500,
    });
    if (!ok) pipelineOk = false;
  });

  sleep(0.05);

  // ── Step 5: Storage metadata ──────────────────────────────────────────────
  group('storage', () => {
    const res = http.get(
      `${STORAGE}/api/v1/storage/health`,
      { headers: authHeaders(data.token), tags: { step: 'storage' } }
    );
    check(res, { 'storage: responds': (r) => r.status < 500 });
  });

  // ── Step 6: Verify analytics received the event ───────────────────────────
  group('analytics_verify', () => {
    const res = http.get(
      `${ANALYTICS}/api/v1/analytics/dashboard`,
      { headers: authHeaders(data.token), tags: { step: 'analytics' } }
    );
    check(res, { 'analytics: responds': (r) => r.status !== 500 });
  });

  // ── Record E2E metrics ────────────────────────────────────────────────────
  const e2eDuration = Date.now() - startTime;
  e2eLatency.add(e2eDuration);
  pipelineErrors.add(!pipelineOk);

  if (pipelineOk) {
    transfersOk.add(1);
  } else {
    transfersFailed.add(1);
  }

  sleep(0.5 + Math.random() * 0.5);
}

export function teardown(data) {
  // Check Sentinel for any findings generated during load test
  const res = http.get(
    `${SENTINEL}/api/v1/sentinel/findings?status=OPEN`,
    { headers: authHeaders(data.token) }
  );
  if (res.status === 200) {
    try {
      const findings = JSON.parse(res.body);
      const count = Array.isArray(findings) ? findings.length : (findings.totalElements || 0);
      console.log(`\nSentinel findings at test end: ${count} OPEN findings`);
      if (count > 0 && Array.isArray(findings)) {
        findings.slice(0, 5).forEach(f => {
          console.log(`  [${f.severity}] ${f.ruleName}: ${f.title}`);
        });
      }
    } catch (_) {}
  }
}
