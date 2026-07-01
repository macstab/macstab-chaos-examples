#!/usr/bin/env python3
"""
Redis chaos client — exercises SET/GET under network fault injection.

libchaos-net is loaded via LD_PRELOAD and injects:
  - 30 % ECONNREFUSED on connect()  → redis.exceptions.ConnectionError
  - 10 % EAGAIN on recv()           → redis.exceptions.TimeoutError
  -  5 % ECONNRESET on recv()       → redis.exceptions.ConnectionError

The client uses redis-py's built-in retry mechanism on top of a
connection pool so that transient failures are retried automatically;
only failures that exhaust all retries are counted as errors.
"""

import os
import sys
import time
import errno
import socket
import threading
from collections import defaultdict
from typing import Dict

import redis
from redis.retry import Retry
from redis.backoff import ExponentialBackoff
from redis.exceptions import ConnectionError as RedisConnError, TimeoutError as RedisTimeout

# ------------------------------------------------------------------ #
# Config
# ------------------------------------------------------------------ #
REDIS_HOST  = os.getenv("REDIS_HOST", "redis")
REDIS_PORT  = int(os.getenv("REDIS_PORT", 6379))
TOTAL_OPS   = 1000          # SET ops + GET ops per run
SLEEP_MS    = 10            # ms between operations
REPORT_SECS = 1.0           # print stats every N seconds
MAX_RETRIES = 3

# ------------------------------------------------------------------ #
# Stats (shared between the worker and the reporter)
# ------------------------------------------------------------------ #
_lock = threading.Lock()
_counters: Dict[str, int] = defaultdict(int)


def inc(key: str, n: int = 1) -> None:
    with _lock:
        _counters[key] += n


def snapshot() -> Dict[str, int]:
    with _lock:
        return dict(_counters)


# ------------------------------------------------------------------ #
# Redis client with retry
# ------------------------------------------------------------------ #
def make_client() -> redis.Redis:
    retry = Retry(ExponentialBackoff(cap=0.5, base=0.05), MAX_RETRIES)
    return redis.Redis(
        host=REDIS_HOST,
        port=REDIS_PORT,
        socket_timeout=2.0,
        socket_connect_timeout=2.0,
        retry=retry,
        retry_on_error=[RedisConnError, RedisTimeout],
        decode_responses=True,
    )


# ------------------------------------------------------------------ #
# Reporter thread
# ------------------------------------------------------------------ #
def reporter(stop_event: threading.Event) -> None:
    prev = snapshot()
    prev_time = time.monotonic()
    print(f"\n{'time':>6s}  {'ok/s':>6s}  {'refused':>8s}  {'timeout':>8s}  {'reset':>8s}  {'other':>6s}")
    print("-" * 60)

    while not stop_event.is_set():
        time.sleep(REPORT_SECS)
        now = snapshot()
        elapsed = time.monotonic() - prev_time

        delta_ok      = now.get("ok", 0)      - prev.get("ok", 0)
        delta_refused = now.get("refused", 0) - prev.get("refused", 0)
        delta_timeout = now.get("timeout", 0) - prev.get("timeout", 0)
        delta_reset   = now.get("reset", 0)   - prev.get("reset", 0)
        delta_other   = now.get("other", 0)   - prev.get("other", 0)

        ops_per_sec = delta_ok / elapsed if elapsed > 0 else 0.0
        print(
            f"{elapsed:6.1f}s  {ops_per_sec:6.1f}  "
            f"{delta_refused:>8d}  {delta_timeout:>8d}  "
            f"{delta_reset:>8d}  {delta_other:>6d}"
        )
        prev = now
        prev_time = time.monotonic()


# ------------------------------------------------------------------ #
# Main workload
# ------------------------------------------------------------------ #
def run_workload(client: redis.Redis) -> None:
    stop = threading.Event()
    t = threading.Thread(target=reporter, args=(stop,), daemon=True)
    t.start()

    for i in range(TOTAL_OPS):
        key = f"chaos:key:{i % 200}"
        val = f"value-{i}"

        # --- SET ---
        try:
            client.set(key, val)
            inc("ok")
        except RedisConnError as e:
            msg = str(e).lower()
            if "connection refused" in msg or "econnrefused" in msg:
                inc("refused")
            elif "reset" in msg or "econnreset" in msg:
                inc("reset")
            else:
                inc("other")
        except RedisTimeout:
            inc("timeout")
        except Exception:
            inc("other")

        # --- GET ---
        try:
            client.get(key)
            inc("ok")
        except RedisConnError as e:
            msg = str(e).lower()
            if "connection refused" in msg or "econnrefused" in msg:
                inc("refused")
            elif "reset" in msg or "econnreset" in msg:
                inc("reset")
            else:
                inc("other")
        except RedisTimeout:
            inc("timeout")
        except Exception:
            inc("other")

        time.sleep(SLEEP_MS / 1000.0)

    stop.set()
    t.join()


# ------------------------------------------------------------------ #
# Entry point
# ------------------------------------------------------------------ #
def main() -> None:
    print(f"Connecting to Redis at {REDIS_HOST}:{REDIS_PORT}")
    client = make_client()

    # Verify baseline connectivity (before chaos may have been loaded)
    try:
        client.ping()
        print("Redis PING OK — starting workload\n")
    except Exception as exc:
        print(f"WARNING: initial PING failed: {exc}")

    start = time.monotonic()
    run_workload(client)
    elapsed = time.monotonic() - start

    final = snapshot()
    total = sum(final.values())
    ok    = final.get("ok", 0)
    errs  = total - ok

    print("\n" + "=" * 60)
    print("FINAL SUMMARY")
    print("=" * 60)
    print(f"  Total operations : {total}")
    print(f"  Successful       : {ok}  ({100*ok/total:.1f}%)")
    print(f"  ECONNREFUSED     : {final.get('refused', 0)}")
    print(f"  EAGAIN/Timeout   : {final.get('timeout', 0)}")
    print(f"  ECONNRESET       : {final.get('reset', 0)}")
    print(f"  Other errors     : {final.get('other', 0)}")
    print(f"  Total errors     : {errs}  ({100*errs/total:.1f}%)")
    print(f"  Elapsed          : {elapsed:.1f}s")
    print(f"  Throughput       : {ok/elapsed:.1f} ok ops/s")
    print("=" * 60)


if __name__ == "__main__":
    main()
