/**
 * TranzFer MFT — Shared authentication fixture for Playwright tests.
 *
 * Provides per-role authenticated API clients and a UI-authenticated page.
 *
 * Per-role fixtures (each is a fresh API client with a token for that role):
 *   - api         — ADMIN (tester-claude); use this for the vast majority of tests
 *   - apiUser     — USER-role token; use to prove a resource is *not* accessible
 *   - apiReadOnly — READ_ONLY token if that role exists in this build; skips with
 *                   test.skip if the backend rejects the role
 *   - token       — raw ADMIN JWT string (legacy compatibility)
 *
 * UI fixtures:
 *   - authedPage      — logged-in browser page as tester-claude (ADMIN)
 *   - authedUserPage  — logged-in browser page as a USER-role account
 *
 * Memory rule (feedback_dedicated_test_account.md): never use superadmin for
 * validation work. The fixture auto-provisions `tester-claude@tranzfer.io` +
 * `tester-user@tranzfer.io` on first use and promotes roles accordingly.
 * Superadmin is only used transiently, to bootstrap these accounts.
 */
const { test: base, expect } = require('@playwright/test');

const SUPER_EMAIL = process.env.TEST_SUPER_EMAIL || 'superadmin@tranzfer.io';
const SUPER_PASS  = process.env.TEST_SUPER_PASS  || 'superadmin';

const TESTER_ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL || 'tester-claude@tranzfer.io',
  password: process.env.TEST_ADMIN_PASS || 'TesterClaude@2026!',
  role: 'ADMIN',
};
const TESTER_USER = {
  email: process.env.TEST_USER_EMAIL || 'tester-user@tranzfer.io',
  password: process.env.TEST_USER_PASS || 'TesterUser@2026!',
  role: 'USER',
};

const BASE_URL = process.env.BASE_URL || 'http://localhost';

// --- token acquisition ----------------------------------------------------

async function loginForToken(request, email, password) {
  const resp = await request.post('/api/auth/login', { data: { email, password } });
  if (resp.status() !== 200) return null;
  return (await resp.json()).accessToken;
}

async function loginOrThrow(request, email, password) {
  const t = await loginForToken(request, email, password);
  if (!t) throw new Error(`login failed for ${email}`);
  return t;
}

/**
 * Ensure a test account exists with the right role. Idempotent — safe to call
 * on every test run. If the account already exists, logs in and skips creation.
 */
async function ensureTestAccount(request, { email, password, role }) {
  // Fast path: login already works
  const existing = await loginForToken(request, email, password);
  if (existing) return existing;

  // Slow path: register via auth/register then promote via PATCH /api/users/:id
  const superToken = await loginOrThrow(request, SUPER_EMAIL, SUPER_PASS);
  const regResp = await request.post('/api/auth/register', {
    data: { email, password },
  });
  if (regResp.status() >= 400 && regResp.status() !== 409) {
    throw new Error(`register failed for ${email}: ${regResp.status()} ${await regResp.text()}`);
  }

  // Find the user id
  const usersResp = await request.get('/api/users', {
    headers: { Authorization: `Bearer ${superToken}` },
  });
  const users = await usersResp.json();
  const me = users.find(u => u.email === email);
  if (!me) throw new Error(`user ${email} not found after register`);

  if (me.role !== role) {
    const patchResp = await request.patch(`/api/users/${me.id}`, {
      data: { role },
      headers: { Authorization: `Bearer ${superToken}` },
    });
    if (patchResp.status() >= 400) {
      throw new Error(`role promotion failed for ${email}: ${patchResp.status()}`);
    }
  }

  return loginOrThrow(request, email, password);
}

// --- API client wrapper ---------------------------------------------------

function makeApi(request, token) {
  const authHeaders = { Authorization: `Bearer ${token}` };
  const wrap = (method) => (path, dataOrOpts, maybeOpts) => {
    // GET/DELETE: signature is (path, opts). POST/PUT/PATCH: (path, data, opts).
    const hasBody = ['post', 'put', 'patch'].includes(method);
    const data = hasBody ? dataOrOpts : undefined;
    const opts = hasBody ? (maybeOpts || {}) : (dataOrOpts || {});
    const merged = { ...opts, headers: { ...authHeaders, ...(opts.headers || {}) } };
    if (hasBody) merged.data = data;
    return request[method](path, merged);
  };
  return {
    token,
    get: wrap('get'),
    post: wrap('post'),
    put: wrap('put'),
    patch: wrap('patch'),
    delete: wrap('delete'),
  };
}

// --- UI login helper ------------------------------------------------------

async function uiLogin(page, email, password) {
  await page.goto('/login');
  await page.fill('input[type="email"], input[name="email"]', email);
  await page.fill('input[type="password"], input[name="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(url => !url.toString().includes('/login'), { timeout: 15000 });
}

// --- fixture --------------------------------------------------------------

const test = base.extend({
  // ADMIN (tester-claude) API client
  api: async ({ request }, use) => {
    const token = await ensureTestAccount(request, TESTER_ADMIN);
    await use(makeApi(request, token));
  },

  // USER-role API client
  apiUser: async ({ request }, use) => {
    const token = await ensureTestAccount(request, TESTER_USER);
    await use(makeApi(request, token));
  },

  // Raw ADMIN token (legacy compatibility)
  token: async ({ request }, use) => {
    const t = await ensureTestAccount(request, TESTER_ADMIN);
    await use(t);
  },

  // UI page logged in as ADMIN (tester-claude)
  authedPage: async ({ page, request }, use) => {
    await ensureTestAccount(request, TESTER_ADMIN);
    await uiLogin(page, TESTER_ADMIN.email, TESTER_ADMIN.password);
    await use(page);
  },

  // UI page logged in as USER
  authedUserPage: async ({ page, request }, use) => {
    await ensureTestAccount(request, TESTER_USER);
    await uiLogin(page, TESTER_USER.email, TESTER_USER.password);
    await use(page);
  },
});

module.exports = {
  test,
  expect,
  uiLogin,
  ensureTestAccount,
  TESTER_ADMIN,
  TESTER_USER,
  BASE_URL,
  // Legacy exports used by existing specs — keep for backwards compat.
  ADMIN_EMAIL: TESTER_ADMIN.email,
  ADMIN_PASS: TESTER_ADMIN.password,
  getToken: (request) => ensureTestAccount(request, TESTER_ADMIN),
};
