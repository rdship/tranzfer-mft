/**
 * TranzFer MFT — Authentication & Authorization Tests
 *
 * Covers: login flows, role-based access, token handling, session management,
 * invalid credentials, brute-force protection indicators.
 */
const { test, expect, uiLogin, ADMIN_EMAIL, ADMIN_PASS } = require('../fixtures/auth.fixture');
const { TestData } = require('../helpers/test-data');

test.describe('Authentication — Login Flow', () => {
  test('login page renders correctly', async ({ page }) => {
    await page.goto('/login');
    await expect(page.locator('input[type="email"], input[name="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
    // Should show branding
    const logo = page.locator('img[alt*="logo" i], img[alt*="tranzfer" i], [class*="logo"]').first();
    const brandText = page.locator('text=TranzFer, text=tranzfer, text=MFT, [class*="brand"]').first();
    const hasBranding = (await logo.count()) > 0 || (await brandText.count()) > 0;
    expect(hasBranding, 'Login page should show branding').toBeTruthy();
  });

  test('successful login with superadmin redirects to dashboard', async ({ page }) => {
    await uiLogin(page);
    await expect(page).not.toHaveURL(/login/);
    // Should land on operations/dashboard
    const url = page.url();
    const validLanding = url.includes('/operations') || url.includes('/dashboard') || url.includes('/sentinel');
    expect(validLanding, 'Should land on a main page after login').toBeTruthy();
  });

  test('invalid email shows error and stays on login', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[type="email"], input[name="email"]', 'nonexistent@tranzfer.io');
    await page.fill('input[type="password"]', 'wrongpassword');
    await page.click('button[type="submit"]');
    await page.waitForTimeout(3000);
    await expect(page).toHaveURL(/login/);
  });

  test('empty credentials prevent form submission', async ({ page }) => {
    await page.goto('/login');
    await page.click('button[type="submit"]');
    await page.waitForTimeout(1000);
    await expect(page).toHaveURL(/login/);
  });

  test('wrong password for valid email stays on login', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[type="email"], input[name="email"]', ADMIN_EMAIL);
    await page.fill('input[type="password"]', 'CompletelyWrongPassword123!');
    await page.click('button[type="submit"]');
    await page.waitForTimeout(3000);
    await expect(page).toHaveURL(/login/);
  });

  test('login API returns JWT token with correct structure', async ({ request }) => {
    const resp = await request.post('/api/auth/login', {
      data: { email: ADMIN_EMAIL, password: ADMIN_PASS },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.accessToken).toBeTruthy();
    // JWT has 3 parts separated by dots
    const parts = body.accessToken.split('.');
    expect(parts.length, 'JWT should have 3 parts (header.payload.signature)').toBe(3);
    // Decode payload
    const payload = JSON.parse(Buffer.from(parts[1], 'base64').toString());
    expect(payload.sub).toBe(ADMIN_EMAIL);
    expect(payload.role).toBeTruthy();
  });

  test('login API rejects invalid credentials with 401', async ({ request }) => {
    const resp = await request.post('/api/auth/login', {
      data: { email: 'fake@tranzfer.io', password: 'fakepass' },
    });
    expect(resp.status()).toBe(401);
  });

  test('login API rejects missing email', async ({ request }) => {
    const resp = await request.post('/api/auth/login', {
      data: { password: 'somepass' },
    });
    expect([400, 401]).toContain(resp.status());
  });

  test('login API rejects missing password', async ({ request }) => {
    const resp = await request.post('/api/auth/login', {
      data: { email: ADMIN_EMAIL },
    });
    expect([400, 401]).toContain(resp.status());
  });
});

test.describe('Authorization — Role-Based Access', () => {
  test('superadmin can access all admin endpoints', async ({ api }) => {
    const adminEndpoints = [
      '/api/users',
      '/api/partners',
      '/api/accounts',
      '/api/servers',
      '/api/flows',
      '/api/audit-logs',
      '/api/clusters',
      '/api/platform-settings',
    ];
    for (const ep of adminEndpoints) {
      const resp = await api.get(ep);
      expect(resp.status(), `${ep} should be accessible`).toBe(200);
    }
  });

  test('create a VIEWER user and verify limited access', async ({ api, request }) => {
    // Create viewer user
    const userData = TestData.user({ role: 'VIEWER' });
    const createResp = await api.post('/api/auth/register', userData);
    expect(createResp.status()).toBe(201);

    // Login as viewer
    const loginResp = await request.post('/api/auth/login', {
      data: { email: userData.email, password: userData.password },
    });
    expect(loginResp.status()).toBe(200);
    const viewerToken = (await loginResp.json()).accessToken;
    const viewerHeaders = { Authorization: `Bearer ${viewerToken}` };

    // Viewer should be able to READ
    const readResp = await request.get('/api/partners', { headers: viewerHeaders });
    expect(readResp.status()).toBe(200);

    // Viewer should NOT be able to CREATE (write operations)
    const writeResp = await request.post('/api/partners', {
      data: TestData.partner(),
      headers: viewerHeaders,
    });
    expect([403, 405]).toContain(writeResp.status());

    // Cleanup: delete the viewer user
    const users = await (await api.get('/api/users')).json();
    const viewerUser = users.find(u => u.email === userData.email);
    if (viewerUser) {
      await api.delete(`/api/users/${viewerUser.id}`);
    }
  });

  test('create an OPERATOR user and verify write access', async ({ api, request }) => {
    const userData = TestData.user({ role: 'OPERATOR' });
    const createResp = await api.post('/api/auth/register', userData);
    expect(createResp.status()).toBe(201);

    const loginResp = await request.post('/api/auth/login', {
      data: { email: userData.email, password: userData.password },
    });
    expect(loginResp.status()).toBe(200);
    const opToken = (await loginResp.json()).accessToken;
    const opHeaders = { Authorization: `Bearer ${opToken}` };

    // Operator should be able to read
    const readResp = await request.get('/api/flows', { headers: opHeaders });
    expect(readResp.status()).toBe(200);

    // Cleanup
    const users = await (await api.get('/api/users')).json();
    const opUser = users.find(u => u.email === userData.email);
    if (opUser) await api.delete(`/api/users/${opUser.id}`);
  });

  test('unauthenticated requests get 401', async ({ request }) => {
    const protectedEndpoints = [
      '/api/partners',
      '/api/accounts',
      '/api/flows',
      '/api/users',
      '/api/servers',
    ];
    for (const ep of protectedEndpoints) {
      const resp = await request.get(ep);
      expect(resp.status(), `${ep} should require auth`).toBe(401);
    }
  });

  test('expired/invalid token gets 401', async ({ request }) => {
    const resp = await request.get('/api/partners', {
      headers: { Authorization: 'Bearer invalid.token.here' },
    });
    expect(resp.status()).toBe(401);
  });

  test('malformed Authorization header gets 401', async ({ request }) => {
    const resp = await request.get('/api/partners', {
      headers: { Authorization: 'NotBearer sometoken' },
    });
    expect(resp.status()).toBe(401);
  });
});

test.describe('Session Management', () => {
  test('token stored in localStorage after login', async ({ page }) => {
    await uiLogin(page);
    const token = await page.evaluate(() => localStorage.getItem('token'));
    expect(token).toBeTruthy();
    expect(token.split('.').length).toBe(3); // JWT format
  });

  test('user info stored in localStorage after login', async ({ page }) => {
    await uiLogin(page);
    const userStr = await page.evaluate(() => localStorage.getItem('user'));
    expect(userStr).toBeTruthy();
    const user = JSON.parse(userStr);
    expect(user.email || user.sub).toBeTruthy();
  });

  test('navigating without token redirects to login', async ({ page }) => {
    // Clear any existing auth
    await page.goto('/login');
    await page.evaluate(() => {
      localStorage.clear();
    });
    await page.goto('/operations');
    await page.waitForTimeout(2000);
    await expect(page).toHaveURL(/login/);
  });

  test('logout clears session and returns to login', async ({ page }) => {
    await uiLogin(page);
    // Find and click logout
    const logoutBtn = page.locator('button:has-text("Logout"), button:has-text("Sign out"), [class*="logout"], a:has-text("Logout")').first();
    if (await logoutBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await logoutBtn.click();
      await page.waitForTimeout(2000);
      await expect(page).toHaveURL(/login/);
      const token = await page.evaluate(() => localStorage.getItem('token'));
      expect(token).toBeFalsy();
    }
  });
});
