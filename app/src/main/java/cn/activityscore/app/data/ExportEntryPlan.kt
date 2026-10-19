package cn.activityscore.app.data

import cn.activityscore.app.domain.DuplicateDecision

enum class ExportMode(val label: String) { MERGED("合并为一行"), SPLIT("按参与拆分") }

/** Session-only choices. The full carrier fact prevents reuse after that participation changes. */
data class ExportChoice(val mode: ExportMode = ExportMode.MERGED, val scoreCarrier: Participation? = null)

data class ExportEvidenceRef(val participationId: Long, val photo: EvidencePhoto)

data class ExportEntry(
    val id: String,
    val itemId: Long,
    val academicYear: Int,
    val dimension: String,
    val title: String,
    val mode: ExportMode,
    val score: Double?,
    val status: String,
    val explanation: String,
    val participations: List<Participation>,
    val evidence: List<ExportEvidenceRef>,
    val assessment: ExportActivityAssessment,
)

/** One authoritative order and grouping for preview, summary and proof sheets. No business writes. */
class ExportEntryPlan internal constructor(
    val academicYear: Int,
    val entries: List<ExportEntry>,
    val problems: List<String>,
    internal val source: AnnualExportAssessment,
) {
    fun requireReady() {
        source.requireReady()
        require(problems.isEmpty()) { problems.joinToString("\n") }
    }
}

object ExportEntryPlanner {
    fun build(year: Int, assessment: AnnualExportAssessment, choices: Map<Long, ExportChoice> = emptyMap()): ExportEntryPlan {
        assessment.requireReady()
        val problems = mutableListOf<String>()
        val entries = scoreDimensions.flatMap { dimension -> assessment.activities
            .filter { it.activity.item.dimension == dimension }.flatMap { projected ->
                val activity = projected.activity
                val visits = activity.participations.sortedWith(compareBy<Participation> { it.eventDate }.thenBy { it.id })
                val choice = choices[activity.item.id] ?: ExportChoice()
                val mode = if (visits.size > 1) choice.mode else ExportMode.MERGED
                val once = activity.decision == DuplicateDecision.COUNT_ONCE
                val carrier = choice.scoreCarrier?.takeIf { it in visits }
                if (mode == ExportMode.SPLIT && once && projected.score != null && carrier == null)
                    problems += "${activity.item.title}（活动 ${activity.item.id}）：只计一次，请选择承载认可分的参与，或切回合并。"
                val visitsByDay = visits.groupBy { it.eventDate }
                val dayOrdinal = visitsByDay.values.flatMap { day -> day.mapIndexed { i, p -> p.id to i + 1 } }.toMap()
                val groups = if (mode == ExportMode.MERGED) listOf(visits) else visits.map { listOf(it) }
                groups.map { group ->
                    val visit = group.singleOrNull()
                    val sameDay = if (visit == null) emptyList() else visitsByDay.getValue(visit.eventDate)
                    val title = if (mode == ExportMode.MERGED) activity.item.title else
                        "${requireNotNull(visit).eventDate} ${activity.item.title}" +
                            if (sameDay.size > 1) "（第 ${dayOrdinal.getValue(visit.id)} 次）" else ""
                    val score = when {
                        mode == ExportMode.MERGED -> projected.score
                        once -> projected.score.takeIf { carrier?.id == visit?.id }
                        else -> projected.participationScores[requireNotNull(visit).id]
                    }
                    val explanation = if (mode == ExportMode.SPLIT && once) when {
                        projected.score == null -> "只计一次；活动认可分尚未确定"
                        carrier == null -> "只计一次；尚未选择分数承载参与"
                        carrier.id == visit?.id -> "只计一次；由本次参与承载活动认可分"
                        else -> "只计一次；认可分由参与 ${carrier.id} 承载，本行留空"
                    } else ""
                    val status = when {
                        explanation.isNotEmpty() -> "${projected.status}；$explanation"
                        mode == ExportMode.SPLIT && score != null -> "已确认（单次值）；活动${projected.status}"
                        else -> projected.status
                    }
                    ExportEntry("$year:${activity.item.id}:" + if (mode == ExportMode.MERGED) "merged" else "participation:${visit!!.id}",
                        activity.item.id, year, dimension, title, mode, score, status, explanation, group,
                        group.flatMap { p -> p.evidence.sortedBy { it.id }.map { ExportEvidenceRef(p.id, it) } }, projected)
                }
            } }
        val participationIds = entries.flatMap { it.participations }.map { it.id }
        require(participationIds.size == participationIds.toSet().size) { "导出范围内参与 ID 重复，请核对原始关联" }
        require(entries.map { it.id }.toSet().size == entries.size) { "申报行标识重复" }
        // A + photos + the thirteen metadata columns. Reject before ZIP output, without dropping images.
        entries.forEach {
            if (it.evidence.size > 16370) problems += "${it.title}（活动 ${it.itemId}）有 ${it.evidence.size} 张证明，超过每行 16370 张的 Excel 安全列数限制；请选择按参与拆分，若单次仍超限请整理该次证明后重试。"
            val facts = listOf(it.participations.joinToString(",") { p -> p.id.toString() },
                it.participations.joinToString("\n") { p -> "${p.id}: ${p.eventDate}" },
                it.participations.joinToString("\n") { p -> "${p.id}: ${p.note}（单次认可分：${it.assessment.participationScores[p.id]?.toString() ?: "留空"}；${it.assessment.status}）" },
                it.evidence.joinToString("\n") { p -> "证明 ${p.photo.id} → 参与 ${p.participationId}" })
            if (facts.any { text -> text.length > 32767 }) problems += "${it.title} 的参与事实或证明归属说明超过 Excel 单元格 32767 字符限制，请按参与拆分，若单次仍超限请整理备注后重试。"
        }
        require(entries.size <= 1_000_000) { "申报记录过多，请缩小导出范围" }
        if (entries.size.toLong() + assessment.warnings.size + 11 > 1_048_576)
            problems += "申报记录与完整提示超过 Excel 1048576 行限制，请整理资料后重新导出。"
        return ExportEntryPlan(year, entries, problems, assessment)
    }
}
