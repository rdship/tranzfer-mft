# R134j — ❌ No Medal: regression flow works, BUG 13 still open on flow-engine path

**Commit verified:** `0315a372` — *R134j: bootstrap INBOUND+FILE_DELIVERY regression flow*
**Branch:** `main`
**Date:** 2026-04-19 (Apr 20 UTC)
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 35/36 healthy
**Verdict:** ❌ **No Medal** — R134j's own deliverable (the regression flow fixture) works perfectly; exercising it confirms BUG 13 is **still open** on the flow-engine path, and R134's enriched WARN does **not** fire there.

---

## Why No Medal

R132 and R133 were called No Medal because "flagship BUG 12+13 still 403". This cycle meets exactly that criterion: R134j succeeded at providing a reproducer, and the reproducer proves the real-path S2S hop still returns `403  no body`. The headline fix (R134's SPIFFE WARN + Path 0.5) is only half-verified — user-initiated S2S hops authorise via X-Forwarded-Authorization as designed (confirmed by a direct curl below), but the flow-engine-initiated hop — which never has a user JWT — still gets 403 silently.

R134j's *own* code change (adding `SFTP Delivery Regression` to PlatformBootstrapService) is correct and working. If we were grading R134j in isolation it'd be 🥈 Silver. But medals gate on the flagship bug status, and the flagship is still open, so ❌.

---

## What R134j added and what it proved

### Regression flow seeded correctly

```sql
SELECT name, direction, priority, steps FROM file_flows WHERE name='SFTP Delivery Regression';
name:       SFTP Delivery Regression
direction:  INBOUND
priority:   10   (beats Mailbox Distribution's 999 catch-all ✓)
steps:      [{"type":"CHECKSUM_VERIFY","order":0},
             {"type":"FILE_DELIVERY","order":1,
              "config":{"deliveryEndpointIds":"22563c77-9b15-46c5-984c-2e44f7a36ac0"}}]
```

Source = `globalbank-sftp` (VIRTUAL per R134i), delivery target = `partner-sftp-endpoint` (→ `sftp.partner-a.com`, non-existent but that's fine — S2S auth happens **before** the outbound SFTP attempt).

### One upload fires the real path

```
sftp -P 2222 globalbank-sftp@localhost          (password: partner123)
  put /tmp/test.regression /inbox/bug13-real-<ts>.regression

→ flow execution 18f9bafc-... created
  Step 0 CHECKSUM_VERIFY    OK
  Step 1 FILE_DELIVERY      FAILED
      error: "FILE_DELIVERY failed for all 1 endpoints:
              partner-sftp-endpoint: 403  on POST request for
              \"http://external-forwarder-service:8087/api/forward/deliver/22563c77-9b15-46c5-984c-2e44f7a36ac0\": [no body]"
```

This is the canonical BUG 13 symptom: the flow engine's outbound call to external-forwarder-service is 403'd.

---

## The troubling log silence

Only one log line fires on `external-forwarder-service` for this request (correlationId `855d4d58`):

```
[PlatformJwtAuthFilter] entered
  method=POST uri=/api/forward/deliver/22563c77-9b15-46c5-984c-2e44f7a36ac0
  authz=Bearer eyJhbG...(370)   ← SPIFFE JWT-SVID (370 chars; user JWTs are ~286)
  xfwd=(none)                    ← no user JWT forwarded; this is a flow-engine-initiated call
```

Then **nothing**. No:
- `authorized via X-Forwarded-Authorization` (expected — `xfwd=(none)`)
- `authorized via SPIFFE` (expected to print if SPIFFE passes)
- `[SPIFFE] JWT-SVID rejected ... actual-aud=... sub=... exp=...` (R134's enriched WARN — designed to print on SPIFFE rejection)

The response comes back as 403 with no body. **R134's new diagnostic didn't run on this path.**

### Why the WARN isn't firing

Big hint in forwarder-service startup logs:

```
Using generated security password: ad5a341f-1e59-4f04-b52f-7c5128212b8e
This generated password is for development use only.
Your security configuration must be updated before running your application in production.
```

Spring Boot's `UserDetailsServiceAutoConfiguration` activated because no explicit `SecurityFilterChain` covers the `/api/forward/deliver/**` paths for SPIFFE-bearing requests. The request path looks like:

1. `PlatformJwtAuthFilter` enters, logs the entry line, inspects `authz` and `xfwd`
2. `xfwd` is empty, so Path 0.5 doesn't authorise
3. Filter probably delegates to the default chain (SPIFFE JWT-SVID isn't handled there)
4. Spring's default 403 fires — **before** the filter reaches its own SPIFFE validation branch
5. Result: 403 no body, no WARN, no decision log

### Confirmation: Path 0.5 works on user-initiated hops

Same endpoint, same body, but with a user JWT in `X-Forwarded-Authorization`:

```
curl -X POST http://localhost:8087/api/forward/deliver/22563c77-... \
     -H "X-Forwarded-Authorization: Bearer $ADMIN_JWT"
→ HTTP 500 (Internal Server Error: business logic fails on missing multipart)
```

500 not 403 = the filter authorised (via X-Forwarded-Authorization Path 0.5) and the request made it into `ForwarderController`. That half of R134's fix works.

The other half — the SPIFFE-only path for flow-engine hops — doesn't.

---

## What this means for BUG 13

The bug class R133 and R134 have been attacking is a two-subcase problem:

| Caller pattern | User JWT present? | R134 behaviour today |
|---|---|---|
| Admin-UI → onboarding → config-service → encryption-service (BUG 12) | yes (forwarded) | ✅ CLOSED — Path 0.5 authorises, R134h proof is runtime-real |
| Flow engine → external-forwarder (BUG 13) | no (machine-to-machine) | ❌ STILL 403 — SPIFFE JWT-SVID present but never validated |

R134's enriched WARN was supposed to be the diagnostic that makes BUG 13 trivial to attribute (audience vs signing vs expiry). That diagnostic **runs on user-initiated rejections** but **not on flow-engine-initiated rejections** because the 403 happens earlier in the chain than `SpiffeWorkloadClient.validate()`.

### Suggested next step (for the dev, not me)

Likely the fix is on `external-forwarder-service`'s Spring Security config: add (or correct) a `SecurityFilterChain` that:

1. Recognises the SPIFFE JWT-SVID in `Authorization:` (not just `X-Forwarded-Authorization:`)
2. Routes it to `SpiffeWorkloadClient.validate()` directly — not to Spring's default UserDetails chain
3. On rejection, allows the existing enriched WARN to fire

Or add the service to whatever SPIFFE-friendly security setup the *other* services already use for S2S ingress (onboarding-api, encryption-service, etc. — BUG 12 authed via the user path there, but presumably they also accept pure SPIFFE). Comparing the SecurityConfig of `external-forwarder-service` vs one of the services that already handles SPIFFE S2S traffic cleanly would show the delta.

---

## Other verifications this cycle

- R134i's Gap A fix still holds: `globalbank-sftp` is VIRTUAL, SFTP upload to `/inbox` works without any manual `mkdir`, VirtualSftpFileSystemProvider shows in `ls`.
- R134j's regression flow respects priority correctly (10 beats Mailbox Distribution's 999 — the catch-all is NOT the one that fired).
- `CHECKSUM_VERIFY` step passes on a real file now — so the fix to Gap C (VFS-only flow-work) held across both steps.

---

## Environment

- Commit: `0315a372`
- Accounts (bootstrap only, no demo-onboard): 6, all VIRTUAL ✓
- Flows on fresh stack: 7 (6 bootstrap + 1 new `SFTP Delivery Regression`) ✓
- SFTP listeners bound: 2 (sftp-1:2222, sftp-2:2223)
- Flow execution fired: 1 — `SFTP Delivery Regression` FAILED at Step 1 (FILE_DELIVERY) with 403, exactly as designed to expose BUG 13

---

**Report author:** Claude (2026-04-19 session). Medal grade: ❌ No Medal — per the rubric (flagship bug still open on the primary path).
