@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mie.kreaworkbench.ui.screens.custom

import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Video
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.BUILTIN_SIZES
import com.mie.kreaworkbench.data.LEGACY_BUILTIN_SIZE_VALUES
import com.mie.kreaworkbench.data.modelFileName
import com.mie.kreaworkbench.data.workflows.LoraEntry
import com.mie.kreaworkbench.data.workflows.Specs
import com.mie.kreaworkbench.data.workflows.VIDEO_SIZE_720P
import com.mie.kreaworkbench.data.workflows.VIDEO_SIZE_SOURCE
import com.mie.kreaworkbench.data.workflows.isTextToImageWorkflow
import com.mie.kreaworkbench.data.workflows.loraStrengthKey
import com.mie.kreaworkbench.data.workflows.shownHelp
import com.mie.kreaworkbench.data.workflows.videoSourceWarnLimit
import com.mie.kreaworkbench.ui.components.CompactMenuField
import com.mie.kreaworkbench.ui.components.CompactTextField
import com.mie.kreaworkbench.ui.components.MiniField
import com.mie.kreaworkbench.ui.components.PromptField
import com.mie.kreaworkbench.ui.locale.knownText
import com.mie.kreaworkbench.util.VIDEO_WARN_BYTES
import com.mie.kreaworkbench.util.VideoThumb
import com.mie.kreaworkbench.util.formatBytes
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * 生成页（CustomScreen）与设置页（ModelsScreen）共用的 spec 控件，禁止复制两份。
 * 生成分工：生成页渲染 text/prompt_pool/image_sizes/int_random/file:image，
 * 设置页渲染 model/select/int/float/bool——类型分支在同一份代码里，页面只按 type 过滤。
 */

/** 单个 spec 的控件。onPickAlbum / onPickGallerySheet 由页面提供（file:image 用）；
 *  onPickVideo / onPickVideoGallery 同理（file:video 用，round17），默认空实现，现有调用点不用改。
 *  belowMainPrompt 传给主提示词（mainPromptKey() 命中的 prompt_pool）行，用于挂抽卡共享筛选。
 *  cardTitle 与字段标签相同时不画小号标签（卡片标题已经是这几个字）。 */
