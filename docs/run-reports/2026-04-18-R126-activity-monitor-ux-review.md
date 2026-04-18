# Activity Monitor — UX review (R126, CTA observations)

**Method:** Loaded the running UI via Playwright on `http://localhost:3000`, proxied `/api/*` to the backend on :8080, logged in as superadmin, navigated to Activity Monitor, captured viewport + full-page + row-hover + row-detail-drawer screenshots. Viewport 1280×720. Screenshots committed at `docs/run-reports/r126-activity-monitor-ux-bundle/`.

**TL;DR:** The page is functional but **cluttered, inconsistent, and low on information-density**. Above the fold at 1280×720 only **~1.5 rows of transfers** are visible. Color palette has too many accents (purple, two greens, amber, slate) with no clear semantic mapping. "Modern simple" is achievable with concrete trims — see §6 for the specific changes.

---

## 1. The big problem — too much chrome, too little data

At 1280×720, the viewport allocates roughly:

```
 0–50 px    top bar (brand + global search + user menu)
 50–130 px  tabs (Dashboard / Flow Fabric / Activity Monitor / Live Activity / Transfer Journey)
130–220 px  KPI bar (IN FABRIC / HEALTHY PODS / STUCK / P95 LATENCY)
220–320 px  section header "Activity Monitor" + subtitle + 4 action buttons
320–380 px  sub-tabs (Transfers / Scheduled Retries)
380–500 px  filters row with FILTERS label + 5 inputs
500–540 px  "tip: Double-click any row to open detailed view" banner
540–580 px  table header
580+        first row of data
```

**560 pixels of chrome before the user sees their first transfer.** At 1280 wide × 720 tall, that leaves ~1.5 rows visible. A MFT operations console should show **8–10 rows** at the default viewport. The Activity Monitor is the data view — the chrome is fighting it.

## 2. Brand & identity — conflicting

- Sidebar header: "TranzFer Command Center / MFT Platform"
- Top nav: "TranzFer MFT"
- Page title: "Activity Monitor"

Pick one brand. "Command Center" feels dated / enterprise-2015. "MFT" is descriptive but boring. Either is fine — but one of them should go. Also the sidebar logo is visually cut off (" Command Cente…") which is a polish bug.

## 3. KPI bar at the top — dead-weight

Four cards, all at "0" or low numbers on this environment:
- IN FABRIC = 0
- HEALTHY PODS = 5
- STUCK = 0
- P95 LATENCY = 286 ms (colored amber for no signaled reason)

Each card has a subtle pastel background + right-arrow icon. The arrows suggest clickable drill-down but:
- On hover: no visual feedback
- On click: behavior unclear (and the page didn't change in my test)
- If they ARE clickable, they should be obvious; if they're not, drop the icon

**Recommendation:** either make the KPI bar collapsible (default collapsed, user expands if interested), or move it to the Dashboard where at-a-glance numbers belong. On the Activity Monitor, the user wants to see transfers.

## 4. Section header + action buttons — duplicate info, redundant action

- "Activity Monitor" heading
- "Polling (30s)" orange-dot indicator
- "9 transfers" pill (count)
- Subtitle "Monitor all file transfers across the platform" (generic, wastes a line)
- Four action buttons right-aligned: "30s" / "Export" / "Views" / "Columns"

Problems:
- "Polling (30s)" and the "30s" button are the same thing said twice.
- "9 transfers" pill is a row count — the table already shows this at the bottom (pagination). Redundant.
- Subtitle is boilerplate. Drop it.
- "Views" and "Columns" buttons are similar — both customize the table. Merge under a single "Customize" menu.

## 5. Colors — inconsistent, semantic mapping unclear

Visible accent colors on this page:
| Color | Where used | Meaning |
|---|---|---|
| Purple `#7F6DF5` (brand) | Track IDs, source user links, selected tab underline | Identity / link |
| Dark emerald (pill) | "COMPLETED" status | Success |
| Lighter emerald (pill) | "MOVED TO SENT" status | … Success too? |
| Amber `#f59e0b` | P95 LATENCY value, polling dot | Warning? Not really warning — it's just the color that was available |
| Slate gray (pill) | "SFTP" protocol badge | Neutral |
| Red (nav pill?) | Only briefly seen in dashboard ("2 failed") | Error |

Status pills have at least **two different shades of green** with no clear difference between what each means. The user will not intuit that "COMPLETED" is a final green and "MOVED TO SENT" is an intermediate green. They look nearly identical but are different shades — worst-of-both.

**Recommendation — a 5-color semantic palette:**
| Token | Hex | Used for |
|---|---|---|
| primary | `#7F6DF5` (keep) | Brand accents, primary CTA, active tab |
| success | single green | COMPLETED, DOWNLOADED |
| in-flight | single blue | PROCESSING, MOVED_TO_SENT, QUEUED |
| warning | amber | PAUSED, AWAITING_APPROVAL, STUCK |
| error | red | FAILED, CANCELLED, REJECTED |
| neutral | slate | PROTOCOL badges, labels |

One color per *meaning*, not per *state name*. Two states in the same category get the same color, label distinguishes them.

## 6. "Modern simple" — concrete changes that would land it

### Chrome trims (fewer pixels, higher density)

1. Move KPI bar to Dashboard or make it collapsed-by-default on Activity Monitor.
2. Drop subtitle "Monitor all file transfers across the platform."
3. Drop "FILTERS" label — the inputs are self-evident.
4. Drop the "tip: Double-click any row to open detailed view" banner — replace with a `?` tooltip on the first row, or make single-click open the drawer.
5. Merge "Views" + "Columns" → single "Customize" menu.
6. Make the four action buttons ("30s", "Export", "Views", "Columns") icon-only with tooltips — saves 200 px of horizontal space.
7. Remove the bottom-right floating "Search ⌘K" pill — the top bar already has "Search everywhere"; duplicate.
8. Help "?" floating button — keep one, not both.

### Table defaults

1. Default to 5 columns: **Track ID**, **Filename**, **Status**, **Flow**, **Updated**. Let advanced users opt into more via Customize.
2. Row height: compact by default (36 px), with density toggle.
3. Track ID display: use a tabular-figures mono font (e.g. `Roboto Mono`, `JetBrains Mono`) — makes IDs clearly non-verbal and easier to scan.
4. Don't link Track ID as purple-underlined text; use a subtle copy icon on hover. Track ID isn't a link; making it look like one adds visual weight.
5. Don't link Source User as purple text either — the external-link arrow is confusing.

### Color palette — enforce semantic (§5)

1. Audit `src/components/StatusBadge` (or wherever the pills render) — collapse green variants to one.
2. Replace amber-on-P95-latency with a rule: if p95 > threshold, red; else slate (default).
3. Remove purple from non-interactive elements (Track ID as a string) — reserve purple for links/selection.

### Dark mode

There's a moon toggle at the bottom of the sidebar — verify dark mode actually works (didn't test). If it does: make sure the semantic palette maps cleanly to dark tokens (harder to get right with 5+ accent colors).

