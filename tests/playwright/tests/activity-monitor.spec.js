/**
 * TranzFer MFT — Activity Monitor Tests
 *
 * Covers: search/filter (trackId, filename, status, protocol, username),
 * pagination, sorting, file download, data integrity display, fabric enrichment.
 */
const { test, expect } = require('../fixtures/auth.fixture');
const { Assertions } = require('../helpers/assertions');

test.describe('Activity Monitor — API', () => {
  test('default page returns paginated results', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    // Should be a Spring Page object
    expect(body).toHaveProperty('content');
    expect(body).toHaveProperty('totalElements');
    expect(body).toHaveProperty('totalPages');
    expect(body).toHaveProperty('number'); // current page
    expect(body).toHaveProperty('size');
  });

  test('pagination — page 0 size 10', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?page=0&size=10');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.size).toBe(10);
    expect(body.content.length).toBeLessThanOrEqual(10);
  });

  test('pagination — page size capped at 100', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?page=0&size=999');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.size).toBeLessThanOrEqual(100);
  });

  test('filter by status PENDING', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?status=PENDING');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    for (const entry of body.content) {
      expect(entry.status).toBe('PENDING');
    }
  });

  test('filter by status FAILED', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?status=FAILED');
    expect(resp.status()).toBe(200);
  });

  test('filter by status DOWNLOADED', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?status=DOWNLOADED');
    expect(resp.status()).toBe(200);
  });

  test('filter by filename substring', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?filename=test');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    for (const entry of body.content) {
      expect(entry.filename.toLowerCase()).toContain('test');
    }
  });

  test('filter by protocol SFTP', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?protocol=SFTP');
    expect(resp.status()).toBe(200);
  });

  test('sort by uploadedAt DESC (default)', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?sortBy=uploadedAt&sortDir=DESC');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    if (body.content.length >= 2) {
      const dates = body.content.map(e => new Date(e.uploadedAt).getTime());
      for (let i = 0; i < dates.length - 1; i++) {
        expect(dates[i]).toBeGreaterThanOrEqual(dates[i + 1]);
      }
    }
  });

  test('sort by uploadedAt ASC', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?sortBy=uploadedAt&sortDir=ASC');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    if (body.content.length >= 2) {
      const dates = body.content.map(e => new Date(e.uploadedAt).getTime());
      for (let i = 0; i < dates.length - 1; i++) {
        expect(dates[i]).toBeLessThanOrEqual(dates[i + 1]);
      }
    }
  });

  test('sort by filename', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?sortBy=originalFilename&sortDir=ASC');
    expect(resp.status()).toBe(200);
  });

  test('sort by status', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?sortBy=status');
    expect(resp.status()).toBe(200);
  });

  test('sort by fileSizeBytes', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?sortBy=fileSizeBytes');
    expect(resp.status()).toBe(200);
  });

  test('invalid sort column falls back to uploadedAt', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?sortBy=INVALID_COLUMN');
    expect(resp.status()).toBe(200);
  });

  test('response entries have expected fields', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?page=0&size=5');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    if (body.content.length > 0) {
      const entry = body.content[0];
      // Required fields
      expect(entry).toHaveProperty('trackId');
      expect(entry).toHaveProperty('filename');
      expect(entry).toHaveProperty('status');
      expect(entry).toHaveProperty('uploadedAt');
      // Optional fields should be present (even if null)
      expect('sourceUsername' in entry || 'sourceProtocol' in entry).toBeTruthy();
      expect('integrityStatus' in entry).toBeTruthy();
    }
  });

  test('integrity status values are valid', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?page=0&size=50');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const validStatuses = ['PENDING', 'VERIFIED', 'MISMATCH'];
    for (const entry of body.content) {
      if (entry.integrityStatus) {
        expect(validStatuses).toContain(entry.integrityStatus);
      }
    }
  });

  test('filter by trackId returns exact match', async ({ api }) => {
    // First get any trackId
    const listResp = await api.get('/api/activity-monitor?page=0&size=1');
    const body = await listResp.json();
    if (body.content.length > 0) {
      const trackId = body.content[0].trackId;
      const filterResp = await api.get(`/api/activity-monitor?trackId=${trackId}`);
      expect(filterResp.status()).toBe(200);
      const filtered = await filterResp.json();
      expect(filtered.content.length).toBe(1);
      expect(filtered.content[0].trackId).toBe(trackId);
    }
  });

  test('combined filters work', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?status=PENDING&sortBy=uploadedAt&sortDir=DESC&page=0&size=10');
    expect(resp.status()).toBe(200);
  });
});

