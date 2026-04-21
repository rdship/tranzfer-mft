# R134K — 🥉 Bronze product-state (first non-❌): flow engine works end-to-end via fallback

**Commit tested:** `226c5212` (R134K)
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy
**Headline:** **After 12 R134-series cycles, BUG 13 regression flow fires end-to-end and returns the canonical "closed" signature.** Product-state climbs off ❌ No Medal for the first time in the R134 series.

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134K contribution** | 🥈 **Silver** (runtime-verified) | Fallback chain reshape works exactly as designed — storage-coord 403 logs a WARN and cleanly drops to pg_advisory_xact_lock. `writeFile()` returns. `close()` completes. Callback fires. Flow engine runs. BUG 13 regression closes on real path. Also corrected the misleading `[VFS][lockPath]` log placement (now fires only on success). Addresses defect #2 from R134J cleanly; defect #1 explicitly deferred to a separate cycle. |
| **Product-state at R134K checkpoint** | 🥉 **Bronze** | First non-❌ grade of the R134-series. Flow engine operational. BUG 13 closed on real path. But: storage-coord auth still 403s (running on fallback), ftp-2 still UNKNOWN, demo-onboard 92.1%, 11 third-party deps. Not yet Silver because vision-axes aren't all preserved (Distributed axis on single-instance fallback, not multi-pod coord). |

---

## The end-to-end chain that finally works

**1. SFTP upload lands:**
```
[sftp-accessor] openFile class=VirtualSftpPath fs=VirtualSftpFileSystem provider=VirtualSftpFileSystemProvider
[vfs-provider] newByteChannel returned=VirtualWriteChannel callbackPresent=true
[vfs-channel] close() entered open=true
[vfs-channel] close() tempFileSize=38
[vfs-channel] close() bucket=INLINE
[vfs-channel] close() about to call vfs.writeFile (INLINE) ...
```

