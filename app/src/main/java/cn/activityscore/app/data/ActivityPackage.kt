package cn.activityscore.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class SharedActivity(
    val shareId: String,
    val title: String,
    val dimension: String,
    val subcategory: String,
    val referenceScore: Double?,
    val note: String,
    val eventDate: String,
    val eventNote: String,
)

class StagedActivityPackage(
    val activity: SharedActivity,
    val photos: List<File>,
    private val directory: File,
) {
    fun close() { directory.deleteRecursively() }
}

/** One activity participation plus optional shared photos. No personal grades are included. */
object ActivityPackage {
    private const val FORMAT = "cn.activityscore.shared-activity"
    private const val MAX_PHOTOS = 30
    private const val MAX_PHOTO_BYTES = 30L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64L * 1024

    fun write(
        store: RecordStore,
        item: ScoreItem,
        participation: Participation,
        selectedPhotos: List<EvidencePhoto>,
        output: OutputStream,
    ) = store.withBackupDatabase {
        NumericDataValidation.reference(item.referenceScore, "活动 #${item.id} 参考分数")
        require(selectedPhotos.size <= MAX_PHOTOS) { "一次最多分享 $MAX_PHOTOS 张图片" }
        require(selectedPhotos.all { it in participation.evidence }) { "图片不属于这次参与" }
        val current = store.listParticipations(item.id).singleOrNull { it.id == participation.id }
        require(current != null && selectedPhotos.all { it in current.evidence }) { "证明或参与已被修改，请重新打开记录后分享" }
        val shareId = store.shareIdFor(participation.id)
        val paths = selectedPhotos.indices.map { "photos/${it + 1}" }
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", 1)
            put("shareId", shareId)
            put("title", item.title)
            put("dimension", item.dimension)
            put("subcategory", item.subcategory)
            if (item.referenceScore == null) put("referenceScore", JSONObject.NULL)
            else put("referenceScore", item.referenceScore)
            put("note", item.note)
            put("eventDate", participation.eventDate)
            put("eventNote", participation.note)
            put("photos", JSONArray(paths))
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            var total = 0L
            selectedPhotos.forEachIndexed { index, photo ->
                val file = store.evidenceFile(photo.fileName)
                val location = EvidenceLocation(item.id, item.title, participation.id, participation.eventDate, photo)
                val problem = EvidenceImages.issue(location, file)
                if (problem != null) throw EvidenceProblems(listOf(problem))
                require(file.length() <= MAX_PHOTO_BYTES) { "${location.label}：活动包单张图片超过 30 MiB" }
                total += file.length()
                require(total <= MAX_TOTAL_BYTES) { "图片总量超过 200 MB" }
                zip.putNextEntry(ZipEntry(paths[index]))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun read(context: Context, uri: Uri): StagedActivityPackage {
        val directory = File.createTempFile("activity-import-", "", context.cacheDir)
        check(directory.delete() && directory.mkdir()) { "无法准备导入空间" }
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("无法读取活动包")
            ZipInputStream(input.buffered()).use { zip ->
                val first = zip.nextEntry ?: error("活动包为空")
                require(first.name == "manifest.json" && !first.isDirectory) {
                    "这不是本 App 生成的活动包"
                }
                val manifestBytes = ByteArrayOutputStream()
                copyLimited(zip, manifestBytes, MAX_MANIFEST_BYTES)
                zip.closeEntry()
                val json = JSONObject(manifestBytes.toString(Charsets.UTF_8.name()))
                require(json.optString("format") == FORMAT && json.optInt("version") == 1) {
                    "活动包格式或版本不受支持"
                }
                val shareId = json.getString("shareId")
                UUID.fromString(shareId)
                val title = json.getString("title").trim()
                val dimension = json.getString("dimension")
                val date = json.getString("eventDate")
                LocalDate.parse(date)
                require(title.isNotBlank() && title.length <= 200 && dimension in scoreDimensions) {
                    "活动名称或维度无效"
                }
                val score = if (json.isNull("referenceScore")) null else {
                    val parsed = NumericValues.parse(json.get("referenceScore").toString(), optional = false)
                    require(parsed.isValid) { "活动包参考分数：${parsed.error}" }
                    parsed.value
                }
                val activity = SharedActivity(
                    shareId, title, dimension,
                    json.optString("subcategory").take(200), score,
                    json.optString("note").take(4000), date,
                    json.optString("eventNote").take(4000),
                )
                val declared = json.getJSONArray("photos")
                require(declared.length() <= MAX_PHOTOS) { "图片数量超过上限" }
                val paths = (0 until declared.length()).map { index ->
                    declared.getString(index).also { path ->
                        require(path == "photos/${index + 1}") { "图片清单无效" }
                    }
                }
                val photos = mutableListOf<File>()
                var total = 0L
                paths.forEachIndexed { index, expected ->
                    val entry = zip.nextEntry ?: error("活动包缺少图片")
                    require(!entry.isDirectory && entry.name == expected) { "活动包图片顺序无效" }
                    val file = File(directory, "photo-${index + 1}")
                    file.outputStream().use { output ->
                        total += copyLimited(zip, output, MAX_PHOTO_BYTES)
                    }
                    require(total <= MAX_TOTAL_BYTES) { "图片总量超过 200 MB" }
                    try { EvidenceImages.validate(file, MAX_PHOTO_BYTES) }
                    catch (error: EvidenceInvalid) { throw EvidenceInvalid(error.type, "$title / $date / 活动包证明 ${index + 1}：${error.message}", error) }
                    photos += file
                    zip.closeEntry()
                }
                require(zip.nextEntry == null) { "活动包包含未声明的文件" }
                return StagedActivityPackage(activity, photos, directory)
            }
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun copyLimited(input: java.io.InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit) { "活动包中的文件超过大小限制" }
            output.write(buffer, 0, count)
        }
    }
}
