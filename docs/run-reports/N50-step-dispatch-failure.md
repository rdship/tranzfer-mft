# N50 — Step Not Dispatched to Kafka SCREEN Topic

**Date:** 2026-04-16 09:39 UTC  
**Build:** R64 + FlowProcessingEngine + RoutingEngine + VirtualSftpFileSystemProvider fixes  
**Track ID:** TRZNRV6SJ4M7  

---

## VFS Pipeline: Working Through Execution Creation

```
[VFS] Inline stored /test_850.edi: 321 bytes ✅
VFS write complete — triggering routing ✅
FileUploadedEvent published to RabbitMQ ✅
Transfer record created (flow_id = EDI Processing Pipeline) ✅
FlowExecution created (status=PROCESSING) ✅
Activity Monitor: 1 entry, flow=PROCESSING ✅
```

## Failure: Step Never Dispatched

```
FlowExecution status=PROCESSING, current_step=0
Config-service SCREEN consumer: "Adding newly assigned partitions: " (EMPTY)
No step processing logs anywhere
After 2 min: "Recovered: stuck in PROCESSING > 2 min, no checkpoint available"
Status → FAILED
```

## Evidence

1. **virtual_entries.track_id = empty** — VFS entry not linked to transfer
2. **flow.step.SCREEN topic**: exists with 1 partition, 1 consumer group
3. **Config-service SCREEN consumer**: subscribed but 0 partitions assigned
4. **No "dispatch" or "publish" log** for Step 0 in any service
5. **FlowProcessingEngine** creates execution but does not publish step message to Kafka

## Diagnosis

The `FlowProcessingEngine` creates the `FlowExecution` row with status=PROCESSING but never publishes the Step 0 message to the `flow.step.SCREEN` Kafka topic. The step consumer is listening but receives nothing. After 2 min the recovery job marks it FAILED.

The step dispatch likely fails silently because:
1. The step processor needs the file content (via VFS) but `virtual_entries.track_id` is empty so it can't look it up
2. OR the Kafka producer for step messages isn't configured
3. OR the FlowProcessingEngine's step dispatch method throws an exception that's swallowed

## For Dev Team

1. Add logging in `FlowProcessingEngine` before/after step dispatch
2. Link `virtual_entries.track_id` when creating the transfer record
3. Verify Kafka producer publishes to `flow.step.SCREEN` topic
