# R134g — Verification Report (tester response to [R134g-asks.md](./R134g-asks.md))

**Release tested:** `b428d092` — *R134g: listener-UI Phase 2 (FTP PASV + SSH allowlist validation) + asks*
**Date:** 2026-04-19
**Environment:** full nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 35/36 healthy, 0 unhealthy
**Verdict matrix:**

| Ask | Verdict |
|---|---|
| 1.1 — BUG 12 (`/api/delivery-endpoints` + password → encryption-service S2S) | ✅ **CLOSED** |
| 1.2 — BUG 13 (FILE_DELIVERY → forwarder-service SPIFFE WARN) | ⚠️ **INCONCLUSIVE** — need flow-wiring help |
| 1.3 — License regression (V93 migration / BUG 14) | ✅ **CLOSED** |
| 2a — FTP PASV inline validation (6 scenarios) | ✅ 5 PASS / ⚠️ 1 message nit |
| 2b — SSH cipher/MAC/KEX format validation (5 scenarios) | ✅ 5 PASS |
| 3 — pom.xml vs PLATFORM_VERSION | 💬 Opinion returned — stay with PLATFORM_VERSION |

---

## Ask 1.1 — BUG 12: ✅ CLOSED

### Reproduction

```bash
TOKEN=$(curl -sk -X POST http://localhost:8080/api/auth/login ...)
curl -sk -X POST http://localhost:8084/api/delivery-endpoints \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"bug12-test-...","protocol":"SFTP","host":"partner.example.com",
       "port":22,"username":"testuser","encryptedPassword":"MyPlaintextPassword123",
       "active":true}' → HTTP 201
```

Response contained the ciphertext (`encryptedPassword=oAtwhTVd+WBfZxucZV+Q...`) proving the encryption-service S2S call succeeded.

### Filter log line (exactly as the ask requested)

```
[PlatformJwtAuthFilter] entered method=POST uri=/api/encrypt/credential/encrypt
  authz=Bearer eyJhbG...(362) xfwd=Bearer eyJhbG...(173)
correlationId=31b01403  service=encryption-service
```

Immediately followed by the decision line:

```
[PlatformJwtAuthFilter] authorized via X-Forwarded-Authorization
correlationId=31b01403
```

### Branch taken

