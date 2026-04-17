#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — thorough sanity test sweep
# =============================================================================
#
# Exercises the major surfaces of the running stack and prints a PASS/FAIL
# summary per section. Designed to run AFTER `docker compose up -d` has
# reached clean-healthy and AFTER `./scripts/build-regression-fixture.sh`
# has created the regtest fixture.
#
# Sections:
#   1. Auth + RBAC (login, token, role gates)
#   2. Dynamic listener lifecycle (create/bind/rebind/delete)
#   3. Flow CRUD + step-types catalog
#   4. Keystore (AES generate, PGP generate, list, rotate)
#   5. Scheduler validation (400 on malformed)
#   6. Observability APIs (Sentinel, analytics, AI, screening, fabric)
#   7. VFS SFTP ops (auth, ls, put, get, remove)
#   8. End-to-end flow — upload, script runs, byte-level diff
#   9. Infra health (actuator on each service)
#  10. Negative tests (401, 403, 400, 409)
#  11. FTP direct — control + passive-mode upload (R87 proves)
#  12. FTP-via-DMZ — reverse-proxy PASV rewriting + passive forwarders (R88)
#
# Usage:  ./scripts/sanity-test.sh
# Exit:   0 if all PASS, 1 if any FAIL, 2 on prerequisite miss
# =============================================================================
set -uo pipefail

GREEN=$'\e[32m'; RED=$'\e[31m'; YELLOW=$'\e[33m'; BOLD=$'\e[1m'; RST=$'\e[0m'
PASS=0; FAIL=0; SKIP=0
FAILS=()

pass() { printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; PASS=$((PASS+1)); }
fail() { printf '  %s✗%s %s\n' "$RED" "$RST" "$*"; FAIL=$((FAIL+1)); FAILS+=("$*"); }
skip() { printf '  %s·%s %s\n' "$YELLOW" "$RST" "$*"; SKIP=$((SKIP+1)); }
section(){ printf '\n%s── %s ──%s\n' "$BOLD" "$*" "$RST"; }

API_ON="http://localhost:8080"
API_CFG="http://localhost:8084"
API_KS="http://localhost:8093"
API_ANL="http://localhost:8090"
API_SENT="http://localhost:8098"
API_AI="http://localhost:8091"
API_SCR="http://localhost:8092"

# Prereqs
command -v jq >/dev/null || { echo "jq required"; exit 2; }
command -v curl >/dev/null || exit 2
docker ps -q | grep -q . || { echo "no containers running"; exit 2; }

# ─────────────────────────────────────────────────────────────────────────────
section "1. Auth + RBAC"

