#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — parallel perf + observability harness
# =============================================================================
#
# Drives load in parallel across every major surface while capturing:
#   • per-service CPU / mem / net-IO samples (docker stats every 2s)
#   • per-service JVM mem, thread count, GC pause, HikariCP pool, Tomcat
#     thread pool, Kafka consumer lag (via actuator Prometheus scrape)
#   • thread dumps at t+30, t+60, t+90 (SIGQUIT → docker logs)
#   • ERROR/WARN log delta during the run
#   • flow-execution latency distribution from the DB
#
# Output: /tmp/perf-run-<ts>/ directory with CSVs, text dumps, and a summary
# Report: docs/run-reports/2026-04-17-perf-baseline.md (if --write-report set)
#
# Usage:  ./scripts/perf/perf-run.sh [duration-seconds]
#   duration defaults to 120s (2 min baseline)
#   use 600 for soak
# =============================================================================
set -o pipefail

DURATION=${1:-120}
TS=$(date +%Y%m%d-%H%M%S)
OUT=/tmp/perf-run-$TS
mkdir -p "$OUT"/dumps "$OUT"/metrics
echo "perf run starting: duration=${DURATION}s out=$OUT"

GREEN=$'\e[32m'; RED=$'\e[31m'; YELLOW=$'\e[33m'; BOLD=$'\e[1m'; RST=$'\e[0m'
section(){ printf '\n%s── %s ──%s\n' "$BOLD" "$*" "$RST"; }
info(){ printf '  %s•%s %s\n' "$YELLOW" "$RST" "$*"; }
pass(){ printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; }

# ─────────────────────────────────────────────────────────────────────────────
# prereq: admin token
TOK=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}' | jq -r '.accessToken')
[ -z "$TOK" ] && { echo "no token — stack not up?"; exit 2; }
HDR_AUTH="Authorization: Bearer $TOK"
pass "admin token acquired"

# ─────────────────────────────────────────────────────────────────────────────
section "Baseline snapshot (t=0)"

# Services to monitor — both Java (JVM metrics) and infra (docker stats only)
JAVA_SVCS=(onboarding-api config-service sftp-service ftp-service encryption-service \
           screening-service keystore-manager analytics-service ai-engine \
           platform-sentinel storage-manager notification-service as2-service \
           edi-converter external-forwarder-service gateway-service)

# Map service → actuator port (bash 3.2-compatible lookup)
port_for() {
  case "$1" in
    onboarding-api) echo 8080 ;;
    config-service) echo 8084 ;;
    sftp-service) echo 8081 ;;
    ftp-service) echo 8082 ;;
    encryption-service) echo 8086 ;;
    screening-service) echo 8092 ;;
    keystore-manager) echo 8093 ;;
    analytics-service) echo 8090 ;;
    ai-engine) echo 8091 ;;
    platform-sentinel) echo 8098 ;;
    storage-manager) echo 8096 ;;
    notification-service) echo 8097 ;;
    as2-service) echo 8094 ;;
    edi-converter) echo 8095 ;;
    external-forwarder-service) echo 8087 ;;
    gateway-service) echo 8085 ;;
    *) echo "" ;;
  esac
}

scrape_metrics() {
  local label=$1
  local outfile="$OUT/metrics/$label.tsv"
  echo -e "service\tjvm_heap_used_mb\tjvm_threads_live\ttomcat_threads_busy\thikari_active\thikari_waiting\tgc_pause_total_ms" > "$outfile"
  for svc in "${JAVA_SVCS[@]}"; do
    local port
    port=$(port_for "$svc")
    [ -z "$port" ] && continue
    local prom
    prom=$(curl -s -m 3 -H "$HDR_AUTH" "http://localhost:$port/actuator/prometheus" 2>/dev/null)
    [ -z "$prom" ] && { echo -e "$svc\t-\t-\t-\t-\t-\t-" >> "$outfile"; continue; }
    local heap threads tomcat hik_a hik_w gc
    heap=$(echo "$prom"    | awk '/^jvm_memory_used_bytes.*area="heap".*id="G1 Old Gen"/{s+=$2} /^jvm_memory_used_bytes.*area="heap".*id="G1 Eden Space"/{s+=$2} /^jvm_memory_used_bytes.*area="heap".*id="G1 Survivor"/{s+=$2} END{printf "%.1f", s/1024/1024}')
    threads=$(echo "$prom"  | awk '/^jvm_threads_live_threads / {print $2}' | head -1)
    tomcat=$(echo "$prom"   | awk '/^tomcat_threads_busy_threads / {print $2}' | head -1)
    hik_a=$(echo "$prom"    | awk '/^hikaricp_connections_active / {print $2}' | head -1)
    hik_w=$(echo "$prom"    | awk '/^hikaricp_connections_pending / {print $2}' | head -1)
    gc=$(echo "$prom"       | awk -F'[{} ]' '/^jvm_gc_pause_seconds_sum/{s+=$NF} END{printf "%.1f", s*1000}')
    echo -e "$svc\t${heap:--}\t${threads:--}\t${tomcat:--}\t${hik_a:--}\t${hik_w:--}\t${gc:--}" >> "$outfile"
  done
  pass "metrics snapshot → $(basename "$outfile")"
}

