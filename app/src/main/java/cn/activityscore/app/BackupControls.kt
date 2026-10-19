package cn.activityscore.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cn.activityscore.app.data.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small SAF entry point; no storage permission, account or network service. */
@Composable
fun BackupControls(store: RecordStore, entryVisible: Boolean = true, blocked: Boolean = false, restoreBlocked: Boolean = false, onEvidenceProblems: () -> Unit = {}, onRestored: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var verified by remember { mutableStateOf<VerifiedBackup?>(null) }
    var cancellation by remember { mutableStateOf(BackupCancellation()) }
    DisposableEffect(Unit) {
        onDispose {
            cancellation.cancelled = true
            pendingFile?.let(FullBackup::discard)
            verified?.close()
        }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingFile; pendingFile = null
        if (uri == null || file == null) {
            file?.let(FullBackup::discard); busy = false
            if (uri != null) { result = "备份暂存已失效，请重新备份"; scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.delete(uri, null, null) } } }
        } else scope.launch {
            try {
                progress = "正在保存并重新读取校验备份…"
                withContext(Dispatchers.IO) { FullBackup.save(context, file, uri, cancellation) }
                result = "完整备份已保存并校验。文件包含个人信息与证明照片，请妥善保管。"
            } catch (error: Exception) { result = "备份未完成：${dataErrorMessage(error)}。若保存位置仍有文件，请删除该不完整文件。" }
            finally { FullBackup.discard(file); busy = false }
        }
    }
    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true; restoring = false; cancellation = BackupCancellation(); progress = "正在验证备份…"
            scope.launch {
                var captured: VerifiedBackup? = null
                try {
                    withContext(Dispatchers.IO) { captured = FullBackup.verify(context, uri, cancellation) { progress = it } }
                    verified = captured; captured = null
                } catch (error: Exception) { result = "备份验证未通过，当前数据未更改：${dataErrorMessage(error)}" }
                finally { captured?.close(); busy = false }
            }
        }
    }
    if (entryVisible) TextButton(enabled = !blocked, onClick = { showMenu = true }) { Text("数据备份与恢复") }
    if (showMenu) AlertDialog(
        onDismissRequest = { showMenu = false }, title = { Text("完整数据备份") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存所有活动、参与、证明照片、历史库、人工核对、年度资料与列映射偏好。备份包含个人信息和证书照片，请妥善保存。恢复会完整替换当前数据。")
            if (restoreBlocked) Text("当前有未提交草稿，请保存或丢弃后再恢复。完整备份不包含草稿。")
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                showMenu = false; busy = true; restoring = false; cancellation = BackupCancellation()
                progress = "正在准备完整备份…"
                scope.launch {
                    var captured: File? = null
                    try {
                        withContext(Dispatchers.IO) { captured = FullBackup.capture(context, store, cancellation) { progress = it } }
                        pendingFile = captured; captured = null
                        progress = "请选择备份保存位置…"
                        savePicker.launch("素养分完整备份-${System.currentTimeMillis()}.zip")
                    } catch (error: Exception) {
                        if (error is EvidenceProblems) { result = ""; onEvidenceProblems() }
                        else result = "备份未完成：${dataErrorMessage(error)}"
                        busy = false
                    }
                    finally { captured?.let(FullBackup::discard) }
                }
            }) { Text("完整备份") }
            OutlinedButton(enabled = !restoreBlocked && !blocked, modifier = Modifier.fillMaxWidth(), onClick = { showMenu = false; openPicker.launch(arrayOf("*/*")) }) { Text("从备份恢复") }
        } },
        confirmButton = { TextButton(onClick = { showMenu = false }) { Text("关闭") } },
    )
    val prepared = verified
    if (prepared != null) AlertDialog(
        onDismissRequest = { prepared.close(); verified = null },
        title = { Text("确认替换当前数据") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("备份已通过格式、结构、附件及完整性验证。")
            Text(prepared.summary)
            Text("恢复将替换当前全部活动、证明、历史库、核对和年度资料，不能合并。建议先备份当前数据。", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { prepared.close(); verified = null }) { Text("取消") } },
        confirmButton = { Button(enabled = !blocked && !restoreBlocked, onClick = {
            if (blocked || restoreBlocked) return@Button
            verified = null; busy = true; restoring = true; progress = "正在恢复，请保持 App 打开…"
            scope.launch {
                try {
                    // Once confirmed, do not interrupt the commit/roll-forward with UI lifecycle cancellation.
                    withContext(NonCancellable + Dispatchers.IO) { FullBackup.restore(context, store, prepared, { progress = it }) }
                    onRestored(); result = "完整恢复成功，数据和证明已重新读取校验。"
                } catch (error: Exception) { result = "恢复未完成：${dataErrorMessage(error)}" }
                finally { prepared.close(); busy = false; restoring = false }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("替换并恢复") } },
    )
    if (busy) AlertDialog(
        onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(if (restoring) "恢复数据" else "备份与验证") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress) } },
        confirmButton = {},
        dismissButton = { if (!restoring && pendingFile == null) TextButton(onClick = { cancellation.cancelled = true }) { Text("取消") } },
    )
    if (result.isNotBlank()) AlertDialog(onDismissRequest = { result = "" }, title = { Text("备份与恢复结果") },
        text = { Text(result, modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { result = "" }) { Text("确定") } })
}
