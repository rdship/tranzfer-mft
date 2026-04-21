# R134N — 🥈 Silver contribution / 🥉 Bronze product-state: 90% of fix landed, coord path missed

**Commit tested:** `3e884133` (R134N)
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 35 healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134N contribution** | 🥈 **Silver** (runtime-verified) | SecurityConfig + YAML binding fix lands correctly. SPIFFE JWT-SVIDs now accepted on storage-manager's **normal storage paths** (`/api/v1/storage/store-stream`, `/api/v1/storage/stream/*`). `PlatformJwtAuthFilter SPIFFE validate result=true selfId=storage-manager callerSub=sftp-service` — smoking-gun success log fires. **But** the SecurityFilterChain's path patterns omit `/api/v1/coordination/locks/**`, so the coord-acquire path is still gated out. Close, needs one more path-pattern addition. |
| **Product-state at R134N checkpoint** | 🥉 **Bronze** (unchanged from R134K) | Coord 403 persists → VFS lockPath falls through to pg_advisory_xact_lock → Distributed vision axis still regressed. Flow engine still fires via fallback, BUG 13 regression still closed on real path (same 500 UnresolvedAddress signature). |

---

## What works now (R134N win evidence)

Two storage paths on `storage-manager` now accept SPIFFE JWT-SVIDs:

```
02:27:36.941  uri=/api/v1/storage/store-stream
              authz=Bearer eyJhbG...(355) xfwd=(none)
02:27:37.888  [PlatformJwtAuthFilter] SPIFFE validate result=true
              selfId=spiffe://filetransfer.io/storage-manager
              callerSub=spiffe://filetransfer.io/sftp-service

02:27:40.408  uri=/api/v1/storage/stream/{hash}
              authz=Bearer eyJhbG...(355) xfwd=(none)
02:27:40.481  [PlatformJwtAuthFilter] SPIFFE validate result=true
              selfId=…/storage-manager  callerSub=…/sftp-service
```

R134N's new service-local `SecurityConfig` is:
- ✅ Loaded on boot (filter-entered logs prove the chain is active)
- ✅ Wired to the `PlatformJwtAuthFilter` (SPIFFE validate fires, selfId + callerSub populated)
- ✅ Permitting the two core storage paths through the filter
- ✅ Not shadowed by any other higher-priority SecurityFilterChain

This confirms the R134M diagnosis was correct — storage-manager really did need a service-local SecurityConfig. The `platform.security.*` YAML toggles set to literal false cleanly exclude the shared InternalServiceSecurityConfig from AOT; R134N's service-local config takes its place.

---

## What's still 403 (R134N path omission)

```
[VFS][lockPath] storage-coord tryAcquire FAILED for account=... path=...
    403  on POST "http://storage-manager:8096/api/v1/coordination/locks/…/acquire"
    [no body].
    Falling through to next backend (redis → pg_advisory).

[VFS][lockPath] backend=pg_advisory_xact_lock (R134z last-resort — single-instance fallback)
```

And the storage-manager `PlatformJwtAuthFilter` count of entered log lines for `/api/v1/coordination/locks/*`: **zero**. Still getting rejected before reaching the filter.

### Why — the permit-list in R134N's SecurityConfig likely missed `/api/v1/coordination/locks/**`

Concrete next-commit fix: add one line to `com.filetransfer.storage.config.SecurityConfig`'s path matcher:

```java
// in the SecurityFilterChain bean:
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/storage/**").authenticated()
    .requestMatchers("/api/v1/coordination/locks/**").authenticated()  // ← add this
    .anyRequest().authenticated()
)
// ... and ensure PlatformJwtAuthFilter is added ahead of UsernamePasswordAuthenticationFilter for this chain
```

Or, if the chain uses a single `anyRequest().authenticated()`, verify that `PlatformJwtAuthFilter` is invoked for `/api/v1/coordination/**` as well as `/api/v1/storage/**` (path-level filter ordering).

---

## What this means for next cycle

**One path pattern away from product-state Silver.** The mechanism is proven:
- SPIFFE client attaches SVIDs ✓
- Storage-manager SecurityConfig loads ✓
- `PlatformJwtAuthFilter` validates SVIDs ✓
- Storage routes work through SPIFFE ✓

Just needs `/api/v1/coordination/locks/**` added to whatever the SecurityFilterChain authorizes. Then:
- storage-coord primary wins
- `[VFS][lockPath] backend=storage-coord …R134z primary path active` fires on success (post-R134K log placement)
- pg_advisory fallback stops being used on the hot path
- Distributed vision axis un-regresses
- **Product-state Silver becomes mechanically achievable** — BUG 13 holds, all migration infrastructure holds, all R134-series fixes runtime-verified

---

## What held from prior cycles (regression check)

- ✅ Flow engine fires end-to-end (R134K fallback)
- ✅ BUG 13 regression closed on real path — 500 UnresolvedAddress at step 1 (same signature as R134k)
- ✅ V95–V99 migrations applied (R134E)
- ✅ Outbox SQL param-6 errors = 0 (R134F)
- ✅ AS2 BOUND / FTP_WEB UNBOUND / encryption-service healthy / https-service running (R134A/B)
- ✅ 11 third-party deps on default profile (Vault retired R134v)
- ✅ Admin UI API smoke 200s (R134F-G unchanged)
- ✅ **storage-manager SPIFFE now works on storage paths** (R134N — new this cycle)

---

## Series trendline (9 cycles)

```
R134F  🥈 fix     outbox SQL unblocked
R134G  🥉 diag    wrong-close-path falsified
R134H  🥉 diag    FS/provider routing falsified
R134I  🥉 diag    channel/callback/size falsified
R134J  🥈 diag    ROOT CAUSE: storage-coord 403 visible
R134K  🥈 fix     fallback chain restructured; flow engine fires
R134L  🥈 diag    SPIFFE UNAVAILABLE on background threads (cold-boot only)
R134M  🥈 diag    R134L refuted; fix target is storage-manager SecurityConfig
R134N  🥈 fix     storage-manager SPIFFE chain lands for storage paths
R134?  → add /api/v1/coordination/locks/** to the chain → Silver product-state
```

9 cycles. One path pattern to go for product-state Silver. Gold still categorically out until third-party-dep footprint shrinks further and every axis re-verifies.

---

**Report author:** Claude (2026-04-20 session). R134N is the right architectural fix applied to 90% of the needed paths. One path-pattern addition next cycle.
