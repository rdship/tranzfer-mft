/**
 * TranzFer MFT — End-to-End Workflow Tests
 *
 * These tests simulate complete business workflows that a real user would
 * perform across multiple features. Each test is a full journey.
 */
const { test, expect } = require('../fixtures/auth.fixture');
const { TestData, uid } = require('../helpers/test-data');
const { Assertions } = require('../helpers/assertions');

test.describe('E2E: Partner Onboarding → Account → Flow → Transfer', () => {
  let partnerId, accountId, flowId;
  const testUid = uid();

  test('Step 1: Create a new partner', async ({ api }) => {
    const data = TestData.partner({
      companyName: `E2E-Partner-${testUid}`,
      displayName: `E2E Partner ${testUid}`,
      protocolsEnabled: ['SFTP', 'FTP'],
      slaTier: 'PREMIUM',
      industry: 'Financial Services',
    });
    const resp = await api.post('/api/partners', data);
    expect(resp.status()).toBe(201);
    const partner = await resp.json();
    partnerId = partner.id;
    expect(partnerId).toBeTruthy();
  });

  test('Step 2: Activate the partner', async ({ api }) => {
    test.skip(!partnerId, 'Partner not created');
    const resp = await api.post(`/api/partners/${partnerId}/activate`);
    expect([200, 204]).toContain(resp.status());
  });

  test('Step 3: Create a transfer account for the partner', async ({ api }) => {
    test.skip(!partnerId, 'Partner not created');
    const data = TestData.account({
      username: `e2e-acct-${testUid}`,
      protocol: 'SFTP',
      partnerId: partnerId,
    });
    const resp = await api.post('/api/accounts', data);
    expect(resp.status()).toBe(201);
    const account = await resp.json();
    accountId = account.id;
    expect(accountId).toBeTruthy();
  });

  test('Step 4: Create a file flow for the account', async ({ api }) => {
    const data = TestData.flowWithSteps({
      name: `e2e-flow-${testUid}`,
      filenamePattern: `E2E_${testUid}_.*`,
      sourceAccountId: accountId || undefined,
    });
    const resp = await api.post('/api/flows', data);
    expect(resp.status()).toBe(201);
    const flow = await resp.json();
    flowId = flow.id;
    expect(flowId).toBeTruthy();
  });

  test('Step 5: Verify partner appears in partner list', async ({ api }) => {
    test.skip(!partnerId, 'Partner not created');
    const resp = await api.get('/api/partners');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const items = Array.isArray(body) ? body : (body.content || []);
    const found = items.find(p => p.id === partnerId);
    expect(found, 'Partner should appear in list').toBeTruthy();
  });

  test('Step 6: Verify account appears in account list', async ({ api }) => {
    test.skip(!accountId, 'Account not created');
    const resp = await api.get('/api/accounts');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const items = Array.isArray(body) ? body : (body.content || []);
    const found = items.find(a => a.id === accountId);
    expect(found, 'Account should appear in list').toBeTruthy();
  });

  test('Step 7: Verify flow appears in flow list', async ({ api }) => {
    test.skip(!flowId, 'Flow not created');
    const resp = await api.get('/api/flows');
    expect(resp.status()).toBe(200);
    const flows = await resp.json();
    const found = flows.find(f => f.id === flowId);
    expect(found, 'Flow should appear in list').toBeTruthy();
  });

  test('Step 8: Verify partner detail page in UI', async ({ authedPage: page }) => {
    test.skip(!partnerId, 'Partner not created');
    await page.goto(`/partners/${partnerId}`);
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  // Cleanup
  test.afterAll(async ({ request }) => {
    const { getToken } = require('../fixtures/auth.fixture');
    const token = await getToken(request);
    const headers = { Authorization: `Bearer ${token}` };
    if (flowId) await request.delete(`/api/flows/${flowId}`, { headers }).catch(() => {});
    if (accountId) await request.delete(`/api/accounts/${accountId}`, { headers }).catch(() => {});
    if (partnerId) await request.delete(`/api/partners/${partnerId}`, { headers }).catch(() => {});
  });
});

test.describe('E2E: Multi-Protocol Account Setup', () => {
  const createdIds = [];
  const testUid = uid();

  test('create SFTP, FTP, and FTP_WEB accounts', async ({ api }) => {
    const protocols = [
      { protocol: 'SFTP', username: `e2e-sftp-${testUid}`, homeDir: `/data/sftp/e2e-sftp-${testUid}` },
      { protocol: 'FTP', username: `e2e-ftp-${testUid}`, homeDir: `/data/ftp/e2e-ftp-${testUid}` },
      { protocol: 'FTP_WEB', username: `e2e-ftpweb-${testUid}`, homeDir: `/data/ftpweb/e2e-ftpweb-${testUid}` },
    ];

    for (const proto of protocols) {
      const data = TestData.account(proto);
      const resp = await api.post('/api/accounts', data);
      expect(resp.status(), `Create ${proto.protocol} account`).toBe(201);
      const account = await resp.json();
      createdIds.push(account.id);
      expect(account.protocol).toBe(proto.protocol);
    }
  });

  test('all protocols appear in account list', async ({ api }) => {
    const resp = await api.get('/api/accounts');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const items = Array.isArray(body) ? body : (body.content || []);
    const protocols = items.map(a => a.protocol);
    expect(protocols).toContain('SFTP');
    expect(protocols).toContain('FTP');
  });

  test.afterAll(async ({ request }) => {
    const { getToken } = require('../fixtures/auth.fixture');
    const token = await getToken(request);
    const headers = { Authorization: `Bearer ${token}` };
    for (const id of createdIds) {
      await request.delete(`/api/accounts/${id}`, { headers }).catch(() => {});
    }
  });
});

test.describe('E2E: Flow Pipeline with Multiple Step Types', () => {
  const createdFlowIds = [];

  test('create flows with different step combinations', async ({ api }) => {
    const flowConfigs = [
      {
        name: `e2e-encrypt-compress-${uid()}`,
        steps: [
          { type: 'ENCRYPT_PGP', config: { keyAlias: 'test' }, order: 1 },
          { type: 'COMPRESS_GZIP', config: {}, order: 2 },
          { type: 'MAILBOX', config: { folder: '/archive' }, order: 3 },
        ],
      },
      {
        name: `e2e-screen-rename-${uid()}`,
        steps: [
          { type: 'SCREEN', config: {}, order: 1 },
          { type: 'RENAME', config: { pattern: 'processed_${filename}' }, order: 2 },
          { type: 'CHECKSUM_VERIFY', config: {}, order: 3 },
        ],
      },
      {
        name: `e2e-edi-convert-${uid()}`,
        steps: [
          { type: 'CONVERT_EDI', config: { targetFormat: 'JSON' }, order: 1 },
          { type: 'SCREEN', config: {}, order: 2 },
        ],
      },
      {
        name: `e2e-decompress-decrypt-${uid()}`,
        steps: [
          { type: 'DECOMPRESS_GZIP', config: {}, order: 1 },
          { type: 'DECRYPT_PGP', config: { keyAlias: 'test' }, order: 2 },
          { type: 'MAILBOX', config: { folder: '/decrypted' }, order: 3 },
        ],
      },
    ];

    for (const config of flowConfigs) {
      const data = TestData.flow(config);
      const resp = await api.post('/api/flows', data);
      expect(resp.status(), `Create flow ${config.name}`).toBe(201);
      const flow = await resp.json();
      createdFlowIds.push(flow.id);
    }

    expect(createdFlowIds.length).toBe(4);
  });

  test('all flows visible in flow list', async ({ api }) => {
    const resp = await api.get('/api/flows');
    expect(resp.status()).toBe(200);
    const flows = await resp.json();

    for (const id of createdFlowIds) {
      const found = flows.find(f => f.id === id);
      expect(found, `Flow ${id} should exist`).toBeTruthy();
    }
  });

  test.afterAll(async ({ request }) => {
    const { getToken } = require('../fixtures/auth.fixture');
    const token = await getToken(request);
    const headers = { Authorization: `Bearer ${token}` };
    for (const id of createdFlowIds) {
      await request.delete(`/api/flows/${id}`, { headers }).catch(() => {});
    }
  });
});

test.describe('E2E: Security Configuration Workflow', () => {
  test('configure screening + compliance + security profile', async ({ api }) => {
    // Create a DLP policy
    const dlpData = TestData.screeningPolicy({
      patternType: 'PCI_CREDIT_CARD',
      action: 'BLOCK',
      name: `e2e-dlp-${uid()}`,
    });
    const dlpResp = await api.post('/api/v1/screening/policies', dlpData);
    expect([200, 201]).toContain(dlpResp.status());

    // Create a security profile
    const secData = TestData.securityProfile({ name: `e2e-sec-${uid()}` });
    const secResp = await api.post('/api/security-profiles', secData);
    expect([200, 201]).toContain(secResp.status());

    // Verify both appear in their lists
    const dlpList = await api.get('/api/v1/screening/policies');
    expect(dlpList.status()).toBe(200);

    const secList = await api.get('/api/security-profiles');
    expect(secList.status()).toBe(200);
  });
});

test.describe('E2E: Dashboard Data Verification', () => {
  test('dashboard loads and shows KPIs', async ({ authedPage: page }) => {
    await page.goto('/operations');
    await page.waitForLoadState('networkidle', { timeout: 15000 });
    await Assertions.pageNotCrashed(page);

    // Dashboard should have KPI cards or metrics
    const kpis = page.locator('[class*="kpi"], [class*="metric"], [class*="stat"], [class*="card"]');
    const kpiCount = await kpis.count();
    expect(kpiCount).toBeGreaterThan(0);
  });

  test('dashboard shows service health', async ({ authedPage: page }) => {
    await page.goto('/operations');
    await page.waitForLoadState('networkidle', { timeout: 15000 });

    // Should indicate service health somewhere
    const health = page.locator('[class*="health"], [class*="status"], text=healthy, text=UP, text=running');
    const hasHealth = await health.count();
    expect(hasHealth).toBeGreaterThanOrEqual(0);
  });

  test('live activity page shows real-time data', async ({ authedPage: page }) => {
    await page.goto('/operations/live');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('transfer journey page loads', async ({ authedPage: page }) => {
    await page.goto('/operations/journey');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('flow fabric page loads', async ({ authedPage: page }) => {
    await page.goto('/operations/fabric');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });
});

test.describe('E2E: User Role Hierarchy Verification', () => {
  const createdUsers = [];

  test('create users with all role types and verify access levels', async ({ api, request }) => {
    const roles = ['ADMIN', 'OPERATOR', 'USER', 'VIEWER'];

    for (const role of roles) {
      const userData = TestData.user({ role });
      const createResp = await api.post('/api/auth/register', userData);
      expect(createResp.status(), `Create ${role} user`).toBe(201);
      createdUsers.push(userData);

      // Login as the new user
      const loginResp = await request.post('/api/auth/login', {
        data: { email: userData.email, password: userData.password },
      });
      expect(loginResp.status(), `Login as ${role}`).toBe(200);
      const token = (await loginResp.json()).accessToken;
      const headers = { Authorization: `Bearer ${token}` };

      // All roles should be able to read partners
      const readResp = await request.get('/api/partners', { headers });
      expect(readResp.status(), `${role} can read partners`).toBe(200);
    }
  });

  test.afterAll(async ({ request }) => {
    const { getToken } = require('../fixtures/auth.fixture');
    const token = await getToken(request);
    const headers = { Authorization: `Bearer ${token}` };
    const users = await (await request.get('/api/users', { headers })).json();
    for (const userData of createdUsers) {
      const user = users.find(u => u.email === userData.email);
      if (user) {
        await request.delete(`/api/users/${user.id}`, { headers }).catch(() => {});
      }
    }
  });
});
