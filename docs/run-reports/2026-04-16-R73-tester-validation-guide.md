# R65–R73 Tester Validation Guide

**Build:** R73 (commit `cd0d177`)
**Purpose:** Targeted validation of features/fixes landed on top of the standard sanity regression. Run AFTER sanity passes green.

---

## Pre-flight

```bash
git pull
mvn clean package -DskipTests -T 1C
docker compose build --no-cache
docker compose down -v
docker compose up -d
```

**First health gate (90–180s):** all runtime services `healthy` + `RestartCount=0`.
**Critical verification:** `docker ps -a --filter name=mft-db-migrate` — must show exit code **0**, not 1 (R72 regression fix).

---

## 1. Dynamic listener lifecycle — end-to-end

### 1a. Create a VIRTUAL SFTP listener on a fresh port

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}' | jq -r .token)

curl -s -X POST http://localhost:8080/api/servers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "instanceId":"sftp-test-2230",
    "protocol":"SFTP",
    "name":"Dynamic test listener",
    "internalHost":"sftp-service",
    "internalPort":2230,
    "externalHost":"localhost",
    "externalPort":2230,
    "maxConnections":50
  }' | jq .
```

**Expect:**
- HTTP 201
- `defaultStorageMode=VIRTUAL` in response (R73 default)
- `bindState=UNKNOWN` initially, transitions to `BOUND` within ~5s (check via `GET /api/servers/{id}`)
- `sftp-service` log: `SFTP listener 'sftp-test-2230' BOUND on port 2230`

### 1b. Create a listener with a port already taken → 409 + suggestions

```bash
curl -s -X POST http://localhost:8080/api/servers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "instanceId":"sftp-conflict-test",
    "protocol":"SFTP",
    "name":"Conflict test",
    "internalHost":"sftp-service",
    "internalPort":2222,
    "externalHost":"localhost",
    "externalPort":2222
  }' | jq .
```

**Expect:**
- HTTP 409
- Response body: `{"error":"Port 2222 already in use on host sftp-service","host":"sftp-service","requestedPort":2222,"suggestedPorts":[...]}`
- Suggested ports list non-empty, sorted ascending from 2223+

### 1c. Port suggestions API

```bash
curl -s "http://localhost:8080/api/servers/port-suggestions?host=sftp-service&port=2222&count=5" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

**Expect:** JSON with `suggestedPorts` array of 5 free ports near 2222.

### 1d. UI: 409 shows clickable port chips

1. Open `https://localhost/servers/instances`
2. Click "Add server"
3. Enter name, protocol=SFTP, internal host=`sftp-service`, internal port=**2222**
4. Submit

**Expect:**
- Red toast appears with text "Port 2222 already in use on sftp-service — try these free ports:"
- 5 port buttons (e.g., 2223, 2224, 2225, 2226, 2227) are clickable
- Clicking a port auto-fills the form's internal + external port
- Re-submitting succeeds with HTTP 201

### 1e. Rebind endpoint — on a DYNAMIC listener

1. Pick a dynamic listener (e.g., `sftp-test-2230` from 1a), note its UUID
2. `curl -X POST http://localhost:8080/api/servers/{UUID}/rebind -H "Authorization: Bearer $TOKEN"`

**Expect:**
- HTTP 202 Accepted
- Response body contains the full updated ServerInstance
- `sftp-service` log within ~2s: `SFTP listener ... UNBOUND` then `SFTP listener ... BOUND on port 2230`
- DB `bind_state` stays `BOUND` (does not flip to UNKNOWN/FAILED)

### 1f. Rebind endpoint — on a PRIMARY listener (R72 fix)

1. Get UUID of the primary `sftp-1` row (port 2222): `curl -s http://localhost:8080/api/servers -H "Authorization: Bearer $TOKEN" | jq '.[] | select(.internalPort==2222)'`
2. `curl -X POST http://localhost:8080/api/servers/{UUID}/rebind -H "Authorization: Bearer $TOKEN"`

**Expect:**
- HTTP 202 (the API accepts it — event was published)
- `sftp-service` log: `Skipping rebind for primary listener 'sftp-1' — restart the container to apply primary config changes`
- **Critical:** DB `bind_state` stays `BOUND` (NOT flipped to BIND_FAILED). This was the R70 bug.
- `sftp -P 2222 <account>@localhost` still connects normally

### 1g. Delete a listener → unbinds

1. Delete the `sftp-test-2230` listener: `curl -X DELETE http://localhost:8080/api/servers/{UUID} -H "Authorization: Bearer $TOKEN"`

**Expect:**
- HTTP 204
- `sftp-service` log: `SFTP listener ... UNBOUND`
- Port 2230 no longer accepts TCP: `nc -zv localhost 2230` fails
- `GET /api/servers/{UUID}` returns the row with `active=false` (soft delete)

---

## 2. Reconciliation + drift self-heal (R68)

