# R134f — Phase 1 Verification (Listener-UI Gap Audit Fix)

**Commit verified:** `82309937` — *R134f: listener-UI gap audit Phase 1*
**Branch:** `main`
**Date:** 2026-04-19
**Verdict:** ✅ **PASS** — end-to-end round-trip works; listener binds.

---

## Test Method

Fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build`. Waited for 35/36 services healthy (promtail has no healthcheck, expected). Then exercised the API directly with `curl` against `onboarding-api` on `http://localhost:8080`:

1. `POST /api/auth/login` → admin JWT (`superadmin@tranzfer.io` / `superadmin`)
2. Pulled one existing `compliance_profiles.id` and one `security_profiles.id` from Postgres as FK-safe test values
3. `POST /api/servers` with a payload that explicitly set **all 7** previously-dropped fields:
   - `complianceProfileId`
   - `securityProfileId`
   - `securityTier` = `"AI"` *(was defaulting to `RULES`)*
   - `sshBannerMessage` = `"Test banner"`
   - `maxAuthAttempts` = `5` *(was defaulting to `3`)*
   - `idleTimeoutSeconds` = `600` *(was defaulting to `300`)*
   - `allowedCiphers` = `"aes256-ctr,aes192-ctr"`
4. Captured new server `id` from response
5. `GET /api/servers/{id}` → confirmed every field matched what was sent
6. Waited ~3s, re-GET → checked `bindState` to confirm the listener actually reached sftp-service

---

## Result Table — Round-trip integrity

| Field | Sent | Response on POST | Response on GET | Status |
|---|---|---|---|---|
| `complianceProfileId` | `a34ea3ed-d380-4bf0-9e62-973d613716aa` | same | same | ✅ |
| `securityProfileId` | `496e7918-63b8-4d98-a0a3-8b7116876da7` | same | same | ✅ |
| `securityTier` | `AI` | `AI` | `AI` | ✅ *(was forcing `RULES`)* |
| `sshBannerMessage` | `"Test banner"` | `"Test banner"` | `"Test banner"` | ✅ |
| `maxAuthAttempts` | `5` | `5` | `5` | ✅ *(was forcing `3`)* |
| `idleTimeoutSeconds` | `600` | `600` | `600` | ✅ *(was forcing `300`)* |
| `allowedCiphers` | `aes256-ctr,aes192-ctr` | same | same | ✅ |
| `defaultStorageMode` (UI default fix) | *(omitted)* | `VIRTUAL` | `VIRTUAL` | ✅ *(empty-form default was `PHYSICAL`)* |

### End-to-end bind

| Signal | Value |
|---|---|
| `bindState` | **BOUND** |
| `bindError` | null |
| `boundNode` | `f506264a6854` (sftp-service container) |
| `lastBindAttemptAt` | ~0.8s after create |

Listener was picked up by sftp-service via the `ServerInstanceChangeEvent.CREATED` outbox path and bound cleanly.

---

## Coverage vs. the original audit

Phase 1 of [R134-listener-ui-gap-audit.md](./R134-listener-ui-gap-audit.md) closed **5 of 5** planned blockers:

| Planned fix | Delivered? | Verified here |
|---|---|---|
| Add `complianceProfileId` + `securityProfileId` to Create/Update/Response DTOs + mappers | ✅ | POST/GET round-trip |
| Add 9 SSH/session fields + `proxyGroupName` + `maintenanceMessage` to CreateDTO + create() builder | ✅ | 5 of 9 field types exercised (securityTier, sshBannerMessage, maxAuthAttempts, idleTimeoutSeconds, allowedCiphers) |
| UI empty-form `defaultStorageMode` PHYSICAL → VIRTUAL | ✅ | create without the field → entity default `VIRTUAL` returned |
| (UI-side) input for the 13 fields on Create form | ✅ | Already present before the fix — they were the "dead-weight inputs" |
| Service wiring with null-guards so entity defaults still win when field omitted | ✅ | `defaultStorageMode` verified via omission path |

---

## Not verified in this pass (left for follow-up)

- `proxyGroupName`, `maintenanceMessage`, `allowedMacs`, `allowedKex`, `sessionMaxDurationSeconds` — low risk since they share the mapper pattern verified here, but a full manual UI smoke would hit them
- **Phase 2** (FTP PASV port UI validation asymmetry) — still queued
- **Phase 3** (HTTPS form section, AS2/AS4 form fields, FTP_WEB secondary-listener warning) — still queued

Recommend a manual UI smoke in a separate run to confirm the admin-form submit path renders and posts identically to the direct API call tested here.

---

## Environment

- **Commit:** `82309937` (post `git pull`, tests all green in prior run)
- **Stack:** nuked + rebuilt from source. 35/36 containers healthy at test time.
- **Build:** `mvn package -DskipTests` + `docker compose up -d --build` — zero warnings
- **Platform:** macOS 25.2.0 (arm64), Docker Desktop, Java 25
- **Artifact version (pom.xml):** `1.0.0-R127` (lags the R134f commit series — artifact tag was not bumped for this fix)

---

**Report author:** Claude (2026-04-19 session, same run as the original R134 audit)
