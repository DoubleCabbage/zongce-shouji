package cn.activityscore.app.tests

import cn.activityscore.app.data.ColumnMapping
import cn.activityscore.app.data.HistoryMapper
import cn.activityscore.app.data.HistoryRow
import cn.activityscore.app.data.LocalSheetReader
import cn.activityscore.app.data.ParsedSheet
import cn.activityscore.app.domain.HistoricalMatcher
import java.io.File

/** Standalone JVM smoke checks for data rules; Android UI is compiled in Android Studio. */
fun main() {
    val headers = listOf("活动名称", "指标名称", "参考分数", "获奖等级")
    val mapping = HistoryMapper.suggest(headers)
    check(mapping == ColumnMapping(activity = 0, dimension = 1, score = 2, role = 3))
    val preview = HistoryMapper.preview(
        ParsedSheet("样例", listOf(
            headers,
            listOf("喜迎2026合唱晚会", "美育", "2", "演员"),
            listOf("喜迎2026合唱晚会", "美育", "0", "观众"),
            listOf("缺分活动", "美育", "", "演员"),
        )), 1, mapping,
    )
    check(preview.records.size == 2)
    check(preview.records[1].referenceScore == 0.0)
    check(preview.issues.single().rowNumber == 4)
    val changedHeaders = listOf("加分", "项目", "大类")
    val changedMapping = ColumnMapping(activity = 1, dimension = 2, score = 0)
    check(changedMapping.isValid(changedHeaders))
    check(!changedMapping.copy(score = 1).isValid(changedHeaders))
    val changed = HistoryMapper.preview(
        ParsedSheet("改版", listOf(changedHeaders, listOf("1.5", "志愿服务", "劳动"))),
        1, changedMapping,
    )
    check(changed.records.single().activityName == "志愿服务")
    check(changed.records.single().referenceScore == 1.5)

    val ordinary = HistoricalMatcher.buildTree(preview.records)
    check(ordinary.size == 1 && ordinary.single().roles.size == 2)
    val results = HistoricalMatcher.search(
        "2027开年合唱", "美育", ordinary +
            HistoricalMatcher.buildTree(listOf(HistoryRow(
                sourceRow = 8, activityName = "电子设计竞赛", dimension = "学术创新",
                subcategory = "", role = "参赛者", referenceScore = 3.0,
            )))
    )
    check(results.first().activity.activityName == "喜迎2026合唱晚会")

    val conflicts = HistoricalMatcher.buildTree(preview.records +
        preview.records.first().copy(sourceRow = 5, referenceScore = 3.0))
    check(conflicts.size == 3 && conflicts.all { it.needsReview })

    val csv = File.createTempFile("history-core-check-", ".csv")
    try {
        csv.writeText("活动名称,指标名称,参考分数\r\n\"合唱,晚会\",美育,2\r\n")
        val parsed = LocalSheetReader.read(csv, "CSV")
        check(parsed.rows[1][0] == "合唱,晚会")
        check(parsed.rows.size == 2)
    } finally {
        csv.delete()
    }
    println("History core checks passed")
}
