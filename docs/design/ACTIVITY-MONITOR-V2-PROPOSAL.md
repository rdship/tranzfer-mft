# TranzFer MFT — Activity Monitor V2 Proposal

**Date:** 2026-04-13  
**Author:** QA & Architecture Team  
**Audience:** CTO Roshan Dubey, Development Team  
**Type:** Design Proposal  
**Status:** Open for Review  
**Priority:** High — core platform feature with critical data-quality gaps  

---

## 1. Current State Assessment

### What We Have (v1)
The Activity Monitor is a **single-endpoint paginated search** (`GET /api/activity-monitor`) with 5 optional filters, 9 sortable columns, and fabric enrichment. The UI renders a feature-rich table with inline expansion, saved views, bulk restart, and a fabric KPI strip.

### What Works Well
- **API response time is excellent:** 10–18ms for all page sizes (10 to 100 records)
- **Fabric enrichment is elegant:** Best-effort enrichment via `FabricCheckpointRepository` never breaks the API
- **Dynamic JpaSpecification avoids Hibernate 6 null-param bugs** (well-engineered)
- **Batch-fetch pattern for FlowExecution** eliminates N+1 on flow data
- **UI saved views, column settings, sticky filters** are professional touches
- **Journey page** is comprehensive (pipeline visualization, event journal, data lineage)

### Critical Gaps Found During Testing

| # | Gap | Impact | Evidence |
|---|-----|--------|----------|
| **G1** | **70% of transfers show MISMATCH integrity, 0% VERIFIED** | Trust crisis — users see red flags everywhere | `Integrity: {'PENDING': 30, 'MISMATCH': 70}` — source/dest checksums always null, integrity computed wrong |
| **G2** | **Partner names always null** | Can't identify who sent/received files | `sourcePartnerName: 100% null`, `destPartnerName: 100% null` across all 157 records |
| **G3** | **Destination info always missing** | Half the story untold — only see source, never destination | `destUsername: 100% null`, `destProtocol: 100% null` |
| **G4** | **No date range filter** | Can't search "show me last week's transfers" | `?startDate=...&endDate=...` ignored (returns all 157) |
| **G5** | **No file size range filter** | Can't find "all files over 10MB" | `?minSize=...&maxSize=...` ignored |
| **G6** | **No partner-based filter** | Can't answer "show me Acme Corp's transfers" | `?partnerName=Acme` ignored |
| **G7** | **No error keyword search** | Can't find "connection refused" errors across all transfers | Not even a parameter |
| **G8** | **CSV export returns 500** | Compliance auditors can't export data | `/api/activity-monitor/export` → 500 |
| **G9** | **File download returns 500** | Activity monitor download button broken | `/api/v1/storage/retrieve/{trackId}` → 500 |
| **G10** | **41 failed flow executions sitting with no scheduled retry** | Dead transfers nobody knows about | `live-stats: {"failed": 41, "processing": 0}` |
| **G11** | **No WebSocket/SSE for real-time updates** | Users must manually refresh or wait 30s poll | Polling only architecture |
| **G12** | **FabricCheckpoint has ZERO database indexes** | Every stuck-detection query does full table scan | Entity has no `@Index` annotations |
| **G13** | **Partner map loaded from DB on every API call** | Unnecessary load — should be cached | `partnerRepo.findAll()` on every page request |
| **G14** | **No audit trail of who searched what** | SOX/PCI compliance gap | No logging of search parameters |

### Data Quality Summary (157 transfers in system)

| Field | % Populated | Assessment |
|-------|------------|------------|
| trackId | 100% | Good |
| filename | 100% | Good |
| status | 100% | Good |
| sourceUsername | 100% | Good |
| sourceProtocol | 100% | Good |
| flowName | 100% | Good |
| flowStatus | 100% | Good |
| uploadedAt | 100% | Good |
| sourcePartnerName | **0%** | **BROKEN** — Partner linking not working |
| destUsername | **0%** | **BROKEN** — Destination not populated |
| destProtocol | **0%** | **BROKEN** — Destination not populated |
| destPartnerName | **0%** | **BROKEN** — Destination not populated |
| externalDestName | **0%** | Expected for internal-only transfers |
| sourceChecksum | **0%** | **BROKEN** — Checksums never computed |
| destinationChecksum | **0%** | **BROKEN** — Checksums never computed |
| encryptionOption | **0%** | Expected if no FolderMapping |
| errorMessage | **2%** | Expected — mostly successful transfers |
| completedAt | 73% | Good — matches MOVED_TO_SENT count |

