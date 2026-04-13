/**
 * TranzFer MFT — Security, Compliance & Threat Intelligence Tests
 *
 * Covers: screening/DLP policies, quarantine management, compliance profiles,
 * security profiles, sentinel findings, threat intelligence, keystore.
 */
const { test, expect } = require('../fixtures/auth.fixture');
const { TestData, uid } = require('../helpers/test-data');
const { Assertions } = require('../helpers/assertions');

// ═══════════════════════════════════════════════════════════
// Screening / DLP
// ═══════════════════════════════════════════════════════════

test.describe('Screening — DLP Policies API', () => {
  test('list screening policies', async ({ api }) => {
    const resp = await api.get('/api/v1/screening/policies');
    expect(resp.status()).toBe(200);
  });

  test('create a custom DLP policy', async ({ api }) => {
    const data = TestData.screeningPolicy();
    const resp = await api.post('/api/v1/screening/policies', data);
    expect([200, 201]).toContain(resp.status());
    if (resp.status() === 201 || resp.status() === 200) {
      const policy = await resp.json();
      if (policy.id) {
        await api.delete(`/api/v1/screening/policies/${policy.id}`).catch(() => {});
      }
    }
  });

  test('create PCI credit card pattern policy', async ({ api }) => {
    const data = TestData.screeningPolicy({
      patternType: 'PCI_CREDIT_CARD',
      action: 'BLOCK',
      name: `pw-pci-${uid()}`,
    });
    const resp = await api.post('/api/v1/screening/policies', data);
    expect([200, 201]).toContain(resp.status());
  });

  test('create PII SSN pattern policy', async ({ api }) => {
    const data = TestData.screeningPolicy({
      patternType: 'PII_SSN',
      action: 'FLAG',
      name: `pw-ssn-${uid()}`,
    });
    const resp = await api.post('/api/v1/screening/policies', data);
    expect([200, 201]).toContain(resp.status());
  });
});

