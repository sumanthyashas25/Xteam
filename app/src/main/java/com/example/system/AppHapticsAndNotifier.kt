package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object AppHapticsAndNotifier {

    const val NOTIFICATION_CHANNEL_ID = "xmax_workspace_alerts"
    private const val PREFS_NAME = "xmax_workspace_prefs"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_TIMER_PREFIX = "work_timer_secs_"

    fun isSoundEnabled(context: Context): Boolean {
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SOUND_ENABLED, true)
        }.getOrDefault(true)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SOUND_ENABLED, enabled)
                .apply()
        }
    }

    fun loadSavedTimers(context: Context): Map<String, Long> {
        return runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.all.entries.mapNotNull { (k, v) ->
                if (k.startsWith(KEY_TIMER_PREFIX) && v is Long && v > 0L) {
                    k.removePrefix(KEY_TIMER_PREFIX) to v
                } else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun saveTimerSeconds(context: Context, deliverableId: String, seconds: Long) {
        if (deliverableId.isBlank()) return
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    if (seconds <= 0L) remove("$KEY_TIMER_PREFIX$deliverableId")
                    else putLong("$KEY_TIMER_PREFIX$deliverableId", seconds)
                }
                .apply()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveVibrator(context: Context): Vibrator? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    fun triggerTickHaptic(context: Context) {
        runCatching {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(18L, 120))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(18L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerCompletionVibration(context: Context) {
        runCatching {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 45L, 40L, 95L),
                        intArrayOf(0, 190, 0, 255),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0L, 45L, 40L, 95L), -1)
            }
        }
    }

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "X.MAX Workspace Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time notifications for chats, project updates, approvals, and payments"
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 60L, 50L, 90L)
                setSound(soundUri, attrs)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun playNotificationSound(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            ringtone?.play()
        }
    }

    @SuppressLint("MissingPermission")
    fun postSystemNotification(
        context: Context,
        title: String,
        body: String,
        notifId: Int = (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
        soundEnabled: Boolean = true
    ) {
        runCatching {
            val appCtx = context.applicationContext
            ensureNotificationChannel(appCtx)
            if (soundEnabled) {
                playNotificationSound(appCtx)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    appCtx,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val launchIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                appCtx,
                notifId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(appCtx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_xteam)
                .setColor(android.graphics.Color.parseColor("#111318"))
                .setContentTitle(title.uppercase())
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (soundEnabled) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                if (uri != null) builder.setSound(uri)
            } else {
                builder.setSilent(true)
            }

            NotificationManagerCompat.from(appCtx).notify(notifId, builder.build())
        }
    }
}
