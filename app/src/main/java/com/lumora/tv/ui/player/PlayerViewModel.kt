package com.lumora.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.RecordingEntity
import com.lumora.tv.data.db.RecordingStatus
import com.lumora.tv.data.recording.RecordingRepository
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.data.epg.EpgRepository
import com.lumora.tv.data.epg.epgKey
import com.lumora.tv.data.remote.XtreamClient
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.data.repo.LastChannel
import com.lumora.tv.data.repo.PlaylistRepository
import com.lumora.tv.data.repo.SettingsRepository
import com.lumora.tv.ui.common.minuteTicker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns what is tuned: zapping through the list the channel was opened from, jumping by channel
 * number, and flipping back to the previous channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val repo: PlaylistRepository,
    private val settings: SettingsRepository,
    epg: EpgRepository,
    private val recordings: RecordingRepository,
    channelId: Long,
    val context: GroupSelection,
) : ViewModel() {

    /** Recordings running or scheduled, to show REC on the tuned channel. */
    val activeRecordings: StateFlow<List<RecordingEntity>> = recordings.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _channel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = _channel.asStateFlow()

    /** The current time, refreshed every minute, for EPG progress. */
    val now: StateFlow<Long> = minuteTicker()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    /** What is on now (if anything) and what follows, for the tuned channel. */
    val upcoming: StateFlow<List<ProgramEntity>> = combine(_channel, now) { c, time -> c to time }
        .flatMapLatest { (c, time) -> if (c == null) flowOf(emptyList()) else epg.upcoming(c.playlistId, c.epgKey, time) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Digits typed so far for a channel number; empty when not entering a number. */
    private val _numberInput = MutableStateFlow("")
    val numberInput: StateFlow<String> = _numberInput.asStateFlow()

    /** A short notice such as "No channel 999"; cleared after a few seconds. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val tuning = Mutex()
    private var zapOrder: List<Long> = emptyList()
    /** Kept by key, not row id: a background playlist refresh gives every channel a new id. */
    private var previousChannel: LastChannel? = null
    private var numberJob: Job? = null
    private var noticeJob: Job? = null

    init {
        viewModelScope.launch {
            tuning.withLock {
                val start = repo.channel(channelId) ?: return@withLock
                zapOrder = repo.channelIds(start.playlistId, context)
                tune(start, rememberPrevious = false)
            }
        }
    }

    /** Starts recording the tuned channel until the current programme ends, or stops it. */
    fun toggleRecording() {
        val c = _channel.value ?: return
        val now = System.currentTimeMillis()
        val current = upcoming.value.firstOrNull { it.start <= now && now < it.stop }
        viewModelScope.launch { showNotice(recordings.toggleLive(c, playableUrl(c), current)) }
    }

    /** The URL to play: Xtream streams follow the stream format chosen in Settings. */
    fun playableUrl(channel: ChannelEntity): String =
        XtreamClient.playableUrl(channel.channelKey, channel.streamUrl, settings.current.value.xtreamFormat.extension)

    /**
     * +1 = Up / CH+, -1 = Down / CH-. Up moves to the next channel in the list unless Settings
     * inverts it. Wraps around at the ends.
     */
    fun zap(button: Int) {
        val step = if (settings.current.value.invertZapping) -button else button
        viewModelScope.launch {
            tuning.withLock {
                val current = _channel.value ?: return@withLock
                var currentId = current.id
                var index = zapOrder.indexOf(currentId)
                if (index < 0) {
                    // Either the playlist was refreshed (new row ids) or we tuned outside the list
                    // by number: look the channel up again and rebuild the order.
                    currentId = repo.channel(current.key())?.id ?: currentId
                    zapOrder = repo.channelIds(current.playlistId, context)
                    index = zapOrder.indexOf(currentId)
                    if (index < 0) {
                        zapOrder = repo.channelIds(current.playlistId, GroupSelection.All)
                        index = zapOrder.indexOf(currentId)
                    }
                }
                if (index < 0 || zapOrder.size < 2) return@withLock
                val nextId = zapOrder[(index + step).mod(zapOrder.size)]
                repo.channel(nextId)?.let { tune(it) }
            }
        }
    }

    /** Collects digits; tunes after a short pause, or straight away at 4 digits. */
    fun typeDigit(digit: Int) {
        val input = (_numberInput.value + digit).takeLast(4)
        _numberInput.value = input
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            if (input.length < 4) delay(NUMBER_ENTRY_TIMEOUT_MS)
            commitNumber()
        }
    }

    /** Ends number entry immediately (OK pressed while typing). */
    fun commitNumberNow() {
        numberJob?.cancel()
        numberJob = viewModelScope.launch { commitNumber() }
    }

    fun cancelNumber() {
        numberJob?.cancel()
        _numberInput.value = ""
    }

    /** Flips back to the channel watched before this one. */
    fun recallPrevious() {
        viewModelScope.launch {
            tuning.withLock {
                val previous = repo.channel(previousChannel)
                if (previous == null) showNotice("No previous channel yet") else tune(previous)
            }
        }
    }

    private suspend fun commitNumber() {
        val input = _numberInput.value
        _numberInput.value = ""
        val number = input.toIntOrNull() ?: return
        if (number == 0) {
            // A lone 0 is the classic "last channel" key on remotes without a dedicated button.
            recallPrevious()
            return
        }
        tuning.withLock {
            val current = _channel.value ?: return@withLock
            val target = repo.channelByNumber(current.playlistId, number)
            when {
                target == null -> showNotice("No channel $number")
                target.id != current.id -> tune(target)
            }
        }
    }

    private suspend fun tune(target: ChannelEntity, rememberPrevious: Boolean = true) {
        val current = _channel.value
        if (rememberPrevious && current != null && current.channelKey != target.channelKey) previousChannel = current.key()
        _channel.value = target
        settings.setLastChannel(target)
        // Recording the channel being watched shares one connection; another channel needs a second.
        val elsewhere = recordings.active.first().firstOrNull {
            it.status == RecordingStatus.RECORDING && it.playlistId == target.playlistId && it.channelKey != target.channelKey
        }
        if (elsewhere != null) {
            showNotice(
                "Recording “${elsewhere.channelName}”: if your account allows one connection, watch that channel until it ends",
                LONG_NOTICE_MS,
            )
        }
    }

    private fun ChannelEntity.key() = LastChannel(playlistId, channelKey)

    fun showNotice(text: String, durationMs: Long = NOTICE_MS) {
        _notice.value = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(durationMs)
            _notice.value = null
        }
    }

    private companion object {
        const val NUMBER_ENTRY_TIMEOUT_MS = 1_500L
        const val NOTICE_MS = 2_500L
        const val LONG_NOTICE_MS = 6_000L
    }
}
