@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mie.kreaworkbench.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.BorderStroke
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mie.kreaworkbench.ui.motion.HeroEasing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** 弹窗面板的着色底。真模糊画在这层上面；采不到背景时用户看到的就是这块。 */
val GlassPanelFallback = Color.Black.copy(alpha = 0.86f)
val GlassPanelTint = Color.Black.copy(alpha = 0.55f)

/** 深底上的危险操作。和生成页失败状态用的是同一个浅红。 */
val GlassDanger = Color(0xFFFF8A80)

private val LocalGlassHost = staticCompositionLocalOf<GlassHostController?> { null }
private val LocalGlassHostRendering = staticCompositionLocalOf { false }
private val LocalGlassIsTop = staticCompositionLocalOf { true }
private val LocalGlassEntry = staticCompositionLocalOf<GlassEntry?> { null }

/** 面板进度：0 = 屏幕外/全透明，1 = 完全展开。sheet 映射成位移，dialog 映射成透明度。 */
private val LocalGlassPanelProgress = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(1f) }

/** 正在播关闭动画（或调用方已经拆掉面板）：此时不接受新的拖动，也不再触发第二次关闭。 */
private val LocalGlassLocked = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** 开合时长和现有遮罩（180 / 140）同量级，不另起一套味道。 */
private const val GLASS_OPEN_MS = 260
private const val GLASS_CLOSE_MS = 200
private const val GLASS_SETTLE_MS = 180

/** 离场加速：起步跟手、末尾利落，别拖泥带水。 */
private val GlassExitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** 遮罩、返回键、下滑、弹层里的「完成」共用。先锁住输入法，再关。 */
val LocalGlassDismiss = staticCompositionLocalOf<() -> Unit> { {} }

internal class GlassEntry(
    val id: Long,
    var dismissOnBack: Boolean,
    var dismissOnClickOutside: Boolean,
    var onDismiss: () -> Unit,
    var content: @Composable () -> Unit,
) {
    /**
     * 调用方直接把 open 置 false（绕过 [LocalGlassDismiss]）时置真：Host 先别拆，
     * 让面板把关闭动画播完再摘条目。否则这类入口仍是硬切。
     */
    var exiting by mutableStateOf(false)
        private set

    fun requestExit() {
        if (!exiting) exiting = true
    }
}

internal class GlassHostController {
    val entries = androidx.compose.runtime.mutableStateListOf<GlassEntry>()
    var tick by mutableIntStateOf(0)
    private var nextId = 1L
    fun newId(): Long = nextId++
}

@Composable
internal fun rememberGlassHost(): GlassHostController = remember { GlassHostController() }

/** 包住导航和弹层。Overlay 要放在页面之后，弹层才盖得住底栏。 */
@Composable
fun GlassHostProvider(content: @Composable () -> Unit) {
    val host = rememberGlassHost()
    CompositionLocalProvider(LocalGlassHost provides host, content = content)
}

/**
 * 画在页面上面、仍在同一个窗口里。必须放在 [LocalGlassHost] 提供之后、
 * 并且盖住底栏，弹层才能模糊到共享背景，也能盖住 tab。
 */
@Composable
fun GlassHostOverlay() {
    val host = LocalGlassHost.current ?: return
    val revision = host.tick
    if (revision < 0) return
    CompositionLocalProvider(LocalGlassHostRendering provides true) {
        val list = host.entries.toList()
        list.forEachIndexed { index, entry ->
            androidx.compose.runtime.key(entry.id) {
                CompositionLocalProvider(
                    LocalGlassIsTop provides (index == list.lastIndex),
                    LocalGlassEntry provides entry,
                ) {
                    entry.content()
                }
            }
        }
    }
}

@Composable
private fun GlassAttach(
    dismissOnBack: Boolean,
    dismissOnClickOutside: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val host = LocalGlassHost.current
    if (host == null) {
        CompositionLocalProvider(LocalGlassIsTop provides true) { content() }
        return
    }
    val entry = remember {
        GlassEntry(host.newId(), dismissOnBack, dismissOnClickOutside, onDismiss, content)
    }
    entry.dismissOnBack = dismissOnBack
    entry.dismissOnClickOutside = dismissOnClickOutside
    entry.onDismiss = onDismiss
    entry.content = content
    DisposableEffect(entry) {
        host.entries.add(entry)
        onDispose {
            // 调用方把弹层从树上拿掉了（open = false）：先让它播完关闭动画，别硬切。
            // 走 LocalGlassDismiss 的入口此时进度已经是 0，动画瞬间收尾，不会再闪一下。
            if (host.entries.contains(entry)) entry.requestExit()
        }
    }
    // 调用方重组时把新的捕获值送进 Host。Host 自己画内容时不再加，避免来回触发。
    if (!LocalGlassHostRendering.current) {
        SideEffect { host.tick++ }
    }
}

