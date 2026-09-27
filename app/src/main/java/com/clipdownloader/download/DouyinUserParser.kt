package com.clipdownloader.download

import com.clipdownloader.model.MediaQuality
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 抖音博主主页解析：列出用户全部作品（「主页下载」栏目用）。
 *
 * 双通道顺序尝试（风控是间歇/地域性的，多通道互补）：
 * 1. iesdouyin v2 post API（移动 UA）：国内网络可用性高；
 * 2. share/user 页内嵌 _ROUTER_DATA JSON（与单视频分享页同路径）。
 *
 * 列表项自带 play_addr 直链与 bit_rate 清晰度档（体积），无需逐作二次解析。
 * 图文作品：images[] 每张出一个图片媒体；带内嵌视频的实况图额外出动图视频媒体。
 */
class DouyinUserParser {

    data class UserWorks(
        val author: String,
        val avatar: String,
        val total: Int,
        val works: List<ParsedMedia>
    )

    private val cookieManager = java.net.CookieManager()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(okhttp3.JavaNetCookieJar(cookieManager))
        .build()

    suspend fun listVideos(secUid: String, maxPages: Int = 20): UserWorks? = withContext(Dispatchers.IO) {
        try {
            warmUpCookie()
            registerTtwid()

            // 通道 1：v2 post API 分页
            val viaApi = tryViaApi(secUid, maxPages)
            if (viaApi != null) return@withContext viaApi

            // 通道 2：share/user 页 SSR
            val viaPage = tryViaSharePage(secUid)
            if (viaPage != null) return@withContext viaPage

            // 通道 3：www.douyin.com/user 页 RENDER_DATA（桌面 SSR，首屏约 18 个作品）
            val viaDesktop = tryViaDesktopPage(secUid)
            if (viaDesktop != null) return@withContext viaDesktop

            LogFile.w(TAG, "三个通道均未获取到作品列表 secUid=${secUid.take(16)}…")
            null
        } catch (e: Exception) {
            LogFile.e(TAG, "拉取主页作品异常", e)
            null
        }
    }

    /**
     * 注册 ttwid 设备令牌（字节跳动统一风控放行凭证）：
     * 无 ttwid 时 v2 post API 返回 status=1、share 页不吐 _ROUTER_DATA。
     * 注册成功后种到 iesdouyin/douyin 两个域，两个通道共用。
     */
    private fun registerTtwid() {
        try {
            val hasTtwid = cookieManager.cookieStore.cookies.any { it.name == "ttwid" }
            if (hasTtwid) return
            val body = JSONObject().apply {
                put("region", "cn")
                put("aid", 1768)
                put("needFid", false)
                put("service", "www.ixigua.com")
                put("migrate_info", JSONObject().apply {
                    put("ticket", ""); put("source", "node")
                })
                put("cbUrlProtocol", "https")
                put("union", true)
            }
            client.newCall(
                Request.Builder().url("https://ttwid.bytedance.com/ttwid/union/register/")
                    .header("User-Agent", MOBILE_UA)
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            ).execute().use { resp ->
                val setCookie = resp.header("Set-Cookie") ?: return
                val ttwid = setCookie.substringAfter("ttwid=").substringBefore(";").takeIf { it.isNotBlank() } ?: return
                for (domain in listOf(".iesdouyin.com", ".douyin.com")) {
                    val ck = java.net.HttpCookie("ttwid", ttwid)
                    ck.domain = domain
                    ck.path = "/"
                    cookieManager.cookieStore.add(
                        java.net.URI(if (domain.contains("ies")) "https://www.iesdouyin.com" else "https://www.douyin.com"),
                        ck
                    )
                }
                LogFile.d(TAG, "ttwid 注册成功（len=${ttwid.length}）")
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "ttwid 注册失败: ${e.message}")
        }
    }

    // ==================== 通道 1：v2 post API ====================

