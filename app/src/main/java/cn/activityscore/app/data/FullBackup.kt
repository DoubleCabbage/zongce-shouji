package cn.activityscore.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** All in-process database, evidence and mapping operations share this monitor. */
internal object DataLock { val monitor = Any() }

class BackupCancellation {
    @Volatile var cancelled = false
    fun check() { check(!cancelled) { "操作已取消，当前数据未更改" } }
}

class VerifiedBackup internal constructor(internal val directory: File, internal val data: JSONObject, internal val manifest: JSONObject) : Closeable {
    val summary: String get() {
        val tables = data.getJSONObject("tables")
        fun count(name: String) = tables.getJSONArray(name).length()
        val years = tables.getJSONArray("annual_inputs")
        return "活动 ${count("score_items")} 项 · 参与 ${count("participations")} 次\n" +
            "证明 ${count("evidence")} 张 · 人工核对 ${count("duplicate_reviews")} 条\n" +
            "历史库 ${count("history_records")} 条 · 导入信息 ${count("history_info")} 条\n" +
            "年度资料 ${years.length()} 个学年" +
            (if (years.length() == 0) "" else "（" + (0 until years.length()).joinToString { years.getJSONArray(it).getString(0) } + "）") +
            "\n包含历史表列映射偏好"
    }
    override fun close() { directory.deleteRecursively() }
}

/** Format 1 supports schema 5 and 6; No SQLite file supplied by an external party is opened. */
object FullBackup {
    const val FORMAT_VERSION = 1
    const val SCHEMA_VERSION = 6
    private const val MAX_ARCHIVE = 512L * 1024 * 1024
    private const val MAX_TOTAL = 512L * 1024 * 1024
    private const val MAX_PHOTO = 32L * 1024 * 1024
    private const val MAX_DATA = 16L * 1024 * 1024
    private const val MAX_MANIFEST = 2L * 1024 * 1024
    private const val MAX_ENTRIES = 10002
    private const val MAX_ROWS = 100000
    private const val PREFS = "history_mapping_profiles"
    private val safeName = Regex("[A-Za-z0-9_-]{1,120}")
    // S=required string, s=nullable string, I=integer, R=required double, r=nullable double.
    internal val schema = linkedMapOf(
        "score_items" to (listOf("id", "title", "dimension", "subcategory", "role", "reference_score", "note", "created_at") to "ISSSSrSI"),
        "participations" to (listOf("id", "item_id", "event_date", "note", "share_id") to "IISSs"),
        "evidence" to (listOf("id", "participation_id", "file_name") to "IIS"),
        "history_records" to (listOf("id", "source_row", "activity_name", "dimension", "subcategory", "role", "reference_score", "sources_json") to "IISSSSRS"),
        "history_info" to (listOf("id", "file_name", "imported_at", "row_count") to "ISII"),
        "duplicate_reviews" to (listOf("review_key", "fingerprint", "decision", "scores_json") to "SSSS"),
        "annual_inputs" to (listOf("academic_year", "self_score", "peer_score", "teacher_score", "deductions", "fitness", "running", "courses_json") to "IrrrrrrS"),
    )

    private fun keys(json: JSONObject) = json.keys().asSequence().toSet()
    private fun exact(json: JSONObject, fields: Set<String>) { require(keys(json) == fields) { "备份字段不完整或不支持" } }
    private fun strictString(json: JSONObject, key: String): String = (json.get(key) as? String) ?: error("字段 $key 类型错误")
    private fun strictLong(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long) { "字段 $key 类型错误" }
        return (value as Number).toLong()
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
            val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun copyLimited(input: InputStream, file: File, limit: Long, cancel: BackupCancellation) {
        FileOutputStream(file).use { output ->
            val buffer = ByteArray(8192); var total = 0L
            while (true) {
                cancel.check(); val n = input.read(buffer); if (n < 0) break
                total += n; require(total <= limit) { "备份超过安全大小限制" }; output.write(buffer, 0, n)
            }
            output.fd.sync()
        }
    }
    private fun staging(context: Context): File = File(context.cacheDir, "full-backup").also { it.mkdirs() }
    private fun newStage(context: Context) = File(staging(context), UUID.randomUUID().toString()).also { check(it.mkdir()) }

