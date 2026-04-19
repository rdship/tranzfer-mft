/*  SCENARIO 01 — First Login + Dashboard Tour
 *  Use: tutorial, marketing
 *  Target viewer: first-time admin opening the platform
 *  Runtime: ~95 s
 */
const path = require('path');
const { setup, login, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '01-first-login-dashboard';
  const s = await setup(OUT, name);

  // Login
  await login(s.page);

  // On dashboard — let the KPI tiles + "Needs Your Attention" animate in
  await pulse(s.page, 3000);

  // Scroll slowly through the dashboard
  for (const y of [200, 500, 800, 1100]) {
    await s.page.evaluate(_y => window.scrollTo({ top: _y, behavior: 'smooth' }), y);
    await pulse(s.page, 1500);
  }
  // Back to top
  await s.page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }));
  await pulse(s.page, 2000);

  // Hover the sidebar key sections briefly
  const sections = ['Activity Monitor', 'Partner Management', 'Processing Flows', 'Server Instances', 'Keystore'];
  for (const label of sections) {
    const target = s.page.locator(`nav a:has-text("${label}")`).first();
    if (await target.count() > 0) { await target.hover().catch(()=>{}); await pulse(s.page, 700); }
  }

  await pulse(s.page, 1500);
  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
