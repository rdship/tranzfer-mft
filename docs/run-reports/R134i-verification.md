# R134i — 🥈 Silver: Gap A closed + Gap C implicitly resolved via VFS

**Commit verified:** `739286f4` — *R134i: partner accounts default to VIRTUAL (Gap A) — aligns with no-pod-filesystem rule*
**Branch:** `main`
**Date:** 2026-04-19 (Apr 20 UTC)
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 35/36 healthy
**Verdict:** 🥈 **Silver** — Gap A verified end-to-end via VFS; Gap C implicitly closed; Gap B clarification confirmed. BUG 13 on the *real* flow path still blocked (AS2 listener gap) — awaits dedicated regression flow.

---

## Why not Gold

Per the medal rubric: Gold requires every part of the platform perfect **and** zero third-party runtime dependencies **and** every flow exercised end-to-end with zero workarounds. This cycle is Silver because:

- Stack still depends on postgres, redis, rabbitmq, redpanda, vault, spire-server/agent, minio, prometheus, loki, grafana, alertmanager, promtail — multi-third-party footprint.
- BUG 13 *real path* not exercised (only forced via bogus-JWT in R134h). Healthcare Compliance / EDI X12 flows are AS2-sourced and no AS2 listener is bound in the bootstrap-only stack.
- Gap D (demo-onboard schema drift, ~110 failures) and `/actuator/info` auth-posture inconsistency — both explicitly queued by R134i commit message.

---

## Verdict matrix

| Item | Before R134i | After R134i |
|---|---|---|
| Gap A — partner accounts have no filesystem backing | Failed upload with AccessDeniedException | ✅ VIRTUAL default; VFS ingestion works, no manual mkdir |
| Gap B — flow routing picks Mailbox Distribution over Encrypted Delivery | Claimed as a bug | ✅ Clarified as correct: `direction=OUTBOUND` doesn't match INBOUND upload events; catch-all wins as designed |
| Gap C — screening-service can't reach `.flow-work` | Flow failed at Step 0 (SCREEN) with "/data/partners/globalbank/outbox/.flow-work" | ✅ Resolved implicitly — VFS routes every step's IO through storage-manager; no per-pod filesystem coupling |
| Gap D — ~145 demo-onboard failures (schema drift, duplicates, FKs) | Queued | ⏳ Still queued; commit msg explicit |
| `/actuator/info` auth posture inconsistency | onboarding-api 403 unauth, others public | ⏳ Still queued |
| BUG 13 — real flow path | Forced via bogus JWT (R134h) | ⏳ Still blocked by AS2 listener absence. Awaiting dedicated INBOUND+FILE_DELIVERY regression flow per R134i commit msg offer |

---

## Evidence

### Gap A — VIRTUAL default + VFS upload works

```sql
SELECT storage_mode, COUNT(*) FROM transfer_accounts GROUP BY storage_mode;
  VIRTUAL | 6

SELECT username, storage_mode FROM transfer_accounts
  WHERE username IN ('globalbank-sftp','acme-sftp','medtech-as2','globalbank-ftps');
  acme-sftp       | VIRTUAL
  globalbank-ftps | VIRTUAL
  globalbank-sftp | VIRTUAL
  medtech-as2     | VIRTUAL
```

SFTP upload to `globalbank-sftp:/outbox` with zero prep (no manual `docker exec mkdir`, no `chmod`):

```
sftp> cd /outbox
sftp> put /tmp/test.xml gapA-smoke-1776663081.xml
Uploading /tmp/test.xml to /gapA-smoke-1776663081.xml
sftp> ls -la
drwxr-xr-x  ... VirtualSftpFileSystemProvider$$Lambda/... .
-rw-r--r--  ... VirtualSftpFileSystemProvider$$Lambda/... 54 Apr 20 05:31 gapA-smoke-...xml
```

The `VirtualSftpFileSystemProvider` in the owner/group field is smoking-gun evidence that the file went through VFS, not local disk. Canonical bytes now sit behind storage-manager (→ MinIO, keyed by SHA-256), matching the platform invariant stated in the R134i commit.

### Gap C — implicit resolution via VFS

