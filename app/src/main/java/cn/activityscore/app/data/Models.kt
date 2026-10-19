package cn.activityscore.app.data

val scoreDimensions = listOf(
    "身心健康",
    "学术创新",
    "思想成长",
    "劳动",
    "美育",
    "组织协调能力",
)

val scoreDimensionCaps = mapOf(
    "身心健康" to 10,
    "学术创新" to 14,
    "思想成长" to 14,
    "劳动" to 12,
    "美育" to 12,
    "组织协调能力" to 8,
)

data class ScoreItem(
    val id: Long,
    val title: String,
    val dimension: String,
    val subcategory: String,
    val role: String,
    val referenceScore: Double?,
    val note: String,
    val createdAt: Long,
)

data class Participation(
    val id: Long,
    val itemId: Long,
    val eventDate: String,
    val note: String,
    val evidence: List<EvidencePhoto>,
)

data class EvidencePhoto(
    val id: Long,
    val participationId: Long,
    val fileName: String,
)

data class ScoreSummary(
    val dimension: String,
    val itemCount: Int,
    val participationCount: Int,
    val referenceTotal: Double,
    val missingReferenceCount: Int,
)
