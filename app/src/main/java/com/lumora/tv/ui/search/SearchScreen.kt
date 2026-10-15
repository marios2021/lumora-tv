package com.lumora.tv.ui.search

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.ui.common.ChannelLogo
import com.lumora.tv.ui.common.FormField
import com.lumora.tv.ui.common.ListItem
import com.lumora.tv.ui.common.LocalIsTv
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.rememberTimeFormat
import com.lumora.tv.ui.common.requestFocusAfterLayout
import com.lumora.tv.ui.common.span

@Composable
fun SearchScreen(
    playlistId: Long,
    onPlay: (channelId: Long) -> Unit,
    /** A future programme: show it in the guide. */
    onOpenGuide: (channelId: Long, time: Long) -> Unit,
) {
    val container = appContainer()
    val vm: SearchViewModel = viewModel { SearchViewModel(container.playlists, container.epg, playlistId) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val isTv = LocalIsTv.current

    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocusAfterLayout() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp),
    ) {
        Text("Search", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        FormField(
            label = "Channel or programme",
            value = query,
            onValueChange = vm::setQuery,
            imeAction = ImeAction.Search,
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }),
            editOnFocus = true,
            modifier = Modifier
                .width(720.dp)
                .focusRequester(fieldFocus),
        )
        Spacer(Modifier.height(16.dp))

        val r = results
        val trimmed = query.trim()
        when {
            trimmed.length < MIN_QUERY -> Hint(
                if (isTv) {
                    "Type at least $MIN_QUERY letters. Press ▼ to move to the results."
                } else {
                    "Type at least $MIN_QUERY letters."
                },
            )
            r == null || r.query != trimmed -> Unit
            r.isEmpty -> Hint("Nothing found for “$trimmed”.")
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                if (r.channels.isNotEmpty()) {
                    item { Heading("Channels", r.channels.size) }
                    items(r.channels, key = { "c" + it.id }) { channel ->
                        ChannelResult(channel) { onPlay(channel.id) }
                    }
                }
                if (r.programs.isNotEmpty()) {
                    item { Heading("Programmes", r.programs.size) }
                    items(r.programs, key = { "p" + it.program.id + "_" + it.channel.id }) { hit ->
                        ProgramResult(hit) {
                            val p = hit.program
                            if (p.start <= System.currentTimeMillis()) {
                                onPlay(hit.channel.id)
                            } else {
                                onOpenGuide(hit.channel.id, p.start)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Heading(title: String, count: Int) {
    Row(
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChannelResult(channel: ChannelEntity, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = Modifier.width(860.dp),
        headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(channel.groupTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.number.toString(),
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(16.dp))
                ChannelLogo(channel.logo, channel.name, size = 44.dp)
            }
        },
    )
}

@Composable
private fun ProgramResult(hit: ProgramHit, onClick: () -> Unit) {
    val context = LocalContext.current
    val timeFormat = rememberTimeFormat()
    val p = hit.program
    val now = System.currentTimeMillis()
    val onNow = p.start <= now
    val day = when {
        DateUtils.isToday(p.start) -> "Today"
        DateUtils.isToday(p.start - DateUtils.DAY_IN_MILLIS) -> "Tomorrow"
        else -> DateUtils.formatDateTime(context, p.start, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
    }
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = Modifier.width(860.dp),
        headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "$day  ${timeFormat.span(p)}   ·   ${hit.channel.name}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { ChannelLogo(hit.channel.logo, hit.channel.name, size = 44.dp) },
        trailingContent = {
            if (onNow) {
                Text(
                    "ON NOW",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        },
    )
}
