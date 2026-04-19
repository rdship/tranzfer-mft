/*  SCENARIO 04 — Partner Management tour
 *  Runtime: ~100 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '04-partners-tour';
  const s = await setup(OUT, name);
  await login(s.page);
  await slowGoto(s.page, 'partners', 2500);

  // 5 partners seeded — show list
  await pulse(s.page, 2000);

  // Open the first partner
  const row = s.page.locator('tr').nth(1);
  if (await row.count() > 0) {
    await row.click().catch(()=>{});
    await pulse(s.page, 3500);
  }

  // Scroll the partner detail page
  await s.page.evaluate(() => window.scrollTo({ top: 300, behavior: 'smooth' }));
  await pulse(s.page, 2500);

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
