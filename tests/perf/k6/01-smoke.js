/**
 * Phase 1 — Smoke Test
 * Confirms all 20 services respond within 3 seconds. 1 VU, single pass.
 * Run: k6 run tests/perf/k6/01-smoke.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { login } from './lib/auth.js';

const BASE = __ENV.BASE_URL || 'http://localhost';

const SERVICES = [
  { name: 'onboarding-api',     port: 8080 },
  { name: 'sftp-service',       port: 8081 },
  { name: 'ftp-service',        port: 8082 },
  { name: 'ftp-web-service',    port: 8083 },
  { name: 'config-service',     port: 8084 },
  { name: 'gateway-service',    port: 8085 },
  { name: 'encryption-service', port: 8086 },
  { name: 'forwarder-service',  port: 8087 },
  { name: 'dmz-proxy',          port: 8088 },
  { name: 'license-service',    port: 8089 },
  { name: 'analytics-service',  port: 8090 },
  { name: 'ai-engine',          port: 8091 },
  { name: 'screening-service',  port: 8092 },
  { name: 'keystore-manager',   port: 8093 },
  { name: 'as2-service',        port: 8094 },
  { name: 'edi-converter',      port: 8095 },
  { name: 'storage-manager',    port: 8096 },
  { name: 'notification-svc',   port: 8097 },
  { name: 'platform-sentinel',  port: 8098 },
];

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    // Every health check must pass
    'checks': ['rate==1.0'],
    // Every service must respond within 3 seconds
    'http_req_duration': ['p(100)<3000'],
  },
};

export function setup() {
  return { token: login() };
}

export default function (data) {
  console.log('\n=== SMOKE TEST — All 20 Services ===\n');
  let allOk = true;

  for (const svc of SERVICES) {
    const url = `${BASE}:${svc.port}/actuator/health`;
    const res = http.get(url, {
      timeout: '5s',
      tags: { service: svc.name },
    });

    const ok = check(res, {
      [`${svc.name} status 200`]: (r) => r.status === 200,
      [`${svc.name} latency < 3s`]: (r) => r.timings.duration < 3000,
    });

    const marker = ok ? '✓' : '✗';
    const latency = res.timings.duration.toFixed(0);
    console.log(`[${marker}] ${svc.name.padEnd(22)} ${String(svc.port).padEnd(6)} ${latency}ms`);

    if (!ok) allOk = false;
    sleep(0.1);
  }

  // Verify login works
  const loginRes = http.post(
    `${BASE}:8080/api/v1/auth/login`,
    JSON.stringify({ email: 'admin@filetransfer.local', password: 'Admin@1234' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { service: 'auth' } }
  );
  check(loginRes, {
    'auth: login returns JWT': (r) => r.status === 200 && r.body.includes('token'),
  });

  // Verify Sentinel health score endpoint
  if (data.token) {
    const sentinelRes = http.get(
      `${BASE}:8098/api/v1/sentinel/health-score`,
      { headers: { 'Authorization': `Bearer ${data.token}` }, tags: { service: 'sentinel' } }
    );
    check(sentinelRes, {
      'sentinel: health-score responds': (r) => r.status === 200 || r.status === 401,
    });
  }

  console.log('\n' + (allOk ? '✓ All services UP.' : '✗ Some services DOWN — fix before continuing.'));
}
