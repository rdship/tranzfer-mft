# N48 Diagnostic — Flow Matching Investigation

## Objective
Determine why `flow_executions` table has 0 rows despite 7 compiled flow rules and transfer records being created. We need to identify which service processes the FileUploadedEvent and whether its FlowRuleRegistry has rules loaded.

## Prerequisites
```bash
git pull
mvn clean package -DskipTests
docker compose build --no-cache
docker compose down -v
docker compose up -d
```

Wait for all services to boot (banner should show `v1.0.0-R63`).

## Step 1: Verify banner version
```bash
for svc in mft-sftp-service mft-config-service mft-onboarding-api; do
  echo "=== $svc ==="
  docker logs "$svc" 2>&1 | head -5
done
```
**Expected:** All show `v1.0.0-R63`. If not, rebuild was incomplete.

## Step 2: Verify FlowRuleRegistryInitializer loaded rules
```bash
for svc in mft-sftp-service mft-config-service mft-ftp-service mft-ftp-web-service mft-gateway-service; do
  echo "=== $svc ==="
  docker logs "$svc" 2>&1 | grep -i "flow rule registry\|FlowRuleRegistryInitializer\|flows compiled"
done
```
**Expected:** Services with `FLOW_RULES_ENABLED=true` should show `Flow rule registry initialized with N active flows`.

## Step 3: Verify FileUploadEventConsumer bean loaded
```bash
for svc in mft-sftp-service mft-config-service mft-ftp-service mft-ftp-web-service; do
  echo "=== $svc ==="
  docker logs "$svc" 2>&1 | grep -i "FileUploadEventConsumer\|uploadListenerFactory\|fileUploadQueue"
done
```

## Step 4: Upload a test file
```bash
echo "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*U*00401*000000001*0*P*>~GS*PO*SENDER*RECEIVER*20230101*1200*1*X*004010~ST*850*0001~BEG*00*NE*PO-001**20230101~END~SE*4*0001~GE*1*1~IEA*1*000000001~" > /tmp/test_edi_850.edi
sshpass -p 'partner123' sftp -oStrictHostKeyChecking=no -oUserKnownHostsFile=/dev/null -P 2222 acme-sftp@localhost <<< "put /tmp/test_edi_850.edi"
```
If `sshpass` is not available, use any SFTP client to upload a file to port 2222.

## Step 5: Capture diagnostic logs (CRITICAL — push these)
```bash
echo "=== FLOW MATCHING DIAGNOSTICS ==="
for svc in mft-sftp-service mft-config-service mft-onboarding-api mft-ftp-service mft-ftp-web-service mft-gateway-service; do
  echo "--- $svc ---"
  docker logs "$svc" 2>&1 | grep -E "Flow matching:|NO FLOW MATCH|EDI detected|Matched flow|Processing file upload event|FileUploadedEvent published" | tail -10
done

echo "=== RABBITMQ QUEUE STATE ==="
docker exec mft-rabbitmq rabbitmqctl list_queues name messages consumers 2>/dev/null | grep -E "file.upload|upload"

echo "=== DATABASE STATE ==="
docker exec mft-postgres psql -U postgres -d filetransfer -c "SELECT count(*) AS transfer_records FROM file_transfer_records;"
docker exec mft-postgres psql -U postgres -d filetransfer -c "SELECT count(*) AS flow_executions FROM flow_executions;"
docker exec mft-postgres psql -U postgres -d filetransfer -c "SELECT track_id, original_filename, status, flow_id FROM file_transfer_records ORDER BY created_at DESC LIMIT 5;"
docker exec mft-postgres psql -U postgres -d filetransfer -c "SELECT id, name, active, priority, filename_pattern, direction FROM file_flows WHERE active=true ORDER BY priority;"

echo "=== FLOW RULE REGISTRY METRICS ==="
# Try each routing service's rule-engine endpoint
for port in 8081 8082 8083 8084 8085; do
  resp=$(curl -s --max-time 3 "http://localhost:$port/api/rule-engine/metrics" 2>/dev/null)
  if [ -n "$resp" ] && [ "$resp" != "" ]; then
    echo "port=$port: $resp"
  fi
done
```

## Step 6: Push results
Commit and push the output as `docs/run-reports/N48-diagnostic-results.md`.

## What we're looking for
1. Which service logs `Flow matching:` — that's the service processing the event
2. What `registry.size=` and `registry.initialized=` values appear
3. Whether `NO FLOW MATCH` or `Matched flow` appears
4. Whether `EDI detected from VFS` appears (confirms VFS EDI detection works)
5. The `file_flows` table — what `filename_pattern` and `direction` each flow has
6. The `file_transfer_records` — whether `flow_id` is NULL or populated