    private fun tryViaApi(secUid: String, maxPages: Int): UserWorks? {
        val all = mutableListOf<JSONObject>()
        var author = ""
        var cursor = 0L
        var page = 1
        var hasMore = true
        while (hasMore && page <= maxPages) {
            val url = "https://www.iesdouyin.com/web/api/v2/user/post/" +
                "?sec_uid=${URLEncoder.encode(secUid, "UTF-8")}" +
                "&count=20&max_cursor=$cursor&aid=1128&_signature=_02B4Z6wo00f01"
            val json = httpGetJson(url, mobileUa = true) ?: run {
                // 分页中途失败不能丢弃已取数据：有作品就返回部分结果
                LogFile.w(TAG, "v2 post API 第 $page 页请求失败，返回已取 ${all.size} 个作品")
                return if (all.isEmpty()) null else convert(all, author)
            }
            val status = json.optInt("status_code", -1)
            if (status != 0 && all.isEmpty()) {
                LogFile.w(TAG, "v2 post API status=$status")
                return null
            }
            val list = json.optJSONArray("aweme_list") ?: JSONArray()
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { all.add(it) }
            if (author.isBlank()) {
                author = list.optJSONObject(0)?.optJSONObject("author")?.optString("nickname").orEmpty()
                    .ifBlank { json.optJSONObject("user")?.optString("nickname").orEmpty() }
            }
            hasMore = json.optInt("has_more", 0) == 1
            cursor = json.optLong("max_cursor", 0L)
            page++
        }
        if (all.isEmpty()) return null
        LogFile.d(TAG, "v2 post API：${all.size} 个作品 · $author")
        return convert(all, author)
    }

    // ==================== 通道 2：share/user 页 SSR ====================

