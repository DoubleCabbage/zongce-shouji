package cn.activityscore.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Reads plain rows only. Formatting, macros and formulas are never executed. */
data class LocalSheetInfo(val name: String, val hidden: Boolean)

object LocalSheetReader {
    private const val MAX_ROWS = 20_000
    private const val MAX_COLUMNS = 120
    private const val MAX_XML_BYTES = 32L * 1024 * 1024
    private const val REL_NS =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun sheetNames(file: File): List<String> = sheetInfo(file).map { it.name }

    fun sheetInfo(file: File): List<LocalSheetInfo> {
        if (file.extension.equals("csv", ignoreCase = true)) return listOf(LocalSheetInfo("CSV", false))
        require(file.extension.equals("xlsx", ignoreCase = true)) { "目前只支持 .xlsx 和 .csv" }
        ZipFile(file).use { zip ->
            val hidden = mutableSetOf<String>()
            val workbook = parser(zip, "xl/workbook.xml") { Xml.newPullParser() }
            while (workbook.next() != XmlPullParser.END_DOCUMENT) {
                if (workbook.eventType == XmlPullParser.START_TAG && workbook.name == "sheet" &&
                    workbook.getAttributeValue(null, "state") in listOf("hidden", "veryHidden"))
                    hidden += workbook.getAttributeValue(null, "name").orEmpty()
            }
            return workbookSheets(zip).map { LocalSheetInfo(it.first, it.first in hidden) }
        }
    }

    /** One ZIP/shared-string read per batch; consumes one sheet at a time. */
    fun readSelected(file: File, names: List<String>, checkCancelled: () -> Unit = {},
                     consume: (ParsedSheet) -> Unit) {
        require(names.isNotEmpty() && names.distinct().size == names.size)
        if (file.extension.equals("csv", ignoreCase = true)) {
            require(names == listOf("CSV"))
            checkCancelled()
            consume(parseCsv(readCsvText(file), checkCancelled))
            return
        }
        ZipFile(file).use { zip ->
            val newParser = { Xml.newPullParser() }
            val sheets = workbookSheets(zip, newParser).toMap()
            val shared = readSharedStrings(zip, newParser, checkCancelled)
            names.forEach { name ->
                checkCancelled()
                val path = sheets[name] ?: error("找不到工作表：$name")
                consume(readXlsx(zip, name, path, shared, newParser, checkCancelled))
            }
        }
    }

    fun read(file: File, sheetName: String): ParsedSheet = read(file, sheetName) { Xml.newPullParser() }

    internal fun read(file: File, sheetName: String, newParser: () -> XmlPullParser): ParsedSheet {
        if (file.extension.equals("csv", ignoreCase = true)) {
            require(sheetName == "CSV")
            return parseCsv(readCsvText(file))
        }
        ZipFile(file).use { zip ->
            val sheet = workbookSheets(zip, newParser).firstOrNull { it.first == sheetName }
                ?: throw IllegalArgumentException("找不到工作表：$sheetName")
            val shared = readSharedStrings(zip, newParser)
            return readXlsx(zip, sheetName, sheet.second, shared, newParser)
        }
    }

