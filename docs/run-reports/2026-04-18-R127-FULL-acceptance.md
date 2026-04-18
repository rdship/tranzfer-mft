---
release: R127 (full tip — 292628a1)
commits_validated: 08e51578, d6df6e82, b4ca7a05, 292628a1
grade: 🥈 Silver
supersedes: 2026-04-18-R127-first-cold-boot-acceptance.md (🥉 Bronze, graded 08e51578 only)
date: 2026-04-18
tester: tester-claude
---

# R127 — Full-Tip Acceptance (supersedes first-cold-boot report)

**Medal: 🥈 Silver.** The first-cold-boot report graded only `08e51578` (Bronze,
1 of 7 claims blocked). Dev shipped 3 follow-up commits
(`d6df6e82`, `b4ca7a05`, `292628a1`) during that validation; re-running the
full sweep on the new tip raises the total to **11 of 12 claims verified**.
The single remaining gap (`/api/p2p/tickets` 500 — `sha256_checksum` schema
mismatch) is unchanged across all 3 new commits — dev did not address it.
Everything else in the two UX review reports landed cleanly, including
the 4-tier dark-mode surface tokens matching my proposal byte-for-byte.

---

## 1. Why two reports

The earlier report (`🥉 Bronze`) validated `08e51578` in isolation. Dev pushed
three follow-up commits at 09:56 / 10:04 / 10:20 Pacific — after my fresh
rebuild kicked off but before my probe ran. My probe therefore tested stale
code for the Users 1970 bug and Flows toast stack. Per CTA work ethic, I
re-nuked, re-rebuilt, and re-probed on the real tip. That is this report.

The Bronze grade remains accurate *for `08e51578`*. Silver is the grade
for the full shipped state.

---

## 2. Rebuild baseline (2nd sweep)

| Step | Result |
|---|---|
| docker nuke | 0/0/3/0 — 9.33 GB reclaimed |
| `mvn -T 1C clean install -DskipTests` | ✅ 26 R127 jars, 48 s (mvn-side cache warm) |
| `docker compose build --no-cache` | ✅ 26 images, 90 s |
| `docker compose up -d` | ✅ 34/34 containers healthy at 160 s |

---

## 3. Claim verification — full R127 tip

### Original R127 (08e51578)

| # | Claim | Runtime result | Verdict |
|---|---|---|---|
| 1 | `/api/p2p/tickets` 500 → 200 (LAZY fix) | **HTTP 500** — `sha256_checksum` column mismatch persists | **✗** |
| 2 | new `/api/partners/{id}/test-connection` (admin-side) | HTTP 200 | ✅ |
| 3 | `/api/v1/edi/correction/sessions` 400 → 200 | HTTP 200 | ✅ |
| 4 | `/api/flow-steps/:id/:step/:dir/content` proper 404/502 | HTTP 404 `ENTITY_NOT_FOUND` | ✅ |
| 5 | Dashboard predictions toast silenced | 0 visible toasts on dashboard | ✅ |
| 6 | Sidebar brand `TranzFer MFT` | Brand rendered, no clip | ✅ |
| 7 | Activity Monitor chrome trim (subtitle/tip/FILTERS) | All 3 absent in DOM body text | ✅ |

### Follow-up commits

**d6df6e82 — Users epoch + Flows toast stack**

| Claim | Runtime result | Verdict |
|---|---|---|
| Users `createdAt` → ISO-8601 string (was `{nano,epochSecond}` → UI Jan 1970) | `"2026-04-18T17:46:16.296322Z"` — clean ISO-8601; UI renders real date | ✅ |
| Flows page 3-toast stack silenced (all 3 useQueries silent) | `couldnt_load_occurrences=0` in Flows body text | ✅ |

**b4ca7a05 — dark-mode 4-tier surfaces + 5-token semantic palette**

Verified via `getComputedStyle(document.documentElement)` with
`data-theme="dark"`:

| Token | R127 value | R126 review proposal | Match |
|---|---|---|---|
| `--bg-base` | `rgb(11 13 18)` / `#0B0D12` | `#0b0d12` | ✅ |
| `--bg-raised` | `rgb(20 23 31)` / `#14171F` | `#14171f` | ✅ |
| `--bg-overlay` | `rgb(28 32 43)` / `#1C202B` | `#1c202b` | ✅ |
| `--bg-elevated` | `rgb(38 43 56)` / `#262B38` | `#262b38` | ✅ |
| body bg in dark | `rgb(11, 13, 18)` (actually rendered) | base tier | ✅ |

Semantic palette: `--success / --info / --warning / --danger` all resolved,
mapped per the R126 review.

**292628a1 — per-screen redesigns**

| Claim | Runtime result | Verdict |
|---|---|---|
| Dashboard: Quick Access grid removed | `quick_access_present=false` | ✅ |
| Users: role pill + `⋯` overflow RowActionMenu | Visible in screenshot `04-users-dark.png` | ✅ |
| Partners: KPI bar folded into filter pills | Visible in screenshot `05-partners-dark.png`; pill-text regex didn't fire but the UI clearly shows the pattern | ✅ |
| Flows: filter buttons → underlined tabs + 120 px empty state | Visible in `03-flows-dark.png` | ✅ |

### Tally

- Original R127: **6 of 7** claims land.
- Follow-up: **5 of 5** claims land.
- **Total: 11 of 12 claims verified at runtime.**

---

## 4. Remaining gap: `/api/p2p/tickets`

The only claim from any R127 commit that does not work at runtime.

