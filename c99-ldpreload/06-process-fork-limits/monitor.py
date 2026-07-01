#!/usr/bin/env python3
"""
Gunicorn worker-count monitor for the fork-chaos demo.

Polls the /proc filesystem (Linux) or falls back to pgrep to count live
gunicorn worker processes.  Alerts when the count drops below the
configured target.

Expected outcome with libchaos-process injecting EAGAIN after 10 forks:
  - gunicorn starts 4 workers (forks 4 times)
  - If a worker dies, gunicorn tries to respawn (fork again)
  - After 10 total forks the chaos library makes fork() return EAGAIN
  - gunicorn logs "worker failed to boot" and the worker count drops
  - This monitor detects and reports the drop
"""

import os
import re
import subprocess
import time
from datetime import datetime
from typing import List

# ------------------------------------------------------------------ #
# Config
# ------------------------------------------------------------------ #
TARGET_WORKERS   = 4
POLL_INTERVAL    = 2.0   # seconds between polls
ALERT_THRESHOLD  = TARGET_WORKERS - 1  # alert if count falls below this
MONITOR_DURATION = 120   # seconds to run before printing summary
GUNICORN_URL     = "http://127.0.0.1:8000/"

# ------------------------------------------------------------------ #
# Worker detection
# ------------------------------------------------------------------ #
def count_gunicorn_workers_proc() -> int:
    """Count gunicorn worker processes via /proc (Linux only)."""
    count = 0
    try:
        for pid in os.listdir("/proc"):
            if not pid.isdigit():
                continue
            try:
                cmdline_path = f"/proc/{pid}/cmdline"
                with open(cmdline_path, "rb") as f:
                    cmdline = f.read().replace(b"\x00", b" ").decode(errors="replace")
                # gunicorn workers have "gunicorn" in cmdline but are not the master
                # The master shows "gunicorn master" in its status file
                status_path = f"/proc/{pid}/status"
                with open(status_path) as f:
                    status = f.read()
                # Workers are children of the master (PPid != 1)
                ppid_match = re.search(r"PPid:\s+(\d+)", status)
                if ppid_match and int(ppid_match.group(1)) > 1:
                    if "gunicorn" in cmdline:
                        count += 1
            except (PermissionError, FileNotFoundError, ProcessLookupError):
                continue
    except PermissionError:
        pass
    return count


def count_gunicorn_workers_pgrep() -> int:
    """Fallback: use pgrep to count gunicorn processes."""
    try:
        result = subprocess.run(
            ["pgrep", "-c", "-f", "gunicorn"],
            capture_output=True, text=True, timeout=5
        )
        # pgrep counts all gunicorn processes; subtract 1 for the master
        total = int(result.stdout.strip()) if result.returncode == 0 else 0
        return max(0, total - 1)
    except (subprocess.SubprocessError, ValueError, FileNotFoundError):
        return -1


def count_workers() -> int:
    """Count gunicorn workers using the best available method."""
    n = count_gunicorn_workers_proc()
    if n > 0:
        return n
    return count_gunicorn_workers_pgrep()


# ------------------------------------------------------------------ #
# HTTP probe
# ------------------------------------------------------------------ #
def probe_http() -> tuple:
    """Return (success, worker_pid) from a GET /."""
    import urllib.request
    import urllib.error
    import json
    try:
        with urllib.request.urlopen(GUNICORN_URL, timeout=3) as resp:
            data = json.loads(resp.read())
            return True, data.get("worker_pid", -1)
    except Exception:
        return False, -1


# ------------------------------------------------------------------ #
# Main monitoring loop
# ------------------------------------------------------------------ #
def main() -> None:
    print(f"Monitoring gunicorn workers (target={TARGET_WORKERS})")
    print(f"Alert threshold: worker count < {ALERT_THRESHOLD}")
    print(f"Polling every {POLL_INTERVAL}s for {MONITOR_DURATION}s\n")
    print(f"{'time':>8}  {'workers':>8}  {'http_ok':>8}  {'pid':>8}  {'alert':>8}")
    print("-" * 52)

    start = time.monotonic()
    alerts = 0
    samples: List[int] = []

    while time.monotonic() - start < MONITOR_DURATION:
        worker_count  = count_workers()
        http_ok, wpid = probe_http()
        samples.append(worker_count)

        alert = ""
        if worker_count >= 0 and worker_count < ALERT_THRESHOLD:
            alerts += 1
            alert = "ALERT"

        ts = datetime.now().strftime("%H:%M:%S")
        print(
            f"{ts:>8}  {worker_count:>8}  "
            f"{'yes' if http_ok else 'NO':>8}  "
            f"{wpid if wpid > 0 else '-':>8}  "
            f"{alert:>8}"
        )
        time.sleep(POLL_INTERVAL)

    # Summary
    valid = [s for s in samples if s >= 0]
    print()
    print("=" * 52)
    print("MONITOR SUMMARY")
    print("=" * 52)
    print(f"  Duration             : {MONITOR_DURATION}s")
    print(f"  Samples              : {len(samples)}")
    print(f"  Avg worker count     : {sum(valid)/len(valid):.1f}" if valid else "  No valid samples")
    print(f"  Min worker count     : {min(valid)}" if valid else "")
    print(f"  Alerts fired         : {alerts}")
    print()
    if alerts > 0:
        print(
            f"  RESULT: {alerts} worker-count alerts detected — fork EAGAIN is working"
        )
    else:
        print("  RESULT: No alerts — workers held steady (check chaos config)")
    print("=" * 52)


if __name__ == "__main__":
    main()
