@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mie.kreaworkbench.ui.screens.custom

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.data.modelFileName
import com.mie.kreaworkbench.data.workflows.Specs
import com.mie.kreaworkbench.ui.components.KreaCard
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.components.TextResultActions
import com.mie.kreaworkbench.ui.motion.ErrorHaptic
import com.mie.kreaworkbench.ui.motion.JobHaptics
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.motion.buzz
import com.mie.kreaworkbench.ui.screens.gen.BatchPicker
import com.mie.kreaworkbench.ui.screens.gen.JobStatusCard
import com.mie.kreaworkbench.ui.screens.gen.ShimmerStrip
import com.mie.kreaworkbench.ui.screens.gen.ThumbRow
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.knownText
import com.mie.kreaworkbench.ui.locale.qty
import com.mie.kreaworkbench.ui.locale.str
import java.io.File
import kotlin.math.roundToInt

/** 生成页的分工：text/prompt_pool/image_sizes/int_random/file:image；model/select/int/float/bool 在设置页。 */
private val GEN_TYPES = setOf("text", "prompt_pool", "image_sizes", "int_random", "file:image")

@Composable
fun CustomScreen(
    workflowId: String,
    pendingPath: String?,
    pendingWorkflowId: String?,
    onConsumePending: () -> Unit,
    onGallery: () -> Unit,
    onOpen: (List<Long>, Int) -> Unit,
    onWorkflows: () -> Unit,
    vm: CustomModel = viewModel(),
) {
    // 「用于图生图」：pending 往往比 currentWorkflow 先到。id 还没对上时不消费，
    // 否则紧接着的 bind() 会把刚填的参考图清掉。对上之后先 bind 再填。
    LaunchedEffect(workflowId, pendingPath, pendingWorkflowId) {
        val path = pendingPath
        if (!path.isNullOrBlank() && workflowId == pendingWorkflowId) {
            vm.bind(workflowId)
            vm.usePath(path)
            onConsumePending()
        } else {
            vm.bind(workflowId)
        }
    }
    val s by vm.settings.collectAsState()
    val jobs by vm.live.collectAsState()
    val mine = jobs.filter { it.mode == "custom" }
    val busy = mine.firstOrNull { it.phase !in setOf("success", "partial", "failed", "cancelled") }
    // 提交 400 等失败原先被滤掉，服务随即停、界面没有任何提示。没有进行中任务时，最新一条若是失败就留下卡片。
    val card = busy ?: mine.firstOrNull()?.takeIf { it.phase == "failed" }
    val haptic = LocalHapticFeedback.current
    JobHaptics(mine)
    ErrorHaptic(vm.message)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.generate() }
    var sheet by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.useUri(uri)
    }
    val scheme = MaterialTheme.colorScheme
    val total = vm.totalCount()
    val overLimit = total > 8
    val header = if (vm.displayName.isBlank()) {
        stringResource(R.string.import_workflow)
    } else {
        knownText(vm.displayName)
    }
    val menuName = if (vm.displayName.isBlank()) "…" else knownText(vm.displayName)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(header, actions = {
            // 右上角下拉直切（round7 fix3）：点选即切工作流（settings.currentWorkflow 变化 → bind()，
            // 含挂起编辑刷盘与文本卡清理），不再跳工作流 tab；导入/删除等管理入口仍在菜单末尾。
            var wfMenuOpen by remember { mutableStateOf(false) }
            val wfList by vm.workflowList.collectAsState()
            Box {
                TextButton(onClick = { wfMenuOpen = true }) {
                    Text(stringResource(R.string.workflow_menu, menuName), fontSize = 12.sp)
                }
                DropdownMenu(expanded = wfMenuOpen, onDismissRequest = { wfMenuOpen = false }) {
                    wfList.forEach { wf ->
                        DropdownMenuItem(
                            text = { Text(knownText(wf.displayName)) },
                            trailingIcon = {
                                if (wf.id == s.currentWorkflow) {
                                    Icon(Lucide.Check, stringResource(R.string.badge_current), tint = scheme.primary)
                                }
                            },
                            onClick = {
                                wfMenuOpen = false
                                if (wf.id != s.currentWorkflow) vm.selectWorkflow(wf.id)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.manage_workflows)) },
                        onClick = {
                            wfMenuOpen = false
                            onWorkflows()
                        },
                    )
                }
            }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            if (vm.loading) {
                Text(stringResource(R.string.state_loading), color = scheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            // 生成页只按 GEN_TYPES 过滤渲染（spec 控件在 SpecRows.kt，与设置页共用）。
            // 卡片固定顺序（对齐 direct9 文生图页）：提示词 → 参考图 → 尺寸与张数 → 视频参数 → 种子 →
            // 负向提示词（折叠）→ 其余（进阶折叠）。
            // 反推这类工作流没有提示词字段是正常的：没有就整卡隐藏（抽卡也跟着消失）。
            val prompts = vm.specs.filter {
                Specs.type(it) in setOf("text", "prompt_pool") &&
                    Specs.tier(it) != "advanced" && !it.optBoolean("kwb_negative")
            }
            val negatives = vm.specs.filter { it.optBoolean("kwb_negative") }
            val images = vm.specs.filter { Specs.type(it) == "file:image" && Specs.tier(it) != "advanced" }
            val sizes = vm.specs.filter { Specs.type(it) == "image_sizes" && Specs.tier(it) != "advanced" }
            val genInts = vm.specs.filter { Specs.type(it) == "int" && it.optBoolean("kwb_gen") }
            val seeds = vm.specs.filter { Specs.type(it) == "int_random" && Specs.tier(it) != "advanced" }
            val advanced = vm.specs.filter {
                Specs.tier(it) == "advanced" && Specs.type(it) in GEN_TYPES && !it.optBoolean("kwb_negative")
            }
            if (prompts.isNotEmpty()) {
                KreaCard {
                    Text(stringResource(R.string.label_prompt), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    prompts.forEach { spec ->
                        SpecRow(
                            vm, spec,
                            { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            { sheet = true },
                            // 主提示词（mainPromptKey 命中的 prompt_pool）下方挂抽卡共享筛选
                            belowMainPrompt = { DrawFilterRow(vm) },
                            cardTitle = R.string.label_prompt,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                KreaCard {
                    Text(stringResource(R.string.card_reference), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    images.forEach { spec ->
                        SpecRow(
                            vm, spec,
                            { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            { sheet = true },
                            cardTitle = R.string.card_reference,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val sizeTitleRes = if (sizes.isEmpty()) R.string.card_count else R.string.card_size_batch
            KreaCard {
                // 没有尺寸字段（如反推工作流）时只留张数，标题跟着变
                Text(
                    stringResource(sizeTitleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                sizes.forEach { spec ->
                    SpecRow(
                        vm, spec,
                        { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        { sheet = true },
                        cardTitle = sizeTitleRes,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 卡片标题已经是「张数」时不再画一行同样的小号标签
                if (sizeTitleRes != R.string.card_count) {
                    Text(stringResource(R.string.card_count), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                BatchPicker(vm.batchSize, onChange = { vm.setBatch(it); vm.persist() })
                Spacer(Modifier.height(8.dp))
                Text(
                    if (overLimit) stringResource(R.string.over_limit, total) else pluralStringResource(R.plurals.image_total, total, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (overLimit) scheme.error else scheme.onSurfaceVariant,
                )
            }
            if (genInts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                KreaCard {
                    Text(stringResource(R.string.card_video), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    genInts.forEach { spec ->
                        SpecRow(
                            vm, spec,
                            { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            { sheet = true },
                            cardTitle = R.string.card_video,
                        )
                    }
                }
            }
            if (negatives.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CollapsibleCard(stringResource(R.string.label_negative)) {
                    negatives.forEach { spec ->
                        SpecRow(
                            vm, spec,
                            { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            { sheet = true },
                            cardTitle = R.string.label_negative,
                        )
                    }
                }
            }
            if (seeds.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                KreaCard {
                    Text(stringResource(R.string.label_seed), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    seeds.forEach { spec ->
                        SpecRow(
                            vm, spec,
                            { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            { sheet = true },
                            cardTitle = R.string.label_seed,
                        )
                    }
                }
            }
            if (advanced.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CollapsibleCard(stringResource(R.string.label_advanced)) {
                    advanced.forEach { spec ->
                        SpecRow(
                            vm, spec,
                            { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            { sheet = true },
                            cardTitle = R.string.label_advanced,
                        )
                    }
                }
            }
            val summary = customSummary(vm, LocalContext.current)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(summary, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    haptic.buzz(HapticFeedbackType.GestureThresholdActivate)
                    val app = vm.getApplication<android.app.Application>()
                    val need = Build.VERSION.SDK_INT >= 33 && s.notifyEnabled &&
                        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (need) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.generate()
                },
                enabled = !vm.submitting && !overLimit && !vm.loading,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
            ) {
                Text(
                    if (vm.submitting) stringResource(R.string.state_submitting) else stringResource(R.string.action_generate),
                    color = scheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (vm.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(vm.message, color = scheme.error)
            }
            if (card != null) {
                Spacer(Modifier.height(12.dp))
                JobStatusCard(
                    card,
                    onCancel = { vm.cancel(card.clientJobId) },
                    // 下载失败的重试要走 retryDownload（重启轮询协程）；paused 的继续走 kick
                    onResume = {
                        if (card.downloadFailed) vm.retryDownload(card.clientJobId) else vm.resume(card.clientJobId)
                    },
                )
            }
            AnimatedVisibility(visible = busy != null && vm.results.isEmpty()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    ShimmerStrip(vm.batchSize)
                }
            }
            // 文本结果（反推提示词等）：可滚动可选中，三个操作按钮；点卡片进查看页（kind=text 页）
            val textRows = vm.textResults()
            if (textRows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val app = LocalContext.current.applicationContext as KreaApp
                textRows.forEachIndexed { textIdx, row ->
                    KreaCard(
                        modifier = Modifier.clickable {
                            onOpen(textRows.map { it.id }, textIdx)
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.text_result),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.text_open),
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                row.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextResultActions(
                            text = row.text,
                            app = app,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            val assets = vm.mediaResults().take(20)
            Spacer(Modifier.height(12.dp))
            ThumbRow(
                assets,
                onOpen = { index -> onOpen(assets.map { it.id }, index) },
                onGallery = onGallery,
            )
            Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
        }
    }

    // 满 10 秒且上传还在跑：继续 / 压缩。上传已经失败：压缩 / 取消。点外面分别等于继续、取消。
    val askJob = mine.firstOrNull { it.phase == "upload_ask" || it.phase == "upload_fail_ask" }
    if (askJob != null) {
        val running = askJob.phase == "upload_ask"
        AlertDialog(
            onDismissRequest = { vm.answerUploadAsk(askJob.clientJobId, false) },
            title = { Text(if (running) stringResource(R.string.upload_slow_title) else stringResource(R.string.upload_fail_title)) },
            text = {
                Text(
                    if (running) {
                        stringResource(R.string.upload_slow_body)
                    } else {
                        askJob.error.ifBlank { stringResource(R.string.upload_fail_body) }
                    },
                )
            },
            confirmButton = {
                if (running) {
                    TextButton(onClick = { vm.answerUploadAsk(askJob.clientJobId, false) }) { Text(stringResource(R.string.action_keep_upload)) }
                } else {
                    TextButton(onClick = { vm.answerUploadAsk(askJob.clientJobId, true) }) { Text(stringResource(R.string.action_compress)) }
                }
            },
            dismissButton = {
                if (running) {
                    TextButton(onClick = { vm.answerUploadAsk(askJob.clientJobId, true) }) { Text(stringResource(R.string.action_compress)) }
                } else {
                    TextButton(onClick = { vm.answerUploadAsk(askJob.clientJobId, false) }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState()) {
            Text(stringResource(R.string.pick_from_gallery), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            // 护栏（direct12）：只给图片行——文本行无文件、视频不能当参考图，都不进网格
            val pickerRows = vm.pickerImages()
            if (pickerRows.isEmpty()) {
                Text(stringResource(R.string.gallery_empty_short), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(360.dp).padding(8.dp)) {
                items(pickerRows, key = { it.id }) { img ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(File(img.localPath)).size(360).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .animateItem()
                            .padding(4.dp)
                            .size(110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                vm.usePath(img.localPath)
                                sheet = false
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsibleCard(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    KreaCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(if (open) stringResource(R.string.collapse) else stringResource(R.string.expand), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** 生成按钮上方的摘要行：模型 · 步数 · CFG · 种子状态（随机/固定值）；取不到的项直接省略。 */
private fun customSummary(vm: CustomModel, context: Context): String {
    val parts = mutableListOf<String>()
    vm.specs.firstOrNull { Specs.type(it) == "model" }?.let { spec ->
        vm.textValues[Specs.key(spec)]?.takeIf { it.isNotBlank() }?.let { parts.add(modelFileName(it)) }
    }
    vm.specs.firstOrNull { Specs.type(it) == "int" && Specs.field(it) == "steps" }?.let { spec ->
        vm.textValues[Specs.key(spec)]?.toIntOrNull()?.let { n -> parts.add(context.qty(R.plurals.steps, n, n)) }
    }
    vm.specs.firstOrNull { Specs.type(it) == "float" && Specs.field(it) == "cfg" }?.let { spec ->
        vm.textValues[Specs.key(spec)]?.toDoubleOrNull()?.let { v ->
            val cfg = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format("%.1f", v)
            parts.add("CFG $cfg")
        }
    }
    vm.specs.firstOrNull { Specs.type(it) == "int_random" }?.let { spec ->
        val key = Specs.key(spec)
        val random = vm.randomOn[key] ?: Specs.randomDefault(spec)
        val value = vm.textValues[key]?.takeIf { it.isNotBlank() } ?: "0"
        parts.add(if (random) context.str(R.string.seed_random) else context.str(R.string.seed_fixed, value))
    }
    return parts.joinToString(" · ")
}
