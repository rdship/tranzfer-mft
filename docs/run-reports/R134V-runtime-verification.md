# R134V — 🥈 Silver (contribution) / 🥉 Bronze (product-state): multi-handler registry works, SFTP auth regression blocks full Silver

**Commit tested:** `17fe754b` (R134V)
**Date:** 2026-04-22
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134V contribution** | 🥈 **Silver** (runtime-verified) | Framework capability add lands cleanly. `Map<String, List<OutboxEventHandler>>` registry works; all 4 existing Sprint-6 event classes drain unchanged; new `(total at prefix: N)` log fires uniformly across services; 15 registrations on boot, each at count=1 (no service registers the same prefix twice yet — the capability is unused-but-ready for Phase B). |
| **Product-state at R134V** | 🥉 **Bronze** (downgraded from R134U Silver) | **New regression observed**: SFTP password auth for `globalbank-sftp` with `partner123` fails as `invalid_credentials` despite the bcrypt hash matching. This blocks the R134j regression flow and the BUG 13 real-path verification. R134V's code path doesn't touch auth, so the regression is likely either a test-sequence side-effect (keystore rotation → listener rebind → bean refresh) or an upstream drift that only surfaced because I ran rotation in the same cycle. Honest downgrade. |

---

## R134V framework cap — runtime-verified

### Boot logs — 15 registrations, all `(total at prefix: 1)`

```
[Outbox/sftp-service]     Registered handler for routing key prefix 'account.'             (total at prefix: 1)
[Outbox/sftp-service]     Registered handler for routing key prefix 'keystore.key.rotated' (total at prefix: 1)
[Outbox/sftp-service]     Registered handler for routing key prefix 'server.instance.'     (total at prefix: 1)
[Outbox/sftp-service]     Registered handler for routing key prefix 'flow.rule.updated'    (total at prefix: 1)
[Outbox/ftp-web-service]  Registered handler for routing key prefix 'account.'             (total at prefix: 1)
[Outbox/ftp-web-service]  Registered handler for routing key prefix 'flow.rule.updated'    (total at prefix: 1)
[Outbox/as2-service]      Registered handler for routing key prefix 'server.instance.'     (total at prefix: 1)
[Outbox/as2-service]      Registered handler for routing key prefix 'flow.rule.updated'    (total at prefix: 1)
[Outbox/gateway-service]  Registered handler for routing key prefix 'flow.rule.updated'    (total at prefix: 1)
[Outbox/https-service]    Registered handler for routing key prefix 'server.instance.'     (total at prefix: 1)
… (15 total)
```

