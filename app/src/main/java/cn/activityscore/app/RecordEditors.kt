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
import androidx.compose.runtime.saveable.rememberSaveable
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

internal data class ItemDraft(
    val title: String,
    val dimension: String,
    val subcategory: String,
    val role: String,
    val referenceScore: Double?,
    val note: String,
    val eventDate: String,
    val eventNote: String,
)

@Composable
internal fun ItemEditor(
    existing: ScoreItem?,
    targetAvailable: Boolean,
    historyRows: List<HistoryRow>,
    onDismiss: () -> Unit,
    onSave: (ItemDraft, List<Uri>) -> Unit,
) {
    val LocalContextForPicker = LocalContext.current
    val drafts = LocalDraft.current
    val prefix = "item/${existing?.id ?: "new"}"
    val formId = draftState("$prefix/formId") { mutableStateOf(java.util.UUID.randomUUID().toString()) }.value
    var pickerOwner by draftState("route/itemPickerOwner") { mutableStateOf("") }
    var title by draftState("$prefix/title") { mutableStateOf(existing?.title ?: "") }
    var dimension by draftState("$prefix/dimension") {
        mutableStateOf(existing?.dimension ?: scoreDimensions.first())
    }
    var subcategory by draftState("$prefix/subcategory") { mutableStateOf(existing?.subcategory ?: "") }
    var role by draftState("$prefix/role") { mutableStateOf(existing?.role ?: "") }
    var scoreText by draftState("$prefix/scoreText") {
        mutableStateOf(NumericValues.formatForEdit(existing?.referenceScore))
    }
    var note by draftState("$prefix/note") { mutableStateOf(existing?.note ?: "") }
    var eventDate by draftState("$prefix/eventDate") { mutableStateOf(LocalDate.now().toString()) }
    var eventNote by draftState("$prefix/eventNote") { mutableStateOf("") }
    var images by draftState("$prefix/images") { mutableStateOf(emptyList<Uri>()) }
    var error by draftState("$prefix/error") { mutableStateOf("") }
    var selectedHistory by draftState("$prefix/selectedHistory") { mutableStateOf<HistoryActivity?>(null) }
    var showSuggestions by draftState("$prefix/showSuggestions") { mutableStateOf(true) }
    var showOptions by rememberSaveable(existing?.id) { mutableStateOf(existing != null) }
    val historicalActivities = rememberHistoryActivities(historyRows)
    val matches = rememberHistoryMatches(title, dimension, historicalActivities, showSuggestions)
    val picker = androidx.compose.runtime.key(formId) { rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selected ->
        if (pickerOwner != formId) return@rememberLauncherForActivityResult
        pickerOwner = ""
        selected.forEach { retainDraftUri(LocalContextForPicker, it, drafts) }
        images = (images + selected).distinct()
    } }

    fun dismiss() { if (!EvidenceOperation.busy) { drafts.removePrefix("$prefix/"); onDismiss() } }
    EditorSheet(
        title = if (existing == null) "新建加分条目" else "修改加分条目",
        onDismiss = ::dismiss,
    ) {
        if (!targetAvailable) Text("所属活动已不存在；原始输入保留，请核对当前记录。", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = title, onValueChange = {
                title = it
                selectedHistory = null
                showSuggestions = true
            }, label = { Text("活动名称 *") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        if (matches.isNotEmpty()) {
            Text("历史相似活动（仅供参考）", style = MaterialTheme.typography.titleSmall)
            matches.forEach { match ->
                TextButton(
                    onClick = {
                        val activity = match.activity
                        selectedHistory = activity
                        title = activity.activityName
                        subcategory = activity.subcategory
                        role = ""
                        scoreText = ""
                        if (activity.dimension in scoreDimensions) {
                            dimension = activity.dimension
                            error = ""
                        } else {
                            error = "历史分类“${activity.dimension}”不在六维列表中，请手动选择所属维度"
                        }
                        showSuggestions = false
                        showOptions = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${match.activity.activityName} · ${match.activity.dimension}" +
                        if (match.activity.needsReview) " · ${match.activity.variantLabel}" else "")
                }
            }
        }
        selectedHistory?.let { activity ->
            Text("选择职务 / 等级后带入参考分数", style = MaterialTheme.typography.titleSmall)
            activity.roles.forEach { option ->
                OutlinedButton(
                    onClick = {
                        role = option.label
                        scoreText = NumericValues.formatForEdit(option.referenceScore)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${option.label.ifBlank { "未注明身份" }} · " +
                        "${formatScore(option.referenceScore)} 分")
                }
            }
            if (activity.needsReview) {
                Text("此活动在原表中存在同身份不同分数，请核对后再保存。",
                    color = MaterialTheme.colorScheme.error)
            }
        }
        Text("所属维度", style = MaterialTheme.typography.titleSmall)
        DimensionChooser(dimension, onSelect = { dimension = it })
        TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "收起可选信息" else "更多可选信息") }
        if (showOptions) {
        OutlinedTextField(
            value = subcategory, onValueChange = { subcategory = it },
            label = { Text("小分类（可选）") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = role, onValueChange = { role = it },
            label = { Text("职务 / 获奖等级（可选）") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = scoreText, onValueChange = { scoreText = it },
            label = { Text("历史参考分数（可留空）") }, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(NumericValues.INPUT_HINT) },
            singleLine = true,
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("条目备注（可选）") }, modifier = Modifier.fillMaxWidth(),
        )
        }
        if (existing == null) {
            Text("首次参与", style = MaterialTheme.typography.titleMedium)
            ParticipationDateField(eventDate, { eventDate = it })
            OutlinedTextField(
                value = eventNote, onValueChange = { eventNote = it },
                label = { Text("这次参与的说明") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { pickerOwner = formId; picker.launch(arrayOf("image/*")) }) {
                Text("添加证明图片（已选 ${images.size} 张）")
            }
            DraftUriWarning(images)
        Text("单张 ≤30 MiB、≤2400 万像素，保存时保留原图。动画图片按首帧预览与导出。")
            DraftProofList(images) { index -> images = images.filterIndexed { position, _ -> position != index } }
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        DialogActions(onDismiss = ::dismiss, onConfirm = {
            val parsed = NumericValues.parse(scoreText)
            error = when {
                title.isBlank() -> "请填写活动名称"
                !parsed.isValid -> "参考分数：${parsed.error}"
                existing == null && !isValidDate(eventDate) -> "日期请填写为 YYYY-MM-DD"
                else -> ""
            }
            if (error.isBlank() && targetAvailable) {
                onSave(
                    ItemDraft(title, dimension, subcategory, role, parsed.value, note, eventDate, eventNote),
                    images,
                )
            }
        })
    }
}

@Composable
internal fun ParticipationEditor(
    existing: Participation?,
    targetAvailable: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, List<Uri>) -> Unit,
) {
    val LocalContextForPicker = LocalContext.current
    val drafts = LocalDraft.current
    val prefix = "participation/${existing?.id ?: "new"}"
    val formId = draftState("$prefix/formId") { mutableStateOf(java.util.UUID.randomUUID().toString()) }.value
    var pickerOwner by draftState("route/participationPickerOwner") { mutableStateOf("") }
    var date by draftState("$prefix/date") {
        mutableStateOf(existing?.eventDate ?: LocalDate.now().toString())
    }
    var note by draftState("$prefix/note") { mutableStateOf(existing?.note ?: "") }
    var images by draftState("$prefix/images") { mutableStateOf(emptyList<Uri>()) }
    var error by draftState("$prefix/error") { mutableStateOf("") }
    val picker = androidx.compose.runtime.key(formId) { rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selected ->
        if (pickerOwner != formId) return@rememberLauncherForActivityResult
        pickerOwner = ""
        selected.forEach { retainDraftUri(LocalContextForPicker, it, drafts) }
        images = (images + selected).distinct()
    } }

    fun dismiss() { if (!EvidenceOperation.busy) { drafts.removePrefix("$prefix/"); onDismiss() } }
    EditorSheet(
        title = if (existing == null) "添加一次参与" else "修改参与记录",
        onDismiss = ::dismiss,
    ) {
        if (!targetAvailable) Text("所属活动或参与已不存在；原始输入保留，请核对当前记录。", color = MaterialTheme.colorScheme.error)
        ParticipationDateField(date, { date = it })
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("这次参与的内容") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { pickerOwner = formId; picker.launch(arrayOf("image/*")) }) {
            Text("添加证明图片（已选 ${images.size} 张）")
        }
        DraftUriWarning(images)
        Text("单张 ≤30 MiB、≤2400 万像素，保存时保留原图。动画图片按首帧预览与导出。")
        DraftProofList(images) { index -> images = images.filterIndexed { position, _ -> position != index } }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        DialogActions(onDismiss = ::dismiss, onConfirm = {
            error = if (isValidDate(date)) "" else "日期请填写为 YYYY-MM-DD"
            if (error.isBlank() && targetAvailable) onSave(date, note, images)
        })
    }
}


@Composable
private fun ParticipationDateField(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(value, onChange, label = { Text("日期（YYYY-MM-DD）") }, modifier = Modifier.fillMaxWidth(),
        trailingIcon = { TextButton(onClick = {
            val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
            android.app.DatePickerDialog(context, { _, year, month, day ->
                onChange(LocalDate.of(year, month + 1, day).toString())
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }) { Text("选择") } }, singleLine = true)
}
