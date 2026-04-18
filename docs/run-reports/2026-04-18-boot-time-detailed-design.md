# Boot-time optimization — detailed design for the dev team

**Date:** 2026-04-18
**Build:** R92 (HEAD `1bc25802`)
**Mandate from CTO:** *no service may take more than 120 s to boot, with every feature preserved, no excuses.*
**Author note:** this report is analysis-only. The dev team picks which option(s) to implement.

---

## 1. Current state vs mandate

| | Value | Mandate |
|---|---:|---|
| Fastest service | edi-converter 23.2 s | — |
| Slowest service | config-service 184.0 s (from Starting; 191.7 s from process start) | **must be ≤120 s** |
| Services under 120 s today | 2 of 18 (edi-converter, storage-manager) | |
| Services over 120 s today | **16 of 18** | must all drop to ≤120 s |
| Range | 162 s | shrink to <120 s |
| Median heavy service | ~165 s | must shave ~45 s |

**Every single service that touches the Fabric (Kafka) bus or shared-platform's RabbitMQ listeners is over the limit.** The two that boot under 120 s are the ones that opt out.

## 2. Phase-by-phase breakdown per service

All timings are seconds from each service's own `Starting XApplication` log line to its `Started XApplication in N seconds` log line.

Phase columns:
- **PreTomcat** = `Starting → Initializing Spring embedded WebApplicationContext` (Spring context assembly before Tomcat)
- **TomcatToFabric** = `TomcatInit → [Fabric/Kafka] Per-function step pipeline active` (for services that log a Fabric-done event)
- **FabricToStarted** = `FabricDone → Started` (HikariCP init, remaining bean wiring, cluster-registration, first health)
- **TomcatToStarted** = `TomcatInit → Started` (for services without a distinct FabricDone event — likely combined)

### Group A — services with explicit Fabric consumer wiring

| Service | PreTomcat | TomcatToFabric | FabricToStarted | **Total** |
|---|---:|---:|---:|---:|
| config-service | 37.9 | 128.4 | 17.7 | **184.0** |
| sftp-service | 31.9 | 112.7 | 37.0 | **181.5** |
| forwarder-service | 34.6 | 92.2 | 54.6 | **181.5** |
| as2-service | 28.4 | 116.4 | 35.6 | **180.4** |
| onboarding-api | 26.1 | 119.8 | 32.0 | **177.9** |
| ftp-service | 30.0 | 105.3 | 42.7 | **178.0** |
| gateway-service | 26.2 | 83.2 | 67.4 | **176.9** |
| ftp-web-service | 34.2 | 106.9 | 29.9 | **171.0** |

### Group B — services without a FlowFabricConsumer but still using the Fabric bus (for cache-eviction, rotation, etc.)

| Service | PreTomcat | TomcatToStarted | **Total** |
|---|---:|---:|---:|
| ai-engine | 33.8 | 144.1 | **177.9** |
| analytics-service | 31.1 | 134.8 | **165.9** |
| notification-service | 31.1 | 132.8 | **163.9** |
| platform-sentinel | 32.0 | 129.7 | **161.7** |
| license-service | 35.1 | 119.3 | **154.3** |
| encryption-service | 26.4 | 114.2 | **140.6** |
| screening-service | 23.8 | 115.8 | **139.5** |
| keystore-manager | 27.3 | 111.9 | **139.2** |
| storage-manager | 25.9 | 89.2 | **115.2** |

### Group C — no Fabric at all

| Service | PreTomcat | TomcatToStarted | **Total** |
|---|---:|---:|---:|
| edi-converter | 12.8 | 10.4 | **23.2** |

**Observation:** edi-converter's PreTomcat is 12.8 s. Every other service's PreTomcat is 23.8–37.9 s. That 11–25 s gap is **pre-Kafka**, present before any broker work. It is shared-platform's eager bean wiring and JPA entity metadata scan.

## 3. Confirmed dominant costs

