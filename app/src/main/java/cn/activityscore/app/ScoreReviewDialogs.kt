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
internal fun DuplicateReviewDialog(
    review: DuplicateReview,
    remaining: Int,
    previous: DuplicateConfirmation?,
    onDismiss: () -> Unit,
    onConfirm: (DuplicateConfirmation) -> Unit,
    onOpenRecord: (Long) -> Unit,
) {
    FormDialog(
        title = if (remaining > 0) "重复记录确认（待处理 $remaining 组）" else "重复记录确认",
        onDismiss = onDismiss,
    ) {
        Text(
            "${review.academicYear}—${review.academicYear + 1} 学年",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (review) {
            is RepeatedParticipationReview -> {
                var decision by draftState("review/${review.key}/${review.fingerprint}/decision") {
                    mutableStateOf(previous?.decision?.takeIf {
                        previous.fingerprint == review.fingerprint &&
                            (it == DuplicateDecision.COUNT_ONCE || it == DuplicateDecision.COUNT_EACH)
                    })
                }
                var scoreTexts by draftState("review/${review.key}/${review.fingerprint}/scoreTexts") {
                    mutableStateOf(previous?.participationScores.orEmpty().mapValues { NumericValues.formatForEdit(it.value) })
                }
                Text(review.item.title, style = MaterialTheme.typography.titleMedium)
                Text("这个活动在本学年记录了 ${review.participations.size} 次参与，请核对计分方式。")
                review.participations.forEachIndexed { index, participation ->
                    Text(
                        "${index + 1}. ${participation.eventDate} · 凭证 ${participation.evidence.size} 张",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = decision == DuplicateDecision.COUNT_ONCE,
                        onClick = { decision = DuplicateDecision.COUNT_ONCE },
                        label = { Text("只计一次") },
                    )
                    FilterChip(
                        selected = decision == DuplicateDecision.COUNT_EACH,
                        onClick = { decision = DuplicateDecision.COUNT_EACH },
                        label = { Text("分别计分") },
                    )
                }
                if (decision == DuplicateDecision.COUNT_EACH) {
                    Text("填写已核对的单次分数；暂未确认的可留空并保存进度。全部补齐前活动总认可分仍待核对。${NumericValues.INPUT_HINT}。")
                    review.participations.forEachIndexed { index, participation ->
                        OutlinedTextField(
                            value = scoreTexts[participation.id].orEmpty(),
                            onValueChange = { value ->
                                scoreTexts = scoreTexts + (participation.id to value)
                            },
                            label = { Text("第 ${index + 1} 次 · ${participation.eventDate} 的确认分数") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = !NumericValues.parse(scoreTexts[participation.id].orEmpty()).isValid,
                            supportingText = {
                                NumericValues.parse(scoreTexts[participation.id].orEmpty()).error?.let { Text(it) }
                            },
                        )
                    }
                }
                val scores = review.participations.mapNotNull { participation ->
                    NumericValues.parse(scoreTexts[participation.id].orEmpty()).value?.let { score ->
                        participation.id to score
                    }
                }.toMap()
                val canConfirm = decision == DuplicateDecision.COUNT_ONCE ||
                    decision == DuplicateDecision.COUNT_EACH &&
                    review.participations.all { NumericValues.parse(scoreTexts[it.id].orEmpty()).isValid }
                Text(
                    "只计一次或逐次分完整时沿用原计分规则；保存不完整进度不会生成最终认可分，也不影响参与事实导出。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        onConfirm(DuplicateConfirmation(
                            review.key, review.fingerprint, requireNotNull(decision),
                            if (decision == DuplicateDecision.COUNT_EACH) scores else emptyMap(),
                        ))
                    },
                    enabled = canConfirm,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (decision == DuplicateDecision.COUNT_EACH && scores.size < review.participations.size) "保存核对进度" else "确认本次核对") }
            }
            is SimilarItemsReview -> {
                Text("${review.dimension} · ${review.title}", style = MaterialTheme.typography.titleMedium)
                Text("发现 ${review.items.size} 条同名活动，可能是重复录入，也可能是不同奖项。")
                review.items.forEach { item ->
                    Text("${item.title} · ${item.role.ifBlank { "未填等级" }} · ${formatOptionalScore(item.referenceScore)}")
                }
                Text("如果确实重复，请到当前记录修改或删除多余条目。只有确认它们是不同活动时，才点下方按钮。")
                OutlinedButton(
                    onClick = { onOpenRecord(review.items.first().id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("查看记录并处理") }
                Button(
                    onClick = {
                        onConfirm(DuplicateConfirmation(
                            review.key, review.fingerprint, DuplicateDecision.DISTINCT_ITEMS,
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确认是不同活动") }
            }
        }
        TextButton(onClick = onDismiss) { Text("丢弃本次输入，稍后核对") }
    }
}


@Composable
internal fun AnnualInputsDialog(
    year: Int,
    existing: AnnualInputs?,
    onDismiss: () -> Unit,
    onSave: (AnnualInputs) -> Unit,
) {
    val drafts = LocalDraft.current
    val prefix = "annual/$year"
    var self by draftState("$prefix/self") { mutableStateOf(NumericValues.formatForEdit(existing?.selfEvaluation)) }
    var peers by draftState("$prefix/peers") { mutableStateOf(NumericValues.formatForEdit(existing?.peerEvaluation)) }
    var teachers by draftState("$prefix/teachers") { mutableStateOf(NumericValues.formatForEdit(existing?.teacherEvaluation)) }
    var deductions by draftState("$prefix/deductions") { mutableStateOf(NumericValues.formatForEdit(existing?.deductions)) }
    var fitness by draftState("$prefix/fitness") { mutableStateOf(NumericValues.formatForEdit(existing?.fitness)) }
    var running by draftState("$prefix/running") { mutableStateOf(NumericValues.formatForEdit(existing?.running)) }
    var courses by draftState("$prefix/courses") {
        mutableStateOf(existing?.courses.orEmpty().map {
            CourseDraft(it.name, NumericValues.formatForEdit(it.credits), NumericValues.formatForEdit(it.grade))
        })
    }
    fun percent(value: String) = NumericValues.parse(value, rule = NumericRule.PERCENT)
    fun credits(value: String) = NumericValues.parse(value, optional = false, rule = NumericRule.POSITIVE)
    fun grade(value: String) = NumericValues.parse(value, optional = false, rule = NumericRule.PERCENT)
    fun validOptionalPercent(value: String): Boolean = percent(value).isValid
    val valid = listOf(self, peers, teachers, fitness, running).all(::validOptionalPercent) &&
        NumericValues.parse(deductions).isValid &&
        courses.all { course ->
            course.name.isNotBlank() && credits(course.credits).isValid && grade(course.grade).isValid
        }
    val parsedCourses = if (valid) courses.map {
        CourseGrade(it.name.trim(), requireNotNull(credits(it.credits).value), requireNotNull(grade(it.grade).value))
    } else emptyList()
    val professional = AnnualScoreCalculator.professional(parsedCourses)
    fun dismiss() { if (!EvidenceOperation.busy) { drafts.removePrefix("$prefix/"); onDismiss() } }
    FormDialog(title = "$year—${year + 1} 学年成绩资料", onDismiss = ::dismiss) {
        Text(NumericValues.INPUT_HINT)
        Text("基本素质", style = MaterialTheme.typography.titleMedium)
        Text("班级评议 = 自评 × 10% + 同学互评 × 70% + 辅导员/班主任评议 × 20%；再减扣分项。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(self, { self = it }, label = { Text("学生自评（0–100）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(peers, { peers = it }, label = { Text("班级同学互评（0–100）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(teachers, { teachers = it }, label = { Text("辅导员、班主任评议（0–100）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(deductions, { deductions = it }, label = { Text("扣分项（没有请填 0）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        HorizontalDivider()
        Text("专业素质", style = MaterialTheme.typography.titleMedium)
        Text("按每门课程的学分与百分制成绩计算加权平均；GPA 不能直接代替。",
            style = MaterialTheme.typography.bodySmall)
        courses.forEachIndexed { index, course ->
            Text("课程 ${index + 1}", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(course.name, { value ->
                courses = courses.toMutableList().apply { this[index] = course.copy(name = value) }
            }, label = { Text("课程名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(course.credits, { value ->
                    courses = courses.toMutableList().apply { this[index] = course.copy(credits = value) }
                }, label = { Text("学分") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(course.grade, { value ->
                    courses = courses.toMutableList().apply { this[index] = course.copy(grade = value) }
                }, label = { Text("成绩") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            TextButton(onClick = { courses = courses.filterIndexed { i, _ -> i != index } }) {
                Text("移除课程")
            }
        }
        OutlinedButton(onClick = { courses = courses + CourseDraft("", "", "") },
            modifier = Modifier.fillMaxWidth()) { Text("添加课程") }
        Text("专业素质：${formatScoreOrMissing(professional)}",
            style = MaterialTheme.typography.bodySmall)
        if (valid && parsedCourses.isNotEmpty() && professional == null)
            Text("课程加权计算超出 Double 有效范围，请核对学分；原始数值不会被舍入或置零。", color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
        Text("身心健康", style = MaterialTheme.typography.titleMedium)
        Text("体测 × 20% + 健步跑 × 10% + 本学年身心健康活动分（上限 10）。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(fitness, { fitness = it }, label = { Text("体能测试成绩（0–100）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(running, { running = it }, label = { Text("体育锻炼／健步跑成绩（0–100）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (!valid) Text("请检查数字格式、有限数值、分数范围、学分和课程信息。", color = MaterialTheme.colorScheme.error)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = ::dismiss) { Text("取消") }
            Button(onClick = {
                onSave(AnnualInputs(year, percent(self).value, percent(peers).value,
                    percent(teachers).value, NumericValues.parse(deductions).value,
                    percent(fitness).value, percent(running).value, parsedCourses))
            }, enabled = valid) { Text("保存资料") }
        }
    }
}

