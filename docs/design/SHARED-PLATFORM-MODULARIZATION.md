# Shared-Platform Modularization Plan

**Date:** 2026-04-13  
**Goal:** Boot time 170s → 25-30s by splitting 63 entities into focused modules  
**Principle:** Each service scans only the entities it uses. No shortcuts.

---

## The Problem

Every service loads `shared-platform.jar` which contains **63 JPA entities**. Hibernate scans and validates ALL of them at boot — even if the service only uses 5. This takes ~90 seconds per service.

```
sftp-service needs:     TransferAccount, ServerInstance, AuditLog, SecurityProfile, LoginAttempt = 5 entities
sftp-service scans:     ALL 63 entities = 90 seconds wasted on 58 unused entities
```

---

## The Solution: 5 Focused Modules

Based on entity usage analysis across all 17 DB services:

### Module 1: `shared-core-entities` (10 entities) — Every DB service needs these

```
TransferAccount          — used by 8 services
ServerInstance           — used by 6 services
AuditLog                 — used by 5 services
User                     — used by 3 services
Partner                  — used by 3 services
Tenant                   — used by 1 but referenced everywhere
ServiceRegistration      — used by 3 services
PlatformSetting          — used by 2 services
ClusterNode              — used by 1 but cluster-aware services need it
SecurityProfile          — used by 3 services
```

**Scan time:** ~10 entities × ~1s = **~10 seconds**

### Module 2: `shared-transfer-entities` (15 entities) — File pipeline services

```
FileTransferRecord       — used by 4 services
FileFlow                 — used by 3 services
FlowExecution            — used by 3 services
FlowStepSnapshot         — used by 2 services
FlowApproval             — used by 1 service
FlowEvent                — used by 1 service
FolderMapping            — used by 1 service
FolderTemplate           — used by 2 services
FabricCheckpoint         — used by 1 service
FabricInstance           — used by 1 service
DeliveryEndpoint         — used by 3 services
ExternalDestination      — used by 2 services
TransferActivityView     — used by 1 service (mat view, read-only)
DeadLetterMessage        — used by 2 services
TransferTicket           — used by 1 service
```

**Scan time:** ~15 entities × ~1s = **~15 seconds**

### Module 3: `shared-vfs-entities` (4 entities) — Virtual filesystem services

```
VirtualEntry             — used by 2 services
VfsIntent                — used by 1 service
VfsChunk                 — used by 1 service
ChunkedUpload            — used by 1 service (ftp-web)
ChunkedUploadChunk       — used by 1 service
```

**Scan time:** ~5 entities × ~1s = **~5 seconds**

### Module 4: `shared-security-entities` (8 entities) — Auth/compliance/screening

```
EncryptionKey            — used by 2 services
DlpPolicy                — used by 1 service
QuarantineRecord         — used by 2 services
ComplianceProfile        — used by 1 service
ComplianceViolation      — used by 1 service
ListenerSecurityPolicy   — used by 1 service
LoginAttempt             — used by 2 services
TotpConfig               — used by 1 service
```

**Scan time:** ~8 entities × ~1s = **~8 seconds**

### Module 5: `shared-integration-entities` (10 entities) — Protocol/notification/webhook

```
As2Message               — used by 2 services
As2Partnership           — used by 2 services
NotificationLog          — used by 1 service
NotificationRule         — used by 1 service
NotificationTemplate     — used by 1 service
WebhookConnector         — used by 1 service
PartnerWebhook           — used by 1 service
PartnerAgreement         — used by 1 service
PartnerContact           — used by 1 service
ScheduledTask            — used by 1 service
```

**Scan time:** ~10 entities × ~1s = **~10 seconds**

### Entities staying in shared-platform (16 entities) — Admin/misc

```
BlockchainAnchor, AutoOnboardSession, ClientPresence, ConnectionAudit,
FunctionQueue, LegacyServerConfig, MigrationEvent, Permission,
ProxyGroup, RolePermission, ServerAccountAssignment, ServerConfig,
UserPermission, ActivityEvent, SlaAgreement, MfaConfig
```

These are only used by onboarding-api or config-service (the admin services).

---

## Which Service Gets Which Modules

| Service | core | transfer | vfs | security | integration | Total Entities |
|---------|:----:|:--------:|:---:|:--------:|:-----------:|:--------------:|
| **sftp-service** | Y | Y | Y | - | - | **30** |
| **ftp-service** | Y | Y | Y | - | - | **30** |
| **ftp-web-service** | Y | Y | Y | - | - | **30** |
| **gateway-service** | Y | Y | - | Y | - | **33** |
| **onboarding-api** | Y | Y | Y | Y | Y | **ALL 63** |
| **config-service** | Y | Y | Y | Y | Y | **ALL 63** |
| **encryption-service** | Y | - | - | Y | - | **18** |
| **forwarder-service** | Y | Y | - | - | Y | **35** |
| **license-service** | Y | - | - | - | - | **10** |
| **analytics-service** | Y | Y | - | - | - | **25** |
| **ai-engine** | Y | Y | - | - | - | **25** |
| **screening-service** | Y | - | - | Y | - | **18** |
| **keystore-manager** | Y | - | - | - | - | **10** |
| **as2-service** | Y | Y | - | - | Y | **35** |
| **storage-manager** | Y | - | Y | - | - | **15** |
| **notification-service** | Y | - | - | - | Y | **20** |
| **platform-sentinel** | Y | Y | - | Y | - | **33** |

### Boot time projections

