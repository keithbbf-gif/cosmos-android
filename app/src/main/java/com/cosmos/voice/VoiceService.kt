package com.cosmos.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service for the microphone — the thing that makes driving mode
 * actually survive the screen turning off or another app coming to the front.
 *
 * The recognition loop itself (VoiceEngine/SpeechService, an AudioRecord on a
 * background thread) lives in this same process and is owned by MainActivity.
 * This service's ongoing notification with FOREGROUND_SERVICE_TYPE_MICROPHONE
 * is what (a) keeps the process from being killed/frozen in the background and
 * (b) entitles that loop to keep capturing audio while backgrounded — Android
 * 11+ silences background mic capture for apps with no mic-type foreground
 * service, which would make hands-free driving mode deaf the moment the phone
 * blanks or Maps comes up.
 *
 * Started when the mic turns on or driving mode starts (always from the
 * foreground, so the FGS-start and while-in-use mic rules are satisfied);
 * stopped when both are off. Swiping the app away still tears everything down
 * (MainActivity owns the engine) — that is the user saying stop.
 */
class VoiceService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Hands-free listening",
            NotificationManager.IMPORTANCE_LOW // silent, no heads-up — just present
        )
        ch.description = "Shown while COSMOS Voice is listening hands-free."
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("COSMOS Voice")
            .setContentText("Listening hands-free — tap to open.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cosmos_voice_mic"
        private const val NOTIF_ID = 1001

        /** Throws ForegroundServiceStartNotAllowedException (API 31+) if the
         *  app is backgrounded — callers catch and log; the mic keeps working
         *  while the activity is up regardless. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
