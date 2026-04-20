# R134p — ❌ No Medal: new `https-service` dead-on-arrival + AS2 bind-state still UNKNOWN

**Commit verified:** `3a2ba050` *(R134p)* — plus R134m + R134n + R134o pulled in one cycle
**Verification date:** 2026-04-20 (session cover)
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 34 / 35 containers healthy; 1 of 23 microservices never started (https-service, ExitCode 128 network bind error)
**Verdict:** ❌ **No Medal** — per the whole-platform rubric, "any broken microservice = No Medal, not downgrade". R134o introduced a new `https-service` and it can't start. R134p's AS2 bind-state claim also doesn't land. Both are headline deliverables of this cycle.

---

## Why No Medal

Three disqualifying findings, any one of which bars the medal:

1. **`mft-https-service` never started (R134o regression)** — new microservice, port conflict with `mft-api-gateway` on 127.0.0.1:443. Inspect shows `State=created, ExitCode=128, Error="Bind for 127.0.0.1:443 failed: port is already allocated"`. Zero runtime coverage of the entire R134o feature surface (dynamic TLS listeners, VFS upload path). The service was designed, coded, tested, documented — then DOA because the host-port allocation conflicts with the existing api-gateway. Under the strict rubric, this alone is No Medal.

2. **AS2 bind_state still UNKNOWN (R134p claim fails)** — R134p commit msg states: *"ServerInstanceEventConsumer — new. Consumes ServerInstanceChangeEvent for Protocol.AS2 / AS4, writes bind_state via BindStateWriter on CREATED / ACTIVATED / UPDATED / DEACTIVATED / DELETED. @ApplicationReadyEvent bootstrap() scans existing active rows and marks them BOUND on startup."* Actual: `as2-server-1` still shows `bind_state=UNKNOWN` after stack boot. Zero `ServerInstanceEventConsumer` log activity in as2-service. The consumer didn't run; the promised visibility fix isn't landing.

