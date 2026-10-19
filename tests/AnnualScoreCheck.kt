package cn.activityscore.app.domain

import cn.activityscore.app.data.Participation
import cn.activityscore.app.data.ScoreItem

fun main() {
    fun item(id: Long, dimension: String, score: Double?) =
        ScoreItem(id, "活动$id", dimension, "", "", score, "", id)
    fun visit(id: Long, itemId: Long, date: String) = Participation(id, itemId, date, "", emptyList())
    val health = item(1, "身心健康", 7.0)
    val academic = item(2, "学术创新", 20.0)
    val labor = item(3, "劳动", null)
    val items = listOf(health, academic, labor)
    val visits = mapOf(
        1L to listOf(visit(10, 1, "2025-09-01"), visit(11, 1, "2026-08-31"), visit(12, 1, "2026-09-01")),
        2L to listOf(visit(20, 2, "2025-10-01")),
        3L to listOf(visit(30, 3, "2025-12-01")),
    )
    val inputs = AnnualInputs(2025, 90.0, 80.0, 70.0, 2.0, 85.0, 90.0,
        listOf(CourseGrade("数学", 2.0, 80.0), CourseGrade("语文", 1.0, 95.0)))
    val pending = AnnualScoreCalculator.calculate(2025, items, visits, emptyMap(), inputs)
    check(pending.total == null && pending.pendingReviews == 1 && pending.missingActivityScores == 2)
    check(pending.activityByDimension.getValue("学术创新") == 14.0)

    val review = DuplicateReviewFinder.all(items, visits).single() as RepeatedParticipationReview
    val once = DuplicateConfirmation(review.key, review.fingerprint, DuplicateDecision.COUNT_ONCE)
    val withMissing = AnnualScoreCalculator.calculate(2025, items, visits,
        mapOf(review.key to once), inputs)
    check(withMissing.activityByDimension.getValue("身心健康") == 7.0)
    check(withMissing.missingActivityScores == 1 && withMissing.total == null)

    val scoredItems = items.map { if (it.id == 3L) it.copy(referenceScore = 3.0) else it }
    val updatedReview = DuplicateReviewFinder.all(scoredItems, visits).single() as RepeatedParticipationReview
    val each = DuplicateConfirmation(updatedReview.key, updatedReview.fingerprint,
        DuplicateDecision.COUNT_EACH, mapOf(10L to 4.0, 11L to 5.0))
    val complete = AnnualScoreCalculator.calculate(2025, scoredItems, visits,
        mapOf(updatedReview.key to each), inputs)
    check(complete.activityByDimension.getValue("身心健康") == 9.0)
    check(complete.basic == 77.0)
    check(complete.professional == 85.0)
    check(complete.physicalMental == 35.0)
    check(complete.development == 52.0)
    check(kotlin.math.abs(requireNotNull(complete.total) - 77.6) < 1e-9)

    val noReferenceButEach = AnnualScoreCalculator.calculate(2025,
        scoredItems.map { if (it.id == 1L) it.copy(referenceScore = null) else it },
        visits, mapOf(updatedReview.key to each), inputs)
    check(noReferenceButEach.total == null) // Editing the item invalidates its earlier confirmation.
    val blankHealth = scoredItems.map { if (it.id == 1L) it.copy(referenceScore = null) else it }
    val blankReview = DuplicateReviewFinder.all(blankHealth, visits).single() as RepeatedParticipationReview
    val reconfirmed = each.copy(fingerprint = blankReview.fingerprint)
    val eachWithoutReference = AnnualScoreCalculator.calculate(2025, blankHealth, visits,
        mapOf(blankReview.key to reconfirmed), inputs)
    check(eachWithoutReference.missingActivityScores == 0 && eachWithoutReference.total != null)

    val nextYear = AnnualScoreCalculator.calculate(2026, scoredItems, visits, emptyMap(), inputs)
    check(nextYear.activityByDimension.getValue("身心健康") == 7.0)
    check(nextYear.activityByDimension.getValue("学术创新") == 0.0)

    val noCourses = AnnualScoreCalculator.calculate(2025, scoredItems, visits,
        mapOf(updatedReview.key to each), inputs.copy(courses = emptyList()))
    check(noCourses.professional == null && noCourses.total == null)
    println("Annual score checks passed")
}
