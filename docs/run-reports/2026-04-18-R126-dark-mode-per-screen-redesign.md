# TranzFer MFT — Dark-mode per-screen redesign (drastic)

**Companion doc** to `2026-04-18-R126-product-ui-modernization.md` (token system + bug list). This doc is a screen-by-screen **drastic** redesign in dark mode. Activity Monitor gets the deepest treatment because it's where ops lives and today it reads as "just black."

Screenshots referenced: `docs/run-reports/r126-product-ui-modernization-bundle/dark-0{1..6}-*.png`.

**Design north-star:** the **login page** (`r126-activity-monitor-ux-bundle/…` wasn't captured for login, but it's the purple-gradient + glassmorphism card). Every other dark-mode screen should feel like a sibling of the login, not a stranger.

---

## 🎯 1. Activity Monitor — drastic redesign

Current state: `dark-02-activity-monitor.png`. At 1440×900, above-the-fold shows 4 table rows. The page is dominated by chrome: tab bar + KPI strip + section header + sub-tabs + filter bar + tip banner. Nothing about the layout signals "this is the operator's command center."

### What's actively bad

1. **KPI strip at the top is generic and flat.** Four black cards in a row with pastel icons, values stranded in negative space. "IN FABRIC: 0" takes 200 px × 90 px to say "nothing is happening."
2. **"Activity Monitor | Polling (30s) | 9 transfers" header row + subtitle + 4 action buttons** consume 130 px to restate what the table already shows.
3. **Tabs "Transfers" / "Scheduled Retries" look like a second row of tabs** below the page-level tabs (Dashboard / Flow Fabric / etc.). Two rows of tabs is visual noise.
4. **Filter bar has a "FILTERS" label** taking up horizontal space. The filter inputs are self-evident.
5. **"tip: Double-click any row to open detailed view"** — instructional ribbon. Clutter.
6. **Table rows are 72 px tall.** Track ID + filename in bold, status pill below. Only 4 rows visible.
7. **Track IDs are purple hyperlink styled** but they're identifiers, not links.
8. **Status pills mix two greens** (COMPLETED is dark-emerald filled, MOVED TO SENT is lighter-emerald filled) — look identical at a glance but mean different things.
9. **Per-row action affordances are absent.** No inline download, retry, pause, or details icon. Everything requires opening a drawer.
10. **Dark background with no surface differentiation** — sidebar, content area, filter card, and table panel are all essentially `#0F0F13`.

### Drastic redesign — the "operator command console" layout

Take a page from tools like **Linear's workspace**, **Vercel's logs viewer**, **Grafana Explore**. Three-zone layout, information-dense, data-first.

```
┌────────────────────────────────────────────────────────────────────────────────────┐
│ [sidebar]  Global bar: TranzFer · search · [env chip] · profile  (bg-raised)       │
├────────────────────────────────────────────────────────────────────────────────────┤
│ [sidebar]  Activity Monitor                              [9 transfers] [Export ⋯]  │
│            Live · auto-refresh 30s · paused manually can be toggled                │
├────┬─ ◀ 68px KPI strip (collapsible) ────────────────────────────────────────────── │
│    │  In flight ·  Stuck ·  Success rate (last 5m) ·  p95 latency                  │
├────┴───────────────────────────────────────────────────────────────────────────────┤
│   ⌘K Quick filter · Status: All  ·  Protocol: All  ·  Time: last 24h  ·  Advanced │
├────────────────────────────────────────────────────────────────────────────────────┤
│   TRACK ID       Filename                         Status      Flow          Time  │
│   TRZ5UCLMYD7    r100-failed-1776529304122.dat    ● COMPLETED  regtest-f1  12:34  │
│   TRZ2NR2RRGBA   r100-failed-1776529300724.dat    ◐ MOVED      regtest-f3  12:33  │
│   TRZ2NR2RRGBA   r100-failed-1776529300724.dat    ◑ PENDING    regtest-f7  12:32  │
│   ...                                                                              │
│   (40 rows visible)                                                                │
└────────────────────────────────────────────────────────────────────────────────────┘
```

### Concrete changes

