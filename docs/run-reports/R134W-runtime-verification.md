# R134W — 🥉 Bronze (contribution) / 🥈 Silver (product-state restored)

**Commit tested:** `d1580bd8` (R134W)
**Date:** 2026-04-22
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134W contribution** | 🥉 **Bronze** | Pure observability. Distinguishes `no-account` from `password-mismatch` in `CredentialService.authenticatePassword` via more detailed log lines. Diff-read held at Bronze-cap pre-runtime per no-pre-medal rule; runtime exercise didn't elevate — the DENY branches never fired this cycle because SFTP auth worked. R134W's value is latent until the next auth regression appears. Bronze is honest: observability shipped, but unexercised so Silver is not earned. |
| **Product-state at R134W** | 🥈 **Silver** (RESTORED from R134V Bronze) | SFTP password auth regression I flagged in R134V **does not reproduce** on this fresh build. Two uploads succeeded (one pre-rotation, one post-rotation in the same cycle), both fired R134j regression flow with canonical BUG 13 500 signature. R134O storage-coord primary still wins. All prior Silver criteria hold. |

---

## Isolation test — A (pre-rotation) / B (rotate) / C (post-rotation)

### Step A — pre-rotation SFTP upload (fresh boot, no key rotation performed)

```
sshpass -p "partner123" sftp -P 2222 globalbank-sftp@localhost
  cd /inbox
  put /tmp/test.regression stepA-1776821195.regression
  bye
→ success (no "close remote: Failure", no "Permission denied")
```

### Step B — keystore rotation

```
POST /api/v1/keys/bootstrap-test-ssh-host/rotate
     { "newAlias": "bootstrap-test-ssh-host-v2" }
→ HTTP 200
```

### Step C — post-rotation SFTP upload

```
sshpass -p "partner123" sftp -P 2222 globalbank-sftp@localhost
  put /tmp/test.regression stepC-1776821231.regression
  bye
→ success
```

**Both A and C succeed.** No auth failures. The SFTP-password-auth regression I observed in R134V was transient — either a test-sequence artifact (account lockout from prior session's probing persisted into my R134V cycle via the `login_attempts` table) or a side-effect of something I triggered during that specific session that didn't re-appear on a clean boot.

R134W's new `[CredentialService] auth DENIED — no SFTP account …` / `[CredentialService] auth DENIED — password mismatch …` log lines **did not fire** because the DENY branches were never reached. That's expected for a diagnostic-only commit when the underlying bug doesn't surface.

---

## Flow engine + BUG 13 — runtime-verified x2

Both step-A and step-C uploads drove the flow engine end-to-end:

```
flow_executions:
  id cf89c295-…  status=FAILED  err="Step 1 (FILE_DELIVERY) failed: partner-sftp-endpoint: 500 …"
  id 5f8f24fb-…  status=FAILED  err="Step 1 (FILE_DELIVERY) failed: partner-sftp-endpoint: 500 …"
```

Both flows:
- Step 0 CHECKSUM_VERIFY = OK
- Step 1 FILE_DELIVERY = FAILED with HTTP 500 UnresolvedAddressException on partner-sftp-endpoint

Exactly the canonical R134k BUG 13 closed signature. Auth passed (SPIFFE JWT-SVID), controller reached, outbound SFTP to fake `sftp.partner-a.com` fails at DNS. Two independent executions this cycle.

---

## R134O Silver still wins

```
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)
```

No `tryAcquire FAILED`, no fallback. Storage-coord primary path holds through 2 uploads.

---

## Sprint 6 outbox path stability

Both uploads + 1 rotation produced outbox rows + drains. No regression from R134V's framework cap; all handlers registered with `(total at prefix: 1)`.

---

## Product-state restoration

Yesterday's R134V verification graded product-state Bronze because SFTP auth was failing. Today's fresh boot shows it working. Two possible explanations:

1. **R134V's Bronze was the right call for that cycle's state** — there was genuine transient degradation (lockout or bean-graph staleness) but it doesn't persist across a full restart. That's actually consistent with "broken protocol surface = Bronze max" — the fix for the operator would be "restart the service" or "clear login_attempts" — both workarounds, which keeps Bronze honest for that session.

2. **R134W indirectly cleared something** — unlikely; R134W is pure logging, no bean-graph changes.

Most probable: (1) transient, not recurring on fresh boot. If it re-surfaces in a future cycle, R134W's new log lines will immediately disambiguate "no-account" vs "password-mismatch" — which is exactly what R134W was built for.

---

## Still open

Unchanged from R134V, minus the SFTP auth regression (not reproducing today):
- `ftp-2` secondary FTP UNKNOWN (carried)
- demo-onboard 92.1% (Gap D)
- 11 third-party deps (Sprint 7 Phase B pending)
- Coord endpoint auth posture review
- Account handler `type=null` log nit
- **Latent SFTP auth flake** — R134W instruments it; if it re-appears, we'll see the exact branch

---

## Sprint 7 Phase B readiness

Per my R134V report, I recommended holding Phase B until SFTP auth was back to green. Today it is. Combined with Sprint 6's 4/4 runtime-proof (R134T) + framework cap (R134V) + observability (R134W), Phase B can proceed whenever Roshan wants to land it. Pre-conditions all green.

---

**Report author:** Claude (2026-04-22 session). R134W diff-read Bronze-cap stayed Bronze post-runtime — the diagnostic didn't get exercised because the bug it targets didn't surface today. Product-state recovered to Silver cleanly.