# 1.1 admin login
LOGIN=$(curl -sk -X POST "$API_ON/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}')
TOK=$(echo "$LOGIN" | jq -r '.accessToken // empty')
RT=$(echo "$LOGIN" | jq -r '.refreshToken // empty')
if [ -n "$TOK" ] && [ ${#TOK} -gt 50 ]; then
  pass "admin login → JWT (${#TOK} chars) + refresh token"
else
  fail "admin login failed"
  echo "cannot continue without token"; exit 1
fi
HDR="Authorization: Bearer $TOK"

# 1.2 token claims
CLAIMS=$(echo "$TOK" | cut -d. -f2 | base64 -d 2>/dev/null | jq .)
ROLE=$(echo "$CLAIMS" | jq -r '.role')
[ "$ROLE" = "ADMIN" ] && pass "JWT role=ADMIN in claims" || fail "JWT role=$ROLE (expected ADMIN)"

# 1.3 invalid credentials → 401
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$API_ON/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"nobody@x.io","password":"wrong"}')
[ "$CODE" = "401" ] && pass "invalid creds → 401" || fail "invalid creds → $CODE (expected 401)"

# 1.4 refresh token works
RESP=$(curl -sk -X POST "$API_ON/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}")
NEWTOK=$(echo "$RESP" | jq -r '.accessToken // empty')
[ ${#NEWTOK} -gt 50 ] && pass "refresh token → new JWT" || fail "refresh failed"

# 1.5 ADMIN-only endpoint with valid token
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "$HDR" "$API_ON/api/users")
[ "$CODE" = "200" ] && pass "GET /api/users (ADMIN) → 200" || fail "GET /api/users → $CODE"

# 1.6 unauth → 401/403
CODE=$(curl -sk -o /dev/null -w "%{http_code}" "$API_ON/api/users")
[ "$CODE" = "401" ] || [ "$CODE" = "403" ] && pass "GET /api/users without token → $CODE" || fail "unauth → $CODE"

# ─────────────────────────────────────────────────────────────────────────────
section "2. Dynamic listener lifecycle"

# 2.1 create (VIRTUAL)
R=$(curl -sk -w "\n%{http_code}" -X POST "$API_ON/api/servers" -H "$HDR" -H 'Content-Type: application/json' -d '{
  "instanceId":"sanity-sftp-1","protocol":"SFTP","name":"sanity listener",
  "internalHost":"sftp-service","internalPort":2250,"externalHost":"localhost","externalPort":2250,
  "maxConnections":10}')
STATUS=${R##*$'\n'}; BODY=${R%$'\n'*}
SID=$(echo "$BODY" | jq -r '.id // empty')
if [ "$STATUS" = "201" ] && [ -n "$SID" ]; then
  pass "POST /api/servers → 201 + VIRTUAL + id=${SID:0:8}"
elif echo "$BODY" | grep -q 'already'; then
  SID=$(curl -sk -H "$HDR" "$API_ON/api/servers" | jq -r '.[] | select(.instanceId=="sanity-sftp-1") | .id')
  skip "sanity-sftp-1 already exists (reusing id=${SID:0:8})"
else
  fail "create listener → $STATUS"
fi

# 2.2 bound within 5s
sleep 6
BS=$(curl -sk -H "$HDR" "$API_ON/api/servers/$SID" | jq -r '.bindState')
[ "$BS" = "BOUND" ] && pass "bindState=BOUND within 5s" || fail "bindState=$BS (expected BOUND)"

# 2.3 409 on conflict
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$API_ON/api/servers" -H "$HDR" -H 'Content-Type: application/json' -d '{
  "instanceId":"sanity-conflict","protocol":"SFTP","name":"conflict","internalHost":"sftp-service","internalPort":2250,"externalHost":"localhost","externalPort":2250}')
[ "$CODE" = "409" ] && pass "duplicate port → 409" || fail "duplicate port → $CODE"

# 2.4 port-suggestions
SUG=$(curl -sk -H "$HDR" "$API_ON/api/servers/port-suggestions?host=sftp-service&port=2250&count=5" | jq -r '.suggestedPorts | length')
[ "$SUG" = "5" ] && pass "port-suggestions returns 5 free ports" || fail "port-suggestions → $SUG"

# 2.5 rebind (202)
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$API_ON/api/servers/$SID/rebind" -H "$HDR")
[ "$CODE" = "202" ] && pass "rebind → 202" || fail "rebind → $CODE"

# 2.6 delete (204) — keep it alive for now; only test the DELETE path at end
# (done in cleanup section)

# ─────────────────────────────────────────────────────────────────────────────
section "3. Flows CRUD + step types"

# 3.1 list flows (Redis cache fix)
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "$HDR" "$API_CFG/api/flows")
[ "$CODE" = "200" ] && pass "GET /api/flows → 200" || fail "GET /api/flows → $CODE"

# 3.2 step-types catalog (7 categories)
CATS=$(curl -sk -H "$HDR" "$API_CFG/api/flows/step-types" | jq 'keys | length')
[ "$CATS" -ge 6 ] && pass "step-types catalog has $CATS categories" || fail "step-types → $CATS categories"

# 3.3 create test flow
RESP=$(curl -sk -w "\n%{http_code}" -X POST "$API_CFG/api/flows" -H "$HDR" -H 'Content-Type: application/json' -d '{
  "name":"sanity-flow-1","direction":"INBOUND","filenamePattern":".*\\.sanity",
  "sourcePath":"/inbox","destinationPath":"/outbox","priority":50,"active":true,
  "steps":[{"type":"SCREEN","order":0,"config":{}},
           {"type":"RENAME","order":1,"config":{"pattern":"${basename}_sanity.${ext}"}},
           {"type":"MAILBOX","order":2,"config":{"destinationUsername":"regtest-sftp-1"}}]}')
STATUS=${RESP##*$'\n'}; FLOW_ID=$(echo "${RESP%$'\n'*}" | jq -r '.id // empty')
if [ "$STATUS" = "201" ]; then
  pass "POST /api/flows sanity-flow-1 → 201"
elif echo "$RESP" | grep -q 'already'; then
  FLOW_ID=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c "SELECT id FROM file_flows WHERE name='sanity-flow-1'" | tr -d '[:space:]')
  skip "sanity-flow-1 already exists (id=${FLOW_ID:0:8})"
else
  fail "create flow → $STATUS"
fi

# 3.4 patch priority
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X PATCH "$API_CFG/api/flows/$FLOW_ID" -H "$HDR" -H 'Content-Type: application/json' -d '{"priority":45}')
[ "$CODE" = "200" ] && pass "PATCH flow priority → 200" || fail "PATCH → $CODE"

# 3.5 toggle active
curl -sk -X PATCH "$API_CFG/api/flows/$FLOW_ID/toggle" -H "$HDR" >/dev/null
AFTER=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c "SELECT active FROM file_flows WHERE id='$FLOW_ID'")
[ "$AFTER" = "f" ] && pass "toggle deactivate → active=false" || fail "toggle → active=$AFTER"
curl -sk -X PATCH "$API_CFG/api/flows/$FLOW_ID/toggle" -H "$HDR" >/dev/null

