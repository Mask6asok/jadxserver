#!/usr/bin/env python3
"""Aggregate matrix_runner cells and their immutable benchmark artifacts.

Usage: python3 benchmarks/formal_report.py --results DIR --json report.json \
    --markdown report.md
"""
from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
from typing import Any, Iterable


def _read(path: Path, default: Any = None) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _jsonl(path: Path) -> list[dict[str, Any]]:
    out = []
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                value = json.loads(line)
                if isinstance(value, dict):
                    out.append(value)
            except ValueError:
                continue
    except OSError:
        pass
    return out


def _num(value: Any) -> float | None:
    try:
        value = float(value)
        return value if math.isfinite(value) else None
    except (TypeError, ValueError):
        return None


def _percentile(values: Iterable[float], p: float) -> float | None:
    values = sorted(v for v in values if _num(v) is not None)
    if not values:
        return None
    pos = (len(values) - 1) * p
    lo, hi = math.floor(pos), math.ceil(pos)
    return values[lo] if lo == hi else values[lo] + (values[hi] - values[lo]) * (pos - lo)


def _dist(values: Iterable[Any]) -> dict[str, Any]:
    clean = [float(v) for v in values if _num(v) is not None]
    return {"count": len(clean), "p50": _percentile(clean, .50), "p95": _percentile(clean, .95),
            "min": min(clean) if clean else None, "max": max(clean) if clean else None}


