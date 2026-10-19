package cn.activityscore.app.domain

import cn.activityscore.app.data.Participation
import cn.activityscore.app.data.ScoreItem
import cn.activityscore.app.data.NumericValues
import cn.activityscore.app.data.NumericRule
import cn.activityscore.app.data.scoreDimensions

data class CourseGrade(val name: String, val credits: Double, val grade: Double)

data class AnnualInputs(
    val academicYear: Int,
    val selfEvaluation: Double? = null,
    val peerEvaluation: Double? = null,
    val teacherEvaluation: Double? = null,
    val deductions: Double? = null,
    val fitness: Double? = null,
    val running: Double? = null,
    val courses: List<CourseGrade> = emptyList(),
)

data class AnnualScore(
    val academicYear: Int,
    val activityByDimension: Map<String, Double>,
    val missingActivityScores: Int,
    val pendingReviews: Int,
    val basic: Double?,
    val professional: Double?,
    val physicalMental: Double?,
    val development: Double?,
    val total: Double?,
    val invalidParticipationCount: Int = 0,
)

object AnnualScoreCalculator {
    fun professional(courses: List<CourseGrade>): Double? {
        if (courses.isEmpty() || courses.any {
                !NumericValues.accepts(it.credits, NumericRule.POSITIVE) || !NumericValues.accepts(it.grade, NumericRule.PERCENT)
            }) return null
        val weighted = courses.sumOf { it.credits * it.grade }
        val credits = courses.sumOf { it.credits }
        if (!weighted.isFinite() || !credits.isFinite()) return null
        return (weighted / credits).takeIf { it.isFinite() }
    }

    fun calculate(
        year: Int,
        items: List<ScoreItem>,
        participationsByItem: Map<Long, List<Participation>>,
        confirmations: Map<String, DuplicateConfirmation>,
        inputs: AnnualInputs?,
    ): AnnualScore {
        val activities = AnnualActivityScoreCalculator.calculate(year, items, participationsByItem, confirmations)
        val capped = activities.cappedByDimension
        val basic = if (listOf(inputs?.selfEvaluation, inputs?.peerEvaluation,
                inputs?.teacherEvaluation).all { it != null && NumericValues.accepts(it, NumericRule.PERCENT) } &&
                inputs?.deductions?.let { NumericValues.accepts(it) } == true
        ) {
            inputs!!.selfEvaluation!! * 0.1 + inputs.peerEvaluation!! * 0.7 +
                inputs.teacherEvaluation!! * 0.2 - inputs.deductions!!
        } else null
        val courses = inputs?.courses.orEmpty()
        val professional = professional(courses)
        val activitiesComplete = activities.isComplete
        val physicalMental = if (activitiesComplete && inputs?.fitness?.let { NumericValues.accepts(it, NumericRule.PERCENT) } == true &&
            inputs.running?.let { NumericValues.accepts(it, NumericRule.PERCENT) } == true) {
            inputs.fitness!! * 0.2 + inputs.running!! * 0.1 + capped.getValue("身心健康")
        } else null
        val development = physicalMental?.let { physical ->
            physical + scoreDimensions.filter { it != "身心健康" }.sumOf { capped.getValue(it) }
        }
        val total = if (basic != null && professional != null && development != null) {
            basic * 0.1 + professional * 0.7 + development * 0.2
        } else null
        return AnnualScore(year, capped, activities.missingScores, activities.pendingReviews.size, basic,
            professional, physicalMental, development, total, activities.invalidParticipations.size)
    }
}
