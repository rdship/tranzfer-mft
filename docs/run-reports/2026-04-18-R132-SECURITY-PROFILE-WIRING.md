---
release: R132 — SecurityProfile end-to-end wiring audit
tester: tester-claude
scope: trace SecurityProfile from UI → DTO → entity → repository → runtime-handshake across
       every protocol (SFTP/FTP/FTPS/FTP_WEB/AS2/HTTPS) × direction (inbound listener, outbound client) ×
       service (sftp-service, ftp-service, ftp-web-service, as2-service, external-forwarder-service)
priority: P0 — platform cannot claim end-to-end security governance until this closes
---

# R132 — SecurityProfile wiring: what exists, what's orphaned, what's missing

The CTO vision: **one `SecurityProfile` concept — TLS or SSH — applies to
every communication surface depending on role (server vs client) and
direction (inbound vs outbound). A default profile ships with the
platform; the admin picks a profile per server and per outbound
destination, and the runtime handshake uses exactly those settings.
Fully wired, UI → DTO → entity → repository → runtime.**

**What actually exists today (verified on R131 tip `b0eaff0c`):**

A `SecurityProfile` JPA entity, fully-formed. A `security_profiles` table
with 4 seeded rows. A `server_instances.security_profile_id` FK column
on disk. An API at `/api/security-profiles` returning 2.4 KB of real
data. **And not a single runtime code path that reads any of it.**

The admin can't pick a profile from any UI. The DTO can't accept a
profile ID from any client. The runtime doesn't consult the profile for
any handshake. 4 protocols × 2 directions = 8 surfaces that should
consume SecurityProfile. **Zero** of them do.

This is the worst flavour of wiring gap — the data model is done (90%
of the work), but the integration was never completed, so the product
*looks* like it has centralized security governance and actually
doesn't.

---

## 1. The wiring matrix

Each row is one communication surface. Columns are each layer the
SecurityProfile value has to pass through to actually affect a handshake.

| Surface | DB col | Entity | Repo | DTO | UI picker | UI → DTO submit | Runtime reads profile | Runtime applies to handshake |
|---|---|---|---|---|---|---|---|---|
| **Inbound SFTP** (sftp-service `SftpSshServerFactory`) | ✅ `server_instances.security_profile_id` | ✅ `ServerInstance.securityProfileId` | ✅ | ❌ **DTO missing `securityProfileId`** | ⚠️ dropdown wrong source | ⚠️ submits the value but backend drops it | ❌ reads `si.getAllowedCiphers()` free-text, never `si.getSecurityProfileId()` | partial — SSH ciphers/MACs/KEX applied from free-text, not profile |
| **Inbound FTP/FTPS** (ftp-service `FtpsConfig`) | ✅ same FK | ✅ same entity | ✅ | ❌ same DTO gap | ⚠️ same | ⚠️ same | ❌ reads `si.getFtpTlsCertAlias()` free-text | partial — TLS cert loaded from Keystore via alias, no cipher or TLS-version control |
| **Inbound FTP_WEB** (ftp-web-service) | ✅ same FK | ✅ same entity | ✅ | ❌ same DTO gap | ⚠️ same | ⚠️ same | ❌ **zero wiring** (no `SecurityProfile` or `securityProfileId` reference anywhere in ftp-web-service code) | ❌ tomcat / spring-boot defaults only |
| **Inbound AS2** (as2-service) | ✅ same FK | ✅ same entity | ✅ | ❌ same DTO gap | ⚠️ same | ⚠️ same | ❌ **zero wiring** | ❌ framework defaults only |
| **Outbound SFTP** (external-forwarder `SftpForwarderService`) | ❌ **no FK** on external_destinations or transfer_accounts | ❌ no entity field | — | ❌ no DTO field | ❌ no UI picker anywhere | ❌ | ❌ not read | ❌ `ClientSession` built with library defaults |
| **Outbound FTP/FTPS** (`FtpsForwarderService`) | ❌ same | ❌ same | — | ❌ same | ❌ same | ❌ | ❌ not read | ❌ `new FTPSClient("TLS", false)` — no cipher / TLS version config |
| **Outbound AS2** (`As2ForwarderService`) | ❌ same | ❌ same | — | ❌ same | ❌ same | ❌ | ❌ not read | ❌ library defaults |
| **Outbound HTTPS** (various partner-portal clients) | ❌ same | ❌ same | — | ❌ same | ❌ same | ❌ | ❌ not read | ❌ library defaults |

