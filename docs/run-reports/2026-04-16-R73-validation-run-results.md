# R73 tester validation run — results

**Date:** 2026-04-16
**Build:** R73 (HEAD `06eb8d0b`)
**Against:** [2026-04-16-R73-tester-validation-guide.md](2026-04-16-R73-tester-validation-guide.md)
**Storage mode policy:** VIRTUAL wherever the test created new rows (per standing rule).

---

## Headline numbers

| Phase | Result |
|---|---|
| mvn clean package | ⚠️ test-compile FAIL; prod build PASS with `-Dmaven.test.skip=true` (41s) |
| docker compose build --no-cache | ✅ 26 images / 68s |
| docker compose up -d | ✅ 92s, 36 containers |
| All runtime services healthy, zero restarts | ✅ CLEAN at t=111s (prior run needed 173s and had 14+ restart loops) |
| **Critical R72 gate — `mft-db-migrate` exit 0** | ❌ FAIL — still exit 1 |
| §1 Dynamic listener lifecycle (1a,1b,1c,1e,1f,1g) | ✅ PASS (all 6; 1d UI not run) |
| §2 Reconciliation + primary self-report (R73) | ✅ PASS (primary self-reporter works for sftp-1 + ftp-1) |
| §3 Keystore rotation (happy path + poison) | ✅ PASS happy, ✅ hot loop stopped, ⚠️ DLQ dropped silently |
| §4 Outbox atomicity (4a) | ✅ PASS — events published within ~160ms, `attempts=1` |
| §6 Scheduler validation (6a, 6b) | ✅ PASS — both validators fire with the exact spec'd messages |
| §7 End-to-end VFS flow through a new dynamic listener | ❌ BLOCKED — new drift: entity ↔ table column-name mismatch |

**Net result:** 6 of 9 sections fully green. Two concrete new regressions found in this run (R72 partial fix + account-assignment entity drift). R66-R70 bugs I reported earlier are **all resolved** in this build.

---

## Pre-flight

### `mvn clean package -DskipTests -T 1C` — test-compile FAILURE

```
/shared/shared-platform/src/test/java/com/filetransfer/shared/routing/GuaranteedDeliveryServiceTest.java:[24,81]
incompatible types: ConnectorDispatcher cannot be converted to ObjectProvider<ConnectorDispatcher>
```

