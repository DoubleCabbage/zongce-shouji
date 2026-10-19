package cn.activityscore.app.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Dependency-free runner. Uses a dedicated fixture directory, never the app's normal database. */
class NumericPersistenceInstrumentation : Instrumentation() {
    private var workbookPhase: String? = null
    private var migrationPhase: String? = null
    private var exportPhase: String? = null
    private var perfPhase: String? = null
    private var perfSize: String = "small"
    private var dataPhase: String? = null
    private var draftPhase: String? = null
    private var evidencePhase: String? = null
    private var backupPhase: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); workbookPhase = arguments?.getString("workbookPhase"); migrationPhase = arguments?.getString("migrationPhase"); exportPhase = arguments?.getString("exportPhase"); perfPhase = arguments?.getString("perfPhase"); perfSize = arguments?.getString("perfSize") ?: "small"; draftPhase = arguments?.getString("draftPhase"); dataPhase = arguments?.getString("dataPhase"); backupPhase = arguments?.getString("backupPhase"); evidencePhase = arguments?.getString("evidencePhase"); start() }

    override fun onStart() {
        workbookPhase?.let {
            val result = WorkbookImportChecks(this).run(it)
            finish(if (result.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        migrationPhase?.let { phase ->
            val result = MigrationUiChecks(this).run(phase)
            finish(if (result.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        exportPhase?.let { phase ->
            val result = ExportUsabilityChecks(this).run(phase)
            finish(if (result.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        perfPhase?.let { phase ->
            val result = PerformanceChecks(this).run(perfSize, phase)
            finish(if (result.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        draftPhase?.let { phase ->
            val result = DraftChecks(this).run(phase)
            finish(if (result.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        dataPhase?.let { phase ->
            val results = if(phase == "ui") DataUiChecks(this).run() else DataOperationChecks(targetContext).run(phase)
            finish(if (results.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
            return
        }
        evidencePhase?.let { phase ->
            val results = EvidencePersistenceChecks(targetContext).run(phase)
            finish(if (results.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
            return
        }
        backupPhase?.let { phase ->
            val backupResults = BackupPersistenceChecks(targetContext).run(phase)
            finish(if (backupResults.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, backupResults)
            return
        }
        val results = Bundle()
        try {
            val directory = targetContext.getDir("numeric-regression-fixture", Context.MODE_PRIVATE)
            // Reset the ENTIRE owned fixture, including P1-09 known-database markers. Deleting
            // only SQLite correctly triggers missing-database protection on a repeated run.
            check(directory.name == "app_numeric-regression-fixture" &&
                directory.parentFile!!.canonicalFile == targetContext.filesDir.parentFile!!.canonicalFile)
            check(directory.deleteRecursively() && directory.mkdirs())
            val fixtureContext = object : ContextWrapper(targetContext) {
                override fun getDatabasePath(name: String) = File(directory, name)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
                override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
                override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
                override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
            }
            check(fixtureContext.getDatabasePath("activity_score.db") != targetContext.getDatabasePath("activity_score.db"))
            fixtureContext.deleteDatabase("activity_score.db")
            val store = RecordStore(fixtureContext)
            fun create(score: Double?, title: String = "精度活动") = store.createItem(
                title, "劳动", "", "", score, "原备注", "2026-09-01", "", emptyList())
            fun rejects(block: () -> Unit) { check(runCatching(block).exceptionOrNull() is IllegalArgumentException) }
            val id = create(1.2345)
            val nullId = create(null, "空分")
            val zeroId = create(0.0, "零分")
            check(store.listItems().single { it.id == nullId }.referenceScore == null)
            check(store.listItems().single { it.id == zeroId }.referenceScore == 0.0)
            repeat(10) { round ->
                val item = store.listItems().single { it.id == id }
                store.updateItem(item.copy(title = "精度活动$round", note = "只改备注$round",
                    referenceScore = NumericValues.parse(NumericValues.formatForEdit(item.referenceScore)).value))
                check(store.listItems().single { it.id == id }.referenceScore!!.toRawBits() == 1.2345.toRawBits())
            }
            val annual = AnnualInputs(2026, 90.123456, 80.234567, 70.345678, 0.12345, 60.456789, 50.567891,
                listOf(CourseGrade("原课程", 1.2345, 87.654321)))
            store.saveAnnualInputs(annual)
            val loaded = store.annualInputs(2026)!!
            store.saveAnnualInputs(loaded.copy(courses = loaded.courses.map {
                it.copy(name = "改课程名", credits = NumericValues.parse(NumericValues.formatForEdit(it.credits), false, NumericRule.POSITIVE).value!!,
                    grade = NumericValues.parse(NumericValues.formatForEdit(it.grade), false, NumericRule.PERCENT).value!!)
            }))
            check(store.annualInputs(2026) == annual.copy(courses = listOf(annual.courses.single().copy(name = "改课程名"))))
            val confirmation = DuplicateConfirmation("repeat:$id:2026", "test-fingerprint", DuplicateDecision.COUNT_EACH,
                mapOf(1L to 1.2345, 2L to 2.3456789))
            store.saveDuplicateConfirmation(confirmation)
            val roundtrip = store.listDuplicateConfirmations().getValue(confirmation.key)
            store.saveDuplicateConfirmation(roundtrip.copy(participationScores = roundtrip.participationScores.mapValues {
                NumericValues.parse(NumericValues.formatForEdit(it.value), false).value!!
            }))
            check(store.listDuplicateConfirmations().getValue(confirmation.key) == confirmation)
            val history = HistoryRow(sourceRow = 7, activityName = "历史精度", dimension = "劳动", subcategory = "", role = "", referenceScore = 1.2345)
            store.replaceHistory("numeric-test.csv", listOf(history))
            check(HistoricalMatcher.buildTree(store.listHistory()).single().roles.single().referenceScore == 1.2345)
            for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
                val before = store.listItems()
                rejects { create(invalid) }
                rejects { store.updateItem(before.single { it.id == id }.copy(referenceScore = invalid)) }
                check(store.listItems() == before)
                rejects { store.replaceHistory("invalid", listOf(history.copy(referenceScore = invalid))) }
                check(store.listHistory().single().referenceScore == 1.2345)
                rejects { store.saveAnnualInputs(annual.copy(deductions = invalid)) }
                rejects { store.saveAnnualInputs(annual.copy(selfEvaluation = invalid)) }
                rejects { store.saveAnnualInputs(annual.copy(courses = listOf(CourseGrade("课程", invalid, 80.0)))) }
                rejects { store.saveAnnualInputs(annual.copy(courses = listOf(CourseGrade("课程", 1.5, invalid)))) }
                rejects { store.saveDuplicateConfirmation(confirmation.copy(participationScores = mapOf(1L to invalid))) }
                rejects { ActivityPackage.write(store, before.single { it.id == id }.copy(referenceScore = invalid), store.listParticipations(id).first(), emptyList(), ByteArrayOutputStream()) }
            }
            check(store.numericDiagnostics().isEmpty())

            // Exercise the actual package JSON/ZIP reader/writer without photos or user data.
            val item = store.listItems().single { it.id == id }
            val bytes = ByteArrayOutputStream()
            ActivityPackage.write(store, item, store.listParticipations(id).first(), emptyList(), bytes)
            val file = File(fixtureContext.cacheDir, "numeric-test.zip")
            try {
                file.writeBytes(bytes.toByteArray())
                val staged = ActivityPackage.read(fixtureContext, Uri.fromFile(file))
                check(staged.activity.referenceScore == 1.2345)
                check(NumericValues.parse(NumericValues.formatForEdit(staged.activity.referenceScore)).value == 1.2345)
                staged.close()
                for (raw in listOf("NaN", "Infinity", "-Infinity", "1e309", "-1", "1,5")) {
                    val manifest = JSONObject(String(java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                        zip.nextEntry; zip.readBytes()
                    })).put("referenceScore", raw).toString()
                    ZipOutputStream(file.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toByteArray()); zip.closeEntry()
                    }
                    check(runCatching { ActivityPackage.read(fixtureContext, Uri.fromFile(file)) }.isFailure)
                }
            } finally { file.delete() }

            // Old anomalies are diagnosed, preserved, and blocked from resaving as legal data.
            fixtureContext.openOrCreateDatabase("activity_score.db", 0, null).use { db ->
                check(db.version == FullBackup.SCHEMA_VERSION)
                db.execSQL("UPDATE score_items SET reference_score = 'not-a-number' WHERE id = ?", arrayOf(id))
                db.execSQL("UPDATE annual_inputs SET courses_json = 'broken-json' WHERE academic_year = 2026")
            }
            check(store.numericDiagnostics().any { it.contains("score_items[$id]") && it.contains("not-a-number") })
            check(store.listItems().single { it.id == id }.referenceScore!!.isNaN())
            rejects { store.updateItem(store.listItems().single { it.id == id }.copy(note = "不能覆盖异常原值")) }
            check(store.numericDiagnostics().any { it.contains("annual_inputs[2026]") })
            rejects { store.saveAnnualInputs(store.annualInputs(2026)!!) }
            results.putString("stream", "Numeric Android persistence/package regressions passed\n")
            results.putInt("failures", 0)
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            results.putInt("failures", 1)
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
