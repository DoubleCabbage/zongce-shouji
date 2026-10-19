package cn.activityscore.app

import android.graphics.BitmapFactory
import cn.activityscore.app.data.EvidenceProblems
import cn.activityscore.app.data.RecordSnapshot
import cn.activityscore.app.data.DataOperationException
import cn.activityscore.app.data.DataReadException
import cn.activityscore.app.data.WriteState
import cn.activityscore.app.data.dataErrorMessage
import cn.activityscore.app.data.readErrorMessage
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.NonCancellable
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.activityscore.app.data.EvidencePhoto
import cn.activityscore.app.data.ActivityPackage
import cn.activityscore.app.data.HistoryRow
import cn.activityscore.app.data.NumericValues
import cn.activityscore.app.data.NumericRule
import cn.activityscore.app.data.Participation
import cn.activityscore.app.data.RecordStore
import cn.activityscore.app.data.ScoreItem
import cn.activityscore.app.data.ScoreSummary
import cn.activityscore.app.data.ScoreExcelExport
import cn.activityscore.app.data.AnnualExportSnapshot
import cn.activityscore.app.data.ExportChoice
import cn.activityscore.app.data.ExportEntryPlan
import cn.activityscore.app.data.ExportEntryPlanner
import cn.activityscore.app.data.ExportBlocked
import cn.activityscore.app.data.exportErrorMessage
import cn.activityscore.app.data.StagedActivityPackage
import cn.activityscore.app.data.scoreDimensionCaps
import cn.activityscore.app.data.scoreDimensions
import cn.activityscore.app.domain.DuplicateConfirmation
import cn.activityscore.app.domain.AnnualInputs
import cn.activityscore.app.domain.AnnualScore
import cn.activityscore.app.domain.AnnualScoreCalculator
import cn.activityscore.app.domain.CourseGrade
import cn.activityscore.app.domain.DuplicateDecision
import cn.activityscore.app.domain.DuplicateReview
import cn.activityscore.app.domain.DuplicateReviewFinder
import cn.activityscore.app.domain.ReferenceSumPolicy
import cn.activityscore.app.domain.RepeatedParticipationReview
import cn.activityscore.app.domain.SimilarItemsReview
import cn.activityscore.app.domain.HistoricalMatcher
import cn.activityscore.app.domain.HistoryActivity
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    companion object {
        /** Instrumentation supplies an isolated context; normal app always opens its own store. */
        internal var draftContext: (android.content.Context) -> android.content.Context = { it }
        internal var openStore: (android.content.Context) -> RecordStore = { RecordStore(it) }
    }
    internal lateinit var drafts: DraftState
    private var incomingPackageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drafts = androidx.lifecycle.ViewModelProvider(this, object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DraftState(draftContext(applicationContext), savedInstanceState?.getBundle("p105draft"), shared = true) as T
            }
        })[DraftState::class.java]
        incomingPackageUri = if (savedInstanceState == null) packageUri(intent) else null
        setContent {
            ScoreTheme {
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalDraft provides drafts) {
                    if (drafts.ready) ScoreApp(incomingPackageUri) { incomingPackageUri = null }
                    else Text("正在读取草稿…")
                }
            }
        }
    }

    override fun onStop() {
        if (::drafts.isInitialized && drafts.ready) drafts.flushSoon()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::drafts.isInitialized && drafts.ready) outState.putBundle("p105draft", drafts.saveInstance())
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingPackageUri = packageUri(intent)
    }

    private fun packageUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }
}

@Composable
private fun ScoreApp(incomingPackageUri: Uri?, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recoveryAttempt by remember { mutableStateOf(0) }
    var openedStore by remember(recoveryAttempt) { mutableStateOf<Result<RecordStore>?>(null) }
    LaunchedEffect(recoveryAttempt) {
        var acquired: RecordStore? = null
        val application = context.applicationContext
        val openFactory = MainActivity.openStore
        val cleanupContext = MainActivity.draftContext(application)
        try {
            withContext(Dispatchers.IO) {
                cn.activityscore.app.data.FullBackup.cleanAbandonedStaging(cleanupContext)
                acquired = openFactory(application)
            }
            openedStore = Result.success(requireNotNull(acquired))
            acquired = null // now owned by the composition's DisposableEffect
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.e("RecordStore", "Open/recovery failed", error)
            openedStore = Result.failure(error)
        } finally {
            // withContext can be cancelled after a synchronous open has produced a handle.
            acquired?.let { abandoned -> withContext(Dispatchers.IO + NonCancellable) { abandoned.close() } }
        }
    }
    val opened = openedStore
    if (opened == null) { Text("正在打开数据…"); return }
    val store = opened.getOrNull()
    if (store == null) {
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
            Text("暂时无法打开数据", style = MaterialTheme.typography.titleLarge)
            Text(readErrorMessage(opened.exceptionOrNull()))
            Button(onClick = { recoveryAttempt += 1 }) { Text("重新检查并打开") }
        }
        return
    }
    DisposableEffect(store) {
        onDispose {
            // A confirmed restore may still own the data lock; never wait for it on the UI thread.
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { store.close() }
        }
    }
    var loadAttempt by remember { mutableStateOf(0) }
    val draftSession = LocalDraft.current
    // User year changes are handled by ScoreContent, not by reopening its initial snapshot.
    val year = remember(store, draftSession.generation) { draftSession.restoreYear(DuplicateReviewFinder.academicYear(LocalDate.now().toString())) }
    var loaded by remember(store, loadAttempt, year, draftSession.generation) { mutableStateOf<Result<RecordPresentation>?>(null) }
    LaunchedEffect(store, loadAttempt, year, draftSession.generation) {
        try { loaded = Result.success(store.loadPresentation(year)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.e("RecordStore", "Initial read failed", error)
            loaded = Result.failure(error)
        }
    }
    val read = loaded
    if (read == null) { Text("正在读取记录…"); return }
    val initial = read.getOrNull()
    if (initial == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("数据读取失败", style = MaterialTheme.typography.titleLarge)
            Text(readErrorMessage(read.exceptionOrNull()))
            Button(onClick = { loadAttempt += 1 }) { Text("重新读取") }
        }
        return
    }
    val drafts = LocalDraft.current
    if (drafts.offerRecovery) {
        AlertDialog(onDismissRequest = {}, title = { Text("发现未完成的草稿") },
            text = { Text("可以继续编辑上次输入或丢弃。草稿不会自动保存；如上次保存结果未确认，仍须先核对当前记录。" +
                if (drafts.error.isNotBlank()) "\n${drafts.error}" else "") },
            confirmButton = { TextButton(enabled = !drafts.discarding, onClick = { drafts.resume() }) { Text("继续编辑") } },
            dismissButton = { TextButton(enabled = !drafts.discarding, onClick = { drafts.discardRecovery() }) { Text(if(drafts.discarding) "正在丢弃…" else "丢弃草稿") } })
        return
    }
    androidx.compose.runtime.key(drafts.generation) {
        ScoreContent(store, initial, incomingPackageUri, onIncomingHandled)
    }
}

