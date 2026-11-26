package dev.hermeskotlin.android.wake

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.hermeskotlin.android.notify.ChatNotifications
import org.koin.android.ext.android.inject

/**
 * Fork (requirements §5): the phone's wake word out of the app. A microphone foreground service, the only way Android
 * lets an app listen out of sight, started from Settings › Start listening with the app on screen (Android refuses it
 * from the background, and nothing here tries): never at boot, never by itself. Its notification's Stop, Stop in
 * Settings, or the wake word turned off end it, and the microphone with it.
 */
class PhoneWakeService : Service() {
    private val notifications: ChatNotifications by inject()
    private val controller: PhoneWakeController by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, PhoneWakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        try {
            ServiceCompat.startForeground(
                this,
                ChatNotifications.WAKE_ID,
                notifications.wakeListening(stop),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // Android 14+ refuses a microphone service without the permission, or from the background.
            Log.w("PhoneWakeService", "Couldn't start listening for the wake word", e)
            controller.refused()
            stopSelf()
            return START_NOT_STICKY
        }
        controller.serviceStarted()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.serviceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "dev.hermeskotlin.wake.STOP"
    }
}
