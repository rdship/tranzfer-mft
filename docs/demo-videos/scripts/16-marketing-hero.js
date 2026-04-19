/*  SCENARIO 16 — Marketing HERO (publishable)
 *
 *  A polished, voice-over-ready marketing reel. Seven scenes, each with an
 *  on-screen overlay message explaining what the viewer is looking at. Opens
 *  on a brand title card, closes on a CTA. No post-production required to
 *  publish — drop it on a landing page / LinkedIn / demo-request follow-up.
 *
 *  Output: docs/demo-videos/marketing/16-marketing-hero.webm (~90 s)
 */
const path = require('path');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

// --- Overlay helpers -------------------------------------------------------

async function injectOverlayCSS(page) {
  await page.addStyleTag({ content: `
    #mft-overlay {
      position: fixed; inset: 0; pointer-events: none;
      z-index: 2147483647; font-family: 'Inter','Segoe UI',-apple-system,sans-serif;
    }
    #mft-overlay .band {
      position: absolute; left: 40px; right: 40px; bottom: 48px;
      padding: 22px 32px; border-radius: 14px;
      background: rgba(11, 13, 18, 0.86);
      border: 1px solid rgba(255,255,255,0.16);
      backdrop-filter: blur(18px);
      color: #F3F5F9; font-size: 26px; line-height: 1.35; font-weight: 500;
      letter-spacing: -0.01em;
      box-shadow: 0 20px 60px rgba(0,0,0,0.45);
      opacity: 0; transform: translateY(16px);
      transition: opacity 420ms ease-out, transform 420ms ease-out;
    }
    #mft-overlay .band.show { opacity: 1; transform: translateY(0); }
    #mft-overlay .band small { display: block; color: #AAB1BF; font-size: 16px; margin-top: 6px; font-weight: 400; }
    #mft-overlay .hero {
      position: absolute; inset: 0; display: flex; flex-direction: column;
      align-items: center; justify-content: center;
      background: radial-gradient(ellipse at 50% 40%, rgba(127, 109, 245, 0.35), rgba(11, 13, 18, 0.98) 65%);
      color: #F3F5F9; text-align: center;
      opacity: 0; transition: opacity 500ms ease-out;
    }
    #mft-overlay .hero.show { opacity: 1; }
    #mft-overlay .hero h1 {
      font-size: 92px; font-weight: 700; letter-spacing: -0.03em;
      background: linear-gradient(135deg, #fff, #B9AAFE 70%);
      -webkit-background-clip: text; -webkit-text-fill-color: transparent;
      margin: 0 0 12px 0;
    }
    #mft-overlay .hero .tag { font-size: 30px; color: #AAB1BF; font-weight: 400; }
    #mft-overlay .cta {
      position: absolute; inset: 0; display: flex; flex-direction: column;
      align-items: center; justify-content: center;
      background: rgba(11, 13, 18, 0.95);
      color: #F3F5F9; text-align: center;
      opacity: 0; transition: opacity 500ms ease-out;
    }
    #mft-overlay .cta.show { opacity: 1; }
    #mft-overlay .cta h2 { font-size: 60px; font-weight: 700; letter-spacing: -0.02em; margin: 0 0 18px 0; }
    #mft-overlay .cta .pill {
      display: inline-block; padding: 14px 28px; border-radius: 999px;
      background: #7F6DF5; color: #fff; font-size: 24px; font-weight: 600;
      box-shadow: 0 16px 44px rgba(127,109,245,0.48);
    }
    #mft-overlay .cta .sub { font-size: 22px; color: #AAB1BF; margin-top: 22px; }
  `});
  await page.evaluate(() => {
    if (!document.getElementById('mft-overlay')) {
      const root = document.createElement('div');
      root.id = 'mft-overlay';
      document.body.appendChild(root);
    }
  });
}

async function showHero(page, title, tag) {
  await page.evaluate(({ title, tag }) => {
    const root = document.getElementById('mft-overlay');
    root.innerHTML = `<div class="hero"><h1>${title}</h1><div class="tag">${tag}</div></div>`;
    requestAnimationFrame(() => root.querySelector('.hero').classList.add('show'));
  }, { title, tag });
}