@Composable
private fun ScoreContent(store: RecordStore, initial: RecordPresentation, incomingPackageUri: Uri?, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drafts = LocalDraft.current
    var presentation by remember { mutableStateOf(initial) }
    val items = presentation.snapshot.items
    val historyRows = presentation.snapshot.history
    val historyInfo = presentation.snapshot.historyInfo
    val participationsByItem = presentation.snapshot.participations
    val duplicateConfirmations = presentation.snapshot.confirmations
    val knownShareIds = presentation.snapshot.shareIds
    var readError by remember { mutableStateOf("") }
    var operationError by remember { mutableStateOf("") }
    var tab by draftState("route/tab") { mutableStateOf(0) }
    var activityPendingOnly by rememberSaveable { mutableStateOf(false) }
    var recordFilter by draftState("route/recordFilter") { mutableStateOf("全部") }
    var editingItem by draftState("route/editingItem") { mutableStateOf<ScoreItem?>(null) }
    var showItemEditor by draftState("route/showItemEditor") { mutableStateOf(false) }
    var selectedItemId by draftState("route/selectedItemId") { mutableStateOf<Long?>(null) }
    var showParticipationEditor by draftState("route/showParticipationEditor") { mutableStateOf(false) }
    var editingParticipation by draftState("route/editingParticipation") { mutableStateOf<Participation?>(null) }
    var participationParentCreatedAt by draftState("route/participationParentCreatedAt") { mutableStateOf<Long?>(null) }
    var participationItemId by draftState("route/participationItemId") { mutableStateOf<Long?>(null) }
    var showAnnualEditor by draftState("route/showAnnualEditor") { mutableStateOf(false) }
    var selectedYear by draftState("route/selectedYear") { mutableStateOf(DuplicateReviewFinder.academicYear(LocalDate.now().toString())) }
    val annualInputs = presentation.snapshot.annual
    var showDuplicateReviews by draftState("route/showDuplicateReviews") { mutableStateOf(false) }
    var showDuplicateReviewList by draftState("route/showDuplicateReviewList") { mutableStateOf(false) }
    var selectedDuplicateReviewKey by draftState("route/selectedDuplicateReviewKey") { mutableStateOf<String?>(null) }
    var reviewFingerprint by draftState("route/reviewFingerprint") { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val numericIssues = presentation.snapshot.numericIssues
    var showEvidenceHealth by draftState("route/showEvidenceHealth") { mutableStateOf(false) }
    var evidencePending by draftState("route/evidencePending") { mutableStateOf("") }
    var pendingDraftPrefix by draftState("route/pendingDraftPrefix") { mutableStateOf("") }
    var evidenceTargetId by draftState("route/evidenceTargetId") { mutableStateOf<Long?>(null) }
    var evidencePickerOwner by draftState("route/evidencePickerOwner") { mutableStateOf("") }
    var requestEvidencePicker by remember { mutableStateOf(false) }
    var evidenceUris by draftState("route/evidenceUris") { mutableStateOf(emptyList<Uri>()) }
    var inspecting by draftState("route/inspecting") { mutableStateOf(false) }
    val historyImportActive by draftState("route/historyImport") { mutableStateOf(false) }
    val evidenceRepairId by draftState("route/evidenceRepairId") { mutableStateOf<Long?>(null) }
    var requestedReplacement by remember { mutableStateOf<EvidencePhoto?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var showExportYear by remember { mutableStateOf(false) }
    var exportYear by remember { mutableStateOf(selectedYear) }
    var pendingExportSnapshot by remember { mutableStateOf<AnnualExportSnapshot?>(null) }
    var exportChoices by remember { mutableStateOf(emptyMap<Long, ExportChoice>()) }
    var exportPreview by remember { mutableStateOf<Pair<Map<Long, ExportChoice>, ExportEntryPlan>?>(null) }
    var pendingExportPlan by remember { mutableStateOf<ExportEntryPlan?>(null) }
    var showExportSummary by remember { mutableStateOf(false) }
    var exportFailure by remember { mutableStateOf<Exception?>(null) }
    var exportFailureStage by remember { mutableStateOf("") }
    fun discardExport() {
        showExportSummary = false
        pendingExportSnapshot?.close()
        pendingExportSnapshot = null
        pendingExportPlan = null
        exportPreview = null
        exportChoices = emptyMap()
        exportBusy = false
    }
    LaunchedEffect(pendingExportSnapshot, exportChoices) {
        val snapshot = pendingExportSnapshot ?: return@LaunchedEffect
        val choices = exportChoices.toMap()
        try {
            val plan = withContext(Dispatchers.Default) {
                ExportEntryPlanner.build(snapshot.scores.academicYear, snapshot.assessment, choices)
            }
            exportPreview = choices to plan
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            discardExport(); exportFailureStage = "整理申报条目"; exportFailure = error
        }
    }
    DisposableEffect(Unit) {
        onDispose { pendingExportSnapshot?.close() }
    }
    var shareBusy by remember { mutableStateOf(false) }
    var shareSelection by remember { mutableStateOf<Pair<ScoreItem, Participation>?>(null) }
    var stagedPackage by remember { mutableStateOf<StagedActivityPackage?>(null) }
    var packageLoading by remember { mutableStateOf(false) }
    var packageUri by draftState("route/packageUri") { mutableStateOf("") }
    var packageFingerprint by draftState("route/packageFingerprint") { mutableStateOf("") }
    var packageLoadSerial by remember { mutableStateOf(0) }
    fun openDraft(action: () -> Unit) {
        if (EvidenceOperation.busy || evidencePending.isNotBlank() || readError.isNotBlank() || evidenceTargetId != null || evidenceRepairId != null)
            operationError = "请先读取核对上次操作，再打开新的编辑；原输入仍保留。"
        else action()
    }

    val refreshOwner = remember { SnapshotRefreshOwner() }
    var refreshing by remember { mutableStateOf(false) }
    var rechecking by remember { mutableStateOf(false) }
    var sawBusy by remember { mutableStateOf(EvidenceOperation.busy) }
    var savedPresentationHere by remember { mutableStateOf(false) }
    DisposableEffect(refreshOwner) {
        onDispose { refreshOwner.active = false; refreshOwner.serial += 1 }
    }
    suspend fun refresh(year: Int = selectedYear): Boolean {
        val serial = ++refreshOwner.serial
        refreshing = true
        try {
            return refreshOwner.mutex.withLock {
                if (!refreshOwner.active || serial != refreshOwner.serial) return@withLock false
                val next = store.loadPresentation(year)
                if (!refreshOwner.active || serial != refreshOwner.serial) return@withLock false
                presentation = next // records, reviews and annual totals published as one value
                selectedYear = year
                readError = ""
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!refreshOwner.active || serial != refreshOwner.serial) return false
            readError = readErrorMessage(error); throw error
        } finally {
            if (serial == refreshOwner.serial && refreshOwner.active) refreshing = false
        }
    }
    fun requestRefresh(year: Int = selectedYear) {
        scope.launch {
            try { refresh(year) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* previous complete presentation and guarded retry remain */ }
        }
    }

    fun reread() {
        if (EvidenceOperation.busy || rechecking) return
        rechecking = true
        scope.launch {
        try {
            val resolution = withContext(Dispatchers.IO) { store.recheckPendingWrite() }
            if (!refreshOwner.active) return@launch
            if (resolution == WriteState.UNKNOWN) { operationError = "提交状态仍无法确认；请保留当前输入，稍后重新读取核对，避免重复操作。"; return@launch }
            if (!refresh()) return@launch
            if (resolution == null && evidencePending.isNotBlank()) {
                drafts.manualCheck = true
                operationError = "已读取持久化记录；上次保存结果没有跨进程回执，无法自动确认。请查看当前记录，核对活动、参与和附件。上次输入仍保留，保存继续受保护。"
                return@launch
            }
            if (resolution == WriteState.SUCCESS) {
                EvidenceOperation.pendingAfter?.invoke()
                // A new Activity may have a different ViewModel: close the matching restored input,
                // never depend solely on a callback that captured a disposed composition.
                when {
                    pendingDraftPrefix == "item/${editingItem?.id ?: "new"}/" -> { showItemEditor = false; editingItem = null }
                    pendingDraftPrefix == "participation/${editingParticipation?.id ?: "new"}/" -> { showParticipationEditor = false; editingParticipation = null }
                    pendingDraftPrefix == "annual/$selectedYear/" -> showAnnualEditor = false
                    pendingDraftPrefix == "review/$selectedDuplicateReviewKey/$reviewFingerprint/" -> { showDuplicateReviews = false; selectedDuplicateReviewKey = null }
                    pendingDraftPrefix.startsWith("package/") && stagedPackage?.activity?.shareId?.let { pendingDraftPrefix == "package/$it/" } == true -> {
                        stagedPackage?.close(); stagedPackage = null; packageUri = ""
                    }
                    pendingDraftPrefix == "history/" -> drafts.state("route/historyImport") { false }.value = false
                    pendingDraftPrefix == "evidence/" -> { evidenceUris = emptyList(); evidenceTargetId = null }
                    pendingDraftPrefix == "repair/" -> {
                        drafts.state<Uri?>("route/evidenceRepairUri") { null }.value = null
                        drafts.state<EvidencePhoto?>("route/evidenceRepairPhoto") { null }.value = null
                        drafts.state<Long?>("route/evidenceRepairId") { null }.value = null
                    }
                }
                if (pendingDraftPrefix.endsWith("/") && pendingDraftPrefix !in listOf("evidence/", "repair/")) drafts.removePrefix(pendingDraftPrefix)
                message = "已重新核实数据库操作完成；保留的无引用文件暂不清理。"
            } else if (resolution == WriteState.FAILED) message = "已核实操作未提交，原有数据未改变，可检查输入后重试。"
            EvidenceOperation.pendingAfter = null
            evidencePending = ""
            pendingDraftPrefix = ""
            EvidenceOperation.result = ""
            operationError = ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if(refreshOwner.active) readError = readErrorMessage(error) }
        finally { rechecking = false }
        }
    }

    fun loadPackage(uri: Uri, restoring: Boolean = false) {
        retainDraftUri(context, uri, drafts)
        packageUri = uri.toString()
        packageLoadSerial += 1
        val serial = packageLoadSerial
        stagedPackage?.close()
        stagedPackage = null
        packageLoading = true
        scope.launch {
            var captured: StagedActivityPackage? = null
            try {
                withContext(Dispatchers.IO + NonCancellable) { captured = ActivityPackage.read(context, uri) }
                val staged = requireNotNull(captured)
                val fingerprint = withContext(Dispatchers.IO) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val activity = staged.activity
                    digest.update(org.json.JSONArray().put(activity.shareId).put(activity.title).put(activity.dimension)
                        .put(activity.subcategory).put(activity.referenceScore ?: org.json.JSONObject.NULL).put(activity.note)
                        .put(activity.eventDate).put(activity.eventNote).put(staged.photos.size).toString().toByteArray())
                    staged.photos.forEach { file -> file.inputStream().use { input ->
                        digest.update(java.nio.ByteBuffer.allocate(8).putLong(file.length()).array())
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    } }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                require(!restoring || packageFingerprint.isBlank() || packageFingerprint == fingerprint) {
                    "活动包内容已变化，请重新选择并核对；上次输入仍保留。"
                }
                if (!restoring && packageFingerprint.isNotBlank() && packageFingerprint != fingerprint)
                    drafts.removePrefix("package/")
                if (serial == packageLoadSerial && packageUri == uri.toString()) {
                    packageFingerprint = fingerprint; stagedPackage = staged; captured = null
                }
            } catch (error: Exception) {
                if (serial == packageLoadSerial) message = error.message ?: "无法读取活动包"
            } finally {
                captured?.close()
                if (serial == packageLoadSerial) packageLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (packageUri.isNotBlank() && stagedPackage == null) loadPackage(Uri.parse(packageUri), true) }
    DisposableEffect(Unit) { onDispose { if (!EvidenceOperation.busy) stagedPackage?.close() } }
    LaunchedEffect(packageUri, EvidenceOperation.busy) {
        if (packageUri.isBlank() && !EvidenceOperation.busy) { stagedPackage?.close(); stagedPackage = null }
    }

    LaunchedEffect(incomingPackageUri) {
        if (incomingPackageUri != null) {
            if (EvidenceOperation.busy || evidencePending.isNotBlank() || packageUri.isNotBlank()) message = "当前活动包或上次操作仍待处理；新活动包未读取，请处理草稿后再次打开。"
            else loadPackage(incomingPackageUri)
            onIncomingHandled()
        }
    }

    val packagePickerTarget = packageUri
    val packagePicker = androidx.compose.runtime.key(packagePickerTarget) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (packageUri == packagePickerTarget && uri != null) {
                if (EvidenceOperation.busy || evidencePending.isNotBlank()) message = "当前操作仍待核对，活动包选择未采用，请处理后重新选择。"
                else loadPackage(uri)
            }
        }
    }

    fun saveEvidence(success: String, action: () -> Unit, after: suspend () -> Unit = {}) {
        if (EvidenceOperation.busy || readError.isNotBlank()) return
        if (evidencePending.isNotBlank()) { operationError = "请先重新读取核对上次操作，避免重复提交。"; return }
        val generation = drafts.generation
        val completedPrefixes = when {
            evidenceUris.isNotEmpty() || evidenceRepairId != null -> emptyList()
            showItemEditor -> listOf("item/${editingItem?.id ?: "new"}/")
            showParticipationEditor -> listOf("participation/${editingParticipation?.id ?: "new"}/")
            showAnnualEditor -> listOf("annual/$selectedYear/")
            showDuplicateReviews -> listOf("review/$selectedDuplicateReviewKey/$reviewFingerprint/")
            historyImportActive && tab == 1 -> listOf("history/")
            stagedPackage != null -> listOf("package/${stagedPackage!!.activity.shareId}/")
            else -> emptyList()
        }
        val expectedPrefix = if (evidenceUris.isNotEmpty()) "evidence/"
            else if (drafts.state<Uri?>("route/evidenceRepairUri") { null }.value != null) "repair/"
            else completedPrefixes.firstOrNull().orEmpty()
        val pendingNotice = "保存状态无法确认：若页面重建或进程退出，请重新读取核对后再操作。"
        pendingDraftPrefix = expectedPrefix
        evidencePending = pendingNotice
        if (pendingDraftPrefix != expectedPrefix || evidencePending != pendingNotice) {
            evidencePending = ""; pendingDraftPrefix = ""
            operationError = "草稿已达到存储边界，未开始保存；请缩短输入，确保保护状态可以写入设备。"
            return
        }
        EvidenceOperation.busy = true
        savedPresentationHere = false
        scope.launch { withContext(NonCancellable) {
            var saved = false
            var draftCleared = false
            var started = false
            var notice = ""
            try {
                drafts.flush()
                started = true
                try { withContext(Dispatchers.IO) { action() } }
                catch (error: DataOperationException) {
                    if (error.state == WriteState.COMMITTED_CLEANUP_PENDING) notice = dataErrorMessage(error) else throw error
                }
                saved = true
                evidencePending = ""
                if (generation == drafts.generation) {
                    after()
                    completedPrefixes.forEach(drafts::removePrefix)
                }
                pendingDraftPrefix = ""
                drafts.flush() // persisted closed routes/tombstone; failure cannot replay SQL
                draftCleared = true
                savedPresentationHere = refresh()
                message = if(notice.isBlank()) success else "$success；$notice"
                EvidenceOperation.result = message
            } catch (error: Exception) {
                val unknown = started && !saved && (error !is DataOperationException || error.state == WriteState.UNKNOWN) &&
                    error !is IllegalArgumentException && error !is cn.activityscore.app.data.EvidenceInvalid
                if (error is DataReadException) readError = readErrorMessage(error)
                if (unknown) EvidenceOperation.pendingAfter = {
                    if (generation == drafts.generation) { after(); completedPrefixes.forEach(drafts::removePrefix) }
                } else { evidencePending = ""; pendingDraftPrefix = "" }
                runCatching { drafts.flush() }
                operationError = if (saved) {
                    if (draftCleared) "$success；界面刷新失败，请重新读取核对。"
                    else "$success；草稿清理未完成，请核对已存记录，勿重复提交。"
                } else dataErrorMessage(error)
                EvidenceOperation.result = operationError
            } finally { EvidenceOperation.busy = false }
        } }
    }

    val pickerEpisode = evidencePickerOwner
    val pickerTarget = evidenceTargetId
    val evidencePicker = androidx.compose.runtime.key(pickerEpisode) { rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (evidencePickerOwner != pickerEpisode || evidenceTargetId != pickerTarget) return@rememberLauncherForActivityResult
        val participationId = pickerTarget
        if (participationId != null && uris.isNotEmpty()) {
            uris.forEach { retainDraftUri(context, it, drafts) }
            evidenceUris = uris
        } else { evidenceTargetId = null; message = "已取消选择，证明未更改" }
    } }
    LaunchedEffect(requestEvidencePicker, pickerEpisode) {
        if (requestEvidencePicker) { requestEvidencePicker = false; evidencePicker.launch(arrayOf("image/*")) }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri ->
        val snapshot = pendingExportSnapshot
        val plan = pendingExportPlan
        pendingExportSnapshot = null
        pendingExportPlan = null
        if (uri == null) {
            message = "已取消本次 Excel 保存"
            snapshot?.close()
            exportBusy = false
        } else if (snapshot == null || plan == null) {
            snapshot?.close()
            message = "导出快照已失效，请重新选择学年导出"
            exportBusy = false
            scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.delete(uri, null, null) } }
        } else {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        var temporary: File? = null
                        try {
                            store.validateAnnualExport(snapshot)
                            temporary = File.createTempFile("activity-score-", ".xlsx", context.cacheDir)
                            temporary.outputStream().use { ScoreExcelExport.write(snapshot, it, plan) }
                            store.validateAnnualExport(snapshot)
                            val destination = context.contentResolver.openOutputStream(uri, "wt")
                                ?: error("无法打开保存位置")
                            destination.use { stream -> temporary.inputStream().use { it.copyTo(stream) } }
                        } catch (error: Exception) {
                            runCatching { context.contentResolver.delete(uri, null, null) }
                            throw error
                        } finally {
                            temporary?.delete()
                        }
                    }
                    message = "${snapshot.scores.academicYear}—${snapshot.scores.academicYear + 1} 学年资料 Excel 已保存（${snapshot.assessment.pendingCount} 项计分待确定）"
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(context.contentResolver, "活动记录.xlsx", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "分享 Excel 文件"))
                    } catch (_: Exception) {
                        message = "Excel 已保存，但无法打开分享面板"
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    android.util.Log.e("AnnualExport", "Generate/save failed", error)
                    exportFailureStage = "生成或保存 Excel"
                    exportFailure = error
                } finally {
                    snapshot.close()
                    exportBusy = false
                }
            }
        }
    }

    LaunchedEffect(EvidenceOperation.busy) {
        // Initial already contains a complete snapshot. Only a finished write from another
        // composition needs this refresh; our own save awaited publication before reporting.
        if (EvidenceOperation.busy) sawBusy = true
        else if (sawBusy) {
            sawBusy = false
            if (!savedPresentationHere) try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            savedPresentationHere = false
        }
    }
    val selectedItem = items.firstOrNull { it.id == selectedItemId }
    val participationCounts = presentation.participationCounts
    val summaries = presentation.summaries
    val allDuplicateReviews = presentation.allReviews
    val pendingDuplicateReviews = presentation.pendingReviews
    val annualScore = presentation.annualScore

    androidx.activity.compose.BackHandler(enabled = tab != 0 && selectedItemId == null && !showItemEditor && !showParticipationEditor && !showAnnualEditor && !showDuplicateReviews) { tab = if (tab in listOf(1, 5)) 4 else 0 }
    fun openExport() {
        if (!exportBusy) {
            if (EvidenceOperation.busy || evidencePending.isNotBlank() || drafts.manualCheck || readError.isNotBlank())
                operationError = "请先重新读取并核对上次操作，再准备导出资料。"
            else { exportYear = selectedYear; showExportYear = true }
        }
    }
    LaunchedEffect(presentation) { if (!exportBusy) exportChoices = emptyMap() }
    // The process-level receipt survives an Activity recreation during a confirmed write.
    // Keep it visible until dismissed, without treating an UNKNOWN/pending notice as success.
    val visibleFeedback = message.ifBlank {
        if (!EvidenceOperation.busy && evidencePending.isBlank()) EvidenceOperation.result else ""
    }
    val expanded = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!expanded) JournalNavigation(tab, { tab = it })
        },
    ) { padding ->
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(padding)) {
        if (expanded) JournalNavigation(tab, { tab = it }, rail = true)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            JournalTopBar(tab, selectedYear, { year ->
                if (showAnnualEditor) message = "学年资料草稿仍绑定当前学年，请先结束当前草稿。" else requestRefresh(year)
            }, { tab = if (tab == 4) 0 else 4 }, { openDraft { editingItem = null; showItemEditor = true } })
            if (tab == 4) TextButton(onClick = { selectedItemId = null; showEvidenceHealth = true }) { Text("证明检查与修复") }
            if (refreshing) Text("正在读取记录…")
            if (evidenceTargetId != null && evidenceUris.isEmpty()) {
                Text("上次证明选择尚未完成；目标仍为参与 #$evidenceTargetId，可重新选择或丢弃。")
                TextButton(onClick = { evidencePickerOwner = java.util.UUID.randomUUID().toString(); requestEvidencePicker = true }) { Text("继续选择证明") }
                TextButton(onClick = { evidenceTargetId = null; evidencePickerOwner = "" }) { Text("丢弃待选证明") }
            }
            if (evidencePending.isNotBlank()) {
                Text(evidencePending.ifBlank { EvidenceOperation.result })
                TextButton(enabled = !EvidenceOperation.busy, onClick = ::reread) { Text("重新读取记录") }
                if (inspecting) TextButton(onClick = { inspecting = false }) { Text("返回上次输入") }
            }
            BackupControls(store, entryVisible = tab == 4, blocked = evidencePending.isNotBlank() || readError.isNotBlank() || EvidenceOperation.busy,
                restoreBlocked = showItemEditor || showParticipationEditor || showAnnualEditor || showDuplicateReviews || historyImportActive ||
                    packageUri.isNotBlank() || evidenceTargetId != null || evidenceRepairId != null,
                onEvidenceProblems = { selectedItemId = null; showEvidenceHealth = true }) {
                selectedItemId = null
                requestRefresh()

            }
            if (numericIssues.isNotEmpty()) {
                Text("发现旧数值异常（${numericIssues.size} 项）：${numericIssues.first()}。" +
                    "请修正对应活动、学年资料或重新导入历史源表；未自动置零。",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                TextButton(onClick = { message = numericIssues.joinToString("\n") }) { Text("查看全部数值诊断") }
            }
            if (visibleFeedback.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(visibleFeedback, modifier = Modifier.weight(1f))
                        TextButton(onClick = { message = ""; EvidenceOperation.result = "" }) { Text("关闭") }
                    }
                }
            }
            if (packageUri.isNotBlank() && stagedPackage == null && !packageLoading) {
                Text("活动包需要重新选择；上次确认前的输入仍保留。")
                TextButton(onClick = { packagePicker.launch(arrayOf("*/*")) }) { Text("重新选择活动包") }
                TextButton(onClick = { packageUri = ""; drafts.removePrefix("package/") }) { Text("丢弃活动包草稿") }
            }
            if (drafts.error.isNotBlank()) {
                Text(drafts.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { drafts.retry() }) { Text("重试保存草稿") }
            } else if (!drafts.saved && (showItemEditor || showParticipationEditor || showAnnualEditor || showDuplicateReviews)) Text("草稿正在写入设备…")
            if (packageLoading) {
                Text("正在读取活动包…", modifier = Modifier.padding(16.dp))
            }
            when (tab) {
                0 -> JournalHomeScreen(presentation,
                    onAdd = { openDraft { editingItem = null; showItemEditor = true } },
                    onPending = { activityPendingOnly = true; recordFilter = "全部"; tab = 2 },
                    onActivities = { activityPendingOnly = false; recordFilter = "全部"; tab = 2 }, onOpen = { selectedItemId = it })
                5 -> AnnualOverviewScreen(
                    summaries = summaries,
                    annualScore = annualScore,
                    onChangeYear = { year ->
                        if (showAnnualEditor) message = "学年资料草稿仍绑定当前学年，请先结束当前草稿。"
                        else requestRefresh(year)
                    },
                    pendingDuplicateCount = pendingDuplicateReviews.size,
                    hasDuplicateReviews = allDuplicateReviews.isNotEmpty(),
                    onReviewDuplicates = { openDraft {
                        pendingDuplicateReviews.firstOrNull()?.let {
                            selectedDuplicateReviewKey = it.key; reviewFingerprint = it.fingerprint
                            showDuplicateReviews = true
                        }
                    } },
                    onManageDuplicates = { showDuplicateReviewList = true },
                    onAdd = { openDraft {
                        editingItem = null
                        showItemEditor = true
                    } },
                    onEditAnnual = { openDraft { showAnnualEditor = true } },
                    onOpenRecords = { dimension ->
                        recordFilter = dimension
                        tab = 2
                    },
                )
                1 -> HistoryScreen(store, historyRows, historyInfo, { requestRefresh() }, ::saveEvidence,
                    blocked = evidencePending.isNotBlank(), suspended = inspecting)
                2 -> JournalActivitiesScreen(presentation, recordFilter, { recordFilter = it }, activityPendingOnly, { activityPendingOnly = it }, { selectedItemId = it })
                3 -> JournalOrganizeScreen(presentation, store, exportChoices,
                    onChoice = { id, choice -> exportChoices = exportChoices + (id to choice) },
                    onPrepare = ::openExport, busy = exportBusy,
                    onOpen = { selectedItemId = it }, onReviews = { showDuplicateReviewList = true },
                    onAnnual = { openDraft { showAnnualEditor = true } })
                else -> JournalMoreScreen(
                    onHistory = { tab = 1 },
                    onPackage = { openDraft { packagePicker.launch(arrayOf("*/*")) } },
                    onAnnual = { openDraft { showAnnualEditor = true } }, onOverview = { tab = 5 },
                    onReviews = { showDuplicateReviewList = true }, onReread = ::reread,
                    onDiagnostics = { message = if (numericIssues.isEmpty()) "当前完整快照未发现旧数值异常。" else numericIssues.joinToString("\n") })
            }
        }
        }
        }
    }

    if (showExportYear) {
        FormDialog(title = "导出学年活动资料", onDismiss = { showExportYear = false }) {
            Text("$exportYear—${exportYear + 1} 学年", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { exportYear -= 1 }) { Text("上一学年") }
                TextButton(onClick = { exportYear += 1 }) { Text("下一学年") }
            }
            Text("导出该学年全部参与事实和已上传证明。缺分、无图或计分待核对可继续导出；未确认分数留空，不构成正式核定成绩。")
            DialogActions(onDismiss = { showExportYear = false }, onConfirm = {
                if (exportBusy) return@DialogActions
                val year = exportYear
                showExportYear = false
                exportBusy = true
                scope.launch {
                    var captured: AnnualExportSnapshot? = null
                    try {
                        withContext(Dispatchers.IO) { captured = store.captureAnnualExport(year) }
                        val capturedAssessment = requireNotNull(captured).assessment
                        if (year != selectedYear || capturedAssessment != presentation.activityAssessment) exportChoices = emptyMap()
                        exportPreview = null
                        pendingExportSnapshot = requireNotNull(captured)
                        captured = null // Ownership moves to summary, picker callback or disposal.
                        showExportSummary = true
                    } catch (error: Exception) {
                        captured?.close()
                        pendingExportSnapshot?.close()
                        pendingExportSnapshot = null
                        exportBusy = false
                        if (error is CancellationException) throw error
                        android.util.Log.e("AnnualExport", "Snapshot preparation failed", error)
                        exportFailureStage = "准备学年快照"
                        exportFailure = error
                    }
                }
            }, confirmLabel = "准备导出摘要")
        }
    }

    if (showExportSummary) pendingExportSnapshot?.let { snapshot ->
        val displayedPlan = exportPreview?.second?.takeIf { it.source === snapshot.assessment }
        val plan = displayedPlan?.takeIf { exportPreview?.first == exportChoices }
        AlertDialog(onDismissRequest = ::discardExport,
            modifier = Modifier.widthIn(max = 720.dp),
            title = { Text("学年资料导出摘要") },
            text = { ExportGroupingSummary(snapshot, exportChoices, displayedPlan, updating = plan == null,
                onChoice = { id, choice -> exportChoices = exportChoices + (id to choice) },
                onView = { id -> discardExport(); selectedItemId = id }) },
            confirmButton = { TextButton(enabled = plan != null && plan.problems.isEmpty(), onClick = {
                if (showExportSummary && plan != null) {
                    pendingExportPlan = plan
                    showExportSummary = false
                    try { exportPicker.launch("学年活动资料-${snapshot.scores.academicYear}-${snapshot.scores.academicYear + 1}.xlsx") }
                    catch (error: Exception) {
                        discardExport(); exportFailureStage = "打开文件保存器"; exportFailure = error
                    }
                }
            }) { Text("继续导出资料") } },
            dismissButton = { TextButton(onClick = ::discardExport) { Text("取消") } })
    }
    exportFailure?.let { error ->
        FormDialog(title = "资料导出未完成", onDismiss = { exportFailure = null }) {
            Text(exportErrorMessage(error, exportFailureStage))
            if (error is ExportBlocked) error.issues.distinctBy { it.itemId }.filter { it.itemId > 0 }.forEach { issue ->
                TextButton(onClick = { exportFailure = null; selectedItemId = issue.itemId }) { Text("查看活动 ${issue.itemId}") }
            }
            if (error is EvidenceProblems) TextButton(onClick = {
                exportFailure = null; selectedItemId = null; showEvidenceHealth = true
            }) { Text("证明检查与修复") }
            TextButton(onClick = { exportFailure = null }) { Text("返回记录") }
        }
    }

    if (showItemEditor && !inspecting) {
        ItemEditor(
            existing = editingItem,
            targetAvailable = editingItem == null || items.any { it.id == editingItem?.id && it.createdAt == editingItem?.createdAt },
            historyRows = historyRows,
            onDismiss = { showItemEditor = false; editingItem = null },
            onSave = { draft, imageUris ->
                val original = editingItem
                var createdItemId: Long? = null
                saveEvidence(if (original == null) "记录已保存" else "条目已更新", action = {
                    if (original == null) {
                        createdItemId = store.createItem(
                            title = draft.title,
                            dimension = draft.dimension,
                            subcategory = draft.subcategory,
                            role = draft.role,
                            referenceScore = draft.referenceScore,
                            note = draft.note,
                            eventDate = draft.eventDate,
                            eventNote = draft.eventNote,
                            imageUris = imageUris,
                        )
                    } else {
                        val current = store.listItems().firstOrNull { it.id == original.id }
                        if (current?.createdAt != original.createdAt) throw DataOperationException(WriteState.NOT_FOUND, "活动目标已变化，请保留输入并核对当前记录。")
                        store.updateItem(
                            original.copy(
                                title = draft.title,
                                dimension = draft.dimension,
                                subcategory = draft.subcategory,
                                role = draft.role,
                                referenceScore = draft.referenceScore,
                                note = draft.note,
                            )
                        )
                    }
                }, after = {
                    showItemEditor = false; editingItem = null
                    if (original == null && createdItemId != null) selectedItemId = createdItemId
                })
            },
        )
    }

    if (selectedItem != null && (inspecting || !showItemEditor && !showParticipationEditor && !showDuplicateReviews && !showEvidenceHealth)) {
        val participations = participationsByItem[selectedItem.id].orEmpty()
        JournalDetailScreen(
            year = selectedYear,
            assessment = presentation.activityAssessment.activities.firstOrNull { it.activity.item.id == selectedItem.id },
            feedback = visibleFeedback,
            onClearFeedback = { message = ""; EvidenceOperation.result = "" },
            onReview = {
                val review = allDuplicateReviews.firstOrNull { review -> review.academicYear == selectedYear && when (review) {
                    is RepeatedParticipationReview -> review.item.id == selectedItem.id
                    is SimilarItemsReview -> review.items.any { it.id == selectedItem.id }
                } }
                if (review != null) openDraft {
                    selectedDuplicateReviewKey = review.key; reviewFingerprint = review.fingerprint; showDuplicateReviews = true
                } else message = "本活动无重复参与核对；单次参考认可沿用现有规则，可在编辑活动中补充分数。"
            },
            onReplaceEvidence = { photo -> openDraft { requestedReplacement = photo; showEvidenceHealth = true } },
            item = selectedItem,
            participations = participations,
            store = store,
            onDismiss = { selectedItemId = null },
            onEdit = { openDraft {
                editingItem = selectedItem
                showItemEditor = true
            } },
            onDelete = {
                saveEvidence("活动已删除", { store.deleteItem(selectedItem.id) }, {
                    if (selectedItemId == selectedItem.id) selectedItemId = null
                })
            },
            onAddParticipation = { openDraft {
                participationItemId = selectedItem.id
                participationParentCreatedAt = selectedItem.createdAt
                editingParticipation = null
                showParticipationEditor = true
            } },
            onEditParticipation = { participation -> openDraft {
                participationItemId = participation.itemId
                participationParentCreatedAt = selectedItem.createdAt
                editingParticipation = participation
                showParticipationEditor = true
            } },
            onDeleteParticipation = { participation ->
                saveEvidence("参与记录已删除", { store.deleteParticipation(participation.id) })
            },
            onAddEvidence = { participation -> openDraft {
                val owner = java.util.UUID.randomUUID().toString()
                evidencePickerOwner = owner
                if (evidencePickerOwner == owner) {
                    evidenceTargetId = participation.id
                    if (evidenceTargetId == participation.id) requestEvidencePicker = true
                }
            } },
            onDeleteEvidence = { photo ->
                saveEvidence("证明图片已移除", { store.deleteEvidence(photo) })
            },
            onShare = { participation ->
                shareSelection = selectedItem to participation
            },
        )
    }

    shareSelection?.let { (item, participation) ->
        ShareActivityDialog(
            item = item,
            participation = participation,
            busy = shareBusy,
            onDismiss = { shareSelection = null },
            onShare = { selectedPhotos ->
                shareBusy = true
                scope.launch {
                    var file: File? = null
                    try {
                        file = withContext(Dispatchers.IO) {
                            val directory = File(context.cacheDir, "activity-share").also { it.mkdirs() }
                            val safeTitle = item.title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
                                .take(24).ifBlank { "活动" }
                            val suffix = java.util.UUID.randomUUID().toString().take(8)
                            val destination = File(directory,
                                "班级活动-${participation.eventDate}-$safeTitle-$suffix.zip")
                            try {
                                destination.outputStream().use { output ->
                                    ActivityPackage.write(store, item, participation, selectedPhotos, output)
                                }
                                destination
                            } catch (error: Exception) {
                                destination.delete()
                                throw error
                            }
                        }
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file!!
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(context.contentResolver, "活动包.zip", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "分享活动包"))
                        shareSelection = null
                    } catch (error: Exception) {
                        file?.delete()
                        message = dataErrorMessage(error)
                    } finally {
                        shareBusy = false
                    }
                }
            },
        )
    }

    stagedPackage?.takeIf { packageUri.isNotBlank() && !inspecting && !historyImportActive && evidenceTargetId == null && evidenceRepairId == null &&
        !showItemEditor && !showParticipationEditor && !showAnnualEditor && !showDuplicateReviews }?.let { staged ->
        ImportActivityDialog(
            staged = staged,
            alreadyImported = staged.activity.shareId in knownShareIds,
            possibleDuplicate = items.any { item ->
                item.title.trim().equals(staged.activity.title, ignoreCase = true) &&
                    item.dimension == staged.activity.dimension &&
                    participationsByItem[item.id].orEmpty().any {
                        it.eventDate == staged.activity.eventDate
                    }
            },
            busy = EvidenceOperation.busy,
            onDismiss = {
                staged.close()
                stagedPackage = null
                packageUri = ""
                drafts.removePrefix("package/")
            },
            onImport = { role, score, selectedPhotos ->
                saveEvidence("活动和 ${selectedPhotos.size} 张图片已导入", {
                    val activity = staged.activity
                    require(!store.hasSharedParticipation(activity.shareId)) { "该活动已导入" }
                    store.createItem(activity.title, activity.dimension, activity.subcategory, role, score,
                        activity.note, activity.eventDate, activity.eventNote, selectedPhotos.map(Uri::fromFile), activity.shareId)
                }, { staged.close(); stagedPackage = null; packageUri = ""; tab = 2 })
            },
        )
    }

    if (showParticipationEditor && !inspecting) {
        val targetItemId = editingParticipation?.itemId ?: participationItemId
        ParticipationEditor(
            existing = editingParticipation,
            targetAvailable = items.any { it.id == targetItemId && it.createdAt == participationParentCreatedAt } &&
                (editingParticipation == null || participationsByItem[targetItemId].orEmpty().any { it.id == editingParticipation?.id }),
            onDismiss = { showParticipationEditor = false },
            onSave = { date, note, images ->
                val participation = editingParticipation
                saveEvidence(if (participation == null) "已添加一次参与" else "参与记录已更新", action = {
                    val parent = store.listItems().firstOrNull { it.id == targetItemId }
                    if (parent?.createdAt != participationParentCreatedAt || parent == null)
                        throw DataOperationException(WriteState.NOT_FOUND, "所属活动已不存在或目标已变化，请保留输入并核对。")
                    if (participation != null) {
                        val current = store.listParticipations(parent.id).firstOrNull { it.id == participation.id }
                        if (current == null || current.itemId != participation.itemId || current.eventDate != participation.eventDate || current.note != participation.note)
                            throw DataOperationException(WriteState.CONFLICT, "参与目标已变化，请保留输入并核对当前参与。")
                    }
                    if (participation == null) store.addParticipation(
                        targetItemId ?: throw DataOperationException(WriteState.NOT_FOUND, "所属活动不存在，请保留输入并核对活动。"), date, note, images)
                    else store.updateParticipation(participation.id, date, note, images)
                }, after = { showParticipationEditor = false; editingParticipation = null })
            },
        )
    }

    if (showAnnualEditor && !inspecting) {
        AnnualInputsDialog(
            year = selectedYear,
            existing = annualInputs,
            onDismiss = { showAnnualEditor = false },
            onSave = { inputs ->
                saveEvidence("学年成绩资料已保存", { store.saveAnnualInputs(inputs) }, { showAnnualEditor = false })
            },
        )
    }
    if (showDuplicateReviewList) {
        FormDialog(title = "重复记录核对", onDismiss = { showDuplicateReviewList = false }) {
            allDuplicateReviews.forEach { review ->
                val pending = pendingDuplicateReviews.any { it.key == review.key }
                val label = when (review) {
                    is RepeatedParticipationReview -> review.item.title
                    is SimilarItemsReview -> "${review.title}（同名条目）"
                }
                OutlinedButton(
                    onClick = { openDraft {
                        selectedDuplicateReviewKey = review.key
                        reviewFingerprint = review.fingerprint
                        showDuplicateReviewList = false
                        showDuplicateReviews = true
                    } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("$label · ${review.academicYear}—${review.academicYear + 1} · ${if (pending) "待确认" else "已确认"}")
                }
            }
            TextButton(onClick = { showDuplicateReviewList = false }) { Text("关闭") }
        }
    }
    if (showDuplicateReviews && !inspecting) {
        val review = selectedDuplicateReviewKey?.let { key ->
            allDuplicateReviews.firstOrNull { it.key == key }
        } ?: if (selectedDuplicateReviewKey == null) pendingDuplicateReviews.firstOrNull() else null
        if (review == null || reviewFingerprint.isNotBlank() && review.fingerprint != reviewFingerprint) {
            FormDialog("核对目标已变化", onDismiss = {}) {
                Text("参与或活动已变化，不能将旧确认分应用到当前记录。上次原始输入仍保留：")
                val oldScores = drafts.state<Map<Long, String>>("review/$selectedDuplicateReviewKey/$reviewFingerprint/scoreTexts") { emptyMap() }.value
                oldScores.forEach { (id, value) -> Text("参与 #$id：$value") }
                TextButton(onClick = {
                    drafts.removePrefix("review/"); showDuplicateReviews = false
                    selectedDuplicateReviewKey = null; reviewFingerprint = ""
                }) { Text("丢弃旧核对草稿") }
            }
        } else {
            DuplicateReviewDialog(
                review = review,
                remaining = pendingDuplicateReviews.size,
                previous = duplicateConfirmations[review.key],
                onDismiss = {
                    drafts.removePrefix("review/")
                    showDuplicateReviews = false
                    selectedDuplicateReviewKey = null
                },
                onConfirm = { confirmation ->
                    saveEvidence("重复记录已核对", {
                        val current = store.loadSnapshot(review.academicYear)
                        val actual = DuplicateReviewFinder.all(current.items, current.participations).firstOrNull { it.key == confirmation.key }
                        if (actual?.fingerprint != confirmation.fingerprint) throw DataOperationException(WriteState.CONFLICT, "核对目标已变化，请保留输入并核对当前参与。")
                        store.saveDuplicateConfirmation(confirmation)
                    }, {
                        selectedDuplicateReviewKey = null
                        showDuplicateReviews = false
                        showDuplicateReviewList = true
                    })
                },
                onOpenRecord = { itemId ->
                    showDuplicateReviews = false
                    selectedDuplicateReviewKey = null
                    recordFilter = "全部"
                    tab = 2
                    selectedItemId = itemId
                },
            )
        }
    }
    EvidenceControls(store, showEvidenceHealth && !inspecting, requestedReplacement = requestedReplacement, onReplacementHandled = { requestedReplacement = null }, suspended = inspecting, blocked = evidencePending.isNotBlank(), onClose = { showEvidenceHealth = false },
        onOpenRecord = { tab = 2; recordFilter = "全部"; selectedItemId = it },
        onWrite = ::saveEvidence)
    if (evidenceUris.isNotEmpty() && !inspecting) FormDialog("确认添加证明", onDismiss = {
        evidenceUris = emptyList(); evidenceTargetId = null
    }) {
        val target = evidenceTargetId
        val available = participationsByItem.values.flatten().any { it.id == target }
        Text("参与 #$target，待添加 ${evidenceUris.size} 张；确认前不会复制图片或写入证明引用。")
        DraftUriWarning(evidenceUris)
        if (!available) Text("目标参与已不存在，请核对当前记录。", color = MaterialTheme.colorScheme.error)
        DialogActions(onDismiss = { evidenceUris = emptyList(); evidenceTargetId = null }, onConfirm = {
            if (available && target != null) {
                val uris = evidenceUris
                saveEvidence("已添加 ${uris.size} 张证明图片", { store.addEvidence(target, uris) }, {
                    evidenceUris = emptyList(); evidenceTargetId = null
                })
            }
        }, confirmLabel = "确认添加")
    }
    if (EvidenceOperation.busy) {
        FormDialog(title = "正在保存数据", onDismiss = {}) { Text("正在读取、验证并保存，请稍候。") }
    }
    if (drafts.manualCheck && !EvidenceOperation.busy) {
        AlertDialog(onDismissRequest = {}, title = { Text("上次保存需要人工核对") },
            text = { Text("已读取数据库。没有持久化事务回执，草稿不能证明保存成功或失败。查看当前记录后，可以明确丢弃上次输入并结束本次草稿保护；不会再次执行保存。") },
            confirmButton = { TextButton(onClick = {
                operationError = ""; tab = 2
                inspecting = true
                drafts.manualCheck = false
            }) { Text("查看当前记录") } },
            dismissButton = { TextButton(enabled = !rechecking, onClick = {
                rechecking = true
                scope.launch {
                try {
                    val resolution = withContext(Dispatchers.IO) { store.recheckPendingWrite() }
                    if (!refreshOwner.active) return@launch
                    if (resolution == WriteState.UNKNOWN) { operationError = "提交状态仍无法核实；保护保持。" }
                    else if (refresh()) { evidencePending = ""; EvidenceOperation.pendingAfter = null
                        drafts.manualCheck = false; drafts.clearInputs() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if(refreshOwner.active) readError = readErrorMessage(error) }
                finally { rechecking = false }
                }
            }) { Text("已核对，丢弃上次输入") } })
    }
    if (operationError.isNotBlank() && !drafts.manualCheck) AlertDialog(
        onDismissRequest = { operationError = "" }, title = { Text("操作结果") }, text = { Text(operationError) },
        confirmButton = { TextButton(onClick = { operationError = "" }) { Text("保留输入并返回") } },
        dismissButton = { TextButton(onClick = ::reread, enabled = !EvidenceOperation.busy) { Text("重新读取核对") } })
    if (evidencePending.isNotBlank() && !inspecting && !drafts.manualCheck && !EvidenceOperation.busy && operationError.isBlank() &&
        !showItemEditor && !showParticipationEditor && !showAnnualEditor && !showDuplicateReviews && stagedPackage == null) {
        AlertDialog(onDismissRequest = {}, title = { Text("上次操作尚未核实") },
            text = { Text("请先重新读取核对上次操作，再打开新的编辑或删除。当前不会自动重试或清理文件。" +
                if(drafts.error.isNotBlank()) "\n${drafts.error}" else "") },
            confirmButton = { TextButton(onClick = ::reread) { Text("重新读取核对") } })
    }
    if (readError.isNotBlank()) AlertDialog(
        onDismissRequest = {}, title = { Text("数据未完整读取") },
        text = { Text("$readError\n当前显示的数据未刷新，编辑内容已保留。请先重试读取；暂不允许保存、删除、导入或恢复。") },
        confirmButton = { TextButton(onClick = ::reread, enabled = !EvidenceOperation.busy) { Text("重试读取") } })
    if (exportBusy && pendingExportSnapshot == null) {
        FormDialog(title = "正在处理学年资料", onDismiss = {}) {
            Text("正在读取计分数据和证明快照，请稍候…")
        }
    }
}

