/**
 * TranzFer MFT — Shared authentication fixture for Playwright tests.
 *
 * Provides `authenticatedPage` (browser with session) and `api` (API client
 * with Bearer token) so every test file gets auth for free.
 */
const { test: base, expect } = require('@playwright/test');

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL || 'superadmin@tranzfer.io';
const ADMIN_PASS  = process.env.TEST_ADMIN_PASS  || 'superadmin';
const BASE_URL    = process.env.BASE_URL || 'http://localhost';

/**
 * Login via the UI and return after redirect.
 */
async function uiLogin(page, email = ADMIN_EMAIL, password = ADMIN_PASS) {
  await page.goto('/login');
  await page.fill('input[type="email"], input[name="email"]', email);
  await page.fill('input[type="password"], input[name="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(url => !url.toString().includes('/login'), { timeout: 15000 });
}

/**
 * Obtain a JWT token via the API.
 */
async function getToken(request, email = ADMIN_EMAIL, password = ADMIN_PASS) {
  const resp = await request.post('/api/auth/login', {
    data: { email, password },
  });
  if (resp.status() !== 200) {
    throw new Error(`Login failed (${resp.status()}): ${await resp.text()}`);
  }
  const body = await resp.json();
  return body.accessToken;
}

/**
 * Extended test fixture that provides:
 *   - authedPage: a Page already logged in via the UI
 *   - api: helper to make authenticated API calls
 *   - token: raw JWT string
 */
const test = base.extend({
  // Authenticated browser page
  authedPage: async ({ page }, use) => {
    await uiLogin(page);
    await use(page);
  },

  // API helper with auth
  api: async ({ request }, use) => {
    const token = await getToken(request);
    const authHeaders = { Authorization: `Bearer ${token}` };

    const api = {
      token,
      get: (path, opts = {}) =>
        request.get(path, { ...opts, headers: { ...authHeaders, ...opts.headers } }),
      post: (path, data, opts = {}) =>
        request.post(path, { data, ...opts, headers: { ...authHeaders, ...opts.headers } }),
      put: (path, data, opts = {}) =>
        request.put(path, { data, ...opts, headers: { ...authHeaders, ...opts.headers } }),
      patch: (path, data, opts = {}) =>
        request.patch(path, { data, ...opts, headers: { ...authHeaders, ...opts.headers } }),
      delete: (path, opts = {}) =>
        request.delete(path, { ...opts, headers: { ...authHeaders, ...opts.headers } }),
    };
    await use(api);
  },

  // Raw token
  token: async ({ request }, use) => {
    const t = await getToken(request);
    await use(t);
  },
});

module.exports = { test, expect, uiLogin, getToken, ADMIN_EMAIL, ADMIN_PASS, BASE_URL };
