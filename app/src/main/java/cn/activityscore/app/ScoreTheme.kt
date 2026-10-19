package cn.activityscore.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography

private val LightColors = lightColorScheme(
    primary = Color(0xFF5956B4),
    secondary = Color(0xFF377562),
    tertiary = Color(0xFF946323), tertiaryContainer = Color(0xFFF6F0E5), onTertiaryContainer = Color(0xFF805B29),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFEEEDF9),
    onSecondaryContainer = Color(0xFF5956B4),
    background = Color(0xFFF8F8F5),
    onBackground = Color(0xFF252631),
    surface = Color.White,
    onSurface = Color(0xFF252631),
    surfaceVariant = Color(0xFFEEEDF9),
    onSurfaceVariant = Color(0xFF6C6D79),
    outline = Color(0xFF888793),
    outlineVariant = Color(0xFFE6E6E4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC0BCFF),
    secondary = Color(0xFF9BD4B5),
    tertiary = Color(0xFFE9BE7E), tertiaryContainer = Color(0xFF403522), onTertiaryContainer = Color(0xFFF3D5A8),
    onPrimary = Color(0xFF28235B),
    secondaryContainer = Color(0xFF302E48),
    onSecondaryContainer = Color(0xFFC0BCFF),
    background = Color(0xFF19191D),
    onBackground = Color(0xFFE8E7EC),
    surface = Color(0xFF232328),
    onSurface = Color(0xFFE8E7EC),
    surfaceVariant = Color(0xFF302E48),
    onSurfaceVariant = Color(0xFFBBB9C6),
    outline = Color(0xFF9693A1),
    outlineVariant = Color(0xFF414047),
)

private val ScoreShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

private val JournalTypography = Typography(
    displayLarge = TextStyle(fontSize = 54.sp, lineHeight = 64.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 17.sp),
)

@Composable
fun ScoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = ScoreShapes,
        typography = JournalTypography,
        content = content,
    )
}
