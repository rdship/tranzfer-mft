# Configuration Export / Import — Plan

**Status:** Deferred feature (not started). Captured in planning so the idea isn't lost.

## Problem

Roshan needs to promote platform configuration from one environment to another:

```
 test  ─→  stage  ─→  prod
```

Today this is impossible without manually re-running `demo-onboard.sh` (which only creates *seed* data) or doing ad-hoc SQL dumps of specific tables. There's no single, user-facing way to say "export my current test system's configuration and import it into stage."

This is distinct from CSV export (which just dumps one table's rows) and distinct from `pg_dump` (which grabs *everything* including transaction data, audit logs, and per-environment secrets you absolutely don't want to promote).

## What a configuration bundle is

A **configuration bundle** is a JSON file containing the platform's "desired state" — the things an operator configures, not the things the platform produces. Roughly:

### Included (configuration)
- Partners + Partner Detail (type, phase, protocols, SLA tier, contact info)
- Transfer Accounts (username, protocol, permissions, QoS, home dir) — **without passwords**
- Folder Mappings (source → destination routing)
- Folder Templates
- Processing Flows + Flow Rules
- External Destinations
- AS2/AS4 Partnerships
- Server Instances (logical config, not running-state)
- Security Profiles (TLS / SSH algorithm sets)
- Compliance Profiles (PCI/HIPAA/GDPR rulesets)
- DLP Policies (Screening)
- Keystore key **metadata** — aliases, types, expiry — **not the key material**
- Notification Templates + Rules
- Connectors (webhook configs — **URLs ok, secrets redacted to placeholders**)
- SLA Agreements
- Scheduled Tasks
- Platform Settings (only non-secret ones)
- Listener Security Policies
- EDI Partner Profiles
- EDI Maps (custom partner maps only — standard maps are code)
- Tenants (metadata only, not users)

### Excluded (runtime / environment-specific)
- `file_transfer_records` — actual transfer history
- `flow_executions` — execution history
- `fabric_checkpoints` — fabric state
- `audit_logs` — compliance event log
- `sentinel_findings` — generated findings
- Users and their password hashes — auth state should never promote
- License entries — per-environment
- Anything under the JWT secret, DB creds, etc.
- Key material (private keys, certs with private components)
- Connector secrets (webhook tokens, API keys)

### Partially included (metadata only)
- Keystore entries export **alias + type + algorithm + fingerprint + expiry**, but the private bytes must be re-imported separately via Keystore Manager's existing key-upload flow.
- Connector secrets export as placeholder strings (`{{SECRET_PLACEHOLDER}}`). On import, the target environment's operator fills in real values before activation.

## Architecture

### Export flow

```
┌──────────────────────────┐
│  Admin UI /config-export │
│  ┌────────────────────┐  │
│  │  Select scope      │  │
│  │  [x] Partners (48) │  │
│  │  [x] Flows (200)   │  │
│  │  [x] Accounts      │  │      POST /api/v1/config-export
│  │  [ ] DLP Policies  │  │ ───────────────────────────────▶ onboarding-api
│  │  ...               │  │                                        │
│  │  [  Export Bundle ]│  │                                        ▼
│  └────────────────────┘  │                                  ConfigExportService
└──────────────────────────┘                                        │
                                                                    ▼
                                                            ┌───────────────┐
                                                            │ Walk each     │
                                                            │ repository    │
                                                            │ in dep order  │
                                                            └───────────────┘
                                                                    │
                                                                    ▼
                                                      SHA-256 checksummed JSON
                                                      bundle returned as download
```

Bundle format:

