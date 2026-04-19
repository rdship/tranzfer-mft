/*  SCENARIO 10 — AI features tour
 *  Runtime: ~105 s
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '10-ai-features';
  const s = await setup(OUT, name);
  await login(s.page);

  // AI recommendations / training
  await slowGoto(s.page, 'ai-recommendations', 3000).catch(()=>{});
  await pulse(s.page, 3000);

  // Map builder
  await slowGoto(s.page, 'map-builder', 2500);
  await pulse(s.page, 3500);

  // Mapping corrections
  await slowGoto(s.page, 'mapping-corrections', 2500);
  await pulse(s.page, 3000);

  // EDI convert
  await slowGoto(s.page, 'edi-convert', 2500);
  await pulse(s.page, 3000);

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
