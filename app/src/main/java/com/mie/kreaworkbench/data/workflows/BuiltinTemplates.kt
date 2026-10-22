package com.mie.kreaworkbench.data.workflows

import androidx.annotation.StringRes
import com.mie.kreaworkbench.R

/**
 * 内置模板清单（r35）：assets/builtin_templates/ 下的 API 格式工作流。
 * - id 固定（非 UUID）：播种按 id 去重，「设置里恢复」缺哪条补哪条。
 * - r37 起模型槽预填官方工作流默认名（装了官方模型开箱即生成；没装的走「本机未找到」占位）。
 * - r38 起显示名以 [Entry.nameRes] 资源为准（跟随应用语言）；[Entry.displayName] 只是
 *   index.json 里的存盘值/兜底，不再直接上界面。
 * - 不含私人提示词 / 本地路径 / LoRA 挂载；识别与落库复用导入的 inferSpecs/buildDefinition。
 */
object BuiltinTemplates {
    data class Entry(
        val id: String,
        val asset: String,
        val displayName: String,
        @StringRes val nameRes: Int,
        /**
         * r38 E7：这条模板从哪个种子版本开始存在（首播的 5 个 = 1，Moss = 3）。
         * 老 index 的墓碑推断只认 `sinceSeed <= 存量 seedVersion` 的条目——否则老设备上
         * 从没出现过的模板会被误判成「用户删过」，永远不播种。
         */
        val sinceSeed: Long = 1L,
    )

    /** WorkflowStore 里内置条目的 source 值。 */
    const val SOURCE = "builtin"

    /** assets 下的目录名。 */
    const val DIR = "builtin_templates"

    /**
     * 播种版本号（r37）：index.json 里的 builtin_seed_version 低于它时，存量内置条目从资产
     * 重灌一次。r35 首播视作 1（旧 index 没这个字段，缺省当 0），第 2 版把空占位换成官方默认名，
     * 第 3 版（r38 E7）新增 Moss 文生图；以后再改资产内容时 +1 即可让老设备自动拿到新内容。
     */
    const val SEED_VERSION = 3L

    val all: List<Entry> = listOf(
        Entry("builtin-krea2-t2i", "Krea2_T2I.json", "Krea2 文生图", R.string.builtin_krea2_t2i),
        Entry("builtin-krea2-i2i", "Krea2_I2I.json", "Krea2 图生图", R.string.builtin_krea2_i2i),
        Entry("builtin-qwen21-t2i", "Qwen21_T2I.json", "Qwen 2.1 文生图", R.string.builtin_qwen21_t2i),
        Entry("builtin-qwen21-edit", "Qwen21_Edit.json", "Qwen 2.1 图片编辑", R.string.builtin_qwen21_edit),
        Entry("builtin-zimage-t2i", "Zimage_T2I.json", "Z-Image 文生图", R.string.builtin_zimage_t2i),
        // r38 E7：第 3 版种子新增的第 6 个模板（sinceSeed = 3，老 index 推断墓碑时要排除它）
        Entry("builtin-moss-t2i", "Moss_T2I.json", "Moss 文生图", R.string.builtin_moss_t2i, sinceSeed = 3L),
    )

    /** r38：内置模板显示名的唯一判定入口——内置固定 id 返回其名称资源，其余返回 null。 */
    fun nameResOf(id: String): Int? = all.firstOrNull { it.id == id }?.nameRes
}
