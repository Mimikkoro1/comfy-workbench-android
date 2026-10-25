package com.mie.kreaworkbench.service

/** 完成通知的 id：同一个任务恒定、不同任务基本不撞，且避开前台服务的 41。 */
internal fun doneNotifyId(clientId: String): Int = 100 + ((clientId.hashCode() and 0x7fffffff) % 900_000)
