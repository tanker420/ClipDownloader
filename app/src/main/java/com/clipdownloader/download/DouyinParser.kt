package com.clipdownloader.download

import com.clipdownloader.model.MediaQuality
import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 抖音视频解析器（2026-08 重写）
 *
 * 背景：iesdouyin web API（aweme/v1/web/aweme/detail）已被抖音风控（裸调 403 blocked），
 * 改为解析分享页方案：
 * 1. 从分享文本提取短链 (v.douyin.com/xxx)
 * 2. 访问短链获取最终 URL / 页面，提取视频 ID（aweme_id）
 * 3. 访问 https://www.iesdouyin.com/share/video/{id}/ （mobile UA）
 *    页面内嵌 __NEXT_DATA__ 或 window._ROUTER_DATA JSON，递归找到 videoInfoRes.item_list[0]
 * 4. 提取 playwm 地址（play_addr.url_list[0]），把 /playwm/ 替换为 /play/ 得到无水印地址
 * 5. 图集作品：images[] 每项含静态图 url_list[0] 与实况动图 video.play_addr.url_list[0]
 */
class DouyinParser {

    /** 内存 Cookie 管理器：访问主站获取的 cookie 会在此保存，供后续分享页请求使用 */
    private val cookieManager = java.net.CookieManager()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(okhttp3.JavaNetCookieJar(cookieManager))
        .build()

    suspend fun parse(shareText: String): ParsedMedia? = parseAll(shareText).firstOrNull()

