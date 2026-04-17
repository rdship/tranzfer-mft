# R66–R70 feature test report

**Date:** 2026-04-16
**Build:** R70 (post-cold-boot, clean state — see
[2026-04-16-boot-regression-V64-dynamic-listeners.md](2026-04-16-boot-regression-V64-dynamic-listeners.md)
and [2026-04-16-db-migrate-regression-R70-connector-dispatcher.md](2026-04-16-db-migrate-regression-R70-connector-dispatcher.md)
for prior context)
**Scope:** Exercise each feature landed between R66 and R70 against the live stack. Storage mode = VIRTUAL where possible (per standing rule).

---

## Results at a glance

| # | Feature | Release | Status |
|---|---|---|---|
| 1 | Port-conflict 409 with suggestions | R67 | ✅ PASS |
| 2 | Admin rebind endpoint | R66 | ⚠️ PARTIAL — accepts request, but registry fails to unbind-then-bind |
| 3 | Listener diagnostic endpoint `/internal/listeners/live` | R68 | ✅ PASS (wired; SPIFFE-gated as designed) |
| 4 | Keystore hot-reload consumer | R70 | ✅ PASS — consumer wired, event handled, listener rebound |
| 5 | V66 Sentinel `listener_bind_failed` rule | R68 | ❌ FAIL — migration not applied to DB |
| 6 | V64 graceful-degradation columns populated by registry | R68 | ⚠️ PARTIAL — half the listeners stuck at `bind_state=UNKNOWN` |
| 7 | End-to-end VFS file flow (SFTP upload + track) | standing | ⚠️ BLOCKED — not by auth, by physical-filesystem chroot |

---

## 1. ✅ Port-conflict 409 with suggestions (R67)

Test: POST a listener with an (internal_host, internal_port) that already exists.

```bash
curl -X POST http://localhost:8080/api/servers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"conflict-test","instanceId":"conflict-test-1","protocol":"SFTP",
       "internalHost":"sftp-service","internalPort":2222,
       "externalHost":"localhost","externalPort":2222,
       "defaultStorageMode":"VIRTUAL","maxConnections":10}'
```

Response:
```http
HTTP/1.1 409
{"suggestedPorts":[2223,2224,2225,2226,2227],
 "host":"sftp-service",
 "error":"Port 2222 already in use on host sftp-service",
 "requestedPort":2222}
```

Five suggested alternatives, actionable error, correct status code. Exactly the UX the R67 commit advertised.

### Minor rough edge
The DTO (`CreateServerInstanceRequest`) does not accept an `active` field. I sent one on the first attempt and got a 400 with a clear "Unrecognized field" message listing the 15 known properties — so the guardrail works, but the field list notably does not include `active`, meaning every new listener is forced to the default `active=true`. If you want to allow creating listeners in a disabled-draft state, that field needs adding to the DTO.

## 2. ⚠️ Admin rebind endpoint (R66)

Test: POST `/api/servers/{id}/rebind` on the primary SFTP listener.

Response: `HTTP 202` with the full updated ServerInstance JSON — the API is wired and the event was published.

**Problem, caught in the sftp-service logs:**

```
bind(0.0.0.0/0.0.0.0:2222) - failed (BindException) to bind: Address already in use
SFTP listener 'sftp-1' BIND_FAILED on port 2222: port in use
```

`SftpListenerRegistry.onRebind()` attempted to re-bind the port without first unbinding the existing listener. The old listener kept the port, the re-bind failed, and the DB row flipped from `UNKNOWN` → `BIND_FAILED`. The actual SFTP listener is still running and serving — only the database tracking is now wrong.

Contrast with the R70 keystore-rotation path (below), which correctly unbinds then rebinds. The two rebind code paths have diverged.

**Fix direction:** `SftpListenerRegistry.handleUpdated()` (or whatever the `server_instance.updated` RabbitMQ listener dispatches to) must call `unbind(existing)` before `bind(new)`, same as the rotation consumer does.

## 3. ✅ Listener diagnostic endpoint (R68)

`GET /internal/listeners/live` on sftp-service (8081) and ftp-service (8082).

Direct curl returns `HTTP 403` — which is **correct**. The `/internal/*` path sits behind `PlatformJwtAuthFilter` and requires SPIFFE JWT-SVID or mTLS peer cert. Classing this PASS because the route is wired, secured appropriately, and designed to be consumed from the UI / Sentinel / ops dashboards via internal inter-service calls, not bare curl. Live call from inside the SPIFFE trust domain was not run in this test session.

## 4. ✅ Keystore hot-reload consumer (R70)

Queues exist:

