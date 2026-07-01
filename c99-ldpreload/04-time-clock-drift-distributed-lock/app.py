#!/usr/bin/env python3
"""
Distributed lock demo with clock-drift detection.

Architecture:
  - Two "workers" (threads) compete for a single Redis SETNX lock.
  - Lock TTL is set to LOCK_TTL_SECS in Redis.
  - Each holder validates the lock by comparing the lock's issue
    timestamp (stored in the key value) with time.time().
  - libchaos-time is loaded via LD_PRELOAD and offsets CLOCK_REALTIME
    by +5000 ms, so time.time() returns values 5 seconds ahead.

Expected behaviour with drift:
  - Worker A acquires the lock at T=0, sets Redis TTL = 3 s.
  - Worker A calls time.time() → sees T+5 s → thinks TTL already
    expired → releases the lock early.
  - Worker B immediately acquires it.
  - Worker A still believes it holds the lock → split_brain_detected.

Counters:
  lock_acquired       — successful SETNX
  lock_stolen         — lock holder found its own lock gone in Redis
  split_brain_detected — two threads simultaneously believe they hold
  lock_released_early  — holder detected own TTL expired via drifted clock
"""

import os
import sys
import time
import uuid
import threading
from typing import Optional

import redis

# ------------------------------------------------------------------ #
# Config
# ------------------------------------------------------------------ #
REDIS_HOST     = os.getenv("REDIS_HOST", "redis")
REDIS_PORT     = int(os.getenv("REDIS_PORT", 6379))
LOCK_KEY       = "chaos:distributed-lock"
LOCK_TTL_SECS  = 3          # Redis TTL
HOLD_WORK_SECS = 1          # simulated critical-section duration
NUM_WORKERS    = 2
ITERATIONS     = 30         # lock acquire cycles per worker

# ------------------------------------------------------------------ #
# Shared state
# ------------------------------------------------------------------ #
_stats_lock = threading.Lock()
lock_acquired        = 0
lock_stolen          = 0
split_brain_detected = 0
lock_released_early  = 0

# Shared "belief" — which worker thinks it holds the lock right now
_current_holder: Optional[str] = None
_belief_lock = threading.Lock()


def inc(name: str) -> None:
    global lock_acquired, lock_stolen, split_brain_detected, lock_released_early
    with _stats_lock:
        if name == "lock_acquired":
            lock_acquired += 1
        elif name == "lock_stolen":
            lock_stolen += 1
        elif name == "split_brain_detected":
            split_brain_detected += 1
        elif name == "lock_released_early":
            lock_released_early += 1


# ------------------------------------------------------------------ #
# Lock implementation
# ------------------------------------------------------------------ #
class DistributedLock:
    """SETNX-based distributed lock with clock-drift awareness."""

    def __init__(self, r: redis.Redis, worker_id: str) -> None:
        self.r = r
        self.worker_id = worker_id
        self.token: Optional[str] = None
        self.acquired_at: Optional[float] = None

    def acquire(self) -> bool:
        """Try to acquire the lock.  Returns True on success."""
        token = str(uuid.uuid4())
        # SETNX + EXPIRE in one atomic call (SET ... NX PX)
        acquired = self.r.set(
            LOCK_KEY,
            f"{self.worker_id}:{token}:{time.time()}",
            nx=True,
            ex=LOCK_TTL_SECS,
        )
        if acquired:
            self.token = token
            self.acquired_at = time.time()   # <-- drifted clock!
            inc("lock_acquired")
            return True
        return False

    def check_still_held(self) -> bool:
        """
        Verify the lock value in Redis still matches our token.
        Also check whether our drifted clock says the TTL has expired.
        """
        global _current_holder

        val = self.r.get(LOCK_KEY)
        if val is None or self.token not in val:
            inc("lock_stolen")
            return False

        # Clock-drift check: if drifted time.time() is > acquired_at + TTL,
        # we believe the lock is already expired even though Redis hasn't
        # cleared it yet.
        elapsed = time.time() - self.acquired_at  # drifted!
        if elapsed > LOCK_TTL_SECS:
            inc("lock_released_early")
            return False

        return True

    def release(self) -> None:
        """Release the lock if we still hold it (Lua for atomicity)."""
        if self.token is None:
            return
        script = """
if redis.call('get', KEYS[1]) and string.find(redis.call('get', KEYS[1]), ARGV[1]) then
    return redis.call('del', KEYS[1])
else
    return 0
end
"""
        self.r.eval(script, 1, LOCK_KEY, self.token)
        self.token = None
        self.acquired_at = None


# ------------------------------------------------------------------ #
# Worker
# ------------------------------------------------------------------ #
def worker(worker_id: str, r: redis.Redis) -> None:
    global _current_holder

    lk = DistributedLock(r, worker_id)

    for i in range(ITERATIONS):
        # Try to acquire
        if lk.acquire():
            acquired_time = time.time()

            # Update global belief
            with _belief_lock:
                if _current_holder is not None and _current_holder != worker_id:
                    # Another worker believes it holds the lock — split brain!
                    inc("split_brain_detected")
                    print(
                        f"  SPLIT-BRAIN: {worker_id} acquired while "
                        f"{_current_holder} still believes it holds the lock"
                    )
                _current_holder = worker_id

            print(f"  [{worker_id}] acquired lock (iter {i})")

            # Do work inside critical section
            time.sleep(HOLD_WORK_SECS)

            # Check if we still own it (may have been stolen or we drifted)
            if not lk.check_still_held():
                print(f"  [{worker_id}] lock lost during critical section!")
                with _belief_lock:
                    if _current_holder == worker_id:
                        _current_holder = None
                continue

            lk.release()
            with _belief_lock:
                if _current_holder == worker_id:
                    _current_holder = None
            print(f"  [{worker_id}] released lock (iter {i})")
        else:
            # Contended — wait a bit
            time.sleep(0.2)

        time.sleep(0.05)


# ------------------------------------------------------------------ #
# Main
# ------------------------------------------------------------------ #
def main() -> None:
    print(f"Connecting to Redis at {REDIS_HOST}:{REDIS_PORT}")
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    r.ping()

    # Clean up any leftover lock from a previous run
    r.delete(LOCK_KEY)

    print(f"\nStarting {NUM_WORKERS} competing workers")
    print(f"Lock TTL: {LOCK_TTL_SECS}s  |  Work time: {HOLD_WORK_SECS}s  |  Clock drift: +5000ms")
    print(
        "\nExpected: lock holders see their clock jump forward 5s, "
        "causing premature TTL expiry and split-brain events.\n"
    )

    threads = []
    for n in range(NUM_WORKERS):
        wid = f"worker-{n}"
        t = threading.Thread(target=worker, args=(wid, r), daemon=True)
        threads.append(t)

    start = time.monotonic()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=120)

    elapsed = time.monotonic() - start

    print()
    print("=" * 60)
    print("FINAL SUMMARY")
    print("=" * 60)
    print(f"  lock_acquired         : {lock_acquired}")
    print(f"  lock_stolen           : {lock_stolen}")
    print(f"  lock_released_early   : {lock_released_early}  (clock drift!)")
    print(f"  split_brain_detected  : {split_brain_detected}  (two holders at once!)")
    print(f"  elapsed               : {elapsed:.1f}s")
    print()
    if split_brain_detected > 0:
        print("  RESULT: Clock drift caused split-brain — distributed lock is BROKEN")
    else:
        print("  RESULT: No split-brain detected in this run")
    print("=" * 60)


if __name__ == "__main__":
    main()