**Summary:** 8 surfaces × 8 layers = 64 cells. ✅ in 15 cells (DB + entity
on inbound only, plus partial runtime for SFTP ciphers and FTPS cert).
**49 cells marked ❌ or ⚠️.** The work is 3/8 done on the inbound side,
**0/8 done on the outbound side**.

---

## 2. What's there (file evidence)

### 2a. SecurityProfile entity — ready to use

```
shared/shared-platform/src/main/java/com/filetransfer/shared/entity/core/SecurityProfile.java

  @Entity @Table(name = "security_profiles")
  public class SecurityProfile extends Auditable {
      @Id private UUID id;
      @Column(unique = true, nullable = false) private String name;
      private String description;
      private String type = "SSH";   // or "TLS"
      private List<String> sshCiphers;
      private List<String> sshMacs;
      private List<String> kexAlgorithms;
      private List<String> hostKeyAlgorithms;
      private String tlsMinVersion;
      private List<String> tlsCiphers;
      private boolean clientAuthRequired;
      private boolean active;

      // Static fallbacks — used by runtime when no profile linked
      public static final Set<String> ALLOWED_SSH_CIPHERS = Set.of(...);
      public static final Set<String> ALLOWED_SSH_MACS = Set.of(...);
      public static final Set<String> ALLOWED_SSH_KEX = Set.of(...);
      public static final Set<String> ALLOWED_TLS_CIPHERS = Set.of(...);
      public static final Set<String> ALLOWED_TLS_VERSIONS = Set.of(...);
  }
```

Proper entity. Correctly split SSH vs TLS. JSON columns for arrays.
Good foundation.

### 2b. DB columns — partially wired

```
\d security_profiles
  id, name, description, type, ssh_ciphers JSON, ssh_macs JSON,
  kex_algorithms JSON, host_key_algorithms JSON, tls_min_version,
  tls_ciphers JSON, client_auth_required, active, created_*, updated_*

\d server_instances
  ...
  compliance_profile_id UUID    ← FK ready
  security_profile_id    UUID   ← FK ready
  allowed_ciphers        TEXT   ← legacy free-text (should be deleted)
  allowed_macs           TEXT   ← legacy free-text (should be deleted)
  allowed_kex            TEXT   ← legacy free-text (should be deleted)
  ftp_tls_cert_alias     TEXT   ← legacy free-text (should live inside profile)
  ssh_banner_message     TEXT   ← legacy free-text (should live inside profile)
  ftp_banner_message     TEXT   ← legacy free-text (should live inside profile)

\d transfer_accounts
  ... no security_profile_id    ← MISSING

\d external_destinations
  ... no security_profile_id    ← MISSING (indirectly linked via
                                  listener_security_policies.external_destination_id,
                                  but that table is 1:1 with the policy, not the profile)
```

### 2c. Seed — 4 profiles, no default flag

```
SELECT name, type, active FROM security_profiles;

  Standard SSH            | SSH | t
  Standard TLS            | TLS | t
  High Security TLS       | TLS | t
  High Security SSH Ros   | SSH | t

(4 rows)
```