---

## 2. Architecture: Next-Gen Activity Monitor

### Design Principles
1. **Sub-second response for any filter combination** — even with 10M+ records
2. **Real-time without polling** — SSE push for active transfers
3. **One glance = full story** — source, destination, flow, integrity, timing all visible
4. **Drill down, not click around** — every detail reachable from the table
5. **Enterprise compliance** — export, audit, retention, immutable logs
6. **Scale to millions** — partitioned storage, aggregated stats, lazy enrichment

### Backend Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    API Gateway (nginx)                    │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  GET  /api/v2/activity-monitor          ← Enhanced search│
│  GET  /api/v2/activity-monitor/{trackId} ← Single detail │
│  GET  /api/v2/activity-monitor/stats     ← Aggregates    │
│  GET  /api/v2/activity-monitor/export    ← CSV/JSON/PDF  │
│  GET  /api/v2/activity-monitor/stream    ← SSE real-time │
│  POST /api/v2/activity-monitor/saved-views ← Server-side │
│                                                          │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ActivityMonitorV2Controller                             │
│    ├─ ActivitySearchService (Specification + caching)     │
│    ├─ ActivityStatsService  (Redis-cached aggregates)     │
│    ├─ ActivityExportService (streaming CSV/PDF)           │
│    ├─ ActivityStreamService (SSE via RabbitMQ)            │
│    └─ SavedViewService     (per-user view management)    │
│                                                          │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Enhanced Data Layer:                                    │
│    ├─ Materialized view: transfer_activity_view          │
│    │   (pre-joined: record + account + partner + flow)   │
│    ├─ Index: idx_activity_uploaded_status                │
│    ├─ Index: idx_activity_partner_uploaded               │
│    ├─ Index: idx_activity_filename_gin (trigram)         │
│    ├─ Partitioned table: file_transfer_records           │
│    │   (range partition on uploaded_at, monthly)         │
│    └─ Redis: activity-stats (TTL 10s)                    │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 3. API Design: `/api/v2/activity-monitor`

### 3.1 Enhanced Search Endpoint

```
GET /api/v2/activity-monitor
```

**New Parameters (in addition to existing):**

| Parameter | Type | Description |
|-----------|------|-------------|
| `startDate` | ISO 8601 | Filter: uploadedAt >= startDate |
| `endDate` | ISO 8601 | Filter: uploadedAt <= endDate |
| `partnerName` | String | Filter: source OR dest partner name (LIKE) |
| `partnerId` | UUID | Filter: exact partner ID |
| `destUsername` | String | Filter: destination account username |
| `minSize` | Long | Filter: fileSizeBytes >= minSize |
| `maxSize` | Long | Filter: fileSizeBytes <= maxSize |
| `errorKeyword` | String | Filter: errorMessage LIKE %keyword% |
| `flowName` | String | Filter: flow name (LIKE) |
| `flowStatus` | FlowStatus | Filter: flow execution status |
| `integrityStatus` | String | Filter: VERIFIED, MISMATCH, or PENDING |
| `hasChecksum` | Boolean | Filter: sourceChecksum IS NOT NULL |
| `retryCountMin` | Integer | Filter: retryCount >= N |
| `direction` | String | INBOUND / OUTBOUND |
| `fields` | String[] | Sparse fieldset (return only requested fields) |

**Response Enhancement:**

```json
{
  "content": [...],
  "page": { "number": 0, "size": 25, "totalElements": 15432, "totalPages": 618 },
  "stats": {
    "totalTransfers": 15432,
    "statusBreakdown": { "COMPLETED": 14200, "FAILED": 832, "PENDING": 400 },
    "avgDurationMs": 4520,
    "integrityRate": 0.97,
    "topErrors": [
      { "message": "Connection refused", "count": 312 },
      { "message": "Timeout after 60s", "count": 156 }
    ]
  },
  "appliedFilters": { "startDate": "2026-04-06", "status": "FAILED" }
}
```

