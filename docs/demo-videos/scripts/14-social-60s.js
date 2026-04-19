/*  SCENARIO 14 — 60-second social teaser
 *  Runtime: ~60 s. Aimed at LinkedIn / Twitter posts.
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'social');
  const name = '14-social-60s-teaser';
  const s = await setup(OUT, name);
  await login(s.page);

  await pulse(s.page, 2500);
  await slowGoto(s.page, 'flows', 1000);
  await pulse(s.page, 2000);
  await slowGoto(s.page, 'activity-monitor', 1000);
  await pulse(s.page, 2500);
  await slowGoto(s.page, 'journey', 1000);
  await pulse(s.page, 2000);
  await slowGoto(s.page, 'listeners', 1000);
  await pulse(s.page, 2500);
  await slowGoto(s.page, 'dashboard', 1000);
  await pulse(s.page, 2000);

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
