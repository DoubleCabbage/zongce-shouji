package cn.activityscore.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.AtomicFile
import cn.activityscore.app.domain.DuplicateConfirmation
import cn.activityscore.app.domain.DuplicateDecision
import cn.activityscore.app.domain.AnnualInputs
import cn.activityscore.app.domain.AnnualActivityScoreCalculator
import cn.activityscore.app.domain.CourseGrade
import java.io.File
import java.io.DataInputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class RecordStore(private val context: Context) {
    private val database = ScoreDatabase(context)
    private val evidenceDir = File(context.filesDir, "evidence").also { it.mkdirs() }
    private val openedMarker = AtomicFile(File(context.filesDir, "database-opened.marker"))
    private var headerChecked = false

    init {
        synchronized(DataLock.monitor) {
            checkDatabaseFiles()
            try {
                val db = database.writableDatabase
                // Derived safety metadata, not a business table, operation receipt or backup token.
                markDatabaseOpened()
                headerChecked = true
                FullBackup.recover(context, db)
            }
            catch (error: Throwable) { runCatching { database.close() }; throw error }
            if (!DataWrites.hasPending(database.writableDatabase.path)) cleanEvidenceStaging()
        }
    }

    private inline fun <T> withDataLock(block: () -> T): T = synchronized(DataLock.monitor) {
        checkDatabaseFiles()
        FullBackup.recover(context, database.writableDatabase)
        block()
    }

    internal fun <T> withBackupDatabase(block: (SQLiteDatabase) -> T): T =
        withDataLock {
            if (DataWrites.hasPending(database.writableDatabase.path)) throw DataWrites.unknown()
            block(database.writableDatabase)
        }

    /** Instrumentation seam; production leaves it empty. */
    internal var writeFault: (String) -> Unit = {}

    fun recheckPendingWrite(): WriteState? = withDataLock {
        DataWrites.recheck(database.writableDatabase.path)
    }

    private fun <T> write(db: SQLiteDatabase, scopes: (SQLiteDatabase) -> List<WriteScope>, block: () -> T): T =
        DataWrites.transaction(db, scopes, writeFault, { retireFailedDatabase() }, block)

    internal fun retireFailedDatabase() = synchronized(DataLock.monitor) { headerChecked = false; database.close() }

    fun close() = synchronized(DataLock.monitor) { headerChecked = false; database.close() }

    private fun markerExists(file: File): Boolean = listOf("", ".bak", ".new").any { File(file.path + it).exists() }

    private fun checkDatabaseFiles() {
        val path = context.getDatabasePath("activity_score.db")
        if (path.exists()) {
            if (!path.isFile || path.length() < 100) throw DataReadException(
                "数据库文件不完整，当前不会初始化空库或清理证明。请保留现有文件、证明和有效备份。")
            if (!headerChecked) {
                val magic = ByteArray(16)
                try { DataInputStream(path.inputStream()).use { it.readFully(magic) } }
                catch (error: Exception) { throw DataReadException("数据库文件暂时无法读取，请检查访问条件并重试；现有文件和证明保留。", error) }
                if (!magic.contentEquals("SQLite format 3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)))
                    throw DataReadException("数据库文件格式异常，当前不会初始化或清理证明。请保留现有文件和有效备份。")
                headerChecked = true
            }
        } else {
            val files = evidenceDir.listFiles()
            if (headerChecked || markerExists(openedMarker.baseFile) || markerExists(File(context.filesDir,"full-restore-journal.json")) ||
                files?.isNotEmpty() == true || evidenceDir.exists() && files == null) {
                throw DataReadException("已有数据库文件缺失，当前不会新建空库。证明和恢复日志保留，请先核对有效备份。")
            }
        }
    }

    private fun markDatabaseOpened() {
        if (markerExists(openedMarker.baseFile)) return
        val stream = openedMarker.startWrite()
        try {
            stream.write("sqlite-v5".toByteArray(Charsets.US_ASCII)); stream.fd.sync()
            openedMarker.finishWrite(stream)
            syncDirectory(context.filesDir)
        } catch (error: Exception) {
            openedMarker.failWrite(stream)
            throw DataReadException("无法保存数据保护信息，请检查可用空间后重试；当前不开放业务操作。", error)
        }
    }

    fun loadSnapshot(year: Int): RecordSnapshot = withDataLock {
        val db = database.writableDatabase
        if (db.inTransaction()) throw DataWrites.unknown()
        db.beginTransaction()
        try {
            db.rawQuery("PRAGMA foreign_key_check", null).use {
                require(!it.moveToFirst()) { "数据库活动、参与或证明关联异常；原数据已保留，请核对有效备份。" }
            }
            val items = listItems()
            RecordSnapshot(items, items.associate { it.id to listParticipations(it.id) }, listHistory(), historyInfo(),
                listDuplicateConfirmations(), annualInputs(year), numericDiagnostics(),
                db.rawQuery("SELECT share_id FROM participations WHERE share_id IS NOT NULL", null).use { c ->
                    buildSet { while(c.moveToNext()) add(c.getString(0)) }
                })
        } finally { db.endTransaction() }
    }

    /** The transaction also excludes deletes until all referenced files have been copied. */
    fun captureAnnualExport(year: Int): AnnualExportSnapshot {
        withDataLock {
            val directory = File.createTempFile("annual-export-", "", context.cacheDir)
            check(directory.delete() && directory.mkdir()) { "无法准备导出快照空间" }
            try {
                val db = database.writableDatabase
                if (DataWrites.hasPending(db.path) || db.inTransaction()) throw DataWrites.unknown()
                db.beginTransaction()
                try {
                    db.rawQuery("PRAGMA foreign_key_check", null).use {
                        if (it.moveToFirst()) {
                            val type = if (it.getString(0) == "evidence") "证明" else "参与"
                            throw DataReadException("$type（记录 ID ${it.getLong(1)}）的关联损坏，无法准备导出快照。请保留数据库并核对有效备份。")
                        }
                    }
                    val items = listItems()
                    val visits = items.associate { it.id to listParticipations(it.id) }
                    val confirmations = listDuplicateConfirmations()
                    val scores = AnnualActivityScoreCalculator.calculate(year, items, visits,
                        confirmations)
                    val assessment = AnnualExportAssessment.from(scores, confirmations)
                    assessment.requireReady()
                    val issues = evidenceDiagnostics(year)
                    if (issues.isNotEmpty()) throw EvidenceProblems(issues)
                    val snapshot = AnnualExportSnapshot.capture(scores, directory, assessment,
                        ExportSourceState(items, visits, confirmations)) { evidenceFile(it.fileName) }
                    db.setTransactionSuccessful()
                    return snapshot
                } finally {
                    db.endTransaction()
                }
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        }
    }

    /** Read-only stale-snapshot guard; callers run this on IO before generation and final save. */
    fun validateAnnualExport(snapshot: AnnualExportSnapshot) = withDataLock {
        val db = database.writableDatabase
        if (DataWrites.hasPending(db.path) || db.inTransaction()) throw DataWrites.unknown()
        db.beginTransaction()
        try {
            val items = listItems()
            val current = ExportSourceState(items, items.associate { it.id to listParticipations(it.id) }, listDuplicateConfirmations())
            if (snapshot.sourceState != current) throw ExportSnapshotChanged()
        } finally { db.endTransaction() }
    }

    fun listItems(): List<ScoreItem> {
        withDataLock {
            val items = mutableListOf<ScoreItem>()
            database.readableDatabase.rawQuery(
                "SELECT id, title, dimension, subcategory, role, reference_score, note, created_at " +
                    "FROM score_items ORDER BY created_at DESC, id DESC",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    items += ScoreItem(
                        id = cursor.getLong(0),
                        title = cursor.getString(1),
                        dimension = cursor.getString(2),
                        subcategory = cursor.getString(3),
                        role = cursor.getString(4),
                        referenceScore = storedNumber(cursor, 5),
                        note = cursor.getString(6),
                        createdAt = cursor.getLong(7),
                    )
                }
            }
            return items
        }
    }

    fun historyInfo(): HistoryInfo? {
        withDataLock {
            database.readableDatabase.rawQuery(
                "SELECT file_name, imported_at, row_count FROM history_info WHERE id = 1", null
            ).use { cursor ->
                return if (cursor.moveToFirst()) {
                    HistoryInfo(cursor.getString(0), cursor.getLong(1), cursor.getInt(2))
                } else null
            }
        }
    }

    fun listHistory(): List<HistoryRow> {
        withDataLock {
            val records = mutableListOf<HistoryRow>()
            database.readableDatabase.rawQuery(
                "SELECT id, source_row, activity_name, dimension, subcategory, role, reference_score, sources_json " +
                    "FROM history_records ORDER BY activity_name, source_row", null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records += HistoryRow(
                        id = cursor.getLong(0),
                        sourceRow = cursor.getInt(1),
                        activityName = cursor.getString(2),
                        dimension = cursor.getString(3),
                        subcategory = cursor.getString(4),
                        role = cursor.getString(5),
                        referenceScore = storedNumber(cursor, 6) ?: Double.NaN,
                        sources = HistorySourcesJson.decode(cursor.getString(7)),
                    )
                }
            }
            return records
        }
    }

    /** Replaces the one active historical table, never touching personal score items. */
    fun replaceHistory(fileName: String, records: List<HistoryRow>) {
        withDataLock {
            require(records.isNotEmpty()) { "没有可导入的有效记录" }
            NumericDataValidation.history(records)
            val db = database.writableDatabase
            write(db, { listOf(WriteScope("SELECT * FROM history_records ORDER BY id"),
                WriteScope("SELECT * FROM history_info ORDER BY id")) }) {
                db.delete("history_records", null, null) // Clearing an empty history is intentionally idempotent.
                check(db.rawQuery("SELECT 1 FROM history_records LIMIT 1", null).use { !it.moveToFirst() })
                records.forEach { record ->
                    checkedInsert(db, "history_records", ContentValues().apply {
                        put("source_row", record.sourceRow); put("activity_name", record.activityName)
                        put("dimension", record.dimension); put("subcategory", record.subcategory)
                        put("role", record.role); put("reference_score", record.referenceScore)
                        put("sources_json", HistorySourcesJson.encode(record.sources))
                    })
                }
                checkedInsert(db, "history_info", ContentValues().apply {
                    put("id", 1); put("file_name", fileName); put("imported_at", System.currentTimeMillis())
                    put("row_count", records.size)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                check(db.rawQuery("SELECT COUNT(*) FROM history_records", null).use { it.moveToFirst(); it.getInt(0) } == records.size)
            }
        }
    }

    fun listParticipations(itemId: Long): List<Participation> {
        withDataLock {
            val participations = mutableListOf<Participation>()
            database.readableDatabase.rawQuery(
                "SELECT id, event_date, note FROM participations " +
                    "WHERE item_id = ? ORDER BY event_date DESC, id DESC",
                arrayOf(itemId.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val participationId = cursor.getLong(0)
                    participations += Participation(
                        id = participationId,
                        itemId = itemId,
                        eventDate = cursor.getString(1),
                        note = cursor.getString(2),
                        evidence = listEvidence(participationId),
                    )
                }
            }
            return participations
        }
    }

    fun shareIdFor(participationId: Long): String = withDataLock {
        val db = database.writableDatabase
        write(db, { listOf(rowScope("participations", "id = ?", participationId)) }) {
            requireTarget(db, "participations", participationId, "参与记录不存在，可能已被删除。")
            val current = db.rawQuery("SELECT share_id FROM participations WHERE id = ?", arrayOf(participationId.toString())).use {
                it.moveToFirst(); if(it.isNull(0)) null else it.getString(0)
            }
            if (current != null) current else {
                val values = ContentValues().apply { put("share_id", UUID.randomUUID().toString()) }
                requireOne(db.update("participations", values, "id = ? AND share_id IS NULL", arrayOf(participationId.toString())),
                    "参与记录发生冲突，请重新读取。", true)
                verifyValues(db, "participations", "id = ?", arrayOf(participationId.toString()), values)
                values.getAsString("share_id")
            }
        }
    }

    fun hasSharedParticipation(shareId: String): Boolean =
        withDataLock {
            database.readableDatabase.rawQuery(
                "SELECT 1 FROM participations WHERE share_id = ? LIMIT 1", arrayOf(shareId)
            ).use { it.moveToFirst() }
        }

    fun listDuplicateConfirmations(): Map<String, DuplicateConfirmation> {
        withDataLock {
            val confirmations = mutableMapOf<String, DuplicateConfirmation>()
            database.readableDatabase.rawQuery(
                "SELECT review_key, fingerprint, decision, scores_json FROM duplicate_reviews", null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val decision = DuplicateDecision.valueOf(cursor.getString(2))
                    val scores = run {
                        val json = JSONObject(cursor.getString(3))
                        json.keys().asSequence().associate { id -> id.toLong() to json.getDouble(id) }
                    }
                    val confirmation = DuplicateConfirmation(cursor.getString(0), cursor.getString(1), decision, scores)
                    confirmations[confirmation.key] = confirmation
                }
            }
            return confirmations
        }
    }

    fun saveDuplicateConfirmation(confirmation: DuplicateConfirmation) {
        withDataLock {
            require(confirmation.key.isNotBlank() && confirmation.fingerprint.isNotBlank())
            NumericDataValidation.confirmation(confirmation)
            val scores = JSONObject().apply {
                confirmation.participationScores.forEach { (id, score) -> put(id.toString(), score) }
            }
            val db = database.writableDatabase
            write(db, { listOf(rowScope("duplicate_reviews", "review_key = ?", confirmation.key)) }) {
            checkedInsert(db,
                "duplicate_reviews", null,
                ContentValues().apply {
                    put("review_key", confirmation.key)
                    put("fingerprint", confirmation.fingerprint)
                    put("decision", confirmation.decision.name)
                    put("scores_json", scores.toString())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            }
        }
    }

    fun annualInputs(year: Int): AnnualInputs? {
        withDataLock {
            database.readableDatabase.rawQuery(
                "SELECT self_score, peer_score, teacher_score, deductions, fitness, running, courses_json " +
                    "FROM annual_inputs WHERE academic_year = ?", arrayOf(year.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val courses = try {
                    val json = JSONArray(cursor.getString(6))
                    (0 until json.length()).map { index ->
                        val course = json.getJSONObject(index)
                        CourseGrade(course.getString("name"), course.getDouble("credits"), course.getDouble("grade"))
                    }
                } catch (error: org.json.JSONException) {
                    throw IllegalArgumentException("已存课程资料格式异常，请保留原数据并核对备份。", error)
                }
                fun number(index: Int) = storedNumber(cursor, index)
                return AnnualInputs(year, number(0), number(1), number(2), number(3),
                    number(4), number(5), courses)
            }
        }
    }

    fun saveAnnualInputs(inputs: AnnualInputs) {
        withDataLock {
            NumericDataValidation.annual(inputs)
            val courses = JSONArray().apply {
                inputs.courses.forEach { course ->
                    put(JSONObject().apply {
                        put("name", course.name.trim())
                        put("credits", course.credits)
                        put("grade", course.grade)
                    })
                }
            }
            val db = database.writableDatabase
            write(db, { listOf(rowScope("annual_inputs", "academic_year = ?", inputs.academicYear)) }) {
            checkedInsert(db,
                "annual_inputs", null, ContentValues().apply {
                    put("academic_year", inputs.academicYear)
                    listOf("self_score", "peer_score", "teacher_score", "deductions", "fitness", "running")
                        .zip(listOf(inputs.selfEvaluation, inputs.peerEvaluation, inputs.teacherEvaluation,
                            inputs.deductions, inputs.fitness, inputs.running)).forEach { (column, value) ->
                            if (value == null) putNull(column) else put(column, value)
                        }
                    put("courses_json", courses.toString())
                }, SQLiteDatabase.CONFLICT_REPLACE,
            )
            }
        }
    }

    /** Read-only diagnostics. Invalid old data stays in place until the user explicitly repairs it. */
    fun numericDiagnostics(): List<String> {
        withDataLock {
            val issues = mutableListOf<String>()
            fun checkNumbers(table: String, idColumn: String, columns: List<Pair<String, NumericRule>>, optional: Boolean) {
                database.readableDatabase.rawQuery("SELECT $idColumn, ${columns.joinToString { it.first }} FROM $table", null).use { cursor ->
                    while (cursor.moveToNext()) columns.forEachIndexed { index, (column, rule) ->
                        val value = storedNumber(cursor, index + 1)
                        if (value == null && !optional || value != null && !NumericValues.accepts(value, rule))
                            issues += "$table[${cursor.getString(0)}].$column=${cursor.getString(index + 1) ?: "NULL"}"
                    }
                }
            }
            checkNumbers("score_items", "id", listOf("reference_score" to NumericRule.NON_NEGATIVE), true)
            checkNumbers("history_records", "source_row", listOf("reference_score" to NumericRule.NON_NEGATIVE), false)
            checkNumbers("annual_inputs", "academic_year", listOf("self_score" to NumericRule.PERCENT,
                "peer_score" to NumericRule.PERCENT, "teacher_score" to NumericRule.PERCENT,
                "deductions" to NumericRule.NON_NEGATIVE, "fitness" to NumericRule.PERCENT, "running" to NumericRule.PERCENT), true)
            database.readableDatabase.rawQuery("SELECT academic_year, courses_json FROM annual_inputs", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val year = cursor.getInt(0)
                    val inputs = try { annualInputs(year) }
                    catch (error: IllegalArgumentException) { issues += "annual_inputs[$year]：课程格式异常，原资料已保留，请核对备份"; null }
                    if (inputs != null) {
                        val error = runCatching { NumericDataValidation.annual(inputs) }.exceptionOrNull()
                        if (error != null) issues += "annual_inputs[$year]：${error.message}；请在该学年成绩资料中修正"
                        if (error == null && inputs.courses.isNotEmpty() && cn.activityscore.app.domain.AnnualScoreCalculator.professional(inputs.courses) == null)
                            issues += "annual_inputs[$year].courses_json：课程加权计算溢出；请核对学分"
                    }
                }
            }
            database.readableDatabase.rawQuery("SELECT review_key, scores_json FROM duplicate_reviews", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val error = runCatching {
                        val json = JSONObject(cursor.getString(1))
                        json.keys().asSequence().forEach { id -> NumericValues.requireValid(json.getDouble(id), "参与 $id 确认分", optional = false) }
                    }.exceptionOrNull()
                    if (error != null) issues += "duplicate_reviews[${cursor.getString(0)}]：${error.message}；请重新核对"
                }
            }
            return issues
        }
    }

    private fun storedNumber(cursor: Cursor, index: Int): Double? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_INTEGER -> cursor.getDouble(index)
        // Do not let SQLite's getDouble coercion silently turn malformed text into zero.
        else -> Double.NaN
    }

    fun createItem(
        title: String,
        dimension: String,
        subcategory: String,
        role: String,
        referenceScore: Double?,
        note: String,
        eventDate: String,
        eventNote: String,
        imageUris: List<Uri>,
        shareId: String? = null,
    ): Long {
        withDataLock {
            require(title.isNotBlank()) { "活动名称不能为空" }
            require(dimension in scoreDimensions) { "请选择六个维度之一" }
            NumericDataValidation.reference(referenceScore)
            val writtenFiles = mutableListOf<File>()
            val db = database.writableDatabase
            return evidenceTransaction(db, writtenFiles, { listOf(newRows(it, "score_items"), newRows(it, "participations"), newRows(it, "evidence")) }) {
                val itemId = checkedInsert(db,
                    "score_items",
                    null,
                    ContentValues().apply {
                        put("title", title.trim())
                        put("dimension", dimension)
                        put("subcategory", subcategory.trim())
                        put("role", role.trim())
                        if (referenceScore == null) putNull("reference_score")
                        else put("reference_score", referenceScore)
                        put("note", note.trim())
                        put("created_at", System.currentTimeMillis())
                    },
                )
                val participationId = insertParticipation(db, itemId, eventDate, eventNote, shareId)
                insertEvidence(db, participationId, imageUris, writtenFiles)
                itemId
            }
        }
    }

    fun updateItem(item: ScoreItem) {
        withDataLock {
            require(item.title.isNotBlank()) { "活动名称不能为空" }
            require(item.dimension in scoreDimensions) { "请选择六个维度之一" }
            NumericDataValidation.reference(item.referenceScore, "活动 #${item.id} 参考分数")
            val db = database.writableDatabase
            write(db, { listOf(rowScope("score_items", "id = ?", item.id)) }) {
                requireTarget(db, "score_items", item.id, "活动不存在，可能已被其他操作删除。")
                val values = ContentValues().apply {
                    put("title", item.title.trim())
                    put("dimension", item.dimension)
                    put("subcategory", item.subcategory.trim())
                    put("role", item.role.trim())
                    if (item.referenceScore == null) putNull("reference_score")
                    else put("reference_score", item.referenceScore)
                    put("note", item.note.trim())
                }
                requireOne(db.update("score_items", values, "id = ?", arrayOf(item.id.toString())),
                    "活动更新未生效，请重新读取核对。", true)
                verifyValues(db, "score_items", "id = ?", arrayOf(item.id.toString()), values)
            }
        }
    }

    fun addParticipation(itemId: Long, date: String, note: String, imageUris: List<Uri>) {
        withDataLock {
            val writtenFiles = mutableListOf<File>()
            val db = database.writableDatabase
            evidenceTransaction(db, writtenFiles, { listOf(newRows(it, "participations"), newRows(it, "evidence")) }) {
                requireTarget(db, "score_items", itemId, "活动不存在，可能已被删除。")
                val participationId = insertParticipation(db, itemId, date, note)
                insertEvidence(db, participationId, imageUris, writtenFiles)
            }
        }
    }

    fun updateParticipation(
        participationId: Long, date: String, note: String, imageUris: List<Uri>
    ) {
        withDataLock {
            val writtenFiles = mutableListOf<File>()
            val db = database.writableDatabase
            evidenceTransaction(db, writtenFiles, { listOf(rowScope("participations", "id = ?", participationId),
                rowScope("evidence", "participation_id = ?", participationId)) }) {
                requireTarget(db, "participations", participationId, "参与记录不存在，可能已被删除。")
                val values = ContentValues().apply {
                        put("event_date", date.trim())
                        put("note", note.trim())
                    }
                requireOne(db.update("participations", values, "id = ?", arrayOf(participationId.toString())),
                    "参与更新未生效，请重新读取核对。", true)
                verifyValues(db, "participations", "id = ?", arrayOf(participationId.toString()), values)
                insertEvidence(db, participationId, imageUris, writtenFiles)
            }
        }
    }

    fun addEvidence(participationId: Long, imageUris: List<Uri>) {
        withDataLock {
            val writtenFiles = mutableListOf<File>()
            val db = database.writableDatabase
            evidenceTransaction(db, writtenFiles, { listOf(rowScope("evidence", "participation_id = ?", participationId)) }) {
                requireTarget(db, "participations", participationId, "参与记录不存在，可能已被删除。")
                insertEvidence(db, participationId, imageUris, writtenFiles)
            }
        }
    }

    fun deleteItem(itemId: Long) = withDataLock {
        val db = database.writableDatabase
        val filenames = listParticipations(itemId).flatMap { it.evidence.map(EvidencePhoto::fileName) }
        write(db, { listOf(rowScope("score_items", "id = ?", itemId), rowScope("participations", "item_id = ?", itemId),
            rowScope("evidence", "participation_id IN (SELECT id FROM participations WHERE item_id = ?)", itemId)) }) {
            requireTarget(db, "score_items", itemId, "活动不存在，可能已被删除。")
            requireOne(db.delete("score_items", "id = ?", arrayOf(itemId.toString())), "活动删除未生效，请重新读取核对。", true)
            verifyDeleted(db, "score_items", itemId)
        }
        cleanupCommitted(filenames)
    }

    fun deleteParticipation(participationId: Long) = withDataLock {
        val db = database.writableDatabase
        val filenames = listEvidence(participationId).map(EvidencePhoto::fileName)
        write(db, { listOf(rowScope("participations", "id = ?", participationId), rowScope("evidence", "participation_id = ?", participationId)) }) {
            requireTarget(db, "participations", participationId, "参与记录不存在，可能已被删除。")
            requireOne(db.delete("participations", "id = ?", arrayOf(participationId.toString())), "参与删除未生效，请重新读取核对。", true)
            verifyDeleted(db, "participations", participationId)
        }
        cleanupCommitted(filenames)
    }

    fun deleteEvidence(photo: EvidencePhoto) = withDataLock {
        val db = database.writableDatabase
        write(db, { listOf(rowScope("evidence", "id = ?", photo.id)) }) {
            requireTarget(db, "evidence", photo.id, "证明不存在，可能已被删除。")
            requireOne(db.delete("evidence", "id = ? AND participation_id = ? AND file_name = ?",
                arrayOf(photo.id.toString(), photo.participationId.toString(), photo.fileName)),
                "证明已被修改，请重新检查。", true)
            verifyDeleted(db, "evidence", photo.id)
        }
        cleanupCommitted(listOf(photo.fileName))
    }

    /** Rechecking and all repairs share the backup/restore lock. No file is modified by diagnostics. */
    fun evidenceDiagnostics(year: Int? = null, progress: (Int, Int) -> Unit = { _, _ -> }): List<EvidenceIssue> = withDataLock {
        val locations = evidenceLocations().filter { year == null ||
            cn.activityscore.app.domain.DuplicateReviewFinder.academicYear(it.date) == year }
        locations.mapIndexedNotNull { index, location ->
            progress(index, locations.size)
            EvidenceImages.issue(location, evidenceFile(location.photo.fileName))
        }.also { progress(locations.size, locations.size) }
    }

    fun evidenceLocations(): List<EvidenceLocation> = withDataLock {
        val result = mutableListOf<EvidenceLocation>()
        database.readableDatabase.rawQuery("SELECT i.id, i.title, p.id, p.event_date, e.id, e.file_name " +
            "FROM evidence e JOIN participations p ON e.participation_id=p.id JOIN score_items i ON p.item_id=i.id ORDER BY e.id", null).use { c ->
            while (c.moveToNext()) result += EvidenceLocation(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3),
                EvidencePhoto(c.getLong(4), c.getLong(2), c.getString(5)))
        }
        result
    }

    fun replaceEvidence(photo: EvidencePhoto, uri: Uri) = withDataLock {
        val db = database.writableDatabase
        val written = mutableListOf<File>()
        val location = evidenceLocations().singleOrNull { it.photo == photo }
        if (location == null) throw DataOperationException(WriteState.CONFLICT, "原证明已被修改或删除，请重新检查。")
        evidenceTransaction(db, written, { listOf(rowScope("evidence", "id = ?", photo.id)) }) {
            val file = try { prepareEvidence(uri, written) }
                catch (error: EvidenceInvalid) { throw EvidenceInvalid(error.type, "${location.label}：${error.message}", error) }
            val values = ContentValues().apply { put("file_name", file.name) }
            requireOne(db.update("evidence", values,
                "id = ? AND participation_id = ? AND file_name = ?",
                arrayOf(photo.id.toString(), photo.participationId.toString(), photo.fileName)),
                "原证明已被修改或删除，请重新检查。", true)
            verifyValues(db, "evidence", "id = ?", arrayOf(photo.id.toString()), values)
        }
        cleanupCommitted(listOf(photo.fileName))
    }

    fun evidenceFile(fileName: String): File = File(evidenceDir, fileName)

    private fun insertParticipation(
        db: SQLiteDatabase, itemId: Long, date: String, note: String, shareId: String? = null
    ): Long = checkedInsert(db,
        "participations",
        null,
        ContentValues().apply {
            put("item_id", itemId)
            put("event_date", date.trim())
            put("note", note.trim())
            if (shareId != null) put("share_id", shareId)
        },
    )

    private fun insertEvidence(
        db: SQLiteDatabase,
        participationId: Long,
        imageUris: List<Uri>,
        writtenFiles: MutableList<File>,
    ) {
        imageUris.forEachIndexed { index, uri ->
            val file = try { prepareEvidence(uri, writtenFiles) }
                catch (error: EvidenceInvalid) {
                    val label = db.rawQuery("SELECT i.id,i.title,p.event_date FROM participations p JOIN score_items i ON i.id=p.item_id WHERE p.id=?",
                        arrayOf(participationId.toString())).use { c ->
                        if (c.moveToFirst()) "${c.getString(1)}（活动 ${c.getLong(0)}） / ${c.getString(2)}（参与 $participationId）"
                        else "参与 $participationId"
                    }
                    throw EvidenceInvalid(error.type, "$label / 新证明 ${index + 1}：${error.message}", error)
                }
            checkedInsert(db,
                "evidence",
                null,
                ContentValues().apply {
                    put("participation_id", participationId)
                    put("file_name", file.name)
                },
            )
        }
    }

    private fun prepareEvidence(uri: Uri, written: MutableList<File>): File {
        val temporary = EvidenceImages.stage(context, uri)
        val file = evidenceFile("evi_${UUID.randomUUID()}")
        try {
            written += file
            check(temporary.renameTo(file)) { "无法登记证明文件，请检查可用空间" }
            syncEvidenceDirectory()
            return file
        } finally { if (temporary.exists() && !temporary.delete()) android.util.Log.w("RecordStore", "Evidence staging cleanup deferred") }
    }

    /** SQLite and files have no shared transaction. Unknown outcomes retain every staged file. */
    private fun <T> evidenceTransaction(db: SQLiteDatabase, written: MutableList<File>,
        scopes: (SQLiteDatabase) -> List<WriteScope>, block: () -> T): T {
        try { return write(db, scopes, block) }
        catch (error: Exception) {
            if (error is DataOperationException && error.state == WriteState.UNKNOWN) throw error
            // A confirmed rollback may leave files, but never erase a referenced file.
            written.forEach { runCatching { if (!deleteIfUnreferenced(it)) android.util.Log.w("RecordStore", "Rollback file retained") }.onFailure { failure ->
                android.util.Log.w("RecordStore", "Rollback file cleanup pending", failure)
            } }
            throw error
        }
    }

    private fun requireTarget(db: SQLiteDatabase, table: String, id: Long, message: String) {
        if (!db.rawQuery("SELECT 1 FROM $table WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst() })
            throw DataOperationException(WriteState.NOT_FOUND, message)
    }

    private fun cleanupCommitted(filenames: List<String>) {
        var failed = false
        filenames.distinct().forEach { name ->
            try { if (!deleteIfUnreferenced(evidenceFile(name))) failed = true }
            catch (error: Exception) { failed = true; android.util.Log.w("RecordStore", "Committed file cleanup pending", error) }
        }
        if (failed) throw DataOperationException(WriteState.COMMITTED_CLEANUP_PENDING,
            "数据库操作已完成；部分无引用文件清理失败，文件已保留，不影响其他有效证明。")
    }

    private fun deleteIfUnreferenced(file: File): Boolean {
        checkDatabaseFiles()
        if (DataWrites.hasPending(database.writableDatabase.path)) return false
        // Never follow a legacy path outside the private directory or remove an unknown directory.
        if (!Regex("[A-Za-z0-9_-]{1,120}").matches(file.name) || file.canonicalFile.parentFile != evidenceDir.canonicalFile) return false
        return database.readableDatabase.rawQuery("SELECT 1 FROM evidence WHERE file_name = ? LIMIT 1", arrayOf(file.name)).use {
            if (it.moveToFirst() || !file.exists()) true else file.isFile && file.delete()
        }
    }

    private fun syncEvidenceDirectory() = syncDirectory(evidenceDir)

    private fun syncDirectory(directory: File) {
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }

    private fun cleanEvidenceStaging() {
        // All staging/promotion occurs under DataLock. Only our exact names, never legacy/restore files.
        File(context.filesDir, "evidence-staging").listFiles()?.filter {
            it.isFile && Regex("[a-f0-9-]{36}\\.part").matches(it.name)
        }?.forEach { if (!it.delete()) android.util.Log.w("RecordStore", "Evidence staging file retained") }
        evidenceDir.listFiles()?.filter { it.isFile && Regex("evi_[a-f0-9-]{36}").matches(it.name) }
            ?.forEach { file ->
                runCatching { if (!deleteIfUnreferenced(file)) android.util.Log.w("RecordStore", "Orphan file retained") }
                    .onFailure { android.util.Log.w("RecordStore", "Orphan cleanup deferred", it) }
            }
    }

    private fun listEvidence(participationId: Long): List<EvidencePhoto> {
        val photos = mutableListOf<EvidencePhoto>()
        database.readableDatabase.rawQuery(
            "SELECT id, file_name FROM evidence WHERE participation_id = ? ORDER BY id",
            arrayOf(participationId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                photos += EvidencePhoto(cursor.getLong(0), participationId, cursor.getString(1))
            }
        }
        return photos
    }
}

private class ScoreDatabase(context: Context) : SQLiteOpenHelper(
    context, "activity_score.db", null, 6, preserveCorruptDatabase
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous = FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE score_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, dimension TEXT NOT NULL, " +
                "subcategory TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT '', " +
                "reference_score REAL, note TEXT NOT NULL DEFAULT '', " +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE participations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL, " +
                "event_date TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', share_id TEXT, " +
                "FOREIGN KEY(item_id) REFERENCES score_items(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX participation_item_idx ON participations(item_id)")
        db.execSQL("CREATE UNIQUE INDEX participation_share_idx ON participations(share_id)")
        db.execSQL(
            "CREATE TABLE evidence (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, participation_id INTEGER NOT NULL, " +
                "file_name TEXT NOT NULL, " +
                "FOREIGN KEY(participation_id) REFERENCES participations(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX evidence_participation_idx ON evidence(participation_id)")
        createHistoryTables(db)
        createDuplicateReviewTable(db)
        createAnnualInputsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion > 6 || oldVersion < 1) {
            error("Database migration from $oldVersion to $newVersion is not implemented")
        }
        if (oldVersion < 2 && newVersion >= 2) createHistoryTables(db)
        if (oldVersion in 2..5 && newVersion >= 6)
            db.execSQL("ALTER TABLE history_records ADD COLUMN sources_json TEXT NOT NULL DEFAULT '[]'")
        if (oldVersion < 3 && newVersion >= 3) createDuplicateReviewTable(db)
        if (oldVersion < 4 && newVersion >= 4) createAnnualInputsTable(db)
        if (oldVersion < 5 && newVersion >= 5) {
            db.execSQL("ALTER TABLE participations ADD COLUMN share_id TEXT")
            db.execSQL("CREATE UNIQUE INDEX participation_share_idx ON participations(share_id)")
        }
    }

    private fun createAnnualInputsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE annual_inputs (" +
                "academic_year INTEGER PRIMARY KEY, self_score REAL, peer_score REAL, " +
                "teacher_score REAL, deductions REAL, fitness REAL, running REAL, " +
                "courses_json TEXT NOT NULL DEFAULT '[]')"
        )
    }

    private fun createDuplicateReviewTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE duplicate_reviews (" +
                "review_key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, " +
                "decision TEXT NOT NULL, scores_json TEXT NOT NULL DEFAULT '{}')"
        )
    }

    private fun createHistoryTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history_records (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, source_row INTEGER NOT NULL, " +
                "activity_name TEXT NOT NULL, dimension TEXT NOT NULL, " +
                "subcategory TEXT NOT NULL, role TEXT NOT NULL, " +
                "reference_score REAL NOT NULL, sources_json TEXT NOT NULL DEFAULT '[]')"
        )
        db.execSQL("CREATE INDEX history_name_idx ON history_records(activity_name)")
        db.execSQL(
            "CREATE TABLE history_info (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), file_name TEXT NOT NULL, " +
                "imported_at INTEGER NOT NULL, row_count INTEGER NOT NULL)"
        )
    }
}
