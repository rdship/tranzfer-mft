// Shared helpers for all demo-video Playwright scripts.
// Each scenario keeps runtime under 120s so the rendered video naturally clocks < 2 min.
const { chromium } = require('/Users/akankshasrivastava/tranzfer-mft/tests/playwright/node_modules/playwright');

async function setup(outDir, name) {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({
    ignoreHTTPSErrors: true,
    viewport: { width: 1440, height: 900 },
    recordVideo: { dir: outDir, size: { width: 1440, height: 900 } },
    deviceScaleFactor: 1,
  });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.error('[pageerror]', e.message));
  return { browser, ctx, page };
}

async function login(page, email = 'superadmin@tranzfer.io', pass = 'superadmin') {
  await page.goto('https://localhost:443/login');
  await page.waitForSelector('input[type=email]', { timeout: 15000 });
  await page.fill('input[type=email]', email);
  await page.waitForTimeout(500);
  await page.fill('input[type=password]', pass);
  await page.waitForTimeout(500);
  await page.click('button[type=submit]');
  await page.waitForURL(/\/(dashboard|home)/, { timeout: 20000 }).catch(() => {});
  await page.waitForTimeout(1500);
}

async function slowGoto(page, path, pauseMs = 1500) {
  await page.goto('https://localhost:443/' + path, { waitUntil: 'networkidle', timeout: 20000 }).catch(() => {});
  await page.waitForTimeout(pauseMs);
}

async function pulse(page, ms = 800) {
  await page.waitForTimeout(ms);
}

async function teardown({ browser, ctx, page }, targetName) {
  const videoPath = await page.video().path();
  await ctx.close();
  await browser.close();
  // rename output file with a stable scenario name
  const fs = require('fs');
  const dir = require('path').dirname(videoPath);
  const newPath = require('path').join(dir, `${targetName}.webm`);
  fs.renameSync(videoPath, newPath);
  console.log(`[video] ${targetName} → ${newPath}`);
  return newPath;
}

module.exports = { setup, login, slowGoto, pulse, teardown };