**1. Single header line** — rename "Activity Monitor" → page title only. Merge the "Polling / 9 transfers / 30s / Export / Views / Columns" cluster into one right-aligned action group:

```
Activity Monitor                                [🟢 Live · 30s]  [Export]  [Customize]
```

Drop the subtitle. Drop the "tip" banner. Drop the "Transfers | Scheduled Retries" sub-tabs — make "Scheduled Retries" a filter chip instead (`Show scheduled retries only`).

**2. KPI strip — collapsible, compact, 68 px total**

Not 4 large cards. A single row of inline stat pills:

```
  In flight 0   ·   Stuck 0   ·   Success rate 100.0% (5m)   ·   p95 latency 286ms
```

Gray text, no cards, no arrows. Tap the "KPIs" toggle in the header to hide the row entirely. Users who never look at KPIs never see them.

**3. Filter bar — single row, self-evident, auto-apply**

```
[⌘K Filter: TRZK5U…  ]  [Status ▾]  [Protocol ▾]  [Time last 24h ▾]  [+ Advanced]
```

Drop "FILTERS" label. Drop "Stuck only" checkbox (merge as a Status filter option). "Advanced" opens a drawer for the 8+ less-used filters so the power is there but not in the way.

**4. Table — compact, dense, action-rich**

- **Row height 36 px** (was 72).
- **Track ID in JetBrains Mono 12 px,** `text-primary` color, no underline. Click copies to clipboard.
- **Filename in primary-weight sans, single line**, truncates with fade-out on right.
- **Status column** uses a single `●`/`◐`/`◑`/`○` glyph (filled/half/quarter/empty) in the semantic color + short label:
  - `● COMPLETED` in `--success`
  - `◐ PROCESSING` in `--info`
  - `◑ PENDING` in `--warning`
  - `✕ FAILED` in `--danger`
  - `❚❚ PAUSED` in `--neutral`
- **Flow column** shows flow name in mono-compact, linked to Flow detail.
- **Time column** is relative (`12 min ago`) with a `title=` full timestamp on hover.
- **Row hover** reveals a right-aligned inline action group:
  ```
  [ ⬇ Download ]  [ ↻ Restart ]  [ ❚❚ Pause ]  [ ⋯ More ]
  ```
  Actions appear as icon buttons (no labels), ~28 px tall. Click outside the action zone opens the detail drawer.

**5. Detail drawer — right-side, 480 px wide, two-tab**

Slides in from the right. Two tabs: `Summary` and `Steps`. Summary shows the key fields (source, destination, SHA-256, bytes, duration). Steps shows the flow execution timeline with collapsible steps. Drawer has `bg-overlay` + 1 px strong border — floats above the table.

**6. Empty state when no transfers**

A single-panel centered state: "No transfers yet — uploads will appear here as files are processed." 80 px muted illustration. NOT the current giant hero block.

### Color mapping for Activity Monitor status glyphs (dark mode)

| State | Glyph | Semantic | Hex |
|---|---|---|---|
| COMPLETED / DOWNLOADED | `●` | success | `#22C55E` |
| PROCESSING / MOVED_TO_SENT / QUEUED | `◐` | info | `#3B82F6` |
| PENDING / AWAITING | `◑` | warning | `#F59E0B` |
| STUCK | `⚠` | warning | `#F59E0B` |
| FAILED / CANCELLED | `✕` | danger | `#EF4444` |
| PAUSED | `❚❚` | neutral-bright | `#94A3B8` |

### Information density target

- **Before (today):** 4 rows visible on 1440 × 900.
- **After:** 36 px rows × 12 px padding = 48 px. Viewport 900 − 180 (nav + header + KPI + filter) = 720 px for table. 720 ÷ 48 = **15 rows visible**. 4× improvement.

### If you only do 3 things on Activity Monitor

1. Shrink row height from 72 → 36 px and switch Track ID to mono font.
2. Merge KPI strip into one compact inline pill row, collapsible.
3. Add inline action icons on row hover (download, restart, pause, more).

These three changes alone flip the page from "cluttered admin panel" to "operator command center."

---

## 📊 2. Dashboard (`dark-01-dashboard.png`)

### What's bad

