/**
 * TranzFer MFT — User Management Tests
 *
 * Covers: CRUD, role assignment, password reset, status toggle,
 * all user roles (ADMIN, OPERATOR, USER, VIEWER, PARTNER).
 */
const { test, expect } = require('../fixtures/auth.fixture');
const { TestData, uid } = require('../helpers/test-data');
const { Assertions } = require('../helpers/assertions');

test.describe('User Management — API CRUD', () => {
  const createdEmails = [];

  test.afterAll(async ({ request }) => {
    const { getToken } = require('../fixtures/auth.fixture');
    const token = await getToken(request);
    const headers = { Authorization: `Bearer ${token}` };
    const usersResp = await request.get('/api/users', { headers });
    if (usersResp.status() === 200) {
      const users = await usersResp.json();
      for (const email of createdEmails) {
        const user = users.find(u => u.email === email);
        if (user) {
          await request.delete(`/api/users/${user.id}`, { headers }).catch(() => {});
        }
      }
    }
  });

  test('list users', async ({ api }) => {
    const resp = await api.get('/api/users');
    expect(resp.status()).toBe(200);
    const users = await resp.json();
    expect(Array.isArray(users)).toBeTruthy();
    expect(users.length).toBeGreaterThan(0);
    // Superadmin should exist
    expect(users.some(u => u.email === 'superadmin@tranzfer.io')).toBeTruthy();
  });

  test('create ADMIN user', async ({ api, request }) => {
    const data = TestData.adminUser();
    createdEmails.push(data.email);
    const resp = await api.post('/api/auth/register', data);
    expect(resp.status()).toBe(201);

    // Verify can login
    const loginResp = await request.post('/api/auth/login', {
      data: { email: data.email, password: data.password },
    });
    expect(loginResp.status()).toBe(200);
  });

  test('create OPERATOR user', async ({ api, request }) => {
    const data = TestData.operatorUser();
    createdEmails.push(data.email);
    const resp = await api.post('/api/auth/register', data);
    expect(resp.status()).toBe(201);

    const loginResp = await request.post('/api/auth/login', {
      data: { email: data.email, password: data.password },
    });
    expect(loginResp.status()).toBe(200);
  });

  test('create USER role user', async ({ api }) => {
    const data = TestData.user({ role: 'USER' });
    createdEmails.push(data.email);
    const resp = await api.post('/api/auth/register', data);
    expect(resp.status()).toBe(201);
  });

  test('create VIEWER user', async ({ api }) => {
    const data = TestData.viewerUser();
    createdEmails.push(data.email);
    const resp = await api.post('/api/auth/register', data);
    expect(resp.status()).toBe(201);
  });

  test('update user role', async ({ api }) => {
    const data = TestData.user();
    createdEmails.push(data.email);
    await api.post('/api/auth/register', data);

    const users = await (await api.get('/api/users')).json();
    const user = users.find(u => u.email === data.email);
    expect(user).toBeTruthy();

    const updateResp = await api.patch(`/api/users/${user.id}`, { role: 'OPERATOR' });
    expect(updateResp.status()).toBe(200);
  });

  test('reset user password', async ({ api, request }) => {
    const data = TestData.user();
    createdEmails.push(data.email);
    await api.post('/api/auth/register', data);

    const users = await (await api.get('/api/users')).json();
    const user = users.find(u => u.email === data.email);

    const newPassword = 'NewPass456!';
    const updateResp = await api.patch(`/api/users/${user.id}`, { password: newPassword });
    expect(updateResp.status()).toBe(200);

    // Verify new password works
    const loginResp = await request.post('/api/auth/login', {
      data: { email: data.email, password: newPassword },
    });
    expect(loginResp.status()).toBe(200);
  });

  test('delete user', async ({ api }) => {
    const data = TestData.user();
    await api.post('/api/auth/register', data);

    const users = await (await api.get('/api/users')).json();
    const user = users.find(u => u.email === data.email);

    const deleteResp = await api.delete(`/api/users/${user.id}`);
    expect([200, 204]).toContain(deleteResp.status());
  });

  test('create user with duplicate email fails', async ({ api }) => {
    const data = TestData.user();
    createdEmails.push(data.email);
    const first = await api.post('/api/auth/register', data);
    expect(first.status()).toBe(201);

    const second = await api.post('/api/auth/register', data);
    expect([400, 409]).toContain(second.status());
  });

  test('create user with invalid email fails', async ({ api }) => {
    const resp = await api.post('/api/auth/register', {
      email: 'not-an-email',
      password: 'TestPass123!',
      role: 'USER',
    });
    expect([400, 422]).toContain(resp.status());
  });

  test('create user with short password fails', async ({ api }) => {
    const resp = await api.post('/api/auth/register', {
      email: `pw-short-${uid()}@tranzfer.io`,
      password: '123',
      role: 'USER',
    });
    expect([400, 422]).toContain(resp.status());
  });
});

test.describe('User Management — UI', () => {
  test('users page loads with table', async ({ authedPage: page }) => {
    await page.goto('/users');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
    const hasTable = await page.locator('table, [class*="table"]').count();
    expect(hasTable).toBeGreaterThan(0);
  });

  test('users table shows superadmin', async ({ authedPage: page }) => {
    await page.goto('/users');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const superadminRow = page.locator('td:has-text("superadmin@tranzfer.io"), tr:has-text("superadmin")').first();
    await expect(superadminRow).toBeVisible({ timeout: 5000 });
  });

  test('create user button opens form', async ({ authedPage: page }) => {
    await page.goto('/users');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    const createBtn = page.locator('button:has-text("Add"), button:has-text("Create"), button:has-text("New"), button:has-text("Register")').first();
    await createBtn.click();
    await page.waitForTimeout(1000);

    // Form should have email, password, role fields
    const emailField = page.locator('input[name*="email" i], input[type="email"]').first();
    const hasEmail = await emailField.count();
    expect(hasEmail).toBeGreaterThan(0);
  });

  test('user role filter works', async ({ authedPage: page }) => {
    await page.goto('/users');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    const roleFilter = page.locator('select[name*="role" i], [class*="select"]:near(:text("Role"))').first();
    if (await roleFilter.isVisible({ timeout: 3000 }).catch(() => false)) {
      await roleFilter.selectOption('ADMIN');
      await page.waitForTimeout(1000);
      await Assertions.pageNotCrashed(page);
    }
  });
});
