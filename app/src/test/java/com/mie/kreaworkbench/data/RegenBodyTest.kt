package com.mie.kreaworkbench.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 看图页重新生成：同提示词、随机种子、只出一张，并丢掉上次的提交记录。 */
class RegenBodyTest {

    @Test
    fun clonesPromptForcesRandomSeedAndOneImage() {
        val raw = JSONObject()
            .put("client_job_id", "old")
            .put("mode", "custom")
            .put("workflow_id", "wf-1")
            .put("prompt", "old prompt")
            .put("batch_count", 4)
            .put("seed", 42)
            .put("seed_random", false)
            .put("comfy_prompts", JSONArray().put(JSONObject().put("prompt_id", "p")))
            .put("submit_attempted", true)
            .put(
                "values",
                JSONObject()
                    .put("3|text", "keep me")
                    .put("9|width", JSONArray().put("1152x1728").put("1024x1024"))
                    .put("9|tags", JSONArray().put("night")),
            )
            .toString()
        val body = regenerateBody(raw, "  same prompt  ", 832, 1216, "new-id")
        assertEquals("new-id", body!!.optString("client_job_id"))
        assertEquals("same prompt", body.optString("prompt"))
        assertTrue(body.optBoolean("seed_random"))
        assertEquals(1, body.optInt("batch_count"))
        assertEquals("wf-1", body.optString("workflow_id"))
        assertFalse(body.has("comfy_prompts"))
        assertFalse(body.has("submit_attempted"))
        val values = body.getJSONObject("values")
        assertEquals("keep me", values.optString("3|text"))
        assertEquals("night", values.getJSONArray("9|tags").optString(0))
        val sizes = values.getJSONArray("9|width")
        assertEquals(1, sizes.length())
        assertEquals("832x1216", sizes.optString(0))
    }

    @Test
    fun rejectsMissingWorkflow() {
        assertNull(regenerateBody("{}", "hi", 64, 64, "id"))
        assertNull(regenerateBody("not json", "hi", 64, 64, "id"))
    }
}