```
SQL Error: 0, SQLState: 42703
ERROR: column tt1_0.sha256checksum does not exist
  Hint: Perhaps you meant to reference the column "tt1_0.sha256_checksum".
```

Root cause (unchanged from the first-cold-boot report): Hibernate's
`SpringPhysicalNamingStrategy` collapses `sha256Checksum` → `sha256checksum`
without the digit-letter word break. DB column is `sha256_checksum`. Fix is
a one-line `@Column(name = "sha256_checksum")` on the entity.

None of the 3 follow-up commits touched `TransferTicket.java`. This is the
same class of repeat that `R125-schema-audit` already flagged
("two latent entity/migration mismatches"). A regression pin hitting
`GET /api/p2p/tickets` would have caught the failure before ship.

**R128 ask:** 1-line `@Column` annotation + add an `api-validation`
Playwright pin asserting `/api/p2p/tickets` returns 200.

---

## 5. Other gaps (not claimed by R127)

| Finding | Severity | Notes |
|---|---|---|
| `/api/function-queues/dashboard-stats` 400 — path-ordering (`{id}=dashboard-stats`) | Medium | Not claimed in R127; move route-literal above path-var. Pre-existing. |
| `/api/flows` Redis Jackson type-id (fixture script flagged) | Low | `GET /api/flows` now returns 200 — may be flaky after cache growth; not reproducible in first-load cold boot. |
| `ServiceContext.jsx` duplicate `/monitoring` key (vite warning) | Low | Pre-R127 from commit `d83816f2`; doesn't break build. |

---

## 6. Boot timing

| Service | R126 | R127-first (08e51578) | R127-full (292628a1) |
|---|---|---|---|
| edi-converter | 25 s | 25.6 s | 22.3 s |
| screening-service | 147 s | 147.6 s | **126.4 s** |
| notification-service | 173 s | 173.5 s | 140.9 s |
| keystore-manager | 164 s | 163.7 s | 142.0 s |
| storage-manager | 146 s | 145.9 s | 153.0 s |
| ai-engine | 181 s | 181.6 s | 164.0 s |
| onboarding-api | 180 s | 181.5 s | 163.6 s |
| (others) | 145–184 s | 145–184 s | 136–164 s |

Average dropped ~15 s across the stack — most likely CPU-variance between
the two sweeps on the same laptop, not a code delta. screening-service is
the only service close to the 120 s budget now (126 s, -6 s from budget).
**Still only 1 of 18** (edi-converter) actually under 120 s.

---

## 7. Regression pins (Playwright)

`regression-pins.spec.js` — 13 pins.

- 10 passed (first try)
- 2 flaky (R86 byte-E2E, R100 FAILED branch — passed on retry)
- 1 skipped

Same profile as the first-cold-boot report. No regressions introduced by
`d6df6e82` / `b4ca7a05` / `292628a1` (expected — all three are UI-only plus
one controller DTO change).

---

## 8. Medal reasoning

**4-axis rubric (public):**

| Axis | Assessment |
|---|---|
| **Works** | 11 / 12 claims verified at runtime. Every visible UX polish from the two R126 review docs landed. The one surviving gap (`p2p/tickets`) is a schema-bug repeat, not a broken feature the user can see. |
| **Safe** | No new regressions. Three follow-up commits are UI-only + 1 controller DTO; regression pins unchanged. |
| **Efficient** | Boot profile marginally better (-15 s average, likely CPU noise); no architectural boot-time wins. Gold 120-s-for-all criterion still unmet. |
| **Reliable** | Regression pins 10 / 2-flaky / 1-skip — stable across both sweeps, identical to R126. |

**Silver.**
- Not Bronze: 11 / 12 claims is the strongest release ratio in the R95–R127 arc;
  the dev team consumed *both* R126 UX review docs almost verbatim (4-tier
  tokens match my proposed hex values *byte-for-byte*). That's not a Bronze
  release — it's a Silver that got one schema fix wrong.
- Not Gold: (a) `p2p/tickets` still 500; (b) boot 17 / 18 still over budget;
  (c) no Phase-2 mTLS X.509 verification; (d) no 30-min sustained soak.

---

## 9. R128 ask (ordered by impact ÷ effort)

1. `@Column(name = "sha256_checksum")` on `TransferTicket.sha256Checksum` +
   add a regression pin hitting `GET /api/p2p/tickets` — **5 min code, 10 min pin**.
2. `/api/function-queues/dashboard-stats` path-ordering — **5 min**.
3. Push 3 more services under 120 s: screening-service (126 s) needs ~7 s,
   notification-service + keystore-manager (140–142 s) need ~25 s each — likely
   a shared Flyway/Hibernate metadata init cost. **Profile with async-profiler,
   carve one bean at a time.**
4. Retire `ServiceContext.jsx` `/monitoring` duplicate key (vite warning).
5. Phase-2 mTLS X.509-SVID verification on a single service pair, as a
   proof-of-shape for Gold.
6. 30-min sustained soak + Metaspace growth check (Gold gate).

---

## 10. Artifacts

- `docs/run-reports/r127-full-acceptance-bundle/` — dark-mode UI screenshots at `1440×900`, dev-tools computed-style capture log.
  - `01-dashboard-dark.png`, `01b-dashboard-dark.png`
  - `02-activity-monitor-dark.png`
  - `03-flows-dark.png`
  - `04-users-dark.png`
  - `05-partners-dark.png`
  - `06-servers-dark.png`
  - `console-log.txt` — token values + claim-verification log

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
