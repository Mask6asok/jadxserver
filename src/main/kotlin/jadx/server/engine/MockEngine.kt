package jadx.server.engine

import jadx.server.mcp.McpToolDef
import java.nio.file.Path

class MockEngine : DecompilerEngine {
    override val name: String = "mock"

    override fun toolSchemas(): List<McpToolDef> = listOf(
        McpToolDef("list_classes", "List classes (mock)")
            .param("filter", "string", "Filter")
            .param("offset", "number", "Offset")
            .param("count", "number", "Count"),
        McpToolDef("get_class_code", "Get class code (mock)")
            .param("class_name", "string", "Class name", required = true),
        McpToolDef("get_class_info", "Get class info (mock)")
            .param("class_name", "string", "Class name", required = true),
        McpToolDef("search_code", "Search code (mock)")
            .param("query", "string", "Query", required = true)
            .param("limit", "number", "Limit"),
        McpToolDef("health", "Health check (mock)")
    )

    override fun open(file: Path, options: EngineOptions): EngineInstance {
        val mockApk = MockDecompiledApk(file.fileName.toString())
        return EngineInstance(
            engineName = name,
            fileHash = "mock_${file.fileName}",
            state = mockApk
        )
    }

    override fun close(instance: EngineInstance) {}

    override fun health(instance: EngineInstance): InstanceHealth = InstanceHealth.HEALTHY
}

class MockDecompiledApk(val fileName: String) {
    val stubClasses = mapOf(
        "com.example.MainActivity" to """
            package com.example;
            
            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.activity_main);
                }
            }
        """.trimIndent(),
        "com.example.Utils" to """
            package com.example;
            
            public class Utils {
                public static String formatName(String input) {
                    return input.trim().toLowerCase();
                }
            }
        """.trimIndent()
    )

    fun getClassCode(className: String): String? = stubClasses[className]

    fun listClasses(filter: String? = null, offset: Int = 0, count: Int = 100): List<ClassSummary> {
        return stubClasses.keys
            .filter { filter == null || it.contains(filter, ignoreCase = true) }
            .drop(offset)
            .take(count)
            .map { ClassSummary(it, it.substringBeforeLast('.'), true, false, false, false, "java.lang.Object", 2, 0, 0) }
    }

    fun searchCode(query: String, limit: Int = 100): List<SearchMatch> {
        val results = mutableListOf<SearchMatch>()
        for ((className, code) in stubClasses) {
            if (results.size >= limit) break
            code.lines().forEachIndexed { index, line ->
                if (results.size >= limit) return@forEachIndexed
                if (line.contains(query, ignoreCase = true)) {
                    results.add(SearchMatch(className, index + 1, line.trim()))
                }
            }
        }
        return results
    }
}
