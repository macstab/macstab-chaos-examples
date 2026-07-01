#!/usr/bin/env python3
"""
Load generator for the full-stack chaos demo.

Sends requests to the Flask /chaos endpoint at 10 req/s and prints live
per-domain error rates every second.

Output columns:
  t       — elapsed seconds
  rps     — successful requests per second
  io%     — IO error rate (EIO)
  net%    — Network error rate (EAGAIN/ECONNREFUSED)
  dns%    — DNS error rate (EAI_AGAIN)
  mem%    — Memory error rate (ENOMEM)
  proc%   — Fork error rate (EAGAIN)
  p50/p95 — request latency percentiles (ms)
"""

import os
import sys
import time
import threading
import statistics
from collections import deque, defaultdict
from typing import Dict, Deque

import requests

# ------------------------------------------------------------------ #
# Config
# ------------------------------------------------------------------ #
FLASK_URL      = os.getenv("FLASK_URL", "http://localhost:5000")
TARGET_RPS     = 10
RUN_SECS       = 120
REPORT_EVERY   = 1.0   # seconds between stat lines
WINDOW_SECS    = 5.0   # sliding window for rate calculations

# ------------------------------------------------------------------ #
# Shared state
# ------------------------------------------------------------------ #
_lock       = threading.Lock()
_events: Deque[dict] = deque()  # ring buffer of request events
_stop       = threading.Event()

# Per-domain cumulative counters (read from Flask /stats)
_last_stats: Dict[str, int] = defaultdict(int)
_prev_stats: Dict[str, int] = defaultdict(int)


def record(event: dict) -> None:
    event["t"] = time.monotonic()
    with _lock:
        _events.append(event)
        # Trim events older than WINDOW_SECS
        cutoff = event["t"] - WINDOW_SECS
        while _events and _events[0]["t"] < cutoff:
            _events.popleft()


# ------------------------------------------------------------------ #
# Request worker
# ------------------------------------------------------------------ #
def worker() -> None:
    interval = 1.0 / TARGET_RPS
    session  = requests.Session()

    while not _stop.is_set():
        t0 = time.monotonic()
        try:
            resp    = session.get(f"{FLASK_URL}/chaos", timeout=10)
            elapsed = (time.monotonic() - t0) * 1000
            if resp.status_code == 200:
                data = resp.json()
                record({"ok": True, "latency_ms": elapsed,
                        "stats": data.get("cumulative_stats", {})})
            else:
                record({"ok": False, "latency_ms": elapsed,
                        "http_status": resp.status_code, "stats": {}})
        except requests.RequestException as e:
            elapsed = (time.monotonic() - t0) * 1000
            record({"ok": False, "latency_ms": elapsed, "error": str(e), "stats": {}})

        # Sleep to hit target RPS; subtract time already spent
        spent = time.monotonic() - t0
        remaining = interval - spent
        if remaining > 0:
            time.sleep(remaining)


# ------------------------------------------------------------------ #
# Reporter
# ------------------------------------------------------------------ #
def pct(fail: int, total: int) -> str:
    if total == 0:
        return "  n/a"
    return f"{100*fail/total:5.1f}%"


