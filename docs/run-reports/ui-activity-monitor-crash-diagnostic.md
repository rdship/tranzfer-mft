# UI Activity Monitor Crash — Full Diagnostic

**Date:** 2026-04-16  
**URL:** `https://localhost/operations/activity`  
**Error:** `Cannot access 'he' before initialization`  
**Browser:** Chrome 147.0.0.0 on macOS  
**Tested:** Incognito + Cmd+Shift+R — crash persists  

---

## The API Works — Only the UI Renderer Crashes

```bash
curl -s https://localhost/api/activity-monitor -k -H "Authorization: Bearer $TOKEN"
# Returns 200 with 14 entries, flow names, track IDs, all data present
```

The backend is fully functional. This is purely a frontend JavaScript bundle issue.

---

## Error

The Vite production build has a **Temporal Dead Zone (TDZ)** error — a minified variable (`he`) is accessed before its `let`/`const` declaration. This happens when ES module chunks have circular import dependencies that create initialization order issues.

---

## Chunk Load Sequence (from nginx access log)

When the user navigates to `/operations/activity`, the browser loads:

```
1. index-DM5ZKfTl.js        (110.6K) — main app + router
2. shared-api-DLaKGoBm.js   (23.6K)  — API clients (axios instances)
3. shared-context-4A_e4Nmh.js (6.1K) — AuthContext, ServiceContext
4. shared-hooks-DKQWFRqf.js  (6.4K)  — useStickyFilters, etc.
5. shared-components-DNA2hr4b.js (252.4K) — CopyButton, Modal, Skeleton, etc.
6. ActivityMonitor-C43uCqL2.js (57.3K) — the page component (CRASH HERE)
```

The crash happens when `ActivityMonitor-C43uCqL2.js` loads and tries to access a variable from one of the shared chunks that hasn't finished initializing.

---

## Circular Dependency Chain

```
shared-context → imports from shared-api (onboardingApi)
shared-hooks → imports from shared-components (component V)
shared-components → imports from shared-hooks? OR shared-api?
index → imports from all shared-* chunks
ActivityMonitor → imports from shared-api, shared-context, shared-hooks, shared-components
```

**The cycle:**
```
shared-hooks imports from shared-components:
  import{v as I}from"./shared-components-DNA2hr4b.js"

shared-components may import from shared-hooks or shared-api
  → creates circular: hooks ↔ components
```

When `ActivityMonitor` triggers the load of both `shared-hooks` and `shared-components`, the initialization order is non-deterministic. If `shared-hooks` loads first and tries to access `shared-components` export `v` (minified to `I`), but `shared-components` hasn't finished its module-level execution yet, the TDZ error occurs.

---

## ActivityMonitor Import Chain

```javascript
// ActivityMonitor.jsx imports:
import { onboardingApi, configApi } from '../api/client'           // → shared-api
import { useAuth } from '../context/AuthContext'                    // → shared-context → shared-api
import CopyButton from '../components/CopyButton'                  // → shared-components
import useStickyFilters from '../hooks/useStickyFilters'           // → shared-hooks → shared-components (CIRCULAR)
import Modal from '../components/Modal'                            // → shared-components
import LoadingSpinner from '../components/LoadingSpinner'          // → shared-components
import Skeleton from '../components/Skeleton'                      // → shared-components
import ConfigLink from '../components/ConfigLink'                  // → shared-components
import ConfirmDialog from '../components/ConfirmDialog'            // → shared-components
import { LazyExecutionDetailDrawer, ... } from '../components/LazyShared'  // → shared-components (lazy)
import { getFabricQueues, ... } from '../api/fabric'              // → shared-api
```

---

## Vite manualChunks Configuration (Current)

```javascript
manualChunks(id) {
  if (!id.includes('node_modules')) {
    if (id.includes('/components/')) return 'shared-components'
    if (id.includes('/hooks/'))      return 'shared-hooks'
    if (id.includes('/api/'))        return 'shared-api'
    if (id.includes('/context/'))    return 'shared-context'
    return undefined  // ← pages stay in their own lazy chunks
  }
  // vendor chunks...
}
```

**Problem:** This puts ALL components in one chunk and ALL hooks in another. If any hook imports a component (or vice versa), the two chunks have a circular dependency.

---

## Proven Circular Reference

From the minified chunk analysis:

