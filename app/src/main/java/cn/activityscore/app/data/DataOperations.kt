package cn.activityscore.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.security.MessageDigest

enum class WriteState { SUCCESS, FAILED, NOT_FOUND, CONFLICT, UNKNOWN, COMMITTED_CLEANUP_PENDING }

/** Success is a normal return, including an independently verified commit after an exception. */
class DataOperationException(val state: WriteState, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class DataReadException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal val preserveCorruptDatabase = DatabaseErrorHandler {
    // Android's default handler may delete a corrupt database. Keep user data for diagnosis/recovery.
    Log.e("RecordStore", "Database corruption detected; original database retained")
}

fun dataErrorMessage(error: Exception): String {
    Log.e("RecordStore", "Data operation failed", error)
    return when (error) {
        is DataOperationException -> error.message.orEmpty()
        is DataReadException -> error.message.orEmpty()
        is EvidenceProblems -> error.message.orEmpty()
        is EvidenceInvalid -> error.message ?: "证明无法保存，请检查图片内容、读取权限和可用空间。"
        is android.database.SQLException -> "数据库暂时无法访问，请检查可用空间后重新读取；请勿清空或初始化数据。"
        is IllegalArgumentException -> "输入未通过校验，请核对填写内容。"
        else -> when {
            error.message?.startsWith("数据已提交") == true -> "数据已提交；恢复收尾未完成，请重新打开 App 自动核对。"
            error.message?.startsWith("无法确认提交状态") == true -> "恢复提交状态尚未确认；恢复日志和原证明保留，请重新打开 App 核对，避免重复恢复。"
            else -> "操作未完成，请重新读取核对；当前输入会保留，请勿重复提交。"
        }
    }
}

fun readErrorMessage(error: Throwable?): String {
    if (error != null) Log.e("RecordStore", "Complete read failed", error)
    return when(error) {
        is android.database.sqlite.SQLiteDatabaseCorruptException -> "数据库完整性异常，原文件已保留。请保留原数据库和有效备份；请勿清空或初始化。"
        is org.json.JSONException, is IllegalArgumentException -> "已存资料的格式异常，尚未完整读取。请保留原数据库与备份，勿重新保存覆盖资料。"
        is DataOperationException -> error.message.orEmpty()
        is DataReadException -> error.message.orEmpty()
        else -> "数据库读取或恢复收尾失败。请检查可用空间后重试；请勿清空或初始化数据。"
    }
}

internal data class WriteScope(val sql: String, val args: Array<String>? = null)
internal fun rowScope(table: String, where: String, vararg args: Any) =
    WriteScope("SELECT * FROM $table WHERE $where ORDER BY 1", args.map { it.toString() }.toTypedArray())
internal fun newRows(db: SQLiteDatabase, table: String): WriteScope {
    val last = db.rawQuery("SELECT COALESCE(MAX(id),0) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
    return rowScope(table, "id > ?", last)
}

/** Receipts contain hashes of affected rows only, never backup tokens, files or persistent tasks. */
internal object DataWrites {
    private data class Receipt(val scopes: List<WriteScope>, val before: List<String>, val after: List<String>?, val commitKnown: Boolean)
    private val pending = mutableMapOf<String, Receipt>() // accessed only with DataLock held
    private val blocked = mutableSetOf<String>()
    private val validations = mutableMapOf<String, MutableList<() -> Unit>>()
    fun hasPending(path: String) = path in blocked
    fun verifyAtEnd(path: String, check: () -> Unit) { validations[path]?.add(check) }

    private fun snapshot(db: SQLiteDatabase, scopes: List<WriteScope>): List<String> = scopes.map { scope ->
        val hash = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); hash.update("${bytes.size}:".toByteArray()); hash.update(bytes) }
        db.rawQuery(scope.sql, scope.args).use { c ->
            while (c.moveToNext()) {
                add("row")
                repeat(c.columnCount) { index ->
                    val type = c.getType(index); add(type.toString())
                    add(when(type) {
                        Cursor.FIELD_TYPE_NULL -> ""
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index).toString()
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toRawBits().toString()
                        Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(c.getBlob(index), android.util.Base64.NO_WRAP)
                        else -> c.getString(index)
                    })
                }
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun durable(path: String, scopes: List<WriteScope>): List<String> =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY, preserveCorruptDatabase).use { db ->
            // One read transaction prevents a receipt from mixing different committed states.
            db.rawQuery("BEGIN", null).use { it.moveToFirst() }
            try { snapshot(db, scopes) } finally { db.execSQL("ROLLBACK") }
        }

    fun recheck(path: String): WriteState? {
        if (!hasPending(path)) return null
        val receipt = pending[path] ?: return WriteState.UNKNOWN
        val actual = try { durable(path, receipt.scopes) } catch (_: Exception) { return WriteState.UNKNOWN }
        val state = when {
            receipt.after != null && actual == receipt.after && (receipt.after != receipt.before || receipt.commitKnown) -> WriteState.SUCCESS
            actual == receipt.before && !receipt.commitKnown && (receipt.after == null || receipt.after != receipt.before) -> WriteState.FAILED
            else -> WriteState.UNKNOWN
        }
        if (state != WriteState.UNKNOWN) { pending.remove(path); blocked.remove(path) }
        return state
    }

    /** fault is an instrumentation seam. AFTER_END simulates reporting failure after a real commit,
     * not a native COMMIT I/O failure. BEFORE_VERIFY only runs on the exceptional confirmation path. */
    fun <T> transaction(db: SQLiteDatabase, scopes: (SQLiteDatabase) -> List<WriteScope>,
        fault: (String) -> Unit = {}, retire: () -> Unit, block: () -> T): T {
        if (hasPending(db.path)) throw unknown()
        var started = false
        var before: List<String>? = null
        var after: List<String>? = null
        var selected = emptyList<WriteScope>()
        var value: T? = null
        var completed = false
        var failure: Exception? = null
        var retired = false
        var commitRequested = false
        var commitMarked = false
        var endReturned = false
        try {
            fault("BEFORE_BEGIN")
            check(!db.inTransaction()) { "Business writes require their own top-level transaction" }
            db.beginTransaction(); started = true
            validations[db.path] = mutableListOf()
            selected = scopes(db); before = snapshot(db, selected)
            value = block()
            validations.remove(db.path)?.forEach { it() }
            completed = true
            after = snapshot(db, selected)
            fault("BEFORE_COMMIT")
            // Invocation itself can throw after changing internal transaction state.
            commitRequested = true
            db.setTransactionSuccessful()
            commitMarked = true
        } catch (error: Exception) { failure = error }
        finally {
            validations.remove(db.path)
            if (started) {
                try { db.endTransaction(); endReturned = true }
                catch (error: Exception) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                    // A native COMMIT error can leave a native transaction after Android pops its
                    // logical transaction. Retire the connection before any persisted-state query.
                    try { retire(); retired = true }
                    catch (closeError: Exception) { error.addSuppressed(closeError) }
                }
                if (!retired) try { fault("AFTER_END") }
                catch (error: Exception) { failure?.addSuppressed(error) ?: run { failure = error } }
            }
        }
        val error = failure
        if (error == null) {
            @Suppress("UNCHECKED_CAST") return value as T
        }
        Log.e("RecordStore", "Write transaction failed; confirming persisted rows", error)
        if (!started) throw DataOperationException(WriteState.FAILED, "操作失败，尚未开始写入，原有数据未改变。", error)
        if (before != null && (retired || runCatching { !db.inTransaction() }.getOrDefault(false))) {
            val actual = try { fault("BEFORE_VERIFY"); durable(db.path, selected) } catch (_: Exception) { null }
            val commitKnown = commitMarked && endReturned
            if (commitRequested && completed && after != null && actual == after && (after != before || commitKnown)) {
                @Suppress("UNCHECKED_CAST") return value as T
            }
            if (actual == before && !commitKnown && (!commitRequested || after != before)) {
                if (error is DataOperationException || error is IllegalArgumentException || error is EvidenceInvalid) throw error
                throw DataOperationException(if(error is SQLiteConstraintException) WriteState.CONFLICT else WriteState.FAILED,
                    if(error is SQLiteConstraintException) "数据约束冲突，操作未提交，原有数据未改变。" else "操作失败，已核实原有数据未改变。", error)
            }
        }
        if (before != null) pending[db.path] = Receipt(selected, before, if(completed && commitRequested) after else null,
            commitMarked && endReturned)
        blocked.add(db.path)
        throw unknown(error)
    }

    fun unknown(cause: Throwable? = null) = DataOperationException(WriteState.UNKNOWN,
        "提交状态尚未确认；证明文件已保留。请先重新读取核对，避免重复保存、删除或初始化。", cause)
}

