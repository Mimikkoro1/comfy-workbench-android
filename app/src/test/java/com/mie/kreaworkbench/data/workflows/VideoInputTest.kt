package com.mie.kreaworkbench.data.workflows

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** round17 视频输入：file:video 槽识别、VHS 读帧参数、fileSlotCounts / 校验 / 抽卡判定。 */
class VideoInputTest {

    private fun infer(name: String): List<Candidate> =
        inferSpecs(FixtureLoader.loadWf(name))

    private fun enabledCount(all: List<Candidate>): Int =
        all.count { it.spec.optBoolean("enabled") }

    // ---------- 1. vhs_v2v 视频槽 ----------

    @Test
    fun vhsV2vVideoSlot() {
        val m = specMap(infer("vhs_v2v.workflow.json"))
        val video = m.getValue("1|video").spec
        assertEquals("type", "file:video", video.optString("type"))
        assertEquals("confidence", "high", video.optString("confidence"))
        assertTrue("enabled", video.optBoolean("enabled"))
        assertEquals("label", "参考视频", video.optString("label"))
        assertEquals("default", "example_input.mp4", video.optString("default"))
        assertEquals("help", HELP_VIDEO_INPUT, video.optString("help"))
    }

    // ---------- 2. vhs_v2v 读帧参数 ----------

    @Test
    fun vhsV2vFrameParams() {
        val m = specMap(infer("vhs_v2v.workflow.json"))
        val cap = m.getValue("1|frame_load_cap").spec
        assertEquals("cap type", "int", cap.optString("type"))
        assertEquals("cap conf", "medium", cap.optString("confidence"))
        assertTrue("cap enabled", cap.optBoolean("enabled"))
        assertTrue("cap kwb_gen", cap.optBoolean("kwb_gen"))
        assertEquals("cap default", 81L, cap.optLong("default"))
        assertEquals("cap min", 0, cap.optInt("min"))
        assertEquals("cap label", "最多帧数", cap.optString("label"))

        val skip = m.getValue("1|skip_first_frames").spec
        assertEquals("skip type", "int", skip.optString("type"))
        assertEquals("skip conf", "medium", skip.optString("confidence"))
        assertTrue("skip enabled", skip.optBoolean("enabled"))
        assertEquals("skip default", 0L, skip.optLong("default"))

        val nth = m.getValue("1|select_every_nth").spec
        assertEquals("nth type", "int", nth.optString("type"))
        assertEquals("nth conf", "medium", nth.optString("confidence"))
        assertFalse("nth 默认不勾", nth.optBoolean("enabled"))
        assertEquals("nth min", 1, nth.optInt("min"))

        val rate = m.getValue("1|force_rate").spec
        assertEquals("rate type（JSON 整数 0）", "int", rate.optString("type"))
        assertEquals("rate conf", "medium", rate.optString("confidence"))
        assertFalse("rate 默认不勾", rate.optBoolean("enabled"))

        // custom_width / custom_height / format 不加规则，照旧低置信
        val w = m.getValue("1|custom_width").spec
        assertEquals("custom_width type", "int", w.optString("type"))
        assertEquals("custom_width conf", "low", w.optString("confidence"))
        assertFalse("custom_width 不勾", w.optBoolean("enabled"))
        val fmt = m.getValue("1|format").spec
        assertEquals("format type", "text", fmt.optString("type"))
        assertEquals("format conf", "low", fmt.optString("confidence"))
    }

    // ---------- 3. vhs_v2v 输出与抽卡判定 ----------

    @Test
    fun vhsV2vOutputAndDraw() {
        val wf = FixtureLoader.loadWf("vhs_v2v.workflow.json")
        val all = inferSpecs(wf)
        assertEquals("inferOutputKind", "video", inferOutputKind(wf))
        assertEquals("videoOutputNodes", listOf("3"), videoOutputNodes(wf))
        assertFalse("有视频槽就不算文生图", isTextToImageWorkflow("video", all.map { it.spec }))
    }

    // ---------- 4. animate_transfer：双 VHS 合并 + 图片槽 ----------

    @Test
    fun animateTransferMerge() {
        val all = infer("animate_transfer.workflow.json")
        // 实跑值（27 候选 / 9 勾选）：节点 2 的 8 个字段全部与节点 1 同值合并
        assertEquals("候选总数", 27, all.size)
        assertEquals("enabled 数", 9, enabledCount(all))
        val m = specMap(all)
        val video = m.getValue("1|video").spec
        assertEquals("1|video type", "file:video", video.optString("type"))
        val mirror = video.optJSONArray("mirror_to")
        assertEquals("1|video mirror_to 一条", 1, mirror?.length() ?: -1)
        assertEquals("mirror 目标节点", "2", mirror!!.optJSONObject(0)!!.optString("node_id"))
        assertEquals("mirror 目标字段", "video", mirror.optJSONObject(0)!!.optString("field"))
        assertFalse("2|video 已合并", m.containsKey("2|video"))
        val image = m.getValue("3|image").spec
        assertEquals("3|image type", "file:image", image.optString("type"))
        val capMirror = m.getValue("1|frame_load_cap").spec.optJSONArray("mirror_to")
        assertEquals("1|frame_load_cap mirror 目标", "2", capMirror!!.optJSONObject(0)!!.optString("node_id"))
    }

    // ---------- 5. animate_transfer 槽位计数 ----------

