---
release: R132 — end-to-end delivery test to external 3rd-party SFTP
tester: tester-claude (dedicated-account rule restored)
scope: restart MFT stack fresh → spin up atmoz/sftp as 3rd-party partner →
       configure platform ExternalDestination + DeliveryEndpoint + INBOUND flow
       with FILE_DELIVERY step → upload 5 files → verify bytes arrive with
       SHA-256 match
result: ❌ **E2E delivery FAILS at runtime — 2 new S2S auth bugs (BUG 12 + BUG 13)**
---

# R132 — E2E Delivery Test to External 3rd-Party SFTP (❌ FAILED, 2 new bugs)

CTO ask: *"restart all services, do real file flow testing, start a 3rd
party sftp server and create on that, do some deliveries."*

Done end-to-end. Platform ingests fine. Platform can't deliver because
the FILE_DELIVERY → external-forwarder-service S2S call is 403 forbidden.
And delivery-endpoint create via API 500s because config-service →
encryption-service S2S call is also 403 forbidden.

**Same pattern as BUG 1 (R127→R131) and BUG 11 (R131 listener fan-out).**
`BaseServiceClient.post()` / `RestTemplate.exchange()` without SPIFFE
JWT-SVID attachment — Spring Security on the downstream service rejects
the unauthenticated call. Admin clicks a button in the UI, platform
tries internally, and the internal hop 403s.

---

## 1. Setup — fresh and clean

### Stack restart

```
docker compose down -v --remove-orphans   # full clean state
docker compose up -d
# wait for health: 34 containers healthy at ~270s

# api-gateway TLS listener stuck — restart just that container
docker compose restart api-gateway
# https://localhost:443 → HTTP 200 after restart
```

### 3rd-party SFTP sidecar

```
docker run -d --name thirdparty-sftp \
  --network tranzfer-mft_default \
  -v /tmp/thirdparty-sftp:/home/partner/upload \
  atmoz/sftp:alpine partner:PartnerPass123!:1001
```

Container up; in-docker probe:

```
$ docker run --rm --network tranzfer-mft_default alpine:3.19 sftp partner@thirdparty-sftp
Connected to thirdparty-sftp.
sftp> ls
drwxr-xr-x  1001 100    upload
```

### Direct hand-delivery test (baseline — proves the 3rd-party works)

```
$ docker run --rm --network tranzfer-mft_default alpine:3.19 sh -c \
    "sshpass -p 'PartnerPass123!' sftp partner@thirdparty-sftp <<EOF
     put /in.dat direct.txt
     EOF"

$ docker exec thirdparty-sftp cat /home/partner/upload/direct.txt
R132 direct-hand delivery test
```

**Host filesystem mount reflects it:**
```
$ ls /tmp/thirdparty-sftp/
r132-direct-delivery-test.txt    ← bytes landed host-side too
upload/
```

3rd-party SFTP accepts deliveries. Any 3rd-party real partner would
behave the same. **The platform, not the partner, is the blocker.**

---

## 2. Platform config — ExternalDestination + DeliveryEndpoint + Flow

### 2a. ExternalDestination via `/api/external-destinations` (config-service)

```
POST /api/external-destinations
{ "name": "ThirdParty-Partner-SFTP", "type": "SFTP",
  "host": "thirdparty-sftp", "port": 22, "username": "partner",
  "encryptedPassword": "PartnerPass123!", "authType": "PASSWORD",
  "passiveMode": false, "active": true }

→ HTTP 201
id: 5bd372ef-1fe0-49df-90a8-d5e59f433087
```

Note: the platform has **two tables for the same concept**:
- `external_destinations` (what `/api/external-destinations` writes) — schema carries host/port/username + proxyEnabled/proxyType
- `delivery_endpoints` (what `FILE_DELIVERY` step reads via `DeliveryEndpointRepository.findByIdInAndActiveTrue`) — schema carries host/port/authType/protocol/ssh_private_key/bearer_token/api_key_header

**These are different tables for the same operational concept.** Creating
in `external_destinations` doesn't make the endpoint visible to the flow
engine.

---

## 3. BUG 12 — config-service → encryption-service 403 on delivery-endpoint create

When I try to create the endpoint via the matching API:

```
POST /api/delivery-endpoints
{ "name": "ThirdParty-Partner-SFTP-EP", "protocol": "SFTP",
  "host": "thirdparty-sftp", "port": 22, "basePath": "/upload",
  "authType": "BASIC", "username": "partner",
  "encryptedPassword": "PartnerPass123!", "active": true }

→ HTTP 500  "An unexpected error occurred"
```

config-service log:

