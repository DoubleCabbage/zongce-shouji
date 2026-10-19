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
internal fun DimensionChooser(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        scoreDimensions.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { dimension ->
                    FilterChip(
                        selected = selected == dimension,
                        onClick = { onSelect(dimension) },
                        label = { Text(dimension, maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val drafts = LocalDraft.current
    Dialog(onDismissRequest = { if (!EvidenceOperation.busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    
                )
                if (drafts.error.isNotBlank()) {
                    Text(drafts.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { drafts.retry() }) { Text("重试保存草稿") }
                } else if (!drafts.saved) Text("草稿正在写入设备…", style = MaterialTheme.typography.bodySmall)
                content()
            }
        }
    }
}

@Composable
internal fun DialogActions(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "保存",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(enabled = !EvidenceOperation.busy, onClick = onDismiss) { Text("取消") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onConfirm) { Text(confirmLabel) }
    }
}

internal fun formatScore(value: Double): String = NumericValues.formatForDisplay(value)
internal fun formatScoreOrMissing(value: Double?): String = value?.let { "${formatScore(it)} 分" } ?: "待填写"
internal fun formatOptionalScore(value: Double?): String =
    if (value == null) "未填写" else "${formatScore(value)} 分"

internal fun isValidDate(value: String): Boolean = try {
    LocalDate.parse(value.trim())
    true
} catch (_: Exception) {
    false
}
