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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.*

/** Dedicated app_workbook-import-regression directory; never touches the user's database. */
class WorkbookImportChecks(private val ins: Instrumentation) {
    private var assertions = 0
    private fun expect(value: Boolean) { check(value) { "Workbook assertion ${assertions + 1}" }; assertions++ }
    private fun fixture(): Context {
        val base = ins.targetContext
        val root = base.getDir("workbook-import-regression", 0)
        check(root.parentFile!!.canonicalFile == base.filesDir.parentFile!!.canonicalFile && root.name == "app_workbook-import-regression")
        root.deleteRecursively(); check(root.mkdirs())
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
            override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
            override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("workbook-regression-$name", mode)
        }.also { it.getSharedPreferences("history_mapping_profiles", 0).edit().clear().commit() }
    }
    private fun workbook(file: File) {
        val headers = listOf("活动名称", "所属维度", "参考分数", "职务")
        fun xml(rows: List<List<String>>) = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            rows.mapIndexed { r, row -> "<row r=\"${r+1}\">" + row.mapIndexed { c, value ->
                "<c r=\"${('A'.code+c).toChar()}${r+1}\" t=\"inlineStr\"><is><t>$value</t></is></c>"
            }.joinToString("") + "</row>" }.joinToString("") + "</sheetData></worksheet>"
        val names = listOf("分类甲", "分类乙", "空表", "说明")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" + names.mapIndexed { i, n -> "<sheet name=\"$n\" sheetId=\"${i+1}\" r:id=\"r${i+1}\"${if (i == 3) " state=\"hidden\"" else ""}/>" }.joinToString("") + "</sheets></workbook>")
            entry("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + names.indices.joinToString("") { "<Relationship Id=\"r${it+1}\" Target=\"worksheets/sheet${it+1}.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"/>" } + "</Relationships>")
            entry("xl/worksheets/sheet1.xml", xml(listOf(headers, listOf("志愿服务", "劳动", "1", "参与"), listOf("讨论活动", "劳动", "面试讨论", "参与"), listOf("竞赛", "学术创新", "2", "参赛"))))
            entry("xl/worksheets/sheet2.xml", xml(listOf(headers, listOf("志愿服务", "劳动", "1.0", "参与"), listOf("竞赛", "学术创新", "3", "参赛"))))
            entry("xl/worksheets/sheet3.xml", xml(emptyList()))
            entry("xl/worksheets/sheet4.xml", xml(listOf(listOf("导入说明"))))
        }
    }
    private fun core(context: Context, file: File) {
        expect(LocalSheetReader.sheetInfo(file).last().hidden)
        val template = LocalSheetReader.read(file, "分类甲")
        val importer = WorkbookImport(template.headers(1), 1, HistoryMapper.suggest(template.headers(1)))
        LocalSheetReader.readSelected(file, listOf("分类甲", "分类乙", "空表")) { importer.add(it) }
        val preview = importer.finish()
        expect(preview.issues.single().rawScore == "面试讨论" && preview.duplicateCount == 1)
        val resolved = preview.resolve(mapOf(preview.conflicts.single().key to 1), true)
        var store = RecordStore(context)
        val itemId = store.createItem("个人原记录", "劳动", "", "", 7.5, "", "2026-10-01", "", emptyList())
        store.replaceHistory("多分类.xlsx", resolved.records)
        expect(store.listItems().single().let { it.id == itemId && it.referenceScore == 7.5 })
        expect(store.listHistory().single { it.activityName == "志愿服务" }.sources.size == 2)
        store.close(); store = RecordStore(context)
        expect(store.listHistory().map { it.copy(id = 0) }.toSet() == resolved.records.toSet())
        val archive = FullBackup.capture(context, store)
        archive.inputStream().use { FullBackup.verify(context, it) }.use { backup ->
            store.replaceHistory("临时", listOf(resolved.records.first()))
            FullBackup.restore(context, store, backup)
        }
        expect(store.listHistory().size == 2 && store.listHistory().sumOf { it.sources.size } == 3)

        // Exact legacy-v5 bytes, hashes and manifest, including its original seven-column rows.
        val entries = ZipFile(archive).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }.toMutableMap() }
        val data = JSONObject(String(entries.getValue("data.json"), Charsets.UTF_8))
        val rows = data.getJSONObject("tables").getJSONArray("history_records")
        for (i in 0 until rows.length()) rows.getJSONArray(i).remove(7)
        val bytes = data.toString().toByteArray(); entries["data.json"] = bytes
        val manifest = JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8)).put("schemaVersion", 5)
        val descriptors = manifest.getJSONArray("files")
        for (i in 0 until descriptors.length()) descriptors.getJSONObject(i).let { descriptor ->
            if (descriptor.getString("path") == "data.json") descriptor.put("size", bytes.size.toLong()).put("sha256",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
        entries["manifest.json"] = manifest.toString().toByteArray()
        val legacy = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() }
        } }.toByteArray()
        FullBackup.verify(context, legacy.inputStream()).use { FullBackup.restore(context, store, it) }
        expect(store.listHistory().size == 2 && store.listHistory().all { it.sources.isEmpty() })
        expect(store.listItems().single().referenceScore == 7.5)
        FullBackup.discard(archive)
        store.close()

        // Emulate the previous on-device schema and verify the actual SQLiteOpenHelper upgrade.
        context.openOrCreateDatabase("activity_score.db", 0, null).use { db ->
            db.execSQL("ALTER TABLE history_records RENAME TO legacy_history")
            db.execSQL("CREATE TABLE history_records (id INTEGER PRIMARY KEY AUTOINCREMENT, source_row INTEGER NOT NULL, activity_name TEXT NOT NULL, dimension TEXT NOT NULL, subcategory TEXT NOT NULL, role TEXT NOT NULL, reference_score REAL NOT NULL)")
            db.execSQL("INSERT INTO history_records SELECT id,source_row,activity_name,dimension,subcategory,role,reference_score FROM legacy_history")
            db.execSQL("DROP TABLE legacy_history")
            db.execSQL("CREATE INDEX history_name_idx ON history_records(activity_name)")
            db.version = 5
        }
        store = RecordStore(context)
        expect(store.listHistory().size == 2 && store.listItems().single().referenceScore == 7.5)
        store.withBackupDatabase { expect(it.version == 6) }
        store.close()
        val role = cn.activityscore.app.domain.HistoryRole("参与", 1.0, 2, listOf(HistorySource("分类甲", 2, "1")))
        expect(DraftCodec.decode(DraftCodec.encode(role)) == role)
        DraftCodec.validate("history/selectedSheets", DraftCodec.encode(setOf("分类甲", "分类乙")))
    }
    private fun nodes(): List<AccessibilityNodeInfo> = mutableListOf<AccessibilityNodeInfo>().also { result ->
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun collect(n: AccessibilityNodeInfo) { result += n; repeat(n.childCount) { n.getChild(it)?.let(::collect) } }
        ins.uiAutomation.rootInActiveWindow?.let(::collect)
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(80) {
            val snapshot = nodes()
            // A cold, headless emulator can show a system-app ANR over the tested activity.
            // Never dismiss an ANR belonging to this app: that must still fail the check.
            if (snapshot.any { it.text?.toString() in setOf("System UI isn't responding", "Digital Wellbeing isn't responding") }) {
                snapshot.firstOrNull { it.text?.toString() == "Wait" }?.let { wait ->
                    var button = wait
                    while (!button.isClickable && button.parent != null) button = button.parent
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                Thread.sleep(250)
            } else snapshot.firstOrNull { it.text?.toString() == text && it.isVisibleToUser }?.let { return it }
            scrollContainer()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(250)
        }
        screenshot(ins.targetContext, "score-repair-failure")
        error("Missing $text: ${nodes().map { node ->
            "${node.text} [${node.className}, scroll=${node.isScrollable}, bounds=${android.graphics.Rect().also { node.getBoundsInScreen(it) }}]"
        }}")
    }
    private fun scrollContainer() = nodes().filter { it.isScrollable && !it.isEditable }.maxByOrNull { node ->
        android.graphics.Rect().also { node.getBoundsInScreen(it) }.height()
    }
    private fun top() {
        nodes().filter { it.isEditable && it.isFocused }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
        // Long forms containing editable fields can expose incomplete accessibility scroll actions.
        // Use the same app-contained swipe path as the draft form regression checks.
        val root = ins.uiAutomation.rootInActiveWindow ?: return
        val metrics = ins.targetContext.resources.displayMetrics
        val bounds = android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        ins.uiAutomation.windows.firstOrNull { it.id == root.windowId }?.let { window ->
            val visible = android.graphics.Rect().also { window.getBoundsInScreen(it) }
            if (visible.width() >= 100 && visible.height() >= 200) bounds.set(visible)
        }
        val x = bounds.exactCenterX()
        val start = bounds.top + bounds.height() * 0.30f
        val end = bounds.top + bounds.height() * 0.80f
        repeat(6) {
            val time = android.os.SystemClock.uptimeMillis()
            fun event(action: Int, y: Float) {
                val motion = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(ins.uiAutomation.injectInputEvent(motion, true)); motion.recycle()
            }
            event(android.view.MotionEvent.ACTION_DOWN, start)
            repeat(6) { step ->
                event(android.view.MotionEvent.ACTION_MOVE, start + (end - start) * (step + 1) / 6)
                Thread.sleep(20)
            }
            event(android.view.MotionEvent.ACTION_UP, end)
            Thread.sleep(150)
        }
        ins.waitForIdleSync()
        Thread.sleep(300)
    }
    private fun tap(text: String) {
        top(); var node = find(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        expect(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(250)
    }
    private fun enabled(text: String): Boolean {
        top(); var node = find(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        return node.isEnabled
    }
    private fun screenshot(context: Context, name: String) {
        ins.waitForIdleSync(); Thread.sleep(250)
        val image = requireNotNull(ins.uiAutomation.takeScreenshot())
        File(context.cacheDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun ui(context: Context, file: File) {
        runBlocking { DraftDisk(context).write(mapOf("route/tab" to DraftCodec.encode(1),
            "route/historyImport" to DraftCodec.encode(true), "history/sourceUri" to DraftCodec.encode(Uri.fromFile(file).toString()),
            "history/selectedSheets" to DraftCodec.encode(setOf("分类甲", "分类乙")))) }
        lateinit var store: RecordStore
        MainActivity.openStore = { RecordStore(context).also { store = it } }; MainActivity.draftContext = { context }
        val activity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            tap("继续编辑"); find("选择要合并的工作表（已选 2 张）")
            screenshot(context, "workbook-select")
            tap("检查所选工作表"); top(); find("检查结果")
            expect(!enabled("确认替换参考库"))
            tap("我确认跳过剩余 1 行异常")
            expect(!enabled("确认替换参考库"))
            tap("选用 · 3.0 分")
            expect(enabled("确认替换参考库"))
            screenshot(context, "workbook-conflict")
            top(); screenshot(context, "workbook-review")
            tap("返回选择与列映射")
            tap("检查所选工作表"); top(); find("检查结果")
            expect(!enabled("确认替换参考库")) // decisions are invalidated by configuration changes
            tap("我确认跳过剩余 1 行异常"); tap("选用 · 3.0 分")
            tap("确认替换参考库")
            find("已导入 2 行，跳过 2 行，合并重复 1 行；异常及未选用的冲突行已按确认跳过。")
            expect(store.listHistory().size == 2 && store.listHistory().single { it.activityName == "竞赛" }.referenceScore == 3.0)
            expect(store.listItems().single().referenceScore == 7.5)
            top(); screenshot(context, "workbook-imported")
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    private fun scoreRepairUi(context: Context) {
        val repairDraftContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(context.noBackupFilesDir, "score-repair").also { it.mkdirs() }
        }
        val file = File(context.cacheDir, "分数修正.csv").apply {
            writeText("活动名称,所属维度,参考分数,职务\n" +
                "单次活动,劳动,单次0.5,参与\n" +
                "身份活动,劳动,\"成员2，负责人3\",参与\n" +
                "讨论活动,劳动,面试讨论,参与\n" +
                "单次活动,劳动,0.5,参与\n" +
                "身份活动,劳动,2,参与\n")
        }
        runBlocking { DraftDisk(repairDraftContext).write(mapOf("route/tab" to DraftCodec.encode(1),
            "route/historyImport" to DraftCodec.encode(true), "history/sourceUri" to DraftCodec.encode(Uri.fromFile(file).toString()))) }
        lateinit var store: RecordStore
        MainActivity.openStore = { RecordStore(context).also { store = it } }; MainActivity.draftContext = { repairDraftContext }
        val activity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            tap("继续编辑"); tap("检查所选工作表"); top()
            screenshot(context, "score-repair-after-top")
            find("检查结果")
            expect(!enabled("确认替换参考库"))
            tap("0.5 分"); tap("3 分")
            find("分数冲突 1 组 · 未处理 1 组")
            tap("2 分") // Same score merges; switching to 3 reintroduces an explicit conflict.
            tap("3 分")
            top(); find("使用此分数并保留")
            fun enter(text: String) {
                top(); find("使用此分数并保留")
                val field = nodes().last { it.isEditable && it.isVisibleToUser }
                expect(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }))
                ins.waitForIdleSync(); Thread.sleep(200)
            }
            enter("-1")
            expect(!enabled("使用此分数并保留"))
            enter("1.23456789")
            tap("使用此分数并保留")
            expect(!enabled("确认替换参考库"))
            tap("选用 · 3.0 分")
            expect(enabled("确认替换参考库"))
            tap("撤销保留") // Undo restores the issue and invalidates previous confirmations.
            expect(!enabled("确认替换参考库"))
            tap("0.5 分")
            expect(!enabled("确认替换参考库"))
            tap("选用 · 3.0 分")
            expect(enabled("确认替换参考库"))
            top(); find("已指定 0.5 分，将参与重复与冲突检查。"); screenshot(context, "score-repair-candidates")
            find("已指定 3 分，将参与重复与冲突检查。"); screenshot(context, "score-repair-multiple")
            find("已指定 1.23456789 分，将参与重复与冲突检查。"); screenshot(context, "score-repair-manual")
            tap("确认替换参考库")
            find("已导入 3 行，跳过 1 行，合并重复 1 行；异常及未选用的冲突行已按确认跳过。")
            val rows = store.listHistory()
            expect(rows.size == 3)
            expect(rows.single { it.activityName == "单次活动" }.let {
                it.referenceScore == 0.5 && it.sources.map { s -> s.rawScore }.toSet() == setOf("0.5", "单次0.5")
            })
            expect(rows.single { it.activityName == "身份活动" }.let {
                it.referenceScore == 3.0 && it.sources.single().rawScore == "成员2，负责人3"
            })
            expect(rows.single { it.activityName == "讨论活动" }.let {
                it.referenceScore == 1.23456789 && it.sources.single().rawScore == "面试讨论"
            })
            expect(store.listItems().single().referenceScore == 7.5)
            val backup = FullBackup.capture(context, store)
            backup.inputStream().use { FullBackup.verify(context, it) }.use { verified ->
                store.replaceHistory("临时", rows.take(1))
                FullBackup.restore(context, store, verified)
            }
            expect(store.listHistory().map { it.copy(id = 0) }.toSet() == rows.map { it.copy(id = 0) }.toSet())
            FullBackup.discard(backup)
        } finally { ins.runOnMainSync { activity.finish() }; ins.waitForIdleSync() }
        val reopened = RecordStore(context)
        try {
            expect(reopened.listHistory().single { it.activityName == "讨论活动" }.referenceScore == 1.23456789)
        } finally { reopened.close() }
    }
    fun run(phase: String = "suite"): Bundle = Bundle().apply {
        try {
            val context = fixture(); val file = File(context.cacheDir, "多分类.xlsx"); workbook(file)
            core(context, file)
            if (phase != "repair") ui(context, file)
            if (phase != "legacy") scoreRepairUi(context)
            putInt("failures", 0); putString("stream", "Workbook Android integration $phase passed ($assertions assertions): core persistence/backup and selected UI scenarios\n")
        } catch (error: Throwable) { putInt("failures", 1); putString("stream", error.stackTraceToString()) }
    }
}
