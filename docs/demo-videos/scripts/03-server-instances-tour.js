/*  SCENARIO 03 — Server Instances (listeners) tour
 *  Runtime: ~110 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '03-server-instances-tour';
  const s = await setup(OUT, name);
  await login(s.page);
  await slowGoto(s.page, 'server-instances', 3000);

  // 13 servers visible, filter by protocol
  const protoTabs = ['SFTP', 'FTP', 'FTP_WEB'];
  for (const p of protoTabs) {
    const t = s.page.locator(`button:has-text("${p}")`).first();
    if (await t.count() > 0) { await t.click().catch(()=>{}); await pulse(s.page, 2500); }
  }
  // Back to All
  const all = s.page.locator('button:has-text("All")').first();
  if (await all.count() > 0) { await all.click().catch(()=>{}); await pulse(s.page, 2000); }

  // Scroll the table
  await s.page.evaluate(() => window.scrollTo({ top: 400, behavior: 'smooth' }));
  await pulse(s.page, 2500);

  // Hover a row for the action icons
  const row = s.page.locator('tr').nth(3);
  if (await row.count() > 0) { await row.hover().catch(()=>{}); await pulse(s.page, 1800); }

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
