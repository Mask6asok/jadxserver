#!/usr/bin/env python3
"""Capture a post-workload, quiescent JVM and cgroup memory snapshot.

This command is deliberately run outside the workload timer.  It refuses to
claim a terminal sample unless the MCP server has reported no queued work and
no busy instances continuously for the requested grace period.
"""

from __future__ import annotations

import argparse
import http.client
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

PROTOCOL_VERSION = "2025-03-26"


def mcp_call(url: str, tool: str, timeout: float = 10.0) -> dict[str, Any]:
    """One complete MCP session, retaining the session header on initialize."""
    parsed = urlparse(url)
    conn = http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, timeout=timeout) if parsed.scheme == "https" else http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=timeout)
    path = parsed.path.rstrip("/") or "/mcp"
    def post(payload: dict[str, Any], session: str | None = None) -> tuple[dict[str, Any], str | None]:
        nonlocal conn
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": PROTOCOL_VERSION}
        if session: headers["Mcp-Session-Id"] = session
        conn.request("POST", path, json.dumps(payload, separators=(",", ":")).encode(), headers)
        res = conn.getresponse(); out_headers = {k.lower(): v for k, v in res.getheaders()}
        if res.status >= 400: raise RuntimeError(f"HTTP {res.status}: {res.read(500).decode(errors='replace')}")
        if "text/event-stream" in out_headers.get("content-type", ""):
            wanted = payload.get("id")
            while True:
                line = res.readline()
                if not line: break
                s = line.decode(errors="replace").strip()
                if s.startswith("data:") and s[5:].strip() not in ("", "[DONE]"):
                    e = json.loads(s[5:].strip())
                    if wanted is None or e.get("id") == wanted:
                        session_header = out_headers.get("mcp-session-id")
                        conn.close()
                        conn = http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, timeout=timeout) if parsed.scheme == "https" else http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=timeout)
                        return e, session_header
            return {}, out_headers.get("mcp-session-id")
        raw = res.read(); return (json.loads(raw.decode()) if raw else {}), out_headers.get("mcp-session-id")
    init, session = post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": PROTOCOL_VERSION, "capabilities": {}, "clientInfo": {"name": "terminal-probe", "version": "1"}}})
    if not session: raise RuntimeError("server did not return Mcp-Session-Id")
    post({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}, session)
    event, _ = post({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": tool, "arguments": {}}}, session)
    if event.get("error"): raise RuntimeError(str(event["error"]))
    result = event.get("result", {})
    for item in result.get("content", []):
        if item.get("type") == "text":
            try: return json.loads(item["text"])
            except (KeyError, json.JSONDecodeError): pass
    return result if isinstance(result, dict) else {}


def read_proc(pid: int) -> dict[str, Any]:
    out: dict[str, Any] = {"method": "procfs", "available": True}
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith(("VmRSS:", "VmHWM:")):
                key, value, unit = line.split()[:3]; out[key[:-1].lower() + "_bytes"] = int(value) * (1024 if unit == "kB" else 1)
        for line in Path(f"/proc/{pid}/smaps_rollup").read_text().splitlines():
            if line.startswith("Pss:"):
                out["pss_bytes"] = int(line.split()[1]) * 1024; break
    except (OSError, ValueError) as exc:
        out.update(available=False, error=str(exc))
    return out


def read_cgroup(path: Path | None) -> dict[str, Any]:
    out: dict[str, Any] = {"method": "cgroup-v2", "available": bool(path)}
    if not path: out["reason"] = "cgroup path not provided"; return out
    for name, key in (("memory.current", "memory_current_bytes"), ("memory.peak", "memory_peak_bytes"), ("memory.swap.current", "swap_current_bytes")):
        try: out[key] = int((path / name).read_text().strip())
        except (OSError, ValueError) as exc: out.setdefault("unavailable", {})[key] = str(exc)
    try:
        out["memory_events"] = dict(line.split(maxsplit=1) for line in (path / "memory.events").read_text().splitlines() if len(line.split()) == 2)
    except OSError as exc: out.setdefault("unavailable", {})["memory_events"] = str(exc)
    out["available"] = any(k in out for k in ("memory_current_bytes", "memory_peak_bytes", "swap_current_bytes"))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-url", required=True, help="MCP endpoint, e.g. http://127.0.0.1:8765/mcp")
    parser.add_argument("--pid", required=True, type=int, help="JVM/server PID")
    parser.add_argument("--cgroup-path", type=Path)
    parser.add_argument("--output", required=True, type=Path, help="terminal.json output path")
    parser.add_argument("--grace-seconds", type=float, default=30.0)
    parser.add_argument("--post-gc-wait-seconds", type=float, default=5.0,
                        help="fixed wait after the explicit GC request before final memory sampling")
    args = parser.parse_args()
    started = time.monotonic_ns(); quiet_since: float | None = None; last: dict[str, Any] = {}; error: str | None = None
    deadline = time.monotonic() + max(0.0, args.grace_seconds) + 300.0
    while time.monotonic() < deadline:
        # A cgroup OOM or JVM fatal error can terminate the server before the
        # benchmark returns.  Continuing to retry a dead endpoint for the
        # full quiescence deadline only delays the matrix and produces no
        # additional evidence.  Preserve the failed terminal proof instead.
        try:
            proc_dir = Path(f"/proc/{args.pid}")
            stat = (proc_dir / "stat").read_text().split()
            if not stat or stat[2] == "Z":
                error = f"server pid {args.pid} is no longer alive"
                break
        except (OSError, ValueError):
            error = f"server pid {args.pid} is no longer alive"
            break
        try:
            health = mcp_call(args.server_url, "server_health")
            instances = mcp_call(args.server_url, "list_instances")
            data = {**health, "instances": instances.get("instances", [])}
            busy = any(str(i.get("state", "")).upper() in ("BUSY", "RUNNING") for i in data["instances"])
            quiet = data.get("queue_depth") == 0 and data.get("task_count") == 0 and not busy
            last = data
            if quiet:
                quiet_since = quiet_since or time.monotonic()
                if time.monotonic() - quiet_since >= args.grace_seconds: break
            else: quiet_since = None
        except Exception as exc:
            error = str(exc); quiet_since = None
        time.sleep(0.25)
    proven = quiet_since is not None and time.monotonic() - quiet_since >= args.grace_seconds
    record: dict[str, Any] = {"quiescence_proven": proven, "quiescence_grace_seconds": args.grace_seconds,
                              "post_gc_wait_seconds": args.post_gc_wait_seconds,
                              "quiescence_observation": last, "observed_at_ns": time.monotonic_ns(),
                              "proc": read_proc(args.pid), "cgroup": read_cgroup(args.cgroup_path),
                              "gc": {"available": False, "requested": False}}
    if error: record["quiescence_error"] = error
    jcmd = shutil.which("jcmd")
    if jcmd:
        record["gc"].update(available=True, requested=True, method="jcmd GC.run + GC.heap_info")
        try:
            run = subprocess.run((jcmd, str(args.pid), "GC.run"), capture_output=True, text=True, timeout=60, check=False)
            heap = subprocess.run((jcmd, str(args.pid), "GC.heap_info"), capture_output=True, text=True, timeout=60, check=False)
            record["gc"].update(gc_run_exit=run.returncode, heap_info_exit=heap.returncode, heap_info=heap.stdout[-10000:], stderr=(run.stderr + heap.stderr)[-4000:])
            # JDK 25's ``jcmd GC.heap_info`` emits e.g.
            # ``committed 4890624K, used 1433568K`` (without an equals sign).
            # Older JDKs may use ``used=...`` or ``used_heap: ...``; accept
            # all of these forms while avoiding the ``used`` substring in
            # ``unused``/``used regions`` diagnostics.
            match = re.search(r"\b(?:used_heap|used)\b\s*(?:[=:]\s*)?([0-9]+)\s*([KMG]?)\b", heap.stdout, re.IGNORECASE)
            if match:
                factor = {"": 1, "K": 1024, "M": 1024 ** 2, "G": 1024 ** 3}[match.group(2).upper()]
                record["heap_used_bytes"] = int(match.group(1)) * factor
            if args.post_gc_wait_seconds > 0:
                time.sleep(args.post_gc_wait_seconds)
            # The terminal memory values must describe the state after the
            # declared GC wait, rather than the pre-GC sample above.
            record["proc"] = read_proc(args.pid)
            record["cgroup"] = read_cgroup(args.cgroup_path)
        except (OSError, subprocess.SubprocessError) as exc: record["gc"]["error"] = str(exc)
    else: record["gc"]["method"] = "jcmd unavailable"
    record["method"] = "MCP quiescence + jcmd (if available) + fixed post-GC wait + /proc + cgroup"
    record["rss_bytes"] = record["proc"].get("vmrss_bytes")
    record["vmhwm_bytes"] = record["proc"].get("vmhwm_bytes")
    record["pss_bytes"] = record["proc"].get("pss_bytes")
    record["cgroup_memory_current_bytes"] = record["cgroup"].get("memory_current_bytes")
    record["cgroup_memory_peak_bytes"] = record["cgroup"].get("memory_peak_bytes")
    record["cgroup_swap_current_bytes"] = record["cgroup"].get("swap_current_bytes")
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(record, indent=2) + "\n")
    return 0 if proven else 2


if __name__ == "__main__":
    sys.exit(main())
