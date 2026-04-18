# R120 first-cold-boot acceptance — 🥉 Bronze (best of the arc); SPIFFE per-service ID verified; authorization layer now the last block

**Date:** 2026-04-18
**Build:** R120 (HEAD `65f2dbbd`)
**Changes under test:**
- R120 — revert R117's AOT-safety retrofit of `FlowFabricConsumer` + `FlowFabricBridge` back to `@ConditionalOnProperty`. Dev-team determined the conditional was load-bearing (keeping Fabric beans out of services that don't need them).
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ✅ **Crash loop closed, SPIFFE identity per service verified end-to-end, platform reaches 34/34 healthy at t=222 s.** ❌ But flow engine still can't complete — storage-manager now returns `403 Access Denied` at the controller level (authorization, not authentication). Layer 10 of the onion revealed and it's a new kind: **Spring Security authorization ACLs rejecting valid SVIDs**.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy | ✅ **YES, t=222 s** (R118/R119 crash loops fully closed) |
| R118 SPIFFE per-service identity | ✅ **VERIFIED** — each service reports its own name in the SVID (see below). R118's original goal achieved. |
| SVIDs issue and are attached to S2S calls | ✅ Working |
| S2S 403 clears | ❌ Downgraded from auth-layer 403 (empty body) to authz-layer 403 (`"Forbidden"`). Different root cause now. |
| Byte-E2E flow reaches COMPLETED | ❌ Fails at storage-manager with `Access Denied` |
| Sanity sweep | ⚠️ 53/6/1 (same 3 flow-trio + 3 FTP-direct) |
| R100 FAILED mirror | ✅ Held — `r120-e2e.dat` → `status=FAILED, completedAt=True` |
| R100 COMPLETED branch | ❌ Still unverifiable (flow doesn't complete) |
| Restart count under sustained load | ⏳ Not yet tested (need functional flow engine first) |

---

## ✅ R118's goal finally verified end-to-end

```
sftp-service       identity=spiffe://filetransfer.io/sftp-service
storage-manager    identity=spiffe://filetransfer.io/storage-manager
keystore-manager   identity=spiffe://filetransfer.io/keystore-manager
onboarding-api     identity=spiffe://filetransfer.io/onboarding-api
```

On every prior release in the SPIFFE arc, these identities were:
- **Pre-R115**: no identity (attestor broken) or `/unknown` (no registration match)
- **R116-R119**: `/unknown` (registration mismatch)
- **R120**: **per-service names**. R118's original design now fully live.

Credit where due — the R120 revert was the right call. My R119 report recommended broadening `@EnableJpaRepositories` to match R99's precedent. Dev team chose the opposite direction: revert R117 for these specific beans and accept that `@ConditionalOnProperty` is load-bearing for Fabric. Both approaches work; theirs requires fewer code changes and doesn't widen repo scope on services that didn't need it.

---

## ❌ New layer exposed — storage-manager authorization ACL

### Error shape

```
# sftp-service (caller)
INLINE promotion failed: storeStream failed for 'r120-e2e.dat': 403
  POST http://storage-manager:8096/api/v1/storage/store-stream
  body: {"timestamp":1776519346.621282554,"status":403,"error":"Forbidden",...}

# storage-manager (callee)
WARN  Access denied: Access Denied
  logger=com.filetransfer.shared.exception.PlatformExceptionHandler
```

### Why this is different from prior 403s

| Release window | 403 cause | Evidence |
|---|---|---|
| R101-R112 | SPIFFE auth chain broken — no SVID attached | No auth header, filter-level reject, empty response body |
| R113-R119 | SVID attached but identity `/unknown` or classpath/attestor issue | Filter-level reject, empty response body |
| **R120** | **SVID attaches with correct per-service identity; authorization layer rejects it** | Controller-level `Access Denied`; JSON response body; `AccessDeniedException` from Spring Security |

The SVID is validating (authentication ✅). Spring Security's authorization step — `@PreAuthorize`, `AuthorizationManager`, or a `WebSecurityConfigurer` rule — is rejecting. Storage-manager's controller has a rule that `spiffe://filetransfer.io/sftp-service` doesn't satisfy.

### Most likely cause

storage-manager's security config almost certainly has either:
- An allow-list of SPIFFE IDs on each endpoint (and `sftp-service` isn't on the `store-stream` allow-list), or
- A role/scope requirement on the JWT-SVID that isn't being populated from SPIRE, or
- A default-deny rule that treats SPIFFE callers differently from platform-JWT callers

Need dev team to inspect storage-manager's `SecurityFilterChain` or `@PreAuthorize` annotations on the `StoreStreamController` endpoint.

### Fix direction

Per the R26 platform identity design (from `CLAUDE.md`), the inter-service auth contract is:
> "SPIFFE is the only inter-service auth" + 3-path validation: `mTLS peer cert (path 0) → SPIFFE JWT-SVID (path 1) → Platform JWT (path 2)`.

Storage-manager's `store-stream` and other write endpoints should accept any valid SPIFFE caller from the `filetransfer.io` trust domain. If specific endpoints need caller-restriction, model it explicitly in `@PreAuthorize` with SPIFFE ID patterns. The broad "Access Denied" with no caller identity in the log is an observability gap too — the denial should log *which* caller was denied and *why*.

**R121 ask**: either (a) broaden storage-manager's default SPIFFE acceptance to include all `filetransfer.io/*` trust-domain callers, OR (b) explicitly allow-list the services that call `store-stream` (sftp-service, ftp-service, ftp-web-service, etc).

---

## What R120 fixes — more than just the crash loop

- R118's SPIFFE per-service identity goal: verified.
- R117's AOT-safety retrofit footgun that caused R118/R119 P0s: removed via the targeted revert.
- Platform cold-boot-to-clean: now consistent at ~220-230 s (vs R118/R119 which never cleared).
- R100 mirror (FAILED branch): continues to hold.
- All Group-A services (sftp, ftp, ftp-web, gateway, config, onboarding-api, forwarder, as2) boot clean.
- All Group-B services (encryption, keystore, license, screening, storage, notification) boot clean after R119+R120 unblocked them.

---

## What R120 doesn't fix

- Flow engine end-to-end (blocked on storage-manager authorization).
- R100 COMPLETED-branch Playwright pin (needs flow to complete).
- 120 s boot mandate (still 1/18 services under).
- §11 FTP-direct PASV/LIST (pre-existing arc-long issue).

---

## 🏅 Release Rating — R120

> Rubric at [`docs/RELEASE-RATING-RUBRIC.md`](../RELEASE-RATING-RUBRIC.md).

### R120 = 🥉 Bronze (best Bronze of the arc)

**Justification — dimension by dimension:**

| Axis | Assessment | Notes |
|---|---|---|
| **Works** | ❌ Degraded | Flow engine still blocked end-to-end. But the block moved from "broken S2S auth" to "S2S auth works, authorization rule rejects" — structurally different and materially closer to working. |
| **Safe** | ✅ **Big win** — SPIFFE per-service identity verified. Platform now has genuine per-service inter-service auth (which is the whole point of SPIFFE). |
| **Efficient** | ✅ Improved — boot-to-clean reliably at ~222 s; R115 Metaspace + HeapDumpOnOOM still in place. |
| **Reliable** | ✅ **Big win** — no crash loops; R117 AOT footgun closed via targeted revert; every Java service reaches healthy. |

**Bronze because:** Primary feature axis (flow engine) is still broken, but the block is now a specific, visible, single-layer authorization rule rather than the compounding chain of SPIFFE/SPIRE issues the arc has been peeling.

**Why not Silver:** Silver requires primary feature axes functional. Flow engine doesn't complete.

**Why not No Medal:** Obvious — 34/34 healthy, SPIFFE working, login works, no regression vs R117/prior-working-SPIFFE-dormant state.

**Why this is the best Bronze of the arc:**
- R110-R117 Bronzes: SPIFFE was broken at various layers. Auth chain itself not trustworthy.
- R120 Bronze: SPIFFE is actually working. The residual block is a config/authorization layer, not a plumbing issue. One `@PreAuthorize` fix away from Silver.

### Trajectory (R95 → R120)

| Release | Medal | Note |
|---|---|---|
| R95, R97, R105-R109 | 🚫 | P0 blockers |
| R100, R103-R104 | 🥈 | First clean boots; SPIFFE dormant |
| R110-R117 | 🥉 × 8 | SPIFFE onion peeled layer by layer |
| R118, R119 | 🚫 🚫 | R117 retrofit-induced P0s |
| **R120** | 🥉 **Bronze** | SPIFFE per-service ID verified; storage-manager authorization is the last block |

**Recovery from the R118/R119 regression** via targeted revert. Dev team made the right call backing out R117's retrofit for Fabric beans instead of compounding the fix chain.

### What would earn Silver on R121

- Storage-manager (+ probably keystore-manager, screening-service) accept `filetransfer.io/*` SPIFFE identities from known caller services on their write endpoints.
- Byte-E2E completes (`r121-e2e.dat` → `status=COMPLETED`).
- R100 COMPLETED-branch Playwright pin verifies ✅.
- R86 Playwright pin (canonical reproducer for 10 consecutive releases) finally goes green.

### What would earn Gold on R122+

All Silver criteria **plus**: 120 s boot mandate met on ≥15/18 Java services, zero new tester findings, `test:release-gate` all 23 Playwright tests green, sustained-load without OOM.

---

**Git SHA:** `65f2dbbd` (R120 tip).
