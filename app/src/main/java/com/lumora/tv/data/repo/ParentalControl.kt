package com.lumora.tv.data.repo

import com.lumora.tv.data.db.LockedGroupDao
import com.lumora.tv.data.db.LockedGroupEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Parental lock. Groups can be locked with a 4-digit PIN; while locked, their channels are hidden
 * everywhere (lists, guide, zapping, channel numbers, search). Entering the PIN unlocks them for
 * the rest of the session (until the app is closed or "Lock again" is chosen).
 */
class ParentalControl(private val dao: LockedGroupDao, private val settings: SettingsRepository) {

    private val _unlocked = MutableStateFlow(false)

    /** True once the PIN has been entered this session. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    val pinSet: Flow<Boolean> = settings.pinSet

    fun lockedGroups(playlistId: Long): Flow<Set<String>> = dao.observe(playlistId).map { it.toSet() }

    /** Groups whose channels are hidden right now. Empty when unlocked, or when no PIN is set. */
    fun hiddenGroups(playlistId: Long): Flow<List<String>> =
        combine(dao.observe(playlistId), settings.pinSet, _unlocked) { groups, pinSet, unlocked ->
            if (!pinSet || unlocked) emptyList() else groups
        }.distinctUntilChanged()

    suspend fun hidden(playlistId: Long): List<String> = hiddenGroups(playlistId).first()

    /** Checks [pin]; if right, unlocks for the session. */
    suspend fun unlock(pin: String): Boolean = settings.checkPin(pin).also { if (it) _unlocked.value = true }

    suspend fun checkPin(pin: String): Boolean = settings.checkPin(pin)

    fun lockAgain() {
        _unlocked.value = false
    }

    suspend fun setPin(pin: String) = settings.setPin(pin)

    /** Removing the PIN also removes every lock: without a PIN nothing could unlock them. */
    suspend fun removePin() {
        settings.clearPin()
        dao.clear()
        _unlocked.value = false
    }

    suspend fun setLocked(playlistId: Long, group: String, locked: Boolean) {
        if (locked) dao.add(LockedGroupEntity(playlistId, group)) else dao.remove(playlistId, group)
    }
}