1. Two KPI strips stacked — top row (IN-PROGRESS, ACTIVE INSTANCES, STUCK, P95) + bottom row (TRANSFERS TODAY, SUCCESS RATE, DATA MOVED, LAST HOUR, PROTOCOLS ACTIVE). 9 KPI cards on one page is over-indexed on "numbers."
2. "Good morning, superadmin" is friendly but the **"All systems operational"** chip is far away from it, so the user's eye has to jump.
3. "LIVE · 0 AI agents · 0 flows processing · 2 failed" status bar is crammed — five micro-stats in one line with dots as separators.
4. "Transfer Volume" widget and "Quick Access" widget are **side-by-side equal width**, giving Quick Access too much space at the expense of the chart.
5. "Transfer Volume" chart shows "No data yet" as the dominant visual. Empty-state dominates when it should be subtle.
6. Quick Access grid (Partner Mgmt, Accounts, Flows, AS2 Partners, Journey, Audit Logs, Flow Fabric, Activity Monitor…) duplicates the sidebar nav.

### Drastic redesign

**Top section — hero health strip (single row, 80 px tall)**

```
Good morning, superadmin · Saturday, April 18                  [● All systems operational]

5 active instances  ·  0 flows in flight  ·  2 failed in last hour  ·  p95 286ms
```

One friendly greeting line, one inline stat line. No cards. Flatten the 9 KPIs into 4 that actually matter.

**Middle section — two columns, 60/40 split**

Left 60%: Transfer Volume chart (last 24h), BIG — 400 px tall. Show the chart prominently; if there's no data, show a subtle "0 transfers in last 24h — chart will populate as flows run" text.

Right 40%: "Needs your attention" panel — a curated list of flagged items:
- 2 failed transfers in last hour → click to filter Activity Monitor
- 0 DMZ rules outdated
- 0 partners pending onboarding

This is the Dashboard's job: "what do I need to look at?" Not "here are nine numbers."

**Drop Quick Access entirely** — the sidebar is the nav. Quick Access is a redundancy.

**Add at the bottom: Recent activity feed** — last 10 transfers, scannable, with inline status. Acts as a mini Activity Monitor preview; clicking a row deep-links to AM with that trackId filtered.

### If you only do 1 thing on Dashboard

Collapse 9 KPI cards → 4 inline stats in a single hero line. The 5 cards you delete free up 200 px of vertical space for the actual chart.

---

## ⚡ 3. Flows (`dark-03-flows.png`)

### What's bad

1. **3 error toasts stacked in the top-right corner** on page load. `Couldn't load data: No endpoint GET /api/function-queues`, `/api/external-destinations`, `/api/flows`. Fails loudly on background fetches.
2. "No flows configured" empty state is 400 px tall, centered, with a large mailbox illustration. Fine for first-run; wasteful for subsequent visits.
3. Tab pills "All (0) / Active (0) / Inactive (0)" are wrapped as pills that look like chips — unclear they're tabs.
4. "Execution History" section at the bottom has its own "Tip: Double-click any row to open detailed view" — same instructional ribbon as Activity Monitor. Same fix.
5. Search box "Search flows..." is top-right — disconnected from the filters underneath.

### Drastic redesign

**Error toasts — silent-retry policy**

On background fetch failures, don't toast. Show a subtle red dot on the affected widget title: "Function Catalog · •" and a retry icon. Only toast when the user triggered the action ("Delete flow failed" is worth a toast).

**Empty state — compact**

80 px illustration, one-line copy, one primary button:

```
No flows configured yet
[+ New Flow]
```

That's it. Total height 120 px, not 400 px.

**Tabs — use proper underlined tabs**

```
  All · Active · Inactive                                  [Search flows…] [+ New Flow]
  ────
```

Not pills. Pills signal filters; underlined tabs signal primary view-switch.

**Execution History — treat it like mini-Activity-Monitor**

Same row styling as AM (36 px, mono Track ID, inline action icons on hover). Makes the whole product feel coherent.

### If you only do 1 thing on Flows

Kill the stacked error toasts. They are the first impression of the page; they scream "broken" when the underlying issue is a graceful-degradation concern.

---

## 🖥 4. Server Instances (`dark-04-servers.png`)

### What's bad

