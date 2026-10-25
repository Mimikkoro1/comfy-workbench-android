package com.mie.kreaworkbench.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.data.net.urlTargetsLocalNetwork
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.data.workflows.ImportedWorkflowMeta
import com.mie.kreaworkbench.ui.components.AppTab
import com.mie.kreaworkbench.ui.components.BottomDock
import com.mie.kreaworkbench.ui.components.GlassAlertDialog
import com.mie.kreaworkbench.ui.components.GlassHostOverlay
import com.mie.kreaworkbench.ui.components.GlassHostProvider
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.LocalNetworkGlobalPrompt
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.components.UpdateDialog
import com.mie.kreaworkbench.ui.components.rememberLocalNetworkGuard
import com.mie.kreaworkbench.ui.motion.HERO_MS
import com.mie.kreaworkbench.ui.motion.HeroEasing
import com.mie.kreaworkbench.ui.motion.LocalAnimScope
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.locale.workflowLabel
import com.mie.kreaworkbench.ui.screens.gallery.GalleryScreen
import com.mie.kreaworkbench.ui.screens.custom.CustomModel
import com.mie.kreaworkbench.ui.screens.custom.CustomScreen
import com.mie.kreaworkbench.ui.screens.custom.WorkflowEmptyState
import com.mie.kreaworkbench.ui.screens.gen.ImmersiveBackdrop
import com.mie.kreaworkbench.ui.theme.LocalIsDark
import com.mie.kreaworkbench.ui.theme.isClassic
import com.mie.kreaworkbench.ui.theme.pageBackground
import com.mie.kreaworkbench.ui.screens.library.LibraryEditorScreen
import com.mie.kreaworkbench.ui.screens.library.LibraryScreen
import com.mie.kreaworkbench.ui.screens.library.Source
import com.mie.kreaworkbench.ui.screens.library.WorkflowImportScreen
import com.mie.kreaworkbench.ui.screens.models.ModelsScreen
import com.mie.kreaworkbench.ui.screens.onboarding.OnboardingScreen
import com.mie.kreaworkbench.ui.screens.settings.AboutScreen
import com.mie.kreaworkbench.ui.screens.settings.AppearancePage
import com.mie.kreaworkbench.ui.screens.settings.BackgroundPage
import com.mie.kreaworkbench.ui.screens.settings.ConnectionPage
import com.mie.kreaworkbench.ui.screens.settings.FeedbackPage
import com.mie.kreaworkbench.ui.screens.settings.SettingsHome
import com.mie.kreaworkbench.ui.screens.viewer.ViewerScreen
import android.net.Uri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private sealed interface Scene {
    val depth: Int

    data object Home : Scene {
        override val depth = 0
    }

    data object Gallery : Scene {
        override val depth = 1
    }

    data class Viewer(val ids: List<Long>, val index: Int, val origin: String) : Scene {
        override val depth = 2
    }

    data class WorkflowImport(val uris: List<Uri>) : Scene {
        override val depth = 1
    }

    /** r38 E4/E5：库内工作流的参数配置页，复用 WorkflowImportScreen。 */
    data class WorkflowCustomize(val sourceId: String, val mode: String) : Scene {
        override val depth = 1
    }

    data class LibraryEditor(val libId: String) : Scene {
        override val depth = 1
    }

    data object About : Scene {
        override val depth = 1
    }

    data object SettingsConnection : Scene {
        override val depth = 1
    }

    data object SettingsBackground : Scene {
        override val depth = 1
    }

    data object SettingsAppearance : Scene {
        override val depth = 1
    }

    data object SettingsFeedback : Scene {
        override val depth = 1
    }

    /** r36：全新安装引导页。depth = 0（与主界面平级，从它 push 设置子页走正常滑入）。 */
    data object Onboarding : Scene {
        override val depth = 0
    }
}

private fun sceneOf(stack: List<Overlay>): Scene = when (val top = stack.lastOrNull()) {
    null -> Scene.Home
    Overlay.Gallery -> Scene.Gallery
    is Overlay.Viewer -> Scene.Viewer(top.ids, top.index, top.origin)
    is Overlay.WorkflowImport -> Scene.WorkflowImport(top.uris)
    is Overlay.WorkflowCustomize -> Scene.WorkflowCustomize(top.sourceId, top.mode)
    is Overlay.LibraryEditor -> Scene.LibraryEditor(top.libId)
    Overlay.About -> Scene.About
    Overlay.SettingsConnection -> Scene.SettingsConnection
    Overlay.SettingsBackground -> Scene.SettingsBackground
    Overlay.SettingsAppearance -> Scene.SettingsAppearance
    Overlay.SettingsFeedback -> Scene.SettingsFeedback
    Overlay.Onboarding -> Scene.Onboarding
}

