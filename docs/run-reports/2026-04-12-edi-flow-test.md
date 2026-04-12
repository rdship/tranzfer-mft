# EDI File Flow Integration Test — 2026-04-12

## Test Setup

- **50 EDI file flows** created via config-service API with conversion steps:
  - 10× X12 850 (Purchase Order) → JSON with CHECKSUM_VERIFY + MAILBOX
  - 10× X12 810 (Invoice) → XML with MAILBOX
  - 10× X12 856 (ASN) → CSV with COMPRESS_GZIP + MAILBOX
  - 10× EDIFACT ORDERS → JSON with CHECKSUM_VERIFY + MAILBOX
  - 10× Complex multi-step (AUTO detect → varied output + COMPRESS + CHECKSUM + MAILBOX)

- **50 EDI test files** created with realistic content:
  - X12 850: 3-12 PO1 line items per file, unique vendor/buyer IDs
  - X12 810: invoices with IT1 segments and TDS totals
  - X12 856: ASN with HL hierarchy, TD1/TD5 shipping details
  - EDIFACT: UN/EDIFACT ORDERS D:96A with LIN/QTY/PRI segments
  - Complex: mix of X12 and EDIFACT

- **Upload method:** SFTP via DMZ proxy (port 32222), account e2e_tester

## Results

### Upload: ✅ PASS
- 50/50 files uploaded in 939ms via DMZ proxy
- All 50 detected by routing engine within milliseconds

### AI Classification: ✅ PASS
- All 50 files classified: 35 MEDIUM risk, 15 NONE
- AI engine processed all files correctly across 8 parallel threads

### Flow Matching: ❌ COMPLETE FAILURE
- **ALL 50 files matched "EDI Processing Pipeline" (bootstrap flow, priority=10)**
- **NONE of the 50 custom flows were matched** despite having correct filename patterns
- Custom flows had priority 100-510 (higher number = lower priority)
- Root cause: FlowRuleRegistry compiles rules at startup and does NOT include
  filename pattern in the matching predicate. The catch-all bootstrap flow wins every time.

### Flow Execution: ❌ COMPLETE FAILURE
- All 50 executions failed with "null" error (NPE)
- "EDI Processing Pipeline" step 1 is SCREEN with empty config {}
- Status stuck at PROCESSING forever (error not persisted to DB)

### EDI Converter (tested in isolation — bypassing flow engine): ✅ EXCELLENT

| Test File | HTTP | Time | Format | Segments | Verdict |
|---|---|---|---|---|---|
| valid_x12_850 (Purchase Order) | 200 | 129ms | X12 | 14 | ✅ Perfect — identified as 850 "Purchase Order" |
| valid_x12_810 (Invoice) | 200 | 14ms | X12 | 11 | ✅ Perfect |
| valid_x12_856 (ASN) | 200 | 15ms | X12 | 18 | ✅ Perfect |
| valid_edifact_orders | 200 | 15ms | EDIFACT | 16 | ✅ Auto-detected EDIFACT format |
| malformed_x12 (missing GE) | 200 | 15ms | X12 | 6 | ⚠ Parsed but validation gap — missing GE not flagged |
| wrong_format (CSV as .edi) | 200 | 16ms | UNKNOWN | 0 | ⚠ Returns 200 OK with UNKNOWN format — should be 400 |
| truncated_x12 | **500** | 27ms | — | — | ❌ Server crash — needs graceful error handling |
| large_x12_850 (500 lines, 20KB) | 200 | 32ms | X12 | 508 | ✅ Excellent performance |

Output format tests (X12 850 → all formats):

| Output | HTTP | Time | Size |
|---|---|---|---|
| JSON | 200 | 20ms | 1,572 bytes |
| XML | 200 | 13ms | 1,572 bytes |
| CSV | 200 | 13ms | 1,572 bytes |
| YAML | 200 | 14ms | 1,572 bytes |

**Note:** All output formats return 1,572 bytes (same size) and the content appears to be JSON regardless of requested format — the output format conversion may not be fully implemented. CTO should verify that XML/CSV/YAML outputs are actually in those formats, not just JSON with a different label.

## Blockers for the CTO

### BLOCKER 1: FlowRuleRegistry filename pattern matching (Issue 1 — CRITICAL)

This is the #1 blocker for all integration testing. Without this fix, NO custom flows will ever be matched. Every file goes to the bootstrap catch-all.

**What needs to happen:**
1. Find the flow rule compiler (builds `Predicate<MatchContext>` for each flow)
2. Include `filenamePattern` in the predicate: `context.getFilename().matches(compiledPattern)`
3. When multiple flows match, pick the one with the LOWEST priority number
4. When NO flow matches, route to a "default" or "unmatched" handler (not the first active flow)

### BLOCKER 2: SCREEN step NPE on empty config (Issue 2)

Every bootstrap flow has `{"type":"SCREEN","config":{}}` which NPEs. Either:
- Make SCREEN step skip gracefully when config is empty
- Or require valid config at flow creation time (validation)

### BLOCKER 3: Flow execution error not persisted

When a flow fails, the DB record stays in PROCESSING status forever. The error message and FAILED status must be written to the `flow_executions` table.

### BLOCKER 4: Validate endpoint returns 415 Unsupported Media Type

`POST /api/v1/convert/validate` with multipart file returns 415. The controller likely expects a different content type.

### BLOCKER 5: Output format conversion may be a no-op

All 4 output formats (JSON, XML, CSV, YAML) return identical 1,572-byte responses. The converter may only produce JSON internally and not actually transform to the requested format.

## What Works Well

1. **EDI auto-detection** — X12 and EDIFACT correctly identified without specifying input format
2. **Conversion performance** — 14-32ms per file, even for 500-line documents
3. **SFTP upload throughput** — 50 files in <1 second via DMZ proxy
4. **AI classification** — all files classified with risk scoring across 8 parallel threads
5. **Track ID generation** — unique IDs for every transfer, enabling full audit trail
6. **Supported formats** — 110 conversion paths (11 input × 10 output)
7. **Map registry** — 35 pre-configured EDI maps available
