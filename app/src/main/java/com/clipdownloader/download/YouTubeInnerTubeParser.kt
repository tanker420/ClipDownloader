package com.clipdownloader.download

import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import com.clipdownloader.util.LogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube 本地解析通道（InnerTube visionos 客户端，2026-08-23 引入）。
 *
 * 背景：YouTube 的 googlevideo 直链绑定「提取时的请求 IP」（外加 PO token 校验），
 * iiiLab 等服务端代提的直链换到手机 IP 下载必 403（HTTP 403，与 UA/Referer 无关）。
 * 唯一稳妥的路子是让手机自己向 YouTube 要直链——直链绑定的就是手机出口 IP，
 * 下载自然放行。
 *
 * 请求构造逐字段对齐 NewPipeExtractor（2026-08 dev，生产验证可行）：
 * - POST youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&t=<12位随机>&id=<videoId>
 *   （必须走 GAPIS 域名并带 t/id 查询参数；此前用 www.youtube.com 且缺这些指纹字段，
 *   被识别为假客户端直接 LOGIN_REQUIRED "Sign in to confirm you're not a bot"）
 * - 头只有三样：visionos App UA、Content-Type、X-Goog-Api-Format-Version: 2
 * - visitorData 用 VISIONOS 上下文向 www.youtube.com 的 visitor_id 端点获取
 *   （网页 ytcfg 里的 visitorData 属于 WEB 客户端，不能跨客户端使用）
 * - body 带 clientScreen=WATCH、platform=MOBILE、cpn（16 位随机）、hl/gl/utcOffsetMinutes
 * - 版本号跟随 visionos App 现行版：1.04 / visionOS 26.6.0.23O770
 *
 * PO token（BotGuard）分平台：Web 的不能用于 visionos（yt-dlp wiki 与 NewPipe 均确认，
 * 2026-08-23 实测带 token 重试也仍被拒），相关重试已移除；真被风控的 IP 只能换网络/节点。
 * android_vr 自 2026-08-17 起全格式 403；tv 无 cookie 时下发 DRM 流，均不可用。
 * 限制：visionos 拿不到「儿童内容」（made for kids），此类回落 iiiLab/云端通道。
 */
