package com.lumora.tv.data.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lumora.tv.LumoraApp
import com.lumora.tv.MainActivity
import com.lumora.tv.R
import com.lumora.tv.data.db.RecordingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Runs recordings in the foreground, so they continue while the app is in the background. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val titles = ConcurrentHashMap<Long, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires the notification straight away, whatever the command.
        goForeground()
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_START -> if (id >= 0 && !jobs.containsKey(id)) jobs[id] = scope.launch { run(id) }
            ACTION_STOP -> jobs[id]?.cancel()
        }
        if (jobs.isEmpty()) finish()
        return START_NOT_STICKY
    }

    private suspend fun run(id: Long) {
        val container = (application as LumoraApp).container
        val repo = container.recordings
        val dao = container.database.recordingDao()
        val r = dao.get(id)
        if (r == null || r.status != RecordingStatus.SCHEDULED || r.endAt <= System.currentTimeMillis()) {
            jobs.remove(id)
            if (jobs.isEmpty()) finish()
            return
        }
        val file = repo.fileFor(r)
        var current = r.copy(status = RecordingStatus.RECORDING, filePath = file.absolutePath, startedAt = System.currentTimeMillis())
        dao.update(current)
        titles[id] = r.title
        goForeground()
        var lastSaved = 0L
        try {
            file.outputStream().buffered().use { out ->
                Recorder(container.http).record(r.url, out, r.endAt) { bytes ->
                    // Save the size now and then so the Recordings screen shows progress.
                    if (bytes - lastSaved > 2_000_000) {
                        lastSaved = bytes
                        scope.launch { dao.updateBytes(id, bytes) }
                    }
                }
            }
            current = current.copy(status = RecordingStatus.DONE, bytes = file.length())
        } catch (e: CancellationException) {
            current = current.copy(status = RecordingStatus.DONE, bytes = file.length(), error = "Stopped early.")
        } catch (e: Exception) {
            val saved = file.length()
            current = if (saved > 0) {
                current.copy(status = RecordingStatus.DONE, bytes = saved, error = "Stopped early: ${e.message}")
            } else {
                file.delete()
                current.copy(status = RecordingStatus.FAILED, filePath = null, error = e.message ?: "Recording failed.")
            }
        } finally {
            withContext(NonCancellable) {
                if (current.status == RecordingStatus.DONE && file.length() == 0L) {
                    file.delete()
                    current = current.copy(status = RecordingStatus.FAILED, filePath = null, error = current.error ?: "Nothing was received.")
                }
                dao.update(current)
                titles.remove(id)
                jobs.remove(id)
                if (jobs.isEmpty()) finish() else goForeground()
            }
        }
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val names = titles.values.toList()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle(if (names.size <= 1) "Recording" else "Recording ${names.size} programmes")
            .setContentText(names.joinToString(", ").ifEmpty { "Starting…" })
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "recording_id"
        private const val ACTION_START = "com.lumora.tv.record.START"
        private const val ACTION_STOP = "com.lumora.tv.record.STOP"
        private const val CHANNEL = "recordings"
        private const val NOTIFICATION_ID = 42

        /** True while the service exists in this process. */
        @Volatile
        var running = false
            private set

        fun start(context: Context, id: Long) = RecordingRepository.startService(
            context,
            Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id),
        )

        fun stop(context: Context, id: Long) = RecordingRepository.startService(
            context,
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id),
        )
    }
}

/** Fires when a scheduled recording is due. */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(RecordingService.EXTRA_ID, -1)
        if (id < 0) return
        try {
            RecordingService.start(context, id)
        } catch (e: IllegalStateException) {
            // Android 12+ can refuse to start a foreground service from the background (inexact alarm).
            val pending = goAsync()
            val dao = (context.applicationContext as LumoraApp).container.database.recordingDao()
            CoroutineScope(Dispatchers.IO).launch {
                dao.get(id)?.let {
                    dao.update(it.copy(status = RecordingStatus.FAILED, error = "Android didn't allow the recording to start. Allow \"Alarms & reminders\" for Lumora."))
                }
                pending.finish()
            }
        }
    }
}

/** After a reboot or an app update, scheduled recordings need their alarms again. */
class RecordingRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val repo = (context.applicationContext as LumoraApp).container.recordings
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repo.restore(serviceRunning = RecordingService.running) }
            pending.finish()
        }
    }
}
