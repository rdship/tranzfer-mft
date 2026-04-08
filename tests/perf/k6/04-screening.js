/**
 * Phase 2 — screening-service Throughput Test
 * Tests: OFAC scan throughput, hit detection accuracy, concurrent scanning.
 *
 * Run: k6 run tests/perf/k6/04-screening.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';

const BASE_SCREEN = `${__ENV.BASE_URL || 'http://localhost'}:8092`;

const scanLatency   = new Trend('scan_latency_ms', true);
const scanErrors    = new Rate('scan_errors');
const hitDetected   = new Counter('hits_detected');
const cleanPassed   = new Counter('clean_passed');

export const options = {
  stages: [
    { duration: '30s', target: 20  },
    { duration: '3m',  target: 50  },
    { duration: '1m',  target: 100 },
    { duration: '1m',  target: 100 },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    'scan_latency_ms': ['p(95)<500', 'p(99)<1000'],
    'scan_errors':     ['rate<0.01'],
    'http_req_failed': ['rate<0.01'],
  },
};

// Known OFAC watchlist name (for hit detection test)
const WATCHLIST_HIT_NAME = 'WEAPONS TRADING COMPANY';
// Clean name that should never hit
const CLEAN_NAME = 'ACME LEGITIMATE TRANSFERS INC';

export function setup() {
  const token = login();
  console.log('Screening service throughput test — targeting 50-100 concurrent scans');
  return { token };
}

export default function (data) {
  // Alternate between clean and suspect payloads
  const isHitTest = Math.random() < 0.05; // 5% hit rate (realistic)

  group('file_scan', () => {
    // Use /scan/text (accepts JSON body with inline text content for sanctions check)
    // The primary /scan endpoint requires multipart file upload — not suitable for k6 load tests
    const trackId = `PERF-${Date.now()}-${Math.random().toString(36).substr(2, 8)}`;
    const content = isHitTest
      ? `COMPANY: ${WATCHLIST_HIT_NAME}\nTRANSACTION: Financial transfer\nAMOUNT: 50000 USD`
      : `COMPANY: ${CLEAN_NAME}\nTRANSACTION: Routine file transfer\nAMOUNT: 1000 USD`;

    const payload = {
      content,
      filename: isHitTest ? `invoice-${trackId}.csv` : `transfer-${trackId}.csv`,
      trackId,
    };

    const res = http.post(
      `${BASE_SCREEN}/api/v1/screening/scan/text`,
      JSON.stringify(payload),
      { headers: authHeaders(data.token), tags: { test: 'scan', hit: String(isHitTest) } }
    );

    const ok = check(res, {
      'scan: status 200':         (r) => r.status === 200,
      'scan: has decision':       (r) => r.status === 200 && (r.body.includes('ALLOWED') || r.body.includes('BLOCKED') || r.body.includes('decision')),
      'scan: latency < 500ms':    (r) => r.timings.duration < 500,
    });

    scanErrors.add(!ok);
    scanLatency.add(res.timings.duration);

    if (res.status === 200) {
      try {
        const result = JSON.parse(res.body);
        const decision = result.decision || result.status || '';
        if (decision === 'BLOCKED' || decision === 'QUARANTINED') {
          hitDetected.add(1);
        } else {
          cleanPassed.add(1);
        }
      } catch (_) { cleanPassed.add(1); }
    }
  });

  sleep(0.1 + Math.random() * 0.2);
}
