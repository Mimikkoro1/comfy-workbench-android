package com.mie.kreaworkbench.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mie.kreaworkbench.BuildConfig
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.components.CardGroup
import com.mie.kreaworkbench.ui.components.GroupedItem
import com.mie.kreaworkbench.ui.components.LocalToaster
import com.mie.kreaworkbench.ui.components.notify

/**
 * r43：设置首页「反馈与建议」二级页。整组逻辑自关于页原样搬来（GitHub / Gitee / 酷安 /
 * 复制诊断信息）；酷安行在 [ABOUT_COOLAPK_URL] 为空时不显示，count 同步少 1。
 * vm 与设置页共用同一 Activity 级实例，诊断信息直接取当前状态。
 */
@Composable
fun FeedbackPage(onBack: () -> Unit, vm: AppSettingsModel = viewModel()) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    SettingsPageScaffold(title = stringResource(R.string.settings_feedback), onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            // 酷安行在常量为空时不显示，count 同步少 1（圆角靠 index/count 正确闭合）。
            val coolapk = aboutOpenUrl(ABOUT_COOLAPK_URL)
            val feedbackCount = 3 + if (coolapk != null) 1 else 0
            CardGroup {
                GroupedItem(
                    index = 0,
                    count = feedbackCount,
                    onClick = {
                        runCatching {
                            val body = feedbackIssueBody(
                                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                                androidVer = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                                hint = context.getString(R.string.feedback_issue_hint),
                            )
                            val url = buildGithubIssueUrl(
                                ABOUT_GITHUB_URL,
                                context.getString(R.string.feedback_issue_title),
                                body,
                            )
                            aboutOpenUrl(url)?.let {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                            }
                        }
                    },
                    headline = { Text(stringResource(R.string.about_feedback_github)) },
                )
                GroupedItem(
                    index = 1,
                    count = feedbackCount,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ABOUT_GITEE_ISSUES_NEW_URL)))
                        }
                    },
                    headline = { Text(stringResource(R.string.about_feedback_gitee)) },
                )
                if (coolapk != null) {
                    GroupedItem(
                        index = 2,
                        count = feedbackCount,
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(coolapk))) }
                        },
                        headline = { Text(stringResource(R.string.about_feedback_coolapk)) },
                    )
                }
                GroupedItem(
                    index = feedbackCount - 1,
                    count = feedbackCount,
                    onClick = {
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("diagnostics", vm.buildFeedbackDiagnostics()))
                        }
                        notify(toaster, context, context.getString(R.string.feedback_copied))
                    },
                    headline = { Text(stringResource(R.string.about_copy_diagnostics)) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
