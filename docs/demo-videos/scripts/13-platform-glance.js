/*  SCENARIO 13 — Platform at a Glance (marketing highlight reel)
 *  Quick cuts: dashboard → flows → activity → service listeners → partners
 *  Runtime: ~95 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'marketing');
  const name = '13-platform-at-a-glance';
  const s = await setup(OUT, name);
  await login(s.page);

  // Quick dashboard flash
  await pulse(s.page, 3000);
  for (const page of ['flows', 'activity-monitor', 'server-instances', 'partners', 'listeners', 'compliance']) {
    await slowGoto(s.page, page, 1500);
    await pulse(s.page, 2500);
    await s.page.evaluate(() => window.scrollTo({ top: 200, behavior: 'smooth' }));
    await pulse(s.page, 1000);
  }
  await slowGoto(s.page, 'dashboard', 1500);
  await pulse(s.page, 2000);
  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