**Path 0.5 — X-Forwarded-Authorization.** The R134 fix (promote `X-Forwarded-Authorization` evaluation **before** the SPIFFE branch so a valid admin JWT wins even when SPIFFE is strict-audience-failing) is working exactly as designed. The filter is on the chain for every inbound S2S request (including actuator/* which logs `xfwd=(none)` for agent-initiated hops), and it authorises the BUG 12 path via the forwarded admin JWT.

### Why this class stayed open for 5 previous attempts

R133's `X-Forwarded-Authorization` fallback was nested **inside** the SPIFFE-failed branch — so calls where SPIFFE *succeeded* but only granted `ROLE_INTERNAL` (the same-trust-domain fallback behaviour) would pass SPIFFE, fail `@PreAuthorize(OPERATOR)` downstream, and return 403 without ever consulting the forwarded admin JWT. R134 hoists the forwarded-JWT check to Path 0.5 (before SPIFFE), so the user-initiated path is no longer at the mercy of how SPIFFE labels the caller.

---

## Ask 1.2 — BUG 13: ⚠️ INCONCLUSIVE

### Current state

- All SPIFFE agents connected across 12+ services (socket `unix:/run/spire/sockets/agent.sock`, identity `spiffe://filetransfer.io/<service>`)
- **Zero SPIFFE WARN / rejection logs** in forwarder-service or any other service since boot (~8 min uptime at report time)
- `file_flows` table has 6 seeded rows (EDI Processing Pipeline, Encrypted Delivery, Healthcare Compliance, Mailbox Distribution, Archive & Compress, +1) but **no folder_mappings link them to actual flow-trigger events** — the forwarder has no inbound S2S traffic to exercise

### Why I couldn't close this

The BUG 13 code path is:

`file upload to SFTP listener → ftp/sftp-service → flow engine → external-forwarder-service → S2S call (no user JWT) → SPIFFE JWT-SVID validation`

Triggering this end-to-end requires wiring a source listener ⇄ folder_mapping ⇄ flow ⇄ delivery endpoint and then uploading a file. `demo-onboard.sh` is supposed to do this but still has the port bug we reported in R134 (script targets `https://localhost:9XXX`, services actually listen on `http://localhost:8XXX`). With a fresh stack and no demo-onboard run, the forwarder sees no traffic.

### Ask back to dev

One of the following would close Ask 1.2 cleanly:

- **Fix demo-onboard.sh ports** (5-line change to `BASE` + protocol URLs — queued from the R134 audit) so a full flow can execute
- **Expose a "force-trigger" API** — e.g. `POST /api/file-flows/{id}/execute` with a synthetic file reference — so the flow engine → forwarder hop can be exercised without a real upload
- **Accept the partial pass** — negative evidence (no SPIFFE WARN in 8 min of runtime with SPIFFE mandatory everywhere) is already a signal that SPIFFE validation isn't failing silently. But without positive evidence of a *rejected* JWT-SVID with enriched aud/sub dump, we can't tell whether R134's logging enhancement is rendering correctly.

---

## Ask 1.3 — License regression: ✅ CLOSED

### Evidence

```
$ curl -sk -H "Authorization: Bearer $TOKEN" http://localhost:8089/api/v1/licenses
[]
HTTP 200
```

### Migration confirmed

```sql
SELECT version, description, success FROM flyway_schema_history WHERE version = '93';
 93 | license records services | t

SELECT column_name, data_type FROM information_schema.columns
  WHERE table_name = 'license_records' AND column_name = 'services';
 services | jsonb
```

V93 ran clean. `license_records.services JSONB NULL` column is present. The `column lr1_0.services does not exist` 500 from R133 is gone.

### Side finding (not a BUG 14 issue)

Via the **gateway** (`http://localhost:8080/api/v1/licenses`), the call 404s with `{"code":"ENTITY_NOT_FOUND","message":"No endpoint GET /api/v1/licenses"}`. Direct to license-service (`:8089`) works fine. This is a gateway-routing omission, not a license-service bug. Flagging separately because admin UI likely hits the gateway path.

---

## Ask 2a — FTP PASV inline validation

Static trace of all 6 scenarios against [`validateFtpPasvShape`](../../ui-service/src/pages/ServerInstances.jsx#L111-L124) and the pre-submit [`gateSubmit`](../../ui-service/src/pages/ServerInstances.jsx#L428-L436):

| # | Scenario | Expected (per ask) | Function returns | Verdict |
|---|---|---|---|---|
| 1 | `from=21000, to=''` | "set together" | "Passive Port From and To must be set together — either both or neither." | ✅ PASS |
| 2 | `from='', to=21010` | same | same | ✅ PASS |
| 3 | `from=500, to=21010` | "between 1024 and 65535" | "Passive Ports must be between 1024 and 65535." | ✅ PASS |
| 4 | `from=21000, to=100` | "cannot be greater than To" | "Passive Ports must be between 1024 and 65535." — range check at [L121](../../ui-service/src/pages/ServerInstances.jsx#L121) fires before the ordering check at [L122](../../ui-service/src/pages/ServerInstances.jsx#L122) because `to=100` fails the range test first | ⚠️ MESSAGE NIT — submit is still blocked |
| 5 | `from=21000, to=21010` | no error, proceeds | null | ✅ PASS |
| 6 | `from='', to=''` | no error, inherits | null | ✅ PASS |

### Optional fix for scenario 4

Either swap the order of the two guards in `validateFtpPasvShape` so `lo > hi` is checked before range:

```js
if (lo > hi) return `Passive Port From (${lo}) cannot be greater than To (${hi}).`
if (lo < 1024 || lo > 65535 || hi < 1024 || hi > 65535) return 'Passive Ports must be between 1024 and 65535.'
```

Or change the scenario-4 test input to `from=40000, to=30000` (both in range, reversed) so the existing order hits the ordering check.

Not a blocker — submit is correctly blocked either way; only the emitted message differs from the doc.

---

## Ask 2b — SSH cipher/MAC/KEX validation

All 5 scenarios traced against [`validateSshAllowlist`](../../ui-service/src/pages/ServerInstances.jsx#L89-L103):

| # | Input | Expected | Function returns | Verdict |
|---|---|---|---|---|
| 1 | `aes256-ctr, aes192-ctr` | "Remove spaces around commas" | "Remove spaces around commas. Format: aes256-ctr,aes192-ctr" | ✅ PASS |
| 2 | `aes256-ctr,,aes192-ctr` | "Remove empty entries" | "Remove empty entries. Format: aes256-ctr,aes192-ctr" | ✅ PASS |
| 3 | `aes256-ctr,bad token!` | "has an unsupported character" | `Token "bad token!" has an unsupported character. Format: aes256-ctr,aes192-ctr` | ✅ PASS |
| 4 | `aes256-ctr,aes192-ctr` | no error | null | ✅ PASS |
| 5 | empty | no error (inherits) | null (early return at `value === ''`) | ✅ PASS |

Regex `/^[A-Za-z0-9][A-Za-z0-9._@-]*$/` correctly rejects the space and `!` in scenario 3.

### Caveat common to 2a + 2b

This is **static analysis**. I traced each input through the JS functions — the pre-submit gate (`gateSubmit`) and the three inline-error renderers at [L1145](../../ui-service/src/pages/ServerInstances.jsx#L1145), [L1153](../../ui-service/src/pages/ServerInstances.jsx#L1153), [L1161](../../ui-service/src/pages/ServerInstances.jsx#L1161), [L1192](../../ui-service/src/pages/ServerInstances.jsx#L1192) share the same validators, so a correct validator guarantees consistent behaviour between the inline error and the submit-block. What I **cannot** verify without a headless browser: React rendering (red ⚠ colour, placement under the input), toast visibility, and the exact UX. A manual smoke in the Admin UI is still recommended before signing Phase 2 off.

---

## Ask 3 — pom.xml vs PLATFORM_VERSION

**Recommendation: stay with `PLATFORM_VERSION` as the runtime source of truth. Do not bump pom.xml per release.**

Reasoning:

1. **Jars never leave this repo.** Not published to Maven central or an internal Nexus — they only feed Docker builds. Maven coordinates only matter when an external consumer resolves them; nothing does.
2. **22 modules × every R-tag = churn.** `mvn versions:set` touches every module pom *and* every `<parent>` ref, so each release becomes a 22-file diff with zero operational value.
3. **PLATFORM_VERSION already answers "what's running"** — it drives the UI platform-status banner (user-facing SoT) and docker-compose env (operator-facing SoT). Runtime never reads pom.xml.
4. **The real gap is runtime observability, not pom lag.** `/actuator/info` is currently empty across all services (verified earlier in this session — no `build.version` field anywhere). The fix isn't bumping pom — it's wiring `PLATFORM_VERSION` into Spring Boot's `info` endpoint as an `InfoContributor`. One shared bean in `shared-platform` → every service's `/actuator/info` returns the same value the UI banner shows. No per-release churn.

**Actionable ask back:** Add a single `PlatformVersionInfoContributor` to shared-platform that reads `${PLATFORM_VERSION}` (or a Spring property injected at container startup) and surfaces it under `/actuator/info.build.version`. Then the report I file next time can include the runtime-reported version alongside the commit SHA.

If you want pom bumps anyway (for semantic versioning of binary artifacts), do it only at **phase boundaries** (e.g. every 10 R-tags or on GA cuts) — not per R.

---

## Environment footnote

- **Commit tested:** `b428d092` (pulled and fully rebuilt this session)
- **Stack state:** 35 / 36 containers `healthy` (`promtail` has no healthcheck; `db-migrate` exited as one-shot)
- **Test artifacts:** JWTs, POST payloads, and the exact filter log lines are in this report inline — no additional files

---

## Co-author note

Report author: Claude (tester role, 2026-04-19 session).
