package com.mie.kreaworkbench.data.settings

import org.junit.Assert.assertFalse
import org.junit.Test

/** r50 即时预览开关：默认关（PLAN §6——Turbo 低步数价值低 + 很多服务器 --preview-method none）。 */
class LivePreviewDefaultTest {

    @Test
    fun `live preview defaults to off`() {
        assertFalse(UserSettings().livePreviewEnabled)
    }
}
