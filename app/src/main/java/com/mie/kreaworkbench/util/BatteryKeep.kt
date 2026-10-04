package com.mie.kreaworkbench.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活相关跳转：忽略电池优化（系统标准），失败退系统电池优化列表。
 */
object BatteryKeep {

    fun isIgnoring(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }

    /** 弹系统「允许忽略电池优化」对话框（Manifest 已声明权限）；失败退列表页。返回是否发起了跳转。 */
    fun requestIgnore(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(direct)
            true
        } catch (_: Exception) {
            openList(context)
        }
    }

    fun openList(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: Exception) {
        false
    }
}
