package cn.activityscore.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.activityscore.app.data.*

@Composable internal fun JournalActivitiesScreen(presentation: RecordPresentation, dimension: String,
    onDimension: (String) -> Unit, pending: Boolean, onPending: (Boolean) -> Unit, onOpen: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var allYears by rememberSaveable { mutableStateOf(false) }
    var dimensionMenu by remember { mutableStateOf(false) }
    val assessments = presentation.activityAssessment.activities
    val byId = remember(assessments) { assessments.associateBy { it.activity.item.id } }
    val visible = remember(presentation, query, dimension, pending, allYears) {
        presentation.snapshot.items.filter { item ->
            (allYears || item.id in byId) && (dimension == "全部" || item.dimension == dimension) &&
                item.title.contains(query.trim(), ignoreCase = true) && (!pending || byId[item.id]?.score == null)
        }.sortedWith(compareByDescending<ScoreItem> { item ->
            (if (allYears) presentation.snapshot.participations[item.id].orEmpty() else byId[item.id]?.activity?.participations.orEmpty())
                .maxOfOrNull { it.eventDate }.orEmpty()
        }.thenByDescending { it.id })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = JournalSpace.gutter, vertical = 8.dp)) {
        item(key = "filters") {
            OutlinedTextField(query, { query = it }, label = { Text("搜索活动名称") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    FilterChip(onClick = { dimensionMenu = true }, selected = dimension != "全部", label = { Text(if (dimension == "全部") "全部维度" else dimension) })
                    DropdownMenu(dimensionMenu, { dimensionMenu = false }) {
                        (listOf("全部") + scoreDimensions).forEach { d -> DropdownMenuItem(text = { Text(d) },
                            onClick = { dimensionMenu = false; onDimension(d) }) }
                    }
                }
                FilterChip(allYears, { allYears = !allYears }, label = { Text(if (allYears) "全部学年" else "本学年") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(onClick = { onPending(false) }) { Text("全部活动", color = if (!pending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { onPending(true) }) { Text("待核对 ${presentation.activityAssessment.pendingCount}", color = if (pending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider()
            Muted("按最近参与排序 · ${visible.size} 项", Modifier.padding(top = 16.dp))
            if (visible.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                Text("还没有找到活动", style = MaterialTheme.typography.titleMedium)
                Muted("换个关键词，或查看全部学年。未归属日期的旧记录也可以在全部学年中修正。")
                TextButton(onClick = { query = ""; onDimension("全部"); onPending(false); allYears = true }) { Text("清除筛选") }
            }
        }
        items(visible, key = { it.id }, contentType = { "activity" }) { item ->
            ActivityRow(item, if (allYears) presentation.snapshot.participations[item.id].orEmpty() else byId[item.id]?.activity?.participations.orEmpty(),
                byId[item.id], onClick = { onOpen(item.id) })
        }
    }
}
