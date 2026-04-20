# R134 — Listener/ServerInstance Creation UI Gap Audit

**Commit base:** `f27caa8f` *(R134 + test fix follow-up)*
**Branch:** `main`
**Scope:** `ui-service/src/pages/ServerInstances.jsx` + `Listeners.jsx` ↔ `onboarding-api` DTOs ↔ `ServerInstance` entity
**Verdict:** **8 gaps** — 2 blockers (data loss on save), 2 broken (HTTPS + secondary FTP_WEB), 4 functional (missing CreateDTO fields + validation asymmetry)

---

## Executive Summary

The admin UI for creating a listener/server instance has **silent data-loss bugs**: two fields that render in the form (`complianceProfileId`, `securityProfileId`) are never sent to the API on create, and never returned on read, even though the entity persists them. Nine additional fields are present in `UpdateServerInstanceRequest` but missing from `CreateServerInstanceRequest`, forcing a **create-then-edit** round-trip for any non-trivial listener. HTTPS is listed as a selectable protocol but has **no configuration UI at all** — picking it creates an unbindable row.

### Top 5 fixes to land first

| # | Fix | File | Severity |
|---|---|---|---|
| 1 | Add `complianceProfileId` + `securityProfileId` to CreateDTO, UpdateDTO (already has), and ResponseDTO + all three mappers | [CreateServerInstanceRequest.java](../../onboarding-api/src/main/java/com/filetransfer/onboarding/dto/request/CreateServerInstanceRequest.java), [ServerInstanceResponse.java](../../onboarding-api/src/main/java/com/filetransfer/onboarding/dto/response/ServerInstanceResponse.java), [ServerInstanceService.java](../../onboarding-api/src/main/java/com/filetransfer/onboarding/service/ServerInstanceService.java) | BLOCKER |
| 2 | Add 9 missing SSH/session fields to CreateDTO (`securityTier`, `sshBannerMessage`, `maxAuthAttempts`, `idleTimeoutSeconds`, `sessionMaxDurationSeconds`, `allowedCiphers`, `allowedMacs`, `allowedKex`, `proxyGroupName`, `maintenanceMessage`) | CreateServerInstanceRequest.java + create() builder in service | BLOCKER |
| 3 | Add HTTPS-specific form section OR remove HTTPS from `PROTOCOLS` picker in [ServerInstances.jsx:31](../../ui-service/src/pages/ServerInstances.jsx#L31) | ServerInstances.jsx | BROKEN |
| 4 | Add `min/max` + paired-set validation to FTP PASV port inputs at [ServerInstances.jsx:1116-1126](../../ui-service/src/pages/ServerInstances.jsx#L1116-L1126) to mirror service-layer rule (1024–65535, both-or-neither) | ServerInstances.jsx | FUNCTIONAL |
| 5 | Warn + disable non-primary FTP_WEB rows until multi-Tomcat routing lands | ServerInstances.jsx:1207-1212 + ServerInstanceService bindability flag | FUNCTIONAL |

---

## Section 1 — Canonical Field Inventory

Source entity: [ServerInstance.java](../../shared/shared-platform/src/main/java/com/filetransfer/shared/entity/core/ServerInstance.java).

Legend: ✓ present · ✗ missing · 🔒 read-only (server-assigned)

### 1a. Base identity + network

| Field | Entity line | Create | Update | Response | Form state | Form input |
|---|---|---|---|---|---|---|
| `id` | 24 (UUID PK) | 🔒 | 🔒 | L13 | 🔒 | 🔒 |
| `instanceId` | 26 (@NotBlank @Size 64, unique) | L15 | 🔒 (immutable) | L14 | L83 | L952 |
| `protocol` | 30 (enum, @Builder=SFTP) | L18 | L10 | L15 | L83 | L943 |
| `name` | 37 (@NotBlank) | L21 | L11 | L16 | L83 | L961 |
| `description` | 39 | L23 | L12 | L17 | L83 | L966 |
| `internalHost` | 43 (@NotBlank) | L26 | L13 | L18 | L84 | L986 |
| `internalPort` | 48 (@Min 1 @Max 65535) | L29 | L14 | L19 | L84 | L990 |
| `externalHost` | 54 | L31 | L15 | L20 | L85 | L1001 |
| `externalPort` | 57 | L32 | L16 | L21 | L85 | L1005 |
| `active` | 209 (@Builder=true) | L52 | L21 | L29 | L120 | L971 |

### 1b. Proxy + QoS

| Field | Entity line | Create | Update | Response | Form state | Form input |
|---|---|---|---|---|---|---|
| `useProxy` | 62 | L35 | L17 | L22 | L86 | L1281 |
| `proxyHost` | 65 | L36 | L18 | L23 | L86 | L1295 |
| `proxyPort` | 68 | L37 | L19 | L24 | L86 | L1299 |
| `proxyGroupName` | 106 | **✗** | L31 | L45 | L104 | L1320 |
| `proxyQosEnabled` | 86 | nested (ProxyQoSConfig) | nested | L38 | L87 | L1334 |
| `proxyQosMaxBytesPerSecond` | 89 | nested | nested | L39 | L87 | L1347 |
| `proxyQosPerConnectionMaxBytesPerSecond` | 92 | nested | nested | L40 | L87 | L1355 |
| `proxyQosPriority` | 96 (@Builder=5) | nested | nested | L41 | L87 | L1362 |
| `proxyQosBurstAllowancePercent` | 100 (@Builder=20) | nested | nested | L42 | L87 | L1368 |

### 1c. Sessions + SSH security

| Field | Entity line | Create | Update | Response | Form state | Form input |
|---|---|---|---|---|---|---|
| `maxConnections` | 72 (@Min 1, @Builder=500) | L39 | L20 | L25 | L87 | L1221 |
| `folderTemplateId` | 75 (FK) | L41 | L22 | L26 | L89 | L1392+ |
| `defaultStorageMode` | 81 (@Builder="VIRTUAL") | L49 | L24 | L28 | L91 | L1248 |
| `securityTier` | 112 (@Builder="RULES") | **✗** | L33 | L46 | L94 | L1039 |
| `sshBannerMessage` | 116 (TEXT) | **✗** | L35 | L47 | L96 | L1061 |
| `maxAuthAttempts` | 122 (@Min 1, @Builder=3) | **✗** | L37 | L48 | L97 | L1051 |
| `idleTimeoutSeconds` | 128 (@Min 0, @Builder=300) | **✗** | L39 | L49 | L98 | L1226 |
| `sessionMaxDurationSeconds` | 133 (@Builder=86400) | **✗** | L41 | L50 | L99 | L1232 |
| `allowedCiphers` | 140 (TEXT) | **✗** | L43 | L51 | L100 | L1089 |
| `allowedMacs` | 144 (TEXT) | **✗** | L44 | L52 | L101 | L1094 |
| `allowedKex` | 148 (TEXT) | **✗** | L45 | L53 | L102 | L1099 |
| **`complianceProfileId`** | **153 (UUID)** | **✗** | **✗** | **✗** | L92 | L1016 |
| **`securityProfileId`** | **156 (UUID)** | **✗** | **✗** | **✗** | L93 | L1028 |

### 1d. Maintenance

| Field | Entity line | Create | Update | Response | Form state | Form input |
|---|---|---|---|---|---|---|
| `maintenanceMode` | 160 | L52 | L47 | L54 | L106 | L1444 |
| `maintenanceMessage` | 163 (TEXT) | **✗** | L48 | L55 | L107 | L1457 |

### 1e. FTP-specific

| Field | Entity line | Create | Update | Response | Form state | Form input |
|---|---|---|---|---|---|---|
| `ftpPassivePortFrom` | 169 | L61 | L52 | L68 | L109 | L1116 |
| `ftpPassivePortTo` | 172 | L63 | L54 | L69 | L110 | L1123 |
| `ftpTlsCertAlias` | 176 | L65 | L56 | L70 | L111 | L1141 |
| `ftpProtRequired` | 180 (enum NONE\|C\|P — service-enforced) | L67 | L58 | L71 | L112 | L1148 |
| `ftpBannerMessage` | 184 (TEXT) | L69 | L60 | L72 | L113 | L1132 |
| `ftpImplicitTls` | 188 | L71 | L62 | L73 | L114 | L1158 |

### 1f. FTP_WEB-specific

| Field | Entity line | Create | Update | Response | Form state | Form input |
|---|---|---|---|---|---|---|
| `ftpWebSessionTimeoutSeconds` | 194 | L75 | L65 | L76 | L116 | L1176 |
| `ftpWebMaxUploadBytes` | 197 | L77 | L66 | L77 | L117 | L1183 |
| `ftpWebTlsCertAlias` | 201 | L79 | L67 | L78 | L118 | L1193 |
| `ftpWebPortalTitle` | 205 | L81 | L68 | L79 | L119 | L1200 |

### 1g. Runtime bind state (read-only)

| Field | Entity line | Response | Notes |
|---|---|---|---|
| `bindState` | 217 (@Builder="UNKNOWN") | L62 | written by protocol services |
| `bindError` | 220 (TEXT) | L63 | last bind error |
| `lastBindAttemptAt` | 223 | L64 | — |
| `boundNode` | 226 | L65 | cluster node that owns the bind |
| `createdAt` | 230 | L30 | — |
| `updatedAt` | 234 | L31 | — |

---

## Section 2 — Gaps Grouped by Category

### A. Entity → CreateDTO gaps (11 fields silently default on POST)

| Field | Impact | Why it matters |
|---|---|---|
| `complianceProfileId` | Admin selects compliance profile in UI; never reaches DB | Regulatory / audit trail for HIPAA/PCI/GDPR listeners is blank on day-1 |
| `securityProfileId` | Same — selected security profile lost on create | No TLS/auth policy attached to new listener |
| `proxyGroupName` | UI input at L1320 dead on create; only writable via PATCH | Listener's proxy routing group unset until 2nd request |
| `securityTier` | Defaults to "RULES" (entity) regardless of UI choice | Can't create AI or AI_LLM-tier listeners in one step |
| `sshBannerMessage` | UI textarea at L1061 dead on create | SSH pre-auth banner unset until update |
| `maxAuthAttempts` | Defaults to 3; UI value ignored | Can't tighten brute-force policy at create |
| `idleTimeoutSeconds` | Defaults to 300s; UI value ignored | Can't enforce long-session or short-session policy at create |
| `sessionMaxDurationSeconds` | Defaults to 86400s | Same as above |
| `allowedCiphers` / `allowedMacs` / `allowedKex` | Defaults (null = server defaults); UI selections ignored | Can't harden SSH crypto at create |
| `maintenanceMessage` | Create-time maintenance message unsettable | Minor — maintenance is rarely set at create |

### B. DTO → UI gaps

None found — every DTO field that accepts input has a UI input, modulo category C below.

### C. UI → DTO gaps (dead-weight inputs)

| UI input | File:line | What happens today |
|---|---|---|
| `complianceProfileId` dropdown | [ServerInstances.jsx:1016](../../ui-service/src/pages/ServerInstances.jsx#L1016) | Payload key sent; Jackson ignores (no DTO field on Create/Update); silently lost |
| `securityProfileId` dropdown | [ServerInstances.jsx:1028](../../ui-service/src/pages/ServerInstances.jsx#L1028) | Same as above |

### D. Response gaps

| Field | Symptom |
|---|---|
| `complianceProfileId` | Not in ResponseDTO. Admin PATCHes it (UpdateDTO also missing — so PATCH also drops it) then GETs: returned as null. |
| `securityProfileId` | Same. |

Note: the entity *has* the column; the mapper just never reads it. So the row in Postgres always has NULL for these two fields. (Verified in the Category A analysis — the service create() + update() never builder-calls `.complianceProfileId()` / `.securityProfileId()`.)

### E. Validation mismatches

| Rule | Entity/Service enforces | UI enforces | Gap |
|---|---|---|---|
| FTP PASV: `1024 ≤ from ≤ to ≤ 65535` | [ServerInstanceService.java:384-396](../../onboarding-api/src/main/java/com/filetransfer/onboarding/service/ServerInstanceService.java#L384-L396) | None | User can submit 1-65535 or `to < from`; 400 at server |
| FTP PASV: both-or-neither | same | None | Only one-of submission → cryptic backend error |
| FTP `protRequired` enum | [ServerInstanceService.java:398](../../onboarding-api/src/main/java/com/filetransfer/onboarding/service/ServerInstanceService.java#L398) `NONE\|C\|P` | Dropdown hard-codes same 3 values | ✓ consistent |
| SSH cipher/MAC/KEX allowlist | none (free-form TEXT) | none | Backend may reject at bind time with no UI surface |
| `internalPort` @Min 1 @Max 65535 | Entity | HTML5 `min/max` | ✓ consistent |

### F. Enum coverage

| Enum | UI values | Backend values | Gap |
|---|---|---|---|
| `protocol` | `SFTP, FTP, FTP_WEB, HTTPS, AS2, AS4` ([L31](../../ui-service/src/pages/ServerInstances.jsx#L31)) | same — entity declares same `Protocol` enum | ✓ values match, **but** HTTPS has no form section (see Section 4) |
| `defaultStorageMode` | `PHYSICAL, VIRTUAL` | same | ✓ values match; **default mismatch** — UI empty-form uses `PHYSICAL` ([L91](../../ui-service/src/pages/ServerInstances.jsx#L91)), entity default is `VIRTUAL` ([L81](../../shared/shared-platform/src/main/java/com/filetransfer/shared/entity/core/ServerInstance.java#L81)) |
| `securityTier` | `RULES, AI, AI_LLM` | same (per CLAUDE.md) | ✓ but missing from CreateDTO |
| `ftpProtRequired` | `NONE, C, P` + "service default" (empty) | same | ✓ consistent |

### G. Default value mismatches

| Field | Entity default | UI empty-form default |
|---|---|---|
| `defaultStorageMode` | `VIRTUAL` | `PHYSICAL` |
| `internalPort` | `2222` | protocol-indexed via `DEFAULT_PORTS` map (SFTP=2222 ✓, FTP=21 ✓, FTP_WEB=8083 ✓, HTTPS=443, AS2=10080) |

### H. Protocol-specific sections missing

See Section 4 for the bindability simulation per protocol.

---

## Section 3 — Listener Creation UX Flow

**Architecture:** there is no separate `Listener` table. `ServerInstance` *is* the listener spec. On create:

1. `POST /api/servers` → `CreateServerInstanceRequest` → entity persisted
2. `ServerInstanceService` publishes `ServerInstanceChangeEvent.ChangeType.CREATED` (outbox pattern)
3. Protocol services (sftp-service, ftp-service, as2-service, …) consume the event over RabbitMQ and bind dynamically
4. Bind result written back to `bindState`/`bindError`/`lastBindAttemptAt`/`boundNode` on the entity
5. UI polls the row, shows a colored badge per bindState (green = BOUND, red = FAILED, grey = UNKNOWN)

This single-step flow is clean. The gap is purely in **what fields** can be set in the one POST — currently 11 fewer than the entity supports.

**[Listeners.jsx](../../ui-service/src/pages/Listeners.jsx) is a read-only page** — platform-level service-health listeners (HTTP-framework), pause/resume only. It is **not** where ServerInstances are created (despite the name). Admins must go to the Server Instances page to create a listener — this is a discoverability issue but not a correctness issue. Consider renaming Listeners.jsx to "Listener Health" or adding a cross-link.

---

## Section 4 — Bindability Simulation

"Can an admin create each protocol from scratch and have it bind successfully on first save?"

| Protocol | Verdict | What binds | What's lost / broken |
|---|---|---|---|
| **SFTP** | 🟡 BINDABLE (partial) | Core SFTP listener binds on configured port | compliance/security profile, SSH banner, cipher/MAC/KEX, idleTimeout, sessionMaxDuration, maxAuthAttempts, securityTier all default — admin must follow up with a PATCH |
| **FTP** | 🟡 BINDABLE (partial) | FTP listener with PASV range, banner, PROT, implicit-TLS, TLS cert | Same SSH/session fields lost (but SFTP-specific SSH fields don't apply); compliance/security profile lost |
| **FTP_WEB** | 🔴 PARTIAL | **First instance only** — per in-form note at [L1207-1212](../../ui-service/src/pages/ServerInstances.jsx#L1207-L1212), secondary FTP_WEB rows remain `UNBOUND` until multi-Tomcat routing lands. UI allows creation, gives no warning. | All secondary rows are dead until upstream reverse-proxy feature lands |
| **AS2** | 🟡 BINDABLE (minimal) | Base AS2 listener opens on port | **No AS2-specific UI**: MDN settings, partner certs, signing, encryption, message types — none configurable on the ServerInstance form. These presumably belong on a separate AS2 Partner entity; if so, document and cross-link. |
| **AS4** | 🟡 BINDABLE (minimal) | Base AS4 listener opens | Same as AS2 |
| **HTTPS** | 🔴 BROKEN | Protocol selectable in picker; port 443 default | **No HTTPS form section at all** — no TLS cert selector, no cipher suite, no trust store. Likely will fail at bind time, or bind with a hard-coded default cert with no UI knob. Either add a form section or remove from `PROTOCOLS`. |

---

## Section 5 — Proposed Fix Plan

### Phase 1 — Close data-loss holes (blocks merge-to-prod)

1. **Add to `CreateServerInstanceRequest`:** `complianceProfileId`, `securityProfileId`, `proxyGroupName`, `securityTier`, `sshBannerMessage`, `maxAuthAttempts`, `idleTimeoutSeconds`, `sessionMaxDurationSeconds`, `allowedCiphers`, `allowedMacs`, `allowedKex`, `maintenanceMessage` (11 fields).
2. **Add to `UpdateServerInstanceRequest`:** `complianceProfileId`, `securityProfileId` (2 fields — the other 9 are already present).
3. **Add to `ServerInstanceResponse`:** `complianceProfileId`, `securityProfileId` (2 fields).
4. **Wire in `ServerInstanceService`:**
   - `create()` builder: `.complianceProfileId(req.complianceProfileId()).securityProfileId(req.securityProfileId())` + the other 10
   - `update()`: same
   - `toResponse()`: add both fields
5. **UI default fix:** change `ServerInstances.jsx:91` empty-form `defaultStorageMode` from `"PHYSICAL"` to `"VIRTUAL"` to match entity builder default.

### Phase 2 — Close validation asymmetry

6. **FTP PASV inputs at [L1116](../../ui-service/src/pages/ServerInstances.jsx#L1116), [L1123](../../ui-service/src/pages/ServerInstances.jsx#L1123):** add HTML5 `min=1024 max=65535`, client-side check "both-or-neither" + `from ≤ to`. Duplicates server validation but prevents 400 round-trips.
7. **SSH cipher/MAC/KEX inputs at [L1089-L1099](../../ui-service/src/pages/ServerInstances.jsx#L1089-L1099):** show comma-separated format hint + on-blur allowlist check against a known-good list (publishable by ai-engine or sftp-service).

### Phase 3 — Close protocol-section holes

8. **HTTPS:** Either add an HTTPS form section (TLS cert, cipher suites, client-cert required y/n) OR delete HTTPS from `PROTOCOLS` array at [L31](../../ui-service/src/pages/ServerInstances.jsx#L31). Current state is worst-of-both.
9. **AS2 / AS4:** Add a small form section for the minimum set (MDN required y/n, MDN signing alg, partner AS2 ID) OR cross-link to the AS2 Partner page if configuration lives there. Today the admin sees no AS2-specific fields and assumes the listener is complete when it isn't.
10. **FTP_WEB secondary listeners:** When `protocol === 'FTP_WEB'` and there's already ≥1 row, either disable the submit button with tooltip ("Multi-Tomcat routing required; see [note]") or mark the new row's `bindState` as `UNSUPPORTED` with a dedicated badge.

### Phase 4 — Nice-to-haves

11. Rename `Listeners.jsx` → "Listener Health" or add a "Create Listener" CTA that deep-links to `ServerInstances.jsx` in create mode.
12. Add `bindState === 'FAILED'` row highlighting to the ServerInstances table so admins immediately see when a newly created row didn't bind.

---

## Appendix — Files Audited

| File | Lines | Role |
|---|---|---|
| [shared/shared-platform/.../entity/core/ServerInstance.java](../../shared/shared-platform/src/main/java/com/filetransfer/shared/entity/core/ServerInstance.java) | 234+ | JPA entity — source of truth |
| [onboarding-api/.../dto/request/CreateServerInstanceRequest.java](../../onboarding-api/src/main/java/com/filetransfer/onboarding/dto/request/CreateServerInstanceRequest.java) | ~91 | Create payload |
| [onboarding-api/.../dto/request/UpdateServerInstanceRequest.java](../../onboarding-api/src/main/java/com/filetransfer/onboarding/dto/request/UpdateServerInstanceRequest.java) | ~69 | Update payload |
| [onboarding-api/.../dto/response/ServerInstanceResponse.java](../../onboarding-api/src/main/java/com/filetransfer/onboarding/dto/response/ServerInstanceResponse.java) | ~80 | Read payload |
| [onboarding-api/.../service/ServerInstanceService.java](../../onboarding-api/src/main/java/com/filetransfer/onboarding/service/ServerInstanceService.java) | ~400 | Create/update/toResponse mappers |
| [ui-service/src/pages/ServerInstances.jsx](../../ui-service/src/pages/ServerInstances.jsx) | ~1500 | Admin form |
| [ui-service/src/pages/Listeners.jsx](../../ui-service/src/pages/Listeners.jsx) | ~122 | Read-only health view |

---

**Report author:** Claude (sonnet/opus pair, 2026-04-19 session)
**Basis:** Static audit of source at commit `f27caa8f`. No runtime testing of UI — recommend a follow-up manual smoke test in the admin UI after Phase 1 fixes land, to confirm the create-with-settings round-trip.