### 3.2 Aggregation Endpoint

```
GET /api/v2/activity-monitor/stats?period=24h
```

Returns:

```json
{
  "period": "24h",
  "totalTransfers": 5432,
  "successRate": 0.94,
  "avgTransferTimeMs": 3200,
  "p95TransferTimeMs": 12400,
  "byStatus": { "COMPLETED": 5106, "FAILED": 201, "PENDING": 125 },
  "byProtocol": { "SFTP": 4200, "FTP": 800, "AS2": 432 },
  "byPartner": [
    { "name": "Acme Corp", "count": 1200, "failRate": 0.02 },
    { "name": "GlobalBank", "count": 980, "failRate": 0.05 }
  ],
  "byHour": [
    { "hour": "2026-04-13T00:00Z", "count": 234, "failed": 12 },
    { "hour": "2026-04-13T01:00Z", "count": 456, "failed": 8 }
  ],
  "topErrors": [...],
  "integrityStats": { "verified": 4800, "mismatch": 12, "pending": 620 },
  "slaBreaches": 3
}
```

### 3.3 Export Endpoint

```
GET /api/v2/activity-monitor/export?format=csv&startDate=...&endDate=...
```

- Supports `csv`, `json`, `pdf`
- Streams response (no memory buffer for large exports)
- Applies all the same filters as search
- Adds `Content-Disposition: attachment` header
- Audit-logged (who exported, what filters, how many records)
- Rate-limited (1 export per user per minute)

### 3.4 Real-Time Stream

```
GET /api/v2/activity-monitor/stream
Accept: text/event-stream
```

Server-Sent Events (SSE) stream:
```
event: transfer-update
data: {"trackId":"TRZ123","status":"COMPLETED","completedAt":"..."}

event: transfer-new
data: {"trackId":"TRZ456","filename":"invoice.csv","status":"PENDING"}

event: transfer-failed
data: {"trackId":"TRZ789","errorMessage":"Connection refused","retryCount":3}

event: stats-update
data: {"totalActive":42,"failed24h":5,"avgLatencyMs":3200}
```

Implementation: RabbitMQ `file-transfer.events` exchange → SSE adapter. Each browser tab opens one SSE connection. Server-side event filtering by user's active filters.

### 3.5 Single Transfer Detail

```
GET /api/v2/activity-monitor/{trackId}
```

Returns everything in one call (no secondary lookups):

```json
{
  "transfer": { /* all ActivityMonitorEntry fields */ },
  "flowExecution": {
    "status": "COMPLETED",
    "stepResults": [...],
    "attemptHistory": [...],
    "matchedCriteria": {...}
  },
  "fabricTimeline": [
    { "stepIndex": 0, "stepType": "CHECKSUM_VERIFY", "status": "COMPLETED", "durationMs": 45 },
    { "stepIndex": 1, "stepType": "ENCRYPT_PGP", "status": "COMPLETED", "durationMs": 1200 }
  ],
  "auditTrail": [
    { "timestamp": "...", "action": "FILE_UPLOAD", "principal": "acme-sftp", "ipAddress": "10.0.1.5" }
  ],
  "relatedTransfers": [
    { "trackId": "TRZ124", "filename": "invoice-2.csv", "status": "COMPLETED" }
  ]
}
```

---

## 4. UI Design: Next-Gen Activity Monitor

### 4.1 Layout (3-Panel)

