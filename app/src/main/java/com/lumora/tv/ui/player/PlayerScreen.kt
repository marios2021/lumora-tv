package com.lumora.tv.ui.player

import android.graphics.Color as AndroidColor
import android.text.format.DateFormat
import android.view.KeyEvent as AndroidKeyEvent
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.R
import com.lumora.tv.data.repo.BufferProfile
import com.lumora.tv.data.repo.VideoOutput
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.ui.common.Button
import com.lumora.tv.ui.common.ChannelLogo
import com.lumora.tv.ui.common.IconButton
import com.lumora.tv.ui.common.LocalIsTv
import com.lumora.tv.ui.common.ProgressLine
import com.lumora.tv.ui.common.rememberTimeFormat
import com.lumora.tv.ui.common.span
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

private const val OVERLAY_MS = 5_000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channelId: Long,
    context: GroupSelection,
    onOpenGuide: (playlistId: Long, context: GroupSelection, channelId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = appContainer()
    val vm: PlayerViewModel = viewModel {
        PlayerViewModel(container.playlists, container.settings, container.epg, channelId, context)
    }
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val channel by vm.channel.collectAsStateWithLifecycle()
    val numberInput by vm.numberInput.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val androidContext = LocalContext.current

    // Dead streams are common in IPTV lists: give up quickly so the error shows, instead of spinning.
    val streamHttp = remember {
        container.http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    val player = remember {
        // If the device's preferred video decoder fails to start, try the next one instead of failing.
        val renderers = DefaultRenderersFactory(androidContext).setEnableDecoderFallback(true)
        ExoPlayer.Builder(androidContext, renderers)
            .setLoadControl(loadControlFor(container.settings.current.value.bufferProfile))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(streamHttp))
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)),
            )
            .build()
    }
    val live = remember { LivePlayback(player, streamHttp) }
    // Declared first so it is disposed last, after the effects below that still touch the player.
    DisposableEffect(player) {
        onDispose {
            live.stop()
            player.release()
        }
    }

    var error by remember { mutableStateOf<PlaybackException?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var behind by remember { mutableLongStateOf(0L) }
    var canRewind by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        while (true) {
            behind = live.behindMs()
            canRewind = live.canRewind
            delay(500)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (!live.recover(e)) error = e
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoHeight = size.height
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    fun startPlayback(c: ChannelEntity) {
        error = null
        videoHeight = 0
        behind = 0
        live.play(vm.playableUrl(c))
    }

    // Live TV: stop when the app is backgrounded, then rejoin at the live edge when it comes back.
    LifecycleStartEffect(player) {
        if (player.mediaItemCount > 0) live.restart()
        onStopOrDispose { live.stop() }
    }

    // Info overlay: shown on every channel change and on OK, hidden again after a few seconds.
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayStamp by remember { mutableIntStateOf(0) }
    fun showOverlay() {
        overlayVisible = true
        overlayStamp++
    }
    LaunchedEffect(overlayStamp, overlayVisible) {
        if (overlayVisible) {
            delay(OVERLAY_MS)
            overlayVisible = false
        }
    }
    LaunchedEffect(channel) {
        channel?.let {
            startPlayback(it)
            showOverlay()
        }
    }

    // Back first closes the overlay or abandons a half-typed number; only then leaves the player.
    BackHandler(enabled = overlayVisible || numberInput.isNotEmpty()) {
        if (numberInput.isNotEmpty()) vm.cancelNumber() else overlayVisible = false
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusAfterLayout() }

    // OK on the remote, or a tap on a touch screen.
    fun onOk() {
        val c = channel
        when {
            numberInput.isNotEmpty() -> vm.commitNumberNow()
            error != null && c != null -> startPlayback(c)
            overlayVisible -> overlayVisible = false
            else -> showOverlay()
        }
    }

    fun rewind() {
        when {
            !live.canRewind -> vm.showNotice("Rewind isn't available on this channel")
            !live.seekBy(-REWIND_STEP_MS) -> vm.showNotice("Can't go back further")
        }
        behind = live.behindMs()
    }

    fun forward() {
        if (live.atLive) vm.showNotice("You're watching live") else live.seekBy(REWIND_STEP_MS)
        behind = live.behindMs()
    }

    fun handleKey(event: KeyEvent): Boolean {
        val code = event.key.nativeKeyCode
        if (event.type == KeyEventType.KeyDown) {
            digitFor(code)?.let {
                vm.typeDigit(it)
                return true
            }
            when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_UP,
                AndroidKeyEvent.KEYCODE_CHANNEL_UP,
                AndroidKeyEvent.KEYCODE_PAGE_UP -> vm.zap(+1)
                AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
                AndroidKeyEvent.KEYCODE_PAGE_DOWN -> vm.zap(-1)
                AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> vm.recallPrevious()
                AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> rewind()
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> forward()
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                AndroidKeyEvent.KEYCODE_SPACE -> live.togglePause()
                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (!paused) live.togglePause()
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (paused) live.togglePause()
                AndroidKeyEvent.KEYCODE_INFO -> if (overlayVisible) overlayVisible = false else showOverlay()
                // Many Android TV builds reserve the Guide key for the system TV app, so Menu opens it too.
                AndroidKeyEvent.KEYCODE_GUIDE,
                AndroidKeyEvent.KEYCODE_MENU -> channel?.let { onOpenGuide(it.playlistId, vm.context, it.id) }
                // OK acts on key-up; swallow the down so nothing else reacts to it.
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> Unit
                else -> return false
            }
            return true
        }
        if (event.type == KeyEventType.KeyUp && code in OK_KEYS) {
            onOk()
            return true
        }
        return false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent(::handleKey)
            .focusRequester(focus)
            .focusable(),
    ) {
        val useTextureView = remember { useTextureView(androidContext, container.settings.current.value.videoOutput) }
        AndroidView(
            factory = { ctx ->
                val view = if (useTextureView) {
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

        // Touch screens: tap acts as OK; swipe up / down changes channel like the ▲ / ▼ buttons.
        val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onOk() }) }
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            when {
                                dragged < -swipeThreshold -> vm.zap(+1)
                                dragged > swipeThreshold -> vm.zap(-1)
                            }
                        },
                        onVerticalDrag = { _, dy -> dragged += dy },
                    )
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
                Text("Can't play this channel", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(describe(e), style = MaterialTheme.typography.bodyMedium)
                Text(
                    e.errorCodeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (LocalIsTv.current) {
                        "OK to retry  ·  ▲▼ to change channel  ·  Back to return"
                    } else {
                        "Tap to retry  ·  swipe up or down to change channel"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        val c = channel
        AnimatedVisibility(
            visible = (overlayVisible || error != null) && c != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (c != null) InfoOverlay(c, videoHeight, upcoming, now, behind, paused)
        }

        // Touch screens have no remote: offer Back and the TV guide on screen while the info shows.
        if (!LocalIsTv.current) {
            AnimatedVisibility(
                visible = (overlayVisible || error != null) && c != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Back")
                    }
                    Button(onClick = { c?.let { onOpenGuide(it.playlistId, vm.context, it.id) } }) {
                        Icon(Icons.Filled.CalendarViewWeek, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("TV guide")
                    }
                    if (canRewind) {
                        Spacer(Modifier.width(24.dp))
                        IconButton(onClick = ::rewind) { Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds") }
                        IconButton(onClick = { live.togglePause() }) {
                            Icon(
                                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = if (paused) "Play" else "Pause",
                            )
                        }
                        IconButton(onClick = ::forward) { Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds") }
                        if (behind >= 1_000 || paused) {
                            Button(onClick = {
                                live.goLive()
                                behind = 0
                            }) { Text("Live") }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(40.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (numberInput.isNotEmpty()) {
                Text(
                    numberInput,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier
                        .background(Color(0xCC0D0F16), RoundedCornerShape(12.dp))
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                )
            }
            // While paused or rewound, always show how far behind live this is.
            if (paused || behind >= 1_000) {
                val isTv = LocalIsTv.current
                Text(
                    when {
                        paused -> "❚❚  Paused  " + behindLabel(behind)
                        isTv -> behindLabel(behind) + "  ·  ▶ to catch up"
                        else -> behindLabel(behind) + " behind live"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .background(Color(0xCC0D0F16), RoundedCornerShape(24.dp))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .background(Color(0xCC0D0F16), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun InfoOverlay(
    channel: ChannelEntity,
    videoHeight: Int,
    upcoming: List<ProgramEntity>,
    now: Long,
    behind: Long,
    paused: Boolean,
) {
    val context = LocalContext.current
    val timeFormat = rememberTimeFormat()
    val current = upcoming.firstOrNull()?.takeIf { it.start <= now }
    val next = if (current != null) upcoming.getOrNull(1) else upcoming.firstOrNull()
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000))))
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, channel.name, size = 80.dp)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        channel.number.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.primary,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        paused -> Tag("PAUSED", background = colors.surfaceVariant)
                        behind >= 1_000 -> Tag(behindLabel(behind), background = colors.surfaceVariant)
                        else -> Tag("LIVE", background = colors.error)
                    }
                    qualityLabel(videoHeight)?.let { Tag(it, background = colors.surfaceVariant) }
                    Text(
                        channel.groupTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(timeFormat.format(now), style = MaterialTheme.typography.headlineMedium)
                Text(
                    DateFormat.getMediumDateFormat(context).format(now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (current != null || next != null) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.padding(start = 104.dp)) {
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            timeFormat.span(current),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            current.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(current.progress(now), Modifier.width(480.dp))
                    current.description?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(720.dp),
                        )
                    }
                }
                if (next != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Next   " + timeFormat.format(next.start) + "   " + next.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (LocalIsTv.current) {
                "▲▼ channel   ·   ◀▶ rewind / forward   ·   0–9 number   ·   0 previous   ·   Menu: TV guide"
            } else {
                "Swipe up or down to change channel   ·   tap to hide"
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun Tag(text: String, background: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private val OK_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

/** Automatic: TextureView on phones and tablets (avoids black video on some), SurfaceView on TVs. */
internal fun useTextureView(context: Context, output: VideoOutput): Boolean = when (output) {
    VideoOutput.TEXTURE_VIEW -> true
    VideoOutput.SURFACE_VIEW -> false
    VideoOutput.AUTO -> {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiMode.currentModeType != Configuration.UI_MODE_TYPE_TELEVISION
    }
}

@OptIn(UnstableApi::class)
private fun loadControlFor(profile: BufferProfile): DefaultLoadControl {
    // (min buffer, max buffer, buffer before starting, buffer before resuming after a stall), in ms.
    val (min, max, start, resume) = when (profile) {
        BufferProfile.FAST_START -> listOf(15_000, 50_000, 1_000, 2_000)
        BufferProfile.BALANCED -> listOf(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        BufferProfile.SMOOTH -> listOf(30_000, 90_000, 4_000, 8_000)
    }
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(min, max, start, resume)
        // Keep what was just played, so rewinding an HLS stream is instant.
        .setBackBuffer((MAX_REWIND_MS + 5_000).toInt(), true)
        .build()
}

private fun digitFor(keyCode: Int): Int? = when (keyCode) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> keyCode - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

private fun qualityLabel(height: Int): String? = when {
    height <= 0 -> null
    height >= 2000 -> "4K"
    height >= 1000 -> "FHD"
    height >= 700 -> "HD"
    else -> "SD"
}

private fun describe(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "The server refused the stream. The channel may be offline or your account may have reached its connection limit."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Couldn't connect to the stream."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
        "The stream format isn't supported."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
        "This device can't decode the stream."
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
        "The stream fell behind. Retry to rejoin live."
    else -> e.message ?: "Playback failed."
}
