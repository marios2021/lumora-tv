package com.lumora.tv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.lumora.tv.data.db.VodEntity
import com.lumora.tv.data.db.VodKind
import com.lumora.tv.data.repo.VodStatus
import com.lumora.tv.ui.common.Button
import com.lumora.tv.ui.common.Card
import com.lumora.tv.ui.common.ListItem
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout

private const val COLUMNS = 5

/** Movies or series: shelves (categories) on the left, posters on the right. */
@Composable
fun CatalogScreen(playlistId: Long, kind: VodKind, onOpen: (VodEntity) -> Unit) {
    val container = appContainer()
    val vm: CatalogViewModel = viewModel(key = "catalog_${playlistId}_$kind") {
        CatalogViewModel(container.vod, playlistId, kind)
    }
    val groups by vm.groups.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val noun = if (kind == VodKind.MOVIE) "movies" else "series"

    val shelfFocus = remember { FocusRequester() }
    val gridEntryFocus = remember { FocusRequester() }
    // One scroll position per shelf, kept across a visit to a details page.
    val gridState = rememberSaveable(shelf, saver = LazyGridState.Saver) { LazyGridState() }
    val returnFocus = remember { FocusRequester() }
    val gridEntryIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val enterGrid = { runCatching { gridEntryFocus.requestFocus() }.isSuccess }
    val enterShelves = { runCatching { shelfFocus.requestFocus() }.isSuccess }

    // Coming back from a details page: focus the poster that was opened; otherwise the shelves.
    LaunchedEffect(Unit) {
        if (vm.openedItemId == null || !returnFocus.requestFocusAfterLayout()) shelfFocus.requestFocusAfterLayout()
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 20.dp, end = 20.dp, top = 24.dp),
        ) {
            Text(
                if (kind == VodKind.MOVIE) "Movies" else "Series",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && enterGrid() },
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (continueWatching.isNotEmpty()) {
                    item(key = "__continue") {
                        ShelfRow("Continue watching", continueWatching.size, shelf == Shelf.Continue, shelfFocus.takeIf { shelf == Shelf.Continue },
                            onFocus = { vm.selectAfterPause(Shelf.Continue) }, onClick = { if (shelf == Shelf.Continue) enterGrid() else vm.select(Shelf.Continue) })
                    }
                }
                item(key = "__all") {
                    ShelfRow("All $noun", groups.sumOf { it.count }, shelf == Shelf.All, shelfFocus.takeIf { shelf == Shelf.All },
                        onFocus = { vm.selectAfterPause(Shelf.All) }, onClick = { if (shelf == Shelf.All) enterGrid() else vm.select(Shelf.All) })
                }
                items(groups, key = { "g:" + it.name }) { g ->
                    val s = Shelf.Group(g.name)
                    ShelfRow(g.name, g.count, shelf == s, shelfFocus.takeIf { shelf == s },
                        onFocus = { vm.selectAfterPause(s) }, onClick = { if (shelf == s) enterGrid() else vm.select(s) })
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp),
        ) {
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() && status == VodStatus.Loading -> Message("Loading $noun…")
                list.isEmpty() && status is VodStatus.Failed -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Couldn't load $noun", style = MaterialTheme.typography.titleLarge)
                    Text((status as VodStatus.Failed).message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = vm::retry) { Text("Try again") }
                }
                list.isEmpty() -> Message("No $noun here.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent {
                            it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft &&
                                focusedIndex % COLUMNS == 0 && enterShelves()
                        },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                ) {
                    itemsIndexed(list, key = { _, it -> it.id }) { index, item ->
                        Poster(
                            item = item,
                            modifier = Modifier
                                .then(if (index == gridEntryIndex) Modifier.focusRequester(gridEntryFocus) else Modifier)
                                .then(if (item.id == vm.openedItemId) Modifier.focusRequester(returnFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) focusedIndex = index },
                            onClick = {
                                vm.openedItemId = item.id
                                onOpen(item)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShelfRow(
    title: String,
    count: Int,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.hasFocus) onFocus() },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(count.toString(), style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun Poster(item: VodEntity, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
                if (!item.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(item.year, item.rating?.let { "★ $it" }).joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
