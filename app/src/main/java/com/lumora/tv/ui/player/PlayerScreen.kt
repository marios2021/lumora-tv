package com.lumora.tv.ui.player

import android.graphics.Color as AndroidColor
import android.text.format.DateFormat
import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.data.repo.BufferProfile
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.ui.common.ChannelLogo
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

    val player = remember {
        // Dead streams are common in IPTV lists: give up quickly so the error shows, instead of spinning.
        val streamHttp = container.http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        ExoPlayer.Builder(androidContext)
            .setLoadControl(loadControlFor(container.settings.current.value.bufferProfile))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(streamHttp))
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)),
            )
            .build()
    }
    // Declared first so it is disposed last, after the effects below that still touch the player.
    DisposableEffect(player) { onDispose { player.release() } }

    var error by remember { mutableStateOf<PlaybackException?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var videoHeight by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e
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
        player.setMediaItem(mediaItemFor(vm.playableUrl(c)))
        player.prepare()
        player.playWhenReady = true
    }

    // Live TV: stop when the app is backgrounded, then rejoin at the live edge when it comes back.
    LifecycleStartEffect(player) {
        if (player.mediaItemCount > 0) {
            player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = true
        }
        onStopOrDispose { player.stop() }
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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
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
                    "OK to retry  ·  ▲▼ to change channel  ·  Back to return",
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
            if (c != null) InfoOverlay(c, videoHeight, upcoming, now)
        }

        if (numberInput.isNotEmpty()) {
            Text(
                numberInput,
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .background(Color(0xCC0D0F16), RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            )
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
private fun InfoOverlay(channel: ChannelEntity, videoHeight: Int, upcoming: List<ProgramEntity>, now: Long) {
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
                    Tag("LIVE", background = colors.error)
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
            "▲▼ change channel   ·   0–9 channel number   ·   0 previous channel   ·   Menu: TV guide",
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
    return DefaultLoadControl.Builder().setBufferDurationsMs(min, max, start, resume).build()
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

private fun mediaItemFor(url: String): MediaItem {
    val builder = MediaItem.Builder().setUri(url)
    val lower = url.lowercase()
    when {
        ".m3u8" in lower -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        ".mpd" in lower -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
    }
    return builder.build()
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
