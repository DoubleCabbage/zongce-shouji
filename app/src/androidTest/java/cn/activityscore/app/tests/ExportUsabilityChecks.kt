package cn.activityscore.app.tests

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import cn.activityscore.app.MainActivity
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Real SQLite fixtures; never opens or resets the normal application database. */
class ExportUsabilityChecks(private val instrumentation: Instrumentation) {
    private val base get() = instrumentation.targetContext
    private val log = StringBuilder()
    fun run(phase: String): Bundle = Bundle().apply {
        try {
            when (phase) {
                "before" -> matrix(true)
                "suite" -> { matrix(false); safety(); grouping() }
                "ui" -> ui()
                "grouping-ui" -> ui(true)
                else -> error("Unknown export phase")
            }
            putInt("failures", 0)
            putString("stream", "$log\nP1-10A $phase passed; pid=${android.os.Process.myPid()}\n")
        } catch (error: Throwable) {
            putInt("failures", 1); putString("stream", "$log\n${error.stackTraceToString()}\n")
        }
    }
    private fun fixture(name: String): Context {
        val root = base.getDir("p110a-export-$name", 0)
        check(root.name == "app_p110a-export-$name" && root.parentFile!!.canonicalFile == base.filesDir.parentFile!!.canonicalFile)
        check(root.deleteRecursively() && root.mkdirs())
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
            override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("p110a-$name-$name", mode)
        }.also { check(it.getDatabasePath("activity_score.db") != base.getDatabasePath("activity_score.db")) }
    }
    private fun matrix(before: Boolean) {
        for (case in 1..9) {
            val context = fixture("case$case")
            val store = RecordStore(context)
            try {
                val id = store.createItem("校园志愿服务", "劳动", "", "", if (case == 9) null else 2.0, "", "2026-10-01", "首次", emptyList())
                if (case != 1 && case != 8) store.addParticipation(id, if (case == 7) "2025-10-02" else "2026-10-02", "第二次", emptyList())
                if (case == 8) store.createItem("校园志愿服务", "劳动", "", "", 2.0, "", "2026-10-02", "同名独立活动", emptyList())
                val items = store.listItems()
                val visits = items.associate { it.id to store.listParticipations(it.id) }
                if (case in 3..6) {
                    val review = DuplicateReviewFinder.all(items, visits).filterIsInstance<RepeatedParticipationReview>().single()
                    val each = case == 4 || case == 5
                    store.saveDuplicateConfirmation(DuplicateConfirmation(review.key, review.fingerprint,
                        if (each) DuplicateDecision.COUNT_EACH else DuplicateDecision.COUNT_ONCE,
                        if (each) review.participations.take(if (case == 5) 1 else 2).mapIndexed { i, p -> p.id to (4.0 + i) }.toMap() else emptyMap()))
                    if (case == 6) store.updateParticipation(visits.getValue(id).first().id, "2026-10-01", "已修改备注", emptyList())
                }
                log.appendLine("case=$case activityIds=${items.map { it.id }} participationIds=${visits.values.flatten().map { it.id }}")
                val outcome = runCatching {
                    store.captureAnnualExport(2026).use { snapshot ->
                        val output = ByteArrayOutputStream()
                        ScoreExcelExport.write(snapshot, output)
                        check(output.size() > 0)
                        if (!before) {
                            check(snapshot.assessment.blockers.isEmpty())
                            check(snapshot.scores.activities.sumOf { it.participations.size } == if (case in listOf(1,7)) 1 else 2)
                            val projected = snapshot.assessment.activities
                            check(projected.map { it.score } == when(case) { 1,3,7 -> listOf(2.0); 4 -> listOf(9.0); 8 -> listOf(null,null); else -> listOf(null) })
                            if(case == 5) check(projected.single().participationScores.size == 1)
                            if(case == 6) check(projected.single().status == "需重新核对" && projected.single().participationScores.isEmpty())
                        }
                        log.appendLine("exported facts=${snapshot.scores.activities.sumOf { it.participations.size }} exportedScores=${snapshot.assessment.activities.map { it.score }} bytes=${output.size()}")
                    }
                }
                if (before && case in listOf(2,5,6,8,9)) {
                    val error = outcome.exceptionOrNull()
                    check(error is IllegalArgumentException)
                    check(dataErrorMessage(error) == "输入未通过校验，请核对填写内容。")
                    log.appendLine("UI=${dataErrorMessage(error)}\n${error.stackTraceToString()}")
                } else outcome.getOrThrow()
                check(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("annual-export-") })
            } finally { store.close() }
        }
    }

    private fun png(context: Context): File = File(context.cacheDir, "source.png").also { file ->
        val bitmap = Bitmap.createBitmap(20, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    private fun grouping() {
        val context = fixture("grouping")
        val store = RecordStore(context)
        try {
            val source = Uri.fromFile(png(context))
            val id = store.createItem("班级活动", "劳动", "", "", 0.5, "原名不变", "2026-10-01", "第一次", listOf(source, source))
            store.addParticipation(id, "2026-10-01", "同一天第二次", listOf(source))
            store.addParticipation(id, "2026-10-03", "无图第三次", emptyList())
            val facts = store.listParticipations(id)
            val review = DuplicateReviewFinder.all(store.listItems(), mapOf(id to facts)).filterIsInstance<RepeatedParticipationReview>().single()
            store.saveDuplicateConfirmation(DuplicateConfirmation(review.key, review.fingerprint, DuplicateDecision.COUNT_EACH,
                facts.associate { it.id to 0.5 }))
            fun tables(): String = store.withBackupDatabase { db ->
                listOf("score_items", "participations", "evidence", "duplicate_reviews", "annual_inputs", "history_records", "history_info").joinToString("\n") { table ->
                    db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { c ->
                        buildString { append(table); while(c.moveToNext()) repeat(c.columnCount) { column ->
                            append('|').append(c.getType(column)).append(':').append(if (c.isNull(column)) "NULL" else c.getString(column))
                        } }
                    }
                }
            }
            val before = tables()
            val originalModel = store.loadSnapshot(2026)
            store.captureAnnualExport(2026).use { snap ->
                store.validateAnnualExport(snap)
                val merged = ExportEntryPlanner.build(2026, snap.assessment)
                val split = ExportEntryPlanner.build(2026, snap.assessment, mapOf(id to ExportChoice(ExportMode.SPLIT)))
                check(merged.entries.single().score == 1.5)
                check(split.entries.size == 3 && split.entries.mapNotNull { it.score }.sum() == 1.5)
                check(split.entries.map { it.title }.toSet().size == 3)
                listOf(merged, split, ExportEntryPlanner.build(2026, snap.assessment)).forEachIndexed { i, plan ->
                    val file = File(context.cacheDir, "grouping-$i.xlsx")
                    file.outputStream().use { ScoreExcelExport.write(snap, it, plan) }
                    java.util.zip.ZipFile(file).use { zip ->
                        check(zip.entries().asSequence().count { it.name.startsWith("xl/media/") } == 3)
                        val detail = zip.getInputStream(zip.getEntry("xl/worksheets/sheet5.xml")).reader().readText()
                        check(plan.entries.all { detail.contains(it.id) && detail.contains(it.title) })
                        check(Regex("<row r=").findAll(detail).count() == plan.entries.size + 1)
                    }
                    store.validateAnnualExport(snap)
                }
                check(tables() == before && store.loadSnapshot(2026) == originalModel)
                store.updateParticipation(facts.first().id, "2026-10-03", "预览后改变", emptyList())
                val error = runCatching { store.validateAnnualExport(snap) }.exceptionOrNull()
                check(error is ExportSnapshotChanged && exportErrorMessage(error, "保存").contains("预览后已变化"))
            }
            log.appendLine("P1-10B: real SQLite seven tables and annual model unchanged through merged/split/merged; 1.5 numeric total, same-day titles, three media, empty-photo fact and stale-snapshot rejection passed")
        } finally { store.close() }
    }
    private fun safety() {
        val context = fixture("safety")
        val store = RecordStore(context)
        try {
            val id = store.createItem("坏图定位活动", "劳动", "", "", null, "", "2026-10-01", "", listOf(Uri.fromFile(png(context))))
            val p = store.listParticipations(id).single()
            val file = store.evidenceFile(p.evidence.single().fileName)
            val bytes = file.readBytes()
            fun fail(): Exception = requireNotNull(runCatching { store.captureAnnualExport(2026).close() }.exceptionOrNull()) as Exception
            file.writeBytes(byteArrayOf(1,2,3))
            val broken = fail()
            check(broken is EvidenceProblems && broken.issues.single().location.participationId == p.id)
            check(exportErrorMessage(broken, "准备快照").contains("坏图定位活动"))
            check(exportErrorMessage(broken, "准备快照").contains("证明 ${p.evidence.single().id}"))
            check(file.delete()); check(fail() is EvidenceProblems)
            file.writeBytes(bytes)
            store.updateParticipation(p.id, "unknown-date", "", emptyList())
            val date = fail()
            check(date is ExportBlocked && date.issues.single().participationId == p.id && date.message!!.contains("坏图定位活动"))
            store.updateParticipation(p.id, "2026-10-01", "", emptyList())
            store.captureAnnualExport(2026).use { snap ->
                snap.evidenceFiles.values.single().writeBytes(byteArrayOf(3,2,1))
                val output = ByteArrayOutputStream()
                check(runCatching { ScoreExcelExport.write(snap, output) }.exceptionOrNull() is EvidenceProblems)
                check(output.size() == 0)
            }
            store.withBackupDatabase { it.execSQL("ALTER TABLE score_items RENAME TO unavailable_items") }
            check(fail() is android.database.SQLException)
            store.withBackupDatabase { it.execSQL("ALTER TABLE unavailable_items RENAME TO score_items") }
            store.writeFault = { if (it == "AFTER_END" || it == "BEFORE_VERIFY") error("Unconfirmed fixture") }
            check(runCatching { store.updateItem(store.listItems().single().copy(note = "unknown")) }.exceptionOrNull() is DataOperationException)
            check((fail() as DataOperationException).state == WriteState.UNKNOWN)
            store.writeFault = {}; store.recheckPendingWrite()
            check(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("annual-export-") })
            log.appendLine("safety: bad/missing/copied image, unassignable date, database read failure, UNKNOWN block and cleanup passed")
        } finally { store.close() }
        val foreignContext = fixture("foreign")
        val foreignStore = RecordStore(foreignContext)
        try {
            foreignStore.withBackupDatabase { db ->
                db.setForeignKeyConstraintsEnabled(false)
                db.execSQL("INSERT INTO participations(id,item_id,event_date,note) VALUES(999,999,'2026-10-01','orphan')")
            }
            check(runCatching { foreignStore.captureAnnualExport(2026).close() }.exceptionOrNull() is DataReadException)
            log.appendLine("safety: existing orphan participation 999 blocks snapshot")
        } finally { foreignStore.close() }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun collect(n: AccessibilityNodeInfo) { add(n); repeat(n.childCount) { n.getChild(it)?.let(::collect) } }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::collect)
    }
    private fun text(n: AccessibilityNodeInfo) = "${n.text?.toString().orEmpty()} ${n.contentDescription?.toString().orEmpty()}"
    private fun scrollPreview(forward: Boolean) {
        val node = nodes().filter { it.isScrollable }.maxByOrNull { n ->
            android.graphics.Rect().also { n.getBoundsInScreen(it) }.height()
        } ?: return
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val start = rect.top + rect.height() * if (forward) 0.6f else 0.4f
        val end = rect.top + rect.height() * if (forward) 0.4f else 0.6f
        val time = android.os.SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time, time, 0, rect.exactCenterX(), start, 0))
        for (i in 1..20) {
            Thread.sleep(20)
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), 2,
                rect.exactCenterX(), start + (end - start) * i / 20, 0))
        }
        Thread.sleep(250) // Stop before lifting: do not fling past off-screen radio buttons.
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), 1, rect.exactCenterX(), end, 0))
        Thread.sleep(180)
    }
    private fun waitFor(label: String, absent: Boolean = false) {
        repeat(150) {
            if (nodes().any { text(it).contains(label) } != absent) return
            if (label.startsWith("预计导出"))
                scrollPreview(false)
            Thread.sleep(100)
        }
        error("UI expected $label absent=$absent: ${nodes().joinToString { text(it) }}")
    }
    private fun tap(label: String, clicks: Int = 1) {
        // Lazy preview rows outside the viewport are absent from accessibility, so search from the top.
        if (nodes().none { it.text?.toString() == label || it.contentDescription?.toString() == label }) {
            repeat(8) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(120) }
        }
        repeat(40) {
            val found = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (label.contains(" · 参与 ")) log.appendLine("carrier search $it: ${nodes().mapNotNull { it.text?.toString() }.filter { it.contains(" · 参与 ") }}")
            if (found != null) {
                var n: AccessibilityNodeInfo = found
                while (!n.isClickable && n.parent != null) n = n.parent
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    repeat(clicks - 1) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    Thread.sleep(180); return
                }
            }
            if (found == null) scrollPreview(true)
            Thread.sleep(100)
        }
        error("Cannot click $label: ${nodes().joinToString { text(it) }}")
    }
    private fun ui(once: Boolean = false) {
        val context = fixture("ui")
        val store = RecordStore(context)
        val originalFactory = MainActivity.openStore
        val originalDraft = MainActivity.draftContext
        var activity: android.app.Activity? = null
        try {
            val id = store.createItem(if (once) "只计一次三次参与" else "未核对三次参与", "劳动", "", "", if (once) 0.5 else null, "", "2026-10-01", "首次", listOf(Uri.fromFile(png(context))))
            store.addParticipation(id, "2026-10-02", "第二次无图", emptyList())
            store.addParticipation(id, "2026-10-03", "第三次无图", emptyList())
            if (once) {
                val review = DuplicateReviewFinder.all(store.listItems(), mapOf(id to store.listParticipations(id))).filterIsInstance<RepeatedParticipationReview>().single()
                store.saveDuplicateConfirmation(DuplicateConfirmation(review.key, review.fingerprint, DuplicateDecision.COUNT_ONCE))
            }
            MainActivity.openStore = { RecordStore(context) }; MainActivity.draftContext = { context }
            activity = instrumentation.startActivitySync(Intent(base, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("整理"); tap("整理"); waitFor("Excel 申报导出")
            fun prepare() {
                tap("Excel 申报导出"); waitFor("导出学年活动资料"); tap("准备导出摘要", 2); waitFor("学年资料导出摘要")
                check(context.cacheDir.listFiles().orEmpty().count { it.name.startsWith("annual-export-") } == 1)
            }
            fun clean() = context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("annual-export-") }
            prepare()
            check(nodes().any { text(it).contains("参与 3 次") })
            check(nodes().any { text(it).contains("阻断错误 0 项") })
            waitFor("预计导出 1 条申报记录")
            tap("按参与拆分"); waitFor("预计导出 3 条申报记录")
            if (once) {
                val button = nodes().first { it.text?.toString() == "继续导出资料" }
                check(!button.isEnabled || button.parent?.isEnabled == false)
                tap("2026-10-02 · 参与 ${store.listParticipations(id).single { it.eventDate == "2026-10-02" }.id}")
                waitFor("预计导出 3 条申报记录")
            }
            tap("合并为一行"); waitFor("预计导出 1 条申报记录")
            instrumentation.waitForIdleSync(); Thread.sleep(300) // Capture after the dialog's enter animation.
            instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                File(context.cacheDir, "summary.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
            }
            tap("取消"); waitFor("学年资料导出摘要", true); check(clean())
            prepare(); tap("继续导出资料", 2); waitFor("SAVE")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("Excel 申报导出"); Thread.sleep(400); check(clean())
            check(nodes().none { text(it).contains("已保存") })
            prepare(); tap("按参与拆分"); waitFor("预计导出 3 条申报记录")
            if (once) tap("2026-10-02 · 参与 ${store.listParticipations(id).single { it.eventDate == "2026-10-02" }.id}")
            waitFor("预计导出 3 条申报记录")
            if (once) {
                tap("2026-10-02 · 参与 ${store.listParticipations(id).single { it.eventDate == "2026-10-02" }.id}")
                instrumentation.waitForIdleSync(); Thread.sleep(250)
                instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                    File(context.cacheDir, "split.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
                }
            }
            tap("继续导出资料"); waitFor("SAVE")
            val input = nodes().first { it.isEditable }
            val name = "p110a-ui-${android.os.Process.myPid()}.xlsx"
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
            tap("SAVE"); waitFor("Sharing 1 file")
            // Dismiss Android Sharesheet, then verify saved workbook bytes from the system provider.
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("Excel 已保存")
            Thread.sleep(300); check(clean())
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$name")
            val saved = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            check(saved.size > 0 && saved[0] == 'P'.code.toByte() && saved[1] == 'K'.code.toByte())
            val zip = File(context.cacheDir, "saved-ui.xlsx").apply { writeBytes(saved) }
            java.util.zip.ZipFile(zip).use { workbook ->
                val summary = workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
                val detail = workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet5.xml")).reader().readText()
                check(summary.contains(if (once) "只计一次" else "待核对") && summary.contains("<c r=\"E2\" s=\"2\"/>"))
                if (once) check(summary.contains("<c r=\"E3\" s=\"2\"><v>0.5</v></c>") && summary.contains("<c r=\"E4\" s=\"2\"/>"))
                check(Regex("<row r=").findAll(detail).count() == 4)
                check(detail.contains("2026-10-01 ") && detail.contains("2026-10-02 ") && detail.contains("2026-10-03 "))
                store.listParticipations(id).forEach { check(detail.contains(it.note)) }
                check(workbook.entries().asSequence().count { it.name.startsWith("xl/media/") } == 1)
            }
            log.appendLine("UI: summary cancel, real SAF cancel and SAVE, three facts/one image/blank score, temporary cleanup passed; saved=$name bytes=${saved.size}")
            if (once) log.appendLine("P1-10B UI: carrier explicitly selected, E3=0.5, E2/E4 blank; merged/split/merged and final split match preview")
            prepare(); tap("继续导出资料"); waitFor("SAVE")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("已取消本次 Excel 保存"); check(nodes().none { text(it).contains("已保存") }); check(clean())
            log.appendLine("UI: cancel after a previous successful save replaces the old success notice with explicit cancellation")
        } catch (error: Throwable) {
            instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                File(context.cacheDir, "failure.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
            }
            File(context.cacheDir, "ui-failure.txt").writeText(nodes().joinToString("\n") { text(it) }); throw error
        } finally {
            instrumentation.runOnMainSync { activity?.finish(); MainActivity.openStore = originalFactory; MainActivity.draftContext = originalDraft }
            store.close()
        }
    }
}
