package com.lumora.tv.ui.guidepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.GuideChannelEntity
import com.lumora.tv.data.epg.EpgRepository
import com.lumora.tv.data.epg.EpgStatus
import com.lumora.tv.data.epg.GuideChannelSearch
import com.lumora.tv.data.epg.GuideSource
import com.lumora.tv.data.epg.GuideSources
import com.lumora.tv.ui.common.FormField
import com.lumora.tv.ui.common.ListItem
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the picker lists: [suggested] only while the search box is empty. */
data class GuidePickerState(
    val total: Int,
    val suggested: List<GuideChannelEntity>,
    val results: List<GuideChannelEntity>,
    /** The playlist's guide refresh: in progress, failed, or null when idle. */
    val status: EpgStatus?,
) {
    val updating: Boolean get() = status == EpgStatus.Updating
}

class GuidePickerViewModel(private val epg: EpgRepository, val channel: ChannelEntity) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val search = epg.guideChannels(channel.playlistId).map { GuideChannelSearch(it) }

    val state: StateFlow<GuidePickerState?> =
        combine(search, _query, epg.status.map { it[channel.playlistId] }) { s, q, status ->
            GuidePickerState(
                total = s.size,
                suggested = if (q.isBlank()) s.suggestionsFor(channel.name) else emptyList(),
                results = s.search(q),
                status = status,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The playlist's EPG URLs, to mark the free guides already added. */
    val guideUrls: StateFlow<List<String>> = epg.guideUrls(channel.playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Guides refreshed before this version kept no channel list: load the guide once to get one.
        viewModelScope.launch {
            if (epg.guideChannels(channel.playlistId).first().isEmpty()) epg.refreshInBackground(channel.playlistId)
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    /** [guideChannelId]: a guide channel's id, "" for no guide, or null to match automatically. */
    fun choose(guideChannelId: String?) = epg.setGuideChannel(channel, guideChannelId)

    /** Adds a free guide to the playlist's EPG URLs, or removes it; the guide then reloads. */
    fun setSource(source: GuideSource, added: Boolean) = epg.setGuideSource(channel.playlistId, source.url, added)
}

/**
 * Picks the TV guide channel for [channel] by hand, like TiviMate's EPG mapping: a searchable list
 * of the guide's channels, plus Automatic (match by tvg-id or name) and None.
 */
@Composable
fun GuideChannelPicker(channel: ChannelEntity, onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    val container = appContainer()
    val vm: GuidePickerViewModel = viewModel(key = "guide-picker-${channel.id}") {
        GuidePickerViewModel(container.epg, channel)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val guideUrls by vm.guideUrls.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRow.requestFocusAfterLayout() }

    val current = channel.guideOverride
    fun pick(id: String?, message: String) {
        vm.choose(id)
        onChosen(message)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Read inside the dialog: it is a window of its own, with its own focus.
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .width(600.dp)
                .height(480.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text("TV guide for “${channel.name}”", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "The playlist tags it “${channel.tvgId?.takeIf { it.isNotBlank() } ?: "no guide id"}”. " +
                    "Pick the guide channel whose programmes it should show.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val status = state?.status) {
                EpgStatus.Updating -> Text(
                    "Loading the TV guide… new channels appear when it's done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is EpgStatus.Failed -> Text(
                    "Couldn't load the TV guide: ${status.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                null -> Unit
            }
            Spacer(Modifier.height(10.dp))
            FormField(
                label = "Search the guide's channels",
                value = query,
                onValueChange = vm::setQuery,
                imeAction = ImeAction.Search,
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
            )
            Spacer(Modifier.height(8.dp))
            val s = state
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                item(key = "__auto") {
                    PickRow(
                        title = "Automatic",
                        detail = "Match by the playlist's guide id or the channel name",
                        selected = current == null,
                        modifier = Modifier.focusRequester(firstRow),
                        onClick = { pick(null, "TV guide: automatic. Updating the guide…") },
                    )
                }
                item(key = "__none") {
                    PickRow(
                        title = "None",
                        detail = "Show no programmes for this channel",
                        selected = current == "",
                        onClick = { pick("", "No TV guide for “${channel.name}”") },
                    )
                }
                item(key = "__sources") {
                    ListItem(
                        selected = false,
                        onClick = { showSources = true },
                        leadingContent = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        headlineContent = { Text("Add a free TV guide", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("Find channels your guide lacks, such as epgshare01 UK", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
                when {
                    s == null -> Unit
                    s.total == 0 -> item(key = "__empty") {
                        Note(
                            if (s.updating) {
                                "Loading the TV guide…"
                            } else {
                                "No guide channels yet. Add a free TV guide above, or an EPG URL to the playlist."
                            },
                        )
                    }
                    else -> {
                        if (s.suggested.isNotEmpty()) {
                            item(key = "__suggested") { Header("Suggested") }
                            items(s.suggested, key = { "s:" + it.guideChannelId }) { g ->
                                GuideRow(g, current) { pick(g.guideChannelId, "TV guide: ${label(g)}. Updating the guide…") }
                            }
                        }
                        item(key = "__results") {
                            Header(if (query.isBlank()) "All guide channels (${s.total})" else "Results (${s.results.size})")
                        }
                        items(s.results, key = { "r:" + it.guideChannelId }) { g ->
                            GuideRow(g, current) { pick(g.guideChannelId, "TV guide: ${label(g)}. Updating the guide…") }
                        }
                    }
                }
            }
        }
        if (showSources) {
            GuideSourcesDialog(
                addedUrls = guideUrls,
                onDismiss = { showSources = false },
                onSelect = { source, added ->
                    vm.setSource(source, added)
                    showSources = false
                },
            )
        }
    }
}

/** Free guides to add to the playlist in one tap; selecting one already added removes it. */
@Composable
private fun GuideSourcesDialog(
    addedUrls: List<String>,
    onDismiss: () -> Unit,
    onSelect: (GuideSource, added: Boolean) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusAfterLayout() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Add a free TV guide", style = MaterialTheme.typography.titleLarge)
            Text(
                "It is added to the playlist's EPG URLs and loaded with your guide; its channels then show " +
                    "in this list. Community guides can change or stop without notice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            GuideSources.all.forEachIndexed { index, source ->
                val added = source.url in addedUrls
                ListItem(
                    selected = added,
                    onClick = { onSelect(source, !added) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    headlineContent = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(if (added) "Added · select to remove" else source.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = if (added) {
                        { Icon(Icons.Filled.Check, contentDescription = "Added", modifier = Modifier.size(20.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun label(g: GuideChannelEntity): String = g.displayNames.firstOrNull() ?: g.guideChannelId

@Composable
private fun GuideRow(g: GuideChannelEntity, current: String?, onClick: () -> Unit) {
    val others = g.displayNames.drop(1)
    PickRow(
        title = label(g),
        detail = (listOf(g.guideChannelId) + others).joinToString("  ·  "),
        selected = current != null && current.equals(g.guideChannelId, ignoreCase = true),
        onClick = onClick,
    )
}

@Composable
private fun PickRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = "Current", modifier = Modifier.size(20.dp)) }
        } else {
            null
        },
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(12.dp),
    )
}
