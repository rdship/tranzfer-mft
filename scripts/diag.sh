#!/usr/bin/env bash
# R129 — on-demand JDK diagnostics for services running a thin JRE image.
#
# Rationale: our runtime images use eclipse-temurin:25-jre to keep boot cold
# and image size small. That base doesn't include jcmd / jfr / jstack. When a
# live diagnostic is needed (heap dump, thread dump, JFR start/dump), we
# attach a throw-away JDK sidecar sharing the PID namespace of the target
# container. The sidecar is pulled once by Docker and cached; the running
# service stays thin.
#
# Usage:
#   scripts/diag.sh heap      <service>             # writes /tmp/heap-<service>-<ts>.hprof inside the target container
#   scripts/diag.sh threads   <service>             # stack trace of all threads
#   scripts/diag.sh jfr-start <service> [duration]  # kicks off a JFR recording (default 60s)
#   scripts/diag.sh jfr-dump  <service>             # flushes the in-flight JFR to /tmp
#   scripts/diag.sh help
#
# Targets the running container by name prefix (mft-<service> or plain <service>).
# No change to the production runtime image is required — this is a dev/ops
# tool, not a shipped feature.

set -euo pipefail

SIDECAR_IMAGE="${DIAG_SIDECAR_IMAGE:-eclipse-temurin:25-jdk}"
TS=$(date +%Y%m%d-%H%M%S)

usage() {
  sed -n '2,/^set -euo/p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'
  exit 1
}

resolve_container() {
  local name="$1"
  local full="mft-${name}"
  if docker ps --format '{{.Names}}' | grep -qx "$full"; then
    echo "$full"
  elif docker ps --format '{{.Names}}' | grep -qx "$name"; then
    echo "$name"
  else
    echo "ERROR: no running container matches '$name' or 'mft-$name'" >&2
    docker ps --format '{{.Names}}' | sed 's/^/  - /' >&2
    exit 2
  fi
}

run_jcmd() {
  # Share PID ns with the target so `1` refers to the target's java process.
  # Mount /tmp so the resulting .hprof / .jfr lands somewhere the user can
  # copy out with `docker cp <container>:/tmp/<file> .`.
  local target="$1"; shift
  docker run --rm \
    --pid="container:${target}" \
    --volumes-from "${target}" \
    "${SIDECAR_IMAGE}" \
    jcmd 1 "$@"
}

cmd="${1:-help}"
case "$cmd" in
  heap)
    [[ $# -ge 2 ]] || usage
    target=$(resolve_container "$2")
    out="/tmp/heap-${target#mft-}-${TS}.hprof"
    echo "Capturing heap dump on ${target} → ${out} (inside container)..."
    run_jcmd "$target" GC.heap_dump "$out"
    echo "Copy it out with: docker cp ${target}:${out} ./"
    ;;
  threads)
    [[ $# -ge 2 ]] || usage
    target=$(resolve_container "$2")
    run_jcmd "$target" Thread.print
    ;;
  jfr-start)
    [[ $# -ge 2 ]] || usage
    target=$(resolve_container "$2")
    dur="${3:-60s}"
    out="/tmp/jfr-${target#mft-}-${TS}.jfr"
    run_jcmd "$target" JFR.start duration="$dur" filename="$out" settings=default name=diag
    echo "JFR started on ${target} (duration=${dur}) → ${out}"
    echo "When finished: scripts/diag.sh jfr-dump ${2}    and    docker cp ${target}:${out} ./"
    ;;
  jfr-dump)
    [[ $# -ge 2 ]] || usage
    target=$(resolve_container "$2")
    out="/tmp/jfr-${target#mft-}-${TS}-snapshot.jfr"
    run_jcmd "$target" JFR.dump name=diag filename="$out"
    echo "JFR snapshot written to ${out}"
    echo "Copy: docker cp ${target}:${out} ./"
    ;;
  help|-h|--help) usage ;;
  *) echo "ERROR: unknown subcommand '$cmd'" >&2; usage ;;
esac