| Service | Current (63 entities) | After (focused modules) | Savings |
|---------|:--------------------:|:-----------------------:|:-------:|
| license-service | 170s | **~20s** | 150s |
| keystore-manager | 170s | **~20s** | 150s |
| encryption-service | 180s | **~25s** | 155s |
| screening-service | 170s | **~25s** | 145s |
| storage-manager | 184s | **~22s** | 162s |
| notification-service | 180s | **~25s** | 155s |
| analytics-service | 20s | **~18s** | 2s |
| sftp-service | 180s | **~28s** | 152s |
| ai-engine | 186s | **~28s** | 158s |
| onboarding-api | 22s | **~22s** | 0s (already fast, loads all) |
| config-service | 20s | **~20s** | 0s (already fast, loads all) |

---

## Implementation Plan

### Phase 1: Create Module POMs (1 day)

```
shared/
├── shared-core/                    (already exists — DTOs, clients, utils)
├── shared-core-entities/           (NEW — 10 base entities + repositories)
├── shared-transfer-entities/       (NEW — 15 transfer entities + repositories)
├── shared-vfs-entities/            (NEW — 5 VFS entities + repositories)
├── shared-security-entities/       (NEW — 8 security entities + repositories)
├── shared-integration-entities/    (NEW — 10 protocol/notification entities + repos)
├── shared-platform/                (SLIMMED — remaining 16 admin entities + all config/health/routing)
└── shared-fabric/                  (already exists — Kafka client)
```

Each new module is a Maven sub-module with:
- Entity classes (moved from shared-platform)
- Repository interfaces (moved from shared-platform)
- Flyway migration paths stay in shared-platform (migrations are universal)
- Zero Spring configuration — just entities + repos

### Phase 2: Move Entities (2-3 days)

For each entity:
1. `git mv` from `shared-platform/entity/X.java` to `shared-core-entities/entity/X.java`
2. Update package declaration
3. Move matching repository interface
4. Update imports in all services that use it

### Phase 3: Update Service POMs (1 day)

Each service's `pom.xml` adds only the modules it needs:

```xml
<!-- sftp-service/pom.xml -->
<dependencies>
    <dependency>
        <groupId>com.filetransfer</groupId>
        <artifactId>shared-core-entities</artifactId>
    </dependency>
    <dependency>
        <groupId>com.filetransfer</groupId>
        <artifactId>shared-transfer-entities</artifactId>
    </dependency>
    <dependency>
        <groupId>com.filetransfer</groupId>
        <artifactId>shared-vfs-entities</artifactId>
    </dependency>
    <!-- NOT: shared-security-entities, shared-integration-entities -->
</dependencies>
```

### Phase 4: Update @EntityScan (1 day)

Each service's Application class scans only its modules:

```java
@SpringBootApplication
@EntityScan(basePackages = {
    "com.filetransfer.shared.core.entity",
    "com.filetransfer.shared.transfer.entity",
    "com.filetransfer.shared.vfs.entity"
})
public class SftpServiceApplication { ... }
```

### Phase 5: Test + Verify (2-3 days)

- `mvn compile` — all 22 services
- `mvn test` — all 3,852 tests pass
- Boot time measurement per service
- Full pipeline validation (SFTP upload → routing → Activity Monitor)

---

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| Entity A references Entity B across modules | Both entities in same module, or use UUID foreign key (no JPA @ManyToOne cross-module) |
| Circular dependency between modules | Entities that reference each other must be in the same module (already accounted for in grouping above) |
| Migration files reference entities from multiple modules | Migrations stay in shared-platform — they're SQL, not JPA |
| RoutingEngine uses entities from multiple modules | shared-platform depends on ALL entity modules (it's the orchestrator) |
| Test failures from missing entities | @EntityScan explicitly lists packages — compile error if entity missing |

---

## What NOT to Do

| Approach | Why wrong |
|----------|-----------|
| `@EntityScan` with exclude patterns | Fragile — new entities auto-included |
| `SelectiveEntityScanConfig` via YAML | Already tried, abandoned — too complex |
| Remove shared-platform dependency entirely | Breaks RoutingEngine, FlowProcessingEngine |
| Copy entities into each service | Maintenance nightmare — 17 copies of TransferAccount |

---

## Timeline

| Week | Task | Output |
|------|------|--------|
| 1 (Days 1-2) | Create 5 new Maven modules, move entities + repos | Compiles, no service changes yet |
| 1 (Days 3-5) | Update all 17 service POMs + @EntityScan | All services compile with focused scans |
| 2 (Days 1-3) | Full test suite + boot time measurement | 3,852 tests pass, boot times verified |
| 2 (Days 4-5) | Pipeline validation + tester handoff | SFTP → routing → Activity Monitor works |

**Total: 8-10 days, 1 developer. Boot time: 170s → 20-30s.**

---

## Schema Validation — The Right Way

With modularization, each service scans fewer entities. But we still need to know if the schema is correct.

**Current approach (correct, keep it):**
1. `hibernate.ddl-auto=none` — skip validation at boot (fast)
2. `SchemaHealthIndicator` — background validation at 30s mark, then every 5 min
3. `/actuator/health/schema` — reports validation status
4. Flyway owns the schema — migrations are the source of truth

**Enhancement for modularized world:**
- SchemaHealthIndicator validates only the entities THIS service scans
- If a service doesn't scan `NotificationLog`, it doesn't validate that table
- Reduces background validation time proportionally

**No shortcuts. No corruption. Just fast boot + async validation.**
