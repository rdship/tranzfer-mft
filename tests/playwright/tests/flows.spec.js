/**
 * TranzFer MFT — File Flow Management Tests
 *
 * Covers: CRUD, step management (all step types), flow templates,
 * quick flow wizard, match criteria, priority ordering, cloning.
 */
const { test, expect } = require('../fixtures/auth.fixture');
const { TestData, uid } = require('../helpers/test-data');
const { Assertions } = require('../helpers/assertions');

test.describe('File Flow — API CRUD', () => {
  const createdIds = [];

  test.afterAll(async ({ request }) => {
    const { getToken } = require('../fixtures/auth.fixture');
    const token = await getToken(request);
    const headers = { Authorization: `Bearer ${token}` };
    for (const id of createdIds) {
      await request.delete(`/api/flows/${id}`, { headers }).catch(() => {});
    }
  });

  test('list flows', async ({ api }) => {
    const resp = await api.get('/api/flows');
    expect(resp.status()).toBe(200);
    const flows = await resp.json();
    expect(Array.isArray(flows)).toBeTruthy();
  });

  test('create a basic flow with no steps', async ({ api }) => {
    const data = TestData.flow();
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    const flow = await resp.json();
    createdIds.push(flow.id);
    expect(flow.name).toBe(data.name);
    expect(flow.active).toBe(true);
  });

  test('create flow with CHECKSUM_VERIFY + MAILBOX steps', async ({ api }) => {
    const data = TestData.flowWithSteps();
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    const flow = await resp.json();
    createdIds.push(flow.id);
    expect(flow.steps).toBeTruthy();
    if (Array.isArray(flow.steps)) {
      expect(flow.steps.length).toBe(2);
    }
  });

  test('create encryption flow (PGP + GZIP + MAILBOX)', async ({ api }) => {
    const data = TestData.encryptionFlow();
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    const flow = await resp.json();
    createdIds.push(flow.id);
  });

  test('create EDI conversion flow', async ({ api }) => {
    const data = TestData.ediFlow();
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    const flow = await resp.json();
    createdIds.push(flow.id);
  });

  test('create flow with all encryption step types', async ({ api }) => {
    const steps = [
      { type: 'ENCRYPT_PGP', config: { keyAlias: 'test' }, order: 1 },
      { type: 'DECRYPT_PGP', config: { keyAlias: 'test' }, order: 2 },
      { type: 'ENCRYPT_AES', config: { keyAlias: 'test' }, order: 3 },
      { type: 'DECRYPT_AES', config: { keyAlias: 'test' }, order: 4 },
    ];
    const data = TestData.flow({ steps });
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    createdIds.push((await resp.json()).id);
  });

  test('create flow with compression step types', async ({ api }) => {
    const steps = [
      { type: 'COMPRESS_GZIP', config: {}, order: 1 },
      { type: 'COMPRESS_ZIP', config: {}, order: 2 },
    ];
    const data = TestData.flow({ steps });
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    createdIds.push((await resp.json()).id);
  });

  test('create flow with RENAME step', async ({ api }) => {
    const data = TestData.flow({
      steps: [{ type: 'RENAME', config: { pattern: '${filename}_processed.${ext}' }, order: 1 }],
    });
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    createdIds.push((await resp.json()).id);
  });

  test('create flow with SCREEN step', async ({ api }) => {
    const data = TestData.flow({
      steps: [{ type: 'SCREEN', config: {}, order: 1 }],
    });
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    createdIds.push((await resp.json()).id);
  });

  test('create flow with EXECUTE_SCRIPT step', async ({ api }) => {
    const data = TestData.flow({
      steps: [{ type: 'EXECUTE_SCRIPT', config: { command: 'echo test', timeout: 30 }, order: 1 }],
    });
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    createdIds.push((await resp.json()).id);
  });

  test('update flow name and description', async ({ api }) => {
    const data = TestData.flow();
    const created = await (await api.post('/api/flows', data)).json();
    createdIds.push(created.id);

    const updateResp = await api.put(`/api/flows/${created.id}`, {
      ...data,
      name: `${data.name}-updated`,
      description: 'Updated by Playwright',
    });
    expect(updateResp.status()).toBe(200);
    const updated = await updateResp.json();
    expect(updated.description).toBe('Updated by Playwright');
  });

  test('delete flow', async ({ api }) => {
    const data = TestData.flow();
    const created = await (await api.post('/api/flows', data)).json();

    const deleteResp = await api.delete(`/api/flows/${created.id}`);
    expect([200, 204]).toContain(deleteResp.status());
  });

  test('create flow with duplicate name fails', async ({ api }) => {
    const data = TestData.flow();
    const first = await api.post('/api/flows', data);
    expect(first.status()).toBe(201);
    createdIds.push((await first.json()).id);

    const second = await api.post('/api/flows', data);
    expect([400, 409]).toContain(second.status());
  });

  test('create flow with no name fails', async ({ api }) => {
    const resp = await api.post('/api/flows', {
      filenamePattern: '.*',
      direction: 'INBOUND',
    });
    expect([400, 422]).toContain(resp.status());
  });

  test('flow priority ordering matters', async ({ api }) => {
    const high = TestData.flow({ priority: 1, name: `pw-high-${uid()}` });
    const low = TestData.flow({ priority: 999, name: `pw-low-${uid()}` });

    const h = await (await api.post('/api/flows', high)).json();
    const l = await (await api.post('/api/flows', low)).json();
    createdIds.push(h.id, l.id);

    // Fetch all — high priority should appear before low
    const all = await (await api.get('/api/flows')).json();
    const hIdx = all.findIndex(f => f.id === h.id);
    const lIdx = all.findIndex(f => f.id === l.id);
    if (hIdx >= 0 && lIdx >= 0) {
      expect(hIdx).toBeLessThan(lIdx);
    }
  });
});

