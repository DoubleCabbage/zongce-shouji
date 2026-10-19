package cn.activityscore.app.tests

import android.app.Instrumentation
import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import cn.activityscore.app.*
import cn.activityscore.app.data.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Activity/Compose/SQLite with an app-private isolated context. Death phases use SIGKILL. */
class DraftChecks(private val instrumentation: Instrumentation) {
    private val base = instrumentation.targetContext
    private var count = 0
    private lateinit var activity: MainActivity
    private lateinit var uiStore: RecordStore
    private fun expect(ok: Boolean) { check(ok) { "P1-05 assertion ${count+1}" }; count++ }
    private fun fixture(reset: Boolean): Context {
        val root = base.getDir("draft-regression", 0)
        check(root.canonicalPath != base.filesDir.canonicalPath)
        if (reset) { root.deleteRecursively(); check(root.mkdirs()) }
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
            override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").also { it.mkdirs() }
            override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("p105-$name", mode)
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::collect) } }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::collect)
        if (result.any { text(it).contains("System UI isn't responding") }) {
            result.firstOrNull { it.text?.toString() == "Wait" }?.let {
                var button = it
                while (!button.isClickable && button.parent != null) button = button.parent
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                android.util.Log.w("P105UI", "Emulator System UI ANR interrupted test; selected Wait (not an app assertion)")
            }
        }
        return result
    }
    private fun text(n: AccessibilityNodeInfo) = "${n.text?.toString().orEmpty()} ${n.contentDescription?.toString().orEmpty()}"
    private fun waitFor(label: String, absent: Boolean = false, scroll: Boolean = false) {
        repeat(120) {
            if (nodes().any { text(it).contains(label) } != absent) return
            if (scroll && it % 4 == 0) scrollSmall()
            Thread.sleep(100)
        }
        error("UI missing $label absent=$absent: ${nodes().joinToString { text(it) }}")
    }
    private fun top() {
        nodes().filter { it.isEditable && it.isFocused }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
        repeat(12) {
            android.util.Log.i("P105UI", "top scroll candidates: " + nodes().filter { it.isScrollable }.joinToString { n ->
                "${n.className} ${android.graphics.Rect().also { n.getBoundsInScreen(it) }} ${n.actionList.map { it.id }}"
            })
            val moved = nodes().filter { it.isScrollable && !it.isEditable }.maxByOrNull { n ->
                android.graphics.Rect().also { n.getBoundsInScreen(it) }.height()
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            Thread.sleep(250)
            if (!moved) return
        }
    }
    private fun scrollSmall() {
        val node = nodes().firstOrNull { it.className == "android.widget.ScrollView" } ?: return
        if (node.packageName?.toString() != base.packageName) return
        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
        val appWindow = instrumentation.uiAutomation.windows.firstOrNull { it.id == node.windowId } ?: return
        val visible = android.graphics.Rect(); appWindow.getBoundsInScreen(visible)
        if (!rect.intersect(visible)) return
        val metrics = activity.resources.displayMetrics
        val top = maxOf(rect.top, 24)
        var bottom = minOf(rect.bottom, metrics.heightPixels - 24)
        instrumentation.uiAutomation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }.forEach {
            val keyboard = android.graphics.Rect(); it.getBoundsInScreen(keyboard); bottom = minOf(bottom,keyboard.top - 8)
        }
        if (bottom - top < 100) { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); return }
        val x = ((rect.left + rect.right) / 2).toFloat()
        // Keep synthetic swipes well inside the app window, away from gesture navigation.
        val start = top + (bottom - top) * 0.70f
        val distance = minOf((bottom-top)*0.30f,140f)
        val time = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val e = android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,x,y,0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(e,true)); e.recycle()
        }
        event(android.view.MotionEvent.ACTION_DOWN,start)
        repeat(6) { event(android.view.MotionEvent.ACTION_MOVE,start-distance*(it+1)/6); Thread.sleep(30) }
        Thread.sleep(150); event(android.view.MotionEvent.ACTION_UP,start-distance)
    }
    private fun locate(label: String, editable: Boolean = false, expected: String? = null): AccessibilityNodeInfo {
        fun field(n: AccessibilityNodeInfo): Boolean = !n.isEditable && text(n).trim().startsWith(label) ||
            (0 until n.childCount).any { n.getChild(it)?.let(::field) == true }
        var optionalAttempted = false
        repeat(40) {
            if (editable && !optionalAttempted && nodes().any { it.text?.toString() == "更多可选信息" }) {
                NativeUiTestRoutes.expandOptional(label, ::nodes); optionalAttempted = true
            }
            android.util.Log.i("P105UI", "locate $label step=$it: ${nodes().joinToString { text(it) }}")
            nodes().firstOrNull { if(editable) it.isEditable && (field(it) || expected != null && it.text?.toString() == expected)
                else it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            scrollSmall()
            Thread.sleep(300)
        }
        error("Missing field/button $label: ${nodes().joinToString { text(it) }}")
    }
    private fun tap(label: String) {
        if (NativeUiTestRoutes.beforeTap(label, ::tap, ::nodes)) return
        top(); var n = locate(label)
        while (!n.isClickable && n.parent != null) n = n.parent
        expect(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(180)
    }
    private fun type(label: String, value: String) {
        top(); val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        var accepted = false
        repeat(3) { if (!accepted) {
            accepted = locate(label, true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!accepted) Thread.sleep(300) // reacquire a node after Compose/layout invalidation
        } }
        expect(accepted); Thread.sleep(150)
        // ACTION_SET_TEXT is synthetic input; do not send Back based on a stale IME window.
        // Native scrolling works while the keyboard is visible. Real Back is tested separately.

    }
    private fun value(label: String, expected: String) { top(); expect(locate(label, true, expected).text?.toString() == expected) }
    private fun start(context: Context) {
        MainActivity.openStore = { RecordStore(context).also { uiStore = it } }
        MainActivity.draftContext = { context }
        activity = instrumentation.startActivitySync(Intent(base, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        repeat(100) {
            if (nodes().any { text(it).contains("活动") || text(it).contains("发现未完成的草稿") ||
                    text(it).contains("上次操作尚未核实") || text(it).contains("新建加分条目") }) return
            Thread.sleep(100)
        }
        error("App not ready")
    }
    private fun flush() {
        val done = CountDownLatch(1); var failure: Throwable? = null
        instrumentation.runOnMainSync { CoroutineScope(Dispatchers.Main).launch {
            try { activity.drafts.flush() } catch(e: Throwable) { failure=e } finally { done.countDown() }
        } }
        check(done.await(10, TimeUnit.SECONDS)); failure?.let { throw it }
    }
    private fun recreate(rotation: Boolean = false) {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val old = activity
        instrumentation.runOnMainSync {
            if (rotation) activity.requestedOrientation = if(activity.resources.configuration.orientation == 1) 0 else 1
            else activity.recreate()
        }
        activity = instrumentation.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity ?: error("Activity was not rebuilt")
        instrumentation.removeMonitor(monitor)
        expect(activity !== old)
        Thread.sleep(600)
        expect(nodes().none { text(it).contains("发现未完成的草稿") })
    }
    private fun seedDatabase(context: Context) = RecordStore(context).useFixture { store ->
        store.createItem("原活动", "劳动", "小类", "角色", 1.2345, "原备注", "2026-09-01", "原参与", emptyList())
    }
    private fun <T> RecordStore.useFixture(block: (RecordStore) -> T): T = try { block(this) } finally { close() }
    private fun kill(context: Context) {
        flush()
        File(context.filesDir, "seed-pid.txt").writeText(android.os.Process.myPid().toString())
        android.os.Process.killProcess(android.os.Process.myPid())
        Thread.sleep(5000); error("SIGKILL returned")
    }
    private fun storage(context: Context) = runBlocking {
        val disk = DraftDisk(context)
        val fields = mapOf("route/showItemEditor" to DraftCodec.encode(true),
            "item/new/scoreText" to DraftCodec.encode("1.2345000e-2"),
            "annual/2026/courses" to DraftCodec.encode(listOf(CourseDraft("中文课程", "2.000", "89.9999"))),
            "participation/new/images" to DraftCodec.encode(listOf(Uri.parse("content://missing/picture"))))
        disk.write(fields); expect(disk.read() == fields)
        val escaped = mapOf("item/new/note" to DraftCodec.encode("\"".repeat(160000)))
        disk.write(escaped); expect(disk.read() == escaped); disk.write(fields)
        expect(runCatching { DraftCodec.validate("route/showItemEditor", DraftCodec.encode("wrong type")) }.isFailure)
        val backup = RecordStore(context).useFixture { FullBackup.capture(context, it) }
        try { java.util.zip.ZipFile(backup).use { zip ->
            expect(zip.entries().asSequence().none { it.name.contains("draft",ignoreCase=true) || it.name.contains("no-backup") })
        } } finally { FullBackup.discard(backup) }
        expect((DraftCodec.decode(fields.getValue("annual/2026/courses")) as List<*>).single() == CourseDraft("中文课程", "2.000", "89.9999"))
        val path = File(context.noBackupFilesDir,"form-drafts-v1.json")
        val before = path.readBytes()
        expect(runCatching { disk.write(mapOf("item/new/note" to DraftCodec.encode("字".repeat(400000)))) }.isFailure)
        expect(path.readBytes().contentEquals(before))
        // Simulate an interrupted AtomicFile .new; the previous committed document remains valid.
        File(path.path+".new").writeText("partial")
        expect(disk.read() == fields)
        path.writeText("{broken")
        expect(runCatching { disk.read() }.isFailure)
        expect(RecordStore(context).useFixture { it.listItems().single().title } == "原活动")
        disk.write(emptyMap())
        expect(disk.read().isEmpty())
        // A real rename obstruction: AtomicFile.finishWrite only logs this failure on API 36.
        // The pending barrier and body must report failure rather than letting SQL begin.
        for (name in listOf("draft-write-pending", "form-drafts-v1.json")) {
            val folder = File(context.cacheDir,"blocked-$name").also { it.mkdirs() }
            val blocked = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
            File(folder,name).also { it.mkdirs(); File(it,"obstruction").writeText("fixture") }
            expect(runCatching { DraftDisk(blocked).write(mapOf("route/evidencePending" to DraftCodec.encode("pending"))) }.isFailure)
            if (name == "draft-write-pending") expect(!File(folder,"form-drafts-v1.json").exists())
            else expect(DraftDisk(blocked).pendingExists())
        }
    }
    private fun savedState(context: Context) {
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var first: DraftState
        instrumentation.runOnMainSync { first = DraftState(context); store.put("first",first) }
        repeat(100) { if (!first.ready) Thread.sleep(50) }
        check(first.ready)
        lateinit var bundle: Bundle
        instrumentation.runOnMainSync {
            first.state("route/showItemEditor") { false }.value = true
            first.state("item/new/scoreText") { "" }.value = "1.234500000"
            bundle = first.saveInstance()
            first.state("item/new/scoreText") { "" }.value = "字".repeat(400000)
            expect(first.state("item/new/scoreText") { "" }.value == "1.234500000")
            expect(first.error.contains("未接受"))
            store.clear() // cancel debounce: the system bundle is the only source of the latest edit
        }
        val secondStore = androidx.lifecycle.ViewModelStore()
        lateinit var second: DraftState
        instrumentation.runOnMainSync { second = DraftState(context,bundle); secondStore.put("second",second) }
        repeat(100) { if (!second.ready) Thread.sleep(50) }
        instrumentation.runOnMainSync {
            expect(second.ready && !second.offerRecovery)
            expect(second.state("item/new/scoreText") { "" }.value == "1.234500000")
            secondStore.clear()
        }
        val json = org.json.JSONObject(requireNotNull(bundle.getString("fields")))
        json.put("route/showItemEditor",DraftCodec.encode(false))
        json.put("meta/revision",DraftCodec.encode(100L))
        val closed = json.keys().asSequence().associateWith { json.getString(it) }
        runBlocking { DraftDisk(context).write(closed) }
        val thirdStore = androidx.lifecycle.ViewModelStore()
        lateinit var third: DraftState
        instrumentation.runOnMainSync { third = DraftState(context,bundle); thirdStore.put("third",third) }
        repeat(100) { if (!third.ready) Thread.sleep(50) }
        instrumentation.runOnMainSync {
            expect(third.ready && !third.offerRecovery)
            expect(!third.state("route/showItemEditor") { false }.value)
            thirdStore.clear()
        }
        // Cold start from a CLOSED disk revision, then a new edit reaches only system state.
        // It must preserve that disk session lineage and accept the newer saved Bundle.
        val fourthStore = androidx.lifecycle.ViewModelStore(); lateinit var fourth: DraftState
        lateinit var newBundle: Bundle
        instrumentation.runOnMainSync { fourth = DraftState(context); fourthStore.put("fourth",fourth) }
        repeat(100) { if (!fourth.ready) Thread.sleep(50) }
        instrumentation.runOnMainSync {
            check(fourth.ready)
            fourth.state("route/showItemEditor") { false }.value = true
            fourth.state("item/new/scoreText") { "" }.value = "9.87650000"
            newBundle = fourth.saveInstance(); fourthStore.clear()
        }
        val fifthStore = androidx.lifecycle.ViewModelStore(); lateinit var fifth: DraftState
        instrumentation.runOnMainSync { fifth = DraftState(context,newBundle); fifthStore.put("fifth",fifth) }
        repeat(100) { if (!fifth.ready) Thread.sleep(50) }
        instrumentation.runOnMainSync {
            expect(fifth.ready && !fifth.offerRecovery && fifth.state("route/showItemEditor") { false }.value)
            expect(fifth.state("item/new/scoreText") { "" }.value == "9.87650000")
            fifthStore.clear()
        }
    }
    private fun lifecycle(context: Context) {
        seedDatabase(context); start(context)
        tap("记录活动"); type("活动名称", "旋转草稿"); type("历史参考分数", "1.2345000e-2")
        type("条目备注", "保留空格与原始精度  ")
        recreate(true); value("活动名称", "旋转草稿"); value("历史参考分数", "1.2345000e-2")
        recreate(); value("条目备注", "保留空格与原始精度  ")
        tap("添加证明图片（已选 0 张）")
        Thread.sleep(700); instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitFor("添加证明图片"); value("活动名称", "旋转草稿")
        recreate(true); value("历史参考分数", "1.2345000e-2")
        tap("取消"); tap("活动"); tap("原活动"); tap("修改条目")
        type("条目备注", "未保存编辑"); recreate(); value("历史参考分数", "1.2345"); value("条目备注", "未保存编辑")
        expect(RecordStore(context).useFixture { it.listItems().single().note } == "原备注")
        tap("取消"); tap("再参加一次"); type("这次参与的内容", "参与草稿")
        recreate(true); value("这次参与的内容", "参与草稿")
        recreate(true); value("这次参与的内容", "参与草稿")
        tap("取消"); tap("返回活动"); tap("首页"); tap("录入学年成绩")
        type("学生自评", "80.00000"); tap("添加课程"); type("课程名称", "数学草稿")
        type("学分", "2.0000"); type("成绩", "90.12345")
        expect(activity.drafts.state("annual/2026/courses") { emptyList<CourseDraft>() }.value.single() == CourseDraft("数学草稿","2.0000","90.12345"))
        recreate(true); value("课程名称", "数学草稿"); value("学分", "2.0000"); value("成绩", "90.12345")
        recreate(true); value("课程名称", "数学草稿")
        tap("取消")
        val id = RecordStore(context).useFixture { it.listItems().single().id }
        RecordStore(context).useFixture { it.addParticipation(id,"2026-09-02","重复",emptyList()) }
        recreate(); tap("活动"); tap("原活动"); tap("核对计分"); tap("分别计分")
        type("第 1 次", "1.230000"); type("第 2 次", "4.560000")
        recreate(true); value("第 1 次", "1.230000"); value("第 2 次", "4.560000")
        tap("丢弃本次输入，稍后核对"); flush()
        expect(activity.drafts.error.isBlank())
    }
    private fun historyUi(context: Context) {
        val csv = File(context.cacheDir,"draft.csv").apply { writeText("活动名称,所属维度,参考分数,职务\n测试,劳动,1.2345,参与者\n") }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(csv.readBytes()).joinToString("") { "%02x".format(it) }
        runBlocking { DraftDisk(context).write(mapOf(
            "route/tab" to DraftCodec.encode(1), "route/historyImport" to DraftCodec.encode(true),
            "history/sourceUri" to DraftCodec.encode(Uri.fromFile(csv).toString()),
            "history/sourceHash" to DraftCodec.encode(digest), "history/sheetName" to DraftCodec.encode("CSV"),
            "history/headerRowText" to DraftCodec.encode("01"), "history/mapping" to DraftCodec.encode(ColumnMapping(0,1,2,3,-1))
        )) }
        start(context); tap("继续编辑"); waitFor("检查所选工作表", scroll = true)
        value("表头所在行","01"); tap("检查所选工作表"); waitFor("检查结果", scroll = true)
        recreate(); waitFor("检查所选工作表", scroll = true); value("表头所在行","01")
        tap("全不选"); recreate(); waitFor("选择要合并的工作表（已选 0 张）", scroll = true)
        expect(activity.drafts.state("history/selectedSheets") { emptySet<String>() }.value.isEmpty())
        expect(uiStore.listHistory().isEmpty())
        csv.writeText("活动名称,所属维度,参考分数\n变化,劳动,99\n")
        recreate(); waitFor("源文件已变化", scroll = true); expect(uiStore.listHistory().isEmpty())
        tap("取消"); flush()
    }
    private fun packageUi(context: Context) {
        val zip = File(context.cacheDir,"draft.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            out.write(org.json.JSONObject().put("format","cn.activityscore.shared-activity").put("version",1)
                .put("shareId","01234567-1234-1234-1234-012345678901").put("title","待确认包").put("dimension","劳动")
                .put("subcategory","").put("referenceScore",1.2345).put("note","").put("eventDate","2026-09-01")
                .put("eventNote","").put("photos",org.json.JSONArray()).toString().toByteArray())
            out.closeEntry()
        }
        runBlocking { DraftDisk(context).write(mapOf(
            "route/packageUri" to DraftCodec.encode(Uri.fromFile(zip).toString()),
            "package/01234567-1234-1234-1234-012345678901/role" to DraftCodec.encode("未确认角色"),
            "package/01234567-1234-1234-1234-012345678901/scoreText" to DraftCodec.encode("1.2345000"),
            "package/01234567-1234-1234-1234-012345678901/participated" to DraftCodec.encode(true)
        )) }
        start(context); tap("继续编辑"); waitFor("待确认包")
        value("我的职务","未确认角色"); value("我的参考估分","1.2345000")
        recreate(); waitFor("待确认包"); value("我的参考估分","1.2345000")
        expect(uiStore.listItems().isEmpty()); tap("取消"); flush()
    }
    private fun unknownNewActivity(context: Context) {
        start(context); tap("记录活动"); type("活动名称","旧 Activity 的未知保存")
        uiStore.writeFault = { if(it == "AFTER_END" || it == "BEFORE_VERIFY") error("receipt unavailable") }
        tap("保存"); waitFor("操作结果"); tap("保留输入并返回"); flush()
        expect(uiStore.listItems().size == 1)
        instrumentation.runOnMainSync { activity.finish() }
        Thread.sleep(600)
        start(context)
        if (nodes().any { text(it).contains("发现未完成的草稿") }) tap("继续编辑")
        waitFor("新建加分条目")
        tap("保存"); waitFor("操作结果"); tap("重新读取核对")
        waitFor("已重新核实数据库操作完成"); waitFor("新建加分条目",true)
        expect(uiStore.listItems().size == 1); flush()
    }
    private fun saveRecreate(context: Context) {
        start(context); tap("记录活动"); type("活动名称","保存中重建")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        uiStore.writeFault = { if(it == "BEFORE_COMMIT") { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) } }
        tap("保存"); check(entered.await(5,TimeUnit.SECONDS))
        Thread { Thread.sleep(1000); release.countDown() }.start()
        recreate(); waitFor("记录已保存"); waitFor("新建加分条目",true)
        expect(uiStore.listItems().size == 1); flush()
    }
    private fun annualTarget(context: Context) {
        RecordStore(context).useFixture { store ->
            store.saveAnnualInputs(cn.activityscore.app.domain.AnnualInputs(2025,10.0,20.0,30.0,0.0,40.0,50.0,
                listOf(cn.activityscore.app.domain.CourseGrade("目标学年课程",2.0,80.0))))
            store.saveAnnualInputs(cn.activityscore.app.domain.AnnualInputs(2026,90.0,90.0,90.0,0.0,90.0,90.0,
                listOf(cn.activityscore.app.domain.CourseGrade("其他学年课程",3.0,90.0))))
        }
        runBlocking { DraftDisk(context).write(mapOf(
            "route/selectedYear" to DraftCodec.encode(2025), "route/showAnnualEditor" to DraftCodec.encode(true),
            "annual/2025/self" to DraftCodec.encode("12.345000")
        )) }
        start(context); tap("继续编辑"); waitFor("2025—2026 学年成绩资料")
        value("学生自评","12.345000"); value("班级同学互评",NumericValues.formatForEdit(20.0))
        value("课程名称","目标学年课程"); recreate(); value("课程名称","目标学年课程")
        expect(uiStore.annualInputs(2025)!!.selfEvaluation == 10.0); tap("取消"); flush()
    }
    private fun participationTarget(context: Context) {
        val original = seedDatabase(context)
        val other = RecordStore(context).useFixture { store ->
            val id = store.createItem("另一活动","劳动","","",1.0,"","2026-09-01","",emptyList())
            store.withBackupDatabase { it.execSQL("UPDATE score_items SET created_at=(SELECT created_at FROM score_items WHERE id=?) WHERE id=?",arrayOf(original,id)) }
            id
        }
        start(context); tap("活动"); tap("原活动"); tap("再参加一次"); type("这次参与的内容","目标始终是原活动")
        instrumentation.runOnMainSync { activity.drafts.state<Long?>("route/selectedItemId") { null }.value = other }
        recreate(); value("这次参与的内容","目标始终是原活动")
        tap("保存"); waitFor("另一活动")
        expect(uiStore.listParticipations(original).any { it.note == "目标始终是原活动" })
        expect(uiStore.listParticipations(other).size == 1); flush()
    }
    fun run(phase: String): Bundle {
        val result = Bundle()
        val reset = phase in listOf("suite","ui","history-ui","package-ui","corrupt-ui","unknown-new-activity","save-recreate","annual-target","participation-target") || phase.startsWith("kill-") || phase.startsWith("seed-")
        val context = fixture(reset)
        val oldFactory = MainActivity.openStore; val oldDraftContext = MainActivity.draftContext
        try {
            val automationInfo = instrumentation.uiAutomation.serviceInfo
            automationInfo.flags = automationInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrumentation.uiAutomation.serviceInfo = automationInfo
            when {
                phase == "suite" -> { seedDatabase(context); storage(context); savedState(context) }
                phase == "ui" -> lifecycle(context)
                phase == "history-ui" -> historyUi(context)
                phase == "package-ui" -> packageUi(context)
                phase == "unknown-new-activity" -> unknownNewActivity(context)
                phase == "save-recreate" -> saveRecreate(context)
                phase == "annual-target" -> annualTarget(context)
                phase == "participation-target" -> participationTarget(context)
                phase == "corrupt-ui" -> {
                    seedDatabase(context)
                    runBlocking { DraftDisk(context).write(mapOf("route/evidencePending" to DraftCodec.encode("pending"))) }
                    File(context.noBackupFilesDir,"form-drafts-v1.json").writeText("{broken")
                    start(context); waitFor("上次操作尚未核实")
                    expect(uiStore.listItems().single().title == "原活动")
                    tap("重新读取核对"); waitFor("上次保存需要人工核对")
                    expect(uiStore.listItems().size == 1)
                }
                phase.startsWith("kill-") || phase.startsWith("seed-") -> {
                    val kind = phase.substringAfter('-')
                    if (kind in listOf("edit","participation","annual","deleted")) seedDatabase(context)
                    start(context)
                    when (kind) {
                        "create", "before", "committed", "failure" -> {
                            tap("记录活动"); type("活动名称", "进程草稿"); type("历史参考分数", "1.2345000e-2")
                            if (kind == "failure") {
                                uiStore.withBackupDatabase { it.execSQL("CREATE TRIGGER draft_failure BEFORE INSERT ON score_items BEGIN SELECT RAISE(ABORT,'fixture'); END") }
                                tap("保存"); waitFor("操作结果"); tap("保留输入并返回")
                            } else if (kind != "create") {
                                File(context.filesDir,"seed-pid.txt").writeText(android.os.Process.myPid().toString())
                                uiStore.writeFault = { stage -> if(stage == if(kind == "before") "BEFORE_COMMIT" else "AFTER_END") android.os.Process.killProcess(android.os.Process.myPid()) }
                                tap("保存"); Thread.sleep(5000); error("Write death not reached")
                            }
                        }
                        "edit", "deleted" -> {
                            tap("活动"); tap("原活动"); tap("修改条目"); type("条目备注", "跨进程编辑")
                            if(kind == "deleted") RecordStore(context).useFixture { it.deleteItem(it.listItems().single().id) }
                        }
                        "participation" -> {
                            tap("活动"); tap("原活动"); tap("再参加一次"); type("这次参与的内容", "跨进程参与")
                            instrumentation.runOnMainSync { activity.drafts.state("participation/new/images") { emptyList<Uri>() }.value = listOf(Uri.parse("content://missing/picture")) }
                        }
                        "annual" -> {
                            tap("录入学年成绩"); tap("添加课程"); type("课程名称", "跨进程课程"); type("学分", "2.0000"); type("成绩", "90.12345")
                        }
                        else -> error("Unknown phase")
                    }
                    flush()
                    if (phase.startsWith("kill-")) kill(context)
                    File(context.filesDir,"seed-pid.txt").writeText(android.os.Process.myPid().toString())
                }
                phase.startsWith("verify-") -> {
                    val kind = phase.substringAfter('-')
                    expect(File(context.filesDir,"seed-pid.txt").readText().toInt() != android.os.Process.myPid())
                    start(context); waitFor("发现未完成的草稿"); tap("继续编辑")
                    when(kind) {
                        "create", "failure" -> {
                            waitFor("新建加分条目"); value("活动名称","进程草稿"); value("历史参考分数","1.2345000e-2"); expect(uiStore.listItems().isEmpty())
                            if (kind == "failure") { tap("保存"); waitFor("操作结果"); expect(uiStore.listItems().isEmpty()) }
                        }
                        "edit" -> { waitFor("修改加分条目"); value("条目备注","跨进程编辑"); expect(uiStore.listItems().single().note == "原备注") }
                        "deleted" -> { waitFor("所属活动已不存在"); value("条目备注","跨进程编辑"); tap("保存"); expect(uiStore.listItems().isEmpty()) }
                        "participation" -> { waitFor("添加一次参与"); value("这次参与的内容","跨进程参与"); waitFor("所选文件已无法读取"); expect(uiStore.listParticipations(uiStore.listItems().single().id).size == 1) }
                        "annual" -> { waitFor("学年成绩资料"); value("课程名称","跨进程课程"); value("学分","2.0000"); value("成绩","90.12345"); expect(uiStore.annualInputs(2026) == null)
                            expect(activity.drafts.state("annual/2026/courses") { emptyList<CourseDraft>() }.value.single() == CourseDraft("跨进程课程","2.0000","90.12345")) }
                        "before", "committed" -> {
                            waitFor("新建加分条目"); value("活动名称","进程草稿")
                            val expected = if(kind == "committed") 1 else 0
                            expect(uiStore.listItems().size == expected)
                            tap("保存"); waitFor("操作结果"); expect(uiStore.listItems().size == expected)
                            tap("重新读取核对"); waitFor("上次保存需要人工核对")
                            expect(uiStore.listItems().size == expected)
                            tap("查看当前记录"); waitFor("返回上次输入")
                            tap("重新读取记录"); waitFor("上次保存需要人工核对")
                            tap("已核对，丢弃上次输入"); flush(); expect(uiStore.listItems().size == expected)
                        }
                        else -> error("Unknown verify")
                    }
                }
                phase == "discard" -> { start(context); waitFor("发现未完成的草稿"); tap("丢弃草稿"); flush() }
                phase == "empty-restart" -> { start(context); expect(nodes().none { text(it).contains("发现未完成的草稿") }); expect(uiStore.listItems().isEmpty()) }
                phase == "save" -> { start(context); waitFor("发现未完成的草稿"); tap("继续编辑"); tap("保存"); waitFor("记录已保存"); flush(); expect(uiStore.listItems().size == 1) }
                phase == "saved-restart" -> { start(context); expect(nodes().none { text(it).contains("发现未完成的草稿") }); expect(uiStore.listItems().size == 1) }
                phase == "pending-discarded-restart" -> {
                    start(context); waitFor("上次操作尚未核实")
                    expect(nodes().none { text(it).contains("发现未完成的草稿") })
                    expect(uiStore.listItems().size == 1)
                    tap("重新读取核对"); waitFor("上次保存需要人工核对")
                    expect(uiStore.listItems().size == 1)
                }
                else -> error("Unknown phase $phase")
            }
            result.putInt("failures",0)
            result.putString("stream","P1-05 Android draft $phase passed ($count checks), pid=${android.os.Process.myPid()}\n")
        } catch (error: Throwable) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir,"draft-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            File(context.cacheDir,"draft-failure-tree.txt").writeText(nodes().joinToString("\n") { "${it.className} editable=${it.isEditable} ${text(it)}" })
            result.putInt("failures",1); result.putString("stream","P1-05 $phase failed after $count checks: ${error.stackTraceToString()}\n")
        } finally {
            instrumentation.runOnMainSync {
                if (::activity.isInitialized) activity.finish()
                MainActivity.openStore = oldFactory; MainActivity.draftContext = oldDraftContext
            }
        }
        return result
    }
}
