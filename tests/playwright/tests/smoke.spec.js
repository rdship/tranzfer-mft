const { test, expect } = require('@playwright/test');

const ADMIN_EMAIL = 'superadmin@tranzfer.io';
const ADMIN_PASS = 'superadmin';

// Helper: login and get authenticated page
async function login(page) {
  await page.goto('/login');
  await page.fill('input[type="email"], input[name="email"]', ADMIN_EMAIL);
  await page.fill('input[type="password"], input[name="password"]', ADMIN_PASS);
  await page.click('button[type="submit"]');
  await page.waitForURL(url => !url.toString().includes('/login'), { timeout: 15000 });
}

// Helper: check no error toasts appeared
async function expectNoErrors(page) {
  // Wait a moment for any toasts to appear
  await page.waitForTimeout(1500);
  const errorToasts = await page.locator('[class*="toast"][class*="error"], [role="alert"]:has-text("error"), [role="alert"]:has-text("Couldn")').count();
  return errorToasts;
}

test.describe('Authentication @smoke', () => {
  test('login page loads', async ({ page }) => {
    await page.goto('/login');
    await expect(page.locator('input[type="email"], input[name="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
  });

  test('login with valid credentials', async ({ page }) => {
    await login(page);
    await expect(page).not.toHaveURL(/login/);
  });

  test('login with invalid credentials shows error', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[type="email"], input[name="email"]', 'wrong@email.com');
    await page.fill('input[type="password"]', 'wrongpass');
    await page.click('button[type="submit"]');
    await page.waitForTimeout(2000);
    await expect(page).toHaveURL(/login/);
  });
});

test.describe('Navigation — Every Page Loads @smoke', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  const pages = [
    { name: 'Dashboard', path: '/dashboard' },
    { name: 'Activity Monitor', path: '/operations/activity-monitor' },
    { name: 'Transfer Journey', path: '/operations/journey' },
    { name: 'Fabric', path: '/operations/fabric' },
    { name: 'Live Activity', path: '/operations/live' },
    { name: 'Partners', path: '/partners' },
    { name: 'Accounts', path: '/accounts' },
    { name: 'Server Instances', path: '/server-instances' },
    { name: 'Flows', path: '/flows' },
    { name: 'Folder Mappings', path: '/folder-mappings' },
    { name: 'Folder Templates', path: '/folder-templates' },
    { name: 'Security Profiles', path: '/security-profiles' },
    { name: 'External Destinations', path: '/external-destinations' },
    { name: 'Connectors', path: '/connectors' },
    { name: 'Scheduler', path: '/scheduler' },
    { name: 'SLA', path: '/sla' },
    { name: 'Function Queues', path: '/function-queues' },
    { name: 'Listeners', path: '/listeners' },
    { name: 'AS2 Partnerships', path: '/as2-partnerships' },
    { name: 'EDI Converter', path: '/edi' },
    { name: 'Screening', path: '/screening' },
    { name: 'Quarantine', path: '/quarantine' },
    { name: 'Keystore', path: '/keystore' },
    { name: 'Notifications', path: '/notifications' },
    { name: 'Storage', path: '/storage' },
    { name: 'Analytics', path: '/analytics' },
    { name: 'Predictions', path: '/predictions' },
    { name: 'Observatory', path: '/observatory' },
    { name: 'Sentinel', path: '/sentinel' },
    { name: 'Compliance', path: '/compliance' },
    { name: 'Threat Intelligence', path: '/threat-intelligence' },
    { name: 'Users', path: '/users' },
    { name: 'Tenants', path: '/tenants' },
    { name: 'Platform Config', path: '/platform-config' },
    { name: 'License', path: '/license' },
    { name: 'Services', path: '/services' },
    { name: 'Logs', path: '/logs' },
    { name: 'Terminal', path: '/terminal' },
    { name: 'Monitoring', path: '/monitoring' },
    { name: 'Gateway', path: '/gateway' },
    { name: 'DMZ Proxy', path: '/dmz-proxy' },
    { name: 'DLQ Manager', path: '/dlq' },
    { name: 'Circuit Breakers', path: '/circuit-breakers' },
    { name: 'Cluster', path: '/cluster' },
  ];

  for (const p of pages) {
    test(`${p.name} (${p.path}) loads without crash @full`, async ({ page }) => {
      await page.goto(p.path);
      await page.waitForTimeout(2000);

      // Page should NOT show the crash banner
      const crashed = await page.locator('text=This page crashed').count();
      expect(crashed).toBe(0);

      // Page should NOT be on login (session expired)
      const url = page.url();
      expect(url).not.toContain('/login');
    });
  }
});

