# TranzFer MFT — Post-Deploy Checklist

**Purpose:** Run after every `docker compose up -d` on fresh deploy or nuke+rebuild.  
**Why:** Several components need manual initialization that isn't automated yet.  
**Target:** Automate ALL of these into docker-compose or init containers.

---

## Required Steps (Until Permanently Fixed)

### 1. Apply Missing Flyway Migrations

db-migrate container only runs up to V56. V58-V63 must be applied manually:

```bash
# Run from project root
for f in V58__partner_unique_company_name.sql V59__sentinel_and_storage_tables.sql; do
  docker exec -i mft-postgres psql -U postgres -d filetransfer < shared/shared-platform/src/main/resources/db/migration/$f
done
docker exec mft-postgres psql -U postgres -d filetransfer -c \
  "ALTER TABLE file_transfer_records ADD COLUMN IF NOT EXISTS destination_account_id UUID;"
docker exec -i mft-postgres psql -U postgres -d filetransfer < shared/shared-platform/src/main/resources/db/migration/V61__activity_materialized_view.sql
docker exec -i mft-postgres psql -U postgres -d filetransfer < shared/shared-platform/src/main/resources/db/migration/V62__query_timeout_protection.sql
docker exec -i mft-postgres psql -U postgres -d filetransfer < shared/shared-platform/src/main/resources/db/migration/V63__partition_transfer_records.sql
```

**Permanent fix:** Update db-migrate container to include ALL shared migrations.

### 2. Create RabbitMQ Exchanges

```bash
docker exec mft-rabbitmq rabbitmqadmin declare exchange name=file-transfer.events type=topic durable=true
docker exec mft-rabbitmq rabbitmqadmin declare exchange name=file-transfer.events.dlx type=topic durable=true
```

**Permanent fix:** Add `definitions.json` to RabbitMQ container config.

### 3. Flush Redis Cache

```bash
docker exec mft-redis redis-cli FLUSHALL
```

**Why:** `@Cacheable` on `ResponseEntity` poisons the cache on first call. Must flush after every restart until the code is fixed to cache `Map` instead of `ResponseEntity`.

**Permanent fix:** Change `FlowExecutionController.getLiveStats()` to cache the body, not the wrapper.

### 4. Verify All Services Healthy

```bash
echo "$(docker ps --format '{{.Status}}' | grep -c healthy) / $(docker ps -q | wc -l) healthy"
```

Wait until 34/35 (promtail has no healthcheck).

### 5. Verify API Access

```bash
curl -sk https://localhost/api/auth/login -X POST \
  -H "Content-Type: application/json" \
  -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}'
```

Should return `{"accessToken":"..."}`. If returns 429, recreate onboarding-api:
```bash
docker compose stop onboarding-api && docker compose rm -f onboarding-api && docker compose up -d onboarding-api
```

---

## Known Issues (Do Not Repeat)

| Issue | Trigger | Recovery | Permanent Fix |
|-------|---------|----------|---------------|
| 429 rate limit lockout | >20 login attempts/min from same IP | Recreate onboarding-api container | Move rate limiter to Redis, increase limit to 100/min |
| Redis cache poison (live-stats 500) | Any call to /api/flow-executions/live-stats | `docker exec mft-redis redis-cli FLUSHALL` | Cache Map body, not ResponseEntity |
| RabbitMQ exchange missing | Fresh deploy | `rabbitmqadmin declare exchange` | definitions.json in RabbitMQ container |
| File pipeline disconnected | Always (lazy-init=true) | None — requires code fix | Remove lazy-init from JAVA_TOOL_OPTIONS or use excludes |
| CORS 403 on non-onboarding services | Browser sends Origin: https://localhost | nginx strips Origin header (our fix) | Add CORS to shared SecurityConfig |
| Gateway/DMZ/Cluster page crash | Navigate to these pages | Don't navigate there | Fix Vite circular dependency |
| Flows modal ignores Escape | Press Escape on flow modal | Click X button instead | Wire onClose handler |
| V58-V63 migrations missing | Fresh deploy | Run manually (see above) | Update db-migrate classpath |