No `is_default BOOLEAN DEFAULT FALSE` column. There's no way for the
runtime to ask "what is the default SSH profile?" — which is the exact
fallback the CTO described ("we will always have one default security
profile that anyone can by default use").

### 2d. API — already serves the data

```
curl /api/security-profiles → HTTP 200, 2.4 KB, array of 4 profiles

curl /api/security-profiles   (same endpoint)
curl /api/v1/security-profiles  → 200 but returns SPA HTML (nginx fallthrough)
```

`/api/security-profiles` is reachable and returns the profiles. **No UI
dropdown anywhere reads from it.** The ServerInstances.jsx dropdown
labeled "Security Profile" reads from `/api/listener-security-policies`
— a different entity (per-listener enforcement state, not template).

### 2e. Runtime — inbound: partial wiring, always falls through to defaults

```
sftp-service/src/main/java/com/filetransfer/sftp/server/SftpSshServerFactory.java:85

  applyAlgorithms(sshd, si);   // receives ServerInstance si

  private void applyAlgorithms(SshServer sshd, ServerInstance si) {
      applyCiphers(sshd, si.getAllowedCiphers());  // ← free-text CSV
      applyMacs(sshd,    si.getAllowedMacs());     // ← free-text CSV
      applyKex(sshd,     si.getAllowedKex());      // ← free-text CSV
  }

  private void applyCiphers(SshServer sshd, String csv) {
      Set<String> allowed = csv != null && !csv.isBlank()
              ? parse(csv)
              : SecurityProfile.ALLOWED_SSH_CIPHERS;   // ← static constant fallback
      ...
  }
```

**There is no code path that does `securityProfileRepository.findById(si.getSecurityProfileId())`.**
The `SecurityProfile` class is referenced only for its *static constants*
(`SecurityProfile.ALLOWED_SSH_CIPHERS`), never as an entity. The runtime
literally cannot use the profile an admin linked via the UI even if the
link were stored.

```
ftp-service/src/main/java/com/filetransfer/ftp/server/FtpsConfig.java:170

  public SslConfiguration buildSslConfigFor(ServerInstance si) {
      String alias = si.getFtpTlsCertAlias();   // ← free-text
      ...
      keystoreManagerClient.getTlsCertificate(alias);
  }
```

No cipher or TLS-version control. No lookup of `si.getSecurityProfileId()`.

```
ftp-web-service/src/main/java                    # zero SecurityProfile hits
as2-service/src/main/java                        # zero SecurityProfile hits
```

### 2f. Runtime — outbound: zero wiring

```
external-forwarder-service/src/main/java/com/filetransfer/forwarder/service/SftpForwarderService.java:45

  ClientSession sshSession = client.connect(...)     // library defaults,
      .verify(connectTimeout)                         // no cipher/MAC/KEX
      .getSession();                                  // configured
  sshSession.addPasswordIdentity(...);
  sshSession.auth().verify(15, TimeUnit.SECONDS);
```

```
external-forwarder-service/src/main/java/com/filetransfer/forwarder/service/FtpsForwarderService.java:35

  FTPSClient ftps = new FTPSClient("TLS", false);    // no .setEnabledCipherSuites
                                                      // no .setEnabledProtocols
                                                      // no .setKeyManager
```

```
external-forwarder-service/.../As2ForwarderService.java    # no cipher/MAC config
```

Every outbound client uses library defaults. **The admin has no control
over what ciphers the platform presents on outbound handshakes.** A
partner's auditor asks "what TLS version does your platform use to
deliver to us?" — the honest answer is "whatever Apache Commons Net
defaults to on whatever JDK we're running."

### 2g. DTO — the one-line-fix at the centre of it all

```
onboarding-api/src/main/java/com/filetransfer/onboarding/dto/request/CreateServerInstanceRequest.java

  @Data
  public class CreateServerInstanceRequest {
      @NotBlank String instanceId;
      @NotNull  Protocol protocol;
      @NotBlank String name;
      ...
      // 31 fields total
      // ZERO Profile-related fields
  }
```

31 fields. Zero of them are `securityProfileId` or `complianceProfileId`.
The UI submits both; Jackson drops both on deserialise; the entity
FK column stays null; the runtime never has a profile to consult even
if it wanted to.

Same for `UpdateServerInstanceRequest.java`.

---

## 3. The UI side — what the admin sees today

### 3a. Server Instances create form — dropdown points at the wrong entity

```
ui-service/src/pages/ServerInstances.jsx:1018
  const { data: securityProfiles = [] } = useQuery({
    queryKey: ['security-profiles-picker'],
    queryFn: () => configApi.get('/api/listener-security-policies').then(r => r.data),
    // ↑↑↑ reads ListenerSecurityPolicy (per-listener enforcement state)
    //   should read /api/security-profiles (reusable template entity)
  })
```

- UI reads the wrong endpoint.
- Even if it read the right endpoint, the DTO drops the value.
- Even if the DTO accepted it, the runtime ignores it.
- There's still some signal of life: `form.securityProfileId` is
  submitted, so the round-trip would light up if we fixed the three
  layers below it.

### 3b. External Destinations — no SecurityProfile surface at all

```
ui-service/src/pages/ExternalDestinations.jsx

  fields:
    name, type, host, port, username, encryptedPassword,
    proxyEnabled, proxyType, proxyHost, proxyPort
  no securityProfileId
  no tlsCertAlias
  no sshPublicKeyAlias
  no as2SigningKeyAlias
```

- No picker for inbound profile, no picker for outbound profile.
- No "Test with the saved profile" button — the `testConnection` mutation
  takes only raw host/port/user/pass and probes library-default TLS.
- Admin cannot set or audit cipher policy for any outbound delivery.

### 3c. Partner Accounts — same gap on the partner edge

```
TransferAccount entity has no securityProfileId field.
DB server_account_assignments also carries no profile link.
```

So for a partner's account (which is reused across many flows as both
inbound login credentials and outbound delivery identity), there's no
way to scope security config per partner at all.

