# R86 thorough sanity test sweep

**Date:** 2026-04-17
**Build:** R86 (HEAD `73f03bea` at test time)
**Script:** [scripts/sanity-test.sh](../../scripts/sanity-test.sh) — reusable, idempotent, ~60s to run against a warm stack.

---

## Headline

**54 of 55 assertions PASS.** One minor finding — `GET /api/servers/{nonexistent-uuid}` returns 500 instead of 404. Cosmetic; fix direction documented below.

Every major surface of the platform exercised: auth + RBAC, dynamic listener lifecycle, flow CRUD, keystore (AES + PGP), scheduler validation, observability APIs across 6 services, VFS SFTP ops (ls + put + auth), end-to-end flow with byte-level proof, actuator liveness across 12 services, and 5 negative-case validations.

## Pass/fail by section

| § | Section | PASS | FAIL | SKIP |
|---|---|---:|---:|---:|
| 1 | Auth + RBAC | 6 | 0 | 0 |
| 2 | Dynamic listener lifecycle | 5 | 0 | 0 |
| 3 | Flows CRUD + step types | 5 | 0 | 0 |
| 4 | Keystore (AES / PGP / list / public) | 4 | 0 | 0 |
| 5 | Scheduler validation | 2 | 0 | 0 |
| 6 | Observability APIs | 6 | 0 | 0 |
| 7 | VFS SFTP ops | 4 | 0 | 0 |
| 8 | End-to-end flow + byte-level | 4 | 0 | 0 |
| 9 | Infra health (actuator) | 13 | 0 | 0 |
| 10 | Negative tests | 4 | **1** | 0 |
| — | Cleanup | 1 | 0 | 0 |
| | **Total** | **54** | **1** | **0** |

## The one finding — nonexistent listener returns 500 instead of 404

```
GET /api/servers/00000000-0000-0000-0000-000000000000
→ HTTP 500  "An unexpected error occurred"
```

Root cause at [`ServerInstanceService.java:220`](../../onboarding-api/src/main/java/com/filetransfer/onboarding/service/ServerInstanceService.java#L220):
```java
repository.findById(id).orElseThrow(
    () -> new NoSuchElementException("Server instance not found: " + id));
```

`NoSuchElementException` has no `@ResponseStatus` and no `@ExceptionHandler` mapping it to 404, so the global exception handler catches it as an unhandled runtime exception and returns 500.

**Fix direction** (one line):
```java
.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Server instance not found: " + id));
```
Or add an `@ExceptionHandler(NoSuchElementException.class)` in `PlatformExceptionHandler` that maps to 404.

Severity: **P2**. Functional behavior unchanged (the resource is correctly reported as not found); only the status code leaks as 500 instead of the REST-conventional 404.

## Highlights — what the sweep proved

### Everything touching the customer path works

- **Auth:** admin login, JWT role claims (`role=ADMIN`), refresh token round-trip, role-gate (USER gets 403 on ADMIN endpoints).
- **Listener lifecycle:** VIRTUAL SFTP listener created via API binds within 5s, conflict returns 409, port-suggestions returns exactly 5, rebind returns 202, delete returns 204.
- **Flow CRUD:** create, list (via Redis cache fix — R83), PATCH priority, toggle active on/off, duplicate-name rejected 400.
- **Keystore:** AES generation 201, PGP generation 201 (R84 fix holds — RSA-4096 keypair with real PEM), list returns 6 keys, `GET /api/v1/keys/{alias}/public` returns 200.
- **VFS SFTP:** auth on dynamic listener → `pwd` → `put` → `ls` → file visible. Full round-trip on port 2231 via `sftp-reg-1` + `regtest-sftp-1`.
- **End-to-end flow:** `.dat` upload → `regtest-f7-script-mailbox` wins over seeded catch-all (R86 fix holds) → EXECUTE_SCRIPT runs on VFS → byte-level diff shows `sanity e2e first line to uppercase` → `SANITY E2E FIRST LINE TO UPPERCASE`, track `TRZM7RDQC5ZJ`, status `COMPLETED`.

### Observability coverage

- `actuator/health/liveness` returns 200 for all 12 Java services (onboarding-api, config-service, sftp-service, ftp-service, encryption-service, screening-service, keystore-manager, analytics-service, ai-engine, platform-sentinel, storage-manager, notification-service).
- Prometheus metrics scrape works on sftp-service.
- Sentinel rules API returns 200 and the R76-inline-seeded `listener_bind_failed` rule is present.
- Analytics dashboard, AI engine health, fabric queues, screening liveness — all green.

### Scheduler + connector validation

Both scheduler validators from R69 fire with the exact spec-compliant message:
- `EXECUTE_SCRIPT` without `config.command` → 400 "requires config.command (e.g. 'check-pgp-expiry')"
- `RUN_FLOW` without `referenceId` → 400 "requires referenceId (the flow UUID)"

### Negative-test coverage

- Malformed JSON → 400
- Unknown field → 400 with the field name in the error (Jackson-level validation working)
- USER role on ADMIN endpoint → 403
- Duplicate flow name → 400
- *(finding above: nonexistent listener → 500 instead of 404)*

## Runtime characteristics observed during the sweep

- ~60s total wall-clock for the full 55-assertion sweep against a warm stack.
- Zero restart loops, zero unhealthy containers, no "Restarting" state at any point.
- All flow_executions for the sweep recorded with `status=COMPLETED` in <1s each.
- 6 centralized keys now in the keystore (aes-default, aes-outbound, aes-r84, pgp-r84, sanity-aes, sanity-pgp).

## What the script does NOT cover (opportunities for future rounds)

The sanity script is breadth-first. Depth items still worth dedicated suites:

1. **FTP** (not just SFTP). Ports 21 and 2121 and dynamic FTP listeners.
2. **FTP_WEB** HTTP upload/download. `ftp-web-service` on 8083.
3. **AS2** partnership handshake. `as2-service` on 8094.
4. **PGP decrypt flow** (`regtest-f4`). Now that PGP keygen works, can wire an inbound `.pgp` end-to-end.
5. **EDI conversion** (`regtest-f6`). X12 → JSON. Sample files in `demo-data/`.
6. **External delivery** (`regtest-f8`). Forwarding to a 3rd-party SFTP. Needs a test sink container.
7. **P2P transfer** via `/api/p2p/*`.
8. **Keystore rotation hot-reload** (R70). Publish `keystore.key.rotated` → verify listener rebind logged.
9. **ShedLock dedup under scale=2** (R71). Only tested single-replica so far.
10. **DMZ proxy path** (R79). 
11. **Perf/load harness** per the earlier suggestion (handshake/sec, concurrent sessions, SFTP directory-op throughput).
12. **Chaos**: kill a service mid-flow, verify retry from DLQ, verify outbox replays.

Each of those would add 2-5 assertions. The sanity script is designed for easy extension — add more sections following the existing pattern.

## Running it yourself

```bash
# precondition — fresh fixture (takes ~15s)
./scripts/build-regression-fixture.sh

# run the sanity sweep (takes ~60s)
./scripts/sanity-test.sh
```

Exit codes:
- `0` = all PASS
- `1` = one or more FAIL (failures listed at the end)
- `2` = prerequisite missing (jq/curl/docker not available, or no containers running)

Re-running is safe; every assertion is idempotent.
