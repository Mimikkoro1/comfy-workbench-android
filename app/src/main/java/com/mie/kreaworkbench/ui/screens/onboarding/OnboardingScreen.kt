package com.mie.kreaworkbench.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.discovery.LanScanner
import com.mie.kreaworkbench.ui.components.ImmersiveHairline
import com.mie.kreaworkbench.ui.theme.isClassic
import com.mie.kreaworkbench.ui.theme.pageBackground
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.ImmersiveInkMuted
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.motion.pressScale

/**
 * 「快速开始」引导页（r36 初版 / r36.1 强制两步 / r36.2 返工）：我是谁 → 两步开始 → 需要帮助 → 开始生成。
 *
 * r36.2 变更（owner 拍板）：
 * - 返回手势不再直接跳出引导页：统一出口 [onStart]（NavModel.requestOnboardingExit）——
 *   没做完弹「还没完成」逃生窗（渲染在 AppNav，状态在 NavModel），做完直接收尾。
 * - 布局改内容块居中分布：去掉滚动，上下弹性空隙把简介/步骤/帮助夹在标题栏与底部按钮之间。
 * - 文案定稿：问候 + 三条简介（原生安卓应用 / 远程访问一键出图 / 数据存本地）。
 *
 * 步骤判定唯一真源在 settings（① = serverUrl 非空，② = currentWorkflow 非空，AppNav 接线传入）；
 * ①永远不锁；②只在①完成后解锁；未解锁整行置灰 + 锁形不可点。
 */
@Composable
fun OnboardingScreen(
    s1Done: Boolean,
    s2Done: Boolean,
    onConnect: () -> Unit,
    onAutoScan: () -> Unit,
    onWorkflows: () -> Unit,
    onHelp: () -> Unit,
    onStart: () -> Unit,
) {
    // r39：非 Wi-Fi/以太网时第①步「自动查找」置灰（打开页面时查一次）
    val context = LocalContext.current
    val lanOk = remember { LanScanner.available(context) }
    // r46：经典主题换实心页底，白字体系随之跟主题。
    Box(Modifier.fillMaxSize().background(if (isClassic()) pageBackground() else Color.Black.copy(alpha = 0.72f))) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            // 无返回箭头：出口只有「开始生成」（返回手势也汇入同一出口）
            ScreenHeader(stringResource(R.string.onboarding_title), ink = ImmersiveInk)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.onboarding_hi),
                color = ImmersiveInk,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            OnboardingPoint(stringResource(R.string.onboarding_point1))
            Spacer(Modifier.height(4.dp))
            OnboardingPoint(stringResource(R.string.onboarding_point2))
            Spacer(Modifier.height(4.dp))
            OnboardingPoint(stringResource(R.string.onboarding_point3))
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.onboarding_how_title),
                color = ImmersiveInkMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(12.dp))
            StepRow(
                state = if (s1Done) StepState.Done else StepState.Active,
                num = 1,
                label = stringResource(R.string.onboarding_s1_chip),
                hint = stringResource(R.string.onboarding_s1_hint),
                onClick = onConnect,
                secondary = {
                    GlassChip(stringResource(R.string.scan_action), enabled = lanOk, onClick = onAutoScan)
                },
            )
            Spacer(Modifier.height(8.dp))
            StepRow(
                state = when {
                    s2Done -> StepState.Done
                    s1Done -> StepState.Active
                    else -> StepState.Locked
                },
                num = 2,
                label = stringResource(R.string.onboarding_s2_chip),
                hint = stringResource(R.string.onboarding_s2_hint),
                onClick = onWorkflows,
            )
            Spacer(Modifier.height(16.dp))
            HelpRow(onHelp)
            Spacer(Modifier.weight(1f))
            // 终点步骤 + 逃生窗入口：外观不随进度变化、永远可点
            Button(
                onClick = onStart,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(0.62f)
                    .height(44.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = ImmersiveInk,
                ),
                border = BorderStroke(1.dp, ImmersiveHairline),
            ) {
                Text(
                    stringResource(R.string.onboarding_start),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(
                Modifier
                    .navigationBarsPadding()
                    .height(16.dp),
            )
        }
    }
}

/** 步骤行三态：Done 可点（重做）、Active 可点、Locked 整行置灰不可点。 */
private enum class StepState { Done, Active, Locked }

/** 简介一条：圆点 + 一句话。这是页面主内容，允许完整句子。 */
@Composable
private fun OnboardingPoint(text: String) {
    Row {
        Text("•", color = ImmersiveInkMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(10.dp))
        Text(text, color = ImmersiveInk, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 一条步骤：序号徽章 + 玻璃胶囊 + 右侧状态。
 * Done = 右侧打勾（不显示 hint）；Active = hint + 箭头；Locked = 锁形 + 整行 alpha 0.45、无点击。
 * r39：[secondary] 非空时在卡片内第二行右侧渲染（第①步的「自动查找」入口，与「去配置」并列）。
 */
@Composable
private fun StepRow(
    state: StepState,
    num: Int,
    label: String,
    hint: String,
    onClick: () -> Unit,
    secondary: (@Composable () -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val locked = state == StepState.Locked
    val base = Modifier
        .fillMaxWidth()
        .then(if (locked) Modifier.alpha(0.45f) else Modifier)
        .clip(shape)
        .background(Color.White.copy(alpha = 0.08f))
        .border(1.dp, ImmersiveHairline, shape)
    Column(base) {
        Row(
            if (locked) {
                Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
            } else {
                Modifier
                    .pressScale(src)
                    .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepBadge(num)
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = ImmersiveInk,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            when (state) {
                StepState.Done -> {
                    Icon(Lucide.Check, contentDescription = null, tint = ImmersiveInk, modifier = Modifier.size(18.dp))
                }
                StepState.Active -> {
                    Text(hint, color = ImmersiveInkMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = ImmersiveInkMuted, modifier = Modifier.size(18.dp))
                }
                StepState.Locked -> {
                    Icon(Lucide.Lock, contentDescription = null, tint = ImmersiveInkMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (secondary != null && !locked) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                secondary()
            }
        }
    }
}

/** r39：小玻璃 chip 按钮（样式同帮助行的「关于」chip）。enabled=false 时置灰不可点。 */
@Composable
private fun GlassChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        color = if (enabled) ImmersiveInk else ImmersiveInkMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .pressScale(src)
            .clip(shape)
            .background(if (enabled) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, ImmersiveHairline, shape)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 序号徽章：圆形描边 + 数字，自绘不引新依赖。 */
@Composable
private fun StepBadge(num: Int) {
    Box(
        Modifier
            .size(22.dp)
            .border(1.dp, ImmersiveHairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(num.toString(), color = ImmersiveInk, style = MaterialTheme.typography.labelMedium)
    }
}

/** 帮助行：需要帮助？ [关于] 仓库与反馈都在这 —— 点「关于」进关于页。 */
@Composable
private fun HelpRow(onHelp: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.onboarding_help_pre),
            color = ImmersiveInkMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.onboarding_help_chip),
            color = ImmersiveInk,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .pressScale(src)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, ImmersiveHairline, shape)
                .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onHelp)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.onboarding_help_hint),
            color = ImmersiveInkMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
