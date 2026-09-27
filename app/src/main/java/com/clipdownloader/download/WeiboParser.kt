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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 微博解析器（2026-08-17 重写：访客授信 + 双端）
 *
 * 背景：微博无 cookie 时所有入口（weibo.com / m.weibo.cn / ajax / api.weibo.com 老接口）
 * 全部 302 到 passport visitor；api.weibo.com 的 source=2111606799 已失效（400）。
 * 必须先模拟访客授信流程拿到 SUB cookie，再解析。
 *
 * 访客授信（进程内缓存，失效自动重取）：
 * 1. GET passport.weibo.com/visitor/genvisitor?cb=gen_callback&fp=&entry=miniblog → JSON 里取 data.tid
 * 2. GET passport.weibo.com/visitor/visitor?a=incarnate&t={tid}&w=3&c=100&gc=&cb=cross_domain&from=weibo
 *    → JSON 响应体里取 data.sub / data.subp（注意不在 Set-Cookie，需手动拼 Cookie 头）
 *
 * 双端（设置「微博解析端」切换，默认 pc）：
 * - pc：PC UA + SUB → weibo.com/ajax/statuses/show?id={mid} → 完整 JSON
 *       图片 pic_infos[pid]（original/largest/mw2000）；实况图 pic_infos[pid].type=='livephoto'，
 *       视频直链在 video 字段（livephoto.us.sinaimg.cn/xxx.mov?Expires&ssig）
 * - mobile：移动 UA + SUB → m.weibo.cn/detail/{mid} → 页面 $render_data[0].status
 *       pics[]（large=mw2000 原图）；live_photo_video_data{pid→usid} 标记实况图，
 *       视频走 video.weibo.com/media/play?livephoto=... 播放链接
 *
 * 实况图（动图）按两个媒体返回：图片项 + 视频项，首页选择/下载栏各自独立展示。
 */
class WeiboParser(private val endpoint: String = "pc") {

    private val TAG = "WeiboParser"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        // 手动设置的 Cookie 头（含访客 SUB）会跟随重定向被带到外域——
        // OkHttp 只自动剥离 Authorization。网络拦截器在每个跳转 hop 检查目标域名，
        // 非微博域一律剥掉 Cookie，防止 SUB 外泄
        .addNetworkInterceptor { chain ->
            var request = chain.request()
            if (request.header("Cookie") != null) {
                val host = request.url.host
                if (!host.endsWith("weibo.com") && !host.endsWith("weibo.cn")) {
                    request = request.newBuilder().removeHeader("Cookie").build()
                }
            }
            chain.proceed(request)
        }
        .build()

    // ==================== 访客 SUB（进程内缓存） ====================

    private var cachedSub = ""
    private var cachedSubp = ""

