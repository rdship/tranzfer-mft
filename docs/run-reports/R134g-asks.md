---
release: R134g (follows R134f phase-1 verification)
date: 2026-04-19
author: dev
purpose: explicit ask-list to the tester for the next full-axis run
---

# R134g — Dev Asks to Tester

R134f Phase 1 data-loss fixes were verified ✅ (thanks for the
round-trip evidence). This release (R134g) lands Phase 2 of the
listener-UI gap audit + bumps the platform version to keep docker
logs honest. Before the next full-axis sweep, please help us close
two still-open loops by verifying the items below.

---

## Ask 1 — BUG 12 + BUG 13 runtime verdict (oldest outstanding)

R134 shipped the following changes aimed at the S2S auth bug class
that's been open since R127:

- **PlatformJwtAuthFilter**: `log.info` at the top of `doFilter` so
  every request logs `method / uri / Authorization-summary /
  X-Forwarded-Authorization-summary`. Tester's explicit R133 directive.
- **PlatformJwtAuthFilter**: reordered so `X-Forwarded-Authorization`
  (carrying the admin JWT on user-initiated S2S hops) is evaluated
  **before** the SPIFFE branch, so a valid admin JWT wins even when
  SPIFFE is strict-audience-failing.
- **SpiffeWorkloadClient.validate**: R133's same-trust-domain fallback
  removed (it granted ROLE_INTERNAL which then failed downstream
  `@PreAuthorize(OPERATOR)` — identical 403 to the strict case).
- **SpiffeWorkloadClient.validate**: enriched WARN log dumps
  `actual-aud=... sub=... exp=...` on every rejection.
- **Flyway V93**: added `license_records.services JSONB NULL` to close
  BUG 14 (entity/schema drift surfaced once the License withAuth
  wrapper let requests reach the controller).

**Please verify at runtime, in order:**

1. **Trigger BUG 12 via the admin UI** (POST `/api/delivery-endpoints`
   with a password-auth partner). On `mft-encryption-service` logs,
   confirm that the `[PlatformJwtAuthFilter] entered method=POST
   uri=/api/encrypt/credential/encrypt authz=Bearer... xfwd=Bearer...`
   log line appears for the inbound request.
     - If **YES + request returns 201**: R134 closed BUG 12. Great.
     - If **YES + still 403**: log the exact branch the filter takes
       (the enriched WARN on `[SPIFFE] JWT-SVID rejected ... actual-aud=...`
       will tell us the audience mismatch). Report that line back.
     - If **NO log line at all**: confirms the filter isn't on the
       chain for that request — wiring bug (stale jar / security config
       not loaded / another filter 403s first). Report that.

2. **Trigger BUG 13** (FILE_DELIVERY flow against a real SFTP endpoint).
   Flow engine → external-forwarder path has **no user JWT** so it
   can't benefit from the `X-Forwarded-Authorization` fallback. The
   enriched SPIFFE WARN log should now print the actual aud/sub of the
   rejected token on `mft-forwarder-service`. Please capture and
   report the first occurrence — that tells us whether it's an
   audience mismatch (fix: per-target aud issuance) or something else.

3. **Regression spot-check** — license page. R133 unmasked BUG 14;
   R134 added the V93 migration. Confirm `GET /api/v1/licenses` now
   returns 200 (or empty list — either is fine), not the 500 from
   `column lr1_0.services does not exist`.

---

## Ask 2 — Phase 2 listener-UI verification

R134g landed two validation helpers. Both are client-side; server-side
V87 CHECK constraints are the source of truth.

### 2a. FTP PASV inline error

Create or edit a listener with `protocol=FTP`. Verify these scenarios
show an **inline red `⚠ ...`** under the PASV inputs and block submit:

| Scenario | Expected |
|---|---|
| `from=21000`, `to=` (blank) | "set together — either both or neither" |
| `from=`, `to=21010` | same |
| `from=500`, `to=21010` | "between 1024 and 65535" |
| `from=21000`, `to=100` | "cannot be greater than To" |
| `from=21000`, `to=21010` | no error; submit proceeds |
| `from=`, `to=` (both blank) | no error; submit proceeds (inherits service default) |

### 2b. SSH cipher/MAC/KEX format hints

Under SSH advanced, with `protocol=SFTP`. Inputs at Ciphers / MACs /
KEX. Verify these scenarios show an **inline red `⚠ ...`** and block
submit:

| Input | Expected |
|---|---|
| `aes256-ctr, aes192-ctr` (space after comma) | "Remove spaces around commas" |
| `aes256-ctr,,aes192-ctr` | "Remove empty entries" |
| `aes256-ctr,bad token!` | "has an unsupported character" |
| `aes256-ctr,aes192-ctr` | no error; submit proceeds |
| empty | no error (inherits server defaults) |

---

## Ask 3 — pom.xml version sync

R134f verification report flagged: *"Artifact version (pom.xml):
1.0.0-R127 — lags the R134f commit series."* We only bump
docker-compose `PLATFORM_VERSION` on each push (which is what the UI
platform-status banner reads). Question: **do you need pom.xml
artifact versions bumped on every release**, or is docker-compose
`PLATFORM_VERSION` enough? If pom bumps are worth the churn, we'll add
a `mvn versions:set` step to the release script. If not, we'll note
in docs that `PLATFORM_VERSION` is the source of truth for "what's
running" and pom.xml is just module coordinates.

---

## What's queued on our side after this sweep

- **Phase 3 listener-UI**: HTTPS form section (or remove from picker),
  AS2/AS4 minimum fields, FTP_WEB secondary-listener warning.
- **R&D plan** — external-dependency retirement (Vault first as an
  independent low-risk phase), on hold until the tester confirms we
  have a clean runtime state.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
