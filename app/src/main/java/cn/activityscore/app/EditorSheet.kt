package cn.activityscore.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/** Only user-editable forms use a sheet. Safety decisions keep the original blocking dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EditorSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val drafts = LocalDraft.current
    // Scrolling an input form back to its beginning must not turn into a dismiss gesture.
    // The handle, scrim and system Back retain Material's normal dismissal behavior.
    val formScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0f) Offset(0f, available.y) else Offset.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
        }
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !EvidenceOperation.busy })
    ModalBottomSheet(onDismissRequest = { if (!EvidenceOperation.busy) onDismiss() }, sheetState = state,
        containerColor = MaterialTheme.colorScheme.background) {
        val viewport = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.86f
        Column(Modifier.fillMaxWidth().height(viewport).imePadding().nestedScroll(formScroll).verticalScroll(rememberScrollState())
            .padding(start = JournalSpace.gutter, end = JournalSpace.gutter, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Muted("先记下发生的事，分数和证明不着急。")
            if (drafts.error.isNotBlank()) {
                Text(drafts.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { drafts.retry() }) { Text("重试保存草稿") }
            } else if (!drafts.saved) Muted("草稿正在写入设备…")
            content()
        }
    }
}
