---
release: R132 ask list
tester: tester-claude
scope: UI/UX + API gap audit of Server creation, Security Profile, Partner / External Destination, connectivity test
priority: P0 — admin cannot configure the platform's security posture through the UI
---

# R132 — UX + Security wiring gaps (Servers, Security Profiles, Outbound, Connectivity Test)

CTO ask: *"Security profile is not connected to SFTP or FTPS servers; I don't
see certificate selection; server creation forms have pointless fields;
security profile should also apply to outbound connections; admin must be able
to test connectivity for external delivery partners; these are the logical
features I was hoping my product has — find all the gaps."*

This is that audit. Eleven gaps found across three surfaces:
1. **Server Instance create form** (UI, DTO, entity)
2. **Security Profile concept** (model + reuse across listeners + outbound)
3. **External Destination / Partner outbound** (security config + connectivity test)

Every gap is reproducible on R131 tip `b0eaff0c` and documented below with a
specific file + line reference and a concrete fix.

---

## GAP 1 — "Security Profile" dropdown submits a value the backend silently drops

**What admin sees:** Server Instance create form shows a "Security Profile"
dropdown (populated from `/api/listener-security-policies`) and a
"Compliance Profile" dropdown (populated from `/api/compliance/profiles`).
Admin picks values.

**What actually happens:**

```
ui-service/src/pages/ServerInstances.jsx:1028
  <select id="si-security-profile" value={form.securityProfileId} ...>
  <select id="si-compliance-profile" value={form.complianceProfileId} ...>

onboarding-api/src/main/java/com/filetransfer/onboarding/dto/request/
  CreateServerInstanceRequest.java — has NO securityProfileId field
  CreateServerInstanceRequest.java — has NO complianceProfileId field
```

The UI submits `{ securityProfileId, complianceProfileId, ... }`. Jackson
deserializes the body to the DTO, which has no matching fields, so both
values are dropped silently. The resulting `ServerInstance` has no link to
any security or compliance profile.

**Fix:** add `private UUID securityProfileId;` and
`private UUID complianceProfileId;` to `CreateServerInstanceRequest` and
`UpdateServerInstanceRequest`, persist them on `ServerInstance`, expose in
`ServerInstanceResponse`. Pin: create server with profile IDs → GET
server → both fields populated.

---

## GAP 2 — "Security Profile" is mis-modeled as a per-listener policy, not a reusable template

**Current:** `ListenerSecurityPolicy` is 1:1 with `ServerInstance` — each
policy has a `serverInstance` FK and is created as a child of one listener.
`/api/listener-security-policies` returns policies already tied to one
server, with the server object embedded.

**What the UI needs:** a template concept — the admin defines
"FTPS-Financial-Strict" *once* (cipher list, HMAC list, key-exchange list,
TLS min version, session timeout, allowed-auth-methods, etc.) and then
links it from many listeners. Today the admin has to re-enter the same
cipher list on every server.

**Evidence:**

```
GET /api/listener-security-policies →
[
  {
    "name": "FTPS Primary — Financial Policy",
    "serverInstance": { "id": "d319b671...", "name": "FTPS Server 1 — Primary", ... },
    "securityTier": "AI_LLM",
    ...
  }
]
```

Every policy has a single serverInstance. There is no "reusable profile
template" entity.

**Fix:** split the entity. Introduce `SecurityProfile` (template) with
`allowedCiphers`, `allowedMacs`, `allowedKex`, `minTlsVersion`,
`sessionTimeoutSeconds`, `maxAuthAttempts`, `bannerMessage`,
`pgpKeyAliases`, `tlsCertAlias`. Link `ServerInstance.securityProfileId`
→ `SecurityProfile.id` (N:1). Keep `ListenerSecurityPolicy` for per-listener
*enforcement* state (rate limits, IP allowlist, active) if the distinction
matters, but *the security knobs* belong on the template.

---

## GAP 3 — SFTP cipher / MAC / KEX lists are free-text strings on the server form

**What admin sees:**

```
ui-service/src/pages/ServerInstances.jsx:1089-1099
  <input id="si-ciphers" placeholder="aes256-gcm@openssh.com,..." />
  <input id="si-macs"    placeholder="hmac-sha2-256-etm@openssh.com,..." />
  <input id="si-kex"     placeholder="curve25519-sha256,..." />
```

