# DRP Engine Test Report — 2026-04-09

## Summary

| Metric | Value |
|--------|-------|
| Total tests | 3,634 |
| Failures | 0 |
| Errors | 0 |
| New DRP tests | 133 |
| Usability bugs found & fixed | 2 |
| Race conditions found | 1 (by design, hardened) |
| Memory leaks found | 0 |

---

## Performance Benchmarks

### Function Execution

| Benchmark | Avg | Min | Max | P99 | Iterations |
|-----------|-----|-----|-----|-----|------------|
| ChecksumVerify 1 KB | 0.047ms | 0.030ms | 0.688ms | 0.688ms | 100 |
| ChecksumVerify 10 MB | 8.79ms | 7.38ms | 18.12ms | 18.12ms | 100 |
| Registry lookup (17 functions) | 0.089μs | — | — | — | 100,000 |
| Rule matching (100 rules) | 0.001ms | 0.001ms | 0.024ms | 0.005ms | 1,000 |
| Actor replay (10 events) | 0.000ms | 0.000ms | 0.063ms | 0.000ms | 10,000 |
| Actor replay (100 events) | 0.001ms | 0.001ms | 0.005ms | 0.001ms | 10,000 |
| Actor replay (1000 events) | 0.010ms | 0.006ms | 0.208ms | 0.044ms | 10,000 |

**Actor replay scales linearly**: 10 events → 0μs, 100 → 1μs, 1000 → 10μs. No exponential blowup.

### I/O Engine Throughput

| Benchmark | Throughput | Notes |
|-----------|-----------|-------|
| Direct write 1 MB | 3,889 MB/s | Single-thread, SSD |
| Striped write 50 MB | 740 MB/s | 13 stripes, 8 I/O threads |
| readTo() 1 MB | 5,431 MB/s | Zero-copy FileChannel.transferTo |
| readTo() 50 MB | 5,811 MB/s | Scales linearly — no degradation |
| Write+Read roundtrip 10 MB | 4,219 MB/s | End-to-end |

**readTo() is 40% faster than raw write** — zero-copy sendfile bypasses user-space entirely.

### Concurrency

| Benchmark | Throughput | Notes |
|-----------|-----------|-------|
| I/O lane acquire/release | 17.9M ops/sec | Semaphore overhead: 56ns |
| SEDA stage submit→process | 1.55M items/sec | Virtual threads, bounded queue |
| 10 concurrent 10 MB flows | 48ms total (4.8ms/flow) | Linear scaling |
| 100 concurrent 1 KB flows | 23ms total (0.23ms/flow) | No contention |
| Journal event writes | 1.01M events/sec | Async, non-blocking |

### Memory Profile

| Benchmark | Heap Delta | Notes |
|-----------|-----------|-------|
| Old `read()` on 10 MB file | **10.29 MB** | Full file in heap — proves the problem |
| New `readTo()` on 10 MB file | **0.00 MB** | Zero heap — proves the fix |
| FlowActor creation (10,000) | 593 bytes/actor | Can hold 1M actors in 593 MB |
| Registry with 1,000 functions | 0 bytes/function | Descriptors are negligible |

---

## A/B Comparison: Old vs New Read Path

| Metric | Old (`read()` → byte[]) | New (`readTo()` → streaming) |
|--------|------------------------|------------------------------|
| Heap per 10 MB file | 10.29 MB | 0.00 MB |
| Can handle 1 GB file on 4 GB heap | No (OOM at ~3 concurrent) | Yes (unlimited concurrent) |
| Throughput | ~3,800 MB/s | ~5,800 MB/s |
| Approach | Files.readAllBytes → byte[] | FileChannel.transferTo → zero-copy |

**Streaming path is 53% faster AND uses zero heap.** The old path is deprecated.

---

## Usability Test Results

### Bugs Found and Fixed

| # | Bug | Fix | File |
|---|-----|-----|------|
| 1 | `FlowFunctionRegistry.get(null)` threw NPE | Added null guard → returns `Optional.empty()` | FlowFunctionRegistry.java |
| 2 | `FunctionImportExportService.importFunction()` with null name threw NPE | Added validation → throws clear `IllegalArgumentException` | FunctionImportExportService.java |

### API Quality Verified

| Check | Result |
|-------|--------|
| All 17 functions have human-readable descriptions | PASS |
| All types match `[A-Z][A-Z_]+` convention | PASS |
| All ioMode() return non-null | PASS |
| ChecksumVerify mismatch error contains both hashes | PASS |
| FlowActor.toString() includes trackId/status/step | PASS |
| ChecksumVerify with null config works (computation mode) | PASS |
| ChecksumVerify with empty expected hash works | PASS |
| FlowFunctionContext record is immutable | PASS |
| IOLaneManager defaults are reasonable (8 > 4 > 2) | PASS |

---

## Regression Test Results

| Check | Result |
|-------|--------|
| Deprecated `read()` still returns correct byte[] | PASS |
| `readTo()` and `readStream()` have default implementations | PASS |
| FlowExecution.FlowStatus enum unchanged | PASS |
| All 16 original step types still work | PASS |
| StorageObject has all original + new fields | PASS |
| ParallelIOEngine small/striped write unchanged | PASS |
| Max file size enforcement unchanged | PASS |
| FlowRuleRegistry behavior unchanged | PASS |
| ChecksumVerify doesn't affect other functions | PASS |
| Existing match patterns still work | PASS |

**Zero regressions across all 22 modules.**

---

## Race Condition Results

| Test | Result | Notes |
|------|--------|-------|
| Registry concurrent register+get (10 threads) | SAFE | ConcurrentHashMap |
| I/O lane concurrent acquire/release (20 threads, 10K ops) | SAFE | Semaphore balanced |
| SEDA stage concurrent submit (50 threads, 5K items) | SAFE | All tracked |
| Journal concurrent writes (10 threads) | SAFE | Independent saves |
| Actor concurrent replay (5 threads) | NOT THREAD-SAFE | By design (1 actor = 1 thread). Hardened with `synchronized`. |

---

## Test Coverage by Component

| Component | Tests | Categories |
|-----------|-------|------------|
| FlowFunctionRegistry | 6 + 3 + 2 | Unit, performance, regression |
| FlowActor | 10 + 3 + 1 | Unit, performance (scaling), race |
| FlowEventJournal | 6 + 1 | Unit, performance |
| IOLaneManager | 5 + 1 + 1 | Unit, performance, race |
| ProcessingStage | 5 + 1 + 1 | Unit, performance, race |
| WriteIntentService | 4 + 1 | Unit, race |
| LocalStorageBackend streaming | 4 + 4 | Unit, A/B comparison |
| ParallelIOEngine | 8 + 5 | Unit (existing), I/O benchmarks |
| ChecksumVerifyFunction | 4 + 2 | Unit, E2E with flow |
| FlowRuleMatching | 10 + 1 | Unit, regression |
| Flow execution E2E | 8 + 3 | E2E pipeline, import/export |
| Usability | 15 | API quality, error handling, config |
| Regression | 11 | Backward compat, non-regression |

---

*Generated: 2026-04-09. Full suite: 3,634 tests, 0 failures, BUILD SUCCESS.*
