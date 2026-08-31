#!/usr/bin/env python3
"""Prepare one APK in a running JADX HTTP server for benchmark runs.

The command performs only the upload (when needed), MCP initialization, and
the mandatory ``decompile_apk`` call.  It prints a JSON record suitable for
capturing by a runner; filesystem inspection is read-only.
"""
from __future__ import annotations

import argparse
import http.client
import json
import mimetypes
import sys
import time
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from urllib.request import Request, urlopen

PROTOCOL_VERSION = "2025-03-26"


def now_ns() -> int:
    return time.monotonic_ns()


class McpClient:
    def __init__(self, url: str, timeout: float, token: str | None = None) -> None:
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError(f"invalid MCP URL: {url}")
        self.scheme = parsed.scheme
        self.host, self.port = parsed.hostname, parsed.port
        self.path = parsed.path.rstrip("/") or "/mcp"
        self.timeout, self.token = timeout, token
        self.session_id: str | None = None
        self.request_id = 0
        self._connect()

    def _connect(self) -> None:
        cls = http.client.HTTPSConnection if self._scheme == "https" else http.client.HTTPConnection
        self.connection = cls(self.host, self.port or (443 if self._scheme == "https" else 80), timeout=self.timeout)

    @property
    def _scheme(self) -> str:
        return getattr(self, "scheme", "http")

    def close(self) -> None:
        self.connection.close()

    def initialize(self) -> None:
        event, headers = self._post({"jsonrpc": "2.0", "id": self._id(), "method": "initialize",
            "params": {"protocolVersion": PROTOCOL_VERSION, "capabilities": {},
                        "clientInfo": {"name": "jadx-warm-prepare", "version": "1"}}})
        if event.get("error"):
            raise RuntimeError(f"initialize failed: {event['error']}")
        self.session_id = headers.get("mcp-session-id")
        if not self.session_id:
            raise RuntimeError("server did not return Mcp-Session-Id")
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})

    def call(self, tool: str, arguments: dict[str, Any]) -> Any:
        event, _ = self._post({"jsonrpc": "2.0", "id": self._id(), "method": "tools/call",
                               "params": {"name": tool, "arguments": arguments}})
        if event.get("error"):
            raise RuntimeError(f"{tool} failed: {event['error']}")
        result = event.get("result", {})
        if result.get("isError"):
            raise RuntimeError(f"{tool} failed: {result.get('content', result)}")
        structured = result.get("structuredContent")
        if structured is not None:
            return structured
        for item in result.get("content", []):
            if item.get("type") == "text":
                try:
                    return json.loads(item.get("text", "{}"))
                except json.JSONDecodeError:
                    return {"raw_text": item.get("text", "")}
        return result

    def _id(self) -> int:
        self.request_id += 1
        return self.request_id

    def _post(self, payload: dict[str, Any]) -> tuple[dict[str, Any], dict[str, str]]:
        body = json.dumps(payload, separators=(",", ":")).encode()
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": PROTOCOL_VERSION}
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        self.connection.request("POST", self.path, body=body, headers=headers)
        response = self.connection.getresponse()
        out = {k.lower(): v for k, v in response.getheaders()}
        if response.status >= 400:
            raise RuntimeError(f"HTTP {response.status}: {response.read(1000).decode(errors='replace')}")
        if "text/event-stream" in out.get("content-type", ""):
            try:
                data_lines: list[str] = []
                while True:
                    line = response.readline()
                    if not line:
                        raise RuntimeError("SSE stream ended before JSON-RPC response")
                    text = line.decode(errors="replace").rstrip("\r\n")
                    if text.startswith("data:"):
                        value = text[5:].lstrip()
                        if value == "[DONE]":
                            continue
                        data_lines.append(value)
                    elif not text and data_lines:
                        event = json.loads("\n".join(data_lines)); data_lines.clear()
                        if payload.get("id") is None or event.get("id") == payload["id"]:
                            return event, out
            finally:
                self.connection.close(); self._connect()
        raw = response.read()
        return (json.loads(raw.decode(errors="replace")) if raw else {}), out


def upload(server: str, path: Path, timeout: float, token: str | None) -> dict[str, Any]:
    boundary = "----jadx-warm-" + uuid.uuid4().hex
    content = path.read_bytes()
    kind = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path.name}\"\r\n"
            f"Content-Type: {kind}\r\n\r\n").encode()
    body = head + content + f"\r\n--{boundary}--\r\n".encode()
    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(server.rstrip("/") + "/upload", data=body, method="POST", headers=headers)
    with urlopen(request, timeout=timeout) as response:
        data = json.loads(response.read().decode(errors="replace"))
    if not data.get("file_hash"):
        raise RuntimeError(f"upload response has no file_hash: {data}")
    return data