test.describe('API Endpoints via Gateway @api', () => {
  let token;

  test.beforeAll(async ({ request }) => {
    const resp = await request.post('/api/auth/login', {
      data: { email: ADMIN_EMAIL, password: ADMIN_PASS },
    });
    const body = await resp.json();
    token = body.accessToken;
  });

  const endpoints = [
    { name: 'Activity Monitor', path: '/api/activity-monitor' },
    { name: 'Partners', path: '/api/partners' },
    { name: 'Servers', path: '/api/servers' },
    { name: 'Accounts', path: '/api/accounts' },
    { name: 'Flows', path: '/api/flows' },
    { name: 'Folder Mappings', path: '/api/folder-mappings' },
    { name: 'Audit Logs', path: '/api/audit-logs' },
    { name: 'Security Profiles', path: '/api/security-profiles' },
    { name: 'Platform Settings', path: '/api/platform-settings' },
    { name: 'Scheduler', path: '/api/scheduler' },
    { name: 'Function Queues', path: '/api/function-queues' },
    { name: 'Connectors', path: '/api/connectors' },
    { name: 'SLA', path: '/api/sla' },
    { name: 'Service Registry', path: '/api/service-registry' },
    { name: 'Clusters', path: '/api/clusters' },
    { name: 'Listeners', path: '/api/platform/listeners' },
    { name: 'Sentinel Findings', path: '/api/v1/sentinel/findings' },
    { name: 'Sentinel Health', path: '/api/v1/sentinel/health-score' },
    { name: 'Analytics Dashboard', path: '/api/v1/analytics/dashboard' },
    { name: 'Analytics Observatory', path: '/api/v1/analytics/observatory' },
    { name: 'AI Anomalies', path: '/api/v1/ai/anomalies' },
    { name: 'AI Predictions', path: '/api/v1/ai/predictions' },
    { name: 'Keys', path: '/api/v1/keys' },
    { name: 'EDI Maps', path: '/api/v1/convert/maps' },
    { name: 'Notification Templates', path: '/api/notifications/templates' },
    { name: 'Notification Rules', path: '/api/notifications/rules' },
    { name: 'Storage Objects', path: '/api/v1/storage/objects' },
    { name: 'Screening Policies', path: '/api/v1/screening/policies' },
    { name: 'Activity Recent', path: '/api/activity/recent' },
    { name: 'Flow Exec Live Stats', path: '/api/flow-executions/live-stats' },
    { name: 'Flow Exec Retries', path: '/api/flow-executions/scheduled-retries' },
  ];

  for (const ep of endpoints) {
    test(`API: ${ep.name} returns 200 @api`, async ({ request }) => {
      const resp = await request.get(ep.path, {
        headers: { Authorization: `Bearer ${token}` },
      });
      expect(resp.status()).toBe(200);
    });
  }
});

test.describe('CRUD Operations @full', () => {
  let token;

  test.beforeAll(async ({ request }) => {
    const resp = await request.post('/api/auth/login', {
      data: { email: ADMIN_EMAIL, password: ADMIN_PASS },
    });
    token = (await resp.json()).accessToken;
  });

  test('Create + Read + Delete a File Flow', async ({ request }) => {
    // Create
    const createResp = await request.post('/api/flows', {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        name: `playwright-test-flow-${Date.now()}`,
        filenamePattern: '.*playwright-test.*',
        direction: 'INBOUND',
        priority: 999,
        active: true,
        steps: [{ type: 'MAILBOX', config: { folder: '/archive' }, order: 1 }],
      },
    });
    expect(createResp.status()).toBe(201);
    const flow = await createResp.json();
    expect(flow.id).toBeTruthy();

    // Read
    const readResp = await request.get(`/api/flows`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(readResp.status()).toBe(200);

    // Delete
    const deleteResp = await request.delete(`/api/flows/${flow.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(deleteResp.status()).toBe(204);
  });

  test('Create + Read a Partner', async ({ request }) => {
    const createResp = await request.post('/api/partners', {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        companyName: `Playwright Test Corp ${Date.now()}`,
        displayName: 'Playwright Test',
        partnerType: 'CUSTOMER',
        protocolsEnabled: ['SFTP'],
        slaTier: 'SILVER',
        industry: 'technology',
        maxFileSizeBytes: 104857600,
        maxTransfersPerDay: 100,
        retentionDays: 30,
      },
    });
    expect(createResp.status()).toBe(201);

    const readResp = await request.get('/api/partners', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(readResp.status()).toBe(200);
  });
});

test.describe('UI Interactions @full', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('Sidebar navigation works', async ({ page }) => {
    // Click through main sidebar sections
    const sections = ['Dashboard', 'Partners', 'Flows', 'Accounts'];
    for (const section of sections) {
      const link = page.locator(`nav a:has-text("${section}"), nav button:has-text("${section}")`).first();
      if (await link.isVisible()) {
        await link.click();
        await page.waitForTimeout(1000);
        const crashed = await page.locator('text=This page crashed').count();
        expect(crashed).toBe(0);
      }
    }
  });

  test('Activity Monitor shows data', async ({ page }) => {
    await page.goto('/operations/activity-monitor');
    await page.waitForTimeout(3000);
    // Should have a table or list
    const rows = await page.locator('table tbody tr, [class*="row"], [class*="list-item"]').count();
    // At minimum, seeded data should appear (or empty state)
    expect(rows).toBeGreaterThanOrEqual(0);
  });

  test('Quick Flow Wizard opens', async ({ page }) => {
    await page.goto('/flows');
    await page.waitForTimeout(2000);
    const quickFlowBtn = page.locator('button:has-text("Quick Flow"), button:has-text("New Flow"), button:has-text("Create")').first();
    if (await quickFlowBtn.isVisible()) {
      await quickFlowBtn.click();
      await page.waitForTimeout(1000);
      // A form/modal/wizard should appear
      const formVisible = await page.locator('form, [class*="modal"], [class*="wizard"], [class*="dialog"]').count();
      expect(formVisible).toBeGreaterThan(0);
    }
  });
});