    /**
     * 解析出链接中的全部媒体：
     * - 单视频作品 → 1 个视频（去水印）
     * - 图集作品 → N 个条目，其中「实况/动图」条目会带上内嵌视频地址
     */
    suspend fun parseAll(shareText: String): List<ParsedMedia> = withContext(Dispatchers.IO) {
        try {
            // 提取短链接
            val shortUrl = PlatformParser.extractUrl(shareText) ?: return@withContext emptyList()
            if (!shortUrl.contains("douyin.com")) return@withContext emptyList()

            // 预热：先拿 ttwid 等 cookie，否则分享页不内嵌视频数据；短链解析也会受益
            warmUpCookie()

            // 从短链解析出视频 ID
            val videoId = resolveVideoId(shortUrl) ?: run {
                com.clipdownloader.util.LogFile.w("DouyinParser", "无法从短链解析视频 ID")
                return@withContext emptyList()
            }

            // 抓分享页并解析媒体信息
            val mediaInfo = fetchVideoInfo(videoId) ?: run {
                com.clipdownloader.util.LogFile.w("DouyinParser", "分享页解析失败 videoId=$videoId")
                return@withContext emptyList()
            }

            val awemeId = mediaInfo.optString("video_id")
            // music.duration 抖音给的是秒（如 29），毫秒位才是毫秒
            val rawMusicDur = mediaInfo.optLong("music_duration", 0)
            val musicDuration = if (rawMusicDur in 1..600) rawMusicDur * 1000 else rawMusicDur
            val title = mediaInfo.optString("title")
            val author = mediaInfo.optString("author")
            val cover = mediaInfo.optString("cover_url").ifEmpty { null }
            val duration = mediaInfo.optLong("duration", 0)

            val result = mutableListOf<ParsedMedia>()

            // 图集（可能含多张图片 / 实况动图）
            val images = mediaInfo.optJSONArray("images")
            val videoUrl = mediaInfo.optString("video_url").ifEmpty { null }
            
            // 日志：记录解析出的视频 URL
            com.clipdownloader.util.LogFile.d(
                "DouyinParser",
                "parseAll: videoUrl=${videoUrl?.take(200) ?: "null"}, images=${images?.length() ?: 0}"
            )
            
            if (images != null && images.length() > 0) {
                var liveCount = 0
                for (i in 0 until images.length()) {
                    val item = images.optJSONObject(i) ?: continue
                    val liveUrl = item.optString("live").ifEmpty { null }
                    val imgUrl = item.optString("image").ifEmpty { null }
                    if (liveUrl != null) liveCount++
                    when {
                        // 实况图：拆成「图片 + 视频」两个媒体（与小红书/微博一致），
                        // 首页选择/下载栏各自独立展示
                        liveUrl != null && imgUrl != null -> {
                            result.add(
                                ParsedMedia(
                                    platform = Platform.DOUYIN,
                                    mediaId = awemeId,
                                    imageUrl = imgUrl,
                                    title = title,
                                    author = author,
                                    rawShareText = shareText,
                                    originalUrl = shortUrl
                                )
                            )
                            result.add(
                                ParsedMedia(
                                    platform = Platform.DOUYIN,
                                    mediaId = awemeId,
                                    videoUrl = liveUrl,
                                    coverUrl = imgUrl,
                                    title = title,
                                    author = author,
                                    // 实况图接口不返回时长，首页用 2s 估算；下载后探测真实值回填
                                    duration = LIVE_PHOTO_ESTIMATE_MS,
                                    rawShareText = shareText,
                                    originalUrl = shortUrl
                                )
                            )
                        }
                        // 边界：有 live 视频但静态图为空 → 只出视频项，不能丢
                        liveUrl != null -> result.add(
                            ParsedMedia(
                                platform = Platform.DOUYIN,
                                mediaId = awemeId,
                                videoUrl = liveUrl,
                                title = title,
                                author = author,
                                duration = LIVE_PHOTO_ESTIMATE_MS,
                                rawShareText = shareText,
                                originalUrl = shortUrl
                            )
                        )
                        imgUrl != null -> result.add(
                            ParsedMedia(
                                platform = Platform.DOUYIN,
                                mediaId = awemeId,
                                imageUrl = imgUrl,
                                title = title,
                                author = author,
                                rawShareText = shareText,
                                originalUrl = shortUrl
                            )
                        )
                    }
                }
                com.clipdownloader.util.LogFile.d(
                    "DouyinParser",
                    "图集解析完成：共 ${result.size} 张，其中实况图 $liveCount 张"
                )
                
                // 关键修复：如果是实况图集（有图片 + 有视频，但图片里没有 live URL）
                // 把视频作为单独的媒体项返回，这样用户既能下载图片也能下载视频
                if (videoUrl != null && liveCount == 0) {
                    com.clipdownloader.util.LogFile.d(
                        "DouyinParser",
                        "实况图集：图片无 live URL，把视频作为单独媒体项返回"
                    )
                    result.add(
                        ParsedMedia(
                            platform = Platform.DOUYIN,
                            mediaId = awemeId,
                            videoUrl = videoUrl,
                            title = title.ifBlank { "背景音乐" },
                            author = author,
                            coverUrl = cover,
                            duration = musicDuration,
                            isMusic = true,
                            rawShareText = shareText,
                            originalUrl = shortUrl
                        )
                    )
                }
                
                // 如果图片里有 live URL，就不返回视频（避免重复）
                // 如果没有图片，就返回视频
                if (liveCount > 0 || images.length() == 0) return@withContext result
            }

            // 视频作品（没有图集，只有视频）
            if (videoUrl != null && result.isEmpty()) {
                // bit_rate 清晰度档（可能为空）：videoUrl 用最高档，全部档位供首页画质滑块选择
                val qualities = mutableListOf<MediaQuality>()
                mediaInfo.optJSONArray("qualities")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val q = arr.optJSONObject(i) ?: continue
                        qualities.add(
                            MediaQuality(
                                label = q.optString("label"),
                                url = q.optString("url"),
                                sizeBytes = q.optLong("size", 0L),
                                height = q.optInt("height", 0)
                            )
                        )
                    }
                }
                result.add(
                    ParsedMedia(
                        platform = Platform.DOUYIN,
                        mediaId = awemeId,
                        videoUrl = qualities.firstOrNull()?.url?.takeIf { it.startsWith("http") } ?: videoUrl,
                        qualities = qualities,
                        title = title,
                        author = author,
                        coverUrl = cover,
                        duration = duration,
                        rawShareText = shareText,
                        originalUrl = shortUrl
                    )
                )
            }

