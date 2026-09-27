package com.clipdownloader.download

import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import com.clipdownloader.util.LogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 小红书解析器（2026-08-17 改走 PC 端）
 *
 * 背景：移动端笔记页图片带水印；PC 端桌面页（discovery/item）直接返回无水印原图，
 * 且实况图（Live Photo）内嵌 h264 视频流。
 *
 * 解析流程：
 * 1. 从分享文本提取短链（xhslink.cn），跟随重定向拿最终笔记页 URL（含 xsec_token）
 * 2. PC UA 访问 https://www.xiaohongshu.com/discovery/item/{noteId}
 * 3. 提取 window.__INITIAL_STATE__（JS 对象，需括号配对 + 清洗 undefined/尾逗号）
 * 4. 取 noteData.data.noteData（新版）或 note.noteDetailMap[id].note（旧版）
 * 5. imageList[]：url 是无水印原图；livePhoto=true 时 stream.h264[0].masterUrl 是实况视频流
 *
 * 实况图（动图）按两个媒体返回：图片项 + 视频项，首页选择/下载栏各自独立展示。
 */
class XiaohongshuParser {

    private val TAG = "XiaohongshuParser"

    // 页面需要 webId cookie，用内存 CookieJar 自动保存
    private val cookieManager = java.net.CookieManager()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(okhttp3.JavaNetCookieJar(cookieManager))
        .build()

    suspend fun parse(shareText: String): ParsedMedia? = parseAll(shareText).firstOrNull()

    /**
     * 解析笔记中的全部媒体：
     * - 视频笔记 → 1 个视频
     * - 图文笔记 → N 个图片；其中「实况图」拆成「图片 + 视频」两个媒体
     */
    suspend fun parseAll(shareText: String): List<ParsedMedia> = withContext(Dispatchers.IO) {
        try {
            val url = PlatformParser.extractUrl(shareText) ?: return@withContext emptyList()
            LogFile.d(TAG, "开始解析，链接: $url")
            val finalUrl = resolveRedirect(url) ?: url
            LogFile.d(TAG, "重定向后: $finalUrl")

            // 预热：先访问主站获取 cookie（webId），避免笔记页返回登录墙
            warmUpCookie()

            // 提取 note ID
            val noteId = extractNoteId(finalUrl) ?: run {
                LogFile.w(TAG, "无法从 URL 提取 noteId: $finalUrl")
                return@withContext emptyList()
            }
            LogFile.d(TAG, "noteId: $noteId")

            val mediaInfo = fetchNoteInfo(noteId, finalUrl)
            if (mediaInfo == null) {
                LogFile.w(TAG, "笔记页解析失败（可能被平台风控/需要登录）noteId=$noteId")
                return@withContext emptyList()
            }

            val title = mediaInfo.optString("title")
            val author = mediaInfo.optString("author")
            val duration = mediaInfo.optLong("duration", 0)
            val videoUrl = mediaInfo.optString("video_url").ifEmpty { null }
            val result = mutableListOf<ParsedMedia>()

            LogFile.d(TAG, "parseAll: title=$title, author=$author, duration=$duration, videoUrl=${videoUrl?.take(100) ?: "null"}")

            val images = mediaInfo.optJSONArray("images")
            LogFile.d(TAG, "parseAll: images array has ${images?.length() ?: 0} items")
            if (images != null) {
                var motionCount = 0
                var imageCount = 0
                for (i in 0 until images.length()) {
                    val item = images.optJSONObject(i) ?: continue
                    val live = item.optString("live").ifEmpty { null }
                    val img = item.optString("image").ifEmpty { null }
                    when {
                        // 实况图：拆成「图片 + 视频」两个媒体，首页/下载栏各自独立
                        live != null && img != null -> {
                            motionCount++
                            result.add(
                                ParsedMedia(
                                    platform = Platform.XIAOHONGSHU,
                                    imageUrl = img,
                                    title = title,
                                    author = author,
                                    rawShareText = shareText,
                                    originalUrl = finalUrl
                                )
                            )
                            result.add(
                                ParsedMedia(
                                    platform = Platform.XIAOHONGSHU,
                                    videoUrl = live,
                                    coverUrl = img,
                                    title = title,
                                    author = author,
                                    // 实况图接口不返回时长，首页用 2s 估算；下载后探测真实值回填
                                    duration = LIVE_PHOTO_ESTIMATE_MS,
                                    rawShareText = shareText,
                                    originalUrl = finalUrl
                                )
                            )
                        }
                        img != null -> {
                            imageCount++
                            result.add(
                                ParsedMedia(
                                    platform = Platform.XIAOHONGSHU,
                                    imageUrl = img,
                                    title = title,
                                    author = author,
                                    rawShareText = shareText,
                                    originalUrl = finalUrl
                                )
                            )
                        }
                    }
                }
                LogFile.d(TAG, "图片解析统计: 实况图(拆2) $motionCount 张, 普通图 $imageCount 张")
            }

            if (videoUrl != null) {
                result.add(
                    ParsedMedia(
                        platform = Platform.XIAOHONGSHU,
                        videoUrl = videoUrl,
                        title = title,
                        author = author,
                        duration = duration,
                        rawShareText = shareText,
                        originalUrl = finalUrl
                    )
                )
            }

            LogFile.d(TAG, "解析完成，共 ${result.size} 个媒体")
            result
        } catch (e: Exception) {
            LogFile.e(TAG, "解析异常", e)
            emptyList()
        }
    }

