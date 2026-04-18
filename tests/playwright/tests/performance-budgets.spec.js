/**
 * TranzFer MFT — Performance budgets.
 *
 * These tests enforce *soft* performance budgets — they REPORT timings for
 * every run and only FAIL when a budget is breached by a wide margin. The
 * CTA-level intent is to:
 *   - make regressions visible on every run (budget numbers written to stdout)
 *   - prevent the gradual drift that's invisible to functional tests
 *   - keep CI green when a run is 20ms slow; red when it's 3x slow
 *
 * Budgets are intentionally generous — tighten once we have N runs of baseline.
 * Core budgets (HARD FAIL if exceeded):
 *   - /api/auth/login          p95  < 2000ms
 *   - /api/activity-monitor    p95  < 1500ms
 *   - /api/servers             p95  < 1500ms
 *   - /api/flows               p95  < 1500ms
 * UI budgets (SOFT — reports but does not fail):
 *   - Dashboard render          < 4000ms  (load + first data paint)
 *   - Activity Monitor render   < 4000ms
 */
const { test, expect } = require('../fixtures/auth.fixture');

function percentile(values, p) {
  if (values.length === 0) return NaN;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.floor(sorted.length * p));
  return sorted[idx];
}

async function timeApi(fn, { warmup = 2, samples = 15 } = {}) {
  for (let i = 0; i < warmup; i++) await fn();
  const times = [];
  for (let i = 0; i < samples; i++) {
    const t0 = Date.now();
    await fn();
    times.push(Date.now() - t0);
  }
  return {
    p50: percentile(times, 0.50),
    p95: percentile(times, 0.95),
    max: Math.max(...times),
    mean: times.reduce((a, b) => a + b, 0) / times.length,
    samples,
  };
}

const API_BUDGETS = [
  { name: 'GET /api/activity-monitor', fn: (api) => api.get('/api/activity-monitor?page=0&size=10'), p95Budget: 1500 },
  { name: 'GET /api/servers',           fn: (api) => api.get('/api/servers'),                         p95Budget: 1500 },
  { name: 'GET /api/flows',             fn: (api) => api.get('/api/flows'),                           p95Budget: 1500 },
  { name: 'GET /api/accounts',          fn: (api) => api.get('/api/accounts'),                        p95Budget: 1500 },
];

test.describe('API performance budgets @perf', () => {
  for (const b of API_BUDGETS) {
    test(`${b.name} p95 < ${b.p95Budget}ms @perf`, async ({ api }) => {
      const stats = await timeApi(() => b.fn(api), { warmup: 2, samples: 15 });
      // Report numbers so CI logs show the trend regardless of pass/fail
      console.log(`PERF ${b.name}: p50=${stats.p50}ms p95=${stats.p95}ms max=${stats.max}ms mean=${stats.mean.toFixed(0)}ms`);
      expect(stats.p95, `${b.name} p95 ${stats.p95}ms exceeded budget ${b.p95Budget}ms`).toBeLessThan(b.p95Budget);
    });
  }

  test('POST /api/auth/login p95 < 2000ms @perf', async ({ request }) => {
    const stats = await timeApi(async () => {
      const resp = await request.post('/api/auth/login', {
        data: { email: 'tester-claude@tranzfer.io', password: 'TesterClaude@2026!' },
      });
      expect(resp.status()).toBe(200);
    }, { warmup: 2, samples: 10 });
    console.log(`PERF login: p50=${stats.p50}ms p95=${stats.p95}ms max=${stats.max}ms`);
    expect(stats.p95, `login p95 ${stats.p95}ms > 2000ms budget`).toBeLessThan(2000);
  });
});

test.describe('UI render budgets (soft) @perf', () => {
  // TEST-INFRA DEBT (R123): the `authedPage` fixture logs in via BASE_URL (API port), but
  // UI pages are served from UI_BASE_URL. The fixture's login step runs in fixture-setup
  // *before* this block's skip can fire, so the test can never reach its body. Need a
  // separate `authedUiPage` fixture that does UI-origin login. Skipping until that lands.
  test.skip(true, 'TODO: add authedUiPage fixture that logs in via UI_BASE_URL origin');


  // These are advisory — report numbers, warn on breach, do not fail CI.
  // Promote to hard fail once baseline is stable for 2 weeks.
  //
  // UI pages are served by ui-service on :3000, NOT by onboarding-api on :8080 (BASE_URL).
  // Tests use UI_BASE_URL (default http://localhost:3000) for page.goto of UI paths.
  const UI_BASE = process.env.UI_BASE_URL || 'http://localhost:3000';
  const UI_BUDGETS = [
    { name: 'Dashboard',         path: '/dashboard',                   budgetMs: 4000 },
    { name: 'Activity Monitor',  path: '/operations/activity-monitor', budgetMs: 4000 },
    { name: 'Servers',           path: '/server-instances',            budgetMs: 4000 },
    { name: 'Flows',             path: '/flows',                       budgetMs: 4000 },
  ];

  for (const b of UI_BUDGETS) {
    test(`${b.name} page render ≤ ${b.budgetMs}ms (soft) @perf`, async ({ authedPage: page, request }) => {
      // Skip if UI service isn't directly reachable (e.g. running tests API-only or behind gateway).
      try {
        const probe = await request.get(`${UI_BASE}/`);
        if (probe.status() >= 400) test.skip(true, `UI service not reachable at ${UI_BASE}`);
      } catch {
        test.skip(true, `UI service not reachable at ${UI_BASE}`);
      }
      const t0 = Date.now();
      await page.goto(`${UI_BASE}${b.path}`);
      await page.waitForLoadState('networkidle', { timeout: 15000 });
      const elapsed = Date.now() - t0;
      console.log(`PERF UI ${b.name}: ${elapsed}ms  (budget ${b.budgetMs}ms)`);
      if (elapsed > b.budgetMs) {
        console.warn(`  ⚠ SOFT BUDGET BREACH on ${b.name} — ${elapsed}ms > ${b.budgetMs}ms`);
      }
      // Hard upper bound: 3x the soft budget = signs of a real regression
      expect(elapsed, `${b.name} render ${elapsed}ms > 3x budget (${b.budgetMs * 3}ms) — hard fail`).toBeLessThan(b.budgetMs * 3);
    });
  }
});
