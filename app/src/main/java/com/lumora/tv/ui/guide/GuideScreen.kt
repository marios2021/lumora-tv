package com.lumora.tv.ui.guide

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.data.epg.epgKey
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.ui.common.ChannelLogo
import com.lumora.tv.ui.common.LocalIsTv
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.rememberTimeFormat
import com.lumora.tv.ui.common.requestFocusAfterLayout
import com.lumora.tv.ui.common.span
import com.lumora.tv.ui.common.touchClick
import com.lumora.tv.ui.guide.GuideViewModel.Companion.SLOT_MS
import com.lumora.tv.ui.guide.GuideViewModel.Companion.WINDOW_MS
import com.lumora.tv.ui.guide.GuideViewModel.Companion.floorSlot
import java.text.DateFormat
import java.util.concurrent.TimeUnit

private val ChannelColumn = 260.dp
private val RowHeight = 56.dp
private val SidePadding = 32.dp

@Composable
fun GuideScreen(
    playlistId: Long,
    context: GroupSelection,
    startChannelId: Long?,
    onPlay: (channelId: Long, context: GroupSelection) -> Unit,
) {
    val container = appContainer()
    val vm: GuideViewModel = viewModel {
        GuideViewModel(container.playlists, container.settings, container.epg, playlistId, context, startChannelId)
    }
    val channels by vm.channels.collectAsStateWithLifecycle()
    val cursor by vm.cursor.collectAsStateWithLifecycle()
    val windowStart by vm.windowStart.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val timeFormat = rememberTimeFormat()
    val listState = rememberLazyListState()

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusAfterLayout() }

    fun visibleRows(): Int = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(2) - 1

    // Keep the selected row on screen.
    LaunchedEffect(cursor.row, channels?.size) {
        val row = cursor.row
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) {
            listState.scrollToItem((row - 2).coerceAtLeast(0))
            return@LaunchedEffect
        }
        val first = listState.firstVisibleItemIndex
        val fullyVisible = visible.count { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
        when {
            row < first -> listState.scrollToItem(row)
            row >= first + fullyVisible -> listState.scrollToItem((row - fullyVisible + 1).coerceAtLeast(0))
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        val code = event.key.nativeKeyCode
        if (event.type == KeyEventType.KeyDown) {
            when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> vm.moveRows(-1)
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> vm.moveRows(+1)
                AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> vm.moveRows(-visibleRows())
                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> vm.moveRows(+visibleRows())
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> vm.moveLeft()
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> vm.moveRight()
                AndroidKeyEvent.KEYCODE_0 -> vm.jumpToNow()
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> Unit
                else -> return false
            }
            return true
        }
        if (event.type == KeyEventType.KeyUp && code in setOf(
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
            )
        ) {
            vm.focusedChannel()?.let { onPlay(it.id, vm.context) }
            return true
        }
        return false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onKeyEvent(::handleKey)
            .focusRequester(focus)
            .focusable(),
    ) {
        val list = channels
        // Reading programs here (not just in the VM) recomposes the details when guide data arrives.
        val focusedChannel = list?.getOrNull(cursor.row)
        val focusedProgram = focusedChannel?.let { ch ->
            programs[ch.epgKey]?.firstOrNull { it.start <= cursor.time && cursor.time < it.stop }
        }
        GuideDetails(focusedChannel, focusedProgram, now, timeFormat, groupLabel(vm.context))

        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                "No channels in this group.",
                modifier = Modifier.padding(SidePadding),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val gridWidth = maxWidth - ChannelColumn - SidePadding * 2
                val dpPerMs = gridWidth.value / WINDOW_MS
                Column(Modifier.padding(horizontal = SidePadding)) {
                    TimelineHeader(windowStart, now, dpPerMs, timeFormat)
                    // Touch: dragging moves the selection like the arrow buttons (one row per row
                    // height, one programme per half-hour width), which scrolls the grid with it.
                    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
                    val slotPx = with(LocalDensity.current) { (SLOT_MS * dpPerMs).dp.toPx() }
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = false,
                        modifier = Modifier.pointerInput(rowPx, slotPx) {
                            var dx = 0f
                            var dy = 0f
                            detectDragGestures(onDragStart = { dx = 0f; dy = 0f }) { change, drag ->
                                change.consume()
                                dx += drag.x
                                dy += drag.y
                                while (dy <= -rowPx) { vm.moveRows(+1); dy += rowPx }
                                while (dy >= rowPx) { vm.moveRows(-1); dy -= rowPx }
                                while (dx <= -slotPx) { vm.moveRight(); dx += slotPx }
                                while (dx >= slotPx) { vm.moveLeft(); dx -= slotPx }
                            }
                        },
                    ) {
                        itemsIndexed(list, key = { _, ch -> ch.id }) { index, channel ->
                            GuideRow(
                                channel = channel,
                                programs = programs[channel.epgKey].orEmpty(),
                                windowStart = windowStart,
                                now = now,
                                dpPerMs = dpPerMs,
                                focusedTime = if (index == cursor.row) cursor.time else null,
                                onTapChannel = { onPlay(channel.id, vm.context) },
                                onTapTime = { time ->
                                    // First tap selects a programme; tapping the selected one watches it.
                                    val tapped = vm.programAt(index, time)
                                    val selected = vm.focusedProgram()
                                    if (cursor.row == index && tapped != null && tapped.id == selected?.id) {
                                        onPlay(channel.id, vm.context)
                                    } else {
                                        vm.setCursor(index, time)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideDetails(
    channel: ChannelEntity?,
    program: ProgramEntity?,
    now: Long,
    timeFormat: DateFormat,
    group: String,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .padding(horizontal = SidePadding + 16.dp, vertical = 24.dp),
    ) {
        Column(Modifier.weight(1f)) {
            if (channel != null) {
                Text(
                    "${channel.number}  ${channel.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                program?.title ?: "No programme information",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (program != null) {
                Text(
                    timeFormat.span(program) + "   ·   " + describeTiming(program, now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                program.description?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(timeFormat.format(now), style = MaterialTheme.typography.headlineSmall)
            Text(
                android.text.format.DateFormat.getMediumDateFormat(context).format(now),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "TV guide  ·  $group",
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
            )
            Text(
                if (LocalIsTv.current) {
                    "OK watch  ·  ◀ ▶ time  ·  ▲ ▼ channels  ·  0 now"
                } else {
                    "Tap a programme, tap again to watch  ·  drag to scroll"
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineHeader(windowStart: Long, now: Long, dpPerMs: Float, timeFormat: DateFormat) {
    Row(Modifier.fillMaxWidth().height(32.dp)) {
        Spacer(Modifier.width(ChannelColumn))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            NowMarker(windowStart, now, dpPerMs)
            var slot = windowStart
            while (slot < windowStart + WINDOW_MS) {
                Text(
                    timeFormat.format(slot),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .offset(x = msToDp(slot - windowStart, dpPerMs))
                        .padding(start = 6.dp)
                        .align(Alignment.CenterStart),
                )
                slot += SLOT_MS
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programs: List<ProgramEntity>,
    windowStart: Long,
    now: Long,
    dpPerMs: Float,
    focusedTime: Long?,
    onTapChannel: () -> Unit,
    onTapTime: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val windowEnd = windowStart + WINDOW_MS
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .touchClick(onClick = onTapChannel)
                .padding(end = 4.dp)
                .background(
                    if (focusedTime != null) colors.primaryContainer else colors.surface,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.number.toString(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(36.dp),
            )
            ChannelLogo(channel.logo, channel.name, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                channel.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .pointerInput(windowStart, dpPerMs) {
                    detectTapGestures { offset ->
                        onTapTime(windowStart + (offset.x.toDp().value / dpPerMs).toLong())
                    }
                },
        ) {
            val visible = programs.filter { it.stop > windowStart && it.start < windowEnd }
            var focusCovered = false
            for (p in visible) {
                val focused = focusedTime != null && focusedTime >= p.start && focusedTime < p.stop
                if (focused) focusCovered = true
                ProgramCell(
                    from = p.start,
                    to = p.stop,
                    windowStart = windowStart,
                    dpPerMs = dpPerMs,
                    title = p.title,
                    focused = focused,
                    now = now,
                )
            }
            if (visible.isEmpty()) {
                ProgramCell(windowStart, windowEnd, windowStart, dpPerMs, "No information", focusedTime != null, now = 0)
            } else if (focusedTime != null && !focusCovered) {
                val slot = floorSlot(focusedTime)
                ProgramCell(slot, slot + SLOT_MS, windowStart, dpPerMs, "No information", focused = true, now = 0)
            }
        }
    }
}

@Composable
private fun ProgramCell(
    from: Long,
    to: Long,
    windowStart: Long,
    dpPerMs: Float,
    title: String,
    focused: Boolean,
    /** Pass 0 for placeholder cells that never count as airing. */
    now: Long,
) {
    val colors = MaterialTheme.colorScheme
    val start = maxOf(from, windowStart)
    val end = minOf(to, windowStart + WINDOW_MS)
    if (end <= start) return
    val airing = now in from until to
    val shape = RoundedCornerShape(6.dp)
    val background = when {
        focused -> colors.inverseSurface
        airing -> colors.surfaceVariant
        else -> colors.surface
    }
    Box(
        modifier = Modifier
            .offset(x = msToDp(start - windowStart, dpPerMs))
            .width(msToDp(end - start, dpPerMs))
            .fillMaxHeight()
            .padding(horizontal = 2.dp)
            .background(background, shape)
            .clip(shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (airing) {
            // The part already shown, tinted like a progress bar.
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(((now - start).toFloat() / (end - start)).coerceIn(0f, 1f))
                    .background(colors.primary.copy(alpha = if (focused) 0.25f else 0.18f)),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) colors.inverseOnSurface else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

@Composable
private fun NowMarker(windowStart: Long, now: Long, dpPerMs: Float) {
    if (now < windowStart || now >= windowStart + WINDOW_MS) return
    Box(
        Modifier
            .offset(x = msToDp(now - windowStart, dpPerMs))
            .width(2.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.error),
    )
}

private fun groupLabel(group: GroupSelection): String = when (group) {
    GroupSelection.All -> "All channels"
    GroupSelection.Favorites -> "Favorites"
    is GroupSelection.Named -> group.name
}

private fun msToDp(ms: Long, dpPerMs: Float): Dp = (ms * dpPerMs).dp

private fun describeTiming(program: ProgramEntity, now: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(program.stop - program.start)
    val timing = when {
        now >= program.stop -> "ended"
        now >= program.start -> "on now, ${TimeUnit.MILLISECONDS.toMinutes(program.stop - now)} min left"
        else -> {
            val until = TimeUnit.MILLISECONDS.toMinutes(program.start - now)
            if (until < 60) "starts in $until min" else "starts in ${until / 60} h ${until % 60} min"
        }
    }
    return "$minutes min  ·  $timing"
}