def reporter() -> None:
    global _last_stats, _prev_stats
    start = time.monotonic()
    prev_report = start

    header = (
        f"{'t':>5}  {'rps':>5}  {'io%':>6}  {'net%':>6}  "
        f"{'dns%':>6}  {'mem%':>6}  {'proc%':>6}  {'p50':>7}  {'p95':>7}"
    )
    print(header)
    print("-" * len(header))

    while not _stop.is_set():
        time.sleep(REPORT_EVERY)
        now = time.monotonic()
        elapsed_total = now - start
        window = WINDOW_SECS

        with _lock:
            recent = [e for e in _events if e["t"] >= now - window]

        if not recent:
            continue

        # Latency
        latencies = [e["latency_ms"] for e in recent if e.get("ok")]
        p50 = statistics.median(latencies) if latencies else 0.0
        p95 = (sorted(latencies)[int(len(latencies) * 0.95)]
               if len(latencies) > 1 else 0.0)

        ok_count  = sum(1 for e in recent if e.get("ok"))
        rps       = ok_count / window

        # Pull the latest cumulative stats from the most recent OK response
        latest_stats: Dict[str, int] = defaultdict(int)
        for e in reversed(recent):
            if e.get("stats"):
                latest_stats = defaultdict(int, e["stats"])
                break

        # Delta against previous report
        io_fail    = latest_stats["io_fail"]   - _prev_stats["io_fail"]
        io_ok      = latest_stats["io_ok"]     - _prev_stats["io_ok"]
        net_fail   = latest_stats["net_fail"]  - _prev_stats["net_fail"]
        net_ok     = latest_stats["net_ok"]    - _prev_stats["net_ok"]
        dns_fail   = latest_stats["dns_fail"]  - _prev_stats["dns_fail"]
        dns_ok     = latest_stats["dns_ok"]    - _prev_stats["dns_ok"]
        mem_fail   = latest_stats["mem_fail"]  - _prev_stats["mem_fail"]
        mem_ok     = latest_stats["mem_ok"]    - _prev_stats["mem_ok"]
        proc_fail  = latest_stats["proc_fail"] - _prev_stats["proc_fail"]
        proc_ok    = latest_stats["proc_ok"]   - _prev_stats["proc_ok"]
        _prev_stats = dict(latest_stats)

        print(
            f"{elapsed_total:5.0f}s"
            f"  {rps:5.1f}"
            f"  {pct(io_fail, io_ok+io_fail):>6}"
            f"  {pct(net_fail, net_ok+net_fail):>6}"
            f"  {pct(dns_fail, dns_ok+dns_fail):>6}"
            f"  {pct(mem_fail, mem_ok+mem_fail):>6}"
            f"  {pct(proc_fail, proc_ok+proc_fail):>6}"
            f"  {p50:6.0f}ms"
            f"  {p95:6.0f}ms"
        )
        prev_report = now


# ------------------------------------------------------------------ #
# Main
# ------------------------------------------------------------------ #
def main() -> None:
    print(f"Load generator → {FLASK_URL}/chaos")
    print(f"Target: {TARGET_RPS} req/s for {RUN_SECS}s\n")
    print("Expected error rates:")
    print("  IO:      ~5%  EIO on /tmp writes")
    print("  NET:     ~10% EAGAIN on Redis recv")
    print("  DNS:     ~15% EAI_AGAIN on hostname resolution")
    print("  MEMORY:  ~0.5% ENOMEM on large allocations")
    print("  PROCESS: EAGAIN after 50 cumulative forks")
    print()

    workers = [threading.Thread(target=worker, daemon=True) for _ in range(TARGET_RPS)]
    rep     = threading.Thread(target=reporter, daemon=True)

    rep.start()
    for w in workers:
        w.start()

    time.sleep(RUN_SECS)
    _stop.set()

    for w in workers:
        w.join(timeout=5)
    rep.join(timeout=3)

    # Final stats from /stats endpoint
    print()
    print("=" * 70)
    print("FINAL CUMULATIVE STATS (from Flask /stats)")
    print("=" * 70)
    try:
        resp  = requests.get(f"{FLASK_URL}/stats", timeout=5)
        final = resp.json()
        total_reqs = final.get("req_total", 0)
        print(f"  Total requests    : {total_reqs}")
        for domain in ("io", "net", "dns", "mem", "proc"):
            ok   = final.get(f"{domain}_ok", 0)
            fail = final.get(f"{domain}_fail", 0)
            tot  = ok + fail
            print(
                f"  {domain.upper():8s} ok={ok:<6d} fail={fail:<6d} "
                f"err%={100*fail/tot:.1f}%" if tot > 0 else
                f"  {domain.upper():8s} no data"
            )
    except Exception as e:
        print(f"  (could not fetch /stats: {e})")
    print("=" * 70)


if __name__ == "__main__":
    main()