def _first(d: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in d and d[key] is not None:
            return d[key]
    return None


def _artifact_dir(root: Path, result: dict[str, Any]) -> Path | None:
    raw = result.get("raw_json_path")
    if raw:
        candidate = Path(raw).parent / "artifacts" / Path(raw).stem
        if candidate.is_dir():
            return candidate
    run_id = (result.get("benchmark_raw") or {}).get("run_id")
    if not run_id:
        apk = Path(str(result.get("apk", ""))).stem
        profile = result.get("profile", "")
        temp = result.get("temperature", "")
        mode = result.get("mode", "")
        run_id = f"{apk}-{profile}-{temp}-{mode}-r{result.get('round', 0)}"
    matches = list((root / "artifacts").glob(f"{run_id}*"))
    return matches[0] if matches else None


def _queue_from_log(result: dict[str, Any]) -> dict[str, Any]:
    """Extract server-side queue observations from the immutable cell log.

    Queue lines intentionally have no request id, so these are aggregate
    observations, not per-request attribution.  Keep the ambiguity explicit
    while still reporting the wait distribution and maximum observed depth.
    """
    path = result.get("log_path")
    if not path:
        return {"available": False, "reason": "log_path missing"}
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        return {"available": False, "reason": str(exc)}
    enqueues = len(re.findall(r"\[QUEUE\] enqueue", text))
    dequeues = len(re.findall(r"\[QUEUE\] dequeue", text))
    timeouts = len(re.findall(r"\[QUEUE\] timeout", text))
    waits = [int(v) for v in re.findall(r"\[QUEUE\] dequeue[^\n]*?waitMs=(\d+)", text)]
    depths = [int(v) for v in re.findall(r"queueDepth=(\d+)", text)]
    return {"available": True, "enqueue_count": enqueues, "dequeue_count": dequeues,
            "timeout_count": timeouts, "wait_ms": _dist(waits),
            "max_depth": max(depths) if depths else 0,
            "per_request_correlation": False}


def _cell(root: Path, matrix: dict[str, Any], result: dict[str, Any]) -> dict[str, Any]:
    art = _artifact_dir(root, result)
    run = _read(art / "run.json", {}) if art else {}
    requests = _jsonl(art / "requests.jsonl") if art else []
    memory = _jsonl(art / "memory.jsonl") if art else []
    correctness = _read(art / "correctness.json", {}) if art else {}
    terminal = _read(art / "terminal.json", {}) if art else {}
    # The matrix result is authoritative for the harness exit/degrade metadata;
    # artifact terminal/correctness records are used as fallbacks for older runs.
    result_terminal = result.get("terminal") if isinstance(result.get("terminal"), dict) else {}
    if not terminal:
        terminal = result_terminal
    raw = result.get("benchmark_raw") or {}
    if not requests:
        for worker in raw.get("workers", []):
            requests.extend(worker.get("requests", []))
    errors = sum(1 for r in requests if r.get("ok") is False or r.get("error"))
    errors = max(errors, int(_num(run.get("error_count")) or 0), len(raw.get("errors", [])))
    completed = _first(run, "completed_requests")
    completed = int(completed) if completed is not None else sum(1 for r in requests if r.get("ended_ns") is not None)
    workers = int(_num(_first(run, "worker_count", "requested_worker_count") or
                       result.get("effective_worker_count", result.get("worker_count", 0))) or 0)
    expected = _first(run, "expected_requests")
    if expected is None:
        expected = _first(raw, "expected_requests")
    if expected is None:
        worker_records = [w for w in raw.get("workers", []) if isinstance(w, dict)]
        counts = [len(w.get("requests", [])) for w in worker_records]
        # A cgroup/JVM failure can terminate the benchmark before it emits
        # run.json or its worker records.  The harness workload is fixed at
        # seven tools per worker, so retain that denominator instead of
        # turning an unstarted 5-worker cell into the misleading 0/0.
        expected = workers * max(counts) if counts and workers else workers * 7
    mismatch = correctness.get("mismatches", [])
    mismatch_count = len(mismatch) if isinstance(mismatch, list) else int(_num(correctness.get("mismatch_count")) or 0)
    metadata_ok = correctness.get("metadata_ok")
    golden_verified = correctness.get("golden_verified")
    # A formal correctness claim needs an explicit golden comparison.  Older
    # artifacts lack these fields and remain usable as diagnostics, but are
    # not silently promoted to eligible A/B cells.
    correctness_proven = metadata_ok is True and golden_verified is True
    correctness_status = str(correctness.get("status", "")).lower()
    if correctness_proven and correctness_status in {"ok", "match", "success"} and mismatch_count == 0:
        correctness_gate = "verified"
    elif mismatch_count > 0 or correctness_status in {"mismatch", "failed", "error"}:
        correctness_gate = "failed"
    else:
        # A baseline/golden seed can have a self-consistent `match` status
        # without having been compared to an explicit golden.  Keep it visible
        # as a diagnostic, but never let it enter an eligible A/B summary.
        correctness_gate = "unverified"
    exit_code = _num(result.get("benchmark_exit_code", 0))
    failed = (exit_code not in (None, 0) or errors > 0 or mismatch_count > 0 or
              str(correctness.get("status", "")).lower() in {"error", "failed", "mismatch"})
    status = "error" if failed else ("degraded" if result.get("degraded") or workers == 4 else "success")
    requested_workers = int(_num(_first(result, "requested_worker_count", "worker_count") or 0) or 0)
    degraded_retry = bool(result.get("degraded") and requested_workers == 5 and workers == 4)
    original_5_worker_failure = bool(
        result.get("original_5_worker_failure") or
        (requested_workers == 5 and exit_code not in (None, 0))
    )
    correctness_mismatch = bool(mismatch_count > 0 or str(correctness.get("status", "")).lower() in {"mismatch", "failed"})
    failure_reasons: list[str] = []
    if original_5_worker_failure:
        failure_reasons.append("5-worker original failed")
    if degraded_retry:
        failure_reasons.append("retried with 4 workers (degraded)")
    if correctness_mismatch:
        failure_reasons.append("correctness mismatch")
    if errors > 0:
        failure_reasons.append("request errors")
    if exit_code not in (None, 0) and not original_5_worker_failure:
        failure_reasons.append(f"benchmark exit={int(exit_code)}")
    # A retried cell is primarily a degraded retry, while the boolean retains
    # the separately reportable fact that its original five-worker attempt failed.
    failure_class = ("degraded_4_worker_retry" if degraded_retry else
                     "original_5_worker_failure" if original_5_worker_failure else
                     "correctness_mismatch" if correctness_mismatch else
                     "benchmark_error" if failed else "success")
    quiescence_proven = bool(result_terminal.get("quiescence_proven", terminal.get("quiescence_proven", False)))
    durations = []
    per_tool: dict[str, list[float]] = {}
    for req in requests:
        ms = _num(_first(req, "wall_ms", "latency_ms"))
        start_ns = _first(req, "started_ns", "start_ns")
        end_ns = _first(req, "ended_ns", "end_ns")
        if ms is None and _num(start_ns) is not None and _num(end_ns) is not None:
            ms = (end_ns - start_ns) / 1e6
        if ms is not None:
            durations.append(ms); per_tool.setdefault(str(req.get("tool", "?")), []).append(ms)
    peaks: dict[str, int | None] = {}
    for name in ("rss_bytes", "pss_bytes", "vmhwm_bytes", "cgroup_memory_peak_bytes", "heap_used_bytes", "heap_committed_bytes", "heap_max_bytes"):
        vals = [_num(row.get(name)) for row in memory]
        vals = [int(v) for v in vals if v is not None]
        peaks[name] = max(vals) if vals else (_num(terminal.get(name)) if name in terminal else None)
    queue = {k: _dist(r[k] for r in requests if _num(r.get(k)) is not None)
             for k in ("queue_wait_ms", "queue_wait", "queue_wait_ns") if any(k in r for r in requests)}
    resource_deltas = {key: run.get(key) for key in (
        "cpu_user_jiffies_delta", "cpu_system_jiffies_delta", "minor_faults_delta",
        "major_faults_delta", "io_read_bytes_delta", "io_write_bytes_delta")
        if key in run}
    expected = int(expected) if expected is not None else None
    run_id = run.get("run_id") or raw.get("run_id")
    if not run_id and result.get("raw_json_path"):
        # A runner-level failure can terminate the benchmark before it writes
        # run.json. The cell filename is still a stable identity and is
        # preferable to reporting a misleading null retry id.
        run_id = Path(str(result["raw_json_path"])).stem
    return {"apk": result.get("apk"), "profile": result.get("profile"), "temperature": result.get("temperature", result.get("temp")),
            "mode": result.get("mode"), "round": result.get("round"), "run_id": run_id,
            "status": status, "failure_class": failure_class, "failure_reasons": failure_reasons,
            "original_5_worker_failure": original_5_worker_failure,
            "degraded": bool(result.get("degraded") or workers == 4),
            "degradation_reason": result.get("degradation_reason"),
            "retry_of": result.get("retry_of"), "workers": workers,
            "requested_workers": requested_workers, "benchmark_exit_code": int(exit_code) if exit_code is not None else None,
            "requests": {"completed": completed, "expected": expected, "errors": errors},
            "correctness": {
                "status": correctness.get("status"),
                "gate": correctness_gate,
                "mismatches": mismatch_count,
                "wire_location_drift": int(_num(correctness.get("wire_location_drift_count")) or 0),
                # The harness historically wrote `match`, while newer
                # producers may use `ok`/`success`.  Treat only these
                # explicit success states with zero mismatches as eligible;
                # missing status is not a correctness proof.
                "ok": str(correctness.get("status", "")).lower() in {"ok", "match", "success"}
                and mismatch_count == 0 and correctness_proven,
                "metadata_ok": metadata_ok,
                "golden_verified": golden_verified,
            },
            "quiescence_proven": quiescence_proven,
            "makespan_ms": _first(run, "makespan_ms") or _first(raw, "makespan_ms"),
            "latency_ms": {"overall": _dist(durations), "by_tool": {k: _dist(v) for k, v in sorted(per_tool.items())}},
            "memory_peak_bytes": peaks,
            "terminal": {k: terminal.get(k) for k in ("gc_requested", "gc_observation_method", "heap_used_bytes", "heap_committed_bytes", "heap_max_bytes", "rss_bytes", "pss_bytes", "vmhwm_bytes", "cgroup_memory_current_bytes", "live_set_definition", "observed_at_ns") if k in terminal},
            "resource_deltas": resource_deltas,
            "queue": {"request_fields": queue, "server_log": _queue_from_log(result)},
            "artifact_dir": str(art) if art else None}


def _comparison_summary(cells: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Summarize only apples-to-apples, formally admissible cells."""
    groups: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
    for cell in cells:
        key = (Path(str(cell.get("apk", ""))).stem, str(cell.get("temperature")), str(cell.get("mode")))
        groups.setdefault(key, []).append(cell)
    out = []
    for (apk, temperature, mode), members in sorted(groups.items()):
        profiles: dict[str, Any] = {}
        for profile in ("CURRENT", "O1-1", "O1-2"):
            selected = [c for c in members if c.get("profile") == profile]
            eligible, excluded = [], []
            if not selected:
                excluded.append({"run_id": None, "reasons": ["profile cell missing"]})
            for c in selected:
                reasons = []
                if c.get("benchmark_exit_code") != 0: reasons.append(f"benchmark_exit={c.get('benchmark_exit_code')}")
                if not c.get("quiescence_proven"): reasons.append("terminal/quiescence not proven")
                if not c.get("correctness", {}).get("ok"): reasons.append("correctness not ok")
                (eligible if not reasons else excluded).append(c if not reasons else {"run_id": c.get("run_id"), "reasons": reasons})
            profiles[profile] = {
                "eligible_cells": len(eligible),
                "makespan_ms": _dist(c["makespan_ms"] for c in eligible),
                "latency_ms": _dist(c["latency_ms"]["overall"]["p50"] for c in eligible),
                "rss_peak_bytes": _dist(c["memory_peak_bytes"].get("rss_bytes") for c in eligible),
                "wire_location_drift": _dist(c["correctness"].get("wire_location_drift", 0) for c in eligible),
                "wire_location_drift_total": sum(int(c["correctness"].get("wire_location_drift", 0) or 0) for c in eligible),
                "excluded": excluded,
            }
        out.append({"apk": apk, "temperature": temperature, "mode": mode, "profiles": profiles})
    return out


def aggregate(root: Path) -> dict[str, Any]:
    matrices = sorted(root.glob("matrix-*.json"))
    results: list[dict[str, Any]] = []
    # Split round batches normally have disjoint cell identities. If a batch
    # is retried into the same output root, however, its matrix manifest can
    # mention an already-existing raw path; count that cell once (the latest
    # manifest is authoritative) rather than silently doubling a sample.
    by_identity: dict[str, dict[str, Any]] = {}
    for matrix_path in matrices:
        matrix = _read(matrix_path, {})
        if isinstance(matrix, dict) and isinstance(matrix.get("results"), list):
            for item in matrix["results"]:
                if not isinstance(item, dict):
                    continue
                identity = str(item.get("raw_json_path") or item.get("run_id") or
                               f"{item.get('apk')}|{item.get('profile')}|{item.get('temperature')}|{item.get('mode')}|{item.get('round')}|{item.get('effective_worker_count')}")
                by_identity[identity] = item
    # A runner interrupted during a cell's terminal/preparation cleanup may
    # not reach the final matrix manifest write, even though _run_cell has
    # already persisted its per-cell status JSON. Recover those immutable
    # statuses as a report fallback; matrix entries remain authoritative when
    # both sources contain the same raw_json_path/run_id.
    for status_path in sorted(root.glob("*.json")):
        if status_path.name.startswith("matrix-") or status_path.name.startswith("formal-report"):
            continue
        item = _read(status_path, {})
        if not isinstance(item, dict) or not item.get("apk") or not item.get("profile"):
            continue
        if not item.get("raw_json_path") and not item.get("run_id"):
            continue
        identity = str(item.get("raw_json_path") or item.get("run_id") or
                       f"{item.get('apk')}|{item.get('profile')}|{item.get('temperature')}|{item.get('mode')}|{item.get('round')}|{item.get('effective_worker_count')}")
        by_identity.setdefault(identity, item)
    results = list(by_identity.values())
    matrix_name = ",".join(path.name for path in matrices) if matrices else None
    cells = [_cell(root, {}, item) for item in results]
    return {"schema": "formal-report/v2", "results_dir": str(root), "matrix": matrices[-1].name if matrices else None,
            "cell_count": len(cells), "status_counts": {s: sum(c["status"] == s for c in cells) for s in ("success", "degraded", "error")},
            "failure_class_counts": {s: sum(c["failure_class"] == s for c in cells) for s in ("success", "original_5_worker_failure", "degraded_4_worker_retry", "correctness_mismatch", "benchmark_error")},
            "cells": cells, "comparisons": _comparison_summary(cells)}


def markdown(report: dict[str, Any]) -> str:
    lines = ["# Benchmark formal report", "", f"Results: `{report['results_dir']}`", "", f"Cells: **{report['cell_count']}**; status: {report['status_counts']}", f"Failure classes: `{report.get('failure_class_counts', {})}`", "", "| APK | Profile | Temp | Mode | Round | Status / failure class | Correctness gate | Completed/Expected | Errors | Semantic mismatch | Wire location drift | Makespan ms | p50/p95 ms | RSS/PSS peak | cgroup peak | CPU user/sys jiffies | I/O read/write |", "|---|---|---|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for c in report["cells"]:
        p = c["latency_ms"]["overall"]; m = c["memory_peak_bytes"]
        cpu = c.get("resource_deltas", {})
        lines.append(f"| `{Path(str(c['apk'])).stem}` | {c['profile']} | {c['temperature']} | {c['mode']} | {c['round']} | **{c['status']}** / `{c['failure_class']}` | `{c['correctness'].get('gate')}` | {c['requests']['completed']}/{c['requests']['expected']} | {c['requests']['errors']} | {c['correctness']['mismatches']} | {c['correctness'].get('wire_location_drift', 0)} | {c['makespan_ms']} | {p['p50']}/{p['p95']} | {m['rss_bytes']}/{m['pss_bytes']} | {m['cgroup_memory_peak_bytes']} | {cpu.get('cpu_user_jiffies_delta')}/{cpu.get('cpu_system_jiffies_delta')} | {cpu.get('io_read_bytes_delta')}/{cpu.get('io_write_bytes_delta')} |")
    lines += ["", "## CURRENT / O1-1 / O1-2 formal comparisons", "", "Only cells with benchmark exit 0, proven terminal/quiescence, and correctness status `ok` enter these summaries; excluded cells retain their reasons.", ""]
    for group in report.get("comparisons", []):
        lines.append(f"### `{group['apk']}` · {group['temperature']} · {group['mode']}")
        lines.append("")
        lines.append("| Profile | Eligible | Makespan p50/p95 ms | Request p50/p95 ms | RSS peak p50/p95 bytes | Wire drift total/p95 | Excluded |")
        lines.append("|---|---:|---:|---:|---:|---:|---|")
        for profile in ("CURRENT", "O1-1", "O1-2"):
            item = group["profiles"][profile]
            excluded = "; ".join(f"{x.get('run_id')}: {', '.join(x['reasons'])}" for x in item["excluded"]) or "—"
            lines.append(f"| {profile} | {item['eligible_cells']} | {item['makespan_ms']['p50']}/{item['makespan_ms']['p95']} | {item['latency_ms']['p50']}/{item['latency_ms']['p95']} | {item['rss_peak_bytes']['p50']}/{item['rss_peak_bytes']['p95']} | {item['wire_location_drift_total']}/{item['wire_location_drift']['p95']} | {excluded} |")
    lines += ["", "Status semantics: `success` completed without recorded errors; `degraded_4_worker_retry` records the 5-worker failure and its 4-worker retry; `original_5_worker_failure` identifies the failed 5-worker attempt; `correctness_mismatch` is kept distinct from execution failure. Correctness gates are `verified` (explicit golden + metadata + semantic match), `unverified` (typically a baseline golden seed), or `failed`. `Semantic mismatch` compares stable class/method identities and metadata; `Wire location drift` counts otherwise equivalent xref responses whose generated source line/content changed. Raw wire digests and responses remain in each artifact. Missing metrics remain null."]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--json", required=True, type=Path, dest="json_path")
    parser.add_argument("--markdown", required=True, type=Path, dest="markdown_path")
    args = parser.parse_args()
    report = aggregate(args.results)
    args.json_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.markdown_path.write_text(markdown(report), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
