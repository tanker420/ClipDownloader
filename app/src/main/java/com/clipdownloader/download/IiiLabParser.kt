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
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * iiiLab 网页端解析器（国外平台首选通道）。
 *
 * 协议 2026-08-22 从 twitter.iiilab.com 前端逆向（app-*.js）：
 * - POST https://webapi.iiilab.com/api/web/extract
 *   （2026-09-27 实测：旧域名 service.iiilab.com 已返回 404，站点迁移到 webapi 子域，
 *    签名算法与 secret 均未变）
 * - 请求头：G-Timestamp=<unix秒>；G-Footer=MD5(url + site + 时间戳 + secret)
 * - 请求体：{"url":"<分享链接>","site":"<平台标识>"}
 * - secret 为前端两段 base64 拼接（与站点保持一致的弱混淆，避免明文检索）
 * - site 只参与签名校验：与链接实际平台不一致也能解析（实测 tiktok 链接 +
 *   site=twitter 返回 200），接口按 URL 自动识别平台
 * - 响应：{medias:[{media_type,resource_url,preview_url,formats:[…]}],
 *   text,author:{display_name,…},overseas,…}；400 时 {message,code:"ExtractFailed"}
 *
 * 站点本身直链下载（无代理），海外 CDN 可达性取决于用户网络，与网页端一致。
 */
class IiiLabParser {

    /** 最近一次失败原因，供上层并入解析错误提示 */
    var lastError: String = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 取消本实例所有在途请求（竞速解析胜出后掐断败者的同步 execute） */
    fun cancelInFlight() {
        client.dispatcher.cancelAll()
    }

    suspend fun parseAll(shareText: String, platform: Platform): List<ParsedMedia> =
        withContext(Dispatchers.IO) {
            lastError = ""
            val url = PlatformParser.extractUrl(shareText) ?: run {
                lastError = "未找到链接"
                return@withContext emptyList()
            }
            val site = siteFor(url, platform)
            val ts = (System.currentTimeMillis() / 1000L).toString()
            val secret = SECRET_PARTS.map { String(java.util.Base64.getDecoder().decode(it)) }
                .joinToString("")
            val sign = try {
                MessageDigest.getInstance("MD5")
                    .digest((url + site + ts + secret).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                lastError = "签名计算失败: ${e.message}"
                return@withContext emptyList()
            }

            val body = JSONObject().apply {
                put("url", url)
                put("site", site)
            }
            val result = try {
                val req = Request.Builder()
                    .url("$API_BASE/api/web/extract")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://twitter.iiilab.com")
                    .header("Referer", "https://twitter.iiilab.com/")
                    .header("G-Timestamp", ts)
                    .header("G-Footer", sign)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = try {
                            JSONObject(text).optString("message").ifBlank { "HTTP ${resp.code}" }
                        } catch (_: Exception) { "HTTP ${resp.code}" }
                        lastError = msg
                        emptyList()
                    } else {
                        extractMedia(JSONObject(text), url, platform)
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "请求异常"
                emptyList()
            }
            if (result.isEmpty() && lastError.isBlank()) lastError = "未解析到媒体"
            if (result.isNotEmpty()) {
                LogFile.d(TAG, "解析成功：${result.size} 个媒体（site=$site）")
            } else {
                LogFile.w(TAG, "解析失败（site=$site）：$lastError")
            }
            result
        }

    /** 响应 → 媒体列表：视频自动选最高清晰度，图片逐张，音频按纯音乐处理 */
    private fun extractMedia(root: JSONObject, shareUrl: String, platform: Platform): List<ParsedMedia> {
        val medias = root.optJSONArray("medias") ?: run {
            lastError = "响应无 medias"
            return emptyList()
        }
        val title = root.optString("text")
        val authorObj = root.optJSONObject("author")
        val author = authorObj?.optString("display_name").orEmpty()
            .ifBlank { authorObj?.optString("username").orEmpty() }

        val result = mutableListOf<ParsedMedia>()
        for (i in 0 until medias.length()) {
            val m = medias.optJSONObject(i) ?: continue
            val resource = m.optString("resource_url")
            val cover = m.optString("preview_url").takeIf { it.startsWith("http") }
            when (m.optString("media_type")) {
                "image" -> if (resource.startsWith("http")) result.add(
                    ParsedMedia(
                        platform = platform,
                        imageUrl = resource,
                        title = title,
                        author = author,
                        rawShareText = shareUrl,
                        originalUrl = shareUrl
                    )
                )
                // 纯音频（音乐类作品）：下载器按 URL 扩展名/嗅探落盘
                "audio" -> if (resource.startsWith("http")) result.add(
                    ParsedMedia(
                        platform = platform,
                        videoUrl = resource,
                        isMusic = true,
                        title = title,
                        author = author,
                        coverUrl = cover,
                        rawShareText = shareUrl,
                        originalUrl = shareUrl
                    )
                )
                else -> {
                    val video = bestVideoUrl(m)
                    if (video.startsWith("http")) result.add(
                        ParsedMedia(
                            platform = platform,
                            videoUrl = video,
                            coverUrl = cover,
                            title = title,
                            author = author,
                            rawShareText = shareUrl,
                            originalUrl = shareUrl
                        )
                    )
                }
            }
        }
        return result
    }

    /**
     * 从 formats[]（缺失时 variants[]）里选最高清晰度的完整流。
     * separate=1 是与画面分离的纯音轨（App 不做音视频合流），跳过；
     * quality=9999 是"原画"标记，排在所有具体分辨率之上。
     */
    private fun bestVideoUrl(media: JSONObject): String {
        val formats = media.optJSONArray("formats") ?: media.optJSONArray("variants")
            ?: return media.optString("resource_url")
        var bestUrl: String? = null
        var bestScore = -1L
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (f.optInt("separate", 0) == 1) continue
            val u = f.optString("video_url")
            if (!u.startsWith("http")) continue
            val q = f.optLong("quality", 0L)
            val score = if (q == ORIGINAL_QUALITY) ORIGINAL_QUALITY * 10 else q
            if (score > bestScore) {
                bestScore = score
                bestUrl = u
            }
        }
        return bestUrl ?: media.optString("resource_url")
    }

