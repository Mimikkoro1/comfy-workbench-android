package com.mie.kreaworkbench

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.ui.locale.AppLocale
import com.mie.kreaworkbench.ui.components.AppToaster
import com.mie.kreaworkbench.ui.components.LocalBlurEnabled
import com.mie.kreaworkbench.ui.components.LocalHazeState
import com.mie.kreaworkbench.ui.motion.LocalSharedScope
import com.mie.kreaworkbench.ui.nav.AppNav
import com.mie.kreaworkbench.ui.nav.NavModel
import com.mie.kreaworkbench.ui.theme.KreaTheme

@OptIn(ExperimentalSharedTransitionApi::class)
class MainActivity : AppCompatActivity() {
    private var debugBack: android.content.BroadcastReceiver? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /**
     * DEBUG builds only: simulate a predictive back gesture through the real OnBackPressedDispatcher
     * (same path the system gesture uses), for testing on emulators whose launcher lacks gesture nav.
     * adb shell am broadcast -a com.mie.kreaworkbench.DEBUG_BACK --ef to 0.6 --ei ms 900 --ez commit true
     */
    private fun registerDebugBack() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val to = intent.getFloatExtra("to", 0.6f).coerceIn(0f, 1f)
                val ms = intent.getIntExtra("ms", 900).coerceAtLeast(16)
                val commit = intent.getBooleanExtra("commit", true)
                lifecycleScope.launch {
                    val d = onBackPressedDispatcher
                    val edge = androidx.activity.BackEventCompat.EDGE_LEFT
                    d.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 1200f, 0f, edge))
                    val steps = ms / 16
                    for (k in 1..steps) {
                        kotlinx.coroutines.delay(16)
                        val p = to * k / steps
                        d.dispatchOnBackProgressed(androidx.activity.BackEventCompat(p * 1080f, 1200f, p, edge))
                    }
                    kotlinx.coroutines.delay(16)
                    if (commit) d.onBackPressed() else d.dispatchOnBackCancelled()
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, r, android.content.IntentFilter("com.mie.kreaworkbench.DEBUG_BACK"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        debugBack = r
    }

    override fun onDestroy() {
        debugBack?.let { runCatching { unregisterReceiver(it) } }
        debugBack = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val c = (application as KreaApp).container
        lifecycleScope.launch(Dispatchers.IO) {
            val pending = c.db.needsWork().isNotEmpty()
            if (pending) withContext(Dispatchers.Main) { startGenerationService(this@MainActivity) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) registerDebugBack()
        setContent {
            val app = application as KreaApp
            val settings by app.container.settings.flow.collectAsState(initial = UserSettings())
            KreaTheme(settings.themeMode, settings.dynamicColor, settings.amoled) {
                val nav: NavModel = viewModel()
                val haze = rememberHazeState()
                val blur = settings.blurEnabled && Build.VERSION.SDK_INT >= 31
                CompositionLocalProvider(
                    LocalHazeState provides haze,
                    LocalBlurEnabled provides blur,
                ) {
                    AppToaster {
                        SharedTransitionLayout(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalSharedScope provides this) {
                                Box(Modifier.fillMaxSize().imePadding()) {
                                    AppNav(nav)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