            result
        } catch (e: Exception) {
            com.clipdownloader.util.LogFile.e("DouyinParser", "解析异常", e)
            emptyList()
        }
    }

    /**
     * 从短链解析视频 ID：
     * 1. 若 302 重定向，从 Location 提取（/video/xxx、/note/xxx、modal_id=xxx）
     * 2. 否则从页面 body 提取（短链页内嵌跳转 JS，含完整 URL）
     * 3. 递归解析嵌套的 iesdouyin.com / v.douyin.com 短链
     */
    private fun resolveVideoId(shortUrl: String, depth: Int = 0): String? {
        com.clipdownloader.util.LogFile.d("DouyinParser", "resolveVideoId 开始: shortUrl=$shortUrl")
        if (depth > 5) return null  // 防止无限递归

        return try {
            // 先不跟重定向，看是否有 Location
            val noRedirectClient = OkHttpClient.Builder()
                .followRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(shortUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            val resp = noRedirectClient.newCall(req).execute()
            val location = resp.header("Location")
            val status = resp.code
            resp.close()

            if (status in 300..399 && location != null) {
                // 递归解析嵌套短链（v.douyin.com → iesdouyin.com/share/...）
                if (location.contains("iesdouyin.com") || location.contains("v.douyin.com")) {
                    return resolveVideoId(location, depth + 1)
                }
                extractIdFromUrl(location)?.let { return it }
            }

            // 跟随重定向拿最终页面 + body
            val fullReq = Request.Builder().url(shortUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            val fullResp = client.newCall(fullReq).execute()
            val finalUrl = fullResp.request.url.toString()
            val body = fullResp.body?.string().orEmpty()
            fullResp.close()

            // 1) 从最终 URL 提取 ID
            extractIdFromUrl(finalUrl)?.let { return it }

            // 2) 从 body 的 ID_PATTERNS 匹配（/video/Vxxx、aweme_id=xxx 等）
            ID_PATTERNS.forEach { p ->
                p.find(body)?.let { m -> return m.groupValues[1] }
            }

            // 3) fallback：提取 body 中所有 douyin/iesdouyin URL，递归解析
            val douyinUrlPattern = Regex("(?:https?://)?(?:www\\.)?(?:ies?douyin|v\\.douyin)\\.com/(?:share/video|video|note)/([^/?#\"'>]+)")
            douyinUrlPattern.find(body)?.let { m ->
                val candidateId = m.groupValues[1]
                if (candidateId.matches(Regex("\\d{15,21}"))) return candidateId
            }

            // 4) fallback：提取 modal_id=xxx（URL 参数形式）
            Regex("modal_id[\"'=\\s]*([^&\\s'\"]+)").find(body)?.let { m ->
                return m.groupValues[1]
            }

            null
        } catch (e: Exception) {
            com.clipdownloader.util.LogFile.w("DouyinParser", "resolveVideoId 异常: ${e.message}")
            null
        }
    }

    private fun extractIdFromUrl(url: String): String? {
        ID_PATTERNS.forEach { p ->
            p.find(url)?.let { m -> return m.groupValues[1] }
        }
        return null
    }

    private fun fetchVideoInfo(videoId: String): JSONObject? {
        com.clipdownloader.util.LogFile.d("DouyinParser", "fetchVideoInfo 开始 videoId=$videoId")
        // 依次尝试多个入口（share/video 对视频与图集都有效，优先；不同子域风控独立，提高命中率）
        val hosts = listOf(
            "https://www.iesdouyin.com/share/video/$videoId/",
            "https://www.iesdouyin.com/share/note/$videoId/",
            "https://www.iesdouyin.com/video/$videoId"
        )
        for (pageUrl in hosts) {
            try {
                val request = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Referer", "https://www.douyin.com/")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string().orEmpty()
                com.clipdownloader.util.LogFile.d("DouyinParser", "页面大小: ${html.length} bytes, URL=$pageUrl")
                response.close()

                if (html.isBlank()) {
                    com.clipdownloader.util.LogFile.w("DouyinParser", "空页面 $pageUrl")
                } else {
                    val data = extractStateJson(html)
                    if (data == null) {
                        com.clipdownloader.util.LogFile.w("DouyinParser", "未找到状态 JSON $pageUrl")
                    } else {
                        val item = findItemListFirst(data)
                        if (item != null) {
                            return buildMediaInfo(item, videoId)
                        } else {
                            com.clipdownloader.util.LogFile.w("DouyinParser", "未找到 item_list $pageUrl")
                        }
                    }
                }
            } catch (e: Exception) {
                com.clipdownloader.util.LogFile.w("DouyinParser", "入口失败 ${pageUrl.take(50)}: ${e.message}")
                // 尝试下一个入口
            }
        }
        return null
    }

    /** 从 HTML 中提取 __NEXT_DATA__ 或 window._ROUTER_DATA 的 JSON */
    private fun extractStateJson(html: String): JSONObject? {
        // 1) __NEXT_DATA__（JSON 字符串里也可能含 </script>，同样用括号配对而非 .*? 截断）
        val nextTag = Regex("<script id=\"__NEXT_DATA__\"[^>]*>").find(html)
        if (nextTag != null) {
            val start = html.indexOf('{', nextTag.range.last + 1)
            if (start > 0) {
                extractBracketPair(html, start)?.let { raw ->
                    try { return JSONObject(raw) } catch (_: Exception) {}
                }
            }
        }

        // 2) window._ROUTER_DATA / _ROUTER_DATA / __DOUYIN_STATE__ / window.__INITIAL_STATE__
        //    一律用「字符串/转义感知的括号配对」提取，不用非贪婪正则——
        //    作品 desc 里可能含字面 </script> 或 {}，正则会在错误位置截断
        for (keyword in listOf("_ROUTER_DATA", "__DOUYIN_STATE__", "__INITIAL_STATE__")) {
            extractAssignedJson(html, keyword)?.let { raw ->
                try { return JSONObject(raw) } catch (_: Exception) {}
            }
        }

        // 3) fallback：递归扫描所有 <script> 标签内的 JSON 块，找含 videoInfoRes 的
        val scriptPattern = Regex("<script[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        for (match in scriptPattern.findAll(html)) {
            val scriptContent = match.groupValues[1]
            if ("videoInfoRes" in scriptContent || "item_list" in scriptContent) {
                try {
                    // 尝试直接解析
                    return JSONObject(scriptContent)
                } catch (_: Exception) {}
                // 尝试找 {...} 块（同样走括号配对，字符串内的 {} 不计深度）
                val bracePattern = Regex("\\{[^{}]*\"videoInfoRes\"")
                bracePattern.find(scriptContent)?.let { m ->
                    extractBracketPair(scriptContent, m.range.first)?.let { raw ->
                        try { return JSONObject(raw) } catch (_: Exception) {}
                    }
                }
            }
        }

        return null
    }

    /** 定位「keyword = {...}」赋值，从首个 { 起做括号配对提取完整 JSON 文本 */
    private fun extractAssignedJson(html: String, keyword: String): String? {
        var idx = html.indexOf(keyword)
        while (idx >= 0) {
            val eq = html.indexOf('=', idx + keyword.length)
            // keyword 与 = 之间只允许空白（防止匹配到别的标识符引用）
            if (eq > 0 && eq - (idx + keyword.length) <= 4) {
                val start = html.indexOf('{', eq)
                if (start > 0) {
                    extractBracketPair(html, start)?.let { return it }
                }
            }
            idx = html.indexOf(keyword, idx + keyword.length)
        }
        return null
    }

    /** 字符串/转义感知的括号配对：从 start（必须是 {）取到配对的 } 为止 */
    private fun extractBracketPair(text: String, start: Int): String? {
        if (start < 0 || start >= text.length || text[start] != '{') return null
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
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, j + 1)
                    }
                }
            }
        }
        return null
    }

    /** 在 loaderData 里递归找 videoInfoRes.item_list[0] */
    private fun findItemListFirst(data: JSONObject): JSONObject? {
        val loader = data.optJSONObject("loaderData") ?: data
        return findItemRecursive(loader)
    }

    private fun findItemRecursive(o: Any?): JSONObject? {
        when (o) {
            is JSONObject -> {
                val infoRes = o.optJSONObject("videoInfoRes")
                if (infoRes != null) {
                    val list = infoRes.optJSONArray("item_list")
                    if (list != null && list.length() > 0) {
                        return list.optJSONObject(0)
                    }
                }
                val it = o.keys()
                while (it.hasNext()) {
                    findItemRecursive(o.opt(it.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until o.length()) {
                    findItemRecursive(o.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun buildMediaInfo(item: JSONObject, videoId: String): JSONObject? {
        val video = item.optJSONObject("video") ?: run {
            com.clipdownloader.util.LogFile.w("DouyinParser", "item 中没有 video 字段")
            return null
        }

        // 尝试从多个路径提取真实视频 URL（排除 BGM）
        var cleanVideoUrl = ""

        // 1) 先尝试 play_addr
        cleanVideoUrl = resolvePlayUrl(video.optJSONObject("play_addr"))

        // 2) 如果 play_addr 是音频或为空，尝试 bit_rate 数组
        if (cleanVideoUrl.isEmpty()) {
            val bitRate = video.optJSONArray("bit_rate")
            if (bitRate != null && bitRate.length() > 0) {
                // 遍历 bit_rate，找第一个非音频的 play_addr
                for (i in 0 until bitRate.length()) {
                    val br = bitRate.optJSONObject(i) ?: continue
                    val u = resolvePlayUrl(br.optJSONObject("play_addr"))
                    if (u.isNotEmpty()) {
                        cleanVideoUrl = u
                        com.clipdownloader.util.LogFile.d("DouyinParser", "从 bit_rate[$i] 提取到视频 URL")
                        break
                    }
                }
            }
        }

        // 3) 尝试 play_addr_h265
        if (cleanVideoUrl.isEmpty()) cleanVideoUrl = resolvePlayUrl(video.optJSONObject("play_addr_h265"))

        // 4) 尝试 play_addr_264
        if (cleanVideoUrl.isEmpty()) cleanVideoUrl = resolvePlayUrl(video.optJSONObject("play_addr_264"))
        
        // 日志：dump 视频 URL 和 play_addr 完整内容
        com.clipdownloader.util.LogFile.d("DouyinParser", "视频 URL: ${cleanVideoUrl.take(200)}")
        val playAddrObj = video.optJSONObject("play_addr")
        if (playAddrObj != null) {
            com.clipdownloader.util.LogFile.d("DouyinParser", "play_addr 完整内容: ${playAddrObj.toString().take(500)}")
        }
        
        // 检查 video 对象的其他字段
        val videoKeys = mutableListOf<String>()
        val it = video.keys()
        while (it.hasNext()) videoKeys.add(it.next())
        com.clipdownloader.util.LogFile.d("DouyinParser", "video 对象 keys: ${videoKeys.joinToString()}")
        
        // 检查 bit_rate 结构
        val bitRate = video.optJSONArray("bit_rate")
        if (bitRate != null && bitRate.length() > 0) {
            com.clipdownloader.util.LogFile.d("DouyinParser", "bit_rate 数组长度: ${bitRate.length()}")
            val firstBr = bitRate.optJSONObject(0)
            if (firstBr != null) {
                val brKeys = mutableListOf<String>()
                val brIt = firstBr.keys()
                while (brIt.hasNext()) brKeys.add(brIt.next())
                com.clipdownloader.util.LogFile.d("DouyinParser", "bit_rate[0] keys: ${brKeys.joinToString()}")
                
                val brPlayAddr = firstBr.optJSONObject("play_addr")
                if (brPlayAddr != null) {
                    com.clipdownloader.util.LogFile.d("DouyinParser", "bit_rate[0].play_addr: ${brPlayAddr.toString().take(300)}")
                }
            }
        } else {
            com.clipdownloader.util.LogFile.d("DouyinParser", "bit_rate 数组为空或不存在")
        }
        
        // 检查 big_thumbs 字段
        val bigThumbs = video.optJSONArray("big_thumbs")
        if (bigThumbs != null && bigThumbs.length() > 0) {
            com.clipdownloader.util.LogFile.d("DouyinParser", "big_thumbs 数组长度: ${bigThumbs.length()}")
            val firstThumb = bigThumbs.optJSONObject(0)
            if (firstThumb != null) {
                com.clipdownloader.util.LogFile.d("DouyinParser", "big_thumbs[0]: ${firstThumb.toString().take(400)}")
            }
        } else {
            com.clipdownloader.util.LogFile.d("DouyinParser", "big_thumbs 数组为空或不存在")
        }

        val title = item.optString("desc", "")
        val author = item.optJSONObject("author")?.optString("nickname", "").orEmpty()
        val cover = video.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0).orEmpty()
        val duration = video.optLong("duration", 0)

        // 图集：images 数组
        val imagesArray = item.optJSONArray("images")
        val imageItems = JSONArray()
        if (imagesArray != null) {
            for (i in 0 until imagesArray.length()) {
                val img = imagesArray.optJSONObject(i) ?: continue
                val staticUrl = img.optJSONArray("url_list")?.optString(0).orEmpty()

                // 抖音实况图视频 URL 可能出现在多种字段路径中：
                //  - img.video.play_addr.url_list[0]     （最常见的「图集内嵌视频」路径）
                //  - img.clip_video.play_addr.url_list[0] （部分新版分享页）
                //  - img.live_photo_url                   （旧版直接给实况 URL）
                //  - img.video.play_addr_h264.url_list[0] （h264 编码路径）
                val rawLive = resolvePlayUrl(img.optJSONObject("video")?.optJSONObject("play_addr"))
                    .ifEmpty { resolvePlayUrl(img.optJSONObject("video")?.optJSONObject("play_addr_h264")) }
                    .ifEmpty { resolvePlayUrl(img.optJSONObject("clip_video")?.optJSONObject("play_addr")) }
                    .ifEmpty { img.optString("live_photo_url").takeIf { it.isNotBlank() } ?: "" }
                val liveUrl = if (rawLive.isNotBlank()) rawLive.replace("/playwm/", "/play/") else ""

                // dump 第一张图的完整 JSON 便于排查动图字段
                if (i == 0) {
                    val keys = mutableListOf<String>()
                    val it = img.keys()
                    while (it.hasNext()) keys.add(it.next())
                    com.clipdownloader.util.LogFile.d(
                        "DouyinParser",
                        "images[0] keys=${keys.joinToString()}, staticUrl.length=${staticUrl.length}, liveUrl.length=${liveUrl.length}"
                    )
                    com.clipdownloader.util.LogFile.d(
                        "DouyinParser",
                        "images[0] 完整 JSON: ${img.toString().take(2000)}"
                    )
                }

                if (staticUrl.isBlank() && liveUrl.isBlank()) continue
                imageItems.put(JSONObject().apply {
                    put("image", staticUrl)
                    put("live", liveUrl)
                })
            }
        }

        val isImagePost = imageItems.length() > 0
        com.clipdownloader.util.LogFile.d(
            "DouyinParser",
            "buildMediaInfo：video_url=${if (isImagePost) "图集+视频" else "有"}，images=${imageItems.length()} 张"
        )
        
        // dump 原始 item 完整 JSON 便于排查动图字段
        val itemStr = item.toString()
        com.clipdownloader.util.LogFile.d(
            "DouyinParser",
            "item 完整 JSON 长度: ${itemStr.length}"
        )
        // 分段输出（Logcat 有长度限制）
        var offset = 0
        var chunk = 1
        while (offset < itemStr.length) {
            val end = minOf(offset + 3000, itemStr.length)
            com.clipdownloader.util.LogFile.d(
                "DouyinParser",
                "item JSON [$chunk]: ${itemStr.substring(offset, end)}"
            )
            offset = end
            chunk++
        }
        if (imagesArray != null) {
            com.clipdownloader.util.LogFile.d(
                "DouyinParser",
                "imagesArray 完整内容: ${imagesArray.toString().take(2000)}"
            )
        }
        
        // bit_rate 各档清晰度（gear_name→标签，data_size→体积），高度降序去重。
        // gear_name 形如 adapt_1080_1 / normal_720_0，取其中的高度数字
        val qualityList = mutableListOf<MediaQuality>()
        video.optJSONArray("bit_rate")?.let { arr ->
            for (i in 0 until arr.length()) {
                val br = arr.optJSONObject(i) ?: continue
                val u = resolvePlayUrl(br.optJSONObject("play_addr"))
                if (u.isEmpty() || isAudioUrl(u)) continue
                val gear = br.optString("gear_name")
                val height = MediaQuality.heightOf(gear)
                val size = br.optLong("data_size", 0L).takeIf { it > 0 }
                    ?: br.optJSONObject("play_addr")?.optLong("data_size", 0L) ?: 0L
                qualityList.add(
                    MediaQuality(
                        label = if (height > 0) "${height}p" else gear.ifBlank { "默认" },
                        url = u,
                        sizeBytes = size,
                        height = height
                    )
                )
            }
        }
        val qualities = qualityList
            .sortedWith(compareByDescending<MediaQuality> { it.height }.thenByDescending { it.sizeBytes })
            .distinctBy { it.url }
        if (qualities.isNotEmpty()) {
            com.clipdownloader.util.LogFile.d(
                "DouyinParser", "bit_rate 清晰度 ${qualities.size} 档：${qualities.joinToString(" / ") { "${it.label}(${it.sizeBytes / 1024}KB)" }}"
            )
        }

        return JSONObject().apply {
            // 关键修复：不再丢弃图集的视频 URL
            // 如果是实况图集，视频和图都要返回，让用户都能下载
            put("video_url", cleanVideoUrl)
            put("cover_url", cover)
            put("title", title)
            put("author", author)
            put("duration", duration)
            put("music_duration", item.optJSONObject("music")?.optLong("duration", 0) ?: 0L)
            put("images", imageItems)
            put("video_id", videoId)
            put("qualities", JSONArray().apply { qualities.forEach { q ->
                put(JSONObject().apply {
                    put("label", q.label); put("url", q.url)
                    put("size", q.sizeBytes); put("height", q.height)
                })
            } })
        }
    }

    /**
     * 从 play_addr 对象解析出可下载 URL。
     * 2026 新格式：url_list[0] 形如 .../playwm/?video_id=https://xxx.douyinstatic.com/obj/...，
     * 此时 playwm 接口已 404，必须直接用 uri 字段（本身就是 CDN 直链）。
     */
    private fun resolvePlayUrl(playAddr: org.json.JSONObject?): String {
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

    /** 访问预热获取 cookie（关键是 iesdouyin.com 根域的 ttwid，分享页没有它就不会内嵌 videoInfoRes） */
    private fun warmUpCookie() {
        // 1) iesdouyin 根域：发放 ttwid（决定分享页 SSR 是否返回视频数据）
        try {
            client.newCall(
                Request.Builder().url("https://www.iesdouyin.com/")
                    .header("User-Agent", DEFAULT_UA)
                    .build()
            ).execute().close()
            com.clipdownloader.util.LogFile.d("DouyinParser", "iesdouyin cookie 预热完成")
        } catch (e: Exception) {
            com.clipdownloader.util.LogFile.w("DouyinParser", "iesdouyin cookie 预热失败: ${e.message}")
        }
        // 2) 主站：补充 msToken 等
        try {
            client.newCall(
                Request.Builder().url("https://www.douyin.com/")
                    .header("User-Agent", DEFAULT_UA)
                    .build()
            ).execute().close()
            com.clipdownloader.util.LogFile.d("DouyinParser", "主站 cookie 预热完成")
        } catch (e: Exception) {
            com.clipdownloader.util.LogFile.w("DouyinParser", "主站 cookie 预热失败: ${e.message}")
        }
    }
    
    /** 检查 URL 是否为音频文件（BGM 而非视频） */
    private fun isAudioUrl(url: String): Boolean {
        val lower = url.lowercase()
        // 检查常见音频扩展名
        return lower.contains(".mp3") || 
               lower.contains(".m4a") || 
               lower.contains(".aac") ||
               lower.contains(".wav") ||
               lower.contains("music") ||
               lower.contains("ies-music")
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

        /** 实况图视频接口不返回时长，首页预览用 2s 估算（下载后探测真实值回填） */
        private const val LIVE_PHOTO_ESTIMATE_MS = 2000L

        private val ID_PATTERNS = listOf(
            // 视频 / 图文（note/slides 都是图集作品路径，v.douyin.com 短链会重定向到其中一种）
            Regex("/(?:video|note|slides)/(\\d{15,21})"),
            Regex("aweme_id[\"']?\\s*[:=]\\s*[\"']?(\\d{15,21})"),
            Regex("modal_id=(\\d{15,21})")
        )
    }
}
