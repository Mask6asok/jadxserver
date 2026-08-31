#!/usr/bin/env python3
"""Run the five-session, same-APK MCP workload from the memory plan.

The server is intentionally started outside this script so the caller can
compare CURRENT and SHARED JVMs with identical flags.  The script uploads the
APK once, negotiates five independent MCP sessions, releases five workers at a
barrier, and records request timings plus optional /proc/cgroup memory data.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import platform
import subprocess
import threading
import time
import urllib.request
import uuid
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


PROTOCOL_VERSION = "2025-03-26"
# Xref source locations are produced by JADX's lazy code-generation pipeline.
# The referenced class/method identities are stable, while line/content fields
# may move when unrelated classes are materialized in a different order.  Keep
# both digests: the semantic digest is the formal correctness key and the wire
# digest remains diagnostic evidence for location drift.
CANONICALIZER_VERSION = "canonical-semantic-v2"
WORKLOAD_TOOLS = (
    "search_manifest_components", "class_info", "get_class_code", "list_methods",
    "get_method_code", "class_xrefs", "method_xrefs",
)


def utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def git_revision() -> str | None:
    try:
        return subprocess.run(("git", "rev-parse", "HEAD"), capture_output=True,
                              text=True, check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None


def process_command_line(pid: int | None) -> str | None:
    if pid is None:
        return None
    try:
        return Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode().strip()
    except (OSError, UnicodeError):
        return None


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_semantic(value: Any, *, strip_xref_locations: bool = False) -> Any:
    """Normalize response JSON for a stable semantic digest.

    JADX xref results are sets discovered through hash-based collections, so
    their wire order can differ between otherwise equivalent JVM lifecycles.
    Arrays are therefore sorted by their recursively canonical JSON form for
    digesting only; the response sent by the server is never modified.  When
    ``strip_xref_locations`` is enabled, generated source ``line`` and
    ``content`` fields are omitted because lazy JADX code generation can move
    those locations while preserving the referenced class/method identity.
    """
    if isinstance(value, dict):
        return {
            str(key): canonical_semantic(item, strip_xref_locations=strip_xref_locations)
            for key, item in value.items()
            if not (strip_xref_locations and str(key) in {"line", "content"})
        }
    if isinstance(value, list):
        normalized = [canonical_semantic(item, strip_xref_locations=strip_xref_locations) for item in value]
        return sorted(normalized, key=lambda item: json.dumps(item, ensure_ascii=False,
                                                              sort_keys=True, separators=(",", ":")))
    return value


def canonical_mcp_result(value: Any, *, strip_xref_locations: bool = False) -> Any:
    """Canonicalize the JSON-RPC result including text-encoded ToolResult.

    The Kotlin MCP SDK may expose structured data only as
    ``result.content[*].text``.  Those text fields contain another JSON
    document, so a normal recursive pass over the JSON-RPC envelope would
    leave xref arrays opaque and order-sensitive.
    """
    if not isinstance(value, dict):
        return canonical_semantic(value, strip_xref_locations=strip_xref_locations)
    result = dict(value)
    content = result.get("content")
    if isinstance(content, list):
        normalized_content = []
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text" and isinstance(item.get("text"), str):
                copy = dict(item)
                try:
                    inner = json.loads(copy["text"])
                except (TypeError, ValueError):
                    pass
                else:
                    copy["text"] = json.dumps(canonical_semantic(inner,
                                                                  strip_xref_locations=strip_xref_locations), ensure_ascii=False,
                                               sort_keys=True, separators=(",", ":"))
                normalized_content.append(copy)
            else:
                normalized_content.append(item)
        result["content"] = normalized_content
    if "structuredContent" in result:
        result["structuredContent"] = canonical_semantic(
            result["structuredContent"], strip_xref_locations=strip_xref_locations)
    return canonical_semantic(result, strip_xref_locations=strip_xref_locations)


def mcp_error_message(event: dict[str, Any]) -> str | None:
    """Return a typed MCP tool error, including ``result.isError``.

    The MCP SDK encodes tool failures inside a successful JSON-RPC envelope:
    ``result.isError=true`` and a text content block.  Treating only the
    top-level JSON-RPC ``error`` member as a failure makes memory refusals and
    worker-exhausted responses look successful and can corrupt correctness
    statistics (especially when all five queued requests receive the same
    refusal payload).
    """
    if not isinstance(event, dict):
        return "invalid JSON-RPC event"
    if event.get("error") is not None:
        return json.dumps(event["error"], ensure_ascii=False)
    if "result" not in event:
        return "missing JSON-RPC result"
    result = event.get("result")
    if not isinstance(result, dict):
        return None
    if result.get("isError") is not True:
        return None
    content = result.get("content")
    if isinstance(content, list):
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                text = item.get("text")
                if isinstance(text, str) and text:
                    return text
    return "MCP tool returned isError=true"


def write_artifacts(output_dir: Path, run: dict[str, Any], result: dict[str, Any],
                    correctness_golden: Path | None = None) -> None:
    """Write immutable raw records and small run-level evidence files.

    The normal stdout result remains unchanged; this is an additional durable
    representation suitable for phase0/smoke/formal collection.
    """
    output_dir.mkdir(parents=True, exist_ok=False)
    write_json(output_dir / "run.json", run)
    request_records: list[dict[str, Any]] = []
    for worker, worker_data in enumerate(result["workers"]):
        for sample in worker_data["requests"]:
            request_records.append({
                "run_id": run["run_id"], "worker": worker,
                "activity": worker_data.get("activity"), "method": worker_data.get("method"),
                "session_id": worker_data.get("session_id"),
                "request_id": sample.get("request_id"), "tool": sample["tool"],
                "file_hash": result["file_hash"],
                "started_ns": sample["start_ns"],
                "headers_received_ns": sample.get("headers_received_ns"),
                "body_finished_ns": sample.get("body_finished_ns"),
                "ended_ns": sample["end_ns"], "ok": sample["ok"],
                "error": sample.get("error"),
                "queue_wait_ms": None, "response_bytes": sample.get("response_bytes", 0),
                "response_digest": sample.get("response_digest"),
                "wire_response_digest": sample.get("wire_response_digest"),
            })
    with (output_dir / "requests.jsonl").open("w") as stream:
        for record in request_records:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    with (output_dir / "memory.jsonl").open("w") as stream:
        for sample in result.get("memory_samples", []):
            record = {"run_id": run["run_id"], "sample_ns": sample.get("sample_ns"),
                      "pid": run.get("pid")}
            for key in ("rss_bytes", "vmhwm_bytes", "vmsize_bytes", "pss_bytes",
                        "heap_used_bytes", "heap_committed_bytes", "heap_max_bytes",
                        "cgroup_memory_current_bytes", "cgroup_memory_peak_bytes",
                        "cgroup_swap_current_bytes", "threads", "minor_faults",
                        "major_faults", "cpu_user_jiffies", "cpu_system_jiffies",
                        "io_read_bytes", "io_write_bytes"):
                record[key] = sample.get(key)
            stream.write(json.dumps(record, sort_keys=True) + "\n")

    digests = [{"worker": i, "activity": worker.get("activity"), "method": worker.get("method"),
                "tool": req["tool"], "request_id": req["request_id"],
                "response_digest": req["response_digest"],
                "wire_response_digest": req.get("wire_response_digest"), "ok": req["ok"]}
               for i, worker in enumerate(result["workers"]) for req in worker["requests"]]
    expected = None
    if correctness_golden:
        expected = json.loads(correctness_golden.read_text())
    metadata = {
        "canonicalizer_version": CANONICALIZER_VERSION,
        "file_hash": result.get("file_hash"),
        "apk_sha256": run.get("apk_sha256"),
        "worker_count": result.get("worker_count"),
        "activities": result.get("activities"),
        "methods": [worker.get("method") for worker in result.get("workers", [])],
        "tools": list(WORKLOAD_TOOLS),
        "xref_semantic_policy": "identity-fields-only; line/content retained in wire_response_digest",
    }
    mismatches = []
    wire_mismatches = []
    metadata_mismatches = []
    key_set_mismatch = None
    if isinstance(expected, dict):
        expected_records = expected.get("records", expected)
        if isinstance(expected_records, dict):
            expected_records = [expected_records]
        expected_records = [x for x in expected_records if isinstance(x, dict)]
        def record_key(record: dict[str, Any]) -> tuple[Any, ...]:
            # Activity identity remains stable when a burst queue serves the
            # five HTTP sessions in a different order.  Old golden files did
            # not carry activity/method, so retain their worker/tool key.
            if record.get("activity") is not None:
                return ("activity", record.get("activity"), record.get("method"), record.get("tool"))
            return ("worker", record.get("worker"), record.get("tool"))

        expected_map = {record_key(x): x for x in expected_records}
        actual_map = {record_key(x): x for x in digests}
        missing = sorted(set(expected_map) - set(actual_map), key=str)
        extra = sorted(set(actual_map) - set(expected_map), key=str)
        if missing or extra:
            key_set_mismatch = {"missing": missing, "extra": extra}
            mismatches.append({"kind": "key_set", **key_set_mismatch})
        expected_metadata = expected.get("metadata")
        if isinstance(expected_metadata, dict):
            for key in ("canonicalizer_version", "file_hash", "apk_sha256", "worker_count",
                        "activities", "methods", "tools", "xref_semantic_policy"):
                if expected_metadata.get(key) != metadata.get(key):
                    metadata_mismatches.append({"field": key, "expected": expected_metadata.get(key),
                                                "actual": metadata.get(key)})
            if metadata_mismatches:
                mismatches.append({"kind": "metadata", "fields": metadata_mismatches})
        for record in digests:
            key = record_key(record)
            expected_record = expected_map.get(key)
            if expected_record is None:
                continue
            if expected_record.get("response_digest") != record["response_digest"]:
                mismatches.append({"worker": record.get("worker"),
                                   "activity": record.get("activity"),
                                   "method": record.get("method"),
                                   "tool": record.get("tool"),
                                   "expected": expected_record.get("response_digest"),
                                   "actual": record["response_digest"]})
            elif (expected_record.get("wire_response_digest") is not None
                  and record.get("wire_response_digest") is not None
                  and expected_record.get("wire_response_digest") != record.get("wire_response_digest")):
                wire_mismatches.append({"worker": record.get("worker"),
                                        "activity": record.get("activity"),
                                        "method": record.get("method"),
                                        "tool": record.get("tool"),
                                        "expected": expected_record.get("wire_response_digest"),
                                        "actual": record.get("wire_response_digest")})
            if expected_record.get("ok") is not True or record.get("ok") is not True:
                mismatches.append({"kind": "request_status", "worker": record.get("worker"),
                                   "activity": record.get("activity"),
                                   "method": record.get("method"),
                                   "tool": record.get("tool"),
                                   "expected_ok": expected_record.get("ok"),
                                   "actual_ok": record.get("ok")})
    golden_metadata_present = isinstance(expected, dict) and isinstance(expected.get("metadata"), dict)
    golden_verified = bool(correctness_golden and isinstance(expected, dict))
    write_json(output_dir / "correctness.json", {
        "schema": "correctness/v2", "run_id": run["run_id"], "metadata": metadata,
        "records": digests, "mismatches": mismatches,
        "metadata_ok": (not metadata_mismatches) if correctness_golden else None,
        "golden_metadata_present": golden_metadata_present,
        "golden_verified": golden_verified,
        "wire_mismatches": wire_mismatches,
        "wire_location_drift_count": len(wire_mismatches),
        "metadata_mismatches": metadata_mismatches,
        "key_set_mismatch": key_set_mismatch,
        "golden": str(correctness_golden) if correctness_golden else None,
        "status": "match" if not mismatches else "mismatch",
    })
    write_json(output_dir / "terminal.json", {
        "run_id": run["run_id"], "quiescence_grace_ms": run["terminal_grace_ms"],
        "gc_requested": False, "gc_observation_method": "proc/cgroup only",
        "heap_used_bytes": None, "rss_bytes": result.get("memory_terminal", {}).get("rss_bytes"),
        "pss_bytes": result.get("memory_terminal", {}).get("pss_bytes"),
        "cgroup_memory_current_bytes": result.get("memory_terminal", {}).get("cgroup_memory_current_bytes"),
        "live_set_definition": "not measured (no explicit GC request)",
        "observed_at_ns": time.monotonic_ns(),
    })
    files = ["run.json", "requests.jsonl", "memory.jsonl", "correctness.json", "terminal.json"]
    manifest = {"run_id": run["run_id"], "files": {name: hashlib.sha256((output_dir / name).read_bytes()).hexdigest()
                                                       for name in files}}
    write_json(output_dir / "manifest.json", manifest)


@dataclass
class RequestSample:
    tool: str
    start_ns: int
    end_ns: int
    ok: bool
    error: str | None = None
    request_id: int | None = None
    headers_received_ns: int | None = None
    body_finished_ns: int | None = None
    response_bytes: int = 0
    response_digest: str | None = None
    wire_response_digest: str | None = None

    @property
    def wall_ms(self) -> float:
        return (self.end_ns - self.start_ns) / 1_000_000.0

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["wall_ms"] = self.wall_ms
        if self.headers_received_ns is not None:
            value["ttfb_ms"] = (self.headers_received_ns - self.start_ns) / 1_000_000.0
        else:
            value["ttfb_ms"] = None
        if self.body_finished_ns is not None and self.headers_received_ns is not None:
            value["transfer_ms"] = (self.body_finished_ns - self.headers_received_ns) / 1_000_000.0
        else:
            value["transfer_ms"] = None
        return value


@dataclass
class PostResponse:
    event: dict[str, Any]
    headers: dict[str, str]
    request_id: int | None
    started_ns: int
    headers_received_ns: int
    body_finished_ns: int
    ended_ns: int
    response_bytes: int


class McpClient:
    def __init__(self, base_url: str, timeout: float) -> None:
        parsed = urlparse(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError(f"invalid server URL: {base_url}")
        if parsed.scheme != "http":
            raise ValueError("benchmark currently supports HTTP MCP endpoints")
        self.host = parsed.hostname
        self.port = parsed.port or 80
        self.path = parsed.path.rstrip("/") or "/mcp"
        self.timeout = timeout
        self.connection = http.client.HTTPConnection(self.host, self.port, timeout=timeout)
        self.session_id: str | None = None
        self.request_id = 0

    def close(self) -> None:
        self.connection.close()

    def initialize(self) -> None:
        response = self._post({
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "same-apk-benchmark", "version": "1"},
            },
        })
        if response.event.get("error"):
            raise RuntimeError(f"initialize failed: {response.event['error']}")
        self.session_id = response.headers.get("mcp-session-id")
        if not self.session_id:
            raise RuntimeError("server did not return Mcp-Session-Id")
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})

    def call(self, tool: str, arguments: dict[str, Any]) -> tuple[dict[str, Any], PostResponse]:
        if not self.session_id:
            raise RuntimeError("MCP session is not initialized")
        response = self._post({
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/call",
            "params": {"name": tool, "arguments": arguments},
        })
        return response.event, response

    def _next_id(self) -> int:
        self.request_id += 1
        return self.request_id

    def _post(self, payload: dict[str, Any]) -> PostResponse:
        started_ns = time.monotonic_ns()
        body = json.dumps(payload, separators=(",", ":")).encode()
        request_id = payload.get("id")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": PROTOCOL_VERSION,
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        self.connection.request("POST", self.path, body=body, headers=headers)
        response = self.connection.getresponse()
        headers_received_ns = time.monotonic_ns()
        response_headers = {key.lower(): value for key, value in response.getheaders()}
        if response.status >= 400:
            raw = response.read()
            raise RuntimeError(f"HTTP {response.status}: {raw[:500].decode(errors='replace')}")
        content_type = response_headers.get("content-type", "")
        if "text/event-stream" in content_type:
            response_bytes = 0
            # The SDK keeps an SSE response open for notifications.  Consume
            # only the first JSON-RPC data event, then recycle this TCP
            # connection before issuing the next request.
            try:
                while True:
                    line = response.readline()
                    response_bytes += len(line)
                    if not line:
                        now = time.monotonic_ns()
                        return PostResponse({}, response_headers, payload.get("id"), started_ns,
                                            headers_received_ns, now, now, response_bytes)
                    decoded = line.decode(errors="replace").strip()
                    if not decoded.startswith("data:"):
                        continue
                    data = decoded[5:].strip()
                    if data and data != "[DONE]":
                        event = json.loads(data)
                        # Notifications can share the SSE stream with a
                        # request response; match the JSON-RPC id instead of
                        # accidentally treating a notification as the result.
                        if isinstance(event, dict) and (request_id is None or event.get("id") == request_id):
                            body_finished_ns = time.monotonic_ns()
                            return PostResponse(event, response_headers, request_id, started_ns,
                                                headers_received_ns, body_finished_ns, body_finished_ns,
                                                response_bytes)
            finally:
                self.connection.close()
                self.connection = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        raw = response.read()
        body_finished_ns = time.monotonic_ns()
        if not raw:
            return PostResponse({}, response_headers, request_id, started_ns, headers_received_ns,
                                body_finished_ns, body_finished_ns, 0)
        return PostResponse(json.loads(raw.decode(errors="replace")), response_headers, request_id,
                            started_ns, headers_received_ns, body_finished_ns, body_finished_ns, len(raw))


def upload_apk(server: str, apk: Path, timeout: float) -> str:
    boundary = f"----same-apk-{uuid.uuid4().hex}"
    content = apk.read_bytes()
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{apk.name}"\r\n'
        "Content-Type: application/vnd.android.package-archive\r\n\r\n"
    ).encode()
    body = head + content + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        server.rstrip("/") + "/upload",
        data=body,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = json.loads(response.read().decode())
    return data["file_hash"]


def read_memory(pid: int, cgroup_path: Path | None) -> dict[str, int]:
    values: dict[str, int] = {}
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith(("VmRSS:", "VmHWM:", "VmSize:")):
                key, value, unit = line.split()[:3]
                bytes_value = int(value) * (1024 if unit == "kB" else 1)
                short_key = key.rstrip(":").lower()
                values[short_key] = bytes_value
                values[{"vmrss": "rss_bytes", "vmhwm": "vmhwm_bytes", "vmsize": "vmsize_bytes"}[short_key]] = bytes_value
            elif line.startswith("Threads:"):
                values["threads"] = int(line.split()[1])
        try:
            for line in Path(f"/proc/{pid}/smaps_rollup").read_text().splitlines():
                if line.startswith("Pss:"):
                    value, unit = line.split()[1:3]
                    values["pss_bytes"] = int(value) * (1024 if unit == "kB" else 1)
                    break
        except (OSError, ValueError):
            pass
        try:
            # /proc/<pid>/stat fields after the final ')' avoid ambiguity from
            # a command name containing spaces or parentheses.  The suffix
            # starts at field 3 (state): minflt=10, majflt=12, utime=14,
            # stime=15 in the procfs ABI.
            suffix = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
            values["minor_faults"] = int(suffix[7])
            values["major_faults"] = int(suffix[9])
            values["cpu_user_jiffies"] = int(suffix[11])
            values["cpu_system_jiffies"] = int(suffix[12])
        except (OSError, ValueError, IndexError):
            pass
        try:
            for line in Path(f"/proc/{pid}/io").read_text().splitlines():
                key, value = line.split()[:2]
                if key == "read_bytes:":
                    values["io_read_bytes"] = int(value)
                elif key == "write_bytes:":
                    values["io_write_bytes"] = int(value)
        except (OSError, ValueError):
            pass
    except (OSError, ValueError):
        return values
    if cgroup_path:
        for name in ("memory.current", "memory.peak", "memory.swap.current"):
            try:
                bytes_value = int((cgroup_path / name).read_text().strip())
                values[name.replace(".", "_")] = bytes_value
                values[{
                    "memory.current": "cgroup_memory_current_bytes",
                    "memory.peak": "cgroup_memory_peak_bytes",
                    "memory.swap.current": "cgroup_swap_current_bytes",
                }[name]] = bytes_value
            except (OSError, ValueError):
                pass
    return values


def memory_sampler(pid: int | None, cgroup_path: Path | None, stop: threading.Event, samples: list[dict[str, int]]) -> None:
    if pid is None:
        return
    while not stop.is_set():
        sample = read_memory(pid, cgroup_path)
        if sample:
            sample["time_ns"] = time.monotonic_ns()
            sample["sample_ns"] = sample["time_ns"]
            samples.append(sample)
        stop.wait(0.25)


def main() -> int:
    run_started_utc = utc_now()
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="http://127.0.0.1:8080", help="server base URL")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--file-hash", help="reuse an already uploaded hash")
    parser.add_argument("--activities", required=True, help="exactly five comma-separated Activity class names")
    parser.add_argument("--method", default="onCreate", help="method name used for method_xrefs/get_method_code")
    parser.add_argument("--methods", help="comma-separated method names aligned with --activities")
    parser.add_argument("--mode", choices=("burst", "agent"), default="burst")
    parser.add_argument("--workers", type=int, choices=(4, 5), default=5,
                        help="parallel MCP workers/activities (default: 5; 4 is an explicit degraded run)")
    parser.add_argument("--thinking-seconds", type=float, default=1.0)
    parser.add_argument("--pid", type=int)
    parser.add_argument("--cgroup-path", type=Path)
    parser.add_argument("--timeout", type=float, default=1800.0)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--phase", choices=("phase0", "smoke", "formal"), default="smoke")
    parser.add_argument("--variant", choices=("current", "shared"))
    parser.add_argument("--terminal-grace-ms", type=int, default=0)
    parser.add_argument("--correctness-golden", type=Path)
    args = parser.parse_args()

    activities = [name.strip() for name in args.activities.split(",") if name.strip()]
    if len(activities) != args.workers or len(set(activities)) != args.workers:
        parser.error(f"--activities must contain exactly {args.workers} distinct class names")
    methods = [name.strip() for name in args.methods.split(",") if name.strip()] if args.methods else [args.method] * args.workers
    if len(methods) != args.workers or any(not name for name in methods):
        parser.error(f"--methods must contain exactly {args.workers} method names when provided")
    if not args.apk.is_file() and not args.file_hash:
        parser.error(f"APK does not exist: {args.apk}")

    file_hash = args.file_hash or upload_apk(args.server, args.apk, args.timeout)
    worker_count = args.workers
    clients = [McpClient(args.server.rstrip("/") + "/mcp", args.timeout) for _ in range(worker_count)]
    all_samples: list[list[RequestSample]] = [[] for _ in range(worker_count)]
    errors: list[str] = []
    fatal_error = threading.Event()
    ready = threading.Barrier(worker_count)
    release = threading.Barrier(worker_count)

    def worker(index: int) -> None:
        client = clients[index]
        activity = activities[index]
        target_method = methods[index]
        try:
            ready.wait()
            release.wait()
            calls = [
                ("search_manifest_components", {"file_hash": file_hash, "query": activity, "type": "activity", "limit": 100}),
                ("class_info", {"file_hash": file_hash, "class_name": activity}),
                ("get_class_code", {"file_hash": file_hash, "class_name": activity}),
                ("list_methods", {"file_hash": file_hash, "class_name": activity}),
                ("get_method_code", {"file_hash": file_hash, "class_name": activity, "method_name": target_method}),
                ("class_xrefs", {"file_hash": file_hash, "class_name": activity, "limit": 100}),
                ("method_xrefs", {"file_hash": file_hash, "class_name": activity, "method_name": target_method, "direction": "both", "limit": 100}),
            ]
            for tool, payload in calls:
                start = time.monotonic_ns()
                try:
                    result, response = client.call(tool, payload)
                    error = mcp_error_message(result)
                    ok = error is None
                    payload = result.get("result", result)
                    # Keep a strict wire-level digest for diagnostics, while
                    # the formal semantic digest ignores only unstable xref
                    # source-location fields (line/content).  Class/method
                    # identities, counts, metadata, and all non-xref tools
                    # remain fully compared.
                    wire_response_digest = "sha256:" + hashlib.sha256(
                        json.dumps(canonical_mcp_result(payload), ensure_ascii=False,
                                   sort_keys=True, separators=(",", ":")).encode()
                    ).hexdigest()
                    response_digest = "sha256:" + hashlib.sha256(
                        json.dumps(canonical_mcp_result(
                            payload, strip_xref_locations=tool in {"class_xrefs", "method_xrefs"}),
                                   ensure_ascii=False, sort_keys=True,
                                   separators=(",", ":")).encode()
                    ).hexdigest()
                    sample = RequestSample(
                        tool=tool,
                        start_ns=response.started_ns,
                        end_ns=response.ended_ns,
                        ok=ok,
                        error=error,
                        request_id=response.request_id,
                        headers_received_ns=response.headers_received_ns,
                        body_finished_ns=response.body_finished_ns,
                        response_bytes=response.response_bytes,
                        response_digest=response_digest,
                        wire_response_digest=wire_response_digest,
                    )
                except Exception as exc:  # keep all five worker outcomes
                    ok, error = False, repr(exc)
                end = time.monotonic_ns()
                if not ok:
                    sample = RequestSample(tool, start, end, ok, error)
                all_samples[index].append(sample)
                if not ok:
                    errors.append(f"worker={index} tool={tool}: {error}")
                    # A memory refusal/exhaustion is a cell-level resource
                    # failure.  Continuing the remaining six calls would
                    # repeatedly ask the same JVM to open another engine and
                    # can turn a bounded failure into minutes of GC thrash.
                    # Break the shared barrier so all workers exit promptly;
                    # the runner can then record/retry the cell at four
                    # workers without hiding the original failure.
                    if "MEMORY_" in str(error).upper() or "MEMORY PRESSURE" in str(error).upper():
                        fatal_error.set()
                        try:
                            release.abort()
                        except threading.BrokenBarrierError:
                            pass
                if fatal_error.is_set():
                    break
                if args.mode == "burst":
                    release.wait()
                elif args.thinking_seconds > 0:
                    time.sleep(args.thinking_seconds)
        except Exception as exc:
            if not (fatal_error.is_set() and isinstance(exc, threading.BrokenBarrierError)):
                errors.append(f"worker={index}: {exc!r}")
            # Do not leave the other four workers blocked forever when one
            # session cannot initialize or a barrier itself breaks.
            for barrier in (ready, release):
                try:
                    barrier.abort()
                except threading.BrokenBarrierError:
                    pass
        finally:
            client.close()

    samples: list[dict[str, int]] = []
    stop = threading.Event()
    # Upload/hash and MCP session negotiation are setup, not Activity
    # analysis.  Complete them before T0 so APP-COLD/WARM latency contains
    # only the fixed seven-tool workload.  Keep each negotiated server-issued
    # session id on its own client; the worker threads below still execute the
    # five Activity workflows concurrently.
    initialization_started_ns = time.monotonic_ns()
    initialization_errors: list[str] = []
    for index, client in enumerate(clients):
        try:
            client.initialize()
        except Exception as exc:
            initialization_errors.append(f"worker={index}: initialize failed: {exc!r}")
    initialization_ended_ns = time.monotonic_ns()
    errors.extend(initialization_errors)
    baseline = read_memory(args.pid, args.cgroup_path) if args.pid else {}
    sampler = threading.Thread(target=memory_sampler, args=(args.pid, args.cgroup_path, stop, samples), daemon=True)
    sampler.start()
    started = time.monotonic_ns()
    threads = [threading.Thread(target=worker, args=(i,), name=f"mcp-worker-{i}") for i in range(worker_count)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    ended = time.monotonic_ns()
    terminal = read_memory(args.pid, args.cgroup_path) if args.pid else {}
    stop.set()
    sampler.join(timeout=1)

    result = {
        "file_hash": file_hash,
        "mode": args.mode,
        "activities": activities,
        "worker_count": worker_count,
        "workload_start_ns": started,
        "workload_end_ns": ended,
        "initialization_start_ns": initialization_started_ns,
        "initialization_end_ns": initialization_ended_ns,
        "initialization_ms": (initialization_ended_ns - initialization_started_ns) / 1_000_000.0,
        "makespan_ms": (ended - started) / 1_000_000.0,
        "errors": errors,
        "workers": [
            {"activity": activities[i], "method": methods[i], "session_id": clients[i].session_id, "requests": [
                sample.to_dict() for sample in all_samples[i]
            ]}
            for i in range(worker_count)
        ],
        "memory_baseline": baseline,
        "memory_terminal": terminal,
        "swap_delta_bytes": (
            terminal.get("cgroup_swap_current_bytes") - baseline.get("cgroup_swap_current_bytes")
            if "cgroup_swap_current_bytes" in terminal and "cgroup_swap_current_bytes" in baseline else None
        ),
        "memory_samples": samples,
    }
    if args.output_dir:
        run = {
            "run_id": args.run_id or f"{run_started_utc.replace(':', '').replace('-', '')}-{uuid.uuid4().hex[:8]}",
            "phase": args.phase, "variant": args.variant,
            "started_at_utc": run_started_utc, "ended_at_utc": utc_now(),
            "git_revision": git_revision(), "file_hash": file_hash,
            "apk_sha256": file_sha256(args.apk) if args.apk.is_file() else None,
            "jvm_command_line": process_command_line(args.pid),
            "server_command_line": process_command_line(args.pid),
            "host": {"hostname": platform.node(), "platform": platform.platform()},
            "benchmark_arguments": {key: str(value) for key, value in vars(args).items()},
            "pid": args.pid, "cgroup_path": str(args.cgroup_path) if args.cgroup_path else None,
            "terminal_grace_ms": args.terminal_grace_ms,
            "workload_start_ns": started, "workload_end_ns": ended,
            "makespan_ms": result["makespan_ms"],
            "expected_requests": worker_count * 7,
            "worker_count": worker_count,
            "completed_requests": sum(len(x) for x in all_samples), "error_count": len(errors),
        }
        # Preserve procfs counters as workload deltas so CPU/I/O trade-offs
        # remain visible without attaching a profiler during timing.
        for key in ("cpu_user_jiffies", "cpu_system_jiffies", "minor_faults",
                    "major_faults", "io_read_bytes", "io_write_bytes"):
            before, after = baseline.get(key), terminal.get(key)
            run[key + "_delta"] = after - before if before is not None and after is not None else None
        if args.terminal_grace_ms > 0:
            time.sleep(args.terminal_grace_ms / 1000.0)
            result["memory_terminal"] = read_memory(args.pid, args.cgroup_path) if args.pid else terminal
        write_artifacts(args.output_dir, run, result, args.correctness_golden)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