    private fun resolveRedirect(url: String): String? {
        return try {
            val noRedirect = OkHttpClient.Builder().followRedirects(false).build()
            val req = Request.Builder().url(url)
                .header("User-Agent", PC_UA)
                .build()
            val resp = noRedirect.newCall(req).execute()
            val loc = resp.header("Location")
            resp.close()
            loc ?: url
        } catch (e: Exception) {
            LogFile.e(TAG, "重定向失败", e)
            null
        }
    }

    private fun extractNoteId(url: String): String? {
        // 格式: https://www.xiaohongshu.com/discovery/item/xxx 或 /explore/xxx
        val patterns = listOf(
            Regex("/item/(\\w+)"),
            Regex("/explore/(\\w+)"),
            Regex("/discovery/item/(\\w+)"),
            Regex("noteId=(\\w+)")
        )
        for (p in patterns) {
            p.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** 访问主站触发 Set-Cookie（webId 等），供后续笔记页请求使用 */
    private fun warmUpCookie() {
        try {
            client.newCall(
                Request.Builder().url("https://www.xiaohongshu.com/")
                    .header("User-Agent", PC_UA)
                    .build()
            ).execute().close()
        } catch (e: Exception) {
            LogFile.w(TAG, "预热 cookie 失败: ${e.message}")
        }
    }

    private fun fetchNoteInfo(noteId: String, pageUrl: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", PC_UA)
                .build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return null
            response.close()
            LogFile.d(TAG, "笔记页大小: ${html.length}")

            // 括号配对提取 __INITIAL_STATE__ 完整对象（避免正则截断）
            val stateStr = extractInitialState(html)
            if (stateStr == null) {
                LogFile.w(TAG, "页面无 __INITIAL_STATE__")
                return null
            }
            val cleaned = sanitizeJsJson(stateStr)
            val json = JSONObject(cleaned)

            // 新版结构：noteData.data.noteData（本身即是笔记对象）
            // 旧版结构：note.noteDetailMap[id].note
            val note: JSONObject? = try {
                json.optJSONObject("noteData")?.optJSONObject("data")?.optJSONObject("noteData")
            } catch (_: Exception) {
                null
            } ?: run {
                try {
                    json.optJSONObject("note")?.optJSONObject("noteDetailMap")
                        ?.optJSONObject(noteId)?.optJSONObject("note")
                } catch (_: Exception) {
                    null
                }
            }
            if (note == null) {
                LogFile.w(TAG, "INITIAL_STATE 里没有笔记数据（平台风控/需登录）")
                return null
            }

            val type = note.optString("type") // normal/video
            val title = note.optString("title", "")
            val author = note.optJSONObject("user")?.optString("nickname") ?: ""

            val video = note.optJSONObject("video")
            val rawVideoUrl = video?.optJSONObject("media")?.optJSONObject("stream")
                ?.optJSONArray("h264")?.optJSONObject(0)?.optString("masterUrl")
            val videoUrl = if (rawVideoUrl?.startsWith("http://") == true) {
                rawVideoUrl.replace("http://", "https://")
            } else {
                rawVideoUrl
            }

            // 图文笔记：imageList 里可能有多张图，且部分是「实况图」（内含视频流）
            val imageList = note.optJSONArray("imageList")
            val imageItems = JSONArray()
            var livePhotoCount = 0
            if (imageList != null) {
                for (i in 0 until imageList.length()) {
                    val item = imageList.optJSONObject(i) ?: continue
                    // PC 端图片字段是 url（无水印原图）；旧版可能是 urlDefault/urlPre
                    val staticUrl = item.optString("url")
                        .ifEmpty { item.optString("urlDefault") }
                        .ifEmpty { item.optString("urlPre") }

                    // 检查是否是实况图
                    val isLivePhoto = item.optBoolean("livePhoto")

                    // 实况图：livePhoto=true 时优先拿 h264 视频流；
                    // 部分机型/笔记的实况是 h265，相册不认，h265 兜底改走 h264 提取为空
                    val liveUrl = if (isLivePhoto) {
                        val s1 = item.optJSONObject("stream")
                            ?.optJSONArray("h264")?.optJSONObject(0)
                            ?.optString("masterUrl")
                        val s2 = item.optJSONObject("livePhoto")
                            ?.optJSONObject("media")?.optJSONObject("stream")
                            ?.optJSONArray("h264")?.optJSONObject(0)
                            ?.optString("masterUrl")
                        val s3 = item.optJSONObject("stream")
                            ?.optJSONArray("h264")?.optJSONObject(0)
                            // backupUrl 可能是 JSONArray，optString 会返回 "[..."] 垃圾串，
                            // 必须校验拿到的是 http 开头的字符串
                            ?.optString("backupUrl")?.takeIf { it.startsWith("http") }
                        var url = listOf(s1, s2, s3).firstOrNull { !it.isNullOrBlank() && it.startsWith("http") } ?: ""

                        // 重要：升级 http:// 为 https://，避免部分设备拒绝明文流量
                        if (url.startsWith("http://")) {
                            url = url.replace("http://", "https://")
                        }

                        LogFile.d(TAG, "图片[$i]: livePhoto=true, h264提取=${url.isNotBlank()}, url=${url.take(150)}")
                        if (url.isNotBlank()) livePhotoCount++
                        url
                    } else {
                        LogFile.d(TAG, "图片[$i]: livePhoto=false")
                        ""
                    }

                    if (staticUrl.isBlank() && liveUrl.isBlank()) continue
                    imageItems.put(JSONObject().apply {
                        put("image", staticUrl)
                        put("live", liveUrl)
                    })
                }
                LogFile.d(TAG, "图片列表解析完成: 共 ${imageItems.length()} 张，其中实况图 $livePhotoCount 张")
            }

            JSONObject().apply {
                put("video_url", videoUrl ?: "")
                put("images", imageItems)
                put("title", title)
                put("author", author)
                put("note_type", type)
                put("duration", video?.optLong("duration", 0) ?: 0L)
            }
        } catch (e: Exception) {
            LogFile.e(TAG, "fetchNoteInfo 异常", e)
            null
        }
    }

    /** 括号配对提取 __INITIAL_STATE__ 的完整 JSON 对象文本 */
    private fun extractInitialState(html: String): String? {
        val idx = html.indexOf("__INITIAL_STATE__")
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (j in start until html.length) {
            val c = html[j]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, j + 1)
                    }
                }
            }
        }
        return null
    }

    /** 把 JS 对象字面量清洗成合法 JSON */
    private fun sanitizeJsJson(raw: String): String {
        var s = raw.replace(":undefined", ":null")
        // 尾随逗号（转义 } 和 ]，Android 正则对未转义括号更严格）
        s = s.replace(Regex(",\\s*\\}"), "}")
        s = s.replace(Regex(",\\s*\\]"), "]")
        return s
    }

    companion object {
        private const val PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 实况图视频接口不返回时长，首页预览用 2s 估算（下载后探测真实值回填） */
        private const val LIVE_PHOTO_ESTIMATE_MS = 2000L
    }
}