class YouTubeInnerTubeParser(
    private val appContext: android.content.Context,
    private val onStatus: ((String) -> Unit)? = null
) {

    /** 最近一次失败原因，供上层并入解析错误提示 */
    var lastError: String = ""
        private set

    /** 最近一次 player 响应的 playabilityStatus（日志用） */
    private var lastStatusCode = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val locale = java.util.Locale.getDefault()
    private val hl = if (locale.language.isNotBlank()) locale.toLanguageTag() else "en-US"
    private val gl = locale.country.takeIf { it.isNotBlank() } ?: "US"

    suspend fun parseAll(shareText: String, platform: Platform): List<ParsedMedia> =
        withContext(Dispatchers.IO) {
            lastError = ""
            val url = PlatformParser.extractUrl(shareText) ?: run {
                lastError = "未找到链接"
                return@withContext emptyList()
            }
            val videoId = extractVideoId(url) ?: run {
                lastError = "无法从链接提取视频 ID"
                return@withContext emptyList()
            }

            val visitorData = try {
                getVisitorData()
            } catch (e: Exception) {
                lastError = "获取 visitorData 失败: ${e.message ?: e.javaClass.simpleName}"
                LogFile.w(TAG, lastError)
                return@withContext emptyList()
            }

            val result = attemptExtract(videoId, url, platform, visitorData)
            if (result.isEmpty() && lastError.isBlank()) lastError = "未解析到媒体"
            if (result.isNotEmpty()) {
                LogFile.d(TAG, "解析成功（本地 InnerTube visionos）：${result.size} 个媒体")
            } else {
                LogFile.w(TAG, "解析失败：$lastError")
            }
            result
        }

    /** 单次 player 请求 + 响应提取；网络异常归一成可读原因后返回空列表 */
    private fun attemptExtract(
        videoId: String,
        shareUrl: String,
        platform: Platform,
        visitorData: String
    ): List<ParsedMedia> {
        val cpn = randomCpn(16)
        val body = JSONObject().apply {
            put("context", clientContext(visitorData))
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        return try {
            val req = Request.Builder()
                .url("$PLAYER_URL?prettyPrint=false&t=${randomCpn(12)}&id=$videoId")
                .header("User-Agent", appUa())
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Format-Version", "2")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastError = "player API HTTP ${resp.code}"
                    emptyList()
                } else {
                    extractMedia(JSONObject(text), shareUrl, platform, cpn)
                }
            }
        } catch (e: Exception) {
            lastError = if (e.message?.contains("timeout", true) == true ||
                e is java.net.UnknownHostException ||
                e is java.net.ConnectException)
                "无法连接 YouTube（需要可访问 YouTube 的网络）" else (e.message ?: "请求异常")
            emptyList()
        }
    }

    /** VISIONOS 客户端的 visitorData：长期有效，进程内缓存 */
    private fun getVisitorData(): String {
        cachedVisitorData?.let { return it }
        val body = JSONObject().apply { put("context", clientContext(null)) }
        val req = Request.Builder()
            .url("$VISITOR_ID_URL?prettyPrint=false")
            .header("User-Agent", appUa())
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Format-Version", "2")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("visitor_id HTTP ${resp.code}")
            val v = JSONObject(resp.body?.string().orEmpty())
                .optJSONObject("responseContext")?.optString("visitorData").orEmpty()
            if (v.isBlank()) throw RuntimeException("visitor_id 未返回 visitorData")
            cachedVisitorData = v
            LogFile.d(TAG, "visitorData 获取成功（VISIONOS 上下文，${v.length} 字符）")
            return v
        }
    }

    /** InnerTube context 公共部分（对齐 NewPipe prepareJsonBuilder 的 VISIONOS 分支） */
    private fun clientContext(visitorData: String?): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", CLIENT_NAME)
            put("clientVersion", CLIENT_VERSION)
            put("clientScreen", "WATCH")
            put("platform", "MOBILE")
            visitorData?.let { put("visitorData", it) }
            put("deviceMake", "Apple")
            put("deviceModel", "RealityDevice17,1")
            put("osName", "visionOS")
            put("osVersion", OS_VERSION)
            put("hl", hl)
            put("gl", gl)
            put("utcOffsetMinutes", 0)
        })
        put("request", JSONObject()
            .put("internalExperimentFlags", JSONArray())
            .put("useSsl", true))
        put("user", JSONObject().put("lockedSafetyMode", false))
    }

    /** player 响应 → 媒体列表：只取音视频合一的渐进流里最高清晰度的一条 */
    private fun extractMedia(
        root: JSONObject,
        shareUrl: String,
        platform: Platform,
        cpn: String
    ): List<ParsedMedia> {
        val status = root.optJSONObject("playabilityStatus")
        val statusCode = status?.optString("status").orEmpty()
        lastStatusCode = statusCode
        if (statusCode != "OK") {
            // reason 原文透出：区分「机器人验证」「年龄限制」「视频不可用」等
            val reason = status?.optString("reason").orEmpty()
            LogFile.w(TAG, "playabilityStatus=$statusCode reason=$reason")
            lastError = when {
                statusCode == "LOGIN_REQUIRED" && (reason.contains("bot", true) || reason.contains("Sign in", true)) ->
                    "YouTube 要求登录验证（出口 IP 被风控）: $reason — 可更换网络/代理节点后重试"
                statusCode == "LOGIN_REQUIRED" && reason.isNotBlank() -> "需要登录: $reason"
                statusCode == "LOGIN_REQUIRED" -> "该视频需要登录或为儿童内容（visionos 客户端不可用）"
                reason.isNotBlank() -> "不可播放: $reason"
                else -> "playabilityStatus=$statusCode"
            }
            return emptyList()
        }

        val details = root.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty()
        val author = details?.optString("author").orEmpty()
        val durationMs = (details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000L
        val cover = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            ?.maxByOrNull { it.optInt("width", 0) }
            ?.optString("url")?.takeIf { it.startsWith("http") }

        val formats = root.optJSONObject("streamingData")?.optJSONArray("formats")
        var bestUrl: String? = null
        var bestScore = -1
        for (i in 0 until (formats?.length() ?: 0)) {
            val f = formats!!.optJSONObject(i) ?: continue
            // 只带 signatureCipher 的流需要网页签名解密，本地通道拿不到，跳过
            val u = f.optString("url").takeIf { it.startsWith("http") } ?: continue
            val score = formatHeight(f)
            if (score > bestScore) {
                bestScore = score
                bestUrl = u
            }
        }
        if (bestUrl == null) {
            lastError = "无可下载的音视频合一流（可能只有分离轨道或 DRM）"
            return emptyList()
        }

        return listOf(
            ParsedMedia(
                platform = platform,
                videoUrl = "$bestUrl&cpn=$cpn",
                coverUrl = cover,
                title = title,
                author = author,
                duration = durationMs,
                rawShareText = shareUrl,
                originalUrl = shareUrl
            )
        )
    }

    /** 流清晰度：优先 height 字段，其次已知 itag 映射，最后 qualityLabel 里的数字 */
    private fun formatHeight(f: JSONObject): Int {
        f.optInt("height", 0).takeIf { it > 0 }?.let { return it }
        val byItag = mapOf(22 to 720, 18 to 360, 59 to 480, 82 to 360).getOrElse(f.optInt("itag", 0)) { 0 }
        if (byItag > 0) return byItag
        val label = f.optString("qualityLabel")
        return Regex("(\\d{3,4})[pP]?").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** 各种 YouTube 分享形态 → 11 位视频 ID */
    private fun extractVideoId(url: String): String? {
        val m = Regex(
            "(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:shorts/|embed/|live/|v/)|[?&]v=)([A-Za-z0-9_-]{11})",
            RegexOption.IGNORE_CASE
        ).find(url) ?: return null
        return m.groupValues[1]
    }

    companion object {
        private const val TAG = "YouTubeInnerTube"

        /** visionos 走 GAPIS 域名 + t/id 查询参数（NewPipeExtractor 同款指纹） */
        private const val PLAYER_URL = "https://youtubei.googleapis.com/youtubei/v1/player"
        private const val VISITOR_ID_URL = "https://www.youtube.com/youtubei/v1/visitor_id"
        private const val CLIENT_NAME = "VISIONOS"
        private const val CLIENT_VERSION = "1.04"
        private const val OS_VERSION = "26.6.0.23O770"
        private const val UA_OS_VERSION = "26_6_0"

        /** cpn/t 参数共用的字母表（YouTube ContentPlaybackNonce） */
        private const val CPN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        @Volatile private var cachedVisitorData: String? = null

        private fun randomCpn(len: Int): String =
            (0 until len).map { CPN_ALPHABET[kotlin.random.Random.nextInt(CPN_ALPHABET.length)] }
                .joinToString("")

        /** visionos App 的请求 UA（player/visitor_id 用；下载直链仍用 CLIENT_UA 的 Safari UA） */
        private fun appUa(): String =
            "com.google.visionos.youtube/$CLIENT_VERSION(RealityDevice17,1; U; CPU visionOS " +
                "$UA_OS_VERSION like Mac OS X; ${if (java.util.Locale.getDefault().country.isNotBlank()) java.util.Locale.getDefault().country else "US"})"

        /** 与 yt-dlp visionos 客户端一致的 Safari UA（下载直链时也用它） */
        const val CLIENT_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    }
}