**2. storage-coord primary fails → fallback chain triggers (R134K's fix):**
```
[VFS][lockPath] storage-coord tryAcquire FAILED for account=... path=...
    403 on POST "http://storage-manager:8096/api/v1/coordination/locks/.../acquire": [no body].
    Falling through to next backend (redis → pg_advisory).
[VFS][lockPath] backend=pg_advisory_xact_lock (R134z last-resort — single-instance fallback; no storage-coord, no Redis)
```

**3. writeFile returns (the log that never fired pre-R134K):**
```
[vfs-channel] close() vfs.writeFile (INLINE) RETURNED for vpath=...
```

**4. Flow engine picks up the upload:**
```
flow_executions: SFTP Delivery Regression status=PROCESSING
→ CHECKSUM_VERIFY  OK
→ FILE_DELIVERY    …in flight…
```

**5. External-forwarder-service reached (BUG 13 closed pattern from R134k):**
```
[PlatformJwtAuthFilter] entered method=POST uri=/api/forward/deliver/...
    authz=Bearer eyJhbG...(370)    ← 370-char SPIFFE JWT-SVID
    xfwd=(none)                     ← flow-engine initiated, no user JWT
Delivering r134K-1776736337.regression (38 bytes) -> 'partner-sftp-endpoint' [SFTP://sftp.partner-a.com:22]
Delivery attempt 1/3 failed: DefaultConnectFuture[...sftp.partner-a.com/<unresolved>:22]: Failed (UnresolvedAddressException)
Delivery attempt 2/3 failed: UnresolvedAddressException
Unhandled exception: Delivery failed after 3 attempt(s): UnresolvedAddressException
```

**6. Flow execution completes with the expected 500 failure:**
```
flow_executions: SFTP Delivery Regression status=FAILED
    err=FILE_DELIVERY failed for all 1 endpoints: partner-sftp-endpoint: 500 on POST request...
```

This is the **canonical R134k BUG 13 closed signature**: auth passed, controller reached, outbound SFTP attempted, DNS-failed on the deliberately-fake `sftp.partner-a.com` test endpoint. HTTP 500 is the intended outcome; if BUG 13 had regressed we'd see 403 [no body] instead.

---

## Cycle-over-cycle narrative summary

```
R134F  🥈  outbox SQL unblocked (not the blocker for flow silence, but cleared noise)
R134G  🥉  VirtualWriteChannel.close() wrong-class theory: falsified
R134H  🥉  FS/provider routing theory: falsified
R134I  🥉  channel-class / callback-null / size-zero theories: falsified
R134J  🥈  ROOT CAUSE: storage-manager coord endpoint returns 403, exception bubbles
R134K  🥈  fallback chain restructured → flow engine fires for the first time since R134-series
```

Five diagnostic cycles + one structural fix. Product-state moves ❌ → 🥉.

---

## Retroactive grade corrections (no longer inflated)

In R134J I corrected R134z's "Silver runtime-verified" → 🥉 Bronze because the `[VFS][lockPath] primary path active` log I celebrated only proved the attempt. R134K reinforces that correction AND adds honesty:

- R134z's primary PG path is **still failing** on every upload with a 403
- R134K's fallback chain now gracefully catches it, but we're running on the **single-instance pg_advisory_xact_lock** — NOT distributed, NOT multi-pod-correct
- The vision axis **Distributed** is actively regressed for this hot path until defect #1 (storage-coord auth) is fixed

Net R134z status: the code is there and correct, it's just unreachable on production traffic until storage-manager's coord endpoint accepts the auth.

---

## Whole-platform state

| Check | Status | Notes |
|---|---|---|
| 23 microservices healthy | 33/36 at steady-state | https-service "health: starting" (same as prior cycles, functionally up on management ports) |
| V95–V99 migrations apply | ✅ | R134E still holds |
| AS2 bind BOUND | ✅ | R134A still holds |
| FTP_WEB secondaries UNBOUND | ✅ | R134A still holds |
| ftp-2 UNKNOWN | ⚠️ | carried — not addressed this cycle |
| encryption-service healthy | ✅ | R134B still holds |
| https-service runs | ✅ | R134A still holds |
| Outbox SQL errors = 0 | ✅ | R134F still holds |
| Caffeine L2 cache active | ✅ | R134x + R134C still holds |
| Third-party dep count | 11 | R134v Vault retired; Redis/RabbitMQ retirement (Sprints 6–9) still pending |
| Admin UI API smoke 200s | ✅ | same as last 3 cycles |
| **Flow engine fires on upload** | ✅ **(first time in R134 series)** | R134K fallback works |
| **BUG 13 regression closed on real path** | ✅ **(same signature as R134k)** | SPIFFE auth works, controller reaches outbound, 500 UnresolvedAddress on fake endpoint |
| storage-coord coord endpoint auth | ❌ 403 on every call | defect #1, deferred by R134K |
| demo-onboard success rate | 92.1% (unchanged) | seed-script issues unchanged |

---

## Why Bronze not Silver at the product level

The strict rubric says Silver requires "every headline deliverable of the current cycle ships end-to-end AND flagship bug closed on the real path AND only cosmetic or non-user-facing follow-ups remain."

- ✅ Flagship (BUG 13) closed on real path — through the R134j regression flow, same 500 UnresolvedAddress signature as R134k
- ✅ R134K's headline deliverable (fallback chain restructure) ships end-to-end
- ⚠️ **But** the platform's Distributed-vision axis regressed: the hot path runs on single-instance `pg_advisory_xact_lock` because storage-coord primary is still failing. A 2+ pod deployment of sftp-service would lose VFS lock exclusion across pods today.
- ⚠️ Secondary defects open: storage-coord auth, ftp-2 UNKNOWN, demo-onboard 92.1%

Silver would require defect #1 (the actual auth fix on storage-manager) to also land. Until that's shipped, the platform has a measurable Distributed-axis regression vs. pre-R134z state, and Bronze is the honest grade.

---

## What the dev should land next for Silver

1. **Fix storage-manager `/api/v1/coordination/locks/.../acquire` auth (defect #1 from R134J).** Same class as BUG 12/13. Likely:
   - Verify InternalServiceSecurityConfig YAML binding on storage-manager
   - Verify StorageCoordinationClient attaches SPIFFE JWT-SVID or X-Forwarded-Authorization correctly on the background-thread call path (R134J hypothesized the SPIFFE attach may be failing on MINA SSHD threads because no Spring RequestContext)
2. **Re-verify: storage-coord path wins instead of falling through** — the `[VFS][lockPath] backend=storage-coord` log fires with the success post-fix.
3. **Fix ftp-2 UNKNOWN bind** (carried from R134p).
4. **demo-onboard seed payload fixes** (schema drift on the residual ~80 failures).

If all four land in one cycle: product-state 🥈 Silver is mechanically achievable. Gold still categorically out until third-party-dep footprint shrinks and every vision axis holds.

---

**Report author:** Claude (2026-04-20 session). R134K is the cycle where the investigation paid off — flow engine hot path works again, BUG 13 closed on real path, product-state climbs off ❌ for the first time in the R134-series.
