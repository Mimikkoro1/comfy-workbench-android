package com.mie.kreaworkbench.ui.screens.custom

/**
 * r43：生成失败弹窗的完整文本。error 是给用户看的一句话；detail 是 r42 拿到的
 * ComfyUI execution_error（只在当次进程内存里有效），有就空一行追加在后面。
 * 纯 JVM 逻辑：error 空、detail 有 → 只返回 detail（不以空行开头）；两者都空 → 空串，
 * 调用方用 err_unknown_reason 兜底。
 */
fun errorDialogText(error: String, detail: String?): String {
    val e = error.trim()
    val d = detail?.trim().orEmpty()
    return when {
        d.isEmpty() -> e
        e.isEmpty() -> d
        else -> "$e\n\n$d"
    }
}

/**
 * r44：复制用的全文 = 摘要 + 详情 + 原始报错，非空段按空行拼接、空白段跳过、全空返回空串。
 * 与 [errorDialogText] 的区别：多带 raw（ComfyUI 原文，方便贴给别人排查），不受折叠状态影响。
 */
fun errorCopyText(error: String, detail: String?, raw: String?): String =
    listOf(error.trim(), detail?.trim().orEmpty(), raw?.trim().orEmpty())
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
