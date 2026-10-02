package com.mie.kreaworkbench.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.viewinterop.AndroidView
import com.mie.kreaworkbench.BuildConfig
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.components.CardGroup
import com.mie.kreaworkbench.ui.components.GroupedItem
import com.mie.kreaworkbench.ui.components.LargeBarScaffold

/**
 * 关于页链接。留空表示尚未发布：行内显示「待发布」，点击不跳转。
 * 填上 http 或 https 地址后，显示该地址并点击打开。
 */
const val ABOUT_GITHUB_URL = "https://github.com/Mimikkoro1/comfy-workbench-android"
const val ABOUT_LICENSE_URL = "https://github.com/Mimikkoro1/comfy-workbench-android/blob/main/LICENSE"

/** http(s) 才可打开；空字符串和其它占位都不跳转。 */
internal fun aboutOpenUrl(raw: String): String? {
    val url = raw.trim()
    if (url.startsWith("https://") || url.startsWith("http://")) return url
    return null
}

/** 设置页点进来的关于二级页。返回走 Overlay 的 pop（返回键 / 预测性返回 / 顶栏返回）。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val name = stringResource(R.string.app_name)
    val scheme = MaterialTheme.colorScheme
    LargeBarScaffold(title = stringResource(R.string.title_about), onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val icon = remember(context.packageName) {
                    context.packageManager.getApplicationIcon(context.packageName).mutate()
                }
                AndroidView(
                    modifier = Modifier.size(120.dp).clip(CircleShape),
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            setImageDrawable(icon)
                            contentDescription = name
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
            }
            Spacer(Modifier.height(16.dp))
            CardGroup {
                GroupedItem(
                    index = 0,
                    count = 2,
                    onClick = null,
                    headline = { Text(stringResource(R.string.about_version)) },
                    supporting = { Text("${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}") },
                )
                GroupedItem(
                    index = 1,
                    count = 2,
                    onClick = null,
                    headline = { Text(stringResource(R.string.about_system)) },
                    supporting = {
                        Text(
                            "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}",
                        )
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.about_gpl_note),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            CardGroup {
                AboutLinkRow(index = 0, count = 2, title = "Github", url = ABOUT_GITHUB_URL)
                AboutLinkRow(index = 1, count = 2, title = "License", url = ABOUT_LICENSE_URL)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutLinkRow(index: Int, count: Int, title: String, url: String) {
    val context = LocalContext.current
    val open = aboutOpenUrl(url)
    val unreleased = stringResource(R.string.about_unreleased)
    GroupedItem(
        index = index,
        count = count,
        onClick = if (open == null) {
            null
        } else {
            {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(open)))
                } catch (_: Exception) {
                }
            }
        },
        headline = { Text(title) },
        supporting = { Text(open ?: unreleased) },
    )
}
