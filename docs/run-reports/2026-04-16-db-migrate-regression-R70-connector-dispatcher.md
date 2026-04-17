# db-migrate container fails to start — R70 ConnectorDispatcher DI regression

**Date:** 2026-04-16
**Trigger:** Full cold-boot sequence after pulling R67→R70 (fixes to the previous
[V64 boot regression](2026-04-16-boot-regression-V64-dynamic-listeners.md)).
**Severity:** MEDIUM — user-facing services boot healthy and run migrations
themselves (Flyway picks them up from every service's classpath), so there is
no production outage. The dedicated `mft-db-migrate` one-shot container,
however, has been silently broken: it exits with code 1 on every cold boot.

---

## 1. What we saw

Cold-boot sequence at R70:

| Step | Duration | Exit |
|---|---:|---|
| mvn clean package -DskipTests | 75 s | 0 |
| docker compose build --no-cache (26 images; 1 buildx 502 → retry) | 63 s | 0 |
| docker compose down -v (safety) | 0 s | 0 |
| docker compose up -d | 89 s | 0 |
| All runtime services healthy (zero restarts, no Restarting flaps) | **108 s** | ✅ |
| `mft-db-migrate` | — | **Exited (1)** |

All 23 runtime Spring services are `healthy` with `RestartCount=0`. The V64
restart-loop regression is fully fixed by R67 (seeding upsert) and R68
(listener reconciliation / fail-open registry).

`mft-db-migrate` is the only failing unit. It runs the same `onboarding-api`
JAR with `SPRING_MAIN_WEB_APPLICATION_TYPE=none` plus `spring.flyway.enabled=true`
([docker-compose.yml:387-406](../../docker-compose.yml#L387-L406)) and is
supposed to apply Flyway migrations centrally before anything else boots.

## 2. Root cause

From `docker logs mft-db-migrate`:

```
APPLICATION FAILED TO START

Description:
Parameter 2 of constructor in
  com.filetransfer.shared.routing.GuaranteedDeliveryService required a bean
  of type 'com.filetransfer.shared.connector.ConnectorDispatcher' that could
  not be found.

Action:
Consider defining a bean of type 'com.filetransfer.shared.connector.ConnectorDispatcher'
in your configuration.
```

The two relevant classes:

**[ConnectorDispatcher](../../shared/shared-platform/src/main/java/com/filetransfer/shared/connector/ConnectorDispatcher.java#L32-L36)** — conditional on an environment flag:

```java
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    name = "platform.connectors.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class ConnectorDispatcher { ... }
```

**[GuaranteedDeliveryService](../../shared/shared-platform/src/main/java/com/filetransfer/shared/routing/GuaranteedDeliveryService.java#L36-L49)** — unconditional `@Service` with a hard-required constructor dependency:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class GuaranteedDeliveryService {
    ...
    private final ConnectorDispatcher connectorDispatcher;
    ...
}
```

**Mismatch:** `ConnectorDispatcher` is gated off by default
(`matchIfMissing=false`), but `GuaranteedDeliveryService` treats it as a
required constructor parameter with no `Optional` / `ObjectProvider` wrapper
and no matching conditional.

Why runtime services don't hit this: the `&common-env` anchor in docker-compose
sets `PLATFORM_CONNECTORS_ENABLED: "true"`, which turns the conditional on, so
the bean exists when Spring wires `GuaranteedDeliveryService`. `db-migrate`'s
environment block does **not** merge `&common-env` (it only sets 5 DB/Flyway
variables), so `platform.connectors.enabled` defaults to false, the bean is
absent, and the required dependency can't be satisfied.

The regression landed in R70 (connector-dispatcher wiring arrived along with
the keystore-rotation consumers). Before R70, either this edge didn't exist
or the dependency was optional.

## 3. Impact

- **Operational**: low. Every runtime service runs Flyway on its own
  classpath at boot, so migrations do apply. db-migrate's role as a
  deterministic-first-migrator is currently ceremonial.
- **Deterministic-ordering guarantee**: broken. The design intent of
  `db-migrate` (run migrations once, then everyone else boots against a
  known schema) is silently bypassed. On the next pull that adds a migration
  sensitive to ordering (concurrent DDL, `CREATE INDEX CONCURRENTLY`, etc.),
  we'll rediscover the 2026-04-11 V42 Flyway-lock issue noted in
  `docker-compose.override.yml`.
- **CI signal**: any cold-boot test that trusts `docker compose up -d` exit
  code or "all runtime services healthy" will miss this. Exit 1 on a
  `restart: "no"` container shows up in `docker ps -a`, not in the up-d
  return code.

## 4. Fix (preferred → alternatives)

### Preferred — make the dependency actually optional

In `GuaranteedDeliveryService`, change the field type so Spring can wire in
`Optional.empty()` when the conditional bean is off:

```java
private final ObjectProvider<ConnectorDispatcher> connectorDispatcher;
```

Or equivalently, `Optional<ConnectorDispatcher>`. Call sites at lines 210 and
254 then become:

```java
connectorDispatcher.ifAvailable(d -> d.dispatch(...));   // ObjectProvider
// or
connectorDispatcher.ifPresent(d -> d.dispatch(...));     // Optional
```

This is the right fix because it matches the declared intent of
`@ConditionalOnProperty(..., matchIfMissing=false)` on the dispatcher: the
rest of the platform must keep working when connectors are off.

### Alternative A — turn connectors on for db-migrate

Add `PLATFORM_CONNECTORS_ENABLED: "true"` to db-migrate's environment block.
Papers over the bug but doesn't address the `@ConditionalOnProperty` contract
violation. Any other minimal-profile deployment (tests, CI bootstrap, a
future admin CLI reusing the same JAR) will re-hit this.

### Alternative B — gate GuaranteedDeliveryService on the same conditional

Annotate `GuaranteedDeliveryService` with
`@ConditionalOnProperty(name="platform.connectors.enabled", havingValue="true")`.
Simplest, but semantically wrong — `GuaranteedDeliveryService`'s value isn't
limited to connector delivery; it also drives RabbitMQ retry and the DLQ
path. Disabling it just because connectors are off is too broad.

## 5. Recommendations

1. **P0** — apply the preferred fix (ObjectProvider). Unit test:
   `GuaranteedDeliveryService` wires and runs with `ConnectorDispatcher` absent.
2. **P1** — add a boot-regression assertion: after `docker compose up -d`,
   any container in `docker ps -a --filter status=exited` with exit code ≠ 0
   is a test failure. This is the exact CI gate that would have caught both
   this regression and the earlier V64 one.
3. **P2** — audit other `@ConditionalOnProperty` beans under `shared/` for
   the same "conditional producer / unconditional consumer" pattern. Grep
   for `@ConditionalOnProperty` and cross-check constructor params of
   `@Service`/`@Component` classes that reference them.

## 6. Signals elsewhere in this run

Unrelated to the db-migrate bug, captured for completeness:

- **Buildx transient 502** during `docker compose build --no-cache` — the
  first attempt failed immediately (1 s, `listing workers for Build: failed
  to list workers: Unavailable: ... unable to upgrade to h2c, received 502`).
  Retry succeeded in 63 s with no config change. Watch for this; if it
  becomes recurrent we can pin the builder or fall back to `DOCKER_BUILDKIT=0`.
- **promtail** shows "Up" without a healthcheck (no health signal configured
  on that image). Not a regression, but a watcher that greps for `(healthy)`
  will undercount by one — noted so future health monitors expect it.
