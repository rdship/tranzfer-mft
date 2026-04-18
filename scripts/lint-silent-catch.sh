#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Silent-Catch Lint
# =============================================================================
#
# Fails CI if any Java file contains a `catch (...)` block whose body calls
# only `log.debug(...)` without an explicit suppression comment. Prevents
# the R111/R112 class of bug where SpiffeWorkloadClient.tryConnect's
# connection failure was at log.debug and ops didn't see it for 3 releases.
#
# The rule: in src/main/java/, any method body that has the sequence
#   } catch (...) {
#     log.debug(...)   // NO other statement
#   }
# must carry a `// @silent-catch-ok: <reason>` comment on the catch line
# or the line before log.debug. Otherwise → fail.
#
# Allowed reason tags (add more as needed):
#   retryable-probe  // noisy-inner-loop  // guaranteed-safe-degrade
#
# Run modes:
#   ./scripts/lint-silent-catch.sh           lint whole tree, exit 0/1
#   ./scripts/lint-silent-catch.sh --verbose show each violation in full
#
# Exit codes:
#   0  no unsuppressed silent-catch patterns found
#   1  one or more violations
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; BLUE=$'\e[34m'; BOLD=$'\e[1m'; RST=$'\e[0m'

VERBOSE=false
[[ "${1:-}" == "--verbose" ]] && VERBOSE=true

say()  { printf '%s[silent-catch-lint]%s %s\n' "$BLUE" "$RST" "$*"; }
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; }
err()  { printf '  %s✗%s %s\n' "$RED"   "$RST" "$*" >&2; }

# The core check: awk scans each Java file's main source; when it sees
# `} catch (...)` { tracks entering a catch block; if the only non-log
# statement inside that block is `log.debug(...)` AND there's no
# `@silent-catch-ok` marker nearby, flag.
#
# The awk state machine tracks: in_catch (depth tracked by brace counting),
# seen_debug (we've seen log.debug at this catch level), seen_non_debug
# (some other call exists — e.g. log.warn, throw, return — acceptable).

violations=0
offender_files=()

# shellcheck disable=SC2016
AWK_PROG='
function trim(s) { sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s); return s }

BEGIN { in_catch = 0; brace_depth = 0; seen_debug = 0; seen_other = 0; catch_line = 0; suppression = 0 }

# Track the line AFTER a `} catch (...) {` to open the scan window
/^[[:space:]]*}?[[:space:]]*catch[[:space:]]*\(/ {
    catch_line = NR
    if (match($0, /\{[[:space:]]*$/)) {
        in_catch = 1
        brace_depth = 1
        seen_debug = 0
        seen_other = 0
        suppression = 0
        # Check catch line itself for suppression marker
        if (index($0, "@silent-catch-ok") > 0) suppression = 1
        next
    }
}

# Only evaluate the block body
in_catch {
    # Update brace depth
    for (i = 1; i <= length($0); i++) {
        c = substr($0, i, 1)
        if (c == "{") brace_depth++
        if (c == "}") brace_depth--
    }
    if (index($0, "@silent-catch-ok") > 0) suppression = 1

    stripped = trim($0)
    # Ignore pure comment + blank lines
    if (stripped ~ /^(\/\/|\*|\/\*)/ || stripped == "" || stripped == "}") {
        if (brace_depth <= 0) {
            # Block closed — evaluate
            if (seen_debug && !seen_other && !suppression) {
                printf("%s:%d: silent log.debug in catch — add `// @silent-catch-ok: <reason>` or raise to log.warn\n", FILENAME, catch_line)
                any_violation = 1
            }
            in_catch = 0
        }
        next
    }

    # log.debug(...) detection
    if (stripped ~ /^log\.debug[[:space:]]*\(/) {
        seen_debug = 1
        next
    }

    # Any other statement: throw, return, log.warn, log.error, another call
    seen_other = 1
}

END { if (any_violation) exit 1 }
'

# Collect production Java files (skip tests + target)
files=()
while IFS= read -r -d $'\0' f; do files+=("$f"); done \
    < <(find . -type f -name '*.java' \
        -not -path '*/target/*' \
        -not -path '*/node_modules/*' \
        -not -path '*/src/test/java/*' \
        -print0)

say "Scanning ${#files[@]} production Java files..."

for f in "${files[@]}"; do
    output=$(awk "$AWK_PROG" "$f" 2>/dev/null || true)
    if [[ -n "$output" ]]; then
        violations=$((violations + $(echo "$output" | wc -l | tr -d ' ')))
        offender_files+=("$f")
        if [[ "$VERBOSE" == true ]]; then
            echo "$output" | sed 's/^/  /'
        fi
    fi
done

if [[ $violations -eq 0 ]]; then
    ok "no silent-catch patterns found"
    exit 0
fi

err "$violations silent-catch violation(s) across ${#offender_files[@]} file(s):"
if [[ "$VERBOSE" == false ]]; then
    for f in "${offender_files[@]}"; do
        awk "$AWK_PROG" "$f" 2>/dev/null | sed 's/^/  /' >&2
    done
fi

say ""
say "Silent log.debug in a catch block is how the R111 SpiffeWorkloadClient"
say "connection-failure bug hid for 3 releases. Either raise the log level to"
say "warn (so ops sees it), or annotate the catch with"
say "    // @silent-catch-ok: <brief reason>"
say "to document the intentional swallow."
exit 1
