package com.lumora.tv.data.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.LumoraDatabase
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.data.db.RecordingEntity
import com.lumora.tv.data.db.RecordingStatus
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Scheduled, running and finished recordings, and the alarms that start them. */
class RecordingRepository(private val context: Context, db: LumoraDatabase) {
    private val dao = db.recordingDao()
    private val alarms = context.getSystemService(AlarmManager::class.java)

    val all: Flow<List<RecordingEntity>> = dao.observeAll()

    /** Scheduled or running recordings, to mark them in the guide and the player. */
    val active: Flow<List<RecordingEntity>> = dao.observeActive()

    /** Records [channel] now, until [until] (the end of the current programme, or an hour). */
    suspend fun recordNow(channel: ChannelEntity, url: String, title: String, until: Long, programStart: Long?): Long {
        val id = dao.insert(
            RecordingEntity(
                playlistId = channel.playlistId,
                channelKey = channel.channelKey,
                channelName = channel.name,
                title = title,
                url = url,
                programStart = programStart,
                startAt = System.currentTimeMillis(),
                endAt = until,
                status = RecordingStatus.SCHEDULED,
                createdAt = System.currentTimeMillis(),
            ),
        )
        RecordingService.start(context, id)
        return id
    }

    /** Schedules [program], from a minute before it starts to two minutes after it ends. */
    suspend fun schedule(channel: ChannelEntity, url: String, program: ProgramEntity): Long {
        val id = dao.insert(
            RecordingEntity(
                playlistId = channel.playlistId,
                channelKey = channel.channelKey,
                channelName = channel.name,
                title = program.title,
                url = url,
                programStart = program.start,
                startAt = program.start - PADDING_BEFORE_MS,
                endAt = program.stop + PADDING_AFTER_MS,
                status = RecordingStatus.SCHEDULED,
                createdAt = System.currentTimeMillis(),
            ),
        )
        arm(id, program.start - PADDING_BEFORE_MS)
        return id
    }

    suspend fun get(id: Long): RecordingEntity? = dao.get(id)

    /**
     * Record button in the player: starts recording [channel] until the end of [current] (or for
     * an hour without guide data), or stops the recording already running on it.
     */
    suspend fun toggleLive(channel: ChannelEntity, url: String, current: ProgramEntity?): String {
        val now = System.currentTimeMillis()
        val running = dao.withStatus(RecordingStatus.RECORDING).firstOrNull { it.channelKey == channel.channelKey }
        if (running != null) {
            stop(running.id)
            return "Recording stopped"
        }
        val until = (current?.stop ?: (now + TimeUnit.HOURS.toMillis(1))) + PADDING_AFTER_MS
        recordNow(channel, url, current?.title ?: channel.name, until, current?.start)
        return if (current != null) "Recording “${current.title}”" else "Recording for an hour"
    }

    /**
     * Hold OK on a programme in the guide: records it (now if it's on, otherwise when it starts),
     * or cancels it if it's already set to record.
     */
    suspend fun toggleProgram(channel: ChannelEntity, url: String, program: ProgramEntity): String {
        val now = System.currentTimeMillis()
        if (program.stop <= now) return "That programme has ended"
        val existing = dao.observeActiveOnce().firstOrNull { it.channelKey == channel.channelKey && it.programStart == program.start }
        if (existing != null) {
            stop(existing.id)
            return if (existing.status == RecordingStatus.RECORDING) "Recording stopped" else "Recording cancelled"
        }
        return if (program.start <= now + PADDING_BEFORE_MS) {
            recordNow(channel, url, program.title, program.stop + PADDING_AFTER_MS, program.start)
            "Recording “${program.title}”"
        } else {
            schedule(channel, url, program)
            "“${program.title}” will be recorded"
        }
    }

    /** Stops a running recording (keeping what was saved) or cancels a scheduled one. */
    suspend fun stop(id: Long) {
        val r = dao.get(id) ?: return
        when (r.status) {
            RecordingStatus.RECORDING -> RecordingService.stop(context, id)
            RecordingStatus.SCHEDULED -> {
                disarm(id)
                dao.delete(id)
            }
            else -> Unit
        }
    }

    suspend fun delete(id: Long) {
        val r = dao.get(id) ?: return
        if (r.status == RecordingStatus.RECORDING) RecordingService.stop(context, id)
        disarm(id)
        r.filePath?.let { File(it).delete() }
        dao.delete(id)
    }

    /**
     * After a reboot or an app update, alarms are gone: set them again. Recordings that should
     * have started already still start if their end is ahead; ones cut off by a restart are closed.
     */
    suspend fun restore(serviceRunning: Boolean) {
        val now = System.currentTimeMillis()
        for (r in dao.withStatus(RecordingStatus.SCHEDULED)) {
            when {
                r.endAt <= now -> dao.update(r.copy(status = RecordingStatus.FAILED, error = "Missed: the device was off or asleep."))
                r.startAt <= now -> RecordingService.start(context, r.id)
                else -> arm(r.id, r.startAt)
            }
        }
        if (!serviceRunning) {
            for (r in dao.withStatus(RecordingStatus.RECORDING)) {
                val saved = r.filePath?.let { File(it).length() } ?: 0
                dao.update(
                    if (saved > 0) {
                        r.copy(status = RecordingStatus.DONE, bytes = saved, error = "Stopped early: the app was closed.")
                    } else {
                        r.copy(status = RecordingStatus.FAILED, error = "Interrupted.")
                    },
                )
            }
        }
    }

    private fun arm(id: Long, at: Long) {
        val pending = alarmIntent(id)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            // Without the exact-alarm permission Android may run it a little late; the padding covers that.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun disarm(id: Long) = alarms.cancel(alarmIntent(id))

    private fun alarmIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, RecordingAlarmReceiver::class.java).putExtra(RecordingService.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Where recordings are saved: app storage, so no storage permission is needed. */
    fun fileFor(r: RecordingEntity): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(r.programStart ?: r.startAt))
        val name = "$stamp ${r.channelName} - ${r.title}".replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
        return File(dir, "$name (${r.id}).ts")
    }

    companion object {
        val PADDING_BEFORE_MS = TimeUnit.MINUTES.toMillis(1)
        val PADDING_AFTER_MS = TimeUnit.MINUTES.toMillis(2)

        fun startService(context: Context, intent: Intent) = ContextCompat.startForegroundService(context, intent)
    }
}
