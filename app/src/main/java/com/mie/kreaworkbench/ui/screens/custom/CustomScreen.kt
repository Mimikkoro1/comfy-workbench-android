@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mie.kreaworkbench.ui.screens.custom

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Dices
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Video
import com.mie.kreaworkbench.data.modelFileName
import com.mie.kreaworkbench.data.workflows.Specs
import com.mie.kreaworkbench.data.workflows.isTextToImageWorkflow
import com.mie.kreaworkbench.service.line
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.ui.components.ImmersiveHairline
import com.mie.kreaworkbench.ui.components.ImmersiveIcon
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.ImmersiveInkMuted
import com.mie.kreaworkbench.ui.components.ImmersiveShadow
import com.mie.kreaworkbench.ui.components.GlassAlertDialog
import com.mie.kreaworkbench.ui.components.GlassBottomSheet
import com.mie.kreaworkbench.ui.components.GlassCard
import com.mie.kreaworkbench.ui.components.GlassDanger
import com.mie.kreaworkbench.ui.components.GlassPopup
import com.mie.kreaworkbench.ui.components.LocalGlassDismiss
import com.mie.kreaworkbench.ui.components.glassPopupPanel
import com.mie.kreaworkbench.ui.components.suppressIme
import com.mie.kreaworkbench.ui.components.glass
import com.mie.kreaworkbench.ui.components.immersiveGlass
import com.mie.kreaworkbench.ui.components.TextResultActions
import com.mie.kreaworkbench.ui.motion.ErrorHaptic
import com.mie.kreaworkbench.ui.motion.JobHaptics
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.motion.buzz
import com.mie.kreaworkbench.service.jobProgress
import com.mie.kreaworkbench.ui.screens.gen.AssetStack
import com.mie.kreaworkbench.ui.screens.gen.BatchPicker
import com.mie.kreaworkbench.ui.screens.gen.GenerateCapsule
import com.mie.kreaworkbench.ui.screens.gen.GlassPeekBar
import com.mie.kreaworkbench.ui.screens.gen.StatusScrim
import com.mie.kreaworkbench.ui.screens.gen.capsuleHint
import com.mie.kreaworkbench.ui.screens.gen.capsuleStatus
import com.mie.kreaworkbench.ui.theme.PhotoBackdrop
import com.mie.kreaworkbench.ui.theme.PhotoOnBackdrop
import com.mie.kreaworkbench.ui.theme.SystemBarAppearance
import org.json.JSONObject
import com.mie.kreaworkbench.ui.screens.gen.VideoThumbCell
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.knownText
import com.mie.kreaworkbench.ui.locale.qty
import com.mie.kreaworkbench.ui.locale.str
import java.io.File
import kotlin.math.roundToInt

/** 生成页的分工：text/prompt_pool/image_sizes/int_random/file:image/file:video；model/select/int/float/bool 在设置页。 */
private val GEN_TYPES = setOf("text", "prompt_pool", "image_sizes", "int_random", "file:image", "file:video")

/** r21：这些 phase 视为「有进度的进行中」，是底栏进度条优先展示的对象（不被新排队的 queued 盖掉）。 */
private val PROGRESS_PHASES = setOf("running", "downloading", "uploading", "upload_ask", "submitting")

