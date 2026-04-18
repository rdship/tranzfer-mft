#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — perf harness v2
# =============================================================================
#
# v2 improvements over v1:
#   • proper heap sum (G1 Eden + Old + Survivor)
#   • hikaricp_connections_{active,pending,max,acquire_seconds_max}
#   • http_server_requests_seconds per URI-status (real endpoint latencies)
#   • persistent alpine sidecar for SFTP — pre-pull + keep container alive,
#     loop `sftp put` inside it instead of `docker run` per iter
#   • per-endpoint L4 split (OK/total per URI)
#   • SIGQUIT → wait 5s → docker logs --tail 1500 to actually grab the dump
#   • ERROR classification: first 5 distinct messages per service during run
# =============================================================================
set -o pipefail

DURATION=${1:-180}
TS=$(date +%Y%m%d-%H%M%S)
OUT=/tmp/perf-run-v2-$TS
mkdir -p "$OUT"/dumps "$OUT"/metrics

GREEN=$'\e[32m'; RED=$'\e[31m'; YELLOW=$'\e[33m'; BOLD=$'\e[1m'; RST=$'\e[0m'
section(){ printf '\n%s── %s ──%s\n' "$BOLD" "$*" "$RST"; }
info(){ printf '  %s•%s %s\n' "$YELLOW" "$RST" "$*"; }
pass(){ printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; }

TOK=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}' | jq -r '.accessToken')
[ -z "$TOK" ] && { echo "no token"; exit 2; }
HDR="Authorization: Bearer $TOK"
pass "admin token acquired — output=$OUT duration=${DURATION}s"

JAVA_SVCS="onboarding-api config-service sftp-service ftp-service encryption-service screening-service keystore-manager analytics-service ai-engine platform-sentinel storage-manager notification-service as2-service edi-converter external-forwarder-service gateway-service"

port_for() { case "$1" in onboarding-api) echo 8080;; config-service) echo 8084;; sftp-service) echo 8081;; ftp-service) echo 8082;; encryption-service) echo 8086;; screening-service) echo 8092;; keystore-manager) echo 8093;; analytics-service) echo 8090;; ai-engine) echo 8091;; platform-sentinel) echo 8098;; storage-manager) echo 8096;; notification-service) echo 8097;; as2-service) echo 8094;; edi-converter) echo 8095;; external-forwarder-service) echo 8087;; gateway-service) echo 8085;; esac; }

scrape_metrics() {
  local label=$1
  local f="$OUT/metrics/$label.tsv"
  echo -e "service\theap_mb\tnonheap_mb\tthreads_live\thikari_active\thikari_max\thikari_pending\thikari_acquire_max_ms\tgc_pause_total_ms\thttp_requests_total\thttp_5xx_total" > "$f"
  for svc in $JAVA_SVCS; do
    local port; port=$(port_for "$svc"); [ -z "$port" ] && continue
    local prom; prom=$(curl -s -m 3 -H "$HDR" "http://localhost:$port/actuator/prometheus")
    [ -z "$prom" ] && { echo -e "$svc\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-" >> "$f"; continue; }
    local heap nonheap threads hik_a hik_m hik_p hik_am gc http_n http_5
    heap=$(   echo "$prom" | awk -F'[{} ]' '/^jvm_memory_used_bytes.*area="heap"/ {s+=$NF} END {printf "%.1f", s/1024/1024}')
    nonheap=$(echo "$prom" | awk -F'[{} ]' '/^jvm_memory_used_bytes.*area="nonheap"/ {s+=$NF} END {printf "%.1f", s/1024/1024}')
    threads=$(echo "$prom" | awk '/^jvm_threads_live_threads / {print $2; exit}')
    hik_a=$(  echo "$prom" | awk -F'[{} ]' '/^hikaricp_connections_active/ {s+=$NF} END {print s+0}')
    hik_m=$(  echo "$prom" | awk -F'[{} ]' '/^hikaricp_connections_max/ {s+=$NF} END {print s+0}')
    hik_p=$(  echo "$prom" | awk -F'[{} ]' '/^hikaricp_connections_pending/ {s+=$NF} END {print s+0}')
    hik_am=$( echo "$prom" | awk -F'[{} ]' '/^hikaricp_connections_acquire_seconds_max/ {if ($NF>m) m=$NF} END {printf "%.1f", m*1000}')
    gc=$(     echo "$prom" | awk -F'[{} ]' '/^jvm_gc_pause_seconds_sum/ {s+=$NF} END {printf "%.0f", s*1000}')
    http_n=$( echo "$prom" | awk -F'[{} ]' '/^http_server_requests_seconds_count/ {s+=$NF} END {print s+0}')
    http_5=$( echo "$prom" | awk -F'[{} ]' '/^http_server_requests_seconds_count.*status="5/ {s+=$NF} END {print s+0}')
    echo -e "$svc\t${heap}\t${nonheap}\t${threads}\t${hik_a}\t${hik_m}\t${hik_p}\t${hik_am}\t${gc}\t${http_n}\t${http_5}" >> "$f"
  done
}

