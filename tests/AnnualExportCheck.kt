package cn.activityscore.app.data

import cn.activityscore.app.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Regression of the shared calculation, production export mapping, snapshot and workbook facts. */
fun main() {
    val output = File("build/test-annual-export").also { it.mkdirs() }
    fun item(id: Long = 1, score: Double? = 2.0, title: String = "活动$id", dimension: String = "劳动") =
        ScoreItem(id, title, dimension, "服务", "参与者", score, "条目备注$id", id)
    fun visit(id: Long, itemId: Long = 1, date: String = "2025-10-01", photo: Boolean = true) =
        Participation(id, itemId, date, "参与备注$id", if (photo) listOf(EvidencePhoto(id, id, "photo$id")) else emptyList())
    fun confirmation(items: List<ScoreItem>, visits: Map<Long, List<Participation>>, year: Int,
        decision: DuplicateDecision, scores: Map<Long, Double> = emptyMap()): DuplicateConfirmation {
        val review = DuplicateReviewFinder.all(items, visits).filterIsInstance<RepeatedParticipationReview>()
            .single { it.academicYear == year }
        return DuplicateConfirmation(review.key, review.fingerprint, decision, scores)
    }
    fun calculate(items: List<ScoreItem>, visits: Map<Long, List<Participation>>,
        confirmed: List<DuplicateConfirmation> = emptyList(), year: Int = 2025) =
        AnnualActivityScoreCalculator.calculate(year, items, visits, confirmed.associateBy { it.key })
    fun records(scores: AnnualActivityScores, assessment: AnnualExportAssessment = AnnualExportAssessment.from(scores)): List<ExportRecord> {
        val photos = scores.activities.flatMap { it.participations }.flatMap { it.evidence }
            .associate { it.id to File(output, "photo-${it.id}.png").apply { writeBytes(byteArrayOf(it.id.toByte())) } }
        return annualExportRecords(scores, photos, assessment) { ExportPhoto(it, "png") }
    }
    val xmlFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    fun cells(zip: ZipFile, sheet: Int): Map<String, String> {
        val document = zip.getInputStream(zip.getEntry("xl/worksheets/sheet$sheet.xml")).use {
            xmlFactory.newDocumentBuilder().parse(it)
        }
        val nodes = document.getElementsByTagNameNS("*", "c")
        return (0 until nodes.length).associate { index ->
            val cell = nodes.item(index) as Element
            cell.getAttribute("r") to cell.textContent
        }
    }
    fun export(scores: AnnualActivityScores, name: String, assessment: AnnualExportAssessment = AnnualExportAssessment.from(scores)): File {
        val workbook = File(output, "$name.xlsx")
        workbook.outputStream().use { stream ->
            ScoreXlsxWriter.write(records(scores, assessment), stream, scores, assessment) { photo, target -> photo.file.inputStream().use { it.copyTo(target) } }
        }
        ZipFile(workbook).use { zip ->
            check((1..7).all { zip.getEntry("xl/worksheets/sheet$it.xml") != null })
            zip.entries().asSequence().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }.forEach {
                zip.getInputStream(it).use { input -> xmlFactory.newDocumentBuilder().parse(input) }
            }
        }
        return workbook
    }
    fun scoreCase(visits: List<Participation>, decision: DuplicateDecision?, expected: Double,
        confirmedScores: Map<Long, Double> = emptyMap(), name: String) {
        val items = listOf(item())
        val byItem = mapOf(1L to visits)
        val confirmed = decision?.let { listOf(confirmation(items, byItem, 2025, it, confirmedScores)) }.orEmpty()
        val scores = calculate(items, byItem, confirmed)
        check(scores.activities.single().recognizedScore == expected)
        val annual = AnnualScoreCalculator.calculate(2025, items, byItem, confirmed.associateBy { it.key }, null)
        check(annual.activityByDimension == scores.cappedByDimension)
        val mapped = records(scores).single()
        check(mapped.participations.size == visits.size && mapped.photos.size == visits.sumOf { it.evidence.size })
        if (decision == DuplicateDecision.COUNT_ONCE) check(mapped.participations.all { it.confirmedScore == null })
        ZipFile(export(scores, name)).use { zip ->
            val summary = cells(zip, 1)
            check(summary["E2"] == expected.toString())
            check(summary["G2"] == "2025—2026")
            val detail = cells(zip, 5) // Labor is the fourth dimension after the total sheet.
            // P1-10B: one planned proof row; all original facts remain traceable in it.
            val facts = visits.size + 2
            fun col(n: Int) = ('A'.code + n - 1).toChar().toString()
            check(detail["A2"] == "1.0" && detail["A3"] == null)
            check(detail["${col(facts)}2"] == visits.joinToString(",") { it.id.toString() })
            visits.forEach { participation ->
                check(detail["${col(facts + 1)}2"]!!.contains("${participation.id}: ${participation.eventDate}"))
                check(detail["${col(facts + 2)}2"]!!.contains(participation.note))
            }
            check(detail["${col(facts + 3)}2"] == expected.toString())
            check(detail["${col(facts + 5)}2"] == "1")
            check(summary["L2"] == visits.size.toDouble().toString())
            check(zip.entries().asSequence().count { it.name.startsWith("xl/media/") } == visits.size)
        }
        println("$name: recognized=$expected; ${visits.size} participation facts retained")
    }
    scoreCase(listOf(visit(10)), null, 2.0, name = "single")
    val three = listOf(visit(10), visit(11), visit(12))
    scoreCase(three, DuplicateDecision.COUNT_ONCE, 2.0, name = "three-once")
    scoreCase(three, DuplicateDecision.COUNT_EACH, 6.0, mapOf(10L to 2.0, 11L to 2.0, 12L to 2.0), "three-each")
    scoreCase(three.take(2), DuplicateDecision.COUNT_EACH, 9.0, mapOf(10L to 4.0, 11L to 5.0), "two-each")

    // Cross-year conclusions and images cannot leak into the other year.
    val items = listOf(item())
    val cross = mapOf(1L to listOf(visit(10, date = "2025-09-01"), visit(11, date = "2026-08-31"),
        visit(20, date = "2026-09-01"), visit(21, date = "2027-08-31")))
    val once = confirmation(items, cross, 2025, DuplicateDecision.COUNT_ONCE)
    val each = confirmation(items, cross, 2026, DuplicateDecision.COUNT_EACH, mapOf(20L to 4.0, 21L to 5.0))
    for (year in listOf(2025, 2026)) {
        val scores = calculate(items, cross, listOf(once, each), year)
        check(scores.activities.single().recognizedScore == if (year == 2025) 2.0 else 9.0)
        val mapped = records(scores).single()
        check(mapped.participations.map { it.id } == if (year == 2025) listOf("10", "11") else listOf("20", "21"))
        ZipFile(export(scores, "year-$year")).use { zip ->
            val text = cells(zip, 5).values.joinToString("|")
            check((if (year == 2025) "2026-09-01" else "2025-09-01") !in text)
            check(zip.entries().asSequence().count { it.name.startsWith("xl/media/") } == 2)
        }
    }
    fun blocked(scores: AnnualActivityScores) {
        check(!scores.isComplete)
        val buffer = ByteArrayOutputStream()
        check(runCatching {
            ScoreXlsxWriter.write(records(scores), buffer, scores) { _, _ -> error("Must not write images") }
        }.isFailure)
        check(buffer.size() == 0)
    }
    // P1-10A: incomplete recognition no longer blocks record/material exports.
    fun pendingExport(scores: AnnualActivityScores, name: String) {
        check(!scores.isComplete)
        val assessment = AnnualExportAssessment.from(scores)
        check(assessment.pendingCount == assessment.activities.size)
        val mapped = records(scores)
        check(mapped.sumOf { it.participations.size } == scores.activities.sumOf { it.participations.size })
        check(mapped.all { it.score == null })
        ZipFile(export(scores, name)).use { zip ->
            val summary = cells(zip, 1)
            check(summary["E2"] == "")
            check(summary.values.any { it.contains("未确认项目不构成正式核定成绩") })
            check(summary.values.any { it.contains("非最终总分") })
        }
    }
    pendingExport(calculate(items, cross, listOf(once), 2026), "other-year-pending")
    val repeated = mapOf(1L to three)
    pendingExport(calculate(items, repeated), "three-pending")
    val incomplete = confirmation(items, repeated, 2025, DuplicateDecision.COUNT_EACH, mapOf(10L to 2.0))
    pendingExport(calculate(items, repeated, listOf(incomplete)), "partial-pending")
    val stale = confirmation(items, repeated, 2025, DuplicateDecision.COUNT_ONCE)
    pendingExport(calculate(items, mapOf(1L to three + visit(13)), listOf(stale)), "stale-pending")
    // Existing similar-item review is preserved: no automatic same-name scoring decision.
    pendingExport(calculate(listOf(item(), item(2, title = "活动1")), mapOf(1L to listOf(visit(10)), 2L to listOf(visit(20, 2)))), "similar-pending")

    val capItems = listOf(item(score = 9.0), item(2, score = 9.0))
    val capped = calculate(capItems, mapOf(1L to listOf(visit(10)), 2L to listOf(visit(20, 2))))
    check(capped.recognizedByDimension.getValue("劳动") == 18.0)
    check(capped.cappedByDimension.getValue("劳动") == 12.0)
    ZipFile(export(capped, "capped")).use { zip ->
        val summary = cells(zip, 1)
        check(summary["E2"] == "9.0" && summary["E3"] == "9.0")
        check(summary["B10"] == "18.0" && summary["C10"] == "12.0" && summary["D10"] == "12.0")
    }
    val inactive = calculate(listOf(item(score = null)), emptyMap())
    check(inactive.activities.isEmpty() && inactive.recognizedByDimension.values.all { it == 0.0 })
    export(inactive, "inactive")
    pendingExport(calculate(listOf(item(score = null)), mapOf(1L to listOf(visit(10)))), "missing-score")
    for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
        blocked(calculate(listOf(item(score = bad)), mapOf(1L to listOf(visit(10)))))
    }
    blocked(calculate(listOf(item(dimension = "旧未知维度")), mapOf(1L to listOf(visit(10)))))
    blocked(calculate(items, mapOf(1L to listOf(visit(10, date = "invalid-old-date")))))
    // Confirmed per-visit scores can validly stand alone when the reference is absent.
    val blankItems = listOf(item(score = null))
    val validEach = confirmation(blankItems, repeated, 2025, DuplicateDecision.COUNT_EACH,
        mapOf(10L to 2.0, 11L to 2.0, 12L to 2.0))
    check(calculate(blankItems, repeated, listOf(validEach)).activities.single().recognizedScore == 6.0)

    val partialScores = calculate(items, repeated, listOf(incomplete))
    val partialAssessment = AnnualExportAssessment.from(partialScores, mapOf(incomplete.key to incomplete))
    check(partialAssessment.activities.single().participationScores == mapOf(10L to 2.0))
    ZipFile(export(partialScores, "partial-values", partialAssessment)).use { zip ->
        val detail = cells(zip, 5)
        check(detail["H2"] == "" && detail["A3"] == null)
        check(detail["G2"]!!.contains("10: 参与备注10（单次认可分：2.0；已确认（单次值））"))
        check(detail["G2"]!!.contains("11: 参与备注11（单次认可分：留空；待核对）"))
        check(detail["K2"] == "待核对")
        check(cells(zip, 1)["E2"] == "")
    }
    for (changed in listOf(three.map { it.copy(note = "修改备注") }, three.map { it.copy(eventDate = "2025-11-01") },
        three.map { it.copy(evidence = emptyList()) })) {
        val staleScores = calculate(items, mapOf(1L to changed), listOf(validEach))
        val assessment = AnnualExportAssessment.from(staleScores, mapOf(validEach.key to validEach))
        check(assessment.activities.single().status == "需重新核对")
        check(assessment.activities.single().participationScores.isEmpty())
        check(records(staleScores, assessment).single().participations.all { it.confirmedScore == null })
    }
    // A stale same-name group must not erase an independently valid per-visit confirmation.
    val sameItems = items + item(2, title = "活动1")
    val sameVisits = repeated + (2L to listOf(visit(20, 2)))
    val similar = DuplicateReviewFinder.all(sameItems, sameVisits).filterIsInstance<SimilarItemsReview>().single()
    val distinct = DuplicateConfirmation(similar.key, similar.fingerprint, DuplicateDecision.DISTINCT_ITEMS)
    val validRepeat = confirmation(sameItems, sameVisits, 2025, DuplicateDecision.COUNT_EACH, mapOf(10L to 4.0,11L to 5.0,12L to 0.0))
    val changedGroupItems = sameItems + item(3, title = "活动1")
    val changedGroupVisits = sameVisits + (3L to listOf(visit(30, 3)))
    val changedGroup = calculate(changedGroupItems, changedGroupVisits, listOf(distinct,validRepeat))
    val groupAssessment = AnnualExportAssessment.from(changedGroup, listOf(distinct,validRepeat).associateBy { it.key })
    check(groupAssessment.activities.all { it.score == null })
    check(groupAssessment.activities.first().participationScores == validRepeat.participationScores)
    val blankAndZero = calculate(listOf(item(score = null), item(2, score = 0.0)),
        mapOf(1L to listOf(visit(10, photo = false)), 2L to listOf(visit(20, 2, photo = false))))
    ZipFile(export(blankAndZero, "blank-vs-zero")).use { zip ->
        val summary = cells(zip, 1)
        check(summary["E2"] == "" && summary["E3"] == "0.0")
        // No image columns existed in the P1-01 all-no-photo layout: IDs still start at B.
        val detail = cells(zip, 5)
        check(detail["B2"] == "10" && detail["B3"] == "20")
        check(zip.entries().asSequence().none { it.name.startsWith("xl/media/") })
    }
    for (dimension in scoreDimensions) {
        val dimensionItems = listOf(item(score = 9.0, dimension = dimension), item(2, score = null, dimension = dimension))
        val score = calculate(dimensionItems, mapOf(1L to listOf(visit(10)), 2L to listOf(visit(20, 2))))
        val assessment = AnnualExportAssessment.from(score)
        check(assessment.dimensions.getValue(dimension) == ExportDimensionAssessment(9.0, false))
        ZipFile(export(score, "uncertain-${scoreDimensions.indexOf(dimension)}")).use { zip ->
            val summary = cells(zip, 1)
            val row = 7 + scoreDimensions.indexOf(dimension)
            check(summary["B$row"] == "9.0" && summary["D$row"] == "")
            check(summary["E$row"]!!.contains("非最终总分"))
        }
    }

    // A zero-photo participation remains in merged facts; both photos share the planned row.
    val mixedVisits = listOf(visit(10, photo = false), visit(11).copy(evidence =
        listOf(EvidencePhoto(11, 11, "a"), EvidencePhoto(12, 11, "b"))))
    val mixedByItem = mapOf(1L to mixedVisits)
    val mixedOnce = confirmation(items, mixedByItem, 2025, DuplicateDecision.COUNT_ONCE)
    val mixed = calculate(items, mixedByItem, listOf(mixedOnce))
    ZipFile(export(mixed, "mixed-evidence")).use { zip ->
        val detail = cells(zip, 5)
        check(detail["B2"] == "#VALUE!" && detail["C2"] == "#VALUE!")
        check(detail["A3"] == null && detail["D2"] == "10,11")
        check(detail["F2"]!!.contains("参与备注10") && detail["F2"]!!.contains("参与备注11"))
        check(detail["G2"] == "2.0")
        check(zip.getInputStream(zip.getEntry("xl/media/image1.png")).use { it.readBytes() }.contentEquals(byteArrayOf(11)))
        check(zip.getInputStream(zip.getEntry("xl/media/image2.png")).use { it.readBytes() }.contentEquals(byteArrayOf(12)))
    }
    // An incomplete other year cannot block a complete selected year or enter its export.
    val otherYearMissing = calculate(listOf(item(), item(2, score = null)),
        mapOf(1L to listOf(visit(10)), 2L to listOf(visit(20, 2, "2026-09-01"))))
    check(otherYearMissing.isComplete && otherYearMissing.activities.size == 1)
    blocked(calculate(listOf(item(score = 1e308), item(2, score = 1e308)),
        mapOf(1L to listOf(visit(10)), 2L to listOf(visit(20, 2)))))

    // Actual production capture helper owns evidence bytes and copied participation facts.
    val photo = File(output, "source-photo").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val sourceVisits = mutableListOf(visit(10))
    val snapshotScores = calculate(items, mapOf(1L to sourceVisits))
    val directory = File(output, "snapshot").also { check(it.mkdir()) }
    val snapshot = AnnualExportSnapshot.capture(snapshotScores, directory) { photo }
    photo.writeBytes(byteArrayOf(9))
    check(photo.delete())
    sourceVisits.clear()
    check(snapshot.scores.activities.single().participations.size == 1)
    check(snapshot.evidenceFiles.getValue(10).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    snapshot.close()
    check(!directory.exists())
    val failedDirectory = File(output, "failed-snapshot").also { check(it.mkdir()) }
    check(runCatching { AnnualExportSnapshot.capture(snapshotScores, failedDirectory) { photo } }.isFailure)
    check(!failedDirectory.exists())
    println("Annual export regression checks passed")
}
