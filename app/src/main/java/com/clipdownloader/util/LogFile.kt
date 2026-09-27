package com.clipdownloader.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志文件记录器：把关键日志写入文件，便于排查问题。
 *
 * 写入在单一后台线程执行：此前同步写 + 写满后每次都重读重写整个文件，
 * 在解析器大量输出时会把主线程拖死，导致下载页刷新卡顿、不实时。
 */
object LogFile {
    private const val MAX_FILE_BYTES = 512 * 1024
    /** 单条日志上限，防止解析器把整段响应 JSON 灌进日志 */
    private const val MAX_MSG_CHARS = 2000

    @Volatile private var file: File? = null
    private val lock = Any()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "clip-log").apply { isDaemon = true }
    }

    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file == null) {
                try {
                    val dir = File(context.filesDir, "logs")
                    dir.mkdirs()
                    file = File(dir, "app_log.txt")
                } catch (_: Exception) {
                }
            }
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    /** 日志用 URL 简写：scheme://host/path，查询串只保留到指定长度（失败排查足够且不刷屏） */
    fun briefUrl(url: String, maxQuery: Int = 60): String {
        return try {
            val cut = url.substringBefore('?')
            val query = url.substringAfter('?', "")
            if (query.isBlank()) cut.take(90)
            else buildString {
                append(cut.take(90))
                append('?')
                append(query.take(maxQuery))
                if (query.length > maxQuery) append("…")
            }
        } catch (_: Exception) {
            url.take(90)
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        val extra = throwable?.let { "\n     ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        append("E", tag, msg + extra)
    }

    private fun append(level: String, tag: String, msg: String) {
        val f = file ?: return
        val body = if (msg.length > MAX_MSG_CHARS) msg.take(MAX_MSG_CHARS) + "…(${msg.length}字符已截断)" else msg
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "$ts $level [$tag] $body\n"
        executor.execute {
            try {
                synchronized(lock) {
                    f.appendText(line)
                    if (f.length() > MAX_FILE_BYTES) {
                        // 截断：保留后半部分
                        val lines = f.readLines()
                        val keep = lines.takeLast(3000).joinToString("\n")
                        f.writeText("$ts I [ClipLog] 日志已截断\n$keep\n")
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 清空日志文件（写线程上执行，保证与写入串行） */
    fun clear() {
        val f = file ?: return
        executor.execute {
            try {
                synchronized(lock) {
                    val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                    f.writeText("$ts I [ClipLog] 日志已清除\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 读取日志文本（供导出），在写线程上执行保证与写入串行 */
    fun getLogText(maxLines: Int = 3000): String {
        val f = file ?: return "日志未初始化（请先在设置页触发一次操作）"
        return try {
            val future = executor.submit<String> {
                synchronized(lock) {
                    val lines = f.readLines()
                    val content = lines.takeLast(maxLines).joinToString("\n")
                    if (content.isBlank()) "日志为空" else content
                }
            }
            future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }
}
