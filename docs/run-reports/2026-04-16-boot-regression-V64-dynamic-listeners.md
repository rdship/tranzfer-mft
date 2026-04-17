# Boot Regression — V64 dynamic-listener introduces restart loops on cold boot

**Date:** 2026-04-16
**Trigger:** Full cold-boot sequence — `git pull && mvn clean package -DskipTests && docker compose build --no-cache && docker compose down -v && docker compose up -d`
**Released changes in scope:** V64 migration + dynamic SFTP/FTP listener registries (pulled in this run; 47 files / +1149 / -32).
**Severity:** HIGH — `ftp-service` and `onboarding-api` never reach a stable running state after a fresh cold boot.

---

## 1. Boot-up timing

| Step | Duration | Exit |
|---|---:|---|
| git pull | 1 s | 0 |
| mvn clean package -DskipTests | 76 s | 0 |
| docker compose build --no-cache (26 images) | 68 s | 0 |
| docker compose down -v (safety) | 0 s | 0 |
| docker compose up -d | 95 s | 0 |
| "Healthy window" window after up | ~173 s | — |

Total wall-clock to perceived-green: ~7 m 13 s. Real state: two services in a restart loop that did not break inside the observation window.

## 2. What we saw

| Signal | Value |
|---|---|
| Containers running / total | 35 / 36 (db-migrate one-shot exited cleanly — expected) |
| `mft-sftp-service` restarts | **0** |
| `mft-ftp-service` restarts | **14** (still climbing at time of writing) |
| `mft-onboarding-api` restarts | **14** (still climbing at time of writing) |
| `mft-as2-service` | **recovered** after one transient UNHEALTHY window |
| All other services | healthy, 0 restarts |

Key monitor artifact: between restart cycles, the affected containers briefly occupy the `Restarting` status — which is neither `healthy`, `unhealthy`, nor `health: starting`. A naive health watcher (mine was one) that greps for those three states will report ALL_HEALTHY at exactly the moment a restart-looping service is between incarnations. **Any health watcher we ship must count `Restarting` as a non-terminal state.**

## 3. Root causes — two distinct bugs, both from V64

### BUG-V64-A — ftp-service `BindException` on port 2121

Stack trace (from `mft-ftp-service` app log, repeated every ~20 s across 14 restarts):

```
Application run failed
java.net.BindException: Address already in use
  at sun.nio.ch.Net.bind0
  ...
Wrapped by: java.io.IOException: Error while binding on /0.0.0.0:2121
  at o.a.m.t.socket.nio.NioSocketAcceptor.open(NioSocketAcceptor.java:270)
Wrapped by: o.a.f.FtpServerConfigurationException:
  Failed to bind to address /0.0.0.0:2121, check configuration
  at o.a.f.listener.nio.NioListener.start(NioListener.java:166)
  at o.a.ftpserver.impl.DefaultFtpServer.start(DefaultFtpServer.java:80)
  at c.f.ftp.server.FtpServerConfig.lambda$ftpServerRunner$0(FtpServerConfig.java:204)
```

The ftp-service is configured in `docker-compose.yml` for `FTP_PORT: 21` and `FTP_PASSIVE_PORTS: 21000-21010`. There is no compose-level port `2121`. The bind target `0.0.0.0:2121` is being produced from somewhere inside the new `FtpListenerRegistry` / `FtpServerBuilder` — almost certainly by reading multiple `server_instances` rows from the DB and attempting to bind all of them in the same process, with two rows resolving to the same `(host, port)` tuple inside the container.

**Fix direction:** `FtpListenerRegistry.start()` must (a) de-dupe by `(internal_host, internal_port)` before binding, (b) bind each listener in a try/catch that records `bind_state='BIND_FAILED'` + `bind_error` on the row instead of crashing the whole Spring context. `V64__dynamic_listeners.sql` already added `bind_state` / `bind_error` columns exactly for this reason — but the registry isn't using the graceful-degradation path yet.