@Composable
fun CustomScreen(
    workflowId: String,
    pendingPath: String?,
    pendingWorkflowId: String?,
    onConsumePending: () -> Unit,
    onGallery: () -> Unit,
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
    // r21 队列进度修：snapshot 按 createdAt 降序，最新排队任务排在最前；旧写法
    // firstOrNull{非终态} 会先命中 queued，把仍在跑的 running 进度盖成「排队中」。
    // 现在优先「有进度的进行中」，其次排队，最后兜底（paused / upload_fail_ask 等仍要给入口）。
    val active = mine.filter { it.phase !in setOf("success", "partial", "failed", "cancelled") }
    val progressJob = active.firstOrNull { it.phase in PROGRESS_PHASES }
        ?: active.firstOrNull { it.phase == "queued" }
        ?: active.firstOrNull()
    // 进度跟的之外还有几个在排队（跑一张时再点生成的场景），供底栏给短提示
    val queuedExtra = if (progressJob != null) active.count { it.clientJobId != progressJob.clientJobId && it.phase == "queued" } else 0
    // 提交 400 等失败原先被滤掉，服务随即停、界面没有任何提示。没有进行中任务时，
    // 最新一条若是失败就在底栏留一行错误状态（r21 前是顶部大卡片）。
    val failedJob = if (progressJob == null) mine.firstOrNull()?.takeIf { it.phase == "failed" } else null
    val haptic = LocalHapticFeedback.current
    JobHaptics(mine)
    ErrorHaptic(vm.message)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.generateFromButton() }
    var sheet by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.useUri(uri)
    }
    // 参考视频（round17）：系统选择器只显示视频；图库来源走底部弹窗 videoSheet
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.useVideoUri(uri)
    }
    var videoSheet by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val textToImage = isTextToImageWorkflow(vm.outputKind, vm.specs)
    val gachaWanted = vm.advEnabled(s.advGacha)
    val gachaOn = gachaWanted && textToImage
    val total = vm.totalCount()
    // 高级抽卡的张数是「抽几条词」，每条单独入队，不受单次 8 张的限制。10 是十连。
    val overLimit = !gachaOn && total > 8
    LaunchedEffect(gachaOn, vm.batchSize) {
        if (!gachaOn && vm.batchSize > 8) {
            vm.setBatch(8)
            vm.persist()
        }
    }
    // r39：设置真正从磁盘加载完成后才按真实地址刷新；key 带 loaded，避免拿 stateIn 初值（默认地址）开刷。
    // 函数内部自带单飞 + 同址 10s 防抖，tab 来回切不会狂刷。
    LaunchedEffect(gachaOn, vm.settingsLoaded, s.advGachaUrl) {
        if (gachaOn && vm.settingsLoaded) vm.refreshGachaMasters(s.advGachaUrl)
    }
    var paramsOpen by remember { mutableStateOf(false) }
    var gachaGuide by remember { mutableStateOf(false) }
    var promptOpen by remember { mutableStateOf(false) }
    var libraryOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var tempOpen by remember { mutableStateOf(false) }
    LaunchedEffect(gachaOn) {
        if (gachaOn) {
            promptOpen = false
            libraryOpen = false
        }
    }
    var cancelAsk by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val view = LocalView.current
    // 参数类（尺寸/种子/张数/视频参数/进阶）进「更多设置」。
    // 提示词和抽卡库收成底部条；负向、参考图/视频留在画面上方。
    val prompts = vm.specs.filter {
        Specs.type(it) in setOf("text", "prompt_pool") &&
            Specs.tier(it) != "advanced" && !it.optBoolean("kwb_negative")
    }
    val negatives = vm.specs.filter { it.optBoolean("kwb_negative") }
    val images = vm.specs.filter { Specs.type(it) == "file:image" && Specs.tier(it) != "advanced" }
    val videos = vm.specs.filter { Specs.type(it) == "file:video" && Specs.tier(it) != "advanced" }
    val sizes = vm.specs.filter { Specs.type(it) == "image_sizes" && Specs.tier(it) != "advanced" }
    val genInts = vm.specs.filter { Specs.type(it) in setOf("int", "float") && it.optBoolean("kwb_gen") }
    val seeds = vm.specs.filter { Specs.type(it) == "int_random" && Specs.tier(it) != "advanced" }
    val advanced = vm.specs.filter {
        Specs.tier(it) == "advanced" && Specs.type(it) in GEN_TYPES && !it.optBoolean("kwb_negative")
    }
    val summary = customSummary(vm, LocalContext.current)
    val media = vm.mediaResults()
    val onPhoto = media.isNotEmpty()
    val metaColor = if (onPhoto) Color.White.copy(alpha = 0.88f) else scheme.onSurfaceVariant
    val mainKey = vm.mainPromptKey()
    val hasLib by vm.hasPromptLibrary.collectAsState()
    // 普通抽卡才在生成页抽本地库。高级抽卡自己有四筛，这里不放骰子。
    val drawKey = mainKey?.takeIf {
        !gachaOn && hasLib && textToImage && prompts.any { spec -> Specs.key(spec) == it }
    }
    val promptPreview = vm.mainPrompt().replace('\n', ' ').trim()
    val poolCount by vm.drawPoolSize.collectAsState()
    val poolNow = poolCount
    val catShown = if (vm.drawCat.isBlank()) stringResource(R.string.label_all) else knownText(vm.drawCat)
    val libraryLine = if (poolNow == null) catShown else stringResource(R.string.draw_library_line, catShown, poolNow)

    if (onPhoto) SystemBarAppearance(dark = true)
    Column(Modifier.fillMaxSize()) {
        StatusScrim(active = onPhoto)
        ImmersiveStage(
            vm = vm,
            media = media,
            onPhoto = onPhoto,
            negatives = negatives,
            images = images,
            videos = videos,
            onGallery = onGallery,
            onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPickGallery = { sheet = true },
            onPickVideo = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            onPickVideoGallery = { videoSheet = true },
        )
        // —— r21 固定底栏区：细进度（进行中/失败一行）→ 生成+齿轮 → 摘要小字，均不随滚动 ——
        val barSink = remember { MutableInteractionSource() }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = LocalExtraBottom.current)
                .then(
                    if (onPhoto) {
                        Modifier.clickable(interactionSource = barSink, indication = null, onClick = {})
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (failedJob != null) {
                // 没有进行中任务时，最新一条若是失败，缩成一行错误状态（老的大卡片不再放顶部）
                Text(
                    failedJob.line(LocalContext.current),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (onPhoto) Color(0xFFFF8A80) else scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (vm.message.isNotBlank()) {
                Text(
                    vm.message,
                    color = if (onPhoto) Color(0xFFFF8A80) else scheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (!gachaOn && prompts.isNotEmpty()) {
                GlassPeekBar(
                    text = promptPreview.ifBlank { stringResource(R.string.prompt_placeholder) },
                    contentDescription = stringResource(R.string.prompt_edit_title),
                    placeholder = promptPreview.isBlank(),
                    onClick = { promptOpen = true },
                    trailing = drawKey?.let { key ->
                        {
                            val drawing = vm.drawingKey == key
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !drawing) { vm.draw(key) }
                                    .padding(6.dp),
                            ) {
                                ImmersiveIcon(
                                    Lucide.Dices,
                                    if (drawing) stringResource(R.string.state_drawing) else stringResource(R.string.action_draw),
                                    size = 18.dp,
                                    alpha = if (drawing) 0.45f else 1f,
                                )
                            }
                        }
                    },
                )
            }
            if (drawKey != null) {
                Spacer(Modifier.height(8.dp))
                GlassPeekBar(
                    text = libraryLine,
                    contentDescription = stringResource(R.string.draw_library_open),
                    onClick = { libraryOpen = true },
                )
            }
            if (gachaOn) {
                AdvGachaFilterBar(
                    summary = advGachaSummary(vm, s),
                    tempPrompt = vm.tempPrompt,
                    onClick = { filtersOpen = true },
                    onTemp = { tempOpen = true },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val generating = progressJob != null
                val bar = progressJob?.jobProgress()
                val canEnqueue = !vm.submitting && !overLimit && !vm.loading
                GenerateCapsule(
                    generating = generating,
                    label = if (vm.submitting) stringResource(R.string.state_submitting) else stringResource(R.string.action_generate),
                    status = progressJob?.let { capsuleStatus(it, queuedExtra, LocalContext.current) }.orEmpty(),
                    hint = progressJob?.let { capsuleHint(it, LocalContext.current) }.orEmpty(),
                    fraction = bar?.bar ?: -1f,
                    indeterminate = bar?.indeterminate == true,
                    enabled = !vm.loading && (generating || canEnqueue),
                    dimmed = !generating && !canEnqueue,
                    frosted = true,
                    onClick = {
                        val job = progressJob
                        if (job?.phase == "paused") {
                            vm.resume(job.clientJobId)
                            return@GenerateCapsule
                        }
                        if (job?.phase == "downloading" && job.downloadFailed) {
                            vm.retryDownload(job.clientJobId)
                            return@GenerateCapsule
                        }
                        if (!canEnqueue) return@GenerateCapsule
                        val app = vm.getApplication<android.app.Application>()
                        val need = Build.VERSION.SDK_INT >= 33 && s.notifyEnabled &&
                            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (need) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.generateFromButton()
                    },
                    onLongClick = if (generating) {
                        {
                            haptic.buzz(HapticFeedbackType.LongPress)
                            cancelAsk = true
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val gearShape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(gearShape)
                        .immersiveGlass(20.dp)
                        .border(1.dp, ImmersiveHairline, gearShape)
                        .clickable { paramsOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    ImmersiveIcon(Lucide.Settings, stringResource(R.string.more_settings), size = 22.dp)
                }
            }
            // 摘要小字贴着生成按钮下（r21：与底栏同区，不随滚动跑到看不见；单行省略）
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall.copy(shadow = if (onPhoto) ImmersiveShadow else null),
                    color = metaColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    // 满 10 秒且上传还在跑：继续 / 压缩。上传已经失败：压缩 / 取消。点外面分别等于继续、取消。
    val askJob = mine.firstOrNull { it.phase == "upload_ask" || it.phase == "upload_fail_ask" }
    if (askJob != null) {
        val running = askJob.phase == "upload_ask"
        GlassAlertDialog(
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
        GlassBottomSheet(onDismissRequest = { sheet = false }) {
            Text(
                stringResource(R.string.pick_from_gallery),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ImmersiveInk,
            )
            // 护栏（direct12）：只给图片行——文本行无文件、视频不能当参考图，都不进网格
            val pickerRows = vm.pickerImages()
            if (pickerRows.isEmpty()) {
                Text(stringResource(R.string.gallery_empty_short), modifier = Modifier.padding(16.dp), color = ImmersiveInkMuted)
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

    if (videoSheet) {
        GlassBottomSheet(onDismissRequest = { videoSheet = false }) {
            Text(
                stringResource(R.string.pick_video_from_gallery),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ImmersiveInk,
            )
            // 护栏（round17）：只列还存在的视频行——文本行 / 图片行 / localPath 为空或文件已丢的行都不进网格
            val videoRows = vm.pickerVideos()
            if (videoRows.isEmpty()) {
                Text(stringResource(R.string.gallery_empty_videos), modifier = Modifier.padding(16.dp), color = ImmersiveInkMuted)
            }
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(360.dp).padding(8.dp)) {
                items(videoRows, key = { it.id }) { img ->
                    // 点格子只选中不播放；VideoThumbCell 原样复用（画廊 / 任务卡同款首帧 + 时长角标）
                    Box(
                        Modifier
                            .animateItem()
                            .padding(4.dp)
                            .size(110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                vm.useVideoPath(img.localPath)
                                videoSheet = false
                            },
                    ) {
                        VideoThumbCell(img)
                    }
                }
            }
        }
    }

    if (cancelAsk && progressJob != null) {
        val jobId = progressJob.clientJobId
        GlassAlertDialog(
            onDismissRequest = { cancelAsk = false },
            title = { Text(stringResource(R.string.cancel_gen_title)) },
            text = { Text(stringResource(R.string.cancel_gen_body)) },
            confirmButton = {
                TextButton(onClick = {
                    cancelAsk = false
                    vm.cancel(jobId)
                }) { Text(stringResource(R.string.cancel_gen_confirm), color = GlassDanger) }
            },
            dismissButton = {
                TextButton(onClick = { cancelAsk = false }) { Text(stringResource(R.string.cancel_gen_dismiss)) }
            },
        )
    }

    if (promptOpen) {
        GlassBottomSheet(
            onDismissRequest = { promptOpen = false },
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    stringResource(R.string.prompt_edit_title),
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                prompts.forEach { spec ->
                    SpecRow(
                        vm, spec,
                        { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        { sheet = true },
                        cardTitle = R.string.label_prompt,
                        allowDraw = false,
                    )
                }
            }
        }
    }

    if (libraryOpen) {
        GlassBottomSheet(
            onDismissRequest = { libraryOpen = false },
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    stringResource(R.string.draw_library_title),
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                DrawFilterRow(vm)
                poolNow?.let { n ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        pluralStringResource(R.plurals.draw_pool_count, n, n),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                val close = LocalGlassDismiss.current
                TextButton(
                    onClick = { close() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_done)) }
            }
        }
    }

    if (tempOpen) {
        AdvGachaTempSheet(vm) { tempOpen = false }
    }

    if (filtersOpen) {
        GlassBottomSheet(
            onDismissRequest = { filtersOpen = false },
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    stringResource(R.string.gacha_filters_title),
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AdvGachaFilterPanel(vm, s)
                Spacer(Modifier.height(12.dp))
                val close = LocalGlassDismiss.current
                TextButton(
                    onClick = { close() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_done)) }
            }
        }
    }

    if (paramsOpen) {
        GlassBottomSheet(
            onDismissRequest = { paramsOpen = false },
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    stringResource(R.string.more_settings),
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (textToImage) {
                    AdvGachaSettings(vm, s, onAskEnable = { gachaGuide = true })
                    Spacer(Modifier.height(12.dp))
                }
                val showSizesHere = sizes.isNotEmpty()
                val sizeTitleRes = if (showSizesHere) R.string.card_size_batch else R.string.card_count
                GlassCard {
                    Text(
                        stringResource(sizeTitleRes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ImmersiveInk,
                    )
                    if (showSizesHere) {
                        sizes.forEach { spec ->
                            SpecRow(
                                vm, spec,
                                { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                { sheet = true },
                                cardTitle = sizeTitleRes,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (sizeTitleRes != R.string.card_count) {
                        Text(stringResource(R.string.card_count), style = MaterialTheme.typography.labelMedium, color = ImmersiveInkMuted)
                    }
                    BatchPicker(vm.batchSize, showTen = gachaOn, onChange = { vm.setBatch(it); vm.persist() })
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (overLimit) stringResource(R.string.over_limit, total) else pluralStringResource(R.plurals.image_total, total, total),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (overLimit) GlassDanger else ImmersiveInkMuted,
                    )
                }
                if (genInts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    GlassCard {
                        Text(stringResource(R.string.card_video), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ImmersiveInk)
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
                if (seeds.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    GlassCard {
                        Text(stringResource(R.string.label_seed), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ImmersiveInk)
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
                                {
                                    suppressIme(view) {
                                        focus.clearFocus(force = true)
                                        paramsOpen = false
                                    }
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                {
                                    suppressIme(view) {
                                        focus.clearFocus(force = true)
                                        paramsOpen = false
                                    }
                                    sheet = true
                                },
                                onPickVideo = {
                                    suppressIme(view) {
                                        focus.clearFocus(force = true)
                                        paramsOpen = false
                                    }
                                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                },
                                onPickVideoGallery = {
                                    suppressIme(view) {
                                        focus.clearFocus(force = true)
                                        paramsOpen = false
                                    }
                                    videoSheet = true
                                },
                                cardTitle = R.string.label_advanced,
                            )
                        }
                    }
                }
            }
        }
    }

    if (gachaGuide) {
        AdvGachaGuideDialog(
            onConfirm = {
                vm.setAdvGacha(true)
                gachaGuide = false
            },
            onNever = {
                vm.setAdvGacha(true)
                vm.dismissGachaGuide()
                gachaGuide = false
            },
            onCancel = { gachaGuide = false },
        )
    }
}

/** 主列让给大图。上面只留加载、文本结果和负向词。参考图和参考视频是左下角一排槽位。 */
@Composable
private fun ColumnScope.ImmersiveStage(
    vm: CustomModel,
    media: List<ImageRow>,
    onPhoto: Boolean,
    negatives: List<JSONObject>,
    images: List<JSONObject>,
    videos: List<JSONObject>,
    onGallery: () -> Unit,
    onPickImage: () -> Unit,
    onPickGallery: () -> Unit,
    onPickVideo: () -> Unit,
    onPickVideoGallery: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val textRows = vm.textResults()
    val ink = if (onPhoto) Color.White.copy(alpha = 0.85f) else scheme.onSurfaceVariant
    val hasTop = vm.loading || textRows.isNotEmpty() || negatives.isNotEmpty()
    val slots = refSlots(vm, images, videos)
    // r29.2：文本结果要能整块读完、按钮始终露出来。
    // 以前这列封顶 300dp 又整列滚动，卡片正文还有 220dp 上限，卡底按钮直接被裁在可视区外。
    // 现在顶部列占满舞台，只给底部参考槽 / 缩略图让位（不压住它们，也不被它们压住）。
    val bottomReserve = when {
        media.isNotEmpty() -> 116.dp // AssetStack 108 + 底距 4 + 间隙
        slots.isNotEmpty() -> 84.dp // 参考槽 76 + 底距 4 + 间隙
        else -> 0.dp
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (hasTop) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomReserve),
            ) {
                if (vm.loading) {
                    Text(
                        stringResource(R.string.state_loading),
                        color = ink,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                TextResultCards(textRows)
                if (negatives.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    CollapsibleCard(stringResource(R.string.label_negative)) {
                        negatives.forEach { spec ->
                            SpecRow(
                                vm, spec,
                                onPickImage,
                                onPickGallery,
                                cardTitle = R.string.label_negative,
                            )
                        }
                    }
                }
            }
        }
        if (slots.isNotEmpty()) {
            RefSlotRow(
                slots = slots,
                onAlbum = { slot -> if (slot.video) onPickVideo() else onPickImage() },
                onGallery = { slot -> if (slot.video) onPickVideoGallery() else onPickGallery() },
                onRemove = { slot -> if (slot.video) vm.clearVideo() else vm.clearSource() },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 4.dp),
            )
        }
        if (media.isNotEmpty()) {
            AssetStack(
                images = media,
                onGallery = onGallery,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 4.dp),
            )
        }
        // r29.3：生成中不再在画面中央放占位方块——底部胶囊已经有进度/排队状态，这里留空更干净。
    }
}

/** 左下角一排参考槽。现在每个输入一个槽；以后多图只往这个列表里加。 */
private fun refSlots(vm: CustomModel, images: List<JSONObject>, videos: List<JSONObject>): List<RefSlotModel> {
    val image = vm.sourceUri?.let { Uri.parse(it) } ?: vm.sourcePath?.let { File(it) }
    val frame = vm.videoThumb?.asImageBitmap()
    val videoFilled = vm.videoUri != null || vm.videoPath != null
    return buildList {
        images.forEach { spec ->
            add(RefSlotModel(Specs.key(spec), video = false, filled = image != null, image = image, frame = null))
        }
        videos.forEach { spec ->
            add(RefSlotModel(Specs.key(spec), video = true, filled = videoFilled, image = null, frame = frame))
        }
    }
}

private data class RefSlotModel(
    val key: String,
    val video: Boolean,
    val filled: Boolean,
    val image: Any?,
    val frame: androidx.compose.ui.graphics.ImageBitmap?,
)

@Composable
private fun RefSlotRow(
    slots: List<RefSlotModel>,
    onAlbum: (RefSlotModel) -> Unit,
    onGallery: (RefSlotModel) -> Unit,
    onRemove: (RefSlotModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.forEach { slot ->
            key(slot.key) {
                RefSlot(
                    slot = slot,
                    onAlbum = { onAlbum(slot) },
                    onGallery = { onGallery(slot) },
                    onRemove = { onRemove(slot) },
                )
            }
        }
    }
}

/** 空槽是同样大小的毛玻璃方框，中间一个加号。有内容时点开全屏查看。 */
@Composable
private fun RefSlot(
    slot: RefSlotModel,
    onAlbum: () -> Unit,
    onGallery: () -> Unit,
    onRemove: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val desc = stringResource(if (slot.video) R.string.card_reference_video else R.string.card_reference)
    Box {
        Box(
            Modifier
                .size(76.dp)
                .clip(shape)
                .immersiveGlass()
                .border(1.dp, ImmersiveHairline, shape)
                .clickable { if (slot.filled) viewing = true else choosing = true },
            contentAlignment = Alignment.Center,
        ) {
            when {
                slot.image != null -> AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(slot.image).size(240).build(),
                    contentDescription = desc,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(13.dp)),
                )
                slot.frame != null -> Image(
                    bitmap = slot.frame,
                    contentDescription = desc,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(13.dp)),
                )
                slot.filled -> Icon(Lucide.Video, desc, tint = PhotoOnBackdrop)
                else -> ImmersiveIcon(Lucide.Plus, desc, size = 22.dp)
            }
        }
        if (choosing) {
            RefSourcePopup(
                onAlbum = {
                    choosing = false
                    onAlbum()
                },
                onGallery = {
                    choosing = false
                    onGallery()
                },
                onDismiss = { choosing = false },
            )
        }
    }
    if (viewing) {
        RefViewer(
            slot = slot,
            onClose = { viewing = false },
            onAlbum = {
                viewing = false
                onAlbum()
            },
            onGallery = {
                viewing = false
                onGallery()
            },
            onRemove = {
                viewing = false
                onRemove()
            },
        )
    }
}

/** 相册 / 图库选在槽位上方，不盖住方框。 */
@Composable
private fun RefSourcePopup(onAlbum: () -> Unit, onGallery: () -> Unit, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    GlassPopup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, -with(density) { 96.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(112.dp)
                .glassPopupPanel(),
        ) {
            TextButton(onClick = onAlbum, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ref_pick_album))
            }
            TextButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ref_pick_gallery))
            }
        }
    }
}

