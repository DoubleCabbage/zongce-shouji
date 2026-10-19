package cn.activityscore.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

data class HistoryRow(
    val id: Long = 0,
    val sourceRow: Int,
    val activityName: String,
    val dimension: String,
    val subcategory: String,
    val role: String,
    val referenceScore: Double,
    val sources: List<HistorySource> = emptyList(),
)

data class HistorySource(val sheet: String, val row: Int, val rawScore: String) {
    val label: String get() = "${sheet.ifBlank { "原表" }} · 第 $row 行"
}

val HistoryRow.sourceLabel: String get() = sources.joinToString("；") { it.label }
    .ifBlank { "原表第 $sourceRow 行" }

data class HistoryInfo(val fileName: String, val importedAt: Long, val rowCount: Int)

enum class SheetCellKind { TEXT, NUMBER, OTHER }

data class ParsedSheet(val name: String, val rows: List<List<String>>,
                       val cellKinds: Map<Pair<Int, Int>, SheetCellKind> = emptyMap(),
                       val isCsv: Boolean = false,
                       val sourceLines: List<Int> = emptyList()) {
    fun headers(headerRow: Int): List<String> = rows.getOrNull(headerRow - 1).orEmpty()
    fun cellKind(rowIndex: Int, columnIndex: Int): SheetCellKind =
        cellKinds[rowIndex to columnIndex] ?: SheetCellKind.TEXT
}

data class ColumnMapping(
    val activity: Int = -1,
    val dimension: Int = -1,
    val score: Int = -1,
    val role: Int = -1,
    val subcategory: Int = -1,
) {
    fun isValid(headers: List<String>): Boolean {
        val required = listOf(activity, dimension, score)
        return required.all { it in headers.indices && headers[it].isNotBlank() } &&
            required.distinct().size == required.size &&
            listOf(role, subcategory).filter { it >= 0 }.all { it in headers.indices } &&
            listOf(activity, dimension, score, role, subcategory)
                .filter { it >= 0 }.distinct().size ==
            listOf(activity, dimension, score, role, subcategory).count { it >= 0 }
    }
}

data class ScoreRepairContext(val dimension: String, val subcategory: String, val role: String)

data class ImportIssue(val rowNumber: Int, val reason: String,
                       val sheetName: String = "", val activityName: String = "", val rawScore: String = "",
                       val scoreRepair: ScoreRepairContext? = null) {
    fun withScore(text: String): HistoryRow {
        val fields = requireNotNull(scoreRepair) { "此行不只是分数异常，请修改源文件" }
        val parsed = NumericValues.parse(text, optional = false)
        require(parsed.isValid) { parsed.error.orEmpty() }
        return HistoryRow(sourceRow = rowNumber, activityName = activityName,
            dimension = fields.dimension, subcategory = fields.subcategory, role = fields.role,
            referenceScore = requireNotNull(parsed.value),
            sources = listOf(HistorySource(sheetName, rowNumber, rawScore)))
    }
}
data class ImportPreview(val records: List<HistoryRow>, val issues: List<ImportIssue>)

object HistoryMapper {
    private val whitespace = Regex("\\s+")
    private val leadingIndex = Regex("^\\d+\\s*[-—–、.．]\\s*")
    private val aliases = mapOf(
        "activity" to setOf("附加分名称", "活动名称", "项目名称", "活动", "名称"),
        "dimension" to setOf("指标名称", "活动大类", "所属维度", "大类", "分类"),
        "score" to setOf("参考分数", "加分分数", "分数", "得分"),
        "role" to setOf("获奖等级", "职务", "身份", "参与身份", "等级"),
        "subcategory" to setOf("活动分类", "小类", "二级分类"),
    )

    fun suggest(headers: List<String>): ColumnMapping {
        fun find(field: String): Int = headers.indexOfFirst { value ->
            value.trim() in aliases.getValue(field)
        }
        return ColumnMapping(find("activity"), find("dimension"), find("score"),
            find("role"), find("subcategory"))
    }

