package cn.activityscore.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.DuplicateDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lightweight preview has no file ownership. Final export still captures and validates its own snapshot. */
@Composable internal fun JournalOrganizeScreen(presentation: RecordPresentation, store: RecordStore, choices: Map<Long, ExportChoice>,
    onChoice: (Long, ExportChoice) -> Unit, onPrepare: () -> Unit, busy: Boolean,
    onOpen: (Long) -> Unit, onReviews: () -> Unit, onAnnual: () -> Unit) {
    var showMaterials by remember { mutableStateOf(false) }
    val data = presentation.activityAssessment
    val year = presentation.annualScore.academicYear
    var preview by remember(data) { mutableStateOf<Pair<Map<Long, ExportChoice>, ExportEntryPlan>?>(null) }
    var failure by remember(data) { mutableStateOf("") }
    LaunchedEffect(data, choices) {
        failure = ""
        try {
            val plan = withContext(Dispatchers.Default) { ExportEntryPlanner.build(year, data, choices) }
            preview = choices to plan
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failure = exportErrorMessage(e, "申报预览") }
    }
    val plan = preview?.takeIf { it.first == choices }?.second
    val groups = remember(plan) { plan?.entries?.groupBy { it.itemId }.orEmpty().values.toList() }
    val subtotal = remember(data) { data.dimensions.values.sumOf { it.knownSubtotal }.takeIf { it.isFinite() } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = JournalSpace.gutter, vertical = 8.dp)) {
            item(key = "organize-heading") {
                Text("整理与导出", style = MaterialTheme.typography.headlineMedium)
                Muted("学期里的小记录，申报时有条有理。")
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { Text("${data.confirmedCount}", style = MaterialTheme.typography.headlineMedium); Muted("项已确认") }
                    Column(Modifier.weight(1f)) { Text("${data.pendingCount}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.tertiary); Muted("项待核对") }
                    Column(Modifier.weight(1f)) { Text(subtotal?.let { NumericValues.formatForDisplay(it) } ?: "—", style = MaterialTheme.typography.headlineMedium); Muted("分已确认小计") }
                }
                HorizontalDivider()
                Text("待核对", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                if (data.pendingCount == 0) Muted("本学年暂无待核对活动")
            }
            items(data.activities.filter { it.score == null }, key = { "pending:${it.activity.item.id}" }) { a ->
                TextButton(onClick = { onOpen(a.activity.item.id) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    Text(a.activity.item.title, Modifier.weight(1f)); Text(a.status, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
            }
            item(key = "materials") {
                Row {
                    TextButton(onClick = onReviews) { Text("全部重复核对记录") }
                    TextButton(onClick = onAnnual) { Text("年度资料与课程") }
                }
                Text("资料提醒", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Muted("${data.missingMaterialCount} 项参考分或证明尚未补充 · 可继续导出，未知分数留空")
                TextButton(onClick = { showMaterials = !showMaterials }) { Text(if (showMaterials) "收起资料提醒" else "查看资料提醒") }
            }
            items((data.blockers + if (showMaterials) data.warnings else emptyList()).take(12), key = { "issue:${it.itemId}:${it.participationId}:${it.fieldName}:${it.reason}" }) { issue ->
                TextButton(onClick = { onOpen(issue.itemId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(issue.description, style = MaterialTheme.typography.bodySmall,
                        color = if (issue in data.blockers) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "preview-heading") {
                Text("申报表预览", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
                Muted("每项单独选择合并或拆分，预览同步更新。仅影响本次导出。")
                if (plan == null && failure.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                if (failure.isNotEmpty()) Text(failure, color = MaterialTheme.colorScheme.error)
                if (plan != null) Muted("预计导出 ${plan.entries.size} 条申报记录", Modifier.padding(vertical = 8.dp))
            }
            groups.forEach { entries ->
                val first = entries.first(); val a = first.assessment.activity; val id = a.item.id
                val choice = choices[id] ?: ExportChoice()
                item(key = "choice:$id") {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(a.item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onOpen(id) }) { Text("查看") }
                    }
                    Muted("${a.participations.size} 次参与 · ${entries.size} 行 · ${first.assessment.status}")
                    if (a.participations.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(first.mode == ExportMode.MERGED, { onChoice(id, choice.copy(mode = ExportMode.MERGED)) }, label = { Text("合并为一行") })
                        FilterChip(first.mode == ExportMode.SPLIT, { onChoice(id, choice.copy(mode = ExportMode.SPLIT)) }, label = { Text("按参与拆分") })
                    }
                    if (first.mode == ExportMode.SPLIT && a.decision == DuplicateDecision.COUNT_ONCE && first.assessment.score != null)
                        Text("只计一次：请选择一个参与承载分数，其余行留空。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                items(entries, key = { "preview:${it.id}" }) { entry ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(entry.score?.let { NumericValues.formatForDisplay(it) } ?: "—", style = MaterialTheme.typography.titleSmall)
                            }
                            Muted("${entry.participations.size} 次参与 · ${entry.evidence.size} 张证明")
                            if (entry.evidence.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(entry.evidence, key = { it.photo.id }) { ref ->
                                    Column {
                                        EvidenceThumbnail(store.evidenceFile(ref.photo.fileName))
                                        Muted("证明 ${ref.photo.id} → 参与 ${ref.participationId}")
                                    }
                                }
                            }
                            if (entry.explanation.isNotEmpty()) Muted(entry.explanation)
                            if (first.mode == ExportMode.SPLIT && a.decision == DuplicateDecision.COUNT_ONCE && first.assessment.score != null) {
                                val visit = entry.participations.single()
                                Row {
                                    RadioButton(choice.scoreCarrier == visit, { onChoice(id, choice.copy(scoreCarrier = visit)) })
                                    TextButton(onClick = { onChoice(id, choice.copy(scoreCarrier = visit)) }) { Text("${visit.eventDate} · 参与 ${visit.id}") }
                                }
                            }
                        }
                    }
                }
            }
            items(plan?.problems.orEmpty()) { Text(it, color = MaterialTheme.colorScheme.error) }
            item(key = "export-note") { Muted("待核对项目认可分留空，不计入已确认小计；小计不是正式综测成绩。", Modifier.padding(vertical = 20.dp)) }
        }
        HorizontalDivider()
        Column(Modifier.padding(horizontal = JournalSpace.gutter, vertical = 12.dp)) {
            JournalPrimary(if (busy) "正在准备资料…" else "Excel 申报导出", onPrepare, !busy && plan != null && plan.problems.isEmpty())
        }
    }
}
