package cn.activityscore.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.DuplicateDecision

@Composable
internal fun ExportGroupingSummary(snapshot: AnnualExportSnapshot, choices: Map<Long, ExportChoice>, plan: ExportEntryPlan?,
    updating: Boolean, onChoice: (Long, ExportChoice) -> Unit, onView: (Long) -> Unit) {
    val summary = snapshot.assessment
    val groups = remember(plan) { plan?.entries?.groupBy { it.itemId }.orEmpty().values.toList() }
    LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${snapshot.scores.academicYear}—${snapshot.scores.academicYear + 1} 学年")
                Text("活动 ${summary.activities.size} 个 · 参与 ${summary.participationCount} 次 · 证明 ${summary.evidenceCount} 张")
                Text("计分已确认 ${summary.confirmedCount} 项 · 待确定 ${summary.pendingCount} 项")
                Text("资料缺失 ${summary.missingMaterialCount} 项 · 阻断错误 ${summary.blockers.size} 项")
                Text(if (plan == null || updating) "正在更新申报预览…" else "预计导出 ${plan.entries.size} 条申报记录")
                Text("合并/拆分和分数承载仅用于本次导出，下次默认合并。文件使用本次预览快照，保存前会检查记录是否变化。",
                    style = MaterialTheme.typography.bodySmall)
                Text("未确认认可分留空；维度仅在完整时显示最终封顶分。本文件不构成正式核定成绩。", style = MaterialTheme.typography.bodySmall)
            }
        }
        groups.forEach { entries ->
            val first = entries.first()
            val projected = first.assessment
            val activity = projected.activity
            val id = activity.item.id
            val choice = choices[id] ?: ExportChoice()
            item(key = "activity:$id") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider()
                Text(activity.item.title, style = MaterialTheme.typography.titleSmall)
                Text("${first.academicYear}—${first.academicYear + 1} · ${activity.item.dimension} · 参与 ${activity.participations.size} 次 · 证明 ${entries.sumOf { it.evidence.size }} 张")
                Text("${projected.countingLabel} · ${projected.status}")
                Text("当前：${first.mode.label} · 合并 1 行 / 拆分 ${activity.participations.size} 行")
                if (activity.participations.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(enabled = !updating, selected = first.mode == ExportMode.MERGED,
                            onClick = { onChoice(id, choice.copy(mode = ExportMode.MERGED)) }, label = { Text("合并为一行") })
                        FilterChip(enabled = !updating, selected = first.mode == ExportMode.SPLIT,
                            onClick = { onChoice(id, choice.copy(mode = ExportMode.SPLIT)) }, label = { Text("按参与拆分") })
                    }
                }
                if (first.mode == ExportMode.SPLIT && activity.decision == DuplicateDecision.COUNT_ONCE && projected.score != null) {
                    Text("此活动只计一次，请明确选择由哪次参与承载 ${projected.score} 分；其余行留空。")
                }
            }
            }
            items(entries, key = { it.id }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (first.mode == ExportMode.SPLIT && activity.decision == DuplicateDecision.COUNT_ONCE && projected.score != null) {
                        val visit = entry.participations.single()
                        Row {
                            RadioButton(enabled = !updating, selected = choice.scoreCarrier == visit, onClick = { onChoice(id, choice.copy(scoreCarrier = visit)) })
                            TextButton(enabled = !updating, onClick = { onChoice(id, choice.copy(scoreCarrier = visit)) }) { Text("${visit.eventDate} · 参与 ${visit.id}") }
                        }
                    }
                    Text("${entry.title} · 认可分 ${entry.score?.toString() ?: "留空"} · 证明 ${entry.evidence.size} 张", style = MaterialTheme.typography.bodyMedium)
                    Text("参与 ${entry.participations.joinToString { it.id.toString() }}；" +
                        if (entry.evidence.isEmpty()) "无证明" else entry.evidence.take(8).joinToString { "证明 ${it.photo.id} → 参与 ${it.participationId}" } +
                            if (entry.evidence.size > 8) "；另 ${entry.evidence.size - 8} 张，文件包含完整归属" else "",
                        style = MaterialTheme.typography.bodySmall)
                    if (entry.explanation.isNotEmpty()) Text(entry.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(plan?.problems.orEmpty()) { Text(it, color = MaterialTheme.colorScheme.error) }
        items(summary.warnings.take(20)) { issue ->
            Column {
                Text(issue.description, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onView(issue.itemId) }) { Text("查看活动 ${issue.itemId}") }
            }
        }
        if (summary.warnings.size > 20) item { Text("其余 ${summary.warnings.size - 20} 项提示随 Excel 完整导出。") }
    }
}
