"""
Minimal WSGI application used by the fork-chaos demo.

Returns a JSON response identifying which gunicorn worker handled the
request.  Simple enough to show the PID changing as workers die and
(fail to) respawn.
"""

import os
import json
import time

START_TIME = time.time()


def application(environ: dict, start_response) -> list:
    """PEP 3333 WSGI callable."""
    pid     = os.getpid()
    uptime  = round(time.time() - START_TIME, 1)
    payload = json.dumps({
        "status":     "ok",
        "worker_pid": pid,
        "uptime_s":   uptime,
    }).encode()

    start_response(
        "200 OK",
        [
            ("Content-Type", "application/json"),
            ("Content-Length", str(len(payload))),
            ("X-Worker-Pid", str(pid)),
        ],
    )
    return [payload]
