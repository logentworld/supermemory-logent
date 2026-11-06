package dev.logent.supermemory

import dev.logent.plugin.PluginContext
import dev.logent.plugin.PluginHost
import dev.logent.plugin.PluginTool
import dev.logent.plugin.ToolAccess
import dev.logent.plugin.ToolResult
import dev.logent.plugin.array
import dev.logent.plugin.integer
import dev.logent.plugin.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `memory` 도구예요.
 *
 * 호스트가 [PluginHost.ensureReady][dev.logent.plugin.LogentPlugin.ensureReady]로
 * 넘겨 준 호스트를 붙잡아 두고, 실행할 때마다 설정에서 키·네임스페이스를 다시 읽어요.
 * 그래서 설정을 바꾸면 다음 호출부터 바로 반영돼요.
 */
internal class SupermemoryTool {
    @Volatile
    internal var host: PluginHost? = null

    private val client = SupermemoryClient(
        apiKey = { host?.setting(SupermemoryPlugin.KEY_API_KEY).orEmpty() },
        namespace = { host?.setting(SupermemoryPlugin.KEY_NAMESPACE).orEmpty() },
        transport = { request ->
            host?.http(request)
                ?: Result.failure(IllegalStateException("플러그인 준비가 끝나지 않았어요."))
        },
    )

    fun definition(): PluginTool = PluginTool(
        spec = SupermemorySpec.memory,
        access = setOf(ToolAccess.WRITE),
        childVisible = false,
    ) { arguments, context -> execute(arguments, context) }

    @Suppress("UNUSED_PARAMETER")
    internal suspend fun execute(arguments: JsonObject, context: PluginContext): ToolResult =
        when (val action = arguments.text("action")?.trim()?.lowercase()) {
            "save" -> client.save(content = arguments.text("content").orEmpty())
            "recall" -> client.recall(
                query = arguments.text("query").orEmpty(),
                limit = arguments.integer("limit") ?: SupermemorySpec.DEFAULT_RECALL_LIMIT,
            )
            "context" -> client.context()
            "forget" -> client.forget(ids = arguments.array("ids")?.mapNotNull(::idOf).orEmpty())
            null, "" -> SupermemoryReply(
                "action이 필요해요. save·recall·context·forget 중 하나를 정해 주세요.",
                isError = true,
            )
            else -> SupermemoryReply(
                "지원하지 않는 action '$action'이에요. save·recall·context·forget 중 하나를 써 주세요.",
                isError = true,
            )
        }.toToolResult()

    private fun idOf(element: JsonElement): String? =
        (element as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun SupermemoryReply.toToolResult(): ToolResult = ToolResult(text = text, isError = isError)
}
