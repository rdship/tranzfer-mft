# R134O — 🥈 Silver contribution / 🥈 Silver product-state: first non-Bronze of the R134 series

**Commit tested:** `2437c386` (R134O)
**Date:** 2026-04-21
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy at steady-state

---

## Historic verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134O contribution** | 🥈 **Silver** (runtime-verified) | Correct root cause — `StrictHttpFirewall` rejecting `%2F` before the filter chain — and a targeted, scoped fix (service-local `HttpFirewall` @Bean on storage-manager only, not global). Ships end-to-end on the first runtime cycle. |
| **Product-state at R134O** | 🥈 **Silver** — **first Silver of the R134-series** | storage-coord primary path WINS on file upload (not fallback). `[VFS][lockPath] backend=storage-coord (R134z primary path active)` log fires from the success branch, with no preceding "tryAcquire FAILED" warning. Distributed vision axis is un-regressed. BUG 13 still closed on real path. Flow engine fires cleanly. All prior cycle fixes hold. Remaining open items are follow-ups (ftp-2 UNKNOWN, demo-onboard 92.1%, 11 third-party deps — all for further cycles, none of them block Silver). |

---

## The end-to-end chain that finally runs on the distributed lock path

**1. SFTP upload:**
```
sftp.audit: event=UPLOAD username=globalbank-sftp
  filename=/r134O-1776766624.regression bytes=? success=true
```

**2. VFS provider hands off to writeFile:**
```
[sftp-accessor] openFile → VirtualSftpPath
[vfs-provider] newByteChannel returned=VirtualWriteChannel callbackPresent=true
[vfs-channel] close() about to call vfs.writeFile (INLINE)
```

**3. storage-coord primary WINS** (no fallback this time):
```
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)
```
This log is from the **success branch** R134K moved there. In every prior cycle (R134K, R134L, R134M, R134N) we only saw the "tryAcquire FAILED … Falling through" WARN. Now: clean success.

**4. storage-manager sees the coord requests through the filter chain:**
```
[PlatformJwtAuthFilter] entered method=POST uri=/api/v1/coordination/locks/.../acquire
[PlatformJwtAuthFilter] entered method=DELETE uri=/api/v1/coordination/locks/...
```
`StrictHttpFirewall` no longer rejects the URL-encoded slashes that `StorageCoordinationClient` produces when encoding lock keys. R134O's service-local `HttpFirewall` bean with `allowUrlEncodedSlash(true)` is the right surgical scope.

**5. Flow engine runs end-to-end:**
```
SFTP Delivery Regression status=FAILED
  CHECKSUM_VERIFY  OK
  FILE_DELIVERY    FAILED: partner-sftp-endpoint: 500 on POST
```

**6. BUG 13 regression still closes on real path:**
```
external-forwarder-service:
  [PlatformJwtAuthFilter] entered method=POST uri=/api/forward/deliver/...
    authz=Bearer eyJhbG...(370) xfwd=(none)
  Delivering r134O-...regression (40 bytes) -> 'partner-sftp-endpoint' [SFTP://sftp.partner-a.com:22]
  Delivery attempt 1/3 / 2/3 / 3/3 failed: UnresolvedAddressException
  Delivery failed after 3 attempt(s): UnresolvedAddressException
```

Same HTTP 500 `UnresolvedAddressException` signature as R134k. Auth passed (SPIFFE JWT-SVID), controller reached, outbound SFTP to the intentionally-fake `sftp.partner-a.com` test endpoint fails at DNS resolution as designed.

---

## What's un-regressed vs prior cycles

| Axis | State before R134O | After R134O |
|---|---|---|
| **Distributed** (VFS lock works cross-pod) | regressed — running on single-instance `pg_advisory_xact_lock` (R134K fallback) | **un-regressed** — distributed `storage-manager platform_locks` table-row lock |
| **Self-dependent** | holding at 11 third-party deps | unchanged |
| **Resilience** | fallback chain works (R134K) | fallback chain still works AND primary path no longer needs it |
| **Fast** | unchanged | marginal positive — one less HTTP call fails per lock acquire |
| **Independence** | storage-manager's API usable only for /storage paths | storage-manager's coord endpoint now usable by VFS |
| **Fully integrated end-to-end** | flow engine ran but on fallback | flow engine runs on designed distributed path |

