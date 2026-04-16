# N48 Diagnostic Results — R64 Build

**Date:** 2026-04-16 07:16 UTC  
**Build:** PLATFORM_VERSION=1.0.0-R64, shared-platform-1.0.0-R61.jar  
**Platform:** 34/35 healthy  

---

## N48 IS FIXED

**FlowExecution created for the first time.** The end-to-end pipeline now works:

```
Upload → SFTP Event → RabbitMQ → FileUploadEventConsumer → Flow Match → FlowExecution CREATED
```

## Results

| Metric | Value |
|--------|-------|
| transfer_records | **1** |
| flow_executions | **1** (was always 0 in R61-R63) |
| FileUploadEventConsumer | **LOADED in config-service** |
| Flow matched | EDI Processing Pipeline (priority=10) |
| Execution status | FAILED at Step 0 (SCREEN) — file path issue |

## Step-by-Step Trace

```
[TRZPGYKAL5YM] FileUploadedEvent published to RabbitMQ              (sftp-service)
[TRZPGYKAL5YM] Processing file upload event: user=globalbank-sftp    (config-service — CONSUMER WORKS!)
[TRZPGYKAL5YM] Flow matching: registry.size=6 registry.initialized=true
[TRZPGYKAL5YM] Matched flow 'EDI Processing Pipeline' (priority=10)
[TRZPGYKAL5YM] FlowExecution created → Step 0 (SCREEN) → FAILED: /data
```

## What Changed in R64

1. **`FileUploadEventConsumer` now loads in config-service** — the `@ConditionalOnProperty` or routing-env fix worked
2. **VFS wired into SFTP subsystem** — `SftpFileSystemFactory`, `VirtualSftpFileSystem`, `VirtualSftpFileSystemProvider` added
3. **`PLATFORM_DEFAULT_STORAGE_MODE: VIRTUAL`** added to common-env
4. **`x-routing-env`** anchor created with `FLOW_RULES_ENABLED=true` separated from common-env

## Remaining Issue: Step 0 SCREEN Fails

```
status: FAILED
current_step: 0
error_message: Step 0 (SCREEN) failed: /data
```

The SCREEN step can't access the file. The file path `/data/partners/globalbank/test_edi_850.edi` is local to sftp-service container. The screening-service (or whichever service runs SCREEN) needs to read the file via VFS/storage-manager, not from the local filesystem path.

This is the VFS content delivery gap — the file was written to disk by the SFTP subsystem but the VFS bridge may not have persisted it to storage-manager for cross-service access.

## Diagnostic Details

### Step 1: Version
- PLATFORM_VERSION: 1.0.0-R64
- JAR: shared-platform-1.0.0-R61.jar

### Step 2: FlowRuleRegistry
All services: 0 at init → 6 after first refresh (10s)

### Step 3: FileUploadEventConsumer
- sftp-service: not loaded (correct — it publishes, doesn't consume)
- **config-service: LOADED** — logs "Processing file upload event"
- ftp-service: not loaded

### Step 5: RabbitMQ
```
file.upload.events    0 messages    32 consumers
```

### Step 5: Active Flows
6 bootstrap flows, EDI Processing Pipeline matched at priority 10.