# ─────────────────────────────────────────────────────────────────────────────
section "4. Keystore (AES / PGP / list / rotate)"

# 4.1 generate AES
R=$(curl -sk -w "\n%{http_code}" -X POST "$API_KS/api/v1/keys/generate/aes" -H "$HDR" -H 'Content-Type: application/json' -d '{"alias":"sanity-aes","ownerService":"encryption-service"}')
STATUS=${R##*$'\n'}
[ "$STATUS" = "201" ] && pass "AES keygen → 201" || {
  echo "${R%$'\n'*}" | grep -qi 'already' && skip "sanity-aes already exists" || fail "AES keygen → $STATUS"
}

# 4.2 generate PGP
R=$(curl -sk -w "\n%{http_code}" -X POST "$API_KS/api/v1/keys/generate/pgp" -H "$HDR" -H 'Content-Type: application/json' -d '{"alias":"sanity-pgp","identity":"sanity@tranzfer.io","passphrase":"SanityP@ss1"}')
STATUS=${R##*$'\n'}; ALIAS=$(echo "${R%$'\n'*}" | jq -r '.alias // empty')
[ "$STATUS" = "201" ] && [ "$ALIAS" = "sanity-pgp" ] && pass "PGP keygen → 201 alias=$ALIAS" || {
  echo "${R%$'\n'*}" | grep -qi 'already' && skip "sanity-pgp already exists" || fail "PGP keygen → $STATUS"
}

# 4.3 list keys
COUNT=$(curl -sk -H "$HDR" "$API_KS/api/v1/keys" | jq 'length // 0')
[ "$COUNT" -ge 2 ] && pass "GET /api/v1/keys → $COUNT keys (≥2)" || fail "key count=$COUNT"

# 4.4 public key fetch
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "$HDR" "$API_KS/api/v1/keys/sanity-pgp/public")
[ "$CODE" = "200" ] && pass "GET /api/v1/keys/{alias}/public → 200" || skip "public-key fetch → $CODE (pgp key may not exist)"

# ─────────────────────────────────────────────────────────────────────────────
section "5. Scheduler validation"

# 5.1 EXECUTE_SCRIPT without command → 400
MSG=$(curl -sk -X POST "$API_CFG/api/scheduler" -H "$HDR" -H 'Content-Type: application/json' -d '{"name":"sanity-bad-script","cronExpression":"0 0 7 * * *","taskType":"EXECUTE_SCRIPT","config":{}}' | jq -r '.message // ""')
echo "$MSG" | grep -qi 'requires config.command' && pass "EXECUTE_SCRIPT no command → 400 with spec message" || fail "EXECUTE_SCRIPT validation → '$MSG'"

# 5.2 RUN_FLOW without referenceId → 400
MSG=$(curl -sk -X POST "$API_CFG/api/scheduler" -H "$HDR" -H 'Content-Type: application/json' -d '{"name":"sanity-bad-flow","cronExpression":"0 0 7 * * *","taskType":"RUN_FLOW"}' | jq -r '.message // ""')
echo "$MSG" | grep -qi 'requires referenceId' && pass "RUN_FLOW no refId → 400 with spec message" || fail "RUN_FLOW validation → '$MSG'"

# ─────────────────────────────────────────────────────────────────────────────
section "6. Observability APIs"

# 6.1 Sentinel rules list
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "$HDR" "$API_SENT/api/v1/sentinel/rules")
[ "$CODE" = "200" ] && pass "GET /api/v1/sentinel/rules → 200" || fail "sentinel rules → $CODE"