Free-text comma-separated lists. The admin has to know the exact SSH
algorithm names and type them correctly — copy-paste from an external doc
every time. No validation, no picker.

**Fix:** these knobs should live in the Security Profile template (GAP 2).
The server form should show *only* "Security Profile: [dropdown]" with a
"View / edit profile" link. Delete the ciphers / MACs / kex inputs from
the per-server form entirely.

---

## GAP 4 — FTPS TLS certificate alias is a free-text input, not a Keystore picker

**What admin sees:**

```
ui-service/src/pages/ServerInstances.jsx:1141
  <input id="si-ftp-cert" value={form.ftpTlsCertAlias}
         placeholder="default-tls" />
```

Plain text. Admin has to know the keystore alias by heart. No validation,
no autocomplete.

**What exists in the platform:** `GET /api/v1/keys` returns the keystore
list: `aes-default`, `aes-outbound`, `pgp-inbound`, `pgp-outbound`, ... Five
aliases available.

**Fix:** change to `<select>` populated from `/api/v1/keys` filtered to
`type=X509_CERT` or similar. Show alias + algorithm + expiry. The same
picker should exist on the **outbound** External Destination form for
FTPS / AS2 client certificates.

---

## GAP 5 — Security Profile does NOT exist on the outbound (External Destination / Partner Account) surface

CTO: *"security profile will also be used for sftp/ftps/as2 type of outbound
connection when we make during external partner delivery — otherwise how
will admin manage the communication security?"*

**What admin sees on External Destinations form** (ExternalDestinations.jsx
line 69-84, 591-593):

```
fields collected:
  name, type (SFTP|FTP|FTPS|AS2|HTTPS), host, port,
  username, encryptedPassword (or url for HTTPS/API),
  proxyEnabled, proxyType, proxyHost, proxyPort
```

**What is NOT there:**
- No cipher / HMAC / kex selection (SFTP outbound)
- No TLS client cert picker (FTPS outbound)
- No AS2 signing key alias, MDN config, signing algorithm (AS2 outbound)
- No PGP key alias for encrypted-out-of-band handshakes
- **No "Security Profile" dropdown at all**

**Impact:** admin delivers files to an external partner via SFTP, but has
no control over what ciphers are negotiated. Partner may require a
specific TLS version or cert — no way to configure. If the partner is a
regulated entity (PCI, HIPAA), the platform can't prove the outbound
connection meets their security policy.

**Fix:** mirror the inbound concept — add `securityProfileId` on
`ExternalDestination` and on `TransferAccount` (for partner-account-scoped
outbound). The `testConnection` endpoint in `ForwarderController.java`
must accept the profile's cipher/cert/HMAC config and exercise the actual
handshake with those settings.

---

## GAP 6 — Admin can't perform a real connectivity test for a saved partner / external destination

What exists today:

```
POST /api/forwarder/test-connection
  body: { host, port, protocol, username, password, proxyEnabled,
          proxyType, proxyHost, proxyPort }
```

This is probed from the ExternalDestinations **create form** — takes raw
host+port+user+pass BEFORE save. Useful for pre-save validation.

**What DOESN'T exist:**

- "Test Connection" button on an *already-saved* external destination
  (editing an existing row).
- "Test this partner's delivery configuration" button on the
  Partner Management page — the existing `/api/partners/{id}/test-connection`
  returns the partner's account metadata, not a real handshake probe.
- A way to test the test uses **the same security profile / cert / cipher
  set** that the actual flow will use. Today the test probes raw TCP +
  auth; a flow would re-negotiate with its own SSL context, so the test
  is not a truthful proxy for flow behaviour.

**Fix:** wire up:
1. `POST /api/partners/{id}/accounts/{accountId}/probe` — uses the
   account's configured credential + security profile + proxy to perform
   a real SSH/TLS handshake and returns `{success, negotiatedCipher,
   negotiatedTlsVersion, certFingerprint, roundTripMs}` so the admin sees
   exactly what the flow will negotiate.
2. Same shape on ExternalDestination: `POST /api/external-destinations/{id}/probe`.
3. UI button visible on every saved row + at the end of every edit form.

