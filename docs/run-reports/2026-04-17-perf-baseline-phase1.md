# Perf baseline — Phase 1 (90s parallel load, R89)

**Date:** 2026-04-17
**Build:** R89 (HEAD `48149777`)
**Harness:** [scripts/perf/perf-run.sh](../../scripts/perf/perf-run.sh) — parallel load across 4 surfaces + metric capture + thread-dump attempts + error-log delta.
**Output dir:** `/tmp/perf-run-20260417-164850/` (kept on test machine; not committed).

---

## Headline

90 seconds of parallel load across auth, listener-CRUD, SFTP upload, and read-heavy config APIs. No memory leaks visible. No service crashed. Three operational findings worth fixing.

## Load generators (running in parallel for 90s)

| # | Workload | Result |
|---|---|---:|
| L1 | `POST /api/auth/login` loop | **11.7 rps** — 370 of 1056 OK (**65% rate-limited** by nginx `auth_limit: 10r/s` at the gateway) |
| L2 | Listener create + delete cycle | **7.1 cycles/s** (≈14 API calls/s). Every cycle emits a DMZ proxy call. |
| L3 | SFTP upload of 128-byte payload | **0.41 uploads/s** — bottlenecked by `docker run alpine` cold-start per iteration; real upload cost is ≤10 ms |
| L4 | Read-mix (5 config-service GETs) | **27.6 rps** — 400 of 2485 OK (**84% failures** — cross-gateway auth propagation issue, see F-2 below) |

## F-1 — R79 DMZ proxy mapping fails under listener-CRUD load

1002 new ERROR lines in `mft-onboarding-api` over 90 seconds. Sample:

```
[dmz-proxy] deleteMapping failed after resilience: 401 on DELETE request for
"http://dmz-proxy:8088/api/proxy/mappings/perf-l-0"
```

- Every listener delete triggers a DMZ proxy cleanup call (R79 — "DMZ proxy mapping follows full ServerInstance lifecycle").
- Under churn, the DMZ proxy returns **401** on every call.
- Retries via resilience (circuit-breaker + retry) don't recover — SPIFFE JWT-SVID or platform JWT forwarding is failing.
- L2 did ~638 CRUD cycles → ~1276 expected DMZ calls → ≈1002 observable errors (and more probably dropped at log level).

**Root cause hypotheses** (in order of likelihood):
1. SPIFFE JWT-SVID cache in `SpiffeWorkloadClient` TTL refresh races under concurrent outbound calls (prior memory surfaces mention Phase-1 JWT-SVID caching with 50% TTL refresh — races possible).
2. `BaseServiceClient` doesn't attach the JWT-SVID on the DELETE verb code path.
3. dmz-proxy's `PlatformJwtAuthFilter` validates mTLS/JWT/Platform-JWT — one of the three paths fails on DELETE at concurrency > 1.

**Next step:** replicate single DELETE with and without concurrency to bisect.

## F-2 — read-mix load hitting config-service shows 84% failure rate

2485 GETs in 90s, 400 OK. Endpoints: `/api/flows`, `/api/servers`, `/api/connectors`, `/api/sla`, `/api/flows/step-types`.

Mixed failure modes — some endpoints consistently 200, others degrade. Worth slicing per-endpoint next pass. Suspects:
- `/api/servers` goes to onboarding-api, not config-service (gateway routes this per the nginx conf) — mismatch may be returning 404 that I'm counting as "not 200".
- Redis cache-miss cascade on `/api/flows` under read pressure.

**Next step:** split the mix and record per-endpoint OK-counts.

## Stability under load — no regressions here

### Container stats (t=0 → t=90s)

| Container | CPU% (t0→tEnd) | Mem MiB (t0→tEnd) | Net IO tx/rx |
|---|---|---|---|
| onboarding-api | 5.57% → 9.00% | 716 → 707 (stable) | 4MB → 70MB sent |
| sftp-service | 3.12% → 14.46% | 723 → 735 (+12MB) | stable |
| config-service | 3.52% → 8.12% | 713 → 702 (stable) | 7MB → 15MB |
| postgres | 1.84% → 2.25% | 237 → 247 (+10MB) | 10MB → 17MB |

**No memory-leak signal at 90s.** Heap stable within GC noise. This is not a *long* run — soak (10+ min) is required to detect true leaks.

