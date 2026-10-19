package cn.activityscore.app.tests

import android.app.Instrumentation
import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import cn.activityscore.app.*
import cn.activityscore.app.data.*
import java.io.File

/** Real native UI, isolated production SQLite and real images. Never uses the normal store. */
class MigrationUiChecks(private val ins: Instrumentation) {
    private var count = 0
    private lateinit var activity: MainActivity
    private fun expect(ok: Boolean) { check(ok) { "Migration assertion ${count + 1}" }; count++ }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo) { result += n; repeat(n.childCount) { n.getChild(it)?.let(::collect) } }
        ins.uiAutomation.rootInActiveWindow?.let(::collect)
        if (result.any { label(it).contains("System UI isn't responding") }) {
            result.firstOrNull { it.text?.toString() == "Wait" }?.let {
                var button = it
                while (!button.isClickable && button.parent != null) button = button.parent
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                android.util.Log.w("MigrationUI", "Emulator System UI ANR interrupted test; selected Wait, not an app assertion")
            }
        }
        return result
    }
    private fun label(n: AccessibilityNodeInfo) = "${n.text?.toString().orEmpty()} ${n.contentDescription?.toString().orEmpty()}"
    private fun waitFor(text: String, absent: Boolean = false) {
        repeat(150) { if (nodes().any { label(it).contains(text) } != absent) return; Thread.sleep(100) }
        error("UI expected $text absent=$absent: ${nodes().joinToString { label(it) }}")
    }
    private fun waitEnabled(text: String) {
        var settled = 0
        repeat(150) {
            var n = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            while (n != null && !n.isClickable && n.parent != null) n = n.parent
            if (n?.isEnabled == true && !EvidenceOperation.busy) settled++ else settled = 0
            if (settled >= 3) return
            Thread.sleep(100)
        }
        error("Control did not become enabled: $text")
    }
    private fun top() { repeat(12) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(60) } }
    private fun find(text: String, editable: Boolean = false): AccessibilityNodeInfo {
        fun field(n: AccessibilityNodeInfo): Boolean = label(n).contains(text) || (0 until n.childCount).any { n.getChild(it)?.let(::field) == true }
        repeat(30) {
            nodes().firstOrNull { if (editable) it.isEditable && field(it) else if (text.startsWith("ui-migration-proof-")) label(it).contains(text) && !label(it).contains("Preview the file") else it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(120)
        }
        error("Missing $text: ${nodes().joinToString { label(it) }}")
    }
    private fun tap(text: String) {
        top(); val candidate = find(text); var n = candidate
        while (!n.isClickable && n.parent != null) n = n.parent
        var clicked = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked && candidate.isVisibleToUser) {
            // DocumentsUI exposes some file tiles without ACTION_CLICK. Tap the observed tile.
            val rect = android.graphics.Rect(); candidate.getBoundsInScreen(rect)
            android.util.Log.i("MigrationUI", "Observed tile $text bounds=$rect class=${candidate.className}")
            val time = android.os.SystemClock.uptimeMillis()
            fun inject(action: Int, at: Long): Boolean {
                val event = android.view.MotionEvent.obtain(time, at, action, rect.exactCenterX(), rect.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                return try { ins.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
            }
            clicked = inject(0, time) && inject(1, time + 30)
        }
        expect(clicked); Thread.sleep(250)
    }
    private fun type(text: String, value: String) {
        top(); val n = find(text, true)
        expect(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })); Thread.sleep(200)
        if (ins.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            ins.sendKeyDownUpSync(4); Thread.sleep(200)
        }
    }
    private fun screenshot(context: Context, name: String) {
        ins.waitForIdleSync(); Thread.sleep(300)
        val bitmap = requireNotNull(ins.uiAutomation.takeScreenshot())
        File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun historySuggestions(context: Context, db: RecordStore) {
        fun waitValue(label: String, expected: String) {
            repeat(100) {
                if (find(label, true).text?.toString() == expected) { count++; return }
                Thread.sleep(100)
            }
            error("Field $label did not become $expected")
        }
        val candidate = "2025年校园合唱晚会 · 美育"
        tap("记录活动"); waitFor("新建加分条目")
        type("活动名称", "合")
        waitFor("历史相似活动（仅供参考）", absent = true)
        type("活动名称", "2027年校园合唱晚会")
        waitFor(candidate)
        waitFor("更多可选信息")
        screenshot(context, "history-new-suggestions")
        tap(candidate); waitFor("选择职务 / 等级后带入参考分数")
        waitFor("历史相似活动（仅供参考）", absent = true)
        waitFor("收起可选信息")
        tap("演员 · 2 分")
        waitValue("历史参考分数", "2.0")
        tap("保存"); waitFor("活动详情")
        val created = db.listItems().single { it.title == "2025年校园合唱晚会" }
        expect(created.dimension == "美育" && created.role == "演员" && created.referenceScore == 2.0)
        expect(created.subcategory == "校园文化" && db.listParticipations(created.id).size == 1)

        // Opening an editor must offer candidates without applying any of their values.
        tap("修改条目"); waitFor(candidate)
        waitValue("职务 / 获奖等级", "演员")
        waitValue("历史参考分数", "2.0")
        tap("收起可选信息"); waitFor(candidate)
        screenshot(context, "history-edit-suggestions")
        type("活动名称", "2028年校园合唱晚会")
        waitFor(candidate)
        type("活动名称", "")
        waitFor("历史相似活动（仅供参考）", absent = true)
        tap("取消"); waitFor("活动详情")
        expect(db.listItems().single { it.id == created.id } == created)

        tap("修改条目"); waitFor(candidate); tap(candidate)
        tap("观众 · 0 分")
        waitValue("历史参考分数", "0.0")
        screenshot(context, "history-selected-role")
        tap("保存"); waitFor("活动详情")
        val edited = db.listItems().single { it.id == created.id }
        expect(edited.role == "观众" && edited.referenceScore == 0.0)
        expect(db.listParticipations(created.id).size == 1 && db.listHistory().size == 2)
    }
    fun run(phase: String): Bundle {
        val out = Bundle(); val originalFactory = MainActivity.openStore; val originalDraft = MainActivity.draftContext
        var store: RecordStore? = null
        var pickerFixture: Uri? = null
        try {
            val base = ins.targetContext
            val root = base.getDir("ui-compose-migration", 0)
            check(root.name == "app_ui-compose-migration" && root.canonicalFile.parentFile == base.filesDir.canonicalFile.parentFile)
            check(root.deleteRecursively() && root.mkdirs())
            val context = object : ContextWrapper(base) {
                override fun getDatabasePath(name: String) = File(root, name)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
                override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
                override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
                override fun getNoBackupFilesDir() = File(root, "drafts").also { it.mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("ui-migration-$name", mode)
            }
            val db = RecordStore(context); store = db
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(140, 150, 185)) }
            val png = File(context.cacheDir, "test-proof.png")
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val png2 = File(context.cacheDir, "second-test-proof.png")
            Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(96, 150, 130))
                png2.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
            }
            val id = db.createItem("迁移集成测试活动：长名称与真实附件、学年及原生交互", "劳动", "", "参与者", 0.5, "原备注", "2026-10-01", "首次参与，真实图片", listOf(Uri.fromFile(png), Uri.fromFile(png2)))
            db.addParticipation(id, "2026-10-03", "第二次参与", emptyList())
            db.createItem("单次已确认测试", "学术创新", "", "", 2.0, "", "2026-10-02", "", emptyList())
            db.createItem("上学年记录", "美育", "", "", null, "", "2025-10-01", "", emptyList())
            if (phase == "history-suggestions") db.replaceHistory("history-suggestions.csv", listOf(
                HistoryRow(sourceRow = 2, activityName = "2025年校园合唱晚会", dimension = "美育", subcategory = "校园文化", role = "演员", referenceScore = 2.0),
                HistoryRow(sourceRow = 3, activityName = "2025年校园合唱晚会", dimension = "美育", subcategory = "校园文化", role = "观众", referenceScore = 0.0),
            ))
            MainActivity.openStore = { RecordStore(context) }; MainActivity.draftContext = { context }
            activity = ins.startActivitySync(Intent(base, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("首页")
            if (phase == "history-suggestions") {
                historySuggestions(context, db)
                out.putInt("failures", 0)
                out.putString("stream", "History suggestions passed ($count checks), isolated create/edit UI and persistence\n")
                return out
            }
            screenshot(context, "home")
            tap("活动"); waitFor("搜索活动名称"); screenshot(context, "activities")
            if (phase.startsWith("capture")) {
                tap("迁移集成测试活动：长名称与真实附件、学年及原生交互"); waitFor("活动详情"); screenshot(context, "detail")
                ins.sendKeyDownUpSync(4); waitFor("搜索活动名称"); tap("整理"); waitFor("整理与导出"); Thread.sleep(800); screenshot(context, "organize")
                out.putInt("failures", 0); out.putString("stream", "Compose migration $phase passed ($count checks), four emulator captures\n")
                return out
            }
            type("搜索活动名称", "迁移集成")
            waitFor("按最近参与排序 · 1 项")
            expect(nodes().none { label(it).contains("单次已确认测试") })
            tap("迁移集成测试活动：长名称与真实附件、学年及原生交互"); waitFor("活动详情"); screenshot(context, "detail")
            ins.sendKeyDownUpSync(4); waitFor("搜索活动名称")
            tap("迁移集成测试活动：长名称与真实附件、学年及原生交互")
            tap("再参加一次"); waitFor("添加一次参与")
            type("这次参与的内容", "旋转后保存参与")
            tap("添加证明图片（已选 0 张）"); waitFor("Show roots")
            ins.sendKeyDownUpSync(4); waitFor("添加一次参与")
            expect(find("这次参与的内容", true).text.toString().contains("旋转后保存参与"))
            val fixtureName = "ui-migration-proof-${System.currentTimeMillis()}.png"
            pickerFixture = requireNotNull(base.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fixtureName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                }))
            base.contentResolver.openOutputStream(pickerFixture!!).use { output -> requireNotNull(output).write(png.readBytes()) }
            tap("添加证明图片（已选 0 张）"); waitFor("Show roots")
            if (nodes().any { it.contentDescription?.toString() == "List view" }) { tap("List view"); waitFor("Grid view") }
            tap(fixtureName); waitFor("已选 1 张")
            screenshot(context, "participation-sheet")
            ins.runOnMainSync { activity.recreate() }; Thread.sleep(1500)
            waitFor("添加一次参与"); expect(find("这次参与的内容", true).text.toString().contains("旋转后保存参与"))
            tap("保存"); waitFor("旋转后保存参与")
            expect(db.listParticipations(id).size == 3)
            expect(db.listParticipations(id).sumOf { it.evidence.size } == 3)
            // Each actionable proof exposes the actual date, visit and proof ID, even with multiple images.
            top()
            val proofVisit = db.listParticipations(id).first { it.note == "旋转后保存参与" }
            val proof = proofVisit.evidence.single()
            val proofLabel = "${proofVisit.eventDate}，参与 ${proofVisit.id}，证明 ${proof.id}"
            tap(proofLabel); waitFor("证明 ${proof.id} · 参与 ${proofVisit.id}")
            tap("移除"); waitFor("删除证明 ${proof.id}？"); tap("取消")
            expect(db.listParticipations(id).sumOf { it.evidence.size } == 3)

            // Direct replacement uses the original guarded controller and a real system picker.
            tap(proofLabel); tap("替换"); waitFor("Show roots")
            ins.sendKeyDownUpSync(4); waitFor("已取消选择，原证明未更改")
            waitFor("检查完成，未发现异常证明"); waitEnabled("关闭"); tap("关闭"); waitFor("活动详情")
            expect(db.listParticipations(id).flatMap { it.evidence }.single { it.id == proof.id }.fileName == proof.fileName)
            tap(proofLabel); tap("替换"); waitFor("Show roots")
            if (nodes().any { it.contentDescription?.toString() == "List view" }) { tap("List view"); waitFor("Grid view") }
            tap(fixtureName); waitFor("确认替换证明")
            expect(db.listParticipations(id).flatMap { it.evidence }.single { it.id == proof.id }.fileName == proof.fileName)
            tap("确认替换"); waitFor("证明 ${proof.id} 已替换")
            waitFor("检查完成，未发现异常证明"); waitEnabled("关闭"); tap("关闭"); waitFor("活动详情")
            val replaced = db.listParticipations(id).flatMap { it.evidence }.single { it.id == proof.id }
            expect(replaced.fileName != proof.fileName && db.listParticipations(id).sumOf { it.evidence.size } == 3)
            EvidenceImages.validate(db.evidenceFile(replaced.fileName))
            // Remove the first of two proofs on one participation and open the retained proof.
            val firstVisit = db.listParticipations(id).single { it.note == "首次参与，真实图片" }
            val removed = firstVisit.evidence.first(); val kept = firstVisit.evidence.last()
            tap("${firstVisit.eventDate}，参与 ${firstVisit.id}，证明 ${removed.id}")
            tap("移除"); tap("确认删除"); waitFor("证明图片已移除")
            expect(db.listParticipations(id).sumOf { it.evidence.size } == 2)
            expect(db.listParticipations(id).flatMap { it.evidence }.none { it.id == removed.id })
            tap("${firstVisit.eventDate}，参与 ${firstVisit.id}，证明 ${kept.id}")
            waitFor("证明 ${kept.id} · 参与 ${firstVisit.id}"); tap("关闭")
            tap("修改条目"); type("条目备注", "UI编辑真实持久化")
            tap("保存"); waitFor("UI编辑真实持久化"); expect(db.listItems().first { it.id == id }.note == "UI编辑真实持久化")
            tap("删除条目"); waitFor("删除活动？"); tap("取消"); expect(db.listItems().any { it.id == id })
            tap("核对计分"); waitFor("分别计分"); tap("分别计分"); tap("保存核对进度")
            // Save-progress label is intentionally allowed to leave empty per-visit scores.
            waitFor("重复记录核对")
            tap("关闭"); waitFor("活动详情"); tap("返回活动"); waitFor("搜索活动名称")
            if (phase != "stage1") {
                tap("整理"); waitFor("整理与导出"); screenshot(context, "organize")
            }
            tap("首页"); tap("记录活动"); type("活动名称", "UI空参考创建并打开详情")
            tap("保存"); waitFor("活动详情")
            val created = db.listItems().single { it.title == "UI空参考创建并打开详情" }
            expect(created.referenceScore == null && db.listParticipations(created.id).single().evidence.isEmpty())
            tap("删除条目"); tap("确认删除"); waitFor("记录活动")
            expect(db.listItems().none { it.id == created.id } && db.listItems().any { it.id == id })
            screenshot(context, "final-state")
            out.putInt("failures", 0); out.putString("stream", "Compose migration $phase passed ($count checks), isolated screenshots app_ui-compose-migration/cache\n")
        } catch (e: Throwable) {
            File(ins.targetContext.getDir("ui-compose-migration", 0), "cache/failure-tree.txt").writeText(nodes().joinToString("\n") { "${it.className} editable=${it.isEditable} ${label(it)}" })
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(ins.targetContext.getDir("ui-compose-migration", 0), "cache/failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            out.putInt("failures", 1); out.putString("stream", "Compose migration failed after $count checks: ${e.stackTraceToString()}\n")
        } finally {
            ins.runOnMainSync { if (::activity.isInitialized) activity.finish(); MainActivity.openStore = originalFactory; MainActivity.draftContext = originalDraft }
            store?.close()
            pickerFixture?.let { ins.targetContext.contentResolver.delete(it, null, null) }
        }
        return out
    }
}
