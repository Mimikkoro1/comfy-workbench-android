package com.mie.kreaworkbench.ui.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mie.kreaworkbench.R

/**
 * API 33 以下 AppCompat 只给 Activity 套 per-app 语言，Application / Service 要自己包一层。
 * 跟随系统（空 locale 列表）时不动，好让 values-zh 按系统中文（含繁体）命中。
 */
fun Context.localized(): Context {
    // 重启切换写入的选择优先。还没写过时仍走 AppCompat（含系统里已记住的应用语言）。
    val tags = when (val stored = AppLocale.languageTagOrNull(this)) {
        null -> AppCompatDelegate.getApplicationLocales().toLanguageTags()
        else -> stored
    }
    if (tags.isEmpty()) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(tags))
    return createConfigurationContext(config)
}

fun Context.str(@StringRes id: Int): String = localized().getString(id)

fun Context.str(@StringRes id: Int, vararg args: Any): String = localized().getString(id, *args)

fun Context.qty(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
    localized().resources.getQuantityString(id, quantity, *args)

/** 存盘的默认文案：对得上已知原文就换当前语言，用户改过的原样返回。 */
fun Context.known(text: String): String {
    val id = KNOWN_COPY[text] ?: return text
    return str(id)
}

@Composable
fun knownText(text: String): String {
    val id = KNOWN_COPY[text] ?: return text
    return stringResource(id)
}

/** 引擎写入的阶段 token（采样 N/M、合成视频）在显示时映射，不改存下来的字。 */
fun Context.knownStage(stage: String): String {
    if (stage == STAGE_VIDEO) return str(R.string.stage_video)
    val match = SAMPLING.matchEntire(stage) ?: return stage
    return str(R.string.stage_sampling, match.groupValues[1].toInt(), match.groupValues[2].toInt())
}

/** 解析引擎的「采样 k/n」阶段 token，返回 (k, n)；非采样阶段（含合成视频）返回 null。
 *  进度行据此判断采样器总数：只有 n ≥ 2 才把采样器进度显示出来（round13 第 1 项）。 */
fun parseSamplingStage(stage: String): Pair<Int, Int>? =
    SAMPLING.matchEntire(stage.trim())?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

/** 是否视频合成阶段 token（「合成视频」）。 */
fun isVideoStage(stage: String): Boolean = stage.trim() == STAGE_VIDEO

private const val STAGE_VIDEO = "合成视频"
private val SAMPLING = Regex("采样 (\\d+)/(\\d+)")

private val KNOWN_COPY: Map<String, Int> = mapOf(
    "提示词" to R.string.wf_prompt,
    "负向提示词" to R.string.wf_negative,
    "输出尺寸" to R.string.wf_output_size,
    "种子值" to R.string.wf_seed_value,
    "帧数" to R.string.wf_frames,
    "帧率" to R.string.wf_fps,
    "大模型" to R.string.wf_checkpoint,
    "扩散模型" to R.string.wf_diffusion,
    "文本编码器" to R.string.wf_clip,
    "视觉编码器" to R.string.wf_clip_vision,
    "放大模型" to R.string.wf_upscale,
    "上传本次生成使用的参考图片。" to R.string.wf_help_image,
    "本版不支持视频输入。" to R.string.wf_help_video_unsupported,
    "选「随机」每张图自动换种子；选「固定」使用上面的数值。" to R.string.wf_help_seed,
    "勾上「随机」则每张图各自随机；不勾则用固定数值。" to R.string.wf_help_seed_old,
    "可选预设尺寸，或填入自定义尺寸" to R.string.wf_help_size,
    "可选预设尺寸，或填入自定义尺寸。" to R.string.wf_help_size_dot,
    "勾选多个预设尺寸，或填自定义宽×高（每个尺寸出一张图）。" to R.string.wf_help_size_old,
    "填入自定义尺寸，或保持参考图原始分辨率。" to R.string.wf_help_size_video,
    "总帧数；Wan 建议 4n+1，帧越多越慢越吃显存。" to R.string.wf_help_frames,
    "合成视频的播放帧率（fps）。" to R.string.wf_help_fps,
    "不希望出现的内容；留空则保持工作流原值。" to R.string.wf_help_negative,
    "描述希望生成的内容、风格与构图。" to R.string.wf_help_prompt,
    "未分类" to R.string.wf_uncat,
    "导入的工作流" to R.string.wf_imported,
    "导入库" to R.string.wf_import_lib,
    "1152×1728 · 2:3 · 全身（默认）" to R.string.size_full,
    "1024×1536 · 2:3 · 人像" to R.string.size_portrait,
    "1216×1728 · 5:7 · 人像高清" to R.string.size_portrait_hd,
    "1088×1920 · 9:16 · 竖屏" to R.string.size_vertical,
    "1440×2560 · 9:16 · 竖屏高清" to R.string.size_vertical_hd,
    "1152×2560 · 9:20 · 手机壁纸" to R.string.size_wallpaper,
    "1536×1536 · 1:1 · 方图" to R.string.size_square,
    "1536×1024 · 3:2 · 横版" to R.string.size_land,
    "1728×1216 · 7:5 · 横版高清" to R.string.size_land_hd,
    "1536×864 · 16:9 · 横屏" to R.string.size_widescreen,
    "1728×972 · 16:9 · 横屏高清" to R.string.size_wide,
    // r14fix2 前的旧标签（老导入工作流定义 presets 里还存着），同样映射到新文案
    "1152×1728 · 2:3 全身（默认）" to R.string.size_full,
    "1024×1536 · 2:3 人像" to R.string.size_portrait,
    "1216×1728 · 人像高清" to R.string.size_portrait_hd,
    "1152×2560 · 手机壁纸" to R.string.size_wallpaper,
    "1536×864 · 16:9" to R.string.size_widescreen,
    "1728×972 · 16:9 超宽" to R.string.size_wide,
    "1536×1536 · 1:1" to R.string.size_square,
    "1536×1024 · 3:2 横版" to R.string.size_land,
    "1728×1216 · 横版高清" to R.string.size_land_hd,
)
