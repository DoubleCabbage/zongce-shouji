package cn.activityscore.app.data

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.text.Normalizer

enum class NumericRule { NON_NEGATIVE, POSITIVE, PERCENT }

data class ParsedNumber(val value: Double?, val error: String? = null) {
    val isValid: Boolean get() = error == null
}

/** Double remains the storage type. Edit text is never produced by the display formatter. */
object NumericValues {
    const val INPUT_HINT = "数字使用点号小数（如 1.5），不使用逗号或千位分隔符"
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    // Keep comma-separated numeric tokens whole so ambiguous decimals/thousands are never split into hints.
    private val candidateNumber = Regex("[+-]?(?:[0-9]+(?:[.,][0-9]+)*\\.?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    /** Suggestions only: the importer must still wait for an explicit user selection. */
    fun historyCandidates(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).replace('\u2212', '-')
        val seen = mutableSetOf<Double>()
        return candidateNumber.findAll(normalized).mapNotNull { match ->
            val parsed = parse(match.value, optional = false)
            val value = parsed.value
            if (parsed.isValid && value != null && seen.add(if (value == 0.0) 0.0 else value)) match.value else null
        }.toList()
    }

    fun accepts(value: Double, rule: NumericRule = NumericRule.NON_NEGATIVE): Boolean =
        value.isFinite() && when (rule) {
            NumericRule.NON_NEGATIVE -> value >= 0.0
            NumericRule.POSITIVE -> value > 0.0
            NumericRule.PERCENT -> value in 0.0..100.0
        }

    fun parse(text: String, optional: Boolean = true,
              rule: NumericRule = NumericRule.NON_NEGATIVE): ParsedNumber {
        val raw = text.trim()
        if (raw.isEmpty()) return if (optional) ParsedNumber(null) else ParsedNumber(null, "不能为空")
        if (!decimal.matches(raw)) return ParsedNumber(null, "格式无效；$INPUT_HINT")
        val value = raw.toDoubleOrNull()
        if (value == null || !value.isFinite()) return ParsedNumber(null, "必须是有限数值（不能溢出）")
        // Do not silently turn a nonzero exponent input into zero through Double underflow.
        if (value == 0.0 && raw.substringBefore('e').substringBefore('E').any { it in '1'..'9' })
            return ParsedNumber(null, "数值过小，不能用 Double 保留；请核对原值")
        if (!accepts(value, rule)) return ParsedNumber(null, when (rule) {
            NumericRule.NON_NEGATIVE -> "不能为负数"
            NumericRule.POSITIVE -> "必须大于 0"
            NumericRule.PERCENT -> "必须在 0–100 之间"
        })
        return ParsedNumber(value)
    }

    fun requireValid(value: Double?, label: String,
                     rule: NumericRule = NumericRule.NON_NEGATIVE, optional: Boolean = true) {
        require(if (value == null) optional else accepts(value, rule)) { "$label 无效：必须是符合范围的有限数值" }
    }

    /** Includes invalid old values verbatim, so opening a form does not hide them as blank/zero. */
    fun formatForEdit(value: Double?): String = value?.toString().orEmpty()

    fun formatForDisplay(value: Double): String =
        if (!value.isFinite()) "数值异常：$value" else
            DecimalFormat("0.##", DecimalFormatSymbols(Locale.ROOT)).format(value)

    /** XLSX native numbers have canonical dot decimals; text and CSV obey the explicit text rule. */
    fun parseHistory(text: String, kind: SheetCellKind): ParsedNumber {
        if (kind == SheetCellKind.OTHER) return ParsedNumber(null, "不是数字或数字文本单元格")
        if (kind == SheetCellKind.TEXT && (',' in text || '，' in text)) {
            val ambiguous = Regex("[+-]?[0-9]{1,3},[0-9]{3}").matches(text.trim())
            return ParsedNumber(null, if (ambiguous) "数字格式有歧义：无法确定小数或千位分隔；请在源表确认后改用点号小数"
                else "不支持逗号小数或千位分隔；请在源表确认后改用点号小数")
        }
        return parse(text, optional = false)
    }
}
