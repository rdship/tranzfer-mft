# R124 acceptance — 🥉 Bronze hold (pure version bump; all R123 audit findings unchanged)

**Date:** 2026-04-18
**Build:** R124 (HEAD `e6d9d932`)
**Changes under test:** R124 is exclusively a `pom.xml` version bump across 27 modules. No code changes, no behaviour changes.
**Outcome:** 🥉 **Bronze (same as R123 correction).** Every one of the 14 broken endpoints from yesterday's R123 logged-in audit is **still broken identically** on R124. No regressions, no improvements. Filing as a hold.

---

## Re-probe of top broken endpoints (random-sample audit check)

| Endpoint | Expected | R124 actual |
|---|---|---:|
| `GET /api/v1/storage/retrieve/:trackId` | 200 w/ file | **500** (unchanged) |
| `GET /api/flow-steps/:id/0/input/content` | 200 w/ file | **403** (unchanged) |
| `POST /api/flow-executions/:id/retry` | 200 or 409 | **500** (unchanged) |
| `POST /api/flow-executions/:id/cancel` | 200 or 409 | **500** (unchanged) |
| `GET /api/v1/edi/training/health` | 200 | **500** (unchanged) |
| `GET /api/v1/screening/hits` | 200 | **500** (unchanged) |
| `GET /api/flows/executions?size=20` | 200 | **500** (unchanged) |

7 of 7 spot-checked endpoints still broken, matches my full audit filed as
`docs/run-reports/2026-04-18-R123-logged-in-broken-endpoints-audit.md`.

---

## R124 rating

### R124 = 🥉 Bronze (hold from R123 corrected)

Same verdict, same dimensional scoring, same asks as R123 Bronze correction. The full audit at `docs/run-reports/2026-04-18-R123-logged-in-broken-endpoints-audit.md` remains the authoritative to-do list.

### Trajectory

| Release | Medal | Why |
|---|---|---|
| R120 | 🥉 | Recovery |
| R121+R122 | 🥈 | Flow engine unlocked via API |
| R123 | 🥉 | Corrected after UI walk-through — download + retry/cancel/stop broken |
| **R124** | 🥉 | **Hold — pure version bump; nothing from the 14 broken endpoints closed** |

### Asks carried forward unchanged (from R123 audit)

1. Fix `/api/flow-steps/**` 403 + `/api/v1/storage/retrieve/:id` 500 — unblocks all file downloads
2. Fix 500s on `/retry`, `/cancel`, `/stop` — unblocks operator actions
3. Fix EDI + Screening 500 list endpoints (6) — unblocks whole pages
4. Fix `/api/flows/executions?size=20` — unblocks Dashboard widget
5. Add `/api/partner/test-connection` route
6. Plus standing items: FTP-direct sanity, boot mandate, 30-min soak, Phase-2 mTLS, tester Playwright UI/SSE infra debt

---

**Git SHA:** `e6d9d932`. No re-nuke run; R124 behaviour is identical to R123 (version bump only). If dev-team wants a fresh cold-boot on R124, ping me — results will match R123.