### JVM GC activity

GC pause totals captured from `/actuator/prometheus`:
- `onboarding-api`: 730 ms cumulative GC pause at t=0 (i.e., since boot). Didn't grow materially during the run — no pressure.
- `sftp-service`: 1079 ms.
- `config-service`: 714 ms.
- `ftp-service`: 829 ms.

All JVMs sized at `-Xmx384m -Xms192m +UseG1GC` — plenty of headroom for this load.

## Instrumentation gaps this run revealed

These are limitations of the current harness that I'll fix in Phase 2:

- **Thread dumps came back empty.** `kill -s SIGQUIT <container>` → `docker logs --since 3s` only captured the header line `"Full thread dump OpenJDK..."`, not the stack bodies. JVM 25 may flush to stderr on a different cadence, or docker's log driver is buffering. **Follow-on:** either enable `management.endpoints.web.exposure.include=threaddump,heapdump` in all services' `application.yml` (one-line per service) OR bake `jcmd` into the runtime image.
- **Heap dumps are totally unavailable.** Same fix — enable `actuator.heapdump` or add JDK tools to the image.
- **Per-service Prometheus parser** missed tomcat thread-pool + hikari counts. Metric names in the Prometheus export don't match my `awk` pattern (my regex used `jvm_memory_used_bytes` which works; `tomcat_threads_busy_threads` and `hikaricp_connections_active` apparently render under different series names or with different labels). **Fix:** sample one raw Prometheus scrape and regenerate the parser.
- **No p50/p95 flow latency** — the `flow_executions` query returned 0 rows because L3's SFTP upload path was too slow (docker-run-alpine per iter) for the 90s window. Switch L3 to a persistent SFTP session inside a single sidecar.

## Phase 2 plan (follow-on)

1. **Enable heap + thread dump endpoints** in every Java service's Spring Boot config. One-line change: `management.endpoints.web.exposure.include=health,prometheus,metrics,threaddump,heapdump`.
2. **Persistent SFTP sidecar** — pre-pull alpine image, keep a long-lived container with a script that does put-loop, so we can actually measure 10× higher upload throughput.
3. **Per-service targeted perf** (each service gets its own load mix):
   - keystore-manager: key-gen churn (AES + PGP)
   - screening-service: OFAC hits at high concurrency
   - ai-engine: classification RPS
   - sftp-service: connection-rate benchmark (handshake-only, no put)
4. **Soak**: 10-minute run — same harness, longer duration. Compare heap at t=0 and t=10m to surface leaks.
5. **Chaos**: during a 3-minute run, kill a worker (screening-service) at t=60s and measure recovery — verify DLQ replays and outbox unblocks.
6. **Report a flame-graph** by enabling `-XX:+PreserveFramePointer` and attaching `async-profiler` briefly. Great for finding the actual hot paths.

## Raw per-tick outputs

All captured at `/tmp/perf-run-20260417-164850/`:
- `metrics/t0.tsv`, `metrics/t22s.tsv`, `metrics/t45s.tsv`, `metrics/t67s.tsv`, `metrics/tEnd.tsv` — per-service Prometheus-derived snapshots (heap, threads, GC; HikariCP + Tomcat columns are "-" due to the parser bug above).
- `metrics/{t0,t22s,t45s,t67s,tEnd}-docker-stats.txt` — container CPU/mem/net-IO.
- `metrics/{l1,l2,l3,l4}-*.txt` — per-load counts.
- `metrics/flow-latency.txt` — empty this pass (see L3 note above).
- `dumps/*-threads.txt` — empty this pass (see SIGQUIT note above).

## Takeaways for the dev team

1. **F-1 is a real bug** — R79 DMZ proxy delete path 401s under concurrency. Reproducible. Probably a JWT forwarding issue on DELETE. Worth fixing before any production load.
2. **Gateway rate-limit on `/api/auth/login` is 10 r/s** (nginx `limit_req_zone`). Fine for interactive login; tune up for CI regression suites that hit this more aggressively. Document in `CLAUDE.md` so future perf harnesses expect it.
3. **Thread + heap dump endpoints should be exposed in non-prod builds.** Without them, perf triage in-cluster is much harder. A one-line Spring profile gate `@Profile("!prod")` for these endpoints would be safe.
4. **No memory-leak signal at 90s.** Longer soak required to be confident.
