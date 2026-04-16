# N48 Diagnostic Results — R63 Build (Complete)

**Date:** 2026-04-16 06:38 UTC
**Build:** shared-platform-1.0.0-R63.jar (confirmed)
**Platform:** 34/35 healthy

---

## Step 1: R63 CONFIRMED

## Step 2: FlowRuleRegistry — 6 flows at boot (improvement over R61)

## Step 3: FileUploadEventConsumer — NOT loaded (same as R61)

## Step 4+5: Upload + Flow Matching — WORKS!

**Upload:** `globalbank-sftp` via 3rd-party SFTP client (acme-sftp was locked)

**Logs from sftp-service:**
```
[TRZ9AANUTTKK] FileUploadedEvent published to RabbitMQ (broadcast)
[TRZ9AANUTTKK] File received: account=globalbank-sftp file=test_fx.json
[TRZ9AANUTTKK] Flow matching: filename=test_fx.json protocol=SFTP direction=INBOUND registry.size=6 registry.initialized=true
[TRZ9AANUTTKK] Matched flow 'Mailbox Distribution' (priority=100, rule=5aa723d3)
[TRZ9AANUTTKK] VIRTUAL account but no VirtualEntry for path=/test_fx.json — skipping
[ai-engine] classify failed: file [/data/partners/globalbank/test_fx.json] cannot be resolved
```

## Database State
```
transfer_records: 2 (both PENDING, flow_id=Mailbox Distribution)
flow_executions: 0
```

## ROOT CAUSE OF 0 FLOW EXECUTIONS — FOUND

The RoutingEngine:
1. Detects upload ✅
2. Publishes FileUploadedEvent ✅
3. Creates transfer record with matched flow_id ✅
4. Checks VFS: `VIRTUAL account but no VirtualEntry for path=/test_fx.json — skipping` ❌
5. **SKIPS flow execution creation** because VFS entry is missing

**The VFS bridge is not intercepting SFTP file writes.** The SFTP subsystem writes files to local disk (`/data/partners/globalbank/test_fx.json`), but the account is set to `storageMode=VIRTUAL`. The RoutingEngine expects the file to be stored via storage-manager (VFS), but the VFS bridge that should intercept the write and persist to storage-manager is either:
1. Not active (VFS bridge bean not loaded)
2. Blocked by storage-manager's PatternParseException (N47)
3. Not wired to the SFTP file system provider

**Fix path:** The VFS bridge must intercept file writes at the SFTP subsystem level and store via storage-manager BEFORE the RoutingEngine checks for VirtualEntry. OR the RoutingEngine should fall back to physical file path when VFS entry is missing (graceful degradation).

## New Finding: N49 — SFTP Lockout Not Cross-Service Clearable

`acme-sftp` locked for 15 min after 5 failed attempts during boot. Admin API reset-all-lockouts runs on onboarding-api but sftp-service's LoginAttemptTracker is in-memory — different JVM. Lockout persists across sftp-service restarts and Redis FLUSHALL. Only TTL expiry works.

## Summary

| Check | Result |
|-------|--------|
| R63 build | ✅ Confirmed |
| FlowRuleRegistry | ✅ 6 flows at boot |
| FileUploadEventConsumer | ❌ Not loaded |
| File upload | ✅ via globalbank-sftp |
| Flow matching | ✅ Matched 'Mailbox Distribution' |
| Transfer record | ✅ Created with flow_id |
| VFS entry | ❌ "no VirtualEntry — skipping" |
| Flow execution | ❌ 0 (skipped due to missing VFS) |
| AI classification | ❌ File not on shared filesystem |
