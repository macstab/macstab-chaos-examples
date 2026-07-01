#!/usr/bin/env bash
# runtime-config-reload-demo.sh
#
# Demonstrates hot-reload of libchaos-io config WITHOUT restarting the
# target process.  The library re-reads its config file on every
# intercepted syscall — there is no signal, no IPC, no daemon required.
#
# What it shows:
#   Phase 1 — No config   → 0%   errors,  measure baseline throughput
#   Phase 2 — 10% EIO     → ~10% errors,  throughput drops slightly
#   Phase 3 — 40% EIO     → ~40% errors,  noticeable throughput drop
#   Phase 4 — +200ms LAT  → 0%   errors,  but throughput collapses
#   Phase 5 — EIO removed, LAT kept → errors=0, throughput still low
#   Phase 6 — Empty config → full recovery
#
# Prerequisites:
#   $CHAOS_LIBS_PATH  must point to the directory containing the .so files
#   python3           must be available in $PATH
#   The target process writes to /tmp/chaos-reload-test/ which is where
#   the IO chaos rules apply.

set -euo pipefail

# ------------------------------------------------------------------ #
# Setup
# ------------------------------------------------------------------ #
CHAOS_LIBS_PATH="${CHAOS_LIBS_PATH:-$(dirname "$0")/../../../dist}"
CHAOS_LIB="$CHAOS_LIBS_PATH/libchaos-io-musl-amd64.so"

WORK_DIR="$(mktemp -d /tmp/chaos-reload-XXXXXX)"
CONF_FILE="$WORK_DIR/.chaos-io.conf"
LOG_FILE="$WORK_DIR/worker.log"
METRICS_FILE="$WORK_DIR/metrics.jsonl"
WORKER_PID_FILE="$WORK_DIR/worker.pid"

# ANSI
BOLD='\033[1m'; CYAN='\033[0;36m'; GREEN='\033[0;32m'
YELLOW='\033[1;33m'; RED='\033[0;31m'; RESET='\033[0m'

banner() { printf "\n${CYAN}${BOLD}━━━ %s ━━━${RESET}\n\n" "$*"; }
info()   { printf "${GREEN}▶ %s${RESET}\n" "$*"; }
warn()   { printf "${YELLOW}⚠ %s${RESET}\n" "$*"; }

# ------------------------------------------------------------------ #
# Worker script (Python) — runs in background with LD_PRELOAD
# Writes to the chaos-targeted directory and appends JSON metrics
# ------------------------------------------------------------------ #
WORKER_SCRIPT="$WORK_DIR/worker.py"
cat > "$WORKER_SCRIPT" <<'PYEOF'
#!/usr/bin/env python3
"""
Background IO worker.  Writes small files to the chaos-targeted
directory as fast as possible and appends one JSON line per second
to the metrics file with:
  {"ts": <epoch>, "ok": <count>, "fail": <count>}
"""
import os, sys, time, json, signal

WORK_DIR    = sys.argv[1]
METRICS     = sys.argv[2]
TARGET_DIR  = os.path.join(WORK_DIR, "writes")
os.makedirs(TARGET_DIR, exist_ok=True)

running = True
def _stop(sig, frame):
    global running
    running = False
signal.signal(signal.SIGTERM, _stop)
signal.signal(signal.SIGINT,  _stop)

ok_count = fail_count = 0
epoch_start = int(time.time())
seq = 0

with open(METRICS, "a") as mf:
    while running:
        second_start = time.monotonic()
        ok_s = fail_s = 0

        while time.monotonic() - second_start < 1.0 and running:
            path = os.path.join(TARGET_DIR, f"f-{seq}.dat")
            seq += 1
            try:
                with open(path, "wb") as f:
                    f.write(b"X" * 4096)
                os.unlink(path)
                ok_s   += 1
                ok_count += 1
            except OSError:
                fail_s   += 1
                fail_count += 1

        ts = int(time.time())
        mf.write(json.dumps({"ts": ts, "ok": ok_s, "fail": fail_s}) + "\n")
        mf.flush()

PYEOF

# ------------------------------------------------------------------ #
# Metrics reader
# ------------------------------------------------------------------ #
read_metrics() {
  # Read the last N complete lines from the metrics file.
  local n="${1:-3}"
  tail -n "$n" "$METRICS_FILE" 2>/dev/null || true
}

