package cn.activityscore.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.activityscore.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-scoped operation status survives Activity recreation; no new persistence or backup fields. */
internal object EvidenceOperation {
    var busy by mutableStateOf(false)
    var result by mutableStateOf("")
    var pendingAfter: (suspend () -> Unit)? = null
}

@Composable
fun EvidenceControls(store: RecordStore, open: Boolean, requestedReplacement: EvidencePhoto? = null, onReplacementHandled: () -> Unit = {}, suspended: Boolean = false, blocked: Boolean = false, onClose: () -> Unit,
    onOpenRecord: (Long) -> Unit, onWrite: (String, () -> Unit, suspend () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val drafts = LocalDraft.current
    var issues by remember { mutableStateOf<List<EvidenceIssue>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    var replacing by draftState("route/evidenceRepairId") { mutableStateOf<Long?>(null) }
    var replacementPhoto by draftState("route/evidenceRepairPhoto") { mutableStateOf<EvidencePhoto?>(null) }
    var replacementUri by draftState("route/evidenceRepairUri") { mutableStateOf<android.net.Uri?>(null) }
    var pickerOwner by draftState("route/repairPickerOwner") { mutableStateOf("") }
    var requestPicker by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EvidenceIssue?>(null) }
    fun scan() {
        if (scanning || EvidenceOperation.busy) return
        scanning = true; scanned = false
        scope.launch {
            try {
                issues = withContext(Dispatchers.IO) { store.evidenceDiagnostics { n, total -> progress = "正在检查 $n / $total 张证明" } }
                scanned = true
            } catch (error: Exception) { result = "检查未完成：${dataErrorMessage(error)}" }
            finally { scanning = false }
        }
    }
    val episode = pickerOwner
    val pickerPhoto = replacementPhoto
    val picker = key(episode) { rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (pickerOwner != episode || replacementPhoto != pickerPhoto) return@rememberLauncherForActivityResult
        val photo = pickerPhoto; val targetId = replacing
        if (uri == null) { result = "已取消选择，原证明未更改"; replacementPhoto = null; replacing = null }
        else if (photo == null) result = "替换证明 $targetId 未开始：页面已重建，请重新检查并选择证明"
        else {
            retainDraftUri(context, uri, drafts)
            replacementUri = uri
        }
    } }
    LaunchedEffect(requestedReplacement) {
        requestedReplacement?.let { photo ->
            if (!blocked && !EvidenceOperation.busy) {
                replacing = photo.id; replacementPhoto = photo
                pickerOwner = java.util.UUID.randomUUID().toString(); requestPicker = true
            }
            onReplacementHandled()
        }
    }
    LaunchedEffect(requestPicker, episode) {
        if (requestPicker) { requestPicker = false; picker.launch(arrayOf("image/*")) }
    }
    LaunchedEffect(open, EvidenceOperation.busy) { if (open && !EvidenceOperation.busy) scan() }

    replacementUri?.takeIf { !suspended }?.let { uri -> AlertDialog(
        onDismissRequest = { if (!EvidenceOperation.busy) { replacementUri = null; replacementPhoto = null; replacing = null } },
        title = { Text("确认替换证明") },
        text = { Column { Text("证明 #$replacing，确认前不会复制或替换。原目标改变时保存将被拒绝。"); DraftUriWarning(listOf(uri)) } },
        confirmButton = { TextButton(enabled = !blocked && !EvidenceOperation.busy && replacementPhoto != null, onClick = {
            val photo = replacementPhoto ?: return@TextButton
            onWrite("证明 ${photo.id} 已替换", { store.replaceEvidence(photo, uri) }, {
                replacementUri = null; replacementPhoto = null; replacing = null
                result = "证明 ${photo.id} 已替换，请检查结果"
            })
        }) { Text("确认替换") } },
        dismissButton = { TextButton(enabled = !EvidenceOperation.busy, onClick = {
            replacementUri = null; replacementPhoto = null; replacing = null
        }) { Text("丢弃替换选择") } }
    ) }

    if (open && replacementUri == null) AlertDialog(
        onDismissRequest = { if (!scanning && !EvidenceOperation.busy) onClose() },
        title = { Text("证明检查与修复") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅检查已有证明。未上传图片的参与不算异常；检查不会删除或修改文件。")
            Text("替换图片单张 ≤30 MiB、≤2400 万像素。动画图片按首帧预览与导出。")
            if (scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress) }
            if (EvidenceOperation.busy) Text("正在保存证明，请稍候…")
            if (result.isNotBlank()) Text(result)
            if (replacing != null && replacementUri == null) {
                Text("证明 #$replacing 的选择尚未完成，重新选择或丢弃后再处理其他证明。")
                TextButton(enabled = !blocked && !EvidenceOperation.busy, onClick = {
                    pickerOwner = java.util.UUID.randomUUID().toString(); requestPicker = true
                }) { Text("继续选择替换图片") }
                TextButton(enabled = !EvidenceOperation.busy, onClick = { replacing = null; replacementPhoto = null; pickerOwner = "" }) { Text("丢弃待选替换") }
            }
            if (scanned && !scanning && issues.isEmpty()) Text("检查完成，未发现异常证明")
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(issues, key = { it.location.photo.id }) { issue ->
                    Column {
                        Text(issue.description)
                        Row {
                            TextButton(enabled = !scanning && !EvidenceOperation.busy, onClick = { onClose(); onOpenRecord(issue.location.itemId) }) { Text("查看记录") }
                            TextButton(enabled = replacing == null && !blocked && !scanning && !EvidenceOperation.busy, onClick = {
                                val owner = java.util.UUID.randomUUID().toString(); pickerOwner = owner
                                if (pickerOwner == owner) {
                                    replacementPhoto = issue.location.photo; replacing = issue.location.photo.id
                                    if (replacementPhoto == issue.location.photo && replacing == issue.location.photo.id) requestPicker = true
                                }
                            }) { Text("替换图片") }
                            TextButton(enabled = !blocked && !scanning && !EvidenceOperation.busy, onClick = { deleting = issue }) { Text("删除引用") }
                        }
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = !scanning && !EvidenceOperation.busy, onClick = { result = ""; scan() }) { Text("重新检查") } },
        dismissButton = { TextButton(enabled = !scanning && !EvidenceOperation.busy, onClick = onClose) { Text("关闭") } },
    )
    deleting?.let { issue -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除此证明引用？") },
        text = { Text("${issue.location.label}\n只删除这张证明及其引用，活动、参与和其他证明会保留。删除可能使重复参与核对需要重新确认。") },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            deleting = null
            result = ""
            onWrite("证明引用已删除", { store.deleteEvidence(issue.location.photo) }, { result = "证明引用已删除" })
        }) { Text("确认删除") } },
    ) }
}
