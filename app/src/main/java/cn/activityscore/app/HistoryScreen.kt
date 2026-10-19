package cn.activityscore.app

import android.database.Cursor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import cn.activityscore.app.data.LocalSheetInfo
import cn.activityscore.app.data.WorkbookImport
import cn.activityscore.app.data.WorkbookPreview
import cn.activityscore.app.data.ReferenceKey
import cn.activityscore.app.data.mergedDuplicateCount
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.activityscore.app.data.ColumnMapping
import cn.activityscore.app.data.HistoryInfo
import cn.activityscore.app.data.HistoryMapper
import cn.activityscore.app.data.HistoryRow
import cn.activityscore.app.data.ImportPreview
import cn.activityscore.app.data.ImportIssue
import cn.activityscore.app.data.LocalSheetReader
import cn.activityscore.app.data.MappingProfiles
import cn.activityscore.app.data.ParsedSheet
import cn.activityscore.app.data.RecordStore
import cn.activityscore.app.data.copyImportFile
import cn.activityscore.app.domain.HistoricalMatcher
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(
    store: RecordStore,
    historyRows: List<HistoryRow>,
    info: HistoryInfo?,
    onHistoryChanged: () -> Unit,
    onWrite: (String, () -> Unit, suspend () -> Unit) -> Unit,
    blocked: Boolean = false,
    suspended: Boolean = false,
) {
    var showImport by draftState("route/historyImport") { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf("") }
    var query by draftState("historyQuery") { mutableStateOf("") }
    val activities = rememberHistoryActivities(historyRows)
    val visible = remember(activities, query) {
        if (query.isBlank()) activities.take(100)
        else activities.filter {
            it.activityName.contains(query.trim(), ignoreCase = true) ||
                it.dimension.contains(query.trim(), ignoreCase = true)
        }.take(100)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text(
            "REFERENCE LIBRARY",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text("历史评分", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "查找往年活动，填记录时有据可参考。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(22.dp))
        if (importMessage.isNotBlank()) Text(importMessage)
        if (info == null) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("还没有历史评分表", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "导入后可搜索活动，并在新建记录时引用往年参考分数。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(enabled = !blocked, onClick = { showImport = true }) { Text("导入本地 Excel / CSV") }
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("当前参考表", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(info.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "共 ${info.rowCount} 条记录 · ${DateFormat.getDateInstance().format(Date(info.importedAt))} 导入",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(enabled = !blocked, onClick = { showImport = true }) { Text("更换历史评分表") }
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("搜索活动名称或维度") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(26.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("活动条目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "共 ${activities.size} 个",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            visible.forEach { activity ->
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Text(activity.activityName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOf(activity.dimension, activity.subcategory)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (activity.needsReview) {
                        Text(activity.variantLabel, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    activity.roles.forEach { role ->
                        Text(
                            "${role.label.ifBlank { "未注明身份" }}：${role.referenceScore} 分",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ReferenceSources(role.sources, role.sourceRow)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (visible.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("没有找到对应的历史活动。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (activities.size > 100 && query.isBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("先显示前 100 个活动，可搜索查找其他活动。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                Text(
                    "更换参考表不会修改已保存的活动和参考分数。",
                    modifier = Modifier.fillMaxWidth().padding(15.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (showImport && !suspended) {
        HistoryImportDialog(
            store = store,
            onWrite = onWrite,
            onDismiss = { showImport = false },
            onImported = { result, duplicates ->
                importMessage = "已导入 ${result.records.size} 行，跳过 ${result.issues.size} 行" +
                    (if (duplicates > 0) "，合并重复 $duplicates 行" else "") +
                    if (result.issues.isEmpty()) "" else "；异常及未选用的冲突行已按确认跳过。"
                onHistoryChanged()
                showImport = false
            },
        )
    }
}

@Composable
private fun HistoryImportDialog(
    store: RecordStore,
    onWrite: (String, () -> Unit, suspend () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onImported: (ImportPreview, Int) -> Unit,
) {
    val context = LocalContext.current
    val drafts = LocalDraft.current
    val formId = draftState("history/formId") { mutableStateOf(java.util.UUID.randomUUID().toString()) }.value
    var pickerOwner by draftState("route/historyPickerOwner") { mutableStateOf("") }
    var sourceUri by draftState("history/sourceUri") { mutableStateOf("") }
    var sourceHash by draftState("history/sourceHash") { mutableStateOf("") }
    var serial by remember { mutableStateOf(0) }
    val profiles = remember { MappingProfiles(MainActivity.draftContext(context.applicationContext)) }
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<File?>(null) }
    var fileName by draftState("history/fileName") { mutableStateOf("") }
    var sheetNames by remember { mutableStateOf(emptyList<String>()) }
    var sheetInfo by remember { mutableStateOf(emptyList<LocalSheetInfo>()) }
    var selectedSheets by draftState("history/selectedSheets") { mutableStateOf(emptySet<String>()) }
    var selectionSet by draftState("history/selectionSet") { mutableStateOf(false) }
    var workbookPreview by remember { mutableStateOf<WorkbookPreview?>(null) }
    var scoreOverrides by remember { mutableStateOf(emptyMap<ImportIssue, String>()) }
    var choices by remember { mutableStateOf(emptyMap<ReferenceKey, Int>()) }
    var skipIssues by remember { mutableStateOf(false) }
    var analysisJob by remember { mutableStateOf<Job?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0L) }
    fun invalidatePreview() {
        revision++
        analysisJob?.cancel()
        analyzing = false
        workbookPreview = null
        scoreOverrides = emptyMap()
        choices = emptyMap()
        skipIssues = false
    }
    var sheetName by draftState("history/sheetName") { mutableStateOf("") }
    var sheet by remember { mutableStateOf<ParsedSheet?>(null) }
    var headerRowText by draftState("history/headerRowText") { mutableStateOf("1") }
    var mapping by draftState("history/mapping") { mutableStateOf(ColumnMapping()) }
    var mappingLookup by draftState("history/mappingLookup") { mutableStateOf(false) }
    var mappingRequest by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    suspend fun finishMapping(parsed: ParsedSheet, text: String, request: Long) {
        val sourceRequest = serial
        val proposed = withContext(Dispatchers.IO) {
            val headers = parsed.headers(text.toIntOrNull() ?: -1)
            profiles.find(headers) ?: HistoryMapper.suggest(headers)
        }
        if (request == mappingRequest && sourceRequest == serial && sheet === parsed && headerRowText == text) {
            mapping = proposed
            mappingLookup = false
        }
    }
    fun setMapping(value: ColumnMapping) {
        invalidatePreview()
        mappingRequest += 1 // manual choice supersedes a queued preference lookup
        mappingLookup = false
        mapping = value
    }
    suspend fun mapHeaders(parsed: ParsedSheet) {
        mappingLookup = true
        check(mappingLookup) { "草稿空间不足，未开始列映射查找。请缩短输入后重试。" }
        val detected = (1..minOf(20, parsed.rows.size)).maxByOrNull { rowNumber ->
            val proposed = HistoryMapper.suggest(parsed.headers(rowNumber))
            listOf(proposed.activity, proposed.dimension, proposed.score)
                .count { it >= 0 }
        } ?: 1
        headerRowText = detected.toString()
        finishMapping(parsed, headerRowText, ++mappingRequest)
    }

    fun close() {
        if (EvidenceOperation.busy) return
        serial++
        invalidatePreview()
        file?.delete()
        drafts.removePrefix("history/")
        onDismiss()
    }

    fun load(uri: android.net.Uri, restoring: Boolean) {
        invalidatePreview()
        sheet = null
        sheetNames = emptyList(); sheetInfo = emptyList()
        file?.delete(); file = null
        serial++
        val request = serial
        retainDraftUri(context, uri, drafts)
        sourceUri = uri.toString()
        loading = true; error = ""
        scope.launch {
            var stagedFile: File? = null
            try {
                val selectedName = if (uri.scheme == "content") displayName(context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )) ?: uri.lastPathSegment.orEmpty() else uri.lastPathSegment.orEmpty()
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    stagedFile = copyImportFile(context, uri, selectedName)
                }
                val selectedFile = requireNotNull(stagedFile)
                val digest = withContext(Dispatchers.IO) {
                    val hash = java.security.MessageDigest.getInstance("SHA-256")
                    selectedFile.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if(n < 0) break; hash.update(buffer, 0, n) }
                    }
                    hash.digest().joinToString("") { "%02x".format(it) }
                }
                if (restoring && sourceHash.isNotBlank() && sourceHash != digest)
                    error("源文件已变化，映射与输入仍保留；请重新选择并核对。")
                val infos = withContext(Dispatchers.IO) { LocalSheetReader.sheetInfo(selectedFile) }
                val names = infos.map { it.name }
                val chosen = if (restoring && sheetName.isNotBlank()) sheetName else infos.firstOrNull { !it.hidden }?.name ?: names.first()
                require(chosen in names) { "上次工作表已不存在，请重新选择。" }
                val first = withContext(Dispatchers.IO) { LocalSheetReader.read(selectedFile, chosen) }
                if (request != serial) return@launch
                file?.delete(); file = selectedFile; stagedFile = null
                fileName = selectedName; sheetNames = names; sheetInfo = infos; sheetName = chosen; sheet = first
                selectedSheets = if (restoring && (selectionSet || selectedSheets.isNotEmpty())) selectedSheets.intersect(names.toSet())
                    else setOf(chosen)
                selectionSet = true
                if (!restoring || sourceHash.isBlank()) mapHeaders(first)
                else if (mappingLookup) finishMapping(first, headerRowText, ++mappingRequest)
                sourceHash = digest
            } catch (problem: Exception) {
                if (problem is kotlinx.coroutines.CancellationException) throw problem
                if (request == serial) error = "无法重新读取源文件，请重新选择。列映射及表头输入仍保留。" + problem.message.orEmpty()
            } finally {
                stagedFile?.delete()
                if (request == serial) loading = false
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (sourceUri.isNotBlank()) load(android.net.Uri.parse(sourceUri), true)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        // Confirmed SQL consumes the captured preview rows, never this temporary source file.
        onDispose { file?.delete() }
    }
    val picker = androidx.compose.runtime.key(formId) { rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (pickerOwner != formId) return@rememberLauncherForActivityResult
        pickerOwner = ""
        if (uri != null) load(uri, false)
    } }

    val headerRow = headerRowText.toIntOrNull()
    val headers = if (headerRow != null && headerRow > 0) sheet?.headers(headerRow).orEmpty()
    else emptyList()
    val preview = workbookPreview
    val importScroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(preview) { importScroll.scrollTo(0) }
    val reviewed = remember(preview, scoreOverrides) { preview?.withScoreOverrides(scoreOverrides) }
    val resolved = remember(reviewed, choices, skipIssues) {
        reviewed?.let { runCatching { it.resolve(choices, skipIssues) }.getOrNull() }
    }

    fun analyze() {
        val selectedFile = file ?: return
        val row = headerRow ?: return
        if (!mapping.isValid(headers) || selectedSheets.isEmpty()) return
        invalidatePreview()
        val request = revision
        val sourceRequest = serial
        val selected = sheetNames.filter { it in selectedSheets }
        val savedHeaders = headers.toList()
        val savedMapping = mapping
        analyzing = true; error = ""; progress = "正在检查工作表…"
        analysisJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job]!!
                    val importer = WorkbookImport(savedHeaders, row, savedMapping)
                    var count = 0
                    LocalSheetReader.readSelected(selectedFile, selected, { job.ensureActive() }) { parsed ->
                        importer.add(parsed) { job.ensureActive() }
                        count++
                        val status = "已检查 $count / ${selected.size} 张：${parsed.name}"
                        scope.launch { if (request == revision) progress = status }
                    }
                    importer.finish { job.ensureActive() }
                }
                if (request == revision && sourceRequest == serial) workbookPreview = result
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (problem: Exception) {
                if (request == revision) error = problem.message ?: "检查失败，请核对源文件后重试"
            } finally {
                if (request == revision) analyzing = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!loading && !EvidenceOperation.busy) close() }) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.heightIn(max = 700.dp).verticalScroll(importScroll)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (preview == null) "导入本地历史评分表" else "核对导入内容", style = MaterialTheme.typography.titleLarge)
                if (preview == null) {
                    Text("支持 .xlsx 多工作表、.csv。选择分类分表，检查后合并导入。")
                    Text("非数字分数可从原文选取数字，或手动指定分数并保留项目；也可确认跳过。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !loading && !EvidenceOperation.busy, onClick = {
                        invalidatePreview()
                        pickerOwner = formId
                        picker.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/csv", "text/comma-separated-values", "application/octet-stream",
                        ))
                    }) { Text(if (file == null) "选择文件" else "重新选文件") }
                    if (loading) Text("正在读取表格…")
                    if (drafts.error.isNotBlank()) Text(drafts.error, color = MaterialTheme.colorScheme.error)
                    if (file != null) Text("文件：$fileName")
                    if (sheetNames.isNotEmpty() && !loading && !EvidenceOperation.busy) {
                        Text("选择要合并的工作表（已选 ${selectedSheets.size} 张）", style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = { invalidatePreview(); selectedSheets = sheetNames.toSet() }) { Text("全选") }
                            TextButton(onClick = { invalidatePreview(); selectedSheets = emptySet() }) { Text("全不选") }
                        }
                        sheetInfo.forEach { info ->
                            val checked = info.name in selectedSheets
                            Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                                onValueChange = { selected ->
                                    invalidatePreview()
                                    selectedSheets = if (selected) selectedSheets + info.name else selectedSheets - info.name
                                }).padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(info.name + if (info.hidden) "（隐藏表）" else "", Modifier.weight(1f))
                            }
                        }
                        if (sheetNames.size > 1) OptionPicker("用这张表设置共用列映射", sheetName, sheetNames) { chosen ->
                            val selectedFile = file ?: return@OptionPicker
                            invalidatePreview()
                            loading = true
                            scope.launch {
                                try {
                                    val parsed = withContext(Dispatchers.IO) { LocalSheetReader.read(selectedFile, chosen) }
                                    sheetName = chosen
                                    sheet = parsed
                                    mapHeaders(parsed)
                                    error = ""
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (problem: Exception) { error = problem.message ?: "工作表读取失败" }
                                finally { loading = false }
                            }
                        }
                        Text("分类使用表内映射的列；工作表名用于追溯来源。表头行号共用，列按名称核对。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (sheet != null && !loading && !EvidenceOperation.busy) {
                        OutlinedTextField(
                            value = headerRowText,
                            enabled = !loading && !EvidenceOperation.busy,
                            onValueChange = { text ->
                                invalidatePreview()
                                val request = ++mappingRequest
                                val number = text.toIntOrNull()
                                if (number != null && number > 0) {
                                    val parsed = sheet!!
                                    mappingLookup = true
                                    if (!mappingLookup) return@OutlinedTextField
                                    headerRowText = text
                                    if (headerRowText != text) { mappingLookup = false; return@OutlinedTextField }
                                    scope.launch {
                                        try { finishMapping(parsed, text, request) }
                                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (problem: Exception) {
                                            if (request == mappingRequest) error = "列映射读取失败，请手动选择对应列。"
                                        }
                                    }
                                } else { headerRowText = text; mappingLookup = false }
                            },
                            label = { Text("表头所在行") }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        if (headers.isEmpty()) Text("这一行没有列名，请检查表头行号。")
                        else {
                            Text("将原表列对应到 App 字段", style = MaterialTheme.typography.titleMedium)
                            ColumnPicker("活动名称 *", headers, mapping.activity) {
                                setMapping(mapping.copy(activity = it))
                            }
                            ColumnPicker("所属维度 / 大类 *", headers, mapping.dimension) {
                                setMapping(mapping.copy(dimension = it))
                            }
                            ColumnPicker("参考分数 *", headers, mapping.score) {
                                setMapping(mapping.copy(score = it))
                            }
                            ColumnPicker("职务 / 获奖等级", headers, mapping.role) {
                                setMapping(mapping.copy(role = it))
                            }
                            Text("建议映射身份 / 获奖等级，避免不同身份的正常分数被判断为冲突。",
                                style = MaterialTheme.typography.bodySmall)
                            ColumnPicker("小分类", headers, mapping.subcategory) {
                                setMapping(mapping.copy(subcategory = it))
                            }
                        }
                    }
                    if (sheet != null && !loading && !mappingLookup && !EvidenceOperation.busy) {
                        OutlinedButton(enabled = !analyzing && mapping.isValid(headers) && selectedSheets.isNotEmpty(),
                            onClick = ::analyze, modifier = Modifier.fillMaxWidth()) {
                            Text("检查所选工作表")
                        }
                    }
                } else {
                    Text("文件：$fileName · ${preview.sheets.size} 张工作表")
                    TextButton(enabled = !EvidenceOperation.busy, onClick = { invalidatePreview() }) {
                        Text("返回选择与列映射")
                    }
                }
                if (analyzing) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(24.dp).width(24.dp), strokeWidth = 2.dp)
                        Text(progress, Modifier.weight(1f).padding(start = 12.dp))
                        TextButton(onClick = { invalidatePreview() }) { Text("停止检查") }
                    }
                }
                if (preview != null) {
                    HistoryWorkbookReview(preview, requireNotNull(reviewed), scoreOverrides, { issue, text ->
                        val updated = if (text == null) scoreOverrides - issue else scoreOverrides + (issue to text)
                        if (updated != scoreOverrides) {
                            scoreOverrides = updated
                            // New grouping can reorder options: previous indices must never select a different score.
                            choices = emptyMap()
                            skipIssues = false
                        }
                    }, choices, { key, choice -> choices = choices + (key to choice) },
                        skipIssues, { skipIssues = it }, enabled = !EvidenceOperation.busy)
                    if (resolved != null) Text("将导入 ${resolved.records.size} 条，跳过 ${resolved.issues.size} 行，合并重复 ${resolved.mergedDuplicateCount} 行。")
                    else if (!preview.hasSheetErrors) Text("请指定异常分数或确认跳过，并处理全部冲突；没有有效记录时不能替换参考库。")
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (mappingLookup) Text("正在读取列映射…")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = ::close, enabled = !loading && !EvidenceOperation.busy) { Text("取消") }
                    Button(
                        enabled = !loading && !analyzing && !mappingLookup && !EvidenceOperation.busy && resolved != null,
                        onClick = {
                            if (loading || analyzing || mappingLookup || EvidenceOperation.busy) return@Button
                            val result = requireNotNull(resolved)
                            val duplicates = result.mergedDuplicateCount
                            val savedFileName = fileName
                            val savedHeaders = headers.toList()
                            val savedMapping = mapping
                            onWrite("历史评分表已替换", { store.replaceHistory(savedFileName, result.records) }, {
                                // Preferences are ancillary; a preference failure cannot undo a committed history.
                                withContext(Dispatchers.IO) {
                                    runCatching { profiles.save(savedHeaders, savedMapping) }
                                        .onFailure { android.util.Log.w("HistoryImport", "Mapping preference save failed", it) }
                                }
                                file?.delete()
                                drafts.removePrefix("history/")
                                onImported(result, duplicates)
                            })
                        },
                    ) { Text("确认替换参考库") }
                }
            }
        }
    }
}

private fun displayName(cursor: Cursor?): String? = cursor?.use {
    if (it.moveToFirst()) it.getString(0) else null
}

@Composable
private fun ColumnPicker(label: String, headers: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf("不使用") + headers.mapIndexed { index, value ->
        "${index + 1}. ${value.ifBlank { "（空列名）" }}"
    }
    OptionPicker(label, options.getOrElse(selected + 1) { "不使用" }, options) {
        onSelect(options.indexOf(it) - 1)
    }
}

@Composable
private fun OptionPicker(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}
