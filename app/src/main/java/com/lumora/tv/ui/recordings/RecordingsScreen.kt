package com.lumora.tv.ui.recordings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.data.db.RecordingEntity
import com.lumora.tv.data.db.RecordingStatus
import com.lumora.tv.ui.common.OutlinedButton
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout
import com.lumora.tv.ui.vod.VodRequest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun RecordingsScreen(onPlay: (VodRequest) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val recordings by remember { container.recordings.all.stateIn(scope, SharingStarted.Eagerly, null) }
        .collectAsStateWithLifecycle()
    val context = LocalContext.current

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(recordings != null) { if (recordings != null) firstFocus.requestFocusAfterLayout() }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
        Text("Recordings", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Saved on this device. Hold OK on a programme in the TV guide to record it, or hold OK in the player to record what's on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val list = recordings
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings yet.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier.widthIn(max = 1000.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 20.dp, bottom = 48.dp),
            ) {
                items(list, key = { it.id }) { r ->
                    RecordingRow(
                        r = r,
                        firstFocus = firstFocus.takeIf { r.id == list.first().id },
                        onPlay = {
                            val path = r.filePath ?: return@RecordingRow
                            onPlay(
                                VodRequest(
                                    url = Uri.fromFile(File(path)).toString(),
                                    title = r.title,
                                    subtitle = r.channelName + "  ·  " + when (r.programStart) {
                                        null -> DateUtils.formatDateTime(context, r.startAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                                        else -> DateUtils.formatDateTime(context, r.programStart, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                                    },
                                    playlistId = r.playlistId,
                                    contentKey = null,
                                    seriesId = null,
                                    startMs = 0,
                                ),
                            )
                        },
                        onStop = { scope.launch { container.recordings.stop(r.id) } },
                        onDelete = { scope.launch { container.recordings.delete(r.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    r: RecordingEntity,
    firstFocus: FocusRequester?,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.status == RecordingStatus.RECORDING) {
                    Box(Modifier.size(10.dp).background(colors.error, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val window = DateUtils.formatDateRange(
                context, r.startAt, r.endAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            )
            val size = if (r.bytes > 0) "  ·  " + Formatter.formatShortFileSize(context, r.bytes) else ""
            Text("${r.channelName}  ·  $window$size", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            val state = when (r.status) {
                RecordingStatus.SCHEDULED -> "Scheduled"
                RecordingStatus.RECORDING -> "Recording now"
                RecordingStatus.DONE -> r.error ?: "Recorded"
                RecordingStatus.FAILED -> "Failed: " + (r.error ?: "unknown error")
            }
            Text(
                state,
                style = MaterialTheme.typography.labelMedium,
                color = if (r.status == RecordingStatus.FAILED) colors.error else colors.primary,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val first = Modifier.then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            when (r.status) {
                RecordingStatus.DONE -> {
                    OutlinedButton(onClick = onPlay, modifier = first) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                    OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
                RecordingStatus.RECORDING -> OutlinedButton(onClick = onStop, modifier = first) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
                RecordingStatus.SCHEDULED -> OutlinedButton(onClick = onStop, modifier = first) { Text("Cancel") }
                RecordingStatus.FAILED -> OutlinedButton(onClick = onDelete, modifier = first) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Remove")
                }
            }
        }
    }
}

/**
 * Asks once for permission to show notifications (Android 13+ phones and tablets), so the
 * "Recording" notification is visible. Recording works either way.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context, launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}
