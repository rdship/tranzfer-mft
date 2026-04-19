/*  SCENARIO 11 — DMZ Proxy + Proxy Groups (infra security tour)
 *  Runtime: ~100 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '11-proxy-dmz-infra';
  const s = await setup(OUT, name);
  await login(s.page);

  await slowGoto(s.page, 'dmz-proxy', 3000);
  await pulse(s.page, 3000);
  await s.page.evaluate(() => window.scrollTo({ top: 300, behavior: 'smooth' }));
  await pulse(s.page, 2500);

  await slowGoto(s.page, 'proxy-groups', 2500);
  await pulse(s.page, 3500);

  await slowGoto(s.page, 'cluster', 2500);
  await pulse(s.page, 3500);

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
