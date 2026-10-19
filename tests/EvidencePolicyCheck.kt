package cn.activityscore.app.tests

import cn.activityscore.app.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64

fun main() {
    fun fails(type: EvidenceError, action: () -> Unit) { check((runCatching(action).exceptionOrNull() as? EvidenceInvalid)?.type == type) }
    fails(EvidenceError.EMPTY) { EvidencePolicy.copy(byteArrayOf().inputStream(), ByteArrayOutputStream()) }
    fails(EvidenceError.TOO_LARGE) { EvidencePolicy.copy(ByteArray(20).inputStream(), ByteArrayOutputStream(), 19) }
    val target = ByteArrayOutputStream(); val original = ByteArray(20000) { (it % 251).toByte() }
    EvidencePolicy.copy(original.inputStream(), target); check(target.toByteArray().contentEquals(original))
    val interrupted = object : InputStream() { var n = 0; override fun read(): Int { if (++n >= 11) throw IOException("interrupted"); return 42 } }
    check(runCatching { EvidencePolicy.copy(interrupted, ByteArrayOutputStream()) }.exceptionOrNull() is IOException)
    EvidencePolicy.dimensions(6000, 4000)
    fails(EvidenceError.DIMENSIONS) { EvidencePolicy.dimensions(6001, 4000) }
    fails(EvidenceError.DIMENSIONS) { EvidencePolicy.dimensions(Int.MAX_VALUE, Int.MAX_VALUE) }
    fails(EvidenceError.UNSUPPORTED) { EvidencePolicy.dimensions(-1, -1) }
    val file = File.createTempFile("evidence-policy-", ".png")
    try {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==")
        // CRC from a known valid fixture is exercised by the Android generated PNG test too.
        file.writeBytes(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xda.toByte(),0,2,0,0xff.toByte(),0xd9.toByte()))
        EvidencePolicy.container(file, "image/jpeg")
        file.writeBytes(file.readBytes().dropLast(2).toByteArray())
        fails(EvidenceError.CORRUPT) { EvidencePolicy.container(file, "image/jpeg") }
        file.writeBytes(png.copyOf(20))
        fails(EvidenceError.CORRUPT) { EvidencePolicy.container(file, "image/png") }
        val location = EvidenceLocation(3,"校园志愿服务",7,"2026-10-05",EvidencePhoto(2,7,"photo"))
        val error = EvidenceProblems(listOf(EvidenceIssue(location,EvidenceError.DECODE,"图片无法解码")))
        check(error.message!!.contains("校园志愿服务（活动 3） / 2026-10-05（参与 7） / 证明 2"))
    } finally { file.delete() }
    println("EvidencePolicy JVM copy/bounds/container/location checks passed")
}
