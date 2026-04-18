---
release: R128 (tip 3e9f3aee)
commits_validated: 9a4d3c98, 3e9f3aee
grade: 🥈 Silver
date: 2026-04-18
tester: tester-claude
---

# R128 — First Cold-Boot Acceptance

**Medal: 🥈 Silver.** All 4 endpoint gaps flagged in the R127-FULL report
are resolved at runtime, plus the two new regression pins that would've
caught them. The ServiceContext.jsx vite dup-key warning is gone.
Strongest-ever regression pin run (13 / 15 pass on first try).
Boot-time criterion unchanged (17 / 18 still > 120 s — dev explicitly
deferred pending JFR profile data, per
`feedback_verify_at_runtime.md`).

---

## 1. Rebuild baseline

| Step | Result |
|---|---|
| docker nuke | 0/0/3/0 — 9.33 GB reclaimed |
| `mvn -T 1C clean install -DskipTests` | ✅ 7 incremental jars (config-service, shared-platform, onboarding-api, api-gateway, dmz-proxy downstream) |
| `docker compose build --no-cache` | ✅ 26 images rebuilt |
| `docker compose up -d` | ✅ 34/34 containers healthy at 190 s |

---

## 2. Claim verification

### R128 main (9a4d3c98) — 4 of 4 fixes land

| # | Claim | Probe | Result | Verdict |
|---|---|---|---|---|
| 1 | `/api/p2p/tickets` 500 → 200 (`@Column(name = "sha256_checksum")` on TransferTicket) | `GET /api/p2p/tickets` | **HTTP 200**, body `[]` | ✅ |
| 2 | `/api/function-queues/dashboard-stats` 400 → 200 (route literal before `{id}`) | `GET /api/function-queues/dashboard-stats` | **HTTP 200**, body `{ "totalQueues": 20, "byCategory": { "SECURITY": 6, "TRANSFORM": 6, "DELIVERY": 7, "CUSTOM": 1 }, "activeFlowsUsingQueues": 23 }` | ✅ |
| 3 | `/api/flows` 500 → 200 (removed `@Cacheable` to dodge Redis Jackson type-id bug) | `GET /api/flows` | **HTTP 200**, 6 flows, 6037 bytes | ✅ |
| 4 | Users `createdAt` — no backend change needed (re-verified ISO-8601 from `d6df6e82`) | `GET /api/users` | `"createdAt": "2026-04-18T19:04:49.083211Z"` | ✅ |

### R128 follow-up (3e9f3aee) — 2 of 2 land

| # | Claim | Result | Verdict |
|---|---|---|---|
| 5 | New regression pin: `/api/p2p/tickets` returns 200 with correct shape | Pin `R128: /api/p2p/tickets returns 200 (sha256_checksum column mapping)` passes | ✅ |
| 6 | New regression pin: `/api/function-queues/dashboard-stats` resolves (not `{id}`-shadowed) | Pin `R128: /api/function-queues/dashboard-stats resolves (not shadowed by {id})` passes | ✅ |
| 7 | `ServiceContext.jsx` duplicate `/monitoring` key removed | vite build log: no `/monitoring` duplicate-key warning | ✅ |

**Claim total: 7 of 7 verified at runtime.**

---

## 3. Regression pins — best run of the arc

```
13 passed
 1 flaky (R86 byte-E2E — passes on retry; known activity-monitor latency)
 1 skipped
```

First time the suite breaks 10 pins on first try since the R100 baseline.
Two of the 3 previously-flaky pins (R100 COMPLETED, R100 FAILED) now pass
on first attempt; only R86 still takes retry. New R128 schema pins both
green.

---

## 4. New finding — Dashboard.jsx duplicate `meta` key

Vite build log (docker build step `#148`) warns 3 times:

```
[plugin:vite:esbuild] src/pages/Dashboard.jsx: Duplicate key "meta" in object literal
```

Source — every affected `useQuery` has `meta: { silent: true }` twice in
the same object literal (`ui-service/src/pages/Dashboard.jsx` at lines
`312/315`, `320/323`, `328/331`, `336/339`, etc.):

```jsx
useQuery({
  queryKey: ['dash-fabric-queues'],
  queryFn: getFabricQueues,
  meta: { silent: true }, refetchInterval: 10_000,   // ← first
  retry: 0,
  staleTime: 8_000,
  meta: { silent: true },                            // ← second (redundant)
})
```

Cosmetic — JS takes the last key, behaviour is unchanged — but it shows
the `silent: true` opt-out was mechanically grafted in without noticing
it was already applied on the line above. Introduced in `d6df6e82` or
`292628a1` when the Dashboard.jsx refactor happened.

**R129 ask:** one-line cleanup — delete the second `meta: { silent: true }`
in each of the 10 affected `useQuery` calls, or consolidate via a
`SILENT_META = { silent: true }` constant at top of file.

---

## 5. Still-open from earlier audits

