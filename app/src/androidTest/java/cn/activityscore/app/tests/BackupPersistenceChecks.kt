package cn.activityscore.app.tests

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Runs exclusively in app_full-backup-regression and prefixed fixture preferences. */
class BackupPersistenceChecks(private val targetContext: Context) {
    private var assertions = 0
    private fun assertThat(value: Boolean) { check(value); assertions++ }
    private fun rejects(block: () -> Unit) { assertThat(runCatching(block).isFailure) }
    private lateinit var root: File
    private fun fixture(name: String, reset: Boolean = true): Context {
        val directory = File(root, name)
        check(directory.canonicalPath.startsWith(root.canonicalPath + File.separator))
        if (reset) directory.deleteRecursively()
        directory.mkdirs()
        return object : ContextWrapper(targetContext) {
            override fun getDatabasePath(name: String) = File(directory, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
            override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
            override fun getSharedPreferences(pref: String, mode: Int): SharedPreferences = super.getSharedPreferences("p108-fixture-$name-$pref", mode)
        }.also {
            assertThat(it.getDatabasePath("activity_score.db") != targetContext.getDatabasePath("activity_score.db"))
            if (reset) check(it.getSharedPreferences("history_mapping_profiles", 0).edit().clear().commit())
        }
    }
    private fun item(store: RecordStore, title: String = "完整备份活动", photos: List<Uri> = emptyList()): Long =
        store.createItem(title, "劳动", "子类", "身份", 1.2345, "备注\u0000\n中文", "2026-09-01", "参与备注", photos)
    private fun staged(context: Context, file: File) = file.inputStream().use { FullBackup.verify(context, it) }
    private fun stableState(context: Context, store: RecordStore): String = store.withBackupDatabase { db -> FullBackup.snapshot(context, db).toString() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun entries(file: File): LinkedHashMap<String, ByteArray> = ZipFile(file).use { zip ->
        linkedMapOf<String, ByteArray>().apply { zip.entries().asSequence().forEach { entry -> put(entry.name, zip.getInputStream(entry).use { it.readBytes() }) } }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun editedData(base: Map<String, ByteArray>, change: (JSONObject) -> Unit): ByteArray {
        val files = LinkedHashMap(base); val data = JSONObject(String(files.getValue("data.json"), Charsets.UTF_8)); change(data)
        val bytes = data.toString().toByteArray(); files["data.json"] = bytes
        val manifest = JSONObject(String(files.getValue("manifest.json"), Charsets.UTF_8)); val descriptors = manifest.getJSONArray("files")
        for (i in 0 until descriptors.length()) { val descriptor = descriptors.getJSONObject(i); if (descriptor.getString("path") == "data.json") { descriptor.put("size", bytes.size.toLong()); descriptor.put("sha256", digest(bytes)) } }
        files["manifest.json"] = manifest.toString().toByteArray(); return zip(files)
    }
    private fun fixturePhoto(seed: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(16, 12, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.argb(255,seed*37,seed*19,seed*11))
        return ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it); bitmap.recycle() }.toByteArray()
    }
    private fun suite() {
        val concurrentContext = fixture("concurrent"); val concurrent = RecordStore(concurrentContext)
        val concurrentId = item(concurrent, "快照原名称")
        val started = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        var worker: Thread? = null
        val snapshotArchive = FullBackup.capture(concurrentContext, concurrent, progress = { status ->
            if (status.startsWith("正在捕获")) {
                worker = Thread {
                    started.countDown()
                    concurrent.updateItem(concurrent.listItems().single().copy(title = "捕获后修改"))
                    completed.countDown()
                }.also { it.start() }
                assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertThat(completed.count == 1L)
            }
        })
        worker!!.join(5000); assertThat(completed.count == 0L)
        staged(concurrentContext, snapshotArchive).use { assertThat(it.data.getJSONObject("tables").getJSONArray("score_items").getJSONArray(0).getString(1) == "快照原名称") }
        assertThat(concurrent.listItems().single { it.id == concurrentId }.title == "捕获后修改")
        concurrent.close(); FullBackup.discard(snapshotArchive)
        val sourceContext = fixture("source"); val source = RecordStore(sourceContext)
        val targetContext = fixture("destination"); var target = RecordStore(targetContext)
        item(target, "不可丢失的原记录")
        val empty = FullBackup.capture(sourceContext, source)
        staged(targetContext, empty).use { FullBackup.restore(targetContext, target, it) }
        assertThat(target.listItems().isEmpty()); assertThat(target.listHistory().isEmpty()); FullBackup.discard(empty)

        val photo1 = File(sourceContext.cacheDir, "mock1.png").also { it.writeBytes(fixturePhoto(1)) }
        val photo2 = File(sourceContext.cacheDir, "mock2.png").also { it.writeBytes(fixturePhoto(2)) }
        val ids = (0..2).map { index ->
            val id = item(source, "多次活动$index", listOf(Uri.fromFile(photo1), Uri.fromFile(photo2)))
            source.addParticipation(id, "2026-10-02", "第二次", listOf(Uri.fromFile(photo2)))
            id
        }
        source.addParticipation(ids[0], "2025-10-02", "跨学年", emptyList())
        val reviews = DuplicateReviewFinder.all(source.listItems(), ids.associateWith { source.listParticipations(it) })
        val once = reviews.single { it.key == "repeat:${ids[0]}:2026" }
        val each = reviews.single { it.key == "repeat:${ids[1]}:2026" } as RepeatedParticipationReview
        source.saveDuplicateConfirmation(DuplicateConfirmation(once.key, once.fingerprint, DuplicateDecision.COUNT_ONCE))
        source.saveDuplicateConfirmation(DuplicateConfirmation(each.key, each.fingerprint, DuplicateDecision.COUNT_EACH, each.participations.associate { it.id to 1.2345 }))
        val share = source.shareIdFor(source.listParticipations(ids[0]).first().id)
        source.saveAnnualInputs(AnnualInputs(2025, 90.123456, 80.0, 70.0, 0.12345, 60.0, 50.0, listOf(CourseGrade("课程 A", 1.2345, 87.654321))))
        source.saveAnnualInputs(AnnualInputs(2026, courses = listOf(CourseGrade("课程 B", 2.3456, 90.0))))
        source.replaceHistory("有效历史表.xlsx", listOf(HistoryRow(sourceRow = 9, activityName = "历史 A", dimension = "劳动", subcategory = "分类", role = "优秀", referenceScore = 1.2345)))
        val headers = listOf("活动", "维度", "分数", "身份"); val mapping = ColumnMapping(0, 1, 2, 3)
        MappingProfiles(sourceContext).save(headers, mapping)
        // Preserve AUTOINCREMENT high-water mark after a deletion.
        val removedId = item(source, "删除后保留序列"); source.deleteItem(removedId)
        val before = stableState(sourceContext, source)
        val archive = FullBackup.capture(sourceContext, source)
        assertThat(stableState(sourceContext, source) == before)
        val saved = File(targetContext.cacheDir, "saved-backup.zip")
        FullBackup.save(targetContext, archive, Uri.fromFile(saved))
        assertThat(saved.readBytes().contentEquals(archive.readBytes()))
        val original = entries(archive)
        staged(targetContext, archive).use { backup ->
            assertThat(backup.summary.contains("参与 7 次") && backup.summary.contains("证明 9 张"))
            FullBackup.restore(targetContext, target, backup)
        }
        assertThat(target.listItems() == source.listItems())
        assertThat(target.listHistory() == source.listHistory() && target.historyInfo() == source.historyInfo())
        assertThat(target.listDuplicateConfirmations() == source.listDuplicateConfirmations())
        assertThat(target.annualInputs(2025) == source.annualInputs(2025) && target.annualInputs(2026) == source.annualInputs(2026))
        assertThat(MappingProfiles(targetContext).find(headers) == mapping)
        assertThat(target.hasSharedParticipation(share))
        ids.forEach { id ->
            val actual = target.listParticipations(id); val expected = source.listParticipations(id)
            assertThat(actual.map { it.copy(evidence = emptyList()) } == expected.map { it.copy(evidence = emptyList()) })
            actual.zip(expected).forEach { (a, e) -> a.evidence.zip(e.evidence).forEach { (ap, ep) ->
                assertThat(ap.id == ep.id && ap.participationId == ep.participationId)
                assertThat(target.evidenceFile(ap.fileName).readBytes().contentEquals(source.evidenceFile(ep.fileName).readBytes()))
            } }
        }
        assertThat(target.listItems().all { it.referenceScore!!.toRawBits() == 1.2345.toRawBits() })
        val pending = DuplicateReviewFinder.pending(target.listItems(), ids.associateWith { target.listParticipations(it) }, target.listDuplicateConfirmations())
        assertThat(pending.map { it.key } == listOf("repeat:${ids[2]}:2026"))
        target.close(); target = RecordStore(targetContext)
        assertThat(target.listItems() == source.listItems())
        assertThat(item(target, "序列测试") > removedId)

        val preserved = stableState(targetContext, target)
        val preservedPhotos = target.listItems().flatMap { target.listParticipations(it.id) }.flatMap { it.evidence }.associate { it.fileName to target.evidenceFile(it.fileName).readBytes() }
        fun rejected(bytes: ByteArray) {
            rejects { FullBackup.verify(targetContext, bytes.inputStream()).close() }
            assertThat(stableState(targetContext, target) == preserved)
            preservedPhotos.forEach { (name, bytes) -> assertThat(target.evidenceFile(name).readBytes().contentEquals(bytes)) }
        }
        rejected(byteArrayOf(1, 2, 3)); rejected(archive.readBytes().dropLast(12).toByteArray())
        val attachment = original.keys.first { it.startsWith("attachments/") }
        staged(targetContext, archive).use { backup ->
            val file = File(backup.directory, attachment)
            file.writeBytes(byteArrayOf(42))
            rejects { FullBackup.restore(targetContext, target, backup) }
            assertThat(stableState(targetContext, target) == preserved)
        }
        rejected(zip(original.filterKeys { it != attachment }))
        rejected(zip(LinkedHashMap(original).apply { put(attachment, byteArrayOf(9, 9, 9)) }))
        rejected(zip(LinkedHashMap(original).apply { put(attachment, getValue(attachment).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) }))
        rejected(zip(LinkedHashMap(original).apply { put("../outside", byteArrayOf(1)) }))
        rejected(zip(LinkedHashMap(original).apply { put("attachments/C:\\fake", byteArrayOf(1)) }))
        val future = LinkedHashMap(original).apply { put("manifest.json", JSONObject(String(getValue("manifest.json"))).put("formatVersion", 99).toString().toByteArray()) }
        rejected(zip(future))
        rejected(editedData(original) { it.getJSONObject("tables").getJSONArray("participations").getJSONArray(0).put(1, "99999") })
        rejected(editedData(original) { it.getJSONObject("tables").getJSONArray("evidence").getJSONArray(0).put(1, "99999") })
        rejected(editedData(original) { it.getJSONObject("tables").getJSONArray("evidence").getJSONArray(0).put(2, "../../absolute") })
        rejected(editedData(original) { it.getJSONObject("tables").getJSONArray("score_items").getJSONArray(0).put(5, "NaN") })
        rejected(editedData(original) { it.getJSONObject("tables").getJSONArray("score_items").getJSONArray(0).put(5, "-0x1.0p0") })
        rejected(editedData(original) { it.getJSONObject("tables").getJSONArray("score_items").getJSONArray(0).put(0, 1) })
        rejected(editedData(original) { it.getJSONObject("tables").getJSONArray("annual_inputs").getJSONArray(0).put(7, "[{\"name\":\"bad\",\"credits\":\"1\",\"grade\":80}]") })
        rejected(editedData(original) { val rows = it.getJSONObject("tables").getJSONArray("evidence"); rows.put(rows.getJSONArray(0)) })
        // A highly compressible over-limit image is rejected using central-directory size before extraction.
        val bomb = LinkedHashMap(original).apply { put(attachment, ByteArray(32 * 1024 * 1024 + 1)) }
        rejected(zip(bomb))
        // Duplicate ZIP entry names: create equal-length unique names, then rewrite local and central names.
        val duplicate = zip(linkedMapOf("manifest.json" to original.getValue("manifest.json"), "data.json" to original.getValue("data.json"), "datu.json" to original.getValue("data.json")))
        val marker = "datu.json".toByteArray(); val replacement = "data.json".toByteArray()
        for (i in 0..duplicate.size - marker.size) if (marker.indices.all { duplicate[i + it] == marker[it] }) replacement.copyInto(duplicate, i)
        rejected(duplicate)
        val cancel = BackupCancellation()
        rejects { archive.inputStream().use { FullBackup.verify(targetContext, it, cancel) { cancel.cancelled = true } }.close() }
        assertThat(stableState(targetContext, target) == preserved)
        staged(targetContext, archive).use { backup ->
            for (point in listOf("FILES_READY", "BEFORE_COMMIT")) {
                rejects { FullBackup.restore(targetContext, target, backup, fault = { if (it == point) error("controlled $point") }) }
                assertThat(stableState(targetContext, target) == preserved)
                preservedPhotos.forEach { (name, bytes) -> assertThat(target.evidenceFile(name).readBytes().contentEquals(bytes)) }
                assertThat(!File(targetContext.filesDir, "full-restore-journal.json").exists())
                assertThat(File(targetContext.filesDir, "evidence").listFiles()!!.map { it.name }.toSet() == preservedPhotos.keys)
            }
        }
        // Missing source evidence cannot yield a successful backup.
        val missing = source.listParticipations(ids[0]).first { it.evidence.isNotEmpty() }.evidence.first()
        val missingFile = source.evidenceFile(missing.fileName); val bytes = missingFile.readBytes(); missingFile.delete()
        rejects { FullBackup.capture(sourceContext, source) }; missingFile.writeBytes(bytes)
        source.close(); target.close(); FullBackup.discard(archive)
    }
    /** Simulate an unhandled termination by Error, retaining the durable journal; next invocation is a new process. */
    private fun seedInterruption(point: String, kill: Boolean = false) {
        val srcContext = fixture("restart-source"); val src = RecordStore(srcContext)
        val mock = File(srcContext.cacheDir, "mock").also { it.writeBytes(fixturePhoto(3)) }
        item(src, "重启后完整恢复", listOf(Uri.fromFile(mock)))
        MappingProfiles(srcContext).save(listOf("A", "B", "C"), ColumnMapping(0, 1, 2))
        val dstContext = fixture("restart-target"); val dst = RecordStore(dstContext)
        item(dst, "中断前原数据", listOf(Uri.fromFile(mock)))
        val archive = FullBackup.capture(srcContext, src)
        staged(dstContext, archive).use { backup ->
            val failure = runCatching { FullBackup.restore(dstContext, dst, backup, fault = { if (it == point) {
                if (kill) android.os.Process.killProcess(android.os.Process.myPid())
                throw SimulatedDeath()
            } }) }.exceptionOrNull()
            assertThat(failure is SimulatedDeath)
            assertThat(File(dstContext.filesDir, "full-restore-journal.json").isFile)
        }
        src.close(); dst.close(); FullBackup.discard(archive)
    }
    private fun verifyInterruption(committed: Boolean) {
        val context = fixture("restart-target", false); val store = RecordStore(context)
        assertThat(store.listItems().single().title == if (committed) "重启后完整恢复" else "中断前原数据")
        val photos = store.listParticipations(store.listItems().single().id).single().evidence
        assertThat(photos.size == 1 && store.evidenceFile(photos.single().fileName).readBytes().contentEquals(fixturePhoto(3)))
        assertThat(!File(context.filesDir, "full-restore-journal.json").exists())
        assertThat(File(context.filesDir, "evidence").listFiles()!!.size == 1)
        assertThat((MappingProfiles(context).find(listOf("A", "B", "C")) != null) == committed)
        store.close()
    }
    private class SimulatedDeath : Error("simulated process interruption")
    private fun verifyDamagedCommitted() {
        val context = fixture("restart-target", false)
        val files = File(context.filesDir, "evidence").listFiles()!!
        assertThat(files.size == 2)
        val restored = files.single { it.name.startsWith("restore_") }
        val bytes = restored.readBytes()
        restored.writeBytes(bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        rejects { RecordStore(context).close() }
        assertThat(File(context.filesDir, "full-restore-journal.json").isFile)
        assertThat(files.all { it.isFile })
        restored.writeBytes(bytes)
        verifyInterruption(true)
    }
    fun run(phase: String): Bundle {
        val results = Bundle()
        try {
            root = targetContext.getDir("full-backup-regression", Context.MODE_PRIVATE)
            when (phase) {
                "suite" -> suite()
                "seed-files" -> seedInterruption("FILES_READY")
                "seed-transaction" -> seedInterruption("BEFORE_COMMIT")
                "seed-committed" -> seedInterruption("AFTER_COMMIT")
                "kill-files" -> seedInterruption("FILES_READY", true)
                "kill-transaction" -> seedInterruption("BEFORE_COMMIT", true)
                "kill-committed" -> seedInterruption("AFTER_COMMIT", true)
                "verify-original" -> verifyInterruption(false)
                "verify-restored" -> verifyInterruption(true)
                "verify-damaged-committed" -> verifyDamagedCommitted()
                else -> error("unknown phase")
            }
            results.putString("stream", "P1-08 Android backup $phase passed ($assertions assertions), pid=${android.os.Process.myPid()}\n")
            results.putInt("failures", 0)
        } catch (error: Throwable) { results.putString("stream", error.stackTraceToString()); results.putInt("failures", 1) }
        return results
    }
}