---

## 4. What we're actually *building* vs what the customer thinks

**What the customer (and the DTOs and the UI) think we're building:**
Pick a profile from a dropdown → that profile governs the handshake for
this listener / this delivery destination / this partner. One centrally
managed security policy, reused across all surfaces.

**What the platform actually does:**
- Admin picks a profile → value silently dropped in the DTO.
- Per-listener free-text CSV fields (`allowedCiphers`, etc.) partially
  governing **inbound SFTP only**.
- Per-listener free-text TLS alias field governing **inbound FTPS only**.
- Everything else — inbound FTP_WEB, inbound AS2, **all outbound**
  — uses library defaults. No central governance. The FTPS partner
  auditor asks what TLS cipher we present; we can't answer.

**Gap value to the customer:** the customer bought a managed file
transfer platform on the promise that security is centrally
configurable. Today it isn't. They'll find out the first time they need
to produce a compliance artifact ("give me the cipher list your
platform presented to partner X last quarter") and have to answer "it's
whatever Apache Commons Net's SSLSocketFactory default cipher suite is
on JDK 25 in 2026."

---

## 5. R132 work breakdown — what dev needs to wire, where

Ordered so each step unblocks the next. Every step has a specific file
location. None of it is speculative; each one is a line item proven
missing above.

### Phase 1 — Default profile + DTO wiring (1–2 hours)

1. **Migration**: add `is_default BOOLEAN DEFAULT FALSE` column to
   `security_profiles`. Seed **one** `Standard SSH` + one `Standard TLS`
   row with `is_default = true` (guarded so only one per type).
2. **Repository**: add `Optional<SecurityProfile> findByTypeAndIsDefaultTrue(String type)`.
3. **DTOs** (`CreateServerInstanceRequest`, `UpdateServerInstanceRequest`):
   add `private UUID securityProfileId; private UUID complianceProfileId;`.
4. **Controller** persists those fields onto `ServerInstance` on
   create/update.
5. **ServerInstanceResponse** exposes `securityProfileId` + denormalised
   `securityProfileName` so the UI can show "High Security SSH" next to
   a listener.
6. **UI** `ServerInstances.jsx:1018` change the queryFn URL from
   `/api/listener-security-policies` → `/api/security-profiles`. Remove
   the free-text `allowedCiphers` / `allowedMacs` / `allowedKex`
   inputs; they belong inside the profile now.
7. **Pin**: POST `/api/servers` with a chosen profile → GET the server
   → `securityProfileId` round-trips.

### Phase 2 — Runtime reads the profile (inbound) (3–4 hours)

8. **sftp-service** `SftpSshServerFactory.applyAlgorithms`:
   - Receive `SecurityProfile` (resolved from
     `si.getSecurityProfileId()` or default-by-type if null).
   - Replace the free-text CSV parsing with
     `profile.getSshCiphers()` / `.getSshMacs()` / `.getKexAlgorithms()`.
   - Delete `ServerInstance.allowedCiphers/.allowedMacs/.allowedKex`
     columns after a deprecation window (flag them in the UI now, drop
     the columns in R134).
9. **ftp-service** `FtpsConfig.buildSslConfigFor`:
   - Receive `SecurityProfile`.
   - Apply `profile.getTlsMinVersion()` to `SslConfigurationFactory.setSslProtocol`.
   - Apply `profile.getTlsCiphers()` to the SSLSocket's enabled cipher
     suites (`SslConfigurationFactory` doesn't expose this directly;
     may need a custom `SslEngineSupplier`).
   - Move `ftpTlsCertAlias` onto the profile so multiple listeners can
     share a cert; remove the per-listener text field.
10. **ftp-web-service** — wire the same `SecurityProfile` lookup to the
    embedded Tomcat's SSL connector (Spring Boot `Ssl` properties
    resolved per-listener via a custom `TomcatConnectorCustomizer`).
11. **as2-service** — wire the same `SecurityProfile` to the AS2
    MIC/signing/encryption algorithms.

### Phase 3 — Outbound clients read the profile (3–4 hours)

12. **Migration**: add `security_profile_id UUID` FK to
    `external_destinations` and `transfer_accounts`.