/** 全屏看参考图或视频首帧。底部是替换和移除，样式对齐图库查看器。 */
@Composable
private fun RefViewer(
    slot: RefSlotModel,
    onClose: () -> Unit,
    onAlbum: () -> Unit,
    onGallery: () -> Unit,
    onRemove: () -> Unit,
) {
    var replacing by remember { mutableStateOf(false) }
    val desc = stringResource(if (slot.video) R.string.card_reference_video else R.string.card_reference)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SystemBarAppearance(dark = true)
        Box(Modifier.fillMaxSize().background(PhotoBackdrop)) {
            when {
                slot.image != null -> AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(slot.image).size(1600).build(),
                    contentDescription = desc,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                slot.frame != null -> Image(
                    bitmap = slot.frame,
                    contentDescription = desc,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Icon(
                    Lucide.Video,
                    desc,
                    tint = PhotoOnBackdrop,
                    modifier = Modifier.align(Alignment.Center).size(48.dp),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
            ) {
                Icon(Lucide.ArrowLeft, stringResource(R.string.action_back), tint = PhotoOnBackdrop)
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .glass(Color.Black.copy(alpha = 0.45f), tint = Color.Black.copy(alpha = 0.35f))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))))
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (replacing) {
                    TextButton(onClick = onAlbum) { Text(stringResource(R.string.ref_pick_album), color = PhotoOnBackdrop) }
                    TextButton(onClick = onGallery) { Text(stringResource(R.string.ref_pick_gallery), color = PhotoOnBackdrop) }
                } else {
                    TextButton(onClick = { replacing = true }) { Text(stringResource(R.string.ref_replace), color = PhotoOnBackdrop) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.ref_remove), color = PhotoOnBackdrop) }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TextResultCards(rows: List<ImageRow>) {
    if (rows.isEmpty()) return
    val app = LocalContext.current.applicationContext as KreaApp
    Spacer(Modifier.height(8.dp))
    rows.forEach { row ->
        // r27：文本结果卡换成玻璃卡，白字。
        // r29.1：文本结果不再有二级页（看图壳是纯黑，和主页脱节），整块留在预览区：
        // 标题 + 正文 + 复制/存入提示词库就够了，卡片不再可点、不再有「打开」。
        // r29.2：卡片按剩余高度分配（weight），正文区域自己滚，底部按钮永远在卡内可见。
        GlassCard(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.text_result),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ImmersiveInk,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                SelectionContainer {
                    Text(
                        row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ImmersiveInk,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextResultActions(text = row.text, app = app)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    GlassCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ImmersiveInk)
            Spacer(Modifier.weight(1f))
            Text(if (open) stringResource(R.string.collapse) else stringResource(R.string.expand), fontSize = 12.sp, color = ImmersiveInkMuted)
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
    // round20：展示模型优先 UNET/Checkpoint，别被排在前面的 CLIP 抢走
    vm.specs.filter { Specs.type(it) == "model" }.minByOrNull { Specs.modelTier(it) }?.let { spec ->
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
        // r21：摘要只写「随机/固定种子」，不拼完整数字（完整种子仍在「更多设置」抽屉里编辑）
        parts.add(if (random) context.str(R.string.seed_random) else context.str(R.string.seed_fixed))
    }
    // LoRA 开关（round16）：有工作流级的 LoRA 且至少一个关闭时显示「开启数/总数」；全开不显示
    if (vm.loraEntries.isNotEmpty()) {
        val off = vm.loraEntries.count { vm.loraOn[it.key] == false }
        if (off > 0) {
            parts.add(context.getString(R.string.lora_summary, vm.loraEntries.size - off, vm.loraEntries.size))
        }
    }
    return parts.joinToString(" · ")
}
