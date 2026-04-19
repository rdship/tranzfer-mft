/*  SCENARIO 09 — Security + Compliance tour
 *  Runtime: ~110 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '09-security-compliance';
  const s = await setup(OUT, name);
  await login(s.page);

  // Compliance profiles
  await slowGoto(s.page, 'compliance', 3000);
  await pulse(s.page, 3000);
  await s.page.evaluate(() => window.scrollTo({ top: 500, behavior: 'smooth' }));
  await pulse(s.page, 2500);

  // Audit events
  await slowGoto(s.page, 'audit-events', 2500);
  await pulse(s.page, 3500);
  await s.page.evaluate(() => window.scrollTo({ top: 300, behavior: 'smooth' }));
  await pulse(s.page, 2000);

  // Sentinel / Threat Intelligence
  await slowGoto(s.page, 'threat-intelligence', 2500);
  await pulse(s.page, 3500);

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
