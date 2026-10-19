package cn.activityscore.app.tests

import cn.activityscore.app.data.*

fun main() {
    val headers = listOf("活动名称", "指标名称", "参考分数", "获奖等级", "小类")
    val mapping = HistoryMapper.suggest(headers)
    fun sheet(name: String, vararg rows: List<String>) = ParsedSheet(name, listOf(headers) + rows)
    fun row(name: String, score: String, role: String = "参与", dimension: String = "劳动", sub: String = "服务") =
        listOf(name, dimension, score, role, sub)
    val importer = WorkbookImport(headers, 1, mapping)
    importer.add(sheet("分类甲", row("社区服务", "1"), row("讨论活动", "面试讨论"), row("竞赛", "2"),
        row("竞赛", "1", "观众"), row("零分", "0"), row("负分", "-1")))
    importer.add(sheet("分类乙", row("社区服务", "1.0"), row("竞赛", "3"), row("零分", "-0"),
        row("社区服务", "4", dimension = "美育"), row("社区服务", "5", sub = "其他")))
    val result = importer.finish()
    check(result.duplicateCount == 2)
    check(result.conflicts.size == 1)
    check(result.records.size == 5)
    check(result.issues.size == 2)
    check(result.issues.first().let { it.sheetName == "分类甲" && it.rowNumber == 3 && it.rawScore == "面试讨论" })
    val conflict = result.conflicts.single()
    check(conflict.options.map { it.referenceScore } == listOf(2.0, 3.0))
    check(runCatching { result.resolve(emptyMap(), true) }.isFailure)
    check(runCatching { result.resolve(mapOf(conflict.key to 1), false) }.isFailure)
    check(runCatching { result.resolve(mapOf(conflict.key to 99), true) }.isFailure)
    val resolved = result.resolve(mapOf(conflict.key to 1), true)
    check(resolved.records.size == 6 && resolved.issues.size == 3)
    check(resolved.records.single { it.activityName == "竞赛" && it.role == "参与" }.referenceScore == 3.0)
    check(resolved.records.single { it.activityName == "社区服务" && it.dimension == "劳动" && it.subcategory == "服务" }
        .sources.map { it.sheet } == listOf("分类甲", "分类乙"))
    check(result.resolve(mapOf(conflict.key to -1), true).records.size == 5)

    val repeatedConflict = WorkbookImport(headers, 1, mapping)
    repeatedConflict.add(sheet("重复冲突", row("竞赛", "2"), row("竞赛", "2.0"), row("竞赛", "3"), row("正常", "1")))
    val repeated = repeatedConflict.finish()
    val chosen = repeated.resolve(mapOf(repeated.conflicts.single().key to 1), true)
    check(chosen.records.size == 2 && chosen.issues.size == 2 && chosen.mergedDuplicateCount == 0)
    check(repeated.resolve(mapOf(repeated.conflicts.single().key to 0), true).mergedDuplicateCount == 1)

    // Reordered headers work; missing/duplicate mapped headers block the entire sheet.
    val reordered = WorkbookImport(headers, 1, mapping)
    reordered.add(ParsedSheet("重排", listOf(headers.reversed(), row("重排活动", "1.25").reversed())))
    check(reordered.finish().records.single().referenceScore == 1.25)
    reordered.add(ParsedSheet("缺列", listOf(listOf("活动名称", "参考分数"), listOf("活动", "2"))))
    val blocked = reordered.finish()
    check(blocked.hasSheetErrors && blocked.sheets.last().error.contains("指标名称"))
    check(runCatching { blocked.resolve(emptyMap(), true) }.isFailure)
    val duplicateHeader = WorkbookImport(headers, 1, mapping)
    duplicateHeader.add(ParsedSheet("重复列", listOf(headers + "参考分数", row("活动", "2") + "3")))
    check(duplicateHeader.finish().hasSheetErrors)
    val empty = WorkbookImport(headers, 1, mapping)
    empty.add(ParsedSheet("空表", emptyList()))
    check(!empty.finish().hasSheetErrors)
    check(runCatching { empty.finish().resolve(emptyMap(), true) }.isFailure)

    // Exact identity keeps years/editions; only presentation whitespace and width normalize.
    val names = WorkbookImport(headers, 1, mapping)
    names.add(sheet("年份", row("2025活动", "1"), row("2026活动", "2"), row("Ａ活动", "1"), row("A活动 ", "1")))
    check(names.finish().records.size == 3 && names.finish().duplicateCount == 1)
    var checks = 0
    check(runCatching { importer.finish { if (++checks > 1) error("cancel") } }.isFailure)

    // Embedded numbers are suggestions, never automatic score assignments.
    check(NumericValues.historyCandidates("单次0.5") == listOf("0.5"))
    check(NumericValues.historyCandidates("成员2，负责人3") == listOf("2", "3"))
    check(NumericValues.historyCandidates("成员２，负责人３，单次０．５") == listOf("2", "3", "0.5"))
    check(NumericValues.historyCandidates("面试讨论").isEmpty())
    check(NumericValues.historyCandidates("1,000 或 0,5").isEmpty())
    check(NumericValues.historyCandidates("负数-2，溢出1e999，下溢1e-999，扣分−3").isEmpty())
    check(NumericValues.historyCandidates("0、-0、0.0、.5、0.5、1.23456789、2e-3") ==
        listOf("0", ".5", "1.23456789", "2e-3"))

    val repairImport = WorkbookImport(headers, 1, mapping)
    repairImport.add(sheet("修正甲", row("单次活动", "单次0.5"), row("身份活动", "成员2，负责人3"),
        row("无数字", "面试讨论"), row("空分", ""), row("零修正", "待定"),
        row("同分合并", "单次1"), row("冲突活动", "成员2，负责人3"),
        row("正常", "4"), row("同分合并", "1"), row("同分合并", "1.0"), row("冲突活动", "2"),
        row("缺分类", "单次2", dimension = ""), row("", "成员2")))
    repairImport.add(sheet("修正乙", row("身份活动", "成员2，负责人3")))
    val repairs = repairImport.finish()
    check(repairs.issues.size == 10 && repairs.records.size == 3)
    fun issue(name: String, sheetName: String = "修正甲") = repairs.issues.single { it.activityName == name && it.sheetName == sheetName }
    check(issue("单次活动").scoreRepair == ScoreRepairContext("劳动", "服务", "参与"))
    check(issue("缺分类").scoreRepair == null && issue("").scoreRepair == null)
    check(runCatching { issue("缺分类").withScore("2") }.isFailure)
    listOf("", "-1", "NaN", "Infinity", "1e999", "1e-999", "0,5").forEach { invalid ->
        check(runCatching { repairs.withScoreOverrides(mapOf(issue("单次活动") to invalid)) }.isFailure)
    }
    check(runCatching { repairs.withScoreOverrides(mapOf(ImportIssue(999, "其他预览") to "2")) }.isFailure)
    val overrides = mapOf(issue("单次活动") to "0.5", issue("身份活动") to "3",
        issue("身份活动", "修正乙") to "2", issue("无数字") to "1.23456789", issue("空分") to "2.5",
        issue("零修正") to "0", issue("同分合并") to "1", issue("冲突活动") to "3")
    val repaired = repairs.withScoreOverrides(overrides)
    check(repaired.issues.size == 2 && repaired.duplicateCount == 2 && repaired.conflicts.size == 2)
    check(repaired.sheets.first().let { it.valid == 11 && it.invalid == 2 })
    check(repaired.sheets.last().let { it.valid == 1 && it.invalid == 0 })
    check(runCatching { repaired.resolve(emptyMap(), true) }.isFailure)
    val decisions = repaired.conflicts.associate { conflict -> conflict.key to conflict.options.indexOfFirst { it.referenceScore == 3.0 } }
    check(runCatching { repaired.resolve(decisions, false) }.isFailure)
    val final = repaired.resolve(decisions, true)
    check(final.records.size == 8 && final.issues.size == 4 && final.mergedDuplicateCount == 2)
    check(final.records.single { it.activityName == "无数字" }.referenceScore == 1.23456789)
    check(final.records.single { it.activityName == "零修正" }.referenceScore == 0.0)
    check(final.records.single { it.activityName == "单次活动" }.sources.single() == HistorySource("修正甲", 2, "单次0.5"))
    check(final.records.single { it.activityName == "同分合并" }.sources.size == 3)
    check(repairs.issues.size == 10) // Undo always starts with the untouched original preview.
    val undone = repairs.withScoreOverrides(overrides - issue("单次活动"))
    check(issue("单次活动") in undone.issues && undone.records.none { it.activityName == "单次活动" })
    val allInvalid = WorkbookImport(headers, 1, mapping).also { it.add(sheet("全异常", row("保留项目", "待定"))) }.finish()
    check(runCatching { allInvalid.resolve(emptyMap(), true) }.isFailure)
    check(allInvalid.withScoreOverrides(mapOf(allInvalid.issues.single() to "0.5")).resolve(emptyMap(), false).records.size == 1)
    println("Workbook import checks passed: multi-sheet, explicit skips, conflicts, provenance, header errors, empty, cancellation, score candidates, manual repair, regroup and undo")
}
