package cn.activityscore.app.domain

import cn.activityscore.app.data.Participation
import cn.activityscore.app.data.ScoreItem
import cn.activityscore.app.data.scoreDimensionCaps
import cn.activityscore.app.data.scoreDimensions

/** Participation facts remain intact; recognition is a separate, year-specific result. */
data class RecognizedActivity(
    val item: ScoreItem,
    val participations: List<Participation>,
    val recognizedScore: Double?,
    val decision: DuplicateDecision?,
    val participationScores: Map<Long, Double>,
)

data class AnnualActivityScores(
    val academicYear: Int,
    val activities: List<RecognizedActivity>,
    val recognizedByDimension: Map<String, Double>,
    val cappedByDimension: Map<String, Double>,
    val pendingReviews: List<DuplicateReview>,
    val invalidParticipations: List<Participation>,
    val invalidParticipationItems: Map<Long, ScoreItem> = emptyMap(),
) {
    val missingScores: Int get() = activities.count { it.recognizedScore == null }
    val isComplete: Boolean get() = missingScores == 0 && pendingReviews.isEmpty() &&
        invalidParticipations.isEmpty() && recognizedByDimension.values.all { it.isFinite() }

}

/** Shared by the home calculation and export. Uses the existing review keys and validation. */
object AnnualActivityScoreCalculator {
    fun calculate(
        year: Int,
        items: List<ScoreItem>,
        participationsByItem: Map<Long, List<Participation>>,
        confirmations: Map<String, DuplicateConfirmation>,
    ): AnnualActivityScores {
        val reviews = DuplicateReviewFinder.all(items, participationsByItem)
            .filter { it.academicYear == year }
        val pending = DuplicateReviewFinder.pending(items, participationsByItem, confirmations)
            .filter { it.academicYear == year }
        val confirmedRepeats = reviews.filterIsInstance<RepeatedParticipationReview>()
            .filterNot { review -> pending.any { it.key == review.key } }
            .associateBy { it.item.id }
        val invalid = mutableListOf<Participation>()
        val activities = items.mapNotNull { item ->
            val visits = participationsByItem[item.id].orEmpty().filter { participation ->
                val visitYear = runCatching {
                    DuplicateReviewFinder.academicYear(participation.eventDate)
                }.getOrNull()
                if (visitYear == null) invalid += participation
                visitYear == year
            }
            if (visits.isEmpty()) return@mapNotNull null
            val review = confirmedRepeats[item.id]
            val confirmation = review?.let { confirmations[it.key] }
            val score = when {
                visits.size > 1 && review == null -> null
                confirmation?.decision == DuplicateDecision.COUNT_EACH ->
                    confirmation.participationScores.values.sum()
                else -> item.referenceScore
            }?.takeIf { it.isFinite() && it >= 0.0 && item.dimension in scoreDimensions }
            RecognizedActivity(
                item, visits.map { it.copy(evidence = it.evidence.toList()) }, score,
                confirmation?.decision,
                if (confirmation?.decision == DuplicateDecision.COUNT_EACH)
                    confirmation.participationScores.toMap() else emptyMap(),
            )
        }
        val totals = scoreDimensions.associateWith { dimension ->
            activities.filter { it.item.dimension == dimension }.sumOf { it.recognizedScore ?: 0.0 }
        }
        val capped = scoreDimensions.associateWith { dimension ->
            totals.getValue(dimension).coerceAtMost(scoreDimensionCaps.getValue(dimension).toDouble())
        }
        return AnnualActivityScores(year, activities, totals, capped, pending, invalid,
            items.filter { item -> invalid.any { it.itemId == item.id } }.associateBy { it.id })
    }
}
