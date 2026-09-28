package com.clipdownloader.download

import android.graphics.Bitmap
import android.util.Base64
import com.clipdownloader.model.MediaQuality
import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import com.clipdownloader.util.LogFile
import com.clipdownloader.util.PreferencesManager
import com.clipdownloader.util.supportsPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 云端解析器：把分享链接交给云端接口解析（设置里"本地解析"关闭时启用，纯云端，不回落本地）。
 *
 * 通道顺序：首选通道优先，其余按列表顺序逐个尝试，全部失败返回错误明细。
 * 通道格式：GET {通道}?url={分享链接}（若通道含 {url} 占位符则直接替换）。
 */
class CloudParser(
    private val context: android.content.Context,
    private val prefs: Preferences,
    /** 通道级进度回调（尝试/失败/成功），供首页实时显示解析通道状态 */
    private val onProgress: ((String) -> Unit)? = null
) {

    /** 轻量接口，避免 Parser 依赖完整 PreferencesManager（便于测试） */
    interface Preferences {
        fun getCloudChannels(): List<PreferencesManager.CloudChannel>

        /** 该平台的首选通道（"iiilab" 或云端通道 url；"local" 视为无云端首选） */
        fun getPreferredChannel(platform: Platform): String
    }

    constructor(
        context: android.content.Context,
        prefs: PreferencesManager,
        onProgress: ((String) -> Unit)? = null
    ) : this(context, object : Preferences {
        override fun getCloudChannels() = prefs.getCloudChannels()
        override fun getPreferredChannel(platform: Platform) =
            // 未知链接（OTHER）按「国外其他」的键取首选，与设置页兜底下拉一致
            prefs.getPlatformChannel(
                if (platform == Platform.OTHER)
                    PreferencesManager.ChannelPlatforms.OVERSEAS_OTHER
                else platform.name.lowercase()
            )
    }, onProgress)

    /** 每个通道的失败原因（通道名 -> 原因），供上层提示 */
    val channelErrors = linkedMapOf<String, String>()

    /**
     * encrypt 字段判定：部分通道返回数值 1 而非布尔 true，
     * org.json 的 optBoolean 只认 Boolean 和字符串 "true"，数值 1 会被误判为 false，
     * 导致加密响应当明文处理、必走「响应无 data」失败
     */
    private fun isEncryptedResponse(json: JSONObject): Boolean {
        val v = json.opt("encrypt") ?: return false
        return v == true || (v as? Number)?.toInt() == 1 ||
            v.toString().equals("true", ignoreCase = true) || v.toString() == "1"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        // 必须共享 cookie 会话：maxhelper 的 auth 响应会种会话 cookie，
        // parse 请求不带同一会话 cookie 会被判定 auth_session_mismatch(403)
        .cookieJar(okhttp3.JavaNetCookieJar(java.net.CookieManager()))
        .build()

    /**
     * 取消本实例所有在途请求。竞速解析（raceCloud）里协程 cancel 打不断
     * OkHttp 同步 execute()，败者通道会一直跑到 readTimeout 才释放连接，
     * 批量解析时连接数 = 作品数 × 通道数；胜出后调这个方法强制掐断败者。
     */
    fun cancelInFlight() {
        client.dispatcher.cancelAll()
    }

    /**
     * @param channelOrder 指定通道尝试顺序（通道 url 列表，只试列表内的通道）；
     *                     缺省用该平台的首选通道 + 列表顺序
     */
    suspend fun parseAll(
        shareText: String,
        platform: Platform,
        channelOrder: List<String>? = null
    ): List<ParsedMedia> =
        withContext(Dispatchers.IO) {
            channelErrors.clear()
            val url = PlatformParser.extractUrl(shareText) ?: run {
                channelErrors["输入"] = "未找到链接"
                return@withContext emptyList()
            }

            // 只尝试支持该平台的通道（首选优先，其余保持列表顺序；channelOrder 由海外调度传入）
            val channels = prefs.getCloudChannels()
                .filter { it.supportsPlatform(platform) }
                .distinctBy { it.url }
            val preferred = prefs.getPreferredChannel(platform)
            val ordered = when {
                channelOrder != null ->
                    channels.filter { it.url in channelOrder }.sortedBy { channelOrder.indexOf(it.url) }
                channels.any { it.url == preferred } ->
                    channels.filter { it.url == preferred } + channels.filter { it.url != preferred }
                else -> channels
            }

            for (channel in ordered) {
                val base = channel.url
                val label = channel.name.ifBlank { base.take(40) }
                onProgress?.invoke("尝试通道：云端 $label")
                try {
                    // MaxHelper 走专用协议（auth + AES-GCM 信封 + rc 解密），不是通用 GET
                    // Hellotik 走专用协议（gate ticket 动态字段 + AES-GCM 信封 + AES-CBC 响应解密）
                    val media = when {
                        base.contains("maxhelper.app") -> parseViaMaxHelper(base, url, platform)
                        base.contains("hellotik.app") -> parseViaHellotik(base, url, platform)
                        base.contains("downcats.com") -> parseViaDowncats(base, shareText, platform)
                        base.contains("snapany.com") -> parseViaSnapany(base, url, platform)
                        base.contains("oivo.cn") -> parseViaOivo(base, url, platform)
                        base.contains("snaptik.net") -> parseViaSnapTok(base, url, platform)
                        base.contains("xiazaitool.com") -> parseViaXiazaitool(base, url, platform)
                        base.contains("kukutool.com") -> parseViaKukutool(base, shareText, platform)
                        base.contains("viddown.cn") -> parseViaVidDown(base, shareText, platform)
                        else -> {
                        val endpoint = if (base.contains("{url}"))
                            base.replace("{url}", java.net.URLEncoder.encode(url, "UTF-8"))
                        else
                            base + (if (base.contains('?')) '&' else '?') + "url=" +
                                java.net.URLEncoder.encode(url, "UTF-8")

                        val req = Request.Builder()
                            .url(endpoint)
                            .header("User-Agent", DESKTOP_UA)
                            .header("Accept", "application/json")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                channelErrors[label] = "HTTP ${resp.code}"
                                return@use emptyList()
                            }
                            val body = resp.body?.string().orEmpty()
                            if (body.isBlank()) {
                                channelErrors[label] = "响应为空"
                                return@use emptyList()
                            }
                            val json = try { JSONObject(body) } catch (_: Exception) {
                                channelErrors[label] = "非 JSON 响应（可能被防火墙拦截）"
                                return@use emptyList()
                            }
                            extractMedia(json, url, platform)
                        }
                    }
                    }
                    if (media.isNotEmpty()) {
                        LogFile.d("CloudParser", "通道 $label 成功，${media.size} 个媒体")
                        onProgress?.invoke("解析成功 · 云端 $label")
                        return@withContext fillMissingDurations(media)
                    } else {
                        // 失败原因可能已在内层（HTTP/JSON 解析）写入，没写就是纯无结果
                        if (!channelErrors.containsKey(label)) channelErrors[label] = "未解析到媒体"
                        onProgress?.invoke("通道失败：云端 $label —— ${channelErrors[label]}")
                    }
                } catch (e: Exception) {
                    channelErrors[label] = e.message ?: "请求异常"
                    onProgress?.invoke("通道失败：云端 $label —— ${e.message ?: "请求异常"}")
                    LogFile.w("CloudParser", "通道 $label 失败: ${e.message}")
                }
            }
            emptyList()
        }

    /**
     * MaxHelper 专用协议（2026-08-17 从站点 JS 逆向）：
     * 1. POST {root}/api/auth-cf6332，body={requestURL, pagePath, mode} → 响应 {k_cf6332: authKey, s_cf6332: authSeed}
     * 2. AES-GCM 加密请求：key = SHA-256("authKey:authSeed")，iv=12B 随机，明文=请求参数 JSON
     * 3. POST {root}/api/parse，body={version:3, k_cf6332, p_cf6332: b64密文, i_cf6332: b64 iv, r_cf6332: 1}
     * 4. 响应加密 {_e:1, _d, _i, _v}：GET {root}/api/rc?v=_v → {_k: hex密钥}，AES-CBC 解密 → 明文 JSON
     * 注意：auth 与 parse 必须同一 cookie 会话（用同一个 client 自动保持），否则 auth_session_mismatch。
     */
    private fun parseViaMaxHelper(base: String, shareUrl: String, platform: Platform): List<ParsedMedia> {
        val root = base.trimEnd('/')
        // MaxHelper 支持的功能页（2026-08-18 从站点 sitemap 确认）：
        // 国内 douyin/bilibili/xiaohongshu，国外 tiktok/instagram
        val pagePath = when (platform) {
            Platform.DOUYIN -> "/zh/douyin"
            Platform.BILIBILI -> "/zh/bilibili"
            Platform.XIAOHONGSHU -> "/zh/xiaohongshu"
            Platform.TIKTOK -> "/zh/tiktok"
            Platform.INSTAGRAM -> "/zh/instagram"
            else -> "/zh/douyin" // 未支持的平台兜底走 douyin 页，解析失败会记录到 channelErrors
        }

        // 1) auth 拿 authKey/authSeed
        val params = JSONObject().apply {
            put("requestURL", shareUrl)
            put("pagePath", pagePath)
            put("mode", "single")
        }
        val authResp = maxhelperPost(root, "$root/api/auth-cf6332", params) ?: run {
            channelErrors["MaxHelper"] = "auth 请求失败"; return emptyList()
        }
        val authKey = authResp.optString("k_cf6332")
        val authSeed = authResp.optString("s_cf6332")
        if (authKey.isBlank() || authSeed.isBlank()) {
            channelErrors["MaxHelper"] = "auth 未返回密钥（${authResp.optString("reason").ifBlank { authResp.optString("error") }}）"
            return emptyList()
        }

        // 2) AES-GCM 加密（key = SHA-256("authKey:authSeed")）
        val key = try {
            MessageDigest.getInstance("SHA-256")
                .digest("$authKey:$authSeed".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            channelErrors["MaxHelper"] = "SHA-256 失败: ${e.message}"; return emptyList()
        }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(params.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            channelErrors["MaxHelper"] = "AES-GCM 加密失败: ${e.message}"; return emptyList()
        }

        // 3) parse 信封
        val envelope = JSONObject().apply {
            put("version", 3)
            put("k_cf6332", authKey)
            put("p_cf6332", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            put("i_cf6332", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("r_cf6332", 1)
        }
        val parseResp = maxhelperPost(root, "$root/api/parse", envelope) ?: run {
            channelErrors["MaxHelper"] = "parse 请求失败"; return emptyList()
        }

        // 4) 解密响应（_e=1 表示加密）
        val dataJson = if (parseResp.optInt("_e") == 1) {
            val ver = parseResp.optString("_v", "1")
            val rcResp = maxhelperGet(root, "$root/api/rc?v=$ver") ?: run {
                channelErrors["MaxHelper"] = "rc 密钥获取失败"; return emptyList()
            }
            val rcKey = rcResp.optString("_k")
            if (rcKey.isBlank()) {
                channelErrors["MaxHelper"] = "rc 未返回密钥"; return emptyList()
            }
            decryptMaxHelperResponse(rcKey, parseResp.optString("_i"), parseResp.optString("_d")) ?: run {
                channelErrors["MaxHelper"] = "响应解密失败"; return emptyList()
            }
        } else parseResp

        return extractMaxHelperMedia(dataJson, shareUrl, platform)
    }

    /** MaxHelper POST JSON（与 auth/parse 共享同一 OkHttpClient 会话） */
    private fun maxhelperPost(root: String, path: String, body: JSONObject): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(path)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Origin", root)
                .header("Referer", "$root/zh/douyin")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 403 时服务端返回 {reason: "xx_cf6332"}，提取原因便于定位
                    val reason = try {
                        JSONObject(resp.body?.string().orEmpty()).optString("reason").takeIf { it.isNotBlank() }
                    } catch (_: Exception) { null }
                    LogFile.w("CloudParser", "maxhelper POST ${resp.code}: $path${reason?.let { " reason=$it" } ?: ""}")
                    return null
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            LogFile.w("CloudParser", "maxhelper POST 异常: ${e.message}")
            null
        }
    }

    private fun maxhelperGet(root: String, path: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(path)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Referer", "$root/zh/douyin")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            LogFile.w("CloudParser", "maxhelper GET 异常: ${e.message}")
            null
        }
    }

    /** AES-CBC 解密 maxhelper 响应（key=hex、iv=base64、data=base64，PKCS7） */
    private fun decryptMaxHelperResponse(keyHex: String, ivB64: String, dataB64: String): JSONObject? {
        return try {
            val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val data = Base64.decode(dataB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
        } catch (e: Exception) {
            LogFile.w("CloudParser", "maxhelper 响应解密失败: ${e.message}")
            null
        }
    }

    // ==================== Hellotik 专用协议（2026-08-23 从站点 JS 逆向） ====================
    //
    // 1. 协议字段名每周轮换（activeProfileId 如 2026w12）：先从 /zh 页面的 Next.js
    //    chunk 里抓 {"activeProfileId":...,"profiles":[...]} 配置，取当前生效 profile
    //    的 authRoute（如 gate-e5eea8）与字段名（tk_/sd_/pl_/iv_/vr_ 后缀）。
    // 2. POST /api/{authRoute}，body={requestURL, isBatch:false, mode:"single"}
    //    → 响应 {success, <tk_*>: ticket, <sd_*>: encSeed, <ex_*>: 过期时间}
    // 3. AES-GCM 加密请求参数：key = SHA-256("ticket:encSeed")，iv=12B 随机，
    //    明文={requestURL, isMobile, isoCode, adType, uwx_id, successCount, ...}
    // 4. POST /api/parse，信封={<tk_*>: ticket, <pl_*>: b64密文, <iv_*>: b64 iv, <vr_*>: 1}
    //    （vr 固定 1：站点浏览器端 process.env 为空 polyfill，版本常量回退 "1"）
    // 5. 响应 {status, encrypt?, data, key}：encrypt=1 时 data/key 经
    //    「XOR 90 → 8字符块反转 → 自定义 base64 字母表重映射」变换后，
    //    用固定密钥 AES-256-CBC 解密出明文 JSON（结构与 MaxHelper 一致：
    //    data.videos[]/data.pics[]/title/author，直接复用 extractMaxHelperMedia）。

    /** Hellotik 协议配置（authRoute + 动态字段名），按站点缓存，跨实例共享 */
    private data class HellotikProfile(
        val authRoute: String,
        val ticketKey: String, val ticketSeed: String,
        val reqKey: String, val reqPayload: String, val reqIv: String,
        val reqVersion: String
    )

    private fun hellotikError(msg: String): Nothing {
        channelErrors["Hellotik"] = msg
        throw HellotikStop(msg)
    }
    private class HellotikStop(msg: String) : Exception(msg)

    private fun hellotikProfile(root: String): HellotikProfile {
        hellotikProfileCache?.let { return it }
        val html = try {
            client.newCall(
                Request.Builder().url("$root/zh")
                    .header("User-Agent", DESKTOP_UA).build()
            ).execute().use { resp -> if (!resp.isSuccessful) "" else resp.body?.string().orEmpty() }
        } catch (e: Exception) {
            LogFile.w("CloudParser", "hellotik 页面请求失败: ${e.message}"); ""
        }
        if (html.isBlank()) hellotikError("页面请求失败")
        val chunks = CHUNK_SRC_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        for (chunk in chunks) {
            val body = try {
                client.newCall(
                    Request.Builder().url(if (chunk.startsWith("http")) chunk else "$root$chunk")
                        .header("User-Agent", DESKTOP_UA).build()
                ).execute().use { resp -> if (!resp.isSuccessful) null else resp.body?.string().orEmpty() }
            } catch (_: Exception) { null } ?: continue
            if (!body.contains("activeProfileId")) continue
            val m = PROFILE_JSON_REGEX.find(body) ?: continue
            val conf = try { JSONObject(m.value) } catch (_: Exception) { null } ?: continue
            val activeId = conf.optString("activeProfileId")
            val profiles = conf.optJSONArray("profiles") ?: continue
            for (i in 0 until profiles.length()) {
                val p = profiles.optJSONObject(i) ?: continue
                if (p.optString("id") != activeId) continue
                val tf = p.optJSONObject("ticketResponseFields")
                val pf = p.optJSONObject("parseRequestFields")
                if (tf == null || pf == null) continue
                val profile = HellotikProfile(
                    authRoute = p.optString("authRoute"),
                    ticketKey = tf.optString("key"), ticketSeed = tf.optString("seed"),
                    reqKey = pf.optString("key"), reqPayload = pf.optString("payload"),
                    reqIv = pf.optString("iv"), reqVersion = pf.optString("version")
                )
                if (profile.authRoute.isNotBlank() && profile.reqKey.isNotBlank()) {
                    hellotikProfileCache = profile
                    LogFile.d("CloudParser", "hellotik 协议配置：${profile.authRoute}（$activeId）")
                    return profile
                }
            }
        }
        hellotikError("协议配置获取失败（站点已更新）")
    }

    @Volatile private var hellotikProfileCache: HellotikProfile? = null

    private fun parseViaHellotik(base: String, shareUrl: String, platform: Platform): List<ParsedMedia> {
        val root = base.trimEnd('/')
        // hellotikParseOnce 内部 fail() 会抛 HellotikStop（穿透到 parseAll 终止该通道），
        // 必须在这里兜住，否则下面「profile 失效重试一次」永远执行不到（死代码）
        fun attempt(profile: HellotikProfile): List<ParsedMedia> =
            try { hellotikParseOnce(root, profile, shareUrl, platform) }
            catch (e: HellotikStop) { emptyList() }

        var profile = hellotikProfile(root)
        var result = attempt(profile)
        // 协议字段名每周轮换：命中「profile 失效/信封无效」时强制重新发现一次再试
        val reason = channelErrors["Hellotik"].orEmpty()
        if (result.isEmpty() && (reason.contains("失效") || reason.contains("INVALID") ||
                reason.contains("inactive") || reason.startsWith("ie_"))) {
            hellotikProfileCache = null
            profile = hellotikProfile(root)
            result = attempt(profile)
        }
        return result
    }

    private fun hellotikParseOnce(
        root: String, profile: HellotikProfile, shareUrl: String, platform: Platform
    ): List<ParsedMedia> {
        fun fail(msg: String): Nothing {
            channelErrors["Hellotik"] = msg
            throw HellotikStop(msg)
        }

        // 1) ticket
        val ticketBody = JSONObject().apply {
            put("requestURL", shareUrl); put("isBatch", false); put("mode", "single")
        }
        val ticketResp = hellotikPost(root, "$root/api/${profile.authRoute}", ticketBody)
            ?: fail("ticket 请求失败")
        val ticket = ticketResp.optString(profile.ticketKey)
        val encSeed = ticketResp.optString(profile.ticketSeed)
        if (ticket.isBlank() || encSeed.isBlank()) {
            fail("ticket 未返回密钥（${ticketResp.optString("reason").ifBlank { ticketResp.optString("error") }}）")
        }

        // 2) AES-GCM 加密请求参数（key = SHA-256("ticket:encSeed")）
        val params = JSONObject().apply {
            put("requestURL", shareUrl)
            put("isMobile", "false")
            put("isoCode", "Other")
            put("adType", "adsense")
            put("uwx_id", "uwxs_" + java.util.UUID.randomUUID().toString().replace("-", "").take(11))
            put("successCount", "0")
            put("totalSuccessCount", "0")
            put("firstSuccessDate", JSONObject.NULL)
            put("geoipIp", "")
        }
        val key = try {
            MessageDigest.getInstance("SHA-256")
                .digest("$ticket:$encSeed".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            fail("SHA-256 失败: ${e.message}")
        }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(params.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            fail("AES-GCM 加密失败: ${e.message}")
        }
        val envelope = JSONObject().apply {
            put(profile.reqKey, ticket)
            put(profile.reqPayload, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            put(profile.reqIv, Base64.encodeToString(iv, Base64.NO_WRAP))
            put(profile.reqVersion, 1)
        }

        // 3) parse
        val parseResp = hellotikPost(root, "$root/api/parse", envelope) ?: fail("parse 请求失败")
        if (parseResp.optInt("status", -1) != 0) {
            val reason = parseResp.optString("reason").ifBlank { parseResp.optString("error") }
            fail("解析被拒绝（status=${parseResp.optInt("status")} ${reason}）")
        }

        // 4) 响应解密（encrypt=1 时 data/key 为变换后的 base64）
        val dataJson: JSONObject = if (isEncryptedResponse(parseResp)) {
            val dataB64 = parseResp.optString("data")
            val keyB64 = parseResp.optString("key")
            if (dataB64.isBlank() || keyB64.isBlank()) fail("加密响应缺少 data/key")
            hellotikDecryptResponse(dataB64, keyB64) ?: fail("响应解密失败")
        } else {
            parseResp.optJSONObject("data") ?: fail("响应无 data")
        }

        // extractMaxHelperMedia 期望「{data:{...}}」包裹层，hellotik 的 dataJson 已是数据本体
        val media = extractMaxHelperMedia(JSONObject().put("data", dataJson), shareUrl, platform)
        LogFile.d(
            "CloudParser",
            "hellotik 提取：${media.size} 个媒体（data keys=${dataJson.keys().asSequence().toList()}）"
        )
        return media
    }

    /** Hellotik POST JSON（与 ticket/parse 共享同一 OkHttpClient 会话） */
    private fun hellotikPost(root: String, path: String, body: JSONObject): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(path)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Origin", root)
                .header("Referer", "$root/zh")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogFile.w("CloudParser", "hellotik POST ${resp.code}: $path")
                    return null
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            LogFile.w("CloudParser", "hellotik POST 异常: ${e.message}")
            null
        }
    }

    /**
     * 响应解密：data/key 两个 base64 先解出字节串，再逐字符 XOR 90、
     * 按 8 字符块反转、自定义 base64 字母表重映射回标准 base64；
     * 然后 AES-256-CBC（key=固定32字符密钥 UTF-8，iv=重映射后的 key 字段 base64）解密。
     */
    private fun hellotikDecryptResponse(dataB64: String, keyB64: String): JSONObject? {
        return try {
            val dataStd = hellotikUnmask(dataB64)
            val ivStd = hellotikUnmask(keyB64)
            val keyBytes = HELLOTIK_RESP_KEY.toByteArray(Charsets.UTF_8)
            val iv = Base64.decode(ivStd, Base64.NO_WRAP)
            val data = Base64.decode(dataStd, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
        } catch (e: Exception) {
            LogFile.w("CloudParser", "hellotik 响应解密失败: ${e.message}")
            null
        }
    }

    private fun hellotikUnmask(b64: String): String {
        val chars = Base64.decode(b64, Base64.NO_WRAP).toString(Charsets.ISO_8859_1).toCharArray()
        for (i in chars.indices) chars[i] = (chars[i].code xor 90).toChar()
        val sb = StringBuilder(chars.size)
        var i = 0
        while (i < chars.size) {
            val end = minOf(i + 8, chars.size)
            for (j in end - 1 downTo i) sb.append(chars[j])
            i += 8
        }
        return sb.toString().map { c ->
            val idx = HELLOTIK_CUSTOM_ALPHABET.indexOf(c)
            if (idx < 0) c else HELLOTIK_STANDARD_ALPHABET[idx]
        }.joinToString("")
    }

    // ==================== DownCats（downcats.com，2026-08-29 从站点 JS 逆向） ====================
    //
    // POST {root}/v1/extract/free/video，JSON body={text: 分享文本, locale: "zh"}
    // 响应 {code:"OK", data:{name, resourceType, video, lowRate, music, image[], text}}
    // video=视频直链、music=音乐直链、image[]=图片列表、text=标题。
    // 仅接视频解析；站点的「主页提取」需付费会员，App 不接入。

    private fun parseViaDowncats(base: String, shareText: String, platform: Platform): List<ParsedMedia> {
        val root = base.trimEnd('/')
        val resp = try {
            val req = Request.Builder()
                .url("$root/v1/extract/free/video")
                .header("User-Agent", DESKTOP_UA)
                .header("Content-Type", "application/json")
                .header("Origin", root)
                .header("Referer", "$root/zh/douyin")
                .post(JSONObject().apply {
                    put("text", shareText)
                    put("locale", "zh")
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    channelErrors["DownCats"] = "HTTP ${resp.code}"
                    return emptyList()
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            channelErrors["DownCats"] = e.message ?: "请求异常"
            return emptyList()
        }
        if (resp.optString("code") != "OK") {
            channelErrors["DownCats"] = resp.optString("message").ifBlank { "提取失败" }
            return emptyList()
        }
        val data = resp.optJSONObject("data") ?: run {
            channelErrors["DownCats"] = "响应无 data"
            return emptyList()
        }
        val title = data.optString("text").ifBlank { "video" }
        val images = mutableListOf<String>()
        data.optJSONArray("image")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.startsWith("http") }?.let { images.add(it) }
            }
        }
        val media = mutableListOf<ParsedMedia>()
        val video = data.optString("video")
        if (video.startsWith("http")) {
            media.add(
                ParsedMedia(
                    platform = platform,
                    videoUrl = video.replace("/playwm/", "/play/"),
                    coverUrl = images.firstOrNull(),
                    title = title,
                    rawShareText = shareText,
                    originalUrl = shareText
                )
            )
        }
        // 无视频时的背景音乐按音频条目出（下载端按扩展名存 .m4a）
        if (video.isBlank()) {
            val music = data.optString("music")
            if (music.startsWith("http")) {
                media.add(
                    ParsedMedia(
                        platform = platform,
                        videoUrl = music,
                        isMusic = true,
                        title = title,
                        rawShareText = shareText,
                        originalUrl = shareText
                    )
                )
            }
        }
        images.forEach { img ->
            media.add(
                ParsedMedia(
                    platform = platform,
                    imageUrl = img,
                    title = title,
                    rawShareText = shareText,
                    originalUrl = shareText
                )
            )
        }
        if (media.isEmpty()) channelErrors["DownCats"] = "未解析到媒体"
        return media
    }

    // ==================== SnapAny（snapany.com，2026-08-29 从站点 JS 逆向） ====================
    //
    // POST https://api.snapany.com/v1/extract/post，JSON body={link}
    // 鉴权头：G-Timestamp=毫秒时间戳，G-Footer=HMAC-SHA256(key, link+locale+时间戳) hex，
    // Accept-Language=locale（站点 chunk 225 内嵌 HMAC 密钥）。
    // 响应 {medias:[{media_type, resource_url, preview_url, variants[]}], title, site}
    // variants 是 DASH 音视频分离流（video_url+audio_url），无 ffmpeg 合不了，
    // 只保留自带音轨的混流档（audio_url 为空）做清晰度列表；resource_url 恒为混流直链。

    private fun parseViaSnapany(base: String, link: String, platform: Platform): List<ParsedMedia> {
        val endpoint = "https://api.snapany.com/v1/extract/post"
        val locale = "zh"
        val ts = System.currentTimeMillis().toString()
        val sig = try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(SNAPANY_HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.doFinal((link + locale + ts).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            channelErrors["SnapAny"] = "签名失败: ${e.message}"
            return emptyList()
        }
        val resp = try {
            val req = Request.Builder()
                .url(endpoint)
                .header("User-Agent", DESKTOP_UA)
                .header("Content-Type", "application/json")
                .header("Accept-Language", locale)
                .header("G-Timestamp", ts)
                .header("G-Footer", sig)
                .post(JSONObject().apply { put("link", link) }
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    channelErrors["SnapAny"] = "HTTP ${resp.code}"
                    return emptyList()
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            channelErrors["SnapAny"] = e.message ?: "请求异常"
            return emptyList()
        }
        val medias = resp.optJSONArray("medias")
        if (medias == null || medias.length() == 0) {
            channelErrors["SnapAny"] = resp.optString("message").ifBlank { "未解析到媒体" }
            return emptyList()
        }
        val title = resp.optString("title").ifBlank { "video" }
        val result = mutableListOf<ParsedMedia>()
        for (i in 0 until medias.length()) {
            val m = medias.optJSONObject(i) ?: continue
            val resUrl = m.optString("resource_url")
            if (!resUrl.startsWith("http")) continue
            // 混流档清晰度（variants 里 audio_url 为空的才是带音轨的单文件）
            val qualities = mutableListOf<MediaQuality>()
            m.optJSONArray("variants")?.let { arr ->
                for (v in 0 until arr.length()) {
                    val vo = arr.optJSONObject(v) ?: continue
                    val vu = vo.optString("video_url")
                    val au = vo.optString("audio_url")
                    if (!vu.startsWith("http") || au.isNotBlank()) continue
                    val label = vo.optString("quality_label").ifBlank { "默认" }
                    qualities.add(
                        MediaQuality(
                            label = label,
                            url = vu,
                            sizeBytes = vo.optLong("video_filesize", 0L),
                            height = MediaQuality.heightOf(label)
                        )
                    )
                }
            }
            val isImage = m.optString("media_type").contains("image", true) ||
                listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any { resUrl.substringBefore('?').lowercase().endsWith(it) }
            if (isImage) {
                result.add(
                    ParsedMedia(
                        platform = platform,
                        imageUrl = resUrl,
                        title = title,
                        rawShareText = link,
                        originalUrl = link
                    )
                )
            } else {
                val sorted = qualities.sortedWith(
                    compareByDescending<MediaQuality> { it.height }.thenByDescending { it.sizeBytes }
                ).distinctBy { it.url }
                result.add(
                    ParsedMedia(
                        platform = platform,
                        videoUrl = (sorted.firstOrNull()?.url ?: resUrl).replace("/playwm/", "/play/"),
                        qualities = sorted.map { it.copy(url = it.url.replace("/playwm/", "/play/")) },
                        coverUrl = m.optString("preview_url").takeIf { it.startsWith("http") },
                        title = title,
                        rawShareText = link,
                        originalUrl = link
                    )
                )
            }
        }
        if (result.isEmpty()) channelErrors["SnapAny"] = "未解析到媒体"
        return result
    }

    // ==================== Oivo（dy.oivo.cn，仅抖音，2026-08-29 从站点 JS 逆向） ====================
    //
    // 站点前端转发到 https://api.tangdouz.com/dy.php?lj={链接}&return=json
    // 响应 {title, author?, music, list:[{type:"video"|"image", url, jumpurl}]}
    // 视频下载走 item.jumpurl（无水印直链），图片 type=image。

    private fun parseViaOivo(base: String, link: String, platform: Platform): List<ParsedMedia> {
        if (platform != Platform.DOUYIN) {
            channelErrors["Oivo"] = "该通道仅支持抖音"
            return emptyList()
        }
        val endpoint = "https://api.tangdouz.com/dy.php?lj=" +
            java.net.URLEncoder.encode(link, "UTF-8") + "&return=json"
        val resp = try {
            val req = Request.Builder()
                .url(endpoint)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    channelErrors["Oivo"] = "HTTP ${resp.code}"
                    return emptyList()
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            channelErrors["Oivo"] = e.message ?: "请求异常"
            return emptyList()
        }
        resp.optString("error").takeIf { it.isNotBlank() }?.let {
            channelErrors["Oivo"] = it
            return emptyList()
        }
        val title = resp.optString("title").ifBlank { "video" }
        val author = resp.optString("author").ifBlank { resp.optString("nickname") }
        val media = mutableListOf<ParsedMedia>()
        resp.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val u = item.optString("jumpurl").ifBlank { item.optString("url") }
                if (!u.startsWith("http")) continue
                if (item.optString("type") == "image") {
                    media.add(
                        ParsedMedia(
                            platform = platform,
                            imageUrl = u,
                            title = title,
                            author = author,
                            rawShareText = link,
                            originalUrl = link
                        )
                    )
                } else {
                    media.add(
                        ParsedMedia(
                            platform = platform,
                            videoUrl = u.replace("/playwm/", "/play/"),
                            title = title,
                            author = author,
                            coverUrl = resp.optString("cover").takeIf { it.startsWith("http") },
                            rawShareText = link,
                            originalUrl = link
                        )
                    )
                }
            }
        }
        if (media.isEmpty()) channelErrors["Oivo"] = "未解析到媒体"
        return media
    }

    // ==================== SnapTok（snaptik.net，2026-08-29 从站点 JS 逆向） ====================
    //
    // POST /api/ajaxSearch，表单 body={q: 链接, lang: "zh-cn"}（仅收抖音/TikTok 链接）。
    // 响应 {status:"ok", data:"<HTML 片段>"}：片段里 <a href> 下载链接（下载 MP4=标准档、
    // MP4 HD=高清档、MP3=音乐）+ <h3> 标题 + <img> 封面。下载链接经
    // dl.snapcdn.app/get?token=JWT 302 到真实 CDN，OkHttp 自动跟随。

    private fun parseViaSnapTok(base: String, link: String, platform: Platform): List<ParsedMedia> {
        val root = base.trimEnd('/')
        val body = "q=" + java.net.URLEncoder.encode(link, "UTF-8") + "&lang=zh-cn"
        val html = try {
            val req = Request.Builder()
                .url("$root/api/ajaxSearch")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$root/zh-cn")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    channelErrors["SnapTok"] = "HTTP ${resp.code}"
                    return emptyList()
                }
                val json = try { JSONObject(resp.body?.string().orEmpty()) } catch (_: Exception) {
                    channelErrors["SnapTok"] = "非 JSON 响应"
                    return emptyList()
                }
                if (json.optString("status") != "ok") {
                    channelErrors["SnapTok"] = json.optString("msg").ifBlank { "解析被拒绝" }
                    return emptyList()
                }
                json.optString("data")
            }
        } catch (e: Exception) {
            channelErrors["SnapTok"] = e.message ?: "请求异常"
            return emptyList()
        }
        if (html.isBlank()) {
            channelErrors["SnapTok"] = "响应无结果"
            return emptyList()
        }

        val title = Regex("<h3>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1)
            ?.let { raw ->
                raw.replace(Regex("<[^>]*>"), "")
                    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                    .replace("&lt;", "<").replace("&gt;", ">")
                    .trim().takeIf { it.isNotBlank() }
            }
        val cover = Regex("<img[^>]*src=\"(https?://[^\"]+)\"").find(html)
            ?.groupValues?.get(1)?.replace("&amp;", "&")

        // 下载链接按文本分类：MP4 HD=高清、MP4=标准、MP3=音乐
        var hdUrl: String? = null
        var sdUrl: String? = null
        var musicUrl: String? = null
        for (m in Regex("<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)) {
            val label = m.groupValues[2].replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
            val href = m.groupValues[1].replace("&amp;", "&")
            when {
                label.contains("MP4 HD", true) -> if (hdUrl == null) hdUrl = href
                label.contains("MP4", true) -> if (sdUrl == null) sdUrl = href
                label.contains("MP3", true) -> if (musicUrl == null) musicUrl = href
            }
        }

        val result = mutableListOf<ParsedMedia>()
        val best = hdUrl ?: sdUrl
        if (best != null) {
            val qualities = listOfNotNull(hdUrl, sdUrl).distinct().mapIndexed { i, u ->
                MediaQuality(
                    label = if (u == hdUrl && hdUrl != sdUrl) "高清" else "标准",
                    url = u.replace("/playwm/", "/play/"),
                    sizeBytes = 0,
                    height = if (u == hdUrl && hdUrl != sdUrl) 1080 else 720
                )
            }.distinctBy { it.url }
            result.add(
                ParsedMedia(
                    platform = platform,
                    videoUrl = best.replace("/playwm/", "/play/"),
                    qualities = qualities,
                    coverUrl = cover,
                    title = title ?: "video",
                    rawShareText = link,
                    originalUrl = link
                )
            )
        }
        if (musicUrl != null && best == null) {
            // 纯音乐内容（无视频档）按音频条目出
            result.add(
                ParsedMedia(
                    platform = platform,
                    videoUrl = musicUrl,
                    isMusic = true,
                    coverUrl = cover,
                    title = title ?: "audio",
                    rawShareText = link,
                    originalUrl = link
                )
            )
        }
        if (result.isEmpty()) channelErrors["SnapTok"] = "未解析到媒体"
        return result
    }

    // ==================== XiaZaiTool（xiazaitool.com 下载狗，2026-08-29 从站点 JS 逆向） ====================
    //
    // POST /video/parseVideoUrl，JSON body={url, platform, params}：
    // params = SHA-256(salt + url + platform) hex，salt="bf5941f27ee14d9ba9ebb72d89de5dea"。
    // 响应 {status:200, data:{voideDeatilVoList:[{url, title, type}], title, coverUrls}}。
    // 平台 key：douyin/kuaishou/bilibili/xhs/weibo/tiktok/youtube/facebook/instagram（免登录）。

    private fun parseViaXiazaitool(base: String, link: String, platform: Platform): List<ParsedMedia> {
        val platformKey = when (platform) {
            Platform.DOUYIN -> "douyin"
            Platform.KUAISHOU -> "kuaishou"
            Platform.BILIBILI -> "bilibili"
            Platform.XIAOHONGSHU -> "xhs"
            Platform.WEIBO -> "weibo"
            Platform.TIKTOK -> "tiktok"
            Platform.YOUTUBE -> "youtube"
            Platform.FACEBOOK -> "facebook"
            Platform.INSTAGRAM -> "instagram"
            else -> {
                channelErrors["XiaZaiTool"] = "该平台不受此通道支持"
                return emptyList()
            }
        }
        val params = try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(("bf5941f27ee14d9ba9ebb72d89de5dea" + link + platformKey).toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            channelErrors["XiaZaiTool"] = "签名失败: ${e.message}"
            return emptyList()
        }
        val resp = try {
            val body = JSONObject().apply {
                put("url", link)
                put("platform", platformKey)
                put("params", params)
            }
            val req = Request.Builder()
                .url("https://www.xiazaitool.com/video/parseVideoUrl")
                .header("User-Agent", DESKTOP_UA)
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.xiazaitool.com")
                .header("Referer", "https://www.xiazaitool.com/")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    channelErrors["XiaZaiTool"] = "HTTP ${resp.code}"
                    return emptyList()
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            channelErrors["XiaZaiTool"] = e.message ?: "请求异常"
            return emptyList()
        }
        if (resp.optInt("status") != 200) {
            channelErrors["XiaZaiTool"] = resp.optString("message").ifBlank { "status=${resp.optInt("status")}" }
            return emptyList()
        }
        val data = resp.optJSONObject("data") ?: run {
            channelErrors["XiaZaiTool"] = "响应无 data"
            return emptyList()
        }
        // org.json 的 optString 对「字段存在但值为 JSON null」会返回字符串 "null"，需过滤
        val title = data.optString("title").takeUnless { it.isBlank() || it == "null" } ?: "video"
        val result = mutableListOf<ParsedMedia>()
        data.optJSONArray("voideDeatilVoList")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val u = item.optString("url")
                if (!u.startsWith("http")) continue
                val isVideo = item.optString("type").contains("video", true) ||
                    VIDEO_HINTS.any { u.contains(it, true) }
                if (isVideo) {
                    result.add(
                        ParsedMedia(
                            platform = platform,
                            videoUrl = u.replace("/playwm/", "/play/"),
                            title = item.optString("title").ifBlank { title },
                            rawShareText = link,
                            originalUrl = link
                        )
                    )
                } else {
                    result.add(
                        ParsedMedia(
                            platform = platform,
                            imageUrl = u,
                            title = item.optString("title").ifBlank { title },
                            rawShareText = link,
                            originalUrl = link
                        )
                    )
                }
            }
        }
        if (result.isEmpty()) channelErrors["XiaZaiTool"] = "未解析到媒体"
        return result
    }

    // ==================== Kukutool（dy.kukutool.com，2026-08-29 从站点 JS 逆向） ====================
    //
    // MaxHelper 同款三步协议（字段名在站点前端 chunk 里轮换，需动态发现）：
    // 1. POST /api/{authRoute}，body={requestURL, pagePath, mode:"single"} → {<k_*>: authKey, <s_*>: authSeed}
    // 2. AES-GCM 加密请求参数（key=SHA-256("authKey:authSeed")），信封
    //    {version:3, <k_*>: authKey, <p_*>: b64密文, <i_*>: b64 iv, <r_*>: "1"}
    // 3. POST /api/parse → {status, encrypt, data, iv}；encrypt 时 data/iv 为原始掩码串：
    //    逐字符 XOR 90 → 8 字符块反转 → 自定义 base64 字母表重映射 → 标准 base64，
    //    AES-256-CBC（key=SHA-256("12345678901234567890123456789013")）解密出明文 JSON，
    //    结构与 MaxHelper 一致（title/cover/url/videos[]/pics[]），复用 extractMaxHelperMedia。

    private data class KukutoolProfile(
        val authRoute: String,
        val key: String, val seed: String,
        val payload: String, val iv: String, val version: String
    )

    @Volatile private var kukutoolProfileCache: KukutoolProfile? = null

    /** 取文本（失败返回 null），供 Kukutool 协议发现使用 */
    private fun kukutoolText(url: String): String? = try {
        client.newCall(
            Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
        ).execute().use { resp -> if (!resp.isSuccessful) null else resp.body?.string() }
    } catch (e: Exception) {
        LogFile.w("CloudParser", "kukutool 请求失败 $url: ${e.message}")
        null
    }

    /**
     * 从站点首页全部 chunk 里动态发现协议配置（authRoute + 字段名，站点轮换时自动跟上）。
     *
     * 2026-09-27 实测：配置所在 chunk 号会随构建变化（原写死的 9802 已不存在，现为 1778），
     * 且 wafcdn 会间歇返回 39 字节挑战页（无 chunk 列表），故改为「扫描全部 chunk + 首页重试」。
     */
    private fun kukutoolProfile(root: String): KukutoolProfile? {
        kukutoolProfileCache?.let { return it }

        var chunks = emptyList<String>()
        for (attempt in 1..KUKUTOOL_PAGE_TRIES) {
            val html = kukutoolText("$root/").orEmpty()
            chunks = CHUNK_SRC_REGEX.findAll(html).map { it.groupValues[1] }.toList()
            if (chunks.isNotEmpty()) break
            LogFile.w("CloudParser", "kukutool 首页未返回 chunk 列表（疑似 wafcdn 挑战页），第 $attempt 次")
        }
        if (chunks.isEmpty()) {
            channelErrors["Kukutool"] = "协议配置入口未找到（站点已更新）"
            return null
        }

        // 配置藏在某个 chunk 里：新版是 JSON.parse('…') 字面量，旧版是裸对象
        for (chunkUrl in chunks) {
            val chunk = kukutoolText(if (chunkUrl.startsWith("http")) chunkUrl else "$root$chunkUrl") ?: continue
            if (!chunk.contains("activeProfileId")) continue
            val confText = KUKUTOOL_CONF_WRAPPED_REGEX.find(chunk)?.groupValues?.get(1)
                ?: PROFILE_JSON_REGEX.find(chunk)?.value
                ?: continue
            val conf = try { JSONObject(confText) } catch (_: Exception) { continue }
            val activeId = conf.optString("activeProfileId")
            val profiles = conf.optJSONArray("profiles") ?: continue
            for (i in 0 until profiles.length()) {
                val p = profiles.optJSONObject(i) ?: continue
                if (p.optString("id") != activeId) continue
                val rf = p.optJSONObject("authResponseFields")
                val pf = p.optJSONObject("parseRequestFields")
                if (rf == null || pf == null) continue
                val profile = KukutoolProfile(
                    authRoute = p.optString("authRoute"),
                    key = rf.optString("key"), seed = rf.optString("seed"),
                    payload = pf.optString("payload"), iv = pf.optString("iv"),
                    version = pf.optString("version")
                )
                if (profile.authRoute.isNotBlank() && profile.key.isNotBlank()) {
                    kukutoolProfileCache = profile
                    LogFile.d("CloudParser", "kukutool 协议配置：${profile.authRoute}（$activeId）")
                    return profile
                }
            }
        }
        channelErrors["Kukutool"] = "协议配置获取失败（站点已更新）"
        return null
    }

    private fun parseViaKukutool(base: String, shareText: String, platform: Platform): List<ParsedMedia> {
        val root = base.trimEnd('/')
        val url = PlatformParser.extractUrl(shareText) ?: shareText
        val profile = kukutoolProfile(root) ?: return emptyList()

        fun fail(msg: String): List<ParsedMedia> {
            channelErrors["Kukutool"] = msg
            return emptyList()
        }

        // 1) auth
        val authResp = try {
            val body = JSONObject().apply {
                put("requestURL", url)
                put("pagePath", "")
                put("mode", "single")
            }
            val req = Request.Builder()
                .url("$root/api/${profile.authRoute}")
                .header("User-Agent", DESKTOP_UA)
                .header("Content-Type", "application/json")
                .header("Origin", root)
                .header("Referer", "$root/")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                try { JSONObject(resp.body?.string().orEmpty()) } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            LogFile.w("CloudParser", "kukutool auth 异常: ${e.message}"); null
        } ?: return fail("auth 请求失败")
        val authKey = authResp.optString(profile.key)
        val authSeed = authResp.optString(profile.seed)
        if (authKey.isBlank() || authSeed.isBlank()) {
            return fail("auth 未返回密钥（${authResp.optString("reason").ifBlank { authResp.optString("message") }}）")
        }

        // 2) AES-GCM 加密请求参数
        val key = try {
            MessageDigest.getInstance("SHA-256")
                .digest("$authKey:$authSeed".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            return fail("SHA-256 失败: ${e.message}")
        }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(
                JSONObject().apply {
                    put("requestURL", url)
                    put("captchaKey", "")
                    put("captchaInput", "")
                    put("totalSuccessCount", "0")
                    put("successCount", "0")
                    put("firstSuccessDate", "")
                    put("pagePath", "")
                    put("uwx_id", "uwxs_" + java.util.UUID.randomUUID().toString().replace("-", "").take(11))
                    put("isMobile", "false")
                    put("geoipIp", "")
                }.toString().toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            return fail("AES-GCM 加密失败: ${e.message}")
        }

        // 3) parse（信封 version 字段恒为 "1"，与站点前端一致）
        val envelope = JSONObject().apply {
            put("version", 3)
            put(profile.key, authKey)
            put(profile.payload, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            put(profile.iv, Base64.encodeToString(iv, Base64.NO_WRAP))
            put(profile.version, "1")
        }
        val parseResp = try {
            val req = Request.Builder()
                .url("$root/api/parse")
                .header("User-Agent", DESKTOP_UA)
                .header("Content-Type", "application/json")
                .header("Origin", root)
                .header("Referer", "$root/")
                .post(envelope.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                try { JSONObject(resp.body?.string().orEmpty()) } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            LogFile.w("CloudParser", "kukutool parse 异常: ${e.message}"); null
        } ?: return fail("parse 请求失败")

        if (parseResp.has("securityDialog")) {
            return fail(parseResp.optString("reason").ifBlank { "解析被拒绝" })
        }
        if (parseResp.optInt("status", -1) != 0) {
            return fail("解析被拒绝（status=${parseResp.optInt("status")} ${parseResp.optString("message")}）")
        }
        val dataJson: JSONObject = if (isEncryptedResponse(parseResp)) {
            val dataRaw = parseResp.optString("data")
            val ivRaw = parseResp.optString("iv")
            if (dataRaw.isBlank() || ivRaw.isBlank()) return fail("加密响应缺少 data/iv")
            decryptKukutoolResponse(dataRaw, ivRaw) ?: return fail("响应解密失败")
        } else {
            parseResp.optJSONObject("data") ?: return fail("响应无 data")
        }
        // 解密后的数据与 MaxHelper 格式一致，复用同一提取器
        val media = extractMaxHelperMedia(JSONObject().put("data", dataJson), url, platform)
        if (media.isEmpty()) return fail("未解析到媒体")
        LogFile.d("CloudParser", "kukutool 提取：${media.size} 个媒体")
        return media
    }

    /** kukutool 响应解密：data/iv 均为原始掩码串（XOR 90 → 8 字符块反转 → 字母表重映射 → base64） */
    private fun decryptKukutoolResponse(dataRaw: String, ivRaw: String): JSONObject? {
        return try {
            val dataB64 = kukutoolUnmask(dataRaw)
            val ivB64 = kukutoolUnmask(ivRaw)
            val keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(KUKUTOOL_RESP_KEY.toByteArray(Charsets.UTF_8))
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val data = Base64.decode(dataB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
        } catch (e: Exception) {
            LogFile.w("CloudParser", "kukutool 响应解密失败: ${e.message}")
            null
        }
    }

    private fun kukutoolUnmask(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val end = minOf(i + 8, raw.length)
            for (j in end - 1 downTo i) sb.append((raw[j].code xor 90).toChar())
            i += 8
        }
        return sb.toString().map { c ->
            val idx = HELLOTIK_CUSTOM_ALPHABET.indexOf(c)
            if (idx < 0) c else HELLOTIK_STANDARD_ALPHABET[idx]
        }.joinToString("")
    }

    // ==================== VidDown（viddown.cn，2026-08-29 从站点 JS 逆向） ====================
    //
    // 两步异步任务制：
    // 1. POST {root}/（multipart 表单 url + csrfmiddlewaretoken，token 从首页表单取，
    //    与会话 cookie 配套，client 的 CookieJar 自动保持）
    // 2. 轮询 GET /task/{taskId}/info/：status=failed 报错、pending 继续等、
    //    无 status（或 completed）即为结果：
    //    {title, formats:[{ext,url,vcodec,has_audio,filesize,resolution}], duration,
    //     uploader, thumbnail, audio_download_url}
    // formats 可能含无音轨的分段流（has_audio=false），优先带音轨的档。

    private fun parseViaVidDown(base: String, shareText: String, platform: Platform): List<ParsedMedia> {
        val root = base.trimEnd('/')
        val url = PlatformParser.extractUrl(shareText) ?: shareText
        fun fail(msg: String): List<ParsedMedia> {
            channelErrors["VidDown"] = msg
            return emptyList()
        }

        // 1) 取 CSRF token（首页表单隐藏域，与 cookie 会话配套）
        val csrf = try {
            client.newCall(
                Request.Builder().url("$root/")
                    .header("User-Agent", DESKTOP_UA).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return fail("首页请求失败（HTTP ${resp.code}）")
                Regex("""name="csrfmiddlewaretoken" value="([^"]+)"""")
                    .find(resp.body?.string().orEmpty())?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            return fail("首页请求异常：${e.message}")
        } ?: return fail("未取到 CSRF token")

        // 2) 提交解析任务
        val taskId: Long = try {
            val form = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("url", url)
                .addFormDataPart("csrfmiddlewaretoken", csrf)
                .build()
            val req = Request.Builder()
                .url("$root/")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$root/")
                .header("X-CSRFToken", csrf)
                .post(form)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return fail("提交失败（HTTP ${resp.code}）")
                val json = try { JSONObject(resp.body?.string().orEmpty()) } catch (_: Exception) { null }
                json?.optLong("task_id", 0L)?.takeIf { it > 0 }
                    ?: return fail(json?.optString("error")?.takeIf { it.isNotBlank() } ?: "未返回任务 ID")
            }
        } catch (e: Exception) {
            return fail("提交异常：${e.message}")
        }

        // 3) 轮询结果（站点前端最多等 ~3 分钟，这里 90s 上限，3s 一次）
        var result: JSONObject? = null
        var consecutiveFails = 0
        for (i in 1..30) {
            Thread.sleep(3000)
            val data = try {
                client.newCall(
                    Request.Builder().url("$root/task/$taskId/info/")
                        .header("User-Agent", DESKTOP_UA)
                        .header("Referer", "$root/")
                        .build()
                ).execute().use { resp ->
                    if (resp.code == 500) return@use null
                    try { JSONObject(resp.body?.string().orEmpty()) } catch (_: Exception) { null }
                }
            } catch (_: Exception) {
                null
            }
            if (data == null) {
                // 500/网络错误：连续失败 3 次才放弃（一次网络抖动不该判死 pending 任务）
                consecutiveFails++
                if (consecutiveFails >= 3) return fail("任务查询失败")
                continue
            }
            consecutiveFails = 0
            when (data.optString("status")) {
                "failed" -> return fail(data.optString("error_message").ifBlank { data.optString("error").ifBlank { "解析失败" } })
                "", "completed" -> { result = data; break }
                else -> Unit // pending/parsing 等下一轮
            }
        }
        val data = result ?: return fail("解析超时")
        // org.json 的 optString 对「字段存在但值为 JSON null」会返回字符串 "null"，需过滤
        fun jstr(key: String): String =
            data.optString(key).takeUnless { it.isBlank() || it == "null" } ?: ""
        val title = jstr("title").ifBlank { "video" }
        val author = jstr("uploader")
        val cover = jstr("thumbnail").takeIf { it.startsWith("http") }
        // duration 单位未知（秒居多），>10000 视为毫秒
        val durationRaw = data.optLong("duration", 0L)
        val durationMs = when {
            durationRaw <= 0L -> 0L
            durationRaw > 10_000L -> durationRaw
            else -> durationRaw * 1000L
        }

        // 4) formats → 视频/图片档（优先带音轨的流，无音轨的 DASH 分段合不了）
        val videoFormats = mutableListOf<JSONObject>()
        val imageUrls = mutableListOf<String>()
        data.optJSONArray("formats")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val u = f.optString("url")
                if (!u.startsWith("http")) continue
                val ext = f.optString("ext").lowercase()
                if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) {
                    imageUrls.add(u)
                } else if (f.optBoolean("has_audio", true) || f.optString("vcodec") == "none") {
                    videoFormats.add(f)
                }
            }
        }
        // 全是无音轨分段时退回全部分段（至少有画面）
        val usable = if (videoFormats.isEmpty()) {
            data.optJSONArray("formats")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { videoFormats.add(it) }
            }
            videoFormats
        } else videoFormats

        val result2 = mutableListOf<ParsedMedia>()
        if (usable.isNotEmpty()) {
            val qualities = usable.map { f ->
                val label = f.optString("resolution").ifBlank { "默认" }
                MediaQuality(
                    label = label,
                    url = f.optString("url").replace("/playwm/", "/play/"),
                    sizeBytes = f.optLong("filesize", 0L),
                    height = MediaQuality.heightOf(label)
                )
            }.sortedWith(compareByDescending<MediaQuality> { it.height }.thenByDescending { it.sizeBytes })
                .distinctBy { it.url }
            result2.add(
                ParsedMedia(
                    platform = platform,
                    videoUrl = qualities.first().url,
                    qualities = qualities,
                    coverUrl = cover,
                    title = title,
                    author = author,
                    duration = durationMs,
                    rawShareText = shareText,
                    originalUrl = shareText
                )
            )
        }
        if (result2.isEmpty()) {
            data.optString("audio_download_url").takeIf { it.startsWith("http") }?.let {
                result2.add(
                    ParsedMedia(
                        platform = platform,
                        videoUrl = it,
                        isMusic = true,
                        coverUrl = cover,
                        title = title,
                        author = author,
                        rawShareText = shareText,
                        originalUrl = shareText
                    )
                )
            }
        }
        imageUrls.forEach { img ->
            result2.add(
                ParsedMedia(
                    platform = platform,
                    imageUrl = img,
                    title = title,
                    author = author,
                    rawShareText = shareText,
                    originalUrl = shareText
                )
            )
        }
        if (result2.isEmpty()) return fail("未解析到媒体")
        LogFile.d("CloudParser", "viddown 提取：${result2.size} 个媒体")
        return result2
    }

    /** 单个视频的远程探测结果 */
    private class ProbeResult(val duration: Long, val framePath: String?)    /**
     * 云解析响应没有任何时长字段（实测 video_fullinfo 恒为空），云端给的封面也
     * 不一定是视频实拍内容。这里对缺时长的视频并行做远程探测：
     * - 时长：MediaMetadataRetriever 读远程文件头（带 Referer，B站 CDN 无 Referer 会 403）；
     * - 首帧缩略图：所有视频都用各自直链抽第一帧存 cacheDir——与解析网站用
     *   <video> 渲染首帧同理；缩略图即本地文件，首页解析列表与下载栏全程走本地，
     *   下载完成后再切到下载的媒体文件本身。
     * 单个超时/失败只影响自己，探测失败不影响解析结果（无时长角标、保留云端封面兜底）。
     */
    internal suspend fun fillMissingDurations(media: List<ParsedMedia>): List<ParsedMedia> {
        // googlevideo 直链绑定解析服务 IP，远程探测必失败（白白多等几秒），直接跳过
        val missing = media.filter {
            it.videoUrl != null && it.duration <= 0 && !it.videoUrl!!.contains("googlevideo.com")
        }
        if (missing.isEmpty()) return media
        val durations = java.util.Collections.synchronizedMap(HashMap<String, Long>())
        val frames = java.util.Collections.synchronizedMap(HashMap<String, String>())
        // 探测在守护线程跑：setDataSource 等阻塞调用无法被协程取消，CDN 卡住时
        // join 超时后直接弃置线程继续解析（弃置线程最终自行结束，结果被丢弃）
        val threads = missing.map { m ->
            val url = m.videoUrl.orEmpty()
            Thread {
                val r = runCatching { probeRemoteVideo(url, m.originalUrl) }.getOrNull()
                if (r != null && url.isNotBlank()) {
                    if (r.duration > 0) durations[url] = r.duration
                    r.framePath?.let { frames[url] = it }
                }
            }.apply { isDaemon = true; start() }
        }
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS + 2_000
        threads.forEach { t ->
            val remain = deadline - System.currentTimeMillis()
            if (remain > 0) t.join(remain)
        }
        if (durations.isEmpty() && frames.isEmpty()) return media
        LogFile.d("CloudParser", "远程探测：${durations.size} 个补时长，${frames.size} 个抽首帧")
        return media.map { m ->
            val url = m.videoUrl ?: return@map m
            var out = m
            if (out.duration <= 0) durations[url]?.let { out = out.copy(duration = it) }
            frames[url]?.let { out = out.copy(coverUrl = it) }
            out
        }
    }

    /** 同一 retriever 会话读时长 + 抽首帧，避免重复拉流。
     *  setDataSource/getFrameAtTime 是无法被协程取消的阻塞调用，CDN 变慢时会
     *  卡住整个解析——挂看门狗定时器到时强制 release() 解除阻塞。 */
    private fun probeRemoteVideo(url: String, referer: String): ProbeResult? {
        val retriever = android.media.MediaMetadataRetriever()
        val watchdog = java.util.Timer(true)
        try {
            watchdog.schedule(object : java.util.TimerTask() {
                override fun run() {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }, PROBE_TIMEOUT_MS)
            val headers = HashMap<String, String>()
            headers["User-Agent"] = DESKTOP_UA
            // B站 CDN 校验 Referer 必须是 bilibili.com 域，b23.tv 短链不行
            val ref = if (referer.contains("b23.tv")) "https://www.bilibili.com" else referer
            if (ref.isNotBlank()) headers["Referer"] = ref
            retriever.setDataSource(url, headers)
            val dur = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            return ProbeResult(dur, extractFrameFile(retriever, url))
        } catch (e: Exception) {
            LogFile.w("CloudParser", "远程探测失败: ${e.message}")
            return null
        } finally {
            watchdog.cancel()
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** 抽视频第一帧存本地缓存（≤720px JPEG），返回文件路径；失败返回 null */
    private fun extractFrameFile(retriever: android.media.MediaMetadataRetriever, videoUrl: String): String? {
        return try {
            val md = MessageDigest.getInstance("MD5").digest(videoUrl.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val file = java.io.File(java.io.File(context.cacheDir, "vframes"), "vframe_$md.jpg")
            if (file.exists() && file.length() > 0) return file.absolutePath
            val raw = retriever.getFrameAtTime(0) ?: return null
            val side = maxOf(raw.width, raw.height)
            val bmp = if (side > 720) {
                val ratio = 720f / side
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * ratio).toInt().coerceAtLeast(1),
                    (raw.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else raw
            file.parentFile?.mkdirs()
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            file.absolutePath
        } catch (e: Exception) {
            LogFile.w("CloudParser", "抽帧失败: ${e.message}")
            null
        }
    }

    /**
     * 从 maxhelper 解析结果提取媒体：
     * data.videos[].url（视频列表，每项带 video_fullinfo 清晰度数组）+ data.pics[]（图片列表）。
     * videos[] 缺失时用 data.url 兜底（与站点页面一致：页面只消费 videos[]）。
     *
     * 清晰度：video_fullinfo[] 每项 {type, size, url}（如 540p/720p/1080p/超高清），
     * 默认 url 只是 720p 档——全部档位保留进 qualities（高度降序），
     * videoUrl 取最高档，回落默认 url。
     *
     * 云端不返回视频与图片的对应关系，不做任何配对与丢弃：
     * 返回多少媒体就出多少项（先视频后图片），每个媒体都不会丢。
     * 视频缩略图先用作品 cover 兜底，随后由 fillMissingDurations 用各自
     * 直链抽首帧（本地文件）替换——所有视频各用各的实拍首帧。
     */
    private fun extractMaxHelperMedia(root: JSONObject, shareUrl: String, platform: Platform): List<ParsedMedia> {
        val data = root.optJSONObject("data") ?: return emptyList()
        val title = data.optString("title")
        val author = data.optString("author").ifBlank { data.optString("nickname") }
        val cover = data.optString("cover").ifBlank { null }

        // 视频：videos[] 优先（带清晰度列表），按最终直链去重
        val videos = LinkedHashMap<String, List<MediaQuality>>()
        val videoArray = data.optJSONArray("videos")
        if (videoArray != null) {
            for (i in 0 until videoArray.length()) {
                val o = videoArray.optJSONObject(i)
                if (o != null) {
                    val qualities = extractQualities(o)
                    val u = qualities.firstOrNull()?.url ?: o.optString("url")
                    if (u.startsWith("http") && u !in videos) videos[u] = qualities
                } else {
                    val u = videoArray.optString(i)
                    if (u.startsWith("http") && u !in videos) videos[u] = emptyList()
                }
            }
        }
        if (videos.isEmpty()) {
            data.optString("url").takeIf { it.startsWith("http") }?.let { videos[it] = emptyList() }
        }

        // 图片：pics[]（字符串或 {url} 对象）
        val images = mutableListOf<String>()
        data.optJSONArray("pics")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                val u = if (o != null) o.optString("url") else arr.optString(i)
                if (u.startsWith("http")) images.add(u)
            }
        }

        if (videos.isEmpty() && images.isEmpty()) return emptyList()

        fun videoItem(url: String, qualities: List<MediaQuality>, coverUrl: String?) = ParsedMedia(
            platform = platform,
            videoUrl = url.replace("/playwm/", "/play/"),
            // 各档直链同样去水印路径
            qualities = qualities.map { it.copy(url = it.url.replace("/playwm/", "/play/")) },
            coverUrl = coverUrl,
            title = title.ifBlank { "video" },
            author = author,
            rawShareText = shareUrl,
            // 关键：下载时用分享链接作 Referer，否则图床（如 sinaimg.cn）403
            originalUrl = shareUrl
        )

        fun imageItem(url: String) = ParsedMedia(
            platform = platform,
            imageUrl = url,
            title = title.ifBlank { "image" },
            author = author,
            rawShareText = shareUrl,
            originalUrl = shareUrl
        )

        val result = mutableListOf<ParsedMedia>()
        videos.forEach { (v, q) -> result.add(videoItem(v, q, cover ?: images.firstOrNull())) }
        images.forEach { img -> result.add(imageItem(img)) }

        LogFile.d("CloudParser", "maxhelper 提取：${result.size} 个媒体（视频 ${videos.size} + 图片 ${images.size}），画质档 ${videos.values.maxOfOrNull { it.size } ?: 0}")
        return result
    }

    /**
     * 从单个视频条目的 video_fullinfo[] 提取全部清晰度档。
     * 返回按高度降序（同高度按体积降序、按直链去重）的列表；
     * 列表为空/全无效时由调用方回落条目默认 url（实测抖音默认档是 720p，
     * 超高清档体积可达其 10 倍以上，2026-08-22 验证）。
     */
    private fun extractQualities(video: JSONObject): List<MediaQuality> {
        val fullinfo = video.optJSONArray("video_fullinfo") ?: return emptyList()
        val list = mutableListOf<MediaQuality>()
        for (i in 0 until fullinfo.length()) {
            val q = fullinfo.optJSONObject(i) ?: continue
            val u = q.optString("url")
            if (!u.startsWith("http")) continue
            val label = q.optString("type").ifBlank { "默认" }
            list.add(
                MediaQuality(
                    label = label,
                    url = u,
                    sizeBytes = q.optLong("size", 0L),
                    height = MediaQuality.heightOf(label)
                )
            )
        }
        if (list.isEmpty()) return emptyList()
        LogFile.d("CloudParser", "清晰度 ${list.size} 档：${list.joinToString(" / ") { it.label }}")
        return list.sortedWith(
            compareByDescending<MediaQuality> { it.height }.thenByDescending { it.sizeBytes }
        ).distinctBy { it.url }
    }

    /** 递归遍历 JSON，收集视频/图片直链 */
    private fun extractMedia(root: JSONObject, shareUrl: String, platform: Platform): List<ParsedMedia> {
        val videos = mutableListOf<String>()
        val images = mutableListOf<String>()
        var coverUrl: String? = null
        var title = ""
        var author = ""

        fun isVideoUrl(u: String) = VIDEO_HINTS.any { u.contains(it, true) }
        fun isImageUrl(u: String) = IMG_HINTS.any { u.contains(it, true) }

        // 先提取显式字段（优先级高于通配）
        fun extractExplicit(o: JSONObject) {
            // 封面
            COVER_FIELDS.forEach { f ->
                if (coverUrl.isNullOrBlank()) {
                    val v = o.optString(f).takeIf { it?.startsWith("http") == true }
                    if (v != null) coverUrl = v
                }
            }
            // 标题
            TITLE_FIELDS.forEach { f ->
                if (title.isBlank()) {
                    val v = o.optString(f).takeIf { !it.isNullOrBlank() }
                    if (!v.isNullOrBlank()) title = v
                }
            }
            // 作者
            AUTHOR_FIELDS.forEach { f ->
                if (author.isBlank()) {
                    val v = o.optString(f).takeIf { !it.isNullOrBlank() }
                    if (!v.isNullOrBlank()) author = v
                }
            }
            // 作者嵌套对象
            o.optJSONObject("author")?.let { a ->
                if (author.isBlank()) author = a.optString("nickname").ifBlank { a.optString("name") }.ifBlank { a.optString("user_name") }
            }
        }

        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    extractExplicit(o)
                    for (k in o.keys()) walk(o.opt(k))
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
                is String ->
                    if (o.startsWith("http")) {
                        when {
                            isVideoUrl(o) -> if (o !in videos) videos.add(o)
                            isImageUrl(o) -> if (o !in images) images.add(o)
                        }
                    }
            }
        }
        walk(root)

        if (videos.isEmpty() && images.isEmpty()) return emptyList()

        // 与 MaxHelper 提取保持一致：不配对、不丢弃，返回多少媒体出多少项
        return buildList {
            videos.forEach { v ->
                add(
                    ParsedMedia(
                        platform = platform,
                        videoUrl = v.replace("/playwm/", "/play/"),
                        title = title.ifBlank { "video" },
                        author = author,
                        coverUrl = coverUrl ?: images.firstOrNull(),
                        rawShareText = shareUrl,
                        // 分享链接作下载 Referer，避免图床/CDN 因无来源 403
                        originalUrl = shareUrl
                    )
                )
            }
            images.forEach { img ->
                add(
                    ParsedMedia(
                        platform = platform,
                        imageUrl = img,
                        title = title.ifBlank { "image" },
                        author = author,
                        rawShareText = shareUrl,
                        originalUrl = shareUrl
                    )
                )
            }
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 远程探测：单个探测超时（数量不设上限，全部并行探测） */
        private const val PROBE_TIMEOUT_MS = 15_000L

        // ---- Hellotik 协议常量 ----
        private val CHUNK_SRC_REGEX = Regex("""src="(/_next/static/chunks/[^"]+\.js)"""")
        private val PROFILE_JSON_REGEX =
            Regex("""\{"activeProfileId":"[^"]+","previousProfileId":"[^"]*","profiles":\[.*?\]\}""", RegexOption.DOT_MATCHES_ALL)

        /** 响应解密固定密钥（站点 chunk 2261 内嵌，32 字节 = AES-256） */
        private const val HELLOTIK_RESP_KEY = "93838338562359368888868323563256"
        private const val HELLOTIK_CUSTOM_ALPHABET =
            "ZYXABCDEFGHIJKLMNOPQRSTUVWzyxabcdefghijklmnopqrstuvw9876543210-_"
        private const val HELLOTIK_STANDARD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        // ---- SnapAny 协议常量 ----
        private const val SNAPANY_HMAC_KEY = "a5wU-SVyy5gXIyMbPQIfIz7UP7rCBp76U8Z8i-FtDMU"

        // ---- Kukutool 协议常量 ----
        /** 响应解密密钥（站点 chunk 3052 内嵌，AES-256-CBC，key=SHA-256(此字符串)） */
        private const val KUKUTOOL_RESP_KEY = "12345678901234567890123456789013"

        /** 首页请求重试次数（wafcdn 会间歇返回无 chunk 列表的挑战页） */
        private const val KUKUTOOL_PAGE_TRIES = 3

        /** 协议配置字面量：JSON.parse('{"activeProfileId":…}') 包裹式 */
        private val KUKUTOOL_CONF_WRAPPED_REGEX =
            Regex("""JSON\.parse\('(\{"activeProfileId".*?\})'\)""", RegexOption.DOT_MATCHES_ALL)

        private val VIDEO_HINTS = listOf(
            ".mp4", "playwm", "/play?", "video/tos", "douyinvod", "bilivideo",
            "video_id=", "aweme/v1/play", "hdplay", "/video/", ".mov", ".m4v"
        )
        private val IMG_HINTS = listOf(
            ".jpeg", ".jpg", ".webp", ".gif", ".png", "douyinpic", "photo/tos", "hdslb"
        )
        private val COVER_FIELDS = listOf("coverUrl", "cover", "thumbnail", "poster", "thumbUrl", "imageUrl", "picUrl")
        private val TITLE_FIELDS = listOf("title", "caption", "desc", "text", "description", "name")
        private val AUTHOR_FIELDS = listOf("author", "userName", "user_name", "nickname", "userNick", "name")
    }
}
