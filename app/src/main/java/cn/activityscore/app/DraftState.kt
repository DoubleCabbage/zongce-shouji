package cn.activityscore.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Input storage only. No database, evidence copying or transaction replay is possible here. */
internal class DraftDisk(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "form-drafts-v1.json"))
    private val pending = AtomicFile(File(context.noBackupFilesDir, "draft-write-pending"))
    private val mutex = synchronized(locks) { locks.getOrPut(file.baseFile.absolutePath) { Mutex() } }
    companion object {
        const val MAX_BYTES = 1024 * 1024
        private val locks = mutableMapOf<String, Mutex>()
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun read(): Map<String, String> = mutex.withLock {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            check(!File(file.baseFile.path + ".new").exists()) { "Draft write interrupted" }
            return@withLock emptyMap()
        }
        val bytes = file.openRead().use { it.readBytesLimited(MAX_BYTES * 2 + 4096) }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val payload = root.getString("payload")
        require(payload.toByteArray().size <= MAX_BYTES)
        require(root.getString("sha256") == hash(payload))
        val fields = JSONObject(payload)
        require(fields.length() <= 512)
        fields.keys().asSequence().associateWith { key -> fields.getString(key).also { DraftCodec.validate(key, it) } }
    }
    suspend fun write(values: Map<String, String>) = mutex.withLock {
        val payload = JSONObject(values).toString()
        require(payload.toByteArray().size <= MAX_BYTES) { "草稿超过 1 MiB，未截断；请缩短输入后重试。" }
        val bytes = JSONObject().put("version", 1).put("updatedAt", System.currentTimeMillis())
            .put("payload", payload).put("sha256", hash(payload)).toString().toByteArray()
        file.baseFile.parentFile!!.mkdirs()
        val isPending = (values["route/evidencePending"]?.let(DraftCodec::decode) as? String).orEmpty().isNotBlank()
        if (isPending) writeGuard(true)
        commit(file, bytes)
        if (!isPending) writeGuard(false) // only after the completed/abandoned input document is durable
    }
    fun pendingExists(): Boolean = try {
        if (pending.baseFile.exists() || File(pending.baseFile.path + ".bak").exists())
            pending.openRead().use { it.read() } != '0'.code
        else File(pending.baseFile.path + ".new").exists()
    } catch (_: Exception) { true }
    private fun writeGuard(blocked: Boolean) {
        commit(pending, byteArrayOf((if (blocked) '1' else '0').code.toByte()))
    }
    private fun commit(target: AtomicFile, bytes: ByteArray) {
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            // AtomicFile logs some sync/rename failures instead of throwing. SQL must not start
            // merely because finishWrite returned: explicitly sync and verify the committed file.
            stream.fd.sync()
            target.finishWrite(stream)
        } catch (error: Throwable) { target.failWrite(stream); throw error }
        check(!File(target.baseFile.path + ".new").exists() && !File(target.baseFile.path + ".bak").exists() &&
            target.baseFile.inputStream().use { it.readBytesLimited(bytes.size + 1) }.contentEquals(bytes)) {
            "草稿原子写入未完成，请检查存储空间后重试。"
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit); out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}

/** ViewModel retains every raw edit through configuration changes; disk covers new processes.
 * Debounced writes are serialized. Before SQL, flush() is awaited with the pending marker set. */
