package cn.activityscore.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.activityscore.app.data.NumericValues

@Composable internal fun JournalHomeScreen(presentation: RecordPresentation, onAdd: () -> Unit,
    onPending: () -> Unit, onActivities: () -> Unit, onOpen: (Long) -> Unit) {
    val data = presentation.activityAssessment
    val subtotal = remember(data) { data.dimensions.values.sumOf { it.knownSubtotal }.takeIf { it.isFinite() } }
    val recent = remember(data) { data.activities.sortedByDescending { a -> a.activity.participations.maxOfOrNull { it.eventDate } }.take(2) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = JournalSpace.gutter)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(JournalIcons.verified, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
            Muted("已确认活动分 · 小计")
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(subtotal?.let { NumericValues.formatForDisplay(it) } ?: "—", style = MaterialTheme.typography.displayLarge)
            Muted("分", Modifier.padding(start = 10.dp, bottom = 10.dp))
        }
        Muted("仅汇总已确认活动，不代表最终综测成绩")
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${data.activities.size}", style = MaterialTheme.typography.headlineMedium)
            Muted("项活动", Modifier.weight(1f).padding(start = 8.dp))
            VerticalDivider(Modifier.height(22.dp))
            Text("${data.participationCount}", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 24.dp))
            Muted("次参与", Modifier.weight(1f).padding(start = 8.dp))
        }
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        if (data.pendingCount > 0) Surface(onClick = onPending, color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${data.pendingCount} 项计分待核对", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("先留下记录，核对好再计入分数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Text("查看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        } else Muted("本学年暂无待核对记录")
        Spacer(Modifier.height(16.dp))
        JournalPrimary("记录活动", onAdd)
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("最近活动", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onActivities) { Text("查看全部") }
        }
        recent.forEach { a -> ActivityRow(a.activity.item, a.activity.participations, a, { onOpen(a.activity.item.id) }) }
        if (recent.isEmpty()) {
            Text("这个学年，等你写下第一笔。", modifier = Modifier.padding(vertical = 40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (data.blockers.isNotEmpty()) {
            Text("有 ${data.blockers.size} 项资料异常需修正", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            TextButton(onClick = onActivities) { Text("查看活动与筛选") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
