# Heap / leak-signature analysis — post-soak

## Tools limitation

The Spring Boot runtime image ships a slimmed JDK with only `java` and
`jfr`. **No `jcmd`, `jmap`, `jstat`, or `jhsdb`** — so a true `.hprof`
heap dump cannot be triggered on a running container from outside.
`-XX:+HeapDumpOnOutOfMemoryError` is already set (verified via
`JAVA_TOOL_OPTIONS`), but only fires on OOM — which the soak did not
produce.

**R129 ask:** add `jcmd` to the base runtime image (ship
`jdk.jcmd` module or switch to a full JDK image in non-prod profiles).
Cost: ~20 MB per image. Benefit: on-demand heap dumps without kill/restart.

## Evidence collected

`/proc/1/status` on `mft-storage-manager` after the 30 min soak:

```
State:	S (sleeping)
VmPeak:	5,687,172 kB   (5.4 GB virtual, includes mmap'd read-only JAR)
VmSize:	5,687,172 kB
VmHWM:	  781,928 kB   (peak RSS — matches current RSS)
VmRSS:	  781,928 kB   (current RSS)
VmData:	  942,568 kB   (heap + native allocations)
VmSwap:	        0 kB   (no swap activity)
Threads:	    64
```

Key signals:
- `VmHWM == VmRSS` → no memory reclaimed below peak, but also no sustained
  growth peak-vs-current — the JVM is at its working set.
- `VmSwap = 0` → no pressure, no forced pageout.
- Thread count 64 → within healthy bounds for this service.

## Memory-growth signature over soak

`docker stats` sampled every 60 s across the 30 min. First (t+5m) vs last
(t+30m) sample per service, absolute MB + Δ:

| Service | t+5m MB | t+30m MB | Δ MB | Δ % | MB/min |
|---|---|---|---|---|---|
| storage-manager | 741.7 | 746.6 | **+4.9** | +0.7% | 0.20 |
| postgres | 245.1 | 241.3 | -3.8 | -1.5% | -0.15 (stable) |
| rabbitmq | 170.5 | 173.4 | +2.9 | +1.7% | 0.12 |
| gateway-service | 770.0 | 776.6 | +6.6 | +0.9% | 0.26 |
| sftp-service | 596.2 | 605.5 | +9.3 | +1.6% | 0.37 |
| onboarding-api | 634.6 | 652.8 | +18.2 | +2.9% | 0.73 |
| forwarder-service | 550.6 | 569.6 | +19.0 | +3.5% | 0.76 |
| **config-service** | 588.2 | 624.6 | **+36.4** | **+6.2%** | **1.45** |

## Verdict — per-service

- **storage-manager:** 0.20 MB/min growth on a hot upload path — within
  JIT-warmup + G1 heap-expansion noise. **No leak signature.**
- **config-service:** highest growth at 1.45 MB/min. Consistent with
  Caffeine + Redis secondary caches warming; **worth a watch with a
  4 h soak before calling it a leak.**
- **All others:** under 1 MB/min growth, consistent with JIT and G1
  expansion to steady state.

**No runaway signature observed across 30 min.** A 4 h or 24 h soak is
the Gold-gate standard for leak confirmation; this 30 min snapshot is
adequate for Silver-axis reliability but does not earn a leak-free
guarantee.

## R129 asks

1. Add `jcmd` to service runtime images — enables `jmap -dump:live`,
   `jcmd <pid> GC.heap_dump`, `jfr.start` / `jfr.dump` on live
   containers.
2. Repeat the soak at **4 h** and resample `docker stats` every 5 min.
   If `config-service` growth remains linear, capture `.hprof` for
   heap-walker analysis.
3. Wire `management.endpoint.metrics` + `management.endpoint.heapdump`
   behind admin-JWT so tester can pull a dump via HTTPS without
   shelling in.
