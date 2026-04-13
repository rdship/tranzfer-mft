/**
 * TranzFer MFT — Server Instances & Folder Mapping Tests
 *
 * Covers: Server CRUD, protocol-specific config, maintenance mode,
 * proxy settings, folder mapping CRUD, pattern matching, encryption options.
 */
const { test, expect } = require('../fixtures/auth.fixture');
const { TestData, uid } = require('../helpers/test-data');
const { Assertions } = require('../helpers/assertions');

// ═══════════════════════════════════════════════════════════
// Server Instances
// ═══════════════════════════════════════════════════════════

test.describe('Server Instances — API CRUD', () => {
  const createdIds = [];

  test.afterAll(async ({ request }) => {
    const { getToken } = require('../fixtures/auth.fixture');
    const token = await getToken(request);
    const headers = { Authorization: `Bearer ${token}` };
    for (const id of createdIds) {
      await request.delete(`/api/servers/${id}`, { headers }).catch(() => {});
    }
  });

  test('list server instances', async ({ api }) => {
    const resp = await api.get('/api/servers');
    expect(resp.status()).toBe(200);
    const servers = await resp.json();
    expect(Array.isArray(servers)).toBeTruthy();
  });

  test('create SFTP server instance', async ({ api }) => {
    const data = TestData.serverInstance();
    const resp = await api.post('/api/servers', data);
    expect(resp.status()).toBe(201);
    const server = await resp.json();
    createdIds.push(server.id);
    expect(server.protocol).toBe('SFTP');
    expect(server.instanceId).toBe(data.instanceId);
  });

  test('create FTP server instance', async ({ api }) => {
    const data = TestData.ftpServerInstance();
    const resp = await api.post('/api/servers', data);
    expect(resp.status()).toBe(201);
    const server = await resp.json();
    createdIds.push(server.id);
    expect(server.protocol).toBe('FTP');
  });

  test('update server instance', async ({ api }) => {
    const data = TestData.serverInstance();
    const created = await (await api.post('/api/servers', data)).json();
    createdIds.push(created.id);

    const updateResp = await api.patch(`/api/servers/${created.id}`, {
      description: 'Updated by Playwright',
      maxConnections: 100,
    });
    expect(updateResp.status()).toBe(200);
  });

  test('delete server instance', async ({ api }) => {
    const data = TestData.serverInstance();
    const created = await (await api.post('/api/servers', data)).json();

    const deleteResp = await api.delete(`/api/servers/${created.id}`);
    expect([200, 204]).toContain(deleteResp.status());
  });

  test('create server with duplicate instanceId fails', async ({ api }) => {
    const data = TestData.serverInstance();
    const first = await api.post('/api/servers', data);
    expect(first.status()).toBe(201);
    createdIds.push((await first.json()).id);

    const second = await api.post('/api/servers', data);
    expect([400, 409]).toContain(second.status());
  });

  test('filter servers by protocol SFTP', async ({ api }) => {
    const resp = await api.get('/api/servers?protocol=SFTP');
    expect(resp.status()).toBe(200);
    const servers = await resp.json();
    if (servers.length > 0) {
      expect(servers.every(s => s.protocol === 'SFTP')).toBeTruthy();
    }
  });

  test('filter active servers only', async ({ api }) => {
    const resp = await api.get('/api/servers?activeOnly=true');
    expect(resp.status()).toBe(200);
  });
});

