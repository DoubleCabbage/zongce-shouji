package cn.activityscore.app

import androidx.compose.runtime.*
import cn.activityscore.app.data.HistoryRow
import cn.activityscore.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun rememberHistoryActivities(rows: List<HistoryRow>): List<HistoryActivity> {
    var activities by remember(rows) { mutableStateOf(emptyList<HistoryActivity>()) }
    LaunchedEffect(rows) {
        activities = withContext(Dispatchers.Default) { HistoricalMatcher.buildTree(rows) }
    }
    return activities
}

@Composable
internal fun rememberHistoryMatches(query: String, dimension: String,
    activities: List<HistoryActivity>, enabled: Boolean): List<HistoryMatch> {
    // A new key starts with no candidates: stale query/score suggestions must not be selectable.
    var matches by remember(query, dimension, activities, enabled) { mutableStateOf(emptyList<HistoryMatch>()) }
    LaunchedEffect(query, dimension, activities, enabled) {
        if (enabled && query.trim().length >= 2 && activities.isNotEmpty()) {
            delay(150)
            matches = withContext(Dispatchers.Default) {
                HistoricalMatcher.search(query, dimension, activities, checkCancelled = { ensureActive() })
            }
        }
    }
    return matches
}