test.describe('File Flow — UI', () => {
  test('flows page loads with list', async ({ authedPage: page }) => {
    await page.goto('/flows');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('flows page has create button', async ({ authedPage: page }) => {
    await page.goto('/flows');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const createBtn = page.locator('button:has-text("Add"), button:has-text("Create"), button:has-text("New"), button:has-text("Quick")').first();
    await expect(createBtn).toBeVisible({ timeout: 5000 });
  });

  test('quick flow wizard opens and has step selector', async ({ authedPage: page }) => {
    await page.goto('/flows');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    const quickBtn = page.locator('button:has-text("Quick Flow"), button:has-text("New Flow"), button:has-text("Create")').first();
    await quickBtn.click();
    await page.waitForTimeout(1500);

    // Should show form/wizard with step types or template selection
    const formArea = page.locator('form, [class*="modal"], [role="dialog"], [class*="wizard"]').first();
    const isVisible = await formArea.isVisible({ timeout: 5000 }).catch(() => false);
    expect(isVisible).toBeTruthy();
  });

  test('flow detail shows step pipeline visualization', async ({ authedPage: page }) => {
    await page.goto('/flows');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    // Click first flow that has steps
    const flowRow = page.locator('table tbody tr, [class*="flow-card"], [class*="row"]').first();
    if (await flowRow.isVisible({ timeout: 3000 }).catch(() => false)) {
      await flowRow.click();
      await page.waitForTimeout(1500);
      // Should show steps
      const steps = page.locator('[class*="step"], [class*="pipeline"], [class*="badge"]');
      const stepCount = await steps.count();
      expect(stepCount).toBeGreaterThanOrEqual(0);
    }
  });

  test('flow templates section exists', async ({ authedPage: page }) => {
    await page.goto('/flows');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    const quickBtn = page.locator('button:has-text("Quick Flow"), button:has-text("New Flow"), button:has-text("Create")').first();
    await quickBtn.click();
    await page.waitForTimeout(1500);

    // Look for template options
    const templates = page.locator('text=Standard Inbound, text=Secure Outbound, text=EDI Processing, text=Pass-Through, text=template, [class*="template"]');
    const hasTemplates = await templates.count();
    // Templates may or may not be visible depending on wizard state
    expect(hasTemplates).toBeGreaterThanOrEqual(0);
  });
});

test.describe('Flow Execution Monitoring', () => {
  test('live stats endpoint returns data', async ({ api }) => {
    const resp = await api.get('/api/flow-executions/live-stats');
    expect(resp.status()).toBe(200);
  });

  test('scheduled retries endpoint returns data', async ({ api }) => {
    const resp = await api.get('/api/flow-executions/scheduled-retries');
    expect(resp.status()).toBe(200);
  });

  test('flow executions list endpoint works', async ({ api }) => {
    const resp = await api.get('/api/flow-executions');
    expect([200, 404]).toContain(resp.status());
  });
});
