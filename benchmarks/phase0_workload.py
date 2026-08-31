#!/usr/bin/env python3
"""Phase 0 APK/MCP discovery and workload manifest generator.

This is intentionally a pre-check, separate from timed benchmark execution.
It uploads once and writes all discovery responses to a JSON manifest.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import sys
import uuid
import urllib.request
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

PROTOCOL_VERSION = "2025-03-26"
LIFECYCLE = {"onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onRestart", "onNewIntent"}
CANONICALIZER_VERSION = "canonical-semantic-v2"


def canonical_semantic(value: Any, *, strip_xref_locations: bool = False) -> Any:
    """Normalize response JSON for Phase0's semantic expectation records.

    Lists in xref/metadata responses are sets for the purposes of this
    workload.  Sorting recursively removes transport/collection order.  For
    xref payloads only, generated source ``line``/``content`` annotations are
    omitted from the semantic digest because JADX may move those locations
    when lazy code generation order changes; the raw responses remain stored.
    """
    if isinstance(value, dict):
        return {
            str(key): canonical_semantic(item, strip_xref_locations=strip_xref_locations)
            for key, item in value.items()
            if not (strip_xref_locations and str(key) in {"line", "content"})
        }
    if isinstance(value, list):
        normalized = [canonical_semantic(item, strip_xref_locations=strip_xref_locations) for item in value]
        return sorted(normalized, key=lambda item: json.dumps(
            item, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    return value


def response_digest(value: Any, *, strip_xref_locations: bool = False) -> str:
    canonical = json.dumps(canonical_semantic(value, strip_xref_locations=strip_xref_locations), ensure_ascii=False,
                           sort_keys=True, separators=(",", ":"))
    return "sha256:" + hashlib.sha256(canonical.encode()).hexdigest()


class McpClient:
    def __init__(self, url: str, timeout: float):
        p = urlparse(url)
        if p.scheme not in ("http", "https") or not p.hostname:
            raise ValueError(f"invalid server URL: {url}")
        if p.scheme != "http":
            raise ValueError("only HTTP MCP endpoints are supported")
        self.host, self.port = p.hostname, p.port or 80
        self.path = p.path.rstrip("/") or "/mcp"
        self.timeout, self.session_id, self.request_id = timeout, None, 0
        self.connection = http.client.HTTPConnection(self.host, self.port, timeout=timeout)

    def close(self):
        self.connection.close()

    def initialize(self):
        result, headers = self._post({"jsonrpc": "2.0", "id": self._id(), "method": "initialize",
            "params": {"protocolVersion": PROTOCOL_VERSION, "capabilities": {},
                        "clientInfo": {"name": "phase0-workload", "version": "1"}}})
        if result.get("error"):
            raise RuntimeError(f"initialize failed: {result['error']}")
        self.session_id = headers.get("mcp-session-id")
        if not self.session_id:
            raise RuntimeError("server did not return Mcp-Session-Id")
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})

    def call(self, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        event, _ = self._post({"jsonrpc": "2.0", "id": self._id(), "method": "tools/call",
                               "params": {"name": tool, "arguments": arguments}})
        if event.get("error"):
            raise RuntimeError(f"{tool}: {event['error']}")
        result = event.get("result", {})
        if result.get("isError"):
            content = result.get("content", [])
            raise RuntimeError(f"{tool}: " + " ".join(str(x.get("text", x)) for x in content))
        for item in result.get("content", []):
            if item.get("type") == "text":
                try:
                    return json.loads(item.get("text", "{}"))
                except json.JSONDecodeError:
                    return {"raw_text": item.get("text", "")}
        return result.get("structuredContent") or {}

    def _id(self):
        self.request_id += 1
        return self.request_id

    def _post(self, payload):
        body = json.dumps(payload, separators=(",", ":")).encode()
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": PROTOCOL_VERSION}
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        self.connection.request("POST", self.path, body=body, headers=headers)
        response = self.connection.getresponse()
        out_headers = {k.lower(): v for k, v in response.getheaders()}
        if response.status >= 400:
            raise RuntimeError(f"HTTP {response.status}: {response.read(500).decode(errors='replace')}")
        if "text/event-stream" in out_headers.get("content-type", ""):
            try:
                while True:
                    line = response.readline()
                    if not line:
                        return {}, out_headers
                    text = line.decode(errors="replace").strip()
                    if text.startswith("data:") and text[5:].strip() not in ("", "[DONE]"):
                        event = json.loads(text[5:].strip())
                        if payload.get("id") is None or event.get("id") == payload["id"]:
                            return event, out_headers
            finally:
                self.connection.close()
                self.connection = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        raw = response.read()
        return (json.loads(raw.decode()) if raw else {}), out_headers


def upload(server: str, apk: Path, timeout: float) -> str:
    boundary = "----phase0-" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{apk.name}\"\r\n"
            "Content-Type: application/vnd.android.package-archive\r\n\r\n").encode() + apk.read_bytes()
    body += f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(server.rstrip("/") + "/upload", data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        data = json.loads(response.read().decode())
    return data["file_hash"]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_digest(value: Any) -> dict[str, Any]:
    """Keep semantic-size information while avoiding huge source in manifest."""
    source = value.get("source") if isinstance(value, dict) else None
    if not isinstance(source, str):
        return {"available": False}
    return {"available": bool(source), "sha256": hashlib.sha256(source.encode()).hexdigest(),
            "bytes": len(source.encode()), "lines": source.count("\n") + (1 if source else 0),
            "format": value.get("format", "java"), "fallback": bool(value.get("fallback", False)),
            "failed_marker": any(marker in source for marker in ("JADX ERROR:", "Method not decompiled:", "Code decompiled incorrectly"))}


def method_is_bounded(client: McpClient, file_hash: str, class_name: str, method_name: str) -> bool:
    """A method is workload-safe when neither xref direction reaches 100."""
    result = client.call("method_xrefs", {"file_hash": file_hash, "class_name": class_name,
                                           "method_name": method_name, "direction": "both", "limit": 1000})
    xrefs = result.get("xrefs", result)
    return len(xrefs.get("callers", [])) <= 100 and len(xrefs.get("callees", [])) <= 100


def class_is_bounded(client: McpClient, file_hash: str, class_name: str) -> bool:
    result = client.call("class_xrefs", {"file_hash": file_hash, "class_name": class_name, "limit": 1000})
    xrefs = result.get("xrefs", result)
    values = xrefs if isinstance(xrefs, list) else xrefs.get("xrefs", [])
    return isinstance(values, list) and len(values) <= 100


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a Phase 0 APK/MCP workload manifest")
    parser.add_argument("--server", default="http://127.0.0.1:8080")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--file-hash", help="reuse an already uploaded APK hash")
    parser.add_argument("--output", type=Path, help="manifest path (default: stdout)")
    parser.add_argument("--candidate-only", action="store_true", help="only discover and emit Activity candidates")
    parser.add_argument("--activities", help="override final five distinct Activity class names, comma-separated")
    parser.add_argument("--method", default="onCreate", help="method used for method xrefs")
    parser.add_argument("--timeout", type=float, default=1800.0)
    args = parser.parse_args()
    method_explicit = "--method" in sys.argv
    errors: list[str] = []
    if not args.apk.is_file() and not args.file_hash:
        errors.append(f"APK does not exist: {args.apk}")
    if errors:
        return emit({"status": "failed", "errors": errors}, args.output)
    try:
        file_hash = args.file_hash or upload(args.server, args.apk, args.timeout)
        client = McpClient(args.server.rstrip("/") + "/mcp", args.timeout)
        client.initialize()
        manifest_summary = client.call("get_manifest_summary", {"file_hash": file_hash})
        entrypoints = client.call("get_manifest_entrypoints", {"file_hash": file_hash})
        all_components = client.call("list_manifest_components", {"file_hash": file_hash, "type": "all"})
        candidates = [x for x in all_components.get("components", []) if x.get("type") == "activity"]
        names = [x.get("name") for x in candidates if x.get("name")]
        launcher_names = {x.get("name") for x in entrypoints.get("launcher_activities", [])}
        exported_names = {x.get("name") for x in entrypoints.get("exported_activities", [])}
        ordered = sorted(candidates, key=lambda x: (x.get("name") not in exported_names,
                                                     x.get("name") not in launcher_names, x.get("name", "")))
        parseability: dict[str, bool] = {}
        methods_by_target: dict[str, str] = {}
        if args.activities:
            selected = [x.strip() for x in args.activities.split(",") if x.strip()]
        elif args.candidate_only:
            selected = [x.get("name") for x in ordered if x.get("name")][:5]
        else:
            selected = []
            for item in ordered:
                name = item.get("name")
                if not name:
                    continue
                try:
                    client.call("class_info", {"file_hash": file_hash, "class_name": name})
                    listed = client.call("list_methods", {"file_hash": file_hash, "class_name": name})
                    available = {m.get("name") for m in listed.get("methods", [])}
                    choices = [args.method] if method_explicit or args.method != "onCreate" else [x for x in LIFECYCLE if x in available]
                    # Preserve the documented lifecycle order rather than set iteration order.
                    choices = [x for x in ("onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onRestart", "onNewIntent") if x in choices]
                    chosen = next((x for x in choices if method_is_bounded(client, file_hash, name, x)), None)
                    if chosen is None or not class_is_bounded(client, file_hash, name):
                        parseability[name] = False
                        continue
                    methods_by_target[name] = chosen
                    parseability[name] = True
                    selected.append(name)
                except Exception:
                    parseability[name] = False
                if len(selected) == 5:
                    break
        if len(selected) != 5 or len(set(selected)) != 5:
            errors.append(f"need five distinct Activity classes; discovered {len(names)}, selected {len(selected)}")
        if method_explicit and not args.candidate_only:
            # Explicit --method is fixed: do not silently substitute another lifecycle method.
            methods_by_target = {name: args.method for name in selected}
        manifest: dict[str, Any] = {"status": "failed" if errors else "candidates_ready", "apk": {
            "path": str(args.apk), "size": args.apk.stat().st_size, "sha256": sha256(args.apk) if args.apk.is_file() else None,
            "file_hash": file_hash}, "manifest": {"summary": manifest_summary, "entrypoints": entrypoints,
            "components": all_components, "selection": {"excluded_types": ["activity_alias"],
            "priority": ["exported", "launcher", "manifest order"], "exported": sorted(exported_names),
            "launcher": sorted(launcher_names)}}, "activity_candidates": candidates,
            "selected_activities": selected, "selection_parseability": parseability,
            "methods": methods_by_target, "errors": errors}
        if not args.candidate_only and not errors:
            targets = []
            for name in selected:
                info = client.call("class_info", {"file_hash": file_hash, "class_name": name})
                methods = client.call("list_methods", {"file_hash": file_hash, "class_name": name})
                lifecycle = [m.get("name") for m in methods.get("methods", []) if m.get("name") in LIFECYCLE]
                target_method = methods_by_target.get(name, args.method)
                if target_method not in [m.get("name") for m in methods.get("methods", [])]:
                    errors.append(f"{name}: method not found: {target_method}")
                    continue
                class_code = client.call("get_class_code", {"file_hash": file_hash, "class_name": name})
                method_code = client.call("get_method_code", {"file_hash": file_hash, "class_name": name, "method_name": target_method})
                xrefs: dict[str, Any] = {}
                for tool, payload in (("class_xrefs", {"file_hash": file_hash, "class_name": name}),
                                      ("method_xrefs", {"file_hash": file_hash, "class_name": name, "method_name": target_method, "direction": "both"})):
                    full = client.call(tool, {**payload, "limit": 1000})
                    formal = client.call(tool, {**payload, "limit": 100})
                    value = full.get("xrefs", full)
                    formal_value = formal.get("xrefs", formal)
                    counts = [len(value)] if isinstance(value, list) else [len(v) for v in value.values() if isinstance(v, list)]
                    formal_counts = [len(formal_value)] if isinstance(formal_value, list) else [len(v) for v in formal_value.values() if isinstance(v, list)]
                    summary = {"counts": counts, "complete_at_1000": all(c < 1000 for c in counts),
                               "formal_limit_100_truncated": any(c > 100 for c in counts)}
                    if tool == "method_xrefs":
                        summary["callers"] = {"count": len(formal_value.get("callers", [])), "full_count": len(value.get("callers", [])),
                                               "truncated": len(value.get("callers", [])) > 100}
                        summary["callees"] = {"count": len(formal_value.get("callees", [])), "full_count": len(value.get("callees", [])),
                                               "truncated": len(value.get("callees", [])) > 100}
                    else:
                        summary["count"] = len(formal_value) if isinstance(formal_value, list) else formal_value.get("ref_count", 0)
                        summary["full_count"] = len(value) if isinstance(value, list) else len(value.get("xrefs", []))
                    if not summary["complete_at_1000"] or summary["formal_limit_100_truncated"]:
                        errors.append(f"{name}: {tool} exceeds formal limit or is incomplete")
                    xrefs[tool] = {"limit_1000": full, "limit_100": formal, "summary": summary}
                # Keep the raw discovery payloads needed for auditability, and
                # add compact semantic digests for the exact seven-tool
                # workload.  The digests intentionally exclude request ids,
                # timestamps and transport framing, matching the benchmark
                # client's canonicalizer.
                expected = {
                    "class_info": {"response_digest": response_digest(info), "ok": True},
                    "list_methods": {"response_digest": response_digest(methods), "ok": True},
                    "get_class_code": {"response_digest": response_digest(class_code), "ok": True},
                    "get_method_code": {"response_digest": response_digest(method_code), "ok": True},
                    "class_xrefs": {"response_digest": response_digest(xrefs["class_xrefs"]["limit_100"], strip_xref_locations=True), "ok": True},
                    "method_xrefs": {"response_digest": response_digest(xrefs["method_xrefs"]["limit_100"], strip_xref_locations=True), "ok": True},
                }
                targets.append({"class_name": name, "class_info": info, "methods": methods,
                                "lifecycle_methods": lifecycle, "method": target_method,
                                "source": {"class": source_digest(class_code), "method": source_digest(method_code)},
                                "xrefs": xrefs, "expected": expected})
            manifest["targets"] = targets
            manifest["status"] = "complete" if not errors else "failed"
            manifest["canonicalizer"] = {
                "version": CANONICALIZER_VERSION,
                "list_policy": "recursive semantic sort; xref line/content omitted from semantic digest; raw payload retained",
                "xref_semantic_policy": "identity-fields-only; raw xref line/content retained",
                "expected_digest_scope": "target tool payloads without transport framing",
            }
        manifest["errors"] = errors
    except Exception as exc:
        manifest = {"status": "failed", "errors": [repr(exc)]}
    finally:
        try: client.close()
        except (NameError, UnboundLocalError): pass
    return emit(manifest, args.output)


def emit(manifest: dict[str, Any], output: Path | None) -> int:
    text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    if output:
        output.write_text(text)
    else:
        sys.stdout.write(text)
    return 0 if manifest.get("status") in ("complete", "candidates_ready") else 2


if __name__ == "__main__":
    raise SystemExit(main())
