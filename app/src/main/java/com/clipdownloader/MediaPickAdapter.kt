package com.clipdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clipdownloader.model.MediaQuality
import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.util.ThumbLoader

/**
 * 解析结果列表适配器（首页内嵌，手动粘贴与分享面板进来共用）。
 *
 * 按作品分组展示：同一条分享的多张卡片内缩略图一行多个、放不下换行；
 * 每一个缩略图代表一个媒体文件：
 * - 左上角：该媒体的下载大小（视频按当前所选画质实时变化，无画质档用探测体积）
 * - 左下角：该媒体的类型（视频/图片/音乐/动图）
 * - 右下角：该媒体的时长
 * - 右上角：该媒体的勾选框
 *
 * 画质选择由 [setSelectedQuality] 全局驱动：每个视频在各自的画质档里
 * 取「标签相同 → 不超过所选高度的最高档 → 最低档」。
 */
class MediaPickAdapter : RecyclerView.Adapter<MediaPickAdapter.VH>() {

    /** 一个作品 = 一张卡片；checked 与 items 一一对应 */
    private class Group(
        val title: String,
        val items: List<ParsedMedia>,
        val checked: BooleanArray
    )

    private var groups: List<Group> = emptyList()

    /** 当前选择的画质标签（null=未选，视频用各自最高档） */
    private var selectedLabel: String? = null

    fun submitList(list: List<ParsedMedia>) {
        val map = LinkedHashMap<String, MutableList<ParsedMedia>>()
        list.forEach { m ->
            val key = m.mediaId.ifBlank { m.rawShareText.ifBlank { m.title } }
            map.getOrPut(key) { mutableListOf() }.add(m)
        }
        groups = map.values.map { items ->
            Group(
                title = items.firstOrNull()?.title?.ifBlank { items.firstOrNull()?.author.orEmpty() }
                    ?.ifBlank { "作品" }.orEmpty(),
                items = items,
                checked = BooleanArray(items.size) { true }
            )
        }
        notifyDataSetChanged()
    }

    /** 画质滑块切换后全局刷新（列表通常很小，全量刷新足够） */
    fun setSelectedQuality(label: String?) {
        selectedLabel = label
        notifyDataSetChanged()
    }

    fun setAll(c: Boolean) {
        groups.forEach { g -> for (i in g.checked.indices) g.checked[i] = c }
        notifyDataSetChanged()
    }

    fun groupCount(): Int = groups.size

    fun checkedGroupCount(): Int = groups.count { g -> g.checked.any { it } }

