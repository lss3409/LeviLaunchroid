package org.levimc.launcher.core.minecraft

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.levimc.launcher.core.crash.CrashReporter
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager

class LauncherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        FeatureSettings.init(applicationContext)
        LaunchTrace.init(applicationContext)
        val processName = Application.getProcessName()
        if (processName.endsWith(":crash")) return

        CrashReporter.init(this)
        LogcatOverlayManager.init(this)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)

        // v412：前后台检测——静默烘焙只在启动器前台运行
        // （后台/游戏运行时暂停：不抢 IO、不耗电）
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                if (started++ == 0) {
                    org.levimc.launcher.core.content.worldmap.SilentBakeManager.get().resume()
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                if (--started == 0) {
                    org.levimc.launcher.core.content.worldmap.SilentBakeManager.get().pause()
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set
    }
}