@Composable
fun SpecRow(
    vm: CustomModel,
    spec: JSONObject,
    onPickAlbum: () -> Unit,
    onPickGallerySheet: () -> Unit,
    belowMainPrompt: (@Composable () -> Unit)? = null,
    @StringRes cardTitle: Int? = null,
    onPickVideo: () -> Unit = {},
    onPickVideoGallery: () -> Unit = {},
) {
    val key = Specs.key(spec)
    val type = Specs.type(spec)
    val scheme = MaterialTheme.colorScheme
    val rawLabel = Specs.label(spec)
    val label = knownText(rawLabel)
    // 提示词卡里的主提示词不再重复显示字段名（导入时自动生成的「节点标题 · prompt」之类），与 Krea2 一致只留卡片标题
    val isMainPrompt = (type == "text" || type == "prompt_pool") && key == vm.mainPromptKey()
    val showLabel = when {
        cardTitle == null -> true
        cardTitle == R.string.label_prompt && isMainPrompt -> false
        else -> rawLabel != stringZh(cardTitle)
    }
    val sharedFilter = vm.usesSharedFilter(spec)
    // LoRA 单加载器（round16）：model spec 命中的开关；非 model / 非 LoRA 加载器为 null
    val loraEntry = if (type == "model") vm.loraEntryForSpec(spec) else null
    val loraOn = loraEntry?.let { vm.loraOn[it.key] ?: it.defaultOn } ?: true
    val loraDim = loraEntry != null && !loraOn
    val loraCd = if (loraEntry != null) stringResource(R.string.lora_toggle_cd) else ""
    // 模型类 spec 不显示说明（旧定义里每个模型都带同一句 wf_help_model，重复占位）
    val help = if (type == "model") "" else shownHelp(spec, vm.outputKind)
    // 抽卡只在文生图、且当前库有条目时出现。判定只走 isTextToImageWorkflow。
    val hasLib by vm.hasPromptLibrary.collectAsState()
    val drawOk = hasLib && isTextToImageWorkflow(vm.outputKind, vm.specs)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // model 行不依赖 showLabel（round16：模型卡的 cardTitle 为 null 本来就显示标签）；
        // LoRA 单加载器在标签行右侧放开关
        if (showLabel || type == "model") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                Spacer(Modifier.weight(1f))
                if (loraEntry != null) {
                    Switch(
                        checked = loraOn,
                        onCheckedChange = { vm.setLoraOn(loraEntry.key, it) },
                        modifier = Modifier.semantics { contentDescription = loraCd },
                    )
                }
            }
        }
        if (help.isNotBlank()) {
            Text(knownText(help), fontSize = 11.sp, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        when (type) {
            "text", "prompt_pool" -> {
                val fill = stringResource(R.string.fill_field, knownText(rawLabel))
                PromptField(
                    value = vm.textValues[key].orEmpty(),
                    onValueChange = {
                        vm.setText(key, it, Specs.maxChars(spec))
                        vm.persist()
                    },
                    placeholder = Specs.defaultText(spec).ifBlank { fill },
                )
                if (drawOk) {
                    // 与底部「生成」按钮同规格（整行宽、56dp 高、16dp 圆角），Outlined 样式区分主次
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.draw(key) },
                        enabled = vm.drawingKey.isEmpty(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            if (vm.drawingKey == key) stringResource(R.string.state_drawing) else stringResource(R.string.action_draw),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scheme.primary,
                        )
                    }
                    // 备选数小字（round11）：该槽实际生效筛选下的池大小——prompt_pool 槽=共享筛选后，
                    // text 槽=全库（与抽卡按槽型分流一致）；不含被冷却冻结的卡，数字不随抽卡跳动。
                    // null=尚未算出，不显示避免闪 0。与 help 行同风格（11sp / onSurfaceVariant）
                    val poolSize = (if (sharedFilter) vm.drawPoolSize else vm.libraryPoolSize).collectAsState()
                    poolSize.value?.let { n ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            pluralStringResource(R.plurals.draw_pool_count, n, n),
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                // r14fix3：原条件要求 type=="prompt_pool"，主提示词是 text 型（如 Qwen 的
                // TextEncodeQwenImage21.prompt）时筛选框不出；现在主提示词槽不论类型都挂
                if (drawOk && belowMainPrompt != null && isMainPrompt && sharedFilter) {
                    // 抽卡按钮（56dp）与筛选行（40dp）之间留正常间距，此前 0 间距贴死视觉上重叠
                    Spacer(Modifier.height(12.dp))
                    belowMainPrompt()
                }
            }
            "select" -> {
                // 选项三级来源：①kwb_combo 在线实时 → ②definition 的 choices → ③无选项时退化为
                // 可编辑输入框（round13 第 4 项：不再退成只读文本）
                val online = vm.comboChoices[key].orEmpty()
                val fromDef = Specs.choices(spec)
                val choices = when {
                    online.isNotEmpty() -> online.map { it to it }
                    fromDef.isNotEmpty() -> fromDef
                    else -> emptyList()
                }
                val value = vm.textValues[key].orEmpty()
                val labels = choices.map { it.first }
                val idx = choices.indexOfFirst { it.second == value }
                val please = stringResource(R.string.please_select)
                val shown = when {
                    idx >= 0 -> labels[idx]
                    choices.isEmpty() -> value.ifBlank { please }
                    else -> please
                }
                if (choices.isEmpty()) {
                    // 选项拿不到（/object_info 失败且 definition 也没写 choices）：可编辑输入框，
                    // 已填的值原样保留；拉取失败过的槽位给出说明
                    CompactTextField(
                        value = value,
                        onValueChange = { vm.setText(key, it); vm.persist() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (key in vm.choiceLoadFailed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.choices_not_loaded),
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    CompactMenuField(
                        value = shown,
                        options = labels,
                        modifier = Modifier.fillMaxWidth(),
                    ) { i ->
                        choices.getOrNull(i)?.let {
                            vm.setText(key, it.second)
                            vm.persist()
                        }
                    }
                    if (value.isNotBlank() && idx < 0) {
                        Spacer(Modifier.height(4.dp))
                        // 已填的值不在新拉到的列表里：标红提示，值保留不静默替换（round13 第 7 项）
                        Text(
                            stringResource(R.string.value_not_in_list),
                            fontSize = 11.sp,
                            color = scheme.error,
                        )
                    }
                }
            }
            "model" -> {
                val value = vm.textValues[key].orEmpty()
                val choices = vm.modelChoices[key].orEmpty()
                val found = value.isNotBlank() && value in choices
                // 关闭时下拉框与强度行整体变淡；下拉框仍可操作，强度框禁用（值保留不清空）
                val dimMod = if (loraDim) Modifier.alpha(0.5f) else Modifier
                // 选项没拉到（/object_info 失败）：退化为可编辑输入框，值保留；不再弹一个
                // 只有占位项的空下拉（round13 第 4 项）
                if (choices.isEmpty()) {
                    CompactTextField(
                        value = value,
                        onValueChange = { vm.setText(key, it, 400); vm.persist() },
                        modifier = Modifier.fillMaxWidth().then(dimMod),
                    )
                    if (key in vm.choiceLoadFailed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.choices_not_loaded),
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 空值显示「请选择」；有值但不在本机列表才显示「本机未找到」
                    val please = stringResource(R.string.please_select)
                    val placeholder = if (value.isBlank()) please else stringResource(R.string.model_missing, value)
                    val labels = if (found) choices else listOf(placeholder) + choices
                    CompactMenuField(
                        value = if (found) value else placeholder,
                        options = labels,
                        modifier = Modifier.fillMaxWidth().then(dimMod),
                    ) { i ->
                        val chosen = if (found) {
                            choices.getOrNull(i)
                        } else {
                            // 第 0 项是占位（请选择/本机未找到），不选
                            if (i == 0) null else choices.getOrNull(i - 1)
                        }
                        chosen?.let {
                            vm.setText(key, it)
                            vm.persist()
                        }
                    }
                    if (value.isNotBlank() && !found) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.value_not_in_list),
                            fontSize = 11.sp,
                            color = scheme.error,
                        )
                    }
                }
                if (loraEntry != null) {
                    Spacer(Modifier.height(6.dp))
                    LoraStrengthRow(vm, loraEntry, enabled = loraOn)
                }
                if (loraDim) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.lora_off_hint),
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            "int" -> {
                // 与下拉框同款紧凑外观（40dp/12dp 圆角），不再用高 56dp 的 OutlinedTextField
                CompactTextField(
                    value = vm.textValues[key].orEmpty(),
                    onValueChange = { vm.setIntText(key, it); vm.persist() },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                )
                RangeHint(spec)
            }
            "int_random" -> {
                // 一行紧凑布局：细长数字框 + 「随机/固定」互斥小按钮（选中高亮，代替旧开关）。
                // 随机=只读（长按可全选复制），生成完由 CustomModel 回填实际提交的种子；
                // 固定=锁定当前显示值，允许手动编辑
                val random = vm.randomOn[key] ?: Specs.randomDefault(spec)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = vm.textValues[key].orEmpty(),
                        onValueChange = { if (!random) vm.setIntText(key, it) },
                        readOnly = random,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    )
                    FilterChip(
                        selected = random,
                        onClick = { if (!random) { vm.setRandom(key, true); vm.persist() } },
                        label = { Text(stringResource(R.string.label_random)) },
                    )
                    FilterChip(
                        selected = !random,
                        onClick = { if (random) { vm.setRandom(key, false); vm.persist() } },
                        label = { Text(stringResource(R.string.label_fixed)) },
                    )
                }
            }
            "float" -> {
                val min = Specs.min(spec) ?: 0.0
                val max = Specs.max(spec) ?: 1.0
                val step = Specs.step(spec) ?: 0.05
                val value = (vm.textValues[key]?.toDoubleOrNull() ?: Specs.defaultNumber(spec)).coerceIn(min, max)
                val intervals = ((max - min) / step).roundToInt().coerceAtLeast(1)
                Slider(
                    value = value.toFloat(),
                    onValueChange = {
                        val snapped = (min + ((it.toDouble() - min) / step).roundToInt() * step).coerceIn(min, max)
                        vm.setFloatText(key, trimNum(snapped))
                        vm.persist()
                    },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (intervals - 1).coerceAtLeast(0),
                )
                CompactTextField(
                    value = vm.textValues[key].orEmpty(),
                    onValueChange = { vm.setFloatText(key, it); vm.persist() },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Decimal,
                )
            }
            "bool" -> {
                Switch(
                    checked = vm.boolValues[key] ?: Specs.defaultBool(spec),
                    onCheckedChange = { vm.setBool(key, it); vm.persist() },
                )
            }
            "image_sizes" -> {
                if (vm.outputKind == "video") {
                    VideoSizeRow(vm, spec)
                } else {
                // 单选（direct10c）：预设全在一个下拉里，选中即切换不是追加；「自定义」只展开 W×H
                // 输入框，输入的宽高就是当前尺寸；选中预设后输入框收起并同步成该预设值
                // 定义里没写 presets，或写的就是旧版内置 9 项（老导入定义）→ 用当前内置列表（新标签 + 9:16）
                val presets = Specs.sizePresets(spec).takeUnless {
                    it.isEmpty() || it.map { p -> p.second }.toSet() == LEGACY_BUILTIN_SIZE_VALUES
                } ?: BUILTIN_SIZES.map { it.label to "${it.width}x${it.height}" }
                val presetLabels = knownLabels(presets.map { it.first })
                var customW by remember { mutableStateOf("") }
                var customH by remember { mutableStateOf("") }
                var expanded by remember { mutableStateOf(false) }
                val sel = vm.sizesSelected.singleOrNull()
                val rawShown = sel?.let { s -> presets.firstOrNull { it.second == s }?.first ?: s }
                val shown = if (rawShown == null) stringResource(R.string.pick_size) else knownText(rawShown)
                fun syncInputs(size: String) {
                    val p = size.split("x", "X", "×")
                    if (p.size == 2) {
                        customW = p[0]
                        customH = p[1]
                    }
                }
                fun applyCustom() {
                    val w = customW.toIntOrNull() ?: return
                    val h = customH.toIntOrNull() ?: return
                    if (w < 16 || h < 16) return
                    val wc = clamp16(w, Specs.widthRange(spec))
                    val hc = clamp16(h, Specs.heightRange(spec))
                    val size = "${wc}x${hc}"
                    if (size != sel) {
                        vm.setSize(size)
                        vm.persist()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactMenuField(
                        value = shown,
                        options = presetLabels,
                        modifier = Modifier.weight(1f),
                    ) { i ->
                        presets.getOrNull(i)?.let { (_, v) ->
                            vm.setSize(v)
                            vm.persist()
                            syncInputs(v)
                            expanded = false
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (!expanded && customW.isBlank() && customH.isBlank()) sel?.let(::syncInputs)
                            expanded = !expanded
                        },
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.label_custom), style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1)
                    }
                }
                AnimatedVisibility(visible = expanded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = customW,
                            onValueChange = { customW = it.filter { ch -> ch.isDigit() }.take(4); applyCustom() },
                            label = { Text(stringResource(R.string.label_width), color = scheme.onSurfaceVariant) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        )
                        Text("×", color = scheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = customH,
                            onValueChange = { customH = it.filter { ch -> ch.isDigit() }.take(4); applyCustom() },
                            label = { Text(stringResource(R.string.label_height), color = scheme.onSurfaceVariant) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        )
                    }
                }
                if (sel == null) {
                    Text(
                        stringResource(R.string.size_unset, spec.optString("default")),
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                }
            }
            "file:image" -> {
                val model: Any? = vm.sourceUri?.let { Uri.parse(it) } ?: vm.sourcePath?.let { File(it) }
                if (model != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(model).size(800).build(),
                        contentDescription = stringResource(R.string.card_reference),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)),
                    )
                    Text("${vm.srcW} × ${vm.srcH}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.no_image_yet), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    OutlinedButton(onClick = onPickAlbum) { Text(stringResource(R.string.pick_album)) }
                    Spacer(Modifier.padding(6.dp))
                    OutlinedButton(onClick = onPickGallerySheet) { Text(stringResource(R.string.pick_from_gallery)) }
                }
            }
            "file:video" -> {
                // 参考视频（round17）：首帧缩略图（取不到用视频图标占位）+ 元信息 + 大视频提醒
                val hasVideo = vm.videoUri != null || vm.videoPath != null
                if (hasVideo) {
                    val thumb = vm.videoThumb
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = stringResource(R.string.card_reference_video),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp)),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(scheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.Video, contentDescription = stringResource(R.string.card_reference_video))
                        }
                    }
                    val meta = videoMetaText(vm)
                    if (meta.isNotBlank()) {
                        Text(stringResource(R.string.video_meta, meta), fontSize = 11.sp, color = scheme.onSurfaceVariant)
                    }
                    if (vm.videoSize >= VIDEO_WARN_BYTES) {
                        Text(
                            stringResource(R.string.video_large_warn, formatBytes(vm.videoSize)),
                            fontSize = 11.sp,
                            color = scheme.error,
                        )
                    }
                } else {
                    Text(stringResource(R.string.no_video_yet), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                // 按钮行用 FlowRow：窄屏放不下自动换行
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onPickVideo) { Text(stringResource(R.string.pick_video)) }
                    OutlinedButton(onClick = onPickVideoGallery) { Text(stringResource(R.string.pick_from_gallery)) }
                    if (hasVideo) {
                        TextButton(onClick = { vm.clearVideo() }) { Text(stringResource(R.string.action_clear)) }
                    }
                }
            }
        }
    }
}

