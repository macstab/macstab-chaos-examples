"""
Full-stack Flask application under multi-library chaos injection.

Each request exercises all 6 chaos domains:
  IO       — writes a temporary file to /tmp
  NET      — connects to Redis (libchaos-net intercepts recv/send)
  DNS      — resolves 'downstream' hostname (libchaos-dns intercepts getaddrinfo)
  TIME     — reads time.time() (libchaos-time offsets CLOCK_REALTIME)
  MEMORY   — allocates a bytearray (libchaos-memory intercepts mmap on large allocs)
  PROCESS  — spawns a subprocess (libchaos-process intercepts fork/clone)

All errors are caught and counted; the response always returns 200 with
a JSON breakdown so the load generator can track per-domain error rates.
"""

import os
import time
import socket
import subprocess
import tempfile
import threading

import redis
import requests
from flask import Flask, jsonify

# ------------------------------------------------------------------ #
# Configuration
# ------------------------------------------------------------------ #
REDIS_HOST       = os.getenv("REDIS_HOST", "redis")
REDIS_PORT       = int(os.getenv("REDIS_PORT", 6379))
DOWNSTREAM_HOST  = os.getenv("DOWNSTREAM_HOST", "downstream")
DOWNSTREAM_PORT  = int(os.getenv("DOWNSTREAM_PORT", 8080))
ALLOC_SIZE       = 8 * 1024 * 1024   # 8 MiB — large enough to trigger mmap

# ------------------------------------------------------------------ #
# App + counters
# ------------------------------------------------------------------ #
app = Flask(__name__)

_lock = threading.Lock()
_counters = {
    "io_ok": 0, "io_fail": 0,
    "net_ok": 0, "net_fail": 0,
    "dns_ok": 0, "dns_fail": 0,
    "time_ok": 0,
    "mem_ok": 0, "mem_fail": 0,
    "proc_ok": 0, "proc_fail": 0,
    "req_total": 0,
}


def inc(key: str) -> None:
    with _lock:
        _counters[key] = _counters.get(key, 0) + 1


# ------------------------------------------------------------------ #
# Per-domain operations
# ------------------------------------------------------------------ #
def do_io() -> dict:
    """Write a small file to /tmp — libchaos-io intercepts write(2)."""
    try:
        fd, path = tempfile.mkstemp(prefix="chaos-", dir="/tmp")
        os.write(fd, b"chaos payload " * 64)
        os.close(fd)
        os.unlink(path)
        inc("io_ok")
        return {"status": "ok"}
    except OSError as e:
        inc("io_fail")
        return {"status": "error", "errno": e.errno, "msg": str(e)}


def do_net() -> dict:
    """SET/GET a counter in Redis — libchaos-net intercepts recv/send."""
    try:
        r = redis.Redis(
            host=REDIS_HOST, port=REDIS_PORT,
            socket_timeout=2.0, socket_connect_timeout=2.0,
            decode_responses=True,
        )
        key = "chaos:req-count"
        r.incr(key)
        val = r.get(key)
        inc("net_ok")
        return {"status": "ok", "counter": val}
    except Exception as e:
        inc("net_fail")
        return {"status": "error", "msg": str(e)}


def do_dns() -> dict:
    """Resolve downstream hostname — libchaos-dns intercepts getaddrinfo."""
    try:
        addrs = socket.getaddrinfo(
            DOWNSTREAM_HOST, DOWNSTREAM_PORT,
            socket.AF_INET, socket.SOCK_STREAM,
        )
        ip = addrs[0][4][0]
        inc("dns_ok")
        return {"status": "ok", "resolved_ip": ip}
    except socket.gaierror as e:
        inc("dns_fail")
        return {"status": "error", "errno": e.args[0], "msg": str(e)}


def do_downstream_http() -> dict:
    """HTTP GET to downstream — uses the DNS resolution above."""
    try:
        resp = requests.get(
            f"http://{DOWNSTREAM_HOST}:{DOWNSTREAM_PORT}/api/data",
            timeout=3,
        )
        return {"status": "ok", "http_status": resp.status_code, "body": resp.json()}
    except Exception as e:
        return {"status": "error", "msg": str(e)}


def do_time() -> dict:
    """Read the wall clock — libchaos-time offsets CLOCK_REALTIME."""
    t = time.time()
    inc("time_ok")
    return {"status": "ok", "wall_time": t}


def do_memory() -> dict:
    """Allocate a large buffer — libchaos-memory intercepts mmap(MAP_ANONYMOUS)."""
    try:
        buf = bytearray(ALLOC_SIZE)
        # Touch a few pages so the kernel actually maps them
        buf[0] = 0xDE
        buf[ALLOC_SIZE // 2] = 0xAD
        buf[-1] = 0xBE
        inc("mem_ok")
        return {"status": "ok", "size_mb": ALLOC_SIZE // (1024 * 1024)}
    except MemoryError as e:
        inc("mem_fail")
        return {"status": "error", "msg": str(e)}


def do_process() -> dict:
    """Spawn a subprocess — libchaos-process intercepts fork/clone."""
    try:
        result = subprocess.run(
            ["python3", "-c", "import os; print(os.getpid())"],
            capture_output=True, text=True, timeout=5,
        )
        inc("proc_ok")
        return {"status": "ok", "child_pid": result.stdout.strip()}
    except BlockingIOError as e:
        # EAGAIN from fork() surfaces as BlockingIOError on Linux
        inc("proc_fail")
        return {"status": "error", "errno": e.errno, "msg": "fork EAGAIN (chaos)"}
    except Exception as e:
        inc("proc_fail")
        return {"status": "error", "msg": str(e)}


# ------------------------------------------------------------------ #
# Routes
# ------------------------------------------------------------------ #
@app.route("/health")
def health():
    return jsonify({"status": "ok"})


@app.route("/")
@app.route("/chaos")
def chaos_endpoint():
    """Exercise all 6 chaos domains in a single request."""
    inc("req_total")

    io_result    = do_io()
    net_result   = do_net()
    dns_result   = do_dns()
    http_result  = do_downstream_http()
    time_result  = do_time()
    mem_result   = do_memory()
    proc_result  = do_process()

    with _lock:
        stats = dict(_counters)

    return jsonify({
        "request": {
            "io":         io_result,
            "net_redis":  net_result,
            "dns":        dns_result,
            "http":       http_result,
            "time":       time_result,
            "memory":     mem_result,
            "process":    proc_result,
        },
        "cumulative_stats": stats,
    })


@app.route("/stats")
def stats_endpoint():
    """Return cumulative counters without exercising chaos paths."""
    with _lock:
        stats = dict(_counters)
    return jsonify(stats)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
