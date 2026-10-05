package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject

/** 测试工具：读 test resources 里的 fixture 工作流/定义。 */
object FixtureLoader {
    fun loadText(name: String): String =
        javaClass.classLoader!!.getResource("workflows/$name")!!.readText()

    fun loadWf(name: String): JSONObject = JSONObject(loadText(name))
}

/** 候选列表 → "node_id|field" 索引（JSONObject 是 HashMap 顺序，同节点内候选先后不能依赖）。 */
fun specMap(list: List<Candidate>): Map<String, Candidate> =
    list.associateBy { it.spec.optString("node_id") + "|" + it.spec.optString("field") }
