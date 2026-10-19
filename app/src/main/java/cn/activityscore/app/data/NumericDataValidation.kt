package cn.activityscore.app.data

import cn.activityscore.app.domain.AnnualInputs
import cn.activityscore.app.domain.DuplicateConfirmation

/** Called before database transactions, JSON serialization, or file writes. */
object NumericDataValidation {
    fun reference(value: Double?, label: String = "参考分数") = NumericValues.requireValid(value, label)

    fun history(records: List<HistoryRow>) {
        records.forEach { NumericValues.requireValid(it.referenceScore, "历史表第 ${it.sourceRow} 行参考分数", optional = false) }
    }

    fun confirmation(value: DuplicateConfirmation) {
        value.participationScores.forEach { (id, score) ->
            NumericValues.requireValid(score, "核对 ${value.key} 参与 $id 确认分", optional = false)
        }
    }

    fun annual(inputs: AnnualInputs) {
        listOf("自评" to inputs.selfEvaluation, "互评" to inputs.peerEvaluation,
            "教师评议" to inputs.teacherEvaluation, "体测" to inputs.fitness, "健步跑" to inputs.running
        ).forEach { (label, value) -> NumericValues.requireValid(value, label, NumericRule.PERCENT) }
        NumericValues.requireValid(inputs.deductions, "扣分项")
        inputs.courses.forEachIndexed { index, course ->
            require(course.name.isNotBlank()) { "课程 ${index + 1} 名称不能为空" }
            NumericValues.requireValid(course.credits, "课程 ${index + 1} 学分", NumericRule.POSITIVE, optional = false)
            NumericValues.requireValid(course.grade, "课程 ${index + 1} 成绩", NumericRule.PERCENT, optional = false)
        }
    }
}
