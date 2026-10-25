package com.mie.kreaworkbench.ui.screens.custom

/** 老版本 / 手改 JSON 里可能存过「全部」二字当哨兵；读回时统一成空串（空串 = 全部）。 */
internal fun normalizeDrawCat(raw: String?): String {
    val t = raw.orEmpty().trim()
    return if (t == "全部") "" else t
}