```
┌──────────────────────────────────────────────────────────────┐
│ [HEADER]  Activity Monitor          [Search] [Export] [Views]│
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─── Stats Strip ──────────────────────────────────────┐    │
│  │ 📊 15,432    ✅ 94.2%    ❌ 832     ⏱ 3.2s    🔒 97% │    │
│  │ Total        Success    Failed    Avg Time  Integrity│    │
│  └──────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─── Filter Bar ───────────────────────────────────────┐    │
│  │ [Date Range ▼] [Status ▼] [Protocol ▼] [Partner ▼]  │    │
│  │ [Filename...] [Track ID...] [Error...] [Size ▼]      │    │
│  │ [Active filters: 3]  [Clear All]                      │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─── Main Table ───────────────────────────────────────┐    │
│  │ ☐ │Track ID    │File         │Status │Source→Dest    │    │
│  │───│────────────│─────────────│───────│───────────────│    │
│  │ ☐ │TRZ-A1B2C3 │invoice.csv  │✅ DONE│acme → globbnk │    │
│  │ ☐ │TRZ-D4E5F6 │payment.edi  │❌ FAIL│medtch → (ext) │    │
│  │ ▶ │TRZ-G7H8I9 │report.xml   │⏳ PROC│logifl → acme  │    │
│  │   │            │             │       │               │    │
│  │   │  ┌── Expanded Detail ────────────────────┐       │    │
│  │   │  │ ⬤──⬤──⬤──⬤  Pipeline: 3/4 steps    │       │
│  │   │  │ Source: acme-sftp → Dest: globalbank  │       │
│  │   │  │ Size: 2.4 MB │ SHA: a3f2...verified  │       │
│  │   │  │ Duration: 4.2s │ Retries: 0          │       │
│  │   │  │ [Download] [Journey] [Restart]        │       │
│  │   │  └───────────────────────────────────────┘       │
│  │                                                       │
│  │ Showing 1-25 of 15,432  [◀ 1 2 3 ... 618 ▶] [25 ▼] │
│  └──────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─── Mini Chart Strip ─────────────────────────────────┐    │
│  │ [Transfers/Hour sparkline]  [Error Rate sparkline]    │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 Key UI Enhancements

#### A. Source → Destination Column (Unified)
Instead of 6 separate columns (sourceUsername, sourceProtocol, sourcePartnerName, destUsername, destProtocol, destPartnerName), show ONE column:

```
acme-sftp (SFTP) → globalbank-sftp (SFTP)
  Acme Corp          GlobalBank Ltd
```

Hover shows full details. Click either side to navigate to account page.

#### B. Timeline Column (Visual)
Replace 4 timestamp columns with a visual mini-timeline:

```
⬤───────⬤─────⬤──────⬤
Upload  Route  Download  Done
0.0s    0.1s   3.2s      4.5s
```

Shows progress inline. Red dot if failed. Animated dot if in-progress.

#### C. Date Range Picker
Replace missing date filter with a professional date range component:
- Presets: "Last hour", "Today", "Yesterday", "Last 7 days", "Last 30 days", "Custom"
- Custom: two date pickers with time selectors
- URL-encoded for shareable links

#### D. Real-Time Badge
Replace polling toggle with SSE-powered live badge:
```
🟢 Live (3 active transfers)
```
Updates counter in real-time. Pulses when new transfer arrives. Click to show only active transfers.

#### E. Transfer Health Score
Each transfer gets a computed health score (0-100):
- Checksum verified: +30
- Completed within SLA: +30
- No retries: +20
- Encryption applied: +10
- Under expected size: +10

Shown as a colored circle: 🟢 80-100, 🟡 50-79, 🔴 0-49

#### F. Error Categorization
Instead of raw error messages, categorize:
- **Network** — Connection refused, timeout, DNS failure
- **Auth** — Invalid credentials, account locked, expired key
- **Storage** — Disk full, permission denied, quota exceeded
- **Business** — DLP violation, compliance block, SLA breach
- **System** — OOM, service unavailable, circuit open

Show category badge + expand for full message.

#### G. Inline Actions Bar
On row hover, show action buttons contextually:

| Status | Actions |
|--------|---------|
| PENDING | [Cancel] |
| PROCESSING | [Terminate] [View Progress] |
| COMPLETED | [Download] [Verify Integrity] [Re-deliver] |
| FAILED | [Restart] [Restart from Step N] [Schedule Retry] [Download Partial] |
| PAUSED | [Resume] [Approve] [Reject] |

#### H. Comparison Mode
Select 2 transfers → side-by-side comparison showing:
- Same file? (filename match)
- Same source? (account match)
- Time difference
- Size difference
- Step-by-step comparison

Useful for debugging "why did this file succeed but that one failed?"

---

## 5. Backend Fixes (Priority Order)

### P0 — Data Quality (Fixes that make existing data useful)

**Fix 1: Populate checksums during transfer lifecycle**
```
WHERE: sftp-service/SftpFileSystemProvider.java (on upload complete)
       gateway-service/RoutingEngine.java (on delivery complete)
