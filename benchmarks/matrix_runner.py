#!/usr/bin/env python3
"""Bounded matrix runner for the same-APK MCP benchmark.

This is deliberately an orchestration layer, not a statistics implementation.
The server command, benchmark command, manifest/activity source, and optional
sampling hook are inputs.  One server process is created for each matrix cell;
APK cells are run serially while the benchmark command is responsible for the
parallel activity workers (the bundled benchmark accepts five activities).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import shlex
import signal
import shutil
import socket
import subprocess
import sys
import time
from typing import Any


PROFILES = ("CURRENT", "O1-1", "O1-2")
TEMPS = ("APP-COLD", "WARM", "APP-WARM")
MODES = ("BURST", "AGENT")


def _engine_slots_for_profile(profile: str) -> int:
    """Return the fixed per-APK mutable-engine budget for a matrix profile."""
    return {"CURRENT": 5, "O1-1": 1, "O1-2": 2}[profile]


def _stamp() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def _host_available_bytes() -> int | None:
    """Return Linux MemAvailable, or None when procfs is unavailable."""
    try:
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition(":")
            if key.strip() == "MemAvailable":
                fields = value.strip().split()
                if not fields:
                    return None
                # /proc/meminfo reports kB; keep the conversion explicit so
                # the preflight record is byte-comparable with cgroup data.
                return int(fields[0]) * 1024
    except (OSError, ValueError):
        return None
    return None


def _write_preflight_failure(args: argparse.Namespace, reason: str,
                             available_bytes: int | None, required_bytes: int) -> Path:
    """Persist a machine-readable host guard failure before any JVM starts."""
    args.output_dir.mkdir(parents=True, exist_ok=True)
    path = args.output_dir / f"preflight-{_stamp()}.json"
    record = {
        "schema": "benchmark-preflight/v1",
        "status": "blocked",
        "reason": reason,
        "observed_at": _stamp(),
        "host_available_bytes": available_bytes,
        "host_available_gib": (available_bytes / (1024 ** 3)) if available_bytes is not None else None,
        "required_host_available_bytes": required_bytes,
        "required_host_available_gib": required_bytes / (1024 ** 3),
        "memory_max": getattr(args, "memory_max", None),
        "memory_swap_max": getattr(args, "memory_swap_max", None),
        "requested_workers": getattr(args, "workers", None),
        "apk_count": len(getattr(args, "apk", []) or []),
        "jvm_started": False,
    }
    path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return path


def _command(template: str, values: dict[str, Any]) -> list[str]:
    # Templates are intentionally shell-free: quote values in the template if
    # a shell is genuinely required.  shlex.split still permits normal command
    # lines and avoids accidental shell expansion.
    return shlex.split(template.format(**{k: str(v) for k, v in values.items()}))


def _wait_port(host: str, port: int, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    last_error: OSError | None = None
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((host, port), timeout=min(1.0, max(0.1, deadline - time.monotonic()))):
                return
        except OSError as exc:
            last_error = exc
            time.sleep(0.1)
    raise TimeoutError(f"server did not accept TCP connections on {host}:{port}: {last_error}")


def _cgroup_path(pid: int) -> str | None:
    try:
        for line in Path(f"/proc/{pid}/cgroup").read_text().splitlines():
            fields = line.split(":", 2)
            # cgroup v2 is encoded as ``0::<relative-path>``.  Splitting
            # only at the first colon leaves an extra ':' and makes the
            # otherwise valid cgroup look nonexistent.
            if len(fields) == 3 and fields[0] == "0":
                candidate = Path("/sys/fs/cgroup") / fields[2].lstrip("/")
                if (candidate / "memory.current").exists():
                    return str(candidate)
    except (FileNotFoundError, PermissionError):
        pass
    return None


def _find_server_pid(wrapper_pid: int, port: int) -> int:
    """systemd-run --scope normally execs the child; keep a fallback for wrappers."""
    try:
        executable = Path(f"/proc/{wrapper_pid}/exe").resolve().name
        if executable == "java":
            return wrapper_pid
    except (FileNotFoundError, PermissionError, OSError):
        return wrapper_pid
    queue = [wrapper_pid]
    seen = set(queue)
    while queue:
        parent = queue.pop(0)
        try:
            children = Path(f"/proc/{parent}/task/{parent}/children").read_text().split()
        except (FileNotFoundError, PermissionError):
            continue
        for child_text in children:
            child = int(child_text)
            if child in seen:
                continue
            seen.add(child)
            try:
                command = Path(f"/proc/{child}/cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
            except (FileNotFoundError, PermissionError):
                command = ""
            try:
                executable = Path(f"/proc/{child}/exe").resolve().name
            except (FileNotFoundError, PermissionError, OSError):
                executable = ""
            if executable == "java" or "jadx-server" in command:
                return child
            queue.append(child)
    # systemd-run --scope reparents the scoped process to the user manager,
    # so it is not necessarily a child of the systemd-run client.  Match the
    # unique benchmark listen port as a fallback.
    needle = f":{port}"
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        try:
            command = (proc / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
            executable = (proc / "exe").resolve().name
        except (FileNotFoundError, PermissionError, OSError):
            continue
        if executable == "java" and "jadx-server" in command and needle in command:
            return int(proc.name)
    return wrapper_pid


def _scoped_command(args: argparse.Namespace, command: list[str], unit_name: str) -> list[str]:
    if not args.systemd:
        return command
    return ["systemd-run", "--user", "--scope", "--quiet", f"--unit={unit_name}",
            f"--property=MemoryMax={args.memory_max}",
            f"--property=MemorySwapMax={args.memory_swap_max}", *command]


def _stop_server(args: argparse.Namespace, process: subprocess.Popen[str] | None,
                 unit_name: str) -> int | None:
    """Stop a server and return its wrapper exit code when available."""
    if process is None:
        return None
    interrupted = False
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    if args.systemd:
        unit = f"{unit_name}.scope"
        try:
            subprocess.run(["systemctl", "--user", "stop", unit],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           timeout=15, check=False)
        except (OSError, subprocess.TimeoutExpired, KeyboardInterrupt) as exc:
            interrupted = isinstance(exc, KeyboardInterrupt)
            # A JVM at the cgroup memory boundary may not service SIGTERM or
            # let systemd finish the synchronous stop transaction promptly.
            # Do not abandon cleanup here: explicitly kill every process in
            # the owned scope, then fall through to the Popen wait/kill path.
            try:
                subprocess.run(["systemctl", "--user", "kill", "--kill-who=all",
                                "--signal=SIGKILL", unit],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               timeout=10, check=False)
            except (OSError, subprocess.TimeoutExpired, KeyboardInterrupt) as kill_exc:
                interrupted = interrupted or isinstance(kill_exc, KeyboardInterrupt)
    try:
        exit_code = process.wait(timeout=15)
    except KeyboardInterrupt:
        # Preserve Ctrl-C semantics, but finish the owned-process cleanup
        # before propagating the interrupt to the matrix runner.
        interrupted = True
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            exit_code = process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            exit_code = process.wait()
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        exit_code = process.wait()
    if interrupted:
        raise KeyboardInterrupt
    return exit_code


def _wait_port_closed(host: str, port: int, timeout: float = 15.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((host, port), timeout=0.2):
                time.sleep(0.1)
        except OSError:
            return
    raise TimeoutError(f"server port {host}:{port} remained open after shutdown")


def _last_json(text: str) -> dict[str, Any] | None:
    decoder = json.JSONDecoder()
    pos = 0
    parsed: dict[str, Any] | None = None
    while pos < len(text):
        try:
            candidate, end = decoder.raw_decode(text[pos:].lstrip())
            if isinstance(candidate, dict):
                parsed = candidate
            pos += max(end, 1)
        except json.JSONDecodeError:
            pos += 1
    return parsed


def _manifest_value(manifest: Path, apk: Path) -> Any:
    data = json.loads(manifest.read_text())
    key = apk.name
    if isinstance(data, dict) and key in data:
        return data[key]
    elif isinstance(data, dict) and str(apk) in data:
        return data[str(apk)]
    # A Phase 0 manifest describes one APK and is intentionally not keyed by
    # filename.  Keep the combined-index format above for compatibility.
    if isinstance(data, dict) and ("selected_activities" in data or "activity_candidates" in data):
        return data
    raise ValueError(f"manifest has no activities for {apk} (looked for {key})")


def _activities(manifest: Path, apk: Path, explicit: list[str] | None) -> list[str]:
    if explicit:
        return explicit
    value = _manifest_value(manifest, apk)
    if isinstance(value, dict) and isinstance(value.get("selected_activities"), list):
        value = value["selected_activities"]
    if isinstance(value, dict):
        value = value.get("activities")
    if not isinstance(value, list) or not all(isinstance(x, str) and x for x in value):
        raise ValueError(f"manifest activities for {apk} must be a string list")
    return value


def _methods(manifest: Path | None, apk: Path, activities: list[str], explicit: list[str] | None) -> list[str]:
    """Return one manifest-selected lifecycle method per activity."""
    if explicit:
        return explicit
    if manifest is None:
        return ["onCreate"] * len(activities)
    data = json.loads(manifest.read_text())
    key = apk.name
    if isinstance(data, dict):
        value = data.get(key, data.get(str(apk), data))
    else:
        value = data
    if isinstance(value, dict) and isinstance(value.get("methods"), dict):
        return [str(value["methods"].get(activity, "onCreate")) for activity in activities]
    if isinstance(value, dict) and isinstance(value.get("selected_activities"), list):
        # Native Phase 0 output stores methods by Activity at the document root.
        methods = value.get("methods", {})
        if isinstance(methods, dict):
            return [str(methods.get(activity, "onCreate")) for activity in activities]
    if isinstance(value, dict):
        methods = value.get("methods")
        if isinstance(methods, dict):
            return [str(methods.get(activity, "onCreate")) for activity in activities]
        targets = value.get("targets")
        if isinstance(targets, list):
            by_class = {str(item.get("class_name")): str(item.get("method", "onCreate"))
                        for item in targets if isinstance(item, dict) and item.get("class_name")}
            return [by_class.get(activity, "onCreate") for activity in activities]
    return ["onCreate"] * len(activities)


def _run_cell(args: argparse.Namespace, apk: Path, activities: list[str], profile: str,
              temp: str, mode: str, round_no: int, out: Path,
              worker_count: int | None = None, methods: list[str] | None = None,
              timeout_override: float | None = None) -> dict[str, Any]:
    worker_count = worker_count or args.workers
    # APP-WARM remains accepted as a compatibility alias; WARM is canonical.
    temp = "WARM" if temp == "APP-WARM" else temp
    cell = f"{apk.stem}-{profile}-{temp}-{mode}-r{round_no}"
    if worker_count != args.workers:
        cell += f"-w{worker_count}"
    log = out / f"{cell}.log"
    raw = out / f"{cell}.json"
    upload_dir = out / "uploads" / cell
    artifact_dir = out / "artifacts" / cell
    # Profiling hooks run before the benchmark creates artifact_dir (the
    # benchmark intentionally uses exist_ok=False to catch stale/reused cell
    # identities). Keep diagnostic files in a sibling tree so a hook cannot
    # accidentally pre-create the benchmark's output directory.
    profile_dir = out / "profiling" / cell
    golden_dir = args.golden_dir or (out / "goldens")
    # Xref discovery is intentionally lazy: an APP-COLD run can expose a
    # different (but valid) prefix of the same graph than a WARM run whose
    # cache already contains decompiled classes.  Keep correctness baselines
    # scoped to APK + temperature + traffic mode so a cold result is never
    # compared with a warm result by accident.
    # A correctness baseline belongs to one round as well as one workload.
    # Including the round in the name permits batches to share an output root
    # without accidentally comparing a later batch with an earlier baseline.
    golden_path = golden_dir / f"{apk.stem}-{temp}-{mode}-r{round_no}.json"
    upload_dir.mkdir(parents=True, exist_ok=True)
    port = args.base_port + round_no
    values = {"apk": apk, "apk_stem": apk.stem, "server": f"http://127.0.0.1:{port}", "port": port,
              "activities": ",".join(activities[:worker_count]),
              "methods": ",".join((methods or ["onCreate"] * len(activities))[:worker_count]), "profile": profile,
              "temp": temp, "mode": mode.lower(), "round": round_no, "workers": worker_count,
              "log": log, "out": out, "upload_dir": upload_dir, "artifact_dir": artifact_dir,
              "cell": cell, "run_id": cell, "variant": "current" if profile == "CURRENT" else "shared",
              "shared_engine_slots": _engine_slots_for_profile(profile),
              "profile_dir": profile_dir,
              "phase": args.phase,
              "golden": golden_path,
              "golden_option": f"--correctness-golden {golden_path}" if golden_path.is_file() else ""}
    values.update({"file_hash_arg": "", "timeout": timeout_override if timeout_override is not None else args.timeout,
                   "terminal_grace_seconds": args.terminal_grace_seconds})
    server_cmd = _command(args.server_command, values)
    # Include the effective worker count in the transient unit identity.  A
    # 5->4 fallback is a distinct process/cgroup and must not collide with a
    # failed 5-worker unit that systemd is still retaining as a failed
    # transient scope.
    unit_name = "jadx-bench-" + "-".join(
        part for part in (apk.stem, profile.lower(), temp.lower(), mode.lower(), str(round_no), str(worker_count)) if part
    ).replace("_", "-")
    server_cmd = _scoped_command(args, server_cmd, unit_name)
    start_ns = time.monotonic_ns()
    status: dict[str, Any] = {"apk": str(apk), "profile": profile, "temperature": temp,
        "mode": mode, "round": round_no, "requested_worker_count": args.workers,
        "effective_worker_count": worker_count, "worker_count": worker_count,
        # Keep the stable cell identity in the per-cell status as well as in
        # the benchmark/artifact run record.  This matters for a 5->4 retry:
        # the retry may terminate before the benchmark can write run.json,
        # but reports must still identify and correlate that cell.
        "run_id": cell,
        "server_command": server_cmd, "benchmark_command": None,
        "sampling_hook": None, "log_path": str(log), "raw_json_path": str(raw),
        "degraded": False, "degradation_reason": None, "preparation": None,
        "terminal": None}
    status["systemd_unit"] = f"{unit_name}.scope" if args.systemd else None
    interrupted = False
    with log.open("w", encoding="utf-8") as stream:
        server: subprocess.Popen[str] | None = None
        preparation_server: subprocess.Popen[str] | None = None
        sampler = None

        def stop_owned(proc: subprocess.Popen[str] | None, owned_unit: str) -> int | None:
            """Always finish cleanup, but defer Ctrl-C until status is durable."""
            nonlocal interrupted
            try:
                return _stop_server(args, proc, owned_unit)
            except KeyboardInterrupt:
                interrupted = True
                status["interrupted"] = True
                if status.get("benchmark_exit_code") in (None, 0):
                    status["benchmark_exit_code"] = 130
                return None

        try:
            # WARM has a real two-lifecycle protocol: prepare/decompile into
            # the cell's persistent upload/cache directory, stop that JVM,
            # then restart the same server command before T0.  The prepare
            # phase is recorded separately and never enters benchmark timing.
            if temp == "WARM":
                prep_unit = unit_name + "-prep"
                prep_cmd = _scoped_command(args, _command(args.server_command, values), prep_unit)
                status["preparation_server_command"] = prep_cmd
                stream.write("$ preparation-server: " + shlex.join(prep_cmd) + "\n")
                preparation_server = subprocess.Popen(
                    prep_cmd, stdout=stream, stderr=subprocess.STDOUT,
                    start_new_session=True, env=os.environ.copy())
                prep_started = time.monotonic_ns()
                _wait_port("127.0.0.1", port, args.startup_wait)
                prep_pid = _find_server_pid(preparation_server.pid, port)
                prep_cgroup = _cgroup_path(prep_pid)
                prep_values = dict(values)
                prep_values.update({"server_pid": prep_pid, "cgroup_path": prep_cgroup or "",
                                    "cgroup_arg": f"--cgroup-path {prep_cgroup}" if prep_cgroup else ""})
                prep_template = args.warm_prepare_command
                prep_run_cmd = _command(prep_template, prep_values)
                status["preparation_command"] = prep_run_cmd
                stream.write("$ preparation: " + shlex.join(prep_run_cmd) + "\n")
                prep_run = subprocess.run(prep_run_cmd, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, timeout=args.prepare_timeout,
                                           env=os.environ.copy(), text=True)
                stream.write(prep_run.stdout)
                prep_record = _last_json(prep_run.stdout)
                status["preparation"] = {
                    "exit_code": prep_run.returncode,
                    "elapsed_ms": (time.monotonic_ns() - prep_started) / 1_000_000,
                    "pid": prep_pid, "cgroup_path": prep_cgroup,
                    "output": prep_record if prep_record is not None else prep_run.stdout[-20000:],
                }
                if prep_run.returncode != 0 or not isinstance(prep_record, dict) or not prep_record.get("file_hash"):
                    raise RuntimeError("warm preparation failed or returned no file_hash")
                file_hash = str(prep_record["file_hash"])
                values["file_hash_arg"] = f"--file-hash {file_hash}"
                _stop_server(args, preparation_server, prep_unit)
                preparation_server = None
                _wait_port_closed("127.0.0.1", port)

            stream.write("$ server: " + shlex.join(server_cmd) + "\n")
            server = subprocess.Popen(server_cmd, stdout=stream, stderr=subprocess.STDOUT,
                                      start_new_session=True, env=os.environ.copy())
            # A systemd scope can briefly exist before the JVM is reparented
            # into it.  Wait for the actual listening endpoint first; only
            # then resolve the JVM PID/cgroup so memory samples never point at
            # the short-lived systemd-run client.
            _wait_port("127.0.0.1", port, args.startup_wait)
            server_pid = _find_server_pid(server.pid, port)
            cgroup_path = _cgroup_path(server_pid)
            values["server_pid"] = server_pid
            values["cgroup_path"] = cgroup_path or ""
            values["cgroup_arg"] = f"--cgroup-path {cgroup_path}" if cgroup_path else ""
            values["file_hash_arg"] = values.get("file_hash_arg", "")
            bench_cmd = _command(args.benchmark_command, values)
            sampler_cmd = _command(args.sampling_hook, values) if args.sampling_hook else None
            status["benchmark_command"] = bench_cmd
            status["sampling_hook"] = sampler_cmd
            stream.write("$ benchmark: " + shlex.join(bench_cmd) + "\n")
            if sampler_cmd:
                sampler = subprocess.Popen(sampler_cmd, stdout=stream, stderr=subprocess.STDOUT,
                                            env=os.environ.copy())
            bench = subprocess.run(bench_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   timeout=timeout_override if timeout_override is not None else args.timeout,
                                   env=os.environ.copy(), text=True)
            stream.write(bench.stdout)
            # Preserve the benchmark's JSON as a first-class raw artifact.
            parsed = _last_json(bench.stdout)
            if parsed is not None:
                status["benchmark_raw"] = parsed
            elif bench.stdout.strip():
                status["raw_parse_error"] = "benchmark emitted no JSON object"
            status["benchmark_exit_code"] = bench.returncode
            # Terminal probing is deliberately after benchmark T_end and
            # before server shutdown, so queue/idle state and post-GC memory
            # remain observable without contaminating workload timings.
            if args.terminal_command and server_pid:
                terminal_cmd = _command(args.terminal_command, values)
                status["terminal_command"] = terminal_cmd
                stream.write("$ terminal: " + shlex.join(terminal_cmd) + "\n")
                terminal_run = subprocess.run(terminal_cmd, stdout=subprocess.PIPE,
                                              stderr=subprocess.STDOUT,
                                              timeout=args.terminal_timeout,
                                              env=os.environ.copy(), text=True)
                stream.write(terminal_run.stdout)
                status["terminal_exit_code"] = terminal_run.returncode
                terminal_path = artifact_dir / "terminal.json"
                if terminal_path.is_file():
                    try:
                        status["terminal"] = json.loads(terminal_path.read_text())
                    except json.JSONDecodeError as exc:
                        status["terminal_error"] = repr(exc)
        except KeyboardInterrupt as exc:
            # Preserve the interrupt for the matrix loop, but let the finally
            # block persist a cell-level reason and clean both the JVM and its
            # systemd scope first.  Without this, an interrupt during warm
            # preparation/terminal probing could lose the only status record.
            interrupted = True
            status["interrupted"] = True
            status["benchmark_exit_code"] = 130
            status["error"] = repr(exc)
        except (OSError, subprocess.TimeoutExpired, RuntimeError, ValueError) as exc:
            status["benchmark_exit_code"] = 124
            status["error"] = repr(exc)
        finally:
            if sampler is not None:
                sampler.terminate()
                try:
                    # Profiling hooks may need to stop JFR and flush a final
                    # histogram/NMT snapshot after the workload. This is
                    # outside the benchmark timer and must not be truncated
                    # at the old five-second attach window.
                    sampler.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    sampler.kill()
                    sampler.wait(timeout=10)
                except KeyboardInterrupt:
                    interrupted = True
                    status["interrupted"] = True
                    if status.get("benchmark_exit_code") in (None, 0):
                        status["benchmark_exit_code"] = 130
            status["server_exit_code"] = stop_owned(server, unit_name)
            status["preparation_server_exit_code"] = stop_owned(
                preparation_server, unit_name + "-prep")
    status["elapsed_ms"] = (time.monotonic_ns() - start_ns) / 1_000_000
    status["environment"] = {"python": sys.version, "cwd": os.getcwd(),
                              "memory_max": args.memory_max if args.systemd else None,
                              "memory_swap_max": args.memory_swap_max if args.systemd else None}
    raw.write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")
    if interrupted:
        raise KeyboardInterrupt
    return status


def _rewrite_status(result: dict[str, Any]) -> None:
    """Persist runner-level retry/degradation annotations.

    ``_run_cell`` writes its status before the caller knows whether a
    5-worker attempt needs a 4-worker retry. Keep the per-cell JSON in sync
    with the matrix summary so reports built from cell files retain the
    causal retry relationship.
    """
    path = result.get("raw_json_path")
    if not path:
        return
    try:
        Path(path).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    except OSError:
        # The matrix summary remains authoritative if a late status rewrite
        # cannot be persisted (for example, a read-only evidence directory).
        pass


def _resource_or_worker_failure(result: dict[str, Any]) -> bool:
    """Return whether a failed five-worker cell merits a four-worker retry.

    Correctness mismatches are not resource failures and must not be hidden by
    rerunning the same workload at a different concurrency.  In contrast,
    memory refusals/OOM kills, benchmark timeouts, and worker/session setup
    failures are precisely the cases where the plan permits an explicit
    5->4 degraded run.
    """
    exit_code = result.get("benchmark_exit_code")
    server_exit = result.get("server_exit_code")
    if exit_code in (124, -9, 137) or server_exit in (-9, 137):
        return True
    raw = result.get("benchmark_raw") or {}
    errors = list(raw.get("errors", [])) if isinstance(raw, dict) else []
    text = " ".join(str(value) for value in errors)
    text += " " + str(result.get("error", ""))
    upper = text.upper()
    resource_tokens = (
        "MEMORY_", "MEMORY PRESSURE", "OUTOFMEMORY", "OUT OF MEMORY",
        "OOM", "HEAP EXHAUST", "RESOURCE", "WORKER", "SESSION",
        "CONNECTION", "BROKENPIPE", "TIMEOUT",
    )
    return any(token in upper for token in resource_tokens)


def main() -> int:
    p = argparse.ArgumentParser(description="Run bounded serial-APK / parallel-activity MCP matrix cells.")
    p.add_argument("--apk", action="append", required=True, type=Path, help="APK (repeat exactly three times)")
    p.add_argument("--manifest", action="append", type=Path,
                   help="Phase 0 JSON manifest; repeat three times (or pass one combined index/directory)")
    p.add_argument("--activities", action="append", help="comma-separated activities, one value per APK")
    p.add_argument("--server-command", required=True,
                   help="server command template; supports {port} {profile} {temp} {shared_engine_slots}")
    p.add_argument("--benchmark-command", default=f"{shlex.quote(sys.executable)} benchmarks/same_apk_mcp_benchmark.py --server {{server}} --apk {{apk}} {{file_hash_arg}} --activities {{activities}} --methods {{methods}} --mode {{mode}} --workers {{workers}} --timeout {{timeout}} --pid {{server_pid}} {{cgroup_arg}} --output-dir {{artifact_dir}} --run-id {{run_id}} --phase {{phase}} --variant {{variant}} {{golden_option}}", help="benchmark command template")
    p.add_argument("--warm-prepare-command", default=f"{shlex.quote(sys.executable)} benchmarks/warm_prepare.py --server {{server}} --apk {{apk}} --upload-dir {{upload_dir}} --timeout {{timeout}}", help="command template used for WARM preparation")
    p.add_argument("--terminal-command", default=f"{shlex.quote(sys.executable)} benchmarks/terminal_probe.py --server-url {{server}}/mcp --pid {{server_pid}} {{cgroup_arg}} --output {{artifact_dir}}/terminal.json --grace-seconds {{terminal_grace_seconds}}", help="post-T_end terminal probe command; empty disables it")
    p.add_argument("--sampling-hook", help="optional command template run during a cell; supports {server} {log} {out}")
    p.add_argument("--profile", action="append", choices=PROFILES, help="default: all profiles")
    p.add_argument("--temperature", action="append", choices=TEMPS, help="default: APP-COLD and WARM (APP-WARM compatibility alias)")
    p.add_argument("--mode", action="append", choices=MODES, help="default: BURST and AGENT")
    # ``--rounds`` remains the compatibility spelling for callers that run a
    # contiguous sequence from round zero.  The explicit pair is useful when
    # a matrix is split into independent batches (for example, a retry of
    # rounds 3..5) while retaining globally unique cell identities.
    p.add_argument("--rounds", type=int, default=None,
                   help="compatibility alias for --round-count starting at round 0")
    p.add_argument("--round-start", type=int, default=0,
                   help="first persisted round number (default: 0)")
    p.add_argument("--round-count", type=int,
                   help="number of rounds to run; defaults to --rounds or 1")
    p.add_argument("--workers", type=int, choices=(4, 5), default=5)
    p.add_argument("--allow-four", action="store_true", help="permit explicit 5->4 fallback on resource/worker failure")
    p.add_argument("--output-dir", type=Path, default=Path("benchmarks/results"))
    p.add_argument("--golden-dir", type=Path, help="directory for isolated correctness baselines")
    p.add_argument("--golden-source-profile", choices=PROFILES, default="CURRENT",
                   help="profile used to seed isolated correctness goldens (default: CURRENT)")
    p.add_argument("--phase", choices=("smoke", "formal"), default="smoke")
    p.add_argument("--systemd", action=argparse.BooleanOptionalAction, default=True)
    # systemd accepts the binary-size suffix ``G`` here; ``GiB`` is rejected
    # by older user managers even though the plan's unit is GiB.  12G is
    # exactly 12 * 1024^3 bytes for MemoryMax.
    p.add_argument("--memory-max", default="12G")
    p.add_argument("--memory-swap-max", default="0")
    p.add_argument("--min-host-available-gib", type=float, default=16.0,
                   help="minimum /proc/meminfo MemAvailable before starting a systemd cell (default: 16 GiB)")
    p.add_argument("--base-port", type=int, default=18080)
    p.add_argument("--startup-wait", type=float, default=1.0)
    p.add_argument("--timeout", type=float, default=1800.0)
    p.add_argument("--retry-timeout", type=float, default=600.0,
                   help="maximum seconds for an explicit 5->4 resource retry (default: 600)")
    p.add_argument("--prepare-timeout", type=float, default=1800.0)
    p.add_argument("--terminal-timeout", type=float, default=420.0)
    p.add_argument("--terminal-grace-seconds", type=float, default=0.0,
                   help="quiescence grace outside workload timing (formal default should be 30)")
    args = p.parse_args()
    if len(args.apk) != 3:
        p.error("--apk must be supplied exactly three times (APKs run serially)")
    if args.workers == 4 and not args.allow_four:
        p.error("--workers 4 requires --allow-four (degradation must be explicit)")
    if not args.manifest and not args.activities:
        p.error("provide --manifest or three --activities values")
    if args.activities and len(args.activities) != 3:
        p.error("--activities must be supplied exactly three times")
    if args.round_start < 0:
        p.error("--round-start must be non-negative")
    if args.rounds is not None and args.round_count is not None:
        p.error("use only one of --rounds and --round-count")
    round_count = args.round_count if args.round_count is not None else (args.rounds or 1)
    if round_count < 1:
        p.error("--round-count/--rounds must be positive")
    if args.manifest and len(args.manifest) not in (1, 3):
        p.error("--manifest may be supplied once (combined index/directory) or exactly three times")

    # The formal plan reserves the cgroup cap plus 4 GiB for the host.  Make
    # that invariant executable: do not launch even the first JVM if the
    # host is already below the reserve.  A structured record makes a
    # preflight block distinguishable from a failed benchmark cell.
    if args.systemd and args.min_host_available_gib > 0:
        required_bytes = int(args.min_host_available_gib * (1024 ** 3))
        available_bytes = _host_available_bytes()
        if available_bytes is None:
            record = _write_preflight_failure(
                args, "cannot read /proc/meminfo MemAvailable", available_bytes, required_bytes)
            p.error(f"host preflight unavailable; see {record}")
        if available_bytes < required_bytes:
            record = _write_preflight_failure(
                args,
                f"MemAvailable below safety reserve ({available_bytes} < {required_bytes} bytes)",
                available_bytes,
                required_bytes,
            )
            p.error(f"host available memory below {args.min_host_available_gib:g} GiB; see {record}")

    # Resolve the common Phase 0 output layout: one JSON file per APK.  A
    # directory is matched by APK basename, while three files are positional.
    manifest_by_apk: dict[Path, Path] = {}
    if args.manifest and len(args.manifest) == 3:
        manifest_by_apk = dict(zip(args.apk, args.manifest))
    elif args.manifest and args.manifest[0].is_dir():
        root = args.manifest[0]
        for apk in args.apk:
            candidates = (root / f"{apk.stem}.json", root / f"{apk.name}.json")
            match = next((candidate for candidate in candidates if candidate.is_file()), None)
            if match is None:
                p.error(f"no Phase 0 manifest found in {root} for {apk.name}")
            manifest_by_apk[apk] = match
    elif args.manifest:
        manifest_by_apk = {apk: args.manifest[0] for apk in args.apk}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    profiles, temps, modes = args.profile or list(PROFILES), args.temperature or ["APP-COLD", "WARM"], args.mode or list(MODES)
    results: list[dict[str, Any]] = []
    round_numbers = range(args.round_start, args.round_start + round_count)
    for apk_i, apk in enumerate(args.apk):
        explicit = args.activities[apk_i].split(",") if args.activities else None
        manifest = manifest_by_apk.get(apk)
        acts = _activities(manifest, apk, explicit) if manifest else [x.strip() for x in explicit or [] if x.strip()]
        methods = _methods(manifest, apk, acts, None)
        if len(acts) < 5:
            p.error(f"{apk}: need at least five activities for the default worker count")
        for profile in profiles:
            for temp in temps:
                for mode in modes:
                    for round_no in round_numbers:
                        result = _run_cell(args, apk, acts, profile, temp, mode, round_no, args.output_dir, methods=methods)
                        # Keep the first successful CURRENT response set as a
                        # golden for subsequent profiles.  It is copied only
                        # after the raw artifact is complete, and never
                        # overwritten by an optimized result.
                        if profile == args.golden_source_profile and result.get("benchmark_exit_code") == 0:
                            artifact_name = f"{apk.stem}-{profile}-{('WARM' if temp == 'APP-WARM' else temp)}-{mode}-r{round_no}"
                            if result.get("effective_worker_count") != args.workers:
                                artifact_name += f"-w{result['effective_worker_count']}"
                            artifact = args.output_dir / "artifacts" / artifact_name
                            correctness = artifact / "correctness.json"
                            golden_dir = args.golden_dir or (args.output_dir / "goldens")
                            golden_dir.mkdir(parents=True, exist_ok=True)
                            golden = golden_dir / f"{apk.stem}-{('WARM' if temp == 'APP-WARM' else temp)}-{mode}-r{round_no}.json"
                            if correctness.is_file() and not golden.exists():
                                try:
                                    correctness_data = json.loads(correctness.read_text(encoding="utf-8"))
                                except (OSError, ValueError):
                                    correctness_data = None
                                records = correctness_data.get("records", []) if isinstance(correctness_data, dict) else []
                                expected_requests = int(result.get("effective_worker_count", args.workers)) * 7
                                all_ok = isinstance(records, list) and len(records) == expected_requests and all(
                                    isinstance(record, dict) and record.get("ok") is True for record in records
                                )
                                if isinstance(correctness_data, dict) and correctness_data.get("status") == "match" and all_ok:
                                    shutil.copyfile(correctness, golden)
                        # Resource/worker failures may opt into a transparent retry.
                        failed = result.get("benchmark_exit_code", 1) != 0
                        retryable = _resource_or_worker_failure(result)
                        result["retryable_resource_failure"] = bool(failed and retryable)
                        if failed and retryable and args.workers == 5 and args.allow_four:
                            reason = f"5-worker cell failed (exit={result.get('benchmark_exit_code')}); retried with 4 workers"
                            # Keep the original 5-worker failure in the matrix
                            # summary.  The retry is useful evidence, but
                            # replacing the failed result would hide the
                            # actual capacity boundary from formal reports.
                            result["degradation_reason"] = reason
                            result["original_5_worker_failure"] = True
                            _rewrite_status(result)
                            results.append(result)
                            retry = _run_cell(args, apk, acts, profile, temp, mode, round_no, args.output_dir,
                                              worker_count=4, methods=methods,
                                              timeout_override=args.retry_timeout)
                            retry["degraded"] = True
                            retry["degradation_reason"] = reason
                            # Preserve the causal relationship in the
                            # summary itself; a successful retry has exit=0,
                            # so deriving the original failure only from the
                            # retry's exit code would lose the capacity
                            # boundary.
                            retry["original_5_worker_failure"] = True
                            retry["retry_of"] = result.get("run_id") or result.get("raw_json_path")
                            _rewrite_status(retry)
                            results.append(retry)
                        else:
                            results.append(result)
    # Keep summaries immutable if two short invocations happen to share the
    # same output root and timestamp second.
    summary = args.output_dir / f"matrix-r{args.round_start}-n{round_count}-{_stamp()}.json"
    while summary.exists():
        summary = args.output_dir / f"matrix-r{args.round_start}-n{round_count}-{_stamp()}-{time.time_ns()}.json"
    summary.write_text(json.dumps({"generated_at": _stamp(), "results": results}, indent=2) + "\n", encoding="utf-8")
    return 0 if all(r.get("benchmark_exit_code", 1) == 0 for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
