# R134M — 🥈 Silver: R134L cold-boot red herring debunked; true root cause is storage-manager SecurityConfig

**Commit tested:** `5c481ba7` (R134M)
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134M contribution** | 🥈 **Silver** (runtime-verified) | The `awaitAvailable` state-snapshot logs R134M was designed to add never fired (the SPIFFE client is warm by the time callers use it, so the fast-path returns without entering `awaitAvailable`). **But** the broader runtime evidence from this cycle — with SVIDs attaching cleanly on multiple background threads yet storage-manager STILL returning 403 — actively refutes R134L's primary hypothesis and redirects the fix to the correct place: storage-manager's SecurityConfig. Silver because the evidence is diagnostic and actionable. |
| **Product-state at R134M checkpoint** | 🥉 **Bronze** | Same as R134K — flow engine fires via pg_advisory fallback, BUG 13 closed on real path, storage-coord primary still 403s. No regression, no fix. |

---

## What the runtime evidence shows

Four threads on `sftp-service`, all attaching SPIFFE SVIDs cleanly to outbound calls to `storage-manager`:

```
02:45:47.829  thread=sshd-SftpSubsystem-27364-thread-1
    [BaseServiceClient] attached SPIFFE JWT-SVID target=storage-manager svidLen=355 xfwd=none

02:45:53.453  thread=stage-intake-1
    [BaseServiceClient] attached SPIFFE JWT-SVID target=storage-manager svidLen=355 xfwd=none

02:45:58.125  thread=stage-intake-1
    [BaseServiceClient] attached SPIFFE JWT-SVID target=storage-manager svidLen=355 xfwd=none

02:46:01.501  thread=delivery-pump-TRZLPLNWBFJ2
    [BaseServiceClient] attached SPIFFE JWT-SVID target=storage-manager svidLen=355 xfwd=none
```

Every one of those requests — with a valid SPIFFE JWT-SVID attached — gets:

```
[VFS][lockPath] storage-coord tryAcquire FAILED …: 403  on POST …/api/v1/coordination/locks/…/acquire: [no body]
```

And server-side, `storage-manager` has **zero `PlatformJwtAuthFilter` entered …uri=/api/v1/coordination/locks/…` log lines**, which means our filter never sees the request. The 403 comes from Spring Security's default filter chain rejecting before our SPIFFE-aware filter runs.

---

## What this reverses vs R134L

R134L caught an `UNAVAILABLE after 5s wait` from a `scheduling-1` thread at `02:06:34`, one boot cycle earlier. That log **did not reproduce** this cycle — not on `scheduling-1`, not on any other thread. So the "SpiffeWorkloadClient fails on background threads" framing was true for that one cold-boot moment, but it's not the persistent failure mode.

The persistent failure mode is:
- Client-side: SPIFFE SVID attaches fine on every thread (including backgrounds) once warm
- Server-side: storage-manager's SecurityConfig rejects the request before reaching `PlatformJwtAuthFilter` — Spring's default filter 403s it

This is **exactly the R134k BUG 13 pattern** that was resolved for `external-forwarder-service`. R134k's fix was to add explicit `platform.security.*` YAML bindings + cover the `/api/forward/*` path in the service's SecurityFilterChain so that SPIFFE JWT-SVIDs are accepted instead of falling through to Spring's default filter.

storage-manager hasn't received the equivalent fix for its `/api/v1/coordination/locks/*` endpoint. Hence the persistent 403.

---

## Ranked next-cycle ask

**One-line ask:** apply the R134k pattern to storage-manager's SecurityConfig.

Specifically:
1. Confirm storage-manager's `application.yml` has the complete `platform.security.*` block (same tree as encryption-service's post-R134B YAML)
2. Confirm its `InternalServiceSecurityConfig` (or equivalent) permit-list covers `/api/v1/coordination/locks/**` and accepts SPIFFE JWT-SVID via `PlatformJwtAuthFilter`
3. If the filter chain uses order-based precedence, ensure `PlatformJwtAuthFilter` runs before Spring's default UserDetailsService filter on the coord paths
4. Verify at runtime: after fix, `storage-manager` logs should show `[PlatformJwtAuthFilter] entered method=POST uri=/api/v1/coordination/locks/.../acquire authz=Bearer eyJhbG...(355) xfwd=(none)` then `[PlatformJwtAuthFilter] SPIFFE validate result=true`, and the request returns 200/201 (lease issued) instead of 403

Once that lands, the `[VFS][lockPath] backend=storage-coord (R134z primary path active...)` log should fire on success (R134K moved it to the success branch), pg_advisory fallback should stop being used for the hot path, and the Distributed vision axis un-regresses.

---

## What held from prior cycles

- ✅ R134K fallback chain works end-to-end (BUG 13 regression closed on real path, 500 UnresolvedAddress signature same as R134k)
- ✅ V95–V99 applied, outbox SQL clean, AS2 BOUND, FTP_WEB UNBOUND, encryption-service healthy, https-service running, 11 third-party deps, admin-UI 200s

---

## Side note — R134M's intended `awaitAvailable` logs didn't fire

R134M added log points on entry/false-return/InterruptedException of `awaitAvailable()`. They didn't fire because the hot path is:

```
isAvailable() → (available && jwtSource != null) → true → return immediately without awaitAvailable()
```

So when SPIFFE is warm, `awaitAvailable()` is never entered. The intended diagnostic is only useful during a cold-boot window or if the client is actually in an unavailable state.

Despite the intended logs not firing, R134M ships Silver because the surrounding runtime evidence (SVIDs attaching, 403s persisting, filter not entered on storage-manager) is conclusive about the real defect location.

---

## Progress trendline

| Cycle | Contribution | Evidence gained |
|---|---|---|
| R134F | 🥈 fix | outbox SQL unblocked |
| R134G | 🥉 diag | wrong-close-path falsified |
| R134H | 🥉 diag | FS/provider routing falsified |
| R134I | 🥉 diag | channel/callback/size falsified |
| R134J | 🥈 diag | ROOT CAUSE visible: storage-coord 403 |
| R134K | 🥈 fix | fallback chain restructured — flow engine fires |
| R134L | 🥈 diag | SPIFFE UNAVAILABLE on background threads (cold-boot only, per R134M below) |
| R134M | 🥈 diag | R134L cold-boot race refuted; true root cause is storage-manager SecurityConfig rejection |

Eight cycles. Root cause now concretely "apply R134k pattern to storage-manager."

---

**Report author:** Claude (2026-04-20 session). R134L's scheduling-1 evidence was accurate for that moment but not the persistent defect. R134M redirects the fix to storage-manager's server-side SecurityConfig — same class as R134k's external-forwarder fix.