def audit(upload_dir: Path | None, file_hash: str, binary_path: Path | None) -> dict[str, Any]:
    binary = binary_path
    if binary is None and upload_dir:
        binary_root = upload_dir / "binary"
        directory = binary_root / file_hash
        if not directory.is_dir() and binary_root.is_dir():
            matches = sorted(p for p in binary_root.iterdir() if p.is_dir() and p.name.startswith(file_hash))
            directory = matches[0] if len(matches) == 1 else directory
        if directory.is_dir():
            candidates = [p for p in directory.iterdir() if p.is_file() and p.name not in ("project.jadx",)]
            binary = candidates[0] if candidates else None
    root = binary.parent if binary else None
    cache = root / "project.cache" if root else None
    project = root / "project.jadx" if root else None
    snapshot = cache / "server" / "project.snapshot.json" if cache else None
    def info(path: Path | None) -> dict[str, Any]:
        if path is None: return {"path": None, "exists": False}
        try: return {"path": str(path), "exists": path.exists(), "is_file": path.is_file(),
                    "bytes": path.stat().st_size if path.is_file() else None}
        except OSError as exc: return {"path": str(path), "exists": False, "error": str(exc)}
    cache_info = info(cache)
    if cache and cache.is_dir():
        try:
            cache_info["file_count"] = sum(1 for p in cache.rglob("*") if p.is_file())
        except OSError as exc:
            cache_info["walk_error"] = str(exc)
    snapshot_info = info(snapshot)
    if snapshot and snapshot.is_file():
        try:
            json.loads(snapshot.read_text(encoding="utf-8"))
            snapshot_info["json_valid"] = True
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            snapshot_info["json_valid"] = False
            snapshot_info["read_error"] = str(exc)
    return {"binary": info(binary), "project": info(project), "cache": cache_info, "snapshot": snapshot_info}


def main() -> int:
    parser = argparse.ArgumentParser(description="Upload and decompile one APK for a warm benchmark run")
    parser.add_argument("--server", default="http://127.0.0.1:8080", help="server base URL (or /mcp URL)")
    parser.add_argument("--apk", "--file", dest="apk", type=Path, help="APK/JAR to upload when --file-hash is absent")
    parser.add_argument("--file-hash", help="reuse an already uploaded file")
    parser.add_argument("--upload-dir", type=Path, help="server upload directory for cache/project audit")
    parser.add_argument("--binary-path", type=Path, help="uploaded binary path for cache/project audit")
    parser.add_argument("--auth-token", default=None)
    parser.add_argument("--timeout", type=float, default=1800.0)
    args = parser.parse_args()
    started = now_ns()
    try:
        if not args.file_hash and (not args.apk or not args.apk.is_file()):
            raise ValueError("--apk is required and must exist unless --file-hash is supplied")
        base = args.server.rstrip("/")
        if base.endswith("/mcp"): base = base[:-4].rstrip("/")
        timing: dict[str, float] = {}
        if args.file_hash:
            file_hash, upload_result = args.file_hash, None
        else:
            t = now_ns(); upload_result = upload(base, args.apk, args.timeout, args.auth_token)
            timing["upload_ms"] = (now_ns() - t) / 1e6; file_hash = upload_result["file_hash"]
        parsed = urlparse(args.server)
        mcp_url = args.server if parsed.path.rstrip("/").endswith("/mcp") else base + "/mcp"
        client = McpClient(mcp_url, args.timeout, args.auth_token)
        t = now_ns(); client.initialize(); timing["initialize_ms"] = (now_ns() - t) / 1e6
        t = now_ns(); result = client.call("decompile_apk", {"file_hash": file_hash})
        timing["decompile_ms"] = (now_ns() - t) / 1e6; client.close()
        t = now_ns(); fs = audit(args.upload_dir, file_hash, args.binary_path); timing["audit_ms"] = (now_ns() - t) / 1e6
        output = {"status": "ok", "file_hash": file_hash, "timings_ms": timing,
                  "analysis": result, "audit": fs, "total_ms": (now_ns() - started) / 1e6}
        print(json.dumps(output, sort_keys=True)); return 0
    except Exception as exc:
        print(json.dumps({"status": "error", "error": str(exc), "total_ms": (now_ns() - started) / 1e6}, sort_keys=True))
        return 1


if __name__ == "__main__":
    sys.exit(main())