internal class DraftState(context: Context, instanceState: android.os.Bundle? = null, shared: Boolean = false) : ViewModel() {
    companion object {
        private val sessions = mutableMapOf<String, DraftSession>()
    }
    private val ownsSession = !shared
    private val session = if (shared) synchronized(sessions) {
        sessions.getOrPut(context.noBackupFilesDir.absolutePath) { DraftSession(context, instanceState) }
    } else DraftSession(context, instanceState)
    val ready get() = session.ready
    val offerRecovery get() = session.offerRecovery
    val error get() = session.error
    val saved get() = session.saved
    val generation get() = session.generation
    val discarding get() = session.discarding
    var manualCheck: Boolean
        get() = session.manualCheck
        set(value) { session.manualCheck = value }
    fun resume() = session.resume()
    fun restoreYear(default: Int) = session.restoreYear(default)
    fun discardRecovery() = session.discardRecovery()
    fun clearInputs() = session.clearInputs()
    fun removePrefix(prefix: String) = session.removePrefix(prefix)
    fun <T> state(key: String, initial: () -> T) = session.state(key, initial)
    suspend fun flush() = session.flush()
    fun retry() = session.retry()
    fun flushSoon() = session.flushSoon()
    fun saveInstance() = session.saveInstance()
    override fun onCleared() { if (ownsSession) session.close() }
}

/** One bounded input session per app-private directory, holding only application Context.
 * It outlives a finished Activity's in-flight non-cancellable save. A new Activity cannot
 * become a competing writer or lose inputs while the previous callback is completing. */