```
sftp-keystore-rotation  1  0  1
ftp-keystore-rotation   1  0  1
```

Published a well-formed `KeystoreKeyRotatedEvent` on `file-transfer.events / keystore.key.rotated`:

```json
{"newAlias":"sftp-host-key-v3","oldAlias":"sftp-host-key-v2",
 "ownerService":"sftp-service","rotatedAt":"2026-04-17T02:00:00Z",
 "keyType":"SSH_HOST_KEY"}
```

sftp-service logged:
```
SSH host key rotated (sftp-host-key-v2 → sftp-host-key-v3); refreshing dynamic SFTP listeners
Rotation refresh complete — 1 listeners rebound
```

Clean end-to-end round-trip: event → consumer → registry refresh → DB `bind_state` settled. Notably **this** rebind path unbinds first, which is what the R66 admin rebind should be doing too.

### Observation — DLQ is advertised but missing
The consumer declares `x-dead-letter-exchange: file-transfer.events.dlx`, but the exchange does not exist. First time I published a payload with the wrong field names, the message could not be parsed → rejected → requeued forever instead of dead-lettered. A poison message currently crash-loops the consumer until a human purges the queue. This is latent — the happy path is fine, but any upstream schema drift or intermediate JSON corruption turns a warning into a permanent hot loop.

**Fix direction:** declare `file-transfer.events.dlx` as a durable `DirectExchange` beside the other RabbitMQ beans, bind per queue's x-dead-letter-exchange, and set `defaultRequeueRejected=false` on the listener container so bad messages dead-letter on first failure rather than spin.

## 5. ❌ V66 Sentinel `listener_bind_failed` rule (R68)

Migration file `platform-sentinel/src/main/resources/db/migration/V66__listener_bind_failed_rule.sql` **exists on disk and is in the built image** but is **not applied** to the database:

```
 version |        description         | success
 64      | dynamic listeners          | t       ← shared-platform V64
 70      | refresh tokens             | t       ← some other service's V70
```

No 65, 66, 67, 68, or 69. Platform-sentinel's own `V64__sentinel_rules_builtin.sql`, `V65__sentinel_tables.sql`, and `V66__listener_bind_failed_rule.sql` all appear to have been silently skipped.

### Why (root cause, high confidence)

The project has **multiple modules each contributing their own Flyway migrations**, but they all share a single `flyway_schema_history` table in the public schema. Two problems stack:

1. **Version-number collisions across modules.** There are at least two `V64`s (shared-platform/V64__dynamic_listeners and platform-sentinel/V64__sentinel_rules_builtin). Whichever migrator runs first wins; Flyway records `version=64` in history; the loser's V64 file is then considered "already applied" and skipped.

2. **`SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`** is set in `docker-compose.override.yml` for multiple services (config-service, onboarding-api, gateway-service, license-service, platform-sentinel). That override was originally a workaround for the 2026-04-11 V42 CONCURRENTLY lock issue — but its side-effect here is that the checksum mismatch from two-different-V64-files no longer raises an error. The collision is silent.

Net: **R68's "Sentinel bind-failed rule" feature is shipped but inert.** If you trigger a `BIND_FAILED` today, no Sentinel alert will fire because the rule isn't in the database.

### Fix direction
Pick one:
1. Renumber migrations so each module's migrations are in a non-overlapping range (shared-platform: 1–99, platform-sentinel: 100–199, storage-manager: 200–299, etc.) and re-enable validation.
2. Move each module to its own schema-scoped Flyway history table (`flyway.schemas=platform_sentinel` etc. per service) so version collisions become per-schema.
3. Use a module prefix in the migration filename (`V_sentinel_66__...`) — Flyway allows non-numeric prefixes in a limited form.

The override line was a V42 workaround and is now hiding a structural problem. It should come out once the root cause is fixed.

## 6. ⚠️ V64 graceful-degradation columns (R68 reconciler)

`server_instances` shows the registry is populating the new columns only partially:

```
 instance_id    | internal_host     | port |  bind_state  | bound_node
 ftps-server-1  | ftp-service       |  990 | BOUND        | (empty)
 ftps-server-2  | ftp-service-2     |  991 | BOUND        | (empty)
 sftp-2         | sftp-service-2    | 2223 | BOUND        | (empty)
 ftp-1          | ftp-service       |   21 | UNKNOWN      | (empty)
 ftp-2          | ftp-service-2     | 2121 | UNKNOWN      | (empty)
 sftp-1         | sftp-service      | 2222 | BIND_FAILED  | (empty)    ← artifact of the rebind test, was UNKNOWN before
 ftpweb-1       | ftp-web-service   | 8083 | UNKNOWN      | (empty)
 ftpweb-2       | ftp-web-service-2 | 8098 | UNKNOWN      | (empty)
 ftp-web-2      | ftp-web-service-2 | 8183 | UNKNOWN      | (empty)
```

