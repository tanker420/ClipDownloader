package com.clipdownloader.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.clipdownloader.model.DownloadRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 下载历史记录存储（JSON 文件持久化，进程内单例）
 *
 * 写入采用异步线程，读取直接从内存缓存返回，保证「下载」页即时刷新。
 */
class DownloadRecordStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    private val file: File by lazy {
        File(appContext.filesDir, "download_records.json")
    }

    private val records = CopyOnWriteArrayList<DownloadRecord>()
    private val listeners = CopyOnWriteArrayList<(List<DownloadRecord>) -> Unit>()
    // 所有持久化写入必须按调用顺序串行执行。此前每次 add/remove 都启动独立线程，
    // 新记录写入和旧记录删除可能乱序，导致删除后的旧记录又被旧快照写回磁盘。
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DownloadRecordStore-persist").apply { isDaemon = true }
    }
    @Volatile private var loaded = false

    /**
     * 读取失败标志：JSON 损坏/读取异常时置 true，persist() 跳过写盘。
     * 否则一次瞬时故障后，内存里的空快照会在下次 add/remove 时覆盖原文件，
     * 全部历史记录永久丢失
     */
    @Volatile private var loadFailed = false

    companion object {
        private const val TAG = "DownloadRecordStore"
        @Volatile private var instance: DownloadRecordStore? = null

        fun get(context: Context): DownloadRecordStore =
            instance ?: synchronized(this) {
                instance ?: DownloadRecordStore(context).also { instance = it; it.load() }
            }
    }

    private fun load() {
        try {
            if (!file.exists()) {
                loaded = true
                return
            }
            val json = file.readText()
            val type = object : TypeToken<List<DownloadRecord>>() {}.type
            val list: List<DownloadRecord> = gson.fromJson(json, type) ?: emptyList()
            // 旧版本记录没有 coverUrls 字段，Gson 会填 null，这里兜底避免列表 NPE 闪退
            @Suppress("UNNECESSARY_SAFE_CALL")
            val fixed = list.map { r ->
                r.copy(
                    coverUrls = r.coverUrls ?: emptyList(),
                    kind = r.kind ?: "",
                    coverUrl = r.coverUrl ?: "",
                    kinds = r.kinds ?: emptyList(),
                    durations = r.durations ?: emptyList(),
                    filePaths = r.filePaths ?: listOfNotNull(
                        r.filePath?.takeIf { it.isNotBlank() }
                    ),
                    itemSkipped = r.itemSkipped ?: emptyList(),
                    itemErrors = r.itemErrors ?: emptyList(),
                    avatarUrl = r.avatarUrl ?: "",
                )
            }
            records.addAll(fixed.sortedByDescending { it.timestamp })
        } catch (e: Exception) {
            Log.w(TAG, "读取下载记录失败", e)
            // 原文件备份保留（不覆盖不删除），persist 见 loadFailed 跳过写盘
            loadFailed = true
            runCatching {
                val backup = File(file.parentFile, file.name + ".bad")
                file.copyTo(backup, overwrite = true)
            }
        } finally {
            loaded = true
        }
    }

    fun add(record: DownloadRecord) {
        records.add(0, record)
        // 最多保留 100 条
        while (records.size > 100) records.removeAt(records.size - 1)
        persist()
        notifyChanged()
    }

    fun remove(batchId: Long) {
        records.removeAll { it.batchId == batchId }
        persist()
        notifyChanged()
    }

    fun clear() {
        records.clear()
        persist()
        notifyChanged()
    }

    fun getRecords(): List<DownloadRecord> = records.toList()

    fun getByBatchId(batchId: Long): DownloadRecord? =
        records.firstOrNull { it.batchId == batchId }

    fun addListener(listener: (List<DownloadRecord>) -> Unit) {
        listeners.add(listener)
        listener(records.toList())
    }

    fun removeListener(listener: (List<DownloadRecord>) -> Unit) {
        listeners.remove(listener)
    }

    private fun persist() {
        // 读取曾失败时禁止写盘：内存快照不全，覆盖会永久丢失原记录
        if (loadFailed) {
            Log.w(TAG, "记录文件读取曾失败，跳过持久化以保护原文件")
            return
        }
        persistExecutor.execute {
            try {
                synchronized(this) {
                    file.writeText(gson.toJson(records.toList()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "保存下载记录失败", e)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notifyChanged() {
        val snapshot = records.toList()
        // 写入多在 IO 线程，统一切回主线程分发，保证列表刷新时机正确
        mainHandler.post { listeners.forEach { it(snapshot) } }
    }
}