    /** URL → iiilab site 标识（对齐前端域名映射；未识别时按平台枚举兜底） */
    private fun siteFor(url: String, platform: Platform): String {
        val host = try {
            java.net.URI(url).host?.lowercase().orEmpty()
        } catch (_: Exception) { "" }
        return when {
            host.endsWith("douyin.com") -> "douyin"
            host.endsWith("kuaishou.com") || host.endsWith("chenzhongtech.com") -> "kuaishou"
            host.endsWith("bilibili.com") || host.endsWith("b23.tv") -> "bilibili"
            host.endsWith("xiaohongshu.com") || host.endsWith("xhslink.com") -> "xiaohongshu"
            host.endsWith("weibo.com") || host.endsWith("weibo.cn") || host.endsWith("t.cn") -> "weibo"
            host.endsWith("tiktok.com") || host.endsWith("tokcdn.com") -> "tiktok"
            host.endsWith("youtube.com") || host.endsWith("youtu.be") -> "youtube"
            host.endsWith("instagram.com") || host.endsWith("instagr.am") -> "instagram"
            host.endsWith("twitter.com") || host.endsWith("x.com") ||
                host.endsWith("t.co") -> "twitter"
            host.endsWith("facebook.com") || host.endsWith("fb.watch") ||
                host.endsWith("fb.com") -> "facebook"
            host.endsWith("pinterest.com") || host.endsWith("pin.it") -> "pinterest"
            host.endsWith("threads.net") -> "threads"
            host.endsWith("vimeo.com") -> "vimeo"
            host.endsWith("tumblr.com") -> "tumblr"
            host.endsWith("snapchat.com") -> "snapchat"
            host.endsWith("vk.com") || host.endsWith("vkvideo.ru") -> "vk"
            else -> when (platform) {
                Platform.YOUTUBE -> "youtube"
                Platform.TIKTOK -> "tiktok"
                Platform.INSTAGRAM -> "instagram"
                Platform.FACEBOOK -> "facebook"
                Platform.PINTEREST -> "pinterest"
                else -> "twitter"
            }
        }
    }

    companion object {
        private const val TAG = "IiiLabParser"
        private const val API_BASE = "https://webapi.iiilab.com"

        /** 前端 base64 两段（atob 后拼接即为签名 secret） */
        private val SECRET_PARTS = listOf("SlNuSEtRZlA=", "MUlseklRenM=")

        /** formats[].quality 的"原画"哨兵值（前端 XR=9999） */
        private const val ORIGINAL_QUALITY = 9999L

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