dump_threads() {
  local label=$1
  for svc in onboarding-api config-service sftp-service; do
    docker kill -s SIGQUIT "mft-$svc" 2>/dev/null
  done
  sleep 6
  for svc in onboarding-api config-service sftp-service; do
    docker logs --tail 2000 "mft-$svc" 2>&1 | awk '/Full thread dump/,/^Heap$/' > "$OUT/dumps/$label-$svc-threads.txt"
    local lines; lines=$(wc -l < "$OUT/dumps/$label-$svc-threads.txt")
    info "$label $svc thread dump: $lines lines"
  done
}

# ─────────────────────────────────────────────────────────────────────────────
section "t=0 baseline"
scrape_metrics t0
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' > "$OUT/metrics/t0-docker.txt"
pass "t0 captured"

# ─────────────────────────────────────────────────────────────────────────────
section "Start persistent SFTP sidecar"

echo "perf-payload-128-bytes---------------------------------------------" > /tmp/perf-pl-$TS.dat
# pre-pull alpine + openssh-client so iterations don't pay that cost
SID=$(docker run -d --network tranzfer-mft_default -v /tmp/perf-pl-$TS.dat:/p.dat:ro alpine:latest sh -c 'apk add --quiet openssh-client sshpass >/dev/null 2>&1; sleep 3600')
sleep 4  # let apk finish
docker exec "$SID" sh -c 'command -v sftp sshpass' >/dev/null && pass "sidecar $SID ready" || info "sidecar setup may still be pulling"

# ─────────────────────────────────────────────────────────────────────────────
section "Load generators (${DURATION}s parallel)"

# L1 — auth (unchanged; subject to gateway 10r/s rate limit)
(
  end=$(($(date +%s) + DURATION)); n=0; ok=0
  while [ $(date +%s) -lt $end ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}')
    [ "$code" = "200" ] && ok=$((ok+1))
    n=$((n+1))
  done
  echo "$n $ok" > "$OUT/metrics/l1-auth.txt"
) &
L1=$!

# L2 — read-mix split by endpoint
(
  end=$(($(date +%s) + DURATION))
  declare -a EPS=("http://localhost:8084/api/flows" "http://localhost:8080/api/servers" "http://localhost:8084/api/connectors" "http://localhost:8084/api/sla" "http://localhost:8084/api/flows/step-types")
  declare -a LABELS=("config.flows" "onboarding.servers" "config.connectors" "config.sla" "config.step-types")
  declare -a NS
  declare -a OKS
  for i in 0 1 2 3 4; do NS[$i]=0; OKS[$i]=0; done
  while [ $(date +%s) -lt $end ]; do
    for i in 0 1 2 3 4; do
      code=$(curl -s -o /dev/null -w '%{http_code}' -H "$HDR" "${EPS[$i]}")
      [ "$code" = "200" ] && OKS[$i]=$((${OKS[$i]}+1))
      NS[$i]=$((${NS[$i]}+1))
    done
  done
  { for i in 0 1 2 3 4; do echo -e "${LABELS[$i]}\t${NS[$i]}\t${OKS[$i]}"; done; } > "$OUT/metrics/l2-reads.txt"
) &
L2=$!

# L3 — persistent SFTP sidecar upload loop
(
  end=$(($(date +%s) + DURATION)); n=0
  while [ $(date +%s) -lt $end ]; do
    docker exec "$SID" sh -c "echo put /p.dat perf-$n.dat | sshpass -p RegTest@2026! sftp -b - -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o PreferredAuthentications=password -o PubkeyAuthentication=no -P 2231 regtest-sftp-1@sftp-service >/dev/null 2>&1"
    n=$((n+1))
  done
  echo "$n" > "$OUT/metrics/l3-sftp.txt"
) &
L3=$!

# L4 — keystore key-gen churn (AES; PGP is CPU-heavier)
(
  end=$(($(date +%s) + DURATION)); n=0; ok=0
  while [ $(date +%s) -lt $end ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8093/api/v1/keys/generate/aes -H "$HDR" -H 'Content-Type: application/json' -d "{\"alias\":\"perf-aes-$n\",\"ownerService\":\"encryption-service\"}")
    [ "$code" = "201" ] && ok=$((ok+1))
    n=$((n+1))
  done
  echo "$n $ok" > "$OUT/metrics/l4-aes-keygen.txt"
) &
L4=$!

