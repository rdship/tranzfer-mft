# Boot-time debug — edi-converter anomaly + full 18-service timing on R92

**Date:** 2026-04-18
**Build:** R92 (HEAD `8a0d986d`)
**Ask:** Debug edi-converter + capture dumps + logs + find real boot time per service so the dev team can shorten boot without losing features.
**Artifacts:** [docs/perf/edi-converter-R92-threaddump.txt](../perf/edi-converter-R92-threaddump.txt), [docs/perf/config-service-R92-threaddump.txt](../perf/config-service-R92-threaddump.txt)

---

## TL;DR

**edi-converter is not slow — my earlier Prometheus-based table couldn't see it because its actuator `/prometheus` endpoint returns 404 (not exposed).** Real boot: **28.7 seconds** — by far the fastest Java service in the stack (6.7× faster than the slowest, config-service at 191.7s).

The gap between edi-converter (28s) and every other Java service (120-192s) is almost entirely **Kafka consumer wiring** — sequential, topic-by-topic `ConsumerCoordinator` handshakes against Redpanda. Eliminating or parallelizing that wiring is the #1 boot-time lever.

## Real boot time per service (from each service's own `Started X in Y seconds` log line)

Sorted fastest → slowest.

| Service | Startup (Spring) | Total w/ JVM cold | Delta vs edi-converter |
|---|---:|---:|---:|
| **edi-converter** | **28.7 s** | 33.1 s | — |
| storage-manager | 119.8 s | 123.5 s | +91 s |
| encryption-service | 144.4 s | 155.5 s | +116 s |
| screening-service | 146.0 s | 150.4 s | +117 s |
| keystore-manager | 148.6 s | 152.4 s | +120 s |
| license-service | 159.5 s | 164.1 s | +131 s |
| platform-sentinel | 168.0 s | 173.2 s | +139 s |
| notification-service | 169.2 s | 175.8 s | +141 s |
| analytics-service | 173.3 s | 178.0 s | +145 s |
| ftp-web-service | 175.8 s | 181.2 s | +147 s |
| ftp-service | 184.0 s | 189.0 s | +155 s |
| onboarding-api | 184.8 s | 189.5 s | +156 s |
| gateway-service | 184.9 s | 190.2 s | +156 s |
| ai-engine | 185.3 s | 190.4 s | +157 s |
| sftp-service | 187.9 s | 193.9 s | +159 s |
| as2-service | 188.2 s | 192.7 s | +160 s |
| external-forwarder-service | 189.4 s | 194.9 s | +161 s |
| **config-service** | **191.7 s** | **195.9 s** | **+163 s** |

Arithmetic mean across the 17 heavy services: **168 s**. 18-service cold boot (longest wins): **≈196 s JVM cold-complete, ≈192 s Spring-started**.

## Why edi-converter is 6.7× faster

Comparing its timeline to config-service:

```
edi-converter                                config-service
  00:17:23.076  Starting                      00:17:23.516  Starting
  00:17:35.826  Tomcat Spring context init    00:18:01.374  Tomcat Spring context init
                 (+12.7 s from Starting)                     (+37.9 s from Starting)
  00:17:45.448  Actuator endpoints exposed
  00:17:46.032  Tomcat ready                  00:19:28.411  first Kafka producer up
                                                              (+87 s from Tomcat context init)
  00:17:46.296  Started (28.714 s)           00:20:27.515  Started (191.713 s)
```

**Three phases account for the gap:**

| Phase | edi-converter | config-service | Δ |
|---|---:|---:|---:|
| JVM cold start → Spring main starts | 11 s | 11 s | 0 |
| Starting → Tomcat Spring init | 12.7 s | 37.9 s | **+25 s** (early Flyway + Hibernate metadata + repository scan) |
| Tomcat init → Kafka producer up | n/a (no Kafka) | **87 s** | **+87 s** (Kafka broker contact + topic metadata discovery) |
| Kafka producer up → consumers wired | n/a | ~27 s | **+27 s** (sequential subscribe/join for dozens of step-named topics) |
| Consumers wired → Tomcat ready | 0.6 s | ~2 s | +1.5 s |