```json
{
  "schemaVersion": "1.0.0",
  "exportedAt": "2026-04-11T10:00:00Z",
  "sourceEnvironment": "test",
  "sourcePlatformVersion": "1.0.0-SNAPSHOT",
  "sourceCluster": "test-a",
  "scope": ["partners", "accounts", "flows", "folder-mappings", "..."],
  "dependencies": {
    "standardMaps": ["x12_850_v005010", "edifact_orders_d96a", "..."]
  },
  "entities": {
    "partners": [ { "id": "abc...", "companyName": "ACME", "...": "..." } ],
    "transferAccounts": [ ... ],
    "fileFlows": [ ... ],
    "folderMappings": [ ... ],
    "securityProfiles": [ ... ],
    "...": "..."
  },
  "redactions": [
    {
      "entityType": "webhookConnector",
      "entityId": "abc...",
      "field": "secret",
      "reason": "Secret redacted on export — supply on import"
    }
  ],
  "checksum": "sha256:abc123..."
}
```

- **IDs are preserved** (UUIDs stay the same across environments). This makes diffing / upgrading a config bundle natural. Conflicts on import (same ID exists but different content) surface for operator resolution.
- **Redactions list** is explicit so the import wizard can prompt for missing values instead of silently accepting placeholders.
- **Checksum** over a canonical (sorted-keys) serialization of `entities` + `redactions`. Import verifies this before doing any writes.

### Import flow

```
┌────────────────────────────────┐
│  Admin UI /config-import       │
│  ┌──────────────────────────┐  │
│  │  Drop bundle here        │  │
│  │  bundle-20260411.json    │  │
│  │                          │  │
│  │  Pre-flight:             │  │
│  │   48 partners (28 new,   │  │
│  │      20 update, 0 skip)  │  │   POST /api/v1/config-import/preview
│  │   200 flows (180 new,    │  │  ────────────────────────────────▶
│  │      15 update, 5 skip)  │  │
│  │  ...                     │  │
│  │                          │  │
│  │  Fill redactions:        │  │
│  │   webhook acme-slack     │  │
│  │   secret: [__________]   │  │
│  │                          │  │
│  │  Mode: [Dry Run ▼]       │  │
│  │        Merge             │  │
│  │        Replace           │  │
│  │                          │  │
│  │  [ Start Import ]        │  │   POST /api/v1/config-import/apply
│  └──────────────────────────┘  │  ────────────────────────────────▶
└────────────────────────────────┘
```

Three import modes:

| Mode | Behavior |
|---|---|
| **Dry Run** | Never writes. Returns a full `PreviewReport` showing per-entity `new` / `update` / `conflict` / `skip` counts and any validation errors. Operator reviews before deciding. |
| **Merge** | New entities created, existing entities updated. Entities in the target that are NOT in the bundle are left alone. Use case: incremental config sync. |
| **Replace** | New entities created, existing updated, entities in target but not in bundle are **deleted**. Use case: "promote full test → stage, replace everything." **Requires an extra confirmation** because it's destructive. |

Every import run is:
- Transactional at the service layer (Spring `@Transactional` at the import orchestrator method — rollback on any failure)
- Audit-logged: `AuditLog` row per entity touched, with `requestId` grouping all rows under one import run
- **Idempotent**: re-running the same bundle in Merge mode produces zero writes on the second run

## Phases (estimated effort)

| Phase | What | LOC est. |
|---|---|---|
| **1** | `ConfigBundle` DTO + `ConfigBundleBuilder` service (onboarding-api) — walks repos in dependency order, redacts secrets, computes checksum | 600 |
| **2** | `POST /api/v1/config-export` endpoint — takes scope list, streams JSON bundle as download | 150 |
| **3** | Admin UI `/config-export` page — checkbox tree of entities with counts, Export Bundle button, progress bar for large exports | 300 |
| **4** | `ConfigBundleValidator` — checksum verification, schema version check, dependency resolution, redaction inventory | 400 |
| **5** | `ConfigImportService.preview(bundle, mode)` → returns `PreviewReport` with per-entity `new/update/conflict/skip` without touching DB | 500 |
| **6** | `ConfigImportService.apply(bundle, mode, redactions)` → transactional writer with audit log + rollback on failure | 700 |
| **7** | `POST /api/v1/config-import/preview` + `POST /api/v1/config-import/apply` endpoints (+ webhook redaction fill form) | 200 |
| **8** | Admin UI `/config-import` page — file drop zone, preview table, redaction form, mode selector, apply button, progress bar | 500 |
| **9** | Diff viewer — shows per-entity field-level changes before apply (optional polish) | 400 |
| **10** | CLI command `mft config export` / `mft config import --bundle=file.json --mode=merge --dry-run` (reuses same service layer) | 300 |
| **11** | End-to-end integration test — boot two Postgres schemas, export from one, import into the other, assert row-level equality | 400 |
| | **Total** | **~4,450 lines** |