### 2a. Simulate drift — manually unbind mid-flight

1. Create a dynamic listener on port 2231 (from step 1a pattern)
2. `docker exec mft-sftp-service kill -STOP $(docker exec mft-sftp-service pgrep -f 'sftp-test-2231')` — no, that won't work cleanly. Instead:
3. **Simpler drift:** UPDATE the DB directly (as simulating a crash-recovered state):
   `docker exec mft-postgres psql -U mft -d mft -c "UPDATE server_instances SET active=true, bind_state='UNKNOWN' WHERE instance_id='sftp-test-2231'"`
4. Wait 60s (reconcile runs every 30s with 30s initial delay).

**Expect:**
- `sftp-service` log: `Reconcile: drift detected — 'sftp-test-2231' desired but not bound, attempting bind` OR state already correct (if the listener is actually bound, reconcile skips).
- After two reconcile ticks, `bind_state` reflects reality.

### 2b. Live listener diagnostic

```bash
# From inside the SPIFFE trust domain only (e.g., another container):
docker exec mft-sentinel curl -sf http://sftp-service:8081/internal/listeners/live
```

**Expect:** JSON with `protocol: SFTP`, `count`, and `listeners[]` each showing `serverInstanceId`, `port`, `started`, `activeSessions`.

From outside SPIFFE: `curl http://localhost:8081/internal/listeners/live` returns **403**. Expected — endpoint is SPIFFE-gated.

### 2c. Primary self-reports bind state (R73 fix)

After cold boot, check all primary rows:

```bash
docker exec mft-postgres psql -U mft -d mft -c \
  "SELECT instance_id, internal_port, bind_state, bound_node FROM server_instances WHERE active=true ORDER BY internal_port"
```

**Expect:**
- Primary SFTP/FTP rows (ports 2222, 21) show `bind_state=BOUND` and `bound_node` populated (the container hostname).
- **Previously:** UNKNOWN forever. If still UNKNOWN, check service logs for "Primary listener bind state reported" — missing log = the match-by-port fallback failed.

---

## 3. Keystore cert hot reload (R70)

### 3a. Event round-trip

```bash
# Publish a synthetic KeystoreKeyRotatedEvent (requires rabbitmq-admin or rabbitmqctl)
docker exec mft-rabbitmq rabbitmqadmin publish \
  exchange=file-transfer.events \
  routing_key=keystore.key.rotated \
  payload='{"oldAlias":"sftp-host-v1","newAlias":"sftp-host-v2","keyType":"SSH_HOST_KEY","ownerService":"sftp-service","rotatedAt":"2026-04-17T02:00:00Z"}'
```

**Expect:**
- `sftp-service` log: `SSH host key rotated (sftp-host-v1 → sftp-host-v2); refreshing dynamic SFTP listeners`
- Then: `Rotation refresh complete — N listeners rebound`
- Dynamic listeners (not primary) see `unbind → bind` in logs
- Connected SFTP sessions stay alive (existing TCP not killed)
- New SFTP connection works normally

### 3b. Poison message → DLQ (R72 shared DLX)

```bash
# Send malformed payload
docker exec mft-rabbitmq rabbitmqadmin publish \
  exchange=file-transfer.events \
  routing_key=keystore.key.rotated \
  payload='{"garbage":"true"}'
```

**Expect:**
- First attempt fails to parse
- Retry 3x per SharedDlxConfig
- Rejected → dead-letters to `file-transfer.events.dlx`
- **Critical:** no hot loop. `docker logs mft-sftp-service -f` shouldn't show the same error repeating every second.
- `docker exec mft-rabbitmq rabbitmqctl list_queues | grep dlq` shows a dead-letter queue with count ≥1

---

## 4. Outbox atomicity (R65 + R71 ShedLock)

### 4a. Atomic DB + event publish

1. Create a listener (as in 1a)
2. Immediately `docker exec mft-postgres psql -U mft -d mft -c "SELECT COUNT(*) FROM config_event_outbox WHERE published_at IS NOT NULL"`
3. Wait 2s, repeat

**Expect:**
- `published_at` column populated for the create event within 2s
- No rows stuck with `published_at IS NULL` for more than 5s (unless RabbitMQ is down)

### 4b. ShedLock prevents duplicate publish under HA

*(Only if you can run 2+ onboarding-api replicas — skip otherwise.)*

```bash
docker compose up -d --scale onboarding-api=2
```

Create a listener. Verify only ONE copy of `server.instance.created` event appears in consumer logs (not two).

**Expect:** ShedLock table `shedlock` has a row for `outbox_drain` locked by one of the two pods.

---

## 5. Sentinel bind_failed rule (R68)

**Known issue (Flyway collision — deferred):** V66 migration that seeds this rule does not currently apply because platform-sentinel's migrations collide with shared-platform's version numbers. See `docs/run-reports/2026-04-16-R66-R70-feature-test-report.md#5`.