---

## Retroactive grade holds

I'd previously corrected R134z to 🥉 Bronze (in R134J) because its "primary path active" log was misleading — fired on attempt, not success. R134K moved the log to the success branch. **Today, for the first time ever, that log fires from the success branch on a real runtime call.** Under the strict bar, R134z's core design was sound all along; only the missing pieces (R134N service-local SecurityConfig + R134O HttpFirewall + R134K fallback) made it usable. R134z stays 🥉 Bronze — the design deserves Silver but the prior cycles' primary-path misattribution justifies leaving it.

---

## Series progression — 10 cycles to Silver

```
R134F  🥈 fix     outbox SQL unblocked
R134G  🥉 diag    wrong-close-path falsified
R134H  🥉 diag    FS/provider routing falsified
R134I  🥉 diag    channel/callback/size falsified
R134J  🥈 diag    ROOT CAUSE visible: storage-coord 403
R134K  🥈 fix     fallback chain restructured — flow engine fires
R134L  🥈 diag    cold-boot SPIFFE race (later refuted)
R134M  🥈 diag    true target: storage-manager SecurityConfig
R134N  🥈 fix     service-local SecurityConfig — 90% (storage paths only)
R134O  🥈 fix     HttpFirewall allows %2F — coord paths land — 🥈 SILVER product-state
```

5 diagnostic cycles + 4 structural fixes + 1 diagnostic-refutation = 10 cycles. The methodical narrowing paid off: every hypothesis was either confirmed or falsified with a logged decision, and every fix was scoped and targeted rather than speculative.

---

## Whole-platform state at R134O

- ✅ All 22+1 microservices running (https-service "health: starting" per-cycle timer thing; functionally up)
- ✅ V95–V99 migrations applied (R134E)
- ✅ Outbox SQL errors = 0 (R134F)
- ✅ AS2 BOUND, FTP_WEB UNBOUND, encryption-service healthy (R134A/B)
- ✅ https-service running (R134A)
- ✅ 11 third-party deps (Vault retired — R134v)
- ✅ Caffeine L2 cache active (R134x + R134C)
- ✅ Flow engine fires end-to-end on distributed path (R134K+N+O)
- ✅ BUG 13 closed on real path (R134k; re-verified every cycle since)
- ✅ Admin UI API smoke 200s (unchanged)
- ⚠️ ftp-2 secondary FTP UNKNOWN (carried from R134p; not addressed in R134-series so far)
- ⚠️ demo-onboard 92.1% (Gap D partial — 86/1084 failed)
- ⚠️ Auth/authz on coord path: `authz=(none) xfwd=(none)` seen in filter logs — R134N's permit-list likely lets /coordination/locks/** pass without SPIFFE validation. That's a valid internal-trust posture but worth documenting explicitly if that was the intent. (If intent was to require SPIFFE, it's a residual gap.)

---

## Why not Gold

Gold is categorically out under the strict bar:
- 11 third-party runtime deps still on default profile (Self-dependent axis not satisfied)
- ftp-2 UNKNOWN (Integrated axis — any broken listener blocks Gold)
- demo-onboard 92.1% (Quality metric < 100%)
- coord endpoint may permit without SPIFFE validation (Distributed axis trust posture ambiguous)

Gold requires every axis perfect + every test 100% + zero third-party deps + zero open items. Silver is the correct grade for this cycle.

---

## Next-cycle opportunities (post-Silver)

For higher Silver density or approach to Gold:
1. **ftp-2 UNKNOWN** — lowest hanging fruit, carried for many cycles. Likely a similar AOT/SecurityConfig issue per-service pattern.
2. **demo-onboard schema drift** (Gap D) — push 92.1% toward 100%. Split into D1 (wrong-URL, fixed R134p) / D2 (duplicates, partially fixed) / D3 (scheduled tasks, partial).
3. **Sprint 6-9 of R134q retirement plan** — keep chipping at the third-party-dep count.
4. **Coord endpoint auth posture review** — if SPIFFE validation is desired on `/api/v1/coordination/locks/**`, explicitly add `.requestMatchers("/api/v1/coordination/locks/**").hasAuthority("ROLE_INTERNAL")` or similar.

---

**Report author:** Claude (2026-04-21 session). 10 cycles of disciplined narrowing paid off — first product-state Silver of the R134-series.
