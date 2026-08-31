# Benchmark metrics schema

This is the measurement contract for the same-APK benchmark. It separates
three evidence levels:

* **Phase0** is a single controlled validation run (usually one session or a
  very small deterministic fixture). It checks instrumentation and semantic
  equivalence, not capacity or an A/B conclusion.
* **smoke** is the existing five-session harness used to catch wiring,
  correctness, and obvious queue/memory failures. It is a rehearsal and does
  not establish a formal result.
* **formal** is the repeated CURRENT-vs-SHARED experiment. It requires the
  same APK, activities, tool sequence, JVM flags, host/container limits,
  warm-up policy, and run order, with enough repeated runs to report a
  distribution (not one lucky run).

Neither a smoke run nor a single run may be presented as a formal A/B result.
Keep raw artifacts immutable and derive summaries in a separate file.

## Raw artifact set

Each phase should identify a run with `run_id`, `phase` (`phase0`, `smoke`, or
`formal`), `variant` (`current` or `shared`), UTC start/end timestamps, git
revision, APK SHA-256 (`file_hash` is the server's upload identity), JVM
command line, server command line, host/container identity, and benchmark
arguments.

| Artifact | Format / minimum contents | Phase0 | smoke | formal | Current implementation |
| --- | --- | --- | --- | --- | --- |
| `run.json` | Run metadata and environment | required | required | required | emitted by the harness, including git/APK/JVM/cgroup/argument provenance |
| `requests.jsonl` | One record per request (schema below) | required | required | required | emitted with TTFB, transfer, response bytes/digest and request/session/file identity |
| `memory.jsonl` | Timestamped process/cgroup samples | recommended | required when memory is a claim | required | emitted at ~250 ms with RSS/VmHWM/PSS, CPU/I/O/faults, heap fields when available, cgroup and swap |
| `server.log` | Complete stdout/stderr, unmodified | required | required | required | captured by `matrix_runner`; queue and analysis lines remain available for derived metrics |
| `correctness.json` | Canonical response digests and comparison result | required | required | required | emitted for every workload; MCP `isError` and digest mismatches are retained |
| `terminal.json` | Post-run quiescence and post-GC live-set snapshot | recommended | required for memory claims | required | emitted by `terminal_probe` with fixed grace, optional `jcmd` GC evidence, `/proc` and cgroup state |

The formal package must retain one directory per run and a manifest listing
the hashes of all raw files. Do not overwrite raw output when producing
aggregates.

`matrix_runner.py` persists the logical round in every cell `run_id` and
matrix result. For split formal batches, use distinct `--round-start` and
`--round-count` ranges (and, when desired, distinct `--output-dir` roots); the
runner also scopes correctness goldens by APK, temperature, mode, and round
(`...-rN.json`) so a later batch cannot reuse or overwrite another round's
baseline. The legacy `--rounds N` spelling remains equivalent to
`--round-start 0 --round-count N`.

The derived `formal-report.json` currently uses schema `formal-report/v2`.
Each cell exposes `correctness.gate` (`verified`, `unverified`, or `failed`),
and comparison profiles include the total/p95 `wire_location_drift` count.
An r0 golden seed is intentionally `unverified`; only explicit golden and
metadata-verified cells are eligible for an A/B summary.

## Record schemas and provenance

The canonical unit for durations is milliseconds (floating point); memory is
bytes (integer); monotonic timestamps are nanoseconds. Wall-clock timestamps
are for correlation only.

### Request record (`requests.jsonl`)

```json
{
  "run_id": "...", "worker": 0, "session_id": "...",
  "request_id": 1, "tool": "get_class_code", "file_hash": "...",
  "started_ns": 0, "headers_received_ns": 0, "body_finished_ns": 0,
  "ended_ns": 0, "ok": true, "error": null,
  "queue_wait_ms": null, "response_bytes": 0,
  "response_digest": "sha256:...",
  "wire_response_digest": "sha256:..."
}
```

The harness records all fields at the HTTP client boundary. If the transport
cannot distinguish headers from body, record TTFB as unavailable; do not infer
it from total wall time.

`queue_wait_ms` is populated when the server emits a correlated field. The
current report also retains aggregate `[QUEUE]` enqueue/dequeue observations;
because those lines do not carry request ids, aggregate wait statistics are
marked descriptive rather than per-request attribution.

`response_digest` is the formal semantic digest of a canonical, deterministic
representation of the response payload (excluding request ids, timestamps,
session ids, and transport framing). Text-encoded JSON tool payloads are parsed
before canonicalization. For `class_xrefs`/`method_xrefs`, the stable semantic
key retains class/method identities and counts but omits generated source
`line`/`content` annotations; JADX can move those locations when lazy code
generation order changes. `wire_response_digest` applies the same recursive
ordering while retaining every field, and is reported as location-drift
diagnostic evidence rather than a correctness failure. The APK identity itself
is available as SHA-256 from `ApkRuntimeRegistry`/upload response, but that is
not a correctness digest. Phase0 establishes the canonicalizer and expected
digest; smoke and formal compare every specified tool response and record both
semantic mismatches and wire-location drift.

### Memory record (`memory.jsonl`)

```json
{
  "run_id": "...", "sample_ns": 0,
  "pid": 1234,
  "rss_bytes": 0, "vmhwm_bytes": 0, "vmsize_bytes": 0,
  "pss_bytes": null, "heap_used_bytes": null,
  "heap_committed_bytes": null, "heap_max_bytes": null,
  "cgroup_memory_current_bytes": null, "cgroup_memory_peak_bytes": null,
  "cgroup_swap_current_bytes": null
}
```

* **RSS**: read `VmRSS` from `/proc/<pid>/status`; the existing sampler does
  this as `vmrss` (and also records `vmhwm` and `vmsize`).
* **VmHWM**: read `VmHWM` from the same status file. It is a high-water mark,
  not a point-in-time sample; report its maximum/final value with that caveat.
* **PSS**: read `Pss:` from `/proc/<pid>/smaps_rollup`, converting kB to bytes;
  the field may be unavailable due to permissions.
* **JVM heap**: `terminal_probe` uses `jcmd <pid> GC.heap_info` when available;
  `server_health` remains a weaker point-in-time fallback.
* **cgroup memory.peak**: read `<cgroup>/memory.peak`; this is available when
  `--cgroup-path` is supplied.
* **swap delta**: retain baseline/terminal `memory.swap.current` and derive
  `swap_delta_bytes`. On cgroup v1 use the applicable memory and memsw files
  and document the mapping.

Do not call process RSS, JVM heap, and cgroup memory interchangeable: they have
different scopes. For a formal memory claim report all available scopes and
the sampling interval, permissions, cgroup path, and whether the process has
children whose memory is included.

### Run and terminal records

`makespan_ms` is the monotonic interval from harness start until all workers
finish. The existing harness emits it. For formal use also record
`workload_start_ns`, `workload_end_ns`, number of completed requests, errors,
and barrier/initialization time so setup is not silently compared between
variants.

`terminal.json` must be captured only after all clients finish, the server is
quiescent, and a declared grace period has elapsed. It should contain:

```json
{
  "quiescence_grace_ms": 5000,
  "gc_requested": true,
  "gc_observation_method": "jcmd GC.heap_info + /proc + cgroup",
  "heap_used_bytes": 0,
  "rss_bytes": 0, "pss_bytes": null,
  "cgroup_memory_current_bytes": null,
  "live_set_definition": "heap used after quiescence and explicit GC request",
  "observed_at_ns": 0
}
```

This is a **post-GC live-set estimate**, not proof that the collector has
finished or that all native memory is reclaimable. Formal runs use the same GC
policy and grace period for both variants.

## Required derived checks

Derive, without changing raw artifacts: per-tool latency distributions,
TTFB and transfer duration (`body_finished - headers_received`), queue wait
distribution and timeout count, makespan, request error/correctness counts,
RSS/VmHWM/PSS peaks, heap used/committed/max, cgroup peak, and swap delta.
Include sample counts and `null`/unavailable reasons. A missing metric is not
zero.

Phase0 is accepted only when the schema parses, clocks are monotonic, the
canonical correctness digest is stable, and memory probes report their
availability. Smoke is accepted only as a go/no-go rehearsal. Formal A/B
claims require repeated runs per variant and a report of distributions,
environment parity, correctness equality, and any excluded/failed runs.