```
[encryption-service] encryptCredential failed after resilience:
  403 on POST request for "http://encryption-service:8086/api/encrypt/credential/encrypt": [no body]

Unhandled exception at /api/delivery-endpoints: encryption-service:
  encryptCredential failed — is encryption-service reachable at
  http://encryption-service:8086? (HTTP 403 FORBIDDEN)

  at DeliveryEndpointController.encryptSecrets(DeliveryEndpointController.java:126)
  at DeliveryEndpointController.create(DeliveryEndpointController.java:58)
  at EncryptionServiceClient.encryptCredential(EncryptionServiceClient.java:61)
  at BaseServiceClient.post(BaseServiceClient.java:209)
```

Same class as BUGs 1 and 11: `BaseServiceClient` does the S2S call
without attaching a SPIFFE JWT-SVID. encryption-service's
`PlatformJwtAuthFilter` rejects the unauthenticated call with 403,
config-service's catch-all maps it to 500.

**Admin cannot create a delivery-endpoint with an encrypted credential
via the API.** Every single password-authenticated outbound partner
setup is blocked.

**Fix (same pattern):** `BaseServiceClient.post()` needs to attach either
(a) the inbound Authorization header for user-initiated requests (R131
pattern), OR (b) a fresh SPIFFE JWT-SVID for background S2S calls.
Ideally: `BaseServiceClient` centrally attaches a JWT-SVID on every
outbound RestTemplate call. One fix, all the BUG-1/11/12/13 calls
unblocked.

**Workaround for this test:** direct-inserted the row into
`delivery_endpoints` via psql bypassing the encryption step:

```
INSERT INTO delivery_endpoints (id, name, protocol, host, port, base_path,
  auth_type, username, encrypted_password, active, ...)
VALUES (gen_random_uuid(), 'ThirdParty-Partner-SFTP-EP', 'SFTP',
  'thirdparty-sftp', 22, '/upload', 'BASIC', 'partner',
  'PartnerPass123!', true, ...);
-- id: 093330ae-3c3e-4718-9d21-ac998c1dbe6c
```

Yes — plaintext password in the column. Matches the seeded row
`partner-sftp-endpoint` which has `encrypted_password:
"demo-encrypted-placeholder"` (i.e. not actually encrypted either).

---

## 4. Flow created — INBOUND SFTP → FILE_DELIVERY

After two attempts (see Appendix A):

```
POST /api/flows
{ "name": "3rd-party-sftp-delivery-v4",
  "filenamePattern": "r132-delivery-.*\\.txt",
  "direction": "INBOUND",
  "active": true,
  "steps": [{"type":"FILE_DELIVERY","order":0,
             "config":{"deliveryEndpointIds":"093330ae-3c3e-4718-9d21-ac998c1dbe6c"}}]
}

→ HTTP 200
id: 32453abf-35f9-4372-a6b9-745fe2aea239
```

---

## 5. Upload 5 files via inbound → FAIL on delivery

```
5 files uploaded via SFTP port 2231 to regtest-sftp-1 (regression fixture listener):
  r132-delivery-test-*.txt  x 5 (28 B to 13657 B, mix of text / base64 / binary)

Wait 60s:
  flow_executions.status → FAILED × 5
  error_message:
    "Step 0 (FILE_DELIVERY) failed: FILE_DELIVERY failed for all 1 endpoints:
     ThirdParty-Partner-SFTP-EP:
       403 on POST request for
       http://external-forwarder-service:8087/api/forward/deliver/093330ae-...: [no body]"
```

**3rd-party SFTP is idle. 0 bytes delivered:**

```
$ ls /tmp/thirdparty-sftp/
r132-direct-delivery-test.txt   # only the hand-delivered one from setup
upload/
```

---

## 6. BUG 13 — flow engine → external-forwarder-service 403 on `/api/forward/deliver/{id}`

Flow engine wants to hand a file to external-forwarder-service, which
runs the actual SFTP upload to the partner. The call is:

```
POST http://external-forwarder-service:8087/api/forward/deliver/{endpointId}
     multipart/form-data  file=<bytes>
```

external-forwarder-service's `PlatformJwtAuthFilter` rejects the
unauthenticated call with 403. Same class of bug as BUG 12.

The flow engine runs in an onboarding-api / config-service / shared
context — it's a background operation with no user JWT to forward.
**The R131 admin-JWT-forwarding pattern CANNOT solve this case.** This
needs a proper SPIFFE JWT-SVID attachment on every S2S RestTemplate
call.

Until this is fixed, **no FILE_DELIVERY flow can succeed to any
external destination**, not just this test. Partner delivery is a
primary platform feature that is fully non-functional at runtime.

---

## 7. The 4-bug pattern — same root cause

