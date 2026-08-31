#!/usr/bin/env python3
"""Collect an independent JVM profile for one benchmark cell.

The matrix runner starts this process after the server is listening and stops
it after the workload has ended.  The sampler intentionally lives outside the
benchmark timer: JFR/NMT/class-histogram attach overhead is diagnostic data,
not a timing sample.  It is safe to run when a server dies early; missing
attach points are recorded in ``profile.json`` instead of hiding the original
cell failure.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
from typing import Any


def _run(command: list[str], timeout: float = 15.0) -> dict[str, Any]:
    started = time.monotonic_ns()
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, timeout=timeout, check=False)
        return {"command": command, "exit_code": result.returncode,
                "elapsed_ms": (time.monotonic_ns() - started) / 1_000_000,
                "output": result.stdout[-30000:]}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"command": command, "exit_code": None,
                "elapsed_ms": (time.monotonic_ns() - started) / 1_000_000,
                "error": repr(exc)}


def _proc(pid: int) -> dict[str, Any]:
    out: dict[str, Any] = {"pid": pid, "sample_ns": time.monotonic_ns()}
    try:
        status = Path(f"/proc/{pid}/status").read_text(encoding="utf-8")
        for line in status.splitlines():
            if line.startswith(("VmRSS:", "VmHWM:")):
                fields = line.split()
                if len(fields) >= 2:
                    out[fields[0][:-1].lower() + "_bytes"] = int(fields[1]) * 1024
        smaps = Path(f"/proc/{pid}/smaps_rollup").read_text(encoding="utf-8")
        match = re.search(r"^Pss:\s+(\d+)\s+kB$", smaps, re.MULTILINE)
        if match:
            out["pss_bytes"] = int(match.group(1)) * 1024
        stat = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8").split()
        if len(stat) > 19:
            out["minor_faults"] = int(stat[9])
            out["major_faults"] = int(stat[11])
        out["threads"] = len(list(Path(f"/proc/{pid}/task").iterdir()))
    except (OSError, ValueError) as exc:
        out["error"] = repr(exc)
    return out


def _cgroup(path: Path | None) -> dict[str, Any]:
    out: dict[str, Any] = {"sample_ns": time.monotonic_ns(), "available": bool(path)}
    if path is None:
        return out
    for filename, key in (("memory.current", "memory_current_bytes"),
                          ("memory.peak", "memory_peak_bytes"),
                          ("memory.swap.current", "swap_current_bytes")):
        try:
            out[key] = int((path / filename).read_text().strip())
        except (OSError, ValueError):
            pass
    try:
        out["events"] = dict(line.split(maxsplit=1) for line in
                              (path / "memory.events").read_text().splitlines()
                              if len(line.split()) == 2)
    except OSError:
        pass
    return out


class Profiler:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.pid = args.pid
        self.root = args.output_dir
        self.root.mkdir(parents=True, exist_ok=True)
        self.samples = self.root / "profile-memory.jsonl"
        self.stop_requested = False
        self.jfr_name = f"jadx-profile-{os.getpid()}"
        self.jfr_file = self.root / "startup.jfr"
        self.operations: dict[str, Any] = {}

    def stop(self, *_signal: object) -> None:
        self.stop_requested = True

    def attach(self, operation: str, command: list[str], timeout: float = 15.0) -> None:
        self.operations[operation] = _run(command, timeout)

    def start(self) -> None:
        jcmd = self.args.jcmd
        if jcmd:
            # ``settings=profile`` is available in the stock JDK 11+ profile
            # configuration.  Dynamic JFR is best-effort and is reported when
            # a restricted runtime disallows attach.
            self.attach("jfr_start", [jcmd, str(self.pid), "JFR.start",
                                       f"name={self.jfr_name}", "settings=profile",
                                       "disk=true", f"filename={self.jfr_file}",
                                       "maxage=10m", "maxsize=256M"])
            self.attach("nmt_start", [jcmd, str(self.pid), "VM.native_memory", "summary", "scale=MB"])
            if self.operations["nmt_start"].get("output"):
                (self.root / "native-memory-start.txt").write_text(
                    self.operations["nmt_start"]["output"], encoding="utf-8")
        self.operations["started_ns"] = time.monotonic_ns()

    def sample_loop(self) -> None:
        with self.samples.open("w", encoding="utf-8") as stream:
            while not self.stop_requested:
                row = {**_proc(self.pid), "cgroup": _cgroup(self.args.cgroup_path)}
                stream.write(json.dumps(row, separators=(",", ":")) + "\n")
                stream.flush()
                time.sleep(max(0.02, self.args.interval))

    def finish(self) -> None:
        jcmd = self.args.jcmd
        if jcmd:
            self.attach("jfr_stop", [jcmd, str(self.pid), "JFR.stop", f"name={self.jfr_name}"], timeout=20.0)
            self.attach("nmt_end", [jcmd, str(self.pid), "VM.native_memory", "summary", "scale=MB"])
            if self.operations["nmt_end"].get("output"):
                (self.root / "native-memory-end.txt").write_text(
                    self.operations["nmt_end"]["output"], encoding="utf-8")
            self.attach("class_histogram", [jcmd, str(self.pid), "GC.class_histogram"], timeout=30.0)
            if self.operations["class_histogram"].get("output"):
                (self.root / "class-histogram.txt").write_text(
                    self.operations["class_histogram"]["output"], encoding="utf-8")
            self.attach("heap_info", [jcmd, str(self.pid), "GC.heap_info"], timeout=20.0)
            if self.operations["heap_info"].get("output"):
                (self.root / "heap-info.txt").write_text(
                    self.operations["heap_info"]["output"], encoding="utf-8")
        record = {
            "schema": "jvm-profile/v1",
            "run_id": self.args.run_id,
            "pid": self.pid,
            "started_ns": self.operations.get("started_ns"),
            "finished_ns": time.monotonic_ns(),
            "interval_seconds": self.args.interval,
            "jcmd": jcmd,
            "jfr_file": str(self.jfr_file) if self.jfr_file.exists() else None,
            "operations": self.operations,
            "samples": str(self.samples),
            "scope": "diagnostic only; excluded from timing aggregates",
        }
        (self.root / "profile.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pid", required=True, type=int)
    parser.add_argument("--cgroup-path", type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--run-id", default="profile")
    parser.add_argument("--interval", type=float, default=0.10)
    parser.add_argument("--jcmd", default="jcmd")
    args = parser.parse_args()
    profiler = Profiler(args)
    signal.signal(signal.SIGTERM, profiler.stop)
    signal.signal(signal.SIGINT, profiler.stop)
    profiler.start()
    try:
        profiler.sample_loop()
    finally:
        profiler.finish()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
