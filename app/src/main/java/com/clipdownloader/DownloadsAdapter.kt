package com.clipdownloader

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clipdownloader.databinding.ItemDownloadBinding
import com.clipdownloader.model.DownloadBatchState
import com.clipdownloader.model.DownloadRecord
import com.clipdownloader.model.Platform
import com.clipdownloader.util.ThumbLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载列表适配器：同时展示「进行中的批量」与「历史记录」
 *
 * 单媒体：左侧缩略图 + 右侧文字（紧凑单行）；
 * 多媒体：缩略图网格（每行 4 个，排满换行），文字信息放最下面。
 *
 * 刷新策略（高速下载时不卡顿/不闪屏的关键）：
 * - 条目 id 稳定（batchId），列表结构没变时绝不 notifyDataSetChanged：
 *   纯进度推进只对对应行发 PAYLOAD_PROGRESS 局部刷新（只更新进度条/文本，
 *   不重跑标题/缩略图/布局），批量阶段变化（解析中→下载中→完成）才整行重绑，
 *   插入/删除/换位才全量刷新。此前用全量刷新是因为 AsyncListDiffer 对
 *   「同一对象原地改字段」判定无变化导致进度冻结；这里改为手动按 id + 阶段
 *   比较做同步差异分发，既不丢状态也不重绑无关行。
 * - 多图网格按「格子」增量更新：新格子追加、变化格子原地改（ImageView 不重建，
 *   ThumbLoader 按 tag 跳过未变来源，Glide 不重新请求不闪占位图），
 *   不再整格 removeAllViews 重建。
 */
