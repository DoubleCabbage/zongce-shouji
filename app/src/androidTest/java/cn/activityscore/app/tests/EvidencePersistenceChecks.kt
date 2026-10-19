package cn.activityscore.app.tests

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import cn.activityscore.app.data.*
import cn.activityscore.app.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.CRC32

class EvidencePersistenceChecks(private val base: Context) {
    private var count = 0
    private fun assertThat(value: Boolean) { check(value) { "Evidence assertion ${count + 1}" }; count++ }
    private fun rejects(action: () -> Unit): Throwable = requireNotNull(runCatching(action).exceptionOrNull()).also { count++ }
    fun run(phase: String = "suite"): Bundle {
        val result = Bundle()
        try {
            when(phase) { "suite" -> suite(); "kill-copy" -> killCopy(); "verify-copy" -> verifyCopy(); else -> error("Unknown evidence phase") }
            result.putInt("failures", 0)
            result.putString("stream", "P1-06 Android evidence ${if(phase=="suite") "" else "$phase "}passed ($count checks), pid=${android.os.Process.myPid()}\n")
        }
        catch (error: Throwable) { result.putInt("failures", 1); result.putString("stream", "P1-06 failed after $count checks: ${error.stackTraceToString()}\n") }
        return result
    }
    private fun deathContext(reset: Boolean): Context {
        val root=base.getDir("evidence-death-regression",0)
        if(reset) { root.deleteRecursively(); check(root.mkdirs()) }
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String)=File(root,name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
            override fun getFilesDir()=File(root,"files").also { it.mkdirs() }
            override fun getCacheDir()=File(root,"cache").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences=super.getSharedPreferences("p106-death-$name",mode)
        }.also { assertThat(it.getDatabasePath("activity_score.db")!=base.getDatabasePath("activity_score.db")) }
    }
    private fun killCopy() {
        val context=deathContext(true); val store=RecordStore(context)
        val source=File(context.cacheDir,"source.png")
        val bitmap=Bitmap.createBitmap(16,12,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.GREEN)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val id=store.createItem("中断前原活动","劳动","","",1.0,"","2026-10-05","",listOf(Uri.fromFile(source)))
        val visit=store.listParticipations(id).single()
        File(context.cacheDir,"old.pid").writeText(android.os.Process.myPid().toString())
        Thread {
            repeat(200) {
                if(File(context.filesDir,"evidence-staging").listFiles().orEmpty().any { it.name.endsWith(".part") && it.length()>0 }) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                Thread.sleep(50)
            }
        }.start()
        store.addEvidence(visit.id,listOf(Uri.parse("content://cn.activityscore.app.test.evidence-fixture/blocked")))
        error("Expected real death during URI copy")
    }
    private fun verifyCopy() {
        val context=deathContext(false)
        assertThat(File(context.cacheDir,"old.pid").readText().toInt()!=android.os.Process.myPid())
        assertThat(File(context.filesDir,"evidence-staging").listFiles().orEmpty().any { it.length()>0 })
        val store=RecordStore(context)
        assertThat(store.listItems().single().title=="中断前原活动")
        val visit=store.listParticipations(store.listItems().single().id).single()
        assertThat(visit.evidence.size==1)
        assertThat(store.evidenceFile(visit.evidence.single().fileName).readBytes().contentEquals(File(context.cacheDir,"source.png").readBytes()))
        assertThat(File(context.filesDir,"evidence-staging").listFiles().orEmpty().isEmpty())
        assertThat(File(context.filesDir,"evidence").listFiles()!!.size==1)
        assertThat(store.evidenceDiagnostics().isEmpty()); store.close()
    }
    private fun suite() {
        val root = base.getDir("evidence-regression", 0)
        assertThat(root.canonicalPath != base.filesDir.canonicalPath)
        root.deleteRecursively(); check(root.mkdirs())
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            override fun getFilesDir() = File(root,"files").also { it.mkdirs() }
            override fun getCacheDir() = File(root,"cache").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("p106-fixture-$name",mode)
        }
        assertThat(context.getDatabasePath("activity_score.db") != base.getDatabasePath("activity_score.db"))
        context.getSharedPreferences("history_mapping_profiles",0).edit().clear().commit()
        var store = RecordStore(context)
        fun image(name: String, format: Bitmap.CompressFormat, pixel: Int = Color.RED): File = File(context.cacheDir,name).also { file ->
            val bitmap = Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.TRANSPARENT); bitmap.setPixel(0,0,pixel)
            file.outputStream().use { check(bitmap.compress(format,100,it)) }; bitmap.recycle()
        }
        val jpeg = image("external.jpeg", Bitmap.CompressFormat.JPEG)
        val png = image("external.png", Bitmap.CompressFormat.PNG)
        val jpegBytes = jpeg.readBytes(); val pngBytes = png.readBytes()
        val id = store.createItem("校园志愿服务","劳动","","",1.2345,"","2026-10-05","",listOf(Uri.fromFile(jpeg),Uri.fromFile(png)))
        val first = store.listParticipations(id).single()
        assertThat(first.evidence.size == 2)
        first.evidence.zip(listOf(jpegBytes,pngBytes)).forEach { (photo,bytes) -> assertThat(store.evidenceFile(photo.fileName).readBytes().contentEquals(bytes)) }
        assertThat(BitmapFactory.decodeFile(store.evidenceFile(first.evidence[1].fileName).path).let { bitmap -> (bitmap.getPixel(1,1) ushr 24 == 0).also { bitmap.recycle() } })
        store.addParticipation(id,"2026-10-06","第二次",listOf(Uri.fromFile(png)))
        store.addParticipation(id,"2025-10-06","跨学年",listOf(Uri.fromFile(jpeg)))
        val noPhoto = store.createItem("无证明活动","劳动","","",0.0,"","2026-10-07","",emptyList())
        assertThat(store.listParticipations(noPhoto).single().evidence.isEmpty())
        val items = store.listItems(); val visits = items.associate { it.id to store.listParticipations(it.id) }
        val review = DuplicateReviewFinder.all(items,visits).single()
        store.saveDuplicateConfirmation(DuplicateConfirmation(review.key,review.fingerprint,DuplicateDecision.COUNT_ONCE))
        jpeg.delete(); png.delete()
        assertThat(store.evidenceDiagnostics().isEmpty())
        val output = File(context.cacheDir,"annual.xlsx")
        store.captureAnnualExport(2026).use { snapshot -> output.outputStream().use { ScoreExcelExport.write(snapshot,it) } }
        ZipFile(output).use { zip ->
            val photos = zip.entries().asSequence().filter { it.name.startsWith("xl/media/") }.toList()
            assertThat(photos.size == 3)
            assertThat(photos.count { it.name.endsWith(".jpg") } == 1)
            photos.filter { it.name.endsWith(".png") }.forEach { assertThat(zip.getInputStream(it).readBytes().contentEquals(pngBytes)) }
            val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet${scoreDimensions.indexOf("劳动") + 2}.xml")).reader().readText()
            assertThat(sheet.contains("2026-10-05") && sheet.contains("2026-10-06") && !sheet.contains("2025-10-06"))
        }
        val all = store.listParticipations(id)
        assertThat(all.flatMap { it.evidence }.all { photo -> all.single { it.id == photo.participationId }.evidence.contains(photo) })
        val valid = File(context.cacheDir,"valid").also { it.writeBytes(pngBytes) }
        val webp = image("external.webp",Bitmap.CompressFormat.WEBP_LOSSLESS,Color.BLUE)
        val webpBytes=webp.readBytes()
        val webpItem=store.createItem("透明 WebP","劳动","","",0.0,"","2026-10-08","",listOf(Uri.fromFile(webp)))
        val webpPhoto=store.listParticipations(webpItem).single().evidence.single()
        store.captureAnnualExport(2026).use { snapshot -> output.outputStream().use { ScoreExcelExport.write(snapshot,it) } }
        ZipFile(output).use { archive ->
            val converted=archive.entries().asSequence().filter { it.name.startsWith("xl/media/") }.map { archive.getInputStream(it).readBytes() }.first { !it.contentEquals(pngBytes) && !it.contentEquals(jpegBytes) }
            val bitmap=BitmapFactory.decodeByteArray(converted,0,converted.size)
            assertThat(bitmap.width==32 && bitmap.height==24 && bitmap.getPixel(1,1) ushr 24==0 && bitmap.getPixel(0,0)==Color.BLUE); bitmap.recycle()
        }
        assertThat(store.evidenceFile(webpPhoto.fileName).readBytes().contentEquals(webpBytes))
        val zip = File(context.cacheDir,"activity.zip")
        zip.outputStream().use { ActivityPackage.write(store,items.single { it.id==id },first,first.evidence,it) }
        ActivityPackage.read(context,Uri.fromFile(zip)).usePackage { staged ->
            assertThat(staged.photos.size==2)
            staged.photos.forEach { assertThat(EvidenceImages.validate(it).startsWith("image/")) }
            val destination = object : ContextWrapper(context) {
                override fun getDatabasePath(name: String) = File(root,"import-$name")
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
                override fun getFilesDir() = File(root,"import-files").also { it.mkdirs() }
            }
            val importedStore=RecordStore(destination)
            val imported = importedStore.createItem("导入活动","劳动","","",1.0,"","2026-10-05","",staged.photos.map(Uri::fromFile),staged.activity.shareId)
            assertThat(importedStore.listParticipations(imported).single().evidence.size==2)
            assertThat(importedStore.hasSharedParticipation(staged.activity.shareId))
            rejects { importedStore.createItem("重复导入","劳动","","",1.0,"","2026-10-05","",staged.photos.map(Uri::fromFile),staged.activity.shareId) }
            assertThat(importedStore.listItems().size==1)
            importedStore.close()
        }
        val abnormal = listOf(
            "empty" to byteArrayOf(), "truncated" to jpegBytes.copyOf(jpegBytes.size-2),
            "crc" to pngBytes.copyOf().also { it[29]=(it[29].toInt() xor 1).toByte() },
            "fake-image.png" to "not an image".toByteArray(), "unsupported.tiff" to byteArrayOf(0x49,0x49,0x2a,0,0,0,0,0),
            "decode" to pngBytes.copyOf().also { bytes ->
                var offset=8
                while(offset < bytes.size) {
                    val size=java.nio.ByteBuffer.wrap(bytes,offset,4).int
                    if(String(bytes,offset+4,4,Charsets.US_ASCII)=="IDAT") {
                        bytes[offset+8]=0; bytes[offset+9]=0
                        val crc=CRC32(); crc.update(bytes,offset+4,size+4)
                        java.nio.ByteBuffer.wrap(bytes,offset+8+size,4).putInt(crc.value.toInt()); break
                    }
                    offset+=size+12
                }
            },
            "dimensions" to pngBytes.copyOf().also { bytes ->
                java.nio.ByteBuffer.wrap(bytes,16,4).putInt(20000)
                val crc=CRC32(); crc.update(bytes,12,17); java.nio.ByteBuffer.wrap(bytes,29,4).putInt(crc.value.toInt())
            })
        val before = store.listItems()
        abnormal.forEach { (name,bytes) ->
            val file=File(context.cacheDir,name).also { it.writeBytes(bytes) }
            assertThat(rejects { store.createItem("拒绝$name","劳动","","",1.0,"","2026-10-05","",listOf(Uri.fromFile(file))) }.message!!.isNotBlank())
            assertThat(store.listItems()==before)
        }
        val fileNames=File(context.filesDir,"evidence").list()!!.toSet()
        for ((path,type) in listOf("fake" to EvidenceError.UNSUPPORTED,"interrupted" to EvidenceError.UNREADABLE,"unopenable" to EvidenceError.UNREADABLE)) {
            val uri=Uri.parse("content://cn.activityscore.app.test.evidence-fixture/$path")
            assertThat(context.contentResolver.getType(uri)=="image/png")
            assertThat((rejects { store.addEvidence(first.id,listOf(uri)) } as EvidenceInvalid).type==type)
            assertThat(File(context.filesDir,"evidence").list()!!.toSet()==fileNames)
            assertThat(File(context.filesDir,"evidence-staging").listFiles()!!.isEmpty())
        }
        rejects { store.addEvidence(first.id,listOf(Uri.fromFile(valid),Uri.fromFile(File(context.cacheDir,"truncated")))) }
        assertThat(File(context.filesDir,"evidence").list()!!.toSet()==fileNames)
        assertThat(store.listParticipations(id).single { it.id==first.id }.evidence==first.evidence)
        val huge=File(context.cacheDir,"large"); java.io.RandomAccessFile(huge,"rw").use { it.setLength(EvidencePolicy.NEW_MAX_BYTES+1) }
        assertThat((rejects { store.addEvidence(first.id,listOf(Uri.fromFile(huge))) } as EvidenceInvalid).type==EvidenceError.TOO_LARGE)
        assertThat((rejects { store.addEvidence(first.id,listOf(Uri.parse("content://invalid-authority/picture"))) } as EvidenceInvalid).type==EvidenceError.UNREADABLE)
        val badPhoto=first.evidence[0]; val badFile=store.evidenceFile(badPhoto.fileName)
        fun diagnostic(type: EvidenceError) {
            val issue=store.evidenceDiagnostics().single { it.location.photo.id==badPhoto.id }
            assertThat(issue.type==type); assertThat(issue.location.itemId==id && issue.location.participationId==first.id)
            assertThat(issue.description.contains("校园志愿服务") && issue.description.contains("2026-10-05") && issue.description.contains("证明 ${badPhoto.id}"))
            assertThat(store.listParticipations(id).single { it.id==first.id }.evidence.contains(badPhoto))
            for (action in listOf<()->Unit>({ store.captureAnnualExport(2026).close() },{ FullBackup.capture(context,store).let(FullBackup::discard) })) {
                val error=rejects(action) as EvidenceProblems
                assertThat(error.issues.any { it.location.photo.id==badPhoto.id })
            }
        }
        badFile.delete(); diagnostic(EvidenceError.MISSING)
        badFile.writeBytes(byteArrayOf()); diagnostic(EvidenceError.EMPTY)
        badFile.writeBytes(jpegBytes.copyOf(jpegBytes.size-2)); diagnostic(EvidenceError.CORRUPT)
        badFile.writeBytes("not an image".toByteArray()); diagnostic(EvidenceError.UNSUPPORTED)
        badFile.writeBytes(abnormal.single { it.first=="decode" }.second); diagnostic(EvidenceError.DECODE)
        badFile.writeBytes(abnormal.last().second); diagnostic(EvidenceError.DIMENSIONS)
        java.io.RandomAccessFile(badFile,"rw").use { it.setLength(EvidencePolicy.EXISTING_MAX_BYTES+1) }; diagnostic(EvidenceError.TOO_LARGE)
        badFile.writeBytes(jpegBytes); assertThat(badFile.setReadable(false,false)); diagnostic(EvidenceError.UNREADABLE); badFile.setReadable(true,true)
        badFile.writeBytes("broken".toByteArray()); val original=badFile.readBytes()
        rejects { store.replaceEvidence(badPhoto,Uri.fromFile(File(context.cacheDir,"truncated"))) }
        assertThat(badFile.readBytes().contentEquals(original)); assertThat(store.listParticipations(id).single { it.id==first.id }.evidence[0]==badPhoto)
        // SQLite abort after the private file was prepared: rollback must remove only the new file.
        store.withBackupDatabase { it.execSQL("CREATE TRIGGER fail_photo BEFORE INSERT ON evidence BEGIN SELECT RAISE(ABORT,'fixture failure'); END") }
        val names=File(context.filesDir,"evidence").list()!!.toSet()
        val originalItems=store.listItems()
        rejects { store.createItem("数据库故障活动","劳动","","",1.0,"","2026-10-05","",listOf(Uri.fromFile(valid))) }
        assertThat(store.listItems()==originalItems)
        rejects { store.addEvidence(first.id,listOf(Uri.fromFile(valid))) }
        assertThat(File(context.filesDir,"evidence").list()!!.toSet()==names)
        store.withBackupDatabase { it.execSQL("DROP TRIGGER fail_photo"); it.execSQL("CREATE TRIGGER fail_replace BEFORE UPDATE ON evidence BEGIN SELECT RAISE(ABORT,'fixture failure'); END") }
        rejects { store.replaceEvidence(badPhoto,Uri.fromFile(valid)) }
        assertThat(badFile.readBytes().contentEquals(original)); assertThat(File(context.filesDir,"evidence").list()!!.toSet()==names)
        store.withBackupDatabase { it.execSQL("DROP TRIGGER fail_replace") }
        store.replaceEvidence(badPhoto,Uri.fromFile(valid))
        val repaired=store.listParticipations(id).single { it.id==first.id }.evidence[0]
        assertThat(repaired.id==badPhoto.id && repaired.participationId==badPhoto.participationId && repaired.fileName!=badPhoto.fileName)
        assertThat(store.evidenceDiagnostics().isEmpty())
        val started=java.util.concurrent.CountDownLatch(1); val done=java.util.concurrent.CountDownLatch(1)
        var worker: Thread?=null; var workerError: Throwable?=null
        val issues=store.evidenceDiagnostics(progress={ n,_ -> if(n==0) {
            worker=Thread { started.countDown(); try { store.replaceEvidence(webpPhoto,Uri.fromFile(valid)) }
                catch(error: Throwable) { workerError=error } finally { done.countDown() } }.also { it.start() }
            assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertThat(done.count==1L)
        } })
        worker!!.join(5000); assertThat(done.count==0L && workerError==null && issues.isEmpty())
        assertThat(DuplicateReviewFinder.pending(store.listItems(),store.listItems().associate { it.id to store.listParticipations(it.id) },store.listDuplicateConfirmations()).isEmpty())
        rejects { store.deleteEvidence(badPhoto) } // stale UI cannot remove replacement
        assertThat(store.evidenceFile(repaired.fileName).isFile)
        store.deleteEvidence(repaired)
        assertThat(store.listItems().any { it.id==id }); assertThat(store.listParticipations(id).any { it.id==first.id })
        assertThat(store.listParticipations(id).single { it.id==first.id }.evidence.single()==first.evidence[1])
        assertThat(DuplicateReviewFinder.pending(store.listItems(),store.listItems().associate { it.id to store.listParticipations(it.id) },store.listDuplicateConfirmations()).isNotEmpty())
        val legacy=File(context.filesDir,"evidence/legacy-unreferenced").also { it.writeBytes(pngBytes) }
        val orphan=File(context.filesDir,"evidence/evi_${UUID.randomUUID()}").also { it.writeBytes(pngBytes) }
        val part=File(context.filesDir,"evidence-staging/${UUID.randomUUID()}.part").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1)) }
        store.close(); store=RecordStore(context)
        assertThat(!orphan.exists() && !part.exists() && legacy.exists())
        assertThat(store.evidenceFile(first.evidence[1].fileName).isFile)
        val stageDir=File(context.filesDir,"evidence-staging"); stageDir.deleteRecursively(); stageDir.writeText("blocked")
        val intact=store.listParticipations(id)
        rejects { store.addEvidence(first.id,listOf(Uri.fromFile(valid))) }
        assertThat(store.listParticipations(id)==intact)
        stageDir.delete(); stageDir.mkdir()
        // Validate a current full backup round trip in addition to the independent P1-08 death phases.
        val archive=FullBackup.capture(context,store)
        archive.inputStream().use { FullBackup.verify(context,it).close() }
        val entries=ZipFile(archive).use { backup -> backup.entries().asSequence().associate { it.name to backup.getInputStream(it).readBytes() }.toMutableMap() }
        val path=entries.keys.first { it.startsWith("attachments/") }; val damaged="invalid image with valid SHA".toByteArray(); entries[path]=damaged
        val manifest=org.json.JSONObject(String(entries.getValue("manifest.json")))
        val descriptors=manifest.getJSONArray("files")
        repeat(descriptors.length()) { i -> val descriptor=descriptors.getJSONObject(i); if(descriptor.getString("path")==path) {
            descriptor.put("size",damaged.size); descriptor.put("sha256",java.security.MessageDigest.getInstance("SHA-256").digest(damaged).joinToString("") { "%02x".format(it) })
        } }
        entries["manifest.json"]=manifest.toString().toByteArray()
        val changed=ByteArrayOutputStream(); java.util.zip.ZipOutputStream(changed).use { backup -> entries.forEach { (name,bytes) -> backup.putNextEntry(java.util.zip.ZipEntry(name)); backup.write(bytes); backup.closeEntry() } }
        val failure=rejects { FullBackup.verify(context,changed.toByteArray().inputStream()).close() } as EvidenceProblems
        assertThat(failure.issues.single().description.contains("活动") && failure.issues.single().description.contains("参与"))
        assertThat(store.evidenceDiagnostics().isEmpty()); FullBackup.discard(archive)
        store.close()
    }
    private inline fun StagedActivityPackage.usePackage(action: (StagedActivityPackage)->Unit) { try { action(this) } finally { close() } }
}
