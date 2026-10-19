package cn.activityscore.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.activityscore.app.data.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryWorkbookReview(
    preview: WorkbookPreview,
    reviewed: WorkbookPreview,
    scoreOverrides: Map<ImportIssue, String>,
    onScoreOverride: (ImportIssue, String?) -> Unit,
    choices: Map<ReferenceKey, Int>,
    onChoose: (ReferenceKey, Int) -> Unit,
    skipIssues: Boolean,
    onSkipIssues: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    var issuePage by remember(preview) { mutableStateOf(0) }
    var conflictIndex by remember(reviewed.conflicts) { mutableStateOf(0) }
    var scoreInputs by remember(preview) { mutableStateOf(emptyMap<ImportIssue, String>()) }
    HorizontalDivider()
    Text("检查结果", style = MaterialTheme.typography.titleMedium)
    reviewed.sheets.forEach { sheet ->
        Text("${sheet.name}：" + when {
            sheet.error.isNotBlank() -> sheet.error
            sheet.valid + sheet.invalid == 0 -> "无活动数据"
            else -> "有效 ${sheet.valid} 行，异常 ${sheet.invalid} 行"
        }, color = if (sheet.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    Text("发现同条目同身份同分数的重复 ${reviewed.duplicateCount} 行；选用条目会合并并保留全部来源。",
        style = MaterialTheme.typography.bodySmall)
    val unknown = reviewed.records.count { it.dimension !in scoreDimensions } +
        reviewed.conflicts.sumOf { conflict -> conflict.options.count { it.dimension !in scoreDimensions } }
    if (unknown > 0) Text("有 $unknown 个分数选项的分类不在六维列表内，引用时需手动指定维度。",
        color = MaterialTheme.colorScheme.error)
    if (preview.hasSheetErrors) Text("存在工作表格式问题，不能导入。请调整上方映射或取消勾选问题表，再重新检查。",
        color = MaterialTheme.colorScheme.error)
    if (reviewed.records.isNotEmpty()) {
        Text("有效条目示例", style = MaterialTheme.typography.titleSmall)
        reviewed.records.take(3).forEach { row ->
            Text("${row.activityName} · ${row.dimension} · ${row.role.ifBlank { "未注明身份" }} · ${NumericValues.formatForEdit(row.referenceScore)} 分",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (preview.issues.isNotEmpty()) {
        Text("异常分数与其他问题 ${preview.issues.size} 行 · 已指定分数 ${scoreOverrides.size} 行", style = MaterialTheme.typography.titleSmall)
        Text("先选择原文中的数字或手动指定分数，再处理下方冲突。未修正的行需确认跳过。原文与来源会保留。",
            style = MaterialTheme.typography.bodySmall)
        val size = 3
        preview.issues.drop(issuePage * size).take(size).forEach { issue ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${issue.sheetName} · 第 ${issue.rowNumber} 行 · ${issue.activityName.ifBlank { "活动名为空" }}")
                Text("原始分数：${issue.rawScore.ifBlank { "空白" }}", style = MaterialTheme.typography.bodySmall)
                if (issue.scoreRepair == null) {
                    Text(issue.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("请修改源文件后重试，或确认跳过此行。", style = MaterialTheme.typography.bodySmall)
                } else {
                    val applied = scoreOverrides[issue]
                    val input = scoreInputs[issue] ?: applied.orEmpty()
                    val parsed = NumericValues.parse(input, optional = false)
                    val candidates = remember(issue.rawScore) { NumericValues.historyCandidates(issue.rawScore) }
                    if (candidates.isNotEmpty()) {
                        Text("点击数字，指定分数并保留", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            candidates.forEach { candidate ->
                                FilterChip(selected = applied != null &&
                                    NumericValues.parse(applied).value == NumericValues.parse(candidate).value,
                                    enabled = enabled,
                                    onClick = {
                                        scoreInputs = scoreInputs + (issue to candidate)
                                        onScoreOverride(issue, candidate)
                                    }, label = { Text("$candidate 分") },
                                    modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                    }
                    OutlinedTextField(value = input, onValueChange = { text ->
                        scoreInputs = scoreInputs + (issue to text)
                        if (applied != null) onScoreOverride(issue, null)
                    }, label = { Text("手动指定分数") }, singleLine = true, enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = input.isNotBlank() && !parsed.isValid,
                        supportingText = {
                            Text(if (input.isNotBlank() && !parsed.isValid) parsed.error.orEmpty()
                                else "允许 0 和小数，例如 0.5")
                        }, modifier = Modifier.fillMaxWidth())
                    if (applied == null) {
                        OutlinedButton(enabled = enabled && parsed.isValid, onClick = {
                            onScoreOverride(issue, input)
                        }, modifier = Modifier.fillMaxWidth()) { Text("使用此分数并保留") }
                    } else {
                        Text("已指定 $applied 分，将参与重复与冲突检查。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                        TextButton(enabled = enabled, onClick = {
                            scoreInputs = scoreInputs - issue
                            onScoreOverride(issue, null)
                        }) { Text("撤销保留") }
                    }
                }
                HorizontalDivider()
            }
        }
        ReviewPager(issuePage, (preview.issues.size + size - 1) / size) { issuePage = it }
        if (reviewed.issues.isNotEmpty()) Row(Modifier.fillMaxWidth().toggleable(value = skipIssues, role = Role.Checkbox, enabled = enabled,
            onValueChange = onSkipIssues).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = skipIssues, onCheckedChange = null, enabled = enabled)
            Text("我确认跳过剩余 ${reviewed.issues.size} 行异常", Modifier.weight(1f))
        }
    }
    if (reviewed.conflicts.isNotEmpty()) {
        HorizontalDivider()
        val remaining = reviewed.conflicts.count { it.key !in choices }
        Text("分数冲突 ${reviewed.conflicts.size} 组 · 未处理 $remaining 组", style = MaterialTheme.typography.titleSmall)
        Text("每组选择一个分数或排除整组，不会自动采用任何分数。", style = MaterialTheme.typography.bodySmall)
        val conflict = reviewed.conflicts[conflictIndex]
        val selected = choices[conflict.key]
        Text(conflict.key.activity, style = MaterialTheme.typography.titleSmall)
        Text(listOf(conflict.key.dimension, conflict.key.subcategory,
            conflict.key.role.ifBlank { "未注明身份" }).filter { it.isNotBlank() }.joinToString(" · "))
        conflict.options.forEachIndexed { index, row ->
            OutlinedButton(enabled = enabled, onClick = { onChoose(conflict.key, index) }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (selected == index) "已选用 · " else "选用 · "}${NumericValues.formatForEdit(row.referenceScore)} 分")
            }
            ReferenceSources(row.sources, row.sourceRow)
        }
        OutlinedButton(enabled = enabled, onClick = { onChoose(conflict.key, -1) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected == -1) "已排除整组" else "排除整组")
        }
        ReviewPager(conflictIndex, reviewed.conflicts.size) { conflictIndex = it }
        if (remaining > 0) TextButton(enabled = enabled, onClick = {
            conflictIndex = reviewed.conflicts.indexOfFirst { it.key !in choices }
        }) { Text("查看未处理冲突") }
    }
}

@Composable
internal fun ReferenceSources(sources: List<HistorySource>, fallbackRow: Int) {
    var page by remember(sources) { mutableStateOf(0) }
    if (sources.isEmpty()) Text("原表第 $fallbackRow 行", style = MaterialTheme.typography.bodySmall)
    else {
        sources.drop(page * 5).take(5).forEach { source ->
            Text("${source.label} · 原始分数 ${source.rawScore.ifBlank { "空白" }}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sources.size > 5) ReviewPager(page, (sources.size + 4) / 5) { page = it }
    }
}

@Composable
private fun ReviewPager(page: Int, pages: Int, onPage: (Int) -> Unit) {
    if (pages <= 1) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = page > 0, onClick = { onPage(page - 1) }) { Text("上一页") }
        Text("${page + 1} / $pages", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = page + 1 < pages, onClick = { onPage(page + 1) }) { Text("下一页") }
    }
}