**Until fixed:** manually insert the rule:

```bash
docker exec mft-postgres psql -U mft -d mft -c "
INSERT INTO sentinel_rules (name, analyzer, severity, cooldown_minutes, enabled, builtin, description)
VALUES ('listener_bind_failed', 'PERFORMANCE', 'HIGH', 15, TRUE, TRUE,
  'Active listeners that failed to bind their configured port.')
ON CONFLICT (name) DO NOTHING"
```

Then trigger a bind failure:

1. Create two listeners on the same port via DB direct insert (bypassing the 409 pre-check):
   `docker exec mft-postgres psql -U mft -d mft -c "UPDATE server_instances SET internal_port=2222 WHERE instance_id='sftp-test-2230'"`
2. Trigger reconcile manually: `curl -X POST http://localhost:8080/api/servers/{UUID}/rebind -H "Authorization: Bearer $TOKEN"`
3. Wait 5 min for the Sentinel PerformanceAnalyzer cycle
4. `curl -s http://localhost:8098/api/sentinel/findings -H "Authorization: Bearer $TOKEN" | jq '.[] | select(.ruleName=="listener_bind_failed")'`

**Expect:** Finding appears with `severity=HIGH` and the affected instance IDs in `evidence.failedListeners`.

---

## 6. Scheduler config validation (R69)

```bash
# Missing command → 400
curl -s -X POST http://localhost:8084/api/scheduler \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"bad-pgp-task","cronExpression":"0 0 7 * * *","taskType":"EXECUTE_SCRIPT","config":{}}' | jq .
```

**Expect:** HTTP 400 with `"EXECUTE_SCRIPT task 'bad-pgp-task' requires config.command (e.g. 'check-pgp-expiry'). Without it the scheduler will fail at run time."`

```bash
# Missing referenceId for RUN_FLOW → 400
curl -s -X POST http://localhost:8084/api/scheduler \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"bad-flow-task","cronExpression":"0 0 7 * * *","taskType":"RUN_FLOW"}' | jq .
```

**Expect:** HTTP 400 with "RUN_FLOW task requires referenceId (the flow UUID)".

---

## 7. End-to-end VFS file flow through a NEW dynamic listener

Purpose: prove that a listener created at runtime via the UI supports the full flow pipeline identical to the primary.

1. Create a VIRTUAL SFTP listener on port 2240 via the UI (step 1d successful path)
2. Assign an existing account to it: `curl -X POST http://localhost:8080/api/servers/{UUID}/accounts/{ACCOUNT_UUID} -H "Authorization: Bearer $TOKEN"`
3. Upload a file: `sftp -P 2240 acme-sftp@localhost <<< "put /tmp/test.edi"`
4. Check Activity Monitor: `curl -s http://localhost:8080/api/activity-monitor -H "Authorization: Bearer $TOKEN" | jq '.[0]'`

**Expect:**
- Upload succeeds
- Activity Monitor shows a new entry within 5s
- Flow execution triggers matching flows (`flow_executions` table gets a row)
- VFS entry created (`virtual_entries` table)
- If flow has SCREEN → CONVERT_EDI → COMPRESS → MAILBOX: all steps COMPLETED

**This proves dynamic listeners inherit identical file-flow wiring as the primary.**

---

## 8. Known issues NOT to worry about (yet)

These are documented gaps — tester should NOT spend time diagnosing:

- **Sentinel V66 rule inert** — Flyway cross-module version collision. Deferred. Use manual insert from §5.
- **ftp-web listeners show `bind_state=UNKNOWN`** — ftp-web has no dynamic-listener registry yet. Not a bug, expected.
- **`bound_node` may be blank on cold-boot-created rows** — the reconciler doesn't populate it yet; only the primary self-reporter does. Dynamic listeners will show blank until they hit a reconciliation cycle with the fix.

---

## 9. Regression guard — cold-boot inventory

After everything above, re-run the sanity validation script. Any item in the PASS column that flips to FAIL is a regression. Specifically watch:

| Check | Pre-R72 | Post-R72 expected |
|---|---|---|
| `mft-db-migrate` exit code | **1** (broken) | **0** (fixed) |
| Restart loops | Possible | 0 across the board |
| `bind_state` on primary listeners | UNKNOWN | BOUND |

Report any deviations with container name + logs + DB state.

---

## What to file if something fails

1. Container logs: `docker logs mft-<service> --tail 500 > /tmp/<service>.log`
2. DB state: `docker exec mft-postgres pg_dump -U mft -d mft --data-only -t server_instances -t config_event_outbox > /tmp/db.sql`
3. RabbitMQ queue state: `docker exec mft-rabbitmq rabbitmqctl list_queues name messages consumers > /tmp/rabbit.txt`
4. Artifacts dir + short description of what was expected vs observed.

Drop in `docs/run-reports/` as `YYYY-MM-DD-<feature>-<bug>.md`.
