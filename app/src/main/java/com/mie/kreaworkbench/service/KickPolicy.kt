package com.mie.kreaworkbench.service

/** 冷启动 / 服务重启对账时要不要把这行任务拉起来：离线时 paused 不拉（拉了也只会空转到 deadline）。 */
internal fun shouldKickOnReconcile(state: String, online: Boolean): Boolean = online || state != "paused"
