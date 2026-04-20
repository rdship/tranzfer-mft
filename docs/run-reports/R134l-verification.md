# R134l — 🥉 Bronze: BUG 13 holds, but AS2 auto-seed broken + demo-onboard still 13% fail + compose-profile trap

**Commit verified:** `fb8f0285` — *R134l: close R134k open queue (4/5 done) + Vault retired as optional*
**Branch:** `main`
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 34 healthy / 35 total (Vault gone: was 36 total in R134k)
**Verdict:** 🥉 **Bronze**. BUG 13 flagship still closed (R134k regression holds) and 3 cosmetic/ops cleanups shipped. But R134l's main *functional* claim — AS2 auto-seed so Healthcare Compliance is reachable from a fresh stack — ships only half (row seeded, nothing binds). Gap D diagnostic delivered but demo-onboard still fails on 144 of 1100 seeds (13%). And the Vault retirement introduced a compose-profile trap that nukes the whole stack if an operator runs `docker compose --profile vault down`. Too many user-facing issues for Silver.

---

## Scorecard

| R134l ask | Verdict | Evidence |
|---|---|---|
| #1 Suppress "Using generated security password" on the 4 services | ✅ PASS | grep `docker compose logs` for each — all return 0 |
| #2 `/actuator/info` standardized (onboarding-api permitAll) | ✅ PASS | curl :8080/actuator/info → 200 unauth; same on :8084, :8086, :8089, :8091, :8098 |
| #3 AS2 listener auto-seeded (Healthcare Compliance reachable from fresh stack) | ⚠️ **PARTIAL** | Row seeded (`as2-server-1, port 10080`) but **bind_state stays UNKNOWN after 60s + service restart**; as2-service has zero bind-related log activity. See "Regression: AS2 bind" below. |
| #4 Vault retirement Phase 1 (optional profile) | ✅ PASS | no vault container on default profile; `docker compose --profile vault up -d vault` brings it back; total 3rd-party-dep containers 13 → 12 |
| — Gap D diagnostic (tester ask-back) | ✅ DELIVERED | Top-3 error classes identified, ~95 of 144 failures attributed to specific root causes |
| — BUG 13 flagship (R134k) regression | ✅ HOLDS | same `HTTP 500 UnresolvedAddressException` as R134k — auth passes, controller reached |

---

## Cleanup #1 — "Using generated security password" suppressed

```
storage-manager                0
encryption-service             0
external-forwarder-service     0
screening-service              0
edi-converter                  0  (already was 0 from R134k)
```

All 5 services clean.

## Cleanup #2 — `/actuator/info` unauth access standardized

| Service | Port | Prior (R134h) | R134l |
|---|---|---|---|
| onboarding-api | 8080 | 403 unauth | **200 ✅** |
| config-service | 8084 | 200 | 200 |
| encryption-service | 8086 | 200 | 200 |
| license-service | 8089 | 200 | 200 |
| ai-engine | 8091 | 200 | 200 |
| platform-sentinel | 8098 | 200 | 200 |

`PlatformVersionInfoContributor` payload verified on each: `{"build":{"version":"1.0.0-R134l","timestamp":"...","service":"..."}}`.

## Cleanup #4 — Vault retirement Phase 1

```
$ docker ps | grep -i vault
(empty)

$ docker compose ps --format '{{.Name}}' | wc -l
35             (was 36 with vault)

$ docker compose --profile vault up -d vault
  Container mft-vault Created
  Container mft-vault Starting
  Container mft-vault Started
  mft-vault Up 5 seconds (health: starting)

$ docker compose --profile vault stop vault && docker compose --profile vault rm -f vault
  (vault removed cleanly)
```

Third-party runtime dep count on default profile: **12** (was 13). Remaining: postgres, redis, rabbitmq, redpanda, spire-server, spire-agent, minio, prometheus, loki, grafana, alertmanager, promtail.

### Warning — operational gotcha

`docker compose --profile vault down` (without `--volumes`) **brought down the entire stack**, not just vault. It seems to remove the shared `mft-network`, which triggers all dependent containers to die too. Learned this the hard way during verification. Safer commands:

```bash
# To stop JUST vault
docker compose --profile vault stop vault
docker compose --profile vault rm -f vault

# NEVER run:
docker compose --profile vault down         # kills everything
```

Worth a line in the docker-compose.yml comment or a doc note.

---

## Gap D — top error classes identified

`demo-onboard.sh` summary: `Created: 952, Skipped: 4, Failed: 144, Total: 1100`. Server-side log harvest (since all post() calls route to `>/dev/null` on the client side) identified three distinct classes:

### Class D1 — `No handler for POST /api/servers` — 52 occurrences — **config-service**

```
logger: org.springframework.web.servlet.resource.NoResourceFoundException
message: No handler for POST /api/servers: No static resource api/servers.
```

