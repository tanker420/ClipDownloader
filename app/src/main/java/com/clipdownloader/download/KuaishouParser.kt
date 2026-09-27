package com.clipdownloader.download

import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 快手解析器
 *
 * 解析流程（2026-08 快手网页版改版后可行路径）：
 * 1. 从分享文本提取链接（v.kuaishou.com 短链 / m.gifshow.com / www.kuaishou.com/short-video）
 * 2. 跟随重定向拿到最终 URL，提取 photoId（快手视频/图集共用同一套 photoId）
 * 3. 用移动 UA 请求 https://m.kuaishou.com/fw/photo/{photoId}，页面 SSR 内嵌完整数据：
 *    - 视频作品：mainMvUrls[0].url 是完整 mp4 直链（含鉴权参数）
 *    - 图集作品：atlas.cdnList[0].cdn + atlas.list[] 拼出每张原图；atlas.music 是配乐
 *    - caption / userName / coverUrls 都有
 *
 * 关键点（踩坑记录）：
 * - 必须用移动 UA 请求 m.kuaishou.com 移动页：桌面 UA 访问 www.kuaishou.com/short-video 只对
 *   视频有 SSR 数据，图集会返回空壳（__APOLLO_STATE__ 为空）；移动端 fw/photo 页视频/图集都有数据。
 * - 不再走 graphql：visionVideoDetail 接口需 did cookie 养号，否则返回滑块验证码（result=400002）。
 * - 图集（atlas）和视频（mainMvUrls）结构完全不同，必须分别处理，否则图集只抓到封面。
 */
class KuaishouParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun parse(shareText: String): List<ParsedMedia> = withContext(Dispatchers.IO) {
        try {
            val shortUrl = PlatformParser.extractUrl(shareText) ?: return@withContext emptyList()
            val lower = shortUrl.lowercase()
            if (!lower.contains("kuaishou.com") && !lower.contains("gifshow.com") &&
                !lower.contains("chenzhongtech.com")) {
                return@withContext emptyList()
            }

            // 跟随重定向拿到最终 URL（快手短链可能多层跳转，最终落到 m.xxx.com/fw/photo/{id}）
            val finalUrl = resolveRedirect(shortUrl) ?: shortUrl

            // 提取 photoId：优先最终 URL，其次原始短链（某些短链重定向前就带 photoId）
            val photoId = extractVideoId(finalUrl) ?: extractVideoId(shortUrl)
                ?: run {
                    com.clipdownloader.util.LogFile.w("KuaishouParser", "无法提取 photoId: $shortUrl")
                    return@withContext emptyList()
                }

            val pageUrl = "https://m.kuaishou.com/fw/photo/$photoId"
            val html = fetchPage(pageUrl) ?: run {
                com.clipdownloader.util.LogFile.w("KuaishouParser", "移动页获取失败 photoId=$photoId")
                return@withContext emptyList()
            }

            val title = extractCaption(html) ?: ""
            val author = extractAuthor(html) ?: ""
            val cover = extractCover(html)

            val result = mutableListOf<ParsedMedia>()

            // 1) 视频作品：mainMvUrls[0].url 是完整视频地址
            val videoUrl = extractVideoUrl(html)
            if (!videoUrl.isNullOrBlank()) {
                result.add(
                    ParsedMedia(
                        platform = Platform.KUAISHOU,
                        videoUrl = videoUrl,
                        title = title,
                        author = author,
                        coverUrl = cover,
                        duration = extractDuration(html) ?: 0L,
                        rawShareText = shareText,
                        mediaId = photoId,
                        originalUrl = pageUrl
                    )
                )
            }

            // 2) 图集作品：atlas.cdnList + atlas.list 拼出每张原图；atlas.music 是配乐
            val atlas = extractAtlas(html)
            if (atlas != null) {
                val cdn = atlas.optJSONArray("cdnList")?.optJSONObject(0)?.optString("cdn")
                    ?: atlas.optJSONArray("cdn")?.optString(0) ?: ""
                val list = atlas.optJSONArray("list")
                if (cdn.isNotBlank() && list != null && list.length() > 0) {
                    for (i in 0 until list.length()) {
                        val path = list.optString(i).takeIf { it.isNotBlank() } ?: continue
                        val img = if (path.startsWith("http")) path else "https://$cdn$path"
                        result.add(
                            ParsedMedia(
                                platform = Platform.KUAISHOU,
                                imageUrl = img,
                                // 不设 coverUrl：让缩略图落到每张图自己的 imageUrl，
                                // 否则 DownloadManager 收集 coverUrls 时 distinct() 会把相同的封面去重成 1 张
                                coverUrl = img,
                                title = title,
                                author = author,
                                rawShareText = shareText,
                                mediaId = photoId,
                                originalUrl = pageUrl
                            )
                        )
                    }
                }
                // 配乐：单独作为音乐项返回
                val music = atlas.optString("music").takeIf { it.isNotBlank() }
                if (music != null) {
                    val musicCdn = atlas.optJSONArray("musicCdnList")?.optJSONObject(0)?.optString("cdn")
                        ?: cdn
                    val musicUrl = if (music.startsWith("http")) music else "https://$musicCdn$music"
                    result.add(
                        ParsedMedia(
                            platform = Platform.KUAISHOU,
                            videoUrl = musicUrl,
                            title = title.ifBlank { "背景音乐" },
                            author = author,
                            coverUrl = cover,
                            isMusic = true,
                            rawShareText = shareText,
                            mediaId = photoId,
                            originalUrl = pageUrl
                        )
                    )
                }
            }

            if (result.isEmpty()) {
                com.clipdownloader.util.LogFile.w("KuaishouParser", "未解析出媒体 photoId=$photoId")
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun resolveRedirect(url: String): String? {
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", MOBILE_UA)
                .build()
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            response.close()
            finalUrl
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchPage(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                response.body?.string()
            }
        } catch (e: Exception) {
            com.clipdownloader.util.LogFile.e("KuaishouParser", "页面获取失败", e)
            null
        }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("/fw/photo/(\\w+)"),
            Regex("/short-video/(\\w+)"),
            Regex("/fw/video/(\\w+)"),
            Regex("photoId=(\\w+)"),
            Regex("/profile/(\\w+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    /** 视频地址：mainMvUrls[0].url（完整直链）。图集此字段为空数组，不会命中。 */
    private fun extractVideoUrl(html: String): String? {
        // 不假设 cdn/url 字段紧邻或顺序：括号配对取出 mainMvUrls 数组后按 JSON 解析，
        // SSR 字段顺序调整或多塞字段时正则方案会静默失败
        val key = "\"mainMvUrls\""
        val keyIdx = html.indexOf(key)
        if (keyIdx < 0) return null
        val arrStart = html.indexOf('[', keyIdx + key.length)
        if (arrStart < 0) return null
        val arrStr = extractBracketPair(html, arrStart, '[', ']') ?: return null
        return try {
            val arr = org.json.JSONArray(arrStr)
            if (arr.length() == 0) return null
            val url = arr.optJSONObject(0)?.optString("url").orEmpty()
            url.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    /** 提取 atlas 对象（图集数据），用括号匹配拿到完整 JSON 再解析 */
    private fun extractAtlas(html: String): JSONObject? {
        val key = "\"atlas\":"
        val start = html.indexOf(key)
        if (start < 0) return null
        val braceStart = html.indexOf('{', start + key.length)
        if (braceStart < 0) return null
        val jsonStr = extractBracketPair(html, braceStart, '{', '}') ?: return null
        return try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 字符串/转义感知的括号配对：从 start（必须是 open）取到配对的 close 为止。
     * 字符串字面量内的 {} [] 不计深度——图集路径/文案里含括号字符时，
     * 裸计数会截断丢图
     */
    private fun extractBracketPair(text: String, start: Int, open: Char, close: Char): String? {
        if (start < 0 || start >= text.length || text[start] != open) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (j in start until text.length) {
            val c = text[j]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(start, j + 1)
                    }
                }
            }
        }
        return null
    }

    private fun extractCover(html: String): String? {
        // 优先 coverUrls[0].url（图集/视频通用），兜底 poster / coverUrl
        val coverUrls = Regex("\"coverUrls\"\\s*:\\s*\\[\\s*\\{\\s*\"cdn\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        coverUrls.find(html)?.groupValues?.get(1)?.let { return jsonUnescape(it) }
        val poster = Regex("\"poster\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        poster.find(html)?.groupValues?.get(1)?.let { return jsonUnescape(it) }
        val coverUrl = Regex("\"coverUrl\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return coverUrl.find(html)?.groupValues?.get(1)?.let { jsonUnescape(it) }
    }

    private fun extractCaption(html: String): String? {
        val pattern = Regex("\"caption\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return pattern.find(html)?.groupValues?.get(1)?.let { jsonUnescape(it) }?.takeIf { it.isNotBlank() }
    }

    private fun extractAuthor(html: String): String? {
        val patterns = listOf(
            Regex("\"userName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""),
            Regex("\"user_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        )
        for (pattern in patterns) {
            val v = pattern.find(html)?.groupValues?.get(1)?.let { jsonUnescape(it) }
            if (!v.isNullOrBlank() && v != "快手") return v
        }
        return null
    }

    private fun extractDuration(html: String): Long? {
        val pattern = Regex("\"duration\"\\s*:\\s*(\\d+)")
        return pattern.find(html)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** 还原 JSON 字符串转义（反斜杠 + u002F 表示斜杠，反斜杠 + uXXXX 表示中文等） */
    private fun jsonUnescape(s: String): String {
        // 无转义（裸 URL / 裸文案）：直接返回，不能用 JSONTokener 直接 nextValue，
        // 否则 readLiteral 会在 URL 的 ':' 处截断，把 https://... 误解析成 "https"
        if (!s.contains('\\')) return s
        return try {
            // 包成 JSON 字符串再解析，正确还原 \u002F / \uXXXX 等转义
            org.json.JSONTokener("\"$s\"").nextValue().toString()
        } catch (_: Exception) {
            s.replace("\\u002F", "/")
        }
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
    }
}
