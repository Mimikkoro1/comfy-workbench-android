@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mie.kreaworkbench.ui.screens.custom

import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.BUILTIN_SIZES
import com.mie.kreaworkbench.data.workflows.Specs
import com.mie.kreaworkbench.data.workflows.VIDEO_SIZE_720P
import com.mie.kreaworkbench.data.workflows.VIDEO_SIZE_SOURCE
import com.mie.kreaworkbench.data.workflows.isTextToImageWorkflow
import com.mie.kreaworkbench.data.workflows.shownHelp
import com.mie.kreaworkbench.data.workflows.videoSourceWarnLimit
import com.mie.kreaworkbench.ui.components.CompactMenuField
import com.mie.kreaworkbench.ui.components.CompactTextField
import com.mie.kreaworkbench.ui.components.MiniField
import com.mie.kreaworkbench.ui.components.PromptField
import com.mie.kreaworkbench.ui.locale.knownText
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * 生成页（CustomScreen）与设置页（ModelsScreen）共用的 spec 控件，禁止复制两份。
 * 生成分工：生成页渲染 text/prompt_pool/image_sizes/int_random/file:image，
 * 设置页渲染 model/select/int/float/bool——类型分支在同一份代码里，页面只按 type 过滤。
 */

/** 单个 spec 的控件。onPickAlbum / onPickGallerySheet 由页面提供（file:image 用）；
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
) {
    val key = Specs.key(spec)
    val type = Specs.type(spec)
    val scheme = MaterialTheme.colorScheme
    val rawLabel = Specs.label(spec)
    val label = knownText(rawLabel)
    val showLabel = if (cardTitle == null) true else rawLabel != stringZh(cardTitle)
    // 模型类 spec 不显示说明（旧定义里每个模型都带同一句 wf_help_model，重复占位）
    val help = if (type == "model") "" else shownHelp(spec, vm.outputKind)
    // 抽卡只在文生图、且当前库有条目时出现。判定只走 isTextToImageWorkflow。
    val hasLib by vm.hasPromptLibrary.collectAsState()
    val drawOk = hasLib && isTextToImageWorkflow(vm.outputKind, vm.specs)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (showLabel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
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
                    val poolSize = (if (type == "prompt_pool") vm.drawPoolSize else vm.libraryPoolSize).collectAsState()
                    poolSize.value?.let { n ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            pluralStringResource(R.plurals.draw_pool_count, n, n),
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (drawOk && belowMainPrompt != null &&
                    key == vm.mainPromptKey() &&
                    type == "prompt_pool"
                ) {
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
                // 选项没拉到（/object_info 失败）：退化为可编辑输入框，值保留；不再弹一个
                // 只有占位项的空下拉（round13 第 4 项）
                if (choices.isEmpty()) {
                    CompactTextField(
                        value = value,
                        onValueChange = { vm.setText(key, it, 400); vm.persist() },
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
                    // 空值显示「请选择」；有值但不在本机列表才显示「本机未找到」
                    val please = stringResource(R.string.please_select)
                    val placeholder = if (value.isBlank()) please else stringResource(R.string.model_missing, value)
                    val labels = if (found) choices else listOf(placeholder) + choices
                    CompactMenuField(
                        value = if (found) value else placeholder,
                        options = labels,
                        modifier = Modifier.fillMaxWidth(),
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
                val presets = Specs.sizePresets(spec).ifEmpty {
                    BUILTIN_SIZES.map { it.label to "${it.width}x${it.height}" }
                }
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