### Typography

- Display font: looks like default system-ui; fine for admin UIs.
- Header "Activity Monitor" is the right size (H1 weight 700).
- Column labels in UPPERCASE SLATE: dated. Drop the uppercase. Use sentence-case and a neutral-600 color.
- "Polling (30s)" label is too small + amber dot is too visually heavy. Use a subtle `•` in neutral with "Polling · every 30s" text.

### Behavior polish

- "Tip: Double-click any row to open detailed view" → make single-click open the drawer. Double-click is not discoverable.
- Filters: auto-apply on change (no "Apply" button). If there's one, hide it.
- Sort indicators: not visible on any column header — make sort affordance obvious.
- Row actions: today I have to open a drawer to act on a row. Add inline icon-actions on hover: download, restart, more-menu. (When the Restart/Download endpoints work, of course — see R126 acceptance report.)

## 7. Specific bugs / regressions seen during this UX probe

- **Toast error on dashboard:** `Couldn't load data: No endpoint GET /api/v1/analytics/predictions`. Add an R127 ask to either wire the endpoint or gracefully hide the predictions widget when unavailable.
- **Sidebar logo clipping:** "TranzFer Command Center" is truncated to "TranzFer Command Cente…" on 1280 × 720 — needs `overflow:hidden` + ellipsis or a shorter label.
- **Row-hover screenshot** shows source-user column gains an external-link icon on hover. Icon wasn't visible in the viewport shot but appears on hover — hover-only affordances are a discoverability problem; make them persistent-but-subtle.

## 8. What not to change

Several things are already good and should be preserved:

- **Login page** — genuinely nice. Glassmorphism card, purple gradient backdrop, clean form. Keep.
- **Sidebar IA** — section headers (OPERATIONS, PARTNERS & ACCOUNTS, FILE PROCESSING) are clear and scannable.
- **"All systems operational" green pill** on Dashboard — good at-a-glance health signal.
- **Quick Access grid** on Dashboard — good density for secondary navigation.
- **Filter controls are thoughtful** — placeholder "Filename… (press /)" signals a keyboard shortcut. That's modern UX.

## 9. Priority order for R127 (UI subset)

1. Status badge color audit — one color per semantic meaning (1–2 hours of work).
2. Drop chrome: subtitle, tip banner, FILTERS label, one of the two "30s" indicators (1 hour).
3. Default table to 5 columns + compact density (2 hours).
4. Merge "Views" + "Columns" → "Customize" (30 min).
5. Track ID + Source User: drop the purple-link styling; mono font for IDs (1 hour).
6. KPI bar: collapsible or move to Dashboard (2 hours).
7. Sort indicators on columns (30 min).
8. Fix the two bugs: sidebar logo clipping, analytics/predictions toast (30 min + 30 min).

**Total: under a day of UI work** for a meaningful jump in perceived polish.

---

**Screenshots (committed alongside):**
- `docs/run-reports/r126-activity-monitor-ux-bundle/01-activity-monitor-viewport.png`
- `…/02-activity-monitor-full-page.png`
- `…/03-row-hover.png`
- `…/04-row-detail-drawer.png`
