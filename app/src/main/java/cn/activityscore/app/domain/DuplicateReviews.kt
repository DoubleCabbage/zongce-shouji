package cn.activityscore.app.domain

import cn.activityscore.app.data.Participation
import cn.activityscore.app.data.ScoreItem
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

enum class DuplicateDecision { COUNT_ONCE, COUNT_EACH, DISTINCT_ITEMS }

data class DuplicateConfirmation(
    val key: String,
    val fingerprint: String,
    val decision: DuplicateDecision,
    val participationScores: Map<Long, Double> = emptyMap(),
)

sealed interface DuplicateReview {
    val key: String
    val fingerprint: String
    val academicYear: Int
}

data class RepeatedParticipationReview(
    val item: ScoreItem,
    override val academicYear: Int,
    val participations: List<Participation>,
    override val fingerprint: String,
) : DuplicateReview {
    override val key = "repeat:${item.id}:$academicYear"
}

data class SimilarItemsReview(
    val dimension: String,
    val title: String,
    override val academicYear: Int,
    val items: List<ScoreItem>,
    override val fingerprint: String,
) : DuplicateReview {
    override val key = "similar:$academicYear:$dimension:${normalizeTitle(title)}"
}

object DuplicateReviewFinder {
    /** A school year starts in September and is named by its starting calendar year. */
    fun academicYear(date: String): Int {
        val parsed = LocalDate.parse(date)
        return if (parsed.monthValue >= 9) parsed.year else parsed.year - 1
    }

    fun pending(
        items: List<ScoreItem>,
        participationsByItem: Map<Long, List<Participation>>,
        confirmations: Map<String, DuplicateConfirmation>,
    ): List<DuplicateReview> = all(items, participationsByItem).filter { review ->
        val confirmation = confirmations[review.key]
        confirmation == null || confirmation.fingerprint != review.fingerprint ||
            !validDecision(review, confirmation)
    }

    fun all(
        items: List<ScoreItem>,
        participationsByItem: Map<Long, List<Participation>>,
    ): List<DuplicateReview> {
        val visits = items.flatMap { item ->
            participationsByItem[item.id].orEmpty().mapNotNull { participation ->
                runCatching { academicYear(participation.eventDate) }.getOrNull()?.let { year ->
                    Triple(item, year, participation)
                }
            }
        }
        val repeated = visits.groupBy { it.first.id to it.second }
            .values.filter { it.size > 1 }.map { group ->
                val item = group.first().first
                val year = group.first().second
                val participations = group.map { it.third }.sortedBy { it.id }
                RepeatedParticipationReview(
                    item, year, participations,
                    fingerprint(listOf(itemFingerprint(item)) + participations.map(::participationFingerprint)),
                )
            }
        val similar = visits.groupBy { (item, year, _) ->
            Triple(year, item.dimension, normalizeTitle(item.title))
        }.values.mapNotNull { group ->
            val distinctItems = group.map { it.first }.distinctBy { it.id }.sortedBy { it.id }
            if (distinctItems.size < 2) return@mapNotNull null
            val (firstItem, year, _) = group.first()
            SimilarItemsReview(
                firstItem.dimension, firstItem.title, year, distinctItems,
                fingerprint(distinctItems.map(::itemFingerprint)),
            )
        }
        return (repeated + similar).sortedWith(compareByDescending<DuplicateReview> { it.academicYear }.thenBy { it.key })
    }

    private fun validDecision(review: DuplicateReview, confirmation: DuplicateConfirmation): Boolean = when (review) {
        is RepeatedParticipationReview -> when (confirmation.decision) {
            DuplicateDecision.COUNT_ONCE -> true
            DuplicateDecision.COUNT_EACH ->
                confirmation.participationScores.keys == review.participations.map { it.id }.toSet() &&
                    confirmation.participationScores.values.all { it.isFinite() && it >= 0.0 }
            DuplicateDecision.DISTINCT_ITEMS -> false
        }
        is SimilarItemsReview -> confirmation.decision == DuplicateDecision.DISTINCT_ITEMS
    }
}

private fun normalizeTitle(title: String): String =
    title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

private fun itemFingerprint(item: ScoreItem): String =
    listOf(item.id, item.title, item.dimension, item.subcategory, item.role, item.referenceScore).joinToString("\u0000")

private fun participationFingerprint(participation: Participation): String =
    listOf(
        participation.id, participation.eventDate, participation.note,
        participation.evidence.map { it.id }.sorted().joinToString(","),
    ).joinToString("\u0000")

private fun fingerprint(parts: List<String>): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0001").toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
