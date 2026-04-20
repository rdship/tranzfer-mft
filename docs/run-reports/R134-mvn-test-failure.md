# R134 — `mvn test` Failure Report

**Commit:** `39920d92` — *R134: root-cause BUG 12 + schema drift + tester's diagnostic-log directive*
**Branch:** `main`
**Date:** 2026-04-19
**Command:** `mvn test`
**Outcome:** `BUILD FAILURE` (1 test failure halts reactor; 7 modules skipped)

---

## Reactor Summary

| Status | Count | Modules |
|---|---|---|
| ✅ SUCCESS | 20 | parent, shared, shared-core, shared-fabric, shared-platform, shared-tunnel, onboarding-api, sftp-service, ftp-service, ftp-web-service, config-service, gateway-service, encryption-service, external-forwarder-service, dmz-proxy, license-service, analytics-service, cli, client, |
| ❌ FAILURE | 1 | **ai-engine** (1 of 222 tests failed) |
| ⏭ SKIPPED | 7 | screening-service, keystore-manager, storage-manager, edi-converter, as2-service, notification-service, platform-sentinel |

---

## The failing test

**Test:** `com.filetransfer.ai.controller.MappingCorrectionControllerTest.listSessions_missingPartnerId_throws400`
**Location:** `ai-engine/src/test/java/com/filetransfer/ai/controller/MappingCorrectionControllerTest.java:161`

### Assertion error

```
org.opentest4j.AssertionFailedError:
  Expected org.springframework.web.server.ResponseStatusException to be thrown,
  but nothing was thrown.

  at org.junit.jupiter.api.Assertions.assertThrows(Assertions.java:3128)
  at MappingCorrectionControllerTest.listSessions_missingPartnerId_throws400(MappingCorrectionControllerTest.java:161)
```

### Interpretation

The test expects `listSessions(...)` in `MappingCorrectionController` to throw a `ResponseStatusException` (HTTP 400) when `partnerId` is missing. Production code is **not** throwing — call returns normally.

Two likely hypotheses (not verified — no code was read):

1. **Controller validation was removed/loosened** in a recent R134 (or prior) refactor; the endpoint now tolerates missing `partnerId` (defaulting, returning empty list, or delegating to service layer).
2. **Bean Validation annotation gap** — `@RequestParam partnerId` lost its `required=true` or a `@NotBlank`/`@NotNull` constraint.

---

## Impact

- Reactor halts at ai-engine → **7 downstream modules were never tested** this run. Their green/red status for R134 is unknown.
- Re-run suggestion: `mvn test -fae` (fail-at-end) to get full matrix in a single pass.

---

## Environment

- **OS:** macOS 25.2.0 (arm64)
- **JDK:** Java 25 (surefire opens required per CLAUDE.md)
- **Stack:** freshly rebuilt post-`git pull` to `39920d92`; all 36 containers healthy before test run
- **Total test time to failure:** 01:16 min
- **Full log:** `/tmp/mvn-test.log` on local machine

---

## Recommendation

1. Open [MappingCorrectionController.listSessions](ai-engine/src/main/java/com/filetransfer/ai/controller/) and [MappingCorrectionControllerTest:161](ai-engine/src/test/java/com/filetransfer/ai/controller/MappingCorrectionControllerTest.java#L161) to decide which side is authoritative — the behavioral change (controller) or the contract (test).
2. Re-run `mvn test -fae` after the fix to confirm the 7 skipped modules are green.
