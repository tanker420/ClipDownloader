package com.clipdownloader.download

import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import com.clipdownloader.util.LogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * B站博主主页解析：列出 UP 主的全部投稿（wbi 签名）。
 *
 * 注意：B站对未登录请求有风控（-352），无 Cookie 时尝试纯 wbi 签名，失败则返回明确错误。
 */
class BilibiliUserParser {

    data class UserVideos(
        val author: String,
        val avatar: String,
        val total: Int,
        val videos: List<ParsedMedia>
    )

    /** 内存 Cookie：访问 B站主页种 buvid3/buvid4，arc/search 无 cookie 会被 412 风控 */
    private val cookieManager = java.net.CookieManager()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(okhttp3.JavaNetCookieJar(cookieManager))
        .build()

    /** maxPages=100：100 页 × 30 = 最多拉 3000 个投稿（作品更多的 UP 仍会截断） */
    suspend fun listVideos(mid: String, maxPages: Int = 100): UserVideos? = withContext(Dispatchers.IO) {
        try {
            // 0) 预热：拿 buvid 等 cookie（无 cookie 的 arc/search 请求直接 412）
            warmUpCookie()

            // 1) 分页拉投稿列表（每页独立取 wbi 签名；单页失败整页重试，避免列表截断）
            fun fetchPage(pageNo: Int): JSONObject? {
                val navJson = httpGetJson("https://api.bilibili.com/x/web-interface/nav")
                    ?: return null
                val wbi = navJson.optJSONObject("data")?.optJSONObject("wbi_img")
                    ?: return null
                val imgKey = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
                val subKey = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
                val mixinKey = (imgKey + subKey).let { raw ->
                    MIX_KEY_TBL.joinToString("") { raw.getOrNull(it)?.toString() ?: "" }.take(32)
                }
                val params = LinkedHashMap<String, String>()
                params["mid"] = mid
                params["pn"] = pageNo.toString()
                params["ps"] = "30"
                params["keyword"] = ""
                params["order"] = "pubdate"
                var json = fetchArcSearch(mid, params, mixinKey)
                if (json == null) {
                    // fetchArcSearch 内部 3 次都失败：冷却后换新签名再试一轮
                    warmUpCookie()
                    Thread.sleep(3000L)
                    json = fetchArcSearch(mid, params, mixinKey)
                }
                return json
            }

            val all = mutableListOf<ParsedMedia>()
            var author = ""
            var total = 0
            var page = 1
            while (page <= maxPages) {
                val listJson = fetchPage(page) ?: break
                val code = listJson.optInt("code")
                if (code != 0) {
                    LogFile.w("BilibiliUserParser", "arc/search code=$code ${listJson.optString("message")}")
                    return@withContext if (all.isEmpty()) null else UserVideos(author, fetchAvatar(mid), total, all)
                }
                val data = listJson.optJSONObject("data") ?: break
                val vlist = data.optJSONObject("list")?.optJSONArray("vlist") ?: break
                if (vlist.length() == 0) break
                for (i in 0 until vlist.length()) {
                    val v = vlist.optJSONObject(i) ?: continue
                    if (author.isBlank()) author = v.optString("author")
                    all.add(
                        ParsedMedia(
                            platform = Platform.BILIBILI,
                            mediaId = v.optString("bvid"),
                            title = v.optString("title"),
                            author = v.optString("author"),
                            coverUrl = v.optString("pic").takeIf { it.startsWith("http") },
                            rawShareText = "https://www.bilibili.com/video/${v.optString("bvid")}",
                            originalUrl = "https://www.bilibili.com/video/${v.optString("bvid")}"
                        )
                    )
                }
                total = data.optJSONObject("page")?.optInt("count", all.size) ?: all.size
                if (all.size >= total || vlist.length() < 30) break
                page++
            }

            if (all.isEmpty()) null else UserVideos(
                author.ifBlank { mid },
                fetchAvatar(mid),
                total,
                all
            )
        } catch (e: Exception) {
            LogFile.e("BilibiliUserParser", "拉取投稿列表异常", e)
            null
        }
    }

