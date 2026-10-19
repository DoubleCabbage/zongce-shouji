package cn.activityscore.app.data

import cn.activityscore.app.domain.AnnualInputs
import cn.activityscore.app.domain.DuplicateConfirmation

/** Published to UI as one complete value; a failed read never publishes a partial or empty result. */
data class RecordSnapshot(
    val items: List<ScoreItem>,
    val participations: Map<Long, List<Participation>>,
    val history: List<HistoryRow>,
    val historyInfo: HistoryInfo?,
    val confirmations: Map<String, DuplicateConfirmation>,
    val annual: AnnualInputs?,
    val numericIssues: List<String>,
    val shareIds: Set<String>,
)