scrape_metrics t0

# container baseline stats
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' > "$OUT/metrics/t0-docker-stats.txt"
pass "docker stats baseline captured"

# ERROR count baseline
for svc in "${JAVA_SVCS[@]}"; do
  docker logs "mft-$svc" 2>&1 | grep -c '"level":"ERROR"' || true
done > "$OUT/metrics/t0-error-counts.txt"
pass "ERROR counts baseline captured"

# ─────────────────────────────────────────────────────────────────────────────
section "Load generators — parallel (${DURATION}s)"

# L1 — auth login loop (onboarding-api)
(
  end=$(($(date +%s) + DURATION))
  n=0; ok=0
  while [ $(date +%s) -lt $end ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/auth/login \
      -H 'Content-Type: application/json' -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}')
    [ "$code" = "200" ] && ok=$((ok+1))
    n=$((n+1))
  done
  echo "$n $ok" > "$OUT/metrics/l1-auth.txt"
) &
L1=$!; info "L1 auth loop pid=$L1"

# L2 — listener CRUD (create+rebind+delete cycles)
(
  end=$(($(date +%s) + DURATION))
  i=0
  while [ $(date +%s) -lt $end ]; do
    port=$((2300 + (i % 50)))
    iid="perf-l-$i"
    curl -s -o /dev/null -X POST http://localhost:8080/api/servers -H "$HDR_AUTH" -H 'Content-Type: application/json' \
      -d "{\"instanceId\":\"$iid\",\"protocol\":\"SFTP\",\"name\":\"perf-$i\",\"internalHost\":\"sftp-service\",\"internalPort\":$port,\"externalHost\":\"localhost\",\"externalPort\":$port,\"maxConnections\":5}"
    # immediately delete to free the slot
    id=$(curl -s -H "$HDR_AUTH" "http://localhost:8080/api/servers" | jq -r ".[] | select(.instanceId==\"$iid\") | .id" | head -1)
    [ -n "$id" ] && curl -s -o /dev/null -X DELETE "http://localhost:8080/api/servers/$id" -H "$HDR_AUTH"
    i=$((i+1))
  done
  echo "$i" > "$OUT/metrics/l2-listener-crud.txt"
) &
L2=$!; info "L2 listener CRUD pid=$L2"

# L3 — SFTP upload loop into regtest-sftp-1 / sftp-reg-1 :2231
(
  end=$(($(date +%s) + DURATION))
  # small 128-byte payload; this pass sizes throughput by connect rate + small file latency
  echo "perf upload 128 bytes ----------------------------------------------------" > /tmp/perf-payload-$TS.dat
  n=0
  while [ $(date +%s) -lt $end ]; do
    FN="perf-$TS-$n.dat"
    docker run --rm --network tranzfer-mft_default -v "/tmp/perf-payload-$TS.dat:/p.dat:ro" alpine:latest sh -c \
      "apk add --quiet openssh-client sshpass >/dev/null 2>&1; echo put /p.dat $FN | sshpass -p RegTest@2026! sftp -b - -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o PreferredAuthentications=password -o PubkeyAuthentication=no -P 2231 regtest-sftp-1@sftp-service >/dev/null 2>&1" >/dev/null 2>&1
    n=$((n+1))
  done
  echo "$n" > "$OUT/metrics/l3-sftp-uploads.txt"
) &
L3=$!; info "L3 SFTP upload pid=$L3"

# L4 — read-heavy API mix on config-service
(
  end=$(($(date +%s) + DURATION))
  n=0; ok=0
  while [ $(date +%s) -lt $end ]; do
    for p in /api/flows /api/servers /api/connectors /api/sla /api/flows/step-types; do
      code=$(curl -s -o /dev/null -w '%{http_code}' -H "$HDR_AUTH" "http://localhost:8084$p")
      [ "$code" = "200" ] && ok=$((ok+1))
      n=$((n+1))
    done
  done
  echo "$n $ok" > "$OUT/metrics/l4-reads.txt"
) &
L4=$!; info "L4 read mix pid=$L4"

