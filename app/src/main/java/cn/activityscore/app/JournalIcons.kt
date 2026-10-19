package cn.activityscore.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Native vectors from the confirmed HTML's simple line icons; no raster or web renderer. */
internal object JournalIcons {
    private fun outline(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    val home = outline("Home", "M3 10 12 3l9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z")
    val list = outline("Activities", "M6 4h12a2 2 0 0 1 2 2v14H4V6a2 2 0 0 1 2-2ZM8 9h8M8 13h8M8 17h4")
    val organize = outline("Organize", "M4 5h16v15H4ZM8 3v4m8-4v4M4 10h16m-12 5 3 3 5-5")
    val plus = outline("Add", "M12 5v14M5 12h14")
    val download = outline("Export", "M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5")
    val verified = outline("Confirmed", "m12 3 3 2 4 .5.5 4 2 2.5-2 3-.5 4-4 .5-3 2-3-2-4-.5-.5-4L3 12l2-2.5.5-4 4-.5ZM8 12l3 3 5-6")
    private val people = outline("People", "M15 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2M13 3a4 4 0 0 1 0 8m8 10v-2a4 4 0 0 0-3-3.87M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0")
    private val leaf = outline("Leaf", "M20 4c-7-2-15 1-15 8a6 6 0 0 0 6 6c7 0 9-8 9-14ZM5 21 16 9M8 16v-5m0 5h5")
    private val book = outline("Book", "M12 6c-3-2-6-2-9-1v15c3-1 6-1 9 1m0-15c3-2 6-2 9-1v15c-3-1-6-1-9 1Zm0 0v15")
    private val run = outline("Run", "M15 4a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0M5 12l4-4 4 2 3 4h4M12 10l-3 6-5 5m5-5 5 1 1 4")
    private val art = outline("Art", "M12 3a9 9 0 1 0 0 18h1a2 2 0 0 0 1-4c-1-1 0-2 2-2h2c3 0 4-3 2-6a9 9 0 0 0-8-6ZM7 10h.01M10 6h.01M15 6h.01M18 10h.01")
    private val flag = outline("Flag", "M5 21V3c5-3 8 3 14 0v11c-6 3-9-3-14 0")
    fun activity(dimension: String) = when (dimension) {
        "身心健康" -> run; "学术创新" -> book; "劳动" -> leaf; "美育" -> art; "思想成长" -> flag; else -> people
    }
}