```
shared-hooks-DKQWFRqf.js:
  import{v as I}from"./shared-components-DNA2hr4b.js"
  ↑ hooks chunk imports from components chunk

shared-context-4A_e4Nmh.js:
  import{o as y}from"./shared-api-DLaKGoBm.js"
  ↑ context imports from api (this is fine — one-directional)
```

**The fix needs to break the `shared-hooks → shared-components` cycle.**

---

## Recommended Fix Options

### Option 1: Merge hooks + components into ONE shared chunk
```javascript
if (id.includes('/components/') || id.includes('/hooks/')) return 'shared-app'
```
No circular dep if they're in the same chunk. Tradeoff: larger single chunk.

### Option 2: Move the offending import
Find which hook imports which component and refactor to remove the cross-chunk dependency. The hook should accept the component as a parameter instead of importing it.

### Option 3: Dynamic import inside the hook
```javascript
// Instead of:
import { SomeComponent } from '../components/SomeComponent'

// Use:
const SomeComponent = React.lazy(() => import('../components/SomeComponent'))
```

---

## Files to Examine

| File | Role | Issue |
|------|------|-------|
| `ui-service/vite.config.js` | Chunk splitting | manualChunks creates the cycle |
| `ui-service/src/hooks/*.js` | Hooks | One or more imports from `/components/` |
| `ui-service/src/components/*.jsx` | Components | May import from `/hooks/` |
| `ui-service/src/pages/ActivityMonitor.jsx` | Page | Triggers both chunks to load |

---

## Reproduction

1. `docker compose up -d` (full platform)
2. Open `https://localhost` in Chrome incognito
3. Login as `superadmin@tranzfer.io` / `superadmin`
4. Click "Activity Monitor" in sidebar
5. Page shows: "This page crashed — Cannot access 'he' before initialization"

Other pages (Partners, Accounts, Servers, Flows, etc.) work fine — they don't trigger both `shared-hooks` and `shared-components` in a way that hits the circular init.

---

## Update: Chunk Fix Applied But Crash Persists

CTO merged `shared-hooks` + `shared-components` into `shared-app` chunk. No more cross-chunk cycle between hooks and components.

**Current chunk structure (clean):**
```
shared-api    → no shared-* imports
shared-app    → imports shared-api, shared-context (one-directional)
shared-context → imports shared-api (one-directional)
index         → imports shared-app, shared-api, shared-context
ActivityMonitor → imports shared-app, shared-api, shared-context
```

No circular imports detected between chunks. But the crash persists.

**Hypothesis:** The cycle may be WITHIN the `shared-app` chunk — between components and hooks that are now in the same file but still have circular `import` statements at the module level. Vite bundles them into one chunk but the ESM initialization order within the chunk can still cause TDZ if module A's top-level code references module B's export before B's declaration runs.

**Dev needs to:**
1. Build with `vite build --sourcemap` and check Chrome DevTools for the exact line
2. OR run `npx madge --circular ui-service/src/` to find file-level circular imports
3. The variable `he` in the error is a minified name — source maps will reveal the real variable

---

## ROOT CAUSE FOUND — Source Code Hook Order Bug

**File:** `ui-service/src/pages/ActivityMonitor.jsx`

**The bug:** `useEffect` at line 667-736 references `restartOneMut` in its dependency array (line 736), but `const restartOneMut = useMutation(...)` is declared at **line 829** — 93 lines later.

**Minified evidence:**
```
Position 3977: [$, y, d, he]  ← useEffect deps array, 'he' = restartOneMut
Position 6426: he=re({mutationFn:t=>L.post('/api/flow-executions/${t}/restart'...)})  ← declaration
```

Usage at 3977, declaration at 6426 → TDZ error.

**Chrome stack trace confirms:**
```
ReferenceError: Cannot access 'he' before initialization
    at ia (ActivityMonitor-Bn2JVWym.js:9:3977)
```

**Fix:** Move `const restartOneMut = useMutation(...)` (currently line 829) to BEFORE the `useEffect` block (currently line 667). All `useMutation` hooks should be declared before any `useEffect` that references them.

```jsx
// BEFORE (broken):
useEffect(() => {
  // ... keyboard handler uses restartOneMut ...
}, [selectedRowIdx, rows, canOperate, restartOneMut])  // line 736

// ... 93 lines of other code ...

const restartOneMut = useMutation({ ... })  // line 829

// AFTER (fixed):
const restartOneMut = useMutation({ ... })  // MOVE HERE

useEffect(() => {
  // ... keyboard handler uses restartOneMut ...
}, [selectedRowIdx, rows, canOperate, restartOneMut])
```