/** r47：该场景在经典风格下是否整页实心（生成页要看壁纸、看图页是黑底照片，都不算）。 */
private fun solidSceneFor(scene: Scene, tab: AppTab): Boolean = when (scene) {
    is Scene.Viewer -> false
    is Scene.Home -> tab != AppTab.T2I
    else -> true
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNav(nav: NavModel) {
    val holder = rememberSaveableStateHolder()
    val stack = nav.stack.toList()
    val scene = sceneOf(stack)
    val scope = rememberCoroutineScope()

    // r27.2：全 App 唯一一份背景的数据源。CustomModel 是 Activity 级 ViewModel，
    // 和 HomeLayer 里拿到的是同一个实例。
    val rootVm: CustomModel = viewModel()
    val rootMedia = rootVm.mediaResults()
    val rootNewResultId by rootVm.lastResultId.collectAsState()
    val rootWorkflow by nav.workflow.collectAsState()
    val rootAwaitingMedia = !rootVm.mediaLoaded
    // 背景在这些场景显示：生成页 / 图库 / 看图页，以及 r29 玻璃化的设置二级页与关于页。
    // 设置子页原先不挂背景（怕透出图片），但那样玻璃卡没有东西可模糊、只剩一块均匀暗底，
    // 真机看着还是素皮；现在跟设置 tab 一样透背景 + 薄纱。
    // 导入、词库编辑仍不显示，避免编辑长文本时背后有图。
    // 看图页也挂着（被它自己的黑底盖住）：进/出看图页的淡入淡出期间不会突然露出窗口白底。
    val showRootBackdrop = when (scene) {
        Scene.Home, Scene.Gallery, is Scene.Viewer,
        Scene.About, Scene.SettingsConnection, Scene.SettingsBackground, Scene.SettingsAppearance,
        Scene.SettingsFeedback,
        -> true
        else -> false
    }

    // E4: ONE seekable transition drives every overlay change. Button back / push animate it with
    // animateTo(); the predictive back gesture seeks the very same pop transition with the finger
    // (exiting page on top slides right + shrinks, previous page underneath scales/fades in), then on
    // release continues from the current fraction, or on cancel animates back to 0.
    // Previously the gesture drew a separate "preview" copy of the previous page and on release swapped
    // it out and popped with a None transition, so the destination could flash before the exit anim.
    val transitionState = remember { SeekableTransitionState<Scene>(scene) }
    val transition = rememberTransition(transitionState, label = "overlay")
    var gesture by remember { mutableStateOf(false) }
    val cancelJob = remember { arrayOfNulls<Job>(1) }

    // r41：安卓 17 局域网权限——已保存服务器指向局域网且未授权时，每次进程启动最多引导一次。
    // runEvenIfDenied = true、action 为空：只负责说明，不拦截任何已有流程。
    val lanGuard = rememberLocalNetworkGuard()
    val navApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as KreaApp
    val bootSettings by navApp.container.settings.flow.collectAsState(initial = UserSettings())
    LaunchedEffect(bootSettings.serverUrl, bootSettings.advGachaUrl, bootSettings.advGacha) {
        if (LocalNetworkGlobalPrompt.shown) return@LaunchedEffect
        val s = bootSettings
        val target = when {
            s.advGacha && s.advGachaUrl.isNotBlank() -> s.advGachaUrl
            else -> s.serverUrl
        }
        // 只在确实指向局域网时才消耗「一次」的额度；公网地址不弹也不占额度
        if (target.isNotBlank() && urlTargetsLocalNetwork(target)) {
            LocalNetworkGlobalPrompt.shown = true
            lanGuard.ensure(target, runEvenIfDenied = true) {}
        }
    }

    LaunchedEffect(scene) {
        if (transitionState.currentState != scene || transitionState.targetState != scene) {
            transitionState.animateTo(scene)
        }
        gesture = false
    }

    PredictiveBackHandler(enabled = stack.isNotEmpty()) { flow ->
        val current = scene
        val previous = sceneOf(stack.dropLast(1))
        // Fullscreen image: keep the E3 behaviour (wait for commit, then the normal hero pop) — confirmed OK.
        if (current is Scene.Viewer) {
            try {
                flow.collect { }
                nav.pop()
            } catch (_: CancellationException) {
            }
            return@PredictiveBackHandler
        }
        // r36.2：引导页不直接 pop（不能让返回绕过逃生窗）。等手势提交后走统一出口：
        // 做完两步 → 正常收尾；没做完 → 弹「还没完成」。无 seek 动画（同 Viewer 分支）。
        if (current is Scene.Onboarding) {
            try {
                flow.collect { }
                nav.requestOnboardingExit()
            } catch (_: CancellationException) {
            }
            return@PredictiveBackHandler
        }
        cancelJob[0]?.cancel()
        try {
            flow.collect { event ->
                if (!gesture) gesture = true
                transitionState.seekTo(event.progress.coerceIn(0f, 1f), targetState = previous)
            }
            // Commit: popping changes `scene` to `previous`; LaunchedEffect(scene) then animateTo()s
            // from the current seek fraction to the end. If no progress events were delivered (some
            // ROMs only send the final back), this is just the normal pop animation.
            nav.pop()
        } catch (e: CancellationException) {
            cancelJob[0] = scope.launch {
                val from = transitionState.fraction
                val totalMs = (transition.totalDurationNanos / 1_000_000L).coerceAtLeast(1L)
                val durNanos = (from * totalMs * 1_000_000L).toLong().coerceAtLeast(1L)
                val start = withFrameNanos { it }
                while (from > 0f) {
                    val now = withFrameNanos { it }
                    val p = ((now - start).toFloat() / durNanos).coerceIn(0f, 1f)
                    if (p >= 1f) break
                    transitionState.seekTo(from * (1f - FastOutSlowInEasing.transform(p)))
                }
                transitionState.snapTo(current)
                gesture = false
            }
        }
    }

    GlassHostProvider {
    Box(Modifier.fillMaxSize()) {
        // r27.2：背景只有这一份，并且画在 AnimatedContent 下面（z 更低）。
        // 进出图库、进出看图页、切 tab 都不会拆它，所以背景图不会「先消失再冲出来」。
        if (showRootBackdrop) {
            ImmersiveBackdrop(
                image = rootMedia.firstOrNull(),
                onOpen = {
                    // 只有生成页的沉浸态，点空白才进看图。图库/看图页里背景不响应点击。
                    if (scene == Scene.Home && nav.tab == AppTab.T2I && rootWorkflow.isNotBlank()) {
                        val ids = rootMedia.take(20).map { it.id }
                        if (ids.isNotEmpty()) nav.openViewer(ids, 0)
                    }
                },
                slideInKey = rootNewResultId,
                cacheKey = "root",
                awaitingMedia = rootAwaitingMedia,
            )
        }
        // r47：经典风格下，进/出二级页的过渡动画带淡入淡出，实心底跟着半透明，
        // 会把生成页壁纸透出来。两端都是「整页实心」的场景（设置/库/模型等一级页与各二级页，
        // 非生成页、非看图页）时，在壁纸上、场景内容下垫一层不透明页底。
        // 毛玻璃透壁纸是正常效果，不动；任一端是生成页（T2I tab）或看图页时也不垫。
        if (isClassic() &&
            solidSceneFor(transition.targetState, nav.tab) &&
            solidSceneFor(transition.currentState, nav.tab)
        ) {
            Box(Modifier.fillMaxSize().background(pageBackground()))
        }
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                when {
                    // E3: leaving the viewer — list/home sits UNDER the viewer and is visible at once,
                    // the viewer's black backdrop + bars fade out while the hero image shrinks into the
                    // thumbnail (shared bounds, same duration/easing). All in parallel.
                    initialState is Scene.Viewer ->
                        (EnterTransition.None togetherWith fadeOut(tween(HERO_MS, easing = HeroEasing)))
                            .apply { targetContentZIndex = -1f }
                    // Entering the viewer — keep the list in place underneath while the viewer fades in
                    // on top and the hero image grows out of the thumbnail.
                    targetState is Scene.Viewer ->
                        fadeIn(tween(HERO_MS, easing = HeroEasing)) togetherWith
                            ExitTransition.KeepUntilTransitionsFinished
                    targetState.depth > initialState.depth ->
                        slideInHorizontally(tween(380, easing = EmphasizedDecelerate)) { it } togetherWith
                            (slideOutHorizontally(tween(380)) { -it / 4 } +
                                scaleOut(tween(380), targetScale = 0.92f) +
                                fadeOut(tween(250)))
                    // E4: gesture pop — linear so the page tracks the finger 1:1 while seeking; the
                    // remainder after release is played by animateTo() with the same spec.
                    gesture ->
                        ((slideInHorizontally(tween(320, easing = LinearEasing)) { -it / 4 } +
                            scaleIn(tween(320, easing = LinearEasing), initialScale = 0.92f) +
                            fadeIn(tween(320, easing = LinearEasing), initialAlpha = 0.4f)) togetherWith
                            (slideOutHorizontally(tween(320, easing = LinearEasing)) { it } +
                                scaleOut(tween(320, easing = LinearEasing), targetScale = 0.94f)))
                            .apply { targetContentZIndex = -1f }
                    // E3: button pop — the exiting page stays ON TOP and slides out while the previous
                    // page scales/slides in underneath at the same time.
                    else ->
                        ((slideInHorizontally(tween(350, easing = EmphasizedDecelerate)) { -it / 4 } +
                            scaleIn(tween(350, easing = EmphasizedDecelerate), initialScale = 0.92f) +
                            fadeIn(tween(200))) togetherWith
                            slideOutHorizontally(tween(350, easing = EmphasizedDecelerate)) { it })
                            .apply { targetContentZIndex = -1f }
                }
            },
        ) { target ->
            CompositionLocalProvider(LocalAnimScope provides this) {
                SceneBody(target, nav, holder)
            }
        }
        GlassHostOverlay()
    }

    // 「用于图生图」有多个带参考图工作流时弹列表选择（决策 5）
    nav.i2iChooser?.let { targets ->
        GlassAlertDialog(
            onDismissRequest = { nav.dismissI2iChooser() },
            title = { Text(stringResource(R.string.i2i_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.i2i_body),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    targets.forEach { t ->
                        TextButton(onClick = { nav.chooseForI2i(t.id) }) {
                            Text(workflowLabel(t), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { nav.dismissI2iChooser() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // r36.1：引导页②步挑工作流弹窗（照 i2iChooser 模式）。列表可能比 i2i 多，包一层滚动；
    // 空列表（播种未完）只显示提示 + 取消，不出空弹窗。
    nav.wfChooser?.let { targets ->
        GlassAlertDialog(
            onDismissRequest = { nav.dismissWfChooser() },
            title = { Text(stringResource(R.string.onboarding_wf_title)) },
            text = {
                if (targets.isEmpty()) {
                    Text(
                        stringResource(R.string.onboarding_wf_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        targets.forEach { t ->
                            TextButton(onClick = { nav.chooseWf(t.id) }) {
                                Text(workflowLabel(t), color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { nav.dismissWfChooser() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // r36.2：引导页逃生窗（返回手势与「开始生成」共用，状态在 NavModel）。
    // 「继续配置」留下；「仍要进入」置位 onboarded 收引导，此后不再自动弹（设置可复看）。
    if (nav.showOnboardingSkip) {
        GlassAlertDialog(
            onDismissRequest = { nav.dismissOnboardingSkip() },
            title = { Text(stringResource(R.string.onboarding_skip_title)) },
            text = {
                Text(
                    stringResource(R.string.onboarding_skip_body),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { nav.confirmOnboardingSkip() }) {
                    Text(stringResource(R.string.onboarding_skip_go))
                }
            },
            dismissButton = {
                TextButton(onClick = { nav.dismissOnboardingSkip() }) {
                    Text(stringResource(R.string.onboarding_skip_stay))
                }
            },
        )
    }

    // r29：新版本对话框改成玻璃弹层，必须挂在 GlassHostProvider 里才画得出来
    // （以前在 MainActivity 用系统 AlertDialog，现在跟着玻璃宿主走）。
    UpdateDialog()

    // r41：局域网权限的说明弹窗与被拒弹窗（全局检查用；ConnectionPage 另有自己的实例）
    lanGuard.Dialogs()
    }
}
@Composable
private fun SceneBody(scene: Scene, nav: NavModel, holder: SaveableStateHolder) {
    when (scene) {
        Scene.Home -> HomeLayer(nav, holder)
        Scene.Gallery -> holder.SaveableStateProvider("gallery") {
            GalleryScreen(
                onBack = { nav.pop() },
                onOpen = { ids, index -> nav.openViewer(ids, index, "gal") },
            )
        }
        is Scene.Viewer -> ViewerScreen(
            heroPrefix = scene.origin,
            ids = scene.ids,
            start = scene.index,
            onBack = { nav.pop() },
            onUseForI2i = { nav.useForI2i(it) },
        )
        is Scene.WorkflowImport -> WorkflowImportScreen(
            uris = scene.uris,
            onBack = { nav.pop() },
        )
        is Scene.WorkflowCustomize -> WorkflowImportScreen(
            source = Source.Stored(scene.sourceId, scene.mode),
            onBack = { nav.pop() },
        )
        is Scene.LibraryEditor -> LibraryEditorScreen(
            libId = scene.libId,
            onBack = { nav.pop() },
        )
        Scene.About -> AboutScreen(
            onBack = { nav.pop() },
            onQuickStart = { nav.openOnboarding() },
        )
        Scene.SettingsConnection -> ConnectionPage(
            onBack = { nav.pop() },
            autoScan = nav.pendingAutoScan,
            onAutoScanConsumed = { nav.pendingAutoScan = null },
        )
        Scene.SettingsBackground -> BackgroundPage(onBack = { nav.pop() })
        Scene.SettingsAppearance -> AppearancePage(onBack = { nav.pop() })
        Scene.SettingsFeedback -> FeedbackPage(onBack = { nav.pop() })
        Scene.Onboarding -> {
            // r36.1：步骤完成判定从 settings 读，①=地址已配、②=工作流已选
            val serverReady by nav.serverReady.collectAsState()
            val wf by nav.workflow.collectAsState()
            OnboardingScreen(
                s1Done = serverReady,
                s2Done = wf.isNotBlank(),
                onConnect = { nav.push(Overlay.SettingsConnection) },
                onAutoScan = {
                    nav.pendingAutoScan = com.mie.kreaworkbench.data.discovery.LanHit.Kind.COMFY
                    nav.push(Overlay.SettingsConnection)
                },
                onWorkflows = { nav.openWfChooser() },
                onHelp = { nav.push(Overlay.About) },
                onStart = { nav.requestOnboardingExit() },
            )
        }
    }
}

@Composable
private fun HomeLayer(nav: NavModel, holder: SaveableStateHolder) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val wf by nav.workflow.collectAsState()
    val immersive = nav.tab == AppTab.T2I && wf.isNotBlank()
    CompositionLocalProvider(LocalExtraBottom provides 88.dp + navBottom) {
        Box(Modifier.fillMaxSize()) {
            // r27.2：共享背景已提到 AppNav 根，这里只留非沉浸页的遮罩和内容。
            // r28：工作流/库、模型两页改成玻璃卡，遮罩要薄一点，否则背景被糊死、玻璃看不出来。
            // r29：设置页也玻璃化了，跟着走同一套薄纱；生成空态仍用厚遮罩。
            if (!immersive) {
                if (isClassic()) {
                    // 经典主题：不铺薄纱，直接实心页底（保留吃点击，防止点穿到背景图）。
                    ClassicPageVeil()
                } else {
                    ReadabilityScrim(
                        thin = nav.tab == AppTab.WORKFLOWS || nav.tab == AppTab.MODELS || nav.tab == AppTab.SETTINGS,
                    )
                }
            }
            TabPager(nav, holder)
            BottomDock(nav.tab, { nav.tab = it }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** 非生成页压一层遮罩，卡片和文字才看得清。点空白不进看图。
 *  厚遮罩（默认）：生成空态用，保持原来的样子。
 *  薄遮罩（[thin]）：工作流/库、模型、设置页用——这几页的卡片/列表是玻璃的，遮罩太厚背景透不出来，
 *  玻璃等于白做。薄遮罩两个主题都用深色底：页内文字是白字体系，浅色主题若用白遮罩，
 *  白字会直接糊在浅色背景上。α 数值 r28 已定，别再动。 */
@Composable
private fun ReadabilityScrim(thin: Boolean = false) {
    val dark = LocalIsDark.current
    val veil = when {
        !thin -> if (dark) Color.Black.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.85f)
        dark -> Color.Black.copy(alpha = 0.48f)
        else -> Color.Black.copy(alpha = 0.52f)
    }
    val eat = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(veil)
            .clickable(interactionSource = eat, indication = null, onClick = {}),
    )
}

/** 经典主题里顶替 ReadabilityScrim 的实心页底，同样吃掉空白处的点击。 */
@Composable
private fun ClassicPageVeil() {
    val eat = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(pageBackground())
            .clickable(interactionSource = eat, indication = null, onClick = {}),
    )
}

@Composable
private fun TabPager(nav: NavModel, holder: SaveableStateHolder) {
    // transitionSpec 不是 composable，先在外层取好。
    val classic = isClassic()
    AnimatedContent(
        targetState = nav.tab,
        transitionSpec = {
            // 经典主题：低端机档，tab 切换只留淡入淡出；毛玻璃保留原来的轻缩放。
            if (classic) {
                fadeIn(tween(210, delayMillis = 90)).togetherWith(fadeOut(tween(90)))
            } else {
                (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.96f))
                    .togetherWith(fadeOut(tween(90)))
            }
        },
        label = "tabs",
    ) { tab ->
        holder.SaveableStateProvider(tab.name) {
            when (tab) {
                AppTab.T2I -> {
                    // 「生成」tab 按当前工作流渲染：无工作流 → 空状态；非空 → 导入工作流的表单页
                    val wf by nav.workflow.collectAsState()
                    val serverReady by nav.serverReady.collectAsState()
                    val builtins by nav.builtins.collectAsState()
                    if (wf.isBlank()) {
                        GenEmptyTab(
                            serverReady = serverReady,
                            builtins = builtins,
                            onConfigureServer = { nav.openOnboarding() },
                            onPickWorkflow = { id -> nav.pickWorkflow(id) },
                            onImport = { nav.tab = AppTab.WORKFLOWS },
                        )
                    } else {
                        CustomScreen(
                            workflowId = wf,
                            pendingPath = nav.pendingI2iPath,
                            pendingWorkflowId = nav.pendingI2iWorkflowId,
                            onConsumePending = {
                                nav.pendingI2iPath = null
                                nav.pendingI2iWorkflowId = null
                            },
                            onGallery = { nav.push(Overlay.Gallery) },
                            onOpenGachaSettings = {
                                // r40：抽卡空状态「去设置」→ 连接设置页并自动开抽卡扫描弹窗
                                nav.pendingAutoScan = com.mie.kreaworkbench.data.discovery.LanHit.Kind.GACHA
                                nav.push(Overlay.SettingsConnection)
                            },
                        )
                    }
                }
                AppTab.WORKFLOWS -> LibraryScreen()
                AppTab.MODELS -> ModelsScreen()
                AppTab.SETTINGS -> SettingsHome(onAbout = { nav.push(Overlay.About) })
            }
        }
    }
}

/** 生成 tab 的空状态（无可用工作流时）：按配置进度引导。
 *  r27：空态也走沉浸风——铺一层深色，白字 + 玻璃按钮；浅色主题下 ReadabilityScrim 是浅色的，
 *  不铺这层就会白底白字。
 *  r38 E6：没配服务器 → 引导去配置；配好了 → 直接列内置模板点选。 */
@Composable
private fun GenEmptyTab(
    serverReady: Boolean,
    builtins: List<ImportedWorkflowMeta>,
    onConfigureServer: () -> Unit,
    onPickWorkflow: (String) -> Unit,
    onImport: () -> Unit,
) {
    // r46：经典主题换实心页底，空态文案/按钮走主题分支（immersive=false 那套素皮原样）。
    Box(Modifier.fillMaxSize().background(if (isClassic()) pageBackground() else Color.Black.copy(alpha = 0.72f))) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.tab_generate), ink = ImmersiveInk)
            Column(
                Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                WorkflowEmptyState(
                    serverReady = serverReady,
                    builtins = builtins,
                    onConfigureServer = onConfigureServer,
                    onPickWorkflow = onPickWorkflow,
                    onGoImport = onImport,
                    immersive = !isClassic(),
                )
                Spacer(Modifier.weight(1.2f))
            }
        }
    }
}
