/**
 * TranzFer MFT — assertion helpers.
 *
 * Two flavors:
 *   - Surface assertions (legacy): pageNotCrashed, noErrorToasts, etc.
 *   - Deep assertions (CTA-grade): poll until a data-plane invariant is true;
 *     attach meaningful diagnostic data on failure; verify cross-layer state.
 *
 * Prefer the deep assertions. Hardcoded waitForTimeout is an anti-pattern.
 */
const { expect } = require('@playwright/test');

// --- Polling primitive ---------------------------------------------------

/**
 * Poll an async predicate until it returns truthy or the timeout fires.
 * Returns the truthy value on success; throws with the last value on timeout.
 */
async function waitFor(predicate, { timeout = 15000, interval = 500, label = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    try { last = await predicate(); } catch (e) { last = `threw: ${e.message}`; }
    if (last && last !== true && typeof last === 'object') return last;
    if (last === true) return true;
    await new Promise(r => setTimeout(r, interval));
  }
  throw new Error(`timeout waiting for ${label} after ${timeout}ms; last=${JSON.stringify(last)}`);
}

// --- Activity-monitor helpers -------------------------------------------

/**
 * Find the most recent activity-monitor entry matching a filename.
 * Returns null if not present; does not throw.
 */
async function findActivityByFilename(api, filename, { pages = 2, size = 20 } = {}) {
  for (let p = 0; p < pages; p++) {
    const resp = await api.get(
      `/api/activity-monitor?page=${p}&size=${size}&sortBy=uploadedAt&sortDir=DESC`
    );
    if (resp.status() !== 200) return null;
    const body = await resp.json();
    const match = (body.content || []).find(e => e.filename === filename);
    if (match) return match;
  }
  return null;
}

/**
 * Wait until an activity-monitor entry for `filename` reaches `terminalStatus`
 * (default: COMPLETED). Returns the entry. Throws with the entry's state on
 * timeout — which is diagnostic: you see exactly what status it got stuck at.
 */
async function waitForActivityStatus(api, filename, {
  terminalStatus = 'COMPLETED',
  timeout = 30000,
  interval = 1000,
} = {}) {
  return waitFor(async () => {
    const entry = await findActivityByFilename(api, filename);
    if (!entry) return null;
    return entry.status === terminalStatus ? entry : null;
  }, { timeout, interval, label: `activity-monitor[${filename}].status=${terminalStatus}` });
}

// --- Backend error scraper ----------------------------------------------

/**
 * Snapshot the current log tip of `mft-<service>` and return a sentinel
 * that, when `assertNoFreshErrors` is called with it, checks for ERROR-level
 * lines emitted *after* the snapshot. Uses `docker logs` via Playwright's
 * native `request` cannot run shell — this helper is callable only when
 * test-runner host has `docker` on PATH (skips silently otherwise).
 */
async function snapshotServiceErrors(service) {
  const { execSync } = require('child_process');
  try {
    const out = execSync(`docker logs mft-${service} --tail 1 2>&1`).toString();
    // Sentinel = last line's rough timestamp; errors after this are "fresh"
    return { service, takenAt: Date.now(), bookmark: out.slice(0, 200) };
  } catch { return null; }
}

/**
 * Assert no new ERROR lines in `mft-<service>` logs since the snapshot.
 * Silently passes if docker is unavailable (safe in CI without docker).
 */
async function assertNoFreshErrors(service, sinceSnapshot, { ignoreRegex } = {}) {
  if (!sinceSnapshot) return;
  const { execSync } = require('child_process');
  let tail;
  try {
    tail = execSync(`docker logs mft-${service} --since ${Math.floor((sinceSnapshot.takenAt - 2000) / 1000)} 2>&1`).toString();
  } catch { return; }
  const errorLines = tail.split('\n').filter(l => /"level":"ERROR"/.test(l));
  const filtered = ignoreRegex ? errorLines.filter(l => !ignoreRegex.test(l)) : errorLines;
  expect(filtered.length, `fresh ERROR lines in ${service}:\n${filtered.slice(0, 5).join('\n')}`).toBe(0);
}

