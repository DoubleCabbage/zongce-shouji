package cn.activityscore.app.data

import org.json.JSONArray

internal object HistorySourcesJson {
    fun encode(sources: List<HistorySource>): String = JSONArray().apply {
        sources.forEach { source ->
            require(source.row > 0)
            put(JSONArray().put(source.sheet).put(source.row).put(source.rawScore))
        }
    }.toString()

    fun decode(text: String): List<HistorySource> {
        val values = JSONArray(text)
        require(values.length() <= 100_000) { "参考表来源过多" }
        return (0 until values.length()).map { index ->
            val value = values.getJSONArray(index)
            require(value.length() == 3 && value.get(0) is String && value.get(2) is String)
            val row = value.get(1)
            require(row is Int && row > 0) { "参考表来源行号无效" }
            HistorySource(value.getString(0), row, value.getString(2))
        }
    }
}
