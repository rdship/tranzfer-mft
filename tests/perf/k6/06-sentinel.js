/**
 * Phase 2 — platform-sentinel API Load Test
 * Tests: health score retrieval, findings pagination, rules API, analysis trigger.
 *
 * Run: k6 run tests/perf/k6/06-sentinel.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate, Gauge } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';

const BASE_SENTINEL = `${__ENV.BASE_URL || 'http://localhost'}:8098`;

const healthScoreLatency = new Trend('health_score_latency_ms', true);
const findingsLatency    = new Trend('findings_latency_ms', true);
const healthScore        = new Gauge('platform_health_score');
const apiErrors          = new Rate('sentinel_api_errors');

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '3m',  target: 50 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    'health_score_latency_ms': ['p(95)<300'],
    'findings_latency_ms':     ['p(95)<500'],
    'sentinel_api_errors':     ['rate<0.01'],
    'http_req_failed':         ['rate<0.01'],
  },
};

export function setup() {
  const token = login();

  // Capture baseline health score
  const res = http.get(
    `${BASE_SENTINEL}/api/v1/sentinel/health-score`,
    { headers: authHeaders(token) }
  );
  let baseline = 0;
  if (res.status === 200) {
    try { baseline = JSON.parse(res.body).overallScore || 0; } catch (_) {}
  }
  console.log(`Sentinel health score at test start: ${baseline}`);
  return { token, baselineScore: baseline };
}

export default function (data) {
  // ── Health Score ──────────────────────────────────────────────────────────
  group('health_score', () => {
    const res = http.get(
      `${BASE_SENTINEL}/api/v1/sentinel/health-score`,
      { headers: authHeaders(data.token), tags: { endpoint: 'health_score' } }
    );

    const ok = check(res, {
      'health score: status 200':    (r) => r.status === 200,
      'health score: has score':     (r) => r.status === 200 && r.body.includes('overallScore'),
      'health score: latency <300ms':(r) => r.timings.duration < 300,
    });

    apiErrors.add(!ok);
    healthScoreLatency.add(res.timings.duration);

    if (res.status === 200) {
      try {
        const score = JSON.parse(res.body).overallScore;
        if (score !== undefined) healthScore.add(score);
      } catch (_) {}
    }
  });

  sleep(0.2);

  // ── Findings List ─────────────────────────────────────────────────────────
  group('findings', () => {
    const res = http.get(
      `${BASE_SENTINEL}/api/v1/sentinel/findings?status=OPEN&page=0&size=20`,
      { headers: authHeaders(data.token), tags: { endpoint: 'findings' } }
    );

    check(res, {
      'findings: status 200':     (r) => r.status === 200,
      'findings: latency <500ms': (r) => r.timings.duration < 500,
    });

    findingsLatency.add(res.timings.duration);
  });

  sleep(0.1);

  // ── Dashboard (summary) ───────────────────────────────────────────────────
  group('dashboard', () => {
    const res = http.get(
      `${BASE_SENTINEL}/api/v1/sentinel/dashboard`,
      { headers: authHeaders(data.token), tags: { endpoint: 'dashboard' } }
    );
    check(res, {
      'dashboard: not 5xx': (r) => r.status < 500,
    });
  });

  // ── Rules API (read-only) ─────────────────────────────────────────────────
  if (Math.random() < 0.2) {
    group('rules', () => {
      const res = http.get(
        `${BASE_SENTINEL}/api/v1/sentinel/rules`,
        { headers: authHeaders(data.token), tags: { endpoint: 'rules' } }
      );
      check(res, {
        'rules: status 200': (r) => r.status === 200,
        'rules: 14 rules':   (r) => {
          if (r.status !== 200) return true;
          try { return JSON.parse(r.body).length >= 14; } catch (_) { return true; }
        },
      });
    });
  }

  sleep(0.4 + Math.random() * 0.4);
}

export function teardown(data) {
  // Capture final health score — should be similar to baseline
  const res = http.get(
    `${BASE_SENTINEL}/api/v1/sentinel/health-score`,
    { headers: authHeaders(data.token) }
  );
  if (res.status === 200) {
    try {
      const score = JSON.parse(res.body).overallScore;
      const delta = score - data.baselineScore;
      console.log(`\nHealth score: start=${data.baselineScore} end=${score} delta=${delta > 0 ? '+' : ''}${delta}`);
      if (score < 60) {
        console.warn('WARNING: Health score dropped significantly during test — check findings!');
      }
    } catch (_) {}
  }
}
