package cn.activityscore.app.data

import cn.activityscore.app.domain.AnnualActivityScores
import cn.activityscore.app.domain.DuplicateConfirmation
import java.io.File

internal data class ExportSourceState(val items: List<ScoreItem>, val visits: Map<Long, List<Participation>>,
    val confirmations: Map<String, DuplicateConfirmation>)

class ExportSnapshotChanged : IllegalStateException("活动、参与或核对资料在预览后已变化；请重新准备导出并确认合并/拆分及分数归属。")

/** Export-scoped feedback; do not expose SQLite paths or replace the normal write protocol. */
fun exportErrorMessage(error: Exception, stage: String): String {
    val reason = when (error) {
        is ExportSnapshotChanged, is ExportBlocked, is EvidenceProblems, is DataReadException, is DataOperationException -> error.message.orEmpty()
        is android.database.SQLException -> "数据库读取失败。请检查可用空间并重新读取，保留原数据库与有效备份。"
        is org.json.JSONException -> "已存核对资料格式异常。请保留原数据并核对有效备份。"
        is IllegalArgumentException -> error.message ?: "导出数据格式异常，请按记录定位核对。"
        is java.io.IOException -> "文件无法读取或写入。请检查保存位置、访问权限和可用空间后重试。"
        else -> "无法完成此步骤，请重新准备导出；若再次失败，请保留诊断信息。"
    }
    return "${stage}失败 [${error.javaClass.simpleName}]：\n$reason"
}

/** Owned copies of this year's evidence; no live database or private evidence paths are read later. */
class AnnualExportSnapshot internal constructor(
    val scores: AnnualActivityScores,
    internal val evidenceFiles: Map<Long, File>,
    private val directory: File,
    val assessment: AnnualExportAssessment = AnnualExportAssessment.from(scores),
    internal val sourceState: ExportSourceState? = null,
) : AutoCloseable {
    override fun close() { directory.deleteRecursively() }

    internal companion object {
        fun capture(scores: AnnualActivityScores, directory: File,
            assessment: AnnualExportAssessment = AnnualExportAssessment.from(scores), sourceState: ExportSourceState? = null,
            sourceFile: (EvidencePhoto) -> File): AnnualExportSnapshot {
            try {
                assessment.requireReady()
                val files = scores.activities.flatMap { activity -> activity.participations.flatMap { visit ->
                    visit.evidence.map { EvidenceLocation(activity.item.id, activity.item.title, visit.id, visit.eventDate, it) }
                } }.associate { location ->
                        val photo = location.photo
                        val source = sourceFile(photo)
                        val copy = File(directory, "evidence-${photo.id}")
                        try {
                            if (!source.isFile) throw EvidenceInvalid(EvidenceError.MISSING, "证明文件不存在")
                            source.inputStream().use { input -> copy.outputStream().use { EvidencePolicy.copy(input, it, EvidencePolicy.EXISTING_MAX_BYTES) } }
                        } catch (error: Exception) {
                            throw EvidenceProblems(listOf(EvidenceIssue(location, (error as? EvidenceInvalid)?.type ?: EvidenceError.UNREADABLE,
                                "无法复制证明快照：${error.message}")))
                        }
                        photo.id to copy
                    }
                return AnnualExportSnapshot(scores, files, directory, assessment, sourceState)
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        }
    }
}

internal fun annualExportRecords(
    scores: AnnualActivityScores,
    evidenceFiles: Map<Long, File>,
    assessment: AnnualExportAssessment = AnnualExportAssessment.from(scores),
    plan: ExportEntryPlan = ExportEntryPlanner.build(scores.academicYear, assessment),
    preparePhoto: (File) -> ExportPhoto,
): List<ExportRecord> {
    plan.requireReady()
    require(plan.source === assessment && plan.academicYear == scores.academicYear) { "申报行规划与学年快照不一致，请重新准备导出" }
    return plan.entries.map { entry ->
        val projected = entry.assessment
        val activity = projected.activity
        val visits = entry.participations.map { participation ->
            ExportParticipation(
                id = participation.id.toString(),
                date = participation.eventDate,
                note = participation.note,
                confirmedScore = projected.participationScores[participation.id],
                photos = participation.evidence.sortedBy { it.id }.map { preparePhoto(evidenceFiles.getValue(it.id)) },
                scoreStatus = if (projected.participationScores.containsKey(participation.id)) "已确认（单次值）" else projected.status,
            )
        }
        ExportRecord(
            dimension = activity.item.dimension,
            title = entry.title,
            awardLevel = activity.item.role,
            score = entry.score,
            photos = visits.flatMap { it.photos },
            itemId = activity.item.id.toString(),
            participations = visits,
            countingLabel = projected.countingLabel,
            scoreStatus = entry.status,
            subcategory = activity.item.subcategory,
            note = listOf(activity.item.note, entry.explanation).filter { it.isNotEmpty() }.joinToString("\n"),
            entryId = entry.id,
            exportMode = entry.mode.label,
            photoOwners = entry.evidence.joinToString("\n") { "证明 ${it.photo.id} → 参与 ${it.participationId}" },
        )
    }
}
