package cn.activityscore.app.tests

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import cn.activityscore.app.MainActivity
import cn.activityscore.app.data.RecordStore
import java.io.File

/** Real Compose UI + real isolated SQLite. Uses accessibility, never the normal app database. */
class DataUiChecks(private val instrumentation: Instrumentation) {
    private var count = 0
    private fun expect(ok: Boolean) { check(ok) { "UI assertion ${count + 1}" }; count++ }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(::collect) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::collect)
        return result
    }
    private fun text(node: AccessibilityNodeInfo) = "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}"
    private fun waitFor(label: String, absent: Boolean = false) {
        repeat(100) {
            // Some cold emulator boots show a System UI ANR unrelated to this app. Choose Wait;
            // do not count that environment interruption as a passed application assertion.
            if (nodes().any { text(it).contains("System UI isn't responding") }) {
                nodes().firstOrNull { it.text?.toString() == "Wait" }?.let { wait ->
                    var button = wait
                    while(!button.isClickable && button.parent != null) button = button.parent
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.w("P109UI", "Emulator System UI ANR interrupted test; selected Wait")
                }
            }
            if(nodes().any { text(it).contains(label) } != absent) return
            Thread.sleep(100)
        }
        error("UI did not ${if(absent) "hide" else "show"} $label: ${nodes().joinToString { text(it) }}")
    }
    private fun locate(label: String, editable: Boolean = false, exact: Boolean = false): AccessibilityNodeInfo {
        fun fieldLabel(node: AccessibilityNodeInfo): Boolean {
            if(text(node).contains(label)) return true
            return (0 until node.childCount).any { node.getChild(it)?.let(::fieldLabel) == true }
        }
        repeat(20) {
            if (editable) NativeUiTestRoutes.expandOptional(label, ::nodes)
            nodes().firstOrNull { (if(editable) fieldLabel(it) else if(exact) it.text?.toString() == label || it.contentDescription?.toString() == label else text(it).contains(label)) &&
                (!editable || it.isEditable) }?.let { return it }
            val scroll = nodes().firstOrNull { it.isScrollable }
            scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(150)
        }
        error("Missing $label (editable=$editable): ${nodes().joinToString { "${it.className}: ${text(it)}" }}")
    }
    private fun tap(label: String) {
        if (NativeUiTestRoutes.beforeTap(label, ::tap, ::nodes)) return
        var node = locate(label, exact = true)
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        Thread.sleep(150)
    }
    private fun type(label: String, value: String) {
        repeat(8) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(50) }
        val node = locate(label, true)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        expect(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        Thread.sleep(150)
    }
    fun run(): Bundle {
        val results = Bundle()
        val context = DataOperationChecks(instrumentation.targetContext).uiContext()
        val harness = RecordStore(context)
        val originalFactory = MainActivity.openStore
        val originalDraftContext = MainActivity.draftContext
        var uiStore: RecordStore? = null
        var activity: android.app.Activity? = null
        try {
            // Initial read failure must show retry, not an empty business screen.
            harness.withBackupDatabase { it.execSQL("ALTER TABLE history_info RENAME TO unavailable_info") }
            MainActivity.openStore = { RecordStore(context).also { uiStore = it } }
            MainActivity.draftContext = { context }
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("数据读取失败")
            expect(nodes().none { text(it).contains("记录活动") })
            expect(nodes().any { text(it).contains("重新读取") })
            harness.withBackupDatabase { it.execSQL("ALTER TABLE unavailable_info RENAME TO history_info") }
            tap("重新读取"); waitFor("活动")
            harness.withBackupDatabase { it.execSQL("CREATE TRIGGER ui_failure BEFORE INSERT ON score_items BEGIN SELECT RAISE(ABORT,'UI fixture'); END") }
            tap("记录活动"); waitFor("新建加分条目")
            type("活动名称", "保存失败仍保留输入")
            tap("保存"); waitFor("操作结果")
            expect(nodes().any { text(it).contains("原有数据未改变") })
            expect(harness.listItems().isEmpty())
            tap("保留输入并返回")
            // Scroll back to title after the save button, without rebuilding the editor.
            repeat(8) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(50) }
            expect(locate("保存失败仍保留输入", true).text.toString().contains("保存失败仍保留输入"))
            harness.withBackupDatabase { it.execSQL("DROP TRIGGER ui_failure") }
            tap("保存"); waitFor("记录已保存")
            expect(harness.listItems().single().title == "保存失败仍保留输入")
            expect(harness.listParticipations(harness.listItems().single().id).size == 1)
            expect(nodes().none { text(it).contains("新建加分条目") })
            // A later read failure blocks writes and preserves the loaded records.
            harness.withBackupDatabase { it.execSQL("ALTER TABLE history_info RENAME TO unavailable_info") }
            tap("重新读取记录"); waitFor("数据未完整读取")
            expect(harness.listItems().size == 1)
            harness.withBackupDatabase { it.execSQL("ALTER TABLE unavailable_info RENAME TO history_info") }
            tap("重试读取"); waitFor("数据未完整读取", true)
            // Unknown commit status must not enable a blind retry from the retained form.
            tap("记录活动"); type("活动名称", "未知提交保留输入")
            uiStore!!.writeFault = { if(it == "AFTER_END" || it == "BEFORE_VERIFY") error("UI confirmation unavailable") }
            tap("保存"); waitFor("操作结果")
            expect(nodes().any { text(it).contains("提交状态尚未确认") })
            expect(harness.listItems().size == 2)
            tap("保留输入并返回")
            tap("保存"); waitFor("操作结果")
            expect(nodes().any { text(it).contains("请先重新读取核对") })
            expect(harness.listItems().size == 2)
            uiStore!!.writeFault = {}
            tap("重新读取核对"); waitFor("已重新核实数据库操作完成")
            expect(nodes().none { text(it).contains("新建加分条目") })
            expect(harness.listItems().size == 2)
            // Explicitly cancelling an uncertain form must not let a new draft get closed later
            // by the previous operation's completion callback.
            tap("记录活动"); type("活动名称", "取消未知表单后仍须核实")
            uiStore!!.writeFault = { if(it == "AFTER_END" || it == "BEFORE_VERIFY") error("UI confirmation unavailable") }
            tap("保存"); waitFor("操作结果")
            tap("保留输入并返回"); tap("取消")
            waitFor("上次操作尚未核实")
            expect(nodes().none { text(it).contains("记录活动") })
            expect(harness.listItems().size == 3)
            uiStore!!.writeFault = {}
            tap("重新读取核对"); waitFor("上次操作尚未核实", true)
            expect(harness.listItems().size == 3)
            results.putInt("failures", 0)
            results.putString("stream", "P1-09 Android data ui passed ($count checks), pid=${android.os.Process.myPid()}\n")
        } catch(error: Throwable) {
            File(context.cacheDir,"ui-failure-tree.txt").writeText(nodes().joinToString("\n") { "${it.className} editable=${it.isEditable} visible=${it.isVisibleToUser} text=${text(it)}" })
            results.putInt("failures", 1)
            results.putString("stream", "P1-09 UI failed after $count checks: ${error.stackTraceToString()}\n")
        } finally {
            instrumentation.runOnMainSync { activity?.finish(); MainActivity.openStore = originalFactory; MainActivity.draftContext = originalDraftContext }
            harness.close()
        }
        return results
    }
}