Three rows correctly settled at BOUND. Five rows stuck at UNKNOWN even though the protocol services are healthy and serving. `bound_node` is empty across the board. The R68 reconciler is meant to pin these.

**Fix direction:** the periodic reconcile job needs to actually run (not just be scheduled) and update rows where the in-process registry reports a live listener but DB says UNKNOWN. Also set `bound_node` to the SPIRE SPIFFE ID or the `CLUSTER_HOST` value.

## 7. ⚠️ End-to-end VFS flow (blocked, but not by what memory said)

Attempted `sftp -P 2222 acme-sftp@localhost` with password `partner123`. sftp-service log:

```
SFTP password auth attempt: username=acme-sftp ...
{"event":"LOGIN","username":"acme-sftp","success":true,"authMethod":"password"}
exceptionCaught ... AccessDeniedException: /data/partners
{"event":"DISCONNECT","username":"acme-sftp","success":true}
```

Authentication **succeeds**. The session immediately dies on `AccessDeniedException: /data/partners` because:

- The primary SFTP listener (`sftp-1`) is `default_storage_mode=PHYSICAL`. So is `ftp-1`, `sftp-2`, `ftp-web-1`, `ftp-web-2`. The only listeners that came out `VIRTUAL` are the three seeded by `PlatformBootstrapService` (ftps-server-1, ftps-server-2, ftp-web-server-2).
- The account `acme-sftp` has `homeDir=/data/partners/acme`, and with PHYSICAL mode the SFTP subsystem tries to open that filesystem path inside the sftp-service container — which does not exist and is not writable.

**The "SFTP auth bug" note in project memory is misattributed** — auth is working. The actual blocker is:

1. The runtime listener registry defaults new listeners to `PHYSICAL`, contradicting the standing "always VIRTUAL" rule. The seed path correctly uses `VIRTUAL`; the runtime path does not.
2. No `/data/partners/*` directories exist inside sftp-service, which would only matter for PHYSICAL listeners — a VIRTUAL listener would route the upload through storage-manager instead.

**Fix direction:**
1. `SftpListenerRegistry` (and FTP/FTPWEB equivalents) should default `default_storage_mode=VIRTUAL` when creating a row.
2. `acme-sftp`-style seeded accounts should either target a VIRTUAL-mode listener explicitly via `serverInstance`, or the primary seeded listeners should be VIRTUAL (currently only the secondaries are).

I will file a memory correction separately — the memory currently says "integration testing blocked on SFTP auth bug", but the real blocker is storage-mode defaulting + missing physical mounts.

---

## Recommendations, in priority order

1. **P0 — Fix the rebind path (R66 admin trigger).** `SftpListenerRegistry.handleUpdated` must unbind-then-bind. Today it blind-rebinds and leaves the DB lying that the listener is BIND_FAILED.
2. **P0 — Fix Flyway version-number collisions across modules.** Separate ranges or per-schema history tables. While that's being designed, document the override so future eyes don't mistake `validate=false` for "it's fine, the bug is just V42".
3. **P1 — Default new listeners to VIRTUAL in the runtime registry.** Matches the standing rule and unblocks the VFS end-to-end flow test.
4. **P1 — Declare `file-transfer.events.dlx` and disable requeue-on-reject.** Poison-message loops are a latent crash risk.
5. **P1 — Activate / fix the R68 reconciler** so `bind_state` reflects reality for every listener, and populate `bound_node`.
6. **P2 — Add `active` to `CreateServerInstanceRequest`** to allow drafting disabled listeners.
7. **P2 — Correct the memory note** "integration testing blocked on SFTP auth bug" → "… blocked on listener PHYSICAL default + no physical mounts".

## What to retest after the fixes land

| Test | Pass criterion |
|---|---|
| POST /api/servers/{id}/rebind on a BIND_FAILED row | sftp-service log shows `unbind(...)` then `bind(...) - successful`, row transitions BIND_FAILED → BOUND |
| `docker exec mft-postgres psql ... "SELECT version FROM flyway_schema_history WHERE version='66';"` | Returns the sentinel rule row |
| Trigger a simulated bind failure | platform-sentinel fires the `listener_bind_failed` rule |
| VFS SFTP upload (`acme-sftp` → sftp-1 after switching to VIRTUAL) | `put` succeeds, journey endpoint returns a track ID |
| Publish poison keystore rotation event | Logged once, dead-lettered, queue goes idle (no hot loop) |