The demo-onboard script is POSTing `/api/servers` to `$CFG` (config-service:8084) in at least one step. That endpoint lives on `$API` (onboarding-api:8080), not config-service. Either:
- A step is accidentally using `$CFG` where it should use `$API`
- Or a helper function has a hard-coded bad URL

Roughly 26 servers × 2 retries in `post()` = 52 — matches exactly.

### Class D2 — `listener-security-policies uq_policy_server duplicate key` — 34 occurrences — **config-service**

```
SQL Error: 0, SQLState: 23505
ERROR: duplicate key value violates unique constraint "uq_policy_server"
Detail: Key (server_instance_id)=(...) already exists
```

`create_listener_security_policies` loops 26 times but there are only ~8-12 unique server_instance_ids (we have 28 server instances; many policies target the same ones). The DB has `UNIQUE (server_instance_id)` — so duplicates fail. Two fixes:
- Use `ON CONFLICT DO NOTHING` / `ON CONFLICT (server_instance_id) DO UPDATE`
- Or make the script cap at one policy per distinct server_instance_id

### Class D3 — scheduled-task BAD_REQUEST — ~9 occurrences — **config-service**

```
400 BAD_REQUEST: PUSH_FILES task requires referenceId (account UUID)      x 2
400 BAD_REQUEST: PULL_FILES task requires referenceId (account UUID)       x 2
400 BAD_REQUEST: RUN_FLOW task requires referenceId (the flow UUID)        x 2
400 BAD_REQUEST: CLEANUP task requires config.path                         x 3
```

The scheduled-tasks creation step emits payloads missing the required `referenceId` (for PUSH_FILES / PULL_FILES / RUN_FLOW) or `config.path` (for CLEANUP). Easy fix in the seed JSON templates.

### Rough math

52 + 34 + 9 = **95** of the 144 failures explained.

The remaining ~49 are probably:
- A handful of auth-token refresh races on long-running steps
- A few Partner or Account slug duplicates (benign; already saw `Partner slug already exists: medtech-solutions` once)
- Some FK violations where a parent row didn't get saved due to one of the above

Closing D1 and D2 alone would drop Failed from 144 to ~58 — significant.

---

## Regression — AS2 listener auto-seed partial

**Claim in R134l commit msg:** *"AS2 listener auto-seeded in bootstrap — PlatformBootstrapService now seeds as2-server-1 (protocol=AS2, port 10080) on first boot."*

**Actual observation:**

```
SELECT instance_id, protocol, internal_port, bind_state, bound_node, bind_error
  FROM server_instances WHERE protocol='AS2';
  as2-server-1 | AS2 | 10080 | UNKNOWN | (null) | (null)
```

After stack boot (waited 60s), and after `docker compose restart as2-service`, bind_state stays `UNKNOWN`. No `bind_error` written either. as2-service logs show normal boot events (`ContextRefreshedEvent`, `ApplicationStartedEvent`, `ApplicationReadyEvent`) but zero entries matching `bind|listener|as2-server-1|10080`.

Hypothesis: as2-service doesn't consume `ServerInstanceChangeEvent` the same way sftp-service and ftp-service do, OR there's a wire-up gap where it doesn't scan existing ServerInstance rows at startup.

**Impact:** Healthcare Compliance and EDI X12 flows (both AS2-sourced) still cannot be exercised via protocol upload from a fresh stack — same blocker as R134k, despite R134l claiming to close it. The `SFTP Delivery Regression` flow (from R134j) still works, so this doesn't block BUG 13 verification.

---

## BUG 13 regression re-fired (clean R134l stack)

One upload:

```
sftp -P 2222 globalbank-sftp@localhost      # password: partner123
  put /tmp/test.regression /inbox/bug13-R134l-<ts>.regression
```

Result:

```
flow:  SFTP Delivery Regression         status: FAILED
step 0 CHECKSUM_VERIFY  OK
step 1 FILE_DELIVERY    FAILED: 500 on POST to external-forwarder-service:8087
       (UnresolvedAddressException on sftp.partner-a.com as intended)
```

HTTP 500 with a DNS-resolution exception in `SftpForwarderService.forward:46` — same signature as R134k. **BUG 13 stays closed on the real flow-engine path.** No regression from the R134l security/auto-config changes.

---

## Open queue after R134l

1. **Gap D close-out** — address D1, D2, D3 per root causes above
2. **AS2 bind** — find why `as2-service` isn't consuming the seeded ServerInstance; fix wire-up so `bind_state=BOUND`
3. **Compose profile gotcha documentation** — `docker compose --profile vault down` without flags kills everything
4. **R&D plan Phase 2** — retire another third-party dep (candidates: promtail/loki, or alertmanager)
5. Remaining high-risk gates are unchanged (third-party-dep footprint, schema drift, Gap D volume)

---

**Report author:** Claude (2026-04-20 session). BUG 13 flagship still closed; R134l is net incremental progress with one regression surfaced.