    /** UP 主头像（card 接口 data.card.face），失败返回空串不影响列表 */
    private fun fetchAvatar(mid: String): String {
        return try {
            httpGetJson("https://api.bilibili.com/x/web-interface/card?mid=$mid&photo=false")
                ?.optJSONObject("data")?.optJSONObject("card")?.optString("face")
                ?.takeIf { it.startsWith("http") } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** 访问 B站主页种会话 cookie（buvid3/buvid4），失败不阻塞列表拉取 */
    private fun warmUpCookie() {
        try {
            client.newCall(
                Request.Builder().url("https://www.bilibili.com/")
                    .header("User-Agent", DESKTOP_UA)
                    .build()
            ).execute().close()
        } catch (e: Exception) {
            LogFile.w("BilibiliUserParser", "cookie 预热失败: ${e.message}")
        }
        // finger/spi 显式取 buvid3/buvid4 写入会话：无 buvid 的 space 请求会被 412
        try {
            val spi = httpGetJson("https://api.bilibili.com/x/frontend/finger/spi")
            val d = spi?.optJSONObject("data")
            fun put(name: String, value: String) {
                if (value.isBlank()) return
                val ck = java.net.HttpCookie(name, value)
                ck.domain = ".bilibili.com"
                ck.path = "/"
                cookieManager.cookieStore.add(java.net.URI("https://www.bilibili.com"), ck)
            }
            put("buvid3", d?.optString("b_3").orEmpty())
            put("buvid4", d?.optString("b_4").orEmpty())
        } catch (e: Exception) {
            LogFile.w("BilibiliUserParser", "spi 取 buvid 失败: ${e.message}")
        }
    }

    /**
     * 单页投稿列表（wbi 签名）。space 接口风控（HTTP 412 / -352）是间歇性的：
     * 每次失败重新预热 cookie 后退避重试，最多 3 次。
     */
    private fun fetchArcSearch(mid: String, baseParams: Map<String, String>, mixinKey: String): JSONObject? {
        repeat(3) { attempt ->
            val signed = wbiSign(baseParams, mixinKey)
            val json = httpGetJson(
                "https://api.bilibili.com/x/space/wbi/arc/search?$signed",
                referer = "https://space.bilibili.com/$mid"
            )
            if (json != null && json.optInt("code") == 0) return json
            val reason = json?.optString("message")?.ifBlank { "code=${json.optInt("code")}" } ?: "HTTP 失败"
            LogFile.w("BilibiliUserParser", "arc/search 第${attempt + 1}次失败：$reason")
            if (attempt < 2) {
                warmUpCookie()
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        return null
    }

    private fun httpGetJson(url: String, referer: String = "https://www.bilibili.com/"): JSONObject? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", referer)
        try {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogFile.w("BilibiliUserParser", "HTTP ${resp.code} · ${url.take(90)}")
                    return null
                }
                val body = resp.body?.string().orEmpty()
                return try { JSONObject(body) } catch (_: Exception) {
                    LogFile.w("BilibiliUserParser", "非 JSON 响应（${body.take(60)}）· ${url.take(60)}")
                    null
                }
            }
        } catch (e: Exception) {
            LogFile.w("BilibiliUserParser", "请求异常 ${e.javaClass.simpleName}: ${e.message} · ${url.take(60)}")
            return null
        }
    }

    /** wbi 签名：参数排序 + wts + md5(w_rid) */
    private fun wbiSign(params: Map<String, String>, mixinKey: String): String {
        val sorted = params.toSortedMap().toMutableMap()
        sorted["wts"] = (System.currentTimeMillis() / 1000).toString()
        // 过滤 value 中的 !'* 字符
        val query = sorted.entries.joinToString("&") { (k, v) ->
            val filtered = v.replace("!", "").replace("'", "").replace("*", "")
            "$k=" + URLEncoder.encode(filtered, "UTF-8").replace("+", "%20")
        }
        val wRid = md5(query + mixinKey)
        return "$query&w_rid=$wRid"
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** wbi mixin key 重排表 */
        private val MIX_KEY_TBL = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
        )
    }
}
