@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mie.kreaworkbench.ui.screens.custom

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Pin
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.gacha.GACHA_SERVICE_REPO_URL
import com.mie.kreaworkbench.data.gacha.GachaOption
import com.mie.kreaworkbench.data.gacha.visibleModes
import com.mie.kreaworkbench.data.gacha.visibleOutfits
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.ui.components.CompactMenuField
import com.mie.kreaworkbench.ui.components.GlassAlertDialog
import com.mie.kreaworkbench.ui.components.GlassBottomSheet
import com.mie.kreaworkbench.ui.components.GlassCard
import com.mie.kreaworkbench.ui.components.GlassTextField
import com.mie.kreaworkbench.ui.components.LocalGlassDismiss
import com.mie.kreaworkbench.ui.components.ImmersiveHairline
import com.mie.kreaworkbench.ui.components.ImmersiveIcon
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.ImmersiveShadow
import com.mie.kreaworkbench.ui.components.LocalGlassChrome
import com.mie.kreaworkbench.ui.components.glassSwitchColors
import com.mie.kreaworkbench.ui.components.immersiveGlass

private val GACHA_MODES = listOf("wallpaper", "gongsu", "nude")
private val GACHA_OUTFITS = listOf("random", "signature", "char")
private val GACHA_POOLS = listOf("auto", "casual", "lingerie", "gongsu")
private val GACHA_POSES = listOf(
    "",
    "standing",
    "sitting",
    "kneeling",
    "squatting",
    "lying",
    "all_fours",
    "split_leg",
    "suspended",
)

/** 更多设置里只留两个开关。地址在设置页的连接页。 */
@Composable
fun AdvGachaSettings(
    vm: CustomModel,
    settings: UserSettings,
    onAskEnable: () -> Unit,
) {
    val on = vm.advEnabled(settings.advGacha)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.gacha_advanced),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = on,
                onCheckedChange = { checked ->
                    if (checked && !settings.advGachaGuideOff) onAskEnable() else vm.setAdvGacha(checked)
                },
                colors = if (LocalGlassChrome.current) glassSwitchColors() else SwitchDefaults.colors(),
            )
        }
    }
}

