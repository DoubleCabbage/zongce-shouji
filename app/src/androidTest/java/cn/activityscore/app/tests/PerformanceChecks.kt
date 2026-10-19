package cn.activityscore.app.tests

import android.app.Instrumentation
import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.*
import android.net.Uri
import android.os.*
import android.view.FrameMetrics
import android.view.accessibility.AccessibilityNodeInfo
import cn.activityscore.app.*
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Only in the test APK. No ordinary app paths, no release diagnostics or dependencies. */
class PerformanceChecks(private val ins: Instrumentation) {
    private val sizes = mapOf("small" to intArrayOf(20,30,20,500), "medium" to intArrayOf(200,400,200,5000), "large" to intArrayOf(1000,2000,1000,20000))
    private val rows = JSONArray()
    private val handler = Handler(Looper.getMainLooper())
    private var label = "setup"
    private val alive = AtomicBoolean(true)
    private val lags = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val frames = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val stacks = Collections.synchronizedList(mutableListOf<String>())
    private var activity: android.app.Activity? = null
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun context(size: String, reset: Boolean = false): Context {
        require(size in sizes)
        val root = ins.targetContext.getDir("p107-performance-$size", Context.MODE_PRIVATE)
        check(root.name == "app_p107-performance-$size" && root.canonicalFile.name == root.name && root.canonicalFile.parentFile == ins.targetContext.filesDir.canonicalFile.parentFile)
        if (reset) check(root.deleteRecursively() && root.mkdirs())
        return object : ContextWrapper(ins.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(root,"db/$name").also { it.parentFile!!.mkdirs() }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
            override fun getFilesDir() = File(root,"files").also { it.mkdirs() }
            override fun getCacheDir() = File(root,"cache").also { it.mkdirs() }
            override fun getNoBackupFilesDir() = File(root,"drafts").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("p107-$size-$name",mode)
        }.also { check(it.getDatabasePath("activity_score.db") != ins.targetContext.getDatabasePath("activity_score.db")) }
    }
    private fun image(file: File, width: Int, height: Int, format: Bitmap.CompressFormat) {
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); val paint = Paint()
            canvas.drawColor(Color.WHITE)
            val random = java.util.Random(107)
            for (y in 0 until height step 8) for(x in 0 until width step 8) {
                paint.color = Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256))
                canvas.drawRect(x.toFloat(),y.toFloat(),(x+8).toFloat(),(y+8).toFloat(),paint)
            }
            paint.color=Color.BLACK; paint.textSize=width/14f
            canvas.drawText("Evidence P1-07",10f,height/2f,paint)
            file.outputStream().use { check(bitmap.compress(format,90,it)) }
        } finally { bitmap.recycle() }
        EvidenceImages.validate(file)
    }
    private fun seed(size: String) {
        val ctx=context(size,true); val s=sizes.getValue(size)
        // This reset is confined to the named test preferences.
        ctx.getSharedPreferences("history_mapping_profiles",0).edit().clear().commit()
        RecordStore(ctx).let { store ->
            try {
                val jpg=File(ctx.cacheDir,"sample.jpg"); val png=File(ctx.cacheDir,"sample.png")
                image(jpg,320,240,Bitmap.CompressFormat.JPEG); image(png,320,240,Bitmap.CompressFormat.PNG)
                image(File(ctx.cacheDir,"large.jpg"),4000,3000,Bitmap.CompressFormat.JPEG)
                image(File(ctx.cacheDir,"large.png"),4000,3000,Bitmap.CompressFormat.PNG)
                image(File(ctx.cacheDir,"sample.webp"),320,240,Bitmap.CompressFormat.WEBP_LOSSLESS)
                store.withBackupDatabase { db ->
                    db.beginTransaction()
                    try {
                        repeat(s[0]) { i -> db.execSQL("INSERT INTO score_items(id,title,dimension,subcategory,role,reference_score,note,created_at) VALUES(?,?,?,?,?,?,?,?)",arrayOf(i+1,"校园志愿创新实践活动 ${i+1}",scoreDimensions[i%6],"校园实践","参与者",(i%9+1)/10.0,"基准合法备注",1700000000000L+i)) }
                        repeat(s[1]) { i ->
                            val item=i%s[0]+1
                            // Both visits of most items share one year; all years and dimensions appear.
                            val year=2024+(item%3)
                            db.execSQL("INSERT INTO participations(id,item_id,event_date,note,share_id) VALUES(?,?,?,?,?)",arrayOf(i+1,item,"$year-10-${(i%27+1).toString().padStart(2,'0')}","第 ${i/s[0]+1} 次参与",null))
                        }
                        repeat(s[2]) { i ->
                            val name="fixture-${i+1}"; (if(i%3==0) png else jpg).copyTo(store.evidenceFile(name),true)
                            db.execSQL("INSERT INTO evidence(id,participation_id,file_name) VALUES(?,?,?)",arrayOf(i+1,i+1,name))
                        }
                        repeat(s[3]) { i -> db.execSQL("INSERT INTO history_records(source_row,activity_name,dimension,subcategory,role,reference_score) VALUES(?,?,?,?,?,?)",arrayOf(i+2,"${2020+i%6}年第${i%20+1}届校园${listOf("志愿","创新","艺术","体育","劳动")[i%5]}实践项目 ${i/2}",scoreDimensions[i%6],"校园实践",if(i%2==0) "参与者" else "组织者",(i%20+1)/10.0)) }
                        db.execSQL("INSERT INTO history_info VALUES(1,?,?,?)",arrayOf("fixture.csv",1700000000000L,s[3]))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                (2024..2026).forEach { store.saveAnnualInputs(AnnualInputs(it,80.0,81.0,82.0,0.1,83.0,84.0,listOf(CourseGrade("课程",2.0,85.0)))) }
                val snapshot=store.loadSnapshot(2026)
                DuplicateReviewFinder.all(snapshot.items,snapshot.participations).forEach { store.saveDuplicateConfirmation(DuplicateConfirmation(it.key,it.fingerprint,if(it is RepeatedParticipationReview) DuplicateDecision.COUNT_ONCE else DuplicateDecision.DISTINCT_ITEMS)) }
                check(snapshot.items.size==s[0] && snapshot.participations.values.sumOf { it.size }==s[1] && snapshot.participations.values.flatten().sumOf { it.evidence.size }==s[2] && snapshot.history.size==s[3])
                File(ctx.cacheDir,"dataset.json").writeText(JSONObject().put("size",size).put("counts",JSONArray(s.toList())).put("imageBytes",(1..s[2]).sumOf { store.evidenceFile("fixture-$it").length() }).put("largeJpegBytes",File(ctx.cacheDir,"large.jpg").length()).put("largePngBytes",File(ctx.cacheDir,"large.png").length()).toString())
            } finally { store.close() }
        }
    }
    private fun sample(op:String, rounds:Int=10, warm:Int=2, block:()->Unit) {
        label=op
        repeat(warm) { block() }
        repeat(rounds) { round ->
            val gcBefore=Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()?:0
            val gcTime=Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull()?:0
            val start=now(); block()
            rows.put(JSONObject().put("op",op).put("round",round).put("ms",(now()-start)/1e6).put("gcCount",(Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()?:0)-gcBefore).put("gcMs",(Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull()?:0)-gcTime))
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all=mutableListOf<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo) { all+=n; repeat(n.childCount) { n.getChild(it)?.let(::walk) } }
        ins.uiAutomation.rootInActiveWindow?.let(::walk); return all
    }
    private fun hasLabel(text: String): Boolean = nodes().any { n ->
        n.text?.toString() == text || n.contentDescription?.toString() == text ||
            (text.matches(Regex("\\d{4}—\\d{4}( 学年)?")) &&
                n.text?.toString()?.removeSuffix(" ▾") == text.removeSuffix(" 学年"))
    }
    private fun waitLabel(text:String, absent:Boolean=false) {
        val until=now()+60_000_000_000L
        while(now()<until) {
            if(hasLabel(text) != absent) { ins.runOnMainSync {}; return }
            Thread.sleep(10)
        }
        error("UI timeout $text absent=$absent: ${nodes().map{it.text}}")
    }
    private fun tap(text:String) {
        if (NativeUiTestRoutes.beforeTap(text, ::tap, ::nodes)) return
        repeat(10) {
            val candidates=nodes().filter { it.text?.toString()==text || it.contentDescription?.toString()==text }
            for(candidate in candidates.asReversed()) {
                var n=candidate
                while(!n.isClickable && n.parent!=null) n=n.parent
                if(!text.startsWith("校园志愿创新实践活动") && n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(80); return }
                if(candidate.isVisibleToUser) {
                    val rect=Rect(); candidate.getBoundsInScreen(rect)
                    val t=SystemClock.uptimeMillis()
                    ins.sendPointerSync(android.view.MotionEvent.obtain(t,t,0,rect.exactCenterX(),rect.exactCenterY(),0))
                    ins.sendPointerSync(android.view.MotionEvent.obtain(t,t+20,1,rect.exactCenterX(),rect.exactCenterY(),0))
                    Thread.sleep(80); return
                }
            }
            Thread.sleep(50)
        }
        error("Cannot click $text: ${nodes().map { "${it.text} clickable=${it.isClickable} selected=${it.isSelected}" }}")
    }
    private fun launch(ctx:Context) {
        MainActivity.openStore={RecordStore(ctx)}; MainActivity.draftContext={ctx}
        activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity!!.window.addOnFrameMetricsAvailableListener({_,f,_ -> frames.add(JSONObject().put("op",label).put("totalMs",f.getMetric(FrameMetrics.TOTAL_DURATION)/1e6).put("drawMs",f.getMetric(FrameMetrics.DRAW_DURATION)/1e6)) },Handler(Looper.getMainLooper()))
        waitLabel("记录活动")
    }
    private fun finishActivity() { ins.runOnMainSync { activity?.finish() }; activity=null; Thread.sleep(100) }
    private fun verticalScrollNode(): AccessibilityNodeInfo = nodes().filter { it.isScrollable }.maxBy { n ->
        val rect=Rect(); n.getBoundsInScreen(rect); rect.height()
    }
    fun run(size:String, phase:String):Bundle {
        val out=Bundle(); val factory=MainActivity.openStore; val draftFactory=MainActivity.draftContext
        val memory=Collections.synchronizedList(mutableListOf<JSONObject>())
        var monitor:Thread?=null
        try {
            require(size in sizes)
            if(phase=="seed") { seed(size); out.putString("stream","P1-07 seed $size passed\n"); out.putInt("failures",0); return out }
            val ctx=context(size); check(File(ctx.cacheDir,"dataset.json").isFile)
            var deadline=SystemClock.uptimeMillis()+16
            val pulse=object:Runnable { override fun run() {
                if(!alive.get()) return
                val late=(SystemClock.uptimeMillis()-deadline).coerceAtLeast(0)
                lags.add(JSONObject().put("op",label).put("lagMs",late))
                deadline=SystemClock.uptimeMillis()+16; handler.postAtTime(this,deadline)
            } }
            handler.postAtTime(pulse,deadline)
            monitor=thread(name="P107-memory-stacks") { while(alive.get()) {
                val runtime=Runtime.getRuntime(); val info=Debug.MemoryInfo(); Debug.getMemoryInfo(info)
                memory.add(JSONObject().put("op",label).put("javaBytes",runtime.totalMemory()-runtime.freeMemory()).put("nativeBytes",Debug.getNativeHeapAllocatedSize()).put("pssKb",info.totalPss))
                val main=Looper.getMainLooper().thread
                stacks.add("$label: "+main.stackTrace.take(12).joinToString(" <- "))
                Thread.sleep(100)
            } }
            // Only the selected instrumentation process receives StrictMode diagnostics.
            ins.runOnMainSync { StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build()) }
            if(phase=="cold") {
                label="cold.home"; val start=now(); launch(ctx)
                rows.put(JSONObject().put("op","cold.activity_to_home").put("ms",(now()-start)/1e6))
                rows.put(JSONObject().put("op","cold.process_to_home").put("ms",SystemClock.elapsedRealtime()-android.os.Process.getStartElapsedRealtime()))
            } else if(phase=="concurrency" || phase=="concurrency-diagnostic") {
                fun holdLock(): Pair<java.util.concurrent.CountDownLatch, Thread> {
                    val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
                    val worker=thread { synchronized(DataLock.monitor) { entered.countDown(); check(release.await(30,java.util.concurrent.TimeUnit.SECONDS)) } }
                    check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)); return release to worker
                }
                MainActivity.openStore={RecordStore(ctx)}; MainActivity.draftContext={ctx}
                val first=holdLock()
                try {
                    val start=now()
                    activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    val startupMs=(now()-start)/1e6
                    rows.put(JSONObject().put("op","concurrency.activity_startup").put("ms",startupMs))
                    // Original gate remains unchanged. The separately named diagnostic continues
                    // queue/cancellation assertions while retaining the observed cold-start time.
                    if(phase=="concurrency") check(startupMs<2000) { "Activity startup synchronously waited for DataLock" }
                    waitLabel("正在打开数据…")
                    val probe=now(); ins.runOnMainSync {}; check((now()-probe)/1e6<500)
                    // Cancel an open still waiting on the real lock, then start a new composition.
                    finishActivity()
                    activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    waitLabel("正在打开数据…")
                    ins.runOnMainSync {}
                } finally { first.first.countDown(); first.second.join() }
                waitLabel("记录活动")
                val second=holdLock()
                try {
                    tap("‹ 上一学年"); tap("下一学年 ›")
                    val probe=now(); ins.runOnMainSync {}; check((now()-probe)/1e6<500)
                    check(hasLabel("2026—2027"))
                } finally { second.first.countDown(); second.second.join() }
                waitLabel("2027—2028 学年"); Thread.sleep(400)
                check(!hasLabel("2025—2026")) { "Old async snapshot replaced the newest year" }
                tap("‹ 上一学年"); waitLabel("2026—2027")
                RecordStore(ctx).let { store -> try {
                    store.withBackupDatabase { it.execSQL("ALTER TABLE history_info RENAME TO p107_unavailable_info") }
                    tap("‹ 上一学年"); waitLabel("数据未完整读取")
                    store.withBackupDatabase { it.execSQL("ALTER TABLE p107_unavailable_info RENAME TO history_info") }
                    tap("重试读取"); waitLabel("数据未完整读取",true)
                    check(store.loadSnapshot(2026).items.size==sizes.getValue(size)[0])
                } finally { store.close() } }
            } else if(phase=="swipe") {
                launch(ctx); tap("活动"); waitLabel("搜索活动名称"); ins.waitForIdleSync()
                fun swipe(forward:Boolean) {
                    val rect=Rect(); verticalScrollNode().getBoundsInScreen(rect)
                    val x=rect.exactCenterX(); val top=rect.top+rect.height()*0.25f; val bottom=rect.top+rect.height()*0.75f
                    val from=if(forward)bottom else top; val to=if(forward)top else bottom
                    val start=SystemClock.uptimeMillis()
                    ins.sendPointerSync(android.view.MotionEvent.obtain(start,start,0,x,from,0))
                    repeat(25) { i ->
                        val t=(i+1)/25f
                        ins.sendPointerSync(android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),2,x,from+(to-from)*t,0))
                        Thread.sleep(20)
                    }
                    ins.sendPointerSync(android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),1,x,to,0))
                    Thread.sleep(250)
                }
                sample("ui.physical_swipe",10,2) { swipe(true); swipe(false) }
            } else if(phase=="save-ui") {
                launch(ctx); tap("活动"); waitLabel("搜索活动名称"); ins.waitForIdleSync()
                val item=nodes().first{it.text?.toString()?.startsWith("校园志愿创新实践活动")==true}
                val original=RecordStore(ctx).let { store -> try { store.listItems().single { it.title == item.text.toString() } } finally { store.close() } }
                tap(item.text.toString()); waitLabel("再参加一次")
                repeat(12) { round ->
                    label="ui.save.prepare"
                    tap("修改条目"); waitLabel("修改加分条目")
                    val note="性能保存测量 $round"
                    fun noteField(n: AccessibilityNodeInfo): Boolean = n.text?.toString()?.startsWith("条目备注") == true ||
                        (0 until n.childCount).any { n.getChild(it)?.let(::noteField) == true }
                    var field: AccessibilityNodeInfo? = null
                    repeat(20) { if (field == null) {
                        field = nodes().firstOrNull { it.isEditable && noteField(it) }
                        if (field == null) { verticalScrollNode().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(350) }
                    } }
                    check(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,note)}))
                    repeat(4) { if(nodes().none{it.text?.toString()=="保存" && it.isVisibleToUser}) { verticalScrollNode().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(100) } }
                    fun saveAndAwait() {
                        tap("保存"); waitLabel("修改加分条目",true)
                        val until=now()+60_000_000_000L
                        while(EvidenceOperation.busy && now()<until) Thread.sleep(10)
                        check(!EvidenceOperation.busy) { "Save did not complete" }
                        waitLabel(note); ins.waitForIdleSync(); ins.runOnMainSync {}
                    }
                    if(round<2) { label="ui.save.refresh"; saveAndAwait() }
                    else sample("ui.save.refresh",1,0,::saveAndAwait)
                }
                RecordStore(ctx).let { store -> try {
                    check(store.listItems().single { it.id == original.id }.note=="性能保存测量 11")
                    store.updateItem(original)
                    check(store.loadSnapshot(2026).items.size==sizes.getValue(size)[0])
                } finally { store.close() } }
            } else if(phase=="input") {
                launch(ctx); tap("记录活动"); waitLabel("新建加分条目")
                sample("ui.input.confirmed",10,2) {
                    val text="校园志愿实践 ${now()%10000}"
                    val field=nodes().first{it.isEditable}
                    check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)}))
                    waitLabel(text); ins.waitForIdleSync()
                }
                ins.sendKeyDownUpSync(4); waitLabel("新建加分条目",true)
            } else if(phase=="ui") {
                launch(ctx)
                sample("warm.home",5,1) { finishActivity(); launch(ctx) }
                sample("ui.year_roundtrip",5,1) { tap("‹ 上一学年"); waitLabel("2025—2026"); tap("下一学年 ›"); waitLabel("2026—2027") }
                sample("ui.records_roundtrip",5,1) { tap("活动"); waitLabel("搜索活动名称"); ins.waitForIdleSync(); tap("首页"); waitLabel("记录活动"); ins.waitForIdleSync() }
                tap("活动"); waitLabel("搜索活动名称"); ins.waitForIdleSync()
                sample("ui.scroll",5,1) {
                    verticalScrollNode().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(500)
                    verticalScrollNode().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(500)
                }
                repeat(8) { verticalScrollNode().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(100) }
                Thread.sleep(500); ins.waitForIdleSync()
                sample("ui.detail",5,1) { val item=nodes().first{it.text?.toString()?.contains("校园志愿创新实践活动")==true}; tap(item.text.toString()); waitLabel("再参加一次"); ins.runOnMainSync{}; ins.sendKeyDownUpSync(4); waitLabel("再参加一次",true) }
                tap("首页"); waitLabel("记录活动")
                tap("记录活动"); waitLabel("新建加分条目")
                sample("ui.title_input",10,2) {
                    val field=nodes().first{it.isEditable}; val start=now()
                    val text="校园志愿实践 ${start%1000}"
                    check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)}))
                    ins.waitForIdleSync()
                }
                ins.sendKeyDownUpSync(4)
                waitLabel("新建加分条目",true)
                RecordStore(ctx).let { store -> try {
                    sample("ui.year_during_scan",5,1) {
                        val started=java.util.concurrent.CountDownLatch(1)
                        val worker=thread { store.evidenceDiagnostics(progress={a,_->if(a==1)started.countDown()}) }
                        check(started.await(30,java.util.concurrent.TimeUnit.SECONDS))
                        tap("‹ 上一学年"); waitLabel("2025—2026")
                        worker.join(); tap("下一学年 ›"); waitLabel("2026—2027")
                    }
                } finally { store.close() } }
            } else if(phase=="export-control") {
                RecordStore(ctx).let { store -> try {
                    sample("excel.snapshot",5,1) { store.captureAnnualExport(2026).close() }
                    store.captureAnnualExport(2026).use { export ->
                        sample("excel.write",5,1) { File(ctx.cacheDir,"export-control.xlsx").outputStream().use { ScoreExcelExport.write(export,it) } }
                    }
                } finally { store.close() } }
            } else if(phase=="mapping-draft") {
                val source=File(ctx.cacheDir,"mapping-recovery.csv")
                source.writeText("旧表头,说明,说明\n活动名称,所属维度,参考分数\n待确认映射,劳动,1.2345\n")
                val hash=java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
                runBlocking { DraftDisk(ctx).write(mapOf(
                    "route/tab" to DraftCodec.encode(1), "route/historyImport" to DraftCodec.encode(true),
                    "history/sourceUri" to DraftCodec.encode(Uri.fromFile(source).toString()), "history/sourceHash" to DraftCodec.encode(hash),
                    "history/headerRowText" to DraftCodec.encode("02"), "history/sheetName" to DraftCodec.encode("CSV"),
                    "history/mapping" to DraftCodec.encode(ColumnMapping(2,1,0)), "history/mappingLookup" to DraftCodec.encode(true))) }
                MainActivity.openStore={RecordStore(ctx)}; MainActivity.draftContext={ctx}
                activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                waitLabel("继续编辑"); tap("继续编辑")
                repeat(10) {
                    if(nodes().none { it.text?.toString()=="预览：有效 1 行，需跳过 0 行" }) {
                        nodes().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        Thread.sleep(150)
                    }
                }
                waitLabel("预览：有效 1 行，需跳过 0 行")
                val draft=(activity as MainActivity).drafts
                check(draft.state("history/headerRowText"){""}.value=="02")
                check(!draft.state("history/mappingLookup"){true}.value)
                check(draft.state("history/mapping"){ColumnMapping()}.value==ColumnMapping(0,1,2))
                RecordStore(ctx).let { store -> try { check(store.listHistory().size==sizes.getValue(size)[3]) } finally { store.close() } }
            } else if(phase=="cache-cleanup") {
                RecordStore(ctx).let { store -> try {
                    val archive=FullBackup.capture(ctx,store)
                    try {
                        FullBackup.verify(ctx,Uri.fromFile(archive)).use { verified ->
                            repeat(6) { round ->
                                val copy=File(ctx.cacheDir,"cleanup-${java.util.UUID.randomUUID()}")
                                archive.parentFile!!.copyRecursively(copy)
                                label="cache.archive_cleanup"; val start=now()
                                ins.runOnMainSync { FullBackup.discard(File(copy,"backup.zip")) }
                                if(round>0) rows.put(JSONObject().put("op",label).put("round",round-1).put("ms",(now()-start)/1e6))
                                check(!copy.exists())
                                val copyVerified=File(ctx.cacheDir,"cleanup-${java.util.UUID.randomUUID()}")
                                verified.directory.copyRecursively(copyVerified)
                                val owned=VerifiedBackup(copyVerified,verified.data,verified.manifest)
                                label="cache.verified_cleanup"; val startVerified=now()
                                ins.runOnMainSync { owned.close() }
                                if(round>0) rows.put(JSONObject().put("op",label).put("round",round-1).put("ms",(now()-startVerified)/1e6))
                                check(!copyVerified.exists())
                            }
                        }
                    } finally { FullBackup.discard(archive) }
                } finally { store.close() } }
            } else if(phase=="mapping-main" || phase=="mapping-io") {
                val profiles=MappingProfiles(ctx)
                RecordStore(ctx).let { store -> try {
                    sample("mapping.find.complete",5,1) {
                        val entered=java.util.concurrent.CountDownLatch(1)
                        val worker=thread { FullBackup.discard(FullBackup.capture(ctx,store,progress={if(it.startsWith("正在捕获"))entered.countDown()})) }
                        check(entered.await(30,java.util.concurrent.TimeUnit.SECONDS))
                        val done=java.util.concurrent.CountDownLatch(1); val start=now()
                        if(phase=="mapping-main") ins.runOnMainSync { profiles.find(listOf("活动名称","维度","分数")); done.countDown() }
                        else ins.runOnMainSync { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { profiles.find(listOf("活动名称","维度","分数")) } }
                            finally { done.countDown() }
                        } }
                        rows.put(JSONObject().put("op","mapping.callback.return").put("ms",(now()-start)/1e6))
                        check(done.await(30,java.util.concurrent.TimeUnit.SECONDS)); worker.join()
                    }
                } finally { store.close() } }
            } else if(phase=="drafts") {
                val draftContext=object:ContextWrapper(ctx) { override fun getNoBackupFilesDir()=File(ctx.cacheDir,"draft-state-bench").also{it.mkdirs()} }
                lateinit var draft: DraftState
                val owner=androidx.lifecycle.ViewModelStore()
                ins.runOnMainSync { draft=DraftState(draftContext); owner.put("perf",draft) }
                try {
                    repeat(100) { if(!draft.ready) Thread.sleep(20) }; check(draft.ready)
                    ins.runOnMainSync { if(draft.offerRecovery) draft.resume() }
                    val short=draft.state("item/new/note") { "" }
                    val long=draft.state("item/new/eventNote") { "" }
                    val courses=draft.state("annual/2026/courses") { emptyList<CourseDraft>() }
                    sample("draft.setter.short") { ins.runOnMainSync { short.value="普通编辑 ${now()}" } }
                    sample("draft.setter.long") { ins.runOnMainSync { long.value="课程实践长文本".repeat(15000)+now() } }
                    sample("draft.setter.courses") { ins.runOnMainSync { courses.value=(0 until 200).map { CourseDraft("课程 $it ${now()}","2.000","85.000") } } }
                    sample("draft.saved_instance") { ins.runOnMainSync { draft.saveInstance() } }
                    sample("draft.barrier") { runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { draft.flush() } } }
                    val writes=java.util.concurrent.atomic.AtomicInteger()
                    val observer=object:FileObserver(draftContext.noBackupFilesDir, FileObserver.MOVED_TO) { override fun onEvent(event:Int,path:String?) { if(path=="form-drafts-v1.json")writes.incrementAndGet() } }
                    observer.startWatching()
                    try {
                        repeat(40) { ins.runOnMainSync { short.value="连续快速输入 $it ${now()}" }; Thread.sleep(5) }
                        Thread.sleep(600); runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { draft.flush() } }
                        rows.put(JSONObject().put("op","draft.burst.completed_writes").put("ms",writes.get()))
                        check(draft.error.isBlank())
                    } finally { observer.stopWatching() }
                } finally { ins.runOnMainSync { owner.clear() } }
            } else if(phase=="locks") {
                RecordStore(ctx).let { store -> try {
                    sample("lock.scan.hold",5,1) { synchronized(DataLock.monitor) { check(store.evidenceDiagnostics().isEmpty()) } }
                    sample("save.transaction",10,2) {
                        var begin=0L; var commit=0L
                        store.writeFault={ stage -> when(stage) {
                            "BEFORE_BEGIN" -> begin=now()
                            "BEFORE_COMMIT" -> commit=now()
                            "AFTER_END" -> {
                                rows.put(JSONObject().put("op","transaction.save.span").put("ms",(now()-begin)/1e6))
                                rows.put(JSONObject().put("op","transaction.save.commit").put("ms",(now()-commit)/1e6))
                            }
                        } }
                        try { val item=store.listItems().first(); store.updateItem(item.copy(note="更新备注")) } finally { store.writeFault={} }
                    }
                    sample("backup.capture.phases",5,1) {
                        val start=now(); var acquired=0L
                        val file=FullBackup.capture(ctx,store,progress={ message ->
                            if(message.startsWith("正在捕获")) { acquired=now(); rows.put(JSONObject().put("op","lock.backup.wait_plus_stage_setup").put("ms",(acquired-start)/1e6)) }
                            if(message.startsWith("正在自检")) rows.put(JSONObject().put("op","lock.backup.hold_span").put("ms",(now()-acquired)/1e6))
                        }); FullBackup.discard(file)
                    }
                    val archive=FullBackup.capture(ctx,store)
                    try { FullBackup.verify(ctx,Uri.fromFile(archive)).use { verified ->
                        sample("backup.restore.phases",5,1) {
                            var begin=0L; var commit=0L
                            FullBackup.restore(ctx,store,verified,fault={ stage -> when(stage) {
                                "FILES_READY" -> begin=now()
                                "BEFORE_COMMIT" -> commit=now()
                                "AFTER_COMMIT" -> {
                                    rows.put(JSONObject().put("op","transaction.restore.span").put("ms",(now()-begin)/1e6))
                                    rows.put(JSONObject().put("op","transaction.restore.commit").put("ms",(now()-commit)/1e6))
                                }
                            } })
                        }
                    } } finally { FullBackup.discard(archive) }
                } finally { store.close() } }
            } else if(phase=="data") {
                RecordStore(ctx).let { store -> try {
                    val s=sizes.getValue(size)
                    sample("snapshot") {
                        val before=now()
                        synchronized(DataLock.monitor) {
                            val acquired=now(); val snap=store.loadSnapshot(2026)
                            check(snap.items.size==s[0] && snap.history.size==s[3])
                            rows.put(JSONObject().put("op","lock.snapshot.wait").put("ms",(acquired-before)/1e6))
                            rows.put(JSONObject().put("op","lock.snapshot.hold").put("ms",(now()-acquired)/1e6))
                        }
                    }
                    val snap=store.loadSnapshot(2026)
                    sample("home.derive") { AnnualScoreCalculator.calculate(2026,snap.items,snap.participations,snap.confirmations,snap.annual) }
                    sample("history.tree") { HistoricalMatcher.buildTree(snap.history) }
                    val tree=HistoricalMatcher.buildTree(snap.history)
                    sample("history.match") { HistoricalMatcher.search("校园志愿创新实践",scoreDimensions[0],tree) }
                    sample("year.snapshot") { store.loadSnapshot(2025) }
                    sample("save.refresh") { val item=store.listItems().first(); store.updateItem(item.copy(note="更新备注")); store.loadSnapshot(2026) }
                    sample("draft.serialize") { DraftCodec.encode("普通短备注".repeat(50)); JSONObject((0..100).associate { "item/new/note$it" to DraftCodec.encode("课程文字".repeat(100)) }).toString() }
                    val diskContext=object:ContextWrapper(ctx) { override fun getNoBackupFilesDir()=File(ctx.cacheDir,"draft-bench").also{it.mkdirs()} }
                    sample("draft.atomic") { runBlocking { DraftDisk(diskContext).write(mapOf("item/new/note" to DraftCodec.encode("普通短备注".repeat(50)))) } }
                    sample("image.validate.small") { EvidenceImages.validate(File(ctx.cacheDir,"sample.jpg")) }
                    sample("image.validate.12mp",5,1) { EvidenceImages.validate(File(ctx.cacheDir,"large.jpg")) }
                    sample("image.convert.webp",5,1) { EvidenceImages.withBitmap(File(ctx.cacheDir,"sample.webp")) { b -> File(ctx.cacheDir,"converted.png").outputStream().use { check(b.compress(Bitmap.CompressFormat.PNG,100,it)) } } }
                    sample("image.save.single",5,1) { val id=store.createItem("单图","劳动","","",0.5,"","2026-10-01","",listOf(Uri.fromFile(File(ctx.cacheDir,"large.jpg")))); store.deleteItem(id) }
                    sample("image.save.multi",5,1) { val id=store.createItem("多图","劳动","","",0.5,"","2026-10-01","",listOf("sample.jpg","sample.png","large.jpg").map{Uri.fromFile(File(ctx.cacheDir,it))}); store.deleteItem(id) }
                    sample("evidence.scan",5,1) { check(store.evidenceDiagnostics().isEmpty()) }
                    sample("excel.snapshot",5,1) { store.captureAnnualExport(2026).close() }
                    store.captureAnnualExport(2026).use { export -> sample("excel.write",5,1) { File(ctx.cacheDir,"annual.xlsx").outputStream().use { ScoreExcelExport.write(export,it) } } }
                    sample("backup.capture",5,1) { FullBackup.discard(FullBackup.capture(ctx,store)) }
                    val archive=FullBackup.capture(ctx,store)
                    try {
                        sample("backup.verify",5,1) { FullBackup.verify(ctx,Uri.fromFile(archive)).close() }
                        FullBackup.verify(ctx,Uri.fromFile(archive)).use { verified -> sample("backup.restore",5,1) { FullBackup.restore(ctx,store,verified) } }
                    } finally { FullBackup.discard(archive) }
                    // Real long operation on the original monitor. UI queue probe can run independently.
                    val started=java.util.concurrent.CountDownLatch(1)
                    val worker=thread { store.evidenceDiagnostics(progress={a,_-> if(a==1) started.countDown()}) }
                    check(started.await(30,java.util.concurrent.TimeUnit.SECONDS)); val before=now()
                    synchronized(DataLock.monitor) { rows.put(JSONObject().put("op","lock.scan.contender_wait").put("ms",(now()-before)/1e6)) }
                    worker.join()
                } finally { store.close() } }
            } else error("Unknown phase $phase")
            alive.set(false); monitor?.join(2000)
            val json=JSONObject().put("size",size).put("phase",phase).put("rows",rows).put("lags",JSONArray(lags.toList())).put("frames",JSONArray(frames.toList())).put("memory",JSONArray(memory.toList())).put("dataset",JSONObject(File(ctx.cacheDir,"dataset.json").readText()))
            val file=File(ctx.cacheDir,"result-$phase.json"); file.writeText(json.toString())
            File(ctx.cacheDir,"stacks-$phase.txt").writeText(stacks.joinToString("\n"))
            out.putString("stream","P1-07 $size $phase passed: ${file.path}, samples=${rows.length()}\n"); out.putInt("failures",0)
        } catch(t:Throwable) {
            alive.set(false); monitor?.join(2000)
            val ctx=context(size)
            File(ctx.cacheDir,"failed-$phase.json").writeText(JSONObject().put("rows",rows).put("lags",JSONArray(lags.toList())).put("frames",JSONArray(frames.toList())).toString())
            File(ctx.cacheDir,"failed-$phase-stacks.txt").writeText(stacks.joinToString("\n"))
            out.putInt("failures",1); out.putString("stream","Failed operation $label\n${t.stackTraceToString()}\nUI: ${nodes().map { "${it.className}: ${it.text}" }}")
        }
        finally {
            alive.set(false); monitor?.join(2000)
            // Instrumentation ends the process immediately. Complete the explicit test cleanup
            // barrier, so the following benchmark is not measuring a legitimate draft prompt.
            (activity as? MainActivity)?.drafts?.let { draft -> if(draft.ready) {
                ins.runOnMainSync { draft.clearInputs() }
                runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { draft.flush() } }
            } }
            finishActivity()
            ins.runOnMainSync { StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX); MainActivity.openStore=factory; MainActivity.draftContext=draftFactory }
        }
        return out
    }
}


