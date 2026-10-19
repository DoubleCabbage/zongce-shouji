package cn.activityscore.app.tests

import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main() {
    for (text in listOf("-1", "NaN", "Infinity", "-Infinity", "1e309", "abc", "1,5", "1 234", "1.2f", "0x1.0p0", "1e-400"))
        check(!NumericValues.parse(text).isValid) { "Accepted invalid input: $text" }
    check(NumericValues.parse("0").value == 0.0)
    check(NumericValues.parse("1.5").value == 1.5)
    check(NumericValues.parse(" 1.2345 ").value == 1.2345)
    check(NumericValues.parse("1e3").value == 1000.0)
    check(NumericValues.parse("1000").isValid) // No new activity score upper bound.
    check(NumericValues.parse("").isValid && NumericValues.parse("").value == null)
    check(NumericValues.parse("  ").value == null)
    check(!NumericValues.parse("", optional = false).isValid)
    check(!NumericValues.parse("0", rule = NumericRule.POSITIVE).isValid)
    check(!NumericValues.parse("100.1", rule = NumericRule.PERCENT).isValid)

    val oldLocale = Locale.getDefault()
    try {
        val samples = mutableListOf(0.0, -0.0, 1.2345, 99.123456789, Double.MIN_VALUE, Double.MAX_VALUE)
        val random = Random(204)
        repeat(1000) { Double.fromBits(random.nextLong()).takeIf { it.isFinite() && it >= 0 }?.let(samples::add) }
        for (locale in listOf(Locale.CHINA, Locale.US, Locale.GERMANY)) {
            Locale.setDefault(locale)
            check(NumericValues.formatForDisplay(1.2345) == "1.23")
            for (original in samples) {
                var value = original
                repeat(10) {
                    val edit = NumericValues.formatForEdit(value)
                    check(',' !in edit)
                    val parsed = NumericValues.parse(edit)
                    check(parsed.isValid && parsed.value!!.toRawBits() == original.toRawBits()) { "$locale lost $original through $edit" }
                    value = parsed.value!!
                }
            }
            val item = ScoreItem(1, "原活动", "劳动", "", "", 1.2345, "原备注", 0)
            val saved = item.copy(title = "新名称", note = "新备注",
                referenceScore = NumericValues.parse(NumericValues.formatForEdit(item.referenceScore)).value)
            check(saved.referenceScore!!.toRawBits() == item.referenceScore!!.toRawBits())
            val course = CourseGrade("原课程", 1.2345, 87.654321)
            val renamed = course.copy(name = "新课程",
                credits = NumericValues.parse(NumericValues.formatForEdit(course.credits), false, NumericRule.POSITIVE).value!!,
                grade = NumericValues.parse(NumericValues.formatForEdit(course.grade), false, NumericRule.PERCENT).value!!)
            check(renamed.credits == course.credits && renamed.grade == course.grade)
            val confirmation = DuplicateConfirmation("repeat:1:2026", "fp", DuplicateDecision.COUNT_EACH, mapOf(1L to 1.2345, 2L to 2.3456))
            val savedConfirmation = confirmation.copy(participationScores = confirmation.participationScores.mapValues {
                NumericValues.parse(NumericValues.formatForEdit(it.value), false).value!!
            })
            check(savedConfirmation == confirmation)
        }
    } finally { Locale.setDefault(oldLocale) }

    fun rejects(block: () -> Unit) { check(runCatching(block).exceptionOrNull() is IllegalArgumentException) }
    val annual = AnnualInputs(2026, 90.123456, 80.234567, 70.345678, 0.12345, 60.456789, 50.567891,
        listOf(CourseGrade("课程", 1.2345, 87.654321)))
    NumericDataValidation.reference(null)
    NumericDataValidation.reference(0.0)
    NumericDataValidation.reference(Double.MAX_VALUE)
    NumericDataValidation.annual(annual)
    var editedAnnual = annual
    repeat(10) {
        fun edit(value: Double?, rule: NumericRule = NumericRule.PERCENT) =
            NumericValues.parse(NumericValues.formatForEdit(value), rule = rule).value
        editedAnnual = editedAnnual.copy(selfEvaluation = edit(editedAnnual.selfEvaluation),
            peerEvaluation = edit(editedAnnual.peerEvaluation), teacherEvaluation = edit(editedAnnual.teacherEvaluation),
            deductions = edit(editedAnnual.deductions, NumericRule.NON_NEGATIVE),
            fitness = edit(editedAnnual.fitness), running = edit(editedAnnual.running))
        check(editedAnnual == annual)
    }
    for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        rejects { NumericDataValidation.reference(invalid) }
        rejects { NumericDataValidation.history(listOf(HistoryRow(sourceRow = 9, activityName = "活动", dimension = "劳动", subcategory = "", role = "", referenceScore = invalid))) }
        rejects { NumericDataValidation.confirmation(DuplicateConfirmation("key", "fp", DuplicateDecision.COUNT_EACH, mapOf(1L to invalid))) }
        rejects { NumericDataValidation.annual(annual.copy(selfEvaluation = invalid)) }
        rejects { NumericDataValidation.annual(annual.copy(deductions = invalid)) }
        rejects { NumericDataValidation.annual(annual.copy(courses = listOf(CourseGrade("课程", invalid, 80.0)))) }
        rejects { NumericDataValidation.annual(annual.copy(courses = listOf(CourseGrade("课程", 1.5, invalid)))) }
        val score = AnnualScoreCalculator.calculate(2026, emptyList(), emptyMap(), emptyMap(), annual.copy(selfEvaluation = invalid, fitness = invalid))
        check(score.basic == null && score.physicalMental == null && score.total == null)
    }
    check(AnnualScoreCalculator.professional(listOf(CourseGrade("甲", Double.MAX_VALUE, 0.0), CourseGrade("乙", Double.MAX_VALUE, 0.0))) == null)
    check(AnnualScoreCalculator.professional(listOf(CourseGrade("甲", Double.MAX_VALUE, 100.0))) == null)
    check(AnnualScoreCalculator.professional(listOf(CourseGrade("甲", 2.0, 90.0), CourseGrade("乙", 3.0, 80.0))) == 84.0)

    val cases = listOf("1.5", "1,5", "1,234.5", "1.234,5", "1,234", "0", "", " 1.2345 ",
        "1 234", "NaN", "Infinity", "1e309", "-1", "2分", "１．５")
    val headers = listOf("活动名称", "所属维度", "参考分数")
    val mapping = HistoryMapper.suggest(headers)
    fun verify(sheet: ParsedSheet) {
        val preview = HistoryMapper.preview(sheet, 1, mapping)
        check(preview.records.map { it.sourceRow } == listOf(2, 7, 9)) { preview.toString() }
        check(preview.records.map { it.referenceScore } == listOf(1.5, 0.0, 1.2345))
        check(preview.issues.size == cases.size - 3)
        check(preview.issues.single { it.rowNumber == 6 }.reason.contains("歧义"))
        check(preview.issues.single { it.rowNumber == 3 }.reason.contains("逗号"))
        val matched = HistoricalMatcher.buildTree(preview.records).flatMap { it.roles }.single { it.sourceRow == 9 }
        check(NumericValues.parse(NumericValues.formatForEdit(matched.referenceScore)).value == 1.2345)
    }
    val csv = File.createTempFile("numeric-", ".csv")
    val xlsx = File.createTempFile("numeric-", ".xlsx")
    try {
        csv.writeText(headers.joinToString(",") + "\r\n" + cases.mapIndexed { i, value ->
            "活动$i,劳动,\"$value\"\r\n"
        }.joinToString(""))
        verify(LocalSheetReader.read(csv, "CSV"))
        csv.writeText(headers.joinToString(",") + "\n未加引号,劳动,1,5\n\"跨\n行活动\",劳动,\"1,234\"\n")
        val malformedNumbers = HistoryMapper.preview(LocalSheetReader.read(csv, "CSV"), 1, mapping)
        check(malformedNumbers.records.isEmpty())
        check(malformedNumbers.issues.map { it.rowNumber } == listOf(2, 3))
        csv.writeText(headers.joinToString(",") + "\n活动,劳动,1\"2\"\n")
        rejects { LocalSheetReader.read(csv, "CSV") }

        fun inline(ref: String, value: String) = "<c r=\"$ref\" t=\"inlineStr\"><is><t>$value</t></is></c>"
        ZipOutputStream(xlsx.outputStream()).use { zip ->
            fun part(name: String, xml: String) { zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray()); zip.closeEntry() }
            part("xl/workbook.xml", "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"数字\" r:id=\"rId1\"/></sheets></workbook>")
            part("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
            part("xl/sharedStrings.xml", "<sst><si><t>1,5</t></si></sst>")
            part("xl/worksheets/sheet1.xml", buildString {
                append("<worksheet><sheetData><row r=\"1\">")
                headers.forEachIndexed { i, value -> append(inline("${'A' + i}1", value)) }
                append("</row>")
                cases.forEachIndexed { i, value ->
                    val row = i + 2
                    append("<row r=\"$row\">").append(inline("A$row", "活动$i")).append(inline("B$row", "劳动"))
                    append(inline("C$row", value)).append("</row>")
                }
                // Native numeric cells ignore regional display styles; shared text stays text.
                for ((row, cell) in listOf(17 to "<c r=\"C17\" t=\"n\" s=\"1\"><v>1.2345</v></c>",
                    18 to "<c r=\"C18\"><v>0</v></c>", 19 to "<c r=\"C19\" t=\"s\"><v>0</v></c>",
                    20 to "<c r=\"C20\" t=\"b\"><v>1</v></c>", 21 to "<c r=\"C21\"><v>1e309</v></c>")) {
                    append("<row r=\"$row\">").append(inline("A$row", "额外活动$row")).append(inline("B$row", "劳动"))
                    append(cell).append("</row>")
                }
                append("</sheetData></worksheet>")
            })
        }
        val sheet = LocalSheetReader.read(xlsx, "数字") { KXmlParser() }
        verify(sheet.copy(rows = sheet.rows.take(16)))
        check(sheet.cellKind(16, 2) == SheetCellKind.NUMBER)
        check(sheet.cellKind(18, 2) == SheetCellKind.TEXT)
        check(sheet.cellKind(19, 2) == SheetCellKind.OTHER)
        val imported = HistoryMapper.preview(sheet, 1, mapping)
        check(imported.records.takeLast(2).map { it.referenceScore } == listOf(1.2345, 0.0))
        check(imported.issues.takeLast(3).map { it.rowNumber } == listOf(19, 20, 21))
        xlsx.outputStream().use { output ->
            ScoreXlsxWriter.write(listOf(ExportRecord("劳动", "精度导出", "", 1.2345, emptyList(),
                participations = listOf(ExportParticipation("1", "2026-09-01", "备注", 2.3456, emptyList())))), output) { _, _ -> }
        }
        java.util.zip.ZipFile(xlsx).use { zip ->
            fun part(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
            check("<v>1.2345</v>" in part("xl/worksheets/sheet1.xml"))
            check("<v>1.2345</v>" in part("xl/worksheets/sheet5.xml"))
            check("单次认可分：2.3456" in part("xl/worksheets/sheet5.xml"))
        }
        val output = java.io.ByteArrayOutputStream()
        rejects {
            ScoreXlsxWriter.write(listOf(ExportRecord("劳动", "非法确认", "", 1.0, emptyList(),
                participations = listOf(ExportParticipation("1", "2026-09-01", "", Double.NaN, emptyList())))), output) { _, _ -> }
        }
        check(output.size() == 0)
    } finally { csv.delete(); xlsx.delete() }
    println("Numeric consistency checks passed: validation, precision round trips, 3 locales, CSV/XLSX source rows and cell types")
}
