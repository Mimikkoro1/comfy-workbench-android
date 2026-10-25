package com.mie.kreaworkbench.ui.theme

enum class ThemeKind(val id: String) { GLASS("glass"), CLASSIC("classic") }

/** 读取存储值：只认 "classic"，其它一切（含 null、空串、未知值）回落毛玻璃。 */
fun resolveThemeKind(raw: String?): ThemeKind =
    if (raw == ThemeKind.CLASSIC.id) ThemeKind.CLASSIC else ThemeKind.GLASS

/** 外观页的行，顺序即显示顺序。 */
enum class AppearanceRow { STYLE, THEME, LANGUAGE, DYNAMIC, AMOLED, BLUR }

/**
 * 外观页要显示哪些行：
 *  - 风格、语言永远显示；
 *  - 经典：主题（浅/深/跟随）、动态取色（API 31+ 才有）、纯黑深色 —— 这三项只在经典下生效；
 *  - 毛玻璃：模糊效果 —— 只在毛玻璃下生效。
 * 隐藏的行不删存储值，切回来仍是原来的选择。
 */
fun appearanceRows(kind: ThemeKind, sdkInt: Int): List<AppearanceRow> = buildList {
    add(AppearanceRow.STYLE)
    if (kind == ThemeKind.CLASSIC) add(AppearanceRow.THEME)
    add(AppearanceRow.LANGUAGE)
    if (kind == ThemeKind.CLASSIC) {
        if (sdkInt >= 31) add(AppearanceRow.DYNAMIC)
        add(AppearanceRow.AMOLED)
    } else {
        add(AppearanceRow.BLUR)
    }
}