The log format matches the R134V commit message exactly. No service registers twice yet (account.* flip to outbox-only is Phase B's dependency), so every count stays at 1 — the capability is shipped and ready, not yet exercised beyond its existing workload.

### All 4 event classes drain unchanged

```
POST /api/v1/keys/bootstrap-test-ssh-host/rotate → 200
PATCH /api/flows/{id}/toggle                     → 200
POST /api/servers                                → 201
PATCH /api/accounts/{id} (active:false)          → 200
```

```
event_outbox:
 account.updated         UPDATED     1
 flow.rule.updated       UPDATED     1
 keystore.key.rotated    KEY_ROTATED 1
 server.instance.created CREATED     1
```

Per-event drain counts:
```
  keystore.key.rotated     : 2 lines
  flow.rule.updated        : 5 lines
  account.updated          : 4 lines    (both RabbitMQ + outbox — account still dual-path)
  server.instance.created  : 3 lines
```

No regression vs. R134U. The `CopyOnWriteArrayList` registry + `findHandlers(plural)` + single-tx iteration all behave as designed.

---

## Product-state regression — SFTP password auth

### Symptom

```
sshpass -p "partner123" sftp -P 2222 globalbank-sftp@localhost
→ globalbank-sftp@localhost: Permission denied (password,keyboard-interactive,publickey)
  Connection closed
```

### Verification — hash matches password

```
$ python3 -c "import bcrypt; print(bcrypt.checkpw(b'partner123',
    b'\$2a\$10\$P3JAdgQtrZECfvv5FrliBengi0UDqynljtfLeZHx3OXE6PyrmrUp2'))"
partner123 match: True
```

### Server-side logs

```
SFTP password auth attempt: username=globalbank-sftp ip=/172.18.0.1:… listener=sftp-1
{event=LOGIN_FAILED, username=globalbank-sftp, reason=invalid_credentials}
Account locked: username=globalbank-sftp failures=5 lockedUntil=…
SFTP auth rejected: account locked username=globalbank-sftp
```

Five back-to-back failures fire per single `sftp` invocation — client retries across auth methods (password, keyboard-interactive, publickey) → each counts as a failure → lockout at 5.

### Hypothesis (unconfirmed, needs dev diagnostic)

The R134T keystore-rotation test I ran earlier in the cycle triggered `refreshing dynamic SFTP listeners`. Post-R134T this was "2 listeners rebound"; post-R134V it's "1 listener rebound". That rebind may have refreshed a bean graph that the `SftpPasswordAuthenticator` depends on (PasswordEncoder, repository connection) and left a stale handle. Not a confirmed cause.

**This regression did not surface** in prior cycles R134O/Q/R/S/T — globalbank-sftp + partner123 worked repeatedly. So something crossed a threshold between R134T and R134V, or the specific test sequence (rotate in the same run) exposes an existing latent bug.

### Impact on verification

- BUG 13 real-path regression can't be exercised this cycle (R134j regression flow needs SFTP upload)
- R134O Silver "storage-coord primary wins" can't be re-confirmed on a real upload
- UI admin API smoke still OK (unrelated to SFTP listener)

### Why it's product-state Bronze, not No-Medal

- R134V itself is sound. Framework cap ships and works at framework level.
- Flow-engine infrastructure is intact — event_outbox + drain all fine.
- Storage-coord primary path likely still works (code unchanged since R134O), I just can't empirically re-verify this cycle.
- BUG 13 isn't failing, just unverifiable.

Under the rubric: "any broken protocol surface = Bronze max." SFTP password auth is broken on a partner account. Bronze is the fair call even with R134V's framework cap clean.

---

## Side observation

Account handler still logs `type=null` on outbox-sourced events (carried from R134R). Not worse this cycle.

---

## Still open

- **SFTP password auth regression on globalbank-sftp** — NEW this cycle
- `ftp-2` secondary FTP UNKNOWN (carried)
- demo-onboard 92.1% (carried)
- 11 third-party deps (Phase B will shrink after account.* flips)
- Coord endpoint auth posture review (carried)
- Account handler `type=null` log nit (carried)

---

## What dev needs next

Before Sprint 7 Phase B touches account.* and deletes the dormant RabbitMQ code, **resolve the SFTP password auth regression**. The risk: if Phase B's subtractive commit lands while SFTP auth is broken, the root cause is harder to isolate (more variables moving).

Diagnostic suggestions:
1. Boot a fresh stack, try SFTP auth WITHOUT doing a keystore rotation first — does `partner123` work? (isolates "rotation broke it")
2. If yes: the rotation consumer's rebind path clears a bean or cache the SftpPasswordAuthenticator relies on. Trace `KeystoreRotationConsumer.handleEvent` → what beans does it touch besides listeners?
3. If no: the regression is upstream of R134T and has been latent; bisect between R134O (known-working SFTP) and R134V (known-broken SFTP).

---

**Report author:** Claude (2026-04-22 session). R134V framework cap earns Silver at contribution level cleanly. Product-state demoted to Bronze because SFTP auth regression blocks the BUG 13 / R134O re-verification. Diff-read was held at Bronze-cap until runtime per no-pre-medal rule; framework Silver earned, product-state Silver lost this cycle to the auth bug.