/** 第一次打开高级抽卡时说明要有电脑上的抽卡服务。仓库地址空着就不显示链接。 */
@Composable
fun AdvGachaGuideDialog(onConfirm: () -> Unit, onNever: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    GlassAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.gacha_advanced)) },
        text = {
            Column {
                Text(stringResource(R.string.gacha_guide_body))
                if (GACHA_SERVICE_REPO_URL.isNotBlank()) {
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GACHA_SERVICE_REPO_URL)))
                        },
                    ) { Text(stringResource(R.string.gacha_guide_repo)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onNever) { Text(stringResource(R.string.gacha_guide_never)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** 底栏那一行：只拼当前真画出来的维。某维被服端留空时，别留一段陈旧文案。服装池不进这行。 */
@Composable
fun advGachaSummary(vm: CustomModel, settings: UserSettings): String {
    val view = gachaView(vm, settings)
    val parts = ArrayList<String>(4)
    if (view.modes.isNotEmpty()) parts.add(optionLabel(view.modes, view.mode, modeLabel(view.mode)))
    if (view.masterShown.isNotBlank()) parts.add(view.masterShown)
    if (view.outfits.isNotEmpty()) parts.add(optionLabel(view.outfits, view.outfit, outfitLabel(view.outfit)))
    if (view.poses.isNotEmpty()) parts.add(optionLabel(view.poses, view.category, poseLabel(view.category)))
    return parts.joinToString(" · ")
}

/**
 * 点筛选条后向上弹出的筛选。服装池在最下面，不进摘要。
 * r32：有 options 就按服端来——某维数组为空就整框不画；没 options（旧服）才回退写死列表。
 * 母版框始终保留，它是所有筛选的入口。
 */
@Composable
fun AdvGachaFilterPanel(vm: CustomModel, settings: UserSettings) {
    val scheme = MaterialTheme.colorScheme
    val view = gachaView(vm, settings)
    val modeLabelText = stringResource(R.string.gacha_mode)
    val masterLabelText = stringResource(R.string.gacha_master)
    val outfitLabelText = stringResource(R.string.gacha_outfit)
    val poseLabelText = stringResource(R.string.gacha_pose)
    val poolLabelText = stringResource(R.string.gacha_pool)

    Row(Modifier.fillMaxWidth()) {
        if (view.modes.isNotEmpty()) {
            GachaFilterField(
                label = modeLabelText,
                value = optionLabel(view.modes, view.mode, modeLabel(view.mode)),
                options = view.modes,
                modifier = Modifier.weight(1f),
            ) { vm.pickGachaMode(it) }
            Spacer(Modifier.width(8.dp))
        }
        val masters = view.masterIds.zip(view.masterLabels).map { (id, label) -> GachaOption(id, label) }
        GachaFilterField(
            label = masterLabelText,
            value = view.masterShown,
            options = masters,
            modifier = Modifier.weight(1f),
        ) { vm.pickGachaMaster(it) }
    }
    if (!vm.gachaMastersLoaded) {
        Text(stringResource(R.string.gacha_masters_loading), fontSize = 11.sp, color = scheme.onSurfaceVariant)
    } else if (vm.gachaMastersError.isNotBlank() && vm.gachaMasters.isEmpty()) {
        Text(vm.gachaMastersError, fontSize = 11.sp, color = scheme.error)
    }
    if (view.outfits.isNotEmpty() || view.poses.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            if (view.outfits.isNotEmpty()) {
                GachaFilterField(
                    label = outfitLabelText,
                    value = optionLabel(view.outfits, view.outfit, outfitLabel(view.outfit)),
                    options = view.outfits,
                    modifier = Modifier.weight(1f),
                ) { vm.pickGachaOutfit(it) }
                if (view.poses.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (view.poses.isNotEmpty()) {
                GachaFilterField(
                    label = poseLabelText,
                    value = optionLabel(view.poses, view.category, poseLabel(view.category)),
                    options = view.poses,
                    modifier = Modifier.weight(1f),
                ) { vm.pickGachaCategory(it) }
            }
        }
    }
    if (view.pools.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        GachaFilterField(
            label = poolLabelText,
            value = optionLabel(view.pools, view.pool, poolLabel(view.pool)),
            options = view.pools,
            modifier = Modifier.fillMaxWidth(),
        ) { vm.pickGachaPool(it) }
    }
}

/** 贴在生成按钮上面的毛玻璃筛选条。右侧铅笔是临时词，不跟整行一起打开筛选。 */
@Composable
fun AdvGachaFilterBar(
    summary: String,
    tempPrompt: String,
    onClick: () -> Unit,
    onTemp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val shown = tempPrompt.trim().replace('\n', ' ').ifBlank { summary }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .immersiveGlass(18.dp)
            .border(1.dp, ImmersiveHairline, shape)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium.copy(shadow = ImmersiveShadow),
            color = ImmersiveInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .clip(CircleShape)
                .clickable(onClick = onTemp)
                .padding(6.dp),
        ) {
            ImmersiveIcon(
                Lucide.Pencil,
                stringResource(R.string.gacha_temp),
                size = 18.dp,
            )
        }
        ImmersiveIcon(
            Lucide.ChevronUp,
            stringResource(R.string.gacha_filters_open),
            size = 18.dp,
        )
    }
}

/** 粘贴一句临时提示词。生成按钮会用它，不关掉高级抽卡。清空后下次又去抽卡。
 *  r28.1：标题右侧的钉子控制「生成成功后要不要保留这句」。默认不钉（暗淡），成功后自动清空。 */
@Composable
fun AdvGachaTempSheet(vm: CustomModel, onDismiss: () -> Unit) {
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.gacha_temp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val pinned = vm.tempPromptPinned
                IconButton(onClick = { vm.toggleTempPin() }) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (pinned) Color.White.copy(alpha = 0.18f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        ImmersiveIcon(
                            Lucide.Pin,
                            contentDescription = stringResource(
                                if (pinned) R.string.gacha_temp_pin_on else R.string.gacha_temp_pin_off,
                            ),
                            size = 20.dp,
                            alpha = if (pinned) 1f else 0.42f,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            GlassTextField(
                value = vm.tempPrompt,
                onValueChange = { vm.editTempPrompt(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.gacha_temp_hint)) },
                minLines = 4,
            )
            if (vm.tempPrompt.isNotBlank()) {
                val close = LocalGlassDismiss.current
                TextButton(
                    onClick = {
                        vm.clearTempPrompt()
                        close()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_clear)) }
            }
        }
    }
}

/** 面板和摘要条共用：哪些框该画、里面是什么、当前选中的是哪个 id。 */
private class GachaView(
    val masterIds: List<String>,
    val masterLabels: List<String>,
    val masterShown: String,
    val modes: List<GachaOption>,
    val outfits: List<GachaOption>,
    val poses: List<GachaOption>,
    val pools: List<GachaOption>,
    val mode: String,
    val outfit: String,
    val category: String,
    val pool: String,
)

/**
 * 按服端 options 决定每一维的可选项。
 * options == null（旧服/响应里没这字段）→ 写死列表 + 本地文案，跟 r31 前一样；
 * options != null → 用服端，空数组就是空列表（调用方据此不画框），标签优先服端 label。
 * nude / 招牌按选中母版的 has_nude / has_signature 闸；「不限」母版不闸。
 */
