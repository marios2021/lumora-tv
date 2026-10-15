package com.lumora.tv.ui.vod

import android.graphics.Color as AndroidColor
import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.R
import com.lumora.tv.ui.common.Button
import com.lumora.tv.ui.common.IconButton
import com.lumora.tv.ui.common.LocalIsTv
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout
import com.lumora.tv.ui.player.mediaItemFor
import com.lumora.tv.ui.player.useTextureView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val CONTROLS_MS = 4_000L
private const val SAVE_EVERY_MS = 10_000L

/** What to play in the seekable player: a movie, an episode, or a catch-up programme. */
data class VodRequest(
    val url: String,
    val title: String,
    val subtitle: String?,
    val playlistId: Long,
    /** Where to remember the position ("movie:<id>", "episode:<id>"); null to not remember. */
    val contentKey: String?,
    val seriesId: String?,
    val startMs: Long,
)

/** Carried in the navigation route as JSON. */
fun VodRequest.encode(): String = JSONObject()
    .put("url", url)
    .put("title", title)
    .put("subtitle", subtitle ?: JSONObject.NULL)
    .put("playlistId", playlistId)
    .put("contentKey", contentKey ?: JSONObject.NULL)
    .put("seriesId", seriesId ?: JSONObject.NULL)
    .put("startMs", startMs)
    .toString()

fun decodeVodRequest(json: String): VodRequest {
    val o = JSONObject(json)
    fun opt(name: String) = if (o.isNull(name)) null else o.optString(name)
    return VodRequest(
        url = o.getString("url"),
        title = o.optString("title"),
        subtitle = opt("subtitle"),
        playlistId = o.optLong("playlistId"),
        contentKey = opt("contentKey"),
        seriesId = opt("seriesId"),
        startMs = o.optLong("startMs"),
    )
}

/**
 * Full-screen player for things with a beginning and an end. Remote: OK play/pause, ◀ ▶ 10 s
 * (held: 30 s), Rewind / Fast-forward 30 s, Back to leave. Touch: tap shows the controls.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(request: VodRequest, onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val player = remember {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            // Network streams through OkHttp; recordings are local files.
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(container.http))))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setMediaItem(mediaItemFor(request.url), request.startMs)
                prepare()
                playWhenReady = true
            }
    }

    var position by remember { mutableLongStateOf(request.startMs) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<PlaybackException?>(null) }
    var ended by remember { mutableStateOf(false) }

    fun saveProgress() {
        val key = request.contentKey ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        container.appScope.launch {
            container.vod.saveProgress(request.playlistId, key, if (ended) dur else pos, dur, request.seriesId)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
                if (state == Player.STATE_ENDED) {
                    ended = true
                    saveProgress()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = e
            }
        }
        player.addListener(listener)
        onDispose {
            saveProgress()
            player.removeListener(listener)
            player.release()
        }
    }

    // Pause in the background (and save where we are); carry on when back.
    LifecycleStartEffect(player) {
        onStopOrDispose {
            saveProgress()
            player.pause()
        }
    }

    LaunchedEffect(player) {
        var sinceSave = 0L
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(500)
            sinceSave += 500
            if (sinceSave >= SAVE_EVERY_MS && player.isPlaying) {
                saveProgress()
                sinceSave = 0
            }
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var controlsStamp by remember { mutableIntStateOf(0) }
    fun showControls() {
        controlsVisible = true
        controlsStamp++
    }
    LaunchedEffect(controlsStamp, controlsVisible, playing) {
        if (controlsVisible && playing) {
            delay(CONTROLS_MS)
            controlsVisible = false
        }
    }

    fun seekBy(ms: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, dur))
        position = player.currentPosition
        ended = false
        showControls()
    }

    fun togglePlay() {
        when {
            error != null -> {
                error = null
                player.prepare()
                player.play()
            }
            ended -> {
                ended = false
                player.seekTo(0)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
        showControls()
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val repeat = event.nativeKeyEvent.repeatCount
        when (event.key.nativeKeyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> if (repeat == 0) togglePlay()
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (!player.isPlaying) togglePlay()
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (player.isPlaying) togglePlay()
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> seekBy(if (repeat > 2) -30_000 else -10_000)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(if (repeat > 2) 30_000 else 10_000)
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-30_000)
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000)
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_INFO -> showControls()
            else -> return false
        }
        return true
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusAfterLayout() }
    val isTv = LocalIsTv.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent(::handleKey)
            .focusRequester(focus)
            .focusable(),
    ) {
        val texture = remember { useTextureView(context, container.settings.current.value.videoOutput) }
        AndroidView(
            factory = { ctx ->
                val view = if (texture) {
                    LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView
                } else {
                    PlayerView(ctx)
                }
                view.apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Touch: a tap shows or hides the controls.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { if (controlsVisible) controlsVisible = false else showControls() })
                },
        )

        if (buffering && error == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        error?.let { e ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play this", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(e.message ?: e.errorCodeName, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(if (isTv) "OK to retry  ·  Back to return" else "Tap Play to retry", style = MaterialTheme.typography.labelMedium)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible || !playing || error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Controls(
                request = request,
                position = position,
                duration = duration,
                playing = playing,
                isTv = isTv,
                onSeekTo = { fraction ->
                    if (duration > 0) {
                        player.seekTo((duration * fraction).toLong())
                        ended = false
                        showControls()
                    }
                },
                onReplay = { seekBy(-10_000) },
                onToggle = ::togglePlay,
                onForward = { seekBy(10_000) },
            )
        }

        if (!isTv) {
            AnimatedVisibility(
                visible = controlsVisible || !playing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp),
            ) {
                Button(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun Controls(
    request: VodRequest,
    position: Long,
    duration: Long,
    playing: Boolean,
    isTv: Boolean,
    onSeekTo: (Float) -> Unit,
    onReplay: () -> Unit,
    onToggle: () -> Unit,
    onForward: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000))))
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp),
    ) {
        Text(request.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        request.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset -> onSeekTo((offset.x / size.width).coerceIn(0f, 1f)) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(colors.primary, RoundedCornerShape(2.dp)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                formatTime(position) + " / " + (if (duration > 0) formatTime(duration) else "--:--"),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            if (isTv) {
                Text(
                    "OK play / pause   ·   ◀ ▶ 10 s (hold for 30 s)   ·   Back to leave",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onReplay) { Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds") }
                    IconButton(onClick = onToggle) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
                    }
                    IconButton(onClick = onForward) { Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds") }
                }
            }
        }
    }
}

/** 1:02:03 or 2:03. */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