    /** 确保有可用的访客 SUB/SUBP；无则走 genvisitor→incarnate 授权 */
    private fun ensureVisitorCookie(): Boolean {
        if (cachedSub.isNotBlank()) return true
        return try {
            // 1) genvisitor 拿 tid
            val genUrl = "https://passport.weibo.com/visitor/genvisitor" +
                "?cb=gen_callback&fp=&entry=miniblog" +
                "&url=" + URLEncoder.encode("https://weibo.com/", "UTF-8")
            val genBody = client.newCall(
                Request.Builder().url(genUrl).header("User-Agent", PC_UA).build()
            ).execute().use { it.body?.string().orEmpty() }
            val tid = Regex("\"tid\":\"([^\"]+)\"").find(genBody)?.groupValues?.get(1)
            if (tid.isNullOrBlank()) {
                LogFile.w(TAG, "genvisitor 未返回 tid: ${genBody.take(200)}")
                return false
            }

            // 2) incarnate 拿 sub/subp
            val incUrl = "https://passport.weibo.com/visitor/visitor" +
                "?a=incarnate&t=$tid&w=3&c=100&gc=&cb=cross_domain&from=weibo"
            val incBody = client.newCall(
                Request.Builder().url(incUrl)
                    .header("User-Agent", PC_UA)
                    .header("Referer", "https://passport.weibo.com/visitor/visitor")
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
            cachedSub = Regex("\"sub\":\"([^\"]+)\"").find(incBody)?.groupValues?.get(1) ?: ""
            cachedSubp = Regex("\"subp\":\"([^\"]+)\"").find(incBody)?.groupValues?.get(1) ?: ""
            LogFile.d(TAG, "访客授权完成: sub=${cachedSub.take(20)}... subp=${cachedSubp.take(20)}...")
            cachedSub.isNotBlank()
        } catch (e: Exception) {
            LogFile.e(TAG, "访客授权失败", e)
            false
        }
    }

    private fun cookieHeader(): String = "SUB=$cachedSub; SUBP=$cachedSubp"

    // ==================== 解析入口 ====================

    suspend fun parse(shareText: String): ParsedMedia? = parseAll(shareText).firstOrNull()

    suspend fun parseAll(shareText: String): List<ParsedMedia> = withContext(Dispatchers.IO) {
        try {
            val url = PlatformParser.extractUrl(shareText) ?: return@withContext emptyList()
            LogFile.d(TAG, "解析开始 url=$url endpoint=$endpoint")

            // 优先直接从原始链接提取 mid：无 cookie 时 weibo.com 会把详情页 302 到
            // passport.weibo.com/visitor/visitor?url=...，重定向后的 URL 无法提取 mid
            // （兜底数字正则会先匹配到 uid），所以先试原始 URL
            var mid = extractMid(url)

            // 原始链接不是标准格式（如 t.cn 短链）：先访客授权，再带 SUB 跟随重定向
            if (mid == null) {
                ensureVisitorCookie()
                val finalUrl = resolveRedirect(url) ?: url
                mid = extractMid(finalUrl)
                LogFile.d(TAG, "从重定向后 URL 提取 mid=$mid")
            }
            if (mid == null) {
                LogFile.w(TAG, "无法提取 mid: $url")
                return@withContext emptyList()
            }
            LogFile.d(TAG, "mid=$mid")

            if (!ensureVisitorCookie()) {
                LogFile.w(TAG, "访客授权失败，无法解析")
                return@withContext emptyList()
            }

            val media: List<ParsedMedia> = if (endpoint == "mobile") {
                parseFromMobilePage(mid)
            } else {
                parseFromPcApi(mid)
            }
            media.map { it.copy(rawShareText = shareText, originalUrl = url) }
        } catch (e: Exception) {
            LogFile.e(TAG, "解析异常", e)
            emptyList()
        }
    }

    private fun resolveRedirect(url: String): String? {
        return try {
            val builder = Request.Builder().url(url).header("User-Agent", PC_UA)
            // 已有访客 SUB 时带上，短链才能正常重定向到真实详情页
            if (cachedSub.isNotBlank()) builder.header("Cookie", cookieHeader())
            val req = builder.build()
            val resp = client.newCall(req).execute()
            val finalUrl = resp.request.url.toString()
            resp.close()
            finalUrl
        } catch (e: Exception) { null }
    }

    private fun extractMid(url: String): String? {
        val patterns = listOf(
            // 标准详情页 / 移动端详情页
            Regex("weibo\\.com/\\d+/(\\d{10,})"),
            Regex("m\\.weibo\\.cn/(?:detail|status)/(\\d{10,})"),
            // 兜底：仅当出现 16 位以上的 mid 形态时才使用（uid 通常 10 位，
            // 避免从 passport 参数里误匹配到 uid）
            Regex("(\\d{16,})")
        )
        for (p in patterns) {
            p.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    // ==================== PC 端：weibo.com/ajax/statuses/show ====================

    private fun parseFromPcApi(mid: String): List<ParsedMedia> {
        val apiUrl = "https://weibo.com/ajax/statuses/show?id=$mid"
        // 访客 SUB 有有效期：过期后 ajax 返回 401/403，
        // 清缓存重取一次再试，不能卡死在旧 SUB 上直到进程重启
        repeat(2) { attempt ->
            val body = try {
                client.newCall(
                    Request.Builder().url(apiUrl)
                        .header("User-Agent", PC_UA)
                        .header("Referer", "https://weibo.com/")
                        .header("Cookie", cookieHeader())
                        .build()
                ).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        LogFile.w(TAG, "ajax/statuses/show HTTP ${resp.code}，SUB 疑似过期，重取访客授权")
                        cachedSub = ""
                        cachedSubp = ""
                        if (attempt == 0 && ensureVisitorCookie()) return@repeat
                        return emptyList()
                    }
                    if (!resp.isSuccessful) {
                        LogFile.w(TAG, "ajax/statuses/show HTTP ${resp.code}")
                        return emptyList()
                    }
                    resp.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                LogFile.e(TAG, "ajax 请求异常", e)
                return emptyList()
            }
            val json = try { JSONObject(body) } catch (e: Exception) {
                LogFile.e(TAG, "ajax 响应非 JSON", e); return emptyList()
            }
            return buildMediaFromStatus(json)
        }
        return emptyList()
    }

    /** 从微博 status JSON 提取媒体（PC ajax 与移动端数据结构不同，分开处理） */
    private fun buildMediaFromStatus(status: JSONObject): List<ParsedMedia> {
        val title = status.optString("text_raw").ifEmpty { status.optString("text") }
        val author = status.optJSONObject("user")?.optString("screen_name") ?: ""
        val result = mutableListOf<ParsedMedia>()

        // pic_infos：每张图的完整信息（含多尺寸 + 实况标记）
        val picInfos = status.optJSONObject("pic_infos")
        val picIds = status.optJSONArray("pic_ids")
        if (picInfos != null && picIds != null) {
            for (i in 0 until picIds.length()) {
                val pid = picIds.optString(i)
                val info = picInfos.optJSONObject(pid) ?: continue
                val isLive = info.optString("type") == "livephoto"
                val staticUrl = firstPicUrl(info) ?: continue
                if (isLive) {
                    // 实况图：拆「图片 + 视频」两个媒体
                    val live = info.optString("video").ifEmpty {
                        // 兜底 video_hd（video.weibo.com/media/play?livephoto=...）
                        info.optString("video_hd")
                    }
                    result.add(imageMedia(Platform.WEIBO, staticUrl, title, author))
                    if (live.isNotBlank()) {
                        result.add(videoMedia(Platform.WEIBO, live, staticUrl, title, author))
                    }
                } else {
                    result.add(imageMedia(Platform.WEIBO, staticUrl, title, author))
                }
            }
        }

        // 视频微博：page_info.type == video
        if (result.isEmpty()) {
            val pi = status.optJSONObject("page_info")
            if (pi?.optString("type") == "video") {
                val mi = pi.optJSONObject("media_info") ?: JSONObject()
                val video = listOf(
                    "stream_url_hd", "stream_url", "mp4_720p_mp4", "mp4_hd_mp4", "mp4_ld_mp4"
                ).firstNotNullOfOrNull { k -> mi.optString(k).takeIf { it.isNotBlank() } }
                val cover = mi.optString("cover_img").ifEmpty {
                    pi.optJSONObject("page_pic")?.optString("url").orEmpty()
                }
                if (video != null) {
                    result.add(
                        ParsedMedia(
                            platform = Platform.WEIBO,
                            videoUrl = video,
                            coverUrl = cover.ifBlank { null },
                            title = title,
                            author = author,
                            duration = mi.optLong("duration", 0) * 1000
                        )
                    )
                }
            }
        }

        LogFile.d(TAG, "PC 端解析完成：${result.size} 个媒体（实况图已拆双媒体）")
        return result
    }

    /** 取图片最优尺寸：original > largest > mw2000 > large */
    private fun firstPicUrl(info: JSONObject): String? {
        for (k in listOf("original", "largest", "mw2000", "large")) {
            val u = info.optJSONObject(k)?.optString("url")?.takeIf { it.isNotBlank() }
            if (u != null) return u
        }
        return null
    }

    // ==================== 移动端：m.weibo.cn/detail ====================

    private fun parseFromMobilePage(mid: String): List<ParsedMedia> {
        val html = try {
            client.newCall(
                Request.Builder()
                    .url("https://m.weibo.cn/detail/$mid")
                    .header("User-Agent", MOBILE_UA)
                    .header("Cookie", cookieHeader())
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogFile.w(TAG, "m.weibo.cn/detail HTTP ${resp.code}")
                    return emptyList()
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            LogFile.e(TAG, "移动端页面请求异常", e)
            return emptyList()
        }

        // 提取 $render_data 数组（括号配对，跨行赋值）
        val idx = html.indexOf("\$render_data = ")
        if (idx < 0) {
            LogFile.w(TAG, "移动端页面无 render_data")
            return emptyList()
        }
        val arrStr = extractBracket(html, html.indexOf('[', idx)) ?: return emptyList()
        val arr = try { JSONArray(arrStr) } catch (e: Exception) {
            LogFile.e(TAG, "render_data 解析失败", e); return emptyList()
        }
        val status = arr.optJSONObject(0)?.optJSONObject("status") ?: run {
            LogFile.w(TAG, "render_data 无 status")
            return emptyList()
        }
        return buildMediaFromMobileStatus(status)
    }

    private fun buildMediaFromMobileStatus(status: JSONObject): List<ParsedMedia> {
        val title = status.optString("text_raw").ifEmpty { status.optString("text") }
        val author = status.optJSONObject("user")?.optString("screen_name") ?: ""
        val result = mutableListOf<ParsedMedia>()

        // pics：每张图（large 是 mw2000 原图）
        val pics = status.optJSONArray("pics")
        val pidToUrl = LinkedHashMap<String, String>()
        if (pics != null) {
            for (i in 0 until pics.length()) {
                val p = pics.optJSONObject(i) ?: continue
                val pid = p.optString("pid")
                val url = p.optJSONObject("large")?.optString("url")
                    ?: p.optString("url")
                if (pid.isNotBlank() && url.isNotBlank()) {
                    pidToUrl[pid] = url
                    // 先假定全是普通图，实况图后面替换
                    result.add(imageMedia(Platform.WEIBO, url, title, author))
                }
            }
        }

        // live_photo_video_data：pid → usid（权威实况映射）
        val liveData = status.optJSONObject("live_photo_video_data")
        if (liveData != null && pidToUrl.isNotEmpty()) {
            val liveUsid = mutableMapOf<String, String>() // pid -> usid
            val it = liveData.keys()
            while (it.hasNext()) {
                val pid = it.next()
                val usid = liveData.optJSONObject(pid)?.optString("usid")?.takeIf { u -> u.isNotBlank() }
                if (usid != null) liveUsid[pid] = usid
            }
            if (liveUsid.isNotEmpty()) {
                // 移除刚才按普通图加入的实况图，替换为「图片 + 视频」
                result.clear()
                for ((pid, url) in pidToUrl) {
                    val usid = liveUsid[pid]
                    if (usid != null) {
                        val livePlay = "https://video.weibo.com/media/play?livephoto=" +
                            URLEncoder.encode("https://livephoto.us.sinaimg.cn/$usid.mov", "UTF-8")
                        result.add(imageMedia(Platform.WEIBO, url, title, author))
                        result.add(videoMedia(Platform.WEIBO, livePlay, url, title, author))
                    } else {
                        result.add(imageMedia(Platform.WEIBO, url, title, author))
                    }
                }
            }
        }

        // 视频微博
        if (result.isEmpty()) {
            val video = status.optJSONObject("video")
            val stream = video?.optJSONObject("media")?.optJSONObject("stream")
            val url = stream?.optJSONArray("h264")?.optJSONObject(0)?.optString("masterUrl")
            if (!url.isNullOrBlank()) {
                result.add(
                    ParsedMedia(
                        platform = Platform.WEIBO,
                        videoUrl = url,
                        coverUrl = pidToUrl.values.firstOrNull(),
                        title = title,
                        author = author,
                        duration = video.optLong("duration", 0) * 1000
                    )
                )
            }
        }

        LogFile.d(TAG, "移动端解析完成：${result.size} 个媒体")
        return result
    }

    // ==================== 工具 ====================

    private fun imageMedia(
        platform: Platform, imageUrl: String, title: String, author: String
    ): ParsedMedia = ParsedMedia(
        platform = platform,
        imageUrl = imageUrl,
        title = title,
        author = author
    )

    private fun videoMedia(
        platform: Platform, videoUrl: String, cover: String, title: String, author: String
    ): ParsedMedia = ParsedMedia(
        platform = platform,
        videoUrl = videoUrl,
        coverUrl = cover,
        title = title,
        author = author,
        // 实况图接口不返回时长，给 2s 估算值让首页缩略图能显示时长角标；
        // 下载后 DownloadManager 会探测真实时长并回填下载栏
        duration = LIVE_PHOTO_ESTIMATE_MS
    )

    /** 括号配对提取从 start 开始的完整数组/对象文本 */
    private fun extractBracket(s: String, start: Int): String? {
        if (start < 0) return null
        val open = s[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inStr = false
        var esc = false
        for (j in start until s.length) {
            val c = s[j]
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
                        if (depth == 0) return s.substring(start, j + 1)
                    }
                }
            }
        }
        return null
    }

    companion object {
        private const val PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

        /** 实况图视频接口不返回时长，首页预览用 2s 估算（下载后探测真实值回填） */
        private const val LIVE_PHOTO_ESTIMATE_MS = 2000L
    }
}