13. **Entity + DTO** — `ExternalDestination`, `TransferAccount`,
    `CreateExternalDestinationRequest`, `CreateTransferAccountRequest`
    all get the new field.
14. **UI** `ExternalDestinations.jsx` + `Accounts.jsx` / partner-account
    edit form — add the SecurityProfile picker. Default to the platform
    default profile for the account's protocol.
15. **external-forwarder-service**:
    - `SftpForwarderService` — load `SecurityProfile` from dest/acct,
      build a `org.apache.sshd.client.SshClient` with
      `.setCipherFactories(...)` / `.setMacFactories(...)` /
      `.setKeyExchangeFactories(...)` from the profile.
    - `FtpsForwarderService` — subclass `FTPSClient` so
      `prepareSocket()` sets `setEnabledCipherSuites(profile.tlsCiphers)`
      and `setEnabledProtocols(profile.tlsMinVersion-compatible-set)`.
      Load client cert via Keystore Manager using the profile's alias.
    - `As2ForwarderService` — wire profile fields to the AS2 library's
      `getSigningAlgorithm`, `getEncryptionAlgorithm`, `getMDNOptions`.
16. **Pin**: probe a saved external destination via the R132 connectivity-
    test endpoint (coming from the R132 UX gap report) and assert the
    negotiated cipher matches what the profile advertises.

### Phase 4 — Connectivity test uses the profile (1 hour)

17. Extend the R132 `/api/external-destinations/{id}/probe` (still to
    be built — GAP 6 in the UX report) to load the destination's
    `SecurityProfile` and use those settings in the probe
    `ClientSession` / `FTPSClient`. Return the negotiated cipher /
    TLS version / cert fingerprint to the UI so the admin sees
    *exactly* what the profile produced on the wire.

### Phase 5 — Enforcement + audit (2 hours)

18. **Activity record enrichment**: `file_transfer_records` +
    `flow_executions` already track per-transfer metadata. Add
    `negotiated_cipher`, `negotiated_tls_version`, `security_profile_id`
    columns so every transfer can be audited *at the cipher level*.
19. **Compliance hook**: `ComplianceProfile.requireEncryption` /
    `requireTls` should reject transfers whose
    `negotiated_tls_version < compliance.minTlsVersion`. Currently
    unenforced (GAP 11 in UX report).

**Total: ~12 hours dev work to bridge UI → DTO → entity → runtime for
all 8 surfaces, plus ~3 hours for audit + enforcement.**

---

## 6. What good looks like — the acceptance criterion

After R132 lands, this sequence should work end-to-end:

1. Admin creates a `SecurityProfile` template:
   `name=Partner-Finance-Strict, type=TLS, tlsMinVersion=1.3,
    tlsCiphers=[TLS_AES_256_GCM_SHA384, ...], clientAuthRequired=true`.
2. Admin creates a listener: `protocol=FTPS, port=990,
    securityProfileId=<Partner-Finance-Strict>, tlsCertAlias=<from keystore picker>`.
3. Admin creates an external destination pointing at a partner:
    `name=PartnerX-Inbound-SFTP, type=SFTP, host=..., port=22,
     securityProfileId=<Standard-SSH-default>`.
4. Admin clicks "Test connection" on the destination row → probe runs
    using Standard-SSH ciphers → returns
    `{success:true, negotiatedCipher:"aes256-gcm@openssh.com",
     certFingerprint:"...", roundTripMs:42}`.
5. A flow runs, delivers to PartnerX.
   `file_transfer_records.negotiated_cipher = "aes256-gcm@openssh.com"`
   recorded against the transfer.
6. Compliance report for Q3: "Every transfer to PartnerX used
   aes256-gcm@openssh.com. Proof attached."

None of steps 1-6 are possible today. Every one becomes possible after
the wiring above lands.

---

## 7. Appendix — what this is NOT about

- **Not** about `ListenerSecurityPolicy` — that entity is for per-listener
  enforcement state (rate limits, IP allowlist, tier). It should remain,
  with its own `securityProfileId` FK so a policy can say "this listener
  runs the Partner-Finance-Strict profile AND these rate limits."
- **Not** about refactoring the `SecurityProfile` static constants — those
  are useful fallbacks for the default profile. Keep them.
- **Not** about the keystore — Keystore Manager already ships PGP / AES /
  TLS cert material correctly. This is about *which* cert to use *when*.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
