/*  SCENARIO 07 — Journey view: trace a transfer's step-by-step path
 *  Runtime: ~100 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '07-journey-trace';
  const s = await setup(OUT, name);
  await login(s.page);

  // Go to journey (landing list of recent)
  await slowGoto(s.page, 'journey', 2500);
  await pulse(s.page, 3000);

  // Pick the first entry via clicking a recent-journey row if available
  const row = s.page.locator('a[href*="journey?trackId="], tr').first();
  if (await row.count() > 0) {
    await row.click().catch(()=>{});
    await pulse(s.page, 4500);
  }

  // Scroll through stages + step snapshots
  for (const y of [300, 600, 900, 300, 0]) {
    await s.page.evaluate(_y => window.scrollTo({ top: _y, behavior: 'smooth' }), y);
    await pulse(s.page, 1800);
  }

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
