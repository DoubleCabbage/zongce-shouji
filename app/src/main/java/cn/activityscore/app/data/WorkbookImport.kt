package cn.activityscore.app.data

import java.text.Normalizer

val ImportPreview.mergedDuplicateCount: Int get() = records.sumOf { (it.sources.size - 1).coerceAtLeast(0) }

data class ReferenceKey(val dimension: String, val subcategory: String, val activity: String, val role: String)
data class ReferenceConflict(val key: ReferenceKey, val options: List<HistoryRow>)
data class SheetImportSummary(val name: String, val valid: Int, val invalid: Int, val error: String = "")

data class WorkbookPreview(
    val records: List<HistoryRow>,
    val issues: List<ImportIssue>,
    val conflicts: List<ReferenceConflict>,
    val sheets: List<SheetImportSummary>,
    val duplicateCount: Int,
) {
    val hasSheetErrors: Boolean get() = sheets.any { it.error.isNotBlank() }

    /** Rebuild groups after explicit repairs; a repaired row may merge or introduce a new conflict. */
    fun withScoreOverrides(overrides: Map<ImportIssue, String>): WorkbookPreview {
        if (overrides.isEmpty()) return this
        val currentIssues = issues.toHashSet()
        require(overrides.keys.all { it in currentIssues && it.scoreRepair != null }) { "分数修正不属于当前预览" }
        val repaired = overrides.map { (issue, text) -> issue.withScore(text) }
        val counts = overrides.keys.groupingBy { it.sheetName }.eachCount()
        return groupWorkbookRows(records + conflicts.flatMap { it.options } + repaired,
            issues.filter { it !in overrides }, sheets.map { sheet ->
                val count = counts[sheet.name] ?: 0
                sheet.copy(valid = sheet.valid + count, invalid = sheet.invalid - count)
            })
    }

    // -1 explicitly excludes a whole conflict group. An absent choice is never a default.
    fun resolve(choices: Map<ReferenceKey, Int>, skipIssues: Boolean): ImportPreview {
        require(!hasSheetErrors) { "请修正或取消选择格式不一致的工作表" }
        require(issues.isEmpty() || skipIssues) { "请明确确认跳过异常行" }
        val resolved = records.toMutableList()
        val skipped = issues.toMutableList()
        conflicts.forEach { conflict ->
            val choice = choices[conflict.key]
            require(choice != null && choice in -1 until conflict.options.size) { "还有分数冲突未处理" }
            conflict.options.forEachIndexed { index, row ->
                if (index == choice) resolved += row
                else row.sources.ifEmpty { listOf(HistorySource("", row.sourceRow, row.referenceScore.toString())) }
                    .forEach { source -> skipped += ImportIssue(source.row, "分数冲突：用户未选用此分数",
                        source.sheet, row.activityName, source.rawScore) }
            }
        }
        require(resolved.isNotEmpty()) { "没有可导入的有效记录；现有参考库保留" }
        return ImportPreview(resolved, skipped)
    }
}

/** Common mappings are resolved by header names, never blindly applied to column positions. */
class WorkbookImport(private val templateHeaders: List<String>, private val headerRow: Int,
                     private val mapping: ColumnMapping) {
    private val records = mutableListOf<HistoryRow>()
    private val issues = mutableListOf<ImportIssue>()
    private val summaries = mutableListOf<SheetImportSummary>()
    private var totalRows = 0

    init { require(headerRow > 0 && mapping.isValid(templateHeaders)) { "请完成列映射" } }

    fun add(sheet: ParsedSheet, checkCancelled: () -> Unit = {}) {
        totalRows += sheet.rows.size
        require(totalRows <= 100_000) { "选中工作表合计超过 100000 行，请减少选择" }
        if (sheet.rows.all { row -> row.all { it.isBlank() } }) {
            summaries += SheetImportSummary(sheet.name, 0, 0)
            return
        }
        val headers = sheet.headers(headerRow)
        fun resolve(column: Int): Int {
            if (column < 0) return -1
            val name = normalized(templateHeaders[column])
            val matches = headers.indices.filter { normalized(headers[it]) == name }
            require(matches.size == 1) { "第 $headerRow 行缺少或重复列名“${templateHeaders[column]}”" }
            return matches.single()
        }
        val target = try {
            ColumnMapping(resolve(mapping.activity), resolve(mapping.dimension), resolve(mapping.score),
                resolve(mapping.role), resolve(mapping.subcategory))
        } catch (problem: IllegalArgumentException) {
            summaries += SheetImportSummary(sheet.name, 0, 0,
                "${problem.message}；请修改源表、调整共用映射或取消选择此表")
            return
        }
        val preview = HistoryMapper.preview(sheet, headerRow, target, checkCancelled)
        records += preview.records
        issues += preview.issues
        summaries += SheetImportSummary(sheet.name, preview.records.size, preview.issues.size)
    }

    fun finish(checkCancelled: () -> Unit = {}): WorkbookPreview {
        return groupWorkbookRows(records, issues.toList(), summaries.toList(), checkCancelled)
    }

    private fun normalized(value: String) = normalizedReference(value)
}

private fun normalizedReference(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("\\s+"), " ").trim()

private fun groupWorkbookRows(records: List<HistoryRow>, issues: List<ImportIssue>,
                              summaries: List<SheetImportSummary>, checkCancelled: () -> Unit = {}): WorkbookPreview {
        val groups = linkedMapOf<ReferenceKey, MutableList<HistoryRow>>()
        records.forEach { row ->
            checkCancelled()
            val key = ReferenceKey(normalizedReference(row.dimension), normalizedReference(row.subcategory),
                normalizedReference(row.activityName), normalizedReference(row.role))
            groups.getOrPut(key) { mutableListOf() } += row
        }
        var duplicates = 0
        val ordinary = mutableListOf<HistoryRow>()
        val conflicts = mutableListOf<ReferenceConflict>()
        groups.forEach { (key, rows) ->
            checkCancelled()
            // Treat numeric zero identically regardless of its sign; preserve all other precision.
            val options = rows.groupBy { if (it.referenceScore == 0.0) 0.0 else it.referenceScore }.values.map { sameScore ->
                duplicates += sameScore.sumOf { it.sources.size.coerceAtLeast(1) } - 1
                sameScore.first().copy(sources = sameScore.flatMap { it.sources }.distinct())
            }
            if (options.size == 1) ordinary += options.single()
            else conflicts += ReferenceConflict(key, options)
        }
        return WorkbookPreview(ordinary, issues, conflicts, summaries, duplicates)
}