WHAT:  Compute SHA-256 on write, store in FileTransferRecord.sourceChecksum
       Compute SHA-256 on delivery, store in FileTransferRecord.destinationChecksum
WHY:   70% of transfers show MISMATCH because both checksums are null
       null.equals(null) = false → always MISMATCH
```

**Fix 2: Partner linking via TransferAccount.partnerId**
```
WHERE: ActivityMonitorController.toEntry()
WHAT:  TransferAccount has a partnerId field. Currently, partnerMap.get(src.getPartnerId())
       returns null because the accounts' partnerId was never set during demo-onboard.
       The seed script creates partners and accounts separately without linking.
FIX:   Either: (a) seed script sets partnerId on account creation
       OR: (b) link accounts to partners via a join table
WHY:   sourcePartnerName and destPartnerName are 100% null
```

**Fix 3: Populate destination data**
```
WHERE: Flow execution pipeline (when flow assigns destination)
WHAT:  FileTransferRecord.destinationFilePath is set but dest account info is lost
       because VIRTUAL-mode records have no FolderMapping
FIX:   Add destinationAccountId to FileTransferRecord (like sourceAccountId)
       Set it when flow execution determines the destination
WHY:   destUsername, destProtocol, destPartnerName are 100% null
```

### P1 — Missing Filters

**Fix 4: Add date range filter to ActivityMonitorController**
```java
if (startDateF != null) {
    predicates.add(cb.greaterThanOrEqualTo(root.get("uploadedAt"), startDateF));
}
if (endDateF != null) {
    predicates.add(cb.lessThanOrEqualTo(root.get("uploadedAt"), endDateF));
}
```

**Fix 5: Add file size range filter**
```java
if (minSizeF != null) {
    predicates.add(cb.greaterThanOrEqualTo(root.get("fileSizeBytes"), minSizeF));
}
if (maxSizeF != null) {
    predicates.add(cb.lessThanOrEqualTo(root.get("fileSizeBytes"), maxSizeF));
}
```

**Fix 6: Add partner name filter**
```java
if (partnerNameF != null) {
    Join<FileTransferRecord, FolderMapping> fm = root.join("folderMapping", JoinType.LEFT);
    Join<FolderMapping, TransferAccount> sa = fm.join("sourceAccount", JoinType.LEFT);
    Predicate srcPartner = cb.like(cb.lower(sa.get("partner").get("companyName")), 
        "%" + partnerNameF.toLowerCase() + "%");
    predicates.add(srcPartner);
}
```

**Fix 7: Add error keyword filter**
```java
if (errorKeywordF != null) {
    predicates.add(cb.like(cb.lower(root.get("errorMessage")),
        "%" + errorKeywordF.toLowerCase() + "%"));
}
```

### P2 — Performance

**Fix 8: Add FabricCheckpoint indexes**
```sql
-- Flyway migration V57__fabric_checkpoint_indexes.sql
CREATE INDEX idx_fc_track_id ON fabric_checkpoints(track_id);
CREATE INDEX idx_fc_status_lease ON fabric_checkpoints(status, lease_expires_at)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_fc_instance ON fabric_checkpoints(processing_instance)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_fc_completed ON fabric_checkpoints(completed_at DESC)
    WHERE status = 'COMPLETED';