### 3.1 `FlowFabricConsumer.init()` — 20 sequential subscribe() calls
[shared/shared-platform/src/main/java/com/filetransfer/shared/fabric/FlowFabricConsumer.java:86-95](../../shared/shared-platform/src/main/java/com/filetransfer/shared/fabric/FlowFabricConsumer.java#L86-L95)

```java
String[] stepTypes = { "SCREEN", "CHECKSUM_VERIFY", ...21 entries };
for (String stepType : stepTypes) {
    String topic = "flow.step." + stepType;
    String group = FabricGroupIds.shared(serviceName, topic);
    fabricBridge.getClient().subscribe(topic, group, this::onPipelineMessage);
}
```

Each `subscribe(topic, group, handler)` in `KafkaFabricClient` ([shared/shared-fabric/.../KafkaFabricClient.java:106-130](../../shared/shared-fabric/src/main/java/com/filetransfer/fabric/client/KafkaFabricClient.java#L106-L130)):
1. Calls `ensureTopic(topic)` — which opens a **new `AdminClient`** (line 255), calls `createTopics().all().get(timeout)`, and closes it.
2. Spawns a daemon consumer thread.

The fire-and-forget of the consumer thread is fine. The **blocking cost is `ensureTopic()`** — `AdminClient.create()` + `createTopics().get()` serial across 20 topics. Measured at ~5 s per topic on a warm broker. **20 × 5 s ≈ 100 s.** That matches the `TomcatToFabric` column for Group A (83–128 s).

### 3.2 Kafka producer first-metadata on every service (Group B too)

Even services with no consumers still instantiate `KafkaProducer` in `KafkaFabricClient`'s constructor (line 64). The first `producer.send()` or `admin.listTopics()` blocks until the producer fetches broker metadata — ~15–30 s on cold Redpanda. Observable as the `AdminClientConfig values:` log spam across every Kafka-touching service.

Hot fix for Group B is the same as Group A: parallelize or defer.

### 3.3 PreTomcat shared-platform overhead (~15–25 s everywhere except edi-converter)

Entities, JPA metadata, SPIFFE JWT filter chain wiring, HikariCP connection pool handshake, @PostConstruct beans. Done on `main` thread sequentially during `AbstractApplicationContext.refresh()`.

Specific beans with known cost: `SpiffeWorkloadClient` (attempts SVID fetch early with 15 s retry window when SPIRE agent isn't ready), `FabricConfig` (creates `KafkaFabricClient` → triggers broker reachability check with 10 s timeout), `ApiRateLimitFilter` (Redis connection pool).

## 4. Options (priority-ordered, each with expected saving + risk + feature impact)

### Option 1 — Parallelize `FlowFabricConsumer` subscribe loop **[RECOMMENDED]**
Wrap the loop at `FlowFabricConsumer.java:86-95` in an `ExecutorService` with parallelism 8–10, invoke 20 subscribes concurrently, wait for all with a `CountDownLatch` or `CompletableFuture.allOf(...).join()`.

- **Expected saving per service**: Group A services drop `TomcatToFabric` from ~100 s to ~12–15 s → **~85 s per service**.
- **Feature impact**: none. Every subscribe still completes before `@PostConstruct` returns. Consumers start identically. Only the ordering of the individual `subscribe()` calls becomes concurrent.
- **Risk**: Spring allows parallel bean init on non-startup threads only if the called code is thread-safe. `KafkaFabricClient.subscribe()` already guards with `ConcurrentHashMap.add()` for `ensuredTopics`, so multiple threads can call it safely. No shared mutable state is touched unsafely.
- **Code surface**: ~15 lines in one file. Zero other-file impact.
- **Rollback**: single-line flag `fabric.subscribe-parallelism: 1` in properties.
- **Measurability**: existing `Per-function step pipeline active` log line delta gives the proof.

### Option 2 — Parallelize `ensureTopic()` into a single bulk `createTopics()` call
Rather than calling `ensureTopic()` per-subscribe, batch all needed topics into a single `AdminClient.createTopics(Collection<NewTopic>)` call at `FabricConfig` init, reuse a shared `AdminClient` instance instead of creating one per subscribe.

- **Expected saving**: 10–20 s on top of Option 1 (AdminClient creation cost is ~500 ms each × 20 = 10 s).
- **Feature impact**: none.
- **Risk**: if the `AdminClient` dies, subsequent lazy topic creates fail. Already handled by `healthy` flag.
- **Code surface**: ~30 lines in `KafkaFabricClient` + one new field.

### Option 3 — Defer consumer subscription to `ApplicationReadyEvent`
Move the `@PostConstruct` consumer subscriptions out of Spring context refresh. Start them in an `@EventListener(ApplicationReadyEvent.class)` on a background thread.

- **Expected saving**: removes ~100 s from *reported* boot time (log says "Started" much earlier).
- **Feature impact**: **WARNING** — between "Started" and "consumers subscribed", the service advertises itself as ready but won't consume. Any flow events published during that window are *not lost* (Kafka retains them), but they will queue. ~15 s of queued events per consumer.
- **Risk**: external orchestrators (Kubernetes readiness probe, docker compose healthcheck) will direct traffic to a service that can't yet consume. The service's Tomcat handles HTTP but its Kafka consumer backlog grows. Acceptable if combined with a "ready but warming" health signal.
- **Code surface**: refactor of `FlowFabricConsumer.init()` pattern.
- **Verdict**: ONLY paired with a proper readiness-gate change.

### Option 4 — Enable Spring AOT fully
The build already produces `target/spring-aot/` for every service. At runtime, enable `-Dspring.aot.enabled=true` (and possibly `--enable-preview` + the AOT-generated classes on the classpath).

- **Expected saving**: **20–40 % of Spring context refresh time** — cuts PreTomcat from 26–37 s to 15–22 s. Universal, every service benefits.
- **Feature impact**: none if AOT metadata was built correctly (already is per `target/spring-aot/`).
- **Risk**: reflection-heavy code not AOT-hinted can break at runtime with `ClassNotFoundException`. Fabric/Kafka/Hibernate reflection usage should already be AOT-registered via Spring Boot's AOT reflection hints. Requires validation run.
- **Code surface**: Dockerfile flag or JAVA_TOOL_OPTIONS env var.
- **Rollback**: remove the flag.

### Option 5 — Narrow `@EntityScan` per service
Each service currently scans 100 JPA entities via shared-platform. Most services need only a subset.

- **Expected saving**: 3–8 s off PreTomcat per service.
- **Feature impact**: if a bean references an unscanned entity, runtime error. Low risk per-service but requires audit.
- **Code surface**: one `@EntityScan(basePackages = {...})` annotation per service.
- **Verdict**: fine to do, but smaller payoff than Options 1, 4.

### Option 6 — Reduce Fabric step topics by grouping
Instead of 20 `flow.step.<TYPE>` topics, use a single `flow.step` topic with a `stepType` message header. Routing done in handler.

- **Expected saving**: 85–95 s per Group A service (same magnitude as Option 1).
- **Feature impact**: loses the "independent scaling" feature — per-step-type consumer groups today let you scale SCREEN workers separately from ENCRYPT workers. **This is the feature the CTO said not to lose.** REJECTED.

### Option 7 — Lazy-init controllers and non-critical beans
Annotate controllers that aren't on the startup-critical path with `@Lazy`. They initialize on first request.

- **Expected saving**: 5–15 s off PreTomcat.
- **Feature impact**: first request to each lazy controller pays a one-time 100–500 ms init cost. Acceptable.
- **Risk**: low.
- **Code surface**: widespread but mechanical. Probably 50+ controller classes.

### Option 8 — Graal native image
Compile each service to a GraalVM native binary. Boot time ~100 ms instead of 23–184 s.

- **Expected saving**: enormous — eliminates JVM startup entirely.
- **Feature impact**: **SIGNIFICANT** — Java 25 + several reflection-heavy libs (Hibernate, BouncyCastle, Kafka) need extensive native hints. Some features (MBean-based metrics, some Lombok patterns) may not work. Large effort.
- **Verdict**: worth a spike but not the recommended immediate fix.

## 5. Recommended combination to hit the 120 s mandate

Stack Option 1 + Option 2 + Option 4 + Option 5.

| Fix | Est saving (per heavy service) |
|---|---:|
| Option 1 (parallelize FlowFabricConsumer subscribe) | 70–85 s |
| Option 2 (shared AdminClient + batch ensureTopic) | 5–10 s on top |
| Option 4 (enable Spring AOT at runtime) | 5–10 s on PreTomcat |
| Option 5 (narrow @EntityScan per service) | 3–8 s on PreTomcat |
| **Combined expected saving** | **~85–110 s per Group A service** |

Projected boot times after the fix:

| Service | Current | Projected | Under 120 s? |
|---|---:|---:|---|
| config-service | 184 s | ~80–100 s | ✅ |
| sftp-service | 181 s | ~75–95 s | ✅ |
| forwarder-service | 181 s | ~75–95 s | ✅ |
| as2-service | 180 s | ~75–95 s | ✅ |
| onboarding-api | 178 s | ~75–95 s | ✅ |
| ftp-service | 178 s | ~75–95 s | ✅ |
| gateway-service | 177 s | ~75–95 s | ✅ |
| ftp-web-service | 171 s | ~70–90 s | ✅ |

Group B services (no 20-topic subscribe but other Kafka init) benefit from Options 2 + 4 + 5 mainly:

| Service | Current | Projected | Under 120 s? |
|---|---:|---:|---|
| ai-engine | 178 s | ~120–140 s | ⚠️ **marginal** — may need Option 3 + readiness-gate fix |
| analytics-service | 166 s | ~115–130 s | ⚠️ marginal |
| notification-service | 164 s | ~115–130 s | ⚠️ marginal |
| platform-sentinel | 162 s | ~110–130 s | ⚠️ marginal |
| license-service | 154 s | ~105–125 s | mostly ✅ |
| encryption-service | 141 s | ~95–115 s | ✅ |
| screening-service | 140 s | ~95–115 s | ✅ |
| keystore-manager | 139 s | ~95–115 s | ✅ |
| storage-manager | 115 s | ~80–100 s | ✅ already ✅ |

**Gap for the slow Group B services**: their ~130 s `TomcatToStarted` phase is not fully explained by Kafka. Those services have heavier `@PostConstruct` work (analyzer bean wiring in sentinel, model loading in ai-engine, scheduler beans in notification). Investigation work items per service are in §7.

## 6. Acceptance criteria

- Every Java service's "Started XApplication in N seconds" log line: **N ≤ 120.0** on a cold boot against a fresh Redpanda/Postgres/RabbitMQ stack.
- `docker stats` during boot shows no container over 95 % CPU for more than 30 s (indicates blocking JIT pressure).
- `./scripts/sanity-test.sh` post-boot: **55 of 55 PASS** (no regression).
- `./scripts/build-regression-fixture.sh`: succeeds; flow_executions of `regtest-f7` continues to complete in ≤ 1 s.
- CI gate: fail the build if any service's startup time regresses by more than 10 % from the last green run.

## 7. Per-service investigation items for Group B (if Options 1+2+4+5 don't reach 120 s)

These services have boot time dominated by something other than FlowFabricConsumer. Per-service targets for the dev team:

- **ai-engine (178 s)** — check `AutoRemediationService`, model-loading beans, classifier initialization. `-Djava.security.egd=file:/dev/./urandom` may help if entropy seeding stalls.
- **analytics-service (166 s)** — check `SlaBreachDetector`, anything that reads `partner_agreements` on boot. Consider `@Lazy`.
- **notification-service (164 s)** — check WebSocket / STOMP broker registration, SMTP client init.
- **platform-sentinel (162 s)** — multiple analyzer beans; inspect `@PostConstruct` chain and `BuiltinRuleSeeder`.
- **license-service (154 s)** — license-file read on boot with retry; verify no blocking network call.

Recommend: attach a profiler (async-profiler, 30 s JFR) to each during cold boot and publish the top-10 self-time contributors.

## 8. Explicit non-goals (do not do these)

- **Do not drop `spring.jpa.hibernate.ddl-auto=none` to auto-update** — we rely on Flyway for schema; schema drift is a feature loss.
- **Do not skip Flyway validation at boot** (the existing `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false` override is a historical V42 workaround; removing Flyway entirely would be feature loss).
- **Do not remove consumer subscriptions** — each `flow.step.<TYPE>` topic is a scalability feature (per-step independent scaling).
- **Do not use `@Lazy` on beans with `@PostConstruct` that does work** — defeats the purpose.
- **Do not disable SPIFFE JWT-SVID caching warmup** — first inter-service call would pay the full SPIRE round-trip.
- **Do not disable the rate limiter**; tune its limits upward for test (already done in docker-compose.override.yml).

## 9. Rollout plan

Phase 1 — land **Option 4 (AOT)** alone first. Universal, tiny code change (JVM flag), big upside, instantly reversible.
- Expected savings: every service PreTomcat drops ~10 s.
- Gate: sanity sweep green.

Phase 2 — land **Option 1 + Option 2 (parallel subscribe + shared AdminClient)**. Targeted at shared-platform.
- Expected savings: every Group A service boots <120 s.
- Gate: sanity sweep green + flow-execution still <1 s p95 + no "Subscribed to" log missing.

Phase 3 — land **Option 5 (narrow @EntityScan)** per slow Group B service.
- Gate: each service starts its own Hibernate metadata build in <5 s.

Phase 4 — for any service still >120 s, cut the per-service investigation listed in §7.

## 10. Risk register

| Risk | Likelihood | Blast radius | Mitigation |
|---|---|---|---|
| AOT class not hinted → runtime ClassNotFoundException | Low (already compiled) | 1 service fails to start | Run sanity immediately after enabling AOT in each service; per-service rollback |
| Parallel subscribe() surfaces a `KafkaFabricClient` thread-safety bug | Medium | Inconsistent consumer group state on boot | Unit test with 20 parallel `subscribe()` calls; CI guard |
| Shared `AdminClient` long-lived connection dies | Low | topic creation fails until restart | Existing `healthy` flag + consumer-side retry |
| `@EntityScan` narrowing misses a cross-service entity | Medium | Runtime bean creation failure | Run full regression post-change; per-service rollback |
| First-request latency regression from `@Lazy` controllers | Low | +100–500 ms on cold controllers | Measure p99 in sanity sweep; apply sparingly |

## 11. What this buys the product

- **Cold-boot-to-ready** (currently ~192 s for the slowest service, observed as "stack becomes healthy" around 145–173 s for the herd) drops to **~95–115 s** for the slowest service.
- **CI iteration speed**: our current test cycle is limited by boot. Cutting boot by ~90 s per rebuild is ~15 minutes saved per day per engineer on a warm-base-image cycle.
- **Operator experience**: deploys feel faster; rolling upgrades finish in half the wall-clock.

## 12. What this does NOT buy

- Runtime throughput is not affected (Kafka consumer behavior after subscription is unchanged).
- Runtime memory is not affected (number of threads per consumer is unchanged; parallelism only during startup).
- Feature surface is unchanged — explicitly protected.

## 13. Asks of the dev team

1. Pick an option subset. Recommend starting with **Option 4 (AOT only)** as the no-risk-first deploy.
2. Confirm the 120 s ceiling applies to "Started XApplication in N" log line, not to "stack fully healthy" (different number, wider).
3. Assign an owner to §7 per-service investigations for Group B (ai-engine, analytics, notification, platform-sentinel, license).
4. Decide on the CI regression gate (§6 — should any +10 % per-service drift fail a build?).

---

**Data files** (for the dev team):
- Full thread dumps: [docs/perf/edi-converter-R92-threaddump.txt](../perf/edi-converter-R92-threaddump.txt), [docs/perf/config-service-R92-threaddump.txt](../perf/config-service-R92-threaddump.txt)
- Per-service boot-phase extractor: `/tmp/boot-phases.sh` (shell + python3 one-shot)
- Previous report: [docs/run-reports/2026-04-18-boot-time-debug-edi-converter.md](2026-04-18-boot-time-debug-edi-converter.md)
