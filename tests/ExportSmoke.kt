package cn.activityscore.app.data

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

/** Standalone JVM smoke check; creates a sample workbook under build/test-export. */
fun main() {
    val outputDir = File("build/test-export").also { it.mkdirs() }
    val image = File(outputDir, "test.jpg")
    val picture = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB).also {
        val g = it.createGraphics()
        g.color = Color.ORANGE
        g.fillRect(0, 0, 32, 24)
        g.dispose()
    }
    ImageIO.write(picture, "jpg", image)
    val png = File(outputDir, "test.png")
    ImageIO.write(picture, "png", png)
    val workbook = File(outputDir, "sample.xlsx")
    workbook.outputStream().use { stream ->
        ScoreXlsxWriter.write(
            listOf(
                ExportRecord("身心健康", "晨跑", "参与者", 1.5, listOf(ExportPhoto(image, "jpg"), ExportPhoto(png, "png")), itemId = "41"),
                ExportRecord("身心健康", "校运会", "一等奖", null, emptyList()),
                ExportRecord("身心健康", "徒步", "参与者", 2.0, emptyList()),
                ExportRecord("学术创新", "科技竞赛", "二等奖", 0.0, listOf(ExportPhoto(image, "jpg"))),
            ),
            stream,
        ) { source, target -> source.file.inputStream().use { it.copyTo(target) } }
    }
    val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    ZipFile(workbook).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toSet()
        check((1..7).all { "xl/worksheets/sheet$it.xml" in names })
        check("xl/media/image1.jpg" in names && "xl/media/image2.png" in names && "xl/media/image3.jpg" in names)
        check("image/png" in zip.getInputStream(zip.getEntry("[Content_Types].xml")).bufferedReader().use { it.readText() })
        names.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { name ->
            zip.getInputStream(zip.getEntry(name)).use { xml.newDocumentBuilder().parse(it) }
        }
        fun part(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
        val styles = part("xl/styles.xml")
        check("<borders count=\"5\">" in styles && "<cellXfs count=\"6\">" in styles)
        check(styles.contains("<left style=\"thin\"") && styles.contains("<bottom style=\"thin\""))
        val summary = part("xl/worksheets/sheet1.xml")
        check("A2:A4" in summary)
        check("<c r=\"A2\" s=\"3\"" in summary)
        check("<c r=\"A3\" s=\"4\"/>" in summary)
        check("<c r=\"A4\" s=\"5\"/>" in summary)
        check("<c r=\"B2\" s=\"2\"><v>1.0</v></c>" in summary)
        check("<c r=\"B3\" s=\"2\"><v>2.0</v></c>" in summary)
        check("<c r=\"B4\" s=\"2\"><v>3.0</v></c>" in summary)
        check("<c r=\"B5\" s=\"2\"><v>1.0</v></c>" in summary)
        check("<c r=\"E3\" s=\"2\"/>" in summary)
        check("<c r=\"E5\" s=\"2\"><v>0.0</v></c>" in summary)
        check("<col min=\"6\" max=\"6\" hidden=\"1\"/>" in summary)
        check("<c r=\"F2\" s=\"2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">41</t></is></c>" in summary)
        val detail = part("xl/worksheets/sheet2.xml")
        check("vm=\"1\"" in detail && "vm=\"2\"" in detail)
        check("<c r=\"B3\" s=\"2\"/>" in detail)
        check("<c r=\"C3\" s=\"2\"/>" in detail)
        check("vm=\"3\"" in part("xl/worksheets/sheet3.xml"))
        check("count=\"3\"" in part("xl/metadata.xml"))
    }
    val emptyWorkbook = File(outputDir, "empty.xlsx")
    emptyWorkbook.outputStream().use { stream ->
        ScoreXlsxWriter.write(emptyList(), stream) { _, _ -> error("空表不应写图片") }
    }
    ZipFile(emptyWorkbook).use { zip ->
        check(zip.getEntry("xl/metadata.xml") == null)
        check((1..7).all { zip.getEntry("xl/worksheets/sheet$it.xml") != null })
        zip.entries().asSequence().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }
            .forEach { zip.getInputStream(it).use { input -> xml.newDocumentBuilder().parse(input) } }
    }
    println(workbook.absolutePath)
}