```

**Fix 9: Cache partner map**
```java
@Cacheable(value = "partner-names", unless = "#result.isEmpty()")
public Map<UUID, String> getPartnerNameMap() {
    return partnerRepo.findAll().stream()
        .collect(Collectors.toMap(Partner::getId,
            p -> p.getDisplayName() != null ? p.getDisplayName() : p.getCompanyName(),
            (a, b) -> a));
}
// Evict on partner create/update/delete
```

**Fix 10: Optimize FabricCheckpoint batch query**
```sql
-- Replace findLatestByTrackIds with:
SELECT DISTINCT ON (track_id) * FROM fabric_checkpoints
WHERE track_id IN (:trackIds)
ORDER BY track_id, step_index DESC;
-- Returns exactly 1 row per trackId instead of all rows
```

### P3 — New Capabilities

**Fix 11: CSV export endpoint**
```java
@GetMapping("/export")
public void export(@RequestParam String format, HttpServletResponse response, ...) {
    response.setContentType("text/csv");
    response.setHeader("Content-Disposition", "attachment; filename=activity-export.csv");
    try (PrintWriter writer = response.getWriter()) {
        // Stream rows using ScrollableResults, write CSV line by line
        // Never buffer entire result set in memory
    }
}
```

**Fix 12: SSE stream endpoint**
```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<ActivityEvent>> stream() {
    return Flux.create(sink -> {
        rabbitTemplate.addListener("file-transfer.events", msg -> {
            sink.next(ServerSentEvent.builder(parseEvent(msg)).build());
        });
    });
}
```

**Fix 13: Stats aggregation endpoint (Redis-cached)**
```java
@GetMapping("/stats")
@Cacheable(value = "activity-stats", key = "#period")
public ActivityStats getStats(@RequestParam(defaultValue = "24h") String period) {
    // Single query with GROUP BY status, protocol
    // Cached for 10 seconds
}
```

---

## 6. Database Optimizations

### Materialized View for Activity Monitor
```sql
CREATE MATERIALIZED VIEW transfer_activity_view AS
SELECT
    r.id, r.track_id, r.original_filename, r.status,
    r.file_size_bytes, r.source_checksum, r.destination_checksum,
    r.uploaded_at, r.routed_at, r.downloaded_at, r.completed_at,
    r.retry_count, r.error_message, r.source_file_path, r.destination_file_path,
    -- Source
    sa.username AS source_username, sa.protocol AS source_protocol,
    sp.company_name AS source_partner_name,
    -- Destination
    da.username AS dest_username, da.protocol AS dest_protocol,
    dp.company_name AS dest_partner_name,
    -- Flow
    f.name AS flow_name,
    fe.status AS flow_status,
    -- Fabric
    fc.step_index AS current_step, fc.step_type AS current_step_type,
    fc.processing_instance, fc.status AS fabric_status
FROM file_transfer_records r
LEFT JOIN folder_mappings fm ON r.folder_mapping_id = fm.id
LEFT JOIN transfer_accounts sa ON COALESCE(fm.source_account_id, r.source_account_id) = sa.id
LEFT JOIN transfer_accounts da ON fm.destination_account_id = da.id
LEFT JOIN partners sp ON sa.partner_id = sp.id
LEFT JOIN partners dp ON da.partner_id = dp.id
LEFT JOIN flow_executions fe ON r.track_id = fe.track_id
LEFT JOIN file_flows f ON fe.flow_id = f.id
LEFT JOIN LATERAL (
    SELECT * FROM fabric_checkpoints fc2
    WHERE fc2.track_id = r.track_id
    ORDER BY fc2.step_index DESC LIMIT 1
) fc ON true;

CREATE UNIQUE INDEX ON transfer_activity_view (id);
CREATE INDEX ON transfer_activity_view (uploaded_at DESC);
CREATE INDEX ON transfer_activity_view (status);
CREATE INDEX ON transfer_activity_view (source_partner_name);
CREATE INDEX ON transfer_activity_view (flow_status);

-- Refresh every 30 seconds via pg_cron or application scheduler
REFRESH MATERIALIZED VIEW CONCURRENTLY transfer_activity_view;
```

This eliminates ALL joins at query time. The Activity Monitor becomes a simple `SELECT * FROM transfer_activity_view WHERE ... ORDER BY ... LIMIT ...` — sub-millisecond response.

### Table Partitioning (for millions of records)
```sql
-- Partition file_transfer_records by month
ALTER TABLE file_transfer_records RENAME TO file_transfer_records_old;
CREATE TABLE file_transfer_records (
    LIKE file_transfer_records_old INCLUDING ALL
) PARTITION BY RANGE (uploaded_at);

