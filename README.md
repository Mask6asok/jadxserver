# jadx-server

An MCP (Model Context Protocol) server for Android APK decompilation, powered by [jadx](https://github.com/skylot/jadx) as the decompilation engine.

jadx-server exposes jadx's decompilation capabilities through the standardized MCP protocol, allowing any MCP-compatible client (AI assistants, IDEs, automation tools) to decompile, explore, and analyze Android APK files via structured tool calls — no GUI needed.

## Features

- **Pure Kotlin/JVM** — single-process architecture, jadx-core embedded in-process, zero IPC overhead
- **Dual Transport** — stdio and Streamable HTTP (Ktor-backed) out of the box
- **25 MCP Tools** — 9 server management tools + 16 analysis tools covering full APK exploration
- **Instance Pooling** — concurrent decompilation tasks via configurable engine pool with acquire/release/evict lifecycle
- **Session Management** — per-client session tracking with instance affinity
- **Background Tasks** — long-running decompilation executes on virtual threads, clients poll for results
- **Idle Eviction** — automatic cleanup of idle engine instances on a configurable schedule
- **File Indexing** — MD5-based file tracking with JSON persistence across restarts
- **Zero-Copy Access** — direct object reference to jadx's in-memory class metadata, no intermediate files for structure queries
- **Disk Source Cache** — decompiled Java source saved to `uploads/binary/<md5>/cache/sources/` for fast file-based search without memory bloat
- **Multi-Format Support** — APK, AAB, XAPK, APKS, APKM, DEX, and more via jadx plugins

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    MCP Client                        │
│            (AI assistant, IDE, CLI tool)              │
└──────────────────┬──────────────────────────────────┘
                   │ MCP Protocol (JSON-RPC)
          ┌────────┴────────┐
          │   McpHandler    │  Tool registration & dispatch
          │   ToolRegistry  │  Server ↔ Analysis tool routing
          └────────┬────────┘
                   │
    ┌──────────────┼──────────────┐
    │              │              │
┌───┴───┐  ┌──────┴──────┐  ┌───┴────┐
│Server │  │   Engine    │  │  File  │
│Tools  │  │    Pool     │  │ Index  │
└───────┘  └──────┬──────┘  └────────┘
                  │
           ┌──────┴──────┐
           │  JadxEngine  │  Wraps JadxDecompiler
           │ DecompiledApk│  Zero-copy result access
           └─────────────┘
```

### Module Layout

| Module | Key Files | Responsibility |
|--------|-----------|----------------|
| `config` | `ServerConfig.kt` | CLI args, transport mode, pool sizing, timeouts |
| `mcp` | `McpHandler.kt`, `McpToolDef.kt`, `McpResult.kt` | MCP protocol wiring, tool schema generation, result formatting |
| `server` | `EnginePool.kt`, `FileIndex.kt`, `SessionManager.kt`, `TaskManager.kt`, `IdleEvictor.kt`, `ServerState.kt` | Instance lifecycle, file tracking, session affinity, background tasks, eviction |
| `engine` | `DecompilerEngine.kt`, `JadxEngine.kt`, `DecompiledApk.kt`, `MockEngine.kt` | Decompiler abstraction, jadx integration, zero-copy data access |
| `tools` | `ServerTools.kt`, `CoreTools.kt`, `ClassTools.kt`, `MethodTools.kt`, `SearchTools.kt`, `XrefTools.kt`, `ResourceTools.kt`, `ToolRegistry.kt` | 25 MCP tool implementations |
| `util` | `HashUtil.kt`, `JsonExt.kt` | MD5 hashing, JSON helper extensions |

## MCP Tools Reference

### Server Tools (9)

| Tool | Description |
|------|-------------|
| `upload_file` | Get upload URL and instructions for uploading a binary file |
| `list_files` | List known binaries (uploaded or previously opened), with filters |
| `list_instances` | List all active engine instances in the pool |
| `server_health` | Check server health: uptime, memory, instance counts |
| `tool_catalog` | Search and discover available tools by keyword |
| `tool_help` | Get detailed help for a specific tool by exact name |
| `task_status` | Check status/result of a background task |
| `wait_for_analysis` | Wait for a file's background decompilation to complete |
| `cleanup_session_workers` | Close idle (or all) engine instances for the current session |

### Analysis Tools (16)

Each analysis tool requires a `file_hash` parameter (short MD5 prefix returned by `upload_file`).

| Tool | Description |
|------|-------------|
| **Core** | |
| `decompile_apk` | Decompile APK, return summary metadata (package, classes, permissions) |
| `survey` | Comprehensive binary overview (metadata + top classes + resources) |
| `analysis_status` | Check current decompilation status for a file |
| **Class** | |
| `list_classes` | List classes with optional package filter and pagination |
| `get_class_code` | Return full decompiled Java source for a class |
| `class_info` | Return class structure: methods, fields, inheritance, inner classes |
| **Method** | |
| `get_method_code` | Return decompiled source for a specific method |
| `list_methods` | List all methods of a class with signatures |
| **Search** | |
| `search_code` | Regex/text search across all decompiled source code |
| `search_string` | Search string constants in the APK |
| `find_class` | Find classes by name fragment or pattern |
| **Cross-Reference** | |
| `class_xrefs` | Find all classes that reference the target class |
| `method_xrefs` | Find callers and callees of a method |
| **Resource** | |
| `get_manifest` | Return decoded AndroidManifest.xml content |
| `get_resource` | Return specific resource file content by path |
| `list_resources` | List all resource files with type filters |

## Quick Start

### Prerequisites

- JDK 17+
- Gradle (wrapper included)

### Development Run

Run directly via Gradle (no build step needed — compiles and runs in one command):

```bash
./gradlew run --args="--xref-mode jadx"
```

Pass all CLI options through `--args`:

```bash
./gradlew run --args="--listen 0.0.0.0:9090 --max-instances 4"
```

### Build

**Fat JAR** (single file, all dependencies bundled):

```bash
./gradlew shadowJar
```

Output: `build/libs/jadx-server-0.1.0-all.jar` (~25MB)

**Distribution** (start scripts + separate dependency JARs):

```bash
./gradlew installDist
```

Output: `build/install/jadx-server/`

### Run

**Via fat JAR**:

```bash
java -jar build/libs/jadx-server-0.1.0-all.jar
java -jar build/libs/jadx-server-0.1.0-all.jar --xref-mode jadx
java -jar build/libs/jadx-server-0.1.0-all.jar --stdio
```

**Via distribution script**:

```bash
build/install/jadx-server/bin/jadx-server
build/install/jadx-server/bin/jadx-server --xref-mode jadx
build/install/jadx-server/bin/jadx-server --stdio
```

### MCP Client Configuration

#### Claude Code (stdio)

Add to `~/.claude.json`:

```json
{
  "mcpServers": {
    "jadx-server": {
      "command": "java",
      "args": ["-jar", "/path/to/jadx-server-0.1.0-all.jar", "--stdio"]
    }
  }
}
```

#### Claude Code (HTTP)

Start jadx-server in a terminal first, then add to config:

```bash
java -jar jadx-server-0.1.0-all.jar --listen 127.0.0.1:8080
```

```json
{
  "mcpServers": {
    "jadx-server": {
      "transport": "http"
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

#### OpenCode (stdio)

Edit `~/.config/opencode/opencode.json`:

```json
{
  "mcpServers": {
    "jadx-server": {
      "type": "local",
      "command": ["java", "-jar", "/path/to/jadx-server-0.1.0-all.jar", "--stdio"]
    }
  }
}
```

#### OpenCode (HTTP)

```bash
java -jar jadx-server-0.1.0-all.jar --listen 127.0.0.1:8080
```

```json
{
  "mcp": {
    "jadx-server": {
      "type": "remote",
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

### CLI Options

| Flag | Default | Description |
|------|---------|-------------|
| `--stdio` | — | Use stdio transport (overrides `--transport`) |
| `--transport <http\|stdio>` | `http` | Transport mode |
| `--listen <addr:port>` | `127.0.0.1:8080` | HTTP listen address |
| `-m, --max-instances <n>` | `0` (auto=CPU/4, max 2) | Maximum concurrent decompiler instances |
| `--max-per-file <n>` | `4` | Maximum concurrent instances per file |
| `--idle-timeout <s>` | `300` (5 min) | Idle engine eviction timeout (seconds) |
| `--cleanup-interval <s>` | `10` | Eviction check interval (seconds) |
| `--max-cached-apks <n>` | `10` | Maximum cached APK entries |
| `--upload-dir <path>` | `./uploads` | Directory for uploaded binaries |
| `--tool-timeout <s>` | `300` (5 min) | Tool execution timeout (seconds) |
| `--xref-mode <text\|jadx>` | `jadx` | Cross-reference mode: `text` for string matching (fast, less accurate), `jadx` for bytecode API (precise) |
| `--help`, `-h` | — | Show help text |

### Upload an APK

Use the MCP `upload_file` tool to get instructions, then POST the file:

```bash
curl -X POST http://127.0.0.1:8080/upload \
  -F "file=@your-app.apk"
```

Or use any MCP client to call `upload_file`, then use the returned `file_hash` in analysis tools.

### MCP Connection (HTTP)

Connect an MCP client to `POST /mcp` with:

```
Accept: application/json, text/event-stream
Mcp-Session-Id: <session-id>
```

## Configuration Reference

All options are configurable via CLI flags, programmatic `ServerConfig`, or environment variables (future).

| Config Field | CLI Flag | Default | Description |
|---|---|---|---|
| `transport` | `--transport` / `--stdio` | `HTTP` | Transport mode: `HTTP` or `STDIO` |
| `listen` | `--listen` | `127.0.0.1:8080` | HTTP listen address |
| `maxInstances` | `-m` / `--max-instances` | `0` (auto) | Max engine instances; 0 = `min(CPU/4, 2)` |
| `maxPerFile` | `--max-per-file` | `4` | Max concurrent instances per APK file |
| `idleTimeout` | `--idle-timeout` | `300s` (5 min) | Idle instance eviction timeout |
| `cleanupInterval` | `--cleanup-interval` | `10s` | Eviction check interval |
| `maxCachedApks` | `--max-cached-apks` | `10` | Max cached APK metadata entries |
| `uploadDir` | `--upload-dir` | `./uploads` | Upload directory for APK binaries |
| `toolTimeout` | `--tool-timeout` | `300s` (5 min) | MCP tool execution timeout |
| `xrefMode` | `--xref-mode` | `JADX` | Xref mode: `TEXT` (string match) or `JADX` (bytecode analysis) |

### Xref Modes

| Mode | Method | Accuracy | Memory | Line Info |
|------|--------|----------|--------|-----------|
| `text` | String matching on decompiled source | Low (catches comments, string literals) | Low | Yes |
| `jadx` | Bytecode-level call graph via `JavaClass.getUseIn()` / `JavaMethod.getUseIn()` | High (exact call relationships) | Medium | No (line=0) |

Can be overridden per-request on `class_xrefs` / `method_xrefs` tools via `mode` parameter.

```kotlin
data class ServerConfig(
    val transport: TransportMode = TransportMode.HTTP,
    val listen: String = "127.0.0.1:8080",
    val maxInstances: Int = 0,
    val maxPerFile: Int = 4,
    val idleTimeout: Duration = Duration.ofMinutes(5),
    val cleanupInterval: Duration = Duration.ofSeconds(10),
    val maxCachedApks: Int = 10,
    val uploadDir: Path = Path.of("./uploads"),
    val toolTimeout: Duration = Duration.ofMinutes(5),
    val xrefMode: XrefMode = XrefMode.JADX,
)
```

## How It Works

1. **Upload** — Client uploads an APK via `POST /upload` (HTTP) or `upload_file` tool. The server computes an MD5 hash and indexes the file.

2. **Decompile** — Client calls `decompile_apk` with the file hash. The engine pool acquires or spawns a `JadxDecompiler` instance and loads all classes into memory. Long decompilations run as background tasks.

3. **Analyze** — Client calls analysis tools (`get_class_code`, `search_code`, `class_xrefs`, etc.) with the file hash. The pool reuses the existing instance — no re-decompilation needed.

4. **Evict** — Idle instances are automatically closed after the configured timeout, freeing JVM heap. The file index persists across restarts.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin 2.3.10 |
| MCP SDK | `io.modelcontextprotocol:kotlin-sdk:0.12.0` |
| HTTP Server | Ktor 3.1.3 (Netty) |
| Decompilation | jadx-core (local JAR) |
| Serialization | kotlinx-serialization-json 1.8.1 |
| Coroutines | kotlinx-coroutines 1.10.2 |
| Logging | SLF4J + Logback |

## Project Structure

```
src/main/kotlin/jadx/server/
├── config/          # ServerConfig, TransportMode
├── mcp/             # MCP handler, tool definitions, result types
├── server/          # Engine pool, file index, session/task managers, eviction
├── engine/          # DecompilerEngine interface, JadxEngine, DecompiledApk, MockEngine
├── tools/           # 25 tool implementations (Server, Core, Class, Method, Search, Xref, Resource)
└── util/            # Hash utilities, JSON extensions
```

## Testing

```bash
./gradlew test
```

Unit tests cover `FileIndex`, `EnginePool`, and `ToolRegistry` with `MockEngine` for jadx-free test runs.

## Known Limitations

- **jadx warnings** — Complex APKs (especially Kotlin coroutine code) may produce `JadxOverflowException` or `ExceptionHandler` warnings during decompilation. These are normal jadx behavior and do not crash the server. Affected methods will contain `/* JADX WARN: ... */` comments in their decompiled output.
- **Search speed trade-off** — Using `NoOpCodeCache` keeps memory low but means `get_class_code` re-decompiles each time. For search operations (`search_code`, `class_xrefs`, etc.), source files are cached on disk for fast file-based grep without holding all decompiled code in memory.
- **Single-JVM** — All decompiler instances share one JVM process. For heavy concurrent workloads, run multiple instances behind a load balancer.
- **Thread safety** — `JadxDecompiler` instances are not thread-safe. The engine pool enforces one-instance-per-task semantics via `Busy`/`Idle` state transitions.

## License

This project uses [jadx](https://github.com/skylot/jadx) under its Apache 2.0 license.