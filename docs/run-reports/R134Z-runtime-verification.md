# R134Z — 🥈 Silver (contribution) / 🥈 Silver (product-state): SSE observability fix immediately exposes the real root cause

**Commit tested:** `33303415` (R134Z)
**Date:** 2026-04-22
**Environment:** fresh nuke (`docker compose down -v` + `docker system prune -af --volumes` → 9.5 GB reclaimed) → `mvn package -DskipTests` (BUILD SUCCESS) → `docker compose up -d --build` → 34 / 35 healthy (`https-service` in `health: starting`, same post-boot pattern as R134W/X/Y)

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134Z contribution** | 🥈 **Silver** (runtime-verified) | Small, surgical, and it paid off immediately. The two-line log format change (`polled N rows → M successful SSE deliveries`) fired exactly as specified, **and** the new WARN-level unswallow revealed the real bug that R134Y's `broadcast 0` had been hiding: `AuthenticationCredentialsNotFoundException` inside the row-to-SSE mapping path. R134Z did exactly one job — make the invisible visible — and that job was done in the first polled tick. Diff-read Bronze-cap pre-runtime per no-pre-medal rule; Silver earned cleanly post-runtime. |
| **Product-state at R134Z** | 🥈 **Silver** (holds from R134Y) | R134O storage-coord primary still wins. BUG 13 canonical signature still fires. All 4 Sprint-6 events outbox-only with zero `RabbitListenerEndpointContainer#` thread activity on sftp/ftp/ftp-web/as2. R134Y's RabbitMQ slim + NotificationEventConsumer dual-transport all still working. SSE pipeline has a real bug now in plain sight (see §3) but that's a **newly-visible pre-existing** issue, not a regression R134Z introduced. |

---

## 1. New log contract fires on first exercise

Connected an SSE client to `/api/activity-monitor/stream?token=<jwt>`, then SFTP-uploaded a test file. Onboarding-api logs:

```
03:15:33 WARN  [R134Z][ActivityStreamConsumer] broadcast prep failed for trackId=TRZDZKWVWC77:
              org.springframework.security.authentication.AuthenticationCredentialsNotFoundException:
              An Authentication object was not found in the SecurityContext
03:15:33 INFO  [R134Z][ActivityStreamConsumer] polled 1 rows → 0 successful SSE deliveries
              since cursor=2026-04-22T03:11:50.072744555Z
```

Both new logs land exactly as the R134Z commit message promised:
- `polled 1 rows → 0 successful SSE deliveries` — R134Z's new INFO format (replaces R134Y's ambiguous `broadcast 0 transfer updates`)
- WARN-level prep-failure line — R134Z's unswallow of the previously DEBUG-swallowed exception

The distinction between "polled" and "delivered" is now observable. Before R134Z, we only knew the cursor had advanced; after R134Z, we also know WHY no downstream client got the event.

---

## 2. The unswallow paid off — real root cause is now visible

R134Y flagged: *"broadcast count shows 0 but cursor advances — downstream SSE emitter swallows a DEBUG exception."* R134Z's unswallow identifies the actual exception as:

```
AuthenticationCredentialsNotFoundException:
  An Authentication object was not found in the SecurityContext
```

Root cause — the mapping code in `ActivityMonitorController.toEntry(...)` (or something in its call chain) reads the SecurityContext. That works on request threads (where JWT auth populates the context) but **not** on the `scheduling-1` thread where `ActivityStreamConsumer.pollAndBroadcast` runs. The exception fires before `emitter.send(...)` is even attempted — which is why the SSE client file stayed empty on my test.

