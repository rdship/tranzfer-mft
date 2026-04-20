# R134k — 🥈 Silver: BUG 13 CLOSED on the real flow-engine path

**Commit verified:** `a3dbc40b` — *R134k: explicit YAML bindings for platform.security.\* — close BUG 13 on flow-engine path*
**Branch:** `main`
**Date:** 2026-04-19 (Apr 20 UTC)
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 35/36 healthy
**Verdict:** 🥈 **Silver** — BUG 13 (the oldest-outstanding S2S auth bug, open since R127) is now closed on the real flow-engine path. Flagship shipped. Not Gold because of third-party-deps + cosmetic `"Using generated security password"` warning still printing on 4/5 services + unrelated open gaps (D, `/actuator/info` auth posture, missing AS2 listener bootstrap).

---

## The headline

R134j's regression flow re-fired on a fully rebuilt stack; the 403-no-body from previous cycles is gone. Concrete evidence:

### Flow execution end-state

```
flow:   SFTP Delivery Regression          (R134j bootstrap seed)
source: globalbank-sftp (VIRTUAL, R134i)
status: FAILED
step 0  CHECKSUM_VERIFY   OK       ← proves VFS/Gap C still resolved
step 1  FILE_DELIVERY     FAILED   ← but with a DIFFERENT error
```

Step 1 error (full):

```
FILE_DELIVERY failed for all 1 endpoints:
  partner-sftp-endpoint: 500  on POST request for
  "http://external-forwarder-service:8087/api/forward/deliver/<endpointId>"
```

### external-forwarder-service log for that correlationId

```
[PlatformJwtAuthFilter] entered method=POST
  uri=/api/forward/deliver/6ff9bea6-...
  authz=Bearer eyJhbG...(370)           ← SPIFFE JWT-SVID present (370 chars)
  xfwd=(none)                            ← no user JWT — flow-engine initiated

Unhandled exception at /api/forward/deliver/...:
  Delivery failed after 3 attempt(s) to 'partner-sftp-endpoint':
  DefaultConnectFuture[partner-a-user@sftp.partner-a.com/<unresolved>:22]:
  Failed (UnresolvedAddressException)
```

Full stack trace includes `SftpForwarderService.forward(SftpForwarderService.java:46)` and `ForwarderController.dispatchDelivery(ForwarderController.java:375)` — we're deep in the controller's business logic, *well past* the security layer.

### Why this closes BUG 13

| Cycle | Flow engine → forwarder result | Forwarder controller reached? |
|---|---|---|
| R127 → R134j (5 cycles, including R133's X-Forwarded-Authorization nested fallback) | HTTP 403, no body, filter silent | No |
| **R134k (this cycle)** | **HTTP 500** with `UnresolvedAddressException` deep in `SftpForwarderService.forward`:46 | **Yes** |

HTTP 500 on `partner-sftp-endpoint: sftp.partner-a.com` is the **intended** outcome of R134j's regression flow — that's a fake hostname chosen so the outbound SFTP attempt always fails (keeping the test hermetic). What matters is that we got to the outbound attempt at all, which requires the SPIFFE JWT-SVID having been accepted, Spring Security having let the request through, `@PreAuthorize` (if any) having passed, and `ForwarderController.deliverFile` having been entered.

**BUG 13 is closed.**

---

## What R134k did + what it didn't quite finish

### What landed as advertised

Explicit YAML placeholders under `platform.security.*` on the 5 internal services (edi-converter, encryption-service, external-forwarder-service, screening-service, storage-manager). This forces Spring Environment to expose `platform.security.shared-config=false` + `platform.security.internal-service=true` deterministically, regardless of env-var relaxed-binding quirks.

**Functional effect: confirmed.** BUG 13 path now authorises. `InternalServiceSecurityConfig`'s `SecurityFilterChain` is registering and taking priority over Spring's default auto-config for the relevant URL patterns.

### What didn't fully land

The R134k commit message predicted: *"Startup log: NO 'Using generated security password' on any of the 5 internal services"*. Actual:

| Service | Warning count |
|---|---|
| `edi-converter` | **0** ✅ (as predicted) |
| `storage-manager` | 1 |
| `encryption-service` | 1 |
| `external-forwarder-service` | 1 |
| `screening-service` | 1 |

The warning still prints on 4 of 5 services. But because BUG 13 is functionally closed on `external-forwarder-service`, the warning is **cosmetic** — Spring Boot's `UserDetailsServiceAutoConfiguration` always registers when `spring-boot-starter-security` is on the classpath and will print the warning, even when our custom `SecurityFilterChain` takes precedence. The warning reflects an *unused* autoconfig, not an active security posture.

Options the dev can pick:
1. **Leave it** — functionally fine; document the warning as expected noise.
2. **Suppress it** — add `UserDetailsServiceAutoConfiguration.class` to `@EnableAutoConfiguration(exclude=…)` on each internal service's `@SpringBootApplication`.
3. **Match edi-converter** — look at why edi-converter's YAML ends up excluding this auto-config and replicate on the other four.

### Why I'm grading 🥈 not Gold

Per rubric:

- 🥇 Gold = zero third-party runtime deps + every flow perfect e2e + no workarounds. This stack still runs on postgres, redis, rabbitmq, redpanda, vault, spire-server, spire-agent, minio, prometheus, loki, grafana, alertmanager, promtail. Gold is categorically out until that footprint changes.
- Gaps D (demo-onboard schema drift) and the `/actuator/info` auth-posture inconsistency are still open from prior cycles.
- AS2 listener isn't auto-seeded in bootstrap — Healthcare Compliance / EDI X12 flows still unreachable from a fresh stack.
- The R134k fix itself is incomplete (cosmetic warning on 4/5).

---

## Independent re-verification of prior fixes (regression check)

Ran as part of this cycle's single flow execution:

- **Gap A (R134i, partner accounts VIRTUAL):** SFTP upload worked with zero `mkdir` prep ✅
- **Gap C (R134i implicit, VFS handles flow-work):** CHECKSUM_VERIFY step passed (previously failed on `.flow-work` missing dir) ✅
- **Gap B clarification (R134i):** catch-all Mailbox Distribution correctly *lost* to priority-10 regression flow ✅
- **R134j regression seed:** flow seeded cleanly, direction matching works, steps execute in order ✅

---

## Environment

- Commit: `a3dbc40b`
- Accounts (bootstrap only): 6, all VIRTUAL
- Listeners: 2 SFTP bound, 0 AS2
- Flows: 7 (6 bootstrap + 1 `SFTP Delivery Regression`)
- Flow executions this cycle: 1 (`SFTP Delivery Regression` FAILED at step 1 with HTTP 500 `UnresolvedAddressException` — the intended test outcome)
- `external-forwarder-service` correlationId on the closed-BUG-13 path: `85771fc9`

---

## Open queue (unchanged from R134j)

1. **Gap D** — demo-onboard schema drift (~110 failures/run)
2. **`/actuator/info` auth posture** — onboarding-api 403 unauth, others public
3. **AS2 listener auto-seed** — so Healthcare Compliance / EDI X12 flows are reachable without demo-onboard
4. **Cosmetic:** suppress `UserDetailsServiceAutoConfiguration` warning on the 4 remaining services
5. **Third-party-dep retirement** — required for any future 🥇 Gold attempt

---

**Report author:** Claude (2026-04-19 session). BUG 13, open since R127, closed on the real path in R134k. 🥈