    private fun tryViaSharePage(secUid: String): UserWorks? {
        val html = try {
            val req = Request.Builder().url("https://www.iesdouyin.com/share/user/$secUid")
                .header("User-Agent", MOBILE_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "share/user 页请求失败: ${e.message}")
            return null
        }
        // 括号配对提取 _ROUTER_DATA，不用非贪婪正则——
        // desc 含字面 </script> 或 {} 时正则会在错误位置截断
        val routerRaw = extractRouterData(html)
        val items = mutableListOf<JSONObject>()
        var author = ""
        if (routerRaw != null) {
            val root = try { JSONObject(routerRaw) } catch (_: Exception) { null }
            // 递归找 aweme_list 数组
            val lists = mutableListOf<JSONArray>()
            if (root != null) findAwemeLists(root, lists)
            lists.forEach { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let {
                        items.add(it)
                        if (author.isBlank()) author = it.optJSONObject("author")?.optString("nickname").orEmpty()
                    }
                }
            }
        }
        if (items.isEmpty()) {
            // 容错：站点改版换了解析包裹（RENDER_DATA / 其他内嵌 JSON）时，
            // 直接扫描全部内嵌 script，按「元素带 aweme_id 的数组」特征提取
            collectFromInlineScripts(html, items)
            for (it in items) {
                author = it.optJSONObject("author")?.optString("nickname").orEmpty()
                if (author.isNotBlank()) break
            }
        }
        if (items.isEmpty()) {
            LogFile.w(TAG, "share/user 页无作品数据（可能被风控）")
            return null
        }
        LogFile.d(TAG, "share/user SSR：${items.size} 个作品 · $author")
        return convert(items, author)
    }

    /** 扫描页面全部内嵌 <script>，解析其中的 JSON 并收集带 aweme_id 的对象数组 */
    private fun collectFromInlineScripts(html: String, out: MutableList<JSONObject>) {
        for (match in SCRIPT_TAG_REGEX.findAll(html)) {
            var body = match.groupValues[1].trim()
            if (!body.contains("aweme_id") || body.length > 800_000) continue
            if (!body.startsWith("{") && !body.startsWith("[")) {
                val eq = body.indexOf('=')
                if (eq !in 1..200) continue
                body = body.substring(eq + 1).trim().trimEnd(';')
            }
            val parsed: Any? = try {
                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) collectAwemeArrays(arr.opt(i), out)
                    continue
                } else JSONObject(body)
            } catch (_: Exception) {
                continue
            }
            collectAwemeArrays(parsed, out)
        }
    }

    private fun findAwemeLists(node: Any?, out: MutableList<JSONArray>) {
        when (node) {
            is JSONObject -> {
                node.optJSONArray("aweme_list")?.let { out.add(it) }
                for (k in node.keys()) findAwemeLists(node.opt(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) findAwemeLists(node.opt(i), out)
        }
    }

    // ==================== 通道 3：www.douyin.com/user 桌面页 RENDER_DATA ====================

    /**
     * 桌面站用户页 SSR：HTML 里 <script id="RENDER_DATA"> 为 URL 编码的 JSON，
     * 内嵌首屏作品数组（元素带 aweme_id）。结构无固定 key（模块名是哈希），
     * 用「元素为带 aweme_id 的对象」的数组特征递归查找，对改版容错。
     * 反爬壳页（JS 虚拟机挑战）没有 RENDER_DATA，直接失败回落。
     */
    private fun tryViaDesktopPage(secUid: String): UserWorks? {
        val html = try {
            client.newCall(
                Request.Builder().url("https://www.douyin.com/user/$secUid")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://www.douyin.com/")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "桌面页请求失败: ${e.message}")
            return null
        }
        val m = RENDER_DATA_REGEX.find(html) ?: run {
            LogFile.w(TAG, "桌面页无 RENDER_DATA（反爬壳或未登录态）")
            return null
        }
        val decoded = try {
            java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        } catch (_: Exception) {
            return null
        }
        val root = try { JSONObject(decoded) } catch (_: Exception) { return null }
        // 递归收集「元素为带 aweme_id 的对象」的数组
        val items = mutableListOf<JSONObject>()
        collectAwemeArrays(root, items)
        if (items.isEmpty()) return null
        var author = ""
        for (it in items) {
            author = it.optJSONObject("author")?.optString("nickname").orEmpty()
            if (author.isNotBlank()) break
        }
        LogFile.d(TAG, "桌面页 RENDER_DATA：${items.size} 个作品 · $author")
        return convert(items, author)
    }

    /** 递归查找「元素为带 aweme_id 的对象」的 JSON 数组（桌面页模块 key 是哈希，结构不固定） */
    private fun collectAwemeArrays(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> for (k in node.keys()) collectAwemeArrays(node.opt(k), out)
            is JSONArray -> {
                val first = node.optJSONObject(0)
                if (first != null && first.has("aweme_id")) {
                    for (i in 0 until node.length()) node.optJSONObject(i)?.let { out.add(it) }
                } else {
                    for (i in 0 until node.length()) collectAwemeArrays(node.opt(i), out)
                }
            }
        }
    }

    // ==================== 公共转换 ====================

    /** aweme JSON → ParsedMedia（视频直链 + bit_rate 画质档；图文出图片/动图） */
    private fun convert(items: List<JSONObject>, author: String): UserWorks {
        val avatar = items.firstNotNullOfOrNull { it ->
            it.optJSONObject("author")?.optJSONObject("avatar_thumb")
                ?.optJSONArray("url_list")?.optString(0)?.takeIf { u -> u.startsWith("http") }
        }.orEmpty()
        val works = mutableListOf<ParsedMedia>()
        items.forEachIndexed { idx, item ->
            val awemeId = item.optString("aweme_id")
            if (awemeId.isBlank()) return@forEachIndexed
            val title = item.optString("desc")
            val who = item.optJSONObject("author")?.optString("nickname")?.takeIf { it.isNotBlank() } ?: author
            val video = item.optJSONObject("video")

            // bit_rate 各档清晰度（与单视频解析同规则）
            val qualities = mutableListOf<MediaQuality>()
            video?.optJSONArray("bit_rate")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val br = arr.optJSONObject(i) ?: continue
                    val u = resolvePlayUrl(br.optJSONObject("play_addr"))
                    if (u.isBlank() || isAudioUrl(u)) continue
                    val gear = br.optString("gear_name")
                    val height = MediaQuality.heightOf(gear)
                    val size = br.optLong("data_size", 0L).takeIf { it > 0 }
                        ?: br.optJSONObject("play_addr")?.optLong("data_size", 0L) ?: 0L
                    qualities.add(
                        MediaQuality(
                            label = if (height > 0) "${height}p" else gear.ifBlank { "默认" },
                            url = u,
                            sizeBytes = size,
                            height = height
                        )
                    )
                }
            }
            val sorted = qualities
                .sortedWith(compareByDescending<MediaQuality> { it.height }.thenByDescending { it.sizeBytes })
                .distinctBy { it.url }

            fun base() = ParsedMedia(
                platform = Platform.DOUYIN,
                mediaId = awemeId,
                title = title,
                author = who,
                rawShareText = "https://www.douyin.com/video/$awemeId",
                originalUrl = "https://www.douyin.com/video/$awemeId"
            )

            // 图文作品
            val images = item.optJSONArray("images")
            if (images != null && images.length() > 0) {
                for (i in 0 until images.length()) {
                    val img = images.optJSONObject(i) ?: continue
                    val staticUrl = img.optJSONArray("url_list")?.optString(0).orEmpty()
                    val liveUrl = resolvePlayUrl(img.optJSONObject("video")?.optJSONObject("play_addr"))
                        .ifBlank { resolvePlayUrl(img.optJSONObject("video")?.optJSONObject("play_addr_h264")) }
                        .replace("/playwm/", "/play/")
                    if (staticUrl.isNotBlank()) works.add(base().copy(imageUrl = staticUrl))
                    if (liveUrl.isNotBlank() && !isAudioUrl(liveUrl)) {
                        works.add(base().copy(videoUrl = liveUrl, duration = LIVE_PHOTO_ESTIMATE_MS))
                    }
                }
                return@forEachIndexed
            }

            // 视频作品
            val playUrl = resolvePlayUrl(video?.optJSONObject("play_addr"))
                .ifBlank { resolvePlayUrl(video?.optJSONObject("play_addr_h265")) }
                .ifBlank { resolvePlayUrl(video?.optJSONObject("play_addr_264")) }
            if (playUrl.isBlank() || isAudioUrl(playUrl)) return@forEachIndexed
            val best = sorted.firstOrNull()?.url?.takeIf { it.startsWith("http") } ?: playUrl
            val cover = video?.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0)?.ifBlank { null }
            val duration = video?.optLong("duration", 0L) ?: 0L
            works.add(
                base().copy(
                    videoUrl = best,
                    qualities = sorted.map { q -> q.copy(url = q.url.replace("/playwm/", "/play/")) },
                    coverUrl = cover,
                    duration = duration
                )
            )
        }
        // 作品数按 mediaId 去重计数
        val total = works.map { it.mediaId }.distinct().size
        return UserWorks(author, avatar, total, works)
    }

    /** play_addr → 可下载直链（uri 即 CDN 直链的 2026 新格式；其余取 url_list[0] 去水印路径） */
    private fun resolvePlayUrl(playAddr: JSONObject?): String {
        if (playAddr == null) return ""
        val uri = playAddr.optString("uri").trim()
        val url0 = playAddr.optJSONArray("url_list")?.optString(0)?.trim().orEmpty()
        return when {
            url0.contains("video_id=http") && uri.startsWith("http") -> uri
            url0.isNotBlank() && !isAudioUrl(url0) -> url0.replace("/playwm/", "/play/")
            uri.startsWith("http") && !isAudioUrl(uri) -> uri
            else -> ""
        }
    }

    private fun isAudioUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac") ||
            lower.contains(".wav") || lower.contains("music") || lower.contains("ies-music")
    }

    /** 访问 iesdouyin 主页种 ttwid（分享接口没有它常返回空数据） */
    private fun warmUpCookie() {
        try {
            client.newCall(
                Request.Builder().url("https://www.iesdouyin.com/")
                    .header("User-Agent", MOBILE_UA)
                    .build()
            ).execute().close()
        } catch (_: Exception) {
        }
    }

    private fun httpGetJson(url: String, mobileUa: Boolean): JSONObject? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", if (mobileUa) MOBILE_UA else DESKTOP_UA)
                .header("Referer", "https://www.iesdouyin.com/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogFile.w(TAG, "HTTP ${resp.code} · ${url.take(90)}")
                    return null
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "GET 异常 ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DouyinUserParser"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 实况图接口不返回时长，预览用 2s 估算 */
        private const val LIVE_PHOTO_ESTIMATE_MS = 2000L

        private val RENDER_DATA_REGEX = Regex("""<script id="RENDER_DATA"[^>]*>([^<]+)</script>""")

        private val SCRIPT_TAG_REGEX = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }

    /** 定位 window._ROUTER_DATA 赋值，从首个 { 起做字符串/转义感知的括号配对 */
    private fun extractRouterData(html: String): String? {
        val keyword = "_ROUTER_DATA"
        var idx = html.indexOf(keyword)
        while (idx >= 0) {
            val eq = html.indexOf('=', idx + keyword.length)
            if (eq > 0 && eq - (idx + keyword.length) <= 4) {
                val start = html.indexOf('{', eq)
                if (start > 0) {
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
                }
            }
            idx = html.indexOf(keyword, idx + keyword.length)
        }
        return null
    }
}