// --- Role boundary helpers ----------------------------------------------

/**
 * Assert that `apiUser` (USER-role client) is denied access to `path`.
 * Acceptable denial codes: 401, 403. Anything else (including 200) fails.
 */
async function assertForbiddenForUser(apiUser, path, method = 'get') {
  const resp = await apiUser[method](path);
  const code = resp.status();
  expect([401, 403], `USER role should be denied ${method.toUpperCase()} ${path} but got ${code}`)
    .toContain(code);
}

/** Assert ADMIN gets 200 AND USER gets 401/403 on the same path. */
async function assertRoleBoundary(api, apiUser, path) {
  const adminResp = await api.get(path);
  expect(adminResp.status(), `ADMIN should read ${path}`).toBe(200);
  await assertForbiddenForUser(apiUser, path, 'get');
}

// --- Legacy surface assertions (kept for backward compat) ---------------

const Assertions = {
  async pageNotCrashed(page) {
    const crashed = await page.locator('text=This page crashed').count();
    expect(crashed, 'Page should not show crash banner').toBe(0);
  },

  async notRedirectedToLogin(page) {
    expect(page.url(), 'Should not be on login page').not.toContain('/login');
  },

  async noErrorToasts(page) {
    await page.waitForTimeout(1000);
    const errorToasts = await page.locator(
      '[class*="toast"][class*="error"], [role="alert"]:has-text("error"), [role="alert"]:has-text("Couldn")'
    ).count();
    expect(errorToasts, 'No error toasts should appear').toBe(0);
  },

  async tableHasMinRows(page, minRows, tableSelector = 'table tbody tr') {
    const rows = await page.locator(tableSelector).count();
    expect(rows, `Table should have at least ${minRows} rows`).toBeGreaterThanOrEqual(minRows);
  },

  async pageLoadsClean(page, path, timeout = 5000) {
    await page.goto(path);
    await page.waitForLoadState('networkidle', { timeout });
    await Assertions.pageNotCrashed(page);
    await Assertions.notRedirectedToLogin(page);
  },

  async modalVisible(page) {
    const modal = page.locator('[class*="modal"], [role="dialog"], [class*="Modal"]').first();
    await expect(modal).toBeVisible({ timeout: 5000 });
  },

  async successToast(page) {
    const toast = page.locator(
      '[class*="toast"][class*="success"], [class*="toast-success"], [role="status"]:has-text("success"), [role="status"]:has-text("created"), [role="status"]:has-text("saved"), [role="status"]:has-text("deleted")'
    ).first();
    await expect(toast).toBeVisible({ timeout: 5000 });
  },

  async apiResponse(resp, expectedStatus = 200) {
    expect(resp.status(), `Expected status ${expectedStatus}`).toBe(expectedStatus);
    if (expectedStatus === 204) return null;
    return resp.json();
  },

  async apiListNotEmpty(resp) {
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const items = Array.isArray(body) ? body : (body.content || body.data || []);
    expect(items.length, 'API list should not be empty').toBeGreaterThan(0);
    return items;
  },

  async fieldHasError(page, fieldSelector) {
    const field = page.locator(fieldSelector);
    const parent = field.locator('..');
    const hasError = await parent.locator('[class*="error"], [class*="invalid"], [aria-invalid="true"]').count();
    expect(hasError).toBeGreaterThan(0);
  },

  // --- Deep assertions exposed under the Assertions namespace -----------
  waitFor,
  findActivityByFilename,
  waitForActivityStatus,
  snapshotServiceErrors,
  assertNoFreshErrors,
  assertForbiddenForUser,
  assertRoleBoundary,
};

module.exports = {
  Assertions,
  // Top-level exports for ergonomic imports
  waitFor,
  findActivityByFilename,
  waitForActivityStatus,
  snapshotServiceErrors,
  assertNoFreshErrors,
  assertForbiddenForUser,
  assertRoleBoundary,
};
