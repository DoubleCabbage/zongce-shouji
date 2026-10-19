package cn.activityscore.app.data

import android.graphics.Bitmap
import java.io.File
import java.io.OutputStream

object ScoreExcelExport {
    fun write(snapshot: AnnualExportSnapshot, output: OutputStream,
        plan: ExportEntryPlan = ExportEntryPlanner.build(snapshot.scores.academicYear, snapshot.assessment)) {
        plan.requireReady()
        require(plan.source === snapshot.assessment) { "申报行规划与快照不一致，请重新准备导出" }
        val locations = snapshot.scores.activities.flatMap { activity -> activity.participations.flatMap { visit ->
            visit.evidence.map { photo -> EvidenceLocation(activity.item.id, activity.item.title, visit.id, visit.eventDate, photo) }
        } }
        val issues = locations.mapNotNull { EvidenceImages.issue(it, snapshot.evidenceFiles.getValue(it.photo.id)) }
        if (issues.isNotEmpty()) throw EvidenceProblems(issues)
        val locationByFile = locations.associateBy { snapshot.evidenceFiles.getValue(it.photo.id) }
        val records = annualExportRecords(snapshot.scores, snapshot.evidenceFiles, snapshot.assessment, plan, ::preparePhoto)
        ScoreXlsxWriter.write(records, output, snapshot.scores, snapshot.assessment) { photo, destination ->
            val location = locationByFile.getValue(photo.file)
            try {
                if (photo.needsConversion) writePng(photo.file, destination)
                else photo.file.inputStream().use { it.copyTo(destination) }
            } catch (error: Exception) {
                throw EvidenceProblems(listOf(EvidenceIssue(location,
                    (error as? EvidenceInvalid)?.type ?: EvidenceError.DECODE,
                    "Excel 图片处理失败：${error.message}")))
            }
        }
    }

    private fun preparePhoto(file: File): ExportPhoto {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        return when (bounds.outMimeType) {
            "image/jpeg", "image/jpg" -> ExportPhoto(file, "jpg")
            "image/png" -> ExportPhoto(file, "png")
            else -> ExportPhoto(file, "png", needsConversion = true)
        }
    }

    private fun writePng(file: File, output: OutputStream) {
        EvidenceImages.withBitmap(file) { bitmap ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法转换为 Excel 支持的 PNG 图片" }
        }
    }
}