aggregate() {
  # Sum ok/fail from all lines written during a phase.
  # Args: phase_start_line  phase_end_line
  local start_line="$1" end_line="$2"
  python3 - "$METRICS_FILE" "$start_line" "$end_line" <<'PYEOF'
import sys, json
path, s, e = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
lines = open(path).readlines()
ok = fail = 0
for line in lines[s:e]:
    try:
        d = json.loads(line)
        ok   += d.get("ok",   0)
        fail += d.get("fail", 0)
    except Exception:
        pass
total = ok + fail
pct   = 100*fail/total if total > 0 else 0.0
tput  = ok  # ops/s is sum of ok over the lines (each line = 1 second)
print(f"ok={ok} fail={fail} err%={pct:.1f} throughput~{ok//max(1,e-s)}/s")
PYEOF
}

metric_line_count() {
  wc -l < "$METRICS_FILE" 2>/dev/null || echo 0
}

write_conf() {
  printf '%s\n' "$1" > "$CONF_FILE"
  info "Config → $(cat "$CONF_FILE")"
}

clear_conf() {
  > "$CONF_FILE"
  info "Config cleared (empty)"
}

# ------------------------------------------------------------------ #
# Start the worker
# ------------------------------------------------------------------ #
banner "Starting background IO worker with LD_PRELOAD"

if [ ! -f "$CHAOS_LIB" ]; then
  warn "Library not found at $CHAOS_LIB"
  warn "Set CHAOS_LIBS_PATH to the directory containing the .so files"
  warn "Continuing without LD_PRELOAD (chaos will not be injected)"
  PRELOAD_ENV=""
else
  PRELOAD_ENV="LD_PRELOAD=$CHAOS_LIB"
  info "LD_PRELOAD=$CHAOS_LIB"
fi

# Touch conf file so the library sees it immediately
touch "$CONF_FILE"
export CHAOS_IO_CONF="$CONF_FILE"

env $PRELOAD_ENV \
  CHAOS_IO_CONF="$CONF_FILE" \
  python3 "$WORKER_SCRIPT" "$WORK_DIR" "$METRICS_FILE" \
  > "$LOG_FILE" 2>&1 &

WORKER_PID=$!
echo "$WORKER_PID" > "$WORKER_PID_FILE"
info "Worker PID: $WORKER_PID (writing to $WORK_DIR/writes/)"

sleep 2   # let the worker get going

# ------------------------------------------------------------------ #
declare -A PHASE_RESULT

run_phase() {
  local name="$1" secs="$2"
  local line_before line_after
  line_before=$(metric_line_count)
  info "Running phase '$name' for ${secs}s ..."
  sleep "$secs"
  line_after=$(metric_line_count)
  PHASE_RESULT[$name]=$(aggregate "$line_before" "$line_after")
  info "Phase '$name': ${PHASE_RESULT[$name]}"
}

# ------------------------------------------------------------------ #
banner "PHASE 1 — No chaos (0% errors expected)"
clear_conf
run_phase "baseline" 5

banner "PHASE 2 — 10% EIO on writes (hot-reload)"
write_conf "$WORK_DIR/writes:write:ERRNO:EIO:0.10"
warn "Config reloaded — no process restart"
run_phase "10pct_eio" 5

banner "PHASE 3 — 40% EIO (hot-reload again)"
write_conf "$WORK_DIR/writes:write:ERRNO:EIO:0.40"
warn "Changed 0.10 → 0.40 without touching the process"
run_phase "40pct_eio" 5

banner "PHASE 4 — Add 200ms LATENCY rule, keep 40% EIO"
write_conf "$(printf '%s\n%s' \
  "$WORK_DIR/writes:write:ERRNO:EIO:0.40" \
  "$WORK_DIR/writes:write:LATENCY:200:1.00")"
warn "Throughput will collapse — 200ms per write * 100% probability"
run_phase "40pct_eio_200ms" 10

banner "PHASE 5 — Remove EIO, keep 200ms latency"
write_conf "$WORK_DIR/writes:write:LATENCY:200:1.00"
warn "Errors should drop to 0, throughput stays low"
run_phase "latency_only" 10

banner "PHASE 6 — Full recovery (empty config)"
clear_conf
run_phase "recovery" 5

# ------------------------------------------------------------------ #
banner "Stopping worker"
kill "$WORKER_PID" 2>/dev/null || true
wait "$WORKER_PID" 2>/dev/null || true
info "Worker stopped"

# ------------------------------------------------------------------ #
banner "SUMMARY TABLE"
printf "\n${BOLD}%-22s  %-50s${RESET}\n" "Phase" "Result"
printf '%s\n' "$(printf '%0.s-' {1..75})"
for phase in baseline 10pct_eio 40pct_eio 40pct_eio_200ms latency_only recovery; do
  printf "%-22s  %s\n" "$phase" "${PHASE_RESULT[$phase]:-n/a}"
done
echo ""

# Cleanup
rm -rf "$WORK_DIR"
info "Done. Temp dir removed."
