---
release: R134l (follows R134k 🥈 Silver — BUG 13 closed on real path)
date: 2026-04-19
author: dev
purpose: sweep of R134k open queue + one open ask for Gap D
---

# R134l — Sweep + One Ask

R134k closed the flagship BUG 13 auth bug class after 7 releases. This
release cleans up the R134k open queue wholesale except for one item
that needs tester input. Items shipped:

1. **Cosmetic — "Using generated security password" warning suppressed**
   on the 4 services the warning still printed on (storage-manager,
   encryption-service, external-forwarder-service, screening-service).
   Added `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)`.
   edi-converter was already clean; tester's option 2.

2. **`/actuator/info` auth posture standardized** — onboarding-api's
   SecurityConfig now permitAll's `/actuator/info` + `/actuator/info/**`,
   matching the other services' posture. Non-sensitive payload
   (platform.version + build timestamp via `PlatformVersionInfoContributor`).
   Closes R134h tester's "gates /actuator/info behind auth" caveat.

3. **AS2 listener auto-seeded in bootstrap** — `PlatformBootstrapService`
   now seeds `as2-server-1` (protocol=AS2, port 10080 → as2-service:10080)
   on first boot. Healthcare Compliance and EDI X12 flows (both
   medtech-as2 sourced) are now reachable from a fresh stack without
   running demo-onboard. Closes R134j/R134k verification's "AS2 listener
   absence" blocker.

4. **Vault retirement Phase 1 — made optional.** Per the R&D plan:
   - `vault.enabled` default flipped to `false` in
     encryption-service and storage-manager application.yml. Both
     services already had env-var fallbacks (`ENCRYPTION_MASTER_KEY`,
     `STORAGE_ENCRYPTION_KEY`); no functional regression.
   - `vault` + `vault-init` containers moved to `profiles: ["vault"]`
     in docker-compose. Default `docker compose up -d` no longer
     starts them. Opt back in with `docker compose --profile vault up -d`.
   - AuditService HMAC key already has env-var fallback
     (`platform.security.control-api-key`), confirmed no Vault
     dependency at runtime.
   - Result: **Vault is now an opt-in dependency.** Next tester run
     should have 2 fewer containers on the default profile.

---

## One open ask — Gap D (demo-onboard schema drift)

R134h tester report flagged `Failed: 145` in the demo-onboard summary,
roughly:

- ~30 duplicates ("already exists") — benign, idempotency-safe
- ~60 schema drift (column changed / renamed / removed; seed payload
  still sends old shape → 400)
- ~50 FK violations (parent row missing in this pass)

Without the specific error lines, a blind sweep risks breaking the
~951 seeds that currently succeed. The schema-drift failures are
probably concentrated in 5-6 seed functions (`create_dlp_policies`,
`create_edi_maps`, `create_notification_rules`, etc.) — but I need
the first line of failure text from each to fix them surgically.

**Ask back:** please run demo-onboard.sh once and capture the first
non-duplicate 4xx/5xx error message for each distinct endpoint. You
can grep /tmp/demo-onboard.log:

```bash
grep -oE '✗.*→ HTTP.*4[0-9][0-9]|✗.*→ HTTP.*5[0-9][0-9]' /tmp/demo-onboard.log \
  | sort -u | head -40
```

Or share the full log and I'll grep. With that, Gap D closes in one
targeted pass instead of a noisy blind refactor.

---

## Independent verification paths for this release

**Cosmetic warning fix:**
```
docker compose logs mft-storage-manager 2>&1 | grep -c "Using generated security password"
→ 0 (was 1)
```

**/actuator/info standardization:**
```
curl -s http://localhost:8080/actuator/info       # onboarding-api
→ {"build":{"version":"1.0.0-R134l",...}}           (was 403)
curl -s http://localhost:8086/actuator/info       # encryption-service
→ {"build":{"version":"1.0.0-R134l",...}}           (unchanged)
```

**AS2 listener:**
```sql
SELECT instance_id, name, protocol, internal_port FROM server_instances
  WHERE protocol = 'AS2';
→ as2-server-1 | AS2 Server 1 — Primary | AS2 | 10080
```

**Vault optional:**
```
docker compose ps vault         → "no matching service"  (was Running)
docker compose --profile vault up -d vault  → starts it back
```

The flagship (BUG 13) regression flow from R134j should still work:
```
sftp -P 2222 globalbank-sftp@localhost
  put /tmp/x.regression /inbox/
```
→ FILE_DELIVERY step returns HTTP 500 (UnresolvedAddressException on
sftp.partner-a.com) — same as R134k's 🥈 verdict. Auth still passes.

---

## What's still queued after R134l

- **Gap D schema drift** — blocked on tester error text.
- **R&D retirement plan Phases 2-4** — parked until flagship surface
  stays clean across 2-3 tester runs on R134l.
- **Anything new** the tester surfaces in the next verification.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
