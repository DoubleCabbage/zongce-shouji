package cn.activityscore.app.domain

import cn.activityscore.app.data.ScoreItem
import cn.activityscore.app.data.ScoreSummary
import cn.activityscore.app.data.NumericValues
import cn.activityscore.app.data.scoreDimensions

/** Category caps and school rules can replace this policy when supplied. */
interface DimensionScorePolicy {
    fun summarize(items: List<ScoreItem>, participationCounts: Map<Long, Int>): List<ScoreSummary>
}

class ReferenceSumPolicy : DimensionScorePolicy {
    override fun summarize(
        items: List<ScoreItem>, participationCounts: Map<Long, Int>
    ): List<ScoreSummary> = scoreDimensions.map { dimension ->
        val inDimension = items.filter { it.dimension == dimension }
        ScoreSummary(
            dimension = dimension,
            itemCount = inDimension.size,
            participationCount = inDimension.sumOf { participationCounts[it.id] ?: 0 },
            referenceTotal = inDimension.sumOf { it.referenceScore?.takeIf { score -> NumericValues.accepts(score) } ?: 0.0 },
            missingReferenceCount = inDimension.count { it.referenceScore?.let { score -> NumericValues.accepts(score) } != true },
        )
    }
}