@Composable
private fun GlassChrome(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = glassColorScheme()) {
        CompositionLocalProvider(
            LocalContentColor provides ImmersiveInk,
            LocalGlassChrome provides true,
        ) {
            content()
        }
    }
}

@Composable
private fun Modifier.glassPanel(shape: Shape): Modifier =
    clip(shape)
        .background(GlassPanelFallback)
        .glass(GlassPanelFallback, 24.dp, GlassPanelTint)
        .border(1.dp, ImmersiveHairline, shape)

@Composable
private fun GlassLayer(
    onDismissRequest: () -> Unit,
    dismissOnBack: Boolean,
    dismissOnClickOutside: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val top = LocalGlassIsTop.current
    val entry = LocalGlassEntry.current
    val host = LocalGlassHost.current
    var closing by remember { mutableStateOf(false) }
    var sealFocus by remember { mutableStateOf(false) }
    val savedImeMode = remember { intArrayOf(Int.MIN_VALUE) }
    val scrim = remember { Animatable(0f) }
    // 面板进度：0 = 屏外/全透明，1 = 完全展开。sheet 拿它算位移，dialog 拿它算透明度。
    val panel = remember { Animatable(0f) }
    val panelState = remember { derivedStateOf { panel.value } }
    val exiting = entry?.exiting == true
    // 协程里读最新值：关闭动画跑着的时候，调用方可能自己先把 open 置了 false。
    val exitingNow = rememberUpdatedState(exiting)
    val lockedState = remember { derivedStateOf { closing || (entry?.exiting == true) } }
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeShown = WindowInsets.isImeVisible
    val eat = remember { MutableInteractionSource() }
    LaunchedEffect(Unit) {
        // 遮罩和面板并行。面板稍慢一点、自带减速，看起来是「从底下顶上来」而不是整体闪一下。
        launch { scrim.animateTo(1f, tween(180)) }
        launch { panel.animateTo(1f, tween(GLASS_OPEN_MS, easing = HeroEasing)) }
    }
    // closing：弹层内部自己的关闭链；exiting：调用方直接 open = false 的兜底。
    LaunchedEffect(closing || exiting) {
        if (!closing && !exiting) return@LaunchedEffect
        if (closing) {
            // 系统返回只收键盘，输入框焦点还在。这里若再 clearFocus / requestFocus，
            // 会把输入法重新绑上，弹层先被 imePadding 顶起来。先禁止弹出，再收起。
            if (imeShown || view.imeVisible()) {
                keyboard?.hide()
                withTimeoutOrNull(400) {
                    while (view.imeVisible()) delay(16)
                }
            }
            focus.clearFocus(force = true)
            if (view.imeVisible()) keyboard?.hide()
        } else {
            // 调用方已经把面板从树上拆了：键盘随内容一起走，这里只清一次，不等它。
            keyboard?.hide()
            focus.clearFocus(force = true)
        }
        coroutineScope {
            launch { panel.animateTo(0f, tween(GLASS_CLOSE_MS, easing = GlassExitEasing)) }
            launch { scrim.animateTo(0f, tween(140)) }
        }
        if (!exitingNow.value) onDismissRequest()
        // 调用方可能已经先拆了；统一由这里摘条目，避免退出窗口里反复触发。
        if (entry != null) host?.entries?.remove(entry)
    }
    DisposableEffect(view) {
        onDispose { restoreImeMode(view, savedImeMode[0]) }
    }
    if (top && dismissOnBack) {
        BackHandler {
            if (!closing && !exiting) {
                beginGlassDismiss(view, savedImeMode, { sealFocus = true }, { closing = true })
            }
        }
    }
    val dismiss = {
        if (!closing && !exiting) {
            beginGlassDismiss(view, savedImeMode, { sealFocus = true }, { closing = true })
        }
    }
    Box(Modifier.fillMaxSize().semantics { dialog() }) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f * scrim.value))
                .then(
                    if (dismissOnClickOutside) {
                        Modifier.clickable(interactionSource = eat, indication = null, onClick = dismiss)
                    } else {
                        Modifier
                    },
                ),
        )
        CompositionLocalProvider(
            LocalGlassDismiss provides dismiss,
            LocalGlassPanelProgress provides panelState,
            LocalGlassLocked provides lockedState,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = !sealFocus }
                    .pointerInput(exiting) {
                        if (!exiting) return@pointerInput
                        // 调用方已经拆了、面板正在滑出：这段时间的触摸全吃掉，
                        // 免得用户再点一次刚点过的按钮（旧代码这里是瞬间消失，没这个窗口）。
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
                content = content,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun View.imeVisible(): Boolean {
    val insets = ViewCompat.getRootWindowInsets(this) ?: return false
    return insets.isVisible(WindowInsetsCompat.Type.ime())
}

/** 关掉弹层期间不许再把输入法弹出来。保存原来的 mode，下一帧再还回去。 */
private fun beginGlassDismiss(
    view: View,
    savedMode: IntArray,
    seal: () -> Unit,
    close: () -> Unit,
) {
    if (savedMode[0] == Int.MIN_VALUE) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            savedMode[0] = window.attributes.softInputMode
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            )
        }
    }
    seal()
    close()
}

