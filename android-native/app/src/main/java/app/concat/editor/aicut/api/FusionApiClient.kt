package app.concat.editor.aicut.api

import app.concat.editor.aicut.ui.FusionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * fusion-gateway 客户端 — 与 gateway/app/routers/gen.py 的协议一一对应。
 *
 * 用 org.json 解析（Android 自带），不引入 kotlinx-serialization 插件——
 * 这样并入 Concat 的 android-native 工程时不需要动它的 Gradle 插件配置。
 *
 * 协议：方案 §4.1（手机版融合视频App开发方案.md）
 */
object FusionApiClient {

    var baseUrl: String
        get() = FusionConfig.baseUrl
        set(value) { FusionConfig.baseUrl = value }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---- DTO ------------------------------------------------------------

    data class GenRequest(
        val prompt: String,
        val aspect: String = "9:16",           // 9:16 | 16:9 | 1:1
        val voiceName: String = "zh-CN-XiaoxiaoNeural",
        val subtitleEnabled: Boolean = true,
        val bgmType: String = "random",
        val aiVideo: Boolean = false,
    )

    data class Artifact(
        val fileName: String,
        val streamUrl: String,
        val downloadUrl: String,
    )

    data class GenCreated(val genId: String, val status: String, val pollIntervalS: Float)

    data class GenStatus(
        val genId: String,
        val status: String,                    // queued|processing|completed|failed|cancelled
        val progress: Float?,
        val mptTaskId: String?,
        val error: String?,
        val artifacts: List<Artifact>,
    )

    class ApiError(val code: Int, message: String) : RuntimeException("HTTP $code: $message")

    // ---- API ------------------------------------------------------------

    /** ① 提交 AI 成片任务 → genId */
    suspend fun createGen(request: GenRequest): GenCreated = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("prompt", request.prompt)
            put("aspect", request.aspect)
            put("voice_name", request.voiceName)
            put("subtitle_enabled", request.subtitleEnabled)
            put("bgm_type", request.bgmType)
            put("ai_video", request.aiVideo)
        }
        exec("POST", "/api/v1/gen", body).let { resp ->
            GenCreated(resp.getString("gen_id"), resp.getString("status"), (resp.optDouble("poll_interval_s", 2.0)).toFloat())
        }
    }

    /** ③ 轮询状态与产物 */
    suspend fun getGen(genId: String): GenStatus = withContext(Dispatchers.IO) {
        exec("GET", "/api/v1/gen/$genId", null).let { resp ->
            GenStatus(
                genId = resp.getString("gen_id"),
                status = resp.getString("status"),
                progress = resp.optDouble("progress", Double.NaN).let { if (it.isNaN()) null else it.toFloat() },
                mptTaskId = resp.optString("mpt_task_id", "").ifEmpty { null },
                error = resp.optString("error", "").ifEmpty { null },
                artifacts = resp.optJSONArray("artifacts")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val a = arr.getJSONObject(i)
                        Artifact(a.getString("file_name"), a.getString("stream_url"), a.getString("download_url"))
                    }
                } ?: emptyList(),
            )
        }
    }

    /** 取消 / 清除任务 */
    suspend fun deleteGen(genId: String): Unit = withContext(Dispatchers.IO) {
        runCatching { exec("DELETE", "/api/v1/gen/$genId", null) } // 404 可忽略
    }

    /** 健康检查 */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            call(Request.Builder().url("$baseUrl/api/v1/health").get().build()).use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** ④ 下载成片到本地（engine.importVideo 需要真实文件路径） */
    suspend fun downloadTo(artifact: Artifact, target: File, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(artifact.downloadUrl).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiError(resp.code, "download failed")
                val total = (resp.body?.contentLength() ?: -1).coerceAtLeast(1)
                target.outputStream().use { out ->
                    val input = resp.body!!.byteStream()
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            target
        }

    // ---- 内部 ------------------------------------------------------------

    private fun call(request: Request): okhttp3.Response = http.newCall(request).execute()

    private fun exec(method: String, path: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder().url("$baseUrl$path")
        when (method) {
            "POST" -> builder.post(
                (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
            )
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        call(builder.build()).use { resp ->
            val text = resp.body?.string() ?: throw ApiError(resp.code, "empty body")
            if (!resp.isSuccessful) throw ApiError(resp.code, text.take(300))
            return JSONObject(text)
        }
    }
}
