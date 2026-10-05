package com.mie.kreaworkbench.data

data class SizePreset(val label: String, val width: Int, val height: Int)

/** 统一格式「W×H · 比例 · 用途」；第一项为默认。选择按 "WxH" 值存，改标签不影响已存选择。 */
val BUILTIN_SIZES = listOf(
    SizePreset("1152×1728 · 2:3 · 全身（默认）", 1152, 1728),
    SizePreset("1024×1536 · 2:3 · 人像", 1024, 1536),
    SizePreset("1216×1728 · 5:7 · 人像高清", 1216, 1728),
    SizePreset("1088×1920 · 9:16 · 竖屏", 1088, 1920),
    SizePreset("1440×2560 · 9:16 · 竖屏高清", 1440, 2560),
    SizePreset("1152×2560 · 9:20 · 手机壁纸", 1152, 2560),
    SizePreset("1536×1536 · 1:1 · 方图", 1536, 1536),
    SizePreset("1536×1024 · 3:2 · 横版", 1536, 1024),
    SizePreset("1728×1216 · 7:5 · 横版高清", 1728, 1216),
    SizePreset("1536×864 · 16:9 · 横屏", 1536, 864),
    SizePreset("1728×972 · 16:9 · 横屏高清", 1728, 972),
)

/** r14fix2 之前的内置预设值集合：老的导入工作流定义里存的正是这 9 项（旧标签），显示时整体换成新列表。 */
val LEGACY_BUILTIN_SIZE_VALUES = setOf(
    "1152x1728", "1024x1536", "1216x1728", "1152x2560", "1536x1536", "1536x1024", "1728x1216", "1536x864", "1728x972",
)

const val DEFAULT_UNET = "Krea2\\Krea2Turbo_FP8.safetensors"
const val DEFAULT_CLIP = "Krea2\\qwen3-vl-4b-heretic-bf16.safetensors"
const val DEFAULT_VAE = "Krea2\\qwen_image_vae.safetensors"
const val DEFAULT_URL = "http://192.168.1.100:8188"

val FALLBACK_SAMPLERS = listOf(
    "euler", "euler_ancestral", "heun", "dpmpp_2m", "dpmpp_2m_sde",
    "dpmpp_sde", "res_multistep", "uni_pc", "lcm", "ddim",
)
val FALLBACK_SCHEDULERS = listOf(
    "simple", "normal", "karras", "exponential", "sgm_uniform", "beta", "linear_quadratic",
)

fun modelFileName(path: String): String {
    val n = path.replace('\\', '/')
    return n.substringAfterLast('/')
}

fun modelFolder(path: String): String {
    val n = path.replace('\\', '/')
    return if ('/' in n) n.substringBeforeLast('/') else ""
}
