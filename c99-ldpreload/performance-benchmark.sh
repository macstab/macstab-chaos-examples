#!/usr/bin/env bash
# performance-benchmark.sh
#
# Measures the overhead introduced by each libchaos-* library using
# `dd` as a stable, predictable IO workload.
#
# Test matrix (4 columns per library):
#   baseline          — no LD_PRELOAD at all
#   no_rules          — library loaded, empty config file
#   zero_prob         — library loaded, rule matches but probability=0.0
#   hundred_pct_noop  — library loaded, probability=1.0 but LATENCY:0ms
#
# Output: a Markdown table printed to stdout.
#
# Prerequisites:
#   dd, awk, python3 (for stats), and the .so files.
#   Set CHAOS_LIBS_PATH to the directory containing the built .so files.
#   Defaults to ../../../dist relative to this script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHAOS_LIBS_PATH="${CHAOS_LIBS_PATH:-$SCRIPT_DIR/../../../dist}"

# ------------------------------------------------------------------ #
# Parameters
# ------------------------------------------------------------------ #
DD_BLOCK_SIZE="64k"
DD_COUNT=512          # 512 * 64 KiB = 32 MiB per run
RUNS=5                # repetitions per cell (median is reported)
TMPFILE="/tmp/chaos-bench-$$"
CONF_FILE="/tmp/chaos-bench-$$.conf"

# Libraries to benchmark
declare -A LIBS=(
  [io]="libchaos-io-musl-amd64.so"
  [net]="libchaos-net-musl-amd64.so"
  [dns]="libchaos-dns-musl-amd64.so"
  [time]="libchaos-time-musl-amd64.so"
  [memory]="libchaos-memory-glibc-amd64.so"
  [process]="libchaos-process-glibc-amd64.so"
)

# Config env var per library
declare -A CONF_VARS=(
  [io]="CHAOS_IO_CONF"
  [net]="CHAOS_NET_CONF"
  [dns]="CHAOS_DNS_CONF"
  [time]="CHAOS_TIME_CONF"
  [memory]="CHAOS_MEMORY_CONF"
  [process]="CHAOS_PROCESS_CONF"
)

# "Zero probability" rule for each library (matches but never fires)
declare -A ZERO_PROB_RULE=(
  [io]="/tmp:write:ERRNO:EIO:0.0"
  [net]="tcp4://localhost:1234:recv:ERRNO:EAGAIN:0.0"
  [dns]="dns://localhost:EAI_AGAIN:0.0"
  [time]="clock_gettime/realtime:OFFSET:0"
  [memory]="mmap/anon:ERRNO:ENOMEM:0.0"
  [process]="fork:FAIL_AFTER:EAGAIN,999999"
)

# "100% match, 0ms latency" rule (effect fires but adds no wall time)
declare -A LATENCY_ZERO_RULE=(
  [io]="/tmp:write:LATENCY:0:1.0"
  [net]="tcp4://localhost:1234:recv:LATENCY:0:1.0"
  [dns]="dns://localhost:LATENCY:0:1.0"
  [time]="clock_gettime/realtime:OFFSET:0"
  [memory]="mmap/anon:LATENCY:0:1.0"
  [process]="fork:FAIL_AFTER:EAGAIN,999999"
)

# ------------------------------------------------------------------ #
# Helpers
# ------------------------------------------------------------------ #
RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; RESET='\033[0m'
info() { printf "${CYAN}▶ %s${RESET}\n" "$*" >&2; }

run_dd() {
  # Args: extra_env_string (e.g. "LD_PRELOAD=... CHAOS_IO_CONF=...")
  # Returns: MB/s throughput
  local extra_env="${1:-}"
  local total_ms=0

  for _ in $(seq 1 "$RUNS"); do
    local t_start t_end elapsed_ms mb_s
    t_start=$(python3 -c "import time; print(int(time.monotonic()*1000))")
    env $extra_env \
      dd if=/dev/zero of="$TMPFILE" \
         bs="$DD_BLOCK_SIZE" count="$DD_COUNT" \
         conv=fsync 2>/dev/null
    t_end=$(python3 -c "import time; print(int(time.monotonic()*1000))")
    elapsed_ms=$(( t_end - t_start ))
    total_ms=$(( total_ms + elapsed_ms ))
  done

  # Average elapsed → MB/s
  local avg_ms=$(( total_ms / RUNS ))
  local data_mb=$(( DD_BLOCK_SIZE_BYTES * DD_COUNT / 1024 / 1024 ))
  python3 -c "print(f'{$data_mb / ($avg_ms / 1000):.1f}')"
}

# Pre-compute block size in bytes (dd accepts k/m suffixes)
if [[ "$DD_BLOCK_SIZE" == *k ]]; then
  DD_BLOCK_SIZE_BYTES=$(( ${DD_BLOCK_SIZE%k} * 1024 ))
elif [[ "$DD_BLOCK_SIZE" == *m ]]; then
  DD_BLOCK_SIZE_BYTES=$(( ${DD_BLOCK_SIZE%m} * 1024 * 1024 ))
else
  DD_BLOCK_SIZE_BYTES="$DD_BLOCK_SIZE"
fi

cleanup() {
  rm -f "$TMPFILE" "$CONF_FILE"
}
trap cleanup EXIT

