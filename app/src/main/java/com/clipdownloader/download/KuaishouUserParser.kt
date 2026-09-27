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
import java.util.concurrent.TimeUnit

/**
 * 快手博主主页解析：列出用户全部公开作品（「主页下载」栏目用）。
 *
 * 走快手网页版 GraphQL（visionProfilePhotoList，pcursor 分页），
 * 需要先访问主站拿 did 等 cookie；触发验证码（result=400002）时返回明确错误。
 * 列表项 photoUrl 即视频直链，无需逐作二次解析。
 */
class KuaishouUserParser {

    data class UserWorks(
        val author: String,
        val avatar: String,
        val total: Int,
        val works: List<ParsedMedia>
    )

    /** 最近一次失败原因（验证码风控/无数据等），供 UI 明确提示 */
    var lastError: String = ""
        private set

    private val cookieManager = java.net.CookieManager()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(okhttp3.JavaNetCookieJar(cookieManager))
        .build()

    suspend fun listVideos(userId: String, maxPages: Int = 20): UserWorks? = withContext(Dispatchers.IO) {
        lastError = ""
        try {
            // 先访问 profile 页种 kpf/kpn 等 cookie（无 cookie 的 GraphQL 会被拒），
            // 顺手从页面里提取博主头像（og:image / headerUrls）
            var avatar = ""
            try {
                client.newCall(
                    Request.Builder().url("https://www.kuaishou.com/profile/$userId")
                        .header("User-Agent", DESKTOP_UA)
                        .build()
                ).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = resp.body?.string().orEmpty()
                        avatar = AVATAR_OG_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
                            ?: AVATAR_JSON_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
                            ?: ""
                    }
                }
            } catch (_: Exception) {
            }
            // GraphQL 要求 did cookie（浏览器端由 JS 生成）；纯 HTTP 环境自己造一个
            setDid()

            val works = mutableListOf<ParsedMedia>()
            var author = ""
            var pcursor = ""
            var page = 1
            var retriedWithNewDid = false
            while (page <= maxPages) {
                val body = JSONObject().apply {
                    put("operationName", "visionProfilePhotoList")
                    put("variables", JSONObject().apply {
                        put("userId", userId)
                        put("pcursor", pcursor)
                        put("page", "home")
                    })
                    put("query", PHOTO_LIST_QUERY)
                }
                val req = Request.Builder().url("https://www.kuaishou.com/graphql")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Content-Type", "application/json")
                    .header("Referer", "https://www.kuaishou.com/profile/$userId")
                    .header("Origin", "https://www.kuaishou.com")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val json = try {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            LogFile.w(TAG, "GraphQL HTTP ${resp.code}")
                            return@use null
                        }
                        JSONObject(resp.body?.string().orEmpty())
                    }
                } catch (e: Exception) {
                    LogFile.w(TAG, "GraphQL 异常: ${e.message}")
                    null
                } ?: run {
                    // 分页中途失败不丢已取数据（与 result!=1 的风控分支行为一致）
                    LogFile.w(TAG, "第 $page 页请求失败，返回已取 ${works.size} 个作品")
                    return@withContext if (works.isEmpty()) null
                    else UserWorks(author.ifBlank { userId }, avatar, works.size, works)
                }

                val list = json.optJSONObject("data")?.optJSONObject("visionProfilePhotoList")
                if (list == null) {
                    LogFile.w(TAG, "GraphQL 无 visionProfilePhotoList 数据（resp=${json.toString().take(200)}）")
                    // 首页无数据常见于 did 无效：换一个新 did 重试一次
                    if (page == 1 && !retriedWithNewDid) {
                        retriedWithNewDid = true
                        setDid()
                        continue
                    }
                    return@withContext if (works.isEmpty()) null else UserWorks(author.ifBlank { userId }, avatar, works.size, works)
                }
                val result = list.optInt("result", -1)
                if (result != 1) {
                    lastError = if (result == 400002) "触发快手验证码风控，请稍后重试或更换网络"
                    else "GraphQL result=$result"
                    LogFile.w(TAG, "GraphQL result=$result（400002=触发验证码/风控）")
                    return@withContext if (works.isEmpty()) null else UserWorks(author.ifBlank { userId }, avatar, works.size, works)
                }
                val feeds = list.optJSONArray("feeds") ?: break
                if (feeds.length() == 0) break
                for (i in 0 until feeds.length()) {
                    val feed = feeds.optJSONObject(i) ?: continue
                    val photo = feed.optJSONObject("photo") ?: continue
                    val id = photo.optString("id")
                    if (id.isBlank()) continue
                    var dur = photo.optLong("duration", 0L)
                    if (dur in 1..999) dur *= 1000 // 秒值归一成毫秒
                    val cover = photo.optJSONObject("coverUrl")?.optJSONArray("urls")?.optString(0)?.ifBlank { null }
                    if (author.isBlank()) author = photo.optJSONObject("owner")?.optString("name").orEmpty()
                    works.add(
                        ParsedMedia(
                            platform = Platform.KUAISHOU,
                            mediaId = id,
                            videoUrl = photo.optString("photoUrl").takeIf { it.startsWith("http") },
                            title = photo.optString("caption"),
                            author = author,
                            coverUrl = cover,
                            duration = dur,
                            rawShareText = "https://www.kuaishou.com/short-video/$id",
                            originalUrl = "https://www.kuaishou.com/short-video/$id"
                        )
                    )
                }
                pcursor = list.optString("pcursor", "no_more")
                if (pcursor.isBlank() || pcursor == "no_more") break
                page++
            }

            if (works.isEmpty()) {
                LogFile.w(TAG, "主页无作品或全部被风控拦截 userId=$userId")
                return@withContext null
            }
            LogFile.d(TAG, "主页列表：${works.size} 个作品 · $author")
            UserWorks(author.ifBlank { userId }, avatar, works.map { it.mediaId }.distinct().size, works)
        } catch (e: Exception) {
            LogFile.e(TAG, "拉取主页作品异常", e)
            null
        }
    }

    /** 生成并种入 web 端 did cookie（浏览器里由前端 JS 生成，格式 web_<uuid>） */
    private fun setDid() {
        try {
            val ck = java.net.HttpCookie("did", "web_" + java.util.UUID.randomUUID())
            ck.domain = ".kuaishou.com"
            ck.path = "/"
            cookieManager.cookieStore.add(java.net.URI("https://www.kuaishou.com"), ck)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "KuaishouUserParser"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 主页页头头像：og:image 与 SSR JSON 里的 headerUrls 两种形态 */
        private val AVATAR_OG_REGEX = Regex("""og:image"\s*content="([^"]+)"""")
        private val AVATAR_JSON_REGEX = Regex("""headerUrls"\s*:\s*\{\s*"urls"\s*:\s*\[\s*"([^"]+)"""")

        private const val PHOTO_LIST_QUERY =
            "query visionProfilePhotoList(\$userId: String, \$pcursor: String, \$page: String) { " +
                "visionProfilePhotoList(userId: \$userId, pcursor: \$pcursor, page: \$page) { " +
                "result llsid pcursor feeds { type photo { id duration caption photoUrl " +
                "coverUrl { urls } owner { name } } } } }"
    }
}
