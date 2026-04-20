# R134t — ❌ No Medal (product AND contribution): commit does not compile

**Commit verified:** `942123f3` — *R134t: apply PDF learnings — exponential-jitter backoff + cache failure defences + PG scaling playbook*
**Verification date:** 2026-04-20
**Verdict:**
- **Product-state at R134t checkpoint: ❌ No Medal** — carries every prior blocker (https-service DOA from R134o, AS2 consumer miss from R134p, 4/10 listeners UNKNOWN) AND adds a new hard blocker: repo doesn't build.
- **Contribution credit for R134t: ❌ No Medal** — initial read of the diff suggested Silver for resilience engineering (exponential jitter, idempotency contract, jittered Retry-After, split-brain docs). Revised to No Medal after `mvn package -DskipTests` failed before compose can even start.

---

## The compile failure

```
[ERROR] /shared/shared-core/src/main/java/com/filetransfer/shared/ratelimit/
  PgRateLimitCoordinator.java:[5,37]
  package org.springframework.jdbc.core does not exist

[ERROR] PgRateLimitCoordinator.java:[36,19] cannot find symbol
  symbol:   class JdbcTemplate

[ERROR] PgRateLimitCoordinator.java:[33,1] cannot find symbol
  symbol:   class JdbcTemplate

BUILD FAILURE
```

### Root cause

R134t adds `org.springframework.jdbc.core.JdbcTemplate` usage to [PgRateLimitCoordinator.java:5](../../shared/shared-core/src/main/java/com/filetransfer/shared/ratelimit/PgRateLimitCoordinator.java#L5), but `shared/shared-core/pom.xml` does not declare `spring-jdbc` as a dependency. Neither does `shared/shared-platform/pom.xml`. The `JdbcTemplate` import is unresolvable at compile time.

### Impact

- `shared-core` module fails to compile → every module that depends on it (every service) fails to build → **entire Docker image set cannot be built** from R134t.
- Zero of R134t's promised resilience improvements (exponential jitter outbox backoff, idempotency contract codified in the OutboxEventHandler interface, jittered Retry-After, split-brain documentation) ship to the running platform — they're all gated behind compilation.
- The new V99 migration (`event_outbox_defer_until`) can't run because `db-migrate` never starts.

### Fix (for dev, not me)

Add `spring-jdbc` to [shared/shared-core/pom.xml](../../shared/shared-core/pom.xml). Either direct:

```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-jdbc</artifactId>
</dependency>
```

Or transitively via `spring-boot-starter-jdbc` if that's the repo's convention. Then re-verify `mvn compile -q` passes before pushing.

---

## Why this isn't even Bronze

Under the revised medal rubric (memory: "medal grades the whole product, not the tag's diff"), R134t's product-state medal is floor-carried by prior blockers — same No Medal as R134p. That's expected — blockers persist until fixed.

For R134t's **contribution credit**, my first-read grading was Silver: the code (as written) advances the Distributed / Resilience / Fast-under-duress axes, and the R&D doc updates are substantial. But the rubric requires the code to ship. A patch that doesn't compile is not a contribution — it's unshipped work. Revising to No Medal is correct per:

> "The medal is not a nice-to-have. It is a hard requirement. A patch that doesn't compile is not a contribution — it's unshipped work."

---

## What's needed for R134t to salvage any medal

1. **Fix the compile error** — add `spring-jdbc` to shared-core pom.
2. **Re-run `mvn package -DskipTests`** → BUILD SUCCESS.
3. **Bring up the stack** → observe V99 migration applies cleanly, new JSONB column present on `event_outbox`.
4. **Empirically verify the exponential-jitter backoff** — force an outbox consumer to fail, watch `defer_until[consumer]` populate with increasing values across attempts.
5. **Empirically verify jittered Retry-After** — hit rate-limit, inspect response headers across multiple clients, confirm values scatter within the next-window boundary rather than all clustering at `:00`.

If those land cleanly, R134t's contribution would be Silver. But without compile, nothing.

---

## Product-state floor (unchanged from R134p)

Per my standing whole-platform check from R134p-verification.md, the product at R134t checkpoint still has:

- `mft-https-service` DOA (R134o regression — port conflict with api-gateway on :443)
- `as2-server-1` stuck at `bind_state=UNKNOWN` (R134p AS2 consumer doesn't fire)
- 4 of 10 listeners in UNKNOWN bind_state
- 12 third-party runtime deps on default profile (Gold requires zero)

R134t touches none of those. Product stays No Medal.

---

**Report author:** Claude (2026-04-20 session). Revising to No Medal immediately per memory rule "never defer the medal, revise as evidence develops."
