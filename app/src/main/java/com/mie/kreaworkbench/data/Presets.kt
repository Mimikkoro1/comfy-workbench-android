package com.mie.kreaworkbench.data

data class SizePreset(val label: String, val width: Int, val height: Int)

val BUILTIN_SIZES = listOf(
    SizePreset("1152×1728 · 2:3 全身（默认）", 1152, 1728),
    SizePreset("1024×1536 · 2:3 人像", 1024, 1536),
    SizePreset("1216×1728 · 人像高清", 1216, 1728),
    SizePreset("1152×2560 · 手机壁纸", 1152, 2560),
    SizePreset("1536×864 · 16:9", 1536, 864),
    SizePreset("1728×972 · 16:9 超宽", 1728, 972),
    SizePreset("1536×1536 · 1:1", 1536, 1536),
    SizePreset("1536×1024 · 3:2 横版", 1536, 1024),
    SizePreset("1728×1216 · 横版高清", 1728, 1216),
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
