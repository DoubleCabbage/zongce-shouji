package cn.activityscore.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object EvidenceImages {
    private val decoderMonitor = Any()
    private val supported = setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/bmp", "image/x-ms-bmp", "image/heif", "image/heic", "image/avif")

    fun validate(file: File, limit: Long = EvidencePolicy.EXISTING_MAX_BYTES): String = guarded {
        val mime = header(file, limit)
        EvidencePolicy.container(file, mime)
        withBitmap(file) { } // a full decode, not only bounds or a sampled thumbnail
        mime
    }

    private fun header(file: File, limit: Long): String {
        if (!file.isFile) throw EvidenceInvalid(EvidenceError.MISSING, "证明文件不存在")
        if (!file.canRead()) throw EvidenceInvalid(EvidenceError.UNREADABLE, "证明文件无法读取")
        if (file.length() == 0L) throw EvidenceInvalid(EvidenceError.EMPTY, "图片文件为空")
        if (file.length() > limit) throw EvidenceInvalid(EvidenceError.TOO_LARGE, "图片超过 ${limit / 1024 / 1024} MiB 限制")
        file.inputStream().use { if (it.read() < 0) throw EvidenceInvalid(EvidenceError.EMPTY, "图片文件为空") }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        EvidencePolicy.dimensions(bounds.outWidth, bounds.outHeight)
        return bounds.outMimeType?.takeIf { it in supported }
            ?: throw EvidenceInvalid(EvidenceError.UNSUPPORTED, "图片格式不受当前处理链路支持，请重新选择 JPEG、PNG 或设备支持的图片")
    }

    /** Full dimensions also used for lossless Excel conversion. Private source bytes never change. */
    fun <T> withBitmap(file: File, action: (Bitmap) -> T): T = synchronized(decoderMonitor) {
        guarded {
            val bitmap = decode(file)
            try { action(bitmap) } finally { bitmap.recycle() }
        }
    }

    private fun decode(file: File): Bitmap = guarded {
        header(file, EvidencePolicy.EXISTING_MAX_BYTES)
        if (Build.VERSION.SDK_INT >= 28) {
            PlatformDecoder28.decode(file)
        } else BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw EvidenceInvalid(EvidenceError.DECODE, "图片无法完整解码")
    }

    /** Keep API-28 exception types out of the API-26 common exception table. */
    @android.annotation.TargetApi(28)
    private object PlatformDecoder28 {
        fun decode(file: File): Bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                EvidencePolicy.dimensions(info.size.width, info.size.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
        } catch (error: ImageDecoder.DecodeException) {
            throw EvidenceInvalid(EvidenceError.DECODE, "图片无法完整解码（损坏、截断或当前设备不支持）", error)
        }
    }

    fun stage(context: Context, uri: Uri): File {
        val directory = File(context.filesDir, "evidence-staging").also { check(it.mkdirs() || it.isDirectory) { "无法准备证明暂存空间" } }
        val file = File(directory, "${UUID.randomUUID()}.part")
        try {
            guarded {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw EvidenceInvalid(EvidenceError.UNREADABLE, "无法打开选中的图片")
                input.use { source -> FileOutputStream(file).use { target -> EvidencePolicy.copy(source, target); target.fd.sync() } }
                validate(file, EvidencePolicy.NEW_MAX_BYTES)
            }
            return file
        } catch (error: Throwable) {
            if (file.exists() && !file.delete()) android.util.Log.w("EvidenceImages", "Staging cleanup deferred")
            throw error
        }
    }

    fun issue(location: EvidenceLocation, file: File): EvidenceIssue? = try { validate(file); null }
        catch (error: EvidenceInvalid) { EvidenceIssue(location, error.type, error.message.orEmpty()) }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (error: EvidenceInvalid) { throw error }
        catch (error: OutOfMemoryError) { throw EvidenceInvalid(EvidenceError.MEMORY, "当前可用内存不足以处理图片，请选择较小图片", error) }
        catch (error: java.io.IOException) { throw EvidenceInvalid(EvidenceError.UNREADABLE, "图片读取或写入失败，请检查权限和可用空间", error) }
        catch (error: SecurityException) { throw EvidenceInvalid(EvidenceError.UNREADABLE, "没有读取选中图片的权限", error) }
        catch (error: Exception) { throw EvidenceInvalid(EvidenceError.DECODE, "图片无法解码，请重新选择有效图片", error) }
}
