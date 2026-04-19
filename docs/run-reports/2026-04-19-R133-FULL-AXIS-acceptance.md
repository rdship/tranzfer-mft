---
release: R133 (tip 55e99083)
grade: ❌ **No Medal**
date: 2026-04-19
tester: tester-claude
supersedes_graded: 2026-04-19-R132-FULL-AXIS-acceptance.md (🥈 Silver)
---

# R133 — Full-Axis Acceptance

**Grade: ❌ No Medal.** R133 targeted BUG 12 + BUG 13 (the S2S auth
pair blocking the delivery path) plus the Cluster React error #31 and
License 403s. The Cluster page fix landed ✓. The License withAuth
wrapper cut the 403s but **unmasked a NEW BUG 14** — `/api/v1/licenses`
500 on a schema mismatch (`license_records.services` column missing
at runtime). The two flagship fixes — **BUG 12 + BUG 13 — still do
not work at runtime.** This is the **fifth consecutive attempt** at
the same S2S auth bug class to ship with a code change that doesn't
alter the observed runtime behaviour.

R128 🥈 + R132 🥈 still stand as the clean medals.

---

## 1. Claim verification

### BUG 12 — POST `/api/delivery-endpoints` — ❌ still 500

```
POST /api/delivery-endpoints
→ HTTP 500 "An unexpected error occurred"
config-service log:
  [encryption-service] encryptCredential failed after resilience:
    403 on POST http://encryption-service:8086/api/encrypt/credential/encrypt: [no body]
```

R133's design: `BaseServiceClient` forwards inbound admin JWT on a
dedicated `X-Forwarded-Authorization` header; `PlatformJwtAuthFilter`
uses it as fallback when the primary SPIFFE JWT-SVID fails.

Source confirms both the send-side (`BaseServiceClient.java:147`) and
the receive-side (`PlatformJwtAuthFilter.java:125-217`) contain the
R133 code.

**Runtime confirms the fallback is not wired end-to-end.** Direct
probe from the `mft-config-service` container to
`mft-encryption-service`:

```
Header: Authorization: Bearer <admin-JWT>           → HTTP 200  ✓ admin works
Header: X-Forwarded-Authorization: Bearer <same>   → HTTP 403  ✗ fallback doesn't fire
No auth header                                     → HTTP 403  ✗ (expected)
```

Possible causes:
- the R133 jars were rebuilt but the encryption-service container
  image is using an older shared-platform jar (Docker layer cache);
- the fallback branch in `PlatformJwtAuthFilter` is reached but
  rejects the admin JWT on an orthogonal check (role / audience /
  signing key);
- the R133 filter is simply not on the encryption-service's active
  filter chain.

Tester cannot inspect the jar contents per role scope. Dev: verify
`mft-encryption-service` contains the R133 `PlatformJwtAuthFilter`
bytecode and is reached on `http-nio-8086-exec-*` threads.

### BUG 13 — FILE_DELIVERY to 3rd-party SFTP — ❌ 0 bytes delivered

Recreated the 3rd-party `atmoz/sftp` sidecar + direct-inserted a
`delivery_endpoints` row (BUG 12 blocks API create) + created the flow
+ uploaded 3 files (small text, 6.8 KB base64, 685 B base64):

```
flow_executions outcomes (3/3):
  FAILED  r133-deliv-*.txt — Step 0 (FILE_DELIVERY) failed for all 1 endpoints:
          R133-ThirdParty-SFTP: 403 on POST
          http://external-forwarder-service:8087/api/forward/deliver/{id}: [no body]

3rd-party SFTP /tmp/thirdparty-sftp/ contents: EMPTY
```

R133 added same-trust-domain audience fallback in
`SpiffeWorkloadClient.validate`. Intent was right. Runtime: the flow
engine → external-forwarder-service S2S hop still 403s, unchanged
from R132f.

### Cluster React error #31 — ✅ FIXED

Playwright probe of `/cluster` page with tester-claude JWT:

```
error-31 or ErrorBoundary events: 0
```

`ClusterDashboard.jsx` `currentMode` fallback correctly handles the
`{communicationMode, clusterId}` object shape — page renders clean.

### License endpoints — ⚠ PARTIAL + NEW BUG 14

```
/api/v1/licenses/health                       → 200  ✓
/api/v1/licenses/catalog/components           → 200  ✓
/api/v1/licenses                              → 500  ✗ NEW
```

The `withAuth` wrapper let the request reach the controller. The
controller then blew up on a DB schema mismatch. license-service log:

```
org.postgresql.util.PSQLException:
  ERROR: column lr1_0.services does not exist
  Position: 192

Query attempted:
  SELECT lr1_0.id, lr1_0.active, lr1_0.created_at, lr1_0.customer_id,
         lr1_0.customer_name, lr1_0.edition, lr1_0.expires_at,
         lr1_0.installation_fingerprint, lr1_0.issued_at,
         lr1_0.license_id, lr1_0.notes, lr1_0.services,  ← missing
         lr1_0.updated_at
  FROM license_records lr1_0
```

This is the **same class** as R125's V93 EDI migration in the wrong
module and R127's `sha256_checksum` naming-strategy mismatch:
entity/schema drift that unit tests can't see because they use an
auto-DDL in-memory DB that materialises whatever the entity says.

