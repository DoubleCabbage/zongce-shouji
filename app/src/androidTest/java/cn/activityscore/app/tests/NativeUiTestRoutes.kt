package cn.activityscore.app.tests

import android.view.accessibility.AccessibilityNodeInfo

/** Updates navigation/field discovery only; all original persistence/failure assertions stay intact. */
internal object NativeUiTestRoutes {
    private fun click(node: AccessibilityNodeInfo) {
        var n = node
        while (!n.isClickable && n.parent != null) n = n.parent
        check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(200)
    }
    fun beforeTap(label: String, tap: (String) -> Unit, nodes: () -> List<AccessibilityNodeInfo>): Boolean {
        fun has(text: String) = nodes().any { it.text?.toString() == text || it.contentDescription?.toString() == text }
        if (label == "‹ 上一学年" || label == "下一学年 ›") {
            val prefix = if (label.startsWith("‹")) "上一学年 ·" else "下一学年 ·"
            fun selector() = nodes().firstOrNull { it.text?.toString()?.matches(Regex("\\d{4}—\\d{4} ▾")) == true }
            fun entry() = nodes().firstOrNull { it.text?.toString()?.startsWith(prefix) == true }
            if (entry() == null) {
                var header: AccessibilityNodeInfo? = null
                repeat(100) { if (header == null) { header = selector(); if (header == null) Thread.sleep(100) } }
                click(requireNotNull(header) { "Year selector did not settle" })
            }
            var option: AccessibilityNodeInfo? = null
            repeat(100) { if (option == null) { option = entry(); if (option == null) Thread.sleep(100) } }
            click(requireNotNull(option) { "Year menu did not open" })
            var settled = 0
            repeat(100) {
                // A deliberately failed year read opens the existing blocking error dialog.
                // It is a valid post-menu destination; the caller still asserts error/retry behavior.
                val destinationReady = selector() != null || nodes().any { it.text?.toString() == "数据未完整读取" }
                if (destinationReady && entry() == null) settled++ else settled = 0
                if (settled >= 3) return true
                Thread.sleep(100)
            }
            error("Year menu did not close")
        }
        if (label in listOf("记录活动", "录入学年成绩", "历史评分", "重新读取记录") && !has(label)) {
            if (has("返回活动")) tap("返回活动")
            if (label == "记录活动") tap("首页")
            else {
                if (!has("更多")) tap("首页")
                tap("更多")
            }
        }
        return false
    }
    fun expandOptional(label: String, nodes: () -> List<AccessibilityNodeInfo>) {
        if (listOf("历史参考分数", "条目备注", "小分类", "职务 / 获奖等级").any { label.startsWith(it) }) {
            nodes().firstOrNull { it.text?.toString() == "更多可选信息" }?.let { node ->
                click(node)
                repeat(80) {
                    if (nodes().any { it.text?.toString() == "收起可选信息" }) return
                    Thread.sleep(100)
                }
                error("Optional form did not expand: ${nodes().map { it.text }}")
            }
        }
    }
}
