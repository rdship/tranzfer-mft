#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Feature Inventory
# =============================================================================
#
# Builds a single source-of-truth catalogue of every "feature" the platform
# publishes, by walking the actual code:
#
#   1. UI ROUTES         — every <Route path=...> in ui-service/src/App.jsx
#                          plus every Sidebar.jsx `to:` link
#   2. API ENDPOINTS     — every @GetMapping/@PostMapping/@PutMapping/
#                          @DeleteMapping/@PatchMapping under
#                          */src/main/java/**/controller/**.java
#                          (resolved against the class-level @RequestMapping)
#   3. DB ENTITIES       — every @Entity / @Table class under
#                          shared/shared-platform/.../entity and per-service
#                          entity packages
#   4. SERVICE PORTS     — from CLAUDE.md and SERVICE_HEALTH_ENDPOINTS in
#                          ui-service/src/context/ServiceContext.jsx
#
# Output is a JSON document at tests/inventory/feature-inventory.json that
# the random validator (validate-features.sh) reads and samples from.
# Re-run whenever the surface changes — it's idempotent and ships in CI
# right before validate-features.sh.
#
# Usage:
#   ./scripts/feature-inventory.sh                 — write tests/inventory/
#   ./scripts/feature-inventory.sh --pretty        — also print summary
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

OUT_DIR=tests/inventory
mkdir -p "$OUT_DIR"
OUT_JSON="$OUT_DIR/feature-inventory.json"

PRETTY=false
[[ "${1:-}" == "--pretty" ]] && PRETTY=true

GREEN=$'\e[32m'; BLUE=$'\e[34m'; BOLD=$'\e[1m'; RST=$'\e[0m'
log() { printf '\n%s[inventory]%s %s\n' "$BLUE" "$RST" "$*"; }

# ── Inventory via Python (much simpler than awk for JSON output) ────────
log "Walking the codebase..."

python3 << 'PY' > "$OUT_JSON"
import os, re, json

ROOT = "."

# ── 1. UI ROUTES from App.jsx ──────────────────────────────────────────
ui_routes = []
app_path = "ui-service/src/App.jsx"
if os.path.exists(app_path):
    src = open(app_path).read()
    for m in re.finditer(r'<Route\s+path=["\']([^"\']+)["\']', src):
        path = m.group(1)
        if path.startswith('/'):
            path = path
        elif path != "*":
            path = "/" + path
        if path == "*": continue
        ui_routes.append(path)

# Sidebar.jsx for the visible navigation links
sidebar_links = []
sb_path = "ui-service/src/components/Sidebar.jsx"
if os.path.exists(sb_path):
    src = open(sb_path).read()
    for m in re.finditer(r"to:\s*['\"]([^'\"]+)['\"]", src):
        sidebar_links.append(m.group(1))

# ── 2. API ENDPOINTS from every controller ────────────────────────────
api_endpoints = []
for service_dir in sorted(os.listdir(ROOT)):
    service_path = os.path.join(ROOT, service_dir)
    if not os.path.isdir(service_path): continue
    if service_dir.startswith('.') or service_dir in {'node_modules','target','tests','docs','config','scripts','installer','docker','k8s','data','spire'}:
        continue
    java_root = os.path.join(service_path, 'src/main/java')
    if not os.path.isdir(java_root): continue
    for dirpath, _, files in os.walk(java_root):
        if 'controller' not in dirpath.lower(): continue
        for f in files:
            if not f.endswith('.java'): continue
            path = os.path.join(dirpath, f)
            try:
                content = open(path).read()
            except: continue
            class_base = ""
            m = re.search(r'@RequestMapping\s*\(\s*["]([^"]+)["]\s*\)', content)
            if m: class_base = m.group(1)
            for mm in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\s*\(?\s*(?:value\s*=\s*)?["]?([^"\),\s]*)["]?', content):
                method = mm.group(1).upper()
                ep = mm.group(2).strip()
                if not ep.startswith('/') and ep:
                    ep = '/' + ep
                full = (class_base + ep) if class_base else ep
                if not full or not full.startswith('/'):
                    continue
                # normalize {var} to a literal placeholder for stable matching
                normalized = re.sub(r'\{[^}]+\}', '{X}', full)
                api_endpoints.append({
                    'service': service_dir,
                    'method': method,
                    'path': normalized,
                    'controller': os.path.basename(path),
                })

# ── 3. DB ENTITIES ────────────────────────────────────────────────────
db_entities = []
for dirpath, _, files in os.walk(ROOT):
    if 'target' in dirpath.split(os.sep) or 'node_modules' in dirpath.split(os.sep): continue
    if '/test/' in dirpath: continue
    for f in files:
        if not f.endswith('.java'): continue
        path = os.path.join(dirpath, f)
        try:
            content = open(path).read(8192)  # only need the head
        except: continue
        if '@Entity' not in content: continue
        cls_match = re.search(r'(?:public\s+)?class\s+(\w+)', content)
        tbl_match = re.search(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"', content)
        if cls_match:
            db_entities.append({
                'class': cls_match.group(1),
                'table': tbl_match.group(1) if tbl_match else None,
                'file': os.path.relpath(path, ROOT),
            })

# ── 4. SERVICE PORT MAP from ServiceContext.jsx ───────────────────────
service_ports = {}
sc_path = "ui-service/src/context/ServiceContext.jsx"
if os.path.exists(sc_path):
    src = open(sc_path).read()
    for m in re.finditer(r"(\w+):\s*\{\s*url:\s*['\"]http://localhost:(\d+)([^'\"]*)['\"]\s*,\s*port:\s*(\d+)", src):
        service_ports[m.group(1)] = {
            'port': int(m.group(4)),
            'health_path': m.group(3),
        }

inventory = {
    'generated_at': __import__('datetime').datetime.now().isoformat(),
    'summary': {
        'ui_routes': len(ui_routes),
        'sidebar_links': len(sidebar_links),
        'api_endpoints': len(api_endpoints),
        'db_entities': len(db_entities),
        'services_with_ports': len(service_ports),
    },
    'ui_routes': sorted(set(ui_routes)),
    'sidebar_links': sorted(set(sidebar_links)),
    'api_endpoints': sorted(api_endpoints, key=lambda r: (r['service'], r['method'], r['path'])),
    'db_entities': sorted(db_entities, key=lambda e: e['class']),
    'service_ports': service_ports,
}
print(json.dumps(inventory, indent=2))
PY

if [[ ! -s "$OUT_JSON" ]]; then
  echo "ERROR: feature inventory output empty" >&2
  exit 1
fi

if command -v jq >/dev/null; then
  total_routes=$(jq '.summary.ui_routes' "$OUT_JSON")
  total_endpoints=$(jq '.summary.api_endpoints' "$OUT_JSON")
  total_entities=$(jq '.summary.db_entities' "$OUT_JSON")
  total_services=$(jq '.summary.services_with_ports' "$OUT_JSON")
  log "Inventory written to $OUT_JSON"
  printf '  %s%s%s ui routes\n' "$BOLD" "$total_routes" "$RST"
  printf '  %s%s%s api endpoints\n' "$BOLD" "$total_endpoints" "$RST"
  printf '  %s%s%s db entities\n' "$BOLD" "$total_entities" "$RST"
  printf '  %s%s%s services with health ports\n' "$BOLD" "$total_services" "$RST"
fi

if [[ "$PRETTY" == true ]]; then
  jq '.summary, (.api_endpoints | group_by(.service) | map({service: .[0].service, count: length}))' "$OUT_JSON"
fi

echo
echo "${GREEN}Inventory ready: $OUT_JSON${RST}"
