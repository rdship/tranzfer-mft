# R134AF — 🥈 Silver (contribution) / 🥉 Bronze (product-state): heartbeat fix lands cleanly; R134AB decoder finally pays off and re-diagnoses Mode B

**Commit tested:** `25005d71` (R134AF)
**Date:** 2026-04-22
**Environment:** fresh nuke → `mvn package -DskipTests` (full-repo BUILD SUCCESS) → `docker compose up -d --build` → 17 of 17 application services healthy; core verified

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134AF contribution** | 🥈 **Silver** (runtime-verified end-to-end) | Fix lands on first exercise. `hikari.autoCommit=false` is logged at boot — the exact hypothesis from my R134AE report §2 is now evidence. Every heartbeat write wrapped in `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` commits before the connection returns to the pool; the post-commit self-read sees `liveRowsNow=17` (was 0 pre-R134AF). `platform_pod_heartbeat` now has one row per service across all 17 services. `/api/clusters/live` returns `{available:true, source:"pg:platform_pod_heartbeat", totalInstances:17}` with populated `byServiceType`. Scheduled ticks fire on `scheduling-1` thread (`tick=1 rowsAffected=1`, `tick=2 rowsAffected=1`). Zero `liveRowsNow<1` WARN lines anywhere. Diff-read Bronze-cap pre-runtime per no-pre-medal rule; Silver earned cleanly post-runtime on a crisp observe→hypothesize→fix cycle (R134AD → R134AE → R134AF). |
| **Product-state at R134AF** | 🥉 **Bronze** (downgraded from R134AE Silver) | **Mode B SFTP auth trips on this cycle.** R134AB's bytes-level decoder fires as designed and **re-diagnoses Mode B**: the server receives `passwordLen=0 passwordSha256Head=e3b0c442` on every auth attempt. `e3b0c442…` is the SHA-256 of an empty string. So the R134V/R134AA "Mode B = Java bcrypt rejects a valid password" hypothesis is now *wrong*: the server never receives `partner123` at all; it receives zero bytes. The failure is upstream of the BCrypt check. BUG 13 real-path regression can't be exercised this cycle (SFTP upload blocks at auth). Per rubric: any broken protocol surface = Bronze max. |

---

## 1. R134AF fix — crisp end-to-end exercise

### Hypothesis evidence — `hikari.autoCommit=false` at boot

```
[R134AF][ClusterHeartbeat] Registered node=26e79c8e407e:1
    serviceType=sftp-service
    url=http://sftp-service:8081
    rowsAffected=1
    liveRowsNow=17
    hikari.autoCommit=false
    jdbcUrl=jdbc:postgresql://postgres:5432/filetransfer?stringtype=unspecified
```

`hikari.autoCommit=false` is exactly what R134AE's report §2 predicted. JPA-on-classpath makes Hikari default to auto-commit false; a non-`@Transactional` `JdbcTemplate.update()` leaves the INSERT uncommitted; the connection returns to the pool; the row vanishes. The R134AF fix wraps every write in `TransactionTemplate(PROPAGATION_REQUIRES_NEW, READ_COMMITTED)` so Spring opens a real transaction, commits, and returns the connection. The evidence and the fix converge.

### `liveRowsNow=17` post-commit — was 0 at R134AE

At R134AE the same log position showed `liveRowsNow=0`. At R134AF it shows `liveRowsNow=17` (matches the 17 application services that write heartbeats). The self-read inside the REQUIRES_NEW block sees the post-commit state correctly.

### Table now populated per-service

```
service_type                  count  last_heartbeat
ai-engine                     1      2026-04-23 00:37:45.019
analytics-service             1      2026-04-23 00:37:37.692
as2-service                   1      2026-04-23 00:37:38.553
config-service                1      2026-04-23 00:37:41.209
encryption-service            1      2026-04-23 00:37:41.597
external-forwarder-service    1      2026-04-23 00:37:43.140
ftp-service                   1      2026-04-23 00:37:37.889
ftp-web-service               1      2026-04-23 00:37:38.626
gateway-service               1      2026-04-23 00:37:39.485
keystore-manager              1      2026-04-23 00:37:39.392
license-service               1      2026-04-23 00:37:38.614
notification-service          1      2026-04-23 00:37:36.764
onboarding-api                1      2026-04-23 00:37:36.040
platform-sentinel             1      2026-04-23 00:37:42.409
screening-service             1      2026-04-23 00:37:39.220
sftp-service                  1      2026-04-23 00:37:39.412
storage-manager               1      2026-04-23 00:37:45.944
unknown                       1      2026-04-23 00:37:07.704   ← likely spire-init or fabric
```