# ------------------------------------------------------------------ #
# Baseline (no LD_PRELOAD)
# ------------------------------------------------------------------ #
info "Measuring baseline (no LD_PRELOAD) ..."
BASELINE_MBPS=$(run_dd "")
info "Baseline: ${BASELINE_MBPS} MB/s"

# ------------------------------------------------------------------ #
# Per-library measurements
# ------------------------------------------------------------------ #
declare -A RESULT_NO_RULES
declare -A RESULT_ZERO_PROB
declare -A RESULT_LATENCY_ZERO

ORDERED_LIBS=(io net dns time memory process)

for lib in "${ORDERED_LIBS[@]}"; do
  SO_PATH="$CHAOS_LIBS_PATH/${LIBS[$lib]}"
  CONF_VAR="${CONF_VARS[$lib]}"

  if [ ! -f "$SO_PATH" ]; then
    info "SKIP $lib — $SO_PATH not found"
    RESULT_NO_RULES[$lib]="n/a"
    RESULT_ZERO_PROB[$lib]="n/a"
    RESULT_LATENCY_ZERO[$lib]="n/a"
    continue
  fi

  PRELOAD="LD_PRELOAD=$SO_PATH"
  CONF_ENV="$CONF_VAR=$CONF_FILE"

  # -- no_rules: empty config file --
  info "Measuring $lib no_rules ..."
  > "$CONF_FILE"
  RESULT_NO_RULES[$lib]=$(run_dd "$PRELOAD $CONF_ENV")

  # -- zero_prob: rule present but probability=0 --
  info "Measuring $lib zero_prob ..."
  echo "${ZERO_PROB_RULE[$lib]}" > "$CONF_FILE"
  RESULT_ZERO_PROB[$lib]=$(run_dd "$PRELOAD $CONF_ENV")

  # -- latency_zero: 100% match, 0ms latency (overhead of match + apply) --
  info "Measuring $lib latency_zero ..."
  echo "${LATENCY_ZERO_RULE[$lib]}" > "$CONF_FILE"
  RESULT_LATENCY_ZERO[$lib]=$(run_dd "$PRELOAD $CONF_ENV")

  info "$lib: no_rules=${RESULT_NO_RULES[$lib]} zero_prob=${RESULT_ZERO_PROB[$lib]} latency_zero=${RESULT_LATENCY_ZERO[$lib]} MB/s"
done

# ------------------------------------------------------------------ #
# Compute overhead percentage
# ------------------------------------------------------------------ #
pct_overhead() {
  local mbps="$1"
  if [[ "$mbps" == "n/a" ]]; then echo "n/a"; return; fi
  python3 -c "
base=$BASELINE_MBPS
val=$mbps
overhead = 100*(base-val)/base
print(f'{overhead:+.1f}%')
"
}

# ------------------------------------------------------------------ #
# Print Markdown table
# ------------------------------------------------------------------ #
cat <<'HEADER'

## libchaos-* Performance Benchmark

Workload: `dd if=/dev/zero of=<tmpfile> bs=64k count=512 conv=fsync`
(32 MiB per run, average of 5 runs)

Overhead values are relative to the baseline (no LD_PRELOAD).
A negative overhead means faster (within noise).

HEADER

printf "| %-12s | %-12s | %-14s | %-14s | %-16s |\n" \
  "library" "baseline" "no_rules" "zero_prob" "latency_zero(0ms)"
printf "|%s|%s|%s|%s|%s|\n" \
  "--------------" "--------------" "----------------" "----------------" "------------------"

printf "| %-12s | %-12s | %-14s | %-14s | %-16s |\n" \
  "*(none)*" "${BASELINE_MBPS} MB/s" "—" "—" "—"

for lib in "${ORDERED_LIBS[@]}"; do
  nr="${RESULT_NO_RULES[$lib]:-n/a}"
  zp="${RESULT_ZERO_PROB[$lib]:-n/a}"
  lz="${RESULT_LATENCY_ZERO[$lib]:-n/a}"

  nr_pct=$(pct_overhead "$nr")
  zp_pct=$(pct_overhead "$zp")
  lz_pct=$(pct_overhead "$lz")

  nr_cell="$nr MB/s ($nr_pct)"
  zp_cell="$zp MB/s ($zp_pct)"
  lz_cell="$lz MB/s ($lz_pct)"

  printf "| %-12s | %-12s | %-14s | %-14s | %-16s |\n" \
    "$lib" "${BASELINE_MBPS} MB/s" "$nr_cell" "$zp_cell" "$lz_cell"
done

cat <<'FOOTER'

**Column definitions**

| Column | What it measures |
|--------|-----------------|
| `baseline` | Pure `dd` with no library loaded |
| `no_rules` | Library in `LD_PRELOAD`, config file is empty — no rules matched |
| `zero_prob` | Rule is present and matched on every syscall, but `probability=0.0` — no fault injected |
| `latency_zero(0ms)` | Rule matches and fires on every call with `LATENCY:0` — measures match+dispatch overhead only |

The difference between `no_rules` and `zero_prob` is the rule-matching cost.
The difference between `zero_prob` and `latency_zero` is the fault-dispatch cost.
FOOTER