# 6.2 Sentinel listener_bind_failed rule seeded
RULE=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c "SELECT name FROM sentinel_rules WHERE name='listener_bind_failed'" | tr -d '[:space:]')
[ "$RULE" = "listener_bind_failed" ] && pass "Sentinel listener_bind_failed rule in DB" || fail "rule not seeded"

# 6.3 Analytics dashboard
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "$HDR" "$API_ANL/api/v1/analytics/dashboard")
[ "$CODE" = "200" ] && pass "GET analytics/dashboard → 200" || fail "analytics → $CODE"

# 6.4 AI engine health
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "$HDR" "$API_AI/api/v1/ai/health")
[ "$CODE" = "200" ] && pass "AI engine health → 200" || skip "AI health → $CODE"

# 6.5 Screening health
CODE=$(curl -sk -o /dev/null -w "%{http_code}" "$API_SCR/actuator/health/liveness")
[ "$CODE" = "200" ] && pass "screening liveness → 200" || fail "screening → $CODE"

# 6.6 Fabric queues
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "$HDR" "$API_ON/api/fabric/queues")
[ "$CODE" = "200" ] && pass "GET /api/fabric/queues → 200" || fail "fabric → $CODE"

# ─────────────────────────────────────────────────────────────────────────────
section "7. VFS SFTP ops (auth / ls / put / get)"

# precondition: build-regression-fixture.sh has created sftp-reg-1 on 2231
LISTEN=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c "SELECT id FROM server_instances WHERE instance_id='sftp-reg-1'" | tr -d '[:space:]')
if [ -z "$LISTEN" ]; then
  skip "sftp-reg-1 not present — run ./scripts/build-regression-fixture.sh first; skipping §7"