    private fun readXlsx(zip: ZipFile, sheetName: String, path: String, shared: List<String>,
                         newParser: () -> XmlPullParser, checkCancelled: () -> Unit = {}): ParsedSheet {
        val rows = mutableListOf<List<String>>()
        val kinds = mutableMapOf<Pair<Int, Int>, SheetCellKind>()
        val parser = parser(zip, path, newParser)
        var rowNumber = 0
        var currentRow = mutableMapOf<Int, String>()
        var cellColumn = -1
        var cellType = ""
        var value = StringBuilder()
        var inlineText = StringBuilder()
        var inValue = false
        var inInline = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            checkCancelled()
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        rowNumber = parser.getAttributeValue(null, "r")?.toIntOrNull()
                            ?: rows.size + 1
                        require(rowNumber in 1..MAX_ROWS) { "工作表超过 $MAX_ROWS 行" }
                        while (rows.size < rowNumber - 1) rows += emptyList<String>()
                        currentRow = mutableMapOf()
                    }
                    "c" -> {
                        cellColumn = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        require(cellColumn < MAX_COLUMNS) { "工作表超过 $MAX_COLUMNS 列" }
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        value = StringBuilder()
                        inlineText = StringBuilder()
                    }
                    "v" -> inValue = true
                    "is" -> inInline = true
                }
                XmlPullParser.TEXT -> {
                    if (inValue) value.append(parser.text)
                    if (inInline && !inValue) inlineText.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "is" -> inInline = false
                    "c" -> {
                        if (cellColumn in 0 until MAX_COLUMNS) {
                            val text = when (cellType) {
                                "s" -> shared.getOrNull(value.toString().toIntOrNull() ?: -1).orEmpty()
                                "inlineStr" -> inlineText.toString()
                                "b" -> if (value.toString() == "1") "TRUE" else "FALSE"
                                else -> value.toString()
                            }
                            if (text.isNotBlank()) currentRow[cellColumn] = text
                            kinds[(rowNumber - 1) to cellColumn] = when (cellType) {
                                "", "n" -> SheetCellKind.NUMBER
                                "s", "inlineStr", "str" -> SheetCellKind.TEXT
                                else -> SheetCellKind.OTHER
                            }
                        }
                        cellColumn = -1
                    }
                    "row" -> {
                        val width = (currentRow.keys.maxOrNull() ?: -1) + 1
                        rows += List(width) { currentRow[it].orEmpty() }
                    }
                }
            }
        }
        return ParsedSheet(sheetName, rows, kinds)
    }

    private fun workbookSheets(zip: ZipFile, newParser: () -> XmlPullParser = { Xml.newPullParser() }): List<Pair<String, String>> {
        val relations = mutableMapOf<String, String>()
        val relParser = parser(zip, "xl/_rels/workbook.xml.rels", newParser)
        while (relParser.next() != XmlPullParser.END_DOCUMENT) {
            if (relParser.eventType == XmlPullParser.START_TAG && relParser.name == "Relationship") {
                val id = relParser.getAttributeValue(null, "Id").orEmpty()
                val target = relParser.getAttributeValue(null, "Target").orEmpty()
                val type = relParser.getAttributeValue(null, "Type").orEmpty()
                if (id.isNotBlank() && target.isNotBlank() &&
                    type.endsWith("/worksheet") && !target.contains("://")) {
                    val path = if (target.startsWith("/")) target.removePrefix("/")
                    else "xl/$target"
                    val normalized = java.net.URI(null, null, "/$path", null).normalize().path
                        .removePrefix("/")
                    if (normalized.startsWith("xl/")) relations[id] = normalized
                }
            }
        }
        val sheets = mutableListOf<Pair<String, String>>()
        val workbook = parser(zip, "xl/workbook.xml", newParser)
        while (workbook.next() != XmlPullParser.END_DOCUMENT) {
            if (workbook.eventType == XmlPullParser.START_TAG && workbook.name == "sheet") {
                val name = workbook.getAttributeValue(null, "name").orEmpty()
                val id = workbook.getAttributeValue(REL_NS, "id")
                    ?: (0 until workbook.attributeCount).firstOrNull {
                        workbook.getAttributeName(it) == "id"
                    }?.let { workbook.getAttributeValue(it) }
                val path = relations[id]
                if (name.isNotBlank() && path != null) sheets += name to path
            }
        }
        require(sheets.isNotEmpty()) { "文件中没有可读取的工作表" }
        return sheets
    }

    private fun readSharedStrings(zip: ZipFile, newParser: () -> XmlPullParser,
                                  checkCancelled: () -> Unit = {}): List<String> {
        if (zip.getEntry("xl/sharedStrings.xml") == null) return emptyList()
        val result = mutableListOf<String>()
        val parser = parser(zip, "xl/sharedStrings.xml", newParser)
        var inItem = false
        var inText = false
        var value = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            checkCancelled()
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inItem = true; value = StringBuilder() }
                    "t" -> if (inItem) inText = true
                }
                XmlPullParser.TEXT -> if (inText) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> { result += value.toString(); inItem = false }
                }
            }
        }
        return result
    }

    private fun parser(zip: ZipFile, path: String, newParser: () -> XmlPullParser): XmlPullParser {
        val entry = zip.getEntry(path) ?: throw IllegalArgumentException("Excel 文件缺少 $path")
        require(entry.size <= MAX_XML_BYTES || entry.size < 0) { "表格内容过大" }
        val bytes = zip.getInputStream(entry).use { readBounded(it) }
        return newParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(bytes.inputStream(), "UTF-8")
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_XML_BYTES) { "表格内容过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun columnIndex(reference: String): Int {
        var index = 0
        for (char in reference) {
            if (!char.isLetter()) break
            index = index * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return index - 1
    }

    private fun parseCsv(text: String, checkCancelled: () -> Unit = {}): ParsedSheet {
        val rows = mutableListOf<List<String>>()
        val sourceLines = mutableListOf<Int>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var afterQuote = false
        var line = 1
        var rowStart = 1
        var index = 0
        while (index < text.length) {
            if (index % 1024 == 0) checkCancelled()
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"'); index++
                }
                char == '"' && quoted -> { quoted = false; afterQuote = true }
                char == '"' -> {
                    require(cell.isEmpty() && !afterQuote) { "CSV 第 $line 行引号位置无效" }
                    quoted = true
                }
                char == ',' && !quoted -> {
                    row += cell.toString(); cell.clear()
                    afterQuote = false
                    require(row.size <= MAX_COLUMNS) { "CSV 超过 $MAX_COLUMNS 列" }
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString(); cell.clear()
                    require(row.size <= MAX_COLUMNS) { "CSV 超过 $MAX_COLUMNS 列" }
                    rows += row.toList(); row.clear()
                    sourceLines += rowStart
                    line++; rowStart = line; afterQuote = false
                    require(rows.size <= MAX_ROWS) { "CSV 超过 $MAX_ROWS 行" }
                }
                else -> {
                    require(!afterQuote) { "CSV 第 $line 行引号后有多余字符" }
                    cell.append(char)
                    if (char == '\n' || char == '\r' && (index + 1 >= text.length || text[index + 1] != '\n')) line++
                }
            }
            index++
        }
        require(!quoted) { "CSV 引号没有闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty() || afterQuote) {
            row += cell.toString()
            require(row.size <= MAX_COLUMNS) { "CSV 超过 $MAX_COLUMNS 列" }
            rows += row.toList()
            sourceLines += rowStart
            require(rows.size <= MAX_ROWS) { "CSV 超过 $MAX_ROWS 行" }
        }
        return ParsedSheet("CSV", rows, isCsv = true, sourceLines = sourceLines)
    }
}
