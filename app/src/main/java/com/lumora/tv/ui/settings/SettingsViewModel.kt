package com.lumora.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.repo.AppSettings
import com.lumora.tv.data.repo.AutoRefresher
import com.lumora.tv.data.repo.ParentalControl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.lumora.tv.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val autoRefresher: AutoRefresher,
    private val parental: ParentalControl,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repo.current
    val refreshing: StateFlow<Boolean> = autoRefresher.running

    private val _refreshMessage = MutableStateFlow<String?>(null)
    val refreshMessage: StateFlow<String?> = _refreshMessage.asStateFlow()

    val pinSet: StateFlow<Boolean> = parental.pinSet
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val unlocked: StateFlow<Boolean> = parental.unlocked

    suspend fun checkPin(pin: String) = parental.checkPin(pin)

    suspend fun unlock(pin: String) = parental.unlock(pin)

    suspend fun setPin(pin: String) = parental.setPin(pin)

    suspend fun removePin() = parental.removePin()

    fun lockAgain() = parental.lockAgain()

    fun toggleInvertZapping() = change { it.copy(invertZapping = !it.invertZapping) }

    fun toggleStartOnLastChannel() = change { it.copy(startOnLastChannel = !it.startOnLastChannel) }

    fun cycleXtreamFormat() = change { it.copy(xtreamFormat = it.xtreamFormat.next()) }

    fun cycleBufferProfile() = change { it.copy(bufferProfile = it.bufferProfile.next()) }

    fun cycleVideoOutput() = change { it.copy(videoOutput = it.videoOutput.next()) }

    fun cycleAutoRefresh() = change {
        val choices = AppSettings.AUTO_REFRESH_CHOICES
        it.copy(autoRefreshHours = choices[(choices.indexOf(it.autoRefreshHours) + 1).mod(choices.size)])
    }

    fun refreshAllNow() {
        if (refreshing.value) return
        viewModelScope.launch {
            _refreshMessage.value = "Refreshing playlists and TV guides…"
            _refreshMessage.value = autoRefresher.refreshAllNow()
        }
    }

    private fun change(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    private inline fun <reified E : Enum<E>> E.next(): E {
        val values = enumValues<E>()
        return values[(ordinal + 1) % values.size]
    }
}
