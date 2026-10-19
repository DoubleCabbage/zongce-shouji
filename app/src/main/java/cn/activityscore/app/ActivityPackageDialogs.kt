package cn.activityscore.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import java.io.File
import java.time.LocalDate

@Composable
internal fun ShareActivityDialog(
    item: ScoreItem,
    participation: Participation,
    busy: Boolean,
    onDismiss: () -> Unit,
    onShare: (List<EvidencePhoto>) -> Unit,
) {
    var selectedIds by remember(participation.id) {
        mutableStateOf(participation.evidence.map { it.id }.toSet())
    }
    FormDialog(title = "分享班级活动", onDismiss = onDismiss) {
        Text("${item.title} · ${participation.eventDate}")
        Text("将分享活动信息和选中的照片。职务、个人成绩及其他参与记录不会放入活动包。")
        if (participation.evidence.isEmpty()) Text("这次参与还没有照片，可以分享仅含活动信息的文件。")
        participation.evidence.forEachIndexed { index, photo ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = photo.id in selectedIds,
                    onCheckedChange = { checked ->
                        selectedIds = if (checked) selectedIds + photo.id else selectedIds - photo.id
                    },
                )
                EvidenceThumbnail(File(LocalContext.current.filesDir, "evidence/${photo.fileName}"))
                Spacer(Modifier.width(8.dp))
                Text("照片 ${index + 1}")
            }
        }
        Text("已选择 ${selectedIds.size} 张；单次最多 30 张，总量最多 200 MB。")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            Button(
                onClick = { onShare(participation.evidence.filter { it.id in selectedIds }) },
                enabled = !busy && selectedIds.size <= 30,
            ) { Text(if (busy) "生成中…" else "生成并分享") }
        }
    }
}

@Composable
internal fun ImportActivityDialog(
    staged: StagedActivityPackage,
    alreadyImported: Boolean,
    possibleDuplicate: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: (String, Double?, List<File>) -> Unit,
) {
    val drafts = LocalDraft.current
    val prefix = "package/${staged.activity.shareId}"
    val activity = staged.activity
    var participated by draftState("$prefix/participated") { mutableStateOf(false) }
    var role by draftState("$prefix/role") { mutableStateOf("") }
    var scoreText by draftState("$prefix/scoreText") {
        mutableStateOf(NumericValues.formatForEdit(activity.referenceScore))
    }
    var selectedIndices by draftState("$prefix/selectedIndices") {
        mutableStateOf(staged.photos.indices.toSet())
    }
    var error by draftState("$prefix/error") { mutableStateOf("") }
    fun dismiss() { if (!EvidenceOperation.busy) { drafts.removePrefix("$prefix/"); onDismiss() } }
    FormDialog(title = "确认导入班级活动", onDismiss = ::dismiss) {
        Text(activity.title, style = MaterialTheme.typography.titleMedium)
        Text("${activity.eventDate} · ${activity.dimension}" +
            if (activity.subcategory.isBlank()) "" else " · ${activity.subcategory}")
        if (activity.note.isNotBlank()) Text("活动说明：${activity.note}")
        if (activity.eventNote.isNotBlank()) Text("参与说明：${activity.eventNote}")
        if (alreadyImported) {
            Text("这份活动已导入过，无需重复添加。", color = MaterialTheme.colorScheme.error)
        } else if (possibleDuplicate) {
            Text("已有同名、同日期的记录，请核对后再导入。", color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = participated, onCheckedChange = { participated = it },
                enabled = !alreadyImported)
            Text("确认：我参加了这项活动")
        }
        OutlinedTextField(
            value = role, onValueChange = { role = it },
            label = { Text("我的职务 / 获奖等级（可稍后填写）") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = scoreText, onValueChange = { scoreText = it },
            label = { Text("我的参考估分（可留空）") },
            supportingText = { Text(NumericValues.INPUT_HINT) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Text("班委附带 ${staged.photos.size} 张照片，可选择保存到本次参与记录。")
        staged.photos.forEachIndexed { index, file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = index in selectedIndices,
                    onCheckedChange = { checked ->
                        selectedIndices = if (checked) selectedIndices + index
                        else selectedIndices - index
                    },
                )
                EvidenceThumbnail(file)
                Spacer(Modifier.width(8.dp))
                Text("照片 ${index + 1}")
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = ::dismiss, enabled = !busy) { Text("取消") }
            Button(
                onClick = {
                    val parsed = NumericValues.parse(scoreText)
                    error = parsed.error?.let { "参考估分：$it" }.orEmpty()
                    if (error.isBlank()) onImport(
                        role.trim(), parsed.value,
                        staged.photos.filterIndexed { index, _ -> index in selectedIndices },
                    )
                },
                enabled = participated && !alreadyImported && !busy,
            ) { Text(if (busy) "导入中…" else "确认导入") }
        }
    }
}

