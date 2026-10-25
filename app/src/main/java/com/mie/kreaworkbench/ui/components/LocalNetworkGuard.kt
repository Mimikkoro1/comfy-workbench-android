package com.mie.kreaworkbench.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.net.LOCAL_NETWORK_PERMISSION
import com.mie.kreaworkbench.data.net.isLocalNetworkGranted
import com.mie.kreaworkbench.data.net.needsLocalNetworkPermission
import com.mie.kreaworkbench.data.net.urlTargetsLocalNetwork

/** 进程内「全局引导最多弹一次」的标志位（不落库，进程重启重新计）。 */
object LocalNetworkGlobalPrompt {
    @Volatile
    var shown = false
}

/** 跳到本应用的系统设置页（r41：被拒弹窗与连接失败提示的「去系统设置」共用）。 */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * r41：局域网权限引导。安卓 17（targetSdk 37）起访问局域网地址要 ACCESS_LOCAL_NETWORK，
 * 这里统一收口：先弹说明弹窗（GlassAlertDialog），「去授权」拉起系统「附近设备」授权框；
 * 被拒后再给「去系统设置开启」的出口。任何分支都不会卡死调用方。
 *
 * 用法：`val guard = rememberLocalNetworkGuard()`；每个会访问用户填写地址的动作外包一层
 * `guard.ensure(url, runEvenIfDenied) { ... }`；页面末尾放一次 `guard.Dialogs()`。
 * 不需要权限（低版本 / 已授权 / 公网地址）时 ensure 直接执行 action，零打扰。
 */
class LocalNetworkGuard internal constructor(private val context: Context) {
    internal var launcher: ((String) -> Unit)? = null

    internal var explain by mutableStateOf(false)
    internal var denied by mutableStateOf(false)
    private var pendingAction: (() -> Unit)? = null
    private var pendingRunEvenIfDenied = false

    /**
     * [url] 为 null 表示「要扫描整个局域网」（一定算局域网）。
     * 需要权限 → 弹说明弹窗，[action] 挂起等授权结果；不需要 → 立刻执行。
     */
    fun ensure(url: String?, runEvenIfDenied: Boolean, action: () -> Unit) {
        val hostIsLocal = url?.let { urlTargetsLocalNetwork(it) } ?: true
        if (!needsLocalNetworkPermission(
                android.os.Build.VERSION.SDK_INT,
                isLocalNetworkGranted(context),
                hostIsLocal,
            )
        ) {
            action()
            return
        }
        pendingAction = action
        pendingRunEvenIfDenied = runEvenIfDenied
        explain = true
    }

    internal fun onPermissionResult(granted: Boolean) {
        if (granted) {
            val action = pendingAction
            reset()
            action?.invoke()
        } else {
            explain = false
            denied = true
        }
    }

    /** 「暂不」/被拒弹窗点「取消」：按 runEvenIfDenied 决定是否照跑（跑会失败并给出提示）。 */
    internal fun resolveWithoutPermission() {
        val action = pendingAction
        val run = pendingRunEvenIfDenied
        reset()
        if (run) action?.invoke()
    }

    private fun reset() {
        explain = false
        denied = false
        pendingAction = null
        pendingRunEvenIfDenied = false
    }

    /** 说明弹窗 + 被拒弹窗。调用方在页面末尾放一次。 */
    @Composable
    fun Dialogs() {
        if (explain) {
            GlassAlertDialog(
                onDismissRequest = { resolveWithoutPermission() },
                title = { Text(stringResource(R.string.lan_perm_title)) },
                text = {
                    Text(stringResource(R.string.lan_perm_body), fontSize = 13.sp)
                },
                confirmButton = {
                    TextButton(onClick = {
                        explain = false
                        launcher?.invoke(LOCAL_NETWORK_PERMISSION)
                    }) { Text(stringResource(R.string.lan_perm_go)) }
                },
                dismissButton = {
                    TextButton(onClick = { resolveWithoutPermission() }) {
                        Text(stringResource(R.string.lan_perm_later))
                    }
                },
            )
        }
        if (denied) {
            GlassAlertDialog(
                onDismissRequest = { resolveWithoutPermission() },
                title = { Text(stringResource(R.string.lan_perm_denied_title)) },
                text = {
                    Text(stringResource(R.string.lan_perm_denied_body), fontSize = 13.sp)
                },
                confirmButton = {
                    TextButton(onClick = {
                        reset()
                        // 跳转失败（极端 ROM 裁剪）也不能卡住 App
                        openAppSettings(context)
                    }) { Text(stringResource(R.string.lan_perm_open_settings)) }
                },
                dismissButton = {
                    TextButton(onClick = { resolveWithoutPermission() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
fun rememberLocalNetworkGuard(): LocalNetworkGuard {
    val context = LocalContext.current
    val guard = remember { LocalNetworkGuard(context.applicationContext) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        guard.onPermissionResult(granted)
    }
    SideEffect {
        guard.launcher = { permission -> permLauncher.launch(permission) }
    }
    return guard
}