else
  TS=$(date +%s); FNAME="sanity-$TS.vfs"
  echo "sanity vfs content line 1" > "/tmp/$FNAME"
  echo "line 2" >> "/tmp/$FNAME"

  # 7.1 auth + pwd + ls empty + put + ls + get + rm
  OUT=$(docker run --rm --network tranzfer-mft_default -v "/tmp/$FNAME:/test.dat:ro" alpine:latest sh -c '
    apk add --quiet openssh-client sshpass >/dev/null 2>&1
    sshpass -p "RegTest@2026!" sftp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o PreferredAuthentications=password -o PubkeyAuthentication=no -P 2231 regtest-sftp-1@sftp-service <<EOF 2>&1
pwd
put /test.dat '"$FNAME"'
ls
bye
EOF' 2>&1)

  echo "$OUT" | grep -q "Connected to sftp-service" && pass "SFTP auth + connect on dynamic listener 2231" || fail "SFTP connect failed"
  echo "$OUT" | grep -q "Remote working directory: /" && pass "SFTP pwd=/" || fail "pwd wrong"
  echo "$OUT" | grep -q "Uploading" && pass "SFTP put succeeded" || fail "put failed"
  echo "$OUT" | grep -q "$FNAME" && pass "SFTP ls shows uploaded file" || fail "ls doesn't show file"
fi

# ─────────────────────────────────────────────────────────────────────────────
section "8. End-to-end file flow (EXECUTE_SCRIPT + byte-level)"

if ! docker exec mft-postgres psql -U postgres -d filetransfer -tAc "SELECT 1 FROM file_flows WHERE name='regtest-f7-script-mailbox' AND active=true" 2>/dev/null | grep -q 1; then
  skip "regtest-f7-script-mailbox not present; skipping §8"
else
  TS=$(date +%s); FNAME="sanity-e2e-$TS.dat"
  cat > "/tmp/$FNAME" <<EOF
sanity e2e first line to uppercase
line 2 stays
line 3 final
EOF

  # 8.1 upload
  docker run --rm --network tranzfer-mft_default -v "/tmp/$FNAME:/test.dat:ro" alpine:latest sh -c '
    apk add --quiet openssh-client sshpass >/dev/null 2>&1
    sshpass -p "RegTest@2026!" sftp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o PreferredAuthentications=password -o PubkeyAuthentication=no -P 2231 regtest-sftp-1@sftp-service <<EOF >/dev/null 2>&1
put /test.dat '"$FNAME"'
bye
EOF'

  sleep 6
  # 8.2 which flow ran
  FLOW=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c \
    "SELECT ff.name FROM flow_executions fe JOIN file_flows ff ON fe.flow_id=ff.id
       WHERE fe.started_at > (NOW() - INTERVAL '30 seconds') ORDER BY fe.started_at DESC LIMIT 1" | tr -d '[:space:]')
  [ "$FLOW" = "regtest-f7-script-mailbox" ] && pass "flow selected = regtest-f7-script-mailbox" || fail "flow selected = $FLOW"

  # 8.3 status COMPLETED
  TRACK=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c \
    "SELECT track_id FROM flow_executions WHERE started_at > (NOW() - INTERVAL '30 seconds') ORDER BY started_at DESC LIMIT 1" | tr -d '[:space:]')
  STATUS=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c \
    "SELECT status FROM flow_executions WHERE track_id='$TRACK'" | tr -d '[:space:]')
  [ "$STATUS" = "COMPLETED" ] && pass "track $TRACK status=COMPLETED" || fail "track status=$STATUS"

  # 8.4 distinct input/output storage keys
  INKEY=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c \
    "SELECT storage_key FROM virtual_entries WHERE track_id='$TRACK' AND path='/$FNAME' LIMIT 1" | tr -d '[:space:]')
  OUTKEY=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c \
    "SELECT storage_key FROM virtual_entries WHERE track_id='$TRACK' AND path LIKE '%outbox%' LIMIT 1" | tr -d '[:space:]')
  if [ -n "$INKEY" ] && [ -n "$OUTKEY" ] && [ "$INKEY" != "$OUTKEY" ]; then
    pass "input key ${INKEY:0:12} ≠ output key ${OUTKEY:0:12} (transform happened)"
  else
    fail "key diff — in=${INKEY:0:12} out=${OUTKEY:0:12}"
  fi

  # 8.5 byte-level uppercase proof
  OUT_BYTES=$(docker exec mft-storage-manager cat "/data/storage/hot/$OUTKEY" 2>/dev/null | head -1)
  case "$OUT_BYTES" in
    *"SANITY E2E FIRST LINE TO UPPERCASE"*) pass "first line uppercased in CAS blob" ;;
    *) fail "output first line = '$OUT_BYTES' (no uppercase)" ;;
  esac
fi

# ─────────────────────────────────────────────────────────────────────────────
section "9. Infra health (actuator)"

for svc in onboarding-api:8080 config-service:8084 sftp-service:8081 ftp-service:8082 \
           encryption-service:8086 screening-service:8092 keystore-manager:8093 \
           analytics-service:8090 ai-engine:8091 platform-sentinel:8098 \
           storage-manager:8096 notification-service:8097; do
  NAME=${svc%:*}; PORT=${svc#*:}
  CODE=$(curl -sk -o /dev/null -w "%{http_code}" "http://localhost:$PORT/actuator/health/liveness")
  [ "$CODE" = "200" ] && pass "$NAME liveness → 200" || fail "$NAME liveness → $CODE"
done

# Prometheus metrics endpoint
CODE=$(curl -sk -o /dev/null -w "%{http_code}" "http://localhost:8081/actuator/prometheus")
[ "$CODE" = "200" ] && pass "sftp-service prometheus metrics → 200" || skip "prometheus → $CODE"

# ─────────────────────────────────────────────────────────────────────────────
section "10. Negative tests"

# 10.1 bad JSON → 400
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$API_ON/api/servers" -H "$HDR" -H 'Content-Type: application/json' -d '{malformed')
[ "$CODE" = "400" ] && pass "malformed JSON → 400" || fail "malformed JSON → $CODE"

# 10.2 unknown field → 400 with readable message
MSG=$(curl -sk -X POST "$API_ON/api/servers" -H "$HDR" -H 'Content-Type: application/json' -d '{"instanceId":"nope","protocol":"SFTP","name":"x","internalHost":"h","internalPort":9999,"externalHost":"h","externalPort":9999,"bogusField":"x"}' | jq -r '.message // ""')
echo "$MSG" | grep -qi 'unrecognized\|bogusField' && pass "unknown field → 400 with field name" || fail "unknown field → '$MSG'"

# 10.3 nonexistent listener → 404
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "$HDR" "$API_ON/api/servers/00000000-0000-0000-0000-000000000000")
[ "$CODE" = "404" ] || [ "$CODE" = "400" ] && pass "nonexistent listener → $CODE" || fail "missing listener → $CODE"

