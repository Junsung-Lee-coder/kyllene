package dev.hermeskotlin.wear

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * Fork (requirements §5): the watch's wake word outside its app. A microphone foreground service, the only way Android
 * lets an app listen out of sight, started by [WatchWake.appShown] with the app on screen, never otherwise. It shows as
 * an ongoing activity on the watch face, with Stop; Stop, Stop listening in the app's settings, or the wake word turned
 * off on the phone end it, and the microphone with it.
 */
class WatchWakeService : Service() {
    private val wake get() = HeraldWatch.of(this).wake

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wake.stopService()
            return START_NOT_STICKY
        }
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.wear_wake_channel))
                .setShowBadge(false)
                .build(),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WatchWakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.wear_wake_listening))
            .setContentText(getString(R.string.wear_wake_notification_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_close, getString(R.string.wear_wake_stop), stop)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notification)
            .setStaticIcon(R.drawable.ic_mic)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(getString(R.string.wear_wake_listening)).build())
            .build()
            .apply(applicationContext)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android refuses a microphone service without the permission, or from the background.
            Log.w("WatchWakeService", "Couldn't start listening for the wake word", e)
            wake.serviceStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        wake.serviceStarted()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake.serviceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ACTION_STOP = "dev.hermeskotlin.wear.wake.STOP"
        const val CHANNEL = "wake"
        const val NOTIFICATION_ID = 4_005
    }
}
