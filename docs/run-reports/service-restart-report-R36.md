# TranzFer MFT — Service Restart & Exit Report (R36)

**Date:** 2026-04-15
**Build:** R36 (latest main)

---

## Critical: 4 Services Exited and Did Not Restart

| Service | Exit Code | Exited At | Impact |
|---------|-----------|-----------|--------|
| **api-gateway (nginx)** | 0 | 28 min after start | ALL HTTP access lost — no API, no UI |
| **ui-service** | 0 | 27 min after start | Admin UI unavailable |
| **partner-portal** | 0 | 27 min after start | Partner portal unavailable |
| **ftp-web-ui** | 0 | 28 min after start | File portal unavailable |

These are nginx-based containers that ran for ~10 minutes then exited cleanly (code 0). They do NOT have `restart: unless-stopped` policy, so Docker does not restart them.

**This is unacceptable.** These containers must never exit. If they do, they must auto-restart.

**Fix:** Add `restart: unless-stopped` to api-gateway, ui-service, partner-portal, and ftp-web-ui in docker-compose.yml. Or investigate why nginx exits after 10 minutes — likely a signal from Docker or a dependent service going unhealthy.

## Logs from api-gateway Before Exit

nginx started normally, served requests, then exited. Last log shows upstream connection refused errors (backend services still booting when gateway tried to proxy). The gateway may have received a SIGTERM from Docker's dependency management when a dependent service was marked unhealthy.

## Services That Should Never Restart Without Operator Action

Every service should boot once and stay running. The current state:
- 17 Java services: all healthy, 0 restarts this build
- 4 nginx services: exited, no restart
- Init containers (spire-init, vault-init, db-migrate): exited normally — expected

## Recommendation

1. Add `restart: unless-stopped` to ALL service definitions in docker-compose.yml
2. This ensures any container that exits (cleanly or not) restarts automatically
3. Combined with `start_period: 600s`, services have time to boot without being killed
4. Only `docker compose down` should stop services — never auto-exit