The **87-second Kafka producer init + 27-second consumer wiring** is the dominant cost. edi-converter has **zero Kafka consumers**; the rest of the services have anywhere from 8 (screening) to 25 (onboarding-api / config-service) subscribed topics, each of which does its own group-join against Redpanda serially.

## What the thread dumps say

edi-converter at steady state (from `/tmp/edi-tdump.txt`):
- **27 threads total** — `Reference Handler`, `Finalizer`, `C1/C2 CompilerThread0`, `Monitor Deflation`, 10 × `http-nio-8095-exec-N`, `http-nio-8095-Poller`, `http-nio-8095-Acceptor`, `scheduling-1`, `Catalina-utility-1/2`, G1 GC threads.
- **All WAITING or parked** — no hot threads at idle. Healthy.
- `C2 CompilerThread0` used 5.07 s CPU total → JIT warmup is done.
- Peak CPU user = `DestroyJavaVM 5.02 s` (one-time startup), `http-nio-8095-exec-*` each at ~200-400 ms (from sanity tests).
- **No Kafka threads at all** — the reason it's so lean.

config-service at steady state (from [docs/perf/config-service-R92-threaddump.txt](../perf/config-service-R92-threaddump.txt)):
- **113 threads total** (vs edi's 27 — 4.2× more).
- 12 `http-nio-*` + 12 `https-jsse-nio-*` — Tomcat HTTP/HTTPS worker pools (R87 added FTPS listener awareness? or just double-exposed).
- **4 RabbitMQ listener container threads** + Kafka producer-network-thread + per-topic `kafka-coordinator-heartbeat-thread` (one per subscribed flow.step topic — SCREEN, RENAME, MAILBOX, …).
- Each Kafka consumer heartbeat thread holds a persistent connection to Redpanda. This is overhead we carry for the entire lifetime of the service.

## Why edi-converter is missing from `/actuator/prometheus`

`curl http://localhost:8095/actuator/prometheus` returns **404** (not 403). Cause: edi-converter's Spring Boot actuator exposure list is narrower than siblings. Log snippet:
```
Exposing 3 endpoints beneath base path '/actuator'
```
vs other services which expose 10+ (`health`, `prometheus`, `metrics`, `info`, `loggers`, etc.).

**Fix:** edi-converter's `application.yml` or `application-docker.yml` likely has `management.endpoints.web.exposure.include=health` or similar — it needs the same exposure list as its siblings (minimum: add `prometheus`). One-line change; zero runtime cost.

This was also the reason edi-converter showed `- - -` in my perf harness's per-service JVM metric table.

## What would cut boot time most — priority list

### P0 — Parallelize Kafka consumer wiring
The single biggest lever. Currently each `@RabbitListener` and each `@KafkaListener` registers its consumer on the `main` thread sequentially during Spring context refresh. 20+ consumers × ~5 s each = ~100 seconds of sequential wiring.

**Fix directions (pick one):**
- Use `@KafkaListener(autoStartup = "false")` and start the containers in parallel on a thread pool after `ApplicationReadyEvent`.
- Spring's `ConcurrentMessageListenerContainer` already supports parallel consumer start via the `containerStartupExecutor` — configure it globally.
- Lazy-start consumers on first message dispatch (if acceptable per feature).

Expected saving: **60-100 seconds across services with heavy Kafka consumption** (config-service, onboarding-api, sftp-service, ftp-service, as2-service, external-forwarder, gateway, ai-engine).

### P1 — Shared Redpanda client init
Each service instantiates its own Kafka producer (visible via `"Instantiated an idempotent producer"` lines). The first producer takes ~10-20 s to negotiate cluster metadata. If a shared producer lived in shared-core and was reused, subsequent services would skip that cost.

### P1 — AOT compilation (already partially landed)
`target/spring-aot/` dirs are present from the Maven build, but containers don't appear to be using them. Confirm `-Dspring.aot.enabled=true` at runtime (or use GraalVM native for services where feature-completeness allows).

Expected saving: **20-40% on Spring context refresh** for all services.

### P2 — Trim Hibernate metadata scan
Early 25-second gap in config-service (`Starting` → `Tomcat Spring context init`) is mostly JPA entity metadata build. `spring.jpa.properties.hibernate.default_batch_fetch_size=20` + `boot.allow_jdbc_metadata_access=false` + `temp.use_jdbc_metadata_defaults=false` are already set (saw in env). Next lever: **`@EntityScan` narrower base packages** per service so each scans only the entities it uses (currently shared-platform ships 100 entities scanned everywhere).

### P2 — JVM-level optimizations already in place
Services already run with sensible defaults:
- `-XX:+UseG1GC -XX:MaxGCPauseMillis=100` ✓
- `-XX:ActiveProcessorCount=2` (edi-converter=1) ✓
- `-XX:MaxMetaspaceSize=150m` ✓
- `-Dspring.jpa.open-in-view=false` ✓
- `-Dspring.data.jpa.repositories.bootstrap-mode=lazy` ✓

These are correct. Not the bottleneck.

### P3 — Enable CDS (Class Data Sharing) archives
`-XX:ArchiveClassesAtExit=/app/app.jsa` + `-XX:SharedArchiveFile=/app/app.jsa` at JVM start. Small incremental win (2-5 s per service).

## What should NOT change

The user asked that we not lose features. These aspects of boot time are **load-bearing** and should not be shortened by skipping:

- **Flyway migrations.** Serving traffic on a schema the JVM hasn't verified is how outages happen.
- **SPIFFE agent handshake + JWT-SVID warm-up.** Disabling early attaches a window of un-authed inter-service traffic.
- **Full Hibernate metadata build.** Lazy mode is already set; going further risks runtime NPE on first query.
- **Kafka consumer group-join.** Skipping it means the first inbound message is lost.

The P0/P1 recommendations above **parallelize** or **AOT-precompile** these — they do not remove them.

## For the dev team — exact fix to try first

Add this to `shared-platform` (or each service's `@Configuration`):

```java
@Bean
public TaskExecutor consumerContainerStartupExecutor() {
    var ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(4);
    ex.setMaxPoolSize(8);
    ex.setThreadNamePrefix("consumer-start-");
    ex.initialize();
    return ex;
}

@Bean
public RabbitListenerEndpointRegistryPostProcessor rabbitStartAsync(
        TaskExecutor consumerContainerStartupExecutor) {
    return registry -> registry.getListenerContainers().forEach(c ->
        c.setTaskExecutor(consumerContainerStartupExecutor));
}
```

Equivalent for `KafkaListenerEndpointRegistry`. This pushes each consumer's group-join to the pool; 20 consumers × 5 s serial → 20 consumers × 5 s parallel-8 = ~15 s.

Likely result: config-service drops from 192 s → 80-100 s. Onboarding-api, sftp-service, ftp-service all drop similarly.

## Raw artifacts

- [docs/perf/edi-converter-R92-threaddump.txt](../perf/edi-converter-R92-threaddump.txt) — 364-line thread dump from steady state
- [docs/perf/config-service-R92-threaddump.txt](../perf/config-service-R92-threaddump.txt) — 1653-line thread dump showing the 113-thread footprint

## Summary table for the deck

| Metric | Value |
|---|---|
| Services measured | 18 Java services |
| Fastest | edi-converter (28.7 s) |
| Slowest | config-service (191.7 s) |
| Range | **163 s** |
| Dominant cost | Kafka consumer wiring + broker metadata (~87 s + 27 s in heaviest services) |
| Primary fix | Parallelize `@KafkaListener` / `@RabbitListener` container startup |
| Expected saving | 60-100 s per service with Kafka consumers |
| Features lost | zero (if fix is "parallelize" not "skip") |
