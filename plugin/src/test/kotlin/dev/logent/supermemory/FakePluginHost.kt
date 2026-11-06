package dev.logent.supermemory

import dev.logent.plugin.PluginHost
import dev.logent.plugin.PluginHttpRequest
import dev.logent.plugin.PluginHttpResponse
import dev.logent.plugin.ProcessResult

/** 설정과 HTTPS 요청을 그대로 붙잡아 두는 테스트 호스트예요. */
internal class FakePluginHost(
    override val appVersion: String = "0.0.0-test",
    override val apkPath: String = "/data/app/~~abc/dev.logent-1/base.apk",
) : PluginHost {
    private val values = mutableMapOf<String, String>()
    val requests = mutableListOf<PluginHttpRequest>()
    val progressMessages = mutableListOf<String>()

    var respond: (PluginHttpRequest) -> Result<PluginHttpResponse> = {
        Result.failure(IllegalStateException("테스트 응답이 없어요."))
    }

    fun set(key: String, value: String) {
        values[key] = value
    }

    override fun setting(key: String): String? = values[key]

    override suspend fun http(request: PluginHttpRequest): Result<PluginHttpResponse> {
        requests += request
        return respond(request)
    }

    override suspend fun shell(command: String, timeoutSeconds: Int): Result<ProcessResult> =
        Result.failure(UnsupportedOperationException("테스트에서는 셸을 쓰지 않아요: $command"))

    override suspend fun ensureDesktop(): Result<Unit> = Result.success(Unit)

    override suspend fun readAsset(path: String): Result<ByteArray> =
        Result.failure(IllegalArgumentException("테스트에서는 자산을 읽지 않아요: $path"))

    override fun progress(message: String) {
        progressMessages += message
    }
}