Evidence of the intended design (good): [V64 migration lines 30-34](../../shared/shared-platform/src/main/resources/db/migration/V64__dynamic_listeners.sql#L30-L34) adds `bind_state` + `bind_error`. The intent is that a failed bind is recorded, not fatal.
Evidence of the gap (bad): `FtpServiceApplication.main → SpringApplication.run → callRunners → FtpServerConfig.ftpServerRunner` propagates the bind failure out of the runner, which marks the Spring context as failed and exits the process.

### BUG-V64-B — onboarding-api bootstrap races the runtime listener registration

Stack trace (from `mft-onboarding-api` app log):

```
Application run failed
org.postgresql.util.PSQLException: ERROR: duplicate key value violates
  unique constraint "uk_server_instance_host_port_active"
  Detail: Key (internal_host, internal_port)=(sftp-service, 2222) already exists.
Wrapped by: o.h.e.ConstraintViolationException:
  could not execute batch [Batch entry 0 insert into server_instances ...]
```

Source of the conflicting insert:
[`PlatformBootstrapService.seedServerInstances()`](../../onboarding-api/src/main/java/com/filetransfer/onboarding/bootstrap/PlatformBootstrapService.java#L146-L155) at onboarding-api lines 146–155 seeds `sftp-server-1` with `internal_host=sftp-service`, `internal_port=2222`. At the same time, the new `SftpListenerRegistry` in `sftp-service` is inserting its own registration for the same `(sftp-service, 2222)` tuple. Whichever container starts first wins; the loser crashes the Spring context with a ConstraintViolationException. In this run sftp-service won (0 restarts), onboarding-api lost (still looping).

The partial unique index `uk_server_instance_host_port_active` (V64 lines 38-40) is correct as a safety rail — two active listeners *shouldn't* claim the same port. The bug is that the seeding path does a raw `INSERT` instead of an upsert, and it doesn't coordinate with the runtime registry.

**Fix direction:** one of:
1. Move all server_instance seeding out of `PlatformBootstrapService` — each service registers its own listener via the new registry; onboarding-api only seeds *accounts*, *partners*, *endpoints*, *flows*, not server_instances. (Preferred — single writer, clear ownership.)
2. Make `seedServerInstances()` an idempotent upsert (`ON CONFLICT (internal_host, internal_port) WHERE active = true DO NOTHING`).
3. Gate the bootstrap seed behind a feature flag that is off by default in docker-compose, on only for demo mode.

## 4. Secondary observation — healthcheck tuning

Distinct from the two V64 bugs above:

- `as2-service` has its own healthcheck block (`docker-compose.yml` line 947) with `start_period: 30s`. Actual cold-boot time is ~100 s+ (Flyway + Hibernate + Kafka wiring). Result: Docker flagged the container UNHEALTHY at t+56s for a ~30s window before recovery. No restart, but the window spooks monitoring.
- Shared `x-service-healthcheck` anchor at line 105 uses `start_period: 600s` — which *is* correctly forgiving. Services using the anchor (ftp-service, onboarding-api, sftp-service, ...) are *not* being killed by the healthcheck; they're being killed by their own app crashes. The healthcheck is not the problem for the restart loops.

**Fix direction:** align as2-service's `start_period` with the shared anchor (600 s), or simply change `as2-service` to use `<<: *healthcheck` like the other Spring services. Low urgency — cosmetic compared to the V64 bugs.

## 5. Recommendations, in priority order

1. **P0 — BUG-V64-A (ftp-service bind failure).** Make `FtpListenerRegistry.start()` bind-each-listener in its own try/catch and record `bind_state='BIND_FAILED'` per row instead of throwing out of the `ftpServerRunner`. This is exactly what V64 columns were added for.
2. **P0 — BUG-V64-B (onboarding-api seed race).** Remove `seedServerInstances()` from bootstrap, or make it an idempotent upsert. Single writer.
3. **P1 — Re-run the cold-boot sequence** after 1 and 2 are merged; capture restart counts (should be 0 across the board) and update this report.
4. **P1 — Harden boot-up observer.** Any health watcher must explicitly detect `Restarting` status, not just `healthy` / `unhealthy` / `health: starting`. My monitor reported ALL_HEALTHY while two services were in a restart loop — silent failure disguised as success.
5. **P2 — as2-service healthcheck `start_period` tuning.** Minor.
6. **P2 — Add a boot-regression test.** A cold-boot test that runs for 5 min after `up -d` and fails if any container's `RestartCount > 0` would have caught this in CI. Current harness trusts `docker compose up -d` exit code, which is 0 even when a service is looping.

## 6. Raw evidence

- Pre-nuke inventory: 40 containers / 22 volumes / 5 networks / 37 images (reclaimed ~9 GB on nuke).
- Post-up health progression (from monitor stream): t=1s 17/36 → t=75s 19/36 → t=81s 22/36 → t=88s 25/36 → t=100s 27/35 (db-migrate exit) → t=112s 32/35 (as2 recovered) → t=173s 33/35 "ALL_HEALTHY" (false-positive — missed Restarting state) → t=~6 min 14 restarts and counting.
- Shutdown trigger in failing containers: Spring context `Application run failed` → ordered bean destruction → `Onboarding-Pool - Shutdown completed.` → process exit → docker restart.
- `spire-*`, `postgres`, `redpanda`, `rabbitmq`, `redis`, `vault`, `loki`, `prometheus`, `grafana`, `alertmanager`, `promtail`: healthy, not implicated.

## 7. What NOT to do

- Do **not** "just wait longer" — 14+ restarts in a row is not a warmup, it's a loop. Neither the bind failure nor the constraint violation is self-resolving.
- Do **not** `docker compose restart` the affected services as a workaround — the race has the same outcome every cold boot.
- Do **not** delete rows from `server_instances` manually — that masks the seeding race. Fix the seed path.
- Do **not** drop `uk_server_instance_host_port_active` — the constraint is load-bearing. The bug is the path that violates it.
