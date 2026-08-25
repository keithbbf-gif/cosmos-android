package com.cosmos.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Foreground service for the microphone / TTS session.
 *
 * Runs ONLY while the app is actually capturing audio or speaking — it is
 * started on those transitions and stopped the moment both end (see
 * MainActivity.updateMicService). It never keeps the mic alive past STOP:
 * the engine is owned by MainActivity, and this service holds no audio
 * resources of its own.
 *
 * The ongoing notification shows the CURRENT STATE and carries a STOP action
 * that routes to the same authoritative STOP as the big red button — from the
 * lock screen / notification shade, one tap kills everything.
 *
 * START_NOT_STICKY on purpose: if Android kills the process, NOTHING restarts
 * — a resurrected service must never resurrect a microphone (the mic
 * never auto-starts, on any path).
 */
class VoiceService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Authoritative STOP from the notification. Runs on the main
            // thread, same as the button path.
            onStopAction?.invoke()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val stateText = intent?.getStringExtra(EXTRA_STATE) ?: "Active"
        val notif = buildNotification(stateText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Voice session",
            NotificationManager.IMPORTANCE_LOW // silent, no heads-up — just present
        )
        ch.description = "Shown while COSMOS Voice is listening or speaking."
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(stateText: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("COSMOS Voice")
            .setContentText(stateText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "STOP",
                    stop
                ).build()
            )
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.cosmos.voice.action.STOP"
        private const val EXTRA_STATE = "state_text"
        private const val CHANNEL_ID = "cosmos_voice_mic"
        private const val NOTIF_ID = 1001

        /** Set by MainActivity to route the notification STOP action into the
         *  same authoritative performStop() as the red button. Cleared in
         *  onDestroy; a null listener means there is nothing running to stop. */
        @Volatile var onStopAction: (() -> Unit)? = null

        /** Throws ForegroundServiceStartNotAllowedException (API 31+) if the
         *  app is backgrounded — callers catch and log; the mic keeps working
         *  while the activity is up regardless. Re-calling with a new
         *  [stateText] just updates the notification. */
        fun start(context: Context, stateText: String) {
            context.startForegroundService(
                Intent(context, VoiceService::class.java).putExtra(EXTRA_STATE, stateText)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
