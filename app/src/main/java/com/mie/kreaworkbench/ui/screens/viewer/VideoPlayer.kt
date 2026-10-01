@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mie.kreaworkbench.ui.screens.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.mie.kreaworkbench.R
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * 视频内嵌播放：Media3 ExoPlayer + PlayerView。
 * 不循环。播完停在最后一帧，按钮变成重播。默认静音。全屏 = 切横屏。
 */
@Composable
fun VideoPlayer(
    path: String,
    active: Boolean,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var muted by remember(path) { mutableStateOf(true) }
    var playing by remember(path) { mutableStateOf(true) }
    var ended by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableLongStateOf(0L) }
    var duration by remember(path) { mutableLongStateOf(0L) }
    val scrubbing = remember(path) { booleanArrayOf(false) }
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    ended = true
                    playing = false
                    player.pause()
                    val d = player.duration
                    if (d > 0 && d != C.TIME_UNSET) {
                        duration = d
                        position = d
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(active) {
        player.playWhenReady = active && playing && !ended
        onDispose { }
    }
    DisposableEffect(muted) {
        player.volume = if (muted) 0f else 1f
        onDispose { }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing[0]) {
                val d = player.duration
                if (d > 0 && d != C.TIME_UNSET) duration = d
                if (!ended) position = player.currentPosition.coerceAtLeast(0L)
            }
            delay(200)
        }
    }
    val dur = duration.coerceAtLeast(0L)
    val shown = position.coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleChrome,
            ),
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_video_player, FrameLayout(ctx), false) as PlayerView).apply {
                    setPlayer(player)
                }
            },
            update = { it.setPlayer(player) },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    if (chromeVisible) {
                        Modifier.navigationBarsPadding().padding(bottom = 72.dp)
                    } else {
                        Modifier.padding(bottom = 12.dp)
                    },
                )
                .padding(horizontal = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(shown), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = shown.toFloat(),
                    onValueChange = { v ->
                        scrubbing[0] = true
                        position = v.toLong()
                    },
                    onValueChangeFinished = {
                        val target = position.coerceIn(0L, if (dur > 0L) dur else 0L)
                        player.seekTo(target)
                        scrubbing[0] = false
                        if (ended && target < dur) {
                            ended = false
                            playing = false
                            player.pause()
                        }
                    },
                    valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
                    enabled = dur > 0L,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(clock(dur), color = Color.White, fontSize = 12.sp)
            }
            Row(
                Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ended) {
                    Text(stringResource(R.string.action_replay), color = Color.White, fontSize = 12.sp)
                }
                IconButton(onClick = {
                    if (ended) {
                        ended = false
                        playing = true
                        player.seekTo(0)
                        player.play()
                    } else {
                        playing = !playing
                        player.playWhenReady = playing
                    }
                }) {
                    Icon(
                        if (playing && !ended) Lucide.Pause else Lucide.Play,
                        if (ended) stringResource(R.string.action_replay) else if (playing) stringResource(R.string.action_pause) else stringResource(R.string.action_play),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { muted = !muted }) {
                    Icon(
                        if (muted) Lucide.VolumeX else Lucide.Volume2,
                        if (muted) stringResource(R.string.action_unmute) else stringResource(R.string.action_mute),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    val act = view.context as? Activity ?: return@IconButton
                    act.requestedOrientation =
                        if (act.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                }) {
                    Icon(Lucide.Maximize, stringResource(R.string.action_fullscreen), tint = Color.White)
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    if (ms <= 0L || ms == C.TIME_UNSET) return "0:00"
    val total = ms / 1000
    val s = (total % 60).toInt()
    val m = ((total / 60) % 60).toInt()
    val h = (total / 3600).toInt()
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
