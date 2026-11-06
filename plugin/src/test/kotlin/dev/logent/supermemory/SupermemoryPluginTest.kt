package dev.logent.supermemory

import dev.logent.plugin.PluginContext
import dev.logent.plugin.PluginHttpResponse
import dev.logent.plugin.PluginSettingKind
import dev.logent.plugin.ToolAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupermemoryPluginTest {

    @Test
    fun `플러그인 identity와 설정 계약을 노출해요`() {
        val plugin = SupermemoryPlugin()

        assertEquals("dev.logent.supermemory", plugin.id)
        assertEquals("Supermemory 기억", plugin.name)
        assertEquals(BuildConfig.PLUGIN_VERSION, plugin.version)
        assertEquals(3, plugin.apiVersion)

        val settings = plugin.settings().associateBy { it.key }
        assertEquals(2, settings.size)
        val key = requireNotNull(settings[SupermemoryPlugin.KEY_API_KEY])
        assertEquals(PluginSettingKind.SECRET, key.kind)
        assertEquals("", key.defaultValue)
        val namespace = requireNotNull(settings[SupermemoryPlugin.KEY_NAMESPACE])
        assertEquals(PluginSettingKind.TEXT, namespace.kind)
        assertEquals("logent", namespace.defaultValue)

        val prompt = requireNotNull(plugin.promptSection())
        assertTrue(prompt.contains("## 장기 기억(Supermemory)"))
        assertTrue(prompt.contains("memory: Supermemory에 사용자·프로젝트 기억을 저장하거나 recall·context로 불러온다."))
    }

    @Test
    fun `memory 스키마는 코어의 이름·required·속성 한도를 유지해요`() {
        val tool = SupermemoryPlugin().tools().single()
        val function = tool.spec.function

        assertEquals("memory", function.name)
        assertEquals("memory", tool.name)
        assertEquals(setOf(ToolAccess.WRITE), tool.access)
        assertFalse(tool.childVisible)
        assertEquals("""["action"]""", function.parameters["required"].toString())

        val properties = requireNotNull(function.parameters["properties"]).toString()
        assertTrue(properties.contains("save·recall·context·forget"))
        assertTrue(properties.contains("\"minimum\":1"))
        assertTrue(properties.contains("\"maximum\":100"))
        assertTrue(properties.contains("ids"))
    }

    @Test
    fun `도구는 플러그인 설정의 키와 네임스페이스로 요청해요`() = runBlocking {
        val host = FakePluginHost()
        host.set(SupermemoryPlugin.KEY_API_KEY, SECRET)
        host.set(SupermemoryPlugin.KEY_NAMESPACE, "team")
        host.respond = {
            Result.success(PluginHttpResponse(200, emptyMap(), """{"id":"doc_9","status":"queued"}"""))
        }
        val plugin = SupermemoryPlugin()
        assertTrue(plugin.ensureReady(host).isSuccess)

        val result = plugin.tools().single().execute(
            buildJsonObject {
                put("action", "save")
                put("content", "플러그인 설정을 읽어요")
            },
            PluginContext("session", "/tmp"),
        )

        val request = host.requests.single()
        assertEquals("https://api.supermemory.ai/ns/team/document", request.url)
        assertEquals("Bearer $SECRET", request.headers["Authorization"])
        assertFalse(result.isError)
        assertTrue(result.text.contains("id=doc_9"))
        assertFalse(result.text.contains(SECRET))
    }

    @Test
    fun `네임스페이스가 비어 있으면 logent를 써요`() = runBlocking {
        val host = FakePluginHost()
        host.set(SupermemoryPlugin.KEY_API_KEY, SECRET)
        host.respond = {
            Result.success(PluginHttpResponse(200, emptyMap(), """{"id":"doc_1"}"""))
        }
        val plugin = SupermemoryPlugin()
        plugin.ensureReady(host).getOrThrow()

        plugin.tools().single().execute(
            buildJsonObject {
                put("action", "save")
                put("content", "기억")
            },
            PluginContext("session", "/tmp"),
        )

        assertEquals("https://api.supermemory.ai/ns/logent/document", host.requests.single().url)
    }

    @Test
    fun `알 수 없는 action과 누락된 키는 명확한 오류예요`() = runBlocking {
        val host = FakePluginHost()
        host.set(SupermemoryPlugin.KEY_API_KEY, SECRET)
        val plugin = SupermemoryPlugin()
        plugin.ensureReady(host).getOrThrow()
        val tool = plugin.tools().single()

        val unknown = tool.execute(
            buildJsonObject { put("action", "search") },
            PluginContext("session", "/tmp"),
        )
        assertTrue(unknown.isError)
        assertTrue(unknown.text.contains("지원하지 않는 action 'search'이에요."))

        host.set(SupermemoryPlugin.KEY_API_KEY, "")
        val missing = tool.execute(
            buildJsonObject {
                put("action", "recall")
                put("query", "기억")
            },
            PluginContext("session", "/tmp"),
        )
        assertTrue(missing.isError)
        assertTrue(missing.text.contains("Supermemory API 키가 설정되어 있지 않아요."))

        val missingAction = tool.execute(buildJsonObject { }, PluginContext("session", "/tmp"))
        assertTrue(missingAction.isError)
        assertTrue(missingAction.text.contains("action이 필요해요."))
        assertTrue(host.requests.isEmpty())
    }

    @Test
    fun `recall은 설정의 limit을 보내고 배열 ids는 공백을 정리해요`() = runBlocking {
        val host = FakePluginHost()
        host.set(SupermemoryPlugin.KEY_API_KEY, SECRET)
        host.respond = {
            Result.success(PluginHttpResponse(200, emptyMap(), """{"results":[],"count":0}"""))
        }
        val plugin = SupermemoryPlugin()
        plugin.ensureReady(host).getOrThrow()
        val tool = plugin.tools().single()

        tool.execute(
            buildJsonObject {
                put("action", "recall")
                put("query", "주제")
                put("limit", 7)
            },
            PluginContext("session", "/tmp"),
        )
        val recallBody = Json.parseToJsonElement(requireNotNull(host.requests.single().body)).jsonObject
        assertEquals("7", recallBody["limit"]?.jsonPrimitive?.content)

        host.respond = {
            Result.success(PluginHttpResponse(200, emptyMap(), """{"count":0,"matches":[],"errors":[]}"""))
        }
        tool.execute(
            buildJsonObject {
                put("action", "forget")
                put("ids", buildJsonArray {
                    add(JsonPrimitive(" m1 "))
                    add(JsonPrimitive(""))
                })
            },
            PluginContext("session", "/tmp"),
        )
        assertEquals(
            """{"ids":["m1"]}""",
            host.requests.last().body,
        )
    }

    private companion object {
        private const val SECRET = "sk-test-SECRET"
    }
}
