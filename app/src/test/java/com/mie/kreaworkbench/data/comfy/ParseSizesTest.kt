package com.mie.kreaworkbench.data.comfy

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §5 parseSizes / align16（运行时尺寸对齐：向下取 16 的倍数、下限 64）。 */
class ParseSizesTest {

    @Test
    fun parseSizesAlignsTo16AndFloors64() {
        assertEquals("1000x1500 对齐 16", listOf(992 to 1488), parseSizes(JSONArray("""["1000x1500"]""")))
        // 大写 X：代码先 lowercase 再匹配
        assertEquals("1152X1728", listOf(1152 to 1728), parseSizes(JSONArray("""["1152X1728"]""")))
        assertEquals("50x50 下限 64", listOf(64 to 64), parseSizes(JSONArray("""["50x50"]""")))
        assertNull("null 入参返回 null", parseSizes(null))
    }

    @Test
    fun parseSizesSkipsUnparsable() {
        assertEquals("非法条目跳过", emptyList<Pair<Int, Int>>(), parseSizes(JSONArray("""["abc","","1024x"]""")))
    }
}
