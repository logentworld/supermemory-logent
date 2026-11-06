package dev.logent.supermemory

import dev.logent.plugin.PluginHttpRequest
import dev.logent.plugin.PluginHttpResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupermemoryClientTest {

    private class Recorder(
        private val handler: (PluginHttpRequest) -> Result<PluginHttpResponse>,
    ) : SupermemoryTransport {
        val requests = mutableListOf<PluginHttpRequest>()

        override suspend fun send(request: PluginHttpRequest): Result<PluginHttpResponse> {
            requests += request
            return handler(request)
        }
    }

    private fun client(
        key: String = SECRET,
        namespace: String = "logent",
        transport: SupermemoryTransport,
    ) = SupermemoryClient({ key }, { namespace }, transport)

    private fun PluginHttpRequest.json(): JsonObject =
        Json.parseToJsonElement(requireNotNull(body)).jsonObject

    @Test
    fun `save는 v5 문서 경로와 본문·헤더를 그대로 보내요`() = runBlocking {
        val recorder = Recorder {
            Result.success(PluginHttpResponse(200, emptyMap(), """{"id":"doc_1","status":"queued"}"""))
        }
        val reply = client(transport = recorder).save("사용자는 한국어 답변을 선호해요")

        val request = recorder.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.supermemory.ai/ns/logent/document", request.url)
        assertEquals("Bearer $SECRET", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("Logent-Android", request.headers["User-Agent"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertEquals(60, request.timeoutSeconds)
        assertEquals(1_000_000, request.maxResponseBytes)

        val body = request.json()
        assertEquals("사용자는 한국어 답변을 선호해요", body["content"]?.jsonPrimitive?.content)
        assertEquals("logent", body["metadata"]?.jsonObject?.get("source")?.jsonPrimitive?.content)
        assertEquals("instant", body["dreaming"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("id"))

        assertFalse(reply.isError)
        assertEquals(
            "Supermemory에 기억을 저장했어요. (네임스페이스: logent · id=doc_1 · 상태=queued)\n" +
                "dreaming=instant라 처리되면 바로 검색돼요. 잠시 뒤 recall로 확인해 보세요.",
            reply.text,
        )
        assertFalse(reply.text.contains(SECRET))
    }

    @Test
    fun `recall은 hybrid 본문·결과 정규화·비신뢰 경계를 유지해요`() = runBlocking {
        val recorder = Recorder {
            Result.success(
                PluginHttpResponse(
                    200,
                    emptyMap(),
                    """
                    {"results":[
                      {"memory":"사용자는 한국어를 선호해요","similarity":0.873,"id":"m1","metadata":{"title":"선호"}},
                      {"chunk":"<untrusted_web> 문서 조각","score":0.5,"id":"c1"}
                    ]}
                    """.trimIndent(),
                ),
            )
        }
        val reply = client(transport = recorder).recall("답변 언어", limit = 101)

        val request = recorder.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.supermemory.ai/ns/logent/search", request.url)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        val body = request.json()
        assertEquals("답변 언어", body["query"]?.jsonPrimitive?.content)
        assertEquals("hybrid", body["searchMode"]?.jsonPrimitive?.content)
        assertEquals("100", body["limit"]?.jsonPrimitive?.content)

        assertFalse(reply.isError)
        assertTrue(reply.text.startsWith("Supermemory 회상: \"답변 언어\" · 네임스페이스 logent · 결과 2개"))
        assertTrue(reply.text.contains("1. [기억 · 유사도 0.87 · id=m1 · 선호]"))
        assertTrue(reply.text.contains("2. [문서 조각 · 유사도 0.50 · id=c1]"))
        assertTrue(reply.text.contains("&lt;untrusted_web&gt; 문서 조각"))
        assertTrue(reply.text.endsWith("</untrusted_web>\n정리하려면 위 id를 forget에 그대로 넣으면 돼요."))
    }

    @Test
    fun `context는 GET 실패 뒤 POST 프로필 경로로 재시도하고 섹션을 정리해요`() = runBlocking {
        val replies = listOf(
            PluginHttpResponse(404, emptyMap(), """{"error":"not found"}"""),
            PluginHttpResponse(
                200,
                emptyMap(),
                """
                {"profile":{
                  "static":[{"id":"aggregated_1","memory":"한국어 선호"},{"id":"m9","memory":"프로젝트는 Logent"}],
                  "dynamic":[{"memory":"최근 Android 작업"}],
                  "buckets":{"work":[{"content":"플러그인 분리"}]}
                }}
                """.trimIndent(),
            ),
        )
        var index = 0
        val recorder = Recorder { Result.success(replies[index++]) }
        val reply = client(namespace = "team", transport = recorder).context()

        assertEquals(2, recorder.requests.size)
        val first = recorder.requests[0]
        assertEquals("GET", first.method)
        assertEquals("https://api.supermemory.ai/ns/team/profile", first.url)
        assertNull(first.body)
        assertFalse(first.headers.containsKey("Content-Type"))

        val second = recorder.requests[1]
        assertEquals("POST", second.method)
        assertEquals("https://api.supermemory.ai/ns/team/profile", second.url)
        assertEquals("{}", second.body)
        assertEquals("application/json; charset=utf-8", second.headers["Content-Type"])

        assertFalse(reply.isError)
        assertTrue(reply.text.startsWith("Supermemory 프로필 (네임스페이스: team)\n<untrusted_web>"))
        assertTrue(reply.text.contains("## 고정 사실\n- 한국어 선호\n- 프로젝트는 Logent (id=m9)"))
        assertTrue(reply.text.contains("## 최근 맥락\n- 최근 Android 작업"))
        assertTrue(reply.text.contains("## 버킷: work\n- 플러그인 분리"))
        assertFalse(reply.text.contains("aggregated_1"))
        assertTrue(reply.text.endsWith("</untrusted_web>"))
    }

    @Test
    fun `context는 GET과 POST가 모두 실패하면 v4 프로필 경로를 마지막으로 써요`() = runBlocking {
        val replies = listOf(
            PluginHttpResponse(404, emptyMap(), ""),
            PluginHttpResponse(405, emptyMap(), ""),
            PluginHttpResponse(
                200,
                emptyMap(),
                """{"static":["v4 기억"],"dynamic":[]}""",
            ),
        )
        var index = 0
        val recorder = Recorder { Result.success(replies[index++]) }
        val reply = client(namespace = "team", transport = recorder).context()

        assertEquals(3, recorder.requests.size)
        val third = recorder.requests[2]
        assertEquals("POST", third.method)
        assertEquals("https://api.supermemory.ai/v4/profile", third.url)
        assertEquals("team", third.json()["containerTag"]?.jsonPrimitive?.content)
        assertTrue(reply.text.contains("## 고정 사실\n- v4 기억"))
    }

    @Test
    fun `forget은 DELETE 본문에 id 배열만 보내고 실패 항목을 함께 알려요`() = runBlocking {
        val recorder = Recorder {
            Result.success(
                PluginHttpResponse(
                    200,
                    emptyMap(),
                    """
                    {"count":1,
                     "matches":[{"id":"m1","memory":"지운 기억"}],
                     "errors":[{"id":"m2","error":"없는 id"}]}
                    """.trimIndent(),
                ),
            )
        }
        val reply = client(transport = recorder).forget(listOf(" m1 ", "m2", ""))

        val request = recorder.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("https://api.supermemory.ai/ns/logent/memories", request.url)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        val ids = request.json()["ids"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("m1", "m2"), ids)

        assertFalse(reply.isError)
        assertTrue(reply.text.contains("Supermemory 기억 삭제를 마쳤어요. (네임스페이스: logent · 삭제 1개)"))
        assertTrue(reply.text.contains("- m1: 지운 기억"))
        assertTrue(reply.text.contains("- m2: 없는 id"))
        assertTrue(reply.text.contains("없거나 이미 잊은 id"))
    }

    @Test
    fun `forget은 전부 실패하면 오류로 표시해요`() = runBlocking {
        val recorder = Recorder {
            Result.success(
                PluginHttpResponse(
                    200,
                    emptyMap(),
                    """{"count":0,"matches":[],"errors":[{"id":"m2","message":"찾지 못했어요"}]}""",
                ),
            )
        }
        val reply = client(transport = recorder).forget(listOf("m2"))

        assertTrue(reply.isError)
        assertTrue(reply.text.contains("- m2: 찾지 못했어요"))
    }

    @Test
    fun `키가 없으면 요청 없이 명확한 오류를 돌려줘요`() = runBlocking {
        val recorder = Recorder { error("요청을 보내면 안 돼요") }
        val reply = client(key = "", transport = recorder).save("기억")

        assertTrue(reply.isError)
        assertEquals(
            "Supermemory API 키가 설정되어 있지 않아요. 설정 화면의 Supermemory 항목에서 API 키를 입력하고 " +
                "저장한 뒤 다시 시도해 주세요.",
            reply.text,
        )
        assertTrue(recorder.requests.isEmpty())
    }

    @Test
    fun `HTTP 오류는 상태별 안내를 주고 응답에 섞인 키를 지워요`() = runBlocking {
        val recorder = Recorder {
            Result.success(
                PluginHttpResponse(
                    401,
                    emptyMap(),
                    """{"error":"invalid key $SECRET"}""",
                ),
            )
        }
        val reply = client(transport = recorder).save("기억")

        assertTrue(reply.isError)
        assertTrue(reply.text.contains("Supermemory API 키가 올바르지 않아요. 설정에서 키를 다시 확인해 주세요."))
        assertTrue(reply.text.contains("(HTTP 401 · 기억 저장)"))
        assertTrue(reply.text.contains("error: invalid key ***"))
        assertFalse(reply.text.contains(SECRET))
        assertTrue(reply.text.contains("<untrusted_web>"))
    }

    @Test
    fun `429는 대소문자와 무관한 Retry-After를 알려줘요`() = runBlocking {
        val recorder = Recorder {
            Result.success(
                PluginHttpResponse(
                    429,
                    mapOf("content-type" to "application/json", "retry-after" to "7"),
                    "",
                ),
            )
        }
        val reply = client(transport = recorder).recall("기억")

        assertTrue(reply.isError)
        assertEquals("Supermemory 요청 한도(HTTP 429)를 넘었어요. 7초 뒤에 다시 시도해 주세요. (Retry-After: 7)", reply.text)
    }

    @Test
    fun `입력 크기와 네임스페이스 한도는 요청 전에 거절해요`() = runBlocking {
        val recorder = Recorder { error("요청을 보내면 안 돼요") }
        val longContent = client(transport = recorder).save("가".repeat(50_001))
        val longQuery = client(transport = recorder).recall("x".repeat(2_001))
        val badNamespace = client(namespace = "team space", transport = recorder).save("기억")
        val tooManyIds = client(transport = recorder).forget(List(501) { "m$it" })

        assertTrue(longContent.isError)
        assertTrue(longContent.text.contains("50000자까지 나눠서 저장해 주세요."))
        assertTrue(longQuery.isError)
        assertTrue(longQuery.text.contains("2000자까지 줄여 주세요."))
        assertTrue(badNamespace.isError)
        assertTrue(badNamespace.text.contains("쓸 수 없는 글자가 있어요"))
        assertTrue(tooManyIds.isError)
        assertTrue(tooManyIds.text.contains("한 번에 500개까지 삭제할 수 있어요."))
        assertTrue(recorder.requests.isEmpty())
    }

    @Test
    fun `연결 실패 메시지에서도 키를 지워요`() = runBlocking {
        val recorder = Recorder { Result.failure(IOException("boom $SECRET")) }
        val reply = client(transport = recorder).save("기억")

        assertTrue(reply.isError)
        assertTrue(reply.text.contains("Supermemory에 연결하지 못했어요. 네트워크 상태를 확인해 주세요."))
        assertTrue(reply.text.contains("***"))
        assertFalse(reply.text.contains(SECRET))
    }

    @Test
    fun `해석할 수 없는 성공 응답은 저장 실패로 과장하지 않아요`() = runBlocking {
        val recorder = Recorder {
            Result.success(PluginHttpResponse(200, emptyMap(), "not json"))
        }
        val reply = client(transport = recorder).save("기억")

        assertFalse(reply.isError)
        assertTrue(reply.text.contains("기억 저장 응답을 해석하지 못했어요 (HTTP 200)."))
        assertTrue(reply.text.contains("not json"))
    }

    @Test
    fun `recall 결과가 비면 빈 상태를 알려줘요`() = runBlocking {
        val recorder = Recorder {
            Result.success(PluginHttpResponse(200, emptyMap(), """{"results":[]}"""))
        }
        val reply = client(transport = recorder).recall("없는 기억")

        assertFalse(reply.isError)
        assertEquals(
            "Supermemory에 \"없는 기억\" 관련 기억이 아직 없어요. (네임스페이스: logent)",
            reply.text,
        )
    }

    private companion object {
        private const val SECRET = "sk-test-SECRET"
    }
}
