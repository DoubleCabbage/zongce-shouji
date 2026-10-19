package cn.activityscore.app.tests

import android.content.Context
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import java.io.File

/** Every phase uses a separate app-private fixture; no production data or preferences are touched. */
class DataOperationChecks(private val base: Context) {
    private var count = 0
    private fun expect(ok: Boolean) { check(ok) { "P1-09 assertion ${count + 1}" }; count++ }
    private fun failure(state: WriteState, block: () -> Unit): DataOperationException {
        val error = runCatching(block).exceptionOrNull()
        check(error is DataOperationException && error.state == state) { "Expected $state, actual=$error\n${error?.stackTraceToString()}" }; count++
        return error as DataOperationException
    }
    private fun fixture(name: String, reset: Boolean): Context {
        val root = base.getDir(name, 0)
        expect(root.canonicalPath != base.filesDir.canonicalPath)
        if(reset) { root.deleteRecursively(); check(root.mkdirs()) }
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
            override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
            override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("p109-${root.name}-$name", mode)
        }.also { expect(it.getDatabasePath("activity_score.db") != base.getDatabasePath("activity_score.db")) }
    }
    internal fun uiContext() = fixture("data-ui-regression", true)
    private fun image(context: Context): Uri {
        val file = File(context.cacheDir, "source.png")
        val bitmap = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        return Uri.fromFile(file)
    }
    private fun create(store: RecordStore, title: String = "原活动", images: List<Uri> = emptyList(), share: String? = null) =
        store.createItem(title, "劳动", "", "", 1.2345, "原备注", "2026-10-05", "原参与", images, share)

    fun run(phase: String): Bundle {
        val result = Bundle()
        try {
            when {
                phase == "suite" -> suite()
                phase == "backup-native-commit" -> backupNativeCommit()
                phase == "same-state" -> sameState()
                phase == "same-state-restart" -> sameStateRestart()
                phase == "file-guard" -> fileGuard()
                phase == "file-guard-restart" -> fileGuardRestart()
                phase.startsWith("kill-") -> kill(phase.removePrefix("kill-"))
                phase.startsWith("verify-") -> verify(phase.removePrefix("verify-"))
                else -> error("Unknown phase")
            }
            result.putInt("failures", 0)
            result.putString("stream", "P1-09 Android data $phase passed ($count checks), pid=${android.os.Process.myPid()}\n")
        } catch (error: Throwable) {
            result.putInt("failures", 1)
            result.putString("stream", "P1-09 failed after $count checks: ${error.stackTraceToString()}\n")
        }
        return result
    }

    private fun suite() {
        val context = fixture("data-operation-regression", true)
        val store = RecordStore(context)
        val png = image(context)
        val id = create(store, images = listOf(png), share = "unique-share")
        var item = store.listItems().single()
        val visit = store.listParticipations(id).single()
        store.updateItem(item.copy(title = "已更新")); expect(store.listItems().single().title == "已更新")
        store.updateParticipation(visit.id, "2026-10-06", "已更新参与", emptyList())
        expect(store.listParticipations(id).single().note == "已更新参与")
        failure(WriteState.NOT_FOUND) { store.updateItem(item.copy(id = 999999)) }
        failure(WriteState.NOT_FOUND) { store.updateParticipation(999999, "2026-10-06", "", emptyList()) }
        failure(WriteState.NOT_FOUND) { store.deleteParticipation(999999) }
        failure(WriteState.NOT_FOUND) { store.deleteItem(999999) }
        failure(WriteState.NOT_FOUND) { store.addEvidence(999999, emptyList()) }
        val before = store.loadSnapshot(2026)
        failure(WriteState.CONFLICT) { create(store, share = "unique-share", images = listOf(png)) }
        expect(store.loadSnapshot(2026) == before)
        fun sql(command: String) = store.withBackupDatabase { it.execSQL(command) }
        fun trigger(table: String, action: String, whenSql: String = "", body: String = "SELECT RAISE(ABORT,'fixture failure');") =
            sql("CREATE TRIGGER fixture BEFORE $action ON $table $whenSql BEGIN $body END")
        fun drop() = sql("DROP TRIGGER fixture")
        // Real FK violation inside an INSERT trigger, not preflight target-not-found handling.
        trigger("participations", "INSERT", body = "INSERT INTO evidence(participation_id,file_name) VALUES(999999,'missing');")
        failure(WriteState.CONFLICT) { create(store) }; drop()
        expect(store.loadSnapshot(2026) == before)
        trigger("score_items", "INSERT"); failure(WriteState.CONFLICT) { create(store) }; drop()
        trigger("score_items", "UPDATE"); failure(WriteState.CONFLICT) { store.updateItem(item.copy(title = "失败修改")) }; drop()
        trigger("participations", "DELETE"); failure(WriteState.CONFLICT) { store.deleteParticipation(visit.id) }; drop()
        expect(store.evidenceFile(visit.evidence.single().fileName).isFile)
        expect(store.loadSnapshot(2026) == before)
        // INSERT/UPDATE/DELETE IGNORE are zero affected rows, never successful writes.
        trigger("score_items", "INSERT", body = "SELECT RAISE(IGNORE);")
        failure(WriteState.FAILED) { create(store) }; drop()
        trigger("score_items", "UPDATE", body = "SELECT RAISE(IGNORE);")
        failure(WriteState.CONFLICT) { store.updateItem(item.copy(title = "忽略")) }; drop()
        trigger("participations", "DELETE", body = "SELECT RAISE(IGNORE);")
        failure(WriteState.CONFLICT) { store.deleteParticipation(visit.id) }; drop()
        expect(store.loadSnapshot(2026) == before)

        val annual = AnnualInputs(2026, selfEvaluation = 88.123456, courses = listOf(CourseGrade("课程", 1.2345, 90.12345)))
        store.saveAnnualInputs(annual)
        trigger("annual_inputs", "INSERT")
        failure(WriteState.CONFLICT) { store.saveAnnualInputs(annual.copy(selfEvaluation = 10.0)) }; drop()
        expect(store.annualInputs(2026) == annual)
        // Legitimate historical academic-year primary keys remain supported.
        store.saveAnnualInputs(AnnualInputs(0)); store.saveAnnualInputs(AnnualInputs(-1))
        expect(store.annualInputs(0) == AnnualInputs(0) && store.annualInputs(-1) == AnnualInputs(-1))
        val review = DuplicateConfirmation("review", "fingerprint", DuplicateDecision.COUNT_EACH, mapOf(visit.id to 1.2345))
        store.saveDuplicateConfirmation(review)
        trigger("duplicate_reviews", "INSERT")
        failure(WriteState.CONFLICT) { store.saveDuplicateConfirmation(review.copy(participationScores = mapOf(visit.id to 4.0, 999L to 5.0))) }; drop()
        expect(store.listDuplicateConfirmations().getValue("review") == review)
        val history = HistoryRow(sourceRow = 1, activityName = "原历史", dimension = "劳动", subcategory = "", role = "", referenceScore = 1.2345)
        store.replaceHistory("old.csv", listOf(history))
        val oldHistory = store.listHistory(); val oldInfo = store.historyInfo()
        trigger("history_records", "INSERT", "WHEN NEW.source_row = 2")
        failure(WriteState.CONFLICT) { store.replaceHistory("new.csv", listOf(history.copy(activityName="新1"), history.copy(sourceRow=2, activityName="新2"))) }; drop()
        expect(store.listHistory() == oldHistory && store.historyInfo() == oldInfo)
        trigger("history_info", "INSERT")
        failure(WriteState.CONFLICT) { store.replaceHistory("new.csv", listOf(history.copy(activityName="新历史"))) }; drop()
        expect(store.listHistory() == oldHistory && store.historyInfo() == oldInfo)
        trigger("evidence", "INSERT", "WHEN (SELECT COUNT(*) FROM evidence) > 1")
        failure(WriteState.CONFLICT) { store.addEvidence(visit.id, listOf(png, png)) }; drop()
        expect(store.listParticipations(id).single().evidence == visit.evidence)
        expect(File(context.filesDir, "evidence").listFiles()!!.size == 1)

        // Controlled failures at defined stages, followed by independent persisted-row confirmation.
        store.writeFault = { if(it == "BEFORE_BEGIN") error("before writing") }
        failure(WriteState.FAILED) { create(store) }
        store.writeFault = { if(it == "BEFORE_COMMIT") error("before commit request") }
        failure(WriteState.FAILED) { create(store, images=listOf(png)) }
        expect(File(context.filesDir, "evidence").listFiles()!!.size == 1)
        // Actual native COMMIT constraint failure: defer an FK violation until endTransaction.
        val db = store.withBackupDatabase { it }
        store.writeFault = { if(it == "BEFORE_COMMIT") {
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            db.execSQL("INSERT INTO evidence(participation_id,file_name) VALUES(999999,'deferred')")
        } }
        failure(WriteState.CONFLICT) { create(store) }
        store.writeFault = {}
        expect(store.listItems().size == 1)
        expect(store.evidenceDiagnostics().isEmpty())
        store.writeFault = { if(it == "AFTER_END") error("reported after actual commit") }
        val committed = create(store, "收尾异常但核实提交", listOf(png))
        expect(store.listItems().any { it.id == committed })
        store.writeFault = { if(it == "AFTER_END" || it == "BEFORE_VERIFY") error("confirmation unavailable") }
        failure(WriteState.UNKNOWN) { create(store, "未知提交", listOf(png)) }
        val uncertainSnapshot = store.loadSnapshot(2026)
        expect(uncertainSnapshot.items.any { it.title == "未知提交" })
        val retained = File(context.filesDir,"evidence").listFiles()!!.size
        failure(WriteState.UNKNOWN) { create(store, "不可重试") }
        failure(WriteState.UNKNOWN) { store.deleteItem(id) }
        failure(WriteState.UNKNOWN) { FullBackup.capture(context, store) }
        expect(File(context.filesDir,"evidence").listFiles()!!.size == retained)
        store.writeFault = {}
        expect(store.recheckPendingWrite() == WriteState.SUCCESS)
        expect(store.recheckPendingWrite() == null)
        store.writeFault = { if(it == "BEFORE_COMMIT" || it == "BEFORE_VERIFY") error("rollback then unavailable read") }
        failure(WriteState.UNKNOWN) { create(store, "未知回滚", listOf(png)) }
        store.writeFault = {}
        expect(store.recheckPendingWrite() == WriteState.FAILED)
        expect(store.listItems().none { it.title == "未知回滚" })

        // No silent omission of corrupt review/course payloads. Other data stays in place.
        sql("UPDATE duplicate_reviews SET decision='BROKEN' WHERE review_key='review'")
        expect(runCatching { store.loadSnapshot(2026) }.isFailure)
        expect(runCatching { store.listDuplicateConfirmations() }.isFailure)
        sql("UPDATE duplicate_reviews SET decision='COUNT_EACH' WHERE review_key='review'")
        sql("UPDATE annual_inputs SET courses_json='broken' WHERE academic_year=2026")
        expect(runCatching { store.loadSnapshot(2026) }.isFailure)
        expect(runCatching { store.annualInputs(2026) }.isFailure)
        sql("UPDATE annual_inputs SET courses_json='[]' WHERE academic_year=2026")
        sql("ALTER TABLE history_info RENAME TO unavailable_history_info")
        expect(runCatching { store.loadSnapshot(2026) }.isFailure)
        sql("ALTER TABLE unavailable_history_info RENAME TO history_info")
        expect(store.listHistory() == oldHistory)
        val beforeOrphan = store.loadSnapshot(2026)
        sql("PRAGMA foreign_keys = OFF")
        sql("INSERT INTO participations(id,item_id,event_date,note) VALUES(999998,999999,'2026-10-05','orphan fixture')")
        sql("PRAGMA foreign_keys = ON")
        expect(runCatching { store.loadSnapshot(2026) }.isFailure)
        expect(store.withBackupDatabase { it.rawQuery("SELECT 1 FROM participations WHERE id=999998",null).use { c -> c.moveToFirst() } })
        sql("DELETE FROM participations WHERE id=999998")
        expect(store.loadSnapshot(2026) == beforeOrphan)

        // A failed file deletion follows a confirmed DB deletion; other valid proofs remain linked.
        val bad = store.listParticipations(id).single().evidence.single()
        val path = store.evidenceFile(bad.fileName)
        check(path.delete()); check(path.mkdir()); File(path,"cannot-delete-as-file").writeText("fixture")
        failure(WriteState.COMMITTED_CLEANUP_PENDING) { store.deleteEvidence(bad) }
        expect(store.listParticipations(id).single().evidence.isEmpty())
        expect(path.isDirectory)
        expect(store.listParticipations(committed).single().evidence.single().let { store.evidenceFile(it.fileName).isFile })
        failure(WriteState.NOT_FOUND) { store.deleteEvidence(bad) }
        item = store.listItems().single { it.id == id }
        store.deleteItem(id)
        failure(WriteState.NOT_FOUND) { store.updateItem(item.copy(note="旧UI对象")) }
        val queryOnlyDb = store.withBackupDatabase { it }
        queryOnlyDb.execSQL("PRAGMA query_only = ON")
        failure(WriteState.FAILED) { create(store) }
        queryOnlyDb.execSQL("PRAGMA query_only = OFF")
        store.close()
        val readOnly = SQLiteDatabase.openDatabase(context.getDatabasePath("activity_score.db").path, null,
            SQLiteDatabase.OPEN_READONLY, preserveCorruptDatabase)
        failure(WriteState.FAILED) {
            DataWrites.transaction(readOnly, { listOf(newRows(it, "score_items")) }, retire = { readOnly.close() }) {
                checkedInsert(readOnly, "score_items", ContentValues().apply {
                    put("title", "只读失败"); put("dimension", "劳动"); put("created_at", 0L)
                })
            }
        }
        readOnly.close()
        val reopened = RecordStore(context)
        expect(reopened.listItems().size == uncertainSnapshot.items.size - 1); reopened.close()
        val brokenContext = fixture("data-corruption-regression", true)
        val brokenPath = brokenContext.getDatabasePath("activity_score.db")
        val brokenBytes = ByteArray(4096) { 42 }; brokenPath.writeBytes(brokenBytes)
        expect(runCatching { RecordStore(brokenContext) }.isFailure)
        expect(brokenPath.isFile && brokenPath.readBytes().contentEquals(brokenBytes))
        val unopened = fixture("data-open-regression", true)
        unopened.getDatabasePath("activity_score.db").mkdir()
        expect(runCatching { RecordStore(unopened) }.isFailure)
    }

    private fun kill(stage: String) {
        val context = fixture("data-process-regression", true)
        val store = RecordStore(context); val png = image(context)
        create(store, images = listOf(png))
        File(context.cacheDir,"old.pid").writeText(android.os.Process.myPid().toString())
        File(context.cacheDir,"stage").writeText(stage)
        val point = when(stage) { "before" -> "BEFORE_BEGIN"; "transaction" -> "BEFORE_COMMIT"; "committed" -> "AFTER_END"; else -> error("stage") }
        store.writeFault = { if(it == point) android.os.Process.killProcess(android.os.Process.myPid()) }
        create(store,"新进程活动",listOf(png)); error("Expected real process termination")
    }

    private fun backupNativeCommit() {
        val context = fixture("data-backup-commit-regression", true)
        var store = RecordStore(context)
        val png = image(context)
        val first = create(store, "备份源", listOf(png))
        val archive = FullBackup.capture(context, store)
        val verified = archive.inputStream().use { FullBackup.verify(context, it) }
        store.deleteItem(first)
        create(store, "恢复前原数据", listOf(png))
        val before = store.loadSnapshot(2026)
        val files = before.participations.values.flatten().flatMap { it.evidence }.associate { it.fileName to store.evidenceFile(it.fileName).readBytes().toList() }
        val db = store.withBackupDatabase { it }
        val error = runCatching {
            FullBackup.restore(context, store, verified, fault = { stage -> if(stage == "BEFORE_COMMIT") {
                db.execSQL("PRAGMA defer_foreign_keys = ON")
                db.execSQL("INSERT INTO evidence(participation_id,file_name) VALUES(999999,'commit-failure')")
            } })
        }.exceptionOrNull()
        check(error != null && error.message?.contains("数据已提交") != true) { "Native COMMIT failure misclassified: $error" }; count++
        store.close(); store = RecordStore(context)
        expect(store.loadSnapshot(2026) == before)
        files.forEach { (name, bytes) -> expect(store.evidenceFile(name).readBytes().toList() == bytes) }
        expect(!File(context.filesDir,"full-restore-journal.json").exists())
        expect(store.evidenceDiagnostics().isEmpty())
        verified.close(); FullBackup.discard(archive); store.close()
    }

    private fun sameState() {
        val context = fixture("data-same-state-regression", true)
        val store = RecordStore(context); create(store)
        val original = store.listItems().single()
        store.writeFault = { if(it == "BEFORE_COMMIT") error("unchanged values before commit") }
        failure(WriteState.FAILED) { store.updateItem(original) }
        expect(store.listItems().single() == original)
        val db = store.withBackupDatabase { it }
        store.writeFault = { if(it == "BEFORE_COMMIT") {
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            db.execSQL("INSERT INTO evidence(participation_id,file_name) VALUES(999999,'same-state-commit')")
        } }
        // Real COMMIT failed, but unchanged values cannot distinguish commit/rollback by receipt.
        failure(WriteState.UNKNOWN) { store.updateItem(original) }
        store.writeFault = {}
        expect(store.recheckPendingWrite() == WriteState.UNKNOWN)
        expect(store.loadSnapshot(2026).items.single() == original)
        failure(WriteState.UNKNOWN) { store.updateItem(original.copy(note="不可盲目重试")) }
        File(context.cacheDir,"old.pid").writeText(android.os.Process.myPid().toString())
        store.close()
    }
    private fun sameStateRestart() {
        val context = fixture("data-same-state-regression", false)
        expect(File(context.cacheDir,"old.pid").readText().toInt() != android.os.Process.myPid())
        val store = RecordStore(context)
        val snapshot = store.loadSnapshot(2026)
        expect(snapshot.items.single().note == "原备注")
        expect(snapshot.participations.values.flatten().size == 1)
        store.updateItem(snapshot.items.single().copy(note="独立重启后明确的新操作"))
        expect(store.listItems().single().note == "独立重启后明确的新操作")
        store.close()
    }

    private fun fileGuard() {
        val context = fixture("data-empty-database-regression", true)
        var store = RecordStore(context)
        val png = image(context); val id = create(store, images=listOf(png))
        val proof = store.evidenceFile(store.listParticipations(id).single().evidence.single().fileName)
        val proofBytes = proof.readBytes()
        store.close()
        val path = context.getDatabasePath("activity_score.db")
        path.writeBytes(ByteArray(0)) // Deliberate truncation of an isolated existing database.
        val opened = runCatching { RecordStore(context) }
        opened.getOrNull()?.close()
        check(opened.isFailure) { "Empty database was initialized; length=${path.length()}, proof retained=${proof.exists()}" }; count++
        expect(path.length() == 0L)
        expect(proof.readBytes().contentEquals(proofBytes))
        check(path.delete())
        expect(runCatching { RecordStore(context) }.isFailure)
        expect(!path.exists())
        expect(proof.readBytes().contentEquals(proofBytes))

        val scalar = fixture("data-missing-scalar-regression", true)
        store = RecordStore(scalar); create(store); store.close()
        check(scalar.getDatabasePath("activity_score.db").delete())
        expect(runCatching { RecordStore(scalar) }.isFailure)
        expect(!scalar.getDatabasePath("activity_score.db").exists())
        expect(File(scalar.filesDir,"database-opened.marker").isFile)
        File(scalar.cacheDir,"old.pid").writeText(android.os.Process.myPid().toString())

        val journal = fixture("data-missing-journal-regression", true)
        val log = File(journal.filesDir,"full-restore-journal.json").also { it.writeText("protected fixture journal") }
        expect(runCatching { RecordStore(journal) }.isFailure)
        expect(!journal.getDatabasePath("activity_score.db").exists())
        expect(log.readText() == "protected fixture journal")

        val corrupt = fixture("data-page-corruption-regression", true)
        store = RecordStore(corrupt); create(store); store.close()
        val corruptPath = corrupt.getDatabasePath("activity_score.db")
        val bytes = corruptPath.readBytes().also { it[100] = 0x7f } // Invalid sqlite_master B-tree page type; valid magic header.
        corruptPath.writeBytes(bytes)
        val error = runCatching { RecordStore(corrupt) }.exceptionOrNull()
        expect(error is android.database.sqlite.SQLiteDatabaseCorruptException)
        expect(corruptPath.readBytes().contentEquals(bytes))
    }
    private fun fileGuardRestart() {
        val context = fixture("data-missing-scalar-regression", false)
        expect(File(context.cacheDir,"old.pid").readText().toInt() != android.os.Process.myPid())
        expect(runCatching { RecordStore(context) }.isFailure)
        expect(!context.getDatabasePath("activity_score.db").exists())
        expect(File(context.filesDir,"database-opened.marker").isFile)
    }
    private fun verify(stage: String) {
        val context = fixture("data-process-regression", false)
        expect(File(context.cacheDir,"stage").readText() == stage)
        expect(File(context.cacheDir,"old.pid").readText().toInt() != android.os.Process.myPid())
        val store = RecordStore(context)
        val snapshot = store.loadSnapshot(2026)
        expect(snapshot.items.size == if(stage == "committed") 2 else 1)
        expect(snapshot.items.any { it.title == "原活动" })
        expect(snapshot.items.any { it.title == "新进程活动" } == (stage == "committed"))
        snapshot.participations.values.flatten().forEach { p ->
            expect(p.evidence.size == 1)
            expect(store.evidenceFile(p.evidence.single().fileName).readBytes().contentEquals(File(context.cacheDir,"source.png").readBytes()))
        }
        expect(store.evidenceDiagnostics().isEmpty())
        expect(File(context.filesDir,"evidence").listFiles()!!.size == snapshot.items.size)
        expect(!File(context.filesDir,"full-restore-journal.json").exists())
        store.close()
    }
}