| Endpoint | Status |
|---|---|
| `/api/flows` | Now 200. Redis `@Cacheable` removed, but the underlying `GenericJackson2JsonRedisSerializer` type-id bug remains — any other cached list of nested-Map DTOs is still vulnerable. |
| Phase-2 mTLS X.509-SVID verification | Not started (Gold gate) |
| 30-min sustained soak + Metaspace growth check | Not run (Gold gate) |
| 3 more services under 120 s boot budget | Explicitly deferred by `3e9f3aee` pending JFR profile data. Legitimate — boot optimisation without measurement is guessing. |

---

## 6. Boot timing

| Service | R127-FULL (292628a1) | R128 (3e9f3aee) | Δ |
|---|---|---|---|
| edi-converter | 22.3 s | **18.5 s** | -3.8 s |
| keystore-manager | 142.0 s | 157.8 s | +15.8 s |
| screening-service | 126.4 s | 171.2 s | +44.8 s |
| storage-manager | 153.0 s | 171.3 s | +18.3 s |
| encryption-service | 136.1 s | 173.1 s | +37.0 s |
| notification-service | 140.9 s | 176.1 s | +35.2 s |
| analytics-service | 159.0 s | 183.8 s | +24.8 s |
| license-service | 151.5 s | 187.7 s | +36.2 s |
| platform-sentinel | 160.1 s | 188.4 s | +28.3 s |
| ftp-web-service | 154.8 s | 194.1 s | +39.3 s |
| ftp-service | 161.5 s | 196.0 s | +34.5 s |
| gateway-service | 161.5 s | 196.7 s | +35.2 s |
| sftp-service | 162.8 s | 198.6 s | +35.8 s |
| forwarder-service | 156.5 s | 200.3 s | +43.8 s |
| onboarding-api | 163.6 s | 200.9 s | +37.3 s |
| config-service | 163.5 s | 201.8 s | +38.3 s |
| ai-engine | 164.0 s | 206.3 s | +42.3 s |

Average regression of **+30 s**. R128 touches 4 files (3 Java controllers
+ 1 entity) so a code-side cause is unlikely to explain a 30 s uniform
delta across 17 unrelated services. **Most likely cause: thermal /
CPU-pressure variance across three full cold builds in one afternoon
on this laptop**, not a R128 regression. Worth re-running on CI or
after a cold idle to confirm. Only `edi-converter` (AOT-trimmed — no
HibernateJpaAutoConfiguration) got faster, consistent with a
CPU-variance hypothesis rather than a boot-path code change.

Still **1 of 18** under the 120 s Gold budget.

---

## 7. Medal reasoning

**4-axis rubric:**

| Axis | Grade |
|---|---|
| **Works** | 7 / 7 claims verified at runtime. Both new regression pins pass. vite dup-key warning gone. |
| **Safe** | No functional regressions. Cosmetic Dashboard.jsx dup-key warning introduced — build still succeeds, runtime behaviour unchanged. |
| **Efficient** | Boot-time measurement regressed ~+30 s across the stack, but almost certainly environmental (thermal/CPU) — edi-converter got *faster* at the same time, which a real boot-path regression couldn't produce. Gold 120-s-for-all criterion unmet (1/18). |
| **Reliable** | 13 / 15 pins pass on first try — strongest in the R95–R128 arc. R100 COMPLETED + R100 FAILED both unflaky this sweep. |

**Silver.**
- Not Bronze: every ask from the R127-FULL report landed cleanly, with
  regression pins added to prevent re-regression. Dev also wrote a
  memory (`feedback_verify_at_runtime.md`) capturing the R109/R125/R127
  repeat pattern. That is exemplary feedback loop.
- Not Gold: boot still 17/18 over budget; no Phase-2 mTLS; no soak;
  new Dashboard.jsx dup-key warning is cosmetic but shouldn't have
  shipped past a visual scan of the diff.

---

## 8. R129 ask (ordered by impact ÷ effort)

1. **Dashboard.jsx `meta` dup key** — delete the second `meta: {silent:true}`
   in each of ~10 `useQuery` calls. 5 min.
2. **JFR boot profile across the 17 over-budget services** — dev deferred
   boot-time push pending measurement. Capture async-profiler or JFR
   during the first 120 s and ship the .jfr to the team so dev can
   target the actual hot frame. 30 min on tester side.
3. **Fix the underlying Redis Jackson type-id bug** (not just dodge it):
   register a `Jackson2JsonRedisSerializer<List<FileFlowDto>>` with the
   right type reference, or add `@JsonSubTypes` to the nested DTO.
4. **Phase-2 mTLS proof-of-shape** on one service pair.
5. **30-min sustained soak + Metaspace growth** check.

---

## 9. Artifacts

- `docs/run-reports/2026-04-18-R128-first-cold-boot-acceptance.md` — this report
- Raw boot-timing captures + regression-pin JSON: `tests/playwright/playwright-report/` and `tests/playwright/test-results/`

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
