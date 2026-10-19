package cn.activityscore.app.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

enum class EvidenceError { MISSING, UNREADABLE, EMPTY, TOO_LARGE, DIMENSIONS, UNSUPPORTED, CORRUPT, DECODE, MEMORY }

class EvidenceInvalid(val type: EvidenceError, reason: String, cause: Throwable? = null) : IllegalArgumentException(reason, cause)

data class EvidenceLocation(val itemId: Long, val title: String, val participationId: Long,
    val date: String, val photo: EvidencePhoto) {
    val label: String get() = "$title（活动 $itemId） / $date（参与 $participationId） / 证明 ${photo.id}"
}
data class EvidenceIssue(val location: EvidenceLocation, val type: EvidenceError, val reason: String) {
    val description: String get() = "${location.label}：$reason [$type]"
}
class EvidenceProblems(val issues: List<EvidenceIssue>) : IllegalStateException(
    "证明检查未通过，请打开“证明检查与修复”：\n" + issues.joinToString("\n") { it.description })

/** No Android dependency: byte and resource bounds also used by the device decoder. */
object EvidencePolicy {
    const val NEW_MAX_BYTES = 30L * 1024 * 1024 // existing activity-package limit
    const val EXISTING_MAX_BYTES = 32L * 1024 * 1024 // preserve P1-08 v1 limit
    const val MAX_SIDE = 16384
    const val MAX_PIXELS = 24_000_000L // full ARGB decode <= 96 MB; one bitmap at a time

    fun dimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) throw EvidenceInvalid(EvidenceError.UNSUPPORTED, "内容不是当前设备支持的图片")
        if (width > MAX_SIDE || height > MAX_SIDE || width.toLong() * height > MAX_PIXELS)
            throw EvidenceInvalid(EvidenceError.DIMENSIONS, "图片尺寸超过边长 $MAX_SIDE 或 2400 万像素限制（${width}×$height）")
    }

    fun copy(input: InputStream, output: OutputStream, limit: Long = NEW_MAX_BYTES) {
        val buffer = ByteArray(8192); var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw EvidenceInvalid(EvidenceError.TOO_LARGE, "图片超过 ${limit / 1024 / 1024} MiB 限制")
            output.write(buffer, 0, n)
        }
        if (total == 0L) throw EvidenceInvalid(EvidenceError.EMPTY, "图片文件为空")
    }

    /** Decoders can tolerate truncation. Check PNG chunk CRC/end and JPEG marker boundaries too. */
    fun container(file: File, mime: String) {
        fun corrupt(): Nothing = throw EvidenceInvalid(EvidenceError.CORRUPT, "图片内容损坏或被截断")
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            when (mime) {
                "image/png" -> {
                    if (length < 8 || input.readLong() != -8552249625308161526L) corrupt()
                    var end = false; var header = false; var image = false
                    val buffer = ByteArray(8192)
                    while (input.filePointer < length && !end) {
                        if (length - input.filePointer < 12) corrupt()
                        val size = input.readInt().toLong() and 0xffffffffL
                        val type = ByteArray(4); input.readFully(type)
                        val name = String(type, Charsets.US_ASCII)
                        if (!header && (name != "IHDR" || size != 13L)) corrupt()
                        if (size > length - input.filePointer - 4) corrupt()
                        val crc = CRC32(); crc.update(type)
                        var left = size
                        while (left > 0) { val n = minOf(left, buffer.size.toLong()).toInt(); input.readFully(buffer, 0, n); crc.update(buffer, 0, n); left -= n }
                        if (crc.value != (input.readInt().toLong() and 0xffffffffL)) corrupt()
                        header = true; if (name == "IDAT") image = true
                        if (name == "IEND") { if (size != 0L || !image) corrupt(); end = true }
                    }
                    if (!end) corrupt()
                }
                "image/jpeg", "image/jpg" -> {
                    java.io.DataInputStream(file.inputStream().buffered()).use { jpeg ->
                    if (length < 4 || jpeg.readUnsignedShort() != 0xffd8) corrupt()
                    var end = false; var scan = false
                    try { while (!end) {
                        if (jpeg.readUnsignedByte() != 0xff) { if (!scan) corrupt(); continue }
                        var marker: Int
                        do { marker = jpeg.readUnsignedByte() } while (marker == 0xff)
                        when {
                            marker == 0xd9 -> { if (!scan) corrupt(); end = true }
                            marker == 0 && scan || marker in 0xd0..0xd7 && scan -> Unit
                            marker == 0x01 -> Unit
                            else -> {
                                val size = jpeg.readUnsignedShort()
                                if (size < 2) corrupt()
                                repeat(size - 2) { jpeg.readUnsignedByte() }
                                if (marker == 0xda) scan = true
                            }
                        }
                    } } catch (error: java.io.EOFException) { corrupt() }
                    }
                }
                "image/webp" -> {
                    if (length < 12 || input.readInt() != 0x52494646) corrupt()
                    val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                    if (size + 8 != length || input.readInt() != 0x57454250) corrupt()
                }
                "image/gif" -> { input.seek(length - 1); if (input.readUnsignedByte() != 0x3b) corrupt() }
                "image/bmp", "image/x-ms-bmp" -> {
                    if (length < 14 || input.readUnsignedShort() != 0x424d) corrupt()
                    val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                    if (size > length || size < 14) corrupt()
                }
            }
        }
    }
}
