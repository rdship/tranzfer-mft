# Flyway Migration Range Allocation

**Problem we hit:** Multiple modules ship Flyway migrations on the same classpath
and share a single `flyway_schema_history` table. When two modules independently
used the same version number (e.g. `shared-platform/V64__dynamic_listeners.sql`
and `platform-sentinel/V64__sentinel_rules_builtin.sql`), Flyway applied whichever
one it encountered first and silently skipped the other — made more dangerous by
the `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false` override that hides checksum errors.

**Result:** a feature migration could be written, merged, tagged "applied" in the
tracker, and never actually land on the database. That's how R68's Sentinel
`listener_bind_failed` rule stayed inert until we inline-seeded it via
`BuiltinRuleSeeder`.

## Allocated Ranges

Each module owns a disjoint version range. New migrations use the next free
integer inside the owning module's range. Never reuse a number from another
module's range, even if it's "unoccupied."

| Range      | Module              | Notes |
|-----------:|---------------------|-------|
|     1–199  | `shared/shared-platform` | Current head: V64. Ample headroom. |
|   200–299  | `platform-sentinel` | Currently V200, V201, V202. |
|   300–399  | (reserved) `storage-manager` | Empty today; reserve for its future migrations. |
|   400–499  | (reserved) `keystore-manager` | Empty today. |
|   500–599  | (reserved) `edi-converter` | Empty today. |
|   600–699  | (reserved) `encryption-service` | Empty today. |
|   700–799  | (reserved) `analytics-service` | Empty today. |
|   800–899  | (reserved) `ai-engine` | Empty today. |
|   900–999  | (reserved) `license-service` | Empty today. |
| 1000–1099  | (reserved) `external-forwarder-service` | Empty today. |
| 1100–1199  | (reserved) `notification-service` | Empty today. |
| 1200–1299  | (reserved) `screening-service` | Empty today. |
| 1300–1399  | (reserved) `onboarding-api`-specific | Only if it ever needs its own migrations beyond what shared-platform provides. |

Services that don't run Flyway themselves (because `SPRING_FLYWAY_ENABLED=false`
in `&common-env`, with db-migrate running centrally) still MUST respect the
range when they ship migration SQL — all modules' migrations land in the same
`flyway_schema_history` regardless of who triggers them.

## Rules

1. **Never take a number from a sibling module's range.** If `shared-platform`
   needs V65, that's V65. If `platform-sentinel` needs its 4th migration, that's
   V203, not V65.
2. **Never change a committed migration's version.** Once V200 is merged, you
   cannot rename it to V210 later — that breaks `flyway_schema_history`
   checksums on every existing DB.
3. **Idempotent DDL by default.** Every new migration should use
   `CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, and
   `INSERT ... ON CONFLICT DO NOTHING` where practical, so cross-module replays
   are safe during rollout.
4. **SPRING_FLYWAY_VALIDATE_ON_MIGRATE stays off only while it's load-bearing
   for the V42 CONCURRENTLY workaround.** Once that workaround lands properly,
   flip validation back on. Range discipline plus validation-on is the
   end-state.

## How the platform-sentinel rename happened

Original:
- `platform-sentinel/V64__sentinel_rules_builtin.sql`
- `platform-sentinel/V65__sentinel_tables.sql`
- `platform-sentinel/V66__listener_bind_failed_rule.sql`

Collided with:
- `shared/shared-platform/V64__dynamic_listeners.sql`

Renamed to:
- `platform-sentinel/V200__sentinel_rules_builtin.sql`
- `platform-sentinel/V201__sentinel_tables.sql`
- `platform-sentinel/V202__listener_bind_failed_rule.sql`

For fresh databases: V200/V201/V202 run in order, no collision.

For databases where `shared-platform/V64` was already applied (the historical
"winner"): V200/V201/V202 are treated as new migrations. Their contents are
idempotent (`CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`,
`INSERT ... ON CONFLICT DO NOTHING`), so they run cleanly and backfill any
missing columns/rows.

For databases where (extremely unlikely) the sentinel V64/V65 previously won:
the old history rows sit harmlessly under version 64/65; V200+ applies on top,
no data loss. Validation is still off, so no checksum failures.
