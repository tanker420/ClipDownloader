package com.clipdownloader.util

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // === 主题模式：0=跟随系统 1=浅色 2=深色 ===
    fun getThemeMode(): Int = prefs.getInt("theme_mode", 0)
    fun setThemeMode(mode: Int) {
        prefs.edit().putInt("theme_mode", mode).apply()
    }

    // === 按平台独立文件夹保存（开=平台/博主子文件夹，关=全部存根目录） ===
    fun isPlatformFoldersEnabled(): Boolean = prefs.getBoolean("platform_folder_enabled", true)

    fun setPlatformFoldersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("platform_folder_enabled", enabled).apply()
    }

    // === 解析模式：本地解析（开）/ 云端解析（关，云端失败不回落本地） ===
    fun isLocalParse(): Boolean = prefs.getBoolean("local_parse", true)
    fun setLocalParse(local: Boolean) {
        prefs.edit().putBoolean("local_parse", local).apply()
    }

    // === 各平台首选解析通道：每个平台独立一个下拉（设置页） ===
    // 值：CHANNEL_LOCAL（本地解析器，仅本地支持的平台）/ "iiilab" / 云端通道 url。
    // 旧版分组配置（国内统一 domestic_channel、TikTok tiktok_channel、YouTube youtube_channel、
    // 其余海外统一 cloud_preferred_overseas）在首次读取时一次性迁移到按平台的键。

    fun getPlatformChannel(platformKey: String): String {
        migratePlatformChannelsIfNeeded()
        val def = if (platformKey in LOCAL_CAPABLE_KEYS) CHANNEL_LOCAL else "iiilab"
        return prefs.getString(KEY_PLATFORM_CHANNEL_PREFIX + platformKey, null)
            ?.takeIf { it.isNotBlank() } ?: def
    }

    fun setPlatformChannel(platformKey: String, value: String) {
        migratePlatformChannelsIfNeeded()
        prefs.edit().putString(KEY_PLATFORM_CHANNEL_PREFIX + platformKey, value).apply()
    }

    /** 旧分组配置 → 按平台键的一次性迁移（rev 递增，只补缺失的键，已写入的不覆盖） */
    private fun migratePlatformChannelsIfNeeded() {
        val rev = prefs.getInt(KEY_PLATFORM_CHANNELS_REV, 0)
        if (rev >= PLATFORM_CHANNELS_REV) return
        // rev1：国内统一 / TikTok / YouTube / 海外统一 → 按 11 个平台键拆分
        if (rev < 1) {
            // 国内：新版 domestic_channel；更早版本按旧「本地解析开关」推导
            val domestic = if (prefs.contains("domestic_channel")) {
                prefs.getString("domestic_channel", CHANNEL_LOCAL) ?: CHANNEL_LOCAL
            } else if (prefs.getBoolean("local_parse", true)) {
                CHANNEL_LOCAL
            } else {
                prefs.getString("cloud_preferred", "")?.ifBlank { null } ?: CHANNEL_LOCAL
            }
            val tiktok = prefs.getString("tiktok_channel", null)?.takeIf { it != "maxhelper" } ?: CHANNEL_LOCAL
            val youtube = prefs.getString("youtube_channel", null) ?: CHANNEL_LOCAL
            val overseas = prefs.getString("cloud_preferred_overseas", null)?.ifBlank { null } ?: "iiilab"

            val editor = prefs.edit()
            val migrated = mapOf(
                "douyin" to domestic, "kuaishou" to domestic, "bilibili" to domestic,
                "xiaohongshu" to domestic, "weibo" to domestic,
                "tiktok" to tiktok, "youtube" to youtube,
                "instagram" to overseas, "twitter" to overseas,
                "facebook" to overseas, "pinterest" to overseas
            )
            for ((key, value) in migrated) {
                if (prefs.getString(KEY_PLATFORM_CHANNEL_PREFIX + key, null) == null) {
                    editor.putString(KEY_PLATFORM_CHANNEL_PREFIX + key, value)
                }
            }
            editor.apply()
        }
        // rev2：补「国内其他/国外其他」（未知链接兜底）；旧值若是「本地解析」对未知链接无意义，回落 iiilab
        if (rev < 2) {
            fun applicable(raw: String?): String =
                raw?.takeIf { it == "iiilab" || it.startsWith("http") } ?: "iiilab"
            prefs.edit()
                .putString(KEY_PLATFORM_CHANNEL_PREFIX + "domestic_other",
                    applicable(prefs.getString("domestic_channel", null)))
                .putString(KEY_PLATFORM_CHANNEL_PREFIX + "overseas_other",
                    applicable(prefs.getString("cloud_preferred_overseas", null)))
                .apply()
        }
        prefs.edit().putInt(KEY_PLATFORM_CHANNELS_REV, PLATFORM_CHANNELS_REV).apply()
    }

    // === iiiLab 内置通道的支持平台（默认全部平台；取消勾选的平台不再走 iiiLab） ===
    fun getIiiLabPlatforms(): Set<String> =
        (prefs.getStringSet("iiilab_platforms", null)
            ?: (ChannelPlatforms.DOMESTIC.keys + ChannelPlatforms.OVERSEAS.keys +
                ChannelPlatforms.DOMESTIC_OTHER + ChannelPlatforms.OVERSEAS_OTHER)).toSet()

    fun setIiiLabPlatforms(platforms: Set<String>) {
        prefs.edit().putStringSet("iiilab_platforms", platforms).apply()
    }

    // === 云端解析通道（国内+国外统一管理，不分分组；每个通道勾选支持的平台） ===

    /** 通道支持的平台选项（key 与 Platform 枚举对应，另加两个「其他」兜底范围） */
    object ChannelPlatforms {
        const val DOMESTIC_OTHER = "domestic_other"
        const val OVERSEAS_OTHER = "overseas_other"

        val DOMESTIC = linkedMapOf(
            "douyin" to "抖音", "kuaishou" to "快手", "bilibili" to "哔哩哔哩",
            "xiaohongshu" to "小红书", "weibo" to "微博"
        )
        val OVERSEAS = linkedMapOf(
            "youtube" to "YouTube", "tiktok" to "TikTok", "instagram" to "Instagram",
            "twitter" to "Twitter/X", "facebook" to "Facebook", "pinterest" to "Pinterest"
        )

        fun labelOf(key: String): String = when (key) {
            DOMESTIC_OTHER -> "国内其他"
            OVERSEAS_OTHER -> "国外其他"
            else -> DOMESTIC[key] ?: OVERSEAS[key] ?: key
        }

        /** 通道列表里展示的支持范围摘要 */
        fun summary(platforms: Set<String>): String =
            if (platforms.isEmpty()) "全部平台（未设置）"
            else platforms.joinToString(" · ") { labelOf(it) }
    }

    data class CloudChannel(
        val name: String,
        val url: String,
        val note: String = "",
        /** 支持的平台 key 集合；空集=旧数据/未设置，视为全部支持 */
        val platforms: Set<String> = emptySet()
    )

    fun getCloudChannels(): List<CloudChannel> {
        val raw = prefs.getString("cloud_channels", null) ?: return defaultChannels()
        val parsed = try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CloudChannel(
                    name = o.optString("name").ifBlank { o.optString("url") },
                    url = o.optString("url"),
                    note = o.optString("note"),
                    platforms = o.optJSONArray("platforms")?.let { pa ->
                        (0 until pa.length()).mapNotNull { pa.optString(it).ifBlank { null } }.toSet()
                    } ?: emptySet()
                )
            }
        } catch (_: Exception) {
            null
        } ?: return defaultChannels()
        if (parsed.isEmpty()) return defaultChannels()
        // 内置通道定义升级：同域名的旧内置通道刷新为新定义（支持平台等），
        // 并追加本次新增的内置通道（如 Hellotik），自定义通道原样保留
        val rev = prefs.getInt(KEY_BUILTIN_REV, 1)
        if (rev < BUILTIN_CHANNELS_REV) {
            val builtins = builtinByHost()
            fun hostOf(url: String) =
                runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            val migrated = parsed.map { ch -> builtins[hostOf(ch.url)] ?: ch }.toMutableList()
            for ((host, def) in builtins) {
                if (migrated.none { hostOf(it.url) == host }) migrated.add(def)
            }
            prefs.edit().putInt(KEY_BUILTIN_REV, BUILTIN_CHANNELS_REV).apply()
            setCloudChannels(migrated)
            return migrated
        }
        return parsed
    }

    fun setCloudChannels(channels: List<CloudChannel>) {
        val arr = org.json.JSONArray()
        channels.filter { it.url.isNotBlank() }.forEach {
            arr.put(org.json.JSONObject().apply {
                put("name", it.name); put("url", it.url); put("note", it.note)
                if (it.platforms.isNotEmpty()) put("platforms", org.json.JSONArray(it.platforms))
            })
        }
        prefs.edit().putString("cloud_channels", arr.toString()).apply()
    }

    // === 首选云端通道（按 url 匹配；通道列表序备用，逐平台首选见 getPlatformChannel） ===
    fun getPreferredChannel(): String = prefs.getString("cloud_preferred", "") ?: ""

    fun setPreferredChannel(channel: String) {
        prefs.edit().putString("cloud_preferred", channel).apply()
    }

    // === 博主主页增量下载：已下载的作品 ID 集合 ===
    fun getBloggerDone(): Set<String> = prefs.getStringSet("blogger_done", emptySet()) ?: emptySet()

    fun addBloggerDone(ids: Collection<String>) {
        val set = LinkedHashSet(getBloggerDone())
        set.addAll(ids)
        // 上限 2000，超出裁掉最早的
        val trimmed = if (set.size > 2000) set.drop(set.size - 2000).toSet() else set
        prefs.edit().putStringSet("blogger_done", trimmed).apply()
    }

    fun clearBloggerDone() {
        prefs.edit().remove("blogger_done").apply()
    }

    // === 保存路径 ===
    fun getSavePath(): String =
        prefs.getString("save_path", "/storage/emulated/0/Download/ClipDownloader")!!

    fun setSavePath(path: String) {
        prefs.edit().putString("save_path", path).apply()
    }

    // === 下载设置 ===
    fun isWifiOnly(): Boolean = prefs.getBoolean("wifi_only", false)
    fun setWifiOnly(wifiOnly: Boolean) {
        prefs.edit().putBoolean("wifi_only", wifiOnly).apply()
    }

    // === 强制重新下载：重复视频重新下载（总开关）/ 覆盖旧文件（仅总开关开启时可设） ===
    /** 开=下载时不跳过本地已存在的重复视频，直接重新下载；关=跳过（默认） */
    fun isRedownloadDuplicates(): Boolean = prefs.getBoolean("redownload_duplicates", false)

    fun setRedownloadDuplicates(enabled: Boolean) {
        prefs.edit().putBoolean("redownload_duplicates", enabled).apply()
    }

    /** 重新下载后是否删除并替换旧文件；关=另存新文件，新旧都保留。仅总开关开启时生效 */
    fun isRedownloadOverwrite(): Boolean = prefs.getBoolean("redownload_overwrite", false)

    fun setRedownloadOverwrite(enabled: Boolean) {
        prefs.edit().putBoolean("redownload_overwrite", enabled).apply()
    }

    // === 下载完成通知 ===
    fun isShowDownloadResult(): Boolean = prefs.getBoolean("show_download_result", true)
    fun setShowDownloadResult(enabled: Boolean) {
        prefs.edit().putBoolean("show_download_result", enabled).apply()
    }

    // === 打开时自动读剪贴板 ===
    /** 打开 App 时若发现剪贴板里有链接则自动开始下载 */
    fun isAutoReadClipboardOnLaunch(): Boolean = prefs.getBoolean("auto_read_clipboard", true)
    fun setAutoReadClipboardOnLaunch(enabled: Boolean) {
        prefs.edit().putBoolean("auto_read_clipboard", enabled).apply()
    }

    // === 已自动下载过的剪贴板内容（去重，防止同一链接反复下载） ===
    companion object {
        const val KEY_CONSUMED_CLIPS = "consumed_clips"
        const val MAX_CONSUMED_CLIPS = 200

        /** 内置通道定义版本：升级后把存储里同域名的内置通道刷新成新定义（自定义通道不动）
         *  v3（2026-08-24）：修复 v2 迁移只替换不追加的问题——跑过 v2 的设备
         *  （rev 已=2）缺 Hellotik，需要再次触发迁移补上
         *  v4（2026-08-29）：新增 DownCats/SnapAny/Oivo 三个内置通道
         *  v5（2026-08-29）：新增 SnapTok/XiaZaiTool/Kukutool 三个内置通道；
         *  DownCats 收窄为仅视频解析（其主页提取需付费会员，App 不接入）
         *  v6（2026-08-29）：新增 VidDown 内置通道（全平台异步任务制） */
        private const val BUILTIN_CHANNELS_REV = 6
        private const val KEY_BUILTIN_REV = "builtin_channels_rev"

        /** 「本地解析」通道取值；存储 key 前缀与迁移版本 */
        const val CHANNEL_LOCAL = "local"
        private const val KEY_PLATFORM_CHANNEL_PREFIX = "platform_channel_"
        private const val KEY_PLATFORM_CHANNELS_REV = "platform_channels_rev"
        /** v1：分组配置拆到 11 个平台键；v2：补国内其他/国外其他两个兜底键 */
        private const val PLATFORM_CHANNELS_REV = 2

        /** 有本地解析器的平台 key（这些平台下拉里才有「本地解析」选项） */
        val LOCAL_CAPABLE_KEYS = setOf(
            "douyin", "kuaishou", "bilibili", "xiaohongshu", "weibo", "tiktok", "youtube"
        )
    }

    /**
     * 内置默认云端通道。
     *
     * 2026-08-29 起共 8 个内置通道（协议均已在 App 内逆向内置）：
     * - MaxHelper：auth + AES-GCM 信封 + rc 解密，国内抖音/B站/小红书与 TikTok 实测通过。
     * - Hellotik：ticket 动态字段名 + AES-GCM 信封 + AES-CBC 响应解密。
     * - DownCats（downcats.com）：POST /v1/extract/free/video，仅视频解析
     *   （抖音/TikTok/快手/小红书/B站；其主页提取需付费会员，App 不接入）。
     * - SnapAny（snapany.com）：api.snapany.com HMAC 签名接口，声明支持全平台。
     * - Oivo（dy.oivo.cn）：转发 tangdouz 接口，仅抖音。
     * - SnapTok（snaptik.net）：POST /api/ajaxSearch，仅抖音/TikTok。
     * - XiaZaiTool（xiazaitool.com 下载狗）：SHA-256 签名接口，国内全平台+海外主流。
     * - Kukutool（dy.kukutool.com）：MaxHelper 同款信封协议（字段名动态发现），国内全平台+TikTok。
     * 需要更多通道时请自行填写可用的自建/公共通道（设置 → 云端解析通道 → 新增）。
     */
    fun defaultChannels(): List<CloudChannel> = listOf(
        CloudChannel(
            "MaxHelper",
            "https://www.maxhelper.app",
            "内置加密协议，实测通过",
            setOf("douyin", "kuaishou", "bilibili", "xiaohongshu", "weibo", "tiktok")
        ),
        CloudChannel(
            "Hellotik",
            "https://www.hellotik.app",
            "内置加密协议（ticket 动态字段）",
            setOf("douyin", "kuaishou", "bilibili", "xiaohongshu", "weibo", "tiktok")
        ),
        CloudChannel(
            "DownCats",
            "https://www.downcats.com",
            "免登录视频解析（抖音/TikTok/快手/小红书/B站）",
            setOf("douyin", "kuaishou", "bilibili", "xiaohongshu", "tiktok")
        ),
        CloudChannel(
            "SnapAny",
            "https://snapany.com",
            "HMAC 签名接口，支持全平台",
            setOf(
                "douyin", "kuaishou", "bilibili", "xiaohongshu", "weibo",
                "youtube", "tiktok", "instagram", "twitter", "facebook", "pinterest",
                "domestic_other", "overseas_other"
            )
        ),
        CloudChannel(
            "Oivo",
            "https://dy.oivo.cn",
            "仅抖音",
            setOf("douyin")
        ),
        CloudChannel(
            "SnapTok",
            "https://snaptik.net",
            "免登录，仅抖音/TikTok",
            setOf("douyin", "tiktok")
        ),
        CloudChannel(
            "XiaZaiTool",
            "https://www.xiazaitool.com",
            "免登录 SHA-256 签名接口，全平台",
            setOf(
                "douyin", "kuaishou", "bilibili", "xiaohongshu", "weibo",
                "tiktok", "youtube", "facebook", "instagram"
            )
        ),
        CloudChannel(
            "Kukutool",
            "https://dy.kukutool.com",
            "信封协议（动态字段），国内全平台+TikTok",
            setOf("douyin", "kuaishou", "bilibili", "xiaohongshu", "weibo", "tiktok")
        ),
        CloudChannel(
            "VidDown",
            "https://www.viddown.cn",
            "异步任务制，支持全平台",
            setOf(
                "douyin", "kuaishou", "bilibili", "xiaohongshu", "weibo",
                "youtube", "tiktok", "instagram", "twitter", "facebook",
                "domestic_other", "overseas_other"
            )
        )
    )

    /** 内置通道定义版本：升级后自动把存储里同域名的内置通道刷新成新定义（自定义通道不动） */
    private fun builtinByHost(): Map<String, CloudChannel> =
        defaultChannels().associateBy { runCatching { java.net.URI(it.url).host.orEmpty() }.getOrDefault("") }

    fun getConsumedClips(): Set<String> =
        prefs.getStringSet(KEY_CONSUMED_CLIPS, emptySet()) ?: emptySet()

    fun addConsumedClip(text: String) {
        val set = LinkedHashSet(getConsumedClips())
        set.add(text)
        // 集合过大时裁掉最早的一部分，避免无限增长
        val trimmed = if (set.size > MAX_CONSUMED_CLIPS) set.drop(set.size - MAX_CONSUMED_CLIPS).toSet() else set
        prefs.edit().putStringSet(KEY_CONSUMED_CLIPS, trimmed).apply()
    }

    fun clearConsumedClips() {
        prefs.edit().remove(KEY_CONSUMED_CLIPS).apply()
    }

    // === 微博解析端：pc（桌面页 ajax，默认）/ mobile（m.weibo.cn 页面） ===
    // 用开关表示：开=PC 端（推荐），关=移动端
    fun isWeiboPcEndpoint(): Boolean = prefs.getBoolean("weibo_endpoint_pc", true)
    fun setWeiboPcEndpoint(pc: Boolean) {
        prefs.edit().putBoolean("weibo_endpoint_pc", pc).apply()
    }

    /** 返回解析器使用的端点字符串（pc / mobile） */
    fun getWeiboEndpoint(): String = if (isWeiboPcEndpoint()) "pc" else "mobile"
}

// ==================== 通道与平台匹配（顶层扩展，供解析器与设置页直接调用） ====================

/** 通道是否可用于该平台（未知链接 OTHER 归入国外其他；空集=旧数据视为全平台） */
fun PreferencesManager.CloudChannel.supportsPlatform(platform: com.clipdownloader.model.Platform): Boolean {
    if (platforms.isEmpty()) return true
    val key = if (platform == com.clipdownloader.model.Platform.OTHER)
        PreferencesManager.ChannelPlatforms.OVERSEAS_OTHER
    else platform.name.lowercase()
    return key in platforms
}
