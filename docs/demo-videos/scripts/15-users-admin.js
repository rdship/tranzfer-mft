/*  SCENARIO 15 — Users + role administration
 *  Runtime: ~90 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '15-users-admin';
  const s = await setup(OUT, name);
  await login(s.page);
  await slowGoto(s.page, 'users', 2500);
  await pulse(s.page, 3500);
  await s.page.evaluate(() => window.scrollTo({ top: 200, behavior: 'smooth' }));
  await pulse(s.page, 2000);
  // Hover the first row to see action buttons
  const row = s.page.locator('tr').nth(1);
  if (await row.count() > 0) {
    await row.hover().catch(()=>{});
    await pulse(s.page, 2000);
  }
  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