1. **Row text wraps aggressively** — INSTANCE column wraps "ftp-web-server-2" to two lines, NAME column wraps "FTP-Web Server 2 — Secondary" to three lines, DESCRIPTION column wraps its sentence. Net: each row is 120 px tall and only 3 visible.
2. **5 status indicators per row** (PROTOCOL pill, STORAGE pill, SECURITY pill "Rules", STATUS pill "Active", another pill "Bound"). Too many.
3. **ACTIONS column has 4 tiny icons** (edit, X, pen, trash) with no tooltips visible. Dangerous — delete is right next to edit.
4. Tab row "All (14) / SFTP (5) / FTP (6) / FTP-Web (HTTP/S) (3)" — useful. Keep.
5. KPI strip at top shows TOTAL/ACTIVE/PHYSICAL STORAGE/VFS/WITH PROXY — same "4-card wasteland" as other pages.

### Drastic redesign

**Don't wrap. Truncate.**

Give NAME column `min-width: 240px; max-width: 320px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis`. On hover, show full text in a tooltip. Row height drops to 48 px → **15 rows visible.**

**Collapse the status column cluster**

Instead of 5 pills per row, one **composite status badge**:

```
● Active · Bound · Rules        (hover: "running on localhost:8183, bound, using Rules security tier")
```

Single line, tooltip for the detail. Cut the visual weight by 80%.

**Actions column — disambiguate destructive actions**

Group actions into a single `⋯` overflow menu. Put Edit and Disable outside (inline), Delete inside the menu with a confirmation. Current layout (edit/X/pen/trash inline) is a delete-by-accident waiting to happen.

```
[Edit] [Disable]  ⋯   ←  contains: Clone · Delete (confirmation)
```

### If you only do 1 thing on Servers

Stop wrapping the NAME/DESCRIPTION columns. Truncate + tooltip. One change, triples the density.

---

## 🤝 5. Partner Management (`dark-05-partners.png`)

### What's bad

1. 4 KPI cards at top (Total / Active / Pending / Suspended). Same issue as other pages.
2. Filter pills "All / Active / Pending / Suspended / Offboarded" repeat the KPI numbers. Redundant.
3. Table has many columns: TYPE, PROTOCOLS, STATUS, SLA TIER, ACCOUNTS, ACTIONS. COMPANY column takes a lot of width.
4. ACTIONS column is a single `⋯` vertical-dots menu — **actually this is the best action-column treatment in the app**. Use it everywhere.
5. "New Partner" primary CTA is top-right, purple — correct.
6. Tab/filter pills "All / Active / Pending / Suspended / Offboarded" are standard pills. Fine.

### Drastic redesign

**Merge KPI bar into filter pills**

Each filter pill shows its count:

```
[All · 5]  [Active · 5]  [Pending · 0]  [Suspended · 0]  [Offboarded · 0]
```

The user sees the breakdown AND can filter, in one row. Eliminates 150 px of vertical space (KPI row gone).

**Status + SLA TIER columns → merged into Company cell**

Below the company name, show a one-line sub-text:

```
Acme Corp
acme-corp  ·  EXTERNAL  ·  STANDARD                           ● Active
```

Type + SLA tier are metadata; they don't deserve their own columns. Move to subtext. Status stays as a pill on the far right.

**Reduce default columns**

Default: Company (with subtext), Protocols, Accounts, Actions. Everything else in `Customize`.

### If you only do 1 thing on Partners

Fold KPI bar into filter pills. Cleanest, fastest win.

---

## 👥 6. Users (`dark-06-users.png`)

### What's bad

1. **Every user shows "Created: Jan 21, 1970"** — Unix-epoch bug. Real backend issue.
2. **Native `<select>` dropdown** for ROLE column. Looks crude in dark mode. Breaks the design system.
3. ROLE column has a **sub-text description** under the select: "Full platform access, user manag..." truncated. This is doing too much.
4. ACTIONS column: 3 tiny icons (eye / key / trash). Key icon meaning unclear (reset password?). Delete next to view is dangerous.
5. 4 KPI cards (Total / Active / Admins / Disabled). Same pattern as everywhere.

### Drastic redesign

**Fix the Jan-1970 bug — separate tracking item**

