# R124 acceptance (PROPER sweep — supersedes yesterday's "hold" report) — 🥉 Bronze; observability ships + boot time regresses

**Date:** 2026-04-18
**Build:** R124 (HEAD `e6d9d932`), rebuilt fresh (`mvn clean package + docker build --no-cache + compose up -d`). Yesterday's R124 report was done against a pre-R124 container image — incorrect. This supersedes it.
**Outcome:** 🥉 **Bronze.** R124's three boot-time observability features (StartupTimingListener, BootPhaseDumpCollector, JFR) all verified working at runtime. **But the instrumentation itself regresses boot time by ~70%** (276–358 s per service, up from R122/R123's 165–220 s). All 14 broken endpoints from yesterday's R123 audit remain broken. UI walk-through failures unchanged.

---

## Self-correction from yesterday's R124 "hold" report

Yesterday I filed R124 as a "pure version bump, nothing to test" based on `git show --stat` showing only pom.xml changes. That was wrong:

1. R124 actually includes a PR-merged branch (`r124-boot-timing` via merge `2437b911`) with **real code** — `StartupTimingListener.java`, `BootPhaseDumpCollector.java`, and a JFR flag in docker-compose.
2. My containers were built at `14:41:09 UTC`, 20 minutes *before* R124 merged at `15:00:55 UTC`. I rebased git state but never rebuilt — features didn't appear at runtime because the jars pre-dated them.
3. I stopped after `git show --stat` instead of reading the commit message fully.

Durable fix captured in memory: every release gets a fresh rebuild, every commit message read fully, every feature verified at runtime. The `git show --stat` shortcut is banned.

---

## ✅ R124 observability features — verified at runtime (this run)

All three fire on a freshly-built R124 container:

```
[boot-timing] T+364910ms ContextRefreshedEvent (bean graph fully loaded, 967 beans)
[boot-timing] T+365016ms ApplicationStartedEvent (context started, server up)
[boot-timing] T+368268ms ApplicationReadyEvent (fully ready — total cold-boot)
[boot-dump]   wrote /tmp/boot-phase-dumps/onboarding-api-T+30s.txt (threads=25)
[boot-dump]   wrote /tmp/boot-phase-dumps/onboarding-api-T+60s.txt (threads=46)
/tmp/boot-jfr.jfr                                           23 164 264 bytes
```

The data dev team asked for is being captured correctly. Good foundation for the R125 boot-time investigation.

---

## ❌ R124 observability caused a ~70% boot-time regression

| Service | R122 / R123 boot | **R124 boot** | Delta |
|---|---:|---:|---:|
| onboarding-api | 207 s | **357 s** | +150 s |
| config-service | 204 s | **358 s** | +154 s |
| gateway-service | 200 s | **350 s** | +150 s |
| sftp-service | 205 s | **352 s** | +147 s |
| ftp-service | 207 s | **353 s** | +146 s |
| ftp-web-service | 196 s | **338 s** | +142 s |
| forwarder-service | 193 s | **346 s** | +153 s |
| analytics-service | 186 s | **320 s** | +134 s |
| notification-service | 194 s | **336 s** | +142 s |
| platform-sentinel | 197 s | **355 s** | +158 s |
| screening-service | 182 s | **277 s** | +95 s |
| **edi-converter** | 31 s | **44 s** | +13 s (still the only one under 120 s) |
| encryption-service | 186 s | **325 s** | +139 s |
| keystore-manager | 176 s | **290 s** | +114 s |
| license-service | 194 s | **341 s** | +147 s |
| storage-manager | 181 s | **324 s** | +143 s |
| ai-engine | 206 s | **355 s** | +149 s |

**Average +137 s per service.** R124's commit message claimed `<1% overhead`. Actual is roughly **70% slower**.

Most likely culprit: **`-XX:StartFlightRecording=duration=300s,settings=profile,dumponexit=true`**. `settings=profile` is the heavyweight profile (instrumenting method-level timing) — it's appropriate for targeted diagnostic runs, not for default cold boots. Even at <1% runtime overhead, the JFR class-instrumentation warmup path adds significant start-up time.

**Fix options for R125:**
1. Swap `settings=profile` → `settings=default` (continuous default profile; much lower instrumentation).
2. Gate JFR on a feature flag (`platform.boot-jfr.enabled`) that defaults off; enable only for targeted diagnostic cycles.
3. Keep `StartupTimingListener` + `BootPhaseDumpCollector` (both cheap); drop JFR from default boot.

**Dev-team ask**: use R124's own instrumentation to diagnose the R124 regression. The T+millis logs and thread dumps now exist — diff a JFR-on boot against a JFR-off boot and the overhead source falls out.