@Composable
private fun gachaView(vm: CustomModel, settings: UserSettings): GachaView {
    val anyMaster = stringResource(R.string.gacha_master_any)
    val anyPose = stringResource(R.string.gacha_pose_any)
    val mode = vm.gachaModePick ?: settings.advGachaMode.ifBlank { "wallpaper" }
    val master = vm.gachaMasterPick ?: settings.advGachaMaster
    val outfit = vm.gachaOutfitPick ?: settings.advGachaOutfit.ifBlank { "random" }
    val category = vm.gachaCategoryPick ?: settings.advGachaCategory
    val pool = vm.gachaPoolPick ?: settings.advGachaPool.ifBlank { "auto" }

    val owner = vm.gachaMasters.firstOrNull { it.id == master }
    val options = vm.gachaOptions
    val modes: List<GachaOption>
    val outfits: List<GachaOption>
    val poses: List<GachaOption>
    val pools: List<GachaOption>
    if (options == null) {
        modes = GACHA_MODES.map { GachaOption(it, modeLabel(it)) }
        outfits = GACHA_OUTFITS.map { GachaOption(it, outfitLabel(it)) }
        poses = GACHA_POSES.map { GachaOption(it, poseLabel(it)) }
        pools = GACHA_POOLS.map { GachaOption(it, poolLabel(it)) }
    } else {
        modes = visibleModes(options.modes, owner)
        outfits = visibleOutfits(options.outfits, owner)
        // 服端姿势没有「不限」这一项，App 自己补在最前面；空列表就整框不画。
        poses = if (options.poses.isEmpty()) {
            emptyList()
        } else {
            (listOf(GachaOption("", anyPose)) + options.poses).distinctBy { it.id }
        }
        pools = options.pools
    }

    val masterIds = listOf("") + vm.gachaMasters.map { it.id }
    val masterLabels = listOf(anyMaster) + vm.gachaMasters.map { it.label }
    val masterIdx = masterIds.indexOf(master)
    val masterShown = when {
        masterIdx >= 0 -> masterLabels[masterIdx]
        master.isNotBlank() -> master
        else -> anyMaster
    }
    return GachaView(
        masterIds = masterIds,
        masterLabels = masterLabels,
        masterShown = masterShown,
        modes = modes,
        outfits = outfits,
        poses = poses,
        pools = pools,
        mode = mode,
        outfit = outfit,
        category = category,
        pool = pool,
    )
}

/** 非中文界面优先用服端 label_en（英文、日语等都能看到英文名）；中文界面用 label；都空再退回 id。传参照旧只用 id。 */
@Composable
private fun optionText(option: GachaOption): String {
    val locales = LocalConfiguration.current.locales
    val english = locales.isEmpty || locales[0].language != "zh"
    val text = if (english) option.labelEn.ifBlank { option.label } else option.label
    return text.ifBlank { option.id }
}

/** 当前值在列表里就用它的 label（服端文案优先），找不到再退回本地 string。 */
@Composable
private fun optionLabel(list: List<GachaOption>, id: String, fallback: String): String =
    list.firstOrNull { it.id == id }?.let { optionText(it) }?.ifBlank { fallback } ?: fallback

@Composable
private fun GachaFilterField(
    label: String,
    value: String,
    options: List<GachaOption>,
    modifier: Modifier,
    onPick: (String) -> Unit,
) {
    MiniFilter(label, value, options.map { optionText(it) }, modifier) { i ->
        options.getOrNull(i)?.let { onPick(it.id) }
    }
}

@Composable
private fun MiniFilter(
    label: String,
    value: String,
    options: List<String>,
    modifier: Modifier,
    onPick: (Int) -> Unit,
) {
    Column(modifier) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        CompactMenuField(value = value, options = options, onPick = onPick)
    }
}

@Composable
private fun modeLabel(id: String): String = when (id) {
    "gongsu" -> stringResource(R.string.gacha_mode_gongsu)
    "nude" -> stringResource(R.string.gacha_mode_nude)
    else -> stringResource(R.string.gacha_mode_wallpaper)
}

@Composable
private fun outfitLabel(id: String): String = when (id) {
    "signature" -> stringResource(R.string.gacha_outfit_signature)
    "char" -> stringResource(R.string.gacha_outfit_char)
    else -> stringResource(R.string.gacha_outfit_random)
}

@Composable
private fun poolLabel(id: String): String = when (id) {
    "casual" -> stringResource(R.string.gacha_pool_casual)
    "lingerie" -> stringResource(R.string.gacha_pool_lingerie)
    "gongsu" -> stringResource(R.string.gacha_pool_gongsu)
    else -> stringResource(R.string.gacha_pool_auto)
}

@Composable
private fun poseLabel(id: String): String = when (id) {
    "standing" -> stringResource(R.string.gacha_pose_standing)
    "sitting" -> stringResource(R.string.gacha_pose_sitting)
    "kneeling" -> stringResource(R.string.gacha_pose_kneeling)
    "squatting" -> stringResource(R.string.gacha_pose_squatting)
    "lying" -> stringResource(R.string.gacha_pose_lying)
    "all_fours" -> stringResource(R.string.gacha_pose_all_fours)
    "split_leg" -> stringResource(R.string.gacha_pose_split)
    "suspended" -> stringResource(R.string.gacha_pose_suspended)
    else -> stringResource(R.string.gacha_pose_any)
}
