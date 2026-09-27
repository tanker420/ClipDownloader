package com.clipdownloader.download

import com.clipdownloader.model.MediaQuality
import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 哔哩哔哩视频解析器
 *
 * 解析流程：
 * 1. 从分享文本提取短链接 (b23.tv/xxx) 或完整链接 (bilibili.com/video/BVxxx)
 * 2. 如果是短链接，跟随重定向获取 BV 号
 * 3. 调用 Bilibili API 获取视频信息
 * 4. 获取视频流地址，固定请求最高画质（qn=120，接口按账号可返回的最高档给流）
 */
class BilibiliParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun parse(shareText: String, withQualities: Boolean = false): ParsedMedia? = withContext(Dispatchers.IO) {
        try {
            val shortUrl = PlatformParser.extractUrl(shareText) ?: return@withContext null
            if (!shortUrl.contains("b23.tv") && !shortUrl.contains("bilibili.com")) {
                return@withContext null
            }

            // 获取 BV 号
            val bvid = extractBvid(shortUrl) ?: run {
                com.clipdownloader.util.LogFile.w("BilibiliParser", "无法从 URL 提取 BV 号: $shortUrl")
                return@withContext null
            }

            // 获取视频信息
            val mediaInfo = fetchVideoInfo(bvid, withQualities) ?: run {
                com.clipdownloader.util.LogFile.w("BilibiliParser", "fetchVideoInfo 返回 null, bvid=$bvid")
                return@withContext null
            }

            val videoUrl = mediaInfo.optString("video_url").ifEmpty { null }
            com.clipdownloader.util.LogFile.d(
                "BilibiliParser",
                "解析完成: bvid=$bvid, videoUrl=${videoUrl?.take(80) ?: "null"}..."
            )

            // 各档清晰度（手动解析路径才有；按高度降序）
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

            ParsedMedia(
                platform = Platform.BILIBILI,
                mediaId = bvid,
                videoUrl = videoUrl,
                qualities = qualities,
                title = mediaInfo.optString("title"),
                author = mediaInfo.optString("author"),
                coverUrl = mediaInfo.optString("cover_url").ifEmpty { null },
                duration = mediaInfo.optLong("duration", 0),
                rawShareText = shareText,
                // 重要：B站 CDN 校验 Referer，必须是 bilibili.com 根域（用 b23.tv 短链或 video/BVxxx 完整路径都可能 403）
                originalUrl = "https://www.bilibili.com"
            )
        } catch (e: Exception) {
            com.clipdownloader.util.LogFile.e("BilibiliParser", "解析异常", e)
            null
        }
    }

    private fun extractBvid(url: String): String? {
        // 直接从 URL 中提取 BV 号
        val directMatch = Regex("(BV\\w{10})").find(url)
        if (directMatch != null) return directMatch.groupValues[1]

        // 如果是短链接，跟随重定向
        return try {
            val noRedirectClient = OkHttpClient.Builder()
                .followRedirects(false)
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", DEFAULT_UA)
                .build()
            val response = noRedirectClient.newCall(request).execute()
            val location = response.header("Location") ?: ""
            response.close()
            Regex("(BV\\w{10})").find(location)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchVideoInfo(bvid: String, withQualities: Boolean = false): JSONObject? {
        return try {
            // 步骤1: 获取视频基本信息（cid）
            val viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
            val request = Request.Builder()
                .url(viewUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Referer", "https://www.bilibili.com/")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: run {
                response.close()
                com.clipdownloader.util.LogFile.w("BilibiliParser", "view API 返回空 body")
                return null
            }
            response.close()

            val json = JSONObject(body)
            val code = json.optInt("code")
            if (code != 0) {
                com.clipdownloader.util.LogFile.w("BilibiliParser", "view API 错误: code=$code, msg=${json.optString("message")}")
                return null
            }
            val data = json.optJSONObject("data") ?: return null
            val cid = data.optLong("cid")
            val aid = data.optLong("aid")
            val title = data.optString("title", "")
            val cover = data.optString("pic", "")
            val duration = data.optLong("duration", 0) * 1000 // 秒→毫秒
            val author = data.optJSONObject("owner")?.optString("name") ?: ""

            com.clipdownloader.util.LogFile.d("BilibiliParser", "view OK: cid=$cid, aid=$aid, title=$title")

            // 步骤2: 获取播放地址（fnval=0 返回 durl 完整 MP4——含音视频）
            // 固定请求最高画质 qn=120（4K），接口会按当前账号可返回的最高档给流
            val playUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=120&fnval=0"
            val playRequest = Request.Builder()
                .url(playUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Referer", "https://www.bilibili.com/video/$bvid")
                .build()
            val playResponse = client.newCall(playRequest).execute()
            val playBody = playResponse.body?.string() ?: ""
            val playCode = playResponse.code
            playResponse.close()

            com.clipdownloader.util.LogFile.d("BilibiliParser", "playurl HTTP=$playCode, body.length=${playBody.length}")

            if (playBody.isBlank()) {
                com.clipdownloader.util.LogFile.w("BilibiliParser", "playurl API 返回空")
                return null
            }

            val playJson = JSONObject(playBody)
            val playCode2 = playJson.optInt("code")
            if (playCode2 != 0) {
                com.clipdownloader.util.LogFile.w("BilibiliParser", "playurl API 错误: code=$playCode2, msg=${playJson.optString("message")}")
                return null
            }
            val playData = playJson.optJSONObject("data") ?: return null

            // 只用 durl（完整 MP4 含音视频）。dash 是音视频分离流，
            // 直接下 dash.video 的 baseUrl 会得到无声视频，必须忽略 dash 分支
            val durl = playData.optJSONArray("durl")
            val durlUrl = if (durl != null && durl.length() > 0) {
                val url = durl.optJSONObject(0)?.optString("url") ?: ""
                com.clipdownloader.util.LogFile.d("BilibiliParser", "durl: url 完整长度=${url.length}")
                url
            } else {
                ""
            }

            val finalUrl = durlUrl

            // 手动解析路径：枚举 accept_quality 各档，逐档取 durl（完整 MP4 含音频）
            // 拿真实直链与体积；未登录时各 qn 通常回落同一档，按直链去重后自然只剩一档
            val qualitiesJson = org.json.JSONArray()
            if (withQualities) {
                val acceptQn = playData.optJSONArray("accept_quality")
                val acceptDesc = playData.optJSONArray("accept_description")
                if (acceptQn != null && acceptQn.length() > 0) {
                    val seen = HashSet<String>()
                    if (finalUrl.startsWith("http")) seen.add(finalUrl)
                    for (i in 0 until acceptQn.length()) {
                        val qn = acceptQn.optInt(i)
                        val desc = acceptDesc?.optString(i)?.takeIf { it.isNotBlank() }
                            ?: QN_LABELS[qn] ?: "${qn}p"
                        try {
                            val qUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=$qn&fnval=0"
                            val qResp = client.newCall(
                                Request.Builder().url(qUrl)
                                    .header("User-Agent", DEFAULT_UA)
                                    .header("Referer", "https://www.bilibili.com/video/$bvid")
                                    .build()
                            ).execute()
                            val qBody = qResp.body?.string().orEmpty()
                            qResp.close()
                            val qJson = JSONObject(qBody)
                            if (qJson.optInt("code") != 0) continue
                            val d = qJson.optJSONObject("data")
                                ?.optJSONArray("durl")?.optJSONObject(0) ?: continue
                            val u = d.optString("url")
                            if (!u.startsWith("http") || u in seen) continue
                            seen.add(u)
                            qualitiesJson.put(
                                org.json.JSONObject().apply {
                                    put("label", desc)
                                    put("url", u)
                                    put("size", d.optLong("size", 0L))
                                    put("height", MediaQuality.heightOf(desc))
                                }
                            )
                        } catch (e: Exception) {
                            com.clipdownloader.util.LogFile.w("BilibiliParser", "qn=$qn 画质探测失败: ${e.message}")
                        }
                    }
                }
            }

            JSONObject().apply {
                put("video_url", finalUrl)
                put("cover_url", cover)
                put("title", title)
                put("author", author)
                put("duration", duration)
                put("cid", cid)
                put("aid", aid)
                put("qualities", qualitiesJson)
            }.also {
                com.clipdownloader.util.LogFile.d(
                    "BilibiliParser",
                    "fetchVideoInfo: finalUrl.length=${finalUrl.length}, title=$title, duration=$duration, qualities=${qualitiesJson.length()}"
                )
            }
        } catch (e: Exception) {
            com.clipdownloader.util.LogFile.e("BilibiliParser", "fetchVideoInfo 异常", e)
            e.printStackTrace()
            null
        }
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

        /** qn → 清晰度标签（accept_description 缺失时兜底） */
        private val QN_LABELS = mapOf(
            16 to "360p", 32 to "480p", 64 to "720p", 74 to "720p60",
            80 to "1080p", 112 to "1080P高码率", 116 to "1080P60",
            120 to "4K", 125 to "HDR", 126 to "杜比视界", 127 to "8K"
        )
    }
}
