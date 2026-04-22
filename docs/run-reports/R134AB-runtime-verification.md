# R134AB — 🥉 Bronze (contribution) / 🥈 Silver (product-state): self-test fires, mismatch branch latent, SFTP auth clean this cycle

**Commit tested:** `5774bbd5` (R134AB)
**Date:** 2026-04-22
**Environment:** fresh nuke → `mvn package -DskipTests` (BUILD SUCCESS) → `docker compose up -d --build` → core services healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134AB contribution** | 🥉 **Bronze** (partial runtime exercise) | The `@PostConstruct` BCrypt self-test **fires and passes** at boot: `encoderClass=BCryptPasswordEncoder encodedPrefix='$2a$10$' roundTripOk=true`. That branch of the R134AB change is runtime-proven and actively protective — any future BCrypt classpath/library regression would fail at boot and stop auth at the source. **But the primary diagnostic branch — the augmented `auth DENIED — password mismatch` line with `passwordLen` / `passwordSha256Head` / `storedHashPrefix` — is latent this cycle because SFTP auth succeeded.** Per the R134W precedent ("observability that ships but the main DENY branches didn't fire = Bronze"), R134AB sits at the same tier: partial exercise, main claim untested. Diff-read Bronze-cap pre-runtime per no-pre-medal rule; runtime didn't elevate because Mode B didn't reproduce. |
| **Product-state at R134AB** | 🥈 **Silver** (restored from R134AA Bronze) | SFTP password auth **worked this cycle** — Mode B (real server-side Java-bcrypt rejection) did not reproduce. BUG 13 canonical signature fires end-to-end. R134O storage-coord primary wins. All Sprint-6 events outbox-only with zero RabbitListener activity on sftp/ftp/as2 (ftp-web's 2 lines are `FileUploadEventConsumer` on `file.uploaded` — the R134Y-designed surviving channel, NOT a Sprint-6 regression). R134Y RabbitMQ slim holds. Silver recoverable under the "non-reproducing intermittent flake = restored Silver" precedent from R134W. |

---

## 1. R134AB self-test fires at boot

```
[R134AB][CredentialService] BCrypt self-test:
    encoderClass=BCryptPasswordEncoder
    encodedPrefix='$2a$10$'
    roundTripOk=true
```

`roundTripOk=true` means the Spring Security `BCryptPasswordEncoder` on the sftp-service classpath correctly encodes-then-matches a known probe string. If this had been `false`, all password authentications would have been DOA — and R134AB's whole point is that the problem would be caught at boot instead of investigated on every subsequent auth failure. **This is valuable protective observability even without a Mode B trip.**

The encoder identity and variant are now also visible:
- `$2a$10$` → BCrypt variant 2a, cost factor 10
- matches the `storedHashPrefix` that R134W would log in the mismatch branch

If a future cycle shows `encodedPrefix='$2b$12$'` or similar with `roundTripOk=true` but a stored hash that's still `$2a$10$`, that's direct evidence of seed-time / runtime encoder divergence — and R134AB's self-test line makes that visible without needing a failed login to spot it.

---

## 2. Mode B did not reproduce this cycle — SFTP auth succeeded

```
sshpass -p "partner123" sftp -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null -P 2222 globalbank-sftp@localhost
  put /tmp/r134ab.regression …
  → Uploading … (success)
```

R134W's `[CredentialService] auth DENIED` instrumentation count over the 2-minute window: **0**. R134AB's augmented mismatch log fields (`passwordLen`, `passwordSha256Head`, `storedHashPrefix`) are **not exercised this cycle** because the DENY branch wasn't reached.

This matches tester-ask option (a) from the R134AB commit message:

> (a) Silver-path success (R134V was transient and has stopped reproducing), OR
> (b) The R134AB-augmented mismatch line, with…

We got (a). Mode B is confirmed intermittent: tripped at R134AA, not at R134AB, both on clean nuke with no auth-touching code changes between them. The instrumentation stays loaded as a dormant trap for the next trip.

---

## 3. Product-state Silver holds end-to-end

### BUG 13 canonical signature

Flow execution from the successful SFTP upload:

```
flow_executions:
  id 6e3b3bcd-674b-42f5-b13c-738c75e9a178  status=FAILED
  err="Step 1 (FILE_DELIVERY) failed: FILE_DELIVERY failed for all 1
       endpoints: partner-sftp-endpoint: 500 on POST request for
       http://external-forwarder-service:8087/api/forward/deliver/…"
```

R134k canonical signature.

### R134O storage-coord primary wins

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

RabbitListenerEndpointContainer# over 2-minute window:
  sftp-service    : 0
  ftp-service     : 0
  ftp-web-service : 2   ← file.uploaded (R134Y-designed survivor; NOT Sprint-6)
  as2-service     : 0
```

The 2 ftp-web lines are `FileUploadEventConsumer` processing the `file.uploaded` event produced by my SFTP upload. This is exactly what R134Y designed to keep on RabbitMQ. Sprint-6 subtraction teeth still sharp.

### NotificationEventConsumer dual-transport (R134Y holds)

```
[R134Y][NotificationEventConsumer][outbox] drains: 4 (one per migrated prefix)
```

### RabbitMQ slim (R134Y holds)

```
notification.events    0  1 consumer
file.upload.events     0  20 consumers
```

### R134AA SSE path — path alive; empty because no client subscribed this cycle

```
[R134Z][ActivityStreamConsumer] polled 1 rows → 0 successful SSE deliveries
```

M=0 because I didn't attach a curl SSE client this cycle (R134AA already proved the E2E path works with a connected client); the `0` here is the correct output for "no connected listeners", not a regression. Zero `AuthenticationCredentialsNotFoundException` lines in the window confirms R134AA's auth-context fix is still holding.

---

## Stack health

Core services healthy: sftp-service, onboarding-api, notification-service, config-service, keystore-manager, storage-manager.

---

## What R134AB would have told us if Mode B had reproduced

Per the commit message's diagnostic decoder:

| Observed | Root cause named |
|---|---|
| `passwordLen != 10` | trim / padding / NUL-termination upstream |
| `passwordSha256Head != 58f1cbc5` | wire charset / NFC-NFD normalisation |
| `storedHashPrefix != "$2a$10$"` OR `storedHashLen != 60` | DB column or seed corruption |
| `bcryptSelfTest.roundTripOk=false` | BCrypt library / classpath regression |

Self-test `roundTripOk=true` already rules out #4 at every boot. The three remaining hypotheses can be discriminated on the very next Mode B trip by a single R134AB-augmented log line.

---

## Still open

Unchanged:
- **SFTP password auth Mode B regression — REAL, server-side, intermittent, NOW INSTRUMENTED.** R134AB's fields are loaded-and-waiting. Next Mode B trip should give a root-cause diagnosis from a single line.
- R134Y "SFTP client-side hostkey flake" is Mode A — independent, co-occurring possibility.
- Redis retirement (R134AA…R134AE sequence deferred)
- `ftp-2` UNKNOWN, demo-onboard 92.1%, coord auth posture review, account handler `type=null` log nit

---

## Sprint series state

| Tag | What shipped | Runtime | Medal pattern |
|---|---|---|---|
| R134T | Sprint 6: 4 events dual-path | ✓ | 🥈 |
| R134U | Sprint 7A: 3 publishers outbox-only | ✓ | 🥈 |
| R134V | Multi-handler cap | ✓ | 🥈 (exercised at R134X) |
| R134W | SFTP auth DENY branch observability | latent | 🥉 (primary branch dormant) |
| R134X | Sprint 7B: subtractive cutover | ✓ | 🥈 |
| R134Y | Sprint 8: slim RabbitMQ | ✓ | 🥈 |
| R134Z | SSE observability unswallow | ✓ | 🥈 |
| R134AA | SSE auth-context fix | ✓ | 🥈 / 🥉 (SFTP Mode B tripped) |
| **R134AB** | **Bytes-level bcrypt observability + self-test** | partial | 🥉 / 🥈 |

Pattern — **observability commits that partially exercise (self-test works, diagnostic branch dormant) consistently grade Bronze on the no-pre-medal rubric**: that's the fair call for R134W and R134AB alike. Product-state Silver when the thing-being-instrumented happens not to trip in the same cycle. The instrumentation is loaded; waiting for the next Mode B trip to actually earn its keep.

---

**Report author:** Claude (2026-04-22 session). R134AB contribution Bronze — self-test fires and passes (meaningful), but the primary augmented-mismatch branch is dormant because Mode B didn't reproduce. Product-state Silver — SFTP auth clean, BUG 13 signature holds, all carry-forward verifications pass. Diff-read Bronze-capped pre-runtime per no-pre-medal rule; partial exercise holds the Bronze cap post-runtime; product-state restored to Silver on Mode-B non-reproduction.
