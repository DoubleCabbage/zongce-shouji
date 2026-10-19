package cn.activityscore.app.data

import cn.activityscore.app.domain.*

data class ExportIssue(
    val itemId: Long,
    val title: String,
    val fieldName: String,
    val reason: String,
    val action: String,
    val participationId: Long? = null,
    val date: String? = null,
) {
    val description: String get() = "$title（活动 $itemId）" +
        (participationId?.let { " / $date（参与 $it）" } ?: "") + " · $fieldName：$reason。$action"
}

class ExportBlocked(val issues: List<ExportIssue>) : IllegalStateException(
    "资料导出被阻断（${issues.size} 项）：\n" + issues.joinToString("\n") { it.description })

data class ExportActivityAssessment(
    val activity: RecognizedActivity,
    val score: Double?,
    val participationScores: Map<Long, Double>,
    val countingLabel: String,
    val status: String,
)

data class ExportDimensionAssessment(val knownSubtotal: Double, val complete: Boolean)

/** Export projection only. Never supplies defaults or feeds partial totals into annual scoring. */
data class AnnualExportAssessment(
    val activities: List<ExportActivityAssessment>,
    val warnings: List<ExportIssue>,
    val blockers: List<ExportIssue>,
    val dimensions: Map<String, ExportDimensionAssessment>,
) {
    val participationCount get() = activities.sumOf { it.activity.participations.size }
    val evidenceCount get() = activities.sumOf { a -> a.activity.participations.sumOf { it.evidence.size } }
    val confirmedCount get() = activities.count { it.score != null }
    val pendingCount get() = activities.size - confirmedCount
    val missingMaterialCount get() = warnings.count { it.fieldName == "参考分数" || it.fieldName == "证明图片" }
    fun requireReady() { if (blockers.isNotEmpty()) throw ExportBlocked(blockers) }

    companion object {
        fun from(scores: AnnualActivityScores, confirmations: Map<String, DuplicateConfirmation> = emptyMap()): AnnualExportAssessment {
            val warnings = mutableListOf<ExportIssue>()
            val blockers = mutableListOf<ExportIssue>()
            val activities = scores.activities.map { activity ->
                val item = activity.item
                fun issue(field: String, reason: String, action: String) = ExportIssue(item.id, item.title, field, reason, action)
                if (item.dimension !in scoreDimensions) blockers += issue("维度", "未知维度：${item.dimension}", "请在活动编辑中选择六个维度之一。")
                if (item.referenceScore != null && !NumericValues.accepts(item.referenceScore))
                    blockers += issue("参考分数", "已存数值无效", "请修正为有限非负数或明确清空。")
                if (item.referenceScore == null) warnings += issue("参考分数", "未填写", "本次参考资料留空，可稍后补充。")
                activity.participations.filter { it.evidence.isEmpty() }.forEach { p ->
                    warnings += ExportIssue(item.id, item.title, "证明图片", "未上传", "保留参与事实，图片留空。", p.id, p.eventDate)
                }
                val pending = scores.pendingReviews.filter { review -> when (review) {
                    is RepeatedParticipationReview -> review.item.id == item.id
                    is SimilarItemsReview -> review.items.any { it.id == item.id }
                } }
                val repeat = pending.filterIsInstance<RepeatedParticipationReview>().singleOrNull()
                val previous = repeat?.let { confirmations[it.key] }
                val current = previous?.takeIf { it.fingerprint == repeat?.fingerprint }
                if (current?.participationScores?.values?.any { !NumericValues.accepts(it) } == true)
                    blockers += issue("逐次确认分", "已存数值无效", "请在重复核对中修正有限非负分数。")
                if (activity.decision == DuplicateDecision.COUNT_EACH && !activity.participationScores.values.sum().isFinite())
                    blockers += issue("活动认可分", "逐次分数合计溢出", "请核对各次确认分数。")
                val partial = if (current?.decision == DuplicateDecision.COUNT_EACH) current.participationScores
                    .filter { (id, value) -> activity.participations.any { it.id == id } && NumericValues.accepts(value) }
                    else emptyMap()
                val stale = pending.any { review -> confirmations[review.key]?.let { it.fingerprint != review.fingerprint } == true }
                val score = activity.recognizedScore.takeIf { pending.isEmpty() }
                val status = when {
                    stale -> "需重新核对"
                    pending.isNotEmpty() -> "待核对"
                    score == null -> "分数待补充"
                    else -> "已确认"
                }
                val counting = when {
                    repeat != null && current?.decision == DuplicateDecision.COUNT_EACH -> "逐次计分（确认分不完整）"
                    repeat != null -> "计分方式待核对"
                    activity.decision == DuplicateDecision.COUNT_ONCE -> "只计一次（条目级）"
                    activity.decision == DuplicateDecision.COUNT_EACH -> "逐次计分"
                    else -> "单次参与"
                }
                if (pending.isNotEmpty()) warnings += issue("计分核对", when {
                    stale -> "原核对指纹已失效，不采用旧认可分"
                    pending.any { it is SimilarItemsReview } -> "同维度同名活动尚待人工核对，各活动分别保留，认可分留空"
                    current?.decision == DuplicateDecision.COUNT_EACH -> "逐次确认分不完整，有效单次值保留，活动总认可分留空"
                    else -> "参与 ${activity.participations.size} 次，尚未确认计分方式，全部事实保留，认可分留空"
                }, "可继续导出资料，稍后在重复核对中处理。")
                val visitScores = when {
                    previous != null && current == null -> emptyMap()
                    activity.decision == DuplicateDecision.COUNT_EACH -> activity.participationScores
                    partial.isNotEmpty() -> partial
                    activity.participations.size == 1 && score != null -> mapOf(activity.participations.single().id to score)
                    else -> emptyMap()
                }
                ExportActivityAssessment(activity, score, visitScores, counting, status)
            }
            scores.invalidParticipations.forEach { p ->
                // Invalid dates may belong to any year; retain a targeted block rather than guess.
                blockers += ExportIssue(p.itemId, scores.invalidParticipationItems[p.itemId]?.title ?: "未归属日期记录", "参与日期", "无法解析原日期", "请按参与 ID 在记录中修正后重试。", p.id, p.eventDate)
            }
            val dimensions = scoreDimensions.associateWith { dimension ->
                val group = activities.filter { it.activity.item.dimension == dimension }
                // Values come exclusively from the shared calculator; no new recognition formula.
                val subtotal = group.mapNotNull { it.score }.sum()
                if (!subtotal.isFinite()) blockers += ExportIssue(0, dimension, "维度合计", "数值溢出", "请核对该维度各活动的已确认分数。")
                ExportDimensionAssessment(subtotal, group.all { it.score != null })
            }
            return AnnualExportAssessment(activities, warnings, blockers, dimensions)
        }
    }
}