test.describe('Screening — UI', () => {
  test('screening page loads', async ({ authedPage: page }) => {
    await page.goto('/screening');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('screening page shows policy list', async ({ authedPage: page }) => {
    await page.goto('/screening');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const hasTable = await page.locator('table, [class*="table"], [class*="list"]').count();
    expect(hasTable).toBeGreaterThan(0);
  });
});

// ═══════════════════════════════════════════════════════════
// Quarantine
// ═══════════════════════════════════════════════════════════

test.describe('Quarantine — API', () => {
  test('list quarantined items', async ({ api }) => {
    const resp = await api.get('/api/v1/quarantine');
    expect([200, 404]).toContain(resp.status());
  });
});

test.describe('Quarantine — UI', () => {
  test('quarantine page loads', async ({ authedPage: page }) => {
    await page.goto('/quarantine');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });
});

// ═══════════════════════════════════════════════════════════
// Security Profiles
// ═══════════════════════════════════════════════════════════

test.describe('Security Profiles — API', () => {
  test('list security profiles', async ({ api }) => {
    const resp = await api.get('/api/security-profiles');
    expect(resp.status()).toBe(200);
  });

  test('create security profile', async ({ api }) => {
    const data = TestData.securityProfile();
    const resp = await api.post('/api/security-profiles', data);
    expect([200, 201]).toContain(resp.status());
    if (resp.status() === 201) {
      const profile = await resp.json();
      await api.delete(`/api/security-profiles/${profile.id}`).catch(() => {});
    }
  });
});

test.describe('Security Profiles — UI', () => {
  test('security profiles page loads', async ({ authedPage: page }) => {
    await page.goto('/security-profiles');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });
});

// ═══════════════════════════════════════════════════════════
// Compliance
// ═══════════════════════════════════════════════════════════

test.describe('Compliance — API', () => {
  test('compliance endpoint returns data', async ({ api }) => {
    const resp = await api.get('/api/compliance');
    expect([200, 404]).toContain(resp.status());
  });
});

test.describe('Compliance — UI', () => {
  test('compliance page loads', async ({ authedPage: page }) => {
    await page.goto('/compliance');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });
});

// ═══════════════════════════════════════════════════════════
// Sentinel
// ═══════════════════════════════════════════════════════════

test.describe('Sentinel — API', () => {
  test('sentinel findings endpoint', async ({ api }) => {
    const resp = await api.get('/api/v1/sentinel/findings');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    // Should be paginated or array
    const items = Array.isArray(body) ? body : (body.content || []);
    expect(Array.isArray(items)).toBeTruthy();
  });

  test('sentinel health score endpoint', async ({ api }) => {
    const resp = await api.get('/api/v1/sentinel/health-score');
    expect(resp.status()).toBe(200);
    const score = await resp.json();
    expect(score).toBeTruthy();
  });

  test('sentinel findings with filters', async ({ api }) => {
    const resp = await api.get('/api/v1/sentinel/findings?severity=HIGH');
    expect(resp.status()).toBe(200);
  });
});

test.describe('Sentinel — UI', () => {
  test('sentinel page loads', async ({ authedPage: page }) => {
    await page.goto('/sentinel');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('sentinel shows health score visualization', async ({ authedPage: page }) => {
    await page.goto('/sentinel');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    // Should have some score display
    const scoreDisplay = page.locator('[class*="score"], [class*="gauge"], [class*="health"], text=/\\d+/');
    const hasScore = await scoreDisplay.count();
    expect(hasScore).toBeGreaterThan(0);
  });

  test('sentinel shows findings list', async ({ authedPage: page }) => {
    await page.goto('/sentinel');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const findings = page.locator('table, [class*="finding"], [class*="list"], [class*="card"]');
    const hasFindings = await findings.count();
    expect(hasFindings).toBeGreaterThan(0);
  });
});

// ═══════════════════════════════════════════════════════════
// Threat Intelligence
// ═══════════════════════════════════════════════════════════

test.describe('Threat Intelligence — API', () => {
  test('threat intelligence endpoint', async ({ api }) => {
    const resp = await api.get('/api/v1/threats/indicators');
    expect([200, 404]).toContain(resp.status());
  });

  test('AI threat score endpoint', async ({ api }) => {
    const resp = await api.post('/api/v1/ai/threat-score', {
      username: 'test-user',
      ipAddress: '192.168.1.1',
      action: 'FILE_UPLOAD',
      filename: 'test.csv',
      fileSizeBytes: 1024,
    });
    expect([200, 404]).toContain(resp.status());
  });

  test('AI risk score endpoint', async ({ api }) => {
    const resp = await api.post('/api/v1/ai/risk-score', {
      newIp: false,
      unusualHour: false,
      fileSizeMb: 10,
      containsPci: false,
      containsPii: false,
    });
    expect([200, 404]).toContain(resp.status());
  });
});

test.describe('Threat Intelligence — UI', () => {
  test('threat intelligence page loads', async ({ authedPage: page }) => {
    await page.goto('/threat-intelligence');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });
});

// ═══════════════════════════════════════════════════════════
// Keystore Management
// ═══════════════════════════════════════════════════════════

test.describe('Keystore — API', () => {
  test('list encryption keys', async ({ api }) => {
    const resp = await api.get('/api/v1/keys');
    expect(resp.status()).toBe(200);
  });

  test('list keys returns array', async ({ api }) => {
    const resp = await api.get('/api/v1/keys');
    expect(resp.status()).toBe(200);
    const keys = await resp.json();
    expect(Array.isArray(keys)).toBeTruthy();
  });
});

test.describe('Keystore — UI', () => {
  test('keystore page loads', async ({ authedPage: page }) => {
    await page.goto('/keystore');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('keystore page shows key list or import option', async ({ authedPage: page }) => {
    await page.goto('/keystore');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const content = page.locator('table, [class*="table"], button:has-text("Import"), button:has-text("Generate"), button:has-text("Add")');
    const hasContent = await content.count();
    expect(hasContent).toBeGreaterThan(0);
  });
});

// ═══════════════════════════════════════════════════════════
// Audit Logs
// ═══════════════════════════════════════════════════════════

test.describe('Audit Logs — API', () => {
  test('list audit logs', async ({ api }) => {
    const resp = await api.get('/api/audit-logs');
    expect(resp.status()).toBe(200);
  });

  test('audit logs contain login events', async ({ api }) => {
    const resp = await api.get('/api/audit-logs');
    expect(resp.status()).toBe(200);
    const logs = await resp.json();
    const items = Array.isArray(logs) ? logs : (logs.content || []);
    // Should contain some audit entries from test logins
    expect(items.length).toBeGreaterThan(0);
  });
});

test.describe('Audit Logs — UI', () => {
  test('audit logs page loads', async ({ authedPage: page }) => {
    await page.goto('/logs');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('audit logs page shows log entries', async ({ authedPage: page }) => {
    await page.goto('/logs');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const hasTable = await page.locator('table, [class*="table"], [class*="log"]').count();
    expect(hasTable).toBeGreaterThan(0);
  });
});
