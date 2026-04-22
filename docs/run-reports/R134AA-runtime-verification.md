# R134AA — 🥈 Silver (contribution) / 🥉 Bronze (product-state): SSE fix works E2E, but SFTP auth genuinely regressed

**Commit tested:** `bd97706a` (R134AA)
**Date:** 2026-04-22
**Environment:** fresh nuke (`docker compose down -v` + `docker system prune -af --volumes` → 9.5 GB reclaimed) → `mvn package -DskipTests` (BUILD SUCCESS) → `docker compose up -d --build` → core services healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134AA contribution** | 🥈 **Silver** (runtime-verified E2E) | The one-line `@PreAuthorize("permitAll()")` method-level override on `broadcastActivityEvent` closes the bug R134Z exposed. Full end-to-end SSE path now works: scheduled tick → broadcast → connected client receives. Concrete evidence: `polled 1 rows → 1 successful SSE deliveries` (M>0 for the first time since R134Y migration), zero `AuthenticationCredentialsNotFoundException`, SSE client received `event:transfer-completed` with payload. Diff-read Bronze-cap pre-runtime per no-pre-medal rule; Silver earned cleanly post-runtime. |
| **Product-state at R134AA** | 🥉 **Bronze** (downgraded from R134Z Silver) | R134W's `[CredentialService] auth DENIED — password mismatch` instrumentation FIRED THIS CYCLE for `globalbank-sftp`. Cross-verified: the stored bcrypt hash verifies `partner123` correctly in Python's `bcrypt.checkpw` but Java's `CredentialService.authenticatePassword` returns DENIED for the same pair. This is the **real** R134V regression — not a client-side hostkey flake this time. R134AA's code doesn't touch auth, so this is either a pre-existing latent bug that R134Y/R134Z happened to not trip, or freshly surfaced by some boot-order condition. Blocks BUG 13 real-path verification on SFTP. Rubric: broken protocol surface = Bronze max. |

---

## 1. R134AA fix — E2E proof

### Log contract — first M>0 since R134Y migration

```
03:46:03 INFO  [R134Z][ActivityStreamConsumer]
              polled 1 rows → 1 successful SSE deliveries
              since cursor=2026-04-22T03:42:05.679Z
```

The `1 successful SSE deliveries` is what R134AA was shipped to make possible. Pre-R134AA this number was always 0 because `@PreAuthorize(Roles.VIEWER)` at class-level wrapped `broadcastActivityEvent` via AOP proxy, and the `scheduling-1` thread had no SecurityContext. R134AA's method-level `@PreAuthorize("permitAll()")` takes precedence and the call goes through.

### Zero auth exceptions

```
grep -cE 'AuthenticationCredentialsNotFound' onboarding-api logs → 0
grep -cE '[R134Z][ActivityMonitor] SSE send failed'            → 0
```

The exception R134Z made visible is no longer firing.

### SSE client received the event

Connected a raw `curl -N` SSE client to `/api/activity-monitor/stream?token=<jwt>`, inserted a test `file_transfer_records` row (TRZR134AA001), and received:

```
event:transfer-completed
data:{"trackId":"TRZR134AA001","eventType":"COMPLETED","status":"COMPLETED","updatedAt":1776829562.555358000}
```

End-to-end. Scheduled DB-poll → broadcastActivityEvent (now passes AOP) → emitter.send → SSE client. The `ActivityMonitorEntry.builder()` code path inside `ActivityMonitorController.broadcastActivityEvent` that was previously unreachable is now fully exercised.

### Method-level precedence is the right approach

The SSE-registration endpoint (`/api/v1/activity/stream` / `/api/activity-monitor/stream`) still carries its own `@PreAuthorize("permitAll()")` + manual JWT validation of the query-param token, so this override **does not** relax the user-facing auth surface — only the internal broadcast helper, which shouldn't have been security-gated in the first place since it's called from untrusted scheduled threads with no principal.

---

## 2. SFTP auth — R134V regression is REAL and server-side

### Symptom

```
sshpass -p "partner123" sftp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -P 2222 globalbank-sftp@localhost
→ globalbank-sftp@localhost: Permission denied (password,keyboard-interactive,publickey)
```

### Server-side — R134W instrumentation FIRES

```
SFTP password auth attempt: username=globalbank-sftp ip=/172.18.0.1:57360 listener=sftp-1
[CredentialService] auth DENIED — password mismatch for
    username='globalbank-sftp' accountId=73c3a122-14ca-4ea9-9b28-d3bb4def1674
    storedHashLen=60 active=true instance='sftp-1'
LOGIN_FAILED … reason=invalid_credentials
```

R134W's whole point was to distinguish "no-account" from "password-mismatch" branches when the R134V bug resurfaced. It did. The `password-mismatch` branch is what fired.

### Cross-check — hash and password DO match

Pulled the stored hash directly:

```
$ docker exec mft-postgres psql ... -c "SELECT password_hash FROM transfer_accounts
                                        WHERE username='globalbank-sftp'"
$2a$10$c6LaNogRZAiZp./SBptcv.iHrCWe9kY/pEaaTlrZXiplHg3FOAFNG

$ python3 -c "import bcrypt; print(bcrypt.checkpw(b'partner123',
    b'\$2a\$10\$c6LaNogRZAiZp./SBptcv.iHrCWe9kY/pEaaTlrZXiplHg3FOAFNG'))"
True   ← Python bcrypt says partner123 matches
```