/** 参考视频元信息行：文件名 · 大小 · 时长 · 宽×高；取不到的项省略。 */
private fun videoMetaText(vm: CustomModel): String = buildList {
    if (vm.videoName.isNotBlank()) add(vm.videoName)
    formatBytes(vm.videoSize).takeIf { it.isNotBlank() }?.let { add(it) }
    VideoThumb.durationLabel(vm.videoDurationMs).takeIf { it.isNotBlank() }?.let { add(it) }
    if (vm.videoW > 0 && vm.videoH > 0) add("${vm.videoW}×${vm.videoH}")
}.joinToString(" · ")

/**
 * LoRA 强度行（round16）：每个强度字段一个等宽数字框，单加载器与 Power 槽共用。
 * entry 没有强度字段（Power 槽缺 strength）就不画。关闭时整行变淡、框禁用（值保留不清空）。
 * 同一工作流有 ≥2 个 LoRA 开关时，标签后追加节点标题方便区分；标题等于 classType 时不追加。
 */
@Composable
fun LoraStrengthRow(vm: CustomModel, entry: LoraEntry, enabled: Boolean) {
    if (entry.strengthFields.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val alphaMod = if (enabled) Modifier else Modifier.alpha(0.5f)
    val suffix = if (vm.loraEntries.size >= 2 && entry.nodeTitle != entry.classType) " · ${entry.nodeTitle}" else ""
    val single = entry.strengthFields.size == 1
    Row(
        modifier = alphaMod.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        for (f in entry.strengthFields) {
            val sk = loraStrengthKey(entry.key, f)
            val raw = vm.loraStrength[sk].orEmpty()
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(strengthLabelRes(f, single)) + suffix,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                CompactTextField(
                    value = raw,
                    onValueChange = { vm.setLoraStrength(sk, it) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Decimal,
                    enabled = enabled,
                )
                if (raw.toDoubleOrNull() == null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.lora_strength_invalid),
                        fontSize = 11.sp,
                        color = scheme.error,
                    )
                }
            }
        }
    }
}

