package cn.activityscore.app.domain

import cn.activityscore.app.data.HistorySource
import cn.activityscore.app.data.sourceLabel
import cn.activityscore.app.data.HistoryRow
import cn.activityscore.app.data.NumericValues
import java.text.Normalizer

data class HistoryActivity(
    val activityName: String,
    val dimension: String,
    val subcategory: String,
    val roles: List<HistoryRole>,
    val variantLabel: String = "",
    val needsReview: Boolean = false,
)

data class HistoryRole(val label: String, val referenceScore: Double, val sourceRow: Int,
                       val sources: List<HistorySource> = emptyList())
data class HistoryMatch(val activity: HistoryActivity, val similarity: Double)

object HistoricalMatcher {
    private val year = Regex("(?<!\\d)(?:19|20)\\d{2}年?")
    private val edition = Regex("第\\s*\\d+\\s*届")
    private val leadingIndex = Regex("^\\s*\\d+\\s*[-—–、.．]\\s*")
    private val nonWord = Regex("[^0-9A-Za-z\\u4e00-\\u9fff]+")

    fun buildTree(rows: List<HistoryRow>): List<HistoryActivity> {
        val result = mutableListOf<HistoryActivity>()
        rows.filter { NumericValues.accepts(it.referenceScore) }
            .groupBy { Triple(it.dimension, it.subcategory, it.activityName) }
            .forEach { (key, group) ->
                val conflict = group.groupBy { it.role }.values.any { roleRows ->
                    roleRows.map { it.referenceScore }.distinct().size > 1
                }
                if (conflict) {
                    group.sortedBy { it.sourceRow }.forEachIndexed { index, row ->
                        result += HistoryActivity(
                            activityName = key.third,
                            dimension = key.first,
                            subcategory = key.second,
                            roles = listOf(HistoryRole(row.role, row.referenceScore, row.sourceRow, row.sources)),
                            variantLabel = "待确认变体 ${index + 1}（${row.sourceLabel}）",
                            needsReview = true,
                        )
                    }
                } else {
                    result += HistoryActivity(
                        activityName = key.third,
                        dimension = key.first,
                        subcategory = key.second,
                        roles = group.distinctBy { it.role to it.referenceScore }
                            .sortedWith(compareByDescending<HistoryRow> { it.referenceScore }
                                .thenBy { it.sourceRow })
                            .map { HistoryRole(it.role, it.referenceScore, it.sourceRow, it.sources) },
                    )
                }
            }
        return result.sortedWith(compareBy({ it.dimension }, { it.activityName }, { it.variantLabel }))
    }

    fun search(
        query: String,
        dimension: String,
        activities: List<HistoryActivity>,
        limit: Int = 5,
        checkCancelled: () -> Unit = {},
    ): List<HistoryMatch> {
        val queryKey = searchKey(query)
        if (queryKey.isEmpty()) return emptyList()
        return activities.map { activity ->
            checkCancelled()
            val activityScore = textSimilarity(queryKey, searchKey(activity.activityName))
            val score = if (dimension.isBlank()) activityScore else
                0.85 * activityScore + 0.15 * if (dimension == activity.dimension) 1.0 else 0.0
            HistoryMatch(activity, score)
        }.sortedWith(compareByDescending<HistoryMatch> { it.similarity }
            .thenBy { it.activity.needsReview }
            .thenBy { it.activity.activityName }
            .thenBy { it.activity.variantLabel })
            .take(limit)
    }

    private fun searchKey(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(leadingIndex, "")
            .replace(year, "")
            .replace(edition, "")
        return normalized.replace(nonWord, "").lowercase()
    }

    private fun textSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (right.isEmpty()) return 0.0
        val sequence = 2.0 * lcsLength(left, right) / (left.length + right.length)
        val leftPairs = ngrams(left)
        val rightPairs = ngrams(right)
        val union = leftPairs union rightPairs
        val jaccard = if (union.isEmpty()) 0.0 else
            (leftPairs intersect rightPairs).size.toDouble() / union.size
        val containment = if (left.contains(right) || right.contains(left)) 1.0 else 0.0
        return 0.5 * sequence + 0.4 * jaccard + 0.1 * containment
    }

    private fun ngrams(text: String): Set<String> {
        if (text.length <= 2) return if (text.isEmpty()) emptySet() else setOf(text)
        return (0..text.length - 2).map { text.substring(it, it + 2) }.toSet()
    }

    private fun lcsLength(left: String, right: String): Int {
        val previous = IntArray(right.length + 1)
        val current = IntArray(right.length + 1)
        for (a in left) {
            for (index in right.indices) {
                current[index + 1] = if (a == right[index]) previous[index] + 1
                else maxOf(current[index], previous[index + 1])
            }
            current.copyInto(previous)
        }
        return previous[right.length]
    }
}
