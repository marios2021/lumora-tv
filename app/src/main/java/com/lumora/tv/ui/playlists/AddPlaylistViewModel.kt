package com.lumora.tv.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.epg.EpgRepository
import com.lumora.tv.data.repo.PlaylistRepository
import com.lumora.tv.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddPlaylistUiState(
    val saving: Boolean = false,
    val error: String? = null,
    /** Set once the playlist was added or saved; the screen then closes. */
    val doneId: Long? = null,
)

class AddPlaylistViewModel(
    private val repo: PlaylistRepository,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
    private val editId: Long?,
) : ViewModel() {

    private val _state = MutableStateFlow(AddPlaylistUiState())
    val state: StateFlow<AddPlaylistUiState> = _state.asStateFlow()

    /** The playlist being edited; null when adding (or until it has loaded). */
    private val _original = MutableStateFlow<PlaylistEntity?>(null)
    val original: StateFlow<PlaylistEntity?> = _original.asStateFlow()

    init {
        if (editId != null) viewModelScope.launch { _original.value = repo.get(editId) }
    }

    fun submit(draft: PlaylistEntity) {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.value = AddPlaylistUiState(saving = true)
            _state.value = try {
                val original = _original.value
                val id = if (original != null) {
                    val reloadGuide = repo.update(draft.copy(id = original.id))
                    if (reloadGuide) epg.refreshInBackground(original.id)
                    original.id
                } else {
                    repo.add(draft).also {
                        settings.setActivePlaylist(it)
                        epg.refreshInBackground(it)
                    }
                }
                AddPlaylistUiState(doneId = id)
            } catch (e: Exception) {
                AddPlaylistUiState(error = friendlyError(e))
            }
        }
    }

    fun showError(message: String) {
        _state.value = AddPlaylistUiState(error = message)
    }
}