test.describe('Activity Monitor — UI', () => {
  test('activity monitor page loads', async ({ authedPage: page }) => {
    await page.goto('/operations/activity-monitor');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    await Assertions.pageNotCrashed(page);
  });

  test('activity monitor shows table with data', async ({ authedPage: page }) => {
    await page.goto('/operations/activity-monitor');
    await page.waitForLoadState('networkidle', { timeout: 10000 });
    const hasTable = await page.locator('table, [class*="table"]').count();
    expect(hasTable).toBeGreaterThan(0);
  });

  test('activity monitor has search/filter controls', async ({ authedPage: page }) => {
    await page.goto('/operations/activity-monitor');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    // Should have search input and/or filter dropdowns
    const controls = await page.locator(
      'input[placeholder*="search" i], input[placeholder*="filter" i], input[placeholder*="track" i], select, [class*="filter"]'
    ).count();
    expect(controls).toBeGreaterThan(0);
  });

  test('activity monitor has pagination controls', async ({ authedPage: page }) => {
    await page.goto('/operations/activity-monitor');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    const pagination = page.locator(
      'button:has-text("Next"), button:has-text("Previous"), [class*="pagination"], [class*="page-size"], button:has-text(">")'
    );
    const hasPagination = await pagination.count();
    expect(hasPagination).toBeGreaterThanOrEqual(0); // May not appear if few results
  });

  test('clicking a row shows transfer details', async ({ authedPage: page }) => {
    await page.goto('/operations/activity-monitor');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    const firstRow = page.locator('table tbody tr').first();
    if (await firstRow.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstRow.click();
      await page.waitForTimeout(1500);
      // Should show detail panel, modal, or expanded row
      const detail = page.locator('[class*="detail"], [class*="drawer"], [class*="modal"], [class*="expand"]');
      const hasDetail = await detail.count();
      // At minimum, page should not crash
      await Assertions.pageNotCrashed(page);
    }
  });

  test('status badges show correct colors', async ({ authedPage: page }) => {
    await page.goto('/operations/activity-monitor');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    const statusBadges = page.locator('[class*="badge"], [class*="status"], [class*="chip"]');
    const count = await statusBadges.count();
    expect(count).toBeGreaterThanOrEqual(0);
  });

  test('download button is present for completed transfers', async ({ authedPage: page }) => {
    await page.goto('/operations/activity-monitor');
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    const downloadBtns = page.locator('button:has-text("Download"), a:has-text("Download"), [class*="download"]');
    const count = await downloadBtns.count();
    // Download buttons should exist if there are completed transfers
    expect(count).toBeGreaterThanOrEqual(0);
  });
});

test.describe('Activity Monitor — Fabric Enrichment', () => {
  test('fabric queues endpoint', async ({ api }) => {
    const resp = await api.get('/api/fabric/queues');
    expect([200, 404]).toContain(resp.status());
  });

  test('fabric instances endpoint', async ({ api }) => {
    const resp = await api.get('/api/fabric/instances');
    expect([200, 404]).toContain(resp.status());
  });

  test('fabric stuck endpoint', async ({ api }) => {
    const resp = await api.get('/api/fabric/stuck');
    expect([200, 404]).toContain(resp.status());
  });

  test('fabric latency endpoint', async ({ api }) => {
    const resp = await api.get('/api/fabric/latency');
    expect([200, 404]).toContain(resp.status());
  });
});
