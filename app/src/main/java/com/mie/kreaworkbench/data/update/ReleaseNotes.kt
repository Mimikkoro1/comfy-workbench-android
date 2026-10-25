package com.mie.kreaworkbench.data.update

/**
 * r40：Release 说明的最小 Markdown 渲染（纯 Kotlin，无 Android 依赖，方便单测）。
 * 发布说明固定格式：
 * ```
 * # Comfy工作台 v0.4.0
 * ## 中文
 * - ……
 * ## English
 * - ……
 * ```
 * 只认这一小撮语法，其余当普通段落；不引入任何 Markdown 第三方库。
 */

/** 一段行内文本。bold = `**粗体**` 识别出来的段。 */
data class NoteSpan(val text: String, val bold: Boolean = false)

/** 一个渲染块。空行不产块（渲染端统一用段间距表达）。 */
sealed interface NoteBlock {
    /** `### xxx` / `## xxx` 小标题，渲染成粗体。 */
    data class Heading(val spans: List<NoteSpan>) : NoteBlock

    /** `- xxx` / `* xxx` 列表项，渲染时前面画 `•`。 */
    data class Bullet(val spans: List<NoteSpan>) : NoteBlock

    /** 普通段落。 */
    data class Paragraph(val spans: List<NoteSpan>) : NoteBlock
}

private val ZH_SECTION_HEADINGS = setOf("中文", "简体中文", "chinese")
private val EN_SECTION_HEADINGS = setOf("english", "英文")
private val HEADING_LINE = Regex("^#{2,3}\\s+(.+)$")
private val BULLET_LINE = Regex("^[-*]\\s+(.+)$")

/**
 * 从完整说明里挑出当前语言的段落。
 * 按 `## ` 二级标题切段；标题文字（trim、忽略大小写）是 中文 / 简体中文 / Chinese 的算中文段，
 * English / 英文 的算英文段。当前语言是中文取中文段，否则取英文段；
 * 找不到对应段就取另一种语言的段；两种语言的段都没有（或一个二级标题都没有）就返回全文。
 * 第一行的 `# 一级标题` 一律丢掉（弹窗标题已经有版本号）。
 */
fun pickSection(notes: String, zh: Boolean): String {
    val text = notes.replace("\r\n", "\n").trim()
    if (text.isEmpty()) return ""
    val lines = text.split('\n')
    val h2 = ArrayList<Int>()
    for ((i, line) in lines.withIndex()) {
        if (line.trimStart().startsWith("## ") || line.trimStart() == "##") h2.add(i)
    }
    // 无二级标题：全文，只丢第一行的一级标题
    if (h2.isEmpty()) return dropFirstH1(lines)
    fun body(from: Int, to: Int): String =
        lines.subList(from, to).joinToString("\n").trim()

    // 段落：标题行之后到下一个二级标题行之前；最后一段到文末
    var zhBody: String? = null
    var enBody: String? = null
    for ((idx, start) in h2.withIndex()) {
        val title = lines[start].trimStart().removePrefix("##").trim()
        val end = h2.getOrElse(idx + 1) { lines.size }
        val b = body(start + 1, end)
        when {
            ZH_SECTION_HEADINGS.any { title.equals(it, ignoreCase = true) } -> if (zhBody == null) zhBody = b
            EN_SECTION_HEADINGS.any { title.equals(it, ignoreCase = true) } -> if (enBody == null) enBody = b
        }
    }
    val picked = if (zh) zhBody ?: enBody else enBody ?: zhBody
    return picked ?: dropFirstH1(lines)
}

/** 第一行是 `# 一级标题` 就丢掉，其余原样。 */
private fun dropFirstH1(lines: List<String>): String {
    val rest = if (lines.firstOrNull()?.trimStart()?.startsWith("# ") == true) lines.drop(1) else lines
    return rest.joinToString("\n").trim()
}

/**
 * 把一段说明拆成渲染块：`### / ##` → 小标题；`- / *` → 列表项；空行 → 段落间距（不产块）；其余 → 普通段落。
 */
fun parseBlocks(text: String): List<NoteBlock> {
    val out = ArrayList<NoteBlock>()
    for (raw in text.replace("\r\n", "\n").split('\n')) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val heading = HEADING_LINE.matchEntire(line)
        val bullet = if (heading == null) BULLET_LINE.matchEntire(line) else null
        out.add(
            when {
                heading != null -> NoteBlock.Heading(parseInline(heading.groupValues[1]))
                bullet != null -> NoteBlock.Bullet(parseInline(bullet.groupValues[1]))
                else -> NoteBlock.Paragraph(parseInline(line))
            },
        )
    }
    return out
}

/**
 * 行内语法：`**粗体**` → 粗体；`` `code` `` → 去掉反引号；`[文字](链接)` → 只显示文字。
 * 没配对成功的记号原样保留。
 */
fun parseInline(text: String): List<NoteSpan> {
    val out = ArrayList<NoteSpan>()
    var i = 0
    while (i < text.length) {
        val start = text.indexOf("**", i)
        if (start < 0) {
            addPlain(out, text.substring(i))
            break
        }
        val end = text.indexOf("**", start + 2)
        if (end < 0) {
            addPlain(out, text.substring(i))
            break
        }
        if (start > i) addPlain(out, text.substring(i, start))
        val bold = text.substring(start + 2, end)
        if (bold.isNotBlank()) {
            out.add(NoteSpan(bold, bold = true))
        } else {
            addPlain(out, "**")
        }
        i = end + 2
    }
    return out
}

/** 非粗体文本：处理 `code` 反引号与 [文字](链接)，其余原样。 */
private fun addPlain(out: MutableList<NoteSpan>, raw: String) {
    if (raw.isEmpty()) return
    val sb = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        when {
            ch == '`' -> {
                val close = raw.indexOf('`', i + 1)
                if (close > i) {
                    sb.append(raw, i + 1, close)
                    i = close + 1
                } else {
                    sb.append(ch)
                    i++
                }
            }
            ch == '[' -> {
                val close = raw.indexOf(']', i + 1)
                if (close > i && close + 1 < raw.length && raw[close + 1] == '(') {
                    val paren = raw.indexOf(')', close + 2)
                    if (paren > close + 1) {
                        sb.append(raw, i + 1, close)
                        i = paren + 1
                        continue
                    }
                }
                sb.append(ch)
                i++
            }
            else -> {
                sb.append(ch)
                i++
            }
        }
    }
    if (sb.isNotEmpty()) out.add(NoteSpan(sb.toString()))
}