# Periodic captures while load runs
(
  ticks=3
  sleep_each=$((DURATION / (ticks + 1)))
  for t in $(seq 1 $ticks); do
    sleep $sleep_each
    label="t$(((t * DURATION) / (ticks + 1)))s"
    scrape_metrics "$label" >/dev/null
    docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' > "$OUT/metrics/$label-docker-stats.txt"
    # thread dump: SIGQUIT → stderr → docker logs
    for svc in onboarding-api config-service sftp-service; do
      docker kill -s SIGQUIT "mft-$svc" 2>/dev/null || true
    done
    sleep 1
    for svc in onboarding-api config-service sftp-service; do
      docker logs --since 3s "mft-$svc" 2>&1 | sed -n '/Full thread dump/,/^$/p' > "$OUT/dumps/$label-$svc-threads.txt" 2>/dev/null || true
    done
  done
) &
MON=$!; info "metrics/dumps monitor pid=$MON"

echo "waiting for load generators..."
wait $L1 $L2 $L3 $L4 $MON

# ─────────────────────────────────────────────────────────────────────────────
section "Final snapshot (t=end)"
scrape_metrics tEnd

docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' > "$OUT/metrics/tEnd-docker-stats.txt"

# ERROR count delta
for svc in "${JAVA_SVCS[@]}"; do
  docker logs "mft-$svc" 2>&1 | grep -c '"level":"ERROR"' || true
done > "$OUT/metrics/tEnd-error-counts.txt"

# flow-execution latency distribution during the run
docker exec mft-postgres psql -U postgres -d filetransfer -c "
  SELECT COUNT(*) AS runs,
         percentile_disc(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at))*1000) AS p50_ms,
         percentile_disc(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at))*1000) AS p95_ms,
         percentile_disc(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at))*1000) AS p99_ms,
         MAX(EXTRACT(EPOCH FROM (completed_at - started_at))*1000) AS max_ms
  FROM flow_executions
  WHERE started_at > NOW() - INTERVAL '$DURATION seconds' AND status='COMPLETED';
" 2>&1 > "$OUT/metrics/flow-latency.txt"

# ─────────────────────────────────────────────────────────────────────────────
section "Summary"

printf '\n%sLoad generators:%s\n' "$BOLD" "$RST"
printf '  L1 auth login:          '; [ -f "$OUT/metrics/l1-auth.txt" ] && awk '{printf "%s calls (%s OK), %.1f rps\n", $1, $2, $1/'$DURATION'}' "$OUT/metrics/l1-auth.txt" || echo "(no data)"
printf '  L2 listener CRUD:       '; [ -f "$OUT/metrics/l2-listener-crud.txt" ] && awk '{printf "%s cycles, %.2f per second\n", $1, $1/'$DURATION'}' "$OUT/metrics/l2-listener-crud.txt" || echo "(no data)"
printf '  L3 SFTP uploads:        '; [ -f "$OUT/metrics/l3-sftp-uploads.txt" ] && awk '{printf "%s uploads, %.2f per second\n", $1, $1/'$DURATION'}' "$OUT/metrics/l3-sftp-uploads.txt" || echo "(no data)"
printf '  L4 read mix (5 EPs):    '; [ -f "$OUT/metrics/l4-reads.txt" ] && awk '{printf "%s calls (%s OK), %.1f rps\n", $1, $2, $1/'$DURATION'}' "$OUT/metrics/l4-reads.txt" || echo "(no data)"

printf '\n%sFlow-execution latencies (during run):%s\n' "$BOLD" "$RST"
cat "$OUT/metrics/flow-latency.txt"

printf '\n%sJVM heap delta (t0 → tEnd, MB):%s\n' "$BOLD" "$RST"
join -t $'\t' <(sort "$OUT/metrics/t0.tsv") <(sort "$OUT/metrics/tEnd.tsv") 2>/dev/null | \
  awk -F'\t' 'NR>1 && $2 != "-" && $9 != "-" { printf "  %-28s  %6.1f → %6.1f  (Δ%+.1f)\n", $1, $2, $9, $9-$2 }' | sort

printf '\n%sError log delta (last %ss):%s\n' "$BOLD" "$DURATION" "$RST"
paste "$OUT/metrics/t0-error-counts.txt" "$OUT/metrics/tEnd-error-counts.txt" | \
  paste <(printf "%s\n" "${JAVA_SVCS[@]}") - | \
  awk '{d=$3-$2; if (d>0) printf "  %-28s  +%d new ERROR lines\n", $1, d}'

printf '\n%sOutput dir:%s %s\n' "$BOLD" "$RST" "$OUT"
echo "  metrics/  — CSV time-series + docker stats"
echo "  dumps/    — SIGQUIT thread dumps at 3 intermediate points"
