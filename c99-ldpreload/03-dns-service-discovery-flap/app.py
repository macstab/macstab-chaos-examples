#!/usr/bin/env python3
"""
DNS chaos client — calls a downstream service 500 times, forcing DNS
resolution on every request.  libchaos-dns is loaded via LD_PRELOAD and
injects:
  - 30 % EAI_AGAIN on getaddrinfo() for 'downstream'
  - 20 % 500 ms artificial latency on the same resolution

Strategy:
  - Retry on EAI_AGAIN up to MAX_RETRIES times with exponential back-off
  - Track dns_ok / dns_retry_ok / dns_failed / http_ok / http_fail
  - Print per-10-request stats plus latency percentiles at the end
"""

import os
import socket
import time
import http.client
import statistics
from typing import List

# ------------------------------------------------------------------ #
# Config
# ------------------------------------------------------------------ #
HOST        = os.getenv("DOWNSTREAM_HOST", "downstream")
PORT        = int(os.getenv("DOWNSTREAM_PORT", 8080))
PATH        = "/api/data"
TOTAL_REQS  = 500
MAX_RETRIES = 3
RETRY_BASE  = 0.05   # seconds
REPORT_EVERY = 10    # print stats every N requests

# ------------------------------------------------------------------ #
# Counters
# ------------------------------------------------------------------ #
dns_ok         = 0
dns_retry_ok   = 0
dns_failed     = 0
http_ok        = 0
http_fail      = 0
latencies: List[float] = []   # end-to-end latency per successful request

# ------------------------------------------------------------------ #
# Helpers
# ------------------------------------------------------------------ #
def resolve_with_retry(hostname: str) -> List[tuple]:
    """
    Call socket.getaddrinfo() and retry on EAI_AGAIN.
    Returns the address list on success, raises socket.gaierror on
    permanent failure.
    """
    global dns_ok, dns_retry_ok, dns_failed

    last_exc = None
    for attempt in range(MAX_RETRIES + 1):
        t0 = time.monotonic()
        try:
            addrs = socket.getaddrinfo(hostname, PORT, socket.AF_INET,
                                       socket.SOCK_STREAM)
            elapsed_ms = (time.monotonic() - t0) * 1000
            if attempt == 0:
                dns_ok += 1
            else:
                dns_retry_ok += 1
            return addrs, elapsed_ms
        except socket.gaierror as exc:
            last_exc = exc
            # EAI_AGAIN == -3 on Linux, also surfaced as errno 11 (EAGAIN)
            if exc.args[0] in (socket.EAI_AGAIN, -3, 11):
                if attempt < MAX_RETRIES:
                    delay = RETRY_BASE * (2 ** attempt)
                    time.sleep(delay)
                    continue
            # Permanent or exhausted retries
            dns_failed += 1
            raise

    dns_failed += 1
    raise last_exc  # type: ignore[misc]


def do_request(hostname: str) -> bool:
    """Return True on HTTP 2xx, False otherwise."""
    global http_ok, http_fail

    t_start = time.monotonic()
    try:
        # Force DNS resolution by using http.client directly with the hostname.
        # http.client will call socket.getaddrinfo internally — that is where
        # libchaos-dns intercepts.
        conn = http.client.HTTPConnection(hostname, PORT, timeout=5)
        conn.request("GET", PATH)
        resp = conn.getresponse()
        resp.read()
        conn.close()
        elapsed = time.monotonic() - t_start
        if 200 <= resp.status < 300:
            http_ok += 1
            latencies.append(elapsed * 1000)
            return True
        else:
            http_fail += 1
            return False
    except socket.gaierror:
        # DNS failure already counted in resolve_with_retry
        http_fail += 1
        return False
    except OSError:
        http_fail += 1
        return False


def print_stats(req_num: int) -> None:
    total = req_num
    p50 = statistics.median(latencies) if latencies else 0.0
    p95 = sorted(latencies)[int(len(latencies) * 0.95)] if len(latencies) > 1 else 0.0
    print(
        f"  req {req_num:4d}/{TOTAL_REQS}"
        f"  http_ok={http_ok:<4d}"
        f"  http_fail={http_fail:<4d}"
        f"  dns_ok={dns_ok:<4d}"
        f"  dns_retry={dns_retry_ok:<4d}"
        f"  dns_fail={dns_failed:<4d}"
        f"  p50={p50:6.1f}ms  p95={p95:6.1f}ms"
    )


# ------------------------------------------------------------------ #
# Main
# ------------------------------------------------------------------ #
def main() -> None:
    print(f"Target: http://{HOST}:{PORT}{PATH}")
    print(f"Total requests: {TOTAL_REQS}  max_retries_per_dns: {MAX_RETRIES}")
    print(f"Expected: ~30% EAI_AGAIN (retried), ~20% slow DNS (+500ms)")
    print()
    print(f"{'req':>6}  {'http_ok':>8}  {'http_fail':>9}  {'dns_ok':>7}  "
          f"{'dns_retry':>9}  {'dns_fail':>8}  {'p50':>8}  {'p95':>8}")
    print("-" * 80)

    for i in range(1, TOTAL_REQS + 1):
        do_request(HOST)
        if i % REPORT_EVERY == 0:
            print_stats(i)

    print()
    print("=" * 60)
    print("FINAL SUMMARY")
    print("=" * 60)
    total = http_ok + http_fail
    print(f"  HTTP requests    : {total}")
    print(f"  HTTP 2xx         : {http_ok}  ({100*http_ok/total:.1f}%)")
    print(f"  HTTP failures    : {http_fail}  ({100*http_fail/total:.1f}%)")
    print()
    print(f"  DNS resolutions  : {dns_ok + dns_retry_ok + dns_failed}")
    print(f"  DNS ok (first)   : {dns_ok}")
    print(f"  DNS ok (retry)   : {dns_retry_ok}")
    print(f"  DNS failed       : {dns_failed}")
    if latencies:
        sorted_lat = sorted(latencies)
        p50 = statistics.median(sorted_lat)
        p95 = sorted_lat[int(len(sorted_lat) * 0.95)]
        p99 = sorted_lat[int(len(sorted_lat) * 0.99)]
        print()
        print(f"  Latency p50      : {p50:.1f} ms")
        print(f"  Latency p95      : {p95:.1f} ms  (elevated by 500ms DNS latency)")
        print(f"  Latency p99      : {p99:.1f} ms")
    print("=" * 60)


if __name__ == "__main__":
    main()
