package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.12/4.15/4.16 输出节点白名单与优先级、抽卡判定、baseClass，及各 fixture 的输出判定。 */
class OutputNodesTest {

    // ---------- §4.12 输出节点白名单与优先级 ----------

    @Test
    fun imageSaverPriorityAndFuzzyMiss() {
        val wf = JSONObject(
            """{"9":{"class_type":"PreviewImage"},""" +
                """"10":{"class_type":"SaveImage"},""" +
                """"2":{"class_type":"SaveImage"},""" +
                """"3":{"class_type":"Image Save WAS|x"},""" +
                """"4":{"class_type":"SaveImageWebsocket"},""" +
                """"5":{"class_type":"SaveImageAdvanced"},""" +
                """"6":{"class_type":"ShowText|pysssss"},""" +
                """"7":{"class_type":"SaveVideo"}}""",
        )
        // 组内按数字排序，跨组 SaveImage > Advanced > Preview > 模糊兜底
        assertEquals("imageSaverNodes", listOf("2", "10", "5", "9", "4"), imageSaverNodes(wf))
        assertEquals("pickOutputNode", "2", pickOutputNode(wf))
        // 现状锁定：`Image Save WAS` 不含 saveimage/previewimage 子串，不命中模糊兜底
        assertFalse("Image Save WAS 不命中", imageSaverNodes(wf).contains("3"))
        assertEquals("textOutputNodes", listOf("6"), textOutputNodes(wf))
        assertEquals("videoOutputNodes", listOf("7"), videoOutputNodes(wf))
        assertEquals("inferOutputKind video 优先", "video", inferOutputKind(wf))
    }

    // ---------- §4.15 isTextToImageWorkflow ----------

    @Test
    fun isTextToImageWorkflowCases() {
        val plain = listOf(JSONObject("""{"node_id":"1","field":"text","type":"prompt_pool"}"""))
        val withImage = listOf(JSONObject("""{"node_id":"1","field":"image","type":"file:image"}"""))
        assertTrue("image 且无参考图槽", isTextToImageWorkflow("image", plain))
        assertFalse("有 file:image → 图生图", isTextToImageWorkflow("image", withImage))
        assertFalse("video", isTextToImageWorkflow("video", plain))
        assertFalse("text", isTextToImageWorkflow("text", plain))
    }

    // ---------- §4.16 baseClass ----------

    @Test
    fun baseClassStripsSuffix() {
        assertEquals("ShowAnything", baseClass("ShowAnything|Mie"))
        assertEquals("X", baseClass(" X | y "))
    }

    // ---------- 各 fixture 的 outputKind / savers ----------

    @Test
    fun fixtureOutputKinds() {
        val t2i = FixtureLoader.loadWf("kr2turbo_t2i.workflow.json")
        assertEquals("t2i kind", "image", inferOutputKind(t2i))
        assertEquals("t2i savers", listOf("16"), imageSaverNodes(t2i))
        assertEquals("t2i pick", "16", pickOutputNode(t2i))
        assertTrue("t2i 是文生图", isTextToImageWorkflow("image", inferSpecs(t2i).map { it.spec }))

        val i2i = FixtureLoader.loadWf("kr2turbo_i2i.workflow.json")
        assertEquals("i2i kind", "image", inferOutputKind(i2i))
        assertEquals("i2i savers", listOf("16"), imageSaverNodes(i2i))
        assertFalse("i2i 有参考图槽，不是文生图", isTextToImageWorkflow("image", inferSpecs(i2i).map { it.spec }))

        val wan = FixtureLoader.loadWf("wan22_i2v_4step.workflow.json")
        assertEquals("wan kind", "video", inferOutputKind(wan))
        assertEquals("wan 无图片保存节点", emptyList<String>(), imageSaverNodes(wan))
        assertEquals("wan video 节点", listOf("76"), videoOutputNodes(wan))
        assertEquals("wan pick 为空", "", pickOutputNode(wan))

        val florence2 = FixtureLoader.loadWf("florence2_caption.workflow.json")
        assertEquals("florence2 kind", "text", inferOutputKind(florence2))
        assertEquals("florence2 text 节点", listOf("4"), textOutputNodes(florence2))
        assertEquals("florence2 无图片保存节点", emptyList<String>(), imageSaverNodes(florence2))

        val qwenT2i = FixtureLoader.loadWf("qwen21_t2i.workflow.json")
        assertEquals("qwen t2i kind", "image", inferOutputKind(qwenT2i))
        assertEquals("qwen t2i savers", listOf("9"), imageSaverNodes(qwenT2i))
        assertTrue("qwen t2i 是文生图", isTextToImageWorkflow("image", inferSpecs(qwenT2i).map { it.spec }))

        val qwenEdit = FixtureLoader.loadWf("qwen21_edit.workflow.json")
        assertEquals("qwen edit kind", "image", inferOutputKind(qwenEdit))
        assertEquals("qwen edit savers", listOf("10"), imageSaverNodes(qwenEdit))
        assertFalse("qwen edit 有参考图槽", isTextToImageWorkflow("image", inferSpecs(qwenEdit).map { it.spec }))

        val krea2 = FixtureLoader.loadWf("krea2_t2i.workflow.json")
        assertEquals("krea2 kind", "image", inferOutputKind(krea2))
        assertEquals("krea2 savers", listOf("29"), imageSaverNodes(krea2))
        assertTrue("krea2 是文生图", isTextToImageWorkflow("image", inferSpecs(krea2).map { it.spec }))
    }
}