3. **4 of 10 listeners in UNKNOWN bind_state** — systemic listener-observability gap that crosses R134m/o/p:

   ```
   as2-server-1      | AS2     | 10080 | UNKNOWN   ← R134p miss
   ftp-1             | FTP     | 21    | BOUND
   ftps-server-1     | FTP     | 990   | BOUND
   ftps-server-2     | FTP     | 991   | BOUND
   ftp-2             | FTP     | 2121  | UNKNOWN   ← secondary FTP not binding
   ftpweb-1          | FTP_WEB | 8083  | BOUND
   ftpweb-2          | FTP_WEB | 8098  | UNKNOWN   ← secondary FTP_WEB, R134m scope
   ftp-web-server-2  | FTP_WEB | 8183  | UNKNOWN   ← secondary FTP_WEB, R134m scope
   sftp-1            | SFTP    | 2222  | BOUND
   sftp-2            | SFTP    | 2223  | BOUND
   ```

   SFTP clean. FTP has a secondary-port gap. FTP_WEB secondary rows remain UNBOUND (R134m's "secondary block" may or may not be the intended outcome — unclear without form/UX evidence).

---

## What held (regression-check)

| Thing | Prior evidence | This cycle | Verdict |
|---|---|---|---|
| BUG 13 real path (R134k flagship) | 500 UnresolvedAddress on SftpForwarderService.forward:46 | Reproduced identically on R134j regression flow, step 1 FILE_DELIVERY returns 500 | ✅ holds |
| CHECKSUM_VERIFY step before FILE_DELIVERY (Gap C resolution) | OK | OK | ✅ holds |
| globalbank-sftp VIRTUAL storage (R134i) | SFTP upload to /inbox works with VFS | Reproduced — `VirtualSftpFileSystemProvider` in `ls` output | ✅ holds |
| Password warning suppression (R134l #1) | 0 on all 5 services | Reproduced — 0 occurrences | ✅ holds |
| /actuator/info unauth 200 (R134l #2) | Across onboarding-api + 5 others | Reproduced on :8080/8084/8086/8089/8091/8098 | ✅ holds |
| Vault retirement Phase 1 (R134l #4) | 12 third-party deps on default | Reproduced — 12 (postgres, redis, rabbitmq, redpanda, spire-server, spire-agent, minio, prometheus, loki, grafana, alertmanager, promtail) | ✅ holds |
| Admin UI API smoke (login + list servers/accounts/partners/delivery-endpoints) | all 200 | all 200 | ✅ |
| SPIRE enabled by default | `SPIFFE_ENABLED=true` in services | Reproduced | ✅ holds |
| R134n `securityProfileId` in ServerInstanceResponse | — | field present, default null | ✅ API layer |

---

## demo-onboard — R134p's D1/D2/D3 partially land

| Cycle | Failed | Total | Success % |
|---|---|---|---|
| R134l | 144 | 1100 | 86.9% |
| **R134p** | **86** | **1084** | **92.1%** |

+58 fewer failures. The residual 86 break down (from server-side log harvest during the run):

- **D1 (wrong-URL /api/servers)** — 52 → 0 ✅ (line 1165 now POSTs to `/api/legacy-server-configs`, verified in source)
- **D2 (uq_policy_server duplicate)** — 34 → 2 ✅ (loop cap in source; 2 residual edge-cases)
- **D3 (scheduled task missing fields)** — ~9 → ~8 on RUN_FLOW referenceId, 0 on others ⚠️ (partial — RUN_FLOW case still missing flow UUID lookup)
- **Still present:** a mix of duplicate "already exists", partner-slug conflicts, 1x `No handler for GET /api/flows` on onboarding-api (route missing?)

92.1% just clears the rubric's 90% floor. Not a quality bar Roshan would ship at but not a medal disqualifier on its own.

---

## Holistic whole-platform sweep

### 23 microservices expected, 22 actually running

```
mft-ai-engine                 healthy
mft-alertmanager              healthy
mft-analytics-service         healthy
mft-api-gateway               healthy   ← holds :443 on host
mft-as2-service               healthy   (but listener UNKNOWN)
mft-config-service            healthy
mft-dmz-proxy-internal        healthy
mft-edi-converter             healthy
mft-encryption-service        healthy
mft-forwarder-service         healthy
mft-ftp-service               healthy
mft-ftp-web-service           healthy
mft-ftp-web-ui                healthy
mft-gateway-service           healthy
mft-keystore-manager          healthy
mft-license-service           healthy
mft-notification-service      healthy
mft-onboarding-api            healthy
mft-partner-portal            healthy
mft-platform-sentinel         healthy
mft-screening-service         healthy
mft-sftp-service              healthy
mft-storage-manager           healthy
mft-ui-service                healthy
mft-https-service             CREATED     ← never started, :443 bind conflict
```

### Protocol smoke

| Protocol | Listener | Surface test | Result |
|---|---|---|---|
| SFTP | :2222 | `sftp -P 2222 globalbank-sftp@localhost put /inbox/*.regression` | ✅ upload + BUG 13 flow path |
| FTP (primary) | :21 | `nc localhost 21` | ⚠️ empty response (no banner); needs lower-level check |
| FTP (secondary) | :2121 (ftp-2) | — | ❌ UNKNOWN bind state |
| FTP_WEB (primary) | :8083 | `/actuator/health` 200 | ✅ healthy |
| FTP_WEB (secondary) | :8098, :8183 | — | ❌ UNKNOWN bind state (by design per R134m? unclear) |
| AS2 | :10080 (mapped from 8094) | `/actuator/health` 200 on service | ⚠️ service up, listener UNKNOWN |
| HTTPS | :443 (new) | — | ❌ service never started |

### CLAUDE.md invariants quick-scan

| Invariant | Check | Verdict |
|---|---|---|
| SPIRE mandatory / default ON | `SPIFFE_ENABLED=true` in sftp-service env | ✅ |
| Fail-fast services (config/encryption/screening/storage) | all 4 healthy at steady state (no boot failures) | ✅ (implicit — they booted) |
| Graceful degradation (ai-engine 3rd-party feeds) | ai-engine logs 401 from URLhaus + ThreatFox, service still healthy | ✅ honours graceful-degradation rule |
| Circuit breaker on ResilientServiceClient | not directly exercised this run | — (untested) |

---

## Executive takeaway

R134p **did** improve the platform in measurable ways: BUG 13 flagship still holds, demo-onboard 144→86 failures, Vault retirement sticky, password warnings clean. But the cycle's headline deliverables — R134o's brand-new https-service and R134p's AS2 bind-state consumer — both ship broken on a fresh install. Given that, No Medal is mechanical under the rubric.

### Quickest path back to Silver+

1. **Fix https-service port collision** — either (a) move https-service's host-port from `:443` to a different number and put nginx/gateway in front, or (b) teach api-gateway to not claim `:443` when https-service is present, or (c) configure the new service not to publish a host port at all (internal network only).
2. **Debug AS2 ServerInstanceEventConsumer** — grep `as2-service` startup logs; probably a `@Component` scan path or `@RabbitListener` / `@KafkaListener` wiring gap preventing the consumer from registering. The consumer writes a log line — none fired this run → it never loaded.
3. **Fix ftp-2 and ftpweb-* secondary bind** — same pattern for each: secondary rows exist in DB, but no service records a bind attempt. Could be a one-bean-per-service wiring limit that R134m was supposed to address.

After those three: re-fire this verification. Silver is then attainable once everything runs end-to-end; Gold still requires the third-party-dep footprint to shrink further (12 remaining).

---

**Report author:** Claude (2026-04-20 session). No Medal per strict rubric.