private fun restoreImeMode(view: View, savedMode: Int) {
    if (savedMode == Int.MIN_VALUE) return
    val window = view.context.findActivity()?.window ?: return
    view.post { view.post { window.setSoftInputMode(savedMode) } }
}

/** 立刻拆掉弹层（去选图）时也先挡住输入法，避免关的过程里又弹出键盘。 */
fun suppressIme(view: View, block: () -> Unit) {
    val window = view.context.findActivity()?.window
    val mode = window?.attributes?.softInputMode
    if (window != null) {
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
    }
    try {
        block()
    } finally {
        if (window != null && mode != null) view.post { view.post { window.setSoftInputMode(mode) } }
    }
}

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    GlassAttach(
        dismissOnBack = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        onDismiss = onDismissRequest,
    ) {
        GlassLayer(
            onDismissRequest = onDismissRequest,
            dismissOnBack = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
        ) {
            val eat = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(24.dp)
            val panel = LocalGlassPanelProgress.current
            Box(
                Modifier.fillMaxSize().systemBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                GlassChrome {
                    Column(
                        modifier
                            .padding(horizontal = 24.dp)
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            // 面板淡入 + 极轻的 0.96→1，位移只在绘制阶段读，不逐帧重组。
                            .graphicsLayer {
                                val p = panel.value
                                alpha = p
                                val s = 0.96f + 0.04f * p
                                scaleX = s
                                scaleY = s
                            }
                            .clickable(interactionSource = eat, indication = null, onClick = {})
                            .glassPanel(shape)
                            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
                    ) {
                        if (icon != null) {
                            Box(
                                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) { icon() }
                        }
                        if (title != null) {
                            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { title() }
                        }
                        if (text != null) {
                            CompositionLocalProvider(LocalContentColor provides ImmersiveInkMuted) {
                                Box(Modifier.fillMaxWidth()) { text() }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    GlassAttach(
        dismissOnBack = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        onDismiss = onDismissRequest,
    ) {
        GlassLayer(
            onDismissRequest = onDismissRequest,
            dismissOnBack = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
        ) {
            val panel = LocalGlassPanelProgress.current
            val padded = if (properties.decorFitsSystemWindows) {
                Modifier.fillMaxSize().systemBarsPadding()
            } else {
                Modifier.fillMaxSize()
            }
            // 全屏（例如提示词整页编辑）跟着面板进度整体淡入淡出。
            Box(if (properties.usePlatformDefaultWidth) padded else padded.graphicsLayer { alpha = panel.value }) {
                if (properties.usePlatformDefaultWidth) {
                    val eat = remember { MutableInteractionSource() }
                    val shape = RoundedCornerShape(24.dp)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GlassChrome {
                            Box(
                                Modifier
                                    .padding(horizontal = 24.dp)
                                    .widthIn(max = 560.dp)
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        val p = panel.value
                                        alpha = p
                                        val s = 0.96f + 0.04f * p
                                        scaleX = s
                                        scaleY = s
                                    }
                                    .clickable(interactionSource = eat, indication = null, onClick = {})
                                    .glassPanel(shape),
                            ) { content() }
                        }
                    }
                } else {
                    GlassChrome { content() }
                }
            }
        }
    }
}

@Composable
fun GlassDragHandle() {
    Box(
        Modifier
            .padding(vertical = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
    )
}

/**
 * 同窗口底部弹层。下滑只认把手，内容区保持调用方自己的滚动。
 * [sheetState] 留在签名里，方便以后换回 Material 弹层时调用处不用改。
 */
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    dragHandle: @Composable (() -> Unit)? = { GlassDragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    // 窗口内弹层不跟 Material 锚点走，参数留着让调用处和 ModalBottomSheet 对齐。
    if (false) sheetState.targetValue
    GlassAttach(
        dismissOnBack = true,
        dismissOnClickOutside = true,
        onDismiss = onDismissRequest,
    ) {
        GlassLayer(
            onDismissRequest = onDismissRequest,
            dismissOnBack = true,
            dismissOnClickOutside = true,
        ) {
            GlassSheetPanel(modifier, dragHandle, content)
        }
    }
}

@Composable
private fun GlassSheetPanel(
    modifier: Modifier,
    dragHandle: @Composable (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss = LocalGlassDismiss.current
    val panel = LocalGlassPanelProgress.current
    val locked = LocalGlassLocked.current
    val dismissNow = rememberUpdatedState(dismiss)
    // 跟手偏移单独一条：关闭动画用的是 panel，不碰它，所以能「从当前位置继续滑出去」。
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 首帧还量不到面板高度，先用整屏高度把它挪到屏外，避免在终点闪一帧。
    var sheetHeight by remember { mutableIntStateOf(0) }
    val eat = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    GlassChrome {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val maxSheet = (maxHeight - status - 8.dp).coerceAtLeast(160.dp)
            val fallbackPx = constraints.maxHeight.toFloat()
            Column(
                modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxSheet)
                    .onSizeChanged { sheetHeight = it.height }
                    .offset {
                        val h = if (sheetHeight > 0) sheetHeight.toFloat() else fallbackPx
                        val y = (1f - panel.value) * h + drag.value
                        IntOffset(0, y.roundToInt().coerceAtLeast(0))
                    }
                    .clickable(interactionSource = eat, indication = null, onClick = {})
                    .glassPanel(shape)
                    .navigationBarsPadding(),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            val threshold = 96.dp.toPx()
                            detectVerticalDragGestures(
                                onDragCancel = {
                                    scope.launch {
                                        drag.animateTo(0f, tween(GLASS_SETTLE_MS, easing = FastOutSlowInEasing))
                                    }
                                },
                                onDragEnd = {
                                    if (locked.value) {
                                        // 已经在滑出去了，别再触发一次关闭。
                                    } else if (drag.value >= threshold) {
                                        dismissNow.value()
                                    } else {
                                        // 没过阈值：短促回弹，不是硬跳回 0。
                                        scope.launch {
                                            drag.animateTo(0f, tween(GLASS_SETTLE_MS, easing = FastOutSlowInEasing))
                                        }
                                    }
                                },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    // 打开动画没走完 / 正在关的时候不跟手，免得和进度动画打架。
                                    if (panel.value >= 0.999f && !locked.value) {
                                        scope.launch {
                                            drag.snapTo((drag.value + amount).coerceAtLeast(0f))
                                        }
                                    }
                                },
                            )
                        }
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (dragHandle != null) dragHandle() else GlassDragHandle()
                }
                content()
            }
        }
    }
}

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        properties = properties,
        shape = RoundedCornerShape(24.dp),
        containerColor = GlassPanelFallback,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, ImmersiveHairline),
    ) {
        val menu = this
        MaterialTheme(colorScheme = glassColorScheme()) {
            CompositionLocalProvider(
                LocalContentColor provides ImmersiveInk,
                LocalGlassChrome provides true,
            ) {
                menu.content()
            }
        }
    }
}

@Composable
fun GlassMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = ImmersiveInk,
            leadingIconColor = ImmersiveInk,
            trailingIconColor = ImmersiveInkMuted,
            disabledTextColor = ImmersiveInkMuted,
        ),
    )
}

/** 小弹出层继续用独立窗口。位置由调用方给，这里只铺着色玻璃，不走真模糊。 */
@Composable
fun GlassPopup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable () -> Unit,
) {
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        GlassChrome { content() }
    }
}

fun Modifier.glassPopupPanel(): Modifier {
    val shape = RoundedCornerShape(24.dp)
    return clip(shape)
        .background(GlassPanelFallback)
        .border(1.dp, ImmersiveHairline, shape)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun GlassAlertPreview() {
    MaterialTheme {
        GlassAlertDialog(
            onDismissRequest = {},
            confirmButton = { TextButton(onClick = {}) { Text("确定") } },
            dismissButton = { TextButton(onClick = {}) { Text("取消") } },
            title = { Text("标题") },
            text = { Text("正文") },
        )
    }
}
