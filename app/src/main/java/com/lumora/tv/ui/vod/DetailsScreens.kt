package com.lumora.tv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.lumora.tv.data.remote.Episode
import com.lumora.tv.data.repo.VodRepository
import com.lumora.tv.ui.common.Button
import com.lumora.tv.ui.common.ListItem
import com.lumora.tv.ui.common.OutlinedButton
import com.lumora.tv.ui.common.ProgressLine
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout

@Composable
fun MovieDetailsScreen(playlistId: Long, itemId: String, onPlay: (VodRequest) -> Unit) {
    val container = appContainer()
    val vm: MovieDetailsViewModel = viewModel { MovieDetailsViewModel(container.vod, container.playlists, playlistId, itemId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()

    when (val s = state) {
        DetailsState.Loading -> Box(Modifier.fillMaxSize())
        DetailsState.Missing -> Centered("This movie is no longer in the playlist.")
        is DetailsState.Ready -> {
            val info = s.info
            val playFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { playFocus.requestFocusAfterLayout() }
            Backdrop(info?.backdrop ?: info?.poster ?: s.item.poster) {
                Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 48.dp)) {
                    PosterImage(info?.poster ?: s.item.poster, s.item.name, Modifier.width(220.dp))
                    Spacer(Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info?.name ?: s.item.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        val meta = listOfNotNull(
                            info?.releaseDate?.take(4) ?: s.item.year,
                            info?.durationSecs?.let { formatTime(it * 1000L) },
                            (info?.rating ?: s.item.rating)?.let { "★ $it" },
                            info?.genre,
                        ).joinToString("   ·   ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val resumeAt = progress?.positionMs
                            Button(
                                onClick = { vm.request(fromStart = false)?.let(onPlay) },
                                modifier = Modifier.focusRequester(playFocus),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (resumeAt != null) "Resume from ${formatTime(resumeAt)}" else "Play")
                            }
                            if (resumeAt != null) {
                                OutlinedButton(onClick = { vm.request(fromStart = true)?.let(onPlay) }) {
                                    Icon(Icons.Filled.Replay, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Play from start")
                                }
                            }
                        }
                        progress?.let {
                            Spacer(Modifier.height(12.dp))
                            ProgressLine(it.fraction, Modifier.width(320.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        info?.plot?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(16.dp))
                        }
                        info?.director?.let { Credit("Director", it) }
                        info?.cast?.let { Credit("Cast", it) }
                        s.error?.let {
                            Text("Details unavailable: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailsScreen(playlistId: Long, itemId: String, onPlay: (VodRequest) -> Unit) {
    val container = appContainer()
    val vm: SeriesDetailsViewModel = viewModel { SeriesDetailsViewModel(container.vod, container.playlists, playlistId, itemId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()

    when (val s = state) {
        DetailsState.Loading -> Centered("Loading episodes…")
        DetailsState.Missing -> Centered("This series is no longer in the playlist.")
        is DetailsState.Ready -> Backdrop(s.info?.backdrop ?: s.info?.cover ?: s.item.poster) {
            val info = s.info
            Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 40.dp)) {
                Row {
                    PosterImage(info?.cover ?: s.item.poster, s.item.name, Modifier.width(140.dp))
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info?.name ?: s.item.name, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            info?.releaseDate?.take(4) ?: s.item.year,
                            info?.seasons?.size?.let { if (it == 1) "1 season" else "$it seasons" },
                            (info?.rating ?: s.item.rating)?.let { "★ $it" },
                            info?.genre,
                        ).joinToString("   ·   ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        info?.plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        s.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (info != null && info.seasons.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Episodes(info.seasons, season, progress, onSeason = vm::selectSeason, onPlay = { e -> vm.request(e)?.let(onPlay) }, start = vm.lastWatched(info))
                } else if (info != null) {
                    Spacer(Modifier.height(20.dp))
                    Text("No episodes yet.", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Episodes(
    seasons: List<Pair<Int, List<Episode>>>,
    selected: Int?,
    progress: Map<String, com.lumora.tv.data.db.ProgressEntity>,
    onSeason: (Int) -> Unit,
    onPlay: (Episode) -> Unit,
    start: Episode?,
) {
    val episodes = seasons.firstOrNull { it.first == selected }?.second ?: seasons.first().second
    val startFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        startFocus.requestFocusAfterLayout()
        focusedOnce = true
    }
    if (seasons.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(seasons, key = { it.first }) { (number, _) ->
                val isSelected = number == selected
                Button(
                    onClick = { onSeason(number) },
                    colors = if (isSelected) {
                        ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        ButtonDefaults.colors()
                    },
                ) { Text("Season $number") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    val startId = (start ?: episodes.firstOrNull())?.id
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(episodes, key = { it.id }) { e ->
            val p = progress[VodRepository.episodeKey(e.id)]
            ListItem(
                selected = false,
                onClick = { onPlay(e) },
                modifier = if (e.id == startId && !focusedOnce) Modifier.focusRequester(startFocus) else Modifier,
                headlineContent = { Text("${e.number}.  ${e.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        val line = listOfNotNull(e.durationSecs?.let { formatTime(it * 1000L) }, e.plot).joinToString("  ·  ")
                        if (line.isNotEmpty()) Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p != null) {
                            Spacer(Modifier.height(4.dp))
                            ProgressLine(p.fraction, Modifier.width(240.dp))
                        }
                    }
                },
                leadingContent = {
                    Box(
                        Modifier
                            .width(128.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        if (!e.image.isNullOrBlank()) {
                            AsyncImage(model = e.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun Credit(label: String, text: String) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Text("$label  ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PosterImage(url: String?, name: String, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A dimmed backdrop image behind the page. */
@Composable
private fun Backdrop(url: String?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background.copy(alpha = 0.85f), Color(0x99000000)),
                    ),
                ),
        )
        content()
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
