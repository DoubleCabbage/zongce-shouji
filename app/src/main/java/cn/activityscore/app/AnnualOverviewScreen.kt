package cn.activityscore.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import java.io.File
import java.time.LocalDate

@Composable
internal fun AnnualOverviewScreen(
    summaries: List<ScoreSummary>,
    annualScore: AnnualScore,
    onChangeYear: (Int) -> Unit,
    pendingDuplicateCount: Int,
    hasDuplicateReviews: Boolean,
    onReviewDuplicates: () -> Unit,
    onManageDuplicates: () -> Unit,
    onAdd: () -> Unit,
    onEditAnnual: () -> Unit,
    onOpenRecords: (String) -> Unit,
) {
    val totalReference = summaries.sumOf { it.referenceTotal }
    val itemCount = summaries.sumOf { it.itemCount }
    val participationCount = summaries.sumOf { it.participationCount }
    val heroColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text(
            "GROWTH JOURNAL",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text("我的素养分", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "把每一次参与，认真记录下来。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onChangeYear(annualScore.academicYear - 1) }) { Text("‹ 上一学年") }
            Text("${annualScore.academicYear}—${annualScore.academicYear + 1} 学年",
                style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { onChangeYear(annualScore.academicYear + 1) }) { Text("下一学年 ›") }
        }
        Spacer(Modifier.height(8.dp))
        Surface(color = heroColor, shape = RoundedCornerShape(23.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("学年综合素质成绩测算", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    annualScore.total?.let { "${formatScore(it)} 分" } ?: "待完善",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(17.dp))
                Text(
                    "基本 ${formatScoreOrMissing(annualScore.basic)} × 10%  ·  专业 ${formatScoreOrMissing(annualScore.professional)} × 70%",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "发展 ${formatScoreOrMissing(annualScore.development)} × 20%",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "发展素质：身心健康 ${formatScoreOrMissing(annualScore.physicalMental)} / 40 · 其他五类活动最高 60 分",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (annualScore.missingActivityScores > 0 || annualScore.pendingReviews > 0) {
            Text(
                "有 ${annualScore.missingActivityScores} 个活动分数未确定、${annualScore.pendingReviews} 组重复待核对；暂不生成综合成绩。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (annualScore.invalidParticipationCount > 0) {
            Text("另有 ${annualScore.invalidParticipationCount} 条参与日期无效，无法归属学年，请修改后再计分或导出。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(14.dp))
        if (pendingDuplicateCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "有 $pendingDuplicateCount 组重复记录待确认",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onReviewDuplicates) { Text("查看") }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        if (hasDuplicateReviews) {
            TextButton(onClick = onManageDuplicates) { Text("查看重复核对记录") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAdd,
                modifier = Modifier.weight(1.2f).height(52.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("新建记录", maxLines = 1)
            }
            OutlinedButton(
                onClick = onEditAnnual,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(15.dp),
            ) { Text("录入学年成绩", maxLines = 1) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "历史参考估分 ${formatScore(totalReference)} 分 · 累计 $itemCount 个条目、$participationCount 次参与",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(29.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("六维进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "本学年活动分 / 维度上限",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(13.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(19.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                summaries.forEachIndexed { index, summary ->
                    val cap = scoreDimensionCaps.getValue(summary.dimension)
                    val annualDimensionScore = annualScore.activityByDimension.getValue(summary.dimension)
                    val fraction = (annualDimensionScore / cap).toFloat().coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onOpenRecords(summary.dimension) }
                            .padding(vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(summary.dimension, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "${formatScore(annualDimensionScore)} / $cap 分",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(9.dp))
                        Box(
                            Modifier.fillMaxWidth().height(7.dp)
                                .clip(RoundedCornerShape(50.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(fraction).fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                    if (index < summaries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        Text(
            "活动分按学年归属、重复核对结果与领域上限计算；最终以学校核算为准。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