    internal fun snapshot(context: Context, db: SQLiteDatabase): JSONObject {
        check(db.version == SCHEMA_VERSION)
        val tables = JSONObject()
        schema.forEach { (table, definition) ->
            val rows = JSONArray()
            db.rawQuery("SELECT ${definition.first.joinToString()} FROM $table ORDER BY ${definition.first.first()}", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONArray()
                    definition.second.forEachIndexed { index, type ->
                        row.put(when {
                            cursor.isNull(index) -> JSONObject.NULL
                            type == 'I' -> { require(cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER); cursor.getLong(index).toString() }
                            type == 'R' || type == 'r' -> {
                                require(cursor.getType(index) in listOf(Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT))
                                java.lang.Double.toHexString(cursor.getDouble(index))
                            }
                            else -> { require(cursor.getType(index) == Cursor.FIELD_TYPE_STRING); cursor.getString(index) }
                        })
                    }
                    rows.put(row)
                }
            }
            tables.put(table, rows)
        }
        val sequences = JSONObject()
        db.rawQuery("SELECT name, seq FROM sqlite_sequence ORDER BY name", null).use { cursor ->
            while (cursor.moveToNext()) sequences.put(cursor.getString(0), cursor.getLong(1).toString())
        }
        val prefs = JSONObject()
        // This is the only durable preference in v5. Do not silently omit a newly introduced key.
        val all = context.getSharedPreferences(PREFS, 0).all
        require(all.keys.all { it == "profiles" }) { "发现未支持的持久化配置，不能生成不完整备份" }
        if (all.containsKey("profiles")) prefs.put("profiles", all["profiles"] as? String ?: error("列映射配置类型错误"))
        return JSONObject().put("tables", tables).put("sequences", sequences).put("preferences", prefs)
    }

    /** Produces and independently verifies a finished local ZIP before touching SAF. */
    fun capture(context: Context, store: RecordStore, cancel: BackupCancellation = BackupCancellation(), progress: (String) -> Unit = {}): File {
        val stage = newStage(context)
        val archive = File(stage, "backup.zip")
        try {
            store.withBackupDatabase { db ->
                progress("正在捕获七表、配置和证明图片…")
                db.beginTransaction()
                try {
                    cancel.check()
                    val issues = store.evidenceDiagnostics(progress = { current, total ->
                        cancel.check(); progress("正在检查证明 $current / $total…")
                    })
                    if (issues.isNotEmpty()) throw EvidenceProblems(issues)
                    val data = snapshot(context, db); validateData(data)
                    val dataFile = File(stage, "data.json").also { it.writeText(data.toString()) }
                    require(dataFile.length() <= MAX_DATA)
                    val files = JSONArray().put(descriptor("data.json", dataFile))
                    val photos = data.getJSONObject("tables").getJSONArray("evidence")
                    val locations = store.evidenceLocations().associateBy { it.photo.id }
                    val copied = linkedMapOf("data.json" to dataFile)
                    var total = dataFile.length()
                    for (index in 0 until photos.length()) {
                        cancel.check(); val name = photos.getJSONArray(index).getString(2)
                        val path = "attachments/$name"
                        if (copied.containsKey(path)) continue
                        val source = store.evidenceFile(name)
                        val location = locations.getValue(photos.getJSONArray(index).getString(0).toLong())
                        val target = File(stage, "photo-$index")
                        try {
                            source.inputStream().use { copyLimited(it, target, MAX_PHOTO, cancel) }
                            EvidenceImages.issue(location, target)?.let { throw EvidenceProblems(listOf(it)) }
                        } catch (error: Exception) {
                            cancel.check()
                            if (error is EvidenceProblems) throw error
                            android.util.Log.w("FullBackup", "Evidence snapshot copy failed", error)
                            throw EvidenceProblems(listOf(EvidenceIssue(location, (error as? EvidenceInvalid)?.type ?: EvidenceError.UNREADABLE,
                                "无法复制完整备份证明，请检查可用空间和读取权限")))
                        }
                        total += target.length(); require(total <= MAX_TOTAL)
                        copied[path] = target; files.put(descriptor(path, target))
                    }
                    require(copied.size + 1 <= MAX_ENTRIES)
                    val manifest = JSONObject().put("format", "activity-score-full-backup")
                        .put("formatVersion", FORMAT_VERSION).put("schemaVersion", SCHEMA_VERSION)
                        .put("createdAt", System.currentTimeMillis()).put("files", files).toString().toByteArray()
                    require(manifest.size <= MAX_MANIFEST)
                    ZipOutputStream(FileOutputStream(archive)).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
                        copied.forEach { (path, file) -> cancel.check(); zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                    }
                    require(archive.length() <= MAX_ARCHIVE)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            progress("正在自检备份完整性…")
            archive.inputStream().use { verify(context, it, cancel).close() }
            return archive
        } catch (error: Throwable) { stage.deleteRecursively(); throw error }
    }
    fun discard(file: File) { file.parentFile?.deleteRecursively() }
    fun save(context: Context, file: File, uri: Uri, cancel: BackupCancellation = BackupCancellation()) {
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入保存位置" }.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { cancel.check(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                    output.flush()
                }
            }
            // A provider's successful close alone does not prove a complete document.
            verify(context, uri, cancel).use { verified ->
                require(hash(File(verified.directory, "input.zip")) == hash(file)) { "保存后的备份与原文件不一致" }
            }
        } catch (error: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw error
        }
    }
    private fun descriptor(path: String, file: File) = JSONObject().put("path", path).put("size", file.length()).put("sha256", hash(file))

    fun verify(context: Context, uri: Uri, cancel: BackupCancellation = BackupCancellation(), progress: (String) -> Unit = {}): VerifiedBackup =
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取备份文件" }.use { verify(context, it, cancel, progress) }

    fun verify(context: Context, input: InputStream, cancel: BackupCancellation = BackupCancellation(), progress: (String) -> Unit = {}): VerifiedBackup {
        val stage = newStage(context)
        try {
            progress("正在读取备份文件…")
            val zipFile = File(stage, "input.zip"); copyLimited(input, zipFile, MAX_ARCHIVE, cancel)
            ZipFile(zipFile).use { zip ->
                val entries = mutableListOf<ZipEntry>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) { require(entries.size < MAX_ENTRIES); entries += enumeration.nextElement() }
                require(entries.size in 2..MAX_ENTRIES && entries.map { it.name }.distinct().size == entries.size) { "ZIP 条目重复或数量非法" }
                entries.forEach { entry ->
                    require(!entry.isDirectory && (entry.name in setOf("manifest.json", "data.json") ||
                        entry.name.startsWith("attachments/") && safeName.matches(entry.name.removePrefix("attachments/")))) { "非法备份路径" }
                    require(entry.size >= 0 && entry.size <= if (entry.name == "manifest.json") MAX_MANIFEST else if (entry.name == "data.json") MAX_DATA else MAX_PHOTO)
                }
                val manifestFile = File(stage, "manifest.json")
                zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).use { copyLimited(it, manifestFile, MAX_MANIFEST, cancel) }
                val manifest = JSONObject(manifestFile.readText())
                exact(manifest, setOf("format", "formatVersion", "schemaVersion", "createdAt", "files"))
                require(strictString(manifest, "format") == "activity-score-full-backup") { "不是完整数据备份" }
                require(strictLong(manifest, "formatVersion") == FORMAT_VERSION.toLong() && strictLong(manifest, "schemaVersion") in 5L..SCHEMA_VERSION.toLong()) { "不支持此备份版本；支持格式 v1 / 数据库 v5–v6" }
                require(strictLong(manifest, "createdAt") >= 0)
                val files = manifest.getJSONArray("files"); val declared = mutableSetOf<String>(); var total = manifestFile.length()
                require(files.length() == entries.size - 1)
                progress("正在校验文件大小、SHA-256 和附件…")
                for (index in 0 until files.length()) {
                    cancel.check(); val file = files.getJSONObject(index); exact(file, setOf("path", "size", "sha256"))
                    val path = strictString(file, "path"); require(path != "manifest.json" && declared.add(path))
                    val entry = requireNotNull(zip.getEntry(path)); val size = strictLong(file, "size")
                    require(size == entry.size && size >= 0)
                    total += size; require(total <= MAX_TOTAL) { "解压总量超过 512 MB" }
                    val expected = strictString(file, "sha256"); require(Regex("[0-9a-f]{64}").matches(expected))
                    val target = File(stage, path); target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { copyLimited(it, target, size, cancel) }
                    require(target.length() == size && hash(target) == expected) { "文件大小或 SHA-256 校验失败：$path" }
                }
                require(declared + "manifest.json" == entries.map { it.name }.toSet())
                val data = JSONObject(File(stage, "data.json").readText()); validateData(data, manifest.getInt("schemaVersion"))
                val rows = data.getJSONObject("tables").getJSONArray("evidence")
                val expected = (0 until rows.length()).map { "attachments/${rows.getJSONArray(it).getString(2)}" }.toSet() + "data.json"
                require(declared == expected) { "证明图片缺失或包含未关联附件" }
                val tables = data.getJSONObject("tables")
                val items = tables.getJSONArray("score_items")
                val visits = tables.getJSONArray("participations")
                val itemRows = (0 until items.length()).associate { items.getJSONArray(it).let { row -> row.getString(0) to row } }
                val visitRows = (0 until visits.length()).associate { visits.getJSONArray(it).let { row -> row.getString(0) to row } }
                val issues = (0 until rows.length()).mapNotNull { index ->
                    cancel.check(); progress("正在解码检查备份证明 ${index + 1} / ${rows.length()}…")
                    val row = rows.getJSONArray(index); val visit = visitRows.getValue(row.getString(1))
                    val item = itemRows.getValue(visit.getString(1))
                    val photo = EvidencePhoto(row.getString(0).toLong(), row.getString(1).toLong(), row.getString(2))
                    EvidenceImages.issue(EvidenceLocation(item.getString(0).toLong(), item.getString(1),
                        photo.participationId, visit.getString(2), photo), File(stage, "attachments/${photo.fileName}"))
                }
                if (issues.isNotEmpty()) throw EvidenceProblems(issues)
                cancel.check(); progress("备份验证通过")
                return VerifiedBackup(stage, data, manifest)
            }
        } catch (error: Throwable) { stage.deleteRecursively(); throw error }
    }

    internal fun validateData(data: JSONObject, version: Int = SCHEMA_VERSION) {
        require(version in 5..SCHEMA_VERSION)
        val dataSchema = if (version == 5) schema.mapValues { (table, definition) ->
            if (table == "history_records") definition.first.dropLast(1) to definition.second.dropLast(1) else definition
        } else schema
        exact(data, setOf("tables", "sequences", "preferences"))
        val tables = data.getJSONObject("tables"); exact(tables, schema.keys)
        val ids = mutableMapOf<String, Set<String>>()
        dataSchema.forEach { (table, definition) ->
            val rows = tables.getJSONArray(table); require(rows.length() <= MAX_ROWS)
            val primaryKeys = mutableSetOf<String>()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index); require(row.length() == definition.second.length)
                definition.second.forEachIndexed { column, type ->
                    if (row.isNull(column)) require(type == 'r' || type == 's') else {
                        val raw = row.get(column); require(raw is String) { "$table 字段类型非法" }
                        when (type) {
                            'I' -> require(raw.toLongOrNull()?.toString() == raw) { "整数非法" }
                            'R', 'r' -> { val number = java.lang.Double.valueOf(raw); require(number.isFinite() && java.lang.Double.toHexString(number) == raw) { "Double 非法" } }
                            else -> require(raw.length <= 1024 * 1024)
                        }
                    }
                }
                require(primaryKeys.add(row.getString(0))) { "主键重复" }
                if (table != "duplicate_reviews" && table != "annual_inputs") require(row.getString(0).toLong() > 0)
            }
            ids[table] = primaryKeys
        }
        fun rows(table: String, action: (JSONArray) -> Unit) { val rows = tables.getJSONArray(table); for (i in 0 until rows.length()) action(rows.getJSONArray(i)) }
        fun number(row: JSONArray, i: Int): Double? = if (row.isNull(i)) null else java.lang.Double.valueOf(row.getString(i))
        rows("score_items") { require(it.getString(1).isNotBlank() && it.getString(2) in scoreDimensions); NumericDataValidation.reference(number(it, 5)); require(it.getString(7).toLong() >= 0) }
        val shareIds = mutableSetOf<String>()
        rows("participations") { require(it.getString(1) in ids.getValue("score_items")) { "参与的活动不存在" }; LocalDate.parse(it.getString(2)); if (!it.isNull(4)) require(shareIds.add(it.getString(4))) }
        rows("evidence") { require(it.getString(1) in ids.getValue("participations")) { "图片的参与记录不存在" }; require(safeName.matches(it.getString(2))) { "图片文件名非法" } }
        rows("history_records") { require(it.getString(1).toLong() in 1..Int.MAX_VALUE && it.getString(2).isNotBlank() && it.getString(3).isNotBlank()); NumericValues.requireValid(number(it, 6), "历史分数", optional = false); if (version >= 6) HistorySourcesJson.decode(it.getString(7)) }
        val historyCount = tables.getJSONArray("history_records").length()
        val historyInfo = tables.getJSONArray("history_info")
        require(historyInfo.length() <= 1 && (historyCount == 0 || historyInfo.length() == 1))
        rows("history_info") { require(it.getString(0) == "1" && it.getString(2).toLong() >= 0 && it.getString(3).toLong() == historyCount.toLong()) }
        rows("duplicate_reviews") {
            require(it.getString(0).isNotBlank() && it.getString(1).isNotBlank())
            cn.activityscore.app.domain.DuplicateDecision.valueOf(it.getString(2))
            val scores = JSONObject(it.getString(3))
            keys(scores).forEach { id ->
                require(id.toLongOrNull()?.let { value -> value > 0 && value.toString() == id } == true)
                val score = scores.get(id); require(score is Number)
                NumericValues.requireValid(score.toDouble(), "逐次确认分", optional = false)
            }
            // Stale reviews are valid persisted records: the app intentionally uses fingerprints to re-open them.
        }
        rows("annual_inputs") {
            require(it.getString(0).toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            val courses = JSONArray(it.getString(7))
            require(courses.length() <= MAX_ROWS)
            val parsed = (0 until courses.length()).map { index ->
                val course = courses.getJSONObject(index); exact(course, setOf("name", "credits", "grade"))
                val credits = course.get("credits"); val grade = course.get("grade"); require(credits is Number && grade is Number)
                cn.activityscore.app.domain.CourseGrade(strictString(course, "name"), credits.toDouble(), grade.toDouble())
            }
            NumericDataValidation.annual(cn.activityscore.app.domain.AnnualInputs(it.getString(0).toInt(), number(it, 1), number(it, 2), number(it, 3), number(it, 4), number(it, 5), number(it, 6), parsed))
        }
        val sequences = data.getJSONObject("sequences")
        val autoTables = setOf("score_items", "participations", "evidence", "history_records")
        require(keys(sequences).all { it in autoTables })
        autoTables.forEach { table ->
            val maximum = ids.getValue(table).maxOfOrNull { it.toLong() } ?: 0
            if (sequences.has(table)) { val value = strictString(sequences, table); require(value.toLongOrNull()?.let { it >= maximum && it >= 0 && it.toString() == value } == true) }
            else require(maximum == 0L)
        }
        val prefs = data.getJSONObject("preferences"); require(keys(prefs).all { it == "profiles" })
        if (prefs.has("profiles")) {
            val profiles = JSONArray(strictString(prefs, "profiles")); require(profiles.length() <= 20)
            for (i in 0 until profiles.length()) {
                val profile = profiles.getJSONObject(i); exact(profile, setOf("columns"))
                val columns = profile.getJSONObject("columns"); exact(columns, setOf("activity", "dimension", "score", "role", "subcategory"))
                keys(columns).forEach { key -> strictString(columns, key) }
            }
        }
    }

    private fun journal(context: Context) = AtomicFile(File(context.filesDir, "full-restore-journal.json"))
    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    private fun writeJournal(context: Context, data: JSONObject) {
        val atomic = journal(context); val stream = atomic.startWrite()
        try { stream.write(data.toString().toByteArray()); atomic.finishWrite(stream); syncDirectory(context.filesDir) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun marker(db: SQLiteDatabase) = db.rawQuery("PRAGMA application_id", null).use { it.moveToFirst(); it.getInt(0) }
    private fun applyPrefs(context: Context, prefs: JSONObject) {
        val editor = context.getSharedPreferences(PREFS, 0).edit().clear()
        if (prefs.has("profiles")) editor.putString("profiles", prefs.getString("profiles"))
        check(editor.commit()) { "无法持久化列映射；恢复日志已保留，将在重启时补完" }
    }
    /** Called before RecordStore exposes any data. Commit marker determines roll-forward vs rollback. */
    internal fun recover(context: Context, db: SQLiteDatabase) {
        val atomic = journal(context)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return
        val log = atomic.openRead().use { JSONObject(it.bufferedReader().readText()) }
        val committed = marker(db) == log.getInt("token")
        if (committed) {
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "已提交数据关联异常；恢复日志和原证明保留" } }
            val files = log.getJSONArray("fileChecks")
            for (index in 0 until files.length()) {
                val expected = files.getJSONObject(index); val name = expected.getString("path")
                require(safeName.matches(name))
                val file = File(context.filesDir, "evidence/$name")
                check(file.isFile && file.length() == expected.getLong("size") && runCatching { hash(file) }.getOrNull() == expected.getString("sha256")) {
                    val locations = mutableListOf<String>()
                    db.rawQuery("SELECT i.id,i.title,p.id,p.event_date,e.id FROM evidence e JOIN participations p ON p.id=e.participation_id JOIN score_items i ON i.id=p.item_id WHERE e.file_name=?",
                        arrayOf(name)).use { c -> while(c.moveToNext()) locations += "${c.getString(1)}（活动 ${c.getLong(0)}） / ${c.getString(3)}（参与 ${c.getLong(2)}） / 证明 ${c.getLong(4)}" }
                    "恢复图片完整性异常：${locations.joinToString().ifBlank { name }}；恢复日志和原证明保留。此状态须用原备份的对应图片字节修复后重开，不能直接改为任意新图。"
                }
            }
            applyPrefs(context, log.getJSONObject("preferences"))
        }
        // Only delete files recorded by our own durable journal and never a currently referenced file.
        val referenced = mutableSetOf<String>()
        db.rawQuery("SELECT file_name FROM evidence", null).use { while (it.moveToNext()) referenced += it.getString(0) }
        val cleanup = log.getJSONArray(if (committed) "oldFiles" else "newFiles")
        for (index in 0 until cleanup.length()) {
            val name = cleanup.getString(index); require(safeName.matches(name))
            if (name !in referenced) {
                val file = File(context.filesDir, "evidence/$name")
                if (file.exists() && !file.delete()) android.util.Log.w("FullBackup", "Restore cleanup deferred; unreferenced file retained")
            }
        }
        atomic.delete()
        syncDirectory(context.filesDir)
    }

    /** fault is a test seam; production uses no hook. All ordinary precommit failures preserve old data. */
    fun restore(context: Context, store: RecordStore, backup: VerifiedBackup, progress: (String) -> Unit = {}, fault: (String) -> Unit = {}) {
        store.withBackupDatabase { db ->
            validateData(backup.data, backup.manifest.getInt("schemaVersion"))
            // Recheck staged bytes before writing the journal or modifying any current data.
            val files = backup.manifest.getJSONArray("files")
            for (i in 0 until files.length()) {
                val descriptor = files.getJSONObject(i); val file = File(backup.directory, descriptor.getString("path"))
                require(file.isFile && file.length() == descriptor.getLong("size") && hash(file) == descriptor.getString("sha256")) { "已验证备份的暂存文件发生变化" }
            }
            require(File(backup.directory, "data.json").readText() == backup.data.toString()) { "已验证数据发生变化" }
            val data = JSONObject(backup.data.toString())
            if (backup.manifest.getInt("schemaVersion") == 5) {
                val history = data.getJSONObject("tables").getJSONArray("history_records")
                for (i in 0 until history.length()) history.getJSONArray(i).put("[]")
            }
            val evidence = data.getJSONObject("tables").getJSONArray("evidence")
            val names = linkedMapOf<String, String>()
            for (i in 0 until evidence.length()) { val row = evidence.getJSONArray(i); val old = row.getString(2); row.put(2, names.getOrPut(old) { "restore_" + UUID.randomUUID().toString() }) }
            val oldFiles = JSONArray()
            db.rawQuery("SELECT DISTINCT file_name FROM evidence", null).use { while (it.moveToNext()) oldFiles.put(it.getString(0)) }
            var token: Int; do { token = UUID.randomUUID().hashCode() and Int.MAX_VALUE } while (token == 0 || token == marker(db))
            val log = JSONObject().put("token", token).put("preferences", data.getJSONObject("preferences"))
                .put("newFiles", JSONArray(names.values.toList())).put("oldFiles", oldFiles)
                .put("fileChecks", JSONArray().apply {
                    names.forEach { (old, new) -> put(descriptor(new, File(backup.directory, "attachments/$old"))) }
                })
            writeJournal(context, log) // Must precede every promoted file.
            var committed = false
            try {
                progress("正在准备恢复图片（保留原证明）…")
                names.forEach { (old, new) ->
                    val source = File(backup.directory, "attachments/$old"); require(source.isFile)
                    val target = store.evidenceFile(new); require(!target.exists())
                    source.inputStream().use { copyLimited(it, target, MAX_PHOTO, BackupCancellation()) }
                    require(source.length() == target.length() && hash(source) == hash(target))
                }
                syncDirectory(File(context.filesDir, "evidence"))
                fault("FILES_READY")
                progress("正在事务内替换并复核七张表…")
                db.beginTransaction()
                try {
                    schema.keys.reversed().forEach { db.delete(it, null, null) }
                    schema.forEach { (table, definition) ->
                        val rows = data.getJSONObject("tables").getJSONArray(table)
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONArray(i); val values = ContentValues()
                            definition.second.forEachIndexed { column, type ->
                                val name = definition.first[column]
                                if (row.isNull(column)) values.putNull(name) else when (type) {
                                    'I' -> values.put(name, row.getString(column).toLong())
                                    'R', 'r' -> values.put(name, java.lang.Double.valueOf(row.getString(column)))
                                    else -> values.put(name, row.getString(column))
                                }
                            }
                            db.insertOrThrow(table, null, values)
                        }
                    }
                    db.delete("sqlite_sequence", null, null)
                    val seq = data.getJSONObject("sequences")
                    keys(seq).forEach { db.insertOrThrow("sqlite_sequence", null, ContentValues().apply { put("name", it); put("seq", seq.getString(it).toLong()) }) }
                    db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
                    check(equivalentTables(snapshot(context, db), data)) { "恢复后字段或数量不一致" }
                    db.execSQL("PRAGMA application_id = $token")
                    fault("BEFORE_COMMIT")
                    db.setTransactionSuccessful()
                } finally {
                    try { db.endTransaction() }
                    catch (endError: Exception) {
                        // Native COMMIT failure may leave uncommitted token visible on this handle.
                        // Closing releases that transaction; only a separate connection may confirm it.
                        runCatching { store.retireFailedDatabase() }.onFailure { endError.addSuppressed(it) }
                        throw endError
                    }
                }
                committed = true
                fault("AFTER_COMMIT")
                progress("正在完成列映射配置和启动恢复日志…")
                recover(context, db)
                check(equivalentTables(snapshot(context, db), data))
                check(snapshot(context, db).getJSONObject("preferences").toString() == data.getJSONObject("preferences").toString())
            } catch (error: Exception) {
                // endTransaction can fail after the underlying COMMIT; the durable marker wins over a local flag.
                val durableCommit = runCatching {
                    SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY, preserveCorruptDatabase)
                        .use { marker(it) == token }
                }.getOrNull()
                if (!committed && durableCommit == false) {
                    store.withBackupDatabase { reopened -> recover(context, reopened) }
                    throw error
                }
                if (!committed && durableCommit == null) {
                    throw IllegalStateException("无法确认提交状态；恢复日志和原证明已保留，请重新打开 App 检查。${error.message}", error)
                }
                // SQLite is already committed. Never advertise a rollback that has not happened.
                throw IllegalStateException("数据已提交；恢复收尾未完成，请重新打开 App 自动补完。${error.message}", error)
            }
        }
    }
    private fun equivalentTables(a: JSONObject, b: JSONObject): Boolean =
        schema.keys.all { table ->
            fun records(data: JSONObject): Map<String, String> {
                val rows = data.getJSONObject("tables").getJSONArray(table)
                return (0 until rows.length()).associate { rows.getJSONArray(it).getString(0) to rows.getJSONArray(it).toString() }
            }
            records(a) == records(b)
        } &&
            keys(a.getJSONObject("sequences")) == keys(b.getJSONObject("sequences")) &&
            keys(a.getJSONObject("sequences")).all { a.getJSONObject("sequences").getString(it) == b.getJSONObject("sequences").getString(it) }

    /** Cache is never authoritative; discard abandoned staging only when the app starts. */
    private var stagingCleaned = false
    fun cleanAbandonedStaging(context: Context) = synchronized(DataLock.monitor) {
        // Activity recreation must not delete staging owned by an operation still running in this process.
        if (!stagingCleaned) { staging(context).listFiles()?.forEach { it.deleteRecursively() }; stagingCleaned = true }
    }
}
