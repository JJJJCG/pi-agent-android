package com.pi.assistant.util

/**
 * TTS 之前的 Markdown 清洗。
 *
 * pi 的 reply 是 Markdown，直接念会把 `#`、`**`、`` ` ``、括号 URL 全读出来，
 * 听起来像报菜名。这里只留能读的人话。
 */
object MarkdownStripper {

    private val fenced = Regex("```[\\s\\S]*?```")
    private val image = Regex("!\\[([^]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^]]*)]\\([^)]*\\)")
    private val inlineCode = Regex("`([^`]*)`")
    private val horizontalRule = Regex("(?m)^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSeparator = Regex("(?m)^\\s*\\|?[\\s:|-]{2,}\\|\\s*$")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val blockquote = Regex("(?m)^\\s{0,3}>\\s?")
    private val listMark = Regex("(?m)^\\s{0,3}(?:[-*+]|\\d+[.)])\\s+")
    private val emphasis = Regex("[*_]{1,3}")
    private val tableCell = Regex("\\s*\\|\\s*")
    private val manyBlankLines = Regex("\\n{3,}")
    private val trailingSpace = Regex("[ \\t]+$", RegexOption.MULTILINE)

    /** 代码块被整段吞掉前给个交代，免得听的人以为语音断了。 */
    private const val CODE_BLOCK_PLACEHOLDER = "（这里有一段代码，我跳过了）"

    fun strip(markdown: String): String {
        var text = markdown
        text = fenced.replace(text, CODE_BLOCK_PLACEHOLDER)
        text = image.replace(text) { it.groupValues[1] }
        text = link.replace(text) { it.groupValues[1] }
        text = inlineCode.replace(text) { it.groupValues[1] }
        text = horizontalRule.replace(text, "")
        text = tableSeparator.replace(text, "")
        text = heading.replace(text, "")
        text = blockquote.replace(text, "")
        text = listMark.replace(text, "")
        text = tableCell.replace(text, "，")
        text = emphasis.replace(text, "")
        text = trailingSpace.replace(text, "")
        text = manyBlankLines.replace(text, "\n\n")
        return text.trim()
    }

    /** 适合做气泡摘要/通知文案的短版本。 */
    fun preview(markdown: String, max: Int = 60): String {
        val single = strip(markdown).replace(Regex("\\s+"), " ").trim()
        return if (single.length <= max) single else single.take(max) + "…"
    }
}
