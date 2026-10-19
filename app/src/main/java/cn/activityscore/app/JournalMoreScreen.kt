package cn.activityscore.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun JournalMoreScreen(onHistory: () -> Unit, onPackage: () -> Unit, onAnnual: () -> Unit,
    onOverview: () -> Unit, onReviews: () -> Unit, onReread: () -> Unit, onDiagnostics: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = JournalSpace.gutter)) {
        Muted("历史参考、资料维护和数据工具，随时可用。")
        Spacer(Modifier.height(20.dp))
        MoreAction("历史评分", "评分参考库、历史表导入及列映射", onHistory)
        MoreAction("导入活动包", "班级活动与所选证明；分享入口在每次参与的操作中", onPackage)
        MoreAction("录入学年成绩", "年度资料、课程、体测与跑步成绩", onAnnual)
        MoreAction("学年成绩与六维进度", "原统一计分模型的完整测算与缺项说明", onOverview)
        MoreAction("查看重复核对记录", "待核对与已确认结果，可重新检查", onReviews)
        MoreAction("重新读取记录", "读取异常及未知操作结果仍使用原有保护流程", onReread)
        MoreAction("数值与数据诊断", "查看旧数值问题；数据库无法打开时提供原重试页", onDiagnostics)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun MoreAction(title: String, description: String, onClick: () -> Unit) {
    TextButton(onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
        Column(Modifier.fillMaxWidth()) { Text(title, style = MaterialTheme.typography.titleSmall); Muted(description) }
    }
    HorizontalDivider()
}
