# TranzFer MFT — Claude Code Instructions

## User
Roshan Dubey — CTO/founder. Deep Java 21/Spring Boot expertise. Built all 19 services. Production-grade quality, no shortcuts.

## Guiding Principle: Triangle Center
Speed | Security | Stability+Consistency — never sacrifice one for another.

## Workflow (non-negotiable)
1. Code → `mvn compile -q` → **commit+push immediately** → test → fix in follow-up
2. Test ALL services: `mvn test 2>&1 | grep -E 'Tests run|BUILD|FAILURE' | tail -30`
3. Code change = doc update (demos/, standalone-products/, README, API-REFERENCE, SERVICES, CONFIGURATION)

## Context Efficiency (non-negotiable)
- **Grep first** → get line numbers → `Read` with offset/limit (NEVER read full files for small edits)
- **Explore subagent** for any multi-file search — keeps results out of main context
- **Never re-read** files already in context. Never read after editing.
- **`replace_all: true`** for renames — skip reading if the old/new strings are unambiguous
- **Parallel tool calls** — batch all independent reads, all independent edits, always
- **Compact test output** — always pipe mvn through grep/tail

## Port Map
8080=onboarding  8081=sftp(+2222)  8082=ftp(+21)  8083=ftp-web  8084=config
8085=gateway(+2220/2121)  8086=encryption  8087=forwarder  8088=dmz(NO DB)
8089=license  8090=analytics  8091=ai-engine  8092=screening  8093=keystore
8094=as2  8095=edi-converter(NO DB)  8096=storage-manager  8097=notification  8098=sentinel

## JDK 25 — Cannot Mock (use real instances/stubs):
AuthService(stub) | JwtUtil(real) | TrackIdGenerator(reflection) | ClusterService(real)
RabbitTemplate(real) | AesService(real) | MdnGenerator(real) | SshServer(real)
All repositories are interfaces → safe to @Mock

## Surefire Args (never remove):
--add-opens java.base/java.lang=ALL-UNNAMED +reflect +util +invoke +io, -XX:+EnableDynamicAgentLoading

## Architecture
- 22 modules, shared library with 32 JPA entities
- REST via ResilientServiceClient (circuit breaker+retry)
- **Service identity**: SPIFFE/SPIRE — REQUIRED (X-Internal-Key removed; SPIFFE is the only inter-service auth)
  - Default ON: `SPIFFE_ENABLED=true` in docker-compose. Fresh install: fully automatic via spire-init container.
  - Services self-heal: `SpiffeWorkloadClient` retries SPIRE connection every 15s until agent is available.
  - Key classes: `SpiffeWorkloadClient` (shared-core), `SpiffeProxyAuth` (dmz-proxy), `SpiffeX509Manager` (shared-core)
  - `PlatformJwtAuthFilter` validates 3 paths: mTLS peer cert (path 0) → SPIFFE JWT-SVID (path 1) → Platform JWT (path 2)
  - `BaseServiceClient` auto-attaches JWT-SVID (Phase 1) OR skips header for https:// URLs (Phase 2 mTLS)
  - Phase 1 (default): JWT-SVIDs cached with proactive 50% TTL refresh — zero per-request SPIRE calls on hot path
  - Phase 2 (opt-in): `spiffe.mtls-enabled=true` + `https://` service URLs → X.509-SVID in TLS channel, Apache HC5 pool
- JWT for user auth (admin UI / partner portal / CLI)
- RabbitMQ: exchange=file-transfer.events, binding=account.*
- Fail-fast: config, encryption, screening, storage
- Graceful degradation: keystore(local fallback), ai-engine(ALLOWED), license(24h cache), analytics(empty)
- Security tiers: RULES / AI / AI_LLM (per-listener on DMZ proxy)

## SPIFFE/SPIRE Infrastructure (Docker Compose)
- SPIRE Server + Agent: always-on (no profile gate) — start with `docker compose up -d`
- SPIRE Agent socket: /run/spire/sockets/agent.sock (volume: spire-socket, mounted read-only into all services)
- Trust domain: filetransfer.io
- **Fresh install:** `docker compose up -d` — fully automatic. spire-init container generates token + registers all services.
- **Subsequent restarts:** `docker compose up -d` (bootstrap data persists in spire-init-data volume)
- Production / Kubernetes: use Red Hat SPIRE Operator (no static join tokens, automatic attestation)

## Pending (do NOT start unless explicitly asked)
- 9JaGo rebrand (rename from TranzFer MFT)