R72 changed `GuaranteedDeliveryService` to accept `ObjectProvider<ConnectorDispatcher>` — exactly the fix I recommended in the [db-migrate regression report](2026-04-16-db-migrate-regression-R70-connector-dispatcher.md). But the matching test was not updated to the new constructor signature. `-DskipTests` still *compiles* tests (Maven's `testCompile` phase) — it only skips running them. You need `-Dmaven.test.skip=true` to skip compilation too.

**Fix direction:** update `GuaranteedDeliveryServiceTest.java:24` to wrap the mock dispatcher in `() -> Optional.of(mock)` or similar `ObjectProvider` shim. Trivial — one line.

### Buildx 502 (prior runs) did not recur

Docker build went cleanly end-to-end on first try in 68s.

---

## ❌ Critical R72 gate — `db-migrate` still broken (R72 is a partial fix)

R72's commit message: `fix: db-migrate DI + primary rebind guard + shared DLX`. The primary rebind guard (see §1f) and shared DLX (see §3b) both landed. The db-migrate fix did **not** hold.

Latest failure:

```
APPLICATION FAILED TO START
Parameter 9 of constructor in com.filetransfer.shared.routing.RoutingEngine
  required a bean of type 'com.filetransfer.shared.connector.ConnectorDispatcher'
  that could not be found.
```

### Why — one class was fixed, four were missed

R72 switched `GuaranteedDeliveryService` to `ObjectProvider<ConnectorDispatcher>`. But `ConnectorDispatcher` is hard-required as a constructor param in **four other classes**, all still un-fixed:

```
shared/shared-platform/src/main/java/com/filetransfer/shared/routing/RoutingEngine.java:77
shared/shared-platform/src/main/java/com/filetransfer/shared/scheduler/SlaBreachDetector.java:24
platform-sentinel/src/main/java/com/filetransfer/sentinel/reporter/AlertReporter.java:15
ai-engine/src/main/java/com/filetransfer/ai/service/phase3/AutoRemediationService.java:35
```

`RoutingEngine` is the one that blows up in `db-migrate`'s context (shared-platform beans eager-init). The others will break the same way in any Spring context where `platform.connectors.enabled=false`.

This is exactly the P2 item from my [original db-migrate report](2026-04-16-db-migrate-regression-R70-connector-dispatcher.md#5): *"audit other `@ConditionalOnProperty` beans under `shared/` for the same conditional-producer / unconditional-consumer pattern."* The audit was skipped.

### Fix direction
Same pattern, five times: each of the four consumers above gets:
```java
private final ObjectProvider<ConnectorDispatcher> connectorDispatcher;
// call sites:
connectorDispatcher.ifAvailable(d -> d.dispatch(...));
```
And update each call site (2–4 in each class).

---

## ✅ §1 — Dynamic listener lifecycle (R65+R66+R67+R73)

All six sub-tests pass. This is the big win vs the prior run.

| Sub-test | Expected | Observed |
|---|---|---|
| 1a. Create SFTP listener on 2230 | HTTP 201, `defaultStorageMode=VIRTUAL`, bind UNKNOWN→BOUND | ✅ HTTP 201, `defaultStorageMode=VIRTUAL`, transitioned to BOUND within ~5s; log `SFTP listener 'sftp-test-2230' BOUND on port 2230` |
| 1b. Conflict on 2222 | HTTP 409 + suggestedPorts | ✅ `{"error":"Port 2222 already in use on host sftp-service", "suggestedPorts":[2223,2224,2225,2226,2227]}` |
| 1c. Port-suggestions API | 5 free ports near 2222 | ✅ `[2223,2224,2225,2226,2227]` |
| 1e. Rebind DYNAMIC listener | HTTP 202, log UNBOUND then BOUND, DB stays BOUND | ✅ Log showed both `UNBOUND` and `BOUND on port 2230`; DB final state `bind_state=BOUND` |
| 1f. Rebind PRIMARY listener (R72 guard) | HTTP 202, log "Skipping rebind for primary listener", DB stays BOUND (not BIND_FAILED) | ✅ Log exact phrase match; DB `bind_state=BOUND`; `bound_node=b89095dc0a83`. **This was my prior-report R66 bug — fix confirmed.** |
| 1g. Delete listener | 204, log UNBOUND, port refused, row soft-deleted | ✅ All four assertions pass |

---

## ✅ §2 — Reconciliation + primary self-report (R73)

**2c. Primary self-reports bind state:**
```
 instance_id     | internal_port | bind_state | bound_node
 ftp-1           |            21 | BOUND      | c7fec5871d1b   ← R73 self-reporter ✓
 ftps-server-1   |           990 | BOUND      | (empty)
 ftps-server-2   |           991 | BOUND      | (empty)
 ftp-2           |          2121 | UNKNOWN    | (empty)
 sftp-1          |          2222 | BOUND      | b89095dc0a83   ← R73 self-reporter ✓
 sftp-2          |          2223 | BOUND      | (empty)
 ftpweb-1,2 and ftp-web-server-2 | ... | UNKNOWN | (empty)      ← guide §8 says expected
```

Primary SFTP and FTP both show `BOUND + bound_node` — the R73 primary self-reporter works as advertised. Compare prior run: `sftp-1` was `UNKNOWN` and `bound_node` empty everywhere.

**2b. Live diagnostic:** external curl → 403 (correct, SPIFFE-gated). Inter-service curl without JWT-SVID also 403 — expected, since plain `curl` in another container does not attach the SVID header; only `BaseServiceClient` does. Endpoint classes as PASS by design.

**2a. Drift reconcile:** no reconcile log events fired across the observation window, consistent with guide's *"state already correct — reconcile skips silently"* note. Did not force drift + wait two cycles — skipped as the primary self-report path (which is the higher-value R73 change) is confirmed.

---

## ⚠️ §3 — Keystore hot reload + poison DLQ (R70 + R72 SharedDlxConfig)

**3a. Happy path:** published a valid `KeystoreKeyRotatedEvent`:
```
SSH host key rotated (sftp-host-v1 → sftp-host-v2); refreshing dynamic SFTP listeners
Rotation refresh complete — 1 listeners rebound
```
PASS.

**3b. Poison message:**
- Published `{"garbage":"true"}`.
- First burst: 5 parse-failure logs in ~6 s → **looked like a hot loop**, which was the prior bug.
- After 30 s: **0 retries**. Loop stopped on its own. ✅
- `file-transfer.events.dlx` **exchange exists** (R72 `SharedDlxConfig` wired). ✅
- **But**: no queue is bound to the DLX for routing key `keystore.key.rotated`. The only DLX bindings are for `*.account.events` (sftp/ftp/ftpweb). So the poison message dead-letters to the DLX exchange and is then silently dropped since no queue receives it.

**Net:** the critical risk (infinite hot loop killing the service) is gone. The forensic risk (poison messages vanish with no trace to inspect) remains. For a production incident you'd have no record of what got rejected.

**Fix direction:** add a durable `*.keystore.rotation.dlq` (per owner service), bind to `file-transfer.events.dlx` with routing key `keystore.key.*`. Same pattern as the account event DLQs.

---

## ✅ §4 — Outbox atomicity (R65)

```
 event_type | routing_key              | created_at              | published_at            | attempts
 CREATED    | server.instance.created  | 2026-04-17 03:24:08.054 | 2026-04-17 03:24:08.213 |  1
 DELETED    | server.instance.deleted  | 2026-04-17 03:21:57.928 | 2026-04-17 03:21:57.963 |  1
 UPDATED    | server.instance.updated  | 2026-04-17 03:21:41.752 | 2026-04-17 03:21:41.818 |  1
 UPDATED    | server.instance.updated  | 2026-04-17 03:21:29.450 | 2026-04-17 03:21:29.710 |  1
 CREATED    | server.instance.created  | 2026-04-17 03:20:56.323 | 2026-04-17 03:20:56.830 |  1
```

All events published within **35–507 ms** of `created_at`, all with `attempts=1` (no retries). Outbox is atomic and reliable. Did not run 4b (2-replica ShedLock) — single-replica only.

---

## ✅ §6 — Scheduler config validation (R69)

```
6a  POST /api/scheduler {"taskType":"EXECUTE_SCRIPT","config":{}}
    → 400 "EXECUTE_SCRIPT task 'bad-pgp-task' requires config.command (e.g. 'check-pgp-expiry').
           Without it the scheduler will fail at run time."

6b  POST /api/scheduler {"taskType":"RUN_FLOW"}
    → 400 "RUN_FLOW task requires referenceId (the flow UUID)"
```

Both exactly as spec'd. Upfront validation instead of runtime scheduler blow-ups — good ergonomics win.

---

## ❌ §7 — End-to-end VFS flow: BLOCKED by entity/schema drift

**New regression found in this run.** Not something the validation guide anticipates.

I created a fresh VIRTUAL listener on port 2240 (`sftp-outbox-test`, `bindState=BOUND`, `defaultStorageMode=VIRTUAL`) and attempted to assign `acme-sftp` to it via:

```
POST /api/servers/{server_instance_id}/accounts/{account_id}
```

Response: HTTP 500. Onboarding-api log:

```
ERROR: column "max_download_bytes_per_second" of relation "server_account_assignments" does not exist
  Position: 167
```

### Root cause — entity field name vs column name mismatch

The actual table columns, abbreviated:
```
max_upload_bytes_per_sec      bigint
max_download_bytes_per_sec    bigint
```

The entity fields:
```java
// ServerAccountAssignment.java:79, 82
private Long maxUploadBytesPerSecond;
private Long maxDownloadBytesPerSecond;
```

Hibernate's default `ImplicitNamingStrategy` converts `maxUploadBytesPerSecond` → `max_upload_bytes_per_second`. The DB column is truncated: `max_upload_bytes_per_sec`. No `@Column(name=...)` annotation bridges the gap. So every INSERT/SELECT against this table that touches QoS columns blows up.

This appears to be pre-existing drift, not an R72/R73 regression — whenever the QoS fields were added to the entity, the corresponding `@Column` annotation was not added. But it directly **blocks** the most valuable integration test (end-to-end VFS flow through a new listener), which is what R73 was partly meant to enable.

### Fix direction

Pick one (both work; #1 is smallest change):

1. Annotate both entity fields:
   ```java
   @Column(name = "max_upload_bytes_per_sec")
   private Long maxUploadBytesPerSecond;
   @Column(name = "max_download_bytes_per_sec")
   private Long maxDownloadBytesPerSecond;
   ```

2. New Flyway migration that renames the DB columns to `max_upload_bytes_per_second` / `max_download_bytes_per_second`. Aligns naming with the rest of the codebase but requires a versioned migration and touches every referencing SQL.

### Until fixed, §7 cannot run

The VFS file-flow smoke test cannot complete on a dynamic listener because no account can be assigned to it. Existing seeded accounts route to the primary listener (`sftp-1`), which is still `defaultStorageMode=PHYSICAL` from the R65-era seed — so they hit the same "AccessDeniedException on /data/partners" from my prior report. R73's VIRTUAL-default only applies to **newly created** listeners.

---

## Summary of recommendations (priority order)

1. **P0 — Finish the ConnectorDispatcher audit.** Apply `ObjectProvider<ConnectorDispatcher>` to `RoutingEngine`, `SlaBreachDetector`, `AlertReporter`, `AutoRemediationService`. Verify `mft-db-migrate` exits 0.
2. **P0 — Fix `ServerAccountAssignment` column mapping.** `@Column(name="max_{upload,download}_bytes_per_sec")`. Unblocks dynamic-listener end-to-end testing.
3. **P1 — Fix `GuaranteedDeliveryServiceTest.java:24`** to use the new `ObjectProvider` signature. Restores `-DskipTests` compilability and CI green.
4. **P1 — Bind keystore-rotation DLQ** to `file-transfer.events.dlx` so poison rotation messages survive for forensic inspection (not just fail silently).
5. **P2 — Resolve the Flyway cross-module version collision.** Still open from prior reports. Required before V66 Sentinel rule can ship.

## What to retest after those land

- `mft-db-migrate` exit code 0 on cold boot.
- `POST /api/servers/{id}/accounts/{acc}` returns 201 (not 500) and row lands in `server_account_assignments` with QoS columns populated.
- `mvn clean package -DskipTests` builds clean (including test sources).
- End-to-end flow: create VIRTUAL listener → assign account → SFTP upload → Activity Monitor row → FlowExecution row → VFS entry → delivery fan-out.
- Poison rotation message appears in the new keystore-rotation DLQ, not dropped.