# L5 — SFTP handshake rate (connect+auth+close, NO put)
(
  end=$(($(date +%s) + DURATION)); n=0
  while [ $(date +%s) -lt $end ]; do
    docker exec "$SID" sh -c "echo bye | sshpass -p RegTest@2026! sftp -b - -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o PreferredAuthentications=password -o PubkeyAuthentication=no -P 2231 regtest-sftp-1@sftp-service >/dev/null 2>&1"
    n=$((n+1))
  done
  echo "$n" > "$OUT/metrics/l5-handshake.txt"
) &
L5=$!

# Periodic captures + thread dumps
(
  sleep_each=$((DURATION / 4))
  for t in 1 2 3; do
    sleep $sleep_each
    lbl="t$((t * sleep_each))s"
    scrape_metrics "$lbl" >/dev/null
    docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' > "$OUT/metrics/$lbl-docker.txt"
  done
) &
MON=$!

# Capture one good thread dump at t+half
(
  sleep $((DURATION/2))
  dump_threads "tmid"
) &
TD=$!

echo "waiting for load..."
wait $L1 $L2 $L3 $L4 $L5 $MON $TD

# ─────────────────────────────────────────────────────────────────────────────
section "t=end"
scrape_metrics tEnd
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' > "$OUT/metrics/tEnd-docker.txt"
dump_threads "tEnd"

# cleanup sidecar
docker rm -f "$SID" >/dev/null 2>&1

# ─────────────────────────────────────────────────────────────────────────────
section "Summary"

printf '\n%sThroughput:%s\n' "$BOLD" "$RST"
printf '  L1 auth login:       '; awk -v d=$DURATION '{printf "%d/%d OK  (%.1f rps attempted)\n", $2, $1, $1/d}' "$OUT/metrics/l1-auth.txt"
printf '  L2 read-mix:\n'
while IFS=$'\t' read -r ep n ok; do
  printf '    %-22s %6d/%6d OK  (%.1f rps)\n' "$ep" "$ok" "$n" "$(awk "BEGIN{print $n/$DURATION}")"
done < "$OUT/metrics/l2-reads.txt"
printf '  L3 SFTP put:         '; awk -v d=$DURATION '{printf "%d uploads  (%.1f per s)\n", $1, $1/d}' "$OUT/metrics/l3-sftp.txt"
printf '  L4 AES keygen:       '; awk -v d=$DURATION '{printf "%d/%d OK  (%.1f per s)\n", $2, $1, $1/d}' "$OUT/metrics/l4-aes-keygen.txt"
printf '  L5 SFTP handshake:   '; awk -v d=$DURATION '{printf "%d cycles  (%.1f per s)\n", $1, $1/d}' "$OUT/metrics/l5-handshake.txt"

printf '\n%sJVM state deltas (t0 → tEnd):%s\n' "$BOLD" "$RST"
printf '  %-28s %8s %8s %8s %8s %10s %8s\n' "service" "heapMB" "Δheap" "threads" "Δthreads" "httpReqΔ" "http5xxΔ"
while IFS=$'\t' read -r s h0 nh0 t0 ha0 hm0 hp0 ham0 gc0 hr0 h50; do
  [ "$s" = "service" ] && continue
  # find matching tEnd row
  match=$(grep -P "^$s\t" "$OUT/metrics/tEnd.tsv" | head -1)
  [ -z "$match" ] && continue
  IFS=$'\t' read -r _ hE nhE tE haE hmE hpE hamE gcE hrE h5E <<< "$match"
  dh=$(awk "BEGIN{printf \"%+.1f\", $hE - $h0}")
  dt=$(awk "BEGIN{printf \"%+d\", $tE - $t0}")
  dhr=$(awk "BEGIN{printf \"%+d\", $hrE - $hr0}")
  dh5=$(awk "BEGIN{printf \"%+d\", $h5E - $h50}")
  printf '  %-28s %8s %8s %8s %8s %10s %8s\n' "$s" "$hE" "$dh" "$tE" "$dt" "$dhr" "$dh5"
done < "$OUT/metrics/t0.tsv"

printf '\n%sFlow-execution latencies during run:%s\n' "$BOLD" "$RST"
docker exec mft-postgres psql -U postgres -d filetransfer -c "
  SELECT COUNT(*) AS runs,
         percentile_disc(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at-started_at))*1000)::int AS p50_ms,
         percentile_disc(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at-started_at))*1000)::int AS p95_ms,
         percentile_disc(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at-started_at))*1000)::int AS p99_ms,
         MAX(EXTRACT(EPOCH FROM (completed_at-started_at))*1000)::int AS max_ms
  FROM flow_executions
  WHERE started_at > NOW() - INTERVAL '$DURATION seconds' AND status='COMPLETED';" 2>&1

printf '\n%sThread-dump sizes (lines):%s\n' "$BOLD" "$RST"
wc -l "$OUT/dumps"/*.txt 2>/dev/null | head

printf '\n%sOutput:%s %s\n' "$BOLD" "$RST" "$OUT"
