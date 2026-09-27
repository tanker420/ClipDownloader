package com.clipdownloader.download

import com.clipdownloader.util.LogFile
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 动态照片（实况图 / Motion Photo）打包工具。
 *
 * 把「封面 JPEG + 内嵌视频 mp4」合成单个可在相册里播放的 Motion Photo 文件：
 * 标准做法（Google Camera / 小米 / 大部分国产机相册通用）是在 JPEG 的 APP1 段写入
 * 含 Camera:MicroVideo / Camera:MicroVideoOffset 的 XMP 元数据，并在 JPEG 文件尾部拼接 mp4 视频字节。
 * MicroVideoOffset 指向视频在文件中的起始偏移，相册据此把它识别为会动的照片。
 */
object MotionPhotoUtils {

    private const val TAG = "MotionPhotoUtils"

    /** XMP APP1 命名空间标识（结尾必须带 \0） */
    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/"

    /** 封面是不是 JPEG（只有 JPEG 才能拼成 Motion Photo，WebP/其他格式走兜底） */
    fun isJpegFile(file: File): Boolean {
        return try {
            val head = ByteArray(3)
            file.inputStream().use { it.read(head) }
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 打包成单个 Motion Photo（输出文件扩展名用 .jpg）。
     * 成功返回 true，失败（IO 异常 / 封面非 JPEG / 视频为空）返回 false，调用方应退回双文件保存。
     *
     * 全程流式读写：offset 计算只需要文件长度（cover.size / video.size），
     * 不把整个视频读进内存（旧实现峰值内存约 3 倍视频体积，低端机 OOM 风险）。
     * 先写临时文件再 rename，避免写一半崩溃留下半截成品。
     */
    fun pack(coverJpeg: File, videoMp4: File, outJpg: File): Boolean {
        val tmp = File(outJpg.parentFile, outJpg.name + ".tmp")
        return try {
            if (!isJpegFile(coverJpeg) || videoMp4.length() <= 0L) {
                LogFile.w(TAG, "打包中止：封面非 JPEG 或视频为空")
                return false
            }
            val coverSize = coverJpeg.length()
            val videoSize = videoMp4.length()
            if (coverSize > Int.MAX_VALUE || videoSize > Int.MAX_VALUE) {
                LogFile.w(TAG, "打包中止：文件过大 cover=$coverSize video=$videoSize")
                return false
            }

            // offset = 视频在成品文件中的起始偏移 = SOI(2) + APP1(33 + xmpLen) + 封面剩余(cover.size-2)
            //          = cover.size + 33 + xmpLen
            // 因为 offset 会出现在 XMP 文本里，xmpLen 随 offset 位数变化，故迭代求稳（通常 1~2 轮收敛）。
            val c = coverSize.toInt() + 33
            var offset = c + 256
            var xmp = ""
            for (i in 0..6) {
                xmp = buildXmpPacket(offset, videoSize.toInt())
                val xmpLen = xmp.toByteArray(Charsets.UTF_8).size
                val newOffset = c + xmpLen
                if (newOffset == offset) break
                offset = newOffset
            }

            val app1 = buildApp1(xmp)
            val buffer = ByteArray(64 * 1024)
            java.io.FileOutputStream(tmp).use { out ->
                // SOI
                coverJpeg.inputStream().use { input ->
                    val b1 = input.read(); val b2 = input.read()
                    if (b1 < 0 || b2 < 0) throw java.io.IOException("封面文件过短")
                    out.write(b1); out.write(b2)
                    // APP1 + XMP
                    out.write(app1)
                    // 原 JPEG 其余部分（含 EOI）
                    var n: Int
                    while (input.read(buffer).also { n = it } >= 0) out.write(buffer, 0, n)
                }
                // 尾部拼接 mp4
                videoMp4.inputStream().use { input ->
                    var n: Int
                    while (input.read(buffer).also { n = it } >= 0) out.write(buffer, 0, n)
                }
            }
            if (outJpg.exists()) outJpg.delete()
            if (!tmp.renameTo(outJpg)) throw java.io.IOException("rename 失败：${tmp.name} -> ${outJpg.name}")

            LogFile.d(TAG, "Motion Photo 打包成功: ${outJpg.name}, offset=$offset, size=${outJpg.length()}")
            true
        } catch (e: Exception) {
            LogFile.e(TAG, "Motion Photo 打包异常", e)
            tmp.delete()
            false
        }
    }

    private fun buildXmpPacket(offset: Int, videoLen: Int): String {
        val s = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
    Camera:MicroVideo="1"
    Camera:MicroVideoVersion="1"
    Camera:MicroVideoOffset="$offset"
    Camera:MicroVideoPresentationTimestampUs="0"/>
 </rdf:RDF>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
        return if (s.length % 2 == 1) s + " " else s
    }

    private fun buildApp1(xmp: String): ByteArray {
        val data = (XMP_NS + "\u0000").toByteArray(Charsets.UTF_8) + xmp.toByteArray(Charsets.UTF_8)
        val length = data.size + 2 // JPEG 段长度字段 = 长度字段本身(2) + 数据
        val baos = ByteArrayOutputStream()
        baos.write(0xFF)
        baos.write(0xE1) // APP1
        baos.write((length shr 8) and 0xFF)
        baos.write(length and 0xFF)
        baos.write(data)
        return baos.toByteArray()
    }
}