But Java's `CredentialService.authenticatePassword` returns DENIED for the same pair (`storedHashLen=60 active=true`). **The stored hash verifies correctly against `partner123` in a reference bcrypt implementation but not in the server's.** This is the real R134V regression — NOT client-side.

### Divergence from R134Y/R134Z

My R134Y report hypothesized the R134V flake was purely client-side (macOS OpenSSH hostkey distrust). R134Y and R134Z's SFTP uploads both succeeded. R134AA's does not — on the same seed data, with the same client invocation, on a clean nuke. So the bug is intermittent AND real. R134AA didn't introduce it (diff only touches `ActivityMonitorController` + docker-compose version strings). My R134Y memory ("SFTP hostkey client-side flake") needs an amendment: **the hostkey issue is a separate, additional client-side failure mode; there is ALSO a real server-side intermittent Java-bcrypt rejection.** The two can co-occur or surface independently.

### Dev diagnostic ask

1. Is the BCrypt version in Java Spring Security the same `$2a$` variant the seed script produces? If the seed uses Python's `bcrypt` and the Java side uses Spring Security's `BCryptPasswordEncoder`, a subtle variant mismatch (`$2a$` vs `$2b$` vs `$2y$`) could cause intermittent rejection based on platform-specific byte encoding.
2. Is there any password-transformation layer (lowercasing, trim, NFC/NFD Unicode normalization) between the wire and the bcrypt.check call? R134W logs the hash length (60) and success state but not the raw bytes of the received password.
3. Trace a single failed attempt down to the actual `BCrypt.checkpw` call and log what byte-string is being passed.

R134W gives us the "WHICH branch" — next instrumentation should give us "WHAT bytes". Candidate R134AB observability commit.

### Impact on R134AA product-state

- BUG 13 real-path regression can't be exercised this cycle (R134j flow needs SFTP upload)
- R134O storage-coord "backend=storage-coord" log did fire during the failed SFTP attempt (the connection opens, auth fails downstream), so the VFS path itself is fine; I just couldn't run a full flow
- All Sprint-6 + Sprint-7 + Sprint-8 carry-forward evidence outside SFTP is green (see §3)

Per rubric: "any broken protocol surface = Bronze max." SFTP password auth is broken on a partner account. Bronze.

---

## 3. Carry-forward checks (non-SFTP)

### Storage-coord primary (R134O) — backend log during failed SFTP attempt

```
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)
```

### Sprint-6 outbox-only (R134X holds)

4 events triggered via API:

```
event_outbox:
  account.updated         | 1
  flow.rule.updated       | 1
  keystore.key.rotated    | 1
  server.instance.created | 1

RabbitListener activity on sprint-6 services over 2 minutes:
  sftp-service    : 0
  ftp-service     : 0
  ftp-web-service : 0
  as2-service     : 0
```

### NotificationEventConsumer dual-transport (R134Y holds)

```
[R134Y][NotificationEventConsumer][outbox] drains fired: 4 (one per prefix)
```

### RabbitMQ slim (R134Y holds)

```
notification.events    0  1 consumer
file.upload.events     0  20 consumers
```

---

## Stack health

Core services healthy: onboarding-api, sftp-service, notification-service, config-service, keystore-manager, storage-manager. Typical 33-35 / 35 pattern.

---

## Still open

Promoted severity:
- **SFTP password auth regression — REAL, server-side, intermittent.** Java bcrypt verification returns false for a partner account whose stored hash verifies correctly against the same password in a reference implementation. R134W instrumentation (`password-mismatch` branch) fires as designed. R134V's original observation is vindicated. Demands a dev trace through `CredentialService.authenticatePassword` to capture the exact bytes being compared.
- **My R134Y/Z memory about "client-side hostkey flake" was incomplete.** The hostkey issue is a real client-side failure mode on macOS OpenSSH; the bcrypt rejection is a separate, real, server-side failure mode. Updating memory.

Unchanged:
- Redis retirement (R134AA…R134AE sequence deferred)
- `ftp-2` UNKNOWN, demo-onboard 92.1%, coord auth posture review
- Account handler `type=null` log nit
- R134Z-newly-visible (now CLOSED by R134AA): SSE broadcast path couldn't run in scheduled context

---

## Sprint series state

| Tag | What shipped | Runtime | Notes |
|---|---|---|---|
| R134T | Sprint 6 all 4 events dual-path | ✓ | |
| R134U | Sprint 7A: 3 publishers outbox-only | ✓ | |
| R134V | Multi-handler cap | ✓ | First exercised at R134X |
| R134W | SFTP auth DENY observability | ✓ | **FIRED at R134AA — job done** |
| R134X | Sprint 7B: account + @RabbitListener deletions | ✓ | |
| R134Y | Sprint 8: slim RabbitMQ to file.uploaded | ✓ | |
| R134Z | SSE observability unswallow | ✓ | Exposed auth-context bug |
| **R134AA** | **Close R134Z-exposed bug via method-level permitAll** | **✓** | SSE E2E works |

8 of 8 atomic R-tags runtime-proven. The Sprint 6→8 arc landed cleanly. Now back on the SFTP auth trail.

---

**Report author:** Claude (2026-04-22 session). R134AA contribution Silver — surgical one-line fix, E2E SSE delivery verified (SSE client actually received the broadcast). Product-state Bronze — SFTP password-auth regression is real this cycle; R134W's instrumentation justified its existence by firing precisely when the bug resurfaced. Diff-read Bronze-capped pre-runtime per no-pre-medal rule; contribution Silver earned post-runtime, product-state honestly Bronze.
