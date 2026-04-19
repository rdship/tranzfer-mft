/*  SCENARIO 02 — Keystore Manager tour
 *  Use: tutorial (security admin)
 *  Runtime: ~100 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '02-keystore-tour';
  const s = await setup(OUT, name);
  await login(s.page);
  await slowGoto(s.page, 'keystore', 2500);

  // Key cards should appear — scroll through the list
  await pulse(s.page, 2000);
  await s.page.evaluate(() => window.scrollTo({ top: 400, behavior: 'smooth' }));
  await pulse(s.page, 2500);
  await s.page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }));
  await pulse(s.page, 1500);

  // Open the first key's detail (if a "View" or the card itself is clickable)
  const firstCard = s.page.locator('[class*="card"]').first();
  if (await firstCard.count() > 0) {
    await firstCard.hover().catch(()=>{});
    await pulse(s.page, 1200);
    await firstCard.click().catch(()=>{});
    await pulse(s.page, 3000);
  }
  // Close any opened panel via Escape
  await s.page.keyboard.press('Escape').catch(()=>{});
  await pulse(s.page, 1500);

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
