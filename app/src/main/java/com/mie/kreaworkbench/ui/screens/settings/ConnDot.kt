package com.mie.kreaworkbench.ui.screens.settings

/**
 * r43：设置首页「ComfyUI 服务器」行的连接状态点三态（无 Android 依赖，纯 JVM 单测）。
 */
enum class ConnDot { UNKNOWN, OK, FAILED }

/**
 * [connOk]：本进程内最近一次手动「测试连接」的结果（null = 还没测过 / 地址变了结论作废）；
 * [busy]：测试进行中。进行中一律显示灰（UNKNOWN），测试结束后才落绿 / 红。
 */
fun connDotState(connOk: Boolean?, busy: Boolean): ConnDot = when {
    busy -> ConnDot.UNKNOWN
    connOk == null -> ConnDot.UNKNOWN
    connOk -> ConnDot.OK
    else -> ConnDot.FAILED
}