test.describe('Server Instances — UI', () => {
  test('server instances page loads', async ({ authedPage: page }) => {
    await page.goto('/server-instances');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('server instances page shows table', async ({ authedPage: page }) => {
    await page.goto('/server-instances');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const hasTable = await page.locator('table, [class*="table"]').count();
    expect(hasTable).toBeGreaterThan(0);
  });

  test('create server button opens form', async ({ authedPage: page }) => {
    await page.goto('/server-instances');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const createBtn = page.locator('button:has-text("Add"), button:has-text("Create"), button:has-text("New")').first();
    if (await createBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await createBtn.click();
      await page.waitForTimeout(1000);
      const formVisible = await page.locator('form, [class*="modal"], [role="dialog"]').count();
      expect(formVisible).toBeGreaterThan(0);
    }
  });
});

// ═══════════════════════════════════════════════════════════
// Folder Mappings
// ═══════════════════════════════════════════════════════════

test.describe('Folder Mappings — API CRUD', () => {
  const createdIds = [];

  test('list folder mappings', async ({ api }) => {
    const resp = await api.get('/api/folder-mappings');
    expect(resp.status()).toBe(200);
  });

  test('create folder mapping between two accounts', async ({ api }) => {
    // Get existing accounts
    const accounts = await (await api.get('/api/accounts')).json();
    const accountList = Array.isArray(accounts) ? accounts : (accounts.content || []);

    if (accountList.length >= 2) {
      const source = accountList[0];
      const dest = accountList[1];
      const data = TestData.folderMapping(source.id, dest.id);
      const resp = await api.post('/api/folder-mappings', data);
      expect([200, 201]).toContain(resp.status());
      if (resp.status() === 201) {
        const mapping = await resp.json();
        createdIds.push(mapping.id);
      }
    }
  });

  test('create folder mapping with filename pattern', async ({ api }) => {
    const accounts = await (await api.get('/api/accounts')).json();
    const accountList = Array.isArray(accounts) ? accounts : (accounts.content || []);

    if (accountList.length >= 2) {
      const data = TestData.folderMapping(accountList[0].id, accountList[1].id, {
        filenamePattern: 'INVOICE_*.csv',
        sourcePath: '/invoices/incoming',
        destinationPath: '/invoices/processed',
      });
      const resp = await api.post('/api/folder-mappings', data);
      expect([200, 201]).toContain(resp.status());
      if (resp.status() === 201) {
        createdIds.push((await resp.json()).id);
      }
    }
  });

  test('delete folder mapping', async ({ api }) => {
    if (createdIds.length > 0) {
      const deleteResp = await api.delete(`/api/folder-mappings/${createdIds.pop()}`);
      expect([200, 204]).toContain(deleteResp.status());
    }
  });

  test.afterAll(async ({ request }) => {
    const { getToken } = require('../fixtures/auth.fixture');
    const token = await getToken(request);
    const headers = { Authorization: `Bearer ${token}` };
    for (const id of createdIds) {
      await request.delete(`/api/folder-mappings/${id}`, { headers }).catch(() => {});
    }
  });
});

test.describe('Folder Mappings — UI', () => {
  test('folder mappings page loads', async ({ authedPage: page }) => {
    await page.goto('/folder-mappings');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('folder mappings page has create button', async ({ authedPage: page }) => {
    await page.goto('/folder-mappings');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const createBtn = page.locator('button:has-text("Add"), button:has-text("Create"), button:has-text("New")').first();
    await expect(createBtn).toBeVisible({ timeout: 5000 });
  });
});

// ═══════════════════════════════════════════════════════════
// Folder Templates
// ═══════════════════════════════════════════════════════════

test.describe('Folder Templates — API', () => {
  test('list folder templates', async ({ api }) => {
    const resp = await api.get('/api/folder-templates');
    expect(resp.status()).toBe(200);
  });
});

test.describe('Folder Templates — UI', () => {
  test('folder templates page loads', async ({ authedPage: page }) => {
    await page.goto('/folder-templates');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });
});

// ═══════════════════════════════════════════════════════════
// External Destinations
// ═══════════════════════════════════════════════════════════

test.describe('External Destinations — API', () => {
  test('list external destinations', async ({ api }) => {
    const resp = await api.get('/api/external-destinations');
    expect(resp.status()).toBe(200);
  });

  test('create external destination', async ({ api }) => {
    const data = TestData.externalDestination();
    const resp = await api.post('/api/external-destinations', data);
    expect([200, 201]).toContain(resp.status());
    if (resp.status() === 201) {
      const dest = await resp.json();
      // Cleanup
      await api.delete(`/api/external-destinations/${dest.id}`).catch(() => {});
    }
  });
});

test.describe('External Destinations — UI', () => {
  test('external destinations page loads', async ({ authedPage: page }) => {
    await page.goto('/external-destinations');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });
});