/** 强度框的小标签：只有一框时一律「强度」；多框时模型 / CLIP 分开。 */
private fun strengthLabelRes(field: String, single: Boolean): Int = when {
    single -> R.string.lora_strength
    field == "strength_model" -> R.string.lora_strength_model
    field == "strength_clip" || field == "strengthTwo" || field == "clip_strength" -> R.string.lora_strength_clip
    else -> R.string.lora_strength
}

/**
 * 没有对应 model spec 的 LoRA 开关行（round16）：导入时没勾文件字段的单加载器，以及所有 Power 槽。
 * 第一行标题「LoRA」+ 右侧开关；第二行文件名小字（· 节点标题）；第三行强度行。
 * 关闭时强度行置灰禁用，显示对应提示。除开关外不提供编辑（不选文件）。
 */
@Composable
fun LoraToggleRow(vm: CustomModel, entry: LoraEntry) {
    val scheme = MaterialTheme.colorScheme
    val on = vm.loraOn[entry.key] ?: entry.defaultOn
    val cd = stringResource(R.string.lora_toggle_cd)
    val linked = stringResource(R.string.lora_linked)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("LoRA", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = { vm.setLoraOn(entry.key, it) },
                modifier = Modifier.semantics { contentDescription = cd },
            )
        }
        Spacer(Modifier.height(2.dp))
        val fileShown = entry.fileName.takeIf { it.isNotBlank() }?.let { modelFileName(it) } ?: linked
        Text(
            "$fileShown · ${entry.nodeTitle}",
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LoraStrengthRow(vm, entry, enabled = on)
        if (!on) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (entry.slot == null) R.string.lora_off_hint else R.string.lora_slot_off_hint),
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 视频输出尺寸：只有自定义（默认 720P）和保持参考图原始分辨率。没有预设。 */
@Composable
private fun VideoSizeRow(vm: CustomModel, spec: JSONObject) {
    val scheme = MaterialTheme.colorScheme
    val sel = vm.sizesSelected.singleOrNull()
    val source = sel == VIDEO_SIZE_SOURCE
    var customW by remember(Specs.key(spec)) { mutableStateOf("1280") }
    var customH by remember(Specs.key(spec)) { mutableStateOf("720") }
    LaunchedEffect(sel) {
        if (sel != null && sel != VIDEO_SIZE_SOURCE) {
            val p = sel.split("x", "X", "×")
            if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                customW = p[0]
                customH = p[1]
            }
        }
    }
    fun commitCustom() {
        val w = customW.toIntOrNull() ?: return
        val h = customH.toIntOrNull() ?: return
        if (w < 16 || h < 16) return
        val size = "${clamp16(w, Specs.widthRange(spec))}x${clamp16(h, Specs.heightRange(spec))}"
        if (size != vm.sizesSelected.singleOrNull()) {
            vm.setSize(size)
            vm.persist()
        }
    }
    val sizeCustom = stringResource(R.string.size_custom)
    val sizeSource = stringResource(R.string.size_source)
    CompactMenuField(
        value = if (source) sizeSource else sizeCustom,
        options = listOf(sizeCustom, sizeSource),
        modifier = Modifier.fillMaxWidth(),
    ) { i ->
        if (i == 1) {
            if (!source) {
                vm.setSize(VIDEO_SIZE_SOURCE)
                vm.persist()
            }
        } else if (source) {
            if (customW.isBlank() || customH.isBlank()) {
                customW = "1280"
                customH = "720"
            }
            commitCustom()
            if (vm.sizesSelected.singleOrNull() == VIDEO_SIZE_SOURCE) {
                vm.setSize(VIDEO_SIZE_720P)
                vm.persist()
            }
        }
    }
    if (source) {
        Spacer(Modifier.height(8.dp))
        val (maxW, maxH) = videoSourceWarnLimit(spec)
        val tooBig = vm.srcW > 0 && vm.srcH > 0 && (vm.srcW > maxW || vm.srcH > maxH)
        when {
            tooBig -> Text(
                stringResource(R.string.video_size_warn, vm.srcW, vm.srcH, maxW, maxH),
                fontSize = 11.sp,
                color = scheme.error,
            )
            vm.srcW > 0 && vm.srcH > 0 -> Text(
                stringResource(R.string.video_size_use, vm.srcW, vm.srcH),
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
            else -> Text(
                stringResource(R.string.video_size_fallback),
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    } else {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.video_size_custom_hint),
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = customW,
                onValueChange = { customW = it.filter { ch -> ch.isDigit() }.take(4); commitCustom() },
                label = { Text(stringResource(R.string.label_width), color = scheme.onSurfaceVariant) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            )
            Text("×", color = scheme.onSurfaceVariant)
            OutlinedTextField(
                value = customH,
                onValueChange = { customH = it.filter { ch -> ch.isDigit() }.take(4); commitCustom() },
                label = { Text(stringResource(R.string.label_height), color = scheme.onSurfaceVariant) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            )
        }
    }
}

/** 抽卡共享筛选：分类下拉 + 包含 + 排除，所有 prompt_pool 抽卡共用（自 T2iScreen 移植）。 */
@Composable
fun DrawFilterRow(vm: CustomModel) {
    val labels = drawCategoryLabels(vm.drawCategories)
    val idx = vm.drawCategories.indexOfFirst { it.second == vm.drawCat }.coerceAtLeast(0)
    val all = stringResource(R.string.label_all)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactMenuField(
            value = labels.getOrElse(idx) { all },
            options = labels,
            modifier = Modifier.weight(1.1f),
        ) { i ->
            vm.drawCategories.getOrNull(i)?.let { vm.onDrawCat(it.second) }
        }
        MiniField(stringResource(R.string.filter_include), vm.drawInc, { vm.onDrawInc(it) }, Modifier.weight(1f))
        MiniField(stringResource(R.string.filter_exclude), vm.drawExc, { vm.onDrawExc(it) }, Modifier.weight(1f))
    }
}

