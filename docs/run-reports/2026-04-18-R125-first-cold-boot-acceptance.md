# R125 acceptance — 🚫 No Medal — P0 compile failure

**Date:** 2026-04-18
**Build:** R125 (HEAD `d13a310e`)
**Outcome:** 🚫 **Unshippable. `mvn clean package` fails on `storage-manager`.** Cannot validate any of R125's claimed 5 endpoint fixes + JFR revert because the platform won't build.

---

## Build failure (captured on fresh `mvn clean package -DskipTests`)

```
[ERROR] /…/storage-manager/src/main/java/com/filetransfer/storage/controller/StorageController.java:[163,34]
  unreported exception java.lang.Exception; must be caught or declared to be thrown
[ERROR] /…/storage-manager/src/main/java/com/filetransfer/storage/controller/StorageController.java:[349,34]
  unreported exception java.lang.Exception; must be caught or declared to be thrown

[INFO] Storage Manager .................................... FAILURE [ 0.327 s]
[INFO] BUILD FAILURE
```

### Root cause

R125 added a try/catch around `storageBackend.readTo(...)` inside the `retrieve` (line 163) and `stream` (line 349) methods to catch `NoSuchFileException` and return a clean 404. But:

- `retrieve` declares `throws IOException` (line 135).
- `stream` declares `throws IOException` (line 326).
- `storageBackend.readTo(...)` throws `java.lang.Exception` (wider than IOException), surfaced because `Exception` isn't in the catch or the throws clause.

The sibling method `retrieveByKey` at line 257 was already declared `throws Exception` — R125 caught the pattern there but not the other two.

### One-line fix

```java
public void retrieve(…) throws Exception { …  }
public void stream  (…) throws Exception { …  }
```

OR better, narrow the backend signature to throw `IOException` only so the three methods stay tight. Dev-team choice.

### How this shipped

R121's first-boot-strict + ArchUnit CI gates cover runtime, not compile. The merge-to-main CI should have run `mvn clean package` — either it didn't, or it's running on a stale branch, or it was force-merged without green. **Worth re-checking whether R100's CI AOT parity gate actually blocks a failing build, or only tests behavioural parity after a successful one.**

---

## Nothing in R125 could be runtime-validated

Because the platform won't build, none of R125's claims can be re-probed on a fresh container:

| R125 claim | Validated? |
|---|---|
| Storage download 500 → 404 fix | ❌ storage-manager won't compile |
| Flow-action `/retry` `/cancel` `/stop` 500 → 404 | ❌ didn't reach runtime |
| Dashboard `/api/flows/executions?size=20` 500 → 200 | ❌ |
| Activity Monitor Restart button broadened | ⚠️ verified in source (UI code change), can't exercise live |
| JFR `settings=profile` → flag-gated default-off | ⚠️ verified in docker-compose YAML, can't exercise live |

The UI + YAML changes look correct on inspection. The data-plane claims (stores, flow actions, widget) cannot be validated until the build is fixed.

---

## 🏅 Release Rating — R125

### R125 = 🚫 No Medal — P0 build failure

Per the rubric: "P0 present; platform-down; zero-confidence release." A build that doesn't compile is categorically No Medal. Rating withheld until the P0 clears.

No Works / Safe / Efficient / Reliable scoring applies to a release that can't be built. Trajectory sits at R124 Bronze until R126 (or a hot-fix of R125).

### Trajectory

| Release | Medal |
|---|---|
| R120 | 🥉 |
| R121+R122 | 🥈 |
| R123 | 🥉 (after UI walk-through correction) |
| R124 | 🥉 (observability shipped; boot regressed) |
| **R125** | 🚫 **No Medal — P0 compile failure** |

### Asks for R126 (immediate)

1. **FIX COMPILE** — add `throws Exception` to `StorageController.retrieve` (line 135) and `StorageController.stream` (line 326), OR narrow `StorageBackend.readTo(...)` to throw only `IOException`.
2. Re-run the acceptance sweep once green — then we can actually verify R125's 5 claimed endpoint fixes.
3. Harden CI: ensure `mvn clean package` must pass before merge-to-main. Likely the current gate skips compile and only enforces AOT parity.

All prior open asks remain: FTP-direct sanity, 120 s mandate, 30-min soak, Phase-2 mTLS, Playwright UI/SSE fixture debt.

---

**Git SHA:** `d13a310e`. Not runtime-tested; compile failure prevents build.