    /** 勾选中的全部媒体（拍平，视频直链已换成当前所选画质） */
    fun getChecked(): List<ParsedMedia> =
        groups.flatMap { g -> g.items.filterIndexed { i, _ -> g.checked[i] }.map { applyQuality(it, selectedLabel) } }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tv_media_title)
        val thumbs: android.widget.GridLayout = v.findViewById(R.id.layout_thumbs)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_pick, parent, false)
        return VH(v)
    }

    override fun getItemCount() = groups.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = groups[position]
        val ctx = holder.itemView.context
        holder.title.text = g.title

        val grid = holder.thumbs
        // 按缩略图区实际宽度自适应：列数 = 宽度 / 最小格宽（≥3 列），格子填满一行
        val populate = populate@{ w: Int ->
            // 先判宽度再清格子：post 回调执行时宽度可能仍是 0，
            // 先 removeAllViews 会把已排好的格子清光且没有重试机制
            if (w <= 0) return@populate
            grid.removeAllViews()
            val minCell = dp(ctx, 72)
            val margin = dp(ctx, 6)
            val columns = (w / minCell).coerceAtLeast(3)
            grid.columnCount = columns
            val side = ((w - columns * margin) / columns).coerceAtLeast(dp(ctx, 48))
            g.items.forEachIndexed { idx, m ->
                grid.addView(
                    thumbCell(ctx, m, side, margin, g.checked[idx], selectedLabel) { c -> g.checked[idx] = c }
                )
            }
        }
        if (grid.width > 0) populate(grid.width)
        else grid.post { populate(grid.width) }
    }

    /**
     * 单个媒体缩略图：
     * 图 + 左上大小 / 左下类型 / 右下时长 / 右上勾选（side 由外层按页面宽度算出）
     */
    private fun thumbCell(
        ctx: android.content.Context,
        m: ParsedMedia,
        side: Int,
        margin: Int,
        initiallyChecked: Boolean,
        qualityLabel: String?,
        onCheck: (Boolean) -> Unit
    ): android.widget.FrameLayout {
        val cell = android.widget.FrameLayout(ctx).apply {
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = side
                height = side
                setMargins(0, 0, margin, margin)
            }
        }

        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val url = m.imageUrl ?: m.coverUrl
        if (m.isMusic) {
            img.scaleType = ImageView.ScaleType.CENTER_INSIDE
            img.setImageResource(R.drawable.ic_music)
        } else if (url.isNullOrBlank()) {
            // 没拿到封面的视频/图片：占位图，不能误显示成音乐图标
            img.scaleType = ImageView.ScaleType.CENTER_INSIDE
            img.setImageResource(R.drawable.ic_notification)
        } else {
            // 统一加载器：按平台带 Referer/UA + 失败后冷却重试
            ThumbLoader.load(img, url, null, m.platform)
        }
        cell.addView(img)

        fun badge(text: String, gravity: Int) = TextView(ctx).apply {
            this.text = text
            textSize = 9f
            setTextColor(-0x1)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(ctx, 3), 0, dp(ctx, 3), 0)
            maxLines = 1
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                gravity
            )
        }

        // 左上角：下载大小（视频=所选画质档体积；其它=探测体积；未知不显示）
        val size = displaySize(m, qualityLabel)
        if (size > 0) cell.addView(badge(DownloadsAdapter.formatBytes(size), android.view.Gravity.TOP or android.view.Gravity.START))

        // 左下角类型
        val kind = when {
            m.isMusic -> "音乐"
            m.isMotionPhoto -> "动图"
            m.videoUrl != null -> "视频"
            else -> "图片"
        }
        cell.addView(badge(kind, android.view.Gravity.BOTTOM or android.view.Gravity.START))

        // 右下角时长
        if (m.duration > 0 && m.videoUrl != null) {
            cell.addView(badge(DownloadsAdapter.formatDuration(m.duration), android.view.Gravity.BOTTOM or android.view.Gravity.END))
        }

        // 右上角勾选
        val cb = CheckBox(ctx).apply {
            isChecked = initiallyChecked
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            )
            setOnCheckedChangeListener { _, c -> onCheck(c) }
        }
        cell.addView(cb)
        return cell
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).toInt()

    companion object {
        /** 该视频当前生效的画质档：标签相同 → 高度不超过所选的最高档 → 最低档；无档返回 null */
        @JvmStatic
        fun pickQuality(m: ParsedMedia, selectedLabel: String?): MediaQuality? {
            if (m.qualities.isEmpty()) return null
            val sel = selectedLabel ?: return m.qualities.first()
            val selHeight = MediaQuality.heightOf(sel).coerceAtLeast(1)
            m.qualities.firstOrNull { it.label == sel }?.let { return it }
            // qualities 按高度降序：第一个高度 ≤ 所选的即目标；都超过则取最后一档（最低）
            return m.qualities.firstOrNull { it.height in 1..selHeight } ?: m.qualities.last()
        }

        /** 视频按所选画质换直链；图片/音乐原样返回 */
        @JvmStatic
        fun applyQuality(m: ParsedMedia, selectedLabel: String?): ParsedMedia {
            val q = pickQuality(m, selectedLabel) ?: return m
            return if (q.url == m.videoUrl) m else m.copy(videoUrl = q.url)
        }

        /** 当前应显示的体积：所选画质档体积优先，无档用探测体积；未知为 0 */
        @JvmStatic
        fun displaySize(m: ParsedMedia, selectedLabel: String?): Long {
            pickQuality(m, selectedLabel)?.let { q -> if (q.sizeBytes > 0) return q.sizeBytes }
            return m.sizeBytes
        }
    }
}
