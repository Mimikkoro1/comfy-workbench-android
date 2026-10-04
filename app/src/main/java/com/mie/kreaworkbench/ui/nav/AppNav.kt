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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.components.AppTab
import com.mie.kreaworkbench.ui.components.BottomDock
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.components.hazeSourceIfEnabled
import com.mie.kreaworkbench.ui.motion.HERO_MS
import com.mie.kreaworkbench.ui.motion.HeroEasing
import com.mie.kreaworkbench.ui.motion.LocalAnimScope
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.screens.gallery.GalleryScreen
import com.mie.kreaworkbench.ui.screens.custom.CustomScreen
import com.mie.kreaworkbench.ui.screens.custom.WorkflowEmptyState
import com.mie.kreaworkbench.ui.screens.library.LibraryEditorScreen
import com.mie.kreaworkbench.ui.screens.library.LibraryScreen
import com.mie.kreaworkbench.ui.screens.library.WorkflowImportScreen
import com.mie.kreaworkbench.ui.screens.models.ModelsScreen
import com.mie.kreaworkbench.ui.screens.settings.AboutScreen
import com.mie.kreaworkbench.ui.screens.settings.AppearancePage
import com.mie.kreaworkbench.ui.screens.settings.BackgroundPage
import com.mie.kreaworkbench.ui.screens.settings.ConnectionPage
import com.mie.kreaworkbench.ui.screens.settings.SettingsHome
import com.mie.kreaworkbench.ui.screens.viewer.ViewerScreen
import android.net.Uri
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
}

private fun sceneOf(stack: List<Overlay>): Scene = when (val top = stack.lastOrNull()) {
    null -> Scene.Home
    Overlay.Gallery -> Scene.Gallery
    is Overlay.Viewer -> Scene.Viewer(top.ids, top.index, top.origin)
    is Overlay.WorkflowImport -> Scene.WorkflowImport(top.uris)
    is Overlay.LibraryEditor -> Scene.LibraryEditor(top.libId)
    Overlay.About -> Scene.About
    Overlay.SettingsConnection -> Scene.SettingsConnection
    Overlay.SettingsBackground -> Scene.SettingsBackground
    Overlay.SettingsAppearance -> Scene.SettingsAppearance
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNav(nav: NavModel) {
    val holder = rememberSaveableStateHolder()
    val stack = nav.stack.toList()
    val scene = sceneOf(stack)
    val scope = rememberCoroutineScope()

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

    Box(Modifier.fillMaxSize()) {
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
    }

    // 「用于图生图」有多个带参考图工作流时弹列表选择（决策 5）
    nav.i2iChooser?.let { targets ->
        AlertDialog(
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
                            Text(t.displayName, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { nav.dismissI2iChooser() }) { Text(stringResource(R.string.action_cancel)) } },
        )
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
        is Scene.LibraryEditor -> LibraryEditorScreen(
            libId = scene.libId,
            onBack = { nav.pop() },
        )
        Scene.About -> AboutScreen(onBack = { nav.pop() })
        Scene.SettingsConnection -> ConnectionPage(onBack = { nav.pop() })
        Scene.SettingsBackground -> BackgroundPage(onBack = { nav.pop() })
        Scene.SettingsAppearance -> AppearancePage(onBack = { nav.pop() })
    }
}

@Composable
private fun HomeLayer(nav: NavModel, holder: SaveableStateHolder) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    CompositionLocalProvider(LocalExtraBottom provides 88.dp + navBottom) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().hazeSourceIfEnabled()) {
                TabPager(nav, holder)
            }
            BottomDock(nav.tab, { nav.tab = it }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun TabPager(nav: NavModel, holder: SaveableStateHolder) {
    AnimatedContent(
        targetState = nav.tab,
        transitionSpec = {
            (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.96f))
                .togetherWith(fadeOut(tween(90)))
        },
        label = "tabs",
    ) { tab ->
        holder.SaveableStateProvider(tab.name) {
            when (tab) {
                AppTab.T2I -> {
                    // 「生成」tab 按当前工作流渲染：无工作流 → 空状态；非空 → 导入工作流的表单页
                    val wf by nav.workflow.collectAsState()
                    if (wf.isBlank()) {
                        GenEmptyTab(onImport = { nav.tab = AppTab.WORKFLOWS })
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
                            onOpen = { ids, index -> nav.openViewer(ids, index) },
                            onWorkflows = { nav.tab = AppTab.WORKFLOWS },
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

/** 生成 tab 的空状态（无可用工作流时）：文案 + 「去导入工作流」。 */
@Composable
private fun GenEmptyTab(onImport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.tab_generate))
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            WorkflowEmptyState(onGoImport = onImport)
            Spacer(Modifier.weight(1.2f))
        }
    }
}
