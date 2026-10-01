package com.mie.kreaworkbench.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.AppLocalesStorageHelper
import androidx.core.os.LocaleListCompat

/**
 * 语言先写入磁盘，下次冷启动再生效。
 * 当前界面不调用 setApplicationLocales，避免先重建再重启。
 */
object AppLocale {
    private const val PREFS = "krea_locale"
    private const val KEY = "tags"

    /** null：还没用这套重启切换。""：跟随系统。 */
    fun languageTagOrNull(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return null
        return prefs.getString(KEY, "") ?: ""
    }

    fun persist(context: Context, id: String) {
        val tag = when (id) {
            "zh" -> "zh"
            "en" -> "en"
            else -> ""
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, tag)
            .commit()
        // API 33 以下 Activity 起来时 AppCompat 会读这份记录。空串会删掉文件，即跟随系统。
        AppLocalesStorageHelper.persistLocales(context, tag)
    }

    /** 进程刚起来、还没有 Activity。这时改语言不会重建当前界面。 */
    fun applyAtStartup(base: Context) {
        val tag = languageTagOrNull(base) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = base.getSystemService(LocaleManager::class.java) ?: return
            val desired = LocaleList.forLanguageTags(tag)
            if (!same(manager.applicationLocales.toLanguageTags(), tag)) {
                manager.applicationLocales = desired
            }
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    fun wrap(base: Context): Context {
        val tag = languageTagOrNull(base) ?: return base
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    fun restart(context: Context) {
        val appContext = context.applicationContext
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        appContext.startActivity(launch)
        (context as? Activity)?.finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun same(currentTags: String, desired: String): Boolean {
        if (desired.isEmpty()) return currentTags.isEmpty()
        val head = currentTags.substringBefore(',')
        return head == desired || head.startsWith("$desired-")
    }
}
