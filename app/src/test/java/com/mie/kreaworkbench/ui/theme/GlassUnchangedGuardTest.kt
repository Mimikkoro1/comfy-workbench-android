package com.mie.kreaworkbench.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r46 经典主题的「毛玻璃不变」守卫（源码级，不需要 Android 运行时）。
 *
 * r46 把 ImmersiveInk / GlassPanelFallback 这类毛玻璃颜色常量改成了 @Composable 取值，
 * 经典分支取主题色、毛玻璃分支必须**逐字返回原来的字面量**。这里把这些字面量钉在源码里：
 * 谁要是「顺手整理」了毛玻璃一侧的值、alpha、模糊样式，这条测试就红。
 * 以后往经典分支加东西没问题，别动 else 里的原值。
 */
class GlassUnchangedGuardTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("..", relative), File("../..", relative))
        val f = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到源文件，试过：${candidates.joinToString { it.absolutePath }}",
            f != null,
        )
        return f!!.readText()
    }

    private fun mainSource(vararg parts: String): String =
        source("app/src/main/java/com/mie/kreaworkbench/" + parts.joinToString("/"))

    private fun assertLiteral(file: String, vararg literals: String) {
        for (lit in literals) {
            assertTrue("$file 缺少毛玻璃原值：$lit", file.contains(lit))
        }
    }

    @Test
    fun `immersive chrome glass literals unchanged`() {
        assertLiteral(
            mainSource("ui", "components", "ImmersiveChrome.kt"),
            "Color.Black.copy(alpha = 0.58f)",
            "Color.Black.copy(alpha = 0.50f)",
            "Color.White.copy(alpha = 0.72f)",
            "Color.White.copy(alpha = 0.22f)",
            "Color.Black.copy(alpha = 0.65f)",
        )
    }

    @Test
    fun `glass dialogs literals unchanged`() {
        assertLiteral(
            mainSource("ui", "components", "GlassDialogs.kt"),
            "Color.Black.copy(alpha = 0.86f)",
            "Color.Black.copy(alpha = 0.55f)",
            "Color(0xFFFF8A80)",
            "Color.Black.copy(alpha = 0.38f * scrim.value)",
            "GLASS_OPEN_MS",
        )
    }

    @Test
    fun `glass controls literals unchanged`() {
        assertLiteral(
            mainSource("ui", "components", "GlassControls.kt"),
            "Color.White.copy(alpha = 0.08f)",
            "Color.White.copy(alpha = 0.70f)",
        )
    }

    @Test
    fun `app nav scrim literals unchanged`() {
        assertLiteral(
            mainSource("ui", "nav", "AppNav.kt"),
            "alpha = 0.75f",
            "alpha = 0.85f",
            "alpha = 0.48f",
            "alpha = 0.52f",
        )
    }

    @Test
    fun `settings pages veil literals unchanged`() {
        assertLiteral(
            mainSource("ui", "screens", "settings", "SettingsPages.kt"),
            "alpha = 0.48f",
            "alpha = 0.52f",
        )
    }

    @Test
    fun `glass blur style unchanged`() {
        assertLiteral(
            mainSource("ui", "components", "Glass.kt"),
            "HazeBlurStyle.Material3",
        )
    }

    @Test
    fun `local theme kind defaults to glass`() {
        assertLiteral(
            mainSource("ui", "theme", "Theme.kt"),
            "staticCompositionLocalOf { ThemeKind.GLASS }",
        )
    }
}