Obvious backend miss. Add to the R127 ask list.

**Replace native `<select>` with the app's dropdown component**

Same component used by "All Statuses" filter on Activity Monitor. Custom-styled, dark-mode-aware.

**Role as pill, not inline edit**

Don't edit role directly in the row. Show the role as a pill:

```
tester-claude@tranzfer.io              [ADMIN]     Jan 21 1970       ⋯
```

Clicking the row or `⋯` menu gives "Change role…" as an explicit action. Role changes are high-consequence; they shouldn't be a 2-click dropdown.

**Actions: consolidate**

Replace 3 icons with a `⋯` menu (same as Partner Management). Put view + reset-password + disable + delete (confirmation) inside.

**KPI → filter pills (same pattern as Partners)**

```
[All · 4]   [Active · 4]   [Admins · 2]   [Disabled · 0]
```

### If you only do 1 thing on Users

Fix the Jan-1970 timestamp. It makes the whole page look broken.

---

## 🎨 Dark-mode-only polish that applies everywhere

1. **Sidebar at `--bg-raised` (`#14171f`)**, NOT the same color as the body. Instant visual layering.
2. **Top nav at `--bg-raised` with `backdrop-blur-md` and a 1 px bottom border `rgba(255,255,255,0.08)`** — pulls in the login page aesthetic. Tie the app together.
3. **Cards at `--bg-raised`** with `border: 1px solid rgba(255,255,255,0.08)` (visible but not harsh).
4. **Hover state on rows/cards/buttons:** `--bg-elevated` (`#262b38`) with 150 ms ease-out transition.
5. **Status pill filled backgrounds at 15% alpha of the semantic color** (e.g. `rgba(34,197,94,0.15)` for success) with the text at full `#22C55E`. Looks like Linear's approach — tone-on-tone instead of white-on-saturated which is heavy on black.
6. **Neutral text scale (dark):**
   - `--text-primary:   #F3F5F9`  (headings, body)
   - `--text-secondary: #AAB1BF`  (subtext, descriptions, timestamps)
   - `--text-muted:     #747D8D`  (placeholders, disabled)
7. **Drop all uppercase labels globally** — column headers, KPI labels, section labels. Sentence-case, weight 500, `--text-secondary`.
8. **Global focus ring:** `0 0 0 2px rgba(124,109,245,0.5)` — one consistent focus state instead of browser default.

---

## 🚀 Priority order for a "drastic" R127 UI push — 2 days of work

Day 1 (biggest perceptual impact):

1. Apply 4-tier surface tokens everywhere (sidebar, top nav, cards, modals) — 4 h.
2. Apply semantic color palette + pill unification — 3 h.
3. Activity Monitor: shrink rows to 36 px + mono Track IDs + collapse KPI bar — 2 h.
4. Activity Monitor: inline row-action icons on hover (download, restart, pause, more) — 2 h.

Day 2 (per-screen polish):

5. Drop uppercase slate labels everywhere — 1 h.
6. Top-nav glassmorphism to match login — 1 h.
7. Server Instances column truncation + action `⋯` menu — 2 h.
8. Users native `<select>` replacement + role-as-pill — 2 h.
9. Error-toast silent-retry policy + fix 3 Flows-page stacks — 1 h.
10. Users Jan-1970 timestamp fix — 30 min.
11. Sidebar brand clipping — 15 min.

**After these two days the product should read as modern, cohesive, and data-first.** The dark mode will actually feel like a designed theme rather than "chrome on top of black."

---

## Bundle reference

Screenshots: `docs/run-reports/r126-product-ui-modernization-bundle/`
- `dark-01-dashboard.png`
- `dark-02-activity-monitor.png`  ← worst offender, biggest redesign target
- `dark-03-flows.png`
- `dark-04-servers.png`
- `dark-05-partners.png`
- `dark-06-users.png`
- `light-partners.png`

Companion docs:
- `2026-04-18-R126-product-ui-modernization.md` — token system + shared polish
- `2026-04-18-R126-activity-monitor-ux-review.md` — first pass on AM, light-mode
- **This doc** (`2026-04-18-R126-dark-mode-per-screen-redesign.md`) — per-screen drastic redesign for dark mode