    fun preview(sheet: ParsedSheet, headerRow: Int, mapping: ColumnMapping,
                checkCancelled: () -> Unit = {}): ImportPreview {
        val headers = sheet.headers(headerRow)
        require(mapping.isValid(headers)) { "请先将活动名称、所属维度和参考分数对应到不同的列" }
        val records = mutableListOf<HistoryRow>()
        val issues = mutableListOf<ImportIssue>()
        for (index in headerRow until sheet.rows.size) {
            checkCancelled()
            val row = sheet.rows[index]
            if (row.all { it.isBlank() }) continue
            val sourceLine = sheet.sourceLines.getOrNull(index) ?: index + 1
            if (sheet.isCsv && row.size > headers.size) {
                issues += ImportIssue(sourceLine, "CSV 列数超过表头，可能含未加引号的逗号数字；请检查源行",
                    sheet.name, row.getOrNull(mapping.activity).orEmpty(), row.getOrNull(mapping.score).orEmpty())
                continue
            }
            fun cell(column: Int): String = clean(row.getOrNull(column).orEmpty())
            val name = cell(mapping.activity).replace(leadingIndex, "")
            val dimension = cell(mapping.dimension)
            // Never normalize punctuation or remove separators from a score.
            val rawScore = row.getOrNull(mapping.score).orEmpty()
            val parsedScore = NumericValues.parseHistory(rawScore, sheet.cellKind(index, mapping.score))
            val score = parsedScore.value
            val problem = when {
                name.isBlank() -> "活动名称为空"
                dimension.isBlank() -> "所属维度为空"
                !parsedScore.isValid -> "参考分数“${rawScore.ifBlank { "空白" }}”：${parsedScore.error}"
                else -> null
            }
            if (problem != null) {
                issues += ImportIssue(sourceLine, problem, sheet.name, name, rawScore,
                    if (name.isNotBlank() && dimension.isNotBlank() && !parsedScore.isValid)
                        ScoreRepairContext(dimension, cell(mapping.subcategory), cell(mapping.role)) else null)
            } else {
                records += HistoryRow(
                    sourceRow = sourceLine,
                    activityName = name,
                    dimension = dimension,
                    subcategory = cell(mapping.subcategory),
                    role = cell(mapping.role),
                    referenceScore = score!!,
                    sources = listOf(HistorySource(sheet.name, sourceLine, rawScore)),
                )
            }
        }
        return ImportPreview(records, issues)
    }

    private fun clean(value: String): String =
        Normalizer.normalize(value.replace('\u3000', ' '), Normalizer.Form.NFKC)
            .replace(whitespace, " ").trim()
}

class MappingProfiles(context: Context) {
    private val preferences = context.getSharedPreferences("history_mapping_profiles", 0)

    fun find(headers: List<String>): ColumnMapping? {
        synchronized(DataLock.monitor) {
            val profiles = readProfiles()
            for (index in profiles.length() - 1 downTo 0) {
                val profile = profiles.optJSONObject(index) ?: continue
                val saved = profile.optJSONObject("columns") ?: continue
                fun resolve(key: String): Int {
                    val header = saved.optString(key)
                    return if (header.isBlank()) -1 else headers.indexOf(header)
                }
                val mapping = ColumnMapping(resolve("activity"), resolve("dimension"),
                    resolve("score"), resolve("role"), resolve("subcategory"))
                if (mapping.isValid(headers)) return mapping
            }
            return null
        }
    }

    fun save(headers: List<String>, mapping: ColumnMapping) {
        synchronized(DataLock.monitor) {
            require(mapping.isValid(headers))
            val columns = JSONObject().apply {
                put("activity", headers[mapping.activity])
                put("dimension", headers[mapping.dimension])
                put("score", headers[mapping.score])
                put("role", headers.getOrNull(mapping.role).orEmpty())
                put("subcategory", headers.getOrNull(mapping.subcategory).orEmpty())
            }
            val profiles = readProfiles()
            profiles.put(JSONObject().put("columns", columns))
            val recent = JSONArray()
            for (index in maxOf(0, profiles.length() - 19) until profiles.length()) {
                recent.put(profiles.get(index))
            }
            preferences.edit().putString("profiles", recent.toString()).apply()
        }
    }

    private fun readProfiles(): JSONArray = try {
        JSONArray(preferences.getString("profiles", "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
}

fun copyImportFile(context: Context, uri: Uri, displayName: String): File {
    val extension = when {
        displayName.endsWith(".xlsx", ignoreCase = true) -> ".xlsx"
        displayName.endsWith(".csv", ignoreCase = true) -> ".csv"
        else -> throw IllegalArgumentException("目前只支持 .xlsx 和 .csv 文件")
    }
    val file = File.createTempFile("history-", extension, context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取选中的文件" }
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 20L * 1024 * 1024) { "文件超过 20 MB，请先精简表格" }
                    output.write(buffer, 0, count)
                }
            }
        }
        return file
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}

fun readCsvText(file: File): String {
    val bytes = file.readBytes()
    val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: Exception) {
        String(bytes, Charset.forName("GB18030")).removePrefix("\uFEFF")
    }
}
