# R134A + R134B runtime verification — ❌ Blocked by R134w V95 migration SQL bug

**Commits tested:** `1d7a7de3` (R134B, latest on main)
**Date:** 2026-04-20
**Verdict up front:**
- **Product state at R134A / R134B checkpoint: ❌ No Medal** — platform cannot boot. V95 migration fails on real PostgreSQL; db-migrate exits 1; as2-service exits 1; 16 of 32 Java services stuck in permanent `starting` state because their own Flyway hits the same V95 error.
- **R134A contribution: ⏳ Unverifiable** (revised from pre-runtime 🥈 Silver — neither the https-service :443 fix nor the AS2/FTP/FTP_WEB observability instrumentation can be exercised while the stack can't boot). Moves to ❌ No Medal as a contribution for this cycle because "code that ships into an un-bootable stack hasn't shipped."
- **R134B contribution: ⏳ Unverifiable** (same reason — encryption-service's now-correct YAML is academic when encryption-service is stuck in `starting` waiting on a migration that will never succeed). Also ❌ No Medal.
- **R134w retroactive contribution: ❌ No Medal** (was 🥈 Silver pre-runtime yesterday). The V95 migration is the root cause; R134w's rate-limit work never runs on real PG.

---

## Root cause — V95 partial index uses `now()` in predicate

[shared/shared-platform/src/main/resources/db/migration/V95__rate_limit_buckets.sql:62](../../shared/shared-platform/src/main/resources/db/migration/V95__rate_limit_buckets.sql#L62) generates this DDL per-partition:

```sql
CREATE INDEX IF NOT EXISTS idx_%I_recent
    ON %I (window_start)
    WHERE window_start > (now() - INTERVAL '2 hours')
```

PostgreSQL's exact error (reproduced directly against `mft-postgres`):

```
ERROR:  functions in index predicate must be marked IMMUTABLE
```

`now()` is STABLE, not IMMUTABLE — PG rejects it in partial-index WHERE clauses because the index's visible-rows set would drift as wall-clock time advances. This is a deterministic failure; every boot hits it.

---

## Why this wasn't caught

R134w commit message says `mvn test` passed. It did — but `mvn test` runs unit tests that use H2 or testcontainers with permissive mode, not real PostgreSQL. The V95 script's `DO $$` block with `EXECUTE format(...)` is only evaluated when Flyway actually runs it in Postgres; offline static analysis and unit-test runs don't reach the evaluation.

**This is the same class of gap that sent R134t (mvn compile OK but mvn package failed on AOT) and R134v (mvn package OK but runtime failed on YAML) No Medal.** The verification bar for migration SQL is: "run it against a real PostgreSQL instance in the test stack". Nothing less suffices.

---

## Blast radius — every migration V96–V99 is also blocked

Flyway applies migrations in strict order. V95 fails → V96–V99 never run → everything that depends on those schemas is unshipped:

| Migration | Owner tag | Purpose | Status |
|---|---|---|---|
| V95 `rate_limit_buckets` | R134w | PG-backed rate limit counters | ❌ fails on boot |
| V96 `platform_locks` | R134z | VFS distributed lock via storage-manager | ❌ blocked by V95 |
| V97 `cluster_nodes` | R134y | Service registry reader | ❌ blocked by V95 |
| V98 `event_outbox` | (prior) | Outbox for LISTEN/NOTIFY | ❌ blocked by V95 |
| V99 `event_outbox.defer_until` | R134t | Exponential-jitter backoff JSONB column | ❌ blocked by V95 |

Per `flyway_schema_history`, schema is stuck at V94:

```
 version |         description          | success
---------+------------------------------+---------
 94      | server instance https config | t
(stops here; V95 errored and rolled back)
```

Every Sprint 1.5 → Sprint 5 contribution I graded Silver yesterday is now unshipped:
- R134u (spring-jdbc): compiles fine, but the JdbcTemplate code it unblocked (R134w PgRateLimitCoordinator) can't run against a schema that doesn't have V95's table
- R134v (Vault hard-retire): separate concern, unrelated to V95 — Vault retirement itself held fine at the compose level. But encryption-service's YAML fix (R134B) can't be exercised because the service can't boot
- R134w (rate limiter PG counter): the migration fails; zero PgRateLimitCoordinator code runs
- R134x (L2 cache Caffeine): unrelated to V95; Caffeine is in-process, no migration needed. But can't runtime-verify in a stack that won't boot
- R134y (service registry reader): depends on V97 which is blocked by V95
- R134z (VFS lock via storage-manager): depends on V96 which is blocked by V95

**None of R134u–z's production promises are live.**

---

## Current stack state

From `docker compose ps` at verification time:

```
healthy=16       (infra + UIs + api-gateway + dmz-proxy-internal + edi-converter)
starting=16      (Java services stuck on Flyway V95 failure; will never reach healthy)
unhealthy=0      (they're not unhealthy — they're in a perpetual retry loop at app-start)
exited=2         (db-migrate + as2-service, both exit 1)
created=1        (https-service never started — separate issue, see below)
total=35 containers configured
```

### https-service fix (R134A) — can't be verified

`docker inspect mft-https-service` shows `State=created, ExitCode=0, Error=""`. The `:443` port removal *was* done in R134A (confirmed in docker-compose.yml). But the container never actually tried to start because of the cascading failure from db-migrate. Likely the `depends_on: db-migrate: condition: service_completed_successfully` clause holds https-service back. Can't prove R134A's port-conflict fix works until V95 is fixed upstream.

### AS2 observability (R134A) — can't be verified

as2-service crashed at its own Flyway attempt, before any of R134A's new `@PostConstruct`, `@Bean Queue declaration`, `@EventListener bootstrap entry` logs could fire. Observability is real in source but not exercised.

### FTP_WEB markUnbound (R134A) — can't be verified

ftp-web-service is in `starting`, blocked by Flyway. Can't exercise the new explicit `markUnbound()` calls.

### encryption-service YAML fix (R134B) — can't be verified

The YAML rebuild is correct in source (I confirmed via `mvn package -DskipTests` passing AOT). But encryption-service is in `starting` because its own Flyway fails on V95 before the service can progress to Spring Boot startup. Can't prove the YAML round-trip works.

---

## What needs to happen

### The immediate fix — change V95 to not use `now()` in the partial-index predicate

Several options for R134w's intent ("partial index on recent windows"):

1. **Drop the partial index entirely.** A full index on `window_start` per partition is fine — the query planner will use it for time-range queries; the "recent 2 hours" part is just an optimisation. Simplest, lowest risk.

2. **Use a `WITHOUT TIME ZONE` constant.** If V95 must stay partial, use a literal cutoff (but that defeats the rolling-window purpose).

3. **Rewrite the cutoff as a per-partition constraint.** If the partition itself bounds window_start to a known month, the index doesn't need a WHERE clause for the "recent" subset — the partition is already narrow.

Option 1 is the fastest to ship and the least risky. Estimated diff: delete one line + the `DO $$ ... END $$;` wrapper.

### The verification-habit fix

Before pushing ANY migration commit:

```bash
docker compose up -d postgres        # real PG, not H2
docker compose run --rm db-migrate   # actually run Flyway end-to-end
# only push if the migration succeeds
```

R134w's push was gated on `mvn test` — which exercises unit tests, not migrations. A migration that hasn't run against real Postgres hasn't been verified.

---

## Retroactive medal revisions (per strict "revise as evidence develops" rule)

| R-tag | Previous grade | **New grade** | Why |
|---|---|---|---|
| R134w | 🥈 Silver pre-runtime (contribution) | ❌ **No Medal** | Migration fails on real PG; rate-limit work never runs |
| R134y | 🥈 Silver pre-runtime | ❌ **No Medal** | Depends on V97 which is blocked by V95 |
| R134z | 🥈 Silver pre-runtime | ❌ **No Medal** | Depends on V96 which is blocked by V95 |
| R134t | ❌ No Medal (compile) | ❌ **No Medal** (unchanged) | V99 blocked by V95 anyway, so even with spring-jdbc fix the feature doesn't run |
| R134u | 🥈 Silver | 🥉 **Bronze** | Pure pom-dependency fix; still correct at the pom layer. Can't verify end-to-end but the change itself is mechanical |
| R134v | ❌ No Medal (YAML) | ❌ **No Medal** (unchanged) | YAML now fixed by R134B, but Vault retirement work itself still holds (the YAML issue was collateral) |
| R134x | 🥈 Silver pre-runtime | 🥉 **Bronze** | Caffeine L2 cache is an in-process change, doesn't need V95. Can't runtime-verify but code stands |
| R134A | 🥈 Silver pre-runtime | ❌ **No Medal** | Port fix + observability are real in source, unverifiable because V95 blocks stack boot. A fix that can't ship isn't a contribution this cycle |
| R134B | 🥈 Silver pre-runtime | ❌ **No Medal** | Same reasoning |
| R134q | 🥈 Silver (R&D) | 🥈 **Silver** (unchanged) | Pure design doc, no ship dependency |

### Product-state floor

Unchanged at ❌ No Medal, and in fact worse than R134p: the stack no longer boots end-to-end at all. Before R134w, prior blockers (https-service DOA, AS2 UNKNOWN, 4/10 listeners UNKNOWN) meant "stack mostly up, a few things broken". Post-R134w, the stack is catastrophically un-bootable. That's a significant step backwards.

---

## What the dev should prioritise next

1. **Fix V95 first** — this is single-blocker for everything else. Can't test anything else until it's fixed.
2. **After V95 fixes, re-run full whole-platform sweep** — verify V96/V97/V98/V99 all apply in order, verify encryption-service boots with R134B YAML, verify https-service starts with R134A port fix, verify as2-service starts with R134A observability, verify AS2 `bind_state` transitions out of UNKNOWN.
3. **Add a pre-push check to the dev's workflow** — `docker compose run --rm db-migrate` must pass. `mvn test` is not enough. Three cycles in a row (R134t, R134v, R134w) have shipped broken artifacts that mvn test missed.
4. **After verify-sweep is clean**, the next graded cycle could be the first product-state Silver of R134-series. If clean + no regressions + all prior blockers closed.

---

## Honest scope of this verification

What I did:
- Fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build`
- Waited 60s+ for Java services to boot
- Observed stuck state (16/33 stuck in `starting`)
- Investigated db-migrate exit 1
- Read the V95 SQL source and the Flyway error
- Reproduced the error against `mft-postgres` with a minimal CREATE INDEX to prove the pattern is the issue
- Checked `flyway_schema_history` to confirm V95 is the stopping point
- Identified which R-tags' migrations are downstream of V95

What I **did not** do (per the strict bar, these would be gates for any medal climb):
- Protocol smoke (SFTP/FTP/FTP_WEB/AS2/HTTPS) — stack not up
- Admin UI smoke via API — depends on onboarding-api which is in `starting`
- R134j regression flow — requires SFTP + flow-engine both alive
- demo-onboard success rate — requires onboarding-api
- CLAUDE.md invariants walk — most are runtime-observable only
- Third-party-dep count under the retirement sprints' effect — Redis/RabbitMQ usage is gated on the schema that never migrated

These all move to "blocked on V95 fix" and become the checklist for the next runtime-verification attempt.

---

**Report author:** Claude (2026-04-20 session). Stack cannot boot to steady-state due to R134w V95 SQL bug. Multiple retroactive medal downgrades issued per "revise as evidence develops" rule. No shortcuts taken.
