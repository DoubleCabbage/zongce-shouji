package cn.activityscore.app.domain

import cn.activityscore.app.data.Participation
import cn.activityscore.app.data.ScoreItem

fun main() {
    fun item(id: Long, title: String = "晨跑") = ScoreItem(
        id, title, "身心健康", "", "参与者", 2.0, "", id,
    )
    fun visit(id: Long, itemId: Long, date: String) = Participation(id, itemId, date, "", emptyList())
    val first = item(1)
    val p1 = visit(10, 1, "2025-09-01")
    val p2 = visit(11, 1, "2026-08-31")
    val p3 = visit(12, 1, "2026-09-01")
    check(DuplicateReviewFinder.academicYear(p1.eventDate) == 2025)
    check(DuplicateReviewFinder.academicYear(p2.eventDate) == 2025)
    check(DuplicateReviewFinder.academicYear(p3.eventDate) == 2026)

    fun pending(
        items: List<ScoreItem>,
        visits: Map<Long, List<Participation>>,
        confirmations: List<DuplicateConfirmation> = emptyList(),
    ) = DuplicateReviewFinder.pending(items, visits, confirmations.associateBy { it.key })

    val initial = pending(listOf(first), mapOf(first.id to listOf(p1, p2, p3)))
    check(initial.size == 1)
    val repeated = initial.single() as RepeatedParticipationReview
    check(repeated.academicYear == 2025 && repeated.participations.map { it.id } == listOf(10L, 11L))
    val once = DuplicateConfirmation(repeated.key, repeated.fingerprint, DuplicateDecision.COUNT_ONCE)
    check(pending(listOf(first), mapOf(first.id to listOf(p1, p2, p3)), listOf(once)).isEmpty())

    val newVisit = visit(13, 1, "2026-06-01")
    check(pending(listOf(first), mapOf(first.id to listOf(p1, p2, p3, newVisit)), listOf(once)).size == 1)
    val replacement = visit(14, 1, "2026-08-31")
    check(pending(listOf(first), mapOf(first.id to listOf(p1, replacement, p3)), listOf(once)).size == 1)
    check(pending(listOf(first), mapOf(first.id to listOf(p1, p2.copy(eventDate = "2026-08-30"), p3)), listOf(once)).size == 1)
    check(pending(listOf(first), mapOf(first.id to listOf(p1, p3)), listOf(once)).isEmpty())

    val incompleteEach = DuplicateConfirmation(
        repeated.key, repeated.fingerprint, DuplicateDecision.COUNT_EACH, mapOf(10L to 1.0),
    )
    check(pending(listOf(first), mapOf(first.id to listOf(p1, p2)), listOf(incompleteEach)).size == 1)
    val completeEach = incompleteEach.copy(participationScores = mapOf(10L to 1.0, 11L to 1.5))
    check(pending(listOf(first), mapOf(first.id to listOf(p1, p2)), listOf(completeEach)).isEmpty())

    val second = item(2, " 晨 跑 ")
    val otherVisit = visit(20, 2, "2026-03-01")
    val names = pending(listOf(first, second), mapOf(1L to listOf(p1), 2L to listOf(otherVisit)))
    check(names.size == 1 && names.single() is SimilarItemsReview)
    val similar = names.single() as SimilarItemsReview
    val distinct = DuplicateConfirmation(similar.key, similar.fingerprint, DuplicateDecision.DISTINCT_ITEMS)
    check(pending(listOf(first, second), mapOf(1L to listOf(p1), 2L to listOf(otherVisit)), listOf(distinct)).isEmpty())
    check(pending(
        listOf(first, second),
        mapOf(1L to listOf(p1, visit(15, 1, "2026-02-01")), 2L to listOf(otherVisit)),
        listOf(distinct),
    ).count { it is SimilarItemsReview } == 0)
    val third = item(3)
    check(pending(
        listOf(first, second, third),
        mapOf(1L to listOf(p1), 2L to listOf(otherVisit), 3L to listOf(visit(30, 3, "2025-12-01"))),
        listOf(distinct),
    ).size == 1)
    println("Duplicate review checks passed")
}