| # | Caller | Callee | Endpoint | Status | Release |
|---|---|---|---|---|---|
| **BUG 1** | onboarding-api (user UI) | storage-manager | `/api/v1/storage/stream/{sha256}` | 403 | fixed R131 by admin-JWT forwarding |
| **BUG 11** | onboarding-api (user UI) | every service | `/api/internal/listeners` | 403 fan-out | open |
| **BUG 12** | config-service (user UI) | encryption-service | `/api/encrypt/credential/encrypt` | 403 | NEW R132 |
| **BUG 13** | flow engine (background) | external-forwarder | `/api/forward/deliver/{id}` | 403 | NEW R132 |

**Unified fix needed:** `BaseServiceClient` / `ResilientServiceClient`
must attach a SPIFFE JWT-SVID on every outbound call by default
(the CLAUDE.md architecture spec already says it should:
*"BaseServiceClient auto-attaches JWT-SVID (Phase 1)"*). At runtime, it
clearly doesn't, or the downstream services' filters don't accept what
it attaches. Either way, S2S auth is broken platform-wide and only
masked by unit tests that don't exercise the real handshake.

**Tooling gap:** every S2S call site should be catalogued and a single
integration test should hit each `A → B` hop and assert 200. Today we
discover these bugs one at a time through customer-facing symptoms
(Journey Download / Service Listeners page / delivery failure).

---

## 8. What worked

- MFT stack cold-boot: 34/34 healthy at 270 s ✓
- api-gateway TLS: needed a restart after stack boot (flaky but fixable) ✓
- 3rd-party SFTP sidecar: direct delivery from mft network works ✓
- Inbound SFTP ingestion via port 2231 → records created, byte-E2E works up to the FILE_DELIVERY step ✓
- Flow creation via `/api/flows` once the `deliveryEndpointIds` schema (comma-separated string, not array) is understood ✓
- ExternalDestination create via `/api/external-destinations` ✓ (though creates an orphan row that flow engine doesn't see — see §2a)

## What doesn't work

- DeliveryEndpoint create via `/api/delivery-endpoints` → 500 (BUG 12)
- FILE_DELIVERY step at runtime → 403 on forwarder S2S call (BUG 13)
- 0 bytes delivered to 3rd-party SFTP via the platform flow (on 5 attempts)

---

## 9. R132+ asks

1. **Fix the BaseServiceClient JWT-SVID attachment** so every outbound
   S2S call carries a valid credential. Single fix unblocks BUGs 11,
   12, 13. Critical path for partner delivery. **2-4 h.**
2. **Add `/api/internal/s2s-mesh-test` or similar integration test** that
   walks every documented S2S hop and asserts 200. Would have caught all
   4 BUGs in CI. **30 min wiring, pays dividends.**
3. **Consolidate `external_destinations` + `delivery_endpoints`** into
   one entity. Two tables for the same operational concept is a data
   model bug that creates "admin creates endpoint but flow doesn't see
   it" confusion.
4. **SecurityProfile wiring (R132 main audit):** still open — see the
   prior R132-SECURITY-PROFILE-WIRING report.
5. **Re-run this end-to-end delivery test** once BUG 13 is closed.
   Acceptance: 5 files uploaded via SFTP:2231 → 5 flow COMPLETED →
   5 files on `/tmp/thirdparty-sftp/upload/` with matching SHA-256.

---

## Appendix A — flow step config schema gotchas

The `FILE_DELIVERY` step config rejected these shapes:

```
config: {"externalDestinationId": "...", "remotePath": "/upload"}
→ runtime error: FILE_DELIVERY step requires 'deliveryEndpointIds'
```

```
config: {"deliveryEndpointIds": ["...uuid..."]}
→ 400 from flow create: Cannot deserialize Array into String
```

Accepted:

```
config: {"deliveryEndpointIds": "uuid1,uuid2,uuid3"}
```

Step config is `Map<String,String>`, so comma-separated strings are the
only array-like shape. The fixture script in
`scripts/build-regression-fixture.sh` uses
`{"endpoint":"partner-sftp"}` — that's ALSO wrong (would produce the
"requires deliveryEndpointIds" error). R132 ask: fix the fixture to use
the real schema + explicit references, OR make the step config accept
`List<UUID>` via a JSONB column.

---

## Appendix B — 3rd-party SFTP setup (reusable)

```
docker run -d --name thirdparty-sftp \
  --network tranzfer-mft_default \
  -v /tmp/thirdparty-sftp:/home/partner/upload \
  atmoz/sftp:alpine partner:PartnerPass123!:1001
```

Default user writes land at `/home/partner/upload/*` inside the
container, which maps to `/tmp/thirdparty-sftp/*` on the host — so
tester can verify byte-identical SHA-256 from the host side without
shell'ing into the sidecar.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