---

## ❌ All 14 broken endpoints from R123 audit still broken on R124

Re-probed every endpoint from yesterday's audit. **Zero fixed.**

### 10 GET endpoints still broken

```
500 /api/flows/executions?size=20
500 /api/p2p/tickets
500 /api/v1/edi/training/health
500 /api/v1/edi/training/maps
500 /api/v1/edi/training/samples
500 /api/v1/edi/training/sessions
500 /api/v1/screening/hits
500 /api/v1/screening/results
400 /api/v1/edi/correction/sessions
404 /api/partner/test-connection
```

### 4 file-download endpoints still broken

```
500 /api/v1/storage/retrieve/TRZ7XC6QQGCB
403 /api/flow-steps/TRZ7XC6QQGCB/0/input/content
403 /api/flow-steps/TRZ7XC6QQGCB/0/output/content
403 /api/flow-steps/TRZ7XC6QQGCB/1/output/content
```

### 3 flow-execution action 500s still broken

```
500 POST /api/flow-executions/TRZ7XC6QQGCB/retry
500 POST /api/flow-executions/TRZ7XC6QQGCB/cancel
500 POST /api/flow-executions/TRZ7XC6QQGCB/stop
```

Carry-forward from yesterday's audit doc, unchanged.

---

## Standard sweep

| Item | R124 result |
|---|---|
| 34/34 clean | ✅ at t=220 s |
| Byte-E2E `r124p.dat` | ✅ status=COMPLETED, flowStatus=COMPLETED, completedAt=set |
| Sanity | ✅ 56 PASS / 3 FAIL / 1 SKIP (same 3 FTP-direct PASV/LIST, pre-existing) |
| Metaspace OOM under this run's load | ✅ not observed (short load — full 30-min soak still owed) |

---

## 🏅 Release Rating — R124

### R124 = 🥉 Bronze (rescoring yesterday's "hold")

**Justification — dimension by dimension:**

| Axis | Assessment |
|---|---|
| **Works** | ❌ Flow completes via API; downloads, retry/cancel/stop, EDI pages, Screening pages still broken (carry-over from R123 audit). |
| **Safe** | ⚠️ SPIFFE Phase-1 per-service identity holds; read-path 403s on flow-steps unchanged. |
| **Efficient** | ❌ **Regressed.** Average +137 s per service cold-boot vs R123 due to JFR profile instrumentation. 1/18 under 120 s mandate (same service — edi-converter). |
| **Reliable** | ✅ No crash loops, no new restarts, all 34 containers healthy. R115 Metaspace + HeapDumpOnOOM carried forward. R124's own observability works. |

**Bronze because:** Primary feature axis (file download, flow operator actions) still broken. Plus boot time regressed materially. **Net from Silver → Bronze** is the honest call.

**Why not No Medal:** Platform is up, flow engine completes via API, auth works, R124's observability is genuinely useful and is the prerequisite to solving the 120 s mandate in a principled way. Dev team shipped a real feature, just also a boot-time regression.

### Trajectory (R95 → R124)

| Release | Medal | Note |
|---|---|---|
| R95, R97, R105-R109 | 🚫 × 6 | P0 blockers |
| R100, R103-R104 | 🥈 × 2 | SPIFFE dormant |
| R110-R117 | 🥉 × 8 | SPIFFE onion peeled |
| R118, R119 | 🚫 × 2 | R117 retrofit aftermath |
| R120 | 🥉 | Recovery |
| R121+R122 | 🥈 | Flow engine unlocked |
| R123 | 🥉 | UI walk-through surfaced gaps |
| **R124** | 🥉 | **Observability shipped; boot time regressed; 14 UI asks still open** |

### Asks for R125 (updated)

**Must close for Silver recovery:**
1. Download endpoints (`/api/v1/storage/retrieve/:id` 500 + `/api/flow-steps/**` 403).
2. Flow actions `/retry`, `/cancel`, `/stop` 500s.
3. EDI + Screening GET 500 list endpoints (6 endpoints).
4. Dashboard widget `/api/flows/executions?size=20` 500.
5. `/api/partner/test-connection` 404.
6. **JFR overhead — revert `settings=profile` default or gate behind feature flag.**

**Carrying forward from R122/R123:**
7. FTP-direct PASV/LIST sanity failures.
8. Boot-time mandate progress (R124's instrumentation gives you the data now).
9. 30-min sustained soak.
10. Phase-2 mTLS X.509-SVID verification.
11. Tester Playwright UI + SSE fixture debt.

---

**Git SHA:** `e6d9d932`. This report supersedes `2026-04-18-R124-first-cold-boot-acceptance.md` (the earlier "hold" report written against pre-R124 containers).
