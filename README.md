# jadx-server

一个基于 MCP (Model Context Protocol) 协议的 Android APK 反编译服务器，反编译引擎采用 [jadx](https://github.com/skylot/jadx)。

jadx-server 通过标准化的 MCP 协议向外界暴露 jadx 的反编译能力，任何兼容 MCP 的客户端（AI 助手、IDE、自动化工具）都可以通过结构化的工具调用完成 APK、JAR 等 Android/Java 二进制文件的反编译、浏览和分析 —— 无需 GUI。

## 特性

- **纯 Kotlin/JVM** — 单进程架构，jadx-core 进程内嵌入，零 IPC 开销
- **Streamable HTTP** — 基于 Ktor 提供 MCP HTTP 服务和文件上传接口
- **MCP 工具集** — 提供服务端管理工具与分析工具，覆盖 APK 全链路探索
- **实例池化** — 可配置的引擎池，支持并发反编译任务，带 acquire/release/evict 生命周期
- **会话管理** — 按客户端会话追踪，优先复用会话内实例；同一 `file_hash` 的空闲实例也可跨会话接管
- **后台任务** — 长时间反编译在虚拟线程上执行，客户端可轮询结果
- **空闲驱逐** — 按配置自动清理空闲引擎实例
- **文件索引** — 基于 MD5 的文件追踪，JSON 持久化，重启不丢
- **零拷贝访问** — 直接引用 jadx 内存中的类元数据，结构查询不落地中间文件
- **磁盘源码缓存** — 反编译后的 Java 源码保存到 `uploads/binary/<md5>/cache/sources/`，文件级搜索快且不撑爆内存
- **多格式支持** — 支持 APK、JAR、AAB、XAPK、APKS、APKM、DEX 等，通过 jadx 插件扩展

## 架构

```
┌─────────────────────────────────────────────────────┐
│                    MCP 客户端                         │
│            (AI 助手、IDE、CLI 工具)                   │
└──────────────────┬──────────────────────────────────┘
                   │ MCP 协议 (JSON-RPC)
          ┌────────┴────────┐
          │   McpHandler    │  工具注册与分发
          │   ToolRegistry  │  服务端 ↔ 分析工具路由
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
           │  JadxEngine  │  包装 JadxDecompiler
           │ DecompiledApk│  零拷贝结果访问
           └─────────────┘
```

### 模块布局

| 模块 | 关键文件 | 职责 |
|------|---------|------|
| `config` | `ServerConfig.kt` | CLI 参数、池大小、超时 |
| `mcp` | `McpHandler.kt`、`McpToolDef.kt`、`McpResult.kt` | MCP 协议接线、工具 Schema 生成、结果格式化 |
| `server` | `EnginePool.kt`、`FileIndex.kt`、`SessionManager.kt`、`TaskManager.kt`、`IdleEvictor.kt`、`ServerState.kt` | 实例生命周期、文件追踪、会话亲和、后台任务、驱逐 |
| `engine` | `DecompilerEngine.kt`、`JadxEngine.kt`、`DecompiledApk.kt`、`MockEngine.kt` | 反编译器抽象、jadx 集成、零拷贝数据访问 |
| `tools` | `ServerTools.kt`、`CoreTools.kt`、`ClassTools.kt`、`MethodTools.kt`、`SearchTools.kt`、`XrefTools.kt`、`ResourceTools.kt`、`ToolRegistry.kt` | MCP 工具实现 |
| `util` | `HashUtil.kt`、`JsonExt.kt` | MD5 哈希、JSON 辅助扩展 |

## MCP 工具参考

### 服务端工具

| 工具 | 说明 |
|------|------|
| `upload_file` | 获取上传 URL 和上传说明 |
| `save_project` | 为指定文件生成/刷新 upstream 兼容的 `project.jadx` |
| `list_files` | 列出已知二进制文件（已上传或之前打开过），支持过滤 |
| `list_instances` | 列出池中所有活跃引擎实例 |
| `server_health` | 检查服务端健康：运行时间、内存、实例数 |
| `tool_catalog` | 按关键词搜索和发现可用工具 |
| `tool_help` | 按精确名称获取某个工具的详细帮助 |
| `task_status` | 检查后台任务的状态和结果 |
| `wait_for_analysis` | 等待某个文件的反编译完成 |
| `cleanup_session_workers` | 关闭当前会话的空闲（或全部）引擎实例 |

### 分析工具（16 个）

每个分析工具都需要 `file_hash` 参数（`upload_file` 返回的短 MD5 前缀）。

| 工具 | 说明 |
|------|------|
| **核心** | |
| `decompile_apk` | 反编译 APK/JAR，返回摘要元数据（包名、类数、权限） |
| `survey` | 二进制全景概览（元数据 + 主要类 + 资源） |
| `analysis_status` | 检查文件当前反编译状态 |
| **类** | |
| `list_classes` | 列出类，支持包名过滤和分页 |
| `get_class_code` | 返回某个类的完整反编译 Java 源码 |
| `class_info` | 返回类结构：方法、字段、继承关系、内部类 |
| **方法** | |
| `get_method_code` | 返回某个方法的反编译源码 |
| `list_methods` | 列出某个类的所有方法及签名 |
| **搜索** | |
| `search_code` | 在所有反编译源码中做正则/文本搜索 |
| `search_string` | 搜索 APK/JAR 中的字符串常量 |
| `find_class` | 按名称片段或模式查找类 |
| **交叉引用** | |
| `class_xrefs` | 查找引用目标类的所有类 |
| `method_xrefs` | 查找某个方法的调用者和被调用者 |
| **资源** | |
| `get_manifest` | 返回解码后的 AndroidManifest.xml 内容 |
| `get_resource` | 按路径返回特定资源文件内容 |
| `list_resources` | 列出所有资源文件，支持类型过滤 |

## 快速开始

### 前置要求

- JDK 17+
- Gradle（已包含 wrapper）

### 开发运行

直接通过 Gradle 运行（无需构建步骤，一条命令编译并运行）：

```bash
./gradlew run --args="--xref-mode jadx"
```

所有 CLI 选项通过 `--args` 传递：

```bash
./gradlew run --args="--listen 0.0.0.0:9090 --public-base-url https://jadx.example.com --max-instances 4"
```

### 构建

**Fat JAR**（单文件，所有依赖打包）：

```bash
./gradlew shadowJar
```

输出：`build/libs/jadx-server-0.1.7-all.jar`（约 25MB）

**分发包**（启动脚本 + 独立依赖 JAR）：

```bash
./gradlew installDist
```

输出：`build/install/jadx-server/`

### 运行

**通过 fat JAR**：

```bash
java -jar build/libs/jadx-server-0.1.7-all.jar
java -jar build/libs/jadx-server-0.1.7-all.jar --xref-mode jadx
java -jar build/libs/jadx-server-0.1.7-all.jar --auth-token 'replace-with-a-strong-token'
```

**通过分发脚本**：

```bash
jadx-server
jadx-server --xref-mode jadx
```

### MCP 客户端配置

#### Claude Code（HTTP）

先在终端启动 jadx-server，再添加到配置：

如果服务监听在 `0.0.0.0` 或经由反向代理暴露，建议同时设置 `--public-base-url`，这样 `upload_file` 返回的上传地址会是客户端可访问的真实地址，而不是 `0.0.0.0`。

```bash
java -jar jadx-server-0.1.7-all.jar --listen 127.0.0.1:8080
```

如需启用 HTTP 认证，启动时传入 token。未传入或传入空值时保持无需认证模式：

```bash
java -jar jadx-server-0.1.7-all.jar \
  --listen 127.0.0.1:8080 \
  --auth-token 'replace-with-a-strong-token'
```

也可以通过 `JADX_SERVER_AUTH_TOKEN` 环境变量配置；同时存在时，`--auth-token` 优先。生产环境建议使用环境变量，避免 token 出现在进程参数中。

```json
{
  "mcpServers": {
    "jadx-server": {
      "transport": "http",
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer replace-with-a-strong-token"
      }
    }
  }
}
```

#### OpenCode（HTTP）

```bash
java -jar jadx-server-0.1.7-all.jar --listen 127.0.0.1:8080
```

```json
{
  "mcp": {
    "jadx-server": {
      "type": "remote",
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer replace-with-a-strong-token"
      }
    }
  }
}
```

#### Codex（HTTP）

Codex 当前支持为流式 HTTP MCP 服务单独绑定 bearer token 环境变量。推荐让服务端和客户端共用同一个 token 值：

```bash
export JADX_SERVER_AUTH_TOKEN='replace-with-a-strong-token'
java -jar jadx-server-0.1.7-all.jar \
  --listen 127.0.0.1:8080 \
  --auth-token "$JADX_SERVER_AUTH_TOKEN"

codex mcp add jadx-server \
  --url http://127.0.0.1:8080/mcp \
  --bearer-token-env-var JADX_SERVER_AUTH_TOKEN
```

### 上传 APK / JAR

#### HTTP 模式

使用 MCP `upload_file` 工具获取上传地址，然后 POST 文件：

```bash
curl -X POST http://127.0.0.1:8080/upload \
  -F "file=@your-app.apk"
```

或者直接用 MCP 客户端调用 `upload_file`，服务端会返回完整的上传 URL，用返回的 `file_hash` 调用分析工具。

如果服务端使用 `--listen 0.0.0.0:<port>` 部署到远程主机或反向代理后面，请务必额外配置：

```bash
java -jar jadx-server-0.1.7-all.jar \
  --listen 0.0.0.0:19090 \
  --public-base-url https://jadx.example.com
```

此时 `upload_file` 返回的 `upload_url` 将是：

```text
https://jadx.example.com/upload
```

而不是对客户端无意义的 `http://0.0.0.0:19090/upload`。

如需持久化为 upstream 风格项目，可额外调用 `save_project(file_hash=...)`，服务端会在 `uploads/binary/<md5>/project.jadx` 生成项目文件，并将缓存目录固定为同目录下的 `project.cache/`。

### MCP 连接（HTTP）

MCP 客户端连接到 `POST /mcp`，请求头需携带：

```
Accept: application/json, text/event-stream
Mcp-Session-Id: <session-id>
Authorization: Bearer <token>  # 仅在服务端配置了 token 时需要
```

## 配置参考

所有选项均可通过 CLI 参数或编程式 `ServerConfig` 配置；认证 token 还支持环境变量。

| 配置字段 | CLI 参数 | 默认值 | 说明 |
|---|---|---|---|
| `listen` | `--listen` | `127.0.0.1:8080` | HTTP 监听地址 |
| `authorizationToken` | `--auth-token` / `JADX_SERVER_AUTH_TOKEN` | 未配置 | 配置后要求 `/mcp` 携带 Bearer Token；`/upload` 不认证 |
| `maxInstances` | `-m` / `--max-instances` | `0`（自动） | 最大引擎实例数；0 = `min(CPU/4, 2)` |
| `maxPerFile` | `--max-per-file` | `4` | 单个 APK/JAR 文件最大并发实例数 |
| `idleTimeout` | `--idle-timeout` | `300s`（5 分钟） | 空闲实例驱逐超时 |
| `cleanupInterval` | `--cleanup-interval` | `10s` | 驱逐检查间隔 |
| `maxCachedApks` | `--max-cached-apks` | `10` | 最大缓存 APK/JAR 元数据条目数 |
| `uploadDir` | `--upload-dir` | `./uploads` | APK/JAR 二进制上传目录 |
| `toolTimeout` | `--tool-timeout` | `300s`（5 分钟） | MCP 工具执行超时 |
| `xrefMode` | `--xref-mode` | `JADX` | 交叉引用模式：`TEXT`（字符串匹配）或 `JADX`（字节码分析） |

### 交叉引用模式

| 模式 | 方法 | 准确度 | 内存 | 行号信息 |
|------|------|--------|------|----------|
| `text` | 在反编译源码上做字符串匹配 | 低（会命中注释、字符串字面量） | 低 | 有 |
| `jadx` | 字节码级调用图，通过 `JavaClass.getUseIn()` / `JavaMethod.getUseIn()` | 高（精确调用关系） | 中 | 无（line=0） |

可在 `class_xrefs` / `method_xrefs` 工具上通过 `mode` 参数按请求覆盖。

```kotlin
data class ServerConfig(
    val listen: String = "127.0.0.1:8080",
    val authorizationToken: String? = null,
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

## 工作原理

1. **上传** — 客户端调用 `upload_file` 获取地址，再通过 `POST /upload` 上传 APK/JAR。服务端计算 MD5 哈希并建立索引。

2. **项目保存** — 可调用 `save_project(file_hash=...)` 在 `uploads/binary/<md5>/project.jadx` 生成 upstream 兼容的项目文件；其 `cacheDir` 固定指向同目录下的 `project.cache/`。

3. **反编译** — 客户端调用 `decompile_apk` 并传入文件哈希。引擎池获取或创建 `JadxDecompiler` 实例，将所有类加载到内存。若 `uploads/binary/<md5>/project.jadx` 已存在，则后续分析优先从该项目文件及其 `project.cache/` 恢复。支持 APK、JAR、DEX、AAB 等多种格式。

4. **分析** — 客户端调用分析工具（`get_class_code`、`search_code`、`class_xrefs` 等）并传入文件哈希。源码采用按需生成：首次访问某个类时才会将该类代码写入 `project.cache/code/sources/`，而不是在打开 APK 时全量导出全部源码。代码搜索采用与 `jadx-gui` 类似的类级搜索路径：优先命中 code cache，未命中时才按需生成该类代码。`JADX` 模式的 xref 查询结果会额外缓存到 `project.cache/code/usage/`，以便后续查询复用。引擎池优先复用当前会话已有实例；若同一 `file_hash` 在其他会话上存在空闲实例，也会直接接管复用，无需重新反编译。

5. **驱逐** — 空闲实例在配置的超时后自动关闭，释放 JVM 堆内存。文件索引以及 `project.jadx` 路径在重启后持久化保留。

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin 2.3.10 |
| MCP SDK | `io.modelcontextprotocol:kotlin-sdk:0.12.0` |
| HTTP 服务端 | Ktor 3.1.3 (Netty) |
| 反编译 | jadx-core (本地 JAR) |
| 序列化 | kotlinx-serialization-json 1.8.1 |
| 协程 | kotlinx-coroutines 1.10.2 |
| 日志 | SLF4J + Logback |

## 项目结构

```
src/main/kotlin/jadx/server/
├── config/          # ServerConfig, XrefMode
├── mcp/             # MCP handler, tool definitions, result types
├── server/          # Engine pool, file index, session/task managers, eviction
├── engine/          # DecompilerEngine interface, JadxEngine, DecompiledApk, MockEngine
├── tools/           # Tool implementations (Server, Core, Class, Method, Search, Xref, Resource)
└── util/            # Hash utilities, JSON extensions
```

## 测试

```bash
./gradlew test
```

单元测试覆盖 `FileIndex`、`EnginePool` 和 `ToolRegistry`，使用 `MockEngine` 实现不依赖 jadx 的测试运行。

## 已知限制

- **jadx 警告** — 复杂 APK/JAR（尤其是 Kotlin 协程代码）反编译时可能产生 `JadxOverflowException` 或 `ExceptionHandler` 警告。这是 jadx 的正常行为，不会导致服务端崩溃。受影响的方法会在反编译输出中包含 `/* JADX WARN: ... */` 注释。
- **搜索速度权衡** — 使用 `NoOpCodeCache` 保持低内存占用，意味着 `get_class_code` 每次都会重新反编译。搜索操作（`search_code`、`class_xrefs` 等）的源码文件会缓存到磁盘，基于文件的快速 grep 无需在内存中保留全部反编译代码。
- **单 JVM** — 所有反编译实例共享一个 JVM 进程。高并发重负载时，建议在负载均衡器后运行多个实例。
- **线程安全** — `JadxDecompiler` 实例非线程安全。引擎池通过 `Busy`/`Idle` 状态转换强制执行单实例单任务语义。

## 许可证

本项目在 [jadx](https://github.com/skylot/jadx) 的 Apache 2.0 许可证下使用 jadx。
