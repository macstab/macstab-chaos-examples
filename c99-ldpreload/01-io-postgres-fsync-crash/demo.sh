#!/usr/bin/env bash
# demo.sh — Interactive hot-reload demo for IO chaos on PostgreSQL
#
# What it shows:
#   1. Start Compose (postgres + LD_PRELOAD chaos)
#   2. No config → 0 % error rate
#   3. Write config → 10 % EIO on writes
#   4. Run 200 INSERTs → ~10 % fail
#   5. Hot-reload to 40 % EIO → NO container restart
#   6. Run 200 more INSERTs → ~40 % fail
#   7. Clear config → 100 % recovery
#   8. Final summary table
#
# Prerequisites:
#   CHAOS_LIBS_PATH env var pointing to the directory with the .so files,
#   or the default "../../../dist" must be correct relative to this script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
CONF_CONTAINER="chaos-probe"
# chaos-conf volume is mounted at /tmp in chaos-probe, postgres and app —
# postgres reads /tmp/.chaos-io.conf, so the config MUST live there.
CONF_PATH="/tmp/.chaos-io.conf"

# ANSI colours
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

banner() { echo -e "\n${CYAN}${BOLD}━━━ $* ━━━${RESET}\n"; }
info()   { echo -e "${GREEN}▶ $*${RESET}"; }
warn()   { echo -e "${YELLOW}⚠ $*${RESET}"; }

# Track results across phases
declare -A PHASE_OK PHASE_FAIL

# ------------------------------------------------------------------ #
run_inserts() {
  local label="$1"
  local count="$2"
  local ok=0 fail=0

  info "Running $count INSERT batches (phase: $label)..."
  for i in $(seq 1 "$count"); do
    sql="BEGIN;"
    for j in $(seq 1 20); do
      sql+=" INSERT INTO chaos_test(batch, payload) VALUES($i, 'demo-$label-$i-$j');"
    done
    sql+=" COMMIT;"

    if docker compose -f "$COMPOSE_FILE" exec -T postgres \
        psql -U chaos -d chaosdb -c "$sql" &>/dev/null; then
      ok=$((ok+1))
    else
      fail=$((fail+1))
    fi
    printf "  batch %3d/%d  ok=%-4d fail=%d\r" "$i" "$count" "$ok" "$fail"
  done
  echo ""
  PHASE_OK[$label]=$ok
  PHASE_FAIL[$label]=$fail
}

write_conf() {
  local content="$1"
  docker compose -f "$COMPOSE_FILE" exec -T "$CONF_CONTAINER" \
    sh -c "printf '%s\n' '$content' > $CONF_PATH"
  info "Config written → $(docker compose -f "$COMPOSE_FILE" exec -T "$CONF_CONTAINER" cat $CONF_PATH)"
}

clear_conf() {
  docker compose -f "$COMPOSE_FILE" exec -T "$CONF_CONTAINER" \
    sh -c "> $CONF_PATH"
  info "Config cleared (empty file — 0 % error rate)"
}

# ------------------------------------------------------------------ #
banner "STEP 1 — Starting Docker Compose"
export CHAOS_LIBS_PATH="${CHAOS_LIBS_PATH:-$SCRIPT_DIR/../../../dist}"
docker compose -f "$COMPOSE_FILE" up -d --remove-orphans
echo ""

banner "STEP 2 — Waiting for PostgreSQL to be ready"
attempt=0
until docker compose -f "$COMPOSE_FILE" exec -T postgres \
    pg_isready -U chaos -d chaosdb &>/dev/null; do
  attempt=$((attempt+1))
  if [ "$attempt" -gt 30 ]; then
    echo "PostgreSQL never became ready — aborting." >&2
    docker compose -f "$COMPOSE_FILE" logs postgres
    exit 1
  fi
  echo -n "."
  sleep 2
done
echo ""
info "PostgreSQL is ready"

banner "STEP 3 — Create test table"
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  psql -U chaos -d chaosdb -c \
  "DROP TABLE IF EXISTS chaos_test;
   CREATE TABLE chaos_test (
     id      SERIAL PRIMARY KEY,
     batch   INT NOT NULL,
     payload TEXT NOT NULL,
     ts      TIMESTAMPTZ DEFAULT now()
   );"

banner "STEP 4 — Baseline: NO chaos config (0 % errors expected)"
clear_conf
sleep 1   # allow one hot-reload cycle
run_inserts "baseline" 20
info "Baseline: ok=${PHASE_OK[baseline]}  fail=${PHASE_FAIL[baseline]}"

banner "STEP 5 — Enable 10 % EIO on writes (NO restart)"
write_conf "/var/lib/postgresql/data:write:EIO:0.1"
sleep 1   # library re-reads config within one mmap page-fault cycle
run_inserts "10pct" 20
info "10% phase: ok=${PHASE_OK[10pct]}  fail=${PHASE_FAIL[10pct]}"

banner "STEP 6 — Hot-reload to 40 % EIO — NO container restart"
warn "Changing probability from 0.1 → 0.4 via file write only"
write_conf "/var/lib/postgresql/data:write:EIO:0.4"
sleep 1
run_inserts "40pct" 20
info "40% phase: ok=${PHASE_OK[40pct]}  fail=${PHASE_FAIL[40pct]}"

banner "STEP 7 — Clear config → full recovery"
clear_conf
sleep 1
run_inserts "recovery" 20
info "Recovery: ok=${PHASE_OK[recovery]}  fail=${PHASE_FAIL[recovery]}"

banner "STEP 8 — Row count (only committed data survives)"
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  psql -U chaos -d chaosdb \
  -c "SELECT batch, COUNT(*) AS rows FROM chaos_test GROUP BY batch ORDER BY batch;"

banner "STEP 9 — Final summary"
printf "\n${BOLD}%-12s %8s %8s %10s${RESET}\n" "Phase" "OK" "Fail" "Error%"
printf "%s\n" "------------------------------------"
for phase in baseline 10pct 40pct recovery; do
  ok=${PHASE_OK[$phase]}
  fail=${PHASE_FAIL[$phase]}
  total=$((ok+fail))
  if [ "$total" -gt 0 ]; then
    pct=$(awk "BEGIN{printf \"%.1f\", 100*$fail/$total}")
  else
    pct="0.0"
  fi
  printf "%-12s %8d %8d %9s%%\n" "$phase" "$ok" "$fail" "$pct"
done
echo ""

banner "STEP 10 — Tearing down"
docker compose -f "$COMPOSE_FILE" down -v
info "Done."
