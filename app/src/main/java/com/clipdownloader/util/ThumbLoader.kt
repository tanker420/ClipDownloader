package com.clipdownloader.util

import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.clipdownloader.R
import com.clipdownloader.model.Platform
import java.io.File

/**
 * 统一缩略图加载（首页解析列表 / 下载页共用）。
 *
 * - 本地文件优先：已下载的条目直接读本机文件，不依赖图床 CDN。
 *   此前解析阶段远程封面偶发加载失败会卡在占位图，直到条目换绑才恢复。
 * - 远程封面按平台带 Referer/桌面 UA：微博 sinaimg 等图床无来源请求直接 403。
 * - 失败自愈：同一来源失败后记录时间，冷却 RETRY_MS 后的下一次重绑才重试。
 *   配合下载页 600ms 轮询，既不会永远卡占位图，也不会高频重试闪屏/耗流量。
 */
object ThumbLoader {

    /** 失败后的重试冷却 */
    private const val RETRY_MS = 5000L

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun load(img: ImageView, url: String?, localPath: String?, platform: Platform?) {
        val local = localPath?.takeIf { it.isNotBlank() }
        when {
            local != null -> loadSource(img, "L:$local", File(local), url, platform)
            !url.isNullOrBlank() -> loadSource(img, "R:$url", withHeaders(url, platform), null, platform)
            else -> {
                img.setTag(R.id.tag_cover_url, null)
                img.setTag(R.id.tag_cover_failed, null)
                img.setImageResource(R.drawable.ic_notification)
            }
        }
    }

    /** 同一来源已请求/已展示过时跳过；失败过的等冷却结束后重试 */
    private fun loadSource(img: ImageView, source: String, model: Any, fallbackUrl: String?, platform: Platform?) {
        if (img.getTag(R.id.tag_cover_url) == source) {
            val failed = img.getTag(R.id.tag_cover_failed) as? String ?: return
            if (!failed.startsWith("$source#")) return
            val failedAt = failed.substringAfterLast('#').toLongOrNull() ?: return
            if (SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return
        }
        startLoad(img, source, model, fallbackUrl, platform)
    }

    private fun startLoad(img: ImageView, source: String, model: Any, fallbackUrl: String?, platform: Platform?) {
        img.setTag(R.id.tag_cover_url, source)
        img.setTag(R.id.tag_cover_failed, null)
        Glide.with(img).load(model).centerCrop()
            .placeholder(R.drawable.ic_notification)
            .error(R.drawable.ic_notification)
            .listener(object : RequestListener<Drawable> {
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    img.setTag(R.id.tag_cover_failed, null)
                    return false
                }

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    img.setTag(R.id.tag_cover_failed, "$source#${SystemClock.elapsedRealtime()}")
                    // 本地文件读不了（被移动/分区存储误判）→ 退回远程封面再试
                    if (source.startsWith("L:") && !fallbackUrl.isNullOrBlank()) {
                        img.post {
                            startLoad(img, "R:$fallbackUrl", withHeaders(fallbackUrl, platform), null, platform)
                        }
                    }
                    return false
                }
            })
            .into(img)
    }

    /** 图床按平台带 Referer/UA（模拟浏览器行为）；本地文件路径（抽帧缓存等）不加头直接加载 */
    private fun withHeaders(url: String, platform: Platform?): Any {
        if (!url.startsWith("http")) return url
        val referer = when (platform) {
            Platform.WEIBO -> "https://weibo.com/"
            Platform.DOUYIN -> "https://www.douyin.com/"
            Platform.KUAISHOU -> "https://www.kuaishou.com/"
            Platform.BILIBILI -> "https://www.bilibili.com/"
            Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
            Platform.TIKTOK -> "https://www.tiktok.com/"
            else -> null
        } ?: return url
        return GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("User-Agent", DESKTOP_UA)
                .addHeader("Referer", referer)
                .build()
        )
    }
}
