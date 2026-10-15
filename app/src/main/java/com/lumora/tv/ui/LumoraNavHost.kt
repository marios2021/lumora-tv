package com.lumora.tv.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.guide.GuideScreen
import kotlinx.coroutines.flow.first
import com.lumora.tv.ui.home.HomeScreen
import com.lumora.tv.ui.player.PlayerScreen
import com.lumora.tv.ui.playlists.AddPlaylistScreen
import com.lumora.tv.ui.playlists.PlaylistsScreen
import androidx.compose.runtime.remember
import com.lumora.tv.data.db.VodKind
import com.lumora.tv.ui.search.SearchScreen
import com.lumora.tv.ui.vod.CatalogScreen
import com.lumora.tv.ui.vod.MovieDetailsScreen
import com.lumora.tv.ui.vod.SeriesDetailsScreen
import com.lumora.tv.ui.vod.VodPlayerScreen
import com.lumora.tv.ui.vod.VodRequest
import com.lumora.tv.ui.vod.decodeVodRequest
import com.lumora.tv.ui.vod.encode
import com.lumora.tv.ui.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val PLAYLISTS = "playlists"
    const val ADD_PLAYLIST = "add_playlist"
    const val EDIT_PLAYLIST = "edit_playlist/{playlistId}"
    fun editPlaylist(playlistId: Long) = "edit_playlist/$playlistId"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{channelId}?context={context}"
    fun player(channelId: Long, context: GroupSelection) = "player/$channelId?context=" + Uri.encode(context.encode())
    const val GUIDE = "guide/{playlistId}?context={context}&channel={channel}&time={time}"
    fun guide(playlistId: Long, context: GroupSelection, channelId: Long = -1, time: Long = -1) =
        "guide/$playlistId?context=" + Uri.encode(context.encode()) + "&channel=$channelId&time=$time"
    const val CATALOG = "catalog/{playlistId}/{kind}"
    fun catalog(playlistId: Long, kind: VodKind) = "catalog/$playlistId/${kind.name}"
    const val MOVIE = "movie/{playlistId}/{itemId}"
    fun movie(playlistId: Long, itemId: String) = "movie/$playlistId/" + Uri.encode(itemId)
    const val SERIES = "series/{playlistId}/{itemId}"
    fun series(playlistId: Long, itemId: String) = "series/$playlistId/" + Uri.encode(itemId)
    const val PLAY = "play?req={req}"
    fun play(request: VodRequest) = "play?req=" + Uri.encode(request.encode())
    const val SEARCH = "search/{playlistId}"
    fun search(playlistId: Long) = "search/$playlistId"
}

@Composable
fun LumoraNavHost() {
    val nav = rememberNavController()
    val container = appContainer()

    // "Start on last channel": once per app launch, go straight to the player.
    var startChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startChecked) return@LaunchedEffect
        startChecked = true
        if (!container.settings.settings.first().startOnLastChannel) return@LaunchedEffect
        val channel = container.playlists.channel(container.settings.lastChannel.first()) ?: return@LaunchedEffect
        if (container.playlists.isHidden(channel)) return@LaunchedEffect
        container.settings.setActivePlaylist(channel.playlistId)
        nav.navigate(Routes.player(channel.id, GroupSelection.All))
    }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onPlay = { id, context -> nav.navigate(Routes.player(id, context)) },
                onOpenPlaylists = { nav.navigate(Routes.PLAYLISTS) },
                onAddPlaylist = { nav.navigate(Routes.ADD_PLAYLIST) },
                onOpenGuide = { playlistId, context -> nav.navigate(Routes.guide(playlistId, context)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenSearch = { nav.navigate(Routes.search(it)) },
                onOpenCatalog = { playlistId, kind -> nav.navigate(Routes.catalog(playlistId, kind)) },
            )
        }
        composable(
            Routes.CATALOG,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("kind") { type = NavType.StringType },
            ),
        ) { entry ->
            val playlistId = entry.arguments?.getLong("playlistId") ?: 0L
            val kind = runCatching { VodKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(VodKind.MOVIE)
            CatalogScreen(playlistId, kind) { item ->
                nav.navigate(if (kind == VodKind.MOVIE) Routes.movie(playlistId, item.itemId) else Routes.series(playlistId, item.itemId))
            }
        }
        composable(
            Routes.MOVIE,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }, navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            MovieDetailsScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: 0L,
                itemId = entry.arguments?.getString("itemId").orEmpty(),
                onPlay = { nav.navigate(Routes.play(it)) },
            )
        }
        composable(
            Routes.SERIES,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }, navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            SeriesDetailsScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: 0L,
                itemId = entry.arguments?.getString("itemId").orEmpty(),
                onPlay = { nav.navigate(Routes.play(it)) },
            )
        }
        composable(
            Routes.PLAY,
            arguments = listOf(navArgument("req") { type = NavType.StringType }),
        ) { entry ->
            val request = remember(entry) { runCatching { decodeVodRequest(entry.arguments?.getString("req").orEmpty()) }.getOrNull() }
            if (request == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                VodPlayerScreen(request, onBack = { nav.popBackStack() })
            }
        }
        composable(
            Routes.SEARCH,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) { entry ->
            val playlistId = entry.arguments?.getLong("playlistId") ?: 0L
            SearchScreen(
                playlistId = playlistId,
                onPlay = { nav.navigate(Routes.player(it, GroupSelection.All)) },
                onOpenGuide = { channelId, time ->
                    nav.navigate(Routes.guide(playlistId, GroupSelection.All, channelId, time))
                },
                onOpenVod = { v ->
                    nav.navigate(if (v.kind == VodKind.MOVIE) Routes.movie(playlistId, v.itemId) else Routes.series(playlistId, v.itemId))
                },
            )
        }
        composable(
            Routes.GUIDE,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("context") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("channel") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("time") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val args = entry.arguments
            GuideScreen(
                playlistId = args?.getLong("playlistId") ?: 0L,
                context = GroupSelection.decode(args?.getString("context")),
                startChannelId = args?.getLong("channel")?.takeIf { it > 0 },
                startTime = args?.getLong("time")?.takeIf { it > 0 },
                // Guide -> player always leaves home -> player on the stack, so Back returns to the list.
                onPlay = { channelId, context ->
                    nav.navigate(Routes.player(channelId, context)) { popUpTo(Routes.HOME) }
                },
                // Catch-up returns to the guide afterwards.
                onPlayArchive = { nav.navigate(Routes.play(it)) },
            )
        }
        composable(Routes.PLAYLISTS) {
            PlaylistsScreen(
                onAddPlaylist = { nav.navigate(Routes.ADD_PLAYLIST) },
                onEditPlaylist = { nav.navigate(Routes.editPlaylist(it)) },
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.ADD_PLAYLIST) {
            AddPlaylistScreen(
                editId = null,
                onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(
            Routes.EDIT_PLAYLIST,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) { entry ->
            AddPlaylistScreen(
                editId = entry.arguments?.getLong("playlistId"),
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("channelId") { type = NavType.LongType },
                navArgument("context") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
        ) { entry ->
            PlayerScreen(
                channelId = entry.arguments?.getLong("channelId") ?: 0L,
                context = GroupSelection.decode(entry.arguments?.getString("context")),
                onOpenGuide = { playlistId, context, channelId ->
                    nav.navigate(Routes.guide(playlistId, context, channelId)) { popUpTo(Routes.HOME) }
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
