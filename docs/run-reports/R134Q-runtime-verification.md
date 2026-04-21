# R134Q — 🥈 Silver contribution / 🥈 Silver product-state (holds R134O)

**Commit tested:** `8e056b3f` (R134Q)
**Date:** 2026-04-21
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134Q contribution** | 🥈 **Silver** (runtime-verified) | UI bug filed in [R134O-ui-bug-ai-suggest-flow.md](./R134O-ui-bug-ai-suggest-flow.md) closes cleanly. `FlowSuggestRequest` DTO with Jackson nullable fields + `@JsonIgnoreProperties(ignoreUnknown=true)` handles both legacy and structured payloads. Controller synthesises a description from structured fields when the UI omits `description` — giving the LLM real form context. Zero breaking changes. |
| **Product-state at R134Q** | 🥈 **Silver** (holds R134O) | R134O's storage-coord primary-path Silver still in effect. R134Q fixes a user-facing regression. All prior-cycle correctness checks hold. |

---

## Verification evidence

### 1. UI-structured payload (the exact reproducer from the bug report) — ✅

Request:
```json
POST /api/v1/ai/nlp/suggest-flow
{
  "sourceAccountId": "some-id",
  "filenamePattern": "*.edi",
  "direction": "Inbound",
  "existingSteps": ["ENCRYPT_PGP", "COMPRESS_GZIP", "ROUTE"]
}
```

Response: **HTTP 200**
```json
{
  "success": true,
  "flowDefinition": {
    "name": "auto-flow-9918",
    "steps": [
      {"type":"COMPRESS_GZIP","order":0,"config":{}},
      {"type":"ENCRYPT_PGP","order":1,"config":{}},
      {"type":"CONVERT_EDI","order":2,"config":{"targetFormat":"JSON"}},
      {"type":"ROUTE","order":3,"config":{}}
    ],
    "description": "Design a file-processing flow for files matching '*.edi' from partner some-id (direction: Inbound). The admin has already configured these steps: ENCRYPT_PGP, COMPRESS_GZIP, ROUTE. Suggest complementary steps or improvements to the pipeline."
  },
  "explanation": "Generated from keyword matching. Set CLAUDE_API_KEY for intelligent flow design."
}
```

The synthesised description matches the commit message's intent exactly. The LLM (or keyword fallback) gets real form context, not just a freetext paraphrase.

### 2. Legacy `{description}` payload — ✅ backward-compat preserved

Request:
```json
POST /api/v1/ai/nlp/suggest-flow
{"description": "encrypt incoming EDI files then deliver to partner"}
```

Response: **HTTP 200** with 2 suggested steps. Old callers (`ui-service/src/api/ai.js:15-16`) still work.

### 3. Silver regression — R134O storage-coord primary still wins

```
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)
```

No `tryAcquire FAILED` warning, no fallback. Silver path holds.

### 4. BUG 13 regression signature holds

```
SFTP Delivery Regression status=FAILED
    CHECKSUM_VERIFY  OK
    FILE_DELIVERY    FAILED: partner-sftp-endpoint: 500 on POST …
                     (UnresolvedAddressException, same R134k signature)
```

---

## What held across all prior cycles (regression re-verified)

- ✅ storage-coord primary path (R134O)
- ✅ Flow engine end-to-end (R134K + R134N + R134O)
- ✅ BUG 13 closed on real path (R134k, every cycle since)
- ✅ V95–V99 applied, outbox SQL clean, AS2 BOUND, FTP_WEB UNBOUND, encryption-service healthy, https-service running, Vault retired (11 third-party deps)
- ✅ Admin UI API smoke 200s
- ✅ Caffeine L2 cache active

---

## What R134Q added

One user-facing regression closed. The admin can now click "AI Suggest" on the Processing Flows / New Flow form without hitting a 400. The response is structured and immediately usable by the UI to populate the pipeline.

Implementation quality notes:
- **Correct layering** — the structured → description synthesis lives in the controller, not in `NlpService`. NlpService keeps its simple String-based contract.
- **Jackson-tolerant DTO** — `FlowSuggestRequest` with nullable fields + `@JsonIgnoreProperties(ignoreUnknown=true)` means future UI additions of more fields won't break existing callers.
- **Zero breaking changes** — both call shapes now succeed; commits are additive.

Matches Option B from the R134O UI-bug report exactly.

---

## Still open for future cycles

Unchanged from R134O:
- `ftp-2` secondary FTP UNKNOWN
- demo-onboard 92.1% (Gap D)
- 11 third-party runtime deps
- Coord endpoint auth posture (R134N permit-list lets `/coordination/locks/**` through without SPIFFE validation — if that's deliberate trust-internal-network posture, document; if SPIFFE validation desired, add explicit `.requestMatchers(...).hasAuthority(...)`)

Side observations from the R134O UI bug report (for a later cycle):
- Execution-history row shows `Started: 1/21/1970, 6:32:46 AM` — timestamp epoch-offset bug
- "Legacy: Source Path" label needs a deprecation hint
- Priority field lacks seed-flow comparative context

---

## Series progression

```
R134F  🥈 fix     outbox SQL unblocked
R134G  🥉 diag    wrong-close-path falsified
R134H  🥉 diag    FS/provider routing falsified
R134I  🥉 diag    channel/callback/size falsified
R134J  🥈 diag    ROOT CAUSE visible: storage-coord 403
R134K  🥈 fix     fallback chain — flow engine fires
R134L  🥈 diag    SPIFFE cold-boot race (later refuted)
R134M  🥈 diag    fix target is storage-manager SecurityConfig
R134N  🥈 fix     service-local SecurityConfig — storage paths work
R134O  🥈 fix     URL-encoded slash firewall — 🥈 SILVER product-state
R134Q  🥈 fix     AI-Suggest DTO — UI bug closed, Silver holds
```

11 cycles, 6 structural fixes, 5 diagnostic rounds. Silver held across the UI-bug cycle.

---

**Report author:** Claude (2026-04-21 session). R134Q is the first cycle-over-cycle Silver hold — the platform maintains its first Silver while fixing a new admin-UI bug.
