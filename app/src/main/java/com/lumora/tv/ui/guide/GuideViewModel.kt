package com.lumora.tv.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.data.epg.EpgRepository
import com.lumora.tv.data.epg.epgKey
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.data.repo.PlaylistRepository
import com.lumora.tv.data.repo.SettingsRepository
import com.lumora.tv.ui.common.minuteTicker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** The guide selection: a channel row and a point in time on it. The focused cell is whatever airs then. */
data class GuideCursor(val row: Int, val time: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(
    private val repo: PlaylistRepository,
    private val settings: SettingsRepository,
    epg: EpgRepository,
    private val playlistId: Long,
    requestedContext: GroupSelection,
    private val startChannelId: Long?,
    private val startTime: Long? = null,
) : ViewModel() {

    /** The group shown. Falls back to All channels when the requested one is empty. */
    var context: GroupSelection = requestedContext
        private set

    private val _channels = MutableStateFlow<List<ChannelEntity>?>(null)
    val channels: StateFlow<List<ChannelEntity>?> = _channels.asStateFlow()

    val now: StateFlow<Long> = minuteTicker()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    private val _windowStart = MutableStateFlow(floorSlot(System.currentTimeMillis()))
    val windowStart: StateFlow<Long> = _windowStart.asStateFlow()

    private val _cursor = MutableStateFlow(GuideCursor(0, System.currentTimeMillis()))
    val cursor: StateFlow<GuideCursor> = _cursor.asStateFlow()

    /** Programmes overlapping the visible window, by epg key. A slot either side keeps edges filled. */
    val programs: StateFlow<Map<String, List<ProgramEntity>>> = _windowStart
        .flatMapLatest { start -> epg.range(playlistId, start - SLOT_MS, start + WINDOW_MS + SLOT_MS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch {
            var list = repo.channelsFor(playlistId, context)
            if (list.isEmpty() && context != GroupSelection.All) {
                // Reaching the Guide button from the group list passes over (and selects) Favorites,
                // which is often empty: show everything rather than a blank guide.
                context = GroupSelection.All
                list = repo.channelsFor(playlistId, context)
            }
            _channels.value = list
            val focusId = startChannelId ?: repo.channel(settings.lastChannel.first())?.id
            val row = list.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
            val time = startTime ?: System.currentTimeMillis()
            _windowStart.value = floorSlot(time)
            _cursor.value = GuideCursor(row, time)
        }
    }

    fun programAt(row: Int, time: Long): ProgramEntity? {
        val channel = _channels.value?.getOrNull(row) ?: return null
        return programs.value[channel.epgKey]?.firstOrNull { it.start <= time && time < it.stop }
    }

    fun focusedProgram(): ProgramEntity? = _cursor.value.let { programAt(it.row, it.time) }

    fun focusedChannel(): ChannelEntity? = _channels.value?.getOrNull(_cursor.value.row)

    fun moveRows(delta: Int) {
        val count = _channels.value?.size ?: return
        if (count == 0) return
        _cursor.value = _cursor.value.copy(row = (_cursor.value.row + delta).coerceIn(0, count - 1))
    }

    /** Right: to the start of the next programme (or the next half hour where there is no data). */
    fun moveRight() {
        val c = _cursor.value
        val target = programAt(c.row, c.time)?.stop ?: (floorSlot(c.time) + SLOT_MS)
        if (target > System.currentTimeMillis() + MAX_AHEAD_MS) return
        _cursor.value = c.copy(time = target)
        val windowEnd = _windowStart.value + WINDOW_MS
        if (target >= windowEnd) _windowStart.value = floorSlot(target) - SLOT_MS
    }

    /** Left: onto the previous programme, never further back than one slot at a time for long ones. */
    fun moveLeft() {
        val c = _cursor.value
        val edge = (programAt(c.row, c.time)?.start ?: floorSlot(c.time)) - 1
        val previous = programAt(c.row, edge)
        val target = maxOf(previous?.start ?: floorSlot(edge), floorSlot(edge) - SLOT_MS)
        if (target < floorSlot(System.currentTimeMillis() - MAX_BEHIND_MS)) return
        _cursor.value = c.copy(time = target)
        if (target < _windowStart.value) _windowStart.value = floorSlot(target)
    }

    /** Touch: select the programme airing at [time] on [row]. */
    fun setCursor(row: Int, time: Long) {
        val count = _channels.value?.size ?: return
        if (count == 0) return
        _cursor.value = GuideCursor(row.coerceIn(0, count - 1), time)
    }

    /** Jumps the cursor and window back to the current time. */
    fun jumpToNow() {
        val now = System.currentTimeMillis()
        _windowStart.value = floorSlot(now)
        _cursor.value = _cursor.value.copy(time = now)
    }

    companion object {
        val SLOT_MS = TimeUnit.MINUTES.toMillis(30)
        val WINDOW_MS = TimeUnit.HOURS.toMillis(2)
        private val MAX_AHEAD_MS = TimeUnit.DAYS.toMillis(7)
        private val MAX_BEHIND_MS = TimeUnit.HOURS.toMillis(2)

        fun floorSlot(time: Long): Long = time - Math.floorMod(time, SLOT_MS)
    }
}