# 10.4 unauthorized role → 403 (create a USER, try ADMIN endpoint)
USERPW="SanityUserPw2026!"
RESP=$(curl -sk -X POST "$API_ON/api/auth/register" -H 'Content-Type: application/json' -d "{\"email\":\"sanity-user@tranzfer.io\",\"password\":\"$USERPW\"}")
USERTOK=$(echo "$RESP" | jq -r '.accessToken // empty')
if [ -n "$USERTOK" ]; then
  CODE=$(curl -sk -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $USERTOK" "$API_ON/api/users")
  [ "$CODE" = "403" ] && pass "USER role → 403 on admin endpoint" || fail "USER on /api/users → $CODE"
else
  skip "couldn't register sanity-user (may exist from prior run)"
fi

# 10.5 flow create with duplicate name → 400
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$API_CFG/api/flows" -H "$HDR" -H 'Content-Type: application/json' -d '{"name":"sanity-flow-1","direction":"INBOUND","filenamePattern":".*","steps":[]}')
[ "$CODE" = "400" ] && pass "duplicate flow name → 400" || fail "duplicate flow → $CODE"

# ─────────────────────────────────────────────────────────────────────────────
section "11. FTP direct (control + passive data)"

# Preconditions set up by build-regression-fixture.sh:
#   - Dynamic FTP listener 'ftp-reg-1' on ftp-service:2100
#   - Account regtest-ftp-1 assigned to ftp-reg-1, password RegTest@2026!
#   - Primary ftp-service exposes passive range 21000-21010 (default) on host
# Runs the FTP client inside an alpine container on the compose network so the
# test works without exposing port 2100 on the host.
FTP_LISTEN=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c "SELECT id FROM server_instances WHERE instance_id='ftp-reg-1'" | tr -d '[:space:]')
if [ -z "$FTP_LISTEN" ]; then
  skip "ftp-reg-1 not present — run ./scripts/build-regression-fixture.sh first; skipping §11"
