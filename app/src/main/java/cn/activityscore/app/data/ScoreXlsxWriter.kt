package cn.activityscore.app.data

import cn.activityscore.app.domain.AnnualActivityScores
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The exported image is a cell value (Excel's Place in Cell), not a floating drawing. */
internal data class ExportRecord(
    val dimension: String,
    val title: String,
    val awardLevel: String,
    val score: Double?,
    val photos: List<ExportPhoto>,
    val itemId: String = "",
    val participations: List<ExportParticipation> = emptyList(),
    val countingLabel: String = "",
    val subcategory: String = "",
    val note: String = "",
    val scoreStatus: String = "",
    val entryId: String = "",
    val exportMode: String = "",
    val photoOwners: String = "",
)

internal data class ExportPhoto(val file: File, val extension: String, val needsConversion: Boolean = false)

internal data class ExportParticipation(
    val id: String,
    val date: String,
    val note: String,
    val confirmedScore: Double?,
    val photos: List<ExportPhoto>,
    val scoreStatus: String = "",
)

internal object ScoreXlsxWriter {
    private const val sheetNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val packageRelNs = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val richNs = "http://schemas.microsoft.com/office/spreadsheetml/2017/richdata"
    private val sheetNames = listOf("总表") + scoreDimensions

    private data class PhotoCell(val photo: ExportPhoto, val index: Int)
    private data class NumberedRecord(val number: Int, val record: ExportRecord, val photos: List<PhotoCell>)

    fun write(
        records: List<ExportRecord>,
        output: OutputStream,
        writeImage: (ExportPhoto, OutputStream) -> Unit,
    ) = write(records, output, null, writeImage = writeImage)

    fun write(
        records: List<ExportRecord>,
        output: OutputStream,
        annualScores: AnnualActivityScores?,
        assessment: AnnualExportAssessment? = annualScores?.let { AnnualExportAssessment.from(it) },
        writeImage: (ExportPhoto, OutputStream) -> Unit,
    ) {
        assessment?.requireReady()
        require(records.size.toLong() + (assessment?.warnings?.size ?: 0) + 11 <= 1_048_576) { "申报记录和提示超过 Excel 行数限制" }
        require(records.all { it.dimension in scoreDimensions }) { "记录包含未知领域，无法导出" }
        require(records.all { it.score == null || NumericValues.accepts(it.score) }) { "记录包含无效分数，无法导出" }
        require(records.flatMap { it.participations }.all { it.confirmedScore == null || NumericValues.accepts(it.confirmedScore) }) {
            "参与包含无效确认分，无法导出"
        }
        require(records.flatMap { it.photos }.all { it.extension == "jpg" || it.extension == "png" }) {
            "凭证图片格式无效"
        }
        require(records.all { it.participations.isEmpty() || it.photos == it.participations.flatMap { visit -> visit.photos } }) {
            "参与记录与凭证归属不一致"
        }
        require(records.all { it.photos.size + 14 <= 16384 }) { "申报行照片及说明超过 Excel 16384 列限制；请按参与拆分" }
        var photoCount = 0
        val groups = scoreDimensions.associateWith { dimension ->
            records.filter { it.dimension == dimension }.mapIndexed { index, record ->
                NumberedRecord(index + 1, record, record.photos.map { PhotoCell(it, photoCount++) })
            }
        }
        require(groups.values.flatten().all { it.photos.size < 16384 }) { "单条记录的照片数超过 Excel 列数上限" }
        val allPhotos = groups.values.flatten().flatMap { it.photos }

        ZipOutputStream(output).use { zip ->
            zip.xml("[Content_Types].xml", contentTypes(allPhotos))
            zip.xml("_rels/.rels", rootRelationships())
            zip.xml("xl/workbook.xml", workbook())
            zip.xml("xl/_rels/workbook.xml.rels", workbookRelationships(allPhotos.isNotEmpty()))
            zip.xml("xl/styles.xml", styles())
            zip.xml("xl/worksheets/sheet1.xml", summarySheet(groups, annualScores, assessment))
            scoreDimensions.forEachIndexed { index, dimension ->
                zip.xml("xl/worksheets/sheet${index + 2}.xml", detailSheet(groups.getValue(dimension)))
            }
            if (allPhotos.isNotEmpty()) {
                zip.xml("xl/metadata.xml", metadata(allPhotos.size))
                zip.xml("xl/richData/rdrichvaluestructure.xml", richValueStructure())
                zip.xml("xl/richData/rdrichvalue.xml", richValues(allPhotos.size))
                zip.xml("xl/richData/rdRichValueTypes.xml", richValueTypes())
                zip.xml("xl/richData/richValueRel.xml", richValueRelationships(allPhotos.size))
                zip.xml("xl/richData/_rels/richValueRel.xml.rels", imageRelationships(allPhotos))
                allPhotos.forEach { photo ->
                    zip.putNextEntry(ZipEntry("xl/media/image${photo.index + 1}.${photo.photo.extension}"))
                    writeImage(photo.photo, zip)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun contentTypes(photos: List<PhotoCell>) = buildString {
        val hasPhotos = photos.isNotEmpty()
        append(xmlStart()).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        if (photos.any { it.photo.extension == "jpg" }) append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
        if (photos.any { it.photo.extension == "png" }) append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        (1..7).forEach { append("<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>") }
        if (hasPhotos) {
            append("<Override PartName=\"/xl/metadata.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheetMetadata+xml\"/>")
            append("<Override PartName=\"/xl/richData/rdrichvalue.xml\" ContentType=\"application/vnd.ms-excel.rdrichvalue+xml\"/>")
            append("<Override PartName=\"/xl/richData/rdrichvaluestructure.xml\" ContentType=\"application/vnd.ms-excel.rdrichvaluestructure+xml\"/>")
            append("<Override PartName=\"/xl/richData/rdRichValueTypes.xml\" ContentType=\"application/vnd.ms-excel.rdrichvaluetypes+xml\"/>")
            append("<Override PartName=\"/xl/richData/richValueRel.xml\" ContentType=\"application/vnd.ms-excel.richvaluerel+xml\"/>")
        }
        append("</Types>")
    }

    private fun rootRelationships() = xmlStart() +
        "<Relationships xmlns=\"$packageRelNs\"><Relationship Id=\"rId1\" " +
        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
        "Target=\"xl/workbook.xml\"/></Relationships>"

    private fun workbook() = buildString {
        append(xmlStart()).append("<workbook xmlns=\"$sheetNs\" xmlns:r=\"$relNs\"><sheets>")
        sheetNames.forEachIndexed { index, name ->
            append("<sheet name=\"").append(escape(name)).append("\" sheetId=\"")
                .append(index + 1).append("\" r:id=\"rId").append(index + 1).append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRelationships(hasPhotos: Boolean) = buildString {
        append(xmlStart()).append("<Relationships xmlns=\"$packageRelNs\">")
        (1..7).forEach {
            append("<Relationship Id=\"rId$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$it.xml\"/>")
        }
        append("<Relationship Id=\"rId8\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        if (hasPhotos) {
            append("<Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sheetMetadata\" Target=\"metadata.xml\"/>")
            append("<Relationship Id=\"rId10\" Type=\"http://schemas.microsoft.com/office/2022/10/relationships/richValueRel\" Target=\"richData/richValueRel.xml\"/>")
            append("<Relationship Id=\"rId11\" Type=\"http://schemas.microsoft.com/office/2017/06/relationships/rdRichValue\" Target=\"richData/rdrichvalue.xml\"/>")
            append("<Relationship Id=\"rId12\" Type=\"http://schemas.microsoft.com/office/2017/06/relationships/rdRichValueStructure\" Target=\"richData/rdrichvaluestructure.xml\"/>")
            append("<Relationship Id=\"rId13\" Type=\"http://schemas.microsoft.com/office/2017/06/relationships/rdRichValueTypes\" Target=\"richData/rdRichValueTypes.xml\"/>")
        }
        append("</Relationships>")
    }

    private fun styles() = xmlStart() + """
        <styleSheet xmlns="$sheetNs">
          <fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Arial"/></font></fonts>
          <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF234B63"/><bgColor indexed="64"/></patternFill></fill></fills>
          <borders count="5">
            <border><left/><right/><top/><bottom/><diagonal/></border>
            <border><left style="thin"><color rgb="FFB6C5D2"/></left><right style="thin"><color rgb="FFB6C5D2"/></right><top style="thin"><color rgb="FFB6C5D2"/></top><bottom style="thin"><color rgb="FFB6C5D2"/></bottom><diagonal/></border>
            <border><left style="thin"><color rgb="FFB6C5D2"/></left><right style="thin"><color rgb="FFB6C5D2"/></right><top style="thin"><color rgb="FFB6C5D2"/></top><bottom/><diagonal/></border>
            <border><left style="thin"><color rgb="FFB6C5D2"/></left><right style="thin"><color rgb="FFB6C5D2"/></right><top/><bottom/><diagonal/></border>
            <border><left style="thin"><color rgb="FFB6C5D2"/></left><right style="thin"><color rgb="FFB6C5D2"/></right><top/><bottom style="thin"><color rgb="FFB6C5D2"/></bottom><diagonal/></border>
          </borders>
          <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
          <cellXfs count="6">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
            <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"><alignment horizontal="center" vertical="center"/></xf>
            <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"><alignment vertical="center"/></xf>
            <xf numFmtId="0" fontId="0" fillId="0" borderId="2" xfId="0" applyBorder="1"><alignment vertical="center"/></xf>
            <xf numFmtId="0" fontId="0" fillId="0" borderId="3" xfId="0" applyBorder="1"><alignment vertical="center"/></xf>
            <xf numFmtId="0" fontId="0" fillId="0" borderId="4" xfId="0" applyBorder="1"><alignment vertical="center"/></xf>
          </cellXfs>
          <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
        </styleSheet>
    """.trimIndent()

    private fun summarySheet(groups: Map<String, List<NumberedRecord>>, annual: AnnualActivityScores?, assessment: AnnualExportAssessment?) = buildString {
        append(sheetPrefix())
        append("<cols><col min=\"1\" max=\"1\" width=\"19\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"10\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"40\" customWidth=\"1\"/><col min=\"4\" max=\"4\" width=\"22\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"20\" customWidth=\"1\"/><col min=\"6\" max=\"6\" hidden=\"1\"/>")
        if (annual != null) append("<col min=\"7\" max=\"12\" width=\"30\" customWidth=\"1\"/><col min=\"13\" max=\"13\" hidden=\"1\"/><col min=\"14\" max=\"14\" width=\"20\" customWidth=\"1\"/><col min=\"15\" max=\"15\" hidden=\"1\"/>")
        append("</cols>")
        append("<sheetData><row r=\"1\" ht=\"27\" customHeight=\"1\">")
        val headers = listOf("领域", "序号", "活动名", "获奖等级",
            if (annual == null) "分数" else "认可分（封顶前）", "条目ID") +
            if (annual == null) emptyList() else listOf("学年", "计分方式", "小分类", "条目备注", "计分状态", "参与次数", "申报行ID", "导出方式", "参与ID集合")
        headers.forEachIndexed { index, title ->
            append(textCell("${column(index + 1)}1", title, true))
        }
        append("</row>")
        var row = 2
        val merges = mutableListOf<String>()
        scoreDimensions.forEach { dimension ->
            val group = groups.getValue(dimension)
            val start = row
            group.forEach { numbered ->
                append("<row r=\"$row\" ht=\"24\" customHeight=\"1\">")
                val dimensionStyle = when {
                    group.size == 1 -> 2
                    row == start -> 3
                    row == start + group.lastIndex -> 5
                    else -> 4
                }
                if (row == start) append(textCell("A$row", dimension, style = dimensionStyle))
                else append(blankCell("A$row", dimensionStyle))
                append(numberCell("B$row", numbered.number.toDouble()))
                append(textCell("C$row", numbered.record.title))
                append(textCell("D$row", numbered.record.awardLevel))
                numbered.record.score?.let { append(numberCell("E$row", it)) }
                    ?: append(blankCell("E$row"))
                append(textCell("F$row", numbered.record.itemId))
                if (annual != null) {
                    append(textCell("G$row", "${annual.academicYear}—${annual.academicYear + 1}"))
                    append(textCell("H$row", numbered.record.countingLabel))
                    append(textCell("I$row", numbered.record.subcategory))
                    append(textCell("J$row", numbered.record.note))
                    append(textCell("K$row", numbered.record.scoreStatus))
                    append(numberCell("L$row", numbered.record.participations.size.toDouble()))
                    append(textCell("M$row", numbered.record.entryId))
                    append(textCell("N$row", numbered.record.exportMode))
                    append(textCell("O$row", numbered.record.participations.joinToString(",") { it.id }))
                }
                append("</row>")
                row++
            }
            if (group.size > 1) merges += "A$start:A${row - 1}"
        }
        if (annual != null) {
            row++
            append("<row r=\"$row\">").append(textCell("A$row", "${annual.academicYear}—${annual.academicYear + 1} 学年活动统计")).append("</row>")
            row++
            append("<row r=\"$row\">")
            listOf("领域", "已确定部分小计（封顶前）", "维度上限（全维度）", "最终封顶活动分（完整时）", "计分状态").forEachIndexed { index, label ->
                append(textCell("${column(index + 1)}$row", label, true))
            }
            append("</row>")
            scoreDimensions.forEach { dimension ->
                row++
                append("<row r=\"$row\">")
                append(textCell("A$row", dimension))
                val state = requireNotNull(assessment).dimensions.getValue(dimension)
                append(numberCell("B$row", state.knownSubtotal))
                append(numberCell("C$row", scoreDimensionCaps.getValue(dimension).toDouble()))
                if (state.complete) append(numberCell("D$row", annual.cappedByDimension.getValue(dimension)))
                else append(blankCell("D$row"))
                append(textCell("E$row", if (state.complete) "已确认" else "待核对：小计仅含已确定项目，非最终总分"))
                append("</row>")
            }
            row++
            append("<row r=\"$row\">").append(textCell("A$row", "学年活动记录与申报资料；未确认项目不构成正式核定成绩。小计仅含已确定项目，维度未完整时最终封顶分留空。只计一次仍保留全部参与。")).append("</row>")
            assessment?.warnings?.forEach { issue ->
                row++
                append("<row r=\"$row\">").append(textCell("A$row", issue.description)).append("</row>")
            }
        }
        append("</sheetData>")
        if (merges.isNotEmpty()) {
            append("<mergeCells count=\"${merges.size}\">")
            merges.forEach { append("<mergeCell ref=\"$it\"/>") }
            append("</mergeCells>")
        }
        append(sheetSuffix())
    }

    private fun detailSheet(rows: List<NumberedRecord>) = buildString {
        append(sheetPrefix())
        val hasVisits = rows.any { it.record.participations.isNotEmpty() }
        // Exactly the summary's planned records, in the same order. Never regroup by participation here.
        val maxPhotos = rows.maxOfOrNull { it.photos.size } ?: 0
        val factsStart = maxPhotos + 2
        require(maxPhotos + 1 + (if (hasVisits) 13 else 0) <= 16384) { "申报凭证和说明超过 Excel 列数上限" }
        append("<cols><col min=\"1\" max=\"1\" width=\"10\" customWidth=\"1\"/>")
        if (maxPhotos > 0) append("<col min=\"2\" max=\"${maxPhotos + 1}\" width=\"23\" customWidth=\"1\"/>")
        if (hasVisits) {
            append("<col min=\"$factsStart\" max=\"${factsStart + 7}\" width=\"28\" customWidth=\"1\"/>")
            append("<col min=\"${factsStart + 8}\" max=\"${factsStart + 8}\" hidden=\"1\"/>")
            append("<col min=\"${factsStart + 9}\" max=\"${factsStart + 12}\" width=\"30\" customWidth=\"1\"/>")
        }
        append("</cols><sheetData><row r=\"1\" ht=\"27\" customHeight=\"1\">")
        append(textCell("A1", "序号", true))
        (1..maxPhotos).forEach { append(textCell("${column(it + 1)}1", "凭证$it", true)) }
        if (hasVisits) listOf("参与ID", "参与日期", "参与备注", "本行认可分", "计分方式", "条目ID", "计分状态", "活动名称", "申报行ID", "参与次数", "导出方式", "证明原始归属（按照片列顺序）", "学年")
            .forEachIndexed { index, label -> append(textCell("${column(factsStart + index)}1", label, true)) }
        append("</row>")
        rows.forEachIndexed { index, numbered ->
            val photos = numbered.photos
            val record = numbered.record
            val visits = record.participations
            val row = index + 2
            append("<row r=\"$row\" ht=\"${if (photos.isEmpty()) 24 else 105}\" customHeight=\"1\">")
            append(numberCell("A$row", numbered.number.toDouble()))
            (0 until maxPhotos).forEach { photoIndex ->
                val address = "${column(photoIndex + 2)}$row"
                val photo = photos.getOrNull(photoIndex)
                if (photo == null) append(blankCell(address))
                else append("<c r=\"$address\" s=\"2\" t=\"e\" vm=\"${photo.index + 1}\"><v>#VALUE!</v></c>")
            }
            if (hasVisits) {
                listOf(visits.joinToString(",") { it.id },
                    visits.joinToString("\n") { "${it.id}: ${it.date}" },
                    visits.joinToString("\n") { "${it.id}: ${it.note}（单次认可分：${it.confirmedScore?.toString() ?: "留空"}；${it.scoreStatus}）" }).forEachIndexed { fact, text ->
                    append(textCell("${column(factsStart + fact)}$row", text))
                }
                val address = "${column(factsStart + 3)}$row"
                record.score?.let { append(numberCell(address, it)) }
                    ?: append(blankCell(address))
                append(textCell("${column(factsStart + 4)}$row", numbered.record.countingLabel))
                append(textCell("${column(factsStart + 5)}$row", numbered.record.itemId))
                append(textCell("${column(factsStart + 6)}$row", record.scoreStatus))
                append(textCell("${column(factsStart + 7)}$row", record.title))
                append(textCell("${column(factsStart + 8)}$row", record.entryId))
                append(numberCell("${column(factsStart + 9)}$row", visits.size.toDouble()))
                append(textCell("${column(factsStart + 10)}$row", record.exportMode))
                append(textCell("${column(factsStart + 11)}$row", record.photoOwners))
                append(textCell("${column(factsStart + 12)}$row", record.entryId.substringBefore(":")))
            }
            append("</row>")
        }
        append("</sheetData>").append(sheetSuffix())
    }

    private fun sheetPrefix() = xmlStart() +
        "<worksheet xmlns=\"$sheetNs\"><sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"/></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/>"
    private fun sheetSuffix() = "</worksheet>"
    private fun textCell(address: String, value: String, header: Boolean = false, style: Int = if (header) 1 else 2): String {
        require(value.length <= 32767) { "单元格 $address 超过 Excel 32767 字符限制；请按参与拆分或缩短原备注后重试，文件未保存。" }
        return "<c r=\"$address\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>"
    }
    private fun numberCell(address: String, value: Double) = "<c r=\"$address\" s=\"2\"><v>$value</v></c>"
    private fun blankCell(address: String, style: Int = 2) = "<c r=\"$address\" s=\"$style\"/>"

    private fun metadata(count: Int) = buildString {
        append(xmlStart()).append("<metadata xmlns=\"$sheetNs\" xmlns:xlrd=\"$richNs\">")
        append("<metadataTypes count=\"1\"><metadataType name=\"XLRICHVALUE\" minSupportedVersion=\"120000\" copy=\"1\" pasteAll=\"1\" pasteValues=\"1\" merge=\"1\" splitFirst=\"1\" rowColShift=\"1\" clearFormats=\"1\" clearComments=\"1\" assign=\"1\" coerce=\"1\"/></metadataTypes>")
        append("<futureMetadata name=\"XLRICHVALUE\" count=\"$count\">")
        repeat(count) { index -> append("<bk><extLst><ext uri=\"{3e2802c4-a4d2-4d8b-9148-e3be6c30e623}\"><xlrd:rvb i=\"$index\"/></ext></extLst></bk>") }
        append("</futureMetadata><valueMetadata count=\"$count\">")
        repeat(count) { index -> append("<bk><rc t=\"1\" v=\"$index\"/></bk>") }
        append("</valueMetadata></metadata>")
    }

    private fun richValueStructure() = xmlStart() +
        "<rvStructures xmlns=\"$richNs\" count=\"1\"><s t=\"_localImage\"><k n=\"_rvRel:LocalImageIdentifier\" t=\"i\"/><k n=\"CalcOrigin\" t=\"i\"/></s></rvStructures>"

    private fun richValues(count: Int) = buildString {
        append(xmlStart()).append("<rvData xmlns=\"$richNs\" count=\"$count\">")
        repeat(count) { index -> append("<rv s=\"0\"><v>$index</v><v>5</v></rv>") }
        append("</rvData>")
    }

    private fun richValueTypes() = xmlStart() + """
        <rvTypesInfo xmlns="http://schemas.microsoft.com/office/spreadsheetml/2017/richdata2" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" mc:Ignorable="x" xmlns:x="$sheetNs">
          <global><keyFlags>
            <key name="_Self"><flag name="ExcludeFromCalcComparison" value="1"/><flag name="ExcludeFromFile" value="1"/></key>
            <key name="_DisplayString"><flag name="ExcludeFromCalcComparison" value="1"/></key>
            <key name="_Flags"><flag name="ExcludeFromCalcComparison" value="1"/></key>
            <key name="_Format"><flag name="ExcludeFromCalcComparison" value="1"/></key>
            <key name="_SubLabel"><flag name="ExcludeFromCalcComparison" value="1"/></key>
            <key name="_Attribution"><flag name="ExcludeFromCalcComparison" value="1"/></key>
            <key name="_Icon"><flag name="ExcludeFromCalcComparison" value="1"/></key>
            <key name="_Display"><flag name="ExcludeFromCalcComparison" value="1"/></key>
            <key name="_CanonicalPropertyNames"><flag name="ExcludeFromCalcComparison" value="1"/></key>
            <key name="_ClassificationId"><flag name="ExcludeFromCalcComparison" value="1"/></key>
          </keyFlags></global>
        </rvTypesInfo>
    """.trimIndent()

    private fun richValueRelationships(count: Int) = buildString {
        append(xmlStart()).append("<richValueRels xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2022/richvaluerel\" xmlns:r=\"$relNs\">")
        repeat(count) { append("<rel r:id=\"rId${it + 1}\"/>") }
        append("</richValueRels>")
    }

    private fun imageRelationships(photos: List<PhotoCell>) = buildString {
        append(xmlStart()).append("<Relationships xmlns=\"$packageRelNs\">")
        photos.forEach { photo ->
            append("<Relationship Id=\"rId${photo.index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image${photo.index + 1}.${photo.photo.extension}\"/>")
        }
        append("</Relationships>")
    }

    private fun column(index: Int): String {
        var n = index
        val result = StringBuilder()
        while (n > 0) {
            n--
            result.insert(0, ('A'.code + n % 26).toChar())
            n /= 26
        }
        return result.toString()
    }

    private fun escape(input: String): String = buildString {
        input.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (char == '\t' || char == '\n' || char == '\r' || char.code >= 0x20) append(char)
            }
        }
    }

    private fun xmlStart() = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private fun ZipOutputStream.xml(path: String, body: String) {
        putNextEntry(ZipEntry(path))
        write(body.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }
}
