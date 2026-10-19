package cn.activityscore.app

import cn.activityscore.app.data.RecordSnapshot
import cn.activityscore.app.data.RecordStore
import cn.activityscore.app.data.ScoreSummary
import cn.activityscore.app.data.AnnualExportAssessment
import cn.activityscore.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

internal class SnapshotRefreshOwner {
    val mutex = Mutex()
    var serial = 0L // accessed only on Main; database work never publishes directly
    var active = true
}

/** Derived from exactly one complete, immutable database snapshot. No additional reads. */
internal data class RecordPresentation(
    val snapshot: RecordSnapshot,
    val participationCounts: Map<Long, Int>,
    val summaries: List<ScoreSummary>,
    val allReviews: List<DuplicateReview>,
    val pendingReviews: List<DuplicateReview>,
    val annualScore: AnnualScore,
    val activityAssessment: AnnualExportAssessment,
)

internal suspend fun RecordStore.loadPresentation(year: Int): RecordPresentation {
    val snapshot = withContext(Dispatchers.IO) { loadSnapshot(year) }
    return withContext(Dispatchers.Default) {
        val counts = snapshot.participations.mapValues { it.value.size }
        RecordPresentation(snapshot, counts, ReferenceSumPolicy().summarize(snapshot.items, counts),
            DuplicateReviewFinder.all(snapshot.items, snapshot.participations),
            DuplicateReviewFinder.pending(snapshot.items, snapshot.participations, snapshot.confirmations),
            AnnualScoreCalculator.calculate(year, snapshot.items, snapshot.participations,
                snapshot.confirmations, snapshot.annual),
            AnnualExportAssessment.from(AnnualActivityScoreCalculator.calculate(year, snapshot.items, snapshot.participations, snapshot.confirmations), snapshot.confirmations))
    }
}
