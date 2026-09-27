package com.clipdownloader.download

import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import com.clipdownloader.util.LogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 海外平台本地解析器
 *
 * 2026-08-18 清理：只保留实测可用的通道，失败的全部移除。
 * - TikTok → tikwm.com 免费 API（实测通过，保留）
 *
 * 已移除（实测失败）：
 * - YouTube（Piped / Invidious 公共实例）：实例全部超时或关停
 * - Twitter/X（vxtwitter）：被 Cloudflare 拦截 403
 * - Pinterest / Instagram / Facebook（页面 og 标签直解）：登录墙，拿不到直链
 * - Cobalt 公共实例：全部改为要求 turnstile JWT 鉴权（error.api.auth.jwt.missing）
 *
 * 其余海外平台（Instagram / YouTube 等）请切到「云端解析」，
 * 云端默认通道 MaxHelper 支持 TikTok / Instagram 等海外平台。
 */
class UniversalParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun parse(shareText: String, platform: Platform): ParsedMedia? =
        parseAll(shareText, platform).firstOrNull()

    suspend fun parseAll(
        shareText: String,
        platform: Platform
    ): List<ParsedMedia> = withContext(Dispatchers.IO) {
        val url = PlatformParser.extractUrl(shareText)
            ?: return@withContext emptyList()

        val resolved = resolveShortLink(url)
        val result: List<ParsedMedia> = when (platform) {
            Platform.TIKTOK -> parseTikTok(resolved)
            else -> emptyList()
        }

        if (result.isEmpty()) {
            LogFile.w(
                "UniversalParser",
                "海外平台 ${platform.name} 本地解析无结果（可切云端解析用 MaxHelper），url=${resolved.take(80)}"
            )
        }
        result
    }

    /** 展开短链（vm.tiktok.com / vt.tiktok.com 等） */
    private fun resolveShortLink(url: String): String {
        val shortenHosts = listOf("vm.tiktok.com", "vt.tiktok.com")
        val host = try { java.net.URI(url).host ?: return url } catch (_: Exception) { return url }
        if (shortenHosts.none { host.endsWith(it) }) return url
        return try {
            val noRedirect = OkHttpClient.Builder().followRedirects(false).build()
            val resp = noRedirect.newCall(
                Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
            ).execute()
            val loc = resp.header("Location")
            resp.close()
            loc ?: url
        } catch (e: Exception) {
            LogFile.w("UniversalParser", "短链展开失败: ${e.message}")
            url
        }
    }

    // ==================== TikTok：tikwm ====================

    private fun parseTikTok(url: String): List<ParsedMedia> {
        val api = "https://www.tikwm.com/api/?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
        return try {
            val body = httpGet(api)
            val json = JSONObject(body)
            if (json.optInt("code") != 0) {
                LogFile.w("UniversalParser", "tikwm 错误: ${json.optString("msg")}")
                return emptyList()
            }
            val d = json.optJSONObject("data") ?: return emptyList()

            // 图集作品：images[] 里每张图一个媒体
            val images = d.optJSONArray("images")
            if (images != null && images.length() > 0) {
                val list = mutableListOf<ParsedMedia>()
                for (i in 0 until images.length()) {
                    val img = images.optString(i).takeIf { it.isNotBlank() } ?: continue
                    list.add(
                        ParsedMedia(
                            platform = Platform.TIKTOK,
                            imageUrl = img,
                            title = d.optString("title"),
                            author = d.optJSONObject("author")?.optString("nickname").orEmpty(),
                            rawShareText = url,
                            originalUrl = "https://www.tiktok.com/"
                        )
                    )
                }
                if (list.isNotEmpty()) return list
            }

            // 视频作品：hdplay 优先（最高画质），退回 play
            val play = d.optString("hdplay").ifEmpty { d.optString("play") }
            if (play.isBlank()) emptyList() else listOf(
                ParsedMedia(
                    platform = Platform.TIKTOK,
                    videoUrl = play,
                    coverUrl = d.optString("cover").ifEmpty { d.optString("origin_cover") }.ifEmpty { null },
                    title = d.optString("title"),
                    author = d.optJSONObject("author")?.optString("nickname").orEmpty(),
                    duration = d.optLong("duration", 0) * 1000,
                    rawShareText = url,
                    originalUrl = "https://www.tiktok.com/"
                )
            )
        } catch (e: Exception) {
            LogFile.w("UniversalParser", "tikwm 请求失败: ${e.message}")
            emptyList()
        }
    }

    private fun httpGet(url: String): String {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json")
                .build()
        ).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return body
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
