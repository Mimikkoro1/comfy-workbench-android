package com.mie.kreaworkbench.data.workflows

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r38 E3：老 index 墓碑推断的纯函数用例（[inferTombstones]）。
 * store 的运行时行为（删→重启不回来、删光→重启不回来、SEED_VERSION 升级不复活）由
 * seedBuiltinsLocked 的墓碑跳过保证，依赖 Android Context 无法在纯 JVM 单测里跑，
 * 按 PLAN 留给真机验收清单；这里覆盖可离线验证的推断规则。
 */
class WorkflowStoreSeedTest {

    private val allIds: Set<String> = BuiltinTemplates.all.map { it.id }.toSet()

    @Test
    fun hasTombstoneFieldMeansNoInference() {
        // index 里已有 deleted_builtins 字段：尊重现状，不推断
        assertEquals(emptySet<String>(), inferTombstones(emptySet(), seedVersion = 2, hasField = true))
    }

    @Test
    fun neverSeededMeansNoInference() {
        // seedVersion == 0（从没播种过）：不推断，否则首次安装前会写满墓碑、永不播种
        assertEquals(emptySet<String>(), inferTombstones(emptySet(), seedVersion = 0, hasField = false))
    }

    @Test
    fun seededAndMissingBuiltinIsTombstoned() {
        // 老设备播种过（seedVersion ≥ 1）、缺哪条内置 = 用户删过哪条
        val present = allIds - "builtin-krea2-t2i"
        assertEquals(setOf("builtin-krea2-t2i"), inferTombstones(present, seedVersion = 1, hasField = false))
    }

    @Test
    fun deleteAllThenRestartTombstonesAll() {
        // 删光所有工作流后重启：列表为空但播种过 → 当时已存在的内置全入墓碑，不再自动播种回来
        val seededByV2 = BuiltinTemplates.all.filter { it.sinceSeed <= 2L }.map { it.id }.toSet()
        assertEquals(seededByV2, inferTombstones(emptySet(), seedVersion = 2, hasField = false))
    }

    @Test
    fun nothingMissingMeansNoTombstones() {
        // 内置全在（比如 SEED_VERSION 升级触发的重灌场景）：不产生墓碑，重灌照常
        assertEquals(emptySet<String>(), inferTombstones(allIds, seedVersion = 2, hasField = false))
    }

    /** r38 E7：从第 3 版才加的 Moss 在老 index（seedVersion = 2）里「本来就没有」，不算用户删过。 */
    @Test
    fun newerBuiltinIsNeverInferredAsDeleted() {
        val seededByV2 = allIds - "builtin-moss-t2i"
        val inferred = inferTombstones(seededByV2, seedVersion = 2, hasField = false)
        assertEquals(emptySet<String>(), inferred)
        // 反过来，第 3 版播种过的设备（seedVersion = 3）删掉 Moss 后重启，它才进墓碑
        assertEquals(
            setOf("builtin-moss-t2i"),
            inferTombstones(allIds - "builtin-moss-t2i", seedVersion = 3, hasField = false),
        )
    }
}