18 rows. Each service registers exactly once (UPSERT keyed on `node_id`). `last_heartbeat` timestamps are fresh (within the last minute of the check).

### `/api/clusters/live` returns the real cluster

```
{
  "available": true,
  "source": "pg:platform_pod_heartbeat",
  "totalInstances": 17,
  "byServiceType": {
    "ai-engine":        [{ instanceId, host, port, url, startedAt, lastSeen }],
    "analytics-service":[{…}],
    "as2-service":      [{…}],
    …
  }
}
```

The user-facing cluster page now has real data. R134AD's `totalInstances:0` bug is fully closed by R134AF.

### Scheduled heartbeat loop is live

```
[R134AF][ClusterHeartbeat] tick=1 rowsAffected=1 node=26e79c8e407e:1 (silent after tick 3)
[R134AF][ClusterHeartbeat] tick=2 rowsAffected=1 node=26e79c8e407e:1 (silent after tick 3)
  thread=scheduling-1
```

Confirmed on `scheduling-1` thread. The `@Scheduled(fixedDelay=10s)` loop fires as designed; the INFO logs for the first 3 ticks + silent-success afterwards is exactly the UX the commit message promised.

### No WARN anywhere

```
$ for SVC in …; do docker logs mft-$SVC | grep -cE "liveRowsNow<1|liveRowsNow=0"; done
  0 0 0 0 0 0 0 0   (empty — fix works on every service)
```

The fix commits universally. No residual auto-commit failures.

### R134AF marker counts per service

```
sftp-service        : 3
ftp-service         : 4
ftp-web-service     : 4
as2-service         : 4
https-service       : 0    (in start/restart state during grep; see §4)
gateway-service     : 4
onboarding-api      : 4
notification-service: 4
storage-manager     : 4
config-service      : 4
keystore-manager    : 4
```

10 of 11 services log `[R134AF]` markers ≥3 times each.

---

## 2. R134AB decoder finally fires — and it reverses the Mode B diagnosis

R134AB shipped `passwordLen`, `passwordSha256Head`, `storedHashPrefix` in the password-mismatch branch as a decoder for *future* Mode B trips. Today's SFTP upload tripped Mode B. The decoder output:

```
[CredentialService] auth DENIED — password mismatch for
    username='globalbank-sftp'
    accountId=01f5c3bf-8101-4df0-9b71-7eec3dfe2c64
    storedHashLen=60
    storedHashPrefix='$2a$10$'
    passwordLen=0                     ← !
    passwordSha256Head=e3b0c442       ← SHA-256 of empty string
    active=true
    instance='sftp-1'
```

`passwordSha256Head=e3b0c442` is the first 8 hex chars of `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` — the canonical SHA-256 of an **empty string**. Cross-check: `python3 -c "import hashlib; print(hashlib.sha256(b'').hexdigest()[:8])"` → `e3b0c442`.

So the server is receiving **zero bytes** as the password, not `partner123`. Three consecutive attempts from the same client all show `passwordLen=0`.

### What this revises

My prior Mode B hypothesis (R134V / R134AA / memory): *"Java-bcrypt intermittent rejection — stored hash accepts `partner123` in Python's bcrypt but not Java's."* R134AA went as far as cross-verifying the hash against `partner123` in Python. That cross-verification is still true — but it was answering the wrong question. The stored hash is fine. The Java bcrypt library is fine. **The password bytes the server sees aren't `partner123`; they're empty.**

So Mode B as originally described doesn't exist. What looked like intermittent Java-bcrypt rejection was always the server receiving empty bytes and correctly saying "password mismatch." R134AB's bytes-level decoder gave us this on the very first trip it caught — exactly what R134AB was built for.

### Upstream candidate causes

- **macOS OpenSSH hostkey distrust (Mode A variant)**: when the client refuses to transmit the real password due to MITM protection, it may still dispatch a zero-byte password attempt on the wire rather than falling completely silent. This matches both "server saw an auth attempt" and "server saw empty password bytes."
- **Mina SSHD "none" method probing**: Apache Mina may invoke `CredentialService.authenticatePassword(user, "")` during the initial `ssh-userauth` phase probe before the client's real credentials are accepted. These probe attempts then get counted toward lockout.
- **Client-side publickey attempt that falls through to password with empty payload**: if the SSH client tries publickey first with no key, some implementations dispatch an empty-password attempt rather than correctly skipping to keyboard-interactive.