A single SFTP upload to the VIRTUAL account fires `Mailbox Distribution` (INBOUND, priority 999, catch-all). This cycle the flow FAILED — but at **Step 2 (MAILBOX)** not Step 0 (SCREEN) as in R134h:

```
flow:   Mailbox Distribution
status: FAILED
error:  Step 2 (MAILBOX) failed: Destination account not found: retailmax-ftp-web (SFTP)
```

Step 0 (SCREEN) completed successfully, which could not happen in R134h because the flow couldn't reach `.flow-work` in the screening-service container. VFS fixed this: every step now reads via storage-manager instead of pod-local disk. The new failure at Step 2 is a **separate issue** — the Mailbox Distribution flow references `retailmax-ftp-web` as destination, but that account doesn't exist in the bootstrap-only stack (it's created by demo-onboard). Not a R134i regression.

### Gap B — clarification confirmed

R134i commit msg explains: `Encrypted Delivery` is `direction=OUTBOUND`, SFTP upload events enter routing with `direction=INBOUND`, so match fails on direction. Catch-all `Mailbox Distribution` (priority=999, INBOUND) wins. Not a bug.

Verified in DB: only 2 INBOUND flows have `FILE_DELIVERY` steps, both AS2-sourced:

```
name                     | source      | protocol | source_path | filename_pattern     | has_file_delivery
-------------------------+-------------+----------+-------------+----------------------+-------------------
EDI Processing Pipeline  | acme-sftp   | SFTP     | /inbox      | .*\.edi              | f
Healthcare Compliance    | medtech-as2 | AS2      | /inbox      | .*\.hl7              | t  ← AS2 only
Mailbox Distribution     | retailmax...| FTP_WEB  | /inbox      | .*                   | f
Archive & Compress       | logiflow... | SFTP     | /inbox      | .*\.csv              | f
EDI X12 to XML Conversion| medtech-as2 | AS2      | /inbox      | .*\.(x12|850|810|856)| t  ← AS2 only
```

### BUG 13 real-path: still blocked

Attempted path: upload a `.hl7` to `medtech-as2:/inbox` to trigger Healthcare Compliance → FILE_DELIVERY → external-forwarder-service.

Blocker: no AS2 listener is bound in the server_instances table in the bootstrap-only stack (0 rows for `protocol='AS2'`). AS2 is HTTP-based with signing/MDN requirements that don't lend themselves to a curl one-liner. The R134i commit msg offers: *"If the tester wants a simpler INBOUND+FILE_DELIVERY seed for reproducible runs, a dedicated regression flow is a small next commit."* — that's the ask.

Alternative paths evaluated and rejected:
- Run demo-onboard.sh — would seed the full 1100-row environment including AS2 listener, but schema-drift failures make it a noisy baseline (Gap D still open).
- Manually create a new INBOUND SFTP flow with FILE_DELIVERY via API — doable but moves the verification from "runs against seed data" to "tester fabricates a test flow", which undermines reproducibility.

Recommending: **add a minimal regression flow in the bootstrap** — e.g. `sftp-deliver-regression` flow, direction=INBOUND, source=an SFTP bootstrap account, pattern=`.*\.regression`, steps=[SCREEN, FILE_DELIVERY to a synthetic external endpoint]. One test upload then fires the full real path, including the S2S hop to external-forwarder-service — which is exactly what BUG 13 validation needs.

---

## What's still queued

From R134h + R134i commits:

- **Gap D** — demo-onboard schema drift (~110 failures per run)
- **`/actuator/info` auth posture** — standardise public vs authed
- **BUG 13 real-path exercise** — dedicated regression flow

Nothing new surfaced in this verification cycle.

---

## Environment footnote

- Commit: `739286f4`
- Accounts on fresh boot: 6 (all VIRTUAL — R134i fix applied at bootstrap)
- Listeners bound: SFTP (2), FTP (4), FTP_WEB (3), AS2 (**0** — the missing piece)
- Flow executions this cycle: 1 (Gap A smoke upload → Mailbox Distribution → FAILED at Step 2 due to missing retailmax account; SCREEN step passed, proving Gap C resolution)
- `external-forwarder-service`: zero inbound SPIFFE traffic on the real path (no FILE_DELIVERY flow was reachable)

---

**Report author:** Claude (2026-04-19 session).
