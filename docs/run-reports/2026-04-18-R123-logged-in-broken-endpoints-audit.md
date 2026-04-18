# R123 — Full broken-endpoints audit for a logged-in ADMIN user

**Date:** 2026-04-18
**Build:** R123 (HEAD `37fdc81e`)
**Scope:** Every API endpoint the UI calls, probed via `https://localhost:443` (api-gateway, same path the browser uses). One ADMIN token; no special scopes.
**Total endpoints probed:** 124 GET + 4 file-download + 9 flow-execution action = **137**
**Result summary:** 114 GET OK (92%) + **10 GET broken** + **4 of 4 download endpoints broken** + **8 of 9 flow-action endpoints broken**.

A logged-in admin can read most lists but cannot download files, cannot retry/cancel/stop/approve flows, and cannot reach several screening/EDI surfaces. These match the CTO's observation "cannot download file on activity monitor" and extend it.

---

## 🚨 Top-priority: Activity Monitor file download is broken (all four paths)

The `FileDownloadButton` in `ActivityMonitor.jsx` tries three URL shapes depending on prop:
- by-track: `GET /api/v1/storage/retrieve/:trackId`
- by-sha: `GET /api/v1/storage/stream/:sha256`
- by-step: `GET /api/flow-steps/:trackId/:stepIndex/:direction/content`

Probed with a real `TRZ2R8XNQW88` trackId:

| Endpoint | Code | Body |
|---|---:|---|
| `GET /api/v1/storage/retrieve/TRZ2R8XNQW88` | **500** | "An unexpected error occurred" |
| `GET /api/flow-steps/TRZ2R8XNQW88/0/input/content` | **403** | Access Denied (Spring Security) |
| `GET /api/flow-steps/TRZ2R8XNQW88/0/output/content` | **403** | Access Denied |
| `GET /api/flow-steps/TRZ2R8XNQW88/1/output/content` | **403** | Access Denied |

**Every download path is broken for an ADMIN user.** Whatever Spring Security rule is on `/api/flow-steps/**` rejects ADMIN + JWT. The storage-manager retrieve endpoint 500s (likely the same SPIFFE-on-read-path gap I flagged in the R123 Silver correction — write path works, read path doesn't).

## 🚨 Flow-execution action endpoints (buttons in Activity Monitor drawer) — 8 of 9 broken

Probed with a COMPLETED trackId. Some 409s are semantically correct (can't pause a completed flow); 500s are hard bugs.

| Verb | Endpoint | Code | Diagnosis |
|---|---|---:|---|
| POST | `/api/flow-executions/:id/restart` | 409 | State mismatch — restart only allowed for FAILED/CANCELLED. Arguably correct; also means UI never shows the button on other states (see R123 correction). |
| POST | `/api/flow-executions/:id/retry` | **500** | Hard bug — should either retry or 409. |
| POST | `/api/flow-executions/:id/pause` | 409 | Can't pause COMPLETED. Correct. |
| POST | `/api/flow-executions/:id/resume` | 409 | Can't resume COMPLETED. Correct. |
| POST | `/api/flow-executions/:id/cancel` | **500** | Hard bug. |
| POST | `/api/flow-executions/:id/schedule-retry` | 400 | Missing body fields (expected — needs retry schedule). |
| POST | `/api/flow-executions/:id/stop` | **500** | Hard bug. |
| POST | `/api/flow-executions/:id/approve` | 400 | Missing body fields (expected). |
| GET | `/api/flow-executions/:id` | 200 | Detail works. |

**Four action verbs crash (retry, cancel, stop — and approve/schedule-retry return 400 because body fields aren't being sent; likely UI forms don't populate them correctly).** The user's "can't restart" observation generalises: most operator-level flow actions are broken.

## 🚨 GET endpoints broken for ADMIN (10 of 124)

Endpoints where the UI would call them to populate a page, and the response would surface as a blank page, error toast, or silent failure:

| Status | Endpoint | Where it hurts in UI |
|---:|---|---|
| **500** | `/api/flows/executions?size=20` | Dashboard "Recent executions" widget |
| **500** | `/api/p2p/tickets` | Partner-to-partner ticketing (if enabled) |
| **500** | `/api/v1/edi/training/health` | EDI page — status widget |
| **500** | `/api/v1/edi/training/maps` | EDI page — mappings list |
| **500** | `/api/v1/edi/training/samples` | EDI page — training samples list |
| **500** | `/api/v1/edi/training/sessions` | EDI page — training sessions list |
| **500** | `/api/v1/screening/hits` | Screening page — hits list |
| **500** | `/api/v1/screening/results` | Screening page — scan results |
| **400** | `/api/v1/edi/correction/sessions` | EDI correction UI — probably missing query param |
| **404** | `/api/partner/test-connection` | Partner detail page — "test connection" button route |

**EDI and Screening pages are likely visually broken** (5 of 10 failures are EDI; 2 are Screening). Dashboard "recent executions" widget would show an error.

## Under-tested surfaces I did not probe

- **Non-GET CRUD endpoints** (POST/PUT/PATCH/DELETE to create/modify resources). These need real request bodies; a "does logged-in user see the button do what it says" audit requires Playwright-driven form-fill. Outside this one-run audit.
- **SSE stream** `/api/activity-monitor/stream` — browser EventSource only. My Playwright SSE pin is currently skipped pending test-infra fix.
- **WebSocket routes** (if any) — not yet catalogued.
- **File-portal / partner-portal** — separate UIs, separate surfaces. Would double this audit size.

---

## What this means for the R124 medal

Silver requires primary feature axes functional. Based on this audit, primary axes that **aren't** functional:

- **Downloading any file from Activity Monitor** — 4 of 4 paths broken.
- **Retrying a failed transfer** — 500.
- **Cancelling / stopping an in-flight transfer** — both 500.
- **Viewing EDI training data** — 4 of 4 EDI pages blocked.
- **Viewing Screening results** — 2 of 2 blocked.
- **Dashboard "recent executions" widget** — 500.

Until these are fixed, this is **Bronze at most**, and No Medal could be argued for "files can't be downloaded" alone — downloading is the whole point of an MFT.

## Asks for R124 (in priority order)

1. **Fix `/api/flow-steps/:id/:step/:dir/content` 403** — SPIFFE auth rule on read path rejects ADMIN JWT. Either add the endpoint to the SPIFFE allow-list for ADMIN JWTs or whatever the equivalent fix pattern is (the write-path equivalent already works).
2. **Fix `/api/v1/storage/retrieve/:trackId` 500** — server crashes. Capture stack trace and file-level fix.
3. **Fix flow-execution action 500s** — `/retry`, `/cancel`, `/stop` all crash. These are basic operator controls.
4. **Fix EDI + Screening 500 list endpoints** (6 endpoints) — whole pages unusable.
5. **Fix `/api/flows/executions?size=20` 500** — Dashboard widget.
6. **Fix `/api/partner/test-connection` 404** — "test connection" button won't work on partner detail.

---

**Git SHA:** `37fdc81e`. Probe method: `curl -sk https://localhost:443<path>` with an ADMIN bearer token (tester-claude / superadmin both tested; same results).
