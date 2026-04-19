/*  SCENARIO 05 — Processing Flows tour
 *  Runtime: ~100 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '05-flows-tour';
  const s = await setup(OUT, name);
  await login(s.page);
  await slowGoto(s.page, 'flows', 3000);

  // 14+ flows listed
  await pulse(s.page, 2500);

  // Scroll to see more
  await s.page.evaluate(() => window.scrollTo({ top: 500, behavior: 'smooth' }));
  await pulse(s.page, 2000);
  await s.page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }));
  await pulse(s.page, 1500);

  // Click a flow row to see detail
  const row = s.page.locator('tr, [role="row"]').nth(1);
  if (await row.count() > 0) {
    await row.click().catch(()=>{});
    await pulse(s.page, 3500);
  }

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