private class DraftSession(private val context: Context, private val instanceState: android.os.Bundle?) {
    private val disk = DraftDisk(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val flushMutex = Mutex()
    private val values = mutableStateMapOf<String, String>()
    private var recovered = emptyMap<String, String>()
    var ready by mutableStateOf(false); private set
    var offerRecovery by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var saved by mutableStateOf(true); private set
    var generation by mutableStateOf(0); private set
    var discarding by mutableStateOf(false); private set
    var manualCheck by mutableStateOf(false)
    init { scope.launch {
        try {
            var diskProblem: Exception? = null
            recovered = withContext(Dispatchers.IO) {
                try { disk.read() } catch (problem: Exception) {
                    android.util.Log.e("DraftState", "Draft read failed", problem)
                    diskProblem = problem
                    emptyMap()
                }
            }
            if (diskProblem != null) error = "草稿文件无法读取，未修改业务数据；请检查并核对输入。"
            instanceState?.getString("fields")?.let { json ->
                val objectValue = JSONObject(json)
                val savedValues = objectValue.keys().asSequence().associateWith { key ->
                    objectValue.getString(key).also { DraftCodec.validate(key, it) }
                }
                val sameSession = savedValues["meta/session"] == recovered["meta/session"]
                val savedRevision = (savedValues["meta/revision"]?.let(DraftCodec::decode) as? Long) ?: 0L
                val diskRevision = (recovered["meta/revision"]?.let(DraftCodec::decode) as? Long) ?: -1L
                if (recovered.isEmpty() || sameSession && savedRevision >= diskRevision) recovered = savedValues
            }
            offerRecovery = hasInputs(recovered)
            // Pending writes must block even if the user discards the input.
            recovered["route/evidencePending"]?.let { values["route/evidencePending"] = it }
            recovered["grants"]?.let { values["grants"] = it }
        } catch (problem: Exception) {
            error = "草稿文件无法读取，未修改业务数据。请保留文件并检查；本次可重新填写。"
            android.util.Log.e("DraftState", "Draft read failed", problem)
        }
        if (withContext(Dispatchers.IO) { disk.pendingExists() }) {
            val marker = DraftCodec.encode("上次保存结果未确认，请读取当前记录核对；不会自动重试。")
            values["route/evidencePending"] = marker
            recovered = recovered + ("route/evidencePending" to marker)
            offerRecovery = hasInputs(recovered)
        }
        if (instanceState != null && !instanceState.getBoolean("offer") && active(recovered)) {
            values.putAll(recovered); recovered = emptyMap(); offerRecovery = false
            generation = (values["meta/generation"]?.let(DraftCodec::decode) as? Int) ?: 0
        }
        if (!offerRecovery) {
            // A closed disk snapshot still defines the revision lineage. New edits after a cold
            // start must not get a different session and lose their valid system Bundle to it.
            listOf("meta/session", "meta/revision", "meta/generation").forEach { key ->
                if (key !in values) recovered[key]?.let { values[key] = it }
            }
            generation = (values["meta/generation"]?.let(DraftCodec::decode) as? Int) ?: 0
        }
        if ("meta/session" !in values && !offerRecovery) {
            values["meta/session"] = DraftCodec.encode(java.util.UUID.randomUUID().toString())
            values["meta/revision"] = DraftCodec.encode(0L)
        }
        ready = true
    } }
    private fun hasInputs(map: Map<String, String>): Boolean =
        listOf("showItemEditor", "showParticipationEditor", "showAnnualEditor", "showDuplicateReviews", "historyImport")
            .any { map["route/$it"]?.let(DraftCodec::decode) == true } ||
            (map["route/packageUri"]?.let(DraftCodec::decode) as? String).orEmpty().isNotBlank() ||
            map["route/evidenceTargetId"]?.let(DraftCodec::decode) != null ||
            map["route/evidenceRepairId"]?.let(DraftCodec::decode) != null
    private fun active(map: Map<String, String>): Boolean = hasInputs(map) ||
        (map["route/evidencePending"]?.let(DraftCodec::decode) as? String).orEmpty().isNotBlank()
    fun resume() {
        values.putAll(recovered); recovered = emptyMap(); offerRecovery = false
        generation = (values["meta/generation"]?.let(DraftCodec::decode) as? Int) ?: 0
    }
    fun restoreYear(default: Int): Int = ((if (offerRecovery) recovered else values)["route/selectedYear"]
        ?.let(DraftCodec::decode) as? Int) ?: default
    fun discardRecovery() {
        if (discarding) return
        discarding = true
        clearInputs()
        scope.launch {
            try { flush(); recovered = emptyMap(); offerRecovery = false; error = "" }
            catch (_: Exception) { error = "草稿丢弃尚未写入设备，请检查空间后重试；上次草稿仍可继续编辑。" }
            finally { discarding = false }
        }
    }
    fun clearInputs() {
        val pending = values["route/evidencePending"]
        val grants = values["grants"]
        val session = values["meta/session"]
        val revision = values["meta/revision"]
        values.clear(); pending?.let { values["route/evidencePending"] = it }; grants?.let { values["grants"] = it }
        session?.let { values["meta/session"] = it }; revision?.let { values["meta/revision"] = it }
        generation++; values["meta/generation"] = DraftCodec.encode(generation); schedule()
    }
    fun removePrefix(prefix: String) {
        values.keys.filter { it.startsWith(prefix) }.forEach { values.remove(it) }; schedule()
    }
    @Suppress("UNCHECKED_CAST")
    fun <T> state(key: String, initial: () -> T): MutableState<T> {
        if (key !in values) values[key] = DraftCodec.encode(initial())
        return object : MutableState<T> {
            override var value: T
                get() = values[key]?.let { DraftCodec.decode(it) as T } ?: initial()
                set(value) {
                    val encoded = DraftCodec.encode(value)
                    if (values[key] == encoded) return
                    val size = JSONObject(values.toMap() + (key to encoded)).toString().toByteArray().size
                    if (size > DraftDisk.MAX_BYTES || key !in values && values.size >= 500) {
                        error = "草稿已达到 1 MiB 上限；本次输入未接受，已有内容未截断。请缩短输入。"; return
                    }
                    values[key] = encoded; schedule()
                }
            override fun component1() = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
    private fun schedule() {
        val revision = (values["meta/revision"]?.let(DraftCodec::decode) as? Long) ?: 0L
        values["meta/revision"] = DraftCodec.encode(revision + 1)
        if ("meta/session" !in values) values["meta/session"] = DraftCodec.encode(java.util.UUID.randomUUID().toString())
        saved = false; job?.cancel()
        job = scope.launch { delay(250); runCatching { flush() }.onFailure {
            if (it !is CancellationException) error = "草稿尚未写入设备，当前输入仍在内存；请检查存储空间后重试。"
        } }
    }
    suspend fun flush() = flushMutex.withLock {
        val snapshot = if (offerRecovery && !discarding) recovered else values.toMap()
        try { withContext(Dispatchers.IO + NonCancellable) { disk.write(snapshot) }
            saved = snapshot == if (offerRecovery && !discarding) recovered else values.toMap() }
        catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            saved = false; error = "草稿写入失败，当前输入仍在内存；请检查存储空间后重试。"
            throw problem
        }
        // Release only grants acquired by this draft, and only after its removal is durable.
        if (saved && (snapshot["route/evidencePending"]?.let(DraftCodec::decode) as? String).orEmpty().isBlank()) {
            val grants = (snapshot["grants"]?.let(DraftCodec::decode) as? List<*>).orEmpty().filterIsInstance<Uri>()
            val unused = grants.filter { uri -> snapshot.filterKeys { it != "grants" }.values.none {
                it.contains(JSONObject.quote(uri.toString()).removeSurrounding("\""))
            } }
            // Recheck and release in one UI event: a delayed IO callback must not revoke a grant
            // adopted by a newer form. This is bounded permission IPC, not keyboard-triggered file IO.
            val released = unused.filter { uri ->
                values.filterKeys { it != "grants" }.values.none {
                    it.contains(JSONObject.quote(uri.toString()).removeSurrounding("\""))
                } && runCatching {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
            }
            if (released.isNotEmpty()) {
                values["grants"] = DraftCodec.encode(grants.filter { it !in released })
                val revision = (values["meta/revision"]?.let(DraftCodec::decode) as? Long) ?: 0L
                values["meta/revision"] = DraftCodec.encode(revision + 1)
                val cleaned = values.toMap()
                try { withContext(Dispatchers.IO + NonCancellable) { disk.write(cleaned) }; saved = cleaned == values.toMap() }
                catch (problem: Exception) {
                    if (problem is CancellationException) throw problem
                    saved = false; error = "草稿授权清理记录未完成，请检查空间后重试；业务数据不会重新保存。"
                    throw problem
                }
            }
        }
    }
    fun retry() { scope.launch { runCatching { flush() }.onSuccess { error = "" }.onFailure { error = "草稿写入失败，当前输入仍保留；请检查存储空间。" } } }
    fun flushSoon() { job?.cancel(); job = scope.launch { runCatching { flush() }.onFailure {
        if (it !is CancellationException) error = "草稿写入失败，当前输入仍在内存；请检查存储空间。"
    } } }
    fun saveInstance(): android.os.Bundle = android.os.Bundle().apply {
        // Stay comfortably below Binder's shared transaction limit. Images/parsed imports never enter it.
        putBoolean("offer", offerRecovery)
        val json = JSONObject(if (offerRecovery) recovered else values.toMap()).toString()
        if (json.toByteArray().size <= 128 * 1024) putString("fields", json)
    }
    fun close() { scope.cancel() }
}

internal val LocalDraft = staticCompositionLocalOf<DraftState> { error("DraftState not provided") }
@Composable
internal fun <T> draftState(key: String, initial: () -> MutableState<T>): MutableState<T> {
    val draft = LocalDraft.current
    return remember(draft, key, draft.generation) { draft.state(key) { initial().value } }
}

internal data class CourseDraft(val name: String, val credits: String, val grade: String)

/** Explicit, versioned whitelist. Never Java serialization, reflection, full snapshots or bitmaps. */
internal object DraftCodec {
    fun validate(key: String, encoded: String) {
        val v = decode(encoded)
        val field = key.substringAfterLast('/')
        val valid = when {
            key == "meta/session" -> v is String
            key == "meta/revision" -> v is Long && v >= 0L
            key == "meta/generation" -> v is Int && v >= 0
            key == "grants" -> v is List<*> && v.all { it is Uri }
            key == "historyQuery" -> v is String
            key.startsWith("route/") -> when (field) {
                "tab", "selectedYear" -> v is Int
                "recordFilter", "evidencePending", "packageUri", "packageFingerprint", "reviewFingerprint",
                "itemPickerOwner", "participationPickerOwner", "historyPickerOwner", "pendingDraftPrefix",
                "evidencePickerOwner", "repairPickerOwner" -> v is String
                "editingItem" -> v == null || v is ScoreItem
                "editingParticipation" -> v == null || v is Participation
                "selectedItemId", "evidenceTargetId", "evidenceRepairId" -> v == null || v is Long
                "participationParentCreatedAt", "participationItemId" -> v == null || v is Long
                "evidenceRepairPhoto" -> v == null || v is EvidencePhoto
                "evidenceRepairUri" -> v == null || v is Uri
                "evidenceUris" -> v is List<*> && v.all { it is Uri }
                "selectedDuplicateReviewKey" -> v == null || v is String
                "showItemEditor", "showParticipationEditor", "showAnnualEditor", "showDuplicateReviews",
                "showDuplicateReviewList", "historyImport", "inspecting", "showEvidenceHealth" -> v is Boolean
                else -> false
            }
            key.startsWith("item/") -> when (field) {
                "images" -> v is List<*> && v.all { it is Uri }
                "selectedHistory" -> v == null || v is HistoryActivity
                "showSuggestions" -> v is Boolean
                "title", "dimension", "subcategory", "role", "scoreText", "note", "eventDate", "eventNote", "error", "formId" -> v is String
                else -> false
            }
            key.startsWith("participation/") -> when (field) {
                "images" -> v is List<*> && v.all { it is Uri }
                "date", "note", "error", "formId" -> v is String
                else -> false
            }
            key.startsWith("annual/") -> when (field) {
                "courses" -> v is List<*> && v.all { it is CourseDraft }
                "self", "peers", "teachers", "deductions", "fitness", "running" -> v is String
                else -> false
            }
            key.startsWith("review/") -> when (field) {
                "decision" -> v == null || v is DuplicateDecision
                "scoreTexts" -> v is Map<*, *> && v.all { it.key is Long && it.value is String }
                else -> false
            }
            key.startsWith("package/") -> when (field) {
                "participated" -> v is Boolean
                "selectedIndices" -> v is Set<*> && v.all { it is Int }
                "role", "scoreText", "error" -> v is String
                else -> false
            }
            key.startsWith("history/") -> when (field) {
                "mapping" -> v is ColumnMapping
                "mappingLookup", "selectionSet" -> v is Boolean
                "selectedSheets" -> v is Set<*> && v.all { it is String }
                "sourceUri", "sourceHash", "fileName", "sheetName", "headerRowText", "formId" -> v is String
                else -> false
            }
            else -> false
        }
        require(valid) { "Invalid draft field $key" }
    }
    fun encode(value: Any?): String = wrap(value).toString()
    private fun wrap(value: Any?): JSONArray {
        fun tag(type: String, vararg fields: Any?) = JSONArray().put(type).apply { fields.forEach { put(it ?: JSONObject.NULL) } }
        return when (value) {
            null -> tag("null")
            is String -> tag("string", value)
            is Boolean -> tag("boolean", value)
            is Int -> tag("int", value)
            is Long -> tag("long", value.toString())
            is Double -> tag("double", value.toString())
            is Uri -> tag("uri", value.toString())
            is File -> tag("file", value.path)
            is DuplicateDecision -> tag("decision", value.name)
            is List<*> -> tag("list", JSONArray().apply { value.forEach { put(wrap(it)) } })
            is Set<*> -> tag("set", JSONArray().apply { value.forEach { put(wrap(it)) } })
            is Map<*, *> -> tag("map", JSONArray().apply { value.forEach { (k,v) -> put(JSONArray().put(wrap(k)).put(wrap(v))) } })
            is CourseDraft -> tag("course", value.name, value.credits, value.grade)
            is ColumnMapping -> tag("mapping", value.activity, value.dimension, value.score, value.role, value.subcategory)
            is ScoreItem -> tag("item", value.id.toString(), value.title, value.dimension, value.subcategory, value.role,
                value.referenceScore?.toString(), value.note, value.createdAt.toString())
            is Participation -> tag("participation", value.id.toString(), value.itemId.toString(), value.eventDate, value.note)
            is EvidencePhoto -> tag("photo", value.id.toString(), value.participationId.toString(), value.fileName)
            is HistoryActivity -> tag("history", value.activityName, value.dimension, value.subcategory,
                wrap(value.roles), value.variantLabel, value.needsReview)
            is HistoryRole -> tag("role", value.label, value.referenceScore.toString(), value.sourceRow,
                JSONArray().apply { value.sources.forEach { put(JSONArray().put(it.sheet).put(it.row).put(it.rawScore)) } })
            else -> error("Unsupported draft field ${value.javaClass.name}")
        }
    }
    fun decode(value: String): Any? = unwrap(JSONArray(value))
    private fun unwrap(a: JSONArray): Any? {
        fun list(index: Int) = a.getJSONArray(index).let { rows -> (0 until rows.length()).map { unwrap(rows.getJSONArray(it)) } }
        return when (a.getString(0)) {
            "null" -> null
            "string" -> a.getString(1)
            "boolean" -> a.getBoolean(1)
            "int" -> a.getInt(1)
            "long" -> a.getString(1).toLong()
            "double" -> a.getString(1).toDouble()
            "uri" -> Uri.parse(a.getString(1))
            "file" -> File(a.getString(1))
            "decision" -> DuplicateDecision.valueOf(a.getString(1))
            "list" -> list(1)
            "set" -> list(1).toSet()
            "map" -> a.getJSONArray(1).let { rows -> (0 until rows.length()).associate {
                val row = rows.getJSONArray(it); unwrap(row.getJSONArray(0)) to unwrap(row.getJSONArray(1)) } }
            "course" -> CourseDraft(a.getString(1), a.getString(2), a.getString(3))
            "mapping" -> ColumnMapping(a.getInt(1), a.getInt(2), a.getInt(3), a.getInt(4), a.getInt(5))
            "item" -> ScoreItem(a.getString(1).toLong(), a.getString(2), a.getString(3), a.getString(4), a.getString(5),
                if (a.isNull(6)) null else a.getString(6).toDouble(), a.getString(7), a.getString(8).toLong())
            "participation" -> Participation(a.getString(1).toLong(), a.getString(2).toLong(), a.getString(3), a.getString(4), emptyList())
            "photo" -> EvidencePhoto(a.getString(1).toLong(), a.getString(2).toLong(), a.getString(3))
            "history" -> HistoryActivity(a.getString(1), a.getString(2), a.getString(3),
                (unwrap(a.getJSONArray(4)) as List<*>).map { it as HistoryRole }, a.getString(5), a.getBoolean(6))
            "role" -> HistoryRole(a.getString(1), a.getString(2).toDouble(), a.getInt(3),
                if (a.length() < 5) emptyList() else a.getJSONArray(4).let { values ->
                    (0 until values.length()).map { index -> values.getJSONArray(index).let {
                        cn.activityscore.app.data.HistorySource(it.getString(0), it.getInt(1), it.getString(2))
                    } }
                })
            else -> error("Unsupported draft format")
        }
    }
}

/** Keep only grants acquired by drafts. Never release a grant the app already owned. */
internal fun retainDraftUri(context: Context, uri: Uri, draft: DraftState) {
    if (uri.scheme != "content") return
    val owned = draft.state("grants") { emptyList<Uri>() }
    if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onSuccess {
                owned.value = (owned.value + uri).distinct()
                // A rejected inventory update must not leave an untracked new persistent grant.
                if (uri !in owned.value) runCatching {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
    }
}

@Composable
internal fun DraftUriWarning(uris: List<Uri>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val invalid by produceState(false, uris) {
        value = withContext(Dispatchers.IO) { uris.any { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() } ?: error("Missing URI") }.isFailure
        } }
    }
    if (invalid) androidx.compose.material3.Text("所选文件已无法读取，请移除失效选择并重新选择；尚未保存为证明。",
        color = androidx.compose.material3.MaterialTheme.colorScheme.error)
}