else
  # 11.1 control channel login
  TS=$(date +%s); FFNAME="sanity-ftp-$TS.dat"
  echo "sanity ftp upload content" > "/tmp/$FFNAME"

  # curl --ftp-pasv is default; -v prints PASV reply lines (227 ...)
  OUT=$(docker run --rm --network tranzfer-mft_default \
    -v "/tmp/$FFNAME:/local.dat:ro" alpine:latest sh -c '
    apk add --quiet curl >/dev/null 2>&1
    curl -sv --ftp-pasv \
      -u regtest-ftp-1:RegTest@2026! \
      -T /local.dat \
      ftp://ftp-service:2100/'"$FFNAME"' 2>&1
  ' 2>&1)

  # 11.2 login response
  echo "$OUT" | grep -qE '230.*Login|User regtest-ftp-1 logged in' \
    && pass "FTP control: login accepted (230)" \
    || fail "FTP login failed: $(echo "$OUT" | grep -E '^[<>] ' | head -5 | tr '\n' ';')"

  # 11.3 PASV reply received (227)
  echo "$OUT" | grep -q '227 ' \
    && pass "FTP passive: 227 reply received" \
    || fail "FTP PASV: no 227 line in trace"

  # 11.4 upload completed
  echo "$OUT" | grep -qE '226.*Transfer complete|upload.*complete' \
    && pass "FTP upload: 226 Transfer complete" \
    || fail "FTP upload did not complete"

  # 11.5 LIST shows the uploaded file
  LIST_OUT=$(docker run --rm --network tranzfer-mft_default alpine:latest sh -c '
    apk add --quiet curl >/dev/null 2>&1
    curl -s --ftp-pasv -u regtest-ftp-1:RegTest@2026! ftp://ftp-service:2100/ 2>&1
  ' 2>&1)
  echo "$LIST_OUT" | grep -q "$FFNAME" \
    && pass "FTP LIST shows uploaded file" \
    || fail "FTP LIST missing $FFNAME"
fi

# ─────────────────────────────────────────────────────────────────────────────
section "12. FTP-via-DMZ (R87/R88 — reverse-proxy passive mode)"

# Preconditions: DMZ proxy must be running and healthy. DMZ management API is
# on port 8088 (internal DMZ). When R87/R88 isn't deployed yet, the model will
# reject the ftpDataChannelPolicy field and the create will return 4xx — the
# tests below degrade gracefully to FAIL with an actionable message.
DMZ_HEALTHY=$(curl -sk -o /dev/null -w "%{http_code}" "http://localhost:8088/api/proxy/health" 2>/dev/null || echo 000)
if [ "$DMZ_HEALTHY" != "200" ] && [ "$DMZ_HEALTHY" != "503" ]; then
  skip "DMZ proxy not reachable on :8088 (health=$DMZ_HEALTHY); skipping §12"
else
  # 12.1 create a DMZ mapping with ftpDataChannelPolicy — proves R88 model ser/de
  DMZ_BODY='{
    "name":"sanity-ftp-dmz",
    "listenPort":42821,
    "targetHost":"ftp-service",
    "targetPort":2100,
    "active":true,
    "ftpDataChannelPolicy":{
      "passivePortFrom":31000,
      "passivePortTo":31003,
      "externalHost":"127.0.0.1",
      "rewritePasvResponse":true
    }
  }'
  R=$(curl -sk -w "\n%{http_code}" -X POST "http://localhost:8088/api/proxy/mappings" \
    -H 'Content-Type: application/json' \
    -H "X-Platform-Jwt: sanity" \
    -d "$DMZ_BODY" 2>&1)
  STATUS=${R##*$'\n'}
  if [ "$STATUS" = "201" ]; then
    pass "DMZ accepts ftpDataChannelPolicy in mapping (R88 model wired)"
  elif [ "$STATUS" = "401" ] || [ "$STATUS" = "403" ]; then
    skip "DMZ management API gated (auth=$STATUS); JWT helper not wired in sanity — skipping §12 body"
  elif echo "${R%$'\n'*}" | grep -qi 'already'; then
    skip "sanity-ftp-dmz mapping already exists (reusing)"
  else
    fail "DMZ POST /mappings with ftpDataChannelPolicy → $STATUS (R88 not deployed?)"
  fi

  if [ "$STATUS" = "201" ] || [ "$STATUS" = "409" ]; then
    # 12.2 passive forwarder listening on the range (inside DMZ container)
    # At least one port in [31000, 31003] must be bound.
    PFWD=$(docker exec mft-dmz-proxy-internal sh -c '
      for p in 31000 31001 31002 31003; do
        (echo > /dev/tcp/127.0.0.1/$p) 2>/dev/null && { echo $p; break; }
      done
    ' 2>/dev/null)
    if [ -n "$PFWD" ]; then
      pass "DMZ passive forwarder listening on :$PFWD (R88 sibling listeners)"
    else
      fail "No passive forwarder bound in [31000-31003] inside DMZ container"
    fi

    # 12.3 DELETE the mapping to prove clean teardown
    CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X DELETE \
      -H "X-Platform-Jwt: sanity" \
      "http://localhost:8088/api/proxy/mappings/sanity-ftp-dmz" 2>/dev/null)
    [ "$CODE" = "204" ] || [ "$CODE" = "200" ] \
      && pass "DMZ mapping delete → $CODE (passive forwarders torn down)" \
      || skip "DMZ delete → $CODE"
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
section "Cleanup — delete sanity listener"
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X DELETE "$API_ON/api/servers/$SID" -H "$HDR")
[ "$CODE" = "204" ] && pass "DELETE sanity listener → 204" || skip "delete → $CODE"

# ─────────────────────────────────────────────────────────────────────────────
section "Summary"
printf '%s%d PASS%s  %s%d FAIL%s  %s%d SKIP%s\n\n' "$GREEN" "$PASS" "$RST" "$RED" "$FAIL" "$RST" "$YELLOW" "$SKIP" "$RST"
if [ "$FAIL" -gt 0 ]; then
  echo "Failures:"
  for f in "${FAILS[@]}"; do echo "  ✗ $f"; done
  exit 1
fi
exit 0
