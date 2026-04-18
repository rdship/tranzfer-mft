# R114 onboarding-api Metaspace OOM — diagnostic bundle

**Container:** `mft-onboarding-api`  **Uptime at capture:** 21 min  **Trigger:** sustained Playwright `test:regression` load (~50 auth cycles + flow uploads + activity-monitor polls across 12 pins × retries).

## Files

- `onboarding-api.log.txt` (renamed from `.log` — repo `.gitignore` excludes `*.log`) — last 500 log lines; shows the OOM cascade (`java.lang.OutOfMemoryError: Metaspace` repeated across http-nio-8080-exec-N threads + scheduling-1).
- `onboarding-api.thread-dump.txt` — SIGQUIT thread dump at OOM (1166 lines). Captures thread state, stack frames, and class-loader context at the exact moment of failure.
- `onboarding-api.jvm-state.txt` — VmSize / VmRSS / thread count from `/proc/1/status`. Process had 86 threads; resident 688 MB; virtual 6 GB.
- `onboarding-api.class-histogram.txt` — **empty**: `jcmd GC.class_histogram` refused to attach (`AttachNotSupportedException: state not ready to participate in attach handshake`). The JVM is in OOM state and won't accept attach connections — same constraint as R97's crash-loop diagnostics. Thread dump via SIGQUIT signal handler is the available substitute.

## Heap dump — NOT captured and why

`jcmd GC.heap_dump` can't attach to an OOM'd JVM. Recommended fix for next runs: add

```
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp/heapdump-%p.hprof
```

to every service's `JAVA_TOOL_OPTIONS`. On next Metaspace OOM, the JVM writes the heap itself before dying. Note: `HeapDumpOnOutOfMemoryError` in current JVMs fires on Heap OOM by default but **also on Metaspace OOM** when `-XX:MetaspaceSize` is set and exhaustion is hit. Worth verifying by repro with a modified JAVA_TOOL_OPTIONS.

## What to look for

1. **Grep thread dump for `mft-async-`**: any oversubscribed thread pool.
2. **Grep thread dump for `JarUrlClassLoader` / `LaunchedClassLoader`**: if many distinct classloader instances are alive, it confirms a classloader leak per @Async or @Transactional proxy allocation.
3. **Grep `proxy$Lambda`, `cglib`, `$$SpringCGLIB$$`**: if these class count dwarfs user classes, it's a proxy leak.
4. **Timeline in `onboarding-api.log`**: look for repeated `RepositoryFactorySupport.getRepository` or bean-init messages after boot — indicates beans being recreated (should only happen once at startup).

## Root cause hypothesis (from R114 acceptance report)

`-XX:MaxMetaspaceSize=150m` (set in all services' `JAVA_TOOL_OPTIONS`) is too tight for any service that handles sustained load with dynamic class generation (JPA proxies, @Async target-class proxies, CGLIB). Recommended: bump to 384 MB or remove hard cap.

## Reproducer

```
docker compose up -d    # R114 tip
# Wait for clean
cd tests/playwright && BASE_URL=http://localhost:8080 npm run test:regression
# OOM fires ~20 min into the run
```