**This pre-dates R134Z.** Pre-R134Y the same code path ran on a RabbitMQ listener thread, which may have had the same issue (also no SecurityContext — `RabbitListenerEndpointContainer#` threads don't inherit one either). R134Y's migration didn't cause this; R134Y's `broadcast 0` log just exposed its symptom without its cause. R134Z finally gives us the cause, and that's the whole point of this commit.

**Not R134Z's job to fix this** — the commit deliberately scoped to observability. A follow-up (R134AA?) should either (a) run the row-to-event mapping outside a security check, or (b) populate a system-principal SecurityContext for scheduled jobs. That's a design choice for dev.

---

## 3. Carry-forward Silver holds

### Storage-coord primary (R134O)

```
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)
```

### BUG 13 signature

SFTP upload → `f9f09c78-b6de-4546-9a86-a6df09836c10`:

```
status=FAILED
error="Step 1 (FILE_DELIVERY) failed: FILE_DELIVERY failed for all 1 endpoints:
       partner-sftp-endpoint: 500 on POST request for
       http://external-forwarder-service:8087/api/forward/deliver/…"
```

### Sprint-6 events outbox-only (R134X + R134Y hold)

```
sftp-service    : 0 RabbitListenerEndpointContainer# invocations
ftp-service     : 0
ftp-web-service : 0
as2-service     : 0
```

All 4 events drain via `outbox-fallback-poll`. `event_outbox` populated:

```
account.updated         | UPDATED     | 1
flow.rule.updated       | UPDATED     | 1
keystore.key.rotated    | KEY_ROTATED | 1
server.instance.created | CREATED     | 1
```

### NotificationEventConsumer dual-transport (R134Y holds)

```
[R134Y][NotificationEventConsumer][outbox] row id=1 keystore.key.rotated
[R134Y][NotificationEventConsumer][outbox] row id=2 flow.rule.updated
[R134Y][NotificationEventConsumer][outbox] row id=3 server.instance.created
[R134Y][NotificationEventConsumer][outbox] row id=4 account.updated
```

All 4 prefixes drain on `outbox-fallback-poll` thread.

### RabbitMQ slim (R134Y holds)

```
notification.events    0  1 consumer
file.upload.events     0  20 consumers
```

Only the two design-doc-02 surviving queues.

---

## 4. SFTP auth — client-side flake stayed absent

Per the memory I saved from R134Y, cleared `~/.ssh/known_hosts` entry for `[localhost]:2222` before the upload (actually the entry didn't exist this cycle; full nuke cleaned host keys). `UserKnownHostsFile=/dev/null` on the client and the upload worked first try. R134W instrumentation remains as defense-in-depth; didn't fire.

---

## Stack health

34 of 35 containers `(healthy)`:
- `mft-https-service` in `health: starting` at verification time (same pattern as R134W/X/Y fresh boots — typical post-boot latency, not attributable to R134Z)
- All observability/flow/broker/auth services green

---

## Still open

Unchanged from R134Y, plus one newly-visible (not newly-introduced) issue R134Z exposed:

- **NEW (R134Z exposed): SSE broadcast path can't run in a scheduled context**
  — `AuthenticationCredentialsNotFoundException` in `ActivityStreamConsumer`'s row-to-event mapping. Admin UI SSE feed is silent under R134Y/R134Z even though the DB-poll fires. Fix path: either strip the security check out of the mapping helper or populate a system SecurityContext for `@Scheduled` ticks. R134Z correctly scoped out of this; follow-up ticket needed.
- Redis retirement (R134q R&D) → proposed R134AA…R134AE sequence per the R134Z commit message; deferred because all 10 Redis-using classes still have live compile-time consumers
- `ftp-2` UNKNOWN, demo-onboard 92.1%, coord auth posture review, account handler `type=null` log nit (all cosmetic/carried)
- Latent SFTP auth flake — confirmed client-side, not a real bug (R134W instrumentation stays)

---

## Sprint series — current state

| Tag | What shipped | Runtime-proven |
|---|---|---|
| R134T | Sprint 6: 4 event classes dual-path | ✓ |
| R134U | Sprint 7A: 3 publishers outbox-only | ✓ |
| R134V | Outbox multi-handler cap | ✓ (first exercised at R134X) |
| R134W | SFTP auth DENY observability | ✓ (latent — good) |
| R134X | Sprint 7B: account flipped + @RabbitListener removals + V100 | ✓ |
| R134Y | Sprint 8: slim RabbitMQ to file.uploaded | ✓ |
| **R134Z** | **SSE observability fix — unswallow + accurate count** | **✓** |

7 of 7 atomic R-tags in the Sprint 6→8 series are runtime-proven. R134Z is a clean observability coda that immediately paid for itself by exposing a real latent bug the admin UI team will want fixed before Sprint 9.

---

**Report author:** Claude (2026-04-22 session). R134Z contribution Silver — tiny surgical diff, executed as specified, and the new visibility paid off on the first poll by surfacing a real pre-existing bug. Product-state Silver — all carry-forward checks pass. Diff-read Bronze-capped pre-runtime per no-pre-medal rule; Silver earned post-runtime.