internal fun requireOne(count: Int, missing: String, conflict: Boolean = false) {
    if (count == 0) throw DataOperationException(if(conflict) WriteState.CONFLICT else WriteState.NOT_FOUND, missing)
    check(count == 1) { "Unexpected affected row count: $count" }
}

internal fun checkedInsert(db: SQLiteDatabase, table: String, values: ContentValues,
    conflict: Int = SQLiteDatabase.CONFLICT_NONE): Long {
    val id = db.insertWithOnConflict(table, null, values, conflict)
    // A legitimate INTEGER PRIMARY KEY (academic_year) can be zero or negative.
    check(db.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) } == 1) { "Insert returned no row: $table" }
    verifyValues(db, table, "rowid = ?", arrayOf(id.toString()), values)
    return id
}

internal fun checkedInsert(db: SQLiteDatabase, table: String, nullColumnHack: String?, values: ContentValues,
    conflict: Int = SQLiteDatabase.CONFLICT_NONE): Long {
    require(nullColumnHack == null)
    return checkedInsert(db, table, values, conflict)
}

internal fun verifyValues(db: SQLiteDatabase, table: String, where: String, args: Array<String>, values: ContentValues) {
    db.query(table, null, where, args, null, null, null).use { c ->
        check(c.moveToFirst()) { "Written row is missing: $table" }
        values.valueSet().forEach { (name, value) ->
            val index = c.getColumnIndexOrThrow(name)
            check(when(value) {
                null -> c.isNull(index)
                is Double -> !c.isNull(index) && c.getDouble(index) == value
                is Float -> !c.isNull(index) && c.getDouble(index) == value.toDouble()
                is Number -> !c.isNull(index) && c.getLong(index) == value.toLong()
                else -> !c.isNull(index) && c.getString(index) == value.toString()
            }) { "Written value differs: $table.$name" }
        }
        check(!c.moveToNext())
    }
    DataWrites.verifyAtEnd(db.path) { verifyValues(db, table, where, args, values) }
}

internal fun verifyDeleted(db: SQLiteDatabase, table: String, id: Long) {
    check(!db.rawQuery("SELECT 1 FROM $table WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst() }) {
        "Deleted target still exists: $table"
    }
    DataWrites.verifyAtEnd(db.path) { verifyDeleted(db, table, id) }
}