---

## GAP 7 — Form has fields the admin shouldn't have to think about

CTO: *"server creation feels it has fields that have no point — I still need
to fill those fields."*

Looking at the ServerInstances.jsx create form:

| Field | Why it's there | Why admin shouldn't care |
|---|---|---|
| `instanceId` | Internal slug for listener ID | Derivable from protocol + random suffix; auto-generate if blank |
| `internalHost` | Container DNS of the service | Always `<protocol>-service` in docker; auto-fill, read-only |
| `internalPort` | Listener port inside container | Defaulted to a sane value per protocol; admin only cares about `externalPort` |
| `maxConnections=500` | Per-listener tuning | Not something a green admin knows to set; leave default |
| `defaultStorageMode` | VIRTUAL vs PHYSICAL | Should be hardcoded to VIRTUAL per `feedback_use_vfs`; PHYSICAL is legacy-only |
| `useProxy / proxyHost / proxyPort` | Reverse-proxy opt-in | Should collapse under an "Advanced: put this server behind a DMZ proxy" disclosure |
| `proxyQos.*` (burst, priority, max bytes/sec) | QoS knobs | Only appear if useProxy is true — but currently always visible |
| `sshBannerMessage` / `ftpBannerMessage` | Welcome text | Sane default; put under "Branding (optional)" |
| `idleTimeoutSeconds` / `maxAuthAttempts` / `sessionMaxDurationSeconds` | Session policy | Belong on the Security Profile, not per server |

**Fix:** collapse the form to a minimal-viable set per protocol:

```
Common:
  Display name      (required)
  Server type       (required, dropdown: SFTP / FTP / FTPS / FTP_WEB / AS2)
  External port     (required, defaulted)
  Security profile  (required, dropdown from SecurityProfile templates)
  Folder template   (required, dropdown)

Advanced (collapsed):
  - Put behind DMZ proxy → if enabled, show proxy* fields
  - Custom banner message
  - Override max connections
  - Override session timeout
  - instanceId auto-generated; editable in Advanced only
```

Every other field is a computed default or lives in a profile/template.

---

## GAP 8 — BUG 11 already filed (R131 report): `/listeners` page shows all 19 OFFLINE

Same failure mode as BUG 1: `PlatformListenerController` fan-out uses plain
`RestTemplate.getForObject()` with no Authorization header. Each downstream
service's Spring Security 403s the call. **Admin clicks Service Listeners
→ 19 OFFLINE. Platform is actually fine.**

Fix: forward inbound admin JWT on the fan-out, pattern-matching R131's
BUG 1 fix on `FlowStepPreviewController`.

---

## GAP 9 — No Keystore picker on any security-adjacent form

Keystore Manager lists 5 keys: `aes-default`, `aes-outbound`, `pgp-inbound`,
`pgp-outbound`, and one legacy alias. None of these are reachable via
autocomplete / picker from:
- Server Instance create/edit (FTPS TLS cert)
- External Destination create/edit (FTPS client cert, AS2 signing cert)
- Flow step config (when a step needs PGP key or AES alias)
- Partner Account (SFTP public-key auth key alias)

Every one of these surfaces today has a free-text input. Admin has to
alt-tab to Keystore, copy the alias, paste it, and hope they spelled it
correctly. One misspelling = silent failure at handshake time.

**Fix:** ship a reusable `<KeystoreAliasPicker type="TLS_CERT|PGP|AES"/>`
component. Drop it everywhere a cert or key is referenced.

---

## GAP 10 — Security Tier is a three-value enum with unclear semantics

Form field: `securityTier` (RULES / AI / AI_LLM). No explanation in the UI
of what each means beyond a label. `ListenerSecurityPolicy` has the same
enum. A profile-based design should either hide this (tier is derivable
from profile) or explain it in-line.

**Fix:** either remove from the per-server form (inherit from profile) or
add inline help: *"RULES = static allowlist only; AI = add ML-based
anomaly detection; AI_LLM = add LLM-based content inspection on upload."*

---

## GAP 11 — Compliance Profile has rich policy, but no admin UI to link it to a flow step

