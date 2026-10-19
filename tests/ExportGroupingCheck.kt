package cn.activityscore.app.data

import cn.activityscore.app.domain.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Production planner -> mapper -> actual XLSX ZIP: identities, numeric cells and media relationships. */
fun main() {
    val out = File("build/test-export-grouping").also { it.mkdirs() }
    val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    fun item(id: Long = 1, score: Double? = 0.5, dimension: String = "劳动", title: String = "班级活动") =
        ScoreItem(id, title, dimension, "", "", score, "活动备注", id)
    fun visit(id: Long, date: String, photos: Int = 1, itemId: Long = 1) = Participation(id, itemId, date, "备注$id",
        (1..photos).map { EvidencePhoto(id * 100 + it, id, "photo-$id-$it") })
    val visits = listOf(visit(3, "2026-05-19", 0), visit(2, "2026-04-12", 2), visit(1, "2026-03-05", 1))
    fun assessment(items: List<ScoreItem> = listOf(item()), facts: Map<Long, List<Participation>> = mapOf(1L to visits),
        decision: DuplicateDecision? = DuplicateDecision.COUNT_EACH,
        values: Map<Long, Double> = mapOf(1L to 0.5, 2L to 1.0, 3L to 0.5), year: Int = 2025): Pair<AnnualActivityScores, AnnualExportAssessment> {
        val confirmations = if (decision == null) emptyMap() else DuplicateReviewFinder.all(items, facts)
            .filterIsInstance<RepeatedParticipationReview>().filter { it.academicYear == year }.associate {
                it.key to DuplicateConfirmation(it.key, it.fingerprint, decision, values.filterKeys { id -> it.participations.any { p -> p.id == id } })
            }
        val scores = AnnualActivityScoreCalculator.calculate(year, items, facts, confirmations)
        return scores to AnnualExportAssessment.from(scores, confirmations)
    }
    fun plan(pair: Pair<AnnualActivityScores, AnnualExportAssessment>, choices: Map<Long, ExportChoice> = emptyMap()) =
        ExportEntryPlanner.build(pair.first.academicYear, pair.second, choices)
    fun export(pair: Pair<AnnualActivityScores, AnnualExportAssessment>, plan: ExportEntryPlan, name: String): File {
        val photos = pair.first.activities.flatMap { it.participations }.flatMap { it.evidence }.associate {
            val photo = it
            photo.id to File(out, "${photo.id}.png").apply {
                val bitmap = java.awt.image.BufferedImage(160, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val graphics = bitmap.createGraphics()
                graphics.color = java.awt.Color((photo.id * 37013).toInt() or 0x404040)
                graphics.fillRect(0, 0, bitmap.width, bitmap.height)
                graphics.color = java.awt.Color.WHITE
                graphics.drawString("Evidence ${photo.id}", 12, 52)
                graphics.dispose()
                check(javax.imageio.ImageIO.write(bitmap, "png", this))
            }
        }
        val records = annualExportRecords(pair.first, photos, pair.second, plan) { ExportPhoto(it, "png") }
        return File(out, "$name.xlsx").also { file -> file.outputStream().use { stream ->
            ScoreXlsxWriter.write(records, stream, pair.first, pair.second) { p, target -> p.file.inputStream().use { it.copyTo(target) } }
        } }
    }
    fun cells(zip: ZipFile, sheet: Int): Map<String, Element> {
        val doc = zip.getInputStream(zip.getEntry("xl/worksheets/sheet$sheet.xml")).use { xml.newDocumentBuilder().parse(it) }
        val nodes = doc.getElementsByTagNameNS("*", "c")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("r") }
    }
    fun verify(pair: Pair<AnnualActivityScores, AnnualExportAssessment>, plan: ExportEntryPlan, name: String) {
        ZipFile(export(pair, plan, name)).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }.forEach {
                zip.getInputStream(it).use { input -> xml.newDocumentBuilder().parse(input) }
            }
            val summary = cells(zip, 1)
            val summaryXml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
            check("<col min=\"13\" max=\"13\" hidden=\"1\"/>" in summaryXml)
            check("<col min=\"15\" max=\"15\" hidden=\"1\"/>" in summaryXml)
            plan.entries.forEachIndexed { index, entry ->
                val row = index + 2
                check(summary.getValue("C$row").textContent == entry.title)
                check(summary.getValue("F$row").textContent == entry.itemId.toString())
                check(summary.getValue("M$row").textContent == entry.id)
                check(summary.getValue("O$row").textContent == entry.participations.joinToString(",") { it.id.toString() })
                val cell = summary.getValue("E$row")
                check(cell.textContent == (entry.score?.toString() ?: ""))
                check(cell.getAttribute("t").isEmpty()) // Numeric or true blank, never a formula/string.
            }
            var photoIndex = 1
            scoreDimensions.forEachIndexed { dimensionIndex, dimension ->
                val entries = plan.entries.filter { it.dimension == dimension }
                val detail = cells(zip, dimensionIndex + 2)
                val maxPhotos = entries.maxOfOrNull { it.evidence.size } ?: 0
                val detailXml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet${dimensionIndex + 2}.xml")).reader().readText()
                if (entries.isNotEmpty()) check("<col min=\"${maxPhotos + 10}\" max=\"${maxPhotos + 10}\" hidden=\"1\"/>" in detailXml)
                if (maxPhotos > 0) check("<col min=\"2\" max=\"${maxPhotos + 1}\" width=\"23\" customWidth=\"1\"/>" in detailXml)
                fun col(n: Int): String { var i = n; var s = ""; while (i > 0) { i--; s = ('A'.code + i % 26).toChar() + s; i /= 26 }; return s }
                entries.forEachIndexed { rowIndex, entry ->
                    val row = rowIndex + 2
                    check("<row r=\"$row\" ht=\"${if (entry.evidence.isEmpty()) 24 else 105}\"" in detailXml)
                    check(detail.getValue("${col(maxPhotos + 9)}$row").textContent == entry.title)
                    check(detail.getValue("${col(maxPhotos + 10)}$row").textContent == entry.id)
                    check(detail.getValue("${col(maxPhotos + 2)}$row").textContent == entry.participations.joinToString(",") { it.id.toString() })
                    check(detail.getValue("${col(maxPhotos + 5)}$row").textContent == (entry.score?.toString() ?: ""))
                    repeat(maxPhotos) { p ->
                        val cell = detail.getValue("${col(p + 2)}$row")
                        if (p >= entry.evidence.size) check(cell.textContent.isEmpty() && cell.getAttribute("vm").isEmpty())
                        else {
                            check(cell.getAttribute("vm") == photoIndex.toString())
                            val media = zip.getInputStream(zip.getEntry("xl/media/image$photoIndex.png")).use { it.readBytes() }
                            check(media.contentEquals(File(out, "${entry.evidence[p].photo.id}.png").readBytes()))
                            check(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(media)) != null)
                            photoIndex++
                        }
                    }
                }
                check(detail["A${entries.size + 2}"] == null)
                check(detail.filterKeys { it.endsWith("1") && it.dropWhile { c -> !c.isDigit() } == "1" }.values.all { it.getAttribute("vm").isEmpty() })
                val state = pair.second.dimensions.getValue(dimension)
                val statsRow = summary.entries.last { it.key.startsWith("A") && it.value.textContent == dimension }.key.substring(1)
                check(summary.getValue("B$statsRow").textContent == state.knownSubtotal.toString())
                check(summary.getValue("D$statsRow").textContent == if (state.complete)
                    pair.first.cappedByDimension.getValue(dimension).toString() else "")
            }
            val mediaCount = plan.entries.sumOf { it.evidence.size }
            check(zip.entries().asSequence().count { it.name.startsWith("xl/media/") } == mediaCount)
            if (mediaCount > 0) {
                val rel = zip.getInputStream(zip.getEntry("xl/richData/_rels/richValueRel.xml.rels")).use { xml.newDocumentBuilder().parse(it) }
                val relationships = rel.getElementsByTagNameNS("*", "Relationship")
                check(relationships.length == mediaCount)
                repeat(mediaCount) { i ->
                    val relationship = relationships.item(i) as Element
                    check(relationship.getAttribute("Id") == "rId${i + 1}")
                    check(relationship.getAttribute("Target") == "../media/image${i + 1}.png")
                }
            }
        }
    }
    val original = visits.toString()
    val pair = assessment()
    val modelBefore = pair.first.toString()
    val merged = plan(pair)
    check(merged.entries.single().score == 2.0 && merged.entries.single().title == "班级活动")
    check(merged.entries.single().participations.map { it.id } == listOf(1L, 2L, 3L))
    check(merged.entries.single().evidence.map { it.photo.id } == listOf(101L, 201L, 202L))
    val splitChoice = mapOf(1L to ExportChoice(ExportMode.SPLIT))
    val split = plan(pair, splitChoice)
    check(split.entries.map { it.score } == listOf(0.5, 1.0, 0.5))
    check(split.entries.map { it.title } == listOf("2026-03-05 班级活动", "2026-04-12 班级活动", "2026-05-19 班级活动"))
    verify(pair, merged, "merged-varied")
    verify(pair, split, "split-varied-no-photo")
    check(plan(pair).entries == merged.entries)
    check(visits.toString() == original && pair.first.toString() == modelBefore)
    val halves = assessment(values = visits.associate { it.id to 0.5 })
    check(plan(halves).entries.single().score == 1.5)
    verify(halves, plan(halves), "merged-1.5")
    verify(halves, plan(halves, splitChoice), "split-halves")
    val single = assessment(facts = mapOf(1L to visits.take(1)), decision = null)
    check(plan(single, splitChoice).entries.size == 1 && plan(single, splitChoice).entries.single().mode == ExportMode.MERGED)
    verify(single, plan(single), "single")
    val once = assessment(decision = DuplicateDecision.COUNT_ONCE)
    val unallocated = plan(once, splitChoice)
    check(unallocated.problems.size == 1 && unallocated.entries.all { it.score == null })
    check(runCatching { export(once, unallocated, "must-block") }.isFailure)
    for (carrier in visits) {
        val choice = mapOf(1L to ExportChoice(ExportMode.SPLIT, carrier))
        val allocated = plan(once, choice)
        check(allocated.problems.isEmpty() && allocated.entries.mapNotNull { it.score }.sum() == 0.5)
        check(allocated.entries.single { it.score != null }.participations.single() == carrier)
        verify(once, allocated, "once-carrier-${carrier.id}")
        val changed = assessment(facts = mapOf(1L to visits.map { if (it.id == carrier.id) it.copy(note = "changed") else it }), decision = DuplicateDecision.COUNT_ONCE)
        check(plan(changed, choice).problems.isNotEmpty())
        val deleted = assessment(facts = mapOf(1L to visits.filter { it.id != carrier.id }), decision = DuplicateDecision.COUNT_ONCE)
        check(plan(deleted, choice).problems.isNotEmpty())
    }
    verify(once, plan(once), "once-merged")
    val pending = assessment(decision = null)
    val partial = assessment(values = mapOf(1L to 0.0))
    for ((label, scenario) in listOf("pending" to pending, "partial" to partial,
        "no-reference" to assessment(items = listOf(item(score = null)), decision = null))) {
        check(plan(scenario).entries.single().score == null)
        verify(scenario, plan(scenario), "$label-merged")
        verify(scenario, plan(scenario, splitChoice), "$label-split")
    }
    check(plan(partial, splitChoice).entries.map { it.score } == listOf(0.0, null, null))
    val sameDay = assessment(facts = mapOf(1L to visits.map { it.copy(eventDate = "2026-03-05") }))
    check(plan(sameDay, splitChoice).entries.map { it.title }.toSet().size == 3)
    verify(sameDay, plan(sameDay, splitChoice), "same-day")
    val crossFacts = mapOf(1L to visits + visit(4, "2026-09-01"))
    val cross = assessment(facts = crossFacts)
    check(plan(cross).entries.single().participations.size == 3)
    val nextYear = assessment(facts = crossFacts, year = 2026)
    check(plan(nextYear).entries.single().participations.single().id == 4L)
    verify(cross, plan(cross), "cross-year-2025")
    verify(nextYear, plan(nextYear), "cross-year-2026")
    val sameName = assessment(items = listOf(item(), item(2)), facts = mapOf(1L to visits, 2L to listOf(visit(8, "2026-03-05", itemId = 2))))
    check(plan(sameName).entries.size == 2 && plan(sameName).entries.all { it.score == null })
    verify(sameName, plan(sameName, splitChoice), "same-name-distinct-ids")
    val many = assessment(facts = mapOf(1L to listOf(visit(1, "2026-03-05", 30), visit(2, "2026-03-06", 2), visit(3, "2026-03-07", 0))))
    verify(many, plan(many), "32-photos-dynamic-columns")
    verify(many, plan(many, splitChoice), "32-photos-split")
    for (dimension in scoreDimensions) {
        val capped = assessment(items = listOf(item(score = 9.0, dimension = dimension)), values = visits.associate { it.id to 9.0 })
        check(plan(capped).entries.single().score == 27.0)
        check(plan(capped, splitChoice).entries.mapNotNull { it.score }.sum() == 27.0)
        verify(capped, plan(capped), "cap-${scoreDimensions.indexOf(dimension)}-merged")
        verify(capped, plan(capped, splitChoice), "cap-${scoreDimensions.indexOf(dimension)}-split")
    }
    val tooMany = assessment(facts = mapOf(1L to listOf(visit(1, "2026-03-05", 16371))), decision = null)
    check(runCatching { plan(tooMany).requireReady() }.exceptionOrNull()!!.message!!.contains("16370"))
    val badDate = assessment(facts = mapOf(1L to listOf(visit(1, "legacy-invalid"))), decision = null)
    check(runCatching { plan(badDate) }.exceptionOrNull() is ExportBlocked)
    val emptyOutput = ByteArrayOutputStream()
    check(runCatching { annualExportRecords(pair.first, emptyMap(), pair.second, plan(once)) { error("wrong snapshot") } }.isFailure)
    check(emptyOutput.size() == 0)
    println("P1-10B grouping checks passed: numeric totals, both modes, carrier invalidation, 32 photos, IDs/media/rows, six caps and immutable facts")
}