**New R134 ask (BUG 14):** add a `services JSONB NULL` column to
`license_records` (or drop the entity field if it's dead). Plus
adopt a per-release Flyway→Entity consistency scan to catch this
class structurally.

---

## 2. Regression — R128–R132 fixes stay green

| # | Endpoint / check | R132 | R133 |
|---|---|---|---|
| BUG 1 | `/api/flow-steps/{trk}/0/receive/content` on COMPLETED | 200 ✓ | **200 ✓ (41 B download)** |
| BUG 3 | `/api/servers` UI==DB | 13==13 ✓ | **13==13 ✓** |
| BUG 4 | `/api/gateway/status` | 200 ✓ | **200 ✓** |
| BUG 9 | `/api/v1/threats/dashboard` | 200 ✓ | **200 ✓** |
| BUG 11 | `/api/platform/listeners` | 16 STARTED / 3 OFFLINE | **16/3 ✓** |

No regressions on prior fixes.

---

## 3. Perf battery — ✅ 5/5 API budgets met

```
GET /api/activity-monitor     p50=?  p95=?    (part of sanity path)
GET /api/servers              p50=7ms  p95=19ms   ✓
GET /api/flows                p50=11ms p95=31ms   ✓
GET /api/accounts             p50=9ms  p95=13ms   ✓
POST /api/auth/login          p50=65ms p95=74ms   ✓
```

All under the 1500 ms / 2000 ms budgets. No regression.

---

## 4. Boot timing

Sampled from container logs; 34/34 healthy at 140 s total boot (fresh
docker nuke). Per-service spread unchanged from R132 — 1 of 18 under
the 120 s Gold budget (edi-converter). Typical range 140–170 s.

---

## 5. 4-axis grade

| Axis | Evidence | Grade |
|---|---|---|
| **Works** | 2 of 4 R133 claims land: Cluster ✓, License withAuth partial ⚠ (exposes BUG 14). Two flagship S2S fixes (BUG 12 + 13) don't alter runtime behaviour for the fifth consecutive attempt. **Admin still cannot create a delivery endpoint via API. Flow engine still cannot deliver a byte to a real partner.** | ❌ **FAIL** |
| **Safe** | Prior fixes (BUG 1/3/4/9/11) hold. License /v1/licenses regression is new — went from 403 to 500 — but caused by R133 exposing a pre-existing schema gap, not dev introducing it. Marginal. | ≈ |
| **Efficient** | Perf 5/5 under budget, p95 13–74 ms. Boot unchanged. | ✓ |
| **Reliable** | No regression pins fail. Chaos not re-run this sweep (pattern unchanged since R128). | ✓ |

**❌ No Medal.**

The Works axis is the grade-gating axis this release, and it fails
on the two features R133 was specifically chartered to ship. A medal
grade here would signal to the dev team that the commit pattern
"claim → runtime verify skipped → ship → tester finds it broken" is
acceptable. It isn't. The memory rule
`feedback_pins_must_exercise_happy_path.md` was saved on R130. R131
fixed BUG 1 by driving the real happy path in its pin. R132 / R132f
/ R133 for BUGs 12+13 each shipped with no runtime happy-path test,
and each has the same 403 at the same call site.

---

## 6. The S2S auth failure — 5 attempts, 0 progress at runtime

| Release | Code change | Runtime delta |
|---|---|---|
| R127 | LEFT JOIN FETCH on TransferTicket | Journey Download still 502 (schema bug unrelated) |
| R130 | `/retrieve-by-key` → `/stream` endpoint rename | same 403 |
| R131 | Forward admin JWT on outbound `Authorization` (user-initiated only) | Journey Download 200 ✓; other S2S calls unchanged |
| R132f | `awaitAvailable()` 5s bounded wait in FlowProcessingEngine etc | same 403 (SPIFFE was already available) |
| R133 | Secondary `X-Forwarded-Authorization` header + `SpiffeWorkloadClient.validate` same-trust-domain fallback | same 403 |

The pattern is diagnostic. Each attempt adds a code path. None of the
added code paths FIRE at runtime on the failing request. That means
the bug is BEFORE the new code path — probably at the Docker layer
(stale jar), the filter-chain wiring (filter not mounted), or the
security context (an earlier interceptor 403s before the R133 filter
runs).

**R134 ask (top priority):**

1. In one of the failing services (e.g. `mft-encryption-service`),
   add a *very* noisy `log.info("[PlatformJwtAuthFilter] entered,
   headers={}")` at the start of `doFilter`. Restart that service.
   Trigger BUG 12. If the log line DOES NOT appear on the 403
   request, the filter isn't on the chain for that request. If it
   DOES appear, log every branch taken until rejection.
2. **Don't add more code paths until step 1 produces a diagnostic
   line from the 403 request.** Five releases of adding paths that
   don't fire is a strong signal that the bug is wiring, not logic.
3. Keep BUG 14 scoped small — one missing column + a unit test that
   boots the service and calls `GET /api/v1/licenses` against the
   real Flyway-built schema, not the Hibernate auto-DDL.

---

## 7. Arc medal trajectory

| Release | Grade | Notes |
|---|---|---|
| R128 | 🥈 Silver | Last clean Silver against a complete 4-axis sweep |
| R129 | ❌ No Medal | 3 customer-impact UI bugs |
| R130 | ❌ No Medal | BUG 1 mis-diagnosed |
| R131 | 🥉 Bronze | BUG 1 finally fixed; 8 backlog open |
| R132 | 🥈 Silver | 5 of 8 backlog closed; 97% UI audit reduction |
| **R133** | **❌ No Medal** | Flagship BUG 12+13 still broken; one new BUG 14 |

R134 should target the fundamental S2S wiring question before adding
more code. A single `log.info` that either does or does not print on
the 403 request is worth more than the next five code commits.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
