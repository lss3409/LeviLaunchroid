package org.levimc.launcher.core.minecraft

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import org.levimc.launcher.ui.activities.MainActivity

object MinecraftReturnCoordinator {
    private const val LAUNCHER_RETURN_REQUEST_CODE = 0x1E71
    private const val LAUNCHER_RETURN_FALLBACK_DELAY_MS = 1200L

    fun scheduleLauncherReturnFallback(context: Context) {
        val appContext = context.applicationContext
        val pendingIntent = launcherReturnPendingIntent(appContext)
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + LAUNCHER_RETURN_FALLBACK_DELAY_MS

        try {
            alarmManager.cancel(pendingIntent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        } catch (_: Throwable) {
            try {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun cancelLauncherReturnFallback(context: Context) {
        val appContext = context.applicationContext
        try {
            appContext.getSystemService(AlarmManager::class.java)
                ?.cancel(launcherReturnPendingIntent(appContext))
        } catch (_: Throwable) {
        }
    }

    private fun launcherReturnPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        return PendingIntent.getActivity(
            context,
            LAUNCHER_RETURN_REQUEST_CODE,
            intent,
            flags
        )
    }
}
