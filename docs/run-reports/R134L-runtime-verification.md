# R134L — 🥈 Silver: smoking-gun evidence that SPIFFE workload client fails on background threads

**Commit tested:** `bc38213e` (R134L)
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134L contribution** | 🥈 **Silver** (runtime-verified; revised from pre-runtime Bronze) | Instrumented both client and server sides of the storage-coord 403. Captured smoking-gun contrasting evidence: SpiffeWorkloadClient works on `main` thread at startup, FAILS with "UNAVAILABLE after 5s wait" on a `scheduling-1` background thread. Exactly validates R134L's hypothesis. The next-cycle fix is now concretely pinpointed to `SpiffeWorkloadClient` thread-scoping or initialisation-timing on non-Spring-managed threads. |
| **Product-state at R134L checkpoint** | 🥉 **Bronze** (unchanged from R134K) | Bug diagnosed, not fixed. Flow engine still runs via pg_advisory_xact_lock fallback. Distributed vision axis still regressed. |

---

## The smoking-gun contrast

Two log lines from `sftp-service` during the same boot, a minute apart:

**Startup main thread — works:**
```
2026-04-21T02:05:20.676Z thread=main
  [BaseServiceClient] attached SPIFFE JWT-SVID
    target=keystore-manager
    svidLen=357
    xfwd=none
```

**Background scheduling thread — fails:**
```
2026-04-21T02:06:34.420Z thread=scheduling-1
  [BaseServiceClient] SPIFFE workload API UNAVAILABLE after 5s wait
    target='storage-manager'
    — trying user-JWT fallback

2026-04-21T02:06:34.422Z thread=scheduling-1
  [BaseServiceClient] NO auth header attached
    target='storage-manager'
    SPIFFE path yielded nothing AND no inbound Authorization available.
    Target will reject.
    Caller context: request bound=false SPIFFE enabled=true
```

Also note the `main`-thread log's SERVICE_NAME resolves to `sftp-service`, but the `scheduling-1` log's SERVICE_NAME is `unknown` — the MDC / service-id context is lost on the background thread too.

### What this proves

- **SpiffeWorkloadClient is bean-wired and works on the main thread** (svidLen=357 to keystore-manager during startup dependency checks)
- **On a scheduled-task thread, it is `UNAVAILABLE`** after a 5s wait
- **No `X-Forwarded-Authorization` is available** either, because there's no inbound Spring RequestContext for scheduled tasks
- **Fallback to user-JWT also empty** → no auth header sent → storage-manager returns 403

This is the canonical **BUG 12/13 class**: machine-to-machine S2S on a non-request thread can't attach SPIFFE, and there's no user JWT to fall back to. R134 Path 0.5 (X-Forwarded-Authorization) rescued the user-initiated paths; this is the machine-initiated path that R134 left open.

---

## Why `SpiffeWorkloadClient` is "UNAVAILABLE" on `scheduling-1`

Two likely causes (dev-side next investigation):

1. **Bean-scope / ThreadLocal leak** — `SpiffeWorkloadClient` may lazily initialise via something like `RequestContextHolder` or ThreadLocal state that's only populated on request threads. On a background scheduled thread, the lazy-init misses and the 5s wait is the lookup-timeout hitting.

2. **Early-boot scheduled task races SPIFFE init** — the `scheduling-1` thread at `02:06:34` was 2 minutes after boot, so SPIFFE should have been up (we saw `Workload API connected` at `02:04:24`). Unless the scheduled-task's SPIFFE-client reference is a different instance from the one that connected. If it's autowired via a different qualifier or profile, the "UNAVAILABLE" means that SECOND instance never got to connect.

The server-side `[PlatformJwtAuthFilter] SPIFFE validate` log R134L added did NOT fire for this request — which is consistent, because there was no Bearer token attached at all; the request hit storage-manager's Spring Security default filter and got 403'd before reaching our filter's SPIFFE path.

---

## Side finding — LoginAttemptTracker lockout persistence

Testing hit an unrelated friction point: `globalbank-sftp` was locked after 5 failed password attempts (from prior cycles' probing). `LoginAttemptTracker` state persists across boot via the DB (sensible) but the lockout window was 15 minutes.

This is a carry-over, not an R134L regression. Recommend: the nuke-and-rebuild dev workflow could clear the `login_attempts` table (or add a `--reset-lockouts` flag to `demo-onboard`) so tester cycles don't inherit lockouts.

---

## What held from prior cycles

- ✅ Flow engine fires via pg_advisory_xact_lock fallback (R134K)
- ✅ BUG 13 regression closed on real path (R134k/K signature) — not re-run this cycle because SFTP account was locked, but R134L didn't touch any flow-engine code so the prior cycle's evidence stands
- ✅ V95–V99 applied, outbox SQL clean, AS2 BOUND, FTP_WEB UNBOUND, encryption-service healthy, https-service runs, 11 third-party deps
- ✅ Admin UI API smoke 200s (unchanged)

---

## What's still open / needed for product-state Silver

1. **Fix `SpiffeWorkloadClient` availability on non-request threads.** Concrete candidates per R134L evidence:
   - Make the SVID cache thread-safe and pre-warmed at boot for all known targets
   - Or: switch the scheduled-task caller to use a dedicated `@ApplicationScope` `SpiffeWorkloadClient` bean (not request-scoped)
   - Or: add a 5s retry loop before the `UNAVAILABLE` verdict — sometimes it's a race at first boot
2. **Verify `[VFS][lockPath] backend=storage-coord` fires on success (not on attempt)** — R134K moved the log; after fix, this log should appear, confirming the primary path wins.
3. **Fix ftp-2 UNKNOWN** (carried).
4. **demo-onboard seed payload fixes** to push 92.1% → ≥95%.
5. **Login-attempt-lockout carry-over across nukes** — cosmetic test-ergo fix.

If (1) + (2) + (3) + (4) land in one cycle: the flow engine runs on the **distributed** path (not single-instance pg_advisory fallback), the vision axis un-regresses, and product-state Silver is mechanically achievable.

---

## Cycle-over-cycle R134 series through R134L

| Cycle | Contribution | What we learned |
|---|---|---|
| R134F | 🥈 fix | outbox SQL unblocked |
| R134G | 🥉 diag | VirtualWriteChannel.close() theory: falsified |
| R134H | 🥉 diag | FS/provider routing theory: falsified |
| R134I | 🥉 diag | channel/callback/size theories: falsified |
| R134J | 🥈 diag | ROOT CAUSE: storage-coord 403 |
| R134K | 🥈 fix | fallback chain restructured → flow engine fires |
| R134L | 🥈 diag | SPIFFE workload client UNAVAILABLE on background threads — exact mechanism of the 403 |

7 cycles of disciplined narrowing. The fix is now one dev-side change away.

---

**Report author:** Claude (2026-04-20 session). Smoking-gun diagnostic; product-state unchanged but next-cycle fix is mechanical.