/** 无可用工作流时的空状态：生成页与设置页共用，按钮跳工作流/库页。 */
@Composable
fun WorkflowEmptyState(onGoImport: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_workflows), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_workflows_body),
            fontSize = 12.sp,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoImport, shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(R.string.go_import))
        }
    }
}

fun trimNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

private fun clamp16(value: Int, range: IntRange): Int =
    ((value / 16) * 16).coerceIn(range.first, range.last)

@Composable
private fun RangeHint(spec: JSONObject) {
    val min = Specs.min(spec)
    val max = Specs.max(spec)
    if (min != null || max != null) {
        Text(
            stringResource(
                R.string.range_hint,
                min?.toInt()?.toString() ?: "-∞",
                max?.toInt()?.toString() ?: "∞",
            ),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun knownLabels(labels: List<String>): List<String> {
    val out = ArrayList<String>(labels.size)
    for (item in labels) out.add(knownText(item))
    return out
}

@Composable
private fun drawCategoryLabels(categories: List<Pair<String, String>>): List<String> {
    val all = stringResource(R.string.label_all)
    val out = ArrayList<String>(categories.size)
    for (pair in categories) {
        if (pair.second.isEmpty()) {
            out.add(all)
        } else {
            out.add(
                stringResource(
                    R.string.category_count,
                    knownText(pair.second),
                    pair.first.toIntOrNull() ?: 0,
                ),
            )
        }
    }
    return out
}

@Composable
private fun stringZh(@StringRes id: Int): String {
    val base = LocalContext.current.applicationContext
    val zh = remember(base) {
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags("zh"))
        base.createConfigurationContext(config)
    }
    return zh.getString(id)
}
