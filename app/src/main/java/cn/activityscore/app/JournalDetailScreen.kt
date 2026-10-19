package cn.activityscore.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.activityscore.app.data.*

/** Full-screen route in its own window: system Back closes details, not the Activity. */
@Composable internal fun JournalDetailScreen(item: ScoreItem, participations: List<Participation>, store: RecordStore,
    feedback: String, onClearFeedback: () -> Unit, year: Int, assessment: ExportActivityAssessment?, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onReview: () -> Unit, onAddParticipation: () -> Unit, onEditParticipation: (Participation) -> Unit,
    onDeleteParticipation: (Participation) -> Unit, onAddEvidence: (Participation) -> Unit,
    onDeleteEvidence: (EvidencePhoto) -> Unit, onReplaceEvidence: (EvidencePhoto) -> Unit, onShare: (Participation) -> Unit) {
    var confirmDelete by remember(item.id) { mutableStateOf(false) }
    var allVisits by rememberSaveable(item.id) { mutableStateOf(false) }
    val showAll = allVisits || assessment == null
    val visits = remember(participations, assessment, showAll) {
        (if (showAll) participations else assessment?.activity?.participations.orEmpty()).sortedWith(compareByDescending<Participation> { it.eventDate }.thenByDescending { it.id })
    }
    Dialog(onDismissRequest = { if (!EvidenceOperation.busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        SideEffect {
            dialogWindow?.let { window ->
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val context = androidx.compose.ui.platform.LocalContext.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val usableHeight = remember(configuration) {
            val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>().firstOrNull()
            val pixels = if (activity != null && android.os.Build.VERSION.SDK_INT >= 30) {
                val metrics = activity.windowManager.currentWindowMetrics
                val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                metrics.bounds.height() - bars.top - bars.bottom
            } else if (activity != null) {
                android.graphics.Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }.height()
            } else (configuration.screenHeightDp * density.density).toInt()
            with(density) { pixels.toDp() }
        }
        // Compose 1.7's floating Dialog measures screenHeightDp including bars on API 35+.
        // Bound the route to the actual usable window instead of placing its dock off-screen.
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().height(usableHeight)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回活动") }
                    Text("活动详情", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onEdit) { Text("编辑") }
                }
                if (feedback.isNotBlank()) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(feedback, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onClearFeedback) { Text("关闭提示") }
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = JournalSpace.gutter, vertical = 8.dp)) {
                    item(key = "detail-summary") {
                        Text(item.title, style = MaterialTheme.typography.headlineMedium)
                        Row(Modifier.padding(top = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(item.dimension, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Muted(item.role.ifBlank { "职务 / 等级未填写" })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("${assessment?.activity?.participations?.size ?: 0} 次参与 · 本学年", style = MaterialTheme.typography.titleMedium)
                                ScoreStatus(assessment); Muted(assessment?.countingLabel ?: "尚无学年计分") }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(assessment?.score?.let { NumericValues.formatForDisplay(it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
                                Muted("已确认活动分")
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Muted("参考分 ${item.referenceScore?.let { NumericValues.formatForDisplay(it) + " 分" } ?: "未填写（可选）"} · ${if (assessment?.countingLabel == "单次参与" && assessment.score != null) "单次按原规则认可" else "仅作参考"}", Modifier.weight(1f))
                            TextButton(onClick = onEdit) { Text("修改") }
                        }
                        if (item.note.isNotBlank()) Muted(item.note)
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("参与足迹", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { allVisits = !allVisits }) { Text(if (showAll) "全部学年" else "$year—${year + 1}") }
                        }
                        if (visits.isEmpty()) Muted("本学年还没有参与记录。可切换全部学年查看历史参与。", Modifier.padding(vertical = 24.dp))
                    }
                    items(visits, key = { it.id }, contentType = { "timeline" }) { visit ->
                        ParticipationTimeline(visit, store, assessment?.participationScores?.get(visit.id),
                            onEdit = { onEditParticipation(visit) }, onDelete = { onDeleteParticipation(visit) },
                            onAddEvidence = { onAddEvidence(visit) }, onDeleteEvidence = onDeleteEvidence,
                            onReplaceEvidence = onReplaceEvidence, onShare = { onShare(visit) })
                    }
                    item(key = "detail-actions") {
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onReview) { Text("核对计分") }
                            TextButton(onClick = onEdit) { Text("修改条目") }
                            TextButton(onClick = { confirmDelete = true }) { Text("删除条目", color = MaterialTheme.colorScheme.error) }
                        }
                        Muted("参考分与证明均可稍后补充。")
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = JournalSpace.gutter, vertical = 12.dp)) {
                    JournalPrimary("再参加一次", onAddParticipation, !EvidenceOperation.busy)
                }
            }
            }
        }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除活动？") },
            text = { Text("删除后，此条目的参与记录和证明图片也会移除。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    }
}

@Composable private fun ParticipationTimeline(visit: Participation, store: RecordStore, score: Double?, onEdit: () -> Unit,
    onDelete: () -> Unit, onAddEvidence: () -> Unit, onDeleteEvidence: (EvidencePhoto) -> Unit,
    onReplaceEvidence: (EvidencePhoto) -> Unit, onShare: () -> Unit) {
    var deleteVisit by remember(visit.id) { mutableStateOf(false) }
    var removing by remember { mutableStateOf<EvidencePhoto?>(null) }
    var viewing by remember { mutableStateOf<EvidencePhoto?>(null) }
    var menu by remember { mutableStateOf(false) }
    val track = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(50.dp).padding(top = 4.dp)) {
            Text(visit.eventDate.drop(5).replace('-', '.'), style = MaterialTheme.typography.titleSmall)
            Muted(visit.eventDate.take(4))
        }
        Canvas(Modifier.width(14.dp).fillMaxHeight()) {
            drawLine(track, Offset(size.width / 2, 12.dp.toPx()), Offset(size.width / 2, size.height), 1.dp.toPx())
            drawCircle(accent, 3.dp.toPx(), Offset(size.width / 2, 12.dp.toPx()))
        }
        Column(Modifier.weight(1f).padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("参与记录", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(score?.let { "+${NumericValues.formatForDisplay(it)} 分" } ?: "参与事实", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Box {
                    TextButton(onClick = { menu = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.widthIn(min = 48.dp)) { Text("操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("修改") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("补充图片") }, onClick = { menu = false; onAddEvidence() })
                        DropdownMenuItem(text = { Text("分享活动包") }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text("删除这次参与") }, onClick = { menu = false; deleteVisit = true })
                    }
                }
            }
            if (visit.note.isNotBlank()) Muted(visit.note)
            if (visit.evidence.isEmpty()) Muted("未上传证明 · 可稍后补充")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                visit.evidence.forEach { photo -> key(photo.id, photo.fileName) { Box(Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "${visit.eventDate}，参与 ${visit.id}，证明 ${photo.id}"
                }.clickable(role = Role.Button, onClickLabel = "查看证明 ${photo.id}") { viewing = photo }.padding(top = 8.dp)) {
                    EvidenceThumbnail(store.evidenceFile(photo.fileName), contentDescription = null)
                } } }
            }
        }
    }
    viewing?.let { photo -> AlertDialog(onDismissRequest = { viewing = null }, title = { Text("证明 ${photo.id} · 参与 ${visit.id}") },
        text = { EvidenceThumbnail(store.evidenceFile(photo.fileName), expanded = true) },
        confirmButton = { TextButton(onClick = { viewing = null }) { Text("关闭") } },
        dismissButton = { Row {
            TextButton(onClick = { viewing = null; onReplaceEvidence(photo) }) { Text("替换") }
            TextButton(onClick = { viewing = null; removing = photo }) { Text("移除") }
        } }) }
    removing?.let { photo -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除证明 ${photo.id}？") },
        text = { Text("只删除此证明，保留参与记录和其他证明。") },
        confirmButton = { TextButton(onClick = { removing = null; onDeleteEvidence(photo) }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
    if (deleteVisit) AlertDialog(onDismissRequest = { deleteVisit = false }, title = { Text("删除这次参与？") }, text = { Text("同时移除此参与的证明，其他参与保留。") },
        confirmButton = { TextButton(onClick = { deleteVisit = false; onDelete() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteVisit = false }) { Text("取消") } })
}