`/api/compliance/profiles` returns proper policies:
`{severity, allowPciData, allowPhiData, maxAllowedRiskLevel, requireEncryption, requireTls, blockedFileExtensions, ...}`. Rich content.

But:
- The server-create form shows a dropdown that submits to nothing (GAP 1).
- Flows (steps) don't surface a "Compliance profile: PCI-DSS-Strict" link — I checked Flow.jsx and the flow step editor, no compliance reference.
- At runtime, there's no clear enforcement point in the flow engine that reads the profile and rejects non-compliant files.

**Fix:** the compliance profile should be enforceable at least at two
points: (a) on a listener (reject on upload if file violates profile;
needs server ⇄ profile link to work — GAP 1), (b) on a flow step (reject
mid-flow if file metadata violates profile). Both surfaces need UI.

---

## Summary — wiring map

Drawn as who-references-whom:

```
ServerInstance ─(should, today broken)─> SecurityProfile (template)
                                          ├─ allowedCiphers
                                          ├─ allowedMacs
                                          ├─ allowedKex
                                          ├─ tlsCertAlias ──> Keystore(alias)
                                          ├─ bannerMessage
                                          └─ sessionTimeoutSeconds

ServerInstance ─(should, today broken)─> ComplianceProfile
                                          ├─ requireTls
                                          ├─ requireEncryption
                                          ├─ maxAllowedRiskLevel
                                          └─ blockedFileExtensions

ExternalDestination ─(today MISSING)──> SecurityProfile (outbound)
TransferAccount   ─(today MISSING)───> SecurityProfile (partner scoped)

ExternalDestination ─(should, today partial)─> testConnection(
                      uses saved profile + cert + cipher, not raw fields)

Partner ─(today minimal)─> accountlist
      ─(today MISSING)─> probe/verify delivery with actual handshake
```

Today every arrow labelled "should, today broken / missing" is either
silently dropped (securityProfileId on server create), or a free-text
copy-paste, or completely absent from the DTO/entity. Admin cannot
centrally govern ciphers, certs, or compliance policy — they restate it
per-server per-account per-destination, which is the exact opposite of
why a platform exists.

---

## R132 code change estimate (ordered by impact ÷ effort)

1. **GAP 8 (BUG 11) fan-out JWT forwarding** — 15 min + 1 pin. Unblocks
   the Service Listeners page entirely.
2. **GAP 1 — add `securityProfileId` + `complianceProfileId` fields to
   the server create/update DTO + entity + response**. 30 min + 2 pins
   (round-trip on each).
3. **GAP 4 — Keystore picker component, drop into server FTPS cert field**.
   30 min; then cascades to GAPs 5 + 9 which reuse it.
4. **GAP 6 — probe endpoint on saved external destination + partner
   account**. 45 min (backend + UI buttons + result shape).
5. **GAP 2 — separate SecurityProfile template entity from
   ListenerSecurityPolicy enforcement**. 3-4h. Migration + entity split +
   backfill.
6. **GAP 3 — remove cipher/MAC/kex free-text from server form; render
   inside SecurityProfile editor instead**. 1h.
7. **GAP 5 — outbound SecurityProfile on ExternalDestination +
   TransferAccount**. 2h, once GAP 2 lands.
8. **GAP 7 — collapse server create form to essentials + Advanced
   disclosure**. 2h UX pass.
9. **GAP 10 + 11 — Security Tier help text + Compliance Profile
   flow-step enforcement wiring**. 2h.

Total: ~13-14 hours of focused dev work + pin writing. None of it
is speculative; every gap is observed at runtime on the current tip.

---

## Why this is a grade-gating category

Per `feedback_gold_medal_criteria.md`: *Gold = platform delivers its full
design vision.* The platform currently doesn't. Admin can't govern
security centrally — they restate it per listener per partner per
destination per flow, or they can't at all for outbound.

Per `feedback_admin_can_do_anything.md`: *admin UI actions must succeed
end-to-end.* Admin picks Security Profile → backend drops it. That's not
succeeding end-to-end; it's *looking* like it succeeded while actually
ignoring the input.

Closing GAPs 1-11 is what moves the platform from "demos well" to
"operates for a real regulated customer." Everything else R132+ should
block on these first.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