async function showBand(page, big, small = '') {
  await page.evaluate(({ big, small }) => {
    const root = document.getElementById('mft-overlay');
    root.innerHTML = `<div class="band">${big}${small ? `<small>${small}</small>` : ''}</div>`;
    requestAnimationFrame(() => root.querySelector('.band').classList.add('show'));
  }, { big, small });
}

async function showCta(page, headline, pill, sub) {
  await page.evaluate(({ headline, pill, sub }) => {
    const root = document.getElementById('mft-overlay');
    root.innerHTML = `<div class="cta"><h2>${headline}</h2><div class="pill">${pill}</div><div class="sub">${sub}</div></div>`;
    requestAnimationFrame(() => root.querySelector('.cta').classList.add('show'));
  }, { headline, pill, sub });
}

async function clearOverlay(page) {
  await page.evaluate(() => {
    const root = document.getElementById('mft-overlay');
    if (root) root.innerHTML = '';
  });
}

// --- Main --------------------------------------------------------------

(async () => {
  const OUT = path.join(__dirname, '..', 'marketing');
  const name = '16-marketing-hero';
  const s = await setup(OUT, name);

  // Login silently (skip showing the login form)
  await login(s.page);
  await injectOverlayCSS(s.page);

  // ---- Scene 1: Brand hero (5s)
  await showHero(s.page, 'TranzFer MFT', 'Managed file transfer, unified and auditable');
  await pulse(s.page, 4500);
  await clearOverlay(s.page);

  // ---- Scene 2: Dashboard + "one pane of glass" (10s)
  await pulse(s.page, 1500);
  await injectOverlayCSS(s.page);
  await showBand(s.page,
    'One pane of glass across every service',
    'Inbound, outbound, delivery, compliance — all live in a single operator view.');
  await pulse(s.page, 6500);
  await clearOverlay(s.page);

  // ---- Scene 3: Flows (12s)
  await slowGoto(s.page, 'flows', 800);
  await injectOverlayCSS(s.page);
  await showBand(s.page,
    'Pipelines the team can actually read',
    'Compress, encrypt, convert EDI, screen, deliver — composed as discrete steps you can version.');
  await pulse(s.page, 8500);
  await clearOverlay(s.page);

  // ---- Scene 4: Activity monitor (12s)
  await slowGoto(s.page, 'activity-monitor', 800);
  await injectOverlayCSS(s.page);
  await showBand(s.page,
    'Every transfer, auditable at any moment',
    'Real-time row-level status, SLA, retry, and full content lineage — no guessing at partner deliveries.');
  await pulse(s.page, 8500);
  await clearOverlay(s.page);

  // ---- Scene 5: Service listeners (10s)
  await slowGoto(s.page, 'listeners', 800);
  await injectOverlayCSS(s.page);
  await showBand(s.page,
    '19 internal services, always-on',
    'SFTP, FTP, FTPS, AS2, HTTPS — every protocol surface is a managed listener with rate limits and tiered security.');
  await pulse(s.page, 8500);
  await clearOverlay(s.page);

  // ---- Scene 6: Compliance (10s)
  await slowGoto(s.page, 'compliance', 800);
  await injectOverlayCSS(s.page);
  await showBand(s.page,
    'Compliance, policy-first',
    'PCI-DSS, HIPAA, GDPR profiles enforce cipher, TLS, checksum, and blocked-extension rules before the file lands.');
  await pulse(s.page, 8500);
  await clearOverlay(s.page);

  // ---- Scene 7: Partners (10s)
  await slowGoto(s.page, 'partners', 800);
  await injectOverlayCSS(s.page);
  await showBand(s.page,
    'Partner onboarding in minutes',
    'Self-serve portal + admin wizard. Provision credentials, map folders, pick a flow — ship.');
  await pulse(s.page, 8500);
  await clearOverlay(s.page);

  // ---- Scene 8: AI (10s)
  await slowGoto(s.page, 'mapping-corrections', 800);
  await injectOverlayCSS(s.page);
  await showBand(s.page,
    'Mapping that learns from its mistakes',
    'AI-assisted EDI correction. Flag an error once, the platform remembers the fix for every future file.');
  await pulse(s.page, 8500);
  await clearOverlay(s.page);

  // ---- Scene 9: CTA (6s)
  await injectOverlayCSS(s.page);
  await showCta(s.page,
    'File transfer without the suspense.',
    'Book a demo',
    'tranzfer.io · hello@tranzfer.io');
  await pulse(s.page, 6500);

  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
