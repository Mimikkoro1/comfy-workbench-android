package com.mie.kreaworkbench.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeStyleTest {

    @Test
    fun `classic recognized only case-sensitively`() {
        assertEquals(ThemeKind.CLASSIC, resolveThemeKind("classic"))
        assertEquals(ThemeKind.GLASS, resolveThemeKind("glass"))
        assertEquals(ThemeKind.GLASS, resolveThemeKind(null))
        assertEquals(ThemeKind.GLASS, resolveThemeKind(""))
        assertEquals(ThemeKind.GLASS, resolveThemeKind("Classic"))
        assertEquals(ThemeKind.GLASS, resolveThemeKind("modern"))
    }

    @Test
    fun `glass rows on any api`() {
        assertEquals(
            listOf(AppearanceRow.STYLE, AppearanceRow.LANGUAGE, AppearanceRow.BLUR),
            appearanceRows(ThemeKind.GLASS, 34),
        )
        assertEquals(
            listOf(AppearanceRow.STYLE, AppearanceRow.LANGUAGE, AppearanceRow.BLUR),
            appearanceRows(ThemeKind.GLASS, 28),
        )
    }

    @Test
    fun `classic rows with dynamic color on api 31 plus`() {
        assertEquals(
            listOf(
                AppearanceRow.STYLE,
                AppearanceRow.THEME,
                AppearanceRow.LANGUAGE,
                AppearanceRow.DYNAMIC,
                AppearanceRow.AMOLED,
            ),
            appearanceRows(ThemeKind.CLASSIC, 34),
        )
        assertEquals(
            listOf(AppearanceRow.STYLE, AppearanceRow.THEME, AppearanceRow.LANGUAGE, AppearanceRow.AMOLED),
            appearanceRows(ThemeKind.CLASSIC, 30),
        )
        assert(appearanceRows(ThemeKind.CLASSIC, 31).contains(AppearanceRow.DYNAMIC))
    }

    @Test
    fun `stored ids stay stable`() {
        assertEquals(listOf("glass", "classic"), ThemeKind.entries.map { it.id })
    }
}
