package com.clipdownloader.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.clipdownloader.util.LogFile
import androidx.core.app.NotificationCompat
import com.clipdownloader.App
import com.clipdownloader.DownloadResultActivity
import com.clipdownloader.R
import com.clipdownloader.model.DownloadBatchState
import com.clipdownloader.model.DownloadRecord
import com.clipdownloader.model.DownloadResult
import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import com.clipdownloader.util.DownloadRecordStore
import com.clipdownloader.util.PreferencesManager
import com.clipdownloader.util.supportsPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载调度中心（全局单例，ClipboardService 与 MainActivity 共享）
 *
 * 流程：解析（可能返回多个媒体）→ 逐个下载 → 动态照片智能处理 → 保存 → 通知
 *
 * 动态照片策略（与设置项「动态照片智能处理」联动）：
 * - 图片项：先判断能否「直接保存为 JPEG 动图」，可以就原样保留 .jpg；
 *          不行再退化为提取内嵌视频保存 .mp4；都不行就当普通图片保存。
 * - 视频项：默认先尝试转成 JPEG 动图（首帧 + 内嵌原视频）；
 *          时长超过阈值（默认 10s）、体积过大、不适合或转换失败时保存 .mp4。
 */
class DownloadManager private constructor(
    private val context: Context,
    private val prefs: PreferencesManager
) {

    companion object {
        private const val TAG = "DownloadManager"
private const val LINE_BREAK = "\n"

        private val notificationIdSeq = AtomicInteger(2000)

        @Volatile private var instance: DownloadManager? = null

        /** 全局共享实例：ClipboardService 与 MainActivity 都从这里取，保证进度可观察 */
        @JvmStatic
        fun get(context: Context): DownloadManager {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: DownloadManager(app, PreferencesManager(app)).also { instance = it }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val douyinParser by lazy { DouyinParser() }
    private val kuaishouParser by lazy { KuaishouParser() }
    private val bilibiliParser by lazy { BilibiliParser() }
    private val xiaohongshuParser by lazy { XiaohongshuParser() }
    private val universalParser by lazy { UniversalParser() }
    private val iiiLabParser by lazy { IiiLabParser() }
    private val youtubeInnerTubeParser by lazy { YouTubeInnerTubeParser(context) { reportParseProgress(it) } }

    private val recordStore: DownloadRecordStore by lazy { DownloadRecordStore.get(context) }

    // ==================== 批量状态（供「下载」页实时观察） ====================

    private val activeBatches = CopyOnWriteArrayList<DownloadBatchState>()
    private val batchListeners = CopyOnWriteArrayList<(List<DownloadBatchState>) -> Unit>()
    // 用时间戳做起点：进程重启后序号若从 1 重新开始，会与历史记录里持久化的
    // batchId 撞号，导致下载页把新批次误判成"已有记录"而隐藏/错配
    private val idSeq = AtomicLong(System.currentTimeMillis())

    fun getBatches(): List<DownloadBatchState> = activeBatches.toList()

    fun addBatchListener(listener: (List<DownloadBatchState>) -> Unit) {
        batchListeners.add(listener)
        listener(activeBatches.toList())
    }

    fun removeBatchListener(listener: (List<DownloadBatchState>) -> Unit) {
        batchListeners.remove(listener)
    }

    /** 请求取消一个批量（下载中的文件会在下个分块检查点中止） */
    fun cancelBatch(batchId: Long) {
        activeBatches.firstOrNull { it.batchId == batchId }?.cancelRequested?.set(true)
        notifyBatchChanged()
    }

    /** 从列表移除（已完成/失败/已取消的批量） */
    fun removeBatch(batchId: Long) {
        activeBatches.removeAll { it.batchId == batchId }
        notifyBatchChanged()
    }

    /** 清除所有已结束（非进行中）的批量条目，配合「清空历史记录」使用 */
    fun clearFinishedBatches() {
        activeBatches.removeAll { !it.isActive }
        notifyBatchChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notifyBatchChanged() {
        // 下载在 IO 线程推进，切回主线程分发（doDownload 内已 200ms 节流，这里直接 post）
        val snapshot = activeBatches.toList()
        mainHandler.post { dispatchToListeners(snapshot) }
        startTicker()
    }

    /** 逐个 listener 隔离异常：任一 UI 回调抛错不能拖垮主线程、也不能阻断后续 listener */
    private fun dispatchToListeners(snapshot: List<DownloadBatchState>) {
        batchListeners.forEach { l ->
            try { l(snapshot) } catch (e: Exception) { LogFile.e(TAG, "批量监听回调异常", e) }
        }
    }

    /**
     * 心跳广播：只要有活跃批量，每 500ms 强制把状态推给监听者。
     * 与 Activity 生命周期完全解耦，即使界面层的事件链路异常也能保证列表实时。
     */
    private val tickerRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val ticker = object : Runnable {
        override fun run() {
            if (activeBatches.none { it.isActive }) {
                tickerRunning.set(false)
                // 收尾再推一次最终状态
                val snapshot = activeBatches.toList()
                dispatchToListeners(snapshot)
                return
            }
            val snapshot = activeBatches.toList()
            dispatchToListeners(snapshot)
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun startTicker() {
        // check-and-set 必须原子：多条 IO 协程并发调用时，
        // 非原子的「判 false→置 true」会把同一 Runnable postDelayed 多份，回调频率翻倍
        if (!tickerRunning.compareAndSet(false, true)) return
        mainHandler.postDelayed(ticker, 500)
    }

    // ==================== 对外入口 ====================

    /**
     * 入队一条分享文本（剪贴板监听 / 手动触发共用）。
     * IO 协程执行，立即返回；进度通过批量监听器回调。
     */
    fun enqueue(shareText: String, platform: Platform) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                processAndDownload(shareText, platform)
            } catch (e: Exception) {
                LogFile.e(TAG, "批量下载异常", e)
            }
        }
    }

    /** 下载预解析好的媒体列表（首页勾选后下载） */
    fun enqueueMediaList(mediaList: List<ParsedMedia>, platform: Platform, rawText: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                processAndDownload(rawText, platform, preParsed = mediaList)
            } catch (e: Exception) {
                LogFile.e(TAG, "勾选下载异常", e)
            }
        }
    }

    /**
     * 从历史记录强制重新下载：用记录里保存的原始分享文本重新解析下载，
     * 忽略「本地已存在」检测。是否覆盖旧文件由设置「覆盖旧文件」开关决定。
     */
    fun enqueueRedownload(record: com.clipdownloader.model.DownloadRecord) {
        val text = record.shareText
        if (text.isNullOrBlank()) {
            notifyResult("无法重新下载", "该记录没有保存原始链接（旧版本创建的记录）")
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                processAndDownload(text, record.platform, forceRedownload = true)
            } catch (e: Exception) {
                LogFile.e(TAG, "重新下载异常", e)
            }
        }
    }

    /**
     * 主页下载栏的下载入口：下载「主页下载」栏目里解析勾选好的作品列表。
     * 存到平台文件夹下以博主名命名的子文件夹，成功的作品记入增量去重（blogger_done）。
     * 列表拉取与逐作解析由 UI 层（MainActivity.parseAndShowProfile）完成后传入。
     */
    fun enqueueProfileWorks(
        works: List<ParsedMedia>,
        platform: Platform,
        rawText: String,
        author: String,
        avatarUrl: String = ""
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                processAndDownload(
                    rawText, platform,
                    preParsed = works,
                    subFolder = author,
                    recordDone = true,
                    isProfile = true,
                    author = author,
                    avatarUrl = avatarUrl
                )
            } catch (e: Exception) {
                LogFile.e(TAG, "主页批量下载异常", e)
            }
        }
    }

    /**
     * 处理一条分享文本（可能是纯链接、带文字的链接、带口令的分享文本）
     * 一条链接内可能包含多张图片 / 多个动图 / 多个视频，全部下载。
     *
     * 重复视频行为由设置驱动：「重复视频重新下载」开=不跳过本地已有作品直接重下，
     * 此时的「覆盖旧文件」开=下载成功后删除同作品旧文件及对应旧记录，关=另存新文件。
     *
     * @param forceRedownload 忽略「本地已存在」检测（记录「重下」按钮入口）
     */
    suspend fun processAndDownload(
        shareText: String,
        platform: Platform,
        preParsed: List<ParsedMedia>? = null,
        subFolder: String? = null,
        recordDone: Boolean = false,
        isProfile: Boolean = false,
        author: String? = null,
        avatarUrl: String? = null,
        forceRedownload: Boolean = false
    ): List<DownloadResult> {
        val batch = DownloadBatchState(idSeq.getAndIncrement(), shareText, platform, isProfile = isProfile)
        batch.subFolder = subFolder
        batch.profileAvatar = avatarUrl.orEmpty()
        // 主页批量：标题固定用博主名（媒体里的 title 是作品文案，不是博主名）
        if (!author.isNullOrBlank()) batch.title = author
        activeBatches.add(0, batch)
        notifyBatchChanged()

        if (prefs.isWifiOnly() && !isWifiConnected()) {
            LogFile.d(TAG, "仅 WiFi 下载已开启，当前非 WiFi，跳过")
            batch.markFailed("仅 WiFi 下载已开启，当前不在 WiFi 环境")
            notifyBatchChanged()
            notifyResult("已跳过下载", "仅 WiFi 下载已开启，当前不在 WiFi 环境")
            saveRecord(batch)
            notifyBatchChanged()
            return emptyList()
        }

        val progressId = notificationIdSeq.incrementAndGet()
        notifyProgress(progressId, "正在解析 ${platform.displayName} 链接…", null)

        // 兜底：循环体内任何意外异常都不能让批次永远卡在「下载中」——
        // 标记失败、收尾通知、写历史记录，ticker 才能随 isActive=false 停止
        try {
        val mediaList = try {
            val parsed = preParsed ?: parseMedia(shareText, platform)
            // 日志主线：一条总量 + 每个媒体一行简写（完整 URL 只在失败详情里出现）
            LogFile.d(TAG, "解析结果：${parsed.size} 个媒体")
            parsed.forEachIndexed { i, m ->
                val u = (m.videoUrl ?: m.imageUrl)
                LogFile.d(TAG, "媒体[$i] ${if (m.videoUrl != null) "视频" else "图片"} · ${u?.let { com.clipdownloader.util.LogFile.briefUrl(it, 0) } ?: "无URL"}")
            }
            parsed
        } catch (e: Exception) {
            LogFile.e(TAG, "解析失败", e)
            emptyList()
        }

        if (batch.cancelRequested.get()) {
            batch.markCancelled()
            notifyBatchChanged()
            cancelNotification(progressId)
            return emptyList()
        }

        if (mediaList.isEmpty()) {
            val reason = if (lastParseError.isNotBlank())
                "解析全部失败（已尝试全部通道）：" + LINE_BREAK + lastParseError
            else
                "未能从 ${platform.displayName} 链接中获取媒体资源"
            batch.markFailed(reason)
            notifyBatchChanged()
            cancelNotification(progressId)
            notifyResult("解析失败", reason)
            saveRecord(batch)
            notifyBatchChanged()
            return emptyList()
        }

        // 封面按「每个媒体一项」收集、不去重：动图已拆成「图片 + 视频」两个媒体，
        // 视频项的 coverUrl 与图片项的 imageUrl 相同，若 distinct() 会把视频项去重掉，
        // 导致下载栏网格里看不到动图的视频媒体（一个动图应占两个格子）
        // 缩略图不设上限：下载多少显示多少
        // 必须保留与 mediaList 完全相同的长度。mapNotNull 会让没有封面的条目
        // 在这里缩短，后续按位置绑定 filePaths/kinds 时会把图片和视频错配。
        batch.coverUrls = mediaList.map { it.coverUrl ?: it.imageUrl ?: "" }
        batch.coverUrl = batch.coverUrls.firstOrNull().orEmpty()
        // 标题：优先作品文案（空则由列表回退文件名）；主页批量已在上面固定用博主名
        if (batch.title.isBlank()) {
            batch.title = mediaList.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: ""
        }
        // 标识：主页批量=主页 ID（space.bilibili.com/数字、/user/xx、/profile/xx），
        // 单作品=BV号/aweme id 等链接里的作品 id，记录归档与列表展示用。
        // 纯跳过（全部已存在）时 mediaList 里带着解析器给的 mediaId，
        // workIdOf 走链接提取兜底可能为空，先用 mediaId 顶——否则纯跳过记录不显示作品 ID
        batch.workId = if (isProfile) profileIdOf(platform, shareText)
            else mediaList.firstNotNullOfOrNull { m -> m.mediaId.trim().takeIf { it.isNotBlank() } }
                ?: mediaList.firstNotNullOfOrNull { workIdOf(it) } ?: ""
        val total = mediaList.size
        val results = mutableListOf<DownloadResult>()
        var cancelled = false

        // 重复视频处理：「重复视频重新下载」开=不跳过本地已有作品直接重下；
        // 「覆盖旧文件」仅在重下生效时起作用（覆盖并删旧文件 / 另存新文件）。
        // 无论覆盖还是另存，历史记录都只留最新一条（saveRecord 统一清同作品旧记录）
        val redownloadDuplicates = forceRedownload || prefs.isRedownloadDuplicates()
        val overwrite = redownloadDuplicates && prefs.isRedownloadOverwrite()
        // 本批各条目的作品标识（文件名内嵌），保存记录时替换同作品旧记录用
        mediaList.forEach { media ->
            workMarkerOf(media).takeIf { it.isNotBlank() }?.let { batch.workMarkers.add(it) }
        }

        // 本地已有识别：扫描保存目录里文件名内置的作品标识（_m<hash>_）。
        // 靠的是实际文件而非历史记录——记录被删后只要文件还在就不会重复下载
        val existingFiles = if (redownloadDuplicates) emptyMap() else existingMarkerFiles()
        var skippedCount = 0

        mediaList.forEachIndexed { index, media ->
            if (batch.cancelRequested.get()) {
                cancelled = true
                return@forEachIndexed
            }
            batch.markDownloading(index, total, media.title.ifBlank { media.platform.displayName })
            batch.currentKind = when {
                media.isMusic -> "音乐"
                media.isMotionPhoto -> "动图"
                media.videoUrl != null -> "视频"
                else -> "图片"
            }
            batch.currentDuration = media.duration
            batch.itemKinds.add(batch.currentKind)
            batch.itemDurations.add(media.duration)
            batch.itemSizes.add(0L)
            batch.itemSkipped.add(false)
            // 路径列表始终按解析条目占位，不能只在完成后 append；否则前面失败时，
            // 后面的缩略图点击会打开别的媒体。
            batch.itemPaths.add("")
            // 记录归档用第一个条目的类型/时长（列表缩略图与角标对应）
            if (index == 0) {
                batch.firstKind = batch.currentKind
                batch.firstDuration = media.duration
            }
            notifyBatchChanged()

            // 本地已存在同一作品（标识命中）：跳过，不重复下载。
            // 已有文件的路径/时长一并带回——记录里照常有缩略图、时长角标，点击可打开。
            // 必须存在「本条目类型对得上」的文件才算已存在：实况图/图集拆出的多个条目
            // 共用作品标识，上次图片项下载失败时本地只有视频文件，
            // 图片条目要照常下载补齐，不能拿视频文件充数（缩略图/点击/大小全错且永远补不上）
            val marker = workMarkerOf(media)
            val keep = pickExistingFile(
                existingFiles[marker].orEmpty().filter { it.isNotBlank() },
                media, marker, index, total
            )
            if (keep != null) {
                skippedCount++
                val dur = if (media.duration > 0) media.duration
                    else runCatching { probeMediaDuration(File(keep)) }.getOrDefault(0L)
                LogFile.d(TAG, "媒体[$index] 本地已存在（标识 $marker），跳过下载：${keep.substringAfterLast('/')}")
                results.add(
                    DownloadResult(
                        success = true,
                        filePath = keep,
                        fileName = keep.substringAfterLast('/'),
                        platform = media.platform,
                        durationMs = dur,
                        skippedExisting = true
                    )
                )
                // 跳过条目：把已有文件路径回填进 itemPaths——缩略图点击要能打开、
                // 大小角标/总大小要显示。误删防护不靠留空 itemPaths，
                // 靠 removeReplacedRecords 改用 workMarkers/mediaId 判据（见该函数）
                batch.itemPaths[index] = keep
                batch.itemSizes[index] = runCatching { File(keep).length() }.getOrDefault(0L)
                batch.itemSkipped[index] = true
                if (dur > 0) {
                    if (index < batch.itemDurations.size) batch.itemDurations[index] = dur
                    batch.currentDuration = dur
                    if (index == 0) batch.firstDuration = dur
                }
                // 补记主页增量去重，下次主页拉取直接在列表阶段跳过
                if (recordDone && media.mediaId.isNotBlank()) {
                    prefs.addBloggerDone(listOf(media.mediaId))
                }
                notifyBatchChanged()
                return@forEachIndexed
            }

            notifyProgress(
                progressId,
                if (total > 1) "下载中 (${index + 1}/$total) · ${platform.displayName}"
                else "下载中 · ${platform.displayName}",
                media.title.ifBlank { null }
            )
            val result = try {
                downloadMediaItem(batch, media, index, total).also { r ->
                    // 失败详情在这行体现（错误原因已带 HTTP 状态），成功则由保存行体现
                    if (!r.success) LogFile.w(TAG, "媒体[$index] 失败：${r.error}")
                }
            } catch (e: Exception) {
                LogFile.e(TAG, "下载失败 [$index]", e)
                DownloadResult(success = false, platform = media.platform, error = e.message)
            }
            results.add(result)
            if (result.success && !result.skippedExisting) {
                batch.itemPaths[index] = result.filePath ?: ""
                if (index < batch.itemSizes.size) {
                    batch.itemSizes[index] = result.filePath?.let {
                        runCatching { File(it).length() }.getOrDefault(0L)
                    } ?: 0L
                }
            }
            // 覆盖模式：新文件落盘成功后清掉同作品的旧文件与对应旧记录
            // （失败不动旧文件，避免两头空）；保留集用整批已落盘路径——
            // 实况图一次存「视频+图片」两个同标识新文件，都不能误删
            if (overwrite && result.success && !result.skippedExisting) {
                deleteOldMarkerFiles(marker, batch.itemPaths.toList())
            }
            // 下载后探测到的真实时长回填（实况视频/接口没给时长的视频项）。
            // 除了 itemDurations（多媒体网格角标），单视频下载栏角标用的是
            // currentDuration/firstDuration，云解析接口不给时长时必须一并回填，
            // 否则单个视频下载栏与历史记录不显示时长
            if (result.durationMs > 0) {
                if (index < batch.itemDurations.size) batch.itemDurations[index] = result.durationMs
                batch.currentDuration = result.durationMs
                if (index == 0) batch.firstDuration = result.durationMs
            }
            if (recordDone && result.success && media.mediaId.isNotBlank()) {
                prefs.addBloggerDone(listOf(media.mediaId))
            }
            notifyBatchChanged()
        }

        if (cancelled || batch.cancelRequested.get()) {
            batch.markCancelled()
            notifyBatchChanged()
            cancelNotification(progressId)
            return results
        }

        cancelNotification(progressId)
        batch.markCompleted(results)
        notifyBatchChanged()
        LogFile.d(TAG, "批量结束：共 ${results.size} 条，成功 ${results.count { it.success }} 个，准备写入历史记录")
        saveRecord(batch)
        notifyBatchChanged()

        if (prefs.isShowDownloadResult()) {
            val okCount = results.count { it.success && !it.skippedExisting }
            val skipped = results.count { it.skippedExisting }
            val motionCount = results.count { it.success && it.isMotionPhoto }
            if (okCount == 0 && skipped > 0) {
                notifyResult("跳过下载", "本地已存在，跳过 $skipped 个（未重复下载）")
            } else if (okCount == 0) {
                notifyResult(
                    "下载失败",
                    results.firstOrNull()?.error ?: "未知错误"
                )
            } else {
                val detail = buildString {
                    append("${platform.displayName} · 共 $okCount 个文件")
                    if (motionCount > 0) append("（动图 $motionCount）")
                    if (skipped > 0) append("，跳过已存在 $skipped")
                    if (okCount < total - skipped) append("，失败 ${total - skipped - okCount}")
                }
                notifyResult("下载完成", detail)
            }
        }

        return results
        } catch (e: Exception) {
            LogFile.e(TAG, "批量下载异常（兜底收尾）", e)
            batch.markFailed("批量异常：${e.message ?: "未知"}")
            notifyBatchChanged()
            cancelNotification(progressId)
            saveRecord(batch)
            notifyBatchChanged()
            return emptyList()
        }
    }

    /** 批量结束后写入历史记录 */
    private fun saveRecord(batch: DownloadBatchState) {
        try {
            // 「下载成功」只算真正落盘的条目，本地已存在跳过的单独统计——
            // 跳过条目的 success=true 只是为了不记入 error，不能算进成功数，
            // 否则「图片跳过+视频下载」会显示 2 个成功
            val okResults = batch.results.filter { it.success && !it.skippedExisting }
            val okCount = okResults.size
            val firstOk = okResults.firstOrNull()

            // 整批纯跳过（本地已存在）也照常写记录：新记录替换同作品旧记录，
            // 保证「同作品只留一条」且完态批量卡能被记录卡顶替（否则列表同时
            // 挂着批量卡和旧记录两条，底部行样式还不一致）
            val record = DownloadRecord(
                batchId = batch.batchId,
                platform = batch.platform,
                coverUrl = batch.coverUrl ?: "",
                coverUrls = batch.coverUrls ?: emptyList(),
                kind = batch.firstKind ?: "",
                durationMs = batch.firstDuration,
                kinds = batch.itemKinds.toList(),
                durations = batch.itemDurations.toList(),
                sizes = batch.itemSizes.toList(),
                filePaths = batch.itemPaths.toList(),
                itemSkipped = batch.itemSkipped.toList(),
                itemErrors = batch.results.map { it.error },
                // 标题：作品文案优先，没有用文件名，最后退分享文本
                title = batch.title.ifBlank {
                    firstOk?.fileName?.takeIf { it.isNotBlank() } ?: batch.shareText.take(40)
                },
                fileName = firstOk?.fileName ?: "",
                filePath = firstOk?.filePath ?: "",
                // 整体成败：有真正下载成功的、或整批纯跳过（本地已存在）都算成功；
                // 纯失败（无成功无跳过）才是失败。跳过不算「新下载成功」，但算「记录有效」，
                // 否则纯跳过记录会被标成失败混进「清除失败」里
                success = okCount > 0 || (batch.results.isNotEmpty() && batch.results.all { it.success }),
                error = batch.results.firstOrNull { it.error != null }?.error,
                isMotionPhoto = okResults.any { it.isMotionPhoto },
                // 整批总大小（全部落盘文件之和），不是单个文件
                fileSize = batch.itemPaths.sumOf {
                    runCatching { File(it).length() }.getOrDefault(0L)
                },
                itemCount = batch.results.size,
                timestamp = if (batch.endTime > 0) batch.endTime else System.currentTimeMillis(),
                isProfile = batch.isProfile,
                avatarUrl = batch.profileAvatar,
                shareText = batch.shareText,
                skippedCount = batch.results.count { it.skippedExisting },
                okCount = okCount,
                mediaId = batch.workId
            )
            recordStore.add(record)
            LogFile.d(TAG, "历史记录已写入：batchId=${record.batchId} success=${record.success} path=${record.filePath.take(60)}")
            // 同作品只留一条记录：无论覆盖/另存/纯跳过重下，都清掉旧记录
            removeReplacedRecords(record, batch)
        } catch (e: Exception) {
            LogFile.w(TAG, "保存下载记录失败: ${e.message}")
        }
    }

    /**
     * 同作品旧记录自动清理：保证同作品只留最新一条记录。
     * 覆盖重下、另存重下、纯跳过（本地已存在）重下都会清理——用户明确要求
     * 「重新下载只保留一条记录」，旧记录对应的旧文件保留在磁盘上，只是不再
     * 在记录列表里单独占一条。
     *
     * 判据用 batch.workMarkers（解析阶段就算好的作品标识，跳过/新下载条目都有），
     * 不用 record.filePaths——跳过的条目路径是已有文件、新下载的是新文件，
     * 混在一起会让「按路径含标识匹配旧记录」误删/漏删。mediaId 做辅助通道。
     * 安全闸：本批至少有一个成功条目（含跳过）才清理——纯失败批次没有可用文件，
     * 删旧记录会两头空。主页记录与普通记录互不越界（isProfile 相同才比较）。
     */
    private fun removeReplacedRecords(record: DownloadRecord, batch: DownloadBatchState) {
        try {
            if (batch.workMarkers.isEmpty()) return
            // 纯失败批次不清理：旧记录对应着仅存的可用文件
            val anySuccess = batch.results.any { it.success }
            if (!anySuccess) return
            val markerRegexes = batch.workMarkers.map { Regex("_m${it}_") }
            val removed = recordStore.getRecords().filter { old ->
                old.batchId != record.batchId &&
                    old.isProfile == record.isProfile &&
                    (
                        // 通道一：作品 ID 直接命中（B站 BV 号/抖音 aweme id 等）
                        (record.mediaId.isNotBlank() && old.mediaId == record.mediaId) ||
                        // 通道二：旧记录文件名里带本批作品标识
                        old.filePaths.any { p ->
                            p.isNotBlank() && markerRegexes.any { it.containsMatchIn(p) }
                        }
                    )
            }
            removed.forEach { recordStore.remove(it.batchId) }
            if (removed.isNotEmpty()) {
                LogFile.d(TAG, "同作品旧记录已清理：${removed.size} 条（batchId=${removed.joinToString { it.batchId.toString() }}）")
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "清理被替换的旧记录失败: ${e.message}")
        }
    }

    // ==================== 解析 ====================

    /** 最近一次解析的错误明细（云端通道失败原因等），供 UI 弹提示 */
    @Volatile var lastParseError: String = ""
        private set

    // ---- 解析进度事件（首页实时显示「正在用哪个通道、状态如何」）----

    private val parseProgressListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addParseProgressListener(listener: (String) -> Unit) {
        parseProgressListeners.add(listener)
    }

    fun removeParseProgressListener(listener: (String) -> Unit) {
        parseProgressListeners.remove(listener)
    }

    /** 上报一条解析进度（通道尝试/失败/成功），切主线程分发给监听者 */
    private fun reportParseProgress(text: String) {
        LogFile.d(TAG, "解析进度：$text")
        mainHandler.post { parseProgressListeners.forEach { it(text) } }
    }

    /** 单次解析（首页预览 / 主页下载栏解析用），遵循本地/云端解析设置。
     *  withQualities=true 时本地解析尽量枚举全部清晰度档（B站多 qn 探测较慢，仅手动路径开启）；
     *  解析后并行探测缺失体积（HEAD/Range），保证大小角标/总大小尽量有数可显 */
    suspend fun parseOnce(shareText: String, platform: Platform, withQualities: Boolean = false): List<ParsedMedia> {
        val media = parseMedia(shareText, platform, withQualities)
        return if (media.isEmpty()) media else fillMissingSizes(media)
    }

    /** 并行补齐媒体缺失体积（HEAD/Range 探测），供主页下载栏汇总与首页大小角标 */
    suspend fun probeSizes(media: List<ParsedMedia>): List<ParsedMedia> = fillMissingSizes(media)

    /**
     * 并行探测缺失体积：无画质档（或全档无 size）的视频与全部图片，
     * 用 HEAD（失败退 Range 0-0 GET）读 Content-Length。
     * googlevideo 直链绑定解析服务 IP，探测必失败直接跳过。
     * 单个失败不影响其它，整体超时保护。
     */
    private suspend fun fillMissingSizes(media: List<ParsedMedia>): List<ParsedMedia> {
        val targets = media.filter { m ->
            val url = m.videoUrl ?: m.imageUrl
            !url.isNullOrBlank() &&
                !url.contains("googlevideo.com") &&
                m.sizeBytes <= 0 &&
                (m.videoUrl == null || m.qualities.none { it.sizeBytes > 0 })
        }
        if (targets.isEmpty()) return media
        val sizes = java.util.Collections.synchronizedMap(HashMap<String, Long>())
        coroutineScope {
            targets.forEach { m ->
                val url = (m.videoUrl ?: m.imageUrl).orEmpty()
                launch {
                    val len = runCatching {
                        withTimeout(8_000L) { probeContentLength(url, m.originalUrl) }
                    }.getOrNull() ?: 0L
                    if (len > 0) sizes[url] = len
                }
            }
        }
        if (sizes.isEmpty()) return media
        LogFile.d(TAG, "体积探测：${sizes.size}/${targets.size} 个补齐")
        return media.map { m ->
            val url = m.videoUrl ?: m.imageUrl
            if (url != null && m.sizeBytes <= 0) sizes[url]?.let { m.copy(sizeBytes = it) } ?: m else m
        }
    }

    /** 读远程文件体积：HEAD 优先，不给 Content-Length 就 Range 0-0 GET 读 Content-Range */
    private suspend fun probeContentLength(url: String, referer: String): Long {
        val isBiliCdn = url.contains("bilivideo.com") || referer.contains("bilibili.com")
        fun buildReq(range: Boolean): Request {
            val b = Request.Builder().url(url)
                .header("User-Agent", if (isBiliCdn) DESKTOP_USER_AGENT else USER_AGENT)
            if (referer.isNotBlank()) b.header("Referer", referer)
            if (range) b.header("Range", "bytes=0-0")
            return b.build()
        }
        // 同步 execute() 走 suspendCancellableCoroutine + invokeOnCancellation：
        // 外层 withTimeout 取消协程时立刻 call.cancel()，不会傻等 readTimeout
        suspend fun exec(req: Request, pick: (okhttp3.Response) -> Long): Long =
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(req)
                cont.invokeOnCancellation { call.cancel() }
                try {
                    call.execute().use { resp ->
                        cont.resumeWith(Result.success(pick(resp)))
                    }
                } catch (_: Exception) {
                    cont.resumeWith(Result.success(0L))
                }
            }
        exec(buildReq(range = false)) { resp ->
            resp.header("Content-Length")?.toLongOrNull() ?: 0L
        }.let { if (it > 0) return it }
        exec(buildReq(range = true)) { resp ->
            resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: resp.header("Content-Length")?.toLongOrNull() ?: 0L
        }.let { if (it > 0) return it }
        return 0L
    }

    private suspend fun parseMedia(
        shareText: String,
        platform: Platform,
        withQualities: Boolean = false
    ): List<ParsedMedia> {
        // 国外平台单独调度（2026-08-22 起）：iiiLab 通道优先，原逻辑全部保留为回落
        if (platform.isOverseas) return parseOverseas(shareText, platform)

        val errors = mutableListOf<String>()
        val cloudParser = CloudParser(context, prefs, onProgress = ::reportParseProgress)

        suspend fun tryIii(): List<ParsedMedia> {
            reportParseProgress("尝试通道：iiiLab")
            val r = try {
                iiiLabParser.parseAll(shareText, platform)
            } catch (e: Exception) {
                LogFile.w(TAG, "iiiLab 解析异常: ${e.javaClass.simpleName}: ${e.message}"); emptyList()
            }
            if (r.isNotEmpty()) {
                lastParseError = ""
                reportParseProgress("解析成功 · iiiLab")
                return cloudParser.fillMissingDurations(r)
            }
            val reason = iiiLabParser.lastError.ifBlank { "无结果" }
            errors += "· iiiLab —— $reason"
            reportParseProgress("通道失败：iiiLab —— $reason")
            return emptyList()
        }

        suspend fun tryCloud(): List<ParsedMedia> {
            val iiiEnabled = platform.name.lowercase() in prefs.getIiiLabPlatforms()
            val preferred = prefs.getPlatformChannel(platform.name.lowercase())

            // 首选是 iiiLab：先试，失败再走其余云端通道
            if (preferred == "iiilab" && iiiEnabled) {
                val iii = tryIii()
                if (iii.isNotEmpty()) return iii
            }
            val cloud = try {
                cloudParser.parseAll(shareText, platform)
            } catch (e: Exception) {
                LogFile.w(TAG, "云端解析异常: ${e.message}"); emptyList()
            }
            if (cloud.isNotEmpty()) {
                lastParseError = ""
                return cloud
            }
            cloudParser.channelErrors.entries.forEach { (c, e) -> errors += "· ${c.take(50)} —— $e" }
            if (iiiEnabled && preferred != "iiilab") {
                val iii = tryIii()
                if (iii.isNotEmpty()) return iii
            }
            return emptyList()
        }

        suspend fun tryLocal(): List<ParsedMedia> {
            reportParseProgress("尝试通道：本地解析（${platform.displayName}）")
            val local = try {
                when (platform) {
                    Platform.DOUYIN -> douyinParser.parseAll(shareText)
                    Platform.KUAISHOU -> kuaishouParser.parse(shareText)
                    Platform.BILIBILI -> listOfNotNull(bilibiliParser.parse(shareText, withQualities))
                    Platform.XIAOHONGSHU -> xiaohongshuParser.parseAll(shareText)
                    Platform.WEIBO -> WeiboParser(prefs.getWeiboEndpoint()).parseAll(shareText)
                    else -> emptyList()
                }
            } catch (e: Exception) {
                LogFile.w(TAG, "本地解析异常: ${e.message}"); emptyList()
            }
            if (local.isNotEmpty()) {
                lastParseError = ""
                reportParseProgress("解析成功 · 本地解析（${platform.displayName}）")
            } else {
                errors += "· 本地解析 —— 无结果"
                reportParseProgress("通道失败：本地解析（${platform.displayName}）")
            }
            return local
        }

        // 首选=本地解析：本地优先，云端与 iiiLab 回落；
        // 首选=云端通道/iiiLab：云端优先（CloudParser 已按平台首选排序），iiiLab 次之，本地解析器最后兜底
        val result = if (prefs.getPlatformChannel(platform.name.lowercase()) == "local") {
            tryLocal().ifEmpty { tryCloud().ifEmpty { tryIii() } }
        } else {
            tryCloud().ifEmpty { tryIii().ifEmpty { tryLocal() } }
        }
        if (result.isEmpty()) {
            lastParseError = errors.joinToString(LINE_BREAK).ifBlank { "所有通道均无结果" }
            LogFile.w(TAG, "解析全部失败：$lastParseError")
        }
        return result
    }

    /**
     * 国外平台解析调度：按「平台首选通道（设置页下拉）→ 其余通道」逐个尝试，
     * 每级失败原因都进错误明细：
     * - TikTok：本地 tikwm / MaxHelper / iiiLab（下拉首选，其余固定顺序兜底）
     * - YouTube：本地 InnerTube（visionos，含 PO token）/ iiiLab
     * - 其余海外平台：海外首选通道（设置页「首选云端通道」= iiiLab 或云端通道）
     *   → iiiLab 兜底 → 云端通道列表（iiiLab 失效或后续新增通道都能覆盖）
     *
     * YouTube 特殊性：googlevideo 直链绑定「提取时的请求 IP」，iiiLab 等服务端
     * 代提的直链手机下载必 403（与 UA/Referer 无关），所以本地通道优先。
     */
    private suspend fun parseOverseas(shareText: String, platform: Platform): List<ParsedMedia> {
        val errors = mutableListOf<String>()
        val cloudParser = CloudParser(context, prefs, onProgress = ::reportParseProgress)

        // 通道顺序：该平台设置的首选优先，其余按固定顺序补位；
        // TikTok/YouTube = 下拉首选 → 本地 → iiiLab → 支持该平台的云端通道
        // 其余海外平台 = 下拉首选通道（iiiLab 或云端通道）→ iiiLab 兜底 → 支持该平台的云端通道
        val supportedChannelUrls = prefs.getCloudChannels()
            .filter { it.supportsPlatform(platform) }
            .map { it.url }
        // iiiLab 是否对该平台生效（内置通道，支持平台可在「云解析通道」管理里配置）
        val platformKey = if (platform == Platform.OTHER)
            com.clipdownloader.util.PreferencesManager.ChannelPlatforms.OVERSEAS_OTHER
        else platform.name.lowercase()
        val iiiEnabled = platformKey in prefs.getIiiLabPlatforms()

        val order: List<String> = when (platform) {
            Platform.TIKTOK -> (
                listOfNotNull(
                    // 兼容旧值：maxhelper 字面量已废弃；iiiLab 被停用时回落 local
                    prefs.getPlatformChannel("tiktok").takeIf { it != "maxhelper" && (it != "iiilab" || iiiEnabled) } ?: "local",
                    "local",
                    "iiilab".takeIf { iiiEnabled }
                ) + supportedChannelUrls
                ).distinct()
            Platform.YOUTUBE -> (
                listOfNotNull(
                    prefs.getPlatformChannel("youtube").takeIf { it != "iiilab" || iiiEnabled } ?: "local",
                    "local",
                    "iiilab".takeIf { iiiEnabled }
                ) + supportedChannelUrls
                ).distinct()
            else -> (
                listOfNotNull(
                    prefs.getPlatformChannel(platformKey).takeIf { it != "iiilab" || iiiEnabled },
                    // 未知链接可能是国内站：再补「国内其他」首选（仅云端通道，无本地解析器）
                    if (platform == Platform.OTHER)
                        prefs.getPlatformChannel(
                            com.clipdownloader.util.PreferencesManager.ChannelPlatforms.DOMESTIC_OTHER
                        ).takeIf { it.startsWith("http") }
                    else null,
                    "iiilab".takeIf { iiiEnabled }
                ) + supportedChannelUrls
                ).distinct()
        }

        for (key in order) {
            // 云端通道（http 开头 = 通道 url）：进度与错误明细由 CloudParser 上报
            if (key.startsWith("http")) {
                val chLabel = prefs.getCloudChannels().firstOrNull { it.url == key }
                    ?.name?.ifBlank { key.take(30) } ?: key.take(30)
                val media = try {
                    cloudParser.parseAll(shareText, platform, channelOrder = listOf(key))
                } catch (e: Exception) {
                    LogFile.w(TAG, "云端通道 $chLabel 解析异常: ${e.javaClass.simpleName}: ${e.message}")
                    emptyList()
                }
                if (media.isNotEmpty()) {
                    lastParseError = ""
                    return media
                }
                errors += "· 云端 $chLabel —— ${cloudParser.channelErrors.entries.firstOrNull()?.value ?: "无结果"}"
                continue
            }

            val label = when {
                key == "local" && platform == Platform.TIKTOK -> "TikTok 本地（tikwm）"
                key == "local" -> "YouTube 本地（InnerTube）"
                else -> "iiiLab"
            }
            reportParseProgress("尝试通道：$label")
            val media = try {
                when (key) {
                    "local" ->
                        if (platform == Platform.TIKTOK) universalParser.parseAll(shareText, platform)
                        else youtubeInnerTubeParser.parseAll(shareText, platform)
                    else -> iiiLabParser.parseAll(shareText, platform)
                }
            } catch (e: Exception) {
                LogFile.w(TAG, "$label 解析异常: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            if (media.isNotEmpty()) {
                lastParseError = ""
                reportParseProgress("解析成功 · $label")
                // iiiLab 云端结果缺时长需补探测；本地 InnerTube 自带时长
                return if (key == "local") media else cloudParser.fillMissingDurations(media)
            }
            val reason = when {
                key == "local" && platform == Platform.TIKTOK -> "无结果"
                key == "local" -> youtubeInnerTubeParser.lastError.ifBlank { "无结果" }
                else -> iiiLabParser.lastError.ifBlank { "无结果" }
            }
            errors += "· $label —— $reason"
            reportParseProgress("通道失败：$label —— $reason")
        }

        lastParseError = errors.joinToString(LINE_BREAK).ifBlank { "所有通道均无结果" }
        LogFile.w(TAG, "海外解析全部失败：$lastParseError")
        return emptyList()
    }

    // ==================== 主页批量逐作解析（全通道轮换 + 重试 + 风控退避） ====================

    /**
     * 风控信号：错误文案命中后，加大与下一次解析请求的间隔（指数退避）。
     * 覆盖 MaxHelper 429/auth 失败、B站 412、iiiLab「操作太频繁」、验证码等。
     */
    private fun isRiskControlSignal(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val e = error.lowercase()
        return e.contains("429") || e.contains("412") || e.contains("频繁") ||
            e.contains("稍作休息") || e.contains("auth 请求失败") || e.contains("验证码") ||
            e.contains("稍后再试") || e.contains("稍候") || e.contains("rate limit") ||
            e.contains("too many requests")
    }

    /**
     * 主页下载栏逐作解析：每个作品把该平台「全部云端解析通道」同时并发请求
     * （首选通道也参与，最先返回成功结果的通道胜出，其余请求取消），
     * 全部云通道都失败才启用本地解析器兜底。
     * 首遍失败的作品进入下一轮重试，直到全部解析完成或一整轮零进展。
     * 返回 (解析出的全部媒体, 成功作品数)，进度经 onProgress 回调（IO 线程）。
     */
    suspend fun parseProfileWorks(
        todo: List<ParsedMedia>,
        platform: Platform,
        onProgress: (attempted: Int, ok: Int, total: Int) -> Unit
    ): Pair<List<ParsedMedia>, Int> = withContext(Dispatchers.IO) {
        val cloudParser = CloudParser(context, prefs, onProgress = ::reportParseProgress)

        val hasLocal = when (platform) {
            Platform.DOUYIN, Platform.KUAISHOU, Platform.BILIBILI,
            Platform.XIAOHONGSHU, Platform.WEIBO -> true
            else -> false
        }
        val cloudKeys = mutableListOf<String>()
        val cloudUrls = prefs.getCloudChannels().filter { it.supportsPlatform(platform) }.map { it.url }
        val preferred = prefs.getPlatformChannel(platform.name.lowercase())
        if (preferred in cloudUrls) cloudKeys.add(preferred)
        cloudKeys.addAll(cloudUrls.filter { it != preferred })
        if (platform.name.lowercase() in prefs.getIiiLabPlatforms()) cloudKeys.add("iiilab")
        val raceKeys = cloudKeys.toList()

        suspend fun tryChannel(key: String, item: ParsedMedia): List<ParsedMedia> = when {
            key == "local" -> {
                reportParseProgress("尝试通道：本地解析（${platform.displayName}）")
                val r = try {
                    when (platform) {
                        Platform.DOUYIN -> douyinParser.parseAll(item.rawShareText)
                        Platform.KUAISHOU -> kuaishouParser.parse(item.rawShareText)
                        Platform.BILIBILI -> listOfNotNull(bilibiliParser.parse(item.rawShareText, withQualities = true))
                        Platform.XIAOHONGSHU -> xiaohongshuParser.parseAll(item.rawShareText)
                        Platform.WEIBO -> WeiboParser(prefs.getWeiboEndpoint()).parseAll(item.rawShareText)
                        else -> emptyList()
                    }
                } catch (e: Exception) {
                    LogFile.w(TAG, "本地解析异常: ${e.message}"); emptyList()
                }
                if (r.isNotEmpty()) reportParseProgress("解析成功 · 本地解析（${platform.displayName}）")
                r
            }
            key == "iiilab" -> {
                reportParseProgress("尝试通道：iiiLab")
                val r = try {
                    iiiLabParser.parseAll(item.rawShareText, platform)
                } catch (e: Exception) {
                    LogFile.w(TAG, "iiiLab 解析异常: ${e.message}"); emptyList()
                }
                if (r.isNotEmpty()) {
                    reportParseProgress("解析成功 · iiiLab")
                    cloudParser.fillMissingDurations(r)
                } else {
                    val reason = iiiLabParser.lastError.ifBlank { "无结果" }
                    reportParseProgress("通道失败：iiiLab —— $reason")
                    r
                }
            }
            else -> {
                val r = try {
                    cloudParser.parseAll(item.rawShareText, platform, channelOrder = listOf(key))
                } catch (e: Exception) {
                    LogFile.w(TAG, "云端通道解析异常: ${e.message}"); emptyList()
                }
                r
            }
        }

        /**
         * 云通道并发竞速：全部同时发请求，最先返回非空结果的通道胜出并取消其余。
         * 全部失败返回 null。
         */
        suspend fun raceCloud(item: ParsedMedia): Pair<String, List<ParsedMedia>>? {
            if (raceKeys.isEmpty()) return null
            val first = CompletableDeferred<Pair<String, List<ParsedMedia>>>()
            val remaining = java.util.concurrent.atomic.AtomicInteger(raceKeys.size)
            val jobs = raceKeys.map { key ->
                launch {
                    val r = runCatching { tryChannel(key, item) }.getOrDefault(emptyList())
                    if (r.isNotEmpty() && first.isActive) first.complete(key to r)
                    // 全部通道都失败时放行（首个成功已占位时 complete 返回 false）
                    if (remaining.decrementAndGet() == 0) first.complete("__none__" to emptyList())
                }
            }
            val winner = first.await()
            // 未完成的请求直接取消（已发出但慢的通道不再等）。
            // 协程 cancel 打不断 OkHttp 同步 execute()，必须同时掐断在途 Call，
            // 否则败者通道的线程/连接要拖到 readTimeout 才释放
            jobs.forEach { it.cancel() }
            cloudParser.cancelInFlight()
            iiiLabParser.cancelInFlight()
            return winner.takeUnless { it.first == "__none__" }
        }

        val results = mutableListOf<ParsedMedia>()
        val pending = ArrayDeque(todo)
        val total = todo.size
        var attempted = 0
        var okCount = 0
        val maxRounds = 3

        for (round in 1..maxRounds) {
            if (pending.isEmpty()) break
            var roundProgress = 0
            val snapshot = pending.toList()
            for (item in snapshot) {
                // 列表已带直链的作品（抖音/快手）直接采用，无需再解析
                if (!item.videoUrl.isNullOrBlank()) {
                    attempted++
                    pending.remove(item)
                    results.add(item)
                    okCount++
                    roundProgress++
                    onProgress(attempted, okCount, total)
                    continue
                }
                // 云通道并发竞速；全部失败才回落本地解析器
                var media = raceCloud(item)?.second ?: emptyList()
                if (media.isEmpty() && hasLocal) {
                    media = tryChannel("local", item)
                }
                // B站云端通道不带画质档（video_fullinfo 恒空）：本地解析补一档，
                // 否则画质滑块会消失
                if (media.isNotEmpty() && platform == Platform.BILIBILI &&
                    hasLocal && media.all { it.qualities.isEmpty() }
                ) {
                    val enriched = tryChannel("local", item)
                    if (enriched.isNotEmpty() && enriched.any { it.qualities.isNotEmpty() }) media = enriched
                }
                attempted++
                if (media.isNotEmpty()) {
                    pending.remove(item)
                    results.addAll(media)
                    okCount++
                    roundProgress++
                    // 正常间隔：给目标服务留出喘息，也避免瞬间并发触发风控
                    delay(300)
                }
                onProgress(attempted, okCount, total)
            }
            if (roundProgress == 0) {
                LogFile.w(TAG, "主页逐作解析：第 $round 轮零进展，停止重试（剩 ${pending.size} 个）")
                break
            }
            if (pending.isNotEmpty()) delay(2_000)
        }
        LogFile.d(TAG, "主页逐作解析完成：成功 $okCount/$total，媒体 ${results.size} 个")
        results to okCount
    }

    // ==================== 单项下载 ====================

    private suspend fun downloadMediaItem(
        batch: DownloadBatchState,
        media: ParsedMedia,
        index: Int,
        total: Int
    ): DownloadResult = withContext(Dispatchers.IO) {
        val videoUrl = media.videoUrl?.takeIf { it.isNotBlank() }
        val imageUrl = media.imageUrl?.takeIf { it.isNotBlank() }

        // 实况图：解析器标 isMotionPhoto 且同时带着原图和 live 视频
        // → 同时保存「视频 (.mp4) + 图片 (.jpg)」两份，不做合成
        if (media.isMotionPhoto && videoUrl != null && imageUrl != null) {
            return@withContext downloadLivePhotoItem(batch, media, videoUrl, imageUrl, index, total)
        }

        when {
            videoUrl != null -> downloadVideoItem(batch, media, videoUrl, index, total)
            imageUrl != null -> downloadImageItem(batch, media, imageUrl, index, total)
            else -> DownloadResult(
                success = false,
                platform = media.platform,
                error = "该条目没有可下载的媒体地址"
            )
        }
    }

    /**
     * 平台侧实况图（小红书/抖音图集里的 live 图）：
     * → 分别保存「视频 (.mp4) + 图片 (.jpg)」两份独立文件
     *   不打包 Motion Photo，避免用户混淆
     */
    private fun downloadLivePhotoItem(
        batch: DownloadBatchState,
        media: ParsedMedia,
        videoUrl: String,
        coverUrl: String,
        index: Int,
        total: Int
    ): DownloadResult {
        val dir = ensureDir(media.platform, batch.subFolder)
        val tmpVideo = File(context.cacheDir, "cd_live_v_${System.currentTimeMillis()}_$index.mp4")
        val tmpCover = File(context.cacheDir, "cd_live_c_${System.currentTimeMillis()}_$index.tmp")

        LogFile.d(TAG, "下载实况图 [$index] 视频与原图")
        val videoErr = downloadToFile(batch, videoUrl, tmpVideo, media.originalUrl)
        val coverErr = if (batch.cancelRequested.get()) "已取消"
        else downloadToFile(batch, coverUrl, tmpCover, media.originalUrl)
        val videoOk = videoErr == null
        val coverOk = coverErr == null

        LogFile.d(TAG, "实况图 [$index] 下载结果: videoOk=$videoOk, coverOk=$coverOk")

        // 如果都失败，直接返回
        if (!videoOk && !coverOk) {
            tmpVideo.delete(); tmpCover.delete()
            return DownloadResult(
                success = false, platform = media.platform,
                error = "实况图视频和原图都下载失败（$videoErr / $coverErr）"
            )
        }

        var savedPath: String? = null
        var savedName: String = ""
        var savedDuration: Long = 0L

        // 保存视频 (MP4)
        if (videoOk) {
            val videoFile = uniqueFile(dir, buildFileName(media, index, total, "mp4", suffix = "_live"))
            if (moveFile(tmpVideo, videoFile)) {
                scanFile(videoFile)
                LogFile.d(TAG, "实况图[$index] 视频已保存: ${videoFile.name}")
                if (savedPath == null) {
                    savedPath = videoFile.absolutePath
                    savedName = videoFile.name
                    savedDuration = probeMediaDuration(videoFile)
                }
            } else {
                tmpVideo.delete()
            }
        } else {
            tmpVideo.delete()
        }

        // 保存原图
        if (coverOk) {
            val ext = guessImageExt(coverUrl, tmpCover)
            val imgFile = uniqueFile(dir, buildFileName(media, index, total, ext, suffix = "_live"))
            if (moveFile(tmpCover, imgFile)) {
                scanFile(imgFile)
                LogFile.d(TAG, "实况图[$index] 图片已保存: ${imgFile.name}")
                if (savedPath == null) {
                    savedPath = imgFile.absolutePath
                    savedName = imgFile.name
                }
            } else {
                tmpCover.delete()
            }
        } else {
            tmpCover.delete()
        }

        return if (savedPath != null) {
            LogFile.d(TAG, "实况图[$index] 最终保存: $savedName (${if (videoOk && coverOk) "视频+图片" else if (videoOk) "仅视频" else "仅图片"})")
            DownloadResult(
                success = true,
                filePath = savedPath!!,
                fileName = savedName,
                platform = media.platform,
                isMotionPhoto = true,
                durationMs = savedDuration
            )
        } else {
            DownloadResult(success = false, platform = media.platform, error = "实况图保存失败")
        }
    }

    /**
     * 视频项：直接保存 MP4（不再尝试转成动图）
     */
    private fun downloadVideoItem(
        batch: DownloadBatchState,
        media: ParsedMedia,
        url: String,
        index: Int,
        total: Int
    ): DownloadResult {
        val dir = ensureDir(media.platform, batch.subFolder)
        val tmp = File(context.cacheDir, "cd_video_${System.currentTimeMillis()}_$index.tmp")

        val downloadError = downloadToFile(batch, url, tmp, media.originalUrl)
        if (downloadError != null) {
            tmp.delete()
            // googlevideo 403 = 直链绑定了代提服务的 IP（本地解析不可用时的回落路径）
            val hint = if (downloadError.contains("403") && url.contains("googlevideo.com"))
                "，YouTube 直链绑定了解析服务的 IP，无法跨 IP 下载" else ""
            return DownloadResult(
                success = false, platform = media.platform,
                error = "视频下载失败（$downloadError$hint）"
            )
        }

        // 抖音图文作品的 item.video 实际是背景音乐（纯音频），
        // 嗅探文件头识别，按音频保存，避免产出"黑屏视频"；
        // 解析器明确标记的音乐条目同样按音频扩展名保存
        val sniffed = sniffAudioExt(url, tmp)
        val (ext, suffix) = when {
            media.isMusic -> (sniffed ?: "m4a") to ""
            sniffed != null -> sniffed to "_bgm"
            else -> "mp4" to ""
        }
        val mp4File = uniqueFile(dir, buildFileName(media, index, total, ext, suffix = suffix))
        return if (moveFile(tmp, mp4File)) {
            scanFile(mp4File)
            DownloadResult(
                success = true,
                filePath = mp4File.absolutePath,
                fileName = mp4File.name,
                platform = media.platform,
                isMotionPhoto = false,
                // 探测真实时长：实况图/接口没给时长的视频项，下载后补上，下载栏角标才能显示
                durationMs = probeMediaDuration(mp4File)
            )
        } else {
            tmp.delete()
            return DownloadResult(success = false, platform = media.platform, error = "保存视频文件失败")
        }
    }

    /** 用 MediaMetadataRetriever 探测本地媒体真实时长（毫秒），失败返回 0 */
    private fun probeMediaDuration(file: File): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 判断下载内容是否纯音频：优先看 URL 扩展名，再看文件头。
     * MP4 容器不能只看 ftyp brand——B站等平台的 AAC 音频 brand 是 isom/dash 等通用值，
     * 需扫描容器里有无音频轨道（soun）且无视频轨道（vide）才能判定为纯音频。
     */
    private fun sniffAudioExt(url: String, file: File): String? {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (fromUrl in setOf("mp3", "m4a", "aac", "flac", "wav", "opus")) return fromUrl
        return try {
            val head = ByteArray(16)
            file.inputStream().use { it.read(head) }
            when {
                head.size >= 3 &&
                    String(head, 0, 3, Charsets.US_ASCII) == "ID3" -> "mp3"
                // MP3: MPEG 帧同步 0xFF Ex
                head.size >= 2 &&
                    head[0] == 0xFF.toByte() &&
                    (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
                head.size >= 12 &&
                    String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
                    if (isAudioOnlyMp4(file)) "m4a" else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** MP4 容器是否纯音频：扫描文件前段，含 soun（音频轨道 handler）且不含 vide（视频轨道） */
    private fun isAudioOnlyMp4(file: File): Boolean {
        return try {
            val scanLen = minOf(2L * 1024 * 1024, file.length()).toInt()
            if (scanLen <= 0) return false
            val buf = ByteArray(scanLen)
            java.io.DataInputStream(file.inputStream()).use { din ->
                din.readFully(buf)
            }
            val s = String(buf, Charsets.ISO_8859_1)
            s.contains("soun") && !s.contains("vide")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 图片项：按静态图片保存（保留原格式，webp 也保留）
     */
    private fun downloadImageItem(
        batch: DownloadBatchState,
        media: ParsedMedia,
        url: String,
        index: Int,
        total: Int
    ): DownloadResult {
        val dir = ensureDir(media.platform, batch.subFolder)
        val tmp = File(context.cacheDir, "cd_image_${System.currentTimeMillis()}_$index.tmp")

        val downloadError = downloadToFile(batch, url, tmp, media.originalUrl)
        if (downloadError != null) {
            tmp.delete()
            return DownloadResult(
                success = false, platform = media.platform,
                error = "图片下载失败（$downloadError）"
            )
        }

        val imgFile = uniqueFile(dir, buildFileName(media, index, total, guessImageExt(url, tmp)))
        return if (moveFile(tmp, imgFile)) {
            scanFile(imgFile)
            DownloadResult(
                success = true,
                filePath = imgFile.absolutePath,
                fileName = imgFile.name,
                platform = media.platform,
                isMotionPhoto = false
            )
        } else {
            tmp.delete()
            DownloadResult(success = false, platform = media.platform, error = "保存图片文件失败")
        }
    }

    // ==================== 工具方法 ====================

    /** 下载文件（分块写入，逐块上报进度，支持取消）。成功返回 null，失败返回原因 */
    private fun downloadToFile(
        batch: DownloadBatchState,
        url: String,
        target: File,
        referer: String = ""
    ): String? {
        val firstTry = doDownload(batch, url, target, referer)
        if (firstTry == null) return null
        // 用户取消：删除半截文件，绝不再重试（否则取消形同虚设、流量白烧）
        if (firstTry == "已取消") {
            if (target.exists()) target.delete()
            return firstTry
        }

        // 4xx 错误时，用 Referer 的根域重试一次（B 站等 CDN 严格校验 Referer 路径）
        if (referer.isNotBlank() && referer.contains("://")) {
            try {
                val uri = java.net.URI(referer)
                val rootReferer = "${uri.scheme}://${uri.host}"
                if (rootReferer != referer) {
                    LogFile.w(TAG, "首次下载失败（$firstTry），用根域 Referer 重试：$rootReferer")
                    if (target.exists()) target.delete()
                    val retry = doDownload(batch, url, target, rootReferer)
                    if (retry == null) return null
                }
            } catch (_: Exception) {}
        }
        LogFile.w(TAG, "下载最终失败：${firstTry} · ${com.clipdownloader.util.LogFile.briefUrl(url)}")
        return firstTry
    }

    /** 核心下载：成功返回 null，失败返回原因 */
    private fun doDownload(
        batch: DownloadBatchState,
        url: String,
        target: File,
        referer: String
    ): String? {
        return try {
            // B站 upos/bilivideo CDN 会拒绝移动端 UA（403），必须用桌面 Chrome UA；
            // googlevideo 直链用提取客户端同款 UA，与 InnerTube 会话保持一致
            val isBiliCdn = url.contains("bilivideo.com") || referer.contains("bilibili.com")
            val isGoogleVideo = url.contains("googlevideo.com")
            val ua = when {
                isBiliCdn -> DESKTOP_USER_AGENT
                isGoogleVideo -> YouTubeInnerTubeParser.CLIENT_UA
                else -> USER_AGENT
            }
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
            if (referer.isNotBlank()) {
                builder.header("Referer", referer)
                // B站 CDN 有时需要 Origin 头
                if (referer.contains("bilibili.com")) {
                    builder.header("Origin", referer)
                }
            }

            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    LogFile.w(TAG, "下载失败 HTTP ${response.code} · ${com.clipdownloader.util.LogFile.briefUrl(url)}")
                    return "HTTP ${response.code}"
                }
                val body = response.body ?: return "响应无内容"
            val totalBytes = body.contentLength()
            target.parentFile?.mkdirs()
            // 实际落盘字节数提到外层：断流时 input.read 返回 -1 会正常跳出循环，
            // 不拿 downloaded==totalBytes 校验的话半截文件会被判成下载成功
            var downloadedBytes = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastNotifyAt = 0L
                    while (true) {
                        // 取消检查点
                        if (batch.cancelRequested.get()) return "已取消"
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        // 节流：进度回调最多 ~5 次/秒，避免主线程被刷新消息淹没
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastNotifyAt >= 200) {
                            lastNotifyAt = now
                            batch.updateFileProgress(downloadedBytes, totalBytes)
                            notifyBatchChanged()
                        }
                    }
                    batch.updateFileProgress(downloadedBytes, totalBytes)
                }
            }
            // 服务器提前断流：Content-Length 已知但落盘字节不足 → 半截文件按失败处理
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                LogFile.w(TAG, "下载不完整：$downloadedBytes/$totalBytes · ${target.name}")
                if (target.exists()) target.delete()
                return "下载不完整（$downloadedBytes/$totalBytes）"
            }
        }
            val success = target.exists() && target.length() > 0
            if (success) {
                LogFile.d(TAG, "下载成功：${target.name} · ${target.length() / 1024}KB")
            } else {
                LogFile.w(TAG, "下载失败：响应为空 · ${target.name}")
            }
            if (success) null else "空响应"
        } catch (e: Exception) {
            LogFile.e(TAG, "下载异常: ${com.clipdownloader.util.LogFile.briefUrl(url)}", e)
            "网络异常（${e.message ?: "未知"}）"
        }
    }

    private fun ensureDir(platform: Platform, subFolder: String? = null): File {
        // "按平台独立文件夹保存"关闭时：全部直接存根目录
        if (!prefs.isPlatformFoldersEnabled()) {
            val root = File(prefs.getSavePath())
            if (!root.exists()) root.mkdirs()
            return root
        }
        val base = File(prefs.getSavePath(), platform.folderName)
        val dir = if (!subFolder.isNullOrBlank()) File(base, sanitize(subFolder)) else base
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ==================== 本地已有识别（防重复下载） ====================

    /**
     * 扫描保存目录（含平台/博主子文件夹）里文件名内置的作品标识（_m<hash>_），
     * 返回 标识 → 已有文件路径列表。这是实际文件的扫描结果，与历史记录无关——
     * 记录被删后文件还在就不会重复下载，且能把已有文件带回记录（缩略图/打开）。
     */
    fun existingMarkerFiles(): Map<String, List<String>> {
        val regex = Regex("_m([0-9a-f]{10})_")
        val found = linkedMapOf<String, MutableList<String>>()
        try {
            val root = File(prefs.getSavePath())
            if (root.exists()) {
                root.walkTopDown().maxDepth(3).filter { it.isFile }.forEach { f ->
                    regex.find(f.name)?.let { m ->
                        found.getOrPut(m.groupValues[1]) { mutableListOf() }.add(f.absolutePath)
                    }
                }
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "扫描本地已下载标识失败: ${e.message}")
        }
        return found
    }

    /** 已下载作品的标识集合（主页解析前过滤用） */
    fun existingMarkers(): Set<String> = existingMarkerFiles().keys

    /**
     * 跳过回填时从同标识的已有文件里挑本条目对应的那个，挑不到返回 null（条目照常下载）。
     * 实况图拆成的「图片+视频」两个条目标识相同，不分类型会让图片条目
     * 拿到视频文件（缩略图显示视频帧、大小/时长角标全带错）；
     * 本条目类型的文件根本不在本地（上次该条目下载失败）时必须返回 null 去补下载；
     * 图集多张图片同标识时再按文件名里的原始序号 _N_m<hash>_ 配对，否则每个格子都显示同一张。
     */
    private fun pickExistingFile(
        existing: List<String>,
        media: ParsedMedia,
        marker: String,
        index: Int,
        total: Int
    ): String? {
        val exts = when {
            media.isMusic -> setOf("m4a", "mp3", "aac", "wav", "flac", "opus")
            media.videoUrl != null -> setOf("mp4", "mov", "m4v", "webm", "mkv")
            else -> setOf("jpg", "jpeg", "png", "webp", "gif", "heic")
        }
        val pool = existing
            .filter { it.substringAfterLast('.', "").lowercase() in exts }
            // 另存重下后本地可能有多份同类型文件，回填一律用时间最新的那份做下载记录
            .sortedByDescending { runCatching { File(it).lastModified() }.getOrDefault(0L) }
        if (pool.isEmpty()) return null
        if (pool.size > 1 && total > 1) {
            val idxRegex = Regex("_(\\d+)_m${marker}_")
            val byIndex = pool.filter { f ->
                idxRegex.find(File(f).name)?.groupValues?.get(1)?.toIntOrNull() == index + 1
            }
            if (byIndex.isNotEmpty()) return byIndex.first()
        }
        return pool.first()
    }

    /** 覆盖模式：删除同作品（同标识）的旧文件，keepPaths 里的是本次刚保存的新文件；
     *  指向被删旧文件的下载记录一并移除（覆盖旧文件=旧记录自动删除） */
    private fun deleteOldMarkerFiles(marker: String, keepPaths: List<String>) {
        val regex = Regex("_m${marker}_")
        val keep = keepPaths.toHashSet()
        val deleted = mutableListOf<String>()
        try {
            File(prefs.getSavePath()).walkTopDown().maxDepth(3)
                .filter { it.isFile && it.absolutePath !in keep && regex.containsMatchIn(it.name) }
                .forEach { f ->
                    if (f.delete()) {
                        deleted.add(f.absolutePath)
                        LogFile.d(TAG, "覆盖旧文件：${f.name}")
                    }
                }
        } catch (e: Exception) {
            LogFile.w(TAG, "覆盖旧文件失败: ${e.message}")
        }
        if (deleted.isNotEmpty()) {
            val removed = recordStore.getRecords().filter { r ->
                deleted.any { d -> r.filePath == d || r.filePaths?.contains(d) == true }
            }
            removed.forEach { recordStore.remove(it.batchId) }
            if (removed.isNotEmpty()) {
                LogFile.d(TAG, "覆盖后删除旧记录：${removed.size} 条")
                notifyBatchChanged()
            }
        }
    }

    /**
     * 作品唯一键：mediaId（aweme_id/photoId/bvid）优先；没有则从链接提取平台作品 ID
     * （BV 号/抖音/快手数字 id——本地与云通道对同一作品都能算出同一个键）；
     * 短链提取不出再退回分享链接+标题。
     */
    private fun workKeyOf(media: ParsedMedia): String {
        val id = media.mediaId.trim()
        if (id.isNotBlank()) return "${media.platform.name}|id:$id"
        val link = media.originalUrl.trim().takeIf { isRealPageUrl(it) }
            ?: PlatformParser.extractUrl(media.rawShareText)
            ?: media.rawShareText.trim()
        val pid = platformWorkId(link)
        if (pid != null) return "${media.platform.name}|pid:$pid"
        return "${media.platform.name}|link:${link.lowercase()}|${media.title.trim()}"
    }

    /** 从作品页链接提取平台作品 ID（B站 BV 号 / 抖音·TikTok 视频/图集 id / 快手 photo id）。
     *  抖音/快手/数字平台只认纯数字 ID（长链里的 /video/、/note/、modal_id、/short-video/），
     *  短链尾巴（v.douyin.com/xxxx）不是作品 ID，不能当标识用 */
    private fun platformWorkId(link: String): String? {
        return Regex("BV[0-9A-Za-z]{10}").find(link)?.value?.lowercase()
            ?: Regex("/video/(\\d{6,})").find(link)?.groupValues?.get(1)
            ?: Regex("/note/(\\d{6,})").find(link)?.groupValues?.get(1)
            ?: Regex("modal_id=(\\d{6,})").find(link)?.groupValues?.get(1)
            ?: Regex("/short-video/(\\d{6,})").find(link)?.groupValues?.get(1)
    }

    /** 单作品展示用 ID：mediaId 优先，否则从作品链接提取（BV 号/数字 id）；
     *  抖音/B站/快手短链提取不到时展开重定向再提取（云端解析不回传 mediaId 的兜底） */
    suspend fun workIdOf(media: ParsedMedia): String = withContext(Dispatchers.IO) {
        media.mediaId.trim().takeIf { it.isNotBlank() }?.let { return@withContext it }
        var link = media.originalUrl.trim().takeIf { isRealPageUrl(it) }
            ?: PlatformParser.extractUrl(media.rawShareText)
            ?: return@withContext ""
        platformWorkId(link)?.let { return@withContext it }
        if (isShortLink(link)) {
            repeat(3) {
                link = expandShortLink(link) ?: return@repeat
                platformWorkId(link)?.let { return@withContext it }
            }
        }
        ""
    }

    private val shortLinkHosts = listOf(
        "v.douyin.com", "iesdouyin.com", "b23.tv", "v.kuaishou.com",
        "vm.tiktok.com", "vt.tiktok.com"
    )

    private fun isShortLink(url: String): Boolean = try {
        val host = java.net.URI(url).host.orEmpty()
        shortLinkHosts.any { host == it || host.endsWith(".$it") }
    } catch (_: Exception) {
        false
    }

    /** 跟一次重定向拿 Location（短链展开），失败返回 null */
    private fun expandShortLink(url: String): String? = try {
        val noRedirect = okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        noRedirect.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()
        ).execute().use { resp -> resp.header("Location") }
    } catch (e: Exception) {
        LogFile.w(TAG, "短链展开失败: ${e.message}")
        null
    }

    /** 主页 ID：B站 uid / 抖音 sec_uid / 快手 profile id，从主页链接提取，提取不到为空 */
    private fun profileIdOf(platform: Platform, shareText: String): String {
        val link = PlatformParser.extractUrl(shareText) ?: shareText
        return when (platform) {
            Platform.BILIBILI -> Regex("(\\d{4,})").find(link)?.groupValues?.get(1) ?: ""
            Platform.DOUYIN -> Regex("/user/([0-9A-Za-z_-]+)").find(link)?.groupValues?.get(1) ?: ""
            Platform.KUAISHOU -> Regex("/profile/([0-9A-Za-z_-]+)").find(link)?.groupValues?.get(1) ?: ""
            else -> ""
        }
    }

    /** 占位链接判定：tikwm 等解析器把 originalUrl 写成纯域名主页，不能当作品标识 */
    private fun isRealPageUrl(url: String): Boolean = try {
        url.startsWith("http") && ((java.net.URI(url).path?.length ?: 0) > 1)
    } catch (_: Exception) {
        false
    }

    /** 作品的本地去重标识（写入文件名，主页解析前过滤已存在作品也用它） */
    fun workMarkerOf(media: ParsedMedia): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(workKeyOf(media).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(10)

    private fun buildFileName(
        media: ParsedMedia,
        index: Int,
        total: Int,
        ext: String,
        suffix: String = ""
    ): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val base = sanitize(media.title).take(40).ifBlank { media.platform.folderName }
        val idx = if (total > 1) "_${index + 1}" else ""
        // 文件名内置作品标识 _m<hash>_（同作品从任何入口下载标识一致），
        // 时间戳每次不同没关系，本地已有识别只看标识
        return "$base$idx" + "_m${workMarkerOf(media)}" + "_$ts$suffix.$ext"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.', '_')

    private fun uniqueFile(dir: File, fileName: String): File {
        var file = File(dir, fileName)
        if (!file.exists()) return file
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (file.exists() && i < 1000) {
            file = File(dir, "$stem($i)$ext")
            i++
        }
        return file
    }

    private fun moveFile(src: File, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            if (src.renameTo(dest)) return true
            src.inputStream().use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output, 64 * 1024) }
            }
            src.delete()
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            LogFile.e(TAG, "移动文件失败", e)
            false
        }
    }

    private fun guessImageExt(url: String, file: File): String {
        // 直接根据 URL/文件头判断，不再探测 JPEG 动图
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (fromUrl in setOf("jpg", "jpeg", "png", "gif", "webp", "heic")) return fromUrl
        return sniffImageExt(file) ?: "jpg"
    }

    /** 根据文件头判断图片格式 */
    private fun sniffImageExt(file: File): String? {
        return try {
            val head = ByteArray(12)
            file.inputStream().use { it.read(head) }
            when {
                head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "png"
                head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
                    head[2] == 'F'.code.toByte() -> "gif"
                head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
                    head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() -> "webp"
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun scanFile(file: File) {
        try {
            // 显式指定 MIME：让相册立刻识别文件类型，
            // .jpg 的 Motion Photo 需要 image/jpeg 才会被归到「可播放动图」
            val mime = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "m4a" -> "audio/mp4"
                else -> null
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                if (mime != null) arrayOf(mime) else null,
                null
            )
        } catch (e: Exception) {
            LogFile.w(TAG, "媒体库刷新失败: ${e.message}")
        }
    }

    private fun isWifiConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 通知 ====================

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notifyProgress(id: Int, title: String, text: String?) {
        try {
            val n = NotificationCompat.Builder(context, App.CHANNEL_ID_DOWNLOAD)
                .setContentTitle(title)
                .setContentText(text ?: "")
                .setSmallIcon(R.drawable.ic_notification)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            notificationManager().notify(id, n)
        } catch (e: Exception) {
            LogFile.w(TAG, "进度通知失败: ${e.message}")
        }
    }

    private fun cancelNotification(id: Int) {
        try {
            notificationManager().cancel(id)
        } catch (_: Exception) {
        }
    }

    private fun notifyResult(title: String, text: String) {
        try {
            val intent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, DownloadResultActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, App.CHANNEL_ID_RESULT)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            notificationManager().notify(notificationIdSeq.incrementAndGet(), n)
        } catch (e: Exception) {
            LogFile.w(TAG, "结果通知失败: ${e.message}")
        }
    }
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