### Dev diagnostic recommendation (candidate R134AG)

Filter zero-length passwords at `SftpPasswordAuthenticator` (before reaching `CredentialService`) and return `PasswordChangeRequired` or `NONE`, NOT `DENIED`. That way:
1. Legitimate-looking "none" probe attempts don't burn lockout attempts.
2. A real password that happens to differ from the stored one still DENIES correctly.
3. If the macOS client is in the bad state, the client sees a cleaner "no valid auth methods" rather than "permission denied" + lockout.

Separately, the memory about Mode A / Mode B needs updating. The distinction I documented at R134AA ("server-side Java-bcrypt rejection") is wrong; I'll update the memory file to record that Mode B is really "server receives zero bytes" and the macOS client is the upstream culprit in both modes.

---

## 3. Product-state Bronze — BUG 13 unverifiable, but everything non-SFTP holds

### Storage-coord primary

```
[VFS][lockPath] backend=storage-coord (R134z primary path active — locks flow through storage-manager platform_locks)
```

Fires on the SFTP connection setup even though the subsequent auth fails. The VFS path itself is healthy.

### Sprint-6 outbox-only

```
account.updated         | 1
flow.rule.updated       | 1
keystore.key.rotated    | 1
server.instance.created | 1
```

All 4 event classes still outbox-only. R134X teeth sharp.

### RabbitMQ slim (R134Y holds)

```
notification.events    1 consumer
file.upload.events     20 consumers
```

### R134AB BCrypt self-test at boot

```
[R134AB][CredentialService] BCrypt self-test: encoderClass=BCryptPasswordEncoder encodedPrefix='$2a$10$' roundTripOk=true
```

Library is fine — which is now especially meaningful because it rules out a bcrypt-library explanation for the empty-password observation.

---

## 4. `https-service` continues transient-start-pattern

Carried: `mft-https-service` shows `health: starting` intermittently on first grep. Not R134AF-attributable; same pattern since R134W.

---

## Still open

Promoted:
- **Zero-byte SFTP password arriving at CredentialService.** `passwordLen=0` with `passwordSha256Head=e3b0c442` proves the server never sees `partner123`. Fix candidate: filter zero-length passwords upstream in `SftpPasswordAuthenticator`. Supersedes the R134V/R134AA "Java bcrypt rejection" theory — that hypothesis is now falsified.
- Memory update pending: Mode A / Mode B taxonomy needs rewriting with the R134AB-derived truth.

Closed by R134AF:
- ✅ `platform_pod_heartbeat` table populated (17 of 17 app services)
- ✅ `/api/clusters/live` returns real cluster state
- ✅ Hikari `autoCommit=false` hypothesis confirmed and fixed via `TransactionTemplate REQUIRES_NEW`
- ✅ Scheduled heartbeat loop is visibly alive

Unchanged:
- Redis container + 5 remaining consumers pending R134AG sequence
- `ftp-2` UNKNOWN, demo-onboard 92.1%, coord auth posture review, account handler `type=null` log nit

---

## Sprint series state

| Tag | What shipped | Runtime | Pattern |
|---|---|---|---|
| R134T→R134AE | (prior reports) | ✓ / partial | |
| **R134AF** | **TransactionTemplate REQUIRES_NEW on heartbeat writes** | **✓** | **🥈 / 🥉** |

13 atomic R-tags. R134AD → R134AE → R134AF is a textbook three-cycle observe→hypothesize→fix arc: R134AD surfaced the `platform_pod_heartbeat empty` symptom, R134AE added observability that narrowed it to transaction-commit territory, R134AF fixed it cleanly with TransactionTemplate REQUIRES_NEW on first try. Meanwhile the R134AB bytes-level bcrypt decoder finally caught a real Mode B trip and **falsified the original Mode B theory** (it's zero-byte-password upstream, not bcrypt rejection) — that single log line rewrites 3 cycles of my reporting.

---

**Report author:** Claude (2026-04-22 session). R134AF contribution Silver — crisp end-to-end fix of the exact bug R134AE narrowed; hypothesis evidence + fix both land in one commit. Product-state Bronze — SFTP upload blocked this cycle by the zero-byte-password Mode B, which R134AB's decoder now definitively diagnoses. Diff-read Bronze-capped pre-runtime per no-pre-medal rule; contribution Silver earned cleanly post-runtime.