    @Test
    fun animateTransferSlotCounts() {
        val all = infer("animate_transfer.workflow.json")
        val def = buildDefinition(
            displayName = "animate transfer",
            workflowFileName = "animate_transfer.workflow.json",
            outputNode = "7",
            orderedSpecs = all.filter { it.spec.optBoolean("enabled") }.map { it.spec },
            outputKind = "video",
        )
        assertEquals(
            "fileSlotCounts",
            linkedMapOf("file:image" to 1, "file:video" to 1),
            LinkedHashMap(fileSlotCounts(def.optJSONArray("user_facing_inputs"))),
        )
    }

    // ---------- 6. 抽卡判定：图片输出但有视频槽 ----------

    @Test
    fun drawBlockedByVideoSlot() {
        val spec = JSONObject().put("type", "file:video")
        assertFalse(isTextToImageWorkflow("image", listOf(spec)))
        // 对照：没有文件槽仍算文生图
        assertTrue(isTextToImageWorkflow("image", listOf(JSONObject().put("type", "text"))))
    }

    // ---------- 7. 核心 LoadVideo ----------

    @Test
    fun coreLoadVideo() {
        val wf = FixtureLoader.loadWf("core_loadvideo.workflow.json")
        val m = specMap(inferSpecs(wf))
        val file = m.getValue("1|file").spec
        assertEquals("type", "file:video", file.optString("type"))
        assertEquals("confidence", "high", file.optString("confidence"))
        assertTrue("enabled", file.optBoolean("enabled"))
        assertEquals("default", "clip.mp4", file.optString("default"))
        assertEquals("inferOutputKind", "video", inferOutputKind(wf))
        assertEquals("videoOutputNodes", listOf("4"), videoOutputNodes(wf))
        val prefix = m.getValue("4|filename_prefix").spec
        assertEquals("filename_prefix conf", "low", prefix.optString("confidence"))
        assertFalse("filename_prefix 不勾", prefix.optBoolean("enabled"))
    }

    // ---------- 8. VHS_LoadVideoPath 不当上传槽 ----------

    @Test
    fun videoPathStaysLowText() {
        val m = specMap(
            inferSpecs(
                JSONObject(
                    """{"1":{"class_type":"VHS_LoadVideoPath","_meta":{"title":"Path"},""" +
                        """"inputs":{"video":"X:/videos/a.mp4","force_rate":0,"frame_load_cap":0}}}""",
                ),
            ),
        )
        val video = m.getValue("1|video").spec
        assertEquals("type", "text", video.optString("type"))
        assertEquals("confidence", "low", video.optString("confidence"))
        assertFalse("enabled", video.optBoolean("enabled"))
        val cap = m.getValue("1|frame_load_cap").spec
        assertEquals("frame_load_cap type", "int", cap.optString("type"))
        assertEquals("frame_load_cap conf", "low", cap.optString("confidence"))
    }

    // ---------- 9. FFmpeg 的 start_time ----------

    @Test
    fun ffmpegStartTimeFloat() {
        val m = specMap(
            inferSpecs(
                JSONObject(
                    """{"1":{"class_type":"VHS_LoadVideoFFmpeg","_meta":{"title":"FFmpeg"},""" +
                        """"inputs":{"video":"a.mp4","start_time":2,"frame_load_cap":0,"force_rate":0}}}""",
                ),
            ),
        )
        assertEquals("video type", "file:video", m.getValue("1|video").spec.optString("type"))
        val st = m.getValue("1|start_time").spec
        assertEquals("start_time type", "float", st.optString("type"))
        assertEquals("start_time conf", "medium", st.optString("confidence"))
        assertFalse("start_time 默认不勾", st.optBoolean("enabled"))
        assertEquals("start_time default（整数也写 float）", 2.0, st.optDouble("default"), 0.0)
        assertEquals("start_time label", "开始时间（秒）", st.optString("label"))
    }

    // ---------- 10. 带节点包后缀的 class ----------

    @Test
    fun videoLoaderWithSuffix() {
        val spec = specMap(
            inferSpecs(
                JSONObject(
                    """{"1":{"class_type":"VHS_LoadVideo|custom","_meta":{"title":"V"},""" +
                        """"inputs":{"video":"b.webm"}}}""",
                ),
            ),
        ).getValue("1|video").spec
        assertEquals("type", "file:video", spec.optString("type"))
        assertTrue("enabled", spec.optBoolean("enabled"))
    }

    // ---------- 11. fileSlotCounts 纯函数 ----------

    @Test
    fun fileSlotCountsCases() {
        val empty = fileSlotCounts(null)
        assertEquals(0, empty.getValue("file:image"))
        assertEquals(0, empty.getValue("file:video"))
        val none = fileSlotCounts(JSONArray("[]"))
        assertEquals(0, none.getValue("file:image"))
        assertEquals(0, none.getValue("file:video"))
        val twoVideos = JSONArray()
            .put(JSONObject().put("type", "file:video"))
            .put(JSONObject().put("type", "file:video"))
        assertEquals(2, fileSlotCounts(twoVideos).getValue("file:video"))
        assertEquals(0, fileSlotCounts(twoVideos).getValue("file:image"))
    }

    // ---------- 12. KNOWN_TYPES 含 file:video ----------

    @Test
    fun knownTypesIncludeVideo() {
        // round17 二选一里选了「改可见性」：KNOWN_TYPES 改成 internal @VisibleForTesting，直接断言
        assertTrue("file:video" in KNOWN_TYPES)
        assertTrue("file:image" in KNOWN_TYPES)
    }
}