## Open design questions (resolve before starting)

1. **ID collision policy**: if two environments have independently-created partners with *different* UUIDs for the "same" semantic partner (e.g. "ACME Corp" has ID abc in test, xyz in prod), does Merge match by UUID, by `slug`, by `companyName`, or ask the operator? Recommend: match by UUID, with a pre-flight warning if the target has a different partner with the same `slug` — operator can choose to merge by slug instead.

2. **Cross-service references**: a flow references a security profile by UUID. If the security profile isn't in the bundle (operator excluded it from scope), the import must either bail out or auto-include the dependency. Recommend: **automatic dependency closure** — scope expansion happens server-side during export, not client-side.

3. **Secret promotion strategy**: option A = redact everything, operator fills in. Option B = encrypt secrets with a bundle-level passphrase (AES-256-GCM) that the operator types on both ends. Recommend **A for v1** (simpler, auditable). B could be a v2 enhancement.

4. **Keystore material**: private keys never go in the bundle. But a flow referencing key alias `sftp-prod-key` will fail import on the target if that alias doesn't exist there. Recommend: pre-flight check lists missing keys and fails the import-preview until all are resolved. Operator uploads keys via the existing Keystore Manager UI first.

5. **Tenants / multi-tenancy**: does the bundle carry tenant ID, or is it always relative to the importing operator's current tenant? Recommend: **relative**. Exporting a bundle from tenant A and importing into tenant B just re-parents everything. Explicit tenant copying is out of scope for v1.

6. **Schema version upgrades**: what if a bundle is from `1.0.0` and the target is `1.1.0` with added fields? Recommend: forward-compat only. The bundle validator accepts older schemas and fills new fields with defaults. Backward-compat (new bundle → old target) is not supported.

7. **Bundle storage**: should the platform store exported bundles, or is it pure download? Recommend: **store optional**. Operator can opt-in to save the bundle to MinIO under `/config-bundles/<timestamp>.json` for audit trail, but default is pure download.

## What makes this different from `pg_dump`

| Capability | `pg_dump` | Config bundle |
|---|---|---|
| Grabs all tables? | Yes | No — only configuration tables |
| Includes transfer history? | Yes | No |
| Includes audit log? | Yes | No |
| Includes secrets? | Yes (raw) | No (redacted) |
| Schema-aware? | Only via schema version | Yes — validates against platform version |
| Merge-mode-aware? | No (it's replace-all) | Yes (new/update/conflict/skip) |
| Dry-run / preview? | No | Yes |
| Auditable? | No (no app-level tracking) | Yes (audit log row per entity) |
| Cross-environment safe? | No | Yes |
| UI? | CLI only | Admin UI + CLI |
| Operator-facing? | No (DBA tool) | Yes (operator tool) |

## Success criteria for v1

- Operator exports a bundle from test, downloads as JSON
- Operator imports the bundle into stage in Dry Run mode, sees 48 partners / 200 flows / 225 accounts preview
- Operator fills 2 webhook secrets, clicks Apply in Merge mode
- Stage now has all 48 partners / 200 flows / 225 accounts
- Re-running the same bundle in Merge mode produces **zero writes** (idempotent)
- Audit log in stage shows 473 CONFIG_IMPORT rows grouped under one `requestId`

## Next step

This plan needs Roshan's review before breaking into implementation phases. Key decisions: import modes, ID collision policy, secret promotion strategy, tenant scoping. Once those are locked, Phase 1 can start.

**Parked.** No code. No timeline. Picking this up when Roshan says go.
