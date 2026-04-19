/*  SCENARIO 08 — Service Listeners (R132 fix)
 *  Shows all 19 services with STARTED/OFFLINE state
 *  Runtime: ~85 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '08-service-listeners';
  const s = await setup(OUT, name);
  await login(s.page);
  await slowGoto(s.page, 'listeners', 3000);

  await pulse(s.page, 2500);

  // Scroll through the service grid
  for (const y of [300, 600, 900, 300, 0]) {
    await s.page.evaluate(_y => window.scrollTo({ top: _y, behavior: 'smooth' }), y);
    await pulse(s.page, 1500);
  }

  // Filter tabs
  for (const lbl of ['HTTPS', 'HTTP', 'Offline', 'All']) {
    const t = s.page.locator(`button:has-text("${lbl}")`).first();
    if (await t.count() > 0) { await t.click().catch(()=>{}); await pulse(s.page, 1800); }
  }

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