class DownloadsAdapter(
    private val onCancel: (DownloadBatchState) -> Unit,
    private val onRemoveBatch: (DownloadBatchState) -> Unit,
    private val onRemoveRecord: (DownloadRecord) -> Unit,
    private val onOpenFile: (String) -> Unit,
    /** 主页下载条目：点击缩略图打开下载到的文件夹 */
    private val onOpenFolder: (String) -> Unit,
    /** 强制重新下载（历史记录卡片「重下」按钮，MainActivity 弹覆盖确认） */
    private val onRedownload: (DownloadRecord) -> Unit = {}
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private val items = mutableListOf<Any>()

    /** 上次整行绑定时的批量阶段：同 id 同对象但阶段变了也要整行重绑 */
    private val boundPhases = HashMap<Long, DownloadBatchState.Phase>()

    init {
        setHasStableIds(true)
    }

    class VH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    /** 同步设置数据并按差异最小化刷新（主线程调用） */
    fun setItems(newItems: List<Any>) {
        val sameStructure = newItems.size == items.size &&
            newItems.indices.all { i -> itemId(newItems[i]) == itemId(items[i]) }
        if (sameStructure) {
            for (i in newItems.indices) {
                val old = items[i]
                val new = newItems[i]
                when {
                    // 同 id 但换对象：批量条 → 历史记录条，整行重绑
                    new !== old -> notifyItemChanged(i)
                    new is DownloadBatchState -> {
                        val bound = boundPhases[new.batchId]
                        when {
                            // 阶段变化（含从未绑过）：整行重绑
                            bound != new.phase -> notifyItemChanged(i)
                            // 同阶段下载中：只刷进度相关视图
                            new.phase == DownloadBatchState.Phase.DOWNLOADING ->
                                notifyItemChanged(i, PAYLOAD_PROGRESS)
                            else -> Unit // 完态批量/不可变记录：无变化
                        }
                    }
                    else -> Unit
                }
            }
        } else {
            notifyDataSetChanged()
        }
        items.clear()
        items.addAll(newItems)
        // 只保留仍在列表里的阶段记录，防止 map 无限增长
        val liveIds = HashSet<Long>(newItems.size)
        newItems.forEach { liveIds.add(itemId(it)) }
        boundPhases.keys.retainAll(liveIds)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemId(position: Int): Long = itemId(items[position])

    override fun getItemCount(): Int = items.size

    private fun itemId(item: Any): Long = when (item) {
        is DownloadBatchState -> item.batchId
        is DownloadRecord -> item.batchId
        else -> -1L
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        onBindViewHolder(holder, position, emptyList())
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        // 行级异常防护：任何一行绑定出错（Glide 上下文失效等）只丢该行内容，
        // 不能把异常抛进 RecyclerView 布局流程，否则整个列表刷新链路中断
        try {
            when (val item = items[position]) {
                is DownloadBatchState -> {
                    val progressOnly = payloads.isNotEmpty() &&
                        item.phase == DownloadBatchState.Phase.DOWNLOADING
                    if (progressOnly) {
                        bindBatch(holder, item, progressOnly = true)
                    } else {
                        boundPhases[item.batchId] = item.phase
                        bindBatch(holder, item, progressOnly = false)
                    }
                }
                is DownloadRecord -> bindRecord(holder, item)
            }
        } catch (e: Exception) {
            com.clipdownloader.util.LogFile.e("DownloadsAdapter", "行绑定异常", e)
        }
    }

    // ==================== 绑定 ====================

    private fun batchStatusText(batch: DownloadBatchState): String = when (batch.phase) {
        DownloadBatchState.Phase.PARSING -> "解析中"
        DownloadBatchState.Phase.DOWNLOADING -> "下载中"
        DownloadBatchState.Phase.FAILED -> "失败"
        DownloadBatchState.Phase.CANCELLED -> "已取消"
        else -> "已完成"
    }

    private fun batchDetailText(batch: DownloadBatchState): String = when {
        batch.phase == DownloadBatchState.Phase.FAILED -> batch.message
        batch.phase == DownloadBatchState.Phase.CANCELLED -> "已手动取消"
        batch.message.isNotBlank() && batch.results.isEmpty() -> batch.message
        batch.results.isNotEmpty() -> {
            // “成功下载”和“本地已存在而跳过”分开统计，和结果弹窗/历史状态保持一致。
            val ok = batch.results.count { it.success && !it.skippedExisting }
            val skipped = batch.results.count { it.skippedExisting }
            buildString {
                append("成功 $ok/${batch.results.size} 个")
                if (skipped > 0) append("（$skipped 个本地已存在）")
                val name = batch.workId.ifBlank {
                    batch.results.firstOrNull { it.success }?.fileName.orEmpty()
                }
                if (ok > 0 && name.isNotBlank()) append(" · $name")
            }
        }
        else -> batch.message
    }

    private fun bindBatch(holder: VH, batch: DownloadBatchState, progressOnly: Boolean) {
        val b = holder.binding
        // 主页下载条目：缩略图用博主头像，点击打开下载文件夹；普通条目用封面/本地文件
        val covers = if (batch.isProfile) emptyList()
        else batch.coverUrls.ifEmpty { listOfNotNull(batch.coverUrl.ifBlank { null }) }
        val multi = covers.size > 1
        b.cardThumb.visibility = View.VISIBLE

        // 局部刷新：只更新随进度变化的视图，不触碰布局结构/标题/时间
        if (progressOnly) {
            if (multi) {
                val statusText = batchStatusText(batch)
                b.tvStatusM.text = if (batch.totalItems > 1) "$statusText · ${batch.totalItems} 个" else statusText
                b.tvDetailM.text = batchDetailText(batch)
                bindProgress(b.layoutProgressM, b.progressBarM, b.tvProgressTextM, batch)
                bindGrid(holder, covers, batch.itemPaths.toList(), batch.itemKinds, batch.itemDurations, batch.currentKind ?: "", batch.currentDuration, batch.platform, batchGridSizes(batch), batchCellStates(batch))
            } else {
                // 主页条目缩略图是博主头像，进度刷新时也不显示类型/时长角标
                if (batch.isProfile) bindBadges(b.tvTypeS, b.tvDurS, "", 0)
                else bindBadges(b.tvTypeS, b.tvDurS, batch.currentKind ?: "", batch.currentDuration)
                b.tvStatus.text = batchStatusText(batch)
                b.tvDetail.text = batchDetailText(batch)
                bindProgress(b.layoutProgress, b.progressBar, b.tvProgressText, batch)
                // 首个文件落盘后来源从远程封面切到本地文件（来源没变时内部按 tag 跳过）
                val firstPath = batch.itemPaths.firstOrNull { it.isNotBlank() }
                if (batch.isProfile) {
                    b.imgThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    ThumbLoader.load(b.imgThumb, batch.profileAvatar, null, batch.platform)
                    bindProfileThumbClick(b, firstPath)
                } else if ((batch.currentKind ?: "") != "音乐") {
                    ThumbLoader.load(b.imgThumb, covers.firstOrNull(), firstPath, batch.platform)
                    bindThumbSize(b.tvSizeS, firstPath)
                    if (firstPath != null) {
                        b.imgThumb.setOnClickListener { onOpenFile(firstPath) }
                    }
                }
            }
            return
        }

        val title = batch.title.ifBlank {
            batch.results.firstOrNull { it.fileName.isNotBlank() }?.fileName.orEmpty()
        }.ifBlank { batch.shareText }.ifBlank { batch.platform.displayName }
        val statusText = batchStatusText(batch)
        // 完态批量卡与历史记录卡同款布局：统计挪平台标识右侧，
        // 底部行左端作品 ID、右端总大小；进行/失败态维持原有进度/原因显示
        val completed = batch.phase == DownloadBatchState.Phase.COMPLETED
        val counts = if (completed) batchCountsText(batch) else ""
        val detail = if (completed && batch.workId.isNotBlank()) batch.workId else batchDetailText(batch)
        val totalSize = if (completed) batch.itemSizes.sum() else 0L

        // 关键：整行重绑必须显式设置单/多布局可见性。RecyclerView 复用的
        // ViewHolder 可能刚展示过多图记录（layoutSingle 已被置 GONE），
        // 若不重设，新批次（解析中，封面为空走单图布局）的行会复用错误布局，
        // 看起来就是「下载条没出现」——剪贴板自动下载偶现不显示解析条的根因
        b.layoutSingle.visibility = if (multi) View.GONE else View.VISIBLE
        b.layoutMulti.visibility = if (multi) View.VISIBLE else View.GONE

        bindBadges(b.tvTypeS, b.tvDurS, batch.currentKind ?: "", batch.currentDuration)
        // 音乐：缩略图位置显示音乐图标
        if ((batch.currentKind ?: "") == "音乐" && !batch.isProfile) {
            b.imgThumb.setImageResource(R.drawable.ic_music)
            b.imgThumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
        } else {
            b.imgThumb.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        if (multi) {
            b.tvBadgeM.text = batch.platform.displayName
            b.tvTimeM.text = formatTime(batch.startTime)
            // 多文件数量跟在状态后面显示（缩略图网格里不再放「共 N 个」格子）
            b.tvStatusM.text = if (batch.totalItems > 1) "$statusText · ${batch.totalItems} 个" else statusText
            b.tvCountsM.visibility = if (counts.isBlank()) View.GONE else View.VISIBLE
            b.tvCountsM.text = counts
            b.tvTitleM.text = title
            b.tvDetailM.text = detail
            b.tvSizeTotalM.visibility = if (totalSize > 0) View.VISIBLE else View.GONE
            b.tvSizeTotalM.text = if (totalSize > 0) formatBytes(totalSize) else ""
            bindProgress(b.layoutProgressM, b.progressBarM, b.tvProgressTextM, batch)
            bindGrid(holder, covers, batch.itemPaths.toList(), batch.itemKinds, batch.itemDurations, batch.currentKind ?: "", batch.currentDuration, batch.platform, batchGridSizes(batch), batchCellStates(batch))
            bindAction(b.btnActionM, batch)
        } else {
            b.tvBadge.text = batch.platform.displayName
            b.tvTime.text = formatTime(batch.startTime)
            b.tvStatus.text = statusText
            b.tvCounts.visibility = if (counts.isBlank()) View.GONE else View.VISIBLE
            b.tvCounts.text = counts
            b.tvTitle.text = title
            b.tvDetail.text = detail
            b.tvSizeTotal.visibility = if (totalSize > 0) View.VISIBLE else View.GONE
            b.tvSizeTotal.text = if (totalSize > 0) formatBytes(totalSize) else ""
            bindProgress(b.layoutProgress, b.progressBar, b.tvProgressText, batch)
            // 已产出文件的批量条目：缩略图优先用本地文件；主页条目用博主头像，
            // 点击打开下载文件夹（不做 File.exists() 检查，分区存储下可能误判，
            // 交给打开逻辑兜底）
            val firstPath = batch.itemPaths.firstOrNull { it.isNotBlank() }
            if (batch.isProfile) {
                b.tvStateS.visibility = View.GONE
                ThumbLoader.load(b.imgThumb, batch.profileAvatar, null, batch.platform)
                bindProfileThumbClick(b, firstPath)
            } else {
                // 批量条目缩略图右上角状态角标（单图模式，与历史记录一致三态）：
                // 完态按第一个条目结果标——下载中/解析中不标
                val batchThumbState = when {
                    batch.phase != DownloadBatchState.Phase.COMPLETED &&
                        batch.phase != DownloadBatchState.Phase.FAILED -> ""
                    batch.results.firstOrNull()?.skippedExisting == true -> "已存在"
                    batch.results.firstOrNull()?.success == true -> "成功"
                    batch.results.isNotEmpty() -> "失败"
                    else -> ""
                }
                b.tvStateS.visibility = if (batchThumbState.isBlank()) View.GONE else View.VISIBLE
                b.tvStateS.text = batchThumbState
                ThumbLoader.load(b.imgThumb, covers.firstOrNull(), firstPath, batch.platform)
                bindThumbSize(b.tvSizeS, firstPath)
                if (firstPath != null) {
                    b.imgThumb.setOnClickListener { onOpenFile(firstPath) }
                } else {
                    b.imgThumb.setOnClickListener(null)
                }
            }
            bindAction(b.btnAction, batch)
        }
    }

    /** 主页条目缩略图点击：有已落盘文件就打开其所在文件夹（博主归档目录） */
    private fun bindProfileThumbClick(b: ItemDownloadBinding, firstPath: String?) {
        val dir = firstPath?.let { java.io.File(it).parentFile?.absolutePath }
        if (!dir.isNullOrBlank()) {
            b.imgThumb.setOnClickListener { onOpenFolder(dir) }
        } else {
            b.imgThumb.setOnClickListener(null)
        }
    }

    /** 批量网格的大小列表：已落盘条目用 itemSizes，正在下载的条目用已下载字节 */
    private fun batchGridSizes(batch: DownloadBatchState): List<Long> {
        val sizes = batch.itemSizes.toMutableList()
        val idx = batch.currentIndex
        if (idx in sizes.indices && batch.currentBytes > 0) sizes[idx] = batch.currentBytes
        return sizes
    }

    /** 批量网格的逐格状态（完态才标）：跳过=已存在、失败=失败、成功=成功；下载中不标 */
    private fun batchCellStates(batch: DownloadBatchState): List<String> {
        if (batch.phase != DownloadBatchState.Phase.COMPLETED &&
            batch.phase != DownloadBatchState.Phase.FAILED
        ) return emptyList()
        return batch.results.map { r ->
            when {
                r.skippedExisting -> "已存在"
                r.success -> "成功"
                else -> "失败"
            }
        }
    }

    /** 完态批量的统计行（与记录卡一致）：成功 N · 已存在 N · 失败 N（为零的项不显示） */
    private fun batchCountsText(batch: DownloadBatchState): String {
        if (batch.results.isEmpty()) return ""
        val ok = batch.results.count { it.success && !it.skippedExisting }
        val skipped = batch.results.count { it.skippedExisting }
        val failed = (batch.results.size - ok - skipped).coerceAtLeast(0)
        return buildString {
            if (ok > 0) append("成功 $ok")
            if (skipped > 0) { if (isNotEmpty()) append(" · "); append("已存在 $skipped") }
            if (failed > 0) { if (isNotEmpty()) append(" · "); append("失败 $failed") }
        }
    }

    /** 平台徽章右侧的统计行：成功 N · 已存在 N · 失败 N（为零的项不显示，全零空串） */
    private fun recordCountsText(record: DownloadRecord): String {
        if (!record.success) return ""
        val total = record.itemCount
        val skipped = record.skippedCount
        // 成功数用落库的 okCount（不含跳过，含 0——纯跳过就是 0）；
        // 旧记录没有 okCount 字段（Gson 反序列化为 null）时才回退路径数近似
        @Suppress("UNNECESSARY_SAFE_CALL")
        val downloaded = record.okCount
            ?: (record.filePaths ?: emptyList()).count { it.isNotBlank() }
                .takeIf { it > 0 }
            ?: if (record.filePath.isNotBlank()) 1 else 0
        val failed = (total - downloaded - skipped).coerceAtLeast(0)
        return buildString {
            if (downloaded > 0) append("成功 $downloaded")
            if (skipped > 0) { if (isNotEmpty()) append(" · "); append("已存在 $skipped") }
            if (failed > 0) { if (isNotEmpty()) append(" · "); append("失败 $failed") }
        }
    }

    /** 底部行左端：作品 ID（无则空，文件名不再混在这里） */
    private fun recordIdText(record: DownloadRecord): String = record.mediaId

    /** 多图格子的逐格状态：跳过=「已存在」、失败=「失败」、成功=「成功」，三态都标 */
    private fun recordCellStates(record: DownloadRecord): List<String> {
        @Suppress("UNNECESSARY_SAFE_CALL")
        val skipped = record.itemSkipped ?: emptyList()
        @Suppress("UNNECESSARY_SAFE_CALL")
        val errors = record.itemErrors ?: emptyList()
        @Suppress("UNNECESSARY_SAFE_CALL")
        val paths = record.filePaths ?: emptyList()
        val size = maxOf(skipped.size, maxOf(errors.size, paths.size))
        if (size == 0) return emptyList()
        return (0 until size).map { i ->
            when {
                skipped.getOrElse(i) { false } -> "已存在"
                !errors.getOrElse(i) { null }.isNullOrBlank() -> "失败"
                else -> "成功"
            }
        }
    }

    private fun bindRecord(holder: VH, record: DownloadRecord) {
        val b = holder.binding
        // 主页下载记录：缩略图用博主头像，点击打开下载文件夹；普通记录用封面/本地文件
        val covers = if (record.isProfile) emptyList()
        else
            (record.coverUrls ?: emptyList()).ifEmpty {
                listOfNotNull(record.coverUrl?.ifBlank { null })
            }
        val multi = covers.size > 1
        b.cardThumb.visibility = View.VISIBLE
        b.layoutSingle.visibility = if (multi) View.GONE else View.VISIBLE
        b.layoutMulti.visibility = if (multi) View.VISIBLE else View.GONE

        // 打开一律走缩略图点击，右侧按钮固定为「移除」
        val openPath = (record.filePaths ?: emptyList())
            .ifEmpty { listOfNotNull(record.filePath?.takeIf { it.isNotBlank() }) }
            .firstOrNull { it.isNotBlank() } ?: record.filePath
        // 标题：作品文案优先（空则回退文件名）
        val title = record.title.ifBlank { record.fileName }.ifBlank { record.platform.displayName }

        val kind = recordKind(record)
        val durMs = record.durationMs
        val skipped = record.skippedCount
        // 状态行只显示「已完成 / 失败」，数量明细挪到平台徽章右侧统计行
        val status = if (record.success) "已完成" else "失败"
        val counts = recordCountsText(record)
        // 「重下」按钮：只给保存了原始链接的失败记录（成功/跳过记录用设置开关控制重复下载）
        val canRedownload = !record.isProfile && !record.shareText.isNullOrBlank() && !record.success
        b.btnRedownload.visibility = if (canRedownload) View.VISIBLE else View.GONE
        b.btnRedownload.setOnClickListener { onRedownload(record) }
        b.btnRedownloadM.visibility = if (canRedownload) View.VISIBLE else View.GONE
        b.btnRedownloadM.setOnClickListener { onRedownload(record) }

        // 底部行：左端作品 ID（失败记录显示错误原因），右端整批总大小（只算本批落盘文件）
        val detail = if (!record.success) (record.error ?: "下载失败") else recordIdText(record)
        val totalSize = record.fileSize
        // 单图缩略图右上角状态角标：跳过=已存在，失败=失败，成功=成功（三态都标）
        val thumbState = when {
            !record.success -> "失败"
            skipped > 0 -> "已存在"
            else -> "成功"
        }
        b.tvStateS.visibility = if (thumbState.isBlank()) View.GONE else View.VISIBLE
        b.tvStateS.text = thumbState
        // 主页记录：缩略图是博主头像，不显示类型/时长角标
        if (record.isProfile) bindBadges(b.tvTypeS, b.tvDurS, "", 0)
        else bindBadges(b.tvTypeS, b.tvDurS, kind, durMs)
        if (multi) {
            b.tvBadgeM.text = record.platform.displayName
            b.tvTimeM.text = formatTime(record.timestamp)
            b.tvStatusM.text = status
            b.tvCountsM.visibility = if (counts.isBlank()) View.GONE else View.VISIBLE
            b.tvCountsM.text = counts
            b.tvTitleM.text = title
            b.tvDetailM.text = detail
            b.tvSizeTotalM.visibility = if (totalSize > 0) View.VISIBLE else View.GONE
            b.tvSizeTotalM.text = if (totalSize > 0) formatBytes(totalSize) else ""
            b.layoutProgressM.visibility = View.GONE
            b.btnActionM.text = "移除"
            b.btnActionM.setOnClickListener { onRemoveRecord(record) }
            @Suppress("UNNECESSARY_SAFE_CALL")
            val paths = (record.filePaths ?: emptyList()).ifEmpty {
                listOfNotNull(record.filePath?.takeIf { it.isNotBlank() })
            }
            bindGrid(holder, covers, paths, record.kinds ?: emptyList(), record.durations ?: emptyList(), kind, durMs, record.platform, record.sizes ?: emptyList(), recordCellStates(record))
        } else {
            b.tvBadge.text = record.platform.displayName
            b.tvTime.text = formatTime(record.timestamp)
            b.tvStatus.text = status
            b.tvCounts.visibility = if (counts.isBlank()) View.GONE else View.VISIBLE
            b.tvCounts.text = counts
            b.tvTitle.text = title
            b.tvDetail.text = detail
            b.tvSizeTotal.visibility = if (totalSize > 0) View.VISIBLE else View.GONE
            b.tvSizeTotal.text = if (totalSize > 0) formatBytes(totalSize) else ""
            b.layoutProgress.visibility = View.GONE
            b.btnAction.text = "移除"
            b.btnAction.setOnClickListener { onRemoveRecord(record) }
            if (record.isProfile) {
                // 主页记录：头像缩略图（旧记录无头像时回退封面），点击打开下载文件夹
                b.imgThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                val avatarSrc = record.avatarUrl.ifBlank {
                    (record.coverUrls ?: emptyList()).firstOrNull().takeUnless { it.isNullOrBlank() }
                        ?: record.coverUrl.takeUnless { it.isNullOrBlank() } ?: ""
                }
                ThumbLoader.load(b.imgThumb, avatarSrc, null, record.platform)
                bindProfileThumbClick(b, openPath)
            } else if (kind == "音乐") {
                b.imgThumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                b.imgThumb.setImageResource(R.drawable.ic_music)
                bindThumbSize(b.tvSizeS, openPath)
                if (!openPath.isNullOrBlank()) {
                    b.imgThumb.setOnClickListener { onOpenFile(openPath) }
                } else {
                    b.imgThumb.setOnClickListener(null)
                }
            } else {
                // 加载封面（有本地文件优先用本地）
                ThumbLoader.load(b.imgThumb, covers.firstOrNull(), openPath?.takeIf { it.isNotBlank() }, record.platform)
                bindThumbSize(b.tvSizeS, openPath)
                if (!openPath.isNullOrBlank()) {
                    b.imgThumb.setOnClickListener { onOpenFile(openPath) }
                } else {
                    b.imgThumb.setOnClickListener(null)
                }
            }
        }
    }

    /** 缩略图左上角大小角标：显示已落盘文件的体积，无文件隐藏 */
    private fun bindThumbSize(tv: TextView, path: String?) {
        val len = path?.takeIf { it.isNotBlank() }?.let {
            runCatching { java.io.File(it).length() }.getOrDefault(0L)
        } ?: 0L
        if (len > 0) {
            tv.visibility = View.VISIBLE
            tv.text = formatBytes(len)
        } else {
            tv.visibility = View.GONE
        }
    }

    private fun bindAction(btn: com.google.android.material.button.MaterialButton, batch: DownloadBatchState) {
        when (batch.phase) {
            DownloadBatchState.Phase.PARSING,
            DownloadBatchState.Phase.DOWNLOADING -> {
                btn.text = "取消"
                btn.setOnClickListener { onCancel(batch) }
            }
            else -> {
                btn.text = "移除"
                btn.setOnClickListener { onRemoveBatch(batch) }
            }
        }
    }

    private fun bindProgress(
        layout: LinearLayout,
        bar: com.google.android.material.progressindicator.LinearProgressIndicator,
        text: TextView,
        batch: DownloadBatchState
    ) {
        when (batch.phase) {
            DownloadBatchState.Phase.PARSING -> {
                layout.visibility = View.VISIBLE
                bar.isIndeterminate = true
                text.text = "正在解析 ${batch.platform.displayName} 链接"
            }
            DownloadBatchState.Phase.DOWNLOADING -> {
                layout.visibility = View.VISIBLE
                if (batch.currentTotalBytes <= 0) {
                    // 服务器不给 Content-Length（分块传输）时转不确定态，
                    // 固定显示 0% 会让人以为进度条坏了
                    bar.isIndeterminate = true
                    text.text = "${batch.currentIndex + 1}/${batch.totalItems}" +
                        if (batch.currentBytes > 0) "  ${formatBytes(batch.currentBytes)}" else ""
                } else {
                    bar.isIndeterminate = false
                    val pct = ((batch.currentBytes * 100) / batch.currentTotalBytes).toInt().coerceIn(0, 100)
                    // 不带动画：高速下载下动画永远追不上真实进度，刷新频繁时还会
                    // 反复重启动画导致进度条看着卡住；直接跳到真实值最准确
                    bar.setProgress(pct, false)
                    text.text = buildString {
                        append("${batch.currentIndex + 1}/${batch.totalItems}")
                        if (batch.currentBytes > 0) {
                            append("  ").append(formatBytes(batch.currentBytes))
                            append(" / ").append(formatBytes(batch.currentTotalBytes)).append(" ($pct%)")
                        }
                    }
                }
            }
            else -> layout.visibility = View.GONE
        }
    }

    /** 多媒体网格：按实际宽度自适应列数/格子大小；每张缩略图带自己的左上大小/左下类型/右下时长角标，
     *  右上角按条目状态显示「已存在」（成功/下载中不标，失败在文字行体现）。
     *  增量更新：已有格子原地改（不重建 ImageView，避免 Glide 重新请求闪占位图），
     *  新格子追加，多余格子移除；内容没变时直接返回。 */
    private fun bindGrid(
        holder: VH, covers: List<String>, paths: List<String>,
        kinds: List<String>, durations: List<Long>, kind: String, durMs: Long,
        platform: Platform, sizes: List<Long> = emptyList(),
        cellStates: List<String> = emptyList()
    ) {
        val grid = holder.binding.gridThumbs
        // 高频局部刷新时内容没变就跳过
        val sig = "$covers|$paths|$kinds|$durations|$kind|$durMs|$sizes|$cellStates"
        if (grid.getTag(R.id.tag_grid_sig) == sig) return
        val ctx = grid.context

        val populate = populate@{ w: Int ->
            // 宽度未知时保留现有格子等下一轮（清空会导致条目高度塌陷再弹回，视觉上闪跳）
            if (w <= 0) return@populate
            // 成功填充后才记录签名；宽度为 0 时下轮重绑还会重试
            grid.setTag(R.id.tag_grid_sig, sig)
            val minCell = dp(ctx, 72)
            val margin = dp(ctx, 4)
            val columns = (w / minCell).coerceAtLeast(3).coerceAtMost(6)
            grid.columnCount = columns
            val size = ((w - columns * margin) / columns).coerceAtLeast(dp(ctx, 48))
            covers.forEachIndexed { idx, url ->
                // 类型/时长只回退第一个格子（其余格子未下载到时宁可不显示也不能标错）
                val cellKind = kinds.getOrElse(idx) { if (idx == 0) kind else "" }
                val cellDur = durations.getOrElse(idx) { if (idx == 0) durMs else 0L }
                val cellPath = paths.getOrNull(idx)
                val cellState = cellStates.getOrElse(idx) { "" }
                // 大小：sizes 列表（下载中用已下载字节）优先，其次按已落盘文件算
                val cellSize = sizes.getOrElse(idx) { 0L }.takeIf { it > 0 } ?: fileSizeOf(cellPath)
                val cell = grid.getChildAt(idx)
                if (cell is android.widget.FrameLayout) {
                    updateGridCell(ctx, cell, url, cellPath, cellKind, cellDur, cellSize, platform, size, margin, cellState)
                } else {
                    grid.addView(gridCell(ctx, url, size, margin, cellKind, cellDur, cellSize, cellPath, platform, cellState))
                }
            }
            while (grid.childCount > covers.size) grid.removeViewAt(grid.childCount - 1)
        }
        if (grid.width > 0) populate(grid.width)
        else grid.post { populate(grid.width) }
    }

    /** 原地更新已有格子：刷新角标/点击目标/图片来源与尺寸，不重建 ImageView */
    private fun updateGridCell(
        ctx: android.content.Context,
        cell: android.widget.FrameLayout,
        url: String,
        filePath: String?,
        kind: String,
        durMs: Long,
        sizeBytes: Long,
        platform: Platform,
        size: Int,
        margin: Int,
        state: String = ""
    ) {
        (cell.layoutParams as? GridLayout.LayoutParams)?.let {
            it.width = size
            it.height = size
            it.setMargins(0, 0, margin, margin)
        }
        if (!filePath.isNullOrBlank()) cell.setOnClickListener { onOpenFile(filePath) }
        else cell.setOnClickListener(null)
        val img = cell.getChildAt(0) as? ImageView ?: return
        if (kind == "音乐") {
            img.scaleType = ImageView.ScaleType.CENTER_INSIDE
            img.setImageResource(R.drawable.ic_music)
        } else {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            // 来源没变时内部按 tag 跳过，不会重复发起 Glide 请求
            ThumbLoader.load(img, url, filePath, platform)
        }
        // 角标是叠加的小 TextView，重建不影响图片显示
        while (cell.childCount > 1) cell.removeViewAt(cell.childCount - 1)
        badge(ctx, cell, kind, durMs, sizeBytes.takeIf { it > 0 } ?: fileSizeOf(filePath), state)
    }

    private fun gridCell(
        ctx: android.content.Context,
        url: String,
        size: Int,
        margin: Int,
        kind: String,
        durMs: Long,
        sizeBytes: Long,
        filePath: String?,
        platform: Platform,
        state: String = ""
    ): android.widget.FrameLayout {
        return android.widget.FrameLayout(ctx).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(0, 0, margin, margin)
            }
            if (!filePath.isNullOrBlank()) setOnClickListener { onOpenFile(filePath) }
            val img = ImageView(ctx).apply {
                scaleType = if (kind == "音乐") ImageView.ScaleType.CENTER_INSIDE
                else ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            if (kind == "音乐") {
                img.setImageResource(R.drawable.ic_music)
            } else {
                // 已下载完成的格子优先显示本地文件
                ThumbLoader.load(img, url, filePath, platform)
            }
            addView(img)
            badge(ctx, this, kind, durMs, sizeBytes.takeIf { it > 0 } ?: fileSizeOf(filePath), state)
        }
    }

    private fun fileSizeOf(path: String?): Long = path?.takeIf { it.isNotBlank() }?.let {
        runCatching { java.io.File(it).length() }.getOrDefault(0L)
    } ?: 0L

    /** 在缩略图上叠加左上大小 / 左下类型 / 右下时长角标；state 非空时右上角再叠状态（已存在） */
    private fun badge(
        ctx: android.content.Context,
        parent: android.widget.FrameLayout,
        kind: String,
        durMs: Long,
        sizeBytes: Long = 0,
        state: String = ""
    ) {
        if (state.isNotBlank()) {
            parent.addView(TextView(ctx).apply {
                text = state
                textSize = 9f
                setTextColor(-0x1)
                setBackgroundColor(0x99000000.toInt())
                setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                )
            })
        }
        if (sizeBytes > 0) {
            parent.addView(TextView(ctx).apply {
                text = formatBytes(sizeBytes)
                textSize = 9f
                setTextColor(-0x1)
                setBackgroundColor(0x99000000.toInt())
                setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            })
        }
        if (kind.isNotBlank()) {
            parent.addView(TextView(ctx).apply {
                text = kind
                textSize = 9f
                setTextColor(-0x1)
                setBackgroundColor(0x99000000.toInt())
                setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                )
            })
        }
        if (durMs > 0) {
            parent.addView(TextView(ctx).apply {
                text = formatDuration(durMs)
                textSize = 9f
                setTextColor(-0x1)
                setBackgroundColor(0x99000000.toInt())
                setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                )
            })
        }
    }

    /** 记录媒体类型：优先落库值，旧记录按文件扩展名推断 */
    private fun recordKind(record: DownloadRecord): String {
        record.kind?.let { if (it.isNotBlank()) return it }
        if (record.isMotionPhoto) return "动图"
        return when (record.filePath.substringAfterLast('.', "").lowercase()) {
            "mp4", "mov", "m4v" -> "视频"
            "m4a", "mp3", "aac", "wav" -> "音乐"
            "jpg", "jpeg", "png", "webp", "gif", "heic" -> "图片"
            else -> if (record.itemCount > 1) "多个" else ""
        }
    }

    /** 左下角类型 + 右下角时长角标 */
    private fun bindBadges(typeView: TextView, durView: TextView, kind: String, durMs: Long) {
        if (kind.isBlank()) typeView.visibility = View.GONE
        else { typeView.visibility = View.VISIBLE; typeView.text = kind }
        if (durMs > 0) {
            durView.visibility = View.VISIBLE
            durView.text = formatDuration(durMs)
        } else durView.visibility = View.GONE
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).toInt()

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"

        private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun formatTime(ts: Long): String = timeFmt.format(Date(ts))

        /** 时长角标：不足 1 秒按 1 秒算；满 60 秒显示 分:秒（如 1:30） */
        fun formatDuration(ms: Long): String {
            val sec = ((ms + 999) / 1000).coerceAtLeast(1)
            return if (sec >= 60) "${sec / 60}:${String.format(Locale.US, "%02d", sec % 60)}"
            else "${sec}s"
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            return String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }
}