CREATE TABLE ftr_2026_01 PARTITION OF file_transfer_records
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE ftr_2026_02 PARTITION OF file_transfer_records
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
-- ... etc (auto-create via pg_partman)
```

---

## 7. Testing Additions for Next-Gen

### New Playwright Tests Needed

```
tests/activity-monitor-v2.spec.js
├── Date range filter (startDate, endDate, presets)
├── File size range filter (minSize, maxSize)
├── Partner name filter (partial match)
├── Error keyword search
├── Combined multi-filter (date + status + partner + size)
├── CSV export download and content validation
├── JSON export download and structure validation
├── SSE stream connection and event reception
├── Stats endpoint returns correct aggregates
├── Single transfer detail endpoint
├── Integrity verification (VERIFIED when checksums match)
├── Source → Destination unified display
├── Mini timeline visualization
├── Real-time badge updates
├── Comparison mode (select 2, compare)
├── Error categorization display
├── Health score calculation and display
├── Bulk terminate, bulk schedule retry
├── Date range presets ("Last 7 days" etc.)
├── Export audit logging verification
└── Performance: <200ms for all filter combinations on 100K records
```

---

## 8. Implementation Roadmap

### Phase 1: Fix Data Quality (1-2 sprints)
- [ ] Fix checksum computation (source + destination SHA-256)
- [ ] Fix partner linking (set partnerId on accounts)
- [ ] Add destinationAccountId to FileTransferRecord
- [ ] Add FabricCheckpoint database indexes
- [ ] Cache partner name map

### Phase 2: Enhanced Filters (1 sprint)
- [ ] Date range filter (startDate/endDate)
- [ ] File size range filter (minSize/maxSize)
- [ ] Partner name filter
- [ ] Error keyword filter
- [ ] Flow name/status filter
- [ ] Integrity status filter

### Phase 3: Export & Compliance (1 sprint)
- [ ] CSV streaming export
- [ ] JSON export
- [ ] PDF export (with integrity proof)
- [ ] Audit log of all searches
- [ ] Retention policy enforcement

### Phase 4: Real-Time (1-2 sprints)
- [ ] SSE endpoint for live updates
- [ ] RabbitMQ → SSE adapter
- [ ] UI: live badge + auto-insert new rows
- [ ] Stats aggregation endpoint with Redis cache

### Phase 5: Advanced UI (2 sprints)
- [ ] Source → Destination unified column
- [ ] Mini timeline column
- [ ] Health score computation
- [ ] Error categorization
- [ ] Comparison mode
- [ ] Sparkline charts (transfers/hour, error rate)
- [ ] Keyboard shortcuts (J/K navigation, R=restart, D=download)

### Phase 6: Scale (1-2 sprints)
- [ ] Materialized view for zero-join queries
- [ ] Table partitioning by month
- [ ] Concurrent materialized view refresh (30s)
- [ ] Query timeout protection (30s max)

---

## 9. Summary

The Activity Monitor has a solid foundation — the API is fast, the Specification pattern is clean, and the UI has professional touches (saved views, column settings, fabric enrichment). But the **data quality issues** (G1-G3) make 70% of the fields useless, and the **missing filters** (G4-G7) prevent operators from finding what they need.

**The #1 priority is fixing data quality.** No amount of UI polish matters if checksums are null, partner names are missing, and destinations are unknown. Fix that, and the existing Activity Monitor immediately becomes 10x more useful — before writing a single line of new code.

After data quality, the highest-impact additions are:
1. Date range filter (operators' most common question: "what happened last night?")
2. CSV export (compliance auditors ask for this daily)
3. Real-time SSE (eliminate 30-second polling lag)
4. Stats aggregation (one-glance system health)

The rest of the roadmap builds toward a world-class monitoring experience that scales to millions of transfers per day.
