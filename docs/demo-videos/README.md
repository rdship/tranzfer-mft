# TranzFer MFT — Demo Video Bundle

15 short screencasts of the live platform UI, captured via Playwright
`recordVideo` against the running stack on R132f tip (`a9c73165`).
Files are raw `.webm` at 1440×900; every clip is under 90 seconds of
playback so they drop straight into tutorial docs, marketing landing
pages, or social posts without trimming.

**Who these are for:**

- **Tutorial folder** — in-product + onboarding walkthroughs. Viewer
  is a new admin or an ops engineer learning the platform. Assumes
  the stack is up. Each video covers one page or one workflow deeply
  enough to be a complete "show me this screen."
- **Marketing folder** — landing-page / website hero reel. Quick-cut
  tour across the platform highlights. Viewer hasn't seen the product
  before.
- **Social folder** — LinkedIn / Twitter / YouTube Shorts. Sub-one-minute
  teaser that ends on a clear "there's more" hook.

Every script is under `scripts/` so dev can re-run or edit any
scenario. Shared login + navigation helpers live in `scripts/_helpers.js`.

---

## Index

### 🎓 Tutorial (13 clips, ~5 min total)

Recommended viewing order for a first-time admin:

| # | Clip | Duration | Covers |
|---|---|---|---|
| 01 | `01-first-login-dashboard.webm` | 0:20 | Login, dashboard KPI tiles, "Needs your attention" panel, sidebar navigation affordance |
| 02 | `02-keystore-tour.webm` | 0:18 | Keystore Manager — existing PGP / AES keys, detail card, where to add new |
| 03 | `03-server-instances-tour.webm` | 0:17 | Server Instances list, protocol filter tabs (SFTP/FTP/FTP_WEB), row-hover actions |
| 04 | `04-partners-tour.webm` | 0:14 | Partner Management list, open a partner, view accounts and flow binding |
| 05 | `05-flows-tour.webm` | 0:16 | Processing Flows list (14 flows), open a flow, step graph |
| 06 | `06-upload-and-monitor.webm` | 0:40 | **END-TO-END** — SFTP upload via fixture listener, then Activity Monitor picks up the new row, search for the filename |
| 07 | `07-journey-trace.webm` | 0:22 | Journey view — per-transfer step timeline, stage snapshots, audit trail |
| 08 | `08-service-listeners.webm` | 0:25 | Service Listeners page (R132 fix) — all 19 services with HTTP/HTTPS state filters |
| 09 | `09-security-compliance.webm` | 0:27 | Compliance profiles (PCI-DSS Strict), Audit Events log, Threat Intelligence |
| 10 | `10-ai-features.webm` | 0:30 | AI Recommendations, Map Builder, Mapping Corrections, EDI Convert |
| 11 | `11-proxy-dmz-infra.webm` | 0:25 | DMZ Proxy, Proxy Groups, Cluster Dashboard |
| 12 | `12-operations-hub.webm` | 0:33 | Flow Fabric (live throughput), Circuit Breakers, DLQ, Services |
| 15 | `15-users-admin.webm` | 0:13 | Users page, role admin (ADMIN / OPERATOR / USER / VIEWER), row actions |

**If you're onboarding a new admin, have them watch in order: 01 → 02
→ 03 → 04 → 05 → 06 → 07 → 12.** That's the core 3-minute flow from
login to first successful file transfer plus operational visibility.

### 📣 Marketing (2 clips, ~3 min)

| # | Clip | Duration | Purpose |
|---|---|---|---|
| 16 | `16-marketing-hero.webm` / `.mp4` | **1:43** | **🎯 Publishable marketing hero** — brand title card → 7 scene-by-scene product highlights with on-screen overlay copy (each scene pairs a one-line headline with a supporting detail) → closing CTA ("Book a demo · tranzfer.io"). No voice-over required; the overlay text carries the narrative. MP4 provided. **Drop this on a landing page, a LinkedIn post, or a sales-outreach follow-up email.** |
| 13 | `13-platform-at-a-glance.webm` / `.mp4` | 1:05 | **Raw platform tour** — unscripted quick cuts across flows, activity monitor, server instances, partners, service listeners, compliance, back to dashboard. No overlays. Use as b-roll inside a longer-form explainer, or as a sales-call "live walkthrough" opener where the presenter narrates over the top. MP4 provided. |

### 📱 Social (1 clip, under 1 min)

| # | Clip | Duration | Purpose |
|---|---|---|---|
| 14 | `14-social-60s-teaser.webm` / `.mp4` | 0:46 | **60-second teaser** — Dashboard → Flows → Activity Monitor → Journey → Service Listeners → back to Dashboard. Optimised for LinkedIn (≤ 2:20) / Twitter (≤ 2:20) / TikTok / Reels. MP4 provided. |

---

## Regenerate / edit any clip

```
# Stack must be healthy at https://localhost:443 and the regression
# fixture already built:
bash scripts/build-regression-fixture.sh

cd docs/demo-videos/scripts
node 06-upload-and-monitor.js
# → produces docs/demo-videos/tutorial/06-upload-and-monitor.webm
```

Every scenario script sources the same helper:

```js
// scripts/_helpers.js
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');
```

so if the UI adds a new page or an existing page moves, update the
helper's `login()` + selectors once and every script follows.

## Converting to MP4 / cutting for social

Twitter/LinkedIn ingest WebM but prefer MP4. One-liner with ffmpeg:

```
ffmpeg -i 14-social-60s-teaser.webm -c:v libx264 -preset medium -crf 22 \
       -movflags +faststart 14-social-60s-teaser.mp4
```

Vertical crop for mobile / TikTok:

```
ffmpeg -i 14-social-60s-teaser.webm \
       -vf "crop=900:1600:270:0" -c:v libx264 -preset medium -crf 22 \
       14-social-mobile.mp4
```

---

## What these videos do NOT cover (yet)

- **3rd-party SFTP delivery**: the actual FILE_DELIVERY happy-path
  to an external partner is blocked by BUG 12 + 13 at R132f time
  (S2S SPIFFE audience mismatch — see
  `docs/run-reports/2026-04-19-R132-DELIVERY-TEST-3rd-party-SFTP.md`).
  Once R133 closes those, a new `16-external-delivery-e2e.webm` will
  round out the onboarding journey.
- **Partner connectivity test**: needs the R132 UX gap list item
  (probe endpoint + saved-destination test button — see
  `docs/run-reports/2026-04-18-R132-UX-SECURITY-GAPS.md` GAP 6) to
  be wired.
- **Flow creation from scratch via UI**: fixture flows are pre-seeded;
  a `create-flow-wizard.webm` walkthrough depends on the flow-builder
  UI's SecurityProfile wiring (R132 main audit).

Those clips will land as follow-ups once the underlying features are
shipped. Ship order: BUG 12 → BUG 13 → 3rd-party delivery clip →
connectivity-test clip → SecurityProfile wiring clip.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
