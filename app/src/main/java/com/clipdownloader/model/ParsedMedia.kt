package com.clipdownloader.model

enum class Platform(val displayName: String, val folderName: String, val isOverseas: Boolean = false) {
    // 国内
    DOUYIN("抖音", "Douyin"),
    KUAISHOU("快手", "Kuaishou"),
    BILIBILI("哔哩哔哩", "Bilibili"),
    XIAOHONGSHU("小红书", "Xiaohongshu"),
    WEIBO("微博", "Weibo"),
    // 海外
    YOUTUBE("YouTube", "YouTube", true),
    TIKTOK("TikTok", "TikTok", true),
    INSTAGRAM("Instagram", "Instagram", true),
    TWITTER("Twitter/X", "Twitter", true),
    FACEBOOK("Facebook", "Facebook", true),
    PINTEREST("Pinterest", "Pinterest", true),
    // 其他（未知链接，按海外通道兜底）
    OTHER("其他", "Other", true);

    companion object {
        fun fromName(name: String?): Platform? = name?.let { n ->
            values().firstOrNull { it.name.equals(n, true) || it.folderName.equals(n, true) }
        }
    }
}

/**
 * 单个视频的一档清晰度。
 * 来源：云端 video_fullinfo[]（type/size/url）、抖音本地 bit_rate[]（gear_name/data_size）、
 * B站本地多 qn playurl（accept_description/durl.size）。
 */
data class MediaQuality(
    /** 显示标签：超高清 / 1080p / 720p … */
    val label: String,
    /** 该档直链 */
    val url: String,
    /** 该档体积（字节），未知为 0 */
    val sizeBytes: Long = 0,
    /** 高度像素（排序/匹配用），识别不出为 0 */
    val height: Int = 0
) {
    companion object {
        private val HEIGHT_REGEX = Regex("(\\d{3,4})\\s*[pP]?")

        /** 从清晰度标签识别高度：超高清/原画/蓝光/4k→2160，2k→1440，其余取标签里的数字 */
        fun heightOf(label: String): Int {
            val t = label.trim()
            return when {
                t.contains("超高清") || t.contains("原画") || t.contains("蓝光") ||
                    t.contains("4k", true) || t.contains("2160") -> 2160
                t.contains("2k", true) || t.contains("1440") -> 1440
                else -> HEIGHT_REGEX.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }
    }
}

data class ParsedMedia(
    val platform: Platform,
    val videoUrl: String? = null,
    val imageUrl: String? = null,
    val title: String = "",
    val author: String = "",
    /** 平台作品 ID（aweme_id / bvid），用于博主主页增量下载去重 */
    val mediaId: String = "",
    /** 纯音频（抖音图文的背景音乐）：列表显示音乐图标而非缩略图 */
    val isMusic: Boolean = false,
    /** 平台实况图（抖音/小红书图集里的 live）：同时存在 videoUrl 和 imageUrl，
     *  下载时视频和原图分开保存 */
    val isMotionPhoto: Boolean = false,
    val coverUrl: String? = null,
    val duration: Long = 0,
    val rawShareText: String = "",
    val originalUrl: String = "",
    /** 该视频的可选清晰度（按高度降序，首选=第一档=videoUrl）；图片/音乐为空 */
    val qualities: List<MediaQuality> = emptyList(),
    /** 单直链媒体的体积（无画质档时用，探测补齐），未知为 0 */
    val sizeBytes: Long = 0
)

data class DownloadResult(
    val success: Boolean,
    val filePath: String? = null,
    val fileName: String = "",
    val platform: Platform = Platform.OTHER,
    val error: String? = null,
    val isMotionPhoto: Boolean = false,
    /** 下载后探测到的真实媒体时长（毫秒），用于更新下载栏时长角标 */
    val durationMs: Long = 0,
    /** 本地已有该作品（文件名内置标识命中），未下载直接跳过 */
    val skippedExisting: Boolean = false
)
