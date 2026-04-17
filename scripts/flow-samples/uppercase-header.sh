#!/usr/bin/env sh
# Sample EXECUTE_SCRIPT flow-step handler for regression testing.
# Contract (per FlowProcessingEngine.executeScript):
#   - Receives the input file path as $1 (from ${file} placeholder)
#   - Must exit 0 on success; non-zero fails the flow
#   - Optional: write transformed output to $2 (if caller set cfg.outputFile)
#
# This sample uppercases the first line of the input (the "header"),
# leaves subsequent lines untouched, and writes to a side-by-side
# .transformed file. Safe for small files — for large files the
# flow step's cfg.outputFile path would normally be a work-dir temp.
set -eu

IN="${1:?input file path required}"
OUT="${2:-${IN}.transformed}"

if [ ! -r "$IN" ]; then
  echo "uppercase-header: input file not readable: $IN" >&2
  exit 2
fi

# First line uppercased, rest passed through.
{
  awk 'NR==1{print toupper($0); next} {print}' "$IN"
} > "$OUT"

echo "uppercase-header: wrote $OUT ($(wc -c < "$OUT") bytes)"
exit 0
