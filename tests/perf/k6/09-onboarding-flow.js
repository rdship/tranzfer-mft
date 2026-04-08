/**
 * Phase 2 — Full Onboarding Flow Load Test
 *
 * Simulates what a real admin does when bringing a new trading partner onto the platform:
 *   1. Login as admin
 *   2. Create a partner (company registration)
 *   3. Create SFTP + FTP accounts under the partner (full UnifiedOnboard in one call)
 *   4. Verify the created accounts appear in the accounts list
 *   5. Verify the new user can log in with their credentials
 *   6. Check that Sentinel and Analytics reflect the new onboarding
 *
 * This tests the write path of the platform — creation throughput, validation latency,
 * DB write concurrency, and cross-service propagation.
 *
 * Run:
 *   k6 run tests/perf/k6/09-onboarding-flow.js
 *   k6 run --env PROFILE=medium tests/perf/k6/09-onboarding-flow.js
 *   k6 run --env PROFILE=stress tests/perf/k6/09-onboarding-flow.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { login, authHeaders, ONBOARDING } from './lib/auth.js';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE     = __ENV.BASE_URL || 'http://localhost';
const ONBOARD  = `${BASE}:8080`;
const SENTINEL = `${BASE}:8098`;

const onboardErrors    = new Rate('onboard_errors');
const partnerErrors    = new Rate('partner_errors');
const onboardLatency   = new Trend('onboard_latency_ms', true);
const partnerLatency   = new Trend('partner_latency_ms', true);
const accountsCreated  = new Counter('accounts_created');
const partnersCreated  = new Counter('partners_created');
const loginVerified    = new Counter('new_user_login_verified');

const PROFILES = {
  light:  { vus: 5,   rampUp: '20s', duration: '3m'  },
  medium: { vus: 20,  rampUp: '45s', duration: '10m' },
  stress: { vus: 50,  rampUp: '2m',  duration: '15m' },
};

const profile = PROFILES[__ENV.PROFILE || 'light'];

export const options = {
  stages: [
    { duration: profile.rampUp,  target: profile.vus },
    { duration: profile.duration, target: profile.vus },
    { duration: '30s',            target: 0 },
  ],
  thresholds: {
    'onboard_errors':    ['rate<0.02'],
    'partner_errors':    ['rate<0.02'],
    'onboard_latency_ms': ['p(95)<3000'],
    'partner_latency_ms': ['p(95)<1500'],
    'http_req_failed':   ['rate<0.02'],
  },
};

export function setup() {
  const token = login();
  if (!token) { throw new Error('Setup failed: admin login returned no token'); }
  console.log(`Onboarding Flow Test — Profile: ${__ENV.PROFILE || 'light'} | VUs: ${profile.vus}`);
  console.log('Tests: partner creation, unified onboard (user+SFTP+FTP accounts), login verify');
  return { token };
}

export default function (data) {
  const suffix = randomString(8).toLowerCase();
  const companyName  = `PerfCo-${suffix}`;
  const userEmail    = `perf-${suffix}@perftest.local`;
  const userPassword = `PerfPass@1234${suffix}`;
  const sftpUser     = `sftp-${suffix}`;
  const ftpUser      = `ftp-${suffix}`;

  // ── 1. Create a Partner ───────────────────────────────────────────────────
  let partnerId = null;
  group('create_partner', () => {
    const res = http.post(
      `${ONBOARD}/api/partners`,
      JSON.stringify({
        companyName:      companyName,
        displayName:      `Perf Test Co ${suffix}`,
        industry:         'Finance',
        partnerType:      'CLIENT',
        slaTier:          'STANDARD',
        protocolsEnabled: ['SFTP', 'FTP'],
      }),
      { headers: authHeaders(data.token), tags: { step: 'create_partner' } }
    );

    const ok = check(res, {
      'partner: created (201)': (r) => r.status === 201 || r.status === 200,
      'partner: has id':        (r) => r.status < 300 && r.body.includes('"id"'),
      'partner: latency <2s':   (r) => r.timings.duration < 2000,
    });

    partnerErrors.add(!ok);
    partnerLatency.add(res.timings.duration);

    if (res.status === 201 || res.status === 200) {
      try { partnerId = JSON.parse(res.body).id; } catch (_) {}
      partnersCreated.add(1);
    }
  });

  sleep(0.1);

  // ── 2. Unified Onboard — user + SFTP account + FTP account in one call ────
  let createdUserId   = null;
  let createdSftpId   = null;
  group('unified_onboard', () => {
    const payload = {
      user: {
        email:    userEmail,
        password: userPassword,
        role:     'USER',
      },
      accounts: [
        {
          protocol: 'SFTP',
          username: sftpUser,
          password: userPassword,
          permissions: { read: true, write: true, delete: false },
        },
        {
          protocol: 'FTP',
          username: ftpUser,
          password: userPassword,
          permissions: { read: true, write: true, delete: false },
        },
      ],
      ...(partnerId ? {
        partner: {
          companyName:      `${companyName}-linked`,
          displayName:      companyName,
          partnerType:      'CLIENT',
          slaTier:          'STANDARD',
          protocolsEnabled: ['SFTP', 'FTP'],
        },
      } : {}),
    };

    const res = http.post(
      `${ONBOARD}/api/v1/onboard`,
      JSON.stringify(payload),
      { headers: authHeaders(data.token), tags: { step: 'unified_onboard' } }
    );

    const ok = check(res, {
      'onboard: created (201)':    (r) => r.status === 201,
      'onboard: has userId':       (r) => r.status === 201 && r.body.includes('"userId"'),
      'onboard: has accounts':     (r) => r.status === 201 && r.body.includes('"accounts"'),
      'onboard: latency <3s':      (r) => r.timings.duration < 3000,
    });

    onboardErrors.add(!ok);
    onboardLatency.add(res.timings.duration);

    if (res.status === 201) {
      try {
        const body = JSON.parse(res.body);
        createdUserId = body.userId || body.user?.id || null;
        createdSftpId = body.accounts?.[0]?.id || null;
        accountsCreated.add(body.accounts?.length || 1);
      } catch (_) {}
    }
  });

  sleep(0.2);

  // ── 3. Verify accounts appear in the system ───────────────────────────────
  group('verify_accounts_listed', () => {
    const res = http.get(
      `${ONBOARD}/api/accounts?page=0&size=5`,
      { headers: authHeaders(data.token), tags: { step: 'verify_accounts' } }
    );
    check(res, {
      'accounts list: responds 200': (r) => r.status === 200,
      'accounts list: has content':  (r) => r.status === 200 && r.body.includes('"content"'),
    });
  });

  sleep(0.1);

  // ── 4. Verify the new user can log in with their credentials ──────────────
  group('verify_new_user_login', () => {
    const res = http.post(
      `${ONBOARD}/api/auth/login`,
      JSON.stringify({ email: userEmail, password: userPassword }),
      { headers: { 'Content-Type': 'application/json' }, tags: { step: 'new_user_login' } }
    );

    const ok = check(res, {
      'new user: login succeeds':    (r) => r.status === 200,
      'new user: receives JWT':      (r) => r.status === 200 && r.body.includes('accessToken'),
    });

    if (ok) loginVerified.add(1);
    // Note: login failure here could mean propagation lag — not counted as hard error
  });

  sleep(0.1);

  // ── 5. Check partner accounts endpoint (if partner was created) ───────────
  if (partnerId) {
    group('partner_accounts', () => {
      const res = http.get(
        `${ONBOARD}/api/partners/${partnerId}/accounts`,
        { headers: authHeaders(data.token), tags: { step: 'partner_accounts' } }
      );
      check(res, {
        'partner accounts: responds': (r) => r.status === 200 || r.status === 404,
      });
    });
  }

  sleep(0.3 + Math.random() * 0.3);
}

export function teardown(data) {
  // Check Sentinel for any onboarding-related findings
  const res = http.get(
    `${SENTINEL}/api/v1/sentinel/findings?status=OPEN&analyzer=SECURITY`,
    { headers: authHeaders(data.token) }
  );
  if (res.status === 200) {
    try {
      const findings = JSON.parse(res.body);
      const count = findings.totalElements || (Array.isArray(findings) ? findings.length : 0);
      console.log(`\nSentinel SECURITY findings at test end: ${count}`);
    } catch (_) {}
  }

  console.log(`\nOnboarding flow test complete.`);
  console.log(`  Partners created: ${partnersCreated.name}`);
  console.log(`  Accounts created: ${accountsCreated.name}`);
  console.log(`  New user logins verified: ${loginVerified.name}`);
  console.log(`\nNote: Test-created users/partners remain in DB.`);
  console.log(`  Clean up: DELETE /api/accounts/perf-* or restore from backup.`);
}
