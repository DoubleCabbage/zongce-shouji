package cn.activityscore.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.activityscore.app.data.*

internal object JournalSpace { val gutter = 24.dp; val section = 24.dp; val gap = 12.dp }

@Composable internal fun AcademicYearSelector(year: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$year—${year + 1} ▾", style = MaterialTheme.typography.labelMedium) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("上一学年 · ${year - 1}—$year") }, onClick = { expanded = false; onChange(year - 1) })
            DropdownMenuItem(text = { Text("下一学年 · ${year + 1}—${year + 2}") }, onClick = { expanded = false; onChange(year + 1) })
        }
    }
}

@Composable internal fun JournalTopBar(tab: Int, year: Int, onYear: (Int) -> Unit, onMore: () -> Unit, onAdd: () -> Unit) {
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.4f
    val title = when (tab) { 0 -> "拾光"; 2 -> "活动"; 3 -> "申报学年"; 1 -> "历史评分"; else -> "更多与资料" }
    @Composable fun Action() {
        if (tab == 2) TextButton(onClick = onAdd, contentPadding = PaddingValues(8.dp)) {
            Icon(JournalIcons.plus, null, Modifier.size(18.dp)); Text("新增")
        } else TextButton(onClick = onMore) { Text(if (tab == 4) "返回" else "更多") }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = JournalSpace.gutter)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!largeText && tab in listOf(0, 2, 3)) AcademicYearSelector(year, onYear)
            Action()
        }
        if (largeText && tab in listOf(0, 2, 3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AcademicYearSelector(year, onYear)
        }
    }
}

internal data class JournalDestination(val tab: Int, val label: String, val icon: ImageVector)
internal val journalDestinations = listOf(JournalDestination(0, "首页", JournalIcons.home),
    JournalDestination(2, "活动", JournalIcons.list), JournalDestination(3, "整理", JournalIcons.organize))

@Composable internal fun JournalNavigation(tab: Int, onSelect: (Int) -> Unit, rail: Boolean = false) {
    val selected = if (tab in listOf(1, 4)) 3 else tab
    if (rail) NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
        journalDestinations.forEach { d -> NavigationRailItem(selected == d.tab, { onSelect(d.tab) },
            icon = { Icon(d.icon, null) }, label = { Text(d.label) }) }
    } else {
        HorizontalDivider()
        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            journalDestinations.forEach { d -> NavigationBarItem(selected == d.tab, { onSelect(d.tab) },
                icon = { Icon(d.icon, null) }, label = { Text(d.label) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary)) }
        }
    }
}

@Composable internal fun JournalPrimary(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(13.dp)) {
        val icon = if (label in listOf("记录活动", "再参加一次")) JournalIcons.plus else if (label.contains("Excel")) JournalIcons.download else null
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label)
    }
}

@Composable internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun ScoreStatus(assessment: ExportActivityAssessment?) {
    Text(assessment?.status ?: "本学年无参与", style = MaterialTheme.typography.labelMedium,
        color = if (assessment?.score != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
}

@Composable internal fun ActivityRow(item: ScoreItem, visits: List<Participation>, assessment: ExportActivityAssessment?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(JournalIcons.activity(item.dimension), null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Muted("${item.dimension} · ${visits.maxByOrNull { it.eventDate }?.eventDate?.drop(5)?.replace('-', '.') ?: "暂无日期"} · ${visits.size} 次参与")
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 104.dp)) {
            Text(assessment?.score?.let { NumericValues.formatForDisplay(it) } ?: "—", style = MaterialTheme.typography.titleSmall)
            ScoreStatus(assessment)
        }
    }
    HorizontalDivider()
}
