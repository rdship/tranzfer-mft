/*  SCENARIO 12 — Operations Hub (flow fabric, circuit breakers, DLQ, services)
 *  Runtime: ~115 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '12-operations-hub';
  const s = await setup(OUT, name);
  await login(s.page);

  await slowGoto(s.page, 'flow-fabric', 3000);
  await pulse(s.page, 3500);
  await s.page.evaluate(() => window.scrollTo({ top: 400, behavior: 'smooth' }));
  await pulse(s.page, 2500);

  await slowGoto(s.page, 'circuit-breakers', 2500);
  await pulse(s.page, 3500);

  await slowGoto(s.page, 'dlq', 2500);
  await pulse(s.page, 3000);

  await slowGoto(s.page, 'services', 2500);
  await pulse(s.page, 3000);

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
