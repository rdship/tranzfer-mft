/**
 * TranzFer MFT — Common assertion helpers for Playwright tests.
 */
const { expect } = require('@playwright/test');

const Assertions = {
  /**
   * Assert page did not crash (no error boundary fallback).
   */
  async pageNotCrashed(page) {
    const crashed = await page.locator('text=This page crashed').count();
    expect(crashed, 'Page should not show crash banner').toBe(0);
  },

  /**
   * Assert we are not redirected to login (session valid).
   */
  async notRedirectedToLogin(page) {
    expect(page.url(), 'Should not be on login page').not.toContain('/login');
  },

  /**
   * Assert no error toasts appeared on page.
   */
  async noErrorToasts(page) {
    await page.waitForTimeout(1000);
    const errorToasts = await page.locator(
      '[class*="toast"][class*="error"], [role="alert"]:has-text("error"), [role="alert"]:has-text("Couldn")'
    ).count();
    expect(errorToasts, 'No error toasts should appear').toBe(0);
  },

  /**
   * Assert a table has at least N rows.
   */
  async tableHasMinRows(page, minRows, tableSelector = 'table tbody tr') {
    const rows = await page.locator(tableSelector).count();
    expect(rows, `Table should have at least ${minRows} rows`).toBeGreaterThanOrEqual(minRows);
  },

  /**
   * Assert page loads and renders within timeout without errors.
   */
  async pageLoadsClean(page, path, timeout = 5000) {
    await page.goto(path);
    await page.waitForLoadState('networkidle', { timeout });
    await Assertions.pageNotCrashed(page);
    await Assertions.notRedirectedToLogin(page);
  },

  /**
   * Assert a modal/dialog is visible.
   */
  async modalVisible(page) {
    const modal = page.locator('[class*="modal"], [role="dialog"], [class*="Modal"]').first();
    await expect(modal).toBeVisible({ timeout: 5000 });
  },

  /**
   * Assert a success toast appeared.
   */
  async successToast(page) {
    const toast = page.locator(
      '[class*="toast"][class*="success"], [class*="toast-success"], [role="status"]:has-text("success"), [role="status"]:has-text("created"), [role="status"]:has-text("saved"), [role="status"]:has-text("deleted")'
    ).first();
    await expect(toast).toBeVisible({ timeout: 5000 });
  },

  /**
   * Assert API response has correct status and return body.
   */
  async apiResponse(resp, expectedStatus = 200) {
    expect(resp.status(), `Expected status ${expectedStatus}`).toBe(expectedStatus);
    if (expectedStatus === 204) return null;
    return resp.json();
  },

  /**
   * Assert API list response has items.
   */
  async apiListNotEmpty(resp) {
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const items = Array.isArray(body) ? body : (body.content || body.data || []);
    expect(items.length, 'API list should not be empty').toBeGreaterThan(0);
    return items;
  },

  /**
   * Assert form field has validation error.
   */
  async fieldHasError(page, fieldSelector) {
    const field = page.locator(fieldSelector);
    const parent = field.locator('..');
    const hasError = await parent.locator('[class*="error"], [class*="invalid"], [aria-invalid="true"]').count();
    expect(hasError).toBeGreaterThan(0);
  },
};

module.exports = { Assertions };
