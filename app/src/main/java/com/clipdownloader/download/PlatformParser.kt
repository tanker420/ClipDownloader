package com.clipdownloader.download

import com.clipdownloader.model.Platform

/**
 * 全平台链接识别器
 *
 * 支持：抖音、快手、B站、小红书、微博、YouTube、TikTok国际版、
 *      Instagram、Twitter/X、Facebook、Pinterest 等国内外主流平台
 */
object PlatformParser {

    /**
     * 检测分享文本中的平台类型。
     *
     * 必须含平台 URL 特征才算命中——纯文字（如"抖音""快手"字样、方案文档、
     * 日志文本）不触发，避免误判后必然解析失败。
     */
    fun detectPlatform(text: String): Platform? {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // 保护：日志文本（含时间戳前缀 "08-12 00:54:46.750 D [Tag] ..."）里可能夹带链接，
        // 复制日志导出时不应触发下载。格式特征：行首 MM-dd HH:mm:ss.SSS
        if (Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(trimmed)) {
            return null
        }

        // === 国内平台（只认 URL 特征，不认纯字样） ===

        // 抖音
        if (lower.contains("v.douyin.com") ||
            lower.contains("iesdouyin.com") ||
            lower.contains("douyin.com/video") ||
            lower.contains("douyin.com/note") ||
            lower.contains("iesdouyin.com/share") ||
            Regex("""(?:v|www)\.douyin\.com""").containsMatchIn(lower)
        ) {
            return Platform.DOUYIN
        }

        // 快手
        if (lower.contains("v.kuaishou.com") ||
            lower.contains("kuaishou.com/short-video") ||
            lower.contains("kuaishou.com/fw")
        ) {
            return Platform.KUAISHOU
        }

        // 哔哩哔哩
        if (lower.contains("b23.tv") ||
            lower.contains("bilibili.com")
        ) {
            return Platform.BILIBILI
        }

        // 小红书
        if (lower.contains("xhslink.com") ||
            lower.contains("xiaohongshu.com")
        ) {
            return Platform.XIAOHONGSHU
        }

        // 微博
        // t.cn 必须按「host 恰为 t.cn」匹配：contains("t.cn/") 会误中
        // out.cn/、bit.cn/ 等任意以 t.cn 结尾的域名
        if (lower.contains("weibo.com") ||
            lower.contains("weibo.cn") ||
            Regex("https?://t\\.cn/").containsMatchIn(lower)
        ) {
            return Platform.WEIBO
        }

        // === 海外平台 ===

        // YouTube
        if (lower.contains("youtube.com") ||
            lower.contains("youtu.be")
        ) {
            return Platform.YOUTUBE
        }

        // TikTok 国际版
        if (lower.contains("tiktok.com") ||
            lower.contains("vm.tiktok.com") ||
            lower.contains("vt.tiktok.com")
        ) {
            return Platform.TIKTOK
        }

        // Instagram
        if (lower.contains("instagram.com") ||
            lower.contains("instagr.am")
        ) {
            return Platform.INSTAGRAM
        }

        // Twitter / X
        // x.com 必须按 host 边界匹配：contains("x.com") 会误中 max.com/ex.com 等域名
        if (lower.contains("twitter.com") ||
            Regex("https?://(?:www\\.)?x\\.com[/?:#]|https?://(?:www\\.)?x\\.com$").containsMatchIn(lower) ||
            lower.contains("t.co/")
        ) {
            return Platform.TWITTER
        }

        // Facebook
        if (lower.contains("facebook.com") ||
            lower.contains("fb.watch") ||
            lower.contains("fb.com")
        ) {
            return Platform.FACEBOOK
        }

        // Pinterest
        if (lower.contains("pinterest.com") ||
            lower.contains("pin.it")
        ) {
            return Platform.PINTEREST
        }

        // 通用兜底：只要文本中包含 http(s) 链接（无论是纯链接、带文字、还是带口令的分享文本），
        // 都交给通用解析器处理。放在最后，保证前面的平台精确匹配优先。
        if (extractUrl(text) != null) {
            return Platform.OTHER
        }

        return null
    }

    /**
     * 识别博主主页链接。
     * 返回 (平台, 标识)：抖音 sec_uid / B站 mid（数字）/ 快手 userId。
     * 只认明确的主页 URL 特征；短链需先展开才能识别，这里不处理。
     */
    fun detectProfile(text: String): Pair<Platform, String>? {
        val url = extractUrl(text) ?: return null
        Regex("douyin\\.com/user/(MS4w[A-Za-z0-9_-]+)").find(url)?.let { return Platform.DOUYIN to it.groupValues[1] }
        Regex("iesdouyin\\.com/share/user/([A-Za-z0-9._-]+)").find(url)?.let { return Platform.DOUYIN to it.groupValues[1] }
        // B站空间：space.bilibili.com/{mid}（分享按钮给的短形态，无 /space/ 路径）、
        // space.bilibili.com/space/{mid} 与分享短链落地的 m.bilibili.com/space/{mid}
        Regex("(?:space|m)\\.bilibili\\.com/(?:space/)?(\\d+)").find(url)?.let { return Platform.BILIBILI to it.groupValues[1] }
        // 快手主页：web 端 profile/{userId} 与分享短链落地的 fw/user/{userId}
        Regex("kuaishou\\.com/profile/([A-Za-z0-9_-]+)").find(url)?.let { return Platform.KUAISHOU to it.groupValues[1] }
        Regex("chenzhongtech\\.com/fw/user/([A-Za-z0-9_.-]+)").find(url)?.let { return Platform.KUAISHOU to it.groupValues[1] }
        return null
    }

    /**
     * 从分享文本中提取 URL
     *
     * 兼容以下场景：
     * - 纯链接："https://v.douyin.com/xxx/"
     * - 带文字的分享："7.99 复制打开抖音，看看 https://v.douyin.com/xxx/ 这段视频"
     * - 带口令的分享："长按复制此条消息，打开抖音搜索 https://v.kuaishou.com/xxx"
     * 会自动剔除结尾的中文/全角标点与引号。
     */
    fun extractUrl(text: String): String? {
        val urlPattern = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)")
        val match = urlPattern.find(text) ?: return null
        return cleanUrl(match.value)
    }

    /**
     * 清理 URL 结尾可能夹带的中文/全角标点、引号和空白，
     * 例如 "https://v.douyin.com/xxx/。" 或 "https://x.com/a.jpg」"
     */
    private fun cleanUrl(raw: String): String {
        return raw.trimEnd { c ->
            c in ".,;:!?)]}>'\"` \t\n\r" ||
                c in "。，、；：！？…）】」』”’）」』「『（《》【】" ||
                c == '）' || c == '】' || c == '」' || c == '』' || c == '”' || c == '’' ||
                c == '（' || c == '【' || c == '「' || c == '『' || c == '“' || c == '‘'
        }
    }

    /**
     * 从分享文本中提取去重 key。
     * 只取 path 首段：同一短链带不带 query/尾斜杠必须得到同一个 key，
     * 否则 "?x=1" 与否会生成不同 key，去重失效
     */
    fun extractShareKey(text: String): String? {
        val url = extractUrl(text) ?: return null
        val firstSeg = url.substringAfter("://", "")
            .substringAfter("/", "")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore("/")
        return if (firstSeg.length > 8) firstSeg.substring(0, 8)
        else firstSeg.ifEmpty { url.substringAfter("://", "").substringBefore("/") }
    }
}
