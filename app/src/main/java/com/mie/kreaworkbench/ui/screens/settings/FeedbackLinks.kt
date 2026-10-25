package com.mie.kreaworkbench.ui.screens.settings

import java.net.URLEncoder

/**
 * r42 反馈入口的纯函数（无 Android 依赖，纯 JVM 单测）。
 */

/**
 * 拼 GitHub 新建 Issue 的预填链接：`<base>/issues/new?title=…&body=…`。
 * 编码用 URLEncoder，`+` 一律换成 `%20`；总长超过 [maxLen] 时从 [body] 末尾截断
 * （按未编码的字符截、截完再整体编码，保证不会切在 %XX 中间），[title] 保留完整。
 */
fun buildGithubIssueUrl(base: String, title: String, body: String, maxLen: Int = 1900): String {
    val b = base.trim().trimEnd('/')
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    fun assemble(text: String): String = "$b/issues/new?title=${enc(title)}&body=${enc(text)}"
    var cut = body
    var url = assemble(cut)
    while (url.length > maxLen && cut.isNotEmpty()) {
        cut = cut.dropLast(1)
        url = assemble(cut)
    }
    return url
}

/** 反馈 Issue 的正文模板：版本 / 设备 / Android 三行 + 调用方传入的提示段（文案走资源字符串）。 */
fun feedbackIssueBody(appVersion: String, device: String, androidVer: String, hint: String): String {
    return buildString {
        appendLine("app: $appVersion")
        appendLine("device: $device")
        appendLine("android: $androidVer")
        appendLine()
        append(hint)
    }
}
